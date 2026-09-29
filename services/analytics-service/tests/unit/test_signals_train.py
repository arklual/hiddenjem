"""Обучение модели внешних признаков: артефакт читается движком, метрика считается его кодом."""

from __future__ import annotations

import json
import random
from datetime import date
from pathlib import Path

import pytest

from horizon_analytics.domain.signal_scoring import FEATURES, SignalsModel
from horizon_analytics.signals.models import SourceResult, TermSignals
from horizon_analytics.signals.train import load_labels, load_rows, main, train

COLLECTED = date(2026, 9, 20)


def _record(
    term: str, *, weak: bool, rng: random.Random, openalex: bool = True
) -> dict[str, object]:
    """Строка JSONL в том виде, в каком её пишет ``signals.collect``."""
    works = rng.randint(5, 150) if weak else rng.randint(3_000, 60_000)
    mentions = rng.randint(0, 20) if weak else rng.randint(200, 5_000)
    sources = {
        "hackernews": SourceResult(
            source="hackernews",
            ok=True,
            collected_on=COLLECTED,
            version=1,
            data={
                "mentions_window_total": mentions,
                "recent_two_year_share": 0.7 if weak else 0.3,
                "mentions_by_year": {"2025": mentions // 3, "2026": mentions // 2},
            },
        ),
        "wikipedia": SourceResult(
            source="wikipedia",
            ok=True,
            collected_on=COLLECTED,
            version=1,
            data={
                "exists": 0.0 if weak else 1.0,
                **({} if weak else {"age_days": 5_000, "size_bytes": 40_000}),
            },
        ),
    }
    if openalex:
        sources["openalex"] = SourceResult(
            source="openalex",
            ok=True,
            collected_on=COLLECTED,
            version=1,
            data={
                "works_window_total": works,
                "recent_two_year_share": 0.8 if weak else 0.35,
                "distinct_institutions": rng.randint(1, 15) if weak else rng.randint(300, 2_000),
                "top_institution_share": 0.4 if weak else 0.02,
                "works_by_year": {"2025": works // 3, "2026": works // 2},
            },
        )
    else:
        sources["openalex"] = SourceResult(
            source="openalex", ok=False, collected_on=COLLECTED, version=1, error="429"
        )
    return TermSignals(term=term, area="Тест", collected_on=COLLECTED, sources=sources).to_json()


def _write(path: Path, count: int = 60) -> dict[str, int]:
    rng = random.Random(7)
    labels: dict[str, int] = {}
    lines = []
    for index in range(count):
        weak = index % 2 == 0
        term = f"term {index}"
        labels[term] = int(weak)
        lines.append(
            json.dumps(
                _record(term, weak=weak, rng=rng, openalex=index % 7 != 0), ensure_ascii=False
            )
        )
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return labels


def test_the_artifact_loads_in_the_engine_and_separates_the_classes(tmp_path: Path) -> None:
    features = tmp_path / "signals.jsonl"
    labels = _write(features)

    artifact = train(load_rows(features, labels), today=date(2026, 9, 29))

    model = SignalsModel.from_json(artifact, source="тест")
    assert tuple(item.name for item in model.weights) == FEATURES
    assert artifact["holdout"]["metric"] == "roc_auc"
    assert artifact["holdout"]["value"] > 0.9
    assert artifact["holdout"]["size"] == 15
    assert artifact["version"] == "2026-09-29.1"
    # Корпусных признаков в строках сборщика нет — они записаны без веса, а не выдуманы.
    corpus = [item for item in artifact["features"] if item["name"].startswith("corpus.")]
    assert all(item["weight"] == 0.0 and item["scale"] == 1.0 for item in corpus)
    # Массовость по OpenAlex снижает балл: вес числа организаций отрицательный.
    institutions = next(
        item for item in artifact["features"] if item["name"] == "openalex.distinct_institutions"
    )
    assert institutions["transform"] == "log1p" and institutions["weight"] < 0


def test_a_silent_source_is_a_missing_feature_not_a_zero(tmp_path: Path) -> None:
    features = tmp_path / "signals.jsonl"
    _write(features, count=14)

    rows = load_rows(features, {"term 0": 1})

    assert len(rows) == 1
    assert "openalex.works_window_total" not in rows[0].features
    assert rows[0].features["hackernews.mentions_last_year"] >= 0


def test_labels_are_read_from_json_and_csv(tmp_path: Path) -> None:
    as_json = tmp_path / "labels.json"
    as_json.write_text(
        json.dumps([{"term": "a", "label": "да"}, {"term": "b", "label": 0}]), encoding="utf-8"
    )
    as_csv = tmp_path / "labels.csv"
    as_csv.write_text("term,label\na,1\nb,false\n", encoding="utf-8")

    assert load_labels(as_json) == {"a": 1, "b": 0}
    assert load_labels(as_csv) == {"a": 1, "b": 0}


def test_one_class_is_refused(tmp_path: Path) -> None:
    features = tmp_path / "signals.jsonl"
    _write(features, count=10)

    with pytest.raises(ValueError, match="обоих классов"):
        train(load_rows(features, {f"term {i}": 1 for i in range(10)}))


def test_the_command_writes_an_artifact_the_engine_accepts(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    features = tmp_path / "signals.jsonl"
    labels = tmp_path / "labels.json"
    labels.write_text(json.dumps(_write(features)), encoding="utf-8")
    out = tmp_path / "model.json"

    code = main(
        [
            "--features",
            str(features),
            "--labels",
            str(labels),
            "--out",
            str(out),
            "--version",
            "t.1",
        ]
    )

    assert code == 0
    assert SignalsModel.load(out).version == "t.1"
    assert "ROC-AUC на отложенной части" in capsys.readouterr().out
