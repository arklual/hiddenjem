#!/usr/bin/env python3
"""Существуют ли цели перекрёстного словаря в предметном словаре источника.

Зачем
─────
`direction_lexicon.txt` — crosswalk: слева формулировка аналитика, справа метка предметного словаря
корпуса. Правая часть обязана быть настоящей меткой источника, а не тем, как её назвал бы человек.
Цель, которой у источника нет, молчит: направление просто перестаёт что-либо забирать по ней, и
отличить «сузили правильно» от «сузили до пустоты» по зелёным тестам невозможно.

Так и вышло. Разбор [65] заменил шесть широких целей на узкие, и четыре из новых оказались
выдуманными: `financial technology` и `technology economics` в OpenAlex не существуют вовсе, а
`analytical chemistry` совпадает только с концептом `Analytical Chemistry (journal)` — то есть
забирает всё, что напечатано в этом журнале. Проверка написана после того, как это нашлось руками.

Почему не в гейте
─────────────────
Гейт обязан быть офлайновым и детерминированным (ADR-0015): те же входные данные — тот же ответ,
без сети. Этой проверке нужен внешний справочник, поэтому она запускается отдельно —
`make lexicon-targets` — при правке словаря и в обслуживании, а не на каждый коммит.
Адрес для «вежливого пула» берётся из `HORIZON_CONNECTOR_CONTACT_EMAIL`; с умолчанием-заглушкой
источник рано или поздно ответит 429. Проверка тогда говорит «справочник недоступен» — и
называет и причину, и следующий шаг: отличать «спросить не удалось» от «спросили и получили
нет» здесь важнее всего, а диагноз этого отказа добывался сутки и не должен добываться снова.

Что считается совпадением
─────────────────────────
Совпадением считается концепт, чьё имя после того же стемминга, что и в конвейере, содержит цель
как непрерывную фразу — ровно правило `label_matches`. Поэтому цель `cryptography` признаётся
существующей через `Quantum cryptography`, а `financial technology` не признаётся ничем.

Выход: 0 — все цели найдены; 1 — есть цели без единого концепта; 2 — справочник недоступен.
"""

from __future__ import annotations

import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable, Iterable
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ANALYTICS = ROOT / "services" / "analytics-service"
LEXICON = ANALYTICS / "src" / "horizon_analytics" / "domain" / "resources" / "direction_lexicon.txt"
API = "https://api.openalex.org/concepts"

#: Тот же адрес, которым представляется коннектор, и по той же причине.
#:
#: OpenAlex пускает представившихся в «вежливый пул» и режет остальных. Заглушка на example.org
#: работает ровно до того момента, когда начинает не работать: ответы приходят, потом через часы
#: приходит 429. Эта проверка на нём и застряла — прогнать её целиком за весь сеанс не удалось ни
#: разу. Берём переменную продукта, чтобы инструмент и коннектор врали или не врали одинаково.
MAILTO = os.environ.get("HORIZON_CONNECTOR_CONTACT_EMAIL", "horizon@example.org")

#: Цели, которых у OpenAlex нет и быть не должно — коды других источников.
#:
#: Это не исключение из правила, а другой словарь: arXiv-категории и классы CPC живут не здесь.
#: Признак структурный, а не список знакомых префиксов. Список был первым и пропустил `q-fin.TR`:
#: перечисление, составленное по тому, что попалось на глаза, выдаёт себя за правило и молчит на
#: первом же незнакомом архиве. Код узнаётся по форме — «архив.подкласс» одним словом с точкой либо
#: единственное исключение `quant-ph`. Имя концепта OpenAlex так выглядеть не может: это английские
#: слова, а не идентификатор.
SOURCE_CODE = re.compile(r"^(?:quant-ph|[a-z][a-z-]*\.[A-Za-z][A-Za-z-]*)$")

sys.path.insert(0, str(ANALYTICS / "src"))


def targets() -> set[str]:
    """Все правые части словаря, кроме кодов чужих источников."""
    found: set[str] = set()
    for line in LEXICON.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        for target in line.split("=", 1)[1].split("|"):
            # `~` — метка только для отбора (разбор 103); сама метка от этого не меняется.
            target = target.strip().removeprefix("~").strip()
            if target and not SOURCE_CODE.match(target):
                found.add(target)
    return found


def concepts_for(query: str) -> list[str]:
    """Имена концептов, найденных по запросу. 429 — не отказ, а просьба подождать.

    OpenAlex держит «вежливый пул» с ограничением по частоте, и словарь у нас в полторы сотни
    целей: без отступления проверка падает на первой же и сообщает о недоступности справочника
    там, где справочник доступен.
    """
    url = f"{API}?filter=display_name.search:{urllib.parse.quote(query)}&per-page=25&mailto={MAILTO}"
    for pause in (0, 5, 15, 45):
        if pause:
            time.sleep(pause)
        try:
            with urllib.request.urlopen(url, timeout=30) as response:  # noqa: S310 — фиксированный хост
                return [item["display_name"] for item in json.load(response)["results"]]
        except urllib.error.HTTPError as error:
            if error.code != 429:
                raise
    raise TimeoutError("OpenAlex отвечает 429 после четырёх попыток")


def dead_targets(
    wanted: Iterable[str], lookup: Callable[[str], list[str]]
) -> tuple[list[str], int]:
    """Цели, которым не нашлось ни одного концепта, и сколько целей проверено.

    Справочник передаётся, а не берётся изнутри: без этого у проверки нет ни одного случая,
    который можно прогнать без сети, — а именно правило совпадения здесь и стоит проверять.
    """
    from horizon_analytics.domain.direction_lexicon import label_matches
    from horizon_analytics.domain.extraction.normalization import normalize_tokens, tokenize

    def key(text: str) -> str:
        return normalize_tokens(token.normal for token in tokenize(text))

    dead: list[str] = []
    checked = 0
    for target in sorted(wanted):
        names = lookup(target)
        checked += 1
        if not any(label_matches(key(name), key(target)) for name in names):
            dead.append(target)
    return dead, checked


def main() -> int:
    def polite(target: str) -> list[str]:
        names = concepts_for(target)
        time.sleep(0.4)
        return names

    try:
        dead, checked = dead_targets(targets(), polite)
    except (urllib.error.URLError, TimeoutError) as error:
        print(f"  ✗ справочник недоступен: {error}")
        # Отличать «спросить не удалось» от «спросили и получили нет» — половина пользы этой
        # проверки. Вторая половина — сказать, что делать: причина почти всегда одна и известна.
        print(
            f"    Частая причина — адрес {MAILTO}: OpenAlex пускает представившихся в"
            " «вежливый пул» и режет остальных. Задайте HORIZON_CONNECTOR_CONTACT_EMAIL"
            " настоящим ящиком; уже включённое ограничение снимается не сразу — окно порядка суток."
        )
        return 2

    if dead:
        print(f"  ✗ целей без единого концепта источника: {len(dead)} из {checked}")
        for target in dead:
            print(f"      {target}")
        return 1
    print(f"  ✓ цели словаря существуют у источника: проверено {checked}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
