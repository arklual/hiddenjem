#!/usr/bin/env python3
"""Метрика, на которую написан алерт, кем-то испускается.

Найдено чтением: одиннадцать доменных метрик перечислены в шапке `alerts.yml` как обязательство
служб, и правила на них уже написаны — а испускались из них **ноль**. `horizon_outbox_pending` в
коде против `horizon_outbox_pending_messages` в правиле; остальных не существовало вовсе.

Алерт на несуществующую метрику — это не «не сработает при аварии», это хуже: в PromQL нет данных →
нет срабатывания, поэтому панель зелёная и в момент аварии, и всегда. Наблюдаемость, которую нельзя
отличить от исправности, — не наблюдаемость.

Проверка сверяет три вещи:

* имена, объявленные в шапке `alerts.yml`, — с тем, что регистрирует код (JVM: `Counter/Timer/
  Gauge.builder("horizon.x.y")` и `meterRegistry.counter(…)`, где Micrometer превращает точки в
  подчёркивания и добавляет счётчику `_total`; Python: имена в `observability.py` уже прометеевские);
* метрики, по которым строят графики панели Grafana, — с тем же списком: панель молчит так же тихо,
  как правило, и «данных нет» выглядит на ней как «ничего не происходит»;
* правила записи (`horizon:foo:rate5m`), на которые ссылаются панели, — с объявленными в
  `alerts.yml`: дашборд ломается от переименования правила, а не от его отсутствия в коде.

Обратная сторона (метрика есть, алерта нет) намеренно не проверяется: не всякая величина обязана
иметь порог, и правило «на каждую метрику алерт» породило бы шум, ради тишины которого алерты потом
и отключают.

    python3 tools/check-metrics.py
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ALERTS = ROOT / "deploy" / "compose" / "observability" / "alerts.yml"
DASHBOARDS = ROOT / "deploy" / "compose" / "observability" / "grafana" / "dashboards"

#: Строка шапки вида `#    horizon_foo_total{tag}   counter, NFR-…` — объявление обязательства.
DECLARED = re.compile(r"^#\s+(horizon_[a-z_]+?)(?:_bucket)?(?:\{[^}]*\})?\s{2,}\S", re.M)

#: Как метрика регистрируется в JVM-коде.
JVM = re.compile(
    r'(?:Counter|Timer|Gauge|DistributionSummary)\s*\.\s*builder\(\s*"([a-z][a-z.]+)"'
    r'|\.\s*(?:counter|timer|gauge|summary)\(\s*"([a-z][a-z.]+)"'
)

#: Правило записи, объявленное в `alerts.yml`: `- record: horizon:http_errors:rate5m`.
RECORDED = re.compile(r"record:\s*(horizon:[a-z_0-9:]+)")

#: Имя метрики и имя правила записи в запросе панели Grafana.
PANEL_METRIC = re.compile(r"\b(horizon_[a-z_0-9]+)")
PANEL_RECORDING = re.compile(r"\b(horizon:[a-z_0-9:]+)")

#: Счётчику Micrometer добавляет суффикс `_total`, остальным — нет.
COUNTER = re.compile(r'(?:Counter\s*\.\s*builder|\.\s*counter)\(\s*"([a-z][a-z.]+)"')

#: Метрики, которые приходят не из нашего кода. Именно поимённо: молчаливое исключение вернуло бы
#: ровно ту болезнь, от которой проверка написана.
EXTERNAL: dict[str, str] = {}


def prometheus_names(dotted: str, counter: bool) -> set[str]:
    """`horizon.outbox.pending.messages` → `horizon_outbox_pending_messages` (+ `_total` у счётчика)."""
    base = dotted.replace(".", "_")
    return {base, f"{base}_total"} if counter else {base}


def emitted() -> set[str]:
    names: set[str] = set()
    for source in (ROOT / "services").rglob("*.java"):
        if "/target/" in str(source) or "/src/test/" in str(source):
            continue
        text = source.read_text(encoding="utf-8", errors="ignore")
        counters = set(COUNTER.findall(text))
        for builder, direct in JVM.findall(text):
            dotted = builder or direct
            if not dotted.startswith("horizon."):
                continue
            names |= prometheus_names(dotted, dotted in counters)
    python_source = ROOT / "services" / "analytics-service" / "src" / "horizon_analytics" / "observability.py"
    if python_source.exists():
        text = python_source.read_text(encoding="utf-8")
        for name in re.findall(r'"(horizon_[a-z_]+)"', text):
            names |= {name, f"{name}_total"}
    return names


def main() -> int:
    if not ALERTS.is_file():
        print(f"файл правил не найден: {ALERTS}")
        return 1

    declared = set(DECLARED.findall(ALERTS.read_text(encoding="utf-8")))
    if not declared:
        print("в шапке alerts.yml не найдено ни одного объявления — проверка сверяла бы пустоту")
        return 1

    produced = emitted()
    if not produced:
        print("в коде не найдено ни одной метрики — проверка сверяла бы пустоту")
        return 1

    problems: list[str] = []
    for name in sorted(declared):
        if name not in produced and name not in EXTERNAL:
            problems.append(f"alerts.yml объявляет {name}, но её никто не испускает")

    # Панель на несуществующую метрику ведёт себя так же тихо, как правило: рисуется пустой график
    # с подписью, и «данных нет» выглядит как «ничего не происходит».
    panels = 0
    recorded = set(RECORDED.findall(ALERTS.read_text(encoding="utf-8")))
    for dashboard in sorted(DASHBOARDS.glob("*.json")):
        text = dashboard.read_text(encoding="utf-8")
        for name in set(PANEL_METRIC.findall(text)):
            panels += 1
            base = re.sub(r"_(bucket|count|sum)$", "", name)
            if base not in produced and f"{base}_total" not in produced and base not in EXTERNAL:
                problems.append(f"{dashboard.name} строит график по {name}, но её никто не испускает")
        for name in set(PANEL_RECORDING.findall(text)):
            panels += 1
            if name not in recorded:
                problems.append(f"{dashboard.name} ссылается на правило {name}, которого нет в alerts.yml")

    if problems:
        print("\n".join(f"  {line}" for line in problems))
        print("\nАлерт и панель на несуществующую метрику молчат всегда — и при аварии тоже.")
        return 1

    print(
        f"метрики согласованы: {len(declared)} объявлено правилами, {panels} использований на панелях, "
        f"{len(EXTERNAL)} внешних"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
