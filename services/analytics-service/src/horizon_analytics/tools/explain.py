"""Explain why a term is — or is not — in the report.

Run against the golden corpus this is the project's acceptance instrument: the corpus manifest
declares which topics *should* surface, so the tool turns "the ranking looks wrong" into "topic X
died at stage Y because Z, here are the numbers". Aggregate drop counters cannot do that; they say
2400 candidates were removed and nothing about which ones mattered.

The same mechanism answers the question an analyst asks about a real direction — *I know this
technology exists, why is it not in my report?* — which is the reverse of the explainability the
product already provides for the trends it does return.

    python -m horizon_analytics.tools.explain --expected
    python -m horizon_analytics.tools.explain --term "speculative decoding" --term "zk-rollup"
    python -m horizon_analytics.tools.explain --expected --json
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from datetime import date
from pathlib import Path
from typing import Any

from horizon_analytics.adapters.corpus.fixture_loader import load_documents
from horizon_analytics.adapters.embeddings.tfidf_svd import TfidfSvdEmbeddingProvider
from horizon_analytics.domain.extraction.blacklist import stem_phrase
from horizon_analytics.domain.models import AnalysisParams
from horizon_analytics.domain.pipeline import (
    TRACE_STAGES,
    AnalysisPipeline,
    CandidateTrace,
    PipelineRequest,
)
from horizon_analytics.domain.scoring.profile import MethodologyProfile

DEFAULT_CORPUS = Path("fixtures/corpus/documents.jsonl")
DEFAULT_MANIFEST = Path("fixtures/corpus/manifest.json")

#: A stage a topic reaches without being the analysis's fault — used to colour the summary.
_EXPECTED_EXITS = frozenset({"mainstream", "zero_score"})

#: A direction phrase per corpus domain. Recall is only meaningful per direction: a quantum topic
#: absent from an AI report is correct behaviour, not a miss, and averaging the two produces a
#: number that means nothing. The phrases are deliberately written the way an analyst would type
#: them, not as the corpus's internal vocabulary — that is the input the system must actually cope
#: with.
DOMAIN_QUERIES: dict[str, str] = {
    "ai": "artificial intelligence machine learning",
    "security": "computer security cryptography",
    "quantum": "quantum computing",
    "bio": "computational biology genetics",
    "fintech": "financial technology blockchain",
    "energy": "materials science energy storage",
}

#: Те же направления, как их набирает наш аналитик. Не перевод предыдущей таблицы, а формулировки,
#: которые человек действительно печатает: «финтех», а не «финансовые технологии блокчейн».
#:
#: Существуют отдельно, потому что английские фразы проверяют только английский путь. Отнесение к
#: направлению для русского запроса идёт через перекрёстный словарь
#: (`docs/01-analysis/28-direction-lexicon-spec.md`), и без этой таблицы регрессия «направление
#: перестало распознаваться» прошла бы приёмку незамеченной — ровно так она однажды и прожила
#: незамеченной, пока три разных русских направления не начали давать один и тот же отчёт.
DOMAIN_QUERIES_RU: dict[str, str] = {
    "ai": "искусственный интеллект",
    "security": "информационная безопасность",
    "quantum": "квантовые вычисления",
    "bio": "биоинформатика",
    "fintech": "финтех",
    "energy": "материаловедение",
}


def _resolve(path: Path) -> Path:
    """Accept a path relative to the repository root or to the service directory."""
    for candidate in (path, Path("../..") / path, Path(__file__).resolve().parents[4] / path):
        if candidate.exists():
            return candidate
    raise SystemExit(f"не найдено: {path}")


def _expected_terms(manifest_path: Path, domain: str | None = None) -> list[tuple[str, str]]:
    """`(term, expectation)` for topics the manifest marks as expected in the TOP."""
    manifest = json.loads(_resolve(manifest_path).read_text(encoding="utf-8"))
    return [
        (topic["term"], topic["expectation"])
        for topic in manifest.get("topics", ())
        if str(topic.get("expectation", "")).startswith("TOP")
        and (domain is None or topic.get("domain") == domain)
    ]


def _render_detail(detail: dict[str, Any]) -> str:
    if not detail:
        return ""
    return "; ".join(f"{name}={value}" for name, value in sorted(detail.items()))


def _run(
    corpus: Path,
    manifest: Path,
    query: str,
    terms: list[str],
    top_n: int,
) -> tuple[list[CandidateTrace], list[str], dict[str, int]]:
    documents = load_documents(_resolve(corpus))
    watch = frozenset(stem_phrase(term) for term in terms)

    request = PipelineRequest(
        normalized_query=query,
        documents=documents,
        params=AnalysisParams(top_n=top_n, years_window=8),
        profile=MethodologyProfile.default(),
        window_from=date(2018, 1, 1),
        window_to=date(2025, 12, 31),
        today=date(2026, 1, 1),
        query=query,
        watch=watch,
    )
    result = AnalysisPipeline(embedding_provider=TfidfSvdEmbeddingProvider()).run(request)
    ranked = [outcome.result.trend_key for outcome in result.trends]
    return list(result.traces), ranked, dict(result.diagnostics)


def main(argv: list[str] | None = None) -> int:
    """Trace the requested terms and report where each one left the pipeline."""
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--corpus", type=Path, default=DEFAULT_CORPUS)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--query", default="artificial intelligence machine learning")
    parser.add_argument("--top-n", type=int, default=15)
    parser.add_argument(
        "--term", action="append", default=[], help="термин для трассировки (можно несколько)"
    )
    parser.add_argument(
        "--expected",
        action="store_true",
        help="трассировать все темы, помеченные в манифесте как ожидаемые в TOP",
    )
    parser.add_argument(
        "--domain",
        choices=sorted(DOMAIN_QUERIES),
        help="ограничить ожидания одним доменом корпуса и взять его типовое направление",
    )
    parser.add_argument(
        "--lang",
        choices=("en", "ru"),
        default="en",
        help="на каком языке взять типовое направление домена (только вместе с --domain)",
    )
    parser.add_argument("--json", action="store_true", help="машиночитаемый вывод")
    args = parser.parse_args(argv)

    if args.domain:
        table = DOMAIN_QUERIES_RU if args.lang == "ru" else DOMAIN_QUERIES
        args.query = table[args.domain]

    expectations: dict[str, str] = {}
    terms = list(args.term)
    if args.expected:
        for term, expectation in _expected_terms(args.manifest, args.domain):
            terms.append(term)
            expectations[stem_phrase(term)] = expectation
    if not terms:
        parser.error("укажите --term или --expected")

    traces, ranked, diagnostics = _run(args.corpus, args.manifest, args.query, terms, args.top_n)
    by_key = {trace.key: trace for trace in traces}

    if args.json:
        json.dump(
            {
                "query": args.query,
                "ranked": ranked,
                "diagnostics": diagnostics,
                "traces": [
                    {
                        "key": trace.key,
                        "canonicalKey": trace.canonical_key,
                        "stage": trace.stage,
                        "outcome": trace.outcome,
                        "reason": trace.reason,
                        "detail": dict(trace.detail),
                        "expectation": expectations.get(trace.key),
                    }
                    for trace in traces
                ],
            },
            sys.stdout,
            ensure_ascii=False,
            indent=2,
        )
        print()
        return 0

    print(f"направление: {args.query!r}   отслеживаем терминов: {len(terms)}")
    print(f"в отчёт вошло: {len(ranked)}\n")

    width = max((len(term) for term in terms), default=20)
    print(f"{'термин':<{width}} {'стадия':<18} {'итог':<9} причина")
    print("─" * (width + 60))

    stages = Counter[str]()
    for term in sorted(terms):
        key = stem_phrase(term)
        trace = by_key.get(key)
        if trace is None:
            print(f"{term:<{width}} {'—':<18} {'нет':<9} термин не отслеживался")
            continue
        stages[trace.stage] += 1
        detail = _render_detail(dict(trace.detail))
        reason = trace.reason or ("в отчёте" if trace.survived else "")
        suffix = f"  [{detail}]" if detail else ""
        alias = f"  ← слит в «{trace.canonical_key}»" if trace.canonical_key else ""
        print(f"{term:<{width}} {trace.stage:<18} {trace.outcome:<9} {reason}{suffix}{alias}")

    survived = sum(1 for term in terms if (t := by_key.get(stem_phrase(term))) and t.survived)
    print(f"\nдошло до отчёта: {survived}/{len(terms)}")

    if stages:
        print("\nгде теряются, в порядке конвейера:")
        for stage in TRACE_STAGES:
            if stages[stage]:
                marker = "  " if stage in _EXPECTED_EXITS or stage == "ranked" else "← "
                print(f"  {marker}{stage:<20} {stages[stage]}")

    # Non-zero exit when the corpus's own expectations are not met: this is a gate, not a report.
    if args.expected:
        return 0 if survived == len(terms) else 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
