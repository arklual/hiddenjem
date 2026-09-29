"""Прогон проверки на размеченном датасете: `python -m horizon_analytics.validation.run`.

Что делает и в каком порядке.

1. Читает размеченный датасет (положительный класс) и отрицательный контроль из репозитория.
2. Строит поисковую формулировку для каждой строки: локальной моделью, с сохранением результата
   в файл, чтобы прогоны были сравнимы между собой.
3. По каждой формулировке ходит в открытые источники — OpenAlex, arXiv, GitHub, Hacker News,
   Википедию — и собирает ряды по годам.
4. Считает индикаторы и выносит решение по абсолютным порогам (`classifier`).
5. Печатает Precision, Recall, F1, матрицу ошибок и **таблицу по каждой строке**: что решено, по
   каким числам и почему.

Пятый пункт — не оформление. ТЗ требует интерпретируемости: «система обязана показывать, по каким
именно признакам наблюдение отнесено к слабому сигналу». Метрика без этой таблицы проверяема
только на слово.

Сетевые ответы кэшируются на диск. Причина та же, что у сохранения формулировок: OpenAlex — живая
база, и два прогона в разные дни дают разные числа. Кэш делает отчёт воспроизводимым, а его
удаление — способ перемерить всё заново.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections.abc import Iterable, Iterator, Mapping, Sequence
from dataclasses import asdict, dataclass
from datetime import date
from pathlib import Path
from typing import Any

import httpx

from horizon_analytics.validation.classifier import (
    THRESHOLDS,
    Decision,
    Thresholds,
    classify,
    summarize,
)
from horizon_analytics.validation.dataset import (
    DatasetRow,
    NegativeRow,
    load_negatives,
    load_positives,
)
from horizon_analytics.validation.sources import (
    SourceEvidence,
    collect_evidence,
    collect_news,
    set_contact_email,
)

__all__ = ["main"]


@dataclass(frozen=True, slots=True)
class Case:
    """Один проверяемый случай: что искали, какая метка, что решили."""

    label: bool
    name: str
    area: str
    query: str
    query_origin: str
    kind: str
    decision: Decision


def _repository_root() -> Path:
    """Корень репозитория — ближайший каталог вверх, где лежит отрицательный контроль.

    Поиск, а не фиксированное число уровней: пакет запускается и из рабочего дерева, и из
    установленного в образ, и там глубина разная. Счётчик уровней в первом же прогоне в контейнере
    указал на `/repo/services` и уронил утилиту на отсутствующем файле.
    """
    marker = Path("fixtures") / "validation" / "negatives.json"
    for candidate in [Path.cwd(), *Path(__file__).resolve().parents]:
        if (candidate / marker).is_file():
            return candidate
    return Path.cwd()


def _load_cache(path: Path) -> dict[str, dict[str, Any]]:
    if not path.is_file():
        return {}
    try:
        cached: dict[str, dict[str, Any]] = json.loads(path.read_text(encoding="utf-8"))
    except ValueError:
        return {}
    return cached


def _evidence_from_cache(payload: dict[str, Any]) -> SourceEvidence:
    evidence = SourceEvidence(
        query=str(payload.get("query", "")),
        effective_query=str(payload.get("effective_query") or payload.get("query", "")),
    )
    evidence.works_by_year = {
        int(k): int(v) for k, v in (payload.get("works_by_year") or {}).items()
    }
    evidence.preprints = payload.get("preprints")
    evidence.preprint_first_year = payload.get("preprint_first_year")
    evidence.repositories = payload.get("repositories")
    evidence.repository_first_year = payload.get("repository_first_year")
    evidence.stories_by_year = {
        int(k): int(v) for k, v in (payload.get("stories_by_year") or {}).items()
    }
    evidence.wikipedia = {
        str(k): (int(v[0]), int(v[1])) for k, v in (payload.get("wikipedia") or {}).items()
    }
    evidence.unavailable = tuple(payload.get("unavailable") or ())
    return evidence


def _evidence_to_cache(evidence: SourceEvidence) -> dict[str, Any]:
    data = asdict(evidence)
    data["works_by_year"] = {str(k): v for k, v in evidence.works_by_year.items()}
    data["stories_by_year"] = {str(k): v for k, v in evidence.stories_by_year.items()}
    data["wikipedia"] = {k: list(v) for k, v in evidence.wikipedia.items()}
    data["unavailable"] = list(evidence.unavailable)
    return data


def _build_queries(
    rows: Sequence[DatasetRow], *, nlp_url: str, saved: dict[str, str]
) -> dict[str, str]:
    """Достроить недостающие поисковые формулировки локальной моделью.

    Батчами по десять: модель на процессоре отвечает медленно, и сотня отдельных запросов стоит
    сотню прогревов контекста. Уже сохранённые формулировки не пересчитываются — прогоны обязаны
    быть сравнимыми.
    """
    missing = [row for row in rows if row.name not in saved]
    if not missing:
        return saved
    print(f"  формулировки: {len(missing)} строк(и) без сохранённой фразы", file=sys.stderr)
    with httpx.Client(base_url=nlp_url.rstrip("/"), timeout=900.0) as client:
        for start in range(0, len(missing), 10):
            chunk = missing[start : start + 10]
            try:
                response = client.post(
                    "/search-phrase", json={"formulations": [row.name for row in chunk]}
                )
                response.raise_for_status()
                phrases = response.json().get("phrases") or []
            except Exception as error:
                print(f"  модель недоступна ({error}); формулировки не достроены", file=sys.stderr)
                return saved
            for row, phrase in zip(chunk, phrases, strict=False):
                text = str(phrase).strip()
                if text:
                    saved[row.name] = text
            print(
                f"  формулировки: {min(start + 10, len(missing))}/{len(missing)}", file=sys.stderr
            )
    return saved


def _collect(query: str, cache: dict[str, dict[str, Any]], *, refresh: bool) -> SourceEvidence:
    if not refresh and query in cache:
        return _evidence_from_cache(cache[query])
    evidence = collect_evidence(query)
    cache[query] = _evidence_to_cache(evidence)
    return evidence


def _markdown(
    cases: Sequence[Case],
    metrics: Mapping[str, float],
    thresholds: Thresholds,
    measured_on: date,
) -> str:
    lines: list[str] = []
    lines.append(
        "<!-- Сгенерировано `make validate-dataset`. Руками не править: правка разойдётся с замером -->"
    )
    lines.append(
        f"Замер: {measured_on.isoformat()}, живые открытые источники "
        "(OpenAlex, arXiv, GitHub, Hacker News, Википедия)."
    )
    lines.append("")
    lines.append("## Метрики")
    lines.append("")
    lines.append("| Метрика | Значение |")
    lines.append("| --- | --- |")
    lines.append(f"| Precision | **{metrics['precision']:.3f}** |")
    lines.append(f"| Recall | **{metrics['recall']:.3f}** |")
    lines.append(f"| F1 | **{metrics['f1']:.3f}** |")
    lines.append(f"| Accuracy | **{metrics['accuracy']:.3f}** |")
    lines.append("")
    lines.append("| | решено «слабый сигнал» | решено «не слабый» |")
    lines.append("| --- | --- | --- |")
    lines.append(
        f"| размечено «слабый сигнал» | {metrics['truePositives']} | {metrics['falseNegatives']} |"
    )
    lines.append(
        f"| отрицательный контроль | {metrics['falsePositives']} | {metrics['trueNegatives']} |"
    )
    lines.append("")
    lines.append("## Пороги решения")
    lines.append("")
    lines.append("| Порог | Значение |")
    lines.append("| --- | --- |")
    lines.append(
        f"| Научных работ за 3 года, выше которых тема — мейнстрим | {thresholds.mainstream_recent_works} |"
    )
    lines.append(
        f"| Возраст энциклопедической статьи, означающий зрелость | {thresholds.mainstream_wikipedia_age} лет |"
    )
    lines.append(f"| Её длина | {thresholds.mainstream_wikipedia_length} знаков |")
    lines.append(
        f"| Независимых видов источников для доказанности | {thresholds.minimum_source_kinds} |"
    )
    lines.append("")

    negatives = [case for case in cases if not case.label]
    if negatives:
        lines.append("## Отрицательный контроль по видам")
        lines.append("")
        lines.append("| Вид | Всего | Ошибочно принято за слабый сигнал |")
        lines.append("| --- | --- | --- |")
        for kind in sorted({case.kind for case in negatives}):
            group = [case for case in negatives if case.kind == kind]
            wrong = sum(1 for case in group if case.decision.is_weak_signal)
            lines.append(f"| {kind} | {len(group)} | **{wrong}** |")
        lines.append("")

    lines.append("## Ошибки")
    lines.append("")
    errors = [case for case in cases if case.label != case.decision.is_weak_signal]
    if not errors:
        lines.append("Ошибок нет.")
    else:
        lines.append("| Метка | Наблюдение | Искали | Почему решено так |")
        lines.append("| --- | --- | --- | --- |")
        for case in errors:
            mark = "слабый сигнал" if case.label else case.kind
            lines.append(
                f"| {mark} | {case.name[:70]} | `{case.query}` | {case.decision.explanation} |"
            )
    lines.append("")
    lines.append("## Все наблюдения")
    lines.append("")
    lines.append(
        "| # | Метка | Наблюдение | Искали | Решение | Работ всего | За 3 года | Первый след | Видов источников | Объяснение |"
    )
    lines.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    for index, case in enumerate(cases, 1):
        facts = case.decision.evidence
        mark = "слабый сигнал" if case.label else case.kind
        lines.append(
            f"| {index} | {mark} | {case.name[:70]} | `{facts.get('query')}` | {case.decision.verdict} | "
            f"{facts.get('worksTotal')} | {facts.get('worksRecent')} | {facts.get('firstYear') or '—'} | "
            f"{facts.get('sourceKinds')} | {case.decision.explanation} |"
        )
    lines.append("")
    return "\n".join(lines)


def _run(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Проверка методологии на размеченном датасете")
    root = _repository_root()
    parser.add_argument(
        "--dataset",
        type=Path,
        default=root / "100_слабых_технологических_сигналов_сентябрь_2026.xlsx",
        help="Книга Excel с размеченными слабыми сигналами",
    )
    parser.add_argument(
        "--negatives",
        type=Path,
        default=root / "fixtures" / "validation" / "negatives.json",
        help="Отрицательный контроль",
    )
    parser.add_argument(
        "--queries",
        type=Path,
        default=root / "fixtures" / "validation" / "queries.json",
        help="Сохранённые поисковые формулировки",
    )
    parser.add_argument(
        "--cache",
        type=Path,
        default=root / "fixtures" / "validation" / "evidence-cache.json",
        help="Кэш ответов открытых источников",
    )
    parser.add_argument("--nlp-url", default="http://127.0.0.1:18086", help="Адрес сервиса моделей")
    parser.add_argument("--email", default="ops@horizon.example", help="Контакт для User-Agent")
    parser.add_argument("--markdown", type=Path, default=None, help="Куда записать отчёт")
    parser.add_argument("--refresh", action="store_true", help="Игнорировать кэш источников")
    parser.add_argument(
        "--fill-gaps",
        action="store_true",
        help="Дозапросить только источники, отказавшие в прошлый раз (новости)",
    )
    parser.add_argument("--limit", type=int, default=0, help="Ограничить число строк (отладка)")
    parser.add_argument(
        "--year",
        type=int,
        default=date.today().year,
        help="Год, относительно которого считается возраст",
    )
    args = parser.parse_args(argv)

    set_contact_email(args.email)
    positives = load_positives(args.dataset, translations=_load_saved_queries(args.queries))
    negatives = load_negatives(args.negatives)

    saved = _build_queries(positives, nlp_url=args.nlp_url, saved=_load_saved_queries(args.queries))
    args.queries.parent.mkdir(parents=True, exist_ok=True)
    args.queries.write_text(json.dumps(saved, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
    positives = load_positives(args.dataset, translations=saved)

    if args.limit:
        positives = positives[: args.limit]
        negatives = negatives[: args.limit]

    cache = _load_cache(args.cache)
    if args.fill_gaps:
        _fill_gaps(cache, args.cache)
    cases: list[Case] = []
    total = len(positives) + len(negatives)
    for index, (label, name, area, query, origin, kind) in enumerate(
        _iter_rows(positives, negatives), 1
    ):
        evidence = _collect(query, cache, refresh=args.refresh)
        decision = classify(evidence, current_year=args.year)
        cases.append(
            Case(
                label=label,
                name=name,
                area=area,
                query=query,
                query_origin=origin,
                kind=kind,
                decision=decision,
            )
        )
        if index % 10 == 0 or index == total:
            print(f"  источники: {index}/{total}", file=sys.stderr)
            args.cache.parent.mkdir(parents=True, exist_ok=True)
            args.cache.write_text(
                json.dumps(cache, ensure_ascii=False, indent=1, sort_keys=True) + "\n"
            )

    args.cache.parent.mkdir(parents=True, exist_ok=True)
    args.cache.write_text(json.dumps(cache, ensure_ascii=False, indent=1, sort_keys=True) + "\n")

    metrics = summarize([(case.label, case.decision) for case in cases])
    report = _markdown(cases, metrics, THRESHOLDS, date.today())
    if args.markdown:
        args.markdown.parent.mkdir(parents=True, exist_ok=True)
        args.markdown.write_text(report, encoding="utf-8")
        print(f"записано: {args.markdown}")
    else:
        print(report)
    print(
        f"Precision {metrics['precision']:.3f}  Recall {metrics['recall']:.3f}  "
        f"F1 {metrics['f1']:.3f}  Accuracy {metrics['accuracy']:.3f}",
        file=sys.stderr,
    )
    return 0


def _fill_gaps(cache: dict[str, dict[str, Any]], path: Path) -> None:
    """Дозаполнить кэш по источникам, которые в прошлый раз отказали.

    Отказ источника — не факт о технологии, а факт о сети, и оставлять его в кэше значит
    измерять собственную доступность. Пересобирать при этом всё остальное незачем: пять
    источников из шести ответили.
    """
    pending = [key for key, value in cache.items() if "gdelt" in (value.get("unavailable") or [])]
    if not pending:
        return
    print(f"  дозапрос новостей: {len(pending)} тем(ы)", file=sys.stderr)
    for index, key in enumerate(pending, 1):
        entry = cache[key]
        phrase = str(entry.get("effective_query") or entry.get("query") or key)
        years, domains, ok = collect_news(phrase)
        if ok:
            entry["news_by_year"] = {str(year): count for year, count in years.items()}
            entry["news_domains"] = list(domains)
            entry["unavailable"] = [s for s in (entry.get("unavailable") or []) if s != "gdelt"]
        if index % 10 == 0 or index == len(pending):
            print(f"  дозапрос новостей: {index}/{len(pending)}", file=sys.stderr)
            path.write_text(json.dumps(cache, ensure_ascii=False, indent=1, sort_keys=True) + "\n")
    path.write_text(json.dumps(cache, ensure_ascii=False, indent=1, sort_keys=True) + "\n")


def _load_saved_queries(path: Path) -> dict[str, str]:
    if not path.is_file():
        return {}
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except ValueError:
        return {}
    return {str(k): str(v) for k, v in payload.items() if isinstance(v, str) and v.strip()}


def _iter_rows(
    positives: Iterable[DatasetRow], negatives: Iterable[NegativeRow]
) -> Iterator[tuple[bool, str, str, str, str, str]]:
    """Положительные и отрицательные строки в одной форме: метка, имя, область, запрос, вид."""
    for positive in positives:
        yield (
            True,
            positive.name,
            positive.area,
            positive.query,
            positive.query_origin,
            "WEAK_SIGNAL",
        )
    for negative in negatives:
        yield False, negative.query, negative.area, negative.query, "curated", negative.kind


def main() -> None:
    """Точка входа модуля."""
    raise SystemExit(_run())


if __name__ == "__main__":
    main()
