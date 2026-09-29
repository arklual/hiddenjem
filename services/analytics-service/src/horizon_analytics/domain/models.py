"""Core value objects of the emergence-detection domain.

Every structure here is frozen and hashable-by-value where possible: the pipeline is a
pure function of its inputs, and immutability is what makes the determinism guarantee of
ADR-0015 checkable rather than aspirational.

Ordering conventions (load bearing for determinism, see methodology §9):

* every collection stored on a model is a ``tuple`` in a defined order;
* ``document_ids`` are sorted ascending;
* candidates/topics are ordered by their explicit sort keys, never by dict insertion.
"""

from __future__ import annotations

import math
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass, field
from datetime import date, datetime
from typing import Final, Literal

__all__ = [
    "AggregatorName",
    "AnalysisParams",
    "Author",
    "Burst",
    "CaseBasis",
    "CaseExample",
    "CorpusStats",
    "Credibility",
    "Document",
    "DocumentTopic",
    "EmergenceResult",
    "Evidence",
    "IndicatorName",
    "IndicatorValue",
    "LifecycleStage",
    "Motivation",
    "MotivationAttribution",
    "OrganizationType",
    "Posting",
    "ScoredIndicator",
    "SourceClass",
    "TermCandidate",
    "TimeSeries",
    "Topic",
    "Venue",
    "canonical_organization",
    "clamp01",
    "safe_div",
]

SourceClass = Literal[
    "PREPRINT",
    "JOURNAL_ARTICLE",
    "PATENT",
    "CODE_REPOSITORY",
    "NEWS",
    "ANALYST_REPORT",
    "STANDARD",
]

OrganizationType = Literal[
    "COMPANY",
    "UNIVERSITY",
    "RESEARCH_INSTITUTE",
    "GOVERNMENT",
    "NONPROFIT",
]

#: Какое из трёх правил §8 подобрало кейс-пример.
#:
#: Значения перечисляют правила один в один, а не пересказывают их. Различие несёт разный вес
#: доказательства и потому обязано доходить до читателя: патент означает, что организация закрепила
#: право на применение, публикация — что она работает над темой, и внедрение из второго не следует.
CaseBasis = Literal["PATENT", "CORPORATE_PUBLICATION", "ACADEMIC_GROUP"]

LifecycleStage = Literal["EMBRYONIC", "EMERGING", "ACCELERATING", "MATURING"]

#: Уровень доверенности источника — требование ТЗ к отображению каждого источника.
#:
#: Живёт здесь, а не в ``domain.credibility``, по той же причине, по которой здесь живут
#: остальные перечисления: его носит :class:`Evidence`, а модуль правил, определяющий уровень,
#: импортирует модели, а не наоборот.
Credibility = Literal["HIGH", "MEDIUM", "LOW"]

IndicatorName = Literal["novelty", "growth", "diffusion", "weakness", "coherence", "impact"]

AggregatorName = Literal["WEIGHTED_GEOMETRIC", "WEIGHTED_ARITHMETIC", "MIN_BOUND"]

#: All source classes of the canonical document model, in schema order.
SOURCE_CLASSES: Final[tuple[SourceClass, ...]] = (
    "PREPRINT",
    "JOURNAL_ARTICLE",
    "PATENT",
    "CODE_REPOSITORY",
    "NEWS",
    "ANALYST_REPORT",
    "STANDARD",
)

#: Indicator names in canonical (deterministic) iteration order.
INDICATOR_NAMES: Final[tuple[IndicatorName, ...]] = (
    "novelty",
    "growth",
    "diffusion",
    "weakness",
    "coherence",
    "impact",
)

#: Human readable Russian labels used inside explanations.
INDICATOR_LABELS_RU: Final[Mapping[IndicatorName, str]] = {
    "novelty": "новизна",
    "growth": "рост",
    "diffusion": "диффузия",
    "weakness": "слабость сигнала",
    "coherence": "связность",
    "impact": "потенциал влияния",
}


def clamp01(value: float) -> float:
    """Clamp ``value`` into ``[0, 1]``; ``NaN`` maps to ``0.0``.

    Every indicator in the methodology is normalised to ``[0, 1]`` (§2), so this is the
    single choke point that enforces the invariant.
    """
    if math.isnan(value):
        return 0.0
    if value <= 0.0:
        return 0.0
    if value >= 1.0:
        return 1.0
    return value


def safe_div(numerator: float, denominator: float, default: float = 0.0) -> float:
    """Divide, returning ``default`` when the denominator vanishes or is not finite."""
    if denominator == 0.0 or not math.isfinite(denominator):
        return default
    result = numerator / denominator
    if not math.isfinite(result):
        return default
    return result


#: Юридические окончания названий, ничего не говорящие о независимости организации.
#:
#: Список намеренно короток и содержит **только** формы собственности. Слова «university»,
#: «institute», «lab», «research» сюда не входят и не войдут: они различают настоящие организации, и
#: их удаление слило бы «Институт физики» с «Институтом химии» — ошибка в ту сторону, где правило
#: становится неверным, а не строгим.
_LEGAL_SUFFIXES: Final[frozenset[str]] = frozenset(
    {
        "inc",
        "ltd",
        "llc",
        "llp",
        "plc",
        "gmbh",
        "ag",
        "sa",
        "sas",
        "bv",
        "nv",
        "oy",
        "ab",
        "as",
        "spa",
        "srl",
        "pte",
        "pty",
        "corp",
        "corporation",
        "co",
        "company",
        "ооо",
        "оао",
        "зао",
        "пао",
        "ао",
    }
)


def canonical_organization(name: str) -> str:
    """Приведённое имя организации — для счёта различных организаций.

    Правило одностороннее: оно **сливает** написания и никогда не разделяет. Это существенно.
    Слияние двух разных организаций сделало бы BRULE-1 строже, чем нужно, и стоило бы темы;
    разделение одной организации на две сделало бы правило мягче, чем оно обещает, и пропустило бы
    тему, подтверждённую одной лабораторией, — а против этого правило и написано.

    Приводится: регистр, пробелы, пунктуация и юридическое окончание. Больше ничего: всякое
    домысливание («MIT» = «Massachusetts Institute of Technology») требует справочника, которого у
    продукта нет, и без него превращается в угадывание.
    """
    lowered = name.casefold().replace("&", " and ")
    words = [
        word for word in "".join(ch if ch.isalnum() else " " for ch in lowered).split() if word
    ]
    # С обеих сторон: в английском форма собственности стоит в конце («Acme Corp»), в русском —
    # впереди («ООО «Ромашка»»), и односторонняя обрезка не свела бы «ООО Ромашка» с «Ромашка ООО».
    while words and words[-1] in _LEGAL_SUFFIXES:
        words.pop()
    while words and words[0] in _LEGAL_SUFFIXES:
        words.pop(0)
    return " ".join(words)


# ─────────────────────────────── documents ───────────────────────────────


@dataclass(frozen=True, slots=True)
class Author:
    """One author of a document, together with the affiliation we could resolve."""

    full_name: str
    orcid: str | None = None
    organization_name: str | None = None
    organization_type: OrganizationType | None = None
    organization_country: str | None = None


@dataclass(frozen=True, slots=True)
class Venue:
    """Publication venue: journal, conference, patent office classification, repository."""

    name: str
    type: str | None = None
    issn: str | None = None


@dataclass(frozen=True, slots=True)
class DocumentTopic:
    """External subject classification attached to a document by the source."""

    code: str
    label: str | None = None
    score: float | None = None


@dataclass(frozen=True, slots=True)
class Document:
    """Canonical document — the only document shape the analysis ever sees.

    Mirrors ``contracts/schemas/document-ingested.event.json`` one-to-one, plus the
    snapshot-local ``relevance`` coming from ``analytics.snapshot_documents``.
    """

    document_id: str
    source_id: str
    source_class: SourceClass
    external_id: str
    title: str
    published_on: date
    url: str
    fetched_at: datetime
    abstract_text: str | None = None
    language: str | None = None
    doi: str | None = None
    arxiv_id: str | None = None
    patent_number: str | None = None
    venue: Venue | None = None
    authors: tuple[Author, ...] = ()
    topics: tuple[DocumentTopic, ...] = ()
    citation_count: int | None = None
    extra_metrics: Mapping[str, float] = field(default_factory=dict)
    dedup_key: str = ""
    relevance: float = 1.0

    @property
    def year(self) -> int:
        """Publication year — the default period granularity of the methodology (§2)."""
        return self.published_on.year

    @property
    def text(self) -> str:
        """Concatenation of title and abstract used for term extraction and embeddings."""
        if self.abstract_text:
            return f"{self.title}. {self.abstract_text}"
        return self.title

    @property
    def organizations(self) -> tuple[str, ...]:
        """Distinct, sorted, non-empty affiliation names of this document, как они записаны.

        Для **счёта** различных организаций берётся :attr:`canonical_organizations`: BRULE-1 и
        диффузия спрашивают «сколько независимых организаций», а на этот вопрос написание отвечать
        не должно.
        """
        names = {
            author.organization_name.strip()
            for author in self.authors
            if author.organization_name and author.organization_name.strip()
        }
        return tuple(sorted(names))

    @property
    def canonical_organizations(self) -> tuple[str, ...]:
        """Организации документа в приведённом виде — для счёта, а не для показа.

        BRULE-1 требует «не меньше двух **различных** организаций», и смысл требования —
        независимость свидетельств. Сырая аффилиация этого не даёт: «Acme Corp» и «Acme Corp.» —
        одна лаборатория и две строки, а правило, которое их различает, проходит на работах одной
        группы. То же у диффузии: широта охвата организаций считается по тому же множеству.

        Эталонный корпус синтетический, и расхождений написания в нём нет ни одного — 199 имён без
        единого совпадения. Проверить это на нём нельзя; поэтому правило проверяется построенными
        случаями, а здесь записано, что замер к ним не относится.
        """
        return tuple(sorted({canonical_organization(name) for name in self.organizations} - {""}))

    @property
    def venue_key(self) -> str:
        """Stable venue identity for the ``venueBreadth`` term of ``diffusion`` (§3.3).

        Falls back to the source id so that documents without a venue (news, repos) still
        contribute one — and only one — distinct venue per source.
        """
        if self.venue is not None and self.venue.name.strip():
            return self.venue.name.strip()
        return f"source:{self.source_id}"


# ─────────────────────────────── candidates & topics ───────────────────────────────


@dataclass(frozen=True, slots=True)
class Posting:
    """Occurrences of one candidate inside one document."""

    document_id: str
    occurrences: int


@dataclass(frozen=True, slots=True)
class TermCandidate:
    """A normalised term candidate with its postings and termhood score.

    ``key`` is the normalised (stemmed) form and is the identity of the candidate; it is
    also the deterministic tie-break key of the whole pipeline (methodology §9).
    """

    key: str
    surface: str
    surface_forms: tuple[str, ...]
    postings: tuple[Posting, ...]
    termhood: float
    is_acronym: bool = False
    token_count: int = 1

    @property
    def document_ids(self) -> tuple[str, ...]:
        """Sorted ids of documents containing this candidate."""
        return tuple(sorted(posting.document_id for posting in self.postings))

    @property
    def document_frequency(self) -> int:
        """``df(c)`` — number of distinct documents containing the candidate."""
        return len(self.postings)

    @property
    def term_frequency(self) -> int:
        """``tf(c)`` — total number of occurrences across the corpus."""
        return sum(posting.occurrences for posting in self.postings)


@dataclass(frozen=True, slots=True)
class Topic:
    """A cluster of merged term candidates — the unit that gets scored and ranked."""

    key: str
    label: str
    members: tuple[TermCandidate, ...]
    aliases: tuple[str, ...] = ()
    #: Описание предложенной темы: сборщик веб-корпуса или панель экспертов написали его по тем
    #: самым страницам, что стали её доказательствами (разбор 110). У корпусных тем — ``None``.
    description: str | None = None

    @property
    def document_ids(self) -> tuple[str, ...]:
        """Sorted union of the document ids of all members."""
        ids: set[str] = set()
        for member in self.members:
            ids.update(member.document_ids)
        return tuple(sorted(ids))

    @property
    def document_frequency(self) -> int:
        """``df(c)`` of the topic — distinct documents mentioning any member."""
        return len(self.document_ids)

    @property
    def term_frequency(self) -> int:
        """``tf(c)`` of the topic — total occurrences of all members."""
        return sum(member.term_frequency for member in self.members)

    def occurrences_by_document(self) -> Mapping[str, int]:
        """Per-document occurrence counts, summed over members."""
        counts: dict[str, int] = {}
        for member in self.members:
            for posting in member.postings:
                counts[posting.document_id] = (
                    counts.get(posting.document_id, 0) + posting.occurrences
                )
        return dict(sorted(counts.items()))


# ─────────────────────────────── series ───────────────────────────────


@dataclass(frozen=True, slots=True)
class TimeSeries:
    """Per-period document/occurrence counts for one topic, aligned with the corpus.

    ``periods[i]`` is the label of period ``t = i + 1`` in the notation of §2, so index
    ``i`` and methodology index ``t`` differ by one everywhere in this codebase.
    """

    periods: tuple[str, ...]
    df: tuple[int, ...]
    tf: tuple[int, ...]
    corpus_df: tuple[int, ...]
    #: Какая доля последнего периода прошла к моменту анализа: ``1.0`` — период закончился.
    #:
    #: Меньше единицы означает «период идёт прямо сейчас»: анализ 18 сентября видит от 2026 года
    #: семьдесят два процента. Сравнивать неполный период с полными нельзя — в регрессии он тянет
    #: наклон вниз просто потому, что короче, и тема, растущая весь год, получает стадию
    #: «зрелая». Замер на живом корпусе: из одиннадцати тем, доживших до ранжирования, десять
    #: получили `MATURING`, и отчёт о слабых сигналах оказался пуст.
    #:
    #: Выбрасывать такой период тоже нельзя, и это выяснилось следующим замером: живые источники
    #: отдают в первую очередь свежее, поэтому у зарождающейся темы почти все документы лежат
    #: как раз в текущем и прошлом году. Без текущего у неё остаётся одна точка, наклон по одной
    #: точке равен нулю, и BRULE-4 обнуляет балл — сорок семь тем из сорока семи.
    #:
    #: Поэтому период не выбрасывается, а приводится к годовому масштабу: ``df / доля``. Это
    #: оценка, а не наблюдение, и она названа оценкой в диагностике индикатора.
    #:
    #: ``1.0`` по умолчанию: эталонный корпус заканчивается 31 декабря, и до появления живых
    #: данных вопрос не стоял.
    last_period_share: float = 1.0

    def __post_init__(self) -> None:
        """Validate that all four series are aligned."""
        length = len(self.periods)
        if not (len(self.df) == len(self.tf) == len(self.corpus_df) == length):
            raise ValueError("TimeSeries components must have identical length")

    @property
    def annualised_df(self) -> tuple[float, ...]:
        """Ряд документов, приведённый к годовому масштабу.

        Отличается от :attr:`df` только последним значением и только когда период не закончился.
        Нужен регрессии: наклон — утверждение о скорости, а скорость по восьми месяцам,
        сравненная со скоростью по двенадцати, занижена ровно на треть.
        """
        if self.last_period_share >= 1.0 or not self.df:
            return tuple(float(value) for value in self.df)
        share = max(self.last_period_share, 0.05)
        return tuple(
            float(value) if index < self.length - 1 else value / share
            for index, value in enumerate(self.df)
        )

    @property
    def length(self) -> int:
        """``n`` — the number of periods in the window."""
        return len(self.periods)

    @property
    def total_df(self) -> int:
        """Total document frequency across the window."""
        return sum(self.df)

    def rate(self, index: int) -> float:
        """``r(t) = df(c,t) / N(t)`` — normalised presence in period ``index`` (0-based)."""
        return safe_div(float(self.df[index]), float(self.corpus_df[index]))

    def periods_with_data(self) -> int:
        """Number of periods where the topic occurs at least once."""
        return sum(1 for value in self.df if value > 0)


# ─────────────────────────────── corpus level statistics ───────────────────────────────


@dataclass(frozen=True, slots=True)
class CorpusStats:
    """Direction-level aggregates shared by all candidates of one analysis run.

    These are the quantities the methodology defines *relative to the direction* rather
    than to the candidate: ``N(t)``, the set of active source classes, ``recent_max`` for
    ``weakness`` (§3.4) and the volume quantiles for the lifecycle stage (§6).
    """

    periods: tuple[str, ...]
    documents_per_period: tuple[int, ...]
    active_source_classes: tuple[SourceClass, ...]
    total_documents: int
    recent_max: int
    volume_q25: float
    volume_q60: float
    mainstream_threshold: float
    current_year: int

    @property
    def active_source_class_count(self) -> int:
        """``|S_active|`` — normalisation base of the diffusion entropy (§3.3)."""
        return len(self.active_source_classes)


# ─────────────────────────────── indicators & results ───────────────────────────────


@dataclass(frozen=True, slots=True)
class IndicatorValue:
    """Output of a single indicator: the value, its raw diagnostics and an explanation.

    ``diagnostics`` carries the raw quantities the UI needs in order to justify the number
    (slope, r2, org count, entropy, patent ratio…). ``explanation`` is a Russian sentence
    containing those same numbers, ready to be shown to an analyst.
    """

    name: IndicatorName
    value: float
    diagnostics: Mapping[str, float | int | str | bool]
    explanation: str
    #: Было ли чем измерять индикатор в этом корпусе.
    #:
    #: ``False`` означает «нечем», а не «ноль», и различие несущее. BRULE-4 обнуляет балл при
    #: нулевом индикаторе — и это правильно, когда ноль измерен: тема без новизны не может
    #: добрать балл ростом. Но корпус, в котором ни у одного документа нет числа цитирований, ни
    #: одного патента и ни одной корпоративной аффилиации, не сообщает о влиянии темы ничего, и
    #: обнулять её балл за это значит наказывать тему за состав источников.
    #:
    #: Замер, на котором это вскрылось: живой корпус по кибербезопасности — 3363 документа из
    #: arXiv, Crossref и GitHub. Сорок четыре темы из сорока семи получили ``impact = 0`` и
    #: выбыли по BRULE-4; отчёт вышел пустым. Неизмеренный индикатор исключается из свёртки, а
    #: веса остальных нормируются заново.
    measured: bool = True

    def __post_init__(self) -> None:
        """Enforce the ``[0, 1]`` invariant of every indicator."""
        if not 0.0 <= self.value <= 1.0:
            raise ValueError(f"indicator {self.name} out of range: {self.value}")


@dataclass(frozen=True, slots=True)
class ScoredIndicator:
    """An :class:`IndicatorValue` enriched with its aggregation decomposition.

    ``multiplier`` is ``value ** weight`` and ``shortfall_share`` is the normalised
    ``-w·ln(x)`` contribution — "who pulls the score down hardest" (methodology §4).
    """

    name: IndicatorName
    value: float
    weight: float
    multiplier: float
    shortfall_share: float
    diagnostics: Mapping[str, float | int | str | bool]
    explanation: str
    #: Было ли чем измерять индикатор. ``False`` — он исключён из свёртки, а его вес
    #: перераспределён между остальными; в карточке он показывается как «не измерен», а не как
    #: ноль. Прятать его нельзя: пропавшая строка читается как «всё в порядке».
    measured: bool = True


@dataclass(frozen=True, slots=True)
class Burst:
    """Detected burst — start period label and accumulated weight (§5)."""

    start_period: str
    weight: float


@dataclass(frozen=True, slots=True)
class Evidence:
    """One supporting document of a trend, as published in ``DomainAnalyzed``."""

    source_id: str
    source_class: SourceClass
    title: str
    published_on: date
    url: str
    relevance: float
    external_id: str | None = None
    document_id: str | None = None
    authors: str | None = None
    organization: str | None = None
    organization_country: str | None = None
    doi: str | None = None
    citation_count: int | None = None
    snippet: str | None = None
    #: Язык оригинала (ISO 639-1). ТЗ требует показывать его у каждого источника: русскоязычное
    #: резюме зарубежного материала имеет смысл только вместе с указанием, с какого языка оно.
    language: str | None = None
    #: Уровень доверенности по правилам ``domain.credibility``.
    credibility: Credibility = "MEDIUM"
    #: Правило, которое присвоило уровень, словами. Ярлык без основания читатель проверить не
    #: может, а ТЗ просит показывать «уровень доверенности либо критерии его определения».
    credibility_basis: str = ""
    #: Является ли документ самостоятельным свидетельством. ``False`` у перепечаток пресс-релизов
    #: и личных площадок: десять сайтов с одним релизом — это один источник, а не десять.
    independent: bool = True


@dataclass(frozen=True, slots=True)
class MotivationAttribution:
    """Link between a motivation statement and the evidence item it was taken from.

    ``sentence`` — то самое предложение, а не весь абзац. Формулировка склеивается из нескольких
    предложений разных статей, и без этого поля читателю достаётся абзац со списком ссылок в конце:
    какая половина чья, восстановить нельзя. Замер по эталонному корпусу: 166 формулировок из 180
    склеены из разных документов.

    ``None`` — атрибуция, записанная до появления поля.
    """

    statement: Literal["problem", "benefit"]
    evidence_index: int
    sentence: str | None = None


@dataclass(frozen=True, slots=True)
class Motivation:
    """Extractive problem/benefit statement with attribution (ADR-0010)."""

    problem: str
    benefit: str
    attributions: tuple[MotivationAttribution, ...] = ()


@dataclass(frozen=True, slots=True)
class CaseExample:
    """Concrete organisation illustrating the trend, traceable to an evidence item."""

    organization: str
    evidence_index: int
    organization_type: OrganizationType | None = None
    country: str | None = None
    summary: str | None = None
    basis: CaseBasis | None = None


@dataclass(frozen=True, slots=True)
class EmergenceResult:
    """Full scoring outcome for one topic — the domain-side shape of ``AnalyzedTrend``."""

    trend_key: str
    title: str
    aliases: tuple[str, ...]
    score: float
    confidence: float
    low_evidence: bool
    indicators: tuple[ScoredIndicator, ...]
    lifecycle_stage: LifecycleStage
    first_mention_year: int
    total_documents: int
    series: TimeSeries
    dov: tuple[float, ...]
    dod: tuple[float, ...]
    burst: Burst | None
    confidence_diagnostics: Mapping[str, float | int | str | bool]
    confidence_explanation: str

    def indicator(self, name: IndicatorName) -> ScoredIndicator:
        """Return the scored indicator with the given name."""
        for item in self.indicators:
            if item.name == name:
                return item
        raise KeyError(name)

    @property
    def sort_key(self) -> tuple[float, float, float, str]:
        """Deterministic ranking key: ``score ↓, burst_weight ↓, novelty ↓, trend_key ↑``.

        Returned with the descending components negated so that plain ascending ``sorted``
        yields the methodology's order (§7 step 9).
        """
        burst_weight = self.burst.weight if self.burst is not None else 0.0
        return (-self.score, -burst_weight, -self.indicator("novelty").value, self.trend_key)


@dataclass(frozen=True, slots=True)
class AnalysisParams:
    """Run parameters coming from the ``AnalyzeDomain`` command."""

    top_n: int = 15
    years_window: int = 7
    source_classes: tuple[str, ...] = ()
    min_confidence: float | None = None
    include_mature: bool = False
    #: Ключи тем, помеченных аналитиками организации как «не технология» (вердикт NOISE).
    #:
    #: Существует потому, что измерением установлена граница метода: «concrete source passages»,
    #: «retrieved passages», «stale documents» — грамматически правильные именные группы,
    #: отличающиеся от имени технологии только смыслом. Ни балл эмерджентности, ни termhood их не
    #: отделяют — проверено на 5052 кандидатах, обрывок «state space» получает 98-й перцентиль
    #: термхуда, выше всех настоящих тем. Статистика этого не решает, а человек решает с одного
    #: взгляда. Здесь его взгляд возвращается в конвейер.
    #:
    #: Отсев происходит **до** отбора в ТОП-N, поэтому освободившееся место занимает следующий
    #: кандидат: аналитик получает пятнадцать полезных тем, а не тринадцать и две вычеркнутых.
    suppressed_keys: frozenset[str] = frozenset()

    def __post_init__(self) -> None:
        """Validate the ranges declared by ``analyze-domain.command.json``."""
        if not 5 <= self.top_n <= 50:
            raise ValueError(f"topN out of contract range [5, 50]: {self.top_n}")
        if not 3 <= self.years_window <= 15:
            raise ValueError(f"yearsWindow out of contract range [3, 15]: {self.years_window}")
        if self.min_confidence is not None and not 0.0 <= self.min_confidence <= 1.0:
            raise ValueError(f"minConfidence out of range [0, 1]: {self.min_confidence}")


def sorted_unique(values: Iterable[str]) -> tuple[str, ...]:
    """Sorted tuple of distinct non-empty strings — the standard determinism helper."""
    return tuple(sorted({value for value in values if value}))


def as_source_class(value: str) -> SourceClass:
    """Narrow an arbitrary string to :data:`SourceClass`, raising on unknown values."""
    for known in SOURCE_CLASSES:
        if value == known:
            return known
    raise ValueError(f"unknown source class: {value!r}")


def period_label(year: int) -> str:
    """Label of a yearly period, matching the ``TimelinePoint.period`` examples."""
    return str(year)


def quantile(sorted_values: Sequence[float], q: float) -> float:
    """Linear-interpolation quantile over an already sorted sequence.

    Equivalent to ``numpy.quantile(..., method="linear")`` but kept dependency-free so
    that the lifecycle rules stay inside the pure domain and remain trivially auditable.
    """
    if not sorted_values:
        return 0.0
    if len(sorted_values) == 1:
        return float(sorted_values[0])
    position = q * (len(sorted_values) - 1)
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return float(sorted_values[int(position)])
    weight = position - lower
    return float(sorted_values[lower]) * (1.0 - weight) + float(sorted_values[upper]) * weight
