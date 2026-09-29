"""Панели дашборда спрашивают метки, которые движок действительно выставляет.

Проверка имён метрик (`tools/check-metrics.py`) не видит меток: `horizon_analytics_stage_duration_
seconds{stage="total"}` — существующая метрика с несуществующим значением метки, и запрос по ней
рисует пустой график, ничем не отличимый от «ничего не происходило». Самая заметная панель
дашборда — «Полный цикл, p95» — спрашивала именно её, а конвейер размечает только свои шаги.

Здесь сверяются значения меток, которые панели требуют, с тем, что попадает в реестр после прогона.
Реестр берётся настоящий: подменять его значило бы проверять свою же подмену.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

import pytest

from horizon_analytics.observability import REGISTRY, observe_pipeline

REPOSITORY = Path(__file__).resolve().parents[4]
DASHBOARDS = REPOSITORY / "deploy" / "compose" / "observability" / "grafana" / "dashboards"

#: Шаги, которые размечает конвейер (`watch.record`). Список здесь для читаемости отказа: если
#: конвейер переименует шаг, тест назовёт разницу, а не просто упадёт на «total».
PIPELINE_STAGES = ("extracting", "termhood", "embedding", "clustering", "scoring", "narrating")

#: `{stage="total"}` и `{stage!="total"}` в запросах панелей.
STAGE_LITERAL = re.compile(r'stage\s*[!=]=\s*\\?"([a-z_]+)\\?"')


@pytest.fixture(scope="module")
def observed_stages() -> set[str]:
    observe_pipeline(dict.fromkeys(PIPELINE_STAGES, 100.0), {}, 0.5)
    stages: set[str] = set()
    for metric in REGISTRY.collect():
        if metric.name != "horizon_analytics_stage_duration_seconds":
            continue
        for sample in metric.samples:
            stage = sample.labels.get("stage")
            if stage is not None:
                stages.add(stage)
    return stages


def test_the_pipeline_publishes_a_total_stage(observed_stages: set[str]) -> None:
    """`total` — измеренная длительность прогона, а не сумма шагов.

    Сумма покрывает ровно то, что размечено: на настоящем корпусе она давала 108 с при 207 с
    прогона, потому что стадия термхуда не была размечена вовсе.
    """
    assert "total" in observed_stages
    assert set(PIPELINE_STAGES) <= observed_stages


def test_every_stage_a_dashboard_asks_for_is_published(observed_stages: set[str]) -> None:
    assert DASHBOARDS.is_dir(), f"каталог дашбордов не найден: {DASHBOARDS}"

    asked: set[str] = set()
    for dashboard in sorted(DASHBOARDS.glob("*.json")):
        # Разбирается как JSON, а затем сериализуется обратно: так в выражения панелей попадают все
        # запросы, включая вложенные в шаблонные переменные.
        asked |= set(
            STAGE_LITERAL.findall(json.dumps(json.loads(dashboard.read_text(encoding="utf-8"))))
        )

    assert asked, "ни одна панель не фильтрует по стадии — проверка сверяла бы пустоту"
    assert sorted(asked - observed_stages) == []
