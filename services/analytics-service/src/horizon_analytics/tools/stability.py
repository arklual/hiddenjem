"""Печатает таблицу устойчивости состава к весам индикаторов (методология §16).

Инструмент существует не ради удобства, а против конкретной ошибки. Числа §16 были набраны руками,
и дважды подряд при попытке их процитировать выяснялось, что они разошлись с прогоном: «первая
шестёрка не опускается ниже восьмого места» оказалось неверным — шестая тема плавает от 2-го до
14-го. Ошибка тихая: таблица выглядит одинаково убедительно и когда верна, и когда устарела.

Тот же приём уже применён к приёмке (`make accept` → `acceptance-current.md`, «руками не править»).
Здесь он повторён для устойчивости: числа печатает машина, документ на них ссылается, и разойтись
им негде.

    python -m horizon_analytics.tools.stability
    python -m horizon_analytics.tools.stability --direction "квантовые вычисления"
    python -m horizon_analytics.tools.stability --markdown

Детерминизм: конвейер воспроизводим (ADR-0015), сценарии взвешивания перебираются без зерна (§16),
поэтому два запуска на одном корпусе дают побайтово одинаковую таблицу.
"""

from __future__ import annotations

import argparse
import sys
from datetime import date
from pathlib import Path

from horizon_analytics.adapters.corpus.fixture_loader import load_documents
from horizon_analytics.adapters.embeddings.tfidf_svd import TfidfSvdEmbeddingProvider
from horizon_analytics.domain.models import AnalysisParams, Document
from horizon_analytics.domain.pipeline import AnalysisPipeline, PipelineRequest, PipelineResult
from horizon_analytics.domain.scoring.profile import MethodologyProfile
from horizon_analytics.domain.scoring.stability import reweighting_scenarios

DEFAULT_CORPUS = Path("fixtures/corpus/documents.jsonl")

#: Направления по умолчанию — те же, на которых меряется приёмка, и на обоих языках.
#:
#: Один язык не годится: перекрёстный словарь дважды расходился с английской формулировкой, и оба
#: раза это было видно только при сравнении двух колонок.
DEFAULT_DIRECTIONS: tuple[str, ...] = (
    "искусственный интеллект",
    "artificial intelligence",
    "квантовые вычисления",
    "quantum computing",
)


def _resolve(path: Path) -> Path:
    """Принять путь как от корня репозитория, так и от каталога сервиса."""
    for candidate in (path, Path("../..") / path, Path(__file__).resolve().parents[4] / path):
        if candidate.exists():
            return candidate
    raise FileNotFoundError(path)


def _analyse(documents: tuple[Document, ...], direction: str, top_n: int) -> PipelineResult:
    request = PipelineRequest(
        normalized_query=direction,
        documents=documents,
        params=AnalysisParams(top_n=top_n, years_window=8),
        profile=MethodologyProfile.default(),
        window_from=date(2018, 1, 1),
        window_to=date(2025, 12, 31),
        today=date(2026, 1, 1),
        query=direction,
        watch=frozenset(),
    )
    return AnalysisPipeline(embedding_provider=TfidfSvdEmbeddingProvider()).run(request)


def _rows(result: PipelineResult) -> list[tuple[str, int, int, int]]:
    """`(название темы, место в отчёте, лучшее, худшее)` в порядке отчёта.

    Название — то же `title`, что уходит в отчёт и в выгрузки, а не `trend_key`. Ключ нормализован
    стеммером: в таблице он выглядел как «speculative decod», «retriev passage», «constant memory
    decod». Читатель §16 — тот самый скептик, ради которого раздел написан, — видел бы обрубки там,
    где продукт показывает названия, и делал бы вывод не о весах, а о качестве извлечения.
    """
    ranges = {row.trend_key: row for row in result.rank_stability}
    rows: list[tuple[str, int, int, int]] = []
    for place, outcome in enumerate(result.trends, start=1):
        measured = ranges.get(outcome.result.trend_key)
        if measured is None:
            continue
        rows.append((outcome.result.title, place, measured.best, measured.worst))
    return rows


def _render(direction: str, result: PipelineResult, markdown: bool) -> str:
    rows = _rows(result)
    published = len(result.trends)
    steady = sum(1 for _, _, _, worst in rows if worst <= published)
    scenarios = len(reweighting_scenarios(MethodologyProfile.default().weights)) + 1
    lines: list[str] = []
    if markdown:
        lines.append(f"### {direction}")
        lines.append("")
        lines.append(
            f"Тем в отчёте: {published}. Наборов весов: {scenarios}. "
            f"**Держатся в отчёте при любом наборе: {steady}.**"
        )
        lines.append("")
        lines.append("| Место | Тема | Диапазон места |")
        lines.append("| --- | --- | --- |")
        for key, place, best, worst in rows:
            span = str(best) if best == worst else f"{best}–{worst}"
            lines.append(f"| {place} | `{key}` | {span} |")
        lines.append("")
    else:
        lines.append(f"{direction}: тем {published}, наборов весов {scenarios}, держатся {steady}")
        for key, place, best, worst in rows:
            span = str(best) if best == worst else f"{best}–{worst}"
            lines.append(f"  {place:2d}. {key:44s} {span}")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    """Напечатать таблицу устойчивости по одному или нескольким направлениям."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", type=Path, default=DEFAULT_CORPUS)
    parser.add_argument("--direction", action="append", dest="directions")
    parser.add_argument("--top-n", type=int, default=15)
    parser.add_argument("--markdown", action="store_true")
    args = parser.parse_args(argv)

    documents = tuple(load_documents(_resolve(args.corpus)))
    directions = tuple(args.directions) if args.directions else DEFAULT_DIRECTIONS

    if args.markdown:
        print(
            "<!-- Сгенерировано `make stability`. Руками не править: правка разойдётся с замером -->"
        )
        print(f"Замер на эталонном корпусе `{args.corpus}`, ТОП-{args.top_n}.")
        print()
    for direction in directions:
        print(_render(direction, _analyse(documents, direction, args.top_n), args.markdown))
    return 0


if __name__ == "__main__":  # pragma: no cover - точка входа
    sys.exit(main())
