r"""CLI сбора признаков: ``python -m horizon_analytics.signals.collect``.

    python -m horizon_analytics.signals.collect \\
        --terms terms.json --out signals.jsonl --cache ./signals_cache

Вход — JSON со списком ``{"term": ..., "area": ...}`` (путь к файлу или сам текст JSON). Выход —
JSONL, по строке на термин, дописываемый **по мере готовности**: сбор длинный, и его должно быть
не жалко прервать. Повторный запуск с тем же ``--out`` дочитывает недостающее — термины, уже
лежащие в файле, не опрашиваются вовсе. Это второй рубеж после кэша: кэш экономит сеть, докачка
экономит ещё и разбор, и вместе они делают прерывание сбора безопасным.
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
import time
from collections.abc import Sequence
from pathlib import Path

from horizon_analytics.signals.cache import DEFAULT_CACHE_DIR
from horizon_analytics.signals.collector import (
    DEFAULT_YEARS,
    FAST_SOURCES,
    SLOW_SOURCES,
    SOURCE_NAMES,
    TermRequest,
    collect_signals,
)
from horizon_analytics.signals.models import SourceResult, TermSignals

__all__ = ["already_collected", "load_terms", "main"]


def load_terms(raw: str) -> list[TermRequest]:
    """Читает список терминов из файла или из самого текста JSON.

    Двойственность намеренная: в скрипте удобен файл, в разовой проверке — строка, и заставлять
    ради пяти терминов заводить файл незачем.
    """
    text = raw
    candidate = Path(raw)
    if not raw.lstrip().startswith(("[", "{")) and candidate.is_file():
        text = candidate.read_text(encoding="utf-8")
    payload = json.loads(text)
    if isinstance(payload, dict):
        payload = payload.get("terms", [])
    if not isinstance(payload, list):
        raise ValueError('--terms: ожидался список записей {"term": ..., "area": ...}')
    return [TermRequest.from_json(item) for item in payload]


def already_collected(out: Path, *, keep_partial: bool = True) -> tuple[set[str], list[str]]:
    """Термины, уже лежащие в файле результата, и строки, которые стоит сохранить.

    Битые строки (обрыв на середине записи) игнорируются: такой термин просто соберётся заново.

    При ``keep_partial=False`` записи с отказавшими источниками не считаются собранными и из
    файла выбрасываются. Это нужно, потому что отказ источника переживает запуск: arXiv, ответив
    девять раз подряд «429», оставит девять записей с пустым полем, и без такого режима они
    остались бы пустыми навсегда. Пересобирается при этом только отказавший источник — остальные
    пять придут из кэша, не потратив ни одного запроса.
    """
    if not out.is_file():
        return set(), []
    done: set[str] = set()
    kept: list[str] = []
    for line in out.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            record = json.loads(line)
            term = str(record["term"])
        except (ValueError, KeyError):
            continue
        if not keep_partial and record.get("failures"):
            continue
        done.add(term)
        kept.append(line)
    return done, kept


def _parse_workers(raw: str) -> dict[str, int]:
    """Разбирает ``openalex=8,wikipedia=8`` в словарь ширин пулов."""
    workers: dict[str, int] = {}
    for chunk in str(raw).split(","):
        chunk = chunk.strip()
        if not chunk:
            continue
        name, _, value = chunk.partition("=")
        name = name.strip()
        if name not in SOURCE_NAMES or not value.strip().isdigit():
            raise ValueError(f"ожидалось «источник=число», получено «{chunk}»")
        workers[name] = int(value)
    return workers


def _parse_args(argv: Sequence[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="python -m horizon_analytics.signals.collect",
        description="Собирает признаки жизненного цикла технологий из открытых источников.",
    )
    parser.add_argument("--terms", required=True, help="JSON-список {term, area}: путь или текст")
    parser.add_argument("--out", required=True, type=Path, help="куда дописывать JSONL")
    parser.add_argument("--cache", type=Path, default=DEFAULT_CACHE_DIR, help="каталог кэша")
    parser.add_argument(
        "--sources",
        default=",".join(SOURCE_NAMES),
        help=f"через запятую из: {', '.join(SOURCE_NAMES)}",
    )
    parser.add_argument(
        "--fast",
        action="store_true",
        help=(
            "только быстрые источники (" + ", ".join(FAST_SOURCES) + "): для живого анализа "
            "сотен кандидатов; отбрасывает " + ", ".join(SLOW_SOURCES)
        ),
    )
    parser.add_argument(
        "--workers",
        default="",
        help="ширина пула на источник, например openalex=8,wikipedia=8",
    )
    parser.add_argument("--from-year", type=int, default=DEFAULT_YEARS[0])
    parser.add_argument("--to-year", type=int, default=DEFAULT_YEARS[-1])
    parser.add_argument(
        "--no-resume",
        action="store_true",
        help="собрать заново даже то, что уже есть в --out (файл перезаписывается)",
    )
    parser.add_argument(
        "--retry-failed",
        action="store_true",
        help="дособрать термины, у которых какой-то источник отказал (остальные — из кэша)",
    )
    parser.add_argument("--quiet", action="store_true", help="без построчного прогресса")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    """Точка входа CLI."""
    args = _parse_args(argv)
    logging.basicConfig(
        level=logging.WARNING, format="%(levelname)s %(name)s: %(message)s", stream=sys.stderr
    )

    terms = load_terms(args.terms)
    sources = (
        list(FAST_SOURCES)
        if args.fast
        else [name.strip() for name in str(args.sources).split(",") if name.strip()]
    )
    try:
        workers = _parse_workers(args.workers)
    except ValueError as exc:
        print(f"--workers: {exc}", file=sys.stderr)
        return 2
    unknown = sorted(set(sources) - set(SOURCE_NAMES))
    if unknown:
        print(f"неизвестные источники: {', '.join(unknown)}", file=sys.stderr)
        return 2

    args.out.parent.mkdir(parents=True, exist_ok=True)
    if args.no_resume:
        args.out.unlink(missing_ok=True)
        done: set[str] = set()
    else:
        done, kept = already_collected(args.out, keep_partial=not args.retry_failed)
        if args.retry_failed and args.out.is_file():
            # Файл переписывается целиком: иначе недособранный термин остался бы в нём вторым,
            # уже полным дубликатом, и читателю пришлось бы гадать, какая строка свежее.
            args.out.write_text("".join(f"{line}\n" for line in kept), encoding="utf-8")
    pending = [term for term in terms if term.term not in done]

    print(
        f"терминов {len(terms)}, уже собрано {len(terms) - len(pending)}, "
        f"осталось {len(pending)}; источники: {', '.join(sources)}",
        file=sys.stderr,
    )
    if not pending:
        return 0

    started = time.monotonic()
    written = 0
    # Файл открыт на дозапись всё время сбора и сбрасывается после каждой строки: убитый на
    # середине процесс должен оставить ровно то, что успел собрать, и не потерять ни строки.
    with args.out.open("a", encoding="utf-8") as handle:

        def on_source(term: str, result: SourceResult, cached: bool) -> None:
            if args.quiet:
                return
            mark = "кэш" if cached else ("ок " if result.ok else "ОТКАЗ")
            note = f" — {result.error}" if result.error else ""
            print(f"  [{mark}] {result.source:<11} {term}{note}", file=sys.stderr)

        def on_term(signals: TermSignals) -> None:
            nonlocal written
            handle.write(json.dumps(signals.to_json(), ensure_ascii=False) + "\n")
            handle.flush()
            written += 1
            failed = ", ".join(signals.failures) or "—"
            elapsed = time.monotonic() - started
            print(
                f"[{written}/{len(pending)}] {signals.term}: "
                f"признаков {len(signals.features())}, отказы: {failed} "
                f"({elapsed:.0f} с, {elapsed / written:.1f} с/термин)",
                file=sys.stderr,
            )

        collect_signals(
            pending,
            cache_dir=args.cache,
            years=tuple(range(args.from_year, args.to_year + 1)),
            sources=sources,
            workers=workers,
            on_term=on_term,
            on_source=on_source,
        )

    elapsed = time.monotonic() - started
    print(
        f"готово: {written} терминов за {elapsed:.0f} с ({elapsed / max(1, written):.1f} с/термин)",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
