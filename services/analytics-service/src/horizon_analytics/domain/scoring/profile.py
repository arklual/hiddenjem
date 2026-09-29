"""Methodology profile — every constant of the methodology in one versioned value object.

The profile is the only place where a number of the methodology may live. It is carried
through the whole run and echoed back in ``DomainAnalyzed`` so that a result can always
be reproduced from ``(snapshotId, profileId, methodologyVersion)`` alone (ADR-0015).

Constants are exactly those of ``docs/03-methodology/01-emergence-methodology.md``:
``τ = 3.0``, ``G_max = 3.0``, ``tw = 0.05``, ``O_ref = 50``, ``V_ref = 25``,
``cpy_ref = 10``, ``W = 2``, ``s = 2.0``, ``θ = 0.25``, clustering distance ``0.35``,
merge cosine ``0.90``, confidence threshold ``0.40``.
"""

from __future__ import annotations

import math
from collections.abc import Mapping
from dataclasses import dataclass, replace
from typing import Any, Final

from horizon_analytics import METHODOLOGY_VERSION
from horizon_analytics.domain.models import (
    INDICATOR_NAMES,
    AggregatorName,
    IndicatorName,
    SourceClass,
)

__all__ = [
    "DEFAULT_PROFILE_ID",
    "DEFAULT_WEIGHTS",
    "MethodologyParameters",
    "MethodologyProfile",
    "ProfileValidationError",
]

#: Stable identifier of the built-in default profile (a UUIDv5-style fixed constant so
#: that golden runs never depend on a generated id).
DEFAULT_PROFILE_ID: Final[str] = "00000000-0000-4000-8000-000000000001"

#: Default indicator weights — methodology §4.
DEFAULT_WEIGHTS: Final[Mapping[IndicatorName, float]] = {
    "novelty": 0.20,
    "growth": 0.30,
    "diffusion": 0.15,
    "weakness": 0.15,
    "coherence": 0.10,
    "impact": 0.10,
}

#: Prior authority of a source class, used when a document carries no citation count
#: (methodology §8, "authority — нормированная цитируемость либо априорный вес класса").
DEFAULT_SOURCE_AUTHORITY: Final[Mapping[SourceClass, float]] = {
    "JOURNAL_ARTICLE": 0.90,
    "STANDARD": 0.85,
    "PATENT": 0.75,
    "ANALYST_REPORT": 0.60,
    "PREPRINT": 0.55,
    "CODE_REPOSITORY": 0.45,
    "NEWS": 0.30,
}


#: Что делать с классом, которого нет в таблице выше.
#:
#: Середина шкалы, а не ноль и не единица: неизвестный класс — это незнание о документе, а не
#: суждение о нём, и наказывать документ за наше незнание так же неверно, как награждать.
#:
#: Строка достижима только при повреждённом входе: проверка `test_every_source_class_has_an_explicit
#: _authority` не даёт завести класс без веса. Оставлена потому, что исключение здесь остановило бы
#: анализ целиком из-за одного документа, а деградация — только его вклад.
UNKNOWN_CLASS_AUTHORITY: Final[float] = 0.5


def prior_authority(source_class: SourceClass) -> float:
    """Априорный вес класса источника (§8) с явным ответом на незнакомый класс.

    Заведена потому, что подстановка была написана трижды — в двух местах нарратора и в отборе
    свидетельств, — и трижды одним и тем же числом без объяснения. Решение одно, значит и место одно.
    """
    # Ключ типизирован, но приходит из внешнего события: тип обещает, данные — нет.
    return DEFAULT_SOURCE_AUTHORITY.get(source_class) or UNKNOWN_CLASS_AUTHORITY


class ProfileValidationError(ValueError):
    """Raised when a methodology profile is internally inconsistent."""


@dataclass(frozen=True, slots=True)
class MethodologyParameters:
    """Numeric parameters of the methodology.

    Field names follow the symbols of the document; the docstring of each group names the
    section it comes from. Nothing here may be hard-coded anywhere else in the codebase.
    """

    # §3.1 novelty
    novelty_tau: float = 3.0
    """``τ`` — decay constant of ``novelty(c) = exp(−age/τ)``, in years."""

    # §3.2 growth
    growth_g_max: float = 3.0
    """``G_max`` — growth factor per period that saturates the indicator (``β = ln 3``)."""

    growth_relative: bool = False
    """Считать рост темы относительно роста всего корпуса направления, а не в штуках.

    Наклон по сырому числу документов измеряет не только тему, но и то, как собран корпус.
    Живые источники отдают свежее первым, и корпус стенда по кибербезопасности 2026-09-18
    выглядел так: 190–270 документов в год за 2021–2025 и 1196 за неполный 2026-й. Любая тема,
    которая просто присутствует в свежих работах, получает крутой наклон. Выгрузка OpenAlex без
    сортировки смещена в обратную сторону — 562 работы за 2019-й и 4 за 2025-й, — и там
    «падают» все темы подряд.

    Включённый флаг считает наклон ``ln(df + 1) − ln(N + 1)``: насколько тема обгоняет своё
    направление. Это та же поправка, которой бэктест давно судит сам себя (прирост темы
    относительно прироста корпуса), и та, что предлагает исследование к кейсу:
    ``r_t = (n_тема,t + α) / (n_область,t + β)``. Незаконченный год при этом не нуждается в
    пересчёте к годовому масштабу — доля не зависит от длины периода.
    """

    yoon_time_weight: float = 0.05
    """``tw`` — time weight of the DoV/DoD decay ``(1 − tw·(n − t))``."""

    # §3.3 diffusion
    diffusion_org_ref: int = 50
    """``O_ref`` — organisation count that saturates ``orgBreadth``."""

    diffusion_venue_ref: int = 25
    """``V_ref`` — venue count that saturates ``venueBreadth``."""

    diffusion_entropy_weight: float = 0.40
    diffusion_org_weight: float = 0.35
    diffusion_venue_weight: float = 0.25

    # §3.4 weakness
    weakness_window: int = 2
    """``W`` — number of trailing periods summed into ``recent(c)``."""

    weakness_recent_floor: int = 5
    """Lower bound on ``recent_max``.

    The document prescribes "с нижней отсечкой, чтобы избежать неустойчивости на малых
    корпусах" without naming the value; ``5`` keeps ``ln(1 + recent_max) ≈ 1.79`` so that
    a two-document topic in a tiny corpus is not declared mainstream.
    """

    # §3.5 coherence
    coherence_embedding_weight: float = 0.60
    coherence_npmi_weight: float = 0.40
    coherence_top_pairs: int = 10
    """Number of highest co-occurrence member pairs entering the mean NPMI."""

    coherence_single_term_npmi: float = 0.5
    """Neutral ``c_npmi`` for a single-member topic, where no pair exists (``(0+1)/2``)."""

    # §3.6 impact
    impact_cpy_ref: float = 10.0
    """``cpy_ref`` — citations per year that saturate ``citationVel``."""

    impact_patent_ratio_ref: float = 0.30
    impact_industry_ref: float = 0.50
    impact_citation_weight: float = 0.45
    impact_patent_weight: float = 0.30
    impact_industry_weight: float = 0.25

    # §3.7 confidence
    confidence_evidence_ref: int = 20
    confidence_diversity_ref: int = 3
    confidence_span_ref: int = 4
    confidence_evidence_weight: float = 0.35
    confidence_diversity_weight: float = 0.25
    confidence_fit_weight: float = 0.20
    confidence_span_weight: float = 0.20

    # §4 aggregation
    score_floor: float = 1e-9
    """``x_k ← max(x_k, 1e-9)`` numerical protection before taking a logarithm."""

    score_decimals: int = 6
    """Scores are rounded to this many decimals *before* sorting (§9)."""

    # §5 burst
    burst_scale: float = 2.0
    """``s`` — a period bursts when ``r(t) ≥ s·λ0``."""

    burst_gamma: float = 1.0
    """``γ`` — Kleinberg state-transition cost.

    Reserved for the full automaton variant named in §5 as a pluggable alternative; the
    simplified two-state detector that the document actually specifies does not use it.
    Kept in the profile so that switching detectors does not change the contract.
    """

    burst_baseline_floor_documents: float = 1.0
    """Documents-per-corpus floor for ``λ0``.

    The document does not define ``burst`` when ``λ0 = 0`` (a topic that only appears in
    the second half of the window). We floor ``λ0`` at the rate of
    ``burst_baseline_floor_documents`` documents over the whole corpus, which is the
    smallest observable non-zero rate, so such topics are correctly reported as bursting.
    """

    # §6 lifecycle
    lifecycle_volume_low_quantile: float = 0.25
    lifecycle_volume_high_quantile: float = 0.60
    lifecycle_slope_emerging: float = 0.20
    lifecycle_slope_accelerating: float = 0.50
    lifecycle_patent_ratio_mature: float = 0.40

    # §7 pipeline
    max_documents: int = 1500
    """``K`` — corpus truncation limit of step 2.

    Умолчание выбрано **по замеру памяти**, а не по желаемой полноте. Подгонка пространства
    эмбеддингов держит матрицу компонент размера ``384 × признаки``, и признаки растут вместе с
    корпусом: 250 документов → 0.56 ГБ пика, 500 → 1.07 ГБ, 1000 → 1.64 ГБ, 1993 → 2.38 ГБ.
    Контейнер ``analytics-worker`` ограничен двумя гигабайтами (`ANALYTICS_WORKER_MEM_LIMIT`), а
    облегчённый профиль стенда рассчитан на 3 ГБ на всё.

    Прежнее умолчание — 5000 — требовало бы около пяти гигабайт и приводило бы к тому, что
    контейнер убивают по памяти на корпусе, который продукт сам же обещает обработать. Отказ по
    OOM выглядит как «анализ завис», и никакой алерт не назовёт причину.

    Поднимать этот предел можно, но вместе с лимитом памяти воркера и в той же правке: числа выше
    дают наклон примерно 1.2 ГБ на тысячу документов."""

    ngram_min: int = 1
    ngram_max: int = 4
    min_document_frequency: int = 2
    min_term_chars: int = 3
    max_term_chars: int = 60
    #: Потолок кандидатов, идущих в кластеризацию (память O(n²)). 5000, а не 3000, по замеру
    #: разбора 103: на 11 288 работах слияние и кластеризация 5000 терминов заняли 9,8 с против
    #: 4,5 с, пик памяти прогона не изменился (7,3 ГБ — его задаёт подгонка SVD, а не матрица
    #: расстояний 5000×5000 в 200 МБ), а выросших терминов в бюджете стало 47 вместо 24.
    max_terms_clustered: int = 5000
    #: Доля бюджета кластеризации, которая отдаётся не по термхуду, а многословным именам с
    #: наибольшим числом независимых коллективов за последние двенадцать месяцев окна. Термхуд не
    #: предсказывает, что вырастет (бэктест 2016 года: AUC 0,49–0,52), и режет имена семейств —
    #: `edge computing` стоял по нему 5255-м при бюджете 3000 (разбор 103). Ноль — прежнее
    #: поведение: весь бюджет по термхуду.
    cluster_budget_recent_share: float = 0.5
    #: Percentile of the termhood distribution a **single-word** candidate must clear to be
    #: considered a technology term at all. C-value already says a unigram is weak evidence of
    #: termhood (log2(1.1) versus log2(2) for a bigram), but the emergence score does not read
    #: termhood — so without this gate ordinary words like "load", "circuit" or a country name
    #: reach the ranking and win on novelty and weakness alone. Multi-word candidates are exempt:
    #: the phrase itself is the evidence.
    unigram_termhood_percentile: float = 0.90
    """Cap on the number of candidates entering clustering (memory guard, O(n²))."""

    cluster_distance_threshold: float = 0.35
    """Average-linkage cosine distance threshold of step 5."""

    merge_cosine_threshold: float = 0.90
    """Cosine similarity above which two candidates are merged as synonyms (BRULE-7)."""

    #: Absolute cosine floor. Default 0 — **disabled**, and deliberately so. The similarity a
    #: lexical embedder produces has no corpus-independent scale: on the reference corpus every
    #: topic, on-direction and off, scores between 0.019 and 0.036, so any fixed cut-off either
    #: admits everything or nothing. A number that cannot be chosen correctly should not pretend
    #: to be a control; the percentile below does the actual work. Kept for deployments that
    #: swap in a transformer embedder, where an absolute floor does become meaningful.
    relevance_threshold: float = 0.0
    #: Share of topics rejected as off-direction, measured against the run's own similarity
    #: distribution. Self-calibrating: the same value behaves sensibly for a narrow direction and
    #: a broad one, and for any embedder.
    relevance_percentile: float = 0.55
    #: Minimum number of topics step 6 must hand on. When the absolute threshold admits fewer —
    #: which happens whenever the query and the corpus do not share a vocabulary, e.g. a Russian
    #: direction over an English corpus — the filter degrades to "keep the best N by similarity"
    #: instead of returning nothing. An empty report reads to the analyst as "nothing is happening
    #: in this field", which is a far worse error than a few off-topic rows the score will sink.
    relevance_min_topics: int = 60
    #: Share of a topic's documents that must be classified under the direction for the topic to
    #: belong to it (used only when the corpus carries subject codes).
    #:
    #: Unlike a cosine threshold, this one has a corpus-independent meaning — it is a proportion,
    #: so 0.5 reads as "most of the literature discussing this term is about the direction" in any
    #: corpus and for any direction. That is why a fixed value is defensible here and is not above.
    #:
    #: The previous rule admitted a topic if *any* single document of it was on-direction, which is
    #: how a report on computer security came to be led by speculative decoding and topological
    #: qubits: an AI topic mentioned once in a security paper cleared the bar, and its score —
    #: computed over all of its documents, most of them off-direction — beat the genuine ones.
    #:
    #: Expressed as a multiple of the corpus base rate rather than as an absolute share, so that a
    #: direction covering a quarter of the corpus and one covering a fiftieth are held to the same
    #: *question* rather than the same number. 2.0 — "twice as concentrated in this direction as
    #: the corpus at large" — is the weakest bar that still says something.
    relevance_direction_lift: float = 2.0
    """``θ`` — minimum cosine between topic centroid and query embedding (step 6)."""

    relevance_share_cap: float = 0.5
    """Потолок порога доли направления: половина размеченных документов темы.

    Удвоение шансов задумано для корпуса, где направление — меньшинство, и там оно ниже половины
    при любой базовой доле до трети. Продукт же собирает корпус **под** направление, и база бывает
    почти единицей: на живом корпусе Edge 0,93, порог по шансам — 0,963. Тема, у которой из двух
    размеченных работ одна в cs.DC, а другая в cs.NI, выбывала как чужая: правило требовало от неё
    чистоты выше, чем у корпуса, собранного специально под направление. Так выбывали 387 тем из
    1060 с долей направления от половины до 0,963 — среди них DNN partitioning (0,90), а раньше
    `satellite edge computing` эталона (разбор 90, 62% при пороге 81%). Разбор 101.

    Когда направление — большинство корпуса, вопрос «концентрированнее ли тема корпуса» перестаёт
    отличать своё от чужого, и честнее спросить прямо: большая ли часть размеченной литературы
    темы в направлении. Там, где база ниже трети, потолок не действует, и правило прежнее.
    """

    relevance_by_odds: bool = True
    """Мерить «концентрация темы выше корпуса» шансами, а не долей.

    Правило шага 6 при предметной разметке: тема принадлежит направлению, если её документы
    размечены им заметно чаще, чем корпус в целом. «Заметно» было записано как удвоение **доли**:
    ``s ≥ 2p``. Для смешанного корпуса, где направление — десятая часть, это разумно. Но продукт
    собирает корпус **под** направление, и базовая доля там высока: 0,44 и 0,53 на двух снапшотах
    стенда по ИИ 2026-09-18. Порог выходил 0,87 и 1,00 — во втором случае проходили только темы, у
    которых размечены направлением все документы до единого, и отчёт схлопнулся с пятнадцати тем до
    одной от того лишь, что OpenAlex начал приносить свои работы.

    Удвоение **шансов** — ``s/(1−s) ≥ 2·p/(1−p)``, то есть ``s ≥ 2p/(1+p)`` — при малой базовой
    доле совпадает с удвоением доли (``2p/(1+p) ≈ 2p``), а при большой не упирается в единицу:
    0,61 и 0,69 на тех же снапшотах. Смысл правила прежний — «заметно концентрированнее корпуса», —
    но он перестаёт зависеть от того, насколько удачно собран корпус.
    """

    min_documents_credible: int = 2
    """BRULE-1: at least this many documents."""

    min_organizations_credible: int = 2
    """BRULE-1: at least this many distinct organizations."""

    min_source_classes_credible: int = 2
    """BRULE-1 soft condition: preferred number of distinct source classes."""

    mainstream_quantile: float = 0.90
    """BRULE-3: topics above this volume quantile of the direction are mainstream."""

    # §8 evidence & narration
    #: До предела контракта события анализа (20): аналитик видит все источники темы, а не восемь
    #: «ключевых» (29.09) — у тем веб-корпуса их обычно меньше двадцати.
    evidence_max_items: int = 20
    evidence_max_per_class: int = 10
    evidence_relevance_weight: float = 0.40
    evidence_recency_weight: float = 0.30
    evidence_authority_weight: float = 0.30
    motivation_max_sentences: int = 2
    motivation_min_term_coverage: float = 0.0
    """Minimum share of topic terms a sentence must contain to be quotable."""

    # termhood (§7 step 3)
    termhood_cvalue_weight: float = 0.60
    termhood_yake_weight: float = 0.40

    def validate(self) -> None:
        """Raise :class:`ProfileValidationError` when a parameter is out of range."""
        positives: tuple[tuple[str, float], ...] = (
            ("novelty_tau", self.novelty_tau),
            ("growth_g_max", self.growth_g_max - 1.0),
            ("diffusion_org_ref", float(self.diffusion_org_ref)),
            ("diffusion_venue_ref", float(self.diffusion_venue_ref)),
            ("impact_cpy_ref", self.impact_cpy_ref),
            ("confidence_evidence_ref", float(self.confidence_evidence_ref)),
            ("confidence_diversity_ref", float(self.confidence_diversity_ref)),
            ("confidence_span_ref", float(self.confidence_span_ref)),
            ("weakness_window", float(self.weakness_window)),
            ("burst_scale", self.burst_scale),
            ("score_floor", self.score_floor),
        )
        for name, value in positives:
            if value <= 0.0:
                raise ProfileValidationError(f"parameter {name} must be positive, got {value}")
        if not 0.0 <= self.yoon_time_weight < 1.0:
            raise ProfileValidationError("yoon_time_weight must lie in [0, 1)")
        if not 0.0 <= self.relevance_threshold <= 1.0:
            raise ProfileValidationError("relevance_threshold must lie in [0, 1]")
        if not 0.0 < self.relevance_share_cap <= 1.0:
            raise ProfileValidationError("relevance_share_cap must lie in (0, 1]")
        if self.relevance_direction_lift <= 0.0:
            raise ProfileValidationError("relevance_direction_lift must be positive")
        if not 0.0 <= self.relevance_percentile < 1.0:
            raise ProfileValidationError("relevance_percentile must lie in [0, 1)")
        if not 1 <= self.relevance_min_topics <= 1000:
            raise ProfileValidationError("relevance_min_topics must lie in [1, 1000]")
        if not 0.0 < self.cluster_distance_threshold <= 2.0:
            raise ProfileValidationError("cluster_distance_threshold must lie in (0, 2]")
        if not 0.0 <= self.merge_cosine_threshold <= 1.0:
            raise ProfileValidationError("merge_cosine_threshold must lie in [0, 1]")
        # Hard ceilings, not just orderings. These three values arrive from the AnalyzeDomain
        # command, and clustering builds a dense n×n float64 matrix — an unbounded
        # `max_terms_clustered` is a one-line out-of-memory kill for the worker, followed by an
        # uncommitted offset and a replay loop.
        if not 0.0 <= self.unigram_termhood_percentile < 1.0:
            raise ProfileValidationError("unigram_termhood_percentile must lie in [0, 1)")
        if not 0.0 <= self.cluster_budget_recent_share <= 1.0:
            raise ProfileValidationError("cluster_budget_recent_share must lie in [0, 1]")
        if not 1 <= self.max_terms_clustered <= 5000:
            raise ProfileValidationError("max_terms_clustered must lie in [1, 5000]")
        if not 1 <= self.max_documents <= 20000:
            raise ProfileValidationError("max_documents must lie in [1, 20000]")
        if not 1 <= self.ngram_max <= 8:
            raise ProfileValidationError("ngram_max must lie in [1, 8]")
        if self.ngram_min < 1 or self.ngram_max < self.ngram_min:
            raise ProfileValidationError("invalid n-gram range")
        _check_convex(
            "diffusion",
            (
                self.diffusion_entropy_weight,
                self.diffusion_org_weight,
                self.diffusion_venue_weight,
            ),
        )
        _check_convex("coherence", (self.coherence_embedding_weight, self.coherence_npmi_weight))
        _check_convex(
            "impact",
            (self.impact_citation_weight, self.impact_patent_weight, self.impact_industry_weight),
        )
        _check_convex(
            "confidence",
            (
                self.confidence_evidence_weight,
                self.confidence_diversity_weight,
                self.confidence_fit_weight,
                self.confidence_span_weight,
            ),
        )
        _check_convex(
            "evidence",
            (
                self.evidence_relevance_weight,
                self.evidence_recency_weight,
                self.evidence_authority_weight,
            ),
        )
        _check_convex("termhood", (self.termhood_cvalue_weight, self.termhood_yake_weight))

    @property
    def ln_growth_g_max(self) -> float:
        """``ln(G_max)`` — denominator of the growth indicator."""
        return math.log(self.growth_g_max)

    def with_overrides(self, overrides: Mapping[str, Any]) -> MethodologyParameters:
        """Return a copy with the given fields replaced; unknown keys are ignored.

        Unknown keys are tolerated on purpose: ``profile.parameters`` in the command is an
        open object, and a newer orchestrator must not be able to crash an older engine.

        Имена приходят в двух видах, и оба обязаны работать. Профиль в базе оркестратора заведён
        миграцией ``V2__seed_default_methodology_profile.sql`` и хранит ключи вида ``orgRef``;
        поля движка называются по методологии — ``diffusion_org_ref``. Пока перевода не было,
        **ни один** параметр профиля до движка не доходил: все десять ключей считались
        неизвестными и молча отбрасывались. Совпадение чисел в сиде и в умолчаниях скрывало это
        полностью — настройка профиля просто ничего не меняла.
        """
        known: dict[str, Any] = {}
        for key, value in sorted(overrides.items()):
            field_name = _PARAMETER_ALIASES.get(key, key)
            if field_name in _PARAMETER_FIELDS:
                known[field_name] = value
        if not known:
            return self
        return replace(self, **known)


def _check_convex(group: str, weights: tuple[float, ...]) -> None:
    """Verify that a group of sub-weights is non-negative and sums to one."""
    for weight in weights:
        if weight < 0.0:
            raise ProfileValidationError(f"{group}: negative sub-weight {weight}")
    total = math.fsum(weights)
    if abs(total - 1.0) > 1e-9:
        raise ProfileValidationError(f"{group}: sub-weights must sum to 1.0, got {total}")


_PARAMETER_FIELDS: Final[frozenset[str]] = frozenset(
    MethodologyParameters.__dataclass_fields__.keys()
)

#: Имена параметров оркестратора → поля движка.
#:
#: Оркестратор хранит профиль методологии как JSON с ключами в lowerCamelCase (миграция
#: ``V2__seed_default_methodology_profile.sql``), движок называет поля по формулам методологии.
#: Открытый объект ``profile.parameters`` в контракте позволяет обоим быть правыми, но перевод
#: между ними должен где-то существовать — иначе профиль настраивается вхолостую.
_PARAMETER_ALIASES: Final[Mapping[str, str]] = {
    "tau": "novelty_tau",
    "growthMax": "growth_g_max",
    "growthRelative": "growth_relative",
    "relevanceByOdds": "relevance_by_odds",
    "relevanceShareCap": "relevance_share_cap",
    "timeWeight": "yoon_time_weight",
    "orgRef": "diffusion_org_ref",
    "venueRef": "diffusion_venue_ref",
    "citationsPerYearRef": "impact_cpy_ref",
    "burstThreshold": "burst_scale",
    "minDocuments": "min_documents_credible",
    "minOrganizations": "min_organizations_credible",
    # Предел усечения корпуса K. Имя нарочно не совпадает с `maxDocuments` команды сбора: та
    # ограничивает, сколько документов **собрать**, эта — сколько **прочитать при анализе**, и
    # числа у них разные (5000 против 1500). Одно имя на две величины путало бы оператора там,
    # где ошибка стоит либо неполноты корпуса, либо убийства воркера по памяти.
    "maxDocumentsAnalyzed": "max_documents",
}

#: Параметры оркестратора, которых у методологии больше нет.
#:
#: ``relevanceThreshold`` — абсолютный порог близости к направлению. Движок считает отбор по
#: перцентилю собственного распределения прогона (``relevance_percentile``): один и тот же
#: абсолютный порог ведёт себя по-разному для узкого и широкого направления и для другого
#: векторизатора, а доля — одинаково. Подставлять сидовое 0.25 в поле, которое умышленно
#: выключено нулём, значило бы вернуть правило, от которого методология отказалась.
RETIRED_PARAMETERS: Final[frozenset[str]] = frozenset({"relevanceThreshold"})


def unmapped_parameters(overrides: Mapping[str, Any]) -> tuple[str, ...]:
    """Ключи, которые движок не применит: ни поле, ни известный псевдоним, ни отменённый.

    Существует ради того, чтобы «неизвестный ключ молча игнорируется» перестало быть невидимым:
    прикладной слой о таких ключах предупреждает в журнал. Именно эта невидимость и позволила
    десяти ключам профиля годами не доходить до движка.
    """
    return tuple(
        key
        for key in sorted(overrides)
        if key not in RETIRED_PARAMETERS
        and _PARAMETER_ALIASES.get(key, key) not in _PARAMETER_FIELDS
    )


@dataclass(frozen=True, slots=True)
class MethodologyProfile:
    """Versioned bundle of weights, parameters and the chosen aggregator."""

    profile_id: str
    methodology_version: str
    aggregator: AggregatorName
    weights: Mapping[IndicatorName, float]
    parameters: MethodologyParameters
    confidence_threshold: float

    def __post_init__(self) -> None:
        """Validate the profile eagerly — an invalid profile must never reach scoring."""
        self.validate()

    def validate(self) -> None:
        """Raise :class:`ProfileValidationError` when weights or parameters are invalid."""
        missing = [name for name in INDICATOR_NAMES if name not in self.weights]
        if missing:
            raise ProfileValidationError(f"missing indicator weights: {', '.join(missing)}")
        unknown = [name for name in sorted(self.weights) if name not in INDICATOR_NAMES]
        if unknown:
            raise ProfileValidationError(f"unknown indicator weights: {', '.join(unknown)}")
        for name in INDICATOR_NAMES:
            weight = self.weights[name]
            if not 0.0 <= weight <= 1.0:
                raise ProfileValidationError(f"weight {name} out of [0, 1]: {weight}")
        total = math.fsum(self.weights[name] for name in INDICATOR_NAMES)
        if abs(total - 1.0) > 1e-9:
            raise ProfileValidationError(f"indicator weights must sum to 1.0, got {total}")
        if not 0.0 <= self.confidence_threshold <= 1.0:
            raise ProfileValidationError(
                f"confidenceThreshold out of [0, 1]: {self.confidence_threshold}"
            )
        self.parameters.validate()

    def weight(self, name: IndicatorName) -> float:
        """Weight of one indicator."""
        return self.weights[name]

    @classmethod
    def default(cls) -> MethodologyProfile:
        """The built-in ``em-1.0.0`` profile with the documented default weights."""
        return cls(
            profile_id=DEFAULT_PROFILE_ID,
            methodology_version=METHODOLOGY_VERSION,
            aggregator="WEIGHTED_GEOMETRIC",
            weights=dict(DEFAULT_WEIGHTS),
            parameters=MethodologyParameters(),
            confidence_threshold=0.40,
        )
