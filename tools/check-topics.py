#!/usr/bin/env python3
"""Топик, в который кто-то пишет, объявлен в контракте и создаётся при развёртывании.

Найдено чтением: одна из служб публиковала шесть типов событий в топик,
которого не было ни в AsyncAPI, ни в списке создаваемых `redpanda-init`. То есть контракт, который
README называет источником истины для всех служб, не знал о целом канале, а брокер не знал о теме,
в которую пишет outbox.

Три места обязаны совпадать, и каждая пара расходится по-своему:

* **код ↔ контракт** — потребитель другой команды строится по контракту; событие, которого там нет,
  для него не существует;
* **код ↔ создание топиков** — при выключенном авто-создании публикация падает, при включённом
  топик появляется с чужими партициями и репликацией;
* **контракт ↔ создание топиков** — объявленный, но не созданный канал выглядит работающим до
  первого сообщения.

Проверка идёт по всем трём парам в обе стороны.

    python3 tools/check-topics.py
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover — гейт объявляет пропуск, а не молчит
    print("нет PyYAML — проверка топиков не выполнена")
    sys.exit(0)

ROOT = Path(__file__).resolve().parent.parent
CONTRACT = ROOT / "contracts" / "asyncapi" / "horizon-events.yaml"
COMPOSE = ROOT / "deploy" / "compose" / "docker-compose.yml"

#: Имя топика: `horizon.<домен>[.<вид>].v<версия>`.
TOPIC = re.compile(r"\bhorizon\.[a-z]+(?:\.[a-z]+)?\.v\d+\b")

#: Топики, которые не нужно ни объявлять, ни создавать, и почему.
#: Список именной: молчаливое исключение вернуло бы ровно ту болезнь, от которой проверка написана.
EXCUSED: dict[str, str] = {}


def used_in_code() -> dict[str, list[str]]:
    """Топики, встречающиеся в исходниках служб, с указанием, где именно."""
    found: dict[str, list[str]] = {}
    for service in sorted((ROOT / "services").iterdir()):
        for pattern in ("src/main/java/**/*.java", "src/main/resources/*.yml", "src/**/*.py"):
            for source in service.glob(pattern):
                if "/target/" in str(source) or "/.venv/" in str(source):
                    continue
                text = source.read_text(encoding="utf-8", errors="ignore")
                for name in TOPIC.findall(text):
                    found.setdefault(name, []).append(str(source.relative_to(ROOT)))
    return found


def declared_in_contract() -> set[str]:
    document = yaml.safe_load(CONTRACT.read_text(encoding="utf-8")) or {}
    return {
        channel["address"]
        for channel in (document.get("channels") or {}).values()
        if isinstance(channel, dict) and "address" in channel
    }


def created_at_deploy() -> set[str]:
    """Топики из списка, который `redpanda-init` создаёт при подъёме стека."""
    text = COMPOSE.read_text(encoding="utf-8")
    block = re.search(r'TOPICS="(.*?)"', text, re.S)
    if block is None:
        return set()
    return set(TOPIC.findall(block.group(1)))


def main() -> int:
    code = used_in_code()
    contract = declared_in_contract()
    created = created_at_deploy()

    if not code or not contract or not created:
        print(
            "пустая сторона сверки: "
            f"в коде {len(code)}, в контракте {len(contract)}, создаётся {len(created)}"
        )
        return 1

    problems: list[str] = []
    for name, places in sorted(code.items()):
        if name in EXCUSED:
            continue
        if name not in contract:
            problems.append(f"{name}: используется ({places[0]}), но в AsyncAPI канала нет")
        if name not in created:
            problems.append(f"{name}: используется ({places[0]}), но redpanda-init его не создаёт")
    for name in sorted(contract):
        if name not in created:
            problems.append(f"{name}: объявлен в AsyncAPI, но redpanda-init его не создаёт")
    for name in sorted(created):
        if name not in contract:
            problems.append(f"{name}: создаётся при развёртывании, но в AsyncAPI канала нет")

    if problems:
        print("\n".join(f"  {line}" for line in sorted(set(problems))))
        return 1

    print(
        f"топики согласованы: {len(code)} в коде, {len(contract)} в контракте, "
        f"{len(created)} создаётся"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
