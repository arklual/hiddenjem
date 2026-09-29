"""Backtest the methodology against history.

The product claims to find technologies before they matter. That claim is checkable: freeze the
corpus at a past year, run the analysis as if it were that year, and look at what actually happened
afterwards in documents the frozen run could not see.

**The hit rate alone is meaningless, so this tool never reports it alone.** Any ranking of a growing
corpus will contain topics that later grew — the corpus grew. The question is whether the
methodology beats what you would have got for free, so every run also scores a *naive baseline*:
the most-published topics at the same cutoff. That is the analyst's realistic alternative — read
what everyone is already reading. If the methodology does not beat it, the number to report is that
it does not.

    python -m horizon_analytics.tools.backtest --snapshot-year 2021
    python -m horizon_analytics.tools.backtest --snapshot-year 2021 --output backtest.json
    python -m horizon_analytics.tools.backtest --snapshot-year 2021 --domain ai

Determinism: the frozen corpus is a pure function of the cutoff, and the pipeline is deterministic
(ADR-0015), so a backtest is reproducible and can be diffed between methodology versions.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from collections.abc import Mapping
from dataclasses import dataclass, replace
from datetime import date
from pathlib import Path
from typing import Protocol

from horizon_analytics import METHODOLOGY_VERSION
from horizon_analytics.adapters.corpus.fixture_loader import load_documents
from horizon_analytics.adapters.embeddings.tfidf_svd import TfidfSvdEmbeddingProvider
from horizon_analytics.domain.extraction.blacklist import GenericTermFilter, load_stopwords
from horizon_analytics.domain.extraction.candidates import extract_candidates
from horizon_analytics.domain.extraction.normalization import TermNormalizer
from horizon_analytics.domain.models import AnalysisParams, Document
from horizon_analytics.domain.pipeline import (
    AnalysisPipeline,
    PipelineRequest,
    documents_in_direction,
)
from horizon_analytics.domain.scoring.profile import MethodologyProfile
from horizon_analytics.tools.explain import DOMAIN_QUERIES, _resolve

DEFAULT_CORPUS = Path("fixtures/corpus/documents.jsonl")

#: How much a topic must outgrow its own corpus to count as a hit.
#:
#: 1.0 would mean "grew at all", which the whole corpus did — that measures the calendar, not the
#: method. 1.5 asks the topic to have grown half again faster than its field, which is the weakest
#: claim that still means something happened.
DEFAULT_HIT_LIFT = 1.5


@dataclass(frozen=True, slots=True)
class TopicOutcome:
    """What became of one predicted topic after the cutoff."""

    key: str
    rank: int
    score: float
    documents_before: int
    documents_after: int
    #: Post-cutoff documents relative to what corpus-wide growth alone would have produced.
    lift: float
    #: Years from the cutoff until the topic's post-cutoff literature exceeded all of its prior
    #: literature — an interpretable reading of "how early we were". ``None`` if it never did.
    doubling_years: int | None

    @property
    def hit(self) -> bool:
        """Whether the topic grew enough after the cutoff to count as correctly flagged."""
        return self.lift >= DEFAULT_HIT_LIFT


@dataclass(frozen=True, slots=True)
class ArmResult:
    """Outcome of one ranking strategy (the methodology, or the naive baseline)."""

    name: str
    outcomes: tuple[TopicOutcome, ...]

    @property
    def hit_rate(self) -> float:
        """Share of predicted topics that outgrew their corpus."""
        if not self.outcomes:
            return 0.0
        return sum(1 for outcome in self.outcomes if outcome.hit) / len(self.outcomes)

    @property
    def median_lift(self) -> float:
        """Median growth relative to the corpus — robust to one runaway topic carrying the arm."""
        if not self.outcomes:
            return 0.0
        lifts = sorted(outcome.lift for outcome in self.outcomes)
        middle = len(lifts) // 2
        if len(lifts) % 2:
            return lifts[middle]
        return (lifts[middle - 1] + lifts[middle]) / 2

    @property
    def median_lead_years(self) -> float | None:
        """Median years of warning the arm gave, over the topics that did take off."""
        values = sorted(o.doubling_years for o in self.outcomes if o.doubling_years is not None)
        if not values:
            return None
        middle = len(values) // 2
        if len(values) % 2:
            return float(values[middle])
        return (values[middle - 1] + values[middle]) / 2


class TermHistory(Protocol):
    """The only thing scoring needs from an index: when a term was published.

    Narrowing the dependency to this keeps the scoring rules — the part that decides whether a
    backtest means anything — testable without constructing a corpus.
    """

    def years_for(self, key: str) -> tuple[int, ...]:
        """Publication years of every document mentioning ``key``, ascending."""


class MentionIndex:
    """Which documents mention a term, over the **whole** corpus including the future.

    Built once from the unfrozen corpus. The frozen pipeline never sees it — it exists only to
    score, after the fact, what the frozen run predicted.
    """

    def __init__(self, documents: tuple[Document, ...]) -> None:
        """Index every candidate term of the whole corpus by the years it was published in."""
        texts = [f"{document.title}. {document.abstract_text or ''}" for document in documents]
        extraction = extract_candidates(
            documents,
            normalizer=TermNormalizer.from_texts(texts),
            stopwords=load_stopwords(),
        )
        self._years: dict[str, int] = {d.document_id: d.year for d in documents}
        self._by_key: dict[str, tuple[int, ...]] = {
            candidate.key: tuple(
                sorted(
                    self._years[posting.document_id]
                    for posting in candidate.postings
                    if posting.document_id in self._years
                )
            )
            for candidate in extraction.candidates
        }
        self._candidates = extraction.candidates
        self.filter = GenericTermFilter.default()

    def years_for(self, key: str) -> tuple[int, ...]:
        """Publication years of every document mentioning ``key``, ascending."""
        return self._by_key.get(key, ())

    def popular_keys(self, cutoff: int, limit: int, within: frozenset[str]) -> list[str]:
        """The most-published *plausible* terms of one direction as of the cutoff — the baseline.

        Two constraints make this a fair opponent rather than a strawman, and both are load-bearing:

        * ``within`` restricts counting to the direction's own documents. An analyst asking about
          quantum computing reads the most-published quantum papers, not the most-published papers
          on earth. A corpus-wide baseline would score identically for every direction, which is the
          visible symptom of comparing against nobody.
        * the same term filter the real pipeline uses. Without it the baseline is boilerplate
          n-grams, and beating boilerplate demonstrates nothing.
        """
        scored: list[tuple[int, str]] = []
        for candidate in self._candidates:
            if self.filter.rejects(candidate.key, candidate.surface.split(" ")) is not None:
                continue
            before = sum(
                1
                for posting in candidate.postings
                if posting.document_id in within
                and self._years.get(posting.document_id, cutoff + 1) <= cutoff
            )
            if before > 0:
                scored.append((before, candidate.key))
        scored.sort(key=lambda item: (-item[0], item[1]))
        return [key for _, key in scored[:limit]]


def _freeze(documents: tuple[Document, ...], cutoff: int) -> tuple[Document, ...]:
    """The corpus as it stood at the end of ``cutoff``."""
    return tuple(document for document in documents if document.year <= cutoff)


def _outcome(
    key: str, rank: int, score: float, index: TermHistory, cutoff: int, corpus_lift_base: float
) -> TopicOutcome:
    years = index.years_for(key)
    before = sum(1 for year in years if year <= cutoff)
    after = sum(1 for year in years if year > cutoff)

    # Expected post-cutoff volume if the topic had merely kept pace with the corpus.
    expected = before * corpus_lift_base
    lift = after / expected if expected > 0 else 0.0

    doubling: int | None = None
    later = sorted(year for year in years if year > cutoff)
    for accumulated, year in enumerate(later, 1):
        if before > 0 and accumulated >= before:
            doubling = year - cutoff
            break

    return TopicOutcome(
        key=key,
        rank=rank,
        score=round(score, 4),
        documents_before=before,
        documents_after=after,
        lift=round(lift, 4),
        doubling_years=doubling,
    )


def _run_domain(
    documents: tuple[Document, ...],
    index: MentionIndex,
    domain: str,
    cutoff: int,
    top_n: int,
    profile: MethodologyProfile | None = None,
) -> tuple[ArmResult, ArmResult]:
    """Run the methodology and the baseline for one direction, and score both."""
    frozen = _freeze(documents, cutoff)
    corpus_before = len(frozen)
    corpus_after = len(documents) - corpus_before
    # Corpus-wide growth factor: the yardstick every topic is measured against.
    corpus_lift_base = corpus_after / corpus_before if corpus_before else 0.0

    query = DOMAIN_QUERIES[domain]
    # The baseline must play on the same field as the methodology: the direction's own literature,
    # decided by the same membership rule the pipeline uses.
    in_direction = frozenset(
        document.document_id
        for document in documents_in_direction(
            frozen,
            query,
            TermNormalizer.from_texts([f"{d.title}. {d.abstract_text or ''}" for d in frozen]),
        )
    )
    request = PipelineRequest(
        normalized_query=query,
        documents=frozen,
        params=AnalysisParams(top_n=top_n, years_window=8),
        profile=profile or MethodologyProfile.default(),
        window_from=date(cutoff - 7, 1, 1),
        window_to=date(cutoff, 12, 31),
        # The frozen run must believe it is standing at the cutoff, or `novelty` would age every
        # topic by the years it is not allowed to know about.
        today=date(cutoff + 1, 1, 1),
        query=query,
    )
    result = AnalysisPipeline(embedding_provider=TfidfSvdEmbeddingProvider()).run(request)

    method = ArmResult(
        name="methodology",
        outcomes=tuple(
            _outcome(
                outcome.result.trend_key,
                rank,
                outcome.result.score,
                index,
                cutoff,
                corpus_lift_base,
            )
            for rank, outcome in enumerate(result.trends, 1)
        ),
    )
    baseline = ArmResult(
        name="popularity",
        outcomes=tuple(
            _outcome(key, rank, 0.0, index, cutoff, corpus_lift_base)
            for rank, key in enumerate(index.popular_keys(cutoff, top_n, in_direction), 1)
        ),
    )
    return method, baseline


def _print_arm(arm: ArmResult, label: str) -> None:
    lead = arm.median_lead_years
    lead_text = f"{lead:.1f} г." if lead is not None else "—"
    print(
        f"    {label:<14} попаданий {arm.hit_rate:>5.0%}  "
        f"медианный прирост ×{arm.median_lift:<5.2f}  опережение {lead_text}"
    )


def _sweep_year(
    documents: tuple[Document, ...],
    index: MentionIndex,
    domains: list[str],
    year: int,
    top_n: int,
    last_year: int,
    profile: MethodologyProfile | None = None,
) -> dict[str, object]:
    """Итог одного дополнительного среза — только доли попаданий, без разбора по темам.

    Подробности нужны для того среза, который разбирают; остальные отвечают на единственный вопрос
    «а если взять другой год», и лишние подробности сделали бы артефакт нечитаемым, не добавив
    ответа.
    """
    if year >= last_year:
        return {"snapshotYear": year, "hitRate": None, "baselineHitRate": None}
    method_hits: list[float] = []
    baseline_hits: list[float] = []
    for domain in domains:
        method, baseline = _run_domain(documents, index, domain, year, top_n, profile)
        method_hits.append(method.hit_rate)
        baseline_hits.append(baseline.hit_rate)
    return {
        "snapshotYear": year,
        "hitRate": round(math.fsum(method_hits) / len(method_hits), 4) if method_hits else None,
        "baselineHitRate": (
            round(math.fsum(baseline_hits) / len(baseline_hits), 4) if baseline_hits else None
        ),
    }


def _render_markdown(report: Mapping[str, object]) -> str:
    """Таблица §15 методологии: по направлениям и разброс по срезам.

    Печатается машиной по той же причине, что таблицы §16 и приёмки: рукописные числа в этом
    проекте расходились с прогоном четыре раза, и каждый раз это замечали случайно.
    """
    lines = [
        "<!-- Сгенерировано `make backtest`. Руками не править: правка разойдётся с замером -->",
        f"Срез {report['snapshotYear']} года, горизонт {report['horizonYears']} лет, "
        f"ТОП-{report['topN']}, порог попадания ×{report['hitLiftThreshold']}.",
        "",
        "| Направление | Методология | Самое публикуемое | Медианный рост методологии |",
        "| --- | --- | --- | --- |",
    ]
    domains = report.get("domains")
    if isinstance(domains, Mapping):
        for name in sorted(domains):
            entry = domains[name]
            if not isinstance(entry, Mapping):
                continue
            method = entry.get("methodology", {})
            baseline = entry.get("baseline", {})
            if not isinstance(method, Mapping) or not isinstance(baseline, Mapping):
                continue
            lines.append(
                f"| {name} | {float(method.get('hitRate', 0.0)) * 100:.0f}% "
                f"| {float(baseline.get('hitRate', 0.0)) * 100:.0f}% "
                f"| ×{float(method.get('medianLift', 0.0)):.2f} |"
            )
    lines.append(
        f"| **итого** | **{float(str(report['hitRate'])) * 100:.0f}%** "
        f"| **{float(str(report['baselineHitRate'])) * 100:.0f}%** "
        f"| медианное опережение {report.get('medianLeadYears')} г. |"
    )

    sweep = report.get("sweep")
    if isinstance(sweep, list) and sweep:
        lines += [
            "",
            "Разброс по срезам — один год это одно наблюдение, а не проверка:",
            "",
            "| Срез | Методология | Самое публикуемое |",
            "| --- | --- | --- |",
        ]
        for item in sweep:
            if not isinstance(item, Mapping):
                continue
            lines.append(
                f"| {item['snapshotYear']} | {float(str(item['hitRate'])) * 100:.0f}% "
                f"| {float(str(item['baselineHitRate'])) * 100:.0f}% |"
            )
    return "\n".join(lines) + "\n"


def _profile_with(overrides: list[str]) -> MethodologyProfile:
    """Профиль по умолчанию с переопределёнными параметрами из ``--parameter ИМЯ=ЗНАЧЕНИЕ``.

    Значение приводится к типу поля по умолчанию: ``true``/``false`` для флагов, число для чисел.
    Незнакомое имя — ошибка, а не молчаливый пропуск: бэктест, тихо отбросивший параметр, выдал
    бы два одинаковых прогона за сравнение двух вариантов.
    """
    base = MethodologyProfile.default()
    if not overrides:
        return base
    defaults = base.parameters
    values: dict[str, object] = {}
    for item in overrides:
        name, _, raw = item.partition("=")
        name = name.strip()
        if not hasattr(defaults, name):
            raise SystemExit(f"неизвестный параметр методологии: {name}")
        current = getattr(defaults, name)
        if isinstance(current, bool):
            values[name] = raw.strip().lower() in {"1", "true", "yes", "да"}
        elif isinstance(current, int):
            values[name] = int(raw)
        elif isinstance(current, float):
            values[name] = float(raw)
        else:
            values[name] = raw
    return replace(base, parameters=defaults.with_overrides(values))


def main(argv: list[str] | None = None) -> int:
    """Freeze the corpus at a past year, re-run the analysis, and score it against what happened."""
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--corpus", type=Path, default=DEFAULT_CORPUS)
    parser.add_argument("--snapshot-year", type=int, default=2021)
    parser.add_argument("--top-n", type=int, default=15)
    parser.add_argument("--domain", choices=sorted(DOMAIN_QUERIES))
    parser.add_argument(
        "--parameter",
        action="append",
        default=[],
        metavar="ИМЯ=ЗНАЧЕНИЕ",
        help=(
            "переопределить параметр методологии для сравнения вариантов, например "
            "growth_relative=true. Можно повторять. Без этого вариант методологии проверялся "
            "только правкой умолчания в коде — то есть сравнить два варианта одним прогоном "
            "было нечем"
        ),
    )
    parser.add_argument("--output", type=Path, help="записать результат в JSON")
    parser.add_argument(
        "--markdown",
        type=Path,
        help=(
            "записать таблицу для методологии. Числа §15 были набраны руками и разошлись втрое: "
            "документ говорил 1% на срезе 2020 года, поставляемый артефакт — 3%, прогон — 7%"
        ),
    )
    parser.add_argument(
        "--also-years",
        default="",
        help=(
            "дополнительные срезы через запятую: их итоги попадут в артефакт как разброс. "
            "Одно число по одному году выглядит точным, а оно одно наблюдение: на эталонном "
            "корпусе попадания по годам расходятся втрое"
        ),
    )
    args = parser.parse_args(argv)
    profile = _profile_with(args.parameter)

    documents = load_documents(_resolve(args.corpus))
    years = [document.year for document in documents]
    if not years or args.snapshot_year >= max(years):
        print(
            f"срез {args.snapshot_year} не оставляет будущего для проверки "
            f"(последний год корпуса — {max(years) if years else '—'})",
            file=sys.stderr,
        )
        return 2

    index = MentionIndex(documents)
    domains = [args.domain] if args.domain else sorted(DOMAIN_QUERIES)

    print(
        f"срез на конец {args.snapshot_year}; проверка по {max(years) - args.snapshot_year} годам вперёд"
    )
    print(
        f"документов до среза: {len(_freeze(documents, args.snapshot_year))} из {len(documents)}\n"
    )

    horizon_years = max(years) - args.snapshot_year
    report: dict[str, object] = {
        "methodologyVersion": METHODOLOGY_VERSION,
        "snapshotYear": args.snapshot_year,
        "horizonYears": horizon_years,
        "topN": args.top_n,
        "hitLiftThreshold": DEFAULT_HIT_LIFT,
        "domains": {},
    }
    per_domain: dict[str, dict[str, object]] = {}
    method_hits: list[float] = []
    baseline_hits: list[float] = []
    lead_times: list[float] = []

    for domain in domains:
        method, baseline = _run_domain(
            documents, index, domain, args.snapshot_year, args.top_n, profile
        )
        print(f"  {domain}")
        _print_arm(method, "методология")
        _print_arm(baseline, "по популярности")

        method_hits.append(method.hit_rate)
        baseline_hits.append(baseline.hit_rate)
        if method.median_lead_years is not None:
            lead_times.append(method.median_lead_years)
        per_domain[domain] = {
            "query": DOMAIN_QUERIES[domain],
            "methodology": {
                "hitRate": round(method.hit_rate, 4),
                "medianLift": round(method.median_lift, 4),
                "medianLeadYears": method.median_lead_years,
                "topics": [
                    {
                        "key": o.key,
                        "rank": o.rank,
                        "score": o.score,
                        "documentsBefore": o.documents_before,
                        "documentsAfter": o.documents_after,
                        "lift": o.lift,
                        "doublingYears": o.doubling_years,
                        "hit": o.hit,
                    }
                    for o in method.outcomes
                ],
            },
            "baseline": {
                "hitRate": round(baseline.hit_rate, 4),
                "medianLift": round(baseline.median_lift, 4),
                "medianLeadYears": baseline.median_lead_years,
            },
        }

    overall_method = math.fsum(method_hits) / len(method_hits) if method_hits else 0.0
    overall_baseline = math.fsum(baseline_hits) / len(baseline_hits) if baseline_hits else 0.0
    advantage = overall_method - overall_baseline

    report["domains"] = per_domain
    # `hitRate` at the top level is the contract the nightly workflow reads.
    report["hitRate"] = round(overall_method, 4)
    report["baselineHitRate"] = round(overall_baseline, 4)
    report["advantage"] = round(advantage, 4)
    report["medianLeadYears"] = (
        round(math.fsum(lead_times) / len(lead_times), 2) if lead_times else None
    )

    print(
        f"\nитого: методология {overall_method:.0%}, база по популярности {overall_baseline:.0%}, "
        f"преимущество {advantage:+.0%}"
    )
    if advantage <= 0:
        print(
            "методология не превзошла наивную базу — это и есть результат, "
            "а не повод подкрутить порог"
        )

    # Разброс по срезам. Публиковать один год как «проверку на истории» — переоценка: год
    # выбирается произвольно, а результат от него зависит сильно. Разброс отвечает на очевидный
    # вопрос «а если взять другой год» до того, как его зададут, и потому убеждает сильнее точки.
    extra_years = [int(part) for part in args.also_years.split(",") if part.strip()]
    if extra_years:
        sweep: list[dict[str, object]] = [
            {
                "snapshotYear": args.snapshot_year,
                "hitRate": report["hitRate"],
                "baselineHitRate": report["baselineHitRate"],
            }
        ]
        for year in sorted(set(extra_years) - {args.snapshot_year}):
            sweep.append(
                _sweep_year(documents, index, domains, year, args.top_n, max(years), profile)
            )
        sweep.sort(key=lambda item: int(str(item["snapshotYear"])))
        report["sweep"] = sweep
        print("\nразброс по срезам:")
        for item in sweep:
            print(
                f"  {item['snapshotYear']}: методология {float(str(item['hitRate'])) * 100:.0f}%, "
                f"база {float(str(item['baselineHitRate'])) * 100:.0f}%"
            )

    if args.output:
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"записано: {args.output}")

    if args.markdown:
        args.markdown.write_text(_render_markdown(report), encoding="utf-8")
        print(f"записано: {args.markdown}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
