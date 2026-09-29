"""The six emergence indicators — methodology §3.1 – §3.6.

Every indicator is a small stateless object behind the :class:`Indicator` protocol and
receives one :class:`IndicatorContext`. Each returns an :class:`IndicatorValue` carrying

* the value in ``[0, 1]``,
* ``diagnostics`` — the raw quantities the number was built from,
* ``explanation`` — a Russian sentence containing those same numbers, shown in the UI.

Indicators never look at the clock, never touch a repository and never mutate anything.
"""

from __future__ import annotations

import math
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import ClassVar, Protocol, runtime_checkable

from horizon_analytics.domain.models import (
    CorpusStats,
    Document,
    IndicatorName,
    IndicatorValue,
    SourceClass,
    TimeSeries,
    Topic,
    clamp01,
    safe_div,
)
from horizon_analytics.domain.scoring.profile import MethodologyParameters
from horizon_analytics.domain.vectors import Matrix, cosine_to_centroid

__all__ = [
    "CoherenceIndicator",
    "CooccurrenceIndex",
    "DiffusionIndicator",
    "GrowthIndicator",
    "ImpactIndicator",
    "Indicator",
    "IndicatorContext",
    "LinearFit",
    "NoveltyIndicator",
    "WeaknessIndicator",
    "build_indicators",
    "is_corporate_organization",
    "linear_fit",
]

#: Case-insensitive markers of a corporate affiliation, used only when the ingestion
#: pipeline could not resolve ``organizationType`` itself.
_CORPORATE_MARKERS: tuple[str, ...] = (
    " inc",
    " inc.",
    " llc",
    " ltd",
    " ltd.",
    " limited",
    " corp",
    " corp.",
    " corporation",
    " gmbh",
    " s.a.",
    " sas",
    " b.v.",
    " co.",
    " company",
    " technologies",
    " labs",
    " laboratories inc",
    " ооо",
    " оао",
    " пао",
    " зао",
    " ао ",
    " акционерное общество",
)

_ACADEMIC_MARKERS: tuple[str, ...] = (
    "universit",
    "univ.",
    "institute",
    "institut",
    "college",
    "academy",
    "school",
    "cnrs",
    "университет",
    "институт",
    "академия",
)


def is_corporate_organization(name: str | None, organization_type: str | None) -> bool:
    """Decide whether an affiliation is a company.

    ``organizationType`` from the canonical document model wins whenever present; the name
    heuristic only fills the (common) gap where a source exposes no structured type.
    """
    if organization_type is not None:
        return organization_type == "COMPANY"
    if not name:
        return False
    lowered = f" {name.strip().lower()} "
    if any(marker in lowered for marker in _ACADEMIC_MARKERS):
        return False
    return any(marker in lowered for marker in _CORPORATE_MARKERS)


@dataclass(frozen=True, slots=True)
class CooccurrenceIndex:
    """Document-level co-occurrence counts used by the NPMI part of ``coherence``."""

    total_documents: int
    document_frequency: Mapping[str, int]
    pair_frequency: Mapping[tuple[str, str], int]

    def npmi(self, left: str, right: str) -> float:
        """Normalised pointwise mutual information of two terms, in ``[-1, 1]``.

        Conventions at the boundaries follow Bouma (2009): terms that never co-occur give
        ``-1``; terms that always co-occur give ``+1``.
        """
        if self.total_documents <= 0:
            return 0.0
        key = (left, right) if left <= right else (right, left)
        joint = self.pair_frequency.get(key, 0)
        if joint <= 0:
            return -1.0
        p_left = safe_div(float(self.document_frequency.get(left, 0)), float(self.total_documents))
        p_right = safe_div(
            float(self.document_frequency.get(right, 0)), float(self.total_documents)
        )
        p_joint = joint / self.total_documents
        if p_left <= 0.0 or p_right <= 0.0:
            return -1.0
        if p_joint >= 1.0:
            return 1.0
        pmi = math.log(p_joint / (p_left * p_right))
        return max(-1.0, min(1.0, pmi / (-math.log(p_joint))))

    def pair_count(self, left: str, right: str) -> int:
        """Number of documents containing both terms."""
        key = (left, right) if left <= right else (right, left)
        return self.pair_frequency.get(key, 0)


@dataclass(frozen=True, slots=True)
class IndicatorContext:
    """Everything one topic needs to be scored — assembled once by the engine.

    Keeping this a value object is what lets every indicator be unit-tested in isolation
    with a hand-written context and no infrastructure at all.
    """

    topic: Topic
    series: TimeSeries
    documents: tuple[Document, ...]
    corpus: CorpusStats
    parameters: MethodologyParameters
    first_mention_year: int | None
    term_vectors: Matrix | None = None
    document_vectors: Matrix | None = None
    cooccurrence: CooccurrenceIndex | None = None
    growth_fit: LinearFit | None = None

    def documents_by_source_class(self) -> Mapping[SourceClass, int]:
        """``df(c, s)`` — document counts per source class, in deterministic order."""
        counts: dict[SourceClass, int] = {}
        for document in self.documents:
            counts[document.source_class] = counts.get(document.source_class, 0) + 1
        return dict(sorted(counts.items()))

    def organizations(self) -> tuple[str, ...]:
        """``O(c)`` — sorted distinct organisations across the topic's documents."""
        names: set[str] = set()
        for document in self.documents:
            # Приведённые имена — по той же причине, что и в BRULE-1: широта охвата не должна
            # расти оттого, что одна лаборатория записана двумя способами.
            names.update(document.canonical_organizations)
        return tuple(sorted(names))

    def venues(self) -> tuple[str, ...]:
        """``V(c)`` — sorted distinct venues across the topic's documents."""
        return tuple(sorted({document.venue_key for document in self.documents}))


@runtime_checkable
class Indicator(Protocol):
    """One measurable attribute of a topic, normalised into ``[0, 1]``."""

    name: ClassVar[IndicatorName]

    def compute(self, context: IndicatorContext) -> IndicatorValue:
        """Compute the indicator for one topic."""
        ...


# ───────────────────────────── regression helper (§3.2) ─────────────────────────────


@dataclass(frozen=True, slots=True)
class LinearFit:
    """Result of the ordinary least squares fit ``ln(df + 1) = α + β·t``."""

    slope: float
    intercept: float
    r_squared: float
    points: int
    beta_early: float = 0.0
    beta_recent: float = 0.0

    @property
    def acceleration(self) -> float:
        """``β_recent − β_early`` — methodology §3.2 (в)."""
        return self.beta_recent - self.beta_early


def _ols(xs: Sequence[float], ys: Sequence[float]) -> tuple[float, float, float]:
    """Plain OLS returning ``(slope, intercept, r²)``; degenerate inputs give zeros."""
    count = len(xs)
    if count < 2:
        return 0.0, (ys[0] if ys else 0.0), 0.0
    mean_x = math.fsum(xs) / count
    mean_y = math.fsum(ys) / count
    sxx = math.fsum((x - mean_x) ** 2 for x in xs)
    if sxx <= 0.0:
        return 0.0, mean_y, 0.0
    sxy = math.fsum((x - mean_x) * (y - mean_y) for x, y in zip(xs, ys, strict=True))
    slope = sxy / sxx
    intercept = mean_y - slope * mean_x
    syy = math.fsum((y - mean_y) ** 2 for y in ys)
    if syy <= 0.0:
        # A perfectly flat series: the model explains nothing, and the slope is zero.
        return 0.0, mean_y, 0.0
    residual = math.fsum((y - (intercept + slope * x)) ** 2 for x, y in zip(xs, ys, strict=True))
    r_squared = clamp01(1.0 - residual / syy)
    return slope, intercept, r_squared


def linear_fit(series: TimeSeries, *, relative: bool = False) -> LinearFit:
    """Fit ``ln(df(c,t) + 1) = α + β·t`` over the **complete** periods that carry data (§3.2 а).

    ``t`` is the absolute one-based period index of §2, so gaps in the series widen the
    lever arm exactly as they should: a topic that skipped two years genuinely grew slower.

    Незакончившийся период приводится к годовому масштабу. Включать его как есть — значит
    утверждать замедление там, где до конца года ещё четыре месяца: на живом корпусе из
    одиннадцати тем десять получали «зрелую» стадию, и отчёт о слабых сигналах выходил пустым.
    Выбрасывать его — значит отнимать у зарождающейся темы почти все документы: следующий замер
    на том же корпусе дал ноль тем из сорока семи, все по BRULE-4 с нулевым ростом.

    На эталонном корпусе вопрос не стоит: он заканчивается 31 декабря, доля равна единице, и ряд
    совпадает с наблюдённым до последнего знака.
    """
    xs: list[float] = []
    ys: list[float] = []
    # Неполный последний период приводится к годовому масштабу, а не выбрасывается. Выбросить
    # его — значит отнять у зарождающейся темы почти все её документы: живые источники отдают
    # свежее первым, и у такой темы остаётся одна точка, по которой наклон равен нулю.
    fitted = series.length
    if relative:
        # Доля темы в своём направлении: ``ln(df + 1) − ln(N + 1)``. Годового пересчёта здесь
        # нет и не нужно — числитель и знаменатель взяты за один и тот же отрезок, и неполный
        # год сравним с полными без оценки (параметр ``growth_relative``).
        for index, (value, corpus_value) in enumerate(
            zip(series.df, series.corpus_df, strict=True)
        ):
            if value > 0 and corpus_value > 0:
                xs.append(float(index + 1))
                ys.append(math.log(value + 1.0) - math.log(corpus_value + 1.0))
    else:
        for index, annualised in enumerate(series.annualised_df):
            if annualised > 0:
                xs.append(float(index + 1))
                ys.append(math.log(annualised + 1.0))
    slope, intercept, r_squared = _ols(xs, ys)

    half = fitted // 2
    early_xs = [x for x in xs if x <= half]
    early_ys = [y for x, y in zip(xs, ys, strict=True) if x <= half]
    recent_xs = [x for x in xs if x > fitted - half]
    recent_ys = [y for x, y in zip(xs, ys, strict=True) if x > fitted - half]
    beta_early, _, _ = _ols(early_xs, early_ys)
    beta_recent, _, _ = _ols(recent_xs, recent_ys)

    return LinearFit(
        slope=slope,
        intercept=intercept,
        r_squared=r_squared,
        points=len(xs),
        beta_early=beta_early,
        beta_recent=beta_recent,
    )


def _fmt(value: float, digits: int = 3) -> str:
    """Format a float for a Russian explanation string, without a trailing ``-0``."""
    text = f"{value:.{digits}f}"
    return "0." + "0" * digits if text.startswith("-0.") and float(text) == 0.0 else text


# ───────────────────────────── §3.1 novelty ─────────────────────────────


@dataclass(frozen=True, slots=True)
class NoveltyIndicator:
    """``novelty(c) = exp(−age(c) / τ)``, ``τ = 3.0`` years (methodology §3.1).

    ``first_year`` is supplied by the engine and, per BRULE-2, is the earliest year among
    the documents that passed the credibility rule — a single stray mention never moves it.
    """

    name: ClassVar[IndicatorName] = "novelty"

    def compute(self, context: IndicatorContext) -> IndicatorValue:
        """Compute the novelty of the topic."""
        parameters = context.parameters
        current_year = context.corpus.current_year
        if context.first_mention_year is None:
            return IndicatorValue(
                name=self.name,
                value=0.0,
                diagnostics={
                    "firstYear": 0,
                    "age": 0,
                    "tau": parameters.novelty_tau,
                    "currentYear": current_year,
                    "credible": False,
                },
                explanation=(
                    "Достоверный год первого упоминания не определён "
                    "(правило BRULE-1 не выполнено) — новизна принята равной 0."
                ),
            )
        first_year = context.first_mention_year
        age = max(0, current_year - first_year)
        value = clamp01(math.exp(-age / parameters.novelty_tau))
        return IndicatorValue(
            name=self.name,
            value=value,
            diagnostics={
                "firstYear": first_year,
                "age": age,
                "tau": parameters.novelty_tau,
                "currentYear": current_year,
                "credible": True,
            },
            explanation=(
                f"Первое достоверное упоминание — {first_year} г.; "
                f"возраст темы {age} г. при τ = {_fmt(parameters.novelty_tau, 1)}; "
                f"novelty = exp(−{age}/{_fmt(parameters.novelty_tau, 1)}) = {_fmt(value)}."
            ),
        )


# ───────────────────────────── §3.2 growth ─────────────────────────────


@dataclass(frozen=True, slots=True)
class GrowthIndicator:
    """``growth(c) = clamp01(max(0, β) / ln G_max)``, ``G_max = 3.0`` (methodology §3.2 а).

    ``β`` is the OLS slope of ``ln(df + 1)`` over the periods with data. A decaying topic
    has ``β < 0`` hence ``growth = 0``, which zeroes the whole score through BRULE-4 —
    exactly what the document prescribes.
    """

    name: ClassVar[IndicatorName] = "growth"

    def compute(self, context: IndicatorContext) -> IndicatorValue:
        """Compute the growth of the topic."""
        parameters = context.parameters
        fit = (
            context.growth_fit
            if context.growth_fit is not None
            else linear_fit(context.series, relative=parameters.growth_relative)
        )
        value = clamp01(max(0.0, fit.slope) / parameters.ln_growth_g_max)
        factor = math.exp(fit.slope) if fit.slope < 50.0 else float("inf")
        return IndicatorValue(
            name=self.name,
            value=value,
            diagnostics={
                "slope": fit.slope,
                "r2": fit.r_squared,
                "intercept": fit.intercept,
                "pointsUsed": fit.points,
                "betaEarly": fit.beta_early,
                "betaRecent": fit.beta_recent,
                "acceleration": fit.acceleration,
                "gMax": parameters.growth_g_max,
                "lnGMax": parameters.ln_growth_g_max,
                "periodFactor": factor,
                "relativeToCorpus": parameters.growth_relative,
            },
            explanation=(
                f"Лог-линейный наклон β = {_fmt(fit.slope)} (R² = {_fmt(fit.r_squared)}) "
                f"по {fit.points} периодам с данными: это ≈ ×{_fmt(factor, 2)} "
                + (
                    "доли темы в направлении за период — рост сверх роста самого корпуса"
                    if parameters.growth_relative
                    else "документов за период"
                )
                + f"; growth = {_fmt(max(0.0, fit.slope))}/ln {_fmt(parameters.growth_g_max, 1)} "
                f"= {_fmt(value)}. Ускорение β_поздн − β_ранн = {_fmt(fit.acceleration)}."
            ),
        )


# ───────────────────────────── §3.3 diffusion ─────────────────────────────


@dataclass(frozen=True, slots=True)
class DiffusionIndicator:
    """Breadth of a topic over source classes, organisations and venues (methodology §3.3).

    ``entropy`` is normalised by ``ln |S_active|`` where ``S_active`` are the source classes
    active **in the direction**, not in the candidate. Normalising per candidate would give
    a topic that lives in exactly two classes a perfect entropy of ``1.0``, which is plainly
    wrong; using the direction keeps the denominator constant and the values comparable.
    """

    name: ClassVar[IndicatorName] = "diffusion"

    def compute(self, context: IndicatorContext) -> IndicatorValue:
        """Compute the diffusion of the topic."""
        parameters = context.parameters
        per_class = context.documents_by_source_class()
        total = sum(per_class.values())
        entropy_raw = 0.0
        for count in per_class.values():
            probability = safe_div(float(count), float(total))
            if probability > 0.0:
                entropy_raw -= probability * math.log(probability)
        active = context.corpus.active_source_class_count
        entropy = clamp01(safe_div(entropy_raw, math.log(active))) if active > 1 else 0.0

        organizations = context.organizations()
        venues = context.venues()
        org_breadth = clamp01(
            safe_div(math.log1p(len(organizations)), math.log1p(parameters.diffusion_org_ref))
        )
        venue_breadth = clamp01(
            safe_div(math.log1p(len(venues)), math.log1p(parameters.diffusion_venue_ref))
        )
        value = clamp01(
            parameters.diffusion_entropy_weight * entropy
            + parameters.diffusion_org_weight * org_breadth
            + parameters.diffusion_venue_weight * venue_breadth
        )
        return IndicatorValue(
            name=self.name,
            value=value,
            diagnostics={
                "entropy": entropy,
                "entropyRaw": entropy_raw,
                "sourceClassCount": len(per_class),
                "activeSourceClasses": active,
                "orgCount": len(organizations),
                "venueCount": len(venues),
                "orgBreadth": org_breadth,
                "venueBreadth": venue_breadth,
                "orgRef": parameters.diffusion_org_ref,
                "venueRef": parameters.diffusion_venue_ref,
            },
            explanation=(
                f"Тема встречается в {len(per_class)} из {active} активных классов источников "
                f"(нормированная энтропия {_fmt(entropy)}), у {len(organizations)} организаций "
                f"({_fmt(org_breadth)} от O_ref = {parameters.diffusion_org_ref}) и на "
                f"{len(venues)} площадках ({_fmt(venue_breadth)} от V_ref = "
                f"{parameters.diffusion_venue_ref}); diffusion = {_fmt(value)}."
            ),
        )


# ───────────────────────────── §3.4 weakness ─────────────────────────────


@dataclass(frozen=True, slots=True)
class WeaknessIndicator:
    """``weakness = 1 − clamp01(ln(1+recent) / ln(1+recent_max))`` (methodology §3.4).

    ``recent`` sums the last ``W = 2`` periods; ``recent_max`` is the maximum over the
    candidates of the same direction, floored so that tiny corpora stay stable.
    """

    name: ClassVar[IndicatorName] = "weakness"

    def compute(self, context: IndicatorContext) -> IndicatorValue:
        """Compute the weakness (inverse recent volume) of the topic."""
        parameters = context.parameters
        window = max(1, parameters.weakness_window)
        recent = sum(context.series.df[-window:]) if context.series.length else 0
        recent_max = max(context.corpus.recent_max, parameters.weakness_recent_floor)
        loudness = clamp01(safe_div(math.log1p(recent), math.log1p(recent_max)))
        value = clamp01(1.0 - loudness)
        return IndicatorValue(
            name=self.name,
            value=value,
            diagnostics={
                "recentDocuments": recent,
                "recentMax": recent_max,
                "recentMaxObserved": context.corpus.recent_max,
                "window": window,
                "loudness": loudness,
                "floor": parameters.weakness_recent_floor,
            },
            explanation=(
                f"За последние {window} периода тема набрала {recent} документов при максимуме "
                f"{recent_max} по направлению: громкость {_fmt(loudness)}, "
                f"weakness = 1 − {_fmt(loudness)} = {_fmt(value)}."
            ),
        )


# ───────────────────────────── §3.5 coherence ─────────────────────────────


@dataclass(frozen=True, slots=True)
class CoherenceIndicator:
    """``coherence = 0.60·clamp01(c_emb) + 0.40·clamp01(c_npmi)`` (methodology §3.5).

    ``c_emb`` is the mean cosine of the cluster members to their centroid, or — for a
    single-term topic — the mean cosine of the documents containing it to their centroid.
    ``c_npmi`` is ``(mean NPMI over the top co-occurring pairs + 1) / 2``.
    """

    name: ClassVar[IndicatorName] = "coherence"

    def compute(self, context: IndicatorContext) -> IndicatorValue:
        """Compute the coherence of the topic."""
        parameters = context.parameters
        member_count = len(context.topic.members)

        if member_count > 1 and context.term_vectors is not None:
            c_emb = cosine_to_centroid(context.term_vectors)
            emb_basis = "термины кластера"
        elif context.document_vectors is not None:
            c_emb = cosine_to_centroid(context.document_vectors)
            emb_basis = "документы темы"
        else:
            c_emb = 0.0
            emb_basis = "нет эмбеддингов"

        npmi_values = self._top_pair_npmi(context)
        if npmi_values:
            mean_npmi = math.fsum(npmi_values) / len(npmi_values)
            c_npmi = (mean_npmi + 1.0) / 2.0
        else:
            mean_npmi = 0.0
            c_npmi = parameters.coherence_single_term_npmi

        value = clamp01(
            parameters.coherence_embedding_weight * clamp01(c_emb)
            + parameters.coherence_npmi_weight * clamp01(c_npmi)
        )
        return IndicatorValue(
            name=self.name,
            value=value,
            diagnostics={
                "cEmb": c_emb,
                "cNpmi": c_npmi,
                "meanNpmi": mean_npmi,
                "memberCount": member_count,
                "pairsUsed": len(npmi_values),
                "embeddingBasis": emb_basis,
            },
            explanation=(
                f"Семантическая связность {_fmt(c_emb)} ({emb_basis}, {member_count} терм.), "
                f"лексическая связность (NPMI по {len(npmi_values)} парам) {_fmt(c_npmi)}; "
                f"coherence = 0.60·{_fmt(c_emb)} + 0.40·{_fmt(c_npmi)} = {_fmt(value)}."
            ),
        )

    def _top_pair_npmi(self, context: IndicatorContext) -> tuple[float, ...]:
        """NPMI of the highest co-occurring member pairs, deterministically ordered."""
        index = context.cooccurrence
        members = context.topic.members
        if index is None or len(members) < 2:
            return ()
        keys = sorted(member.key for member in members)
        pairs: list[tuple[int, str, str]] = []
        for position, left in enumerate(keys):
            for right in keys[position + 1 :]:
                pairs.append((index.pair_count(left, right), left, right))
        # Sort by co-occurrence desc, then lexicographically — a total order.
        pairs.sort(key=lambda item: (-item[0], item[1], item[2]))
        top = pairs[: max(1, context.parameters.coherence_top_pairs)]
        return tuple(index.npmi(left, right) for _, left, right in top)


# ───────────────────────────── §3.6 impact ─────────────────────────────


@dataclass(frozen=True, slots=True)
class ImpactIndicator:
    """``impact = 0.45·citationVel + 0.30·patentRatio + 0.25·industry`` (methodology §3.6).

    ``cpy`` is the mean citations-per-year of the topic's documents. The document also asks
    for a *field* normalisation; the canonical document model carries no field baseline
    (that would need an external reference distribution per subject category), so only the
    age normalisation is applied. With ``cpy_ref = 10`` the scale is that of raw
    citations-per-year, which confirms the reading. See the service README for the note.
    """

    name: ClassVar[IndicatorName] = "impact"

    def compute(self, context: IndicatorContext) -> IndicatorValue:
        """Compute the impact potential of the topic."""
        parameters = context.parameters
        documents = context.documents
        total = len(documents)
        current_year = context.corpus.current_year

        rates: list[float] = []
        for document in documents:
            if document.citation_count is None:
                continue
            age_years = max(1, current_year - document.year + 1)
            rates.append(document.citation_count / age_years)
        cpy = math.fsum(rates) / len(rates) if rates else 0.0
        citation_velocity = clamp01(
            safe_div(math.log1p(cpy), math.log1p(parameters.impact_cpy_ref))
        )

        patents = sum(1 for document in documents if document.source_class == "PATENT")
        patent_share = safe_div(float(patents), float(total))
        patent_ratio = clamp01(safe_div(patent_share, parameters.impact_patent_ratio_ref))

        industry_documents = sum(
            1
            for document in documents
            if any(
                is_corporate_organization(author.organization_name, author.organization_type)
                for author in document.authors
            )
        )
        industry_share = safe_div(float(industry_documents), float(total))
        industry = clamp01(safe_div(industry_share, parameters.impact_industry_ref))

        value = clamp01(
            parameters.impact_citation_weight * citation_velocity
            + parameters.impact_patent_weight * patent_ratio
            + parameters.impact_industry_weight * industry
        )
        # Нечем измерять — это не ноль. Корпус, в котором ни у одного документа темы нет числа
        # цитирований, нет ни одного патента и ни одной корпоративной аффилиации, о влиянии темы
        # не сообщает ничего. Три отсутствующих входа, а не три нулевых значения.
        measurable = bool(rates) or patents > 0 or industry_documents > 0
        return IndicatorValue(
            name=self.name,
            value=value,
            measured=measurable,
            diagnostics={
                "measurable": measurable,
                "cpy": cpy,
                "citationVelocity": citation_velocity,
                "documentsWithCitations": len(rates),
                "patentDocuments": patents,
                "patentShare": patent_share,
                "patentRatio": patent_ratio,
                "industryDocuments": industry_documents,
                "industryShare": industry_share,
                "industry": industry,
                "cpyRef": parameters.impact_cpy_ref,
            },
            explanation=(
                f"Средняя цитируемость {_fmt(cpy, 2)} цит./год ({len(rates)} док. с данными) → "
                f"citationVel = {_fmt(citation_velocity)}; патентов {patents}/{total} "
                f"({_fmt(patent_share)}) → patentRatio = {_fmt(patent_ratio)}; "
                f"корпоративная аффилиация у {industry_documents}/{total} ({_fmt(industry_share)}) "
                f"→ industry = {_fmt(industry)}; impact = {_fmt(value)}."
                if measurable
                else (
                    f"Влияние не измерено: ни у одного из {total} документов темы нет числа "
                    "цитирований, среди них нет патентов и нет корпоративной аффилиации. "
                    "Индикатор исключён из свёртки, веса остальных нормированы заново."
                )
            ),
        )


def build_indicators() -> tuple[Indicator, ...]:
    """Instantiate the six indicators in canonical order.

    The order is fixed here rather than derived from a dict so that iteration order can
    never depend on hashing (methodology §9).
    """
    return (
        NoveltyIndicator(),
        GrowthIndicator(),
        DiffusionIndicator(),
        WeaknessIndicator(),
        CoherenceIndicator(),
        ImpactIndicator(),
    )
