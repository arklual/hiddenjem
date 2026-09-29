r"""Обучение модели внешних признаков: ``python -m horizon_analytics.signals.train``.

    python -m horizon_analytics.signals.collect --terms terms.json --out signals.jsonl
    python -m horizon_analytics.signals.train \\
        --features signals.jsonl --labels labels.json --out signals-model.json

Вход:

* ``--features`` — JSONL сборщика признаков (``signals.collect``): по строке на термин с разобранными
  ответами источников. Вектор строится той же функцией, что при применении модели
  (:func:`horizon_analytics.signals.features.external_features`), поэтому обучение и работа движка
  не могут разойтись в составе и смысле признаков. Строка может нести и корпусные признаки —
  словарь ``corpus_features`` с именами ``corpus.*``.
* ``--labels`` — метки: JSON-объект ``{"термин": 1 | 0}``, JSON-список ``[{"term": …, "label": …}]``
  или CSV с колонками ``term`` и ``label``. ``1`` — слабый сигнал, ``0`` — зрелая технология,
  стандарт, хайп или шум. Метка может лежать и в самой строке признаков (поле ``label``).

Выход — артефакт ``horizon.signals-model/1``: признаки в порядке
:data:`~horizon_analytics.domain.signal_scoring.FEATURES`, преобразование, центр и масштаб каждого,
веса, смещение, версия, дата обучения и ROC-AUC на отложенной части.

Правила, которые делают артефакт честным:

* **Отложенная часть не видит обучения.** Центр и масштаб признаков, заполнение пропусков и веса
  считаются только на обучающей части; поставляется модель, обученная без отложенной части, —
  ровно та, чья метрика записана в артефакт.
* **Метрика считается кодом продукта.** Отложенные примеры оцениваются загруженным артефактом
  (:class:`~horizon_analytics.domain.signal_scoring.SignalsModel`), а не матрицей обучения: если
  запись артефакта и применение разойдутся, метрика это покажет.
* **Пропуск — не ноль.** Отсутствующий признак после стандартизации равен нулю, то есть центру
  обучающей части, — так же, как движок трактует пропуск при применении.
* **Признак без разброса не получает веса.** Колонка, которой нет в данных или у которой одно
  значение, записывается с нулевым весом.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import sys
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Any, Final

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import roc_auc_score
from sklearn.model_selection import GroupShuffleSplit, StratifiedShuffleSplit

from horizon_analytics.domain.signal_scoring import (
    CORPUS_FEATURES,
    FEATURES,
    MODEL_SCHEMA,
    SignalsModel,
)
from horizon_analytics.signals.features import external_features
from horizon_analytics.signals.models import SourceResult, TermSignals

__all__ = ["TrainingRow", "load_labels", "load_rows", "main", "train"]

#: Счётчики сжимаются логарифмом: сотня и десять тысяч работ различаются порядком, а не в сто раз.
#: Доли и флаги остаются как есть.
LOG_FEATURES: Final[frozenset[str]] = frozenset(
    {
        "corpus.documents",
        "corpus.documents_last_year",
        "openalex.works_window_total",
        "openalex.works_last_year",
        "openalex.distinct_institutions",
        "hackernews.mentions_window_total",
        "hackernews.mentions_last_year",
        "wikipedia.age_days",
        "wikipedia.size_bytes",
        "wikipedia.pageviews_12m",
    }
)


@dataclass(frozen=True, slots=True)
class TrainingRow:
    """Один пример: термин, его область, признаки (без пропусков-нулей) и метка."""

    term: str
    area: str | None
    features: Mapping[str, float]
    label: int


def _truthy(value: object) -> int:
    """Метка из JSON или CSV: 1/0, true/false, «да»/«нет»."""
    text = str(value).strip().lower()
    if text in {"1", "true", "yes", "да", "weak", "weak_signal"}:
        return 1
    if text in {"0", "false", "no", "нет", "not", "mature", "hype", "standard", "noise"}:
        return 0
    raise ValueError(f"метка {value!r} не распознана: ожидается 1/0, true/false или да/нет")


def load_labels(path: Path) -> dict[str, int]:
    """Метки по термину из JSON-объекта, JSON-списка или CSV (``term``, ``label``)."""
    text = path.read_text(encoding="utf-8")
    if path.suffix.lower() == ".csv":
        reader = csv.DictReader(text.splitlines())
        return {row["term"].strip(): _truthy(row["label"]) for row in reader if row.get("term")}
    payload = json.loads(text)
    if isinstance(payload, Mapping):
        return {str(term).strip(): _truthy(label) for term, label in payload.items()}
    return {str(item["term"]).strip(): _truthy(item["label"]) for item in payload}


def _signals(record: Mapping[str, Any]) -> TermSignals:
    """Строка JSONL сборщика → :class:`TermSignals`."""
    return TermSignals(
        term=str(record["term"]),
        area=record.get("area"),
        collected_on=date.fromisoformat(str(record["collected_on"])),
        sources={
            name: SourceResult.from_json(name, payload)
            for name, payload in (record.get("sources") or {}).items()
        },
    )


def load_rows(features_path: Path, labels: Mapping[str, int] | None = None) -> list[TrainingRow]:
    """Прочитать признаки сборщика и сопоставить им метки; термины без метки пропускаются."""
    rows: list[TrainingRow] = []
    with features_path.open(encoding="utf-8") as handle:
        for line in handle:
            if not line.strip():
                continue
            record = json.loads(line)
            term = str(record["term"]).strip()
            label = (labels or {}).get(term)
            if label is None and "label" in record:
                label = _truthy(record["label"])
            if label is None:
                continue
            features = dict(external_features(_signals(record)))
            for name, value in (record.get("corpus_features") or {}).items():
                if name in CORPUS_FEATURES and value is not None and math.isfinite(float(value)):
                    features[name] = float(value)
            rows.append(
                TrainingRow(term=term, area=record.get("area"), features=features, label=int(label))
            )
    return rows


def _transform(name: str) -> str:
    return "log1p" if name in LOG_FEATURES else "identity"


def _apply(name: str, value: float) -> float:
    return math.log1p(max(0.0, value)) if _transform(name) == "log1p" else value


def _split(
    rows: Sequence[TrainingRow], holdout: float, seed: int, group_by_area: bool
) -> tuple[list[int], list[int]]:
    """Индексы обучающей и отложенной частей: стратифицированно по метке или группами по области."""
    labels = np.array([row.label for row in rows])
    indices = np.arange(len(rows))
    if group_by_area:
        groups = np.array([row.area or row.term for row in rows])
        splitter = GroupShuffleSplit(n_splits=1, test_size=holdout, random_state=seed)
        train_idx, test_idx = next(splitter.split(indices, labels, groups))
    else:
        splitter = StratifiedShuffleSplit(n_splits=1, test_size=holdout, random_state=seed)
        train_idx, test_idx = next(splitter.split(indices, labels))
    return sorted(int(i) for i in train_idx), sorted(int(i) for i in test_idx)


def train(
    rows: Sequence[TrainingRow],
    *,
    holdout: float = 0.25,
    seed: int = 20260920,
    regularization: float = 1.0,
    group_by_area: bool = False,
    version: str | None = None,
    today: date | None = None,
) -> dict[str, Any]:
    """Обучить модель и вернуть артефакт ``horizon.signals-model/1``."""
    if len({row.label for row in rows}) < 2:
        raise ValueError("для обучения нужны примеры обоих классов: слабые сигналы и не слабые")
    train_idx, test_idx = _split(rows, holdout, seed, group_by_area)
    train_rows = [rows[i] for i in train_idx]
    test_rows = [rows[i] for i in test_idx]
    if len({row.label for row in test_rows}) < 2:
        raise ValueError(
            "в отложенной части один класс — ROC-AUC не определён; измените --seed или --holdout"
        )

    # Центр и масштаб каждого признака — только по обучающей части и только по наблюдённым значениям.
    columns: list[dict[str, Any]] = []
    for name in FEATURES:
        values = [_apply(name, row.features[name]) for row in train_rows if name in row.features]
        center = float(np.mean(values)) if values else 0.0
        scale = float(np.std(values)) if len(values) > 1 else 0.0
        varies = scale > 1e-12
        columns.append(
            {
                "name": name,
                "transform": _transform(name) if varies else "identity",
                "center": center if varies else 0.0,
                "scale": scale if varies else 1.0,
                "usable": varies,
            }
        )

    def matrix(part: Sequence[TrainingRow]) -> np.ndarray:
        out = np.zeros((len(part), len(columns)))
        for i, row in enumerate(part):
            for j, column in enumerate(columns):
                if column["usable"] and column["name"] in row.features:
                    value = _apply(column["name"], row.features[column["name"]])
                    out[i, j] = (value - column["center"]) / column["scale"]
        return out

    usable = [j for j, column in enumerate(columns) if column["usable"]]
    if not usable:
        raise ValueError("ни у одного признака нет разброса в обучающей части — учить нечему")
    x_train = matrix(train_rows)[:, usable]
    y_train = np.array([row.label for row in train_rows])
    model = LogisticRegression(C=regularization, class_weight="balanced", max_iter=5000)
    model.fit(x_train, y_train)
    weights = dict(zip(usable, model.coef_[0], strict=True))

    today = today or date.today()
    artifact: dict[str, Any] = {
        "schema": MODEL_SCHEMA,
        "version": version or f"{today.isoformat()}.1",
        "trained_on": today.isoformat(),
        "link": "logistic",
        "bias": float(model.intercept_[0]),
        "features": [
            {
                "name": column["name"],
                "weight": float(weights.get(j, 0.0)),
                "transform": column["transform"],
                "center": column["center"],
                "scale": column["scale"],
            }
            for j, column in enumerate(columns)
        ],
        "holdout": {"metric": "roc_auc", "value": 0.0, "size": len(test_rows)},
        "training": {
            "rows": len(train_rows),
            "positives": int(y_train.sum()),
            "negatives": int(len(y_train) - y_train.sum()),
            "split": "group_by_area" if group_by_area else "stratified",
            "holdout_share": holdout,
            "seed": seed,
            "regularization": regularization,
        },
    }

    # Метрика — тем же кодом, которым движок читает и применяет артефакт.
    loaded = SignalsModel.from_json(artifact, source="обучение")
    scores = [loaded.score(row.features)[0] for row in test_rows]
    auc = float(roc_auc_score([row.label for row in test_rows], scores))
    artifact["holdout"]["value"] = round(auc, 4)
    return artifact


def _parse_args(argv: Sequence[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="python -m horizon_analytics.signals.train",
        description="Обучить модель внешних признаков и записать артефакт horizon.signals-model/1.",
    )
    parser.add_argument(
        "--features", type=Path, required=True, help="JSONL сборщика signals.collect"
    )
    parser.add_argument(
        "--labels", type=Path, default=None, help="метки: JSON или CSV (term, label)"
    )
    parser.add_argument("--out", type=Path, required=True, help="куда записать артефакт")
    parser.add_argument("--holdout", type=float, default=0.25, help="доля отложенной части (0.25)")
    parser.add_argument("--seed", type=int, default=20260920, help="зерно разбиения")
    parser.add_argument(
        "--C", dest="regularization", type=float, default=1.0, help="обратная сила L2-регуляризации"
    )
    parser.add_argument(
        "--group-by-area",
        action="store_true",
        help="откладывать области целиком, а не отдельные термины",
    )
    parser.add_argument(
        "--version", default=None, help="версия артефакта (по умолчанию ГГГГ-ММ-ДД.1)"
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    """Точка входа командной строки: обучить, записать артефакт, проверить, что движок его читает."""
    args = _parse_args(argv)
    labels = load_labels(args.labels) if args.labels else None
    rows = load_rows(args.features, labels)
    if not rows:
        print("нет примеров с метками: проверьте --labels и совпадение терминов", file=sys.stderr)
        return 1
    artifact = train(
        rows,
        holdout=args.holdout,
        seed=args.seed,
        regularization=args.regularization,
        group_by_area=args.group_by_area,
        version=args.version,
    )
    args.out.write_text(json.dumps(artifact, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    SignalsModel.load(args.out)
    positives = sum(row.label for row in rows)
    print(
        f"примеров: {len(rows)} (слабых сигналов {positives}, прочих {len(rows) - positives}); "
        f"ROC-AUC на отложенной части: {artifact['holdout']['value']} "
        f"на {artifact['holdout']['size']} примерах"
    )
    ranked = sorted(artifact["features"], key=lambda item: -abs(item["weight"]))[:5]
    print(
        "сильнейшие признаки: "
        + ", ".join(f"{item['name']} {item['weight']:+.3f}" for item in ranked)
    )
    print(f"артефакт: {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
