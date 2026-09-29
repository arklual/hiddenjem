"""The full analysis pipeline — methodology §7, steps 1 to 10.

The pipeline is a **pure function** of ``(documents, query, params, profile, window, today)``
plus the injected embedding provider and narrator. It performs no I/O, reads no clock and
never mutates its inputs, which is what makes the "run it twice, get identical bytes" test
in ``tests/golden`` meaningful rather than decorative.

Steps
-----

1. parse the direction: normalise, expand with mined acronyms, embed the query;
2. corpus selection: window and source-class filters, ``K`` truncation;
3. candidate extraction: n-grams 1..4 with stopword boundaries, termhood, filters;
4. normalisation and synonym merging (BRULE-7);
5. topic assembly: agglomerative clustering, ``c-TF-IDF`` cluster label;
6. relevance filter against the direction (``θ`` or lexical overlap);
7. credibility rule (BRULE-1) and the first credible mention year (BRULE-2);
8. indicators and score (§3–§4);
9. ranking with the mainstream exclusion (BRULE-3), ``minConfidence`` and TOP-N;
10. evidence assembly, case example and extractive narration (§8).
"""

from __future__ import annotations

import math
import re
import time
from collections.abc import Callable, Mapping, Sequence
from concurrent.futures import Future, ThreadPoolExecutor
from dataclasses import dataclass, field, replace
from datetime import date, timedelta
from typing import Any, Final

import numpy as np

from horizon_analytics.domain.clustering import average_linkage_clusters
from horizon_analytics.domain.credibility import is_substantive
from horizon_analytics.domain.direction_lexicon import (
    DirectionLexicon,
    label_matches,
    load_direction_lexicon,
)
from horizon_analytics.domain.direction_lexicon import (
    expand as expand_direction,
)
from horizon_analytics.domain.direction_lexicon import (
    suggest as suggest_directions,
)
from horizon_analytics.domain.evidence import (
    EvidenceSelection,
    on_topic_documents,
    select_case_example,
    select_evidence,
)
from horizon_analytics.domain.explanation import explain
from horizon_analytics.domain.extraction.blacklist import (
    GenericTermFilter,
    load_stopwords,
    stem_phrase,
)
from horizon_analytics.domain.extraction.candidates import RawCandidate
from horizon_analytics.domain.extraction.corpus_cache import CorpusAnalysisCache
from horizon_analytics.domain.extraction.normalization import (
    AcronymDictionary,
    TermNormalizer,
    normalize_tokens,
    stem_token,
)
from horizon_analytics.domain.extraction.termhood import (
    TermhoodScore,
    compute_termhood,
    contains_subsequence,
)
from horizon_analytics.domain.extraction.tokenizer import normalize_text, tokenize
from horizon_analytics.domain.models import (
    AnalysisParams,
    CaseExample,
    CorpusStats,
    Document,
    EmergenceResult,
    Motivation,
    Posting,
    SourceClass,
    TermCandidate,
    TimeSeries,
    Topic,
    period_label,
    quantile,
)
from horizon_analytics.domain.narration.base import NarrationRequest, TrendNarrator
from horizon_analytics.domain.narration.extractive import ExtractiveNarrator, build_definition
from horizon_analytics.domain.ports import (
    EmbeddingProvider,
    MaturityProbe,
    ProgressStage,
    TechnologyJudge,
)
from horizon_analytics.domain.scoring.aggregators import get_aggregator
from horizon_analytics.domain.scoring.engine import EmergenceEngine
from horizon_analytics.domain.scoring.indicators import (
    CooccurrenceIndex,
    IndicatorContext,
    linear_fit,
)
from horizon_analytics.domain.scoring.profile import MethodologyParameters, MethodologyProfile
from horizon_analytics.domain.scoring.stability import (
    RankingCandidate,
    RankStability,
    rank_stability,
)
from horizon_analytics.domain.signal_scoring import (
    PROPOSAL_EXCLUSIONS,
    CandidateSource,
    FeatureContribution,
    ScoredTopic,
    SignalScore,
    TopicRescorer,
    _key_of,
)
from horizon_analytics.domain.vectors import Matrix, cosine, l2_normalize

__all__ = [
    "AnalysisPipeline",
    "Exclusion",
    "PipelineRequest",
    "PipelineResult",
    "ProgressListener",
    "TechnologyVerdict",
    "TrendOutcome",
]

ProgressListener = Callable[[ProgressStage, int, str | None], None]

#: Share of a term vector taken from its surface form; the rest comes from the centroid of
#: the documents containing it (see :meth:`AnalysisPipeline._term_vectors`).
_TERM_SURFACE_WEIGHT = 0.5

#: Доля документов вложенного (более короткого) термина, которые обязаны называть длинное имя,
#: чтобы оба остались в одной теме. Ниже половины тема под длинным именем состоит в основном из
#: документов, которые этого имени не произносят: `fog computing` с одиночным `computing` на
#: бэктесте 2016 года — 1747 документов, из них пятнадцать про туманные вычисления (разбор 103).
_NESTED_NAMED_SHARE = 0.5

#: Сколько документов нужно имени, чтобы его отделяли от кластера (:func:`_stands_alone`).
_NAME_MIN_DOCUMENTS = 5

#: Переранжирование верхушки моделью (разбор 110): сколько лучших читать и сколько пунктов стоит
#: первое место в порядке модели.
RERANK_DEPTH = 40
RERANK_MAX_POINTS = 10.0
#: Докуда читать порциями и сколько проверенных держать сверх ТОП-N.
RERANK_MAX_DEPTH = 120
RERANK_SPARE = 5
#: Экспертная стадия: сколько лучших читают эксперты и во сколько пунктов обходится «нет» большинства.
JURY_DEPTH = 30
JURY_PENALTY = 15.0
#: Сколько «да» из трёх нужно, чтобы пройти. Замер на 179 позициях со слепыми экспертными вердиктами:
#: при большинстве (2 из 3) luna пропускает 135 позиций при 41 настоящей (точность 0,30), при
#: единогласии — 66 при точности 0,47. Порог жюри строгий, и мягкий эксперт его не приближает.
JURY_REQUIRED = 3
#: Строгий эксперт «market» (разбор 110) одобряет вдвое реже, а в v11 стадия и с прежним почти
#: везде доходила до третьей порции из трёх — одобренных на ТОП-15 могло не хватить.
JURY_MAX_DEPTH = 120
#: Свежий след: хотя бы один источник темы не старше стольких лет, считая год конца окна. Жюри
#: засчитывает позицию, только если у неё есть источник 2024–2026 годов, — три эксперта
#: отклонили по этой причине четыре темы из восьми в отчёте по нейроинтерфейсам (разбор 110).
RECENT_EVIDENCE_YEARS = 3

#: Слова, которые не отличают одну тему от другой: «VLA», «VLA models» и «VLA systems» — одна тема.
_TITLE_FILLER: frozenset[str] = frozenset(
    ["a", "an", "the", "of", "for", "in", "on", "and", "with", "to", "based", "via", "using", "model", "models", "system", "systems", "technology", "technologies", "approach", "approaches", "framework", "frameworks", "method", "methods", "solution", "solutions", "platform", "platforms"]
)


#: Сокращения, которые в названиях тем стоят вместо полного слова: «Neuromorphic Sensor MCUs» и
#: «Neuromorphic Sensing Microcontrollers» — одна тема.
_TITLE_SYNONYMS: dict[str, str] = {
    "mcu": "microcontroller",
    "mcus": "microcontroller",
    "microcontrollers": "microcontroller",
    "soc": "system-on-chip",
    "socs": "system-on-chip",
    "llms": "llm",
    "sensing": "sensor",
}


def _concept_key(title: str) -> frozenset[str]:
    """Смысловой ключ названия: слова без служебных, в единственном числе, плюс аббревиатура.

    «of vision-language-action (VLA)», «vision language action VLA» и «vision-language-action
    models vlas» дают один ключ: дефисы и скобки убраны, «models» отброшено, «vlas» → «vla», а
    первые буквы «vision language action» дают ту же «vla».
    """
    words = [w for w in re.findall(r"[a-z0-9а-яё]+", title.lower().replace("-", " ")) if w]
    tokens: list[str] = []
    for word in words:
        if word in _TITLE_FILLER:
            continue
        # Британское и американское написание — одна тема: tokenised/tokenized, optimisation.
        word = re.sub(r"is(e|ed|es|ing|ation|ations)$", r"iz\1", word)
        word = _TITLE_SYNONYMS.get(word, word)
        if len(word) > 3 and word.endswith("s") and not word.endswith("ss"):
            word = word[:-1]
        tokens.append(word)
    key = set(tokens)
    long_words = [t for t in tokens if len(t) > 3 and not t.isdigit()]
    if len(long_words) >= 2:
        # Аббревиатура по первым буквам помечена «~»: она сверяется со словами другого названия,
        # но в сравнение слов не входит — иначе «td» и «tbd» развели бы одну тему.
        key.add("~" + "".join(t[0] for t in long_words))
    return frozenset(key)


def _near_duplicate(a: frozenset[str], b: frozenset[str]) -> bool:
    """Одна тема под двумя названиями: почти те же слова, одно название внутри другого, аббревиатура."""
    words_a = {t for t in a if not t.startswith("~")}
    words_b = {t for t in b if not t.startswith("~")}
    if not words_a or not words_b:
        return False
    if {t[1:] for t in a if t.startswith("~")} & words_b or {t[1:] for t in b if t.startswith("~")} & words_a:
        return True
    small, large = (words_a, words_b) if len(words_a) <= len(words_b) else (words_b, words_a)
    only = next(iter(small))
    if small <= large and (len(small) >= 2 or (only.isalpha() and len(only) <= 5)):
        return True
    return len(words_a & words_b) / len(words_a | words_b) >= 0.6


def _name_tokens(key: str) -> tuple[str, ...]:
    """Слова ключа кандидата; дефис — граница слова (`software-defin` = `software defin`)."""
    return tuple(part for part in key.replace("-", " ").split(" ") if part)


def _abbreviates(tokens: Sequence[str], other: Sequence[str]) -> bool:
    """Есть ли среди ``tokens`` аббревиатура подряд идущих слов ``other`` (`sdn` ← `software defin network`)."""
    for token in tokens:
        size = len(token)
        if size < 2 or size > len(other):
            continue
        for start in range(len(other) - size + 1):
            if "".join(word[0] for word in other[start : start + size]) == token:
                return True
    return False


def _sibling_names(left: str, right: str) -> bool:
    """Два имени одного рода, но разных технологий: одно главное слово, разные определения.

    `mobile edge computing` и `mobile cloud computing`, `fog computing` и `edge computing`,
    `computation offloading` и `mobile data offloading`. В лексическом пространстве они почти
    совпадают — общее главное слово даёт общие символьные n-граммы, общая литература даёт общий
    центр документов, — и слияние синонимов с кластеризацией соединяли их в одну тему. Тема
    получала имя более частого соседа, а технология, которая через пять лет выросла вдевятеро,
    исчезала из отчёта, не будучи ни разу оценена (разбор 103).

    Соседями не считаются уточнения (определения одного — часть определений другого: `mobile
    computing` и `mobile cloud computing`), перестановки (`access network` и `network access`) и
    пары, где одно определение — аббревиатура слов другого (`sdn network` и `software defined
    network`).
    """
    first, second = _name_tokens(left), _name_tokens(right)
    if len(first) < 2 or len(second) < 2 or first[-1] != second[-1]:
        return False
    first_modifiers, second_modifiers = set(first[:-1]), set(second[:-1])
    if first_modifiers <= second_modifiers or second_modifiers <= first_modifiers:
        return False
    return not (_abbreviates(first[:-1], second) or _abbreviates(second[:-1], first))


def _stands_alone(candidate: TermCandidate) -> bool:
    """Имя, которое может быть темой само: многословное и не меньше чем в пяти документах.

    Отделять от кластера стоит только то, что потом выдержит отдельную проверку. Без порога
    правила ниже разрезали и обрывки в два-три документа: на эталонном корпусе тем стало на 43%
    больше, квантили объёма, от которых считаются мейнстрим и стадия жизненного цикла, съехали
    вниз, и `sodium-ion battery` с прежними 23 документами стала «зрелой» (разбор 103). Пять —
    с запасом ниже 9–15 документов четырёх имён, потерянных на бэктесте 2016 года, и выше
    обрывков из двух-трёх упоминаний.
    """
    return candidate.token_count >= 2 and candidate.document_frequency >= _NAME_MIN_DOCUMENTS


def _distinct_topics(left: TermCandidate, right: TermCandidate) -> bool:
    """Нельзя ли двум кандидатам быть одной темой.

    Нельзя в трёх случаях:

    * они соседи по роду (:func:`_sibling_names`);
    * одиночное слово вложено в имя, и большинство документов слова имени не называют — тогда
      слово приносит теме чужие документы, а не синоним (`computing` при `fog computing`);
    * оба — многословные имена с общим словом, и большинство документов меньшего из них другое
      имя не упоминают. Общее слово даёт общие символьные n-граммы, то есть половину сходства
      векторов (:meth:`AnalysisPipeline._term_vectors`), а вторая половина — центр документов —
      в корпусе одной области почти у всех терминов общая. На бэктесте так `fog computing` уходил
      в тему `computing paradigm`, а `computation offloading` — в `offloading traffic`: сходство
      давало слово, а не литература. Совместная встречаемость отвечает на вопрос «одна ли это
      тема» прямо, а не через усреднение.

    Имена без общего слова по встречаемости не разводятся: их сблизили документы, и это ровно то
    сходство, ради которого кластеризация существует. Проба с запретом для любых двух имён
    раскрыла в финтехе десятки оборотов вроде `go beyond` и `research suggests`, прежде спрятанных
    внутри тем, — пул вырос за счёт мусора (разбор 103). Одиночные слова и аббревиатуры по
    встречаемости не судятся вовсе: они присоединяются к ближайшей теме, где им не запрещено.
    """
    left_named, right_named = _stands_alone(left), _stands_alone(right)
    if left_named and right_named and _sibling_names(left.key, right.key):
        return True
    shorter, longer = (left, right) if left.token_count <= right.token_count else (right, left)
    if shorter.token_count < longer.token_count and contains_subsequence(
        tuple(longer.key.split(" ")), tuple(shorter.key.split(" "))
    ):
        # Только голое слово. Фраза, вложенная во фразу, — одна семья имён, и длинная там обычно
        # обрывок (`system hard-carbon anode capacity` при `hard-carbon anode capacity`): отделив
        # её, получаем не технологию, а мусорную тему — так на эталонном корпусе обрывок вышел
        # в ТОП-15 энергетики третьим.
        return (
            shorter.token_count == 1
            and _stands_alone(longer)
            and longer.document_frequency < _NESTED_NAMED_SHARE * shorter.document_frequency
        )
    if not (left_named and right_named):
        return False
    if not set(_name_tokens(left.key)) & set(_name_tokens(right.key)):
        return False
    smaller, larger = (
        (left, right) if left.document_frequency <= right.document_frequency else (right, left)
    )
    shared = len(set(smaller.document_ids) & set(larger.document_ids))
    return shared < _NESTED_NAMED_SHARE * smaller.document_frequency


#: Stage names a candidate can be lost at, in pipeline order. Used by the trace to describe
#: *how far* a term got, which is what makes "why is this topic missing?" answerable.
TRACE_STAGES: tuple[str, ...] = (
    "extracted",
    "min_df",
    "term_filter",
    "unigram_termhood",
    "merged",
    "clustered",
    "relevance",
    "credibility",
    "evidence",
    "mainstream",
    "confidence",
    "zero_score",
    "suppressed",
    "near_duplicate",
    "rerank",
    "jury",
    "top_n",
    "ranked",
)


@dataclass(frozen=True, slots=True)
class CandidateTrace:
    """What happened to one specific term on its way through the pipeline.

    Exists for two audiences that turn out to want the same thing. An engineer asks "why does the
    golden corpus not produce the topic it was built to produce?"; an analyst asks "I know this
    technology exists — why is it not in my report?". Both questions are answered by the same
    record: the last stage the term survived, the stage that removed it, and the numbers that
    decided it. Guessing at either from aggregate drop counters is how a ranking quietly stops
    working without anyone noticing.
    """

    key: str
    #: The canonical key the term was merged into, when synonym merging moved it.
    canonical_key: str | None
    #: Last stage reached; ``"ranked"`` means it appears in the report.
    stage: str
    outcome: str
    reason: str | None
    detail: Mapping[str, float | int | str]

    @property
    def survived(self) -> bool:
        return self.outcome == "ranked"


@dataclass(frozen=True, slots=True)
class _RelevanceVerdict:
    """Чем именно шаг 6 решил судьбу каждой темы.

    Заведено вместо словаря на классе конвейера. Тот словарь переприсваивался при каждом прогоне и
    жил на классе, а не на экземпляре: два одновременных анализа в одном процессе затирали друг
    другу диагностику, и трассировка одного запроса показывала числа другого. Молча — объяснение
    выглядело правдоподобно всегда.

    Второе назначение — говорить правду о пороге. Трассировка печатала
    ``parameters.relevance_threshold``, статический параметр настроек, тогда как решает ``cut``,
    вычисляемый от базовой доли направления в корпусе. Аналитик видел «similarity=0.0;
    threshold=0.0» и вправе был заключить, что тема отброшена вопреки собственному правилу:
    ноль не меньше нуля. Объяснение, спорящее со своим же вердиктом, хуже отсутствия объяснения —
    а объяснимость это то, чем продукт отличается от списка слов.
    """

    #: тема → измеренная величина: доля документов направления либо близость к запросу
    measure: Mapping[str, float]
    #: порог, фактически применённый в этом прогоне
    cut: float
    #: True — решали коды рубрик источника, False — запасная лексическая близость
    by_subjects: bool
    #: Темы без единого размеченного документа, чья доля выведена по соседству их документов с
    #: размеченными (разбор 101). Пусто вне предметного режима.
    inferred: frozenset[str] = frozenset()

    def explain(self, key: str) -> tuple[str, dict[str, float | int | str]]:
        """Причина отказа человеку и те же числа машине."""
        value = self.measure.get(key, 0.0)
        if self.by_subjects and key in self.inferred:
            reason = (
                "тема не отнесена к запрошенному направлению: ни один её документ источник не "
                f"рубрицировал, а по соседству с размеченными к направлению ближе {value:.0%} "
                f"её документов при пороге {self.cut:.0%}"
            )
            rule = "subject-share-inferred"
        elif self.by_subjects:
            reason = (
                "тема не отнесена к запрошенному направлению: "
                f"в нём классифицировано {value:.0%} документов темы при пороге {self.cut:.0%}"
            )
            rule = "subject-share"
        else:
            reason = (
                "тема не отнесена к запрошенному направлению: "
                f"близость к формулировке {value:.2f} при пороге {self.cut:.2f}"
            )
            rule = "embedding-cut"
        return reason, {"measure": round(value, 4), "cut": round(self.cut, 4), "rule": rule}


#: Метка отказа правила имени → объяснение для аналитика.
#:
#: Метки — машинные и в отчёт не годятся, а вопрос «куда делось это имя» задают именно про них.
#: Существеннее прочего `generic_boundary`: по нему теряются настоящие названия — разбор 30 (`docs/01-analysis/30-boundary-filter-findings.md`) называет
#: `high bandwidth memory`, `high availability cluster`, `availability zone`, — и до сих пор аналитик
#: получал на них «никогда не извлекалось», то есть правду, из которой ничего не следует.
_REJECTION_REASONS: Final[Mapping[str, str]] = {
    "generic_boundary": (
        "правило имени: формулировка начинается или заканчивается словом из списка границ "
        "(оценочные прилагательные, глаголы утверждения, абстрактные вершины)"
    ),
    "generic_term": "правило имени: формулировка целиком состоит из общих слов",
    "attributive_tail": (
        "правило имени: формулировка кончается словом-определением, вершина отрезана окном "
        "(«field of quantum», «performance of deep neural»)"
    ),
    "process_noun": (
        "правило имени: вершина называет ход исследования, а не его предмет "
        "(«research interest», «pivotal role», «systematic literature»)"
    ),
    "clause_fragment": "правило имени: это обрывок предложения, а не название",
    "trailing_preposition": "правило имени: формулировка заканчивается предлогом",
    "participle_tail": "правило имени: формулировка заканчивается причастием",
    "too_short": "правило имени: формулировка короче допустимой длины",
    "too_long": "правило имени: формулировка длиннее допустимой длины",
    "numeric_only": "правило имени: в формулировке нет ничего, кроме чисел",
    "no_alphabetic_token": "правило имени: в формулировке нет ни одного буквенного слова",
    "empty": "правило имени: пустая формулировка",
}


@dataclass(frozen=True, slots=True)
class TechnologyVerdict:
    """Суждение о том, называет ли строка технологию.

    Приходит из модели (`nlp-service`), и потому у него три состояния, а не два.
    ``is_technology=None`` означает «модель не ответила», и это обязано читаться как «оставить»:
    отказ модели не должен удалять тему из отчёта. На этом держится соответствие ТЗ, запрещающему
    формировать выдачу «исключительно на основании знаний языковой модели»: модель здесь способна
    только **убрать** то, что уже нашёл поиск, и никогда — добавить.
    """

    is_technology: bool | None
    reason: str


@dataclass(frozen=True, slots=True)
class MaturityVerdict:
    """Суждение внешних источников о том, массовая ли уже технология.

    Три состояния по той же причине, что у :class:`TechnologyVerdict`: ``mature=None`` значит
    «не выяснено» — источник не ответил или название слишком неоднозначно для точного поиска, —
    и это обязано читаться как «оставить».
    """

    mature: bool | None
    #: Основание словами, с числами: «о технологии вышло 12 400 работ за три года».
    reason: str


@dataclass(frozen=True, slots=True)
class _HeadCheck:
    """Одна проверка верхушки: код отказа, код недоступности, решение и запись отказа."""

    code: str
    unavailable_code: str
    #: Названия → ``{название: причина}`` для тех, кого убрать; отсутствие — «оставить».
    decide: Callable[[Sequence[str]], Mapping[str, str]]
    reject: Callable[[EmergenceResult, str], None]


#: Причины исключения, которые ТЗ требует показывать аналитику, — и только они.
#:
#: ТЗ: «Отдельно должна быть продемонстрирована логика исключения зрелых трендов, массово
#: внедренных технологий, отраслевых стандартов, маркетингового хайпа и информационного шума»;
#: «в веб-интерфейсе должны отображаться … причины исключения зрелых технологий или
#: нерелевантных кандидатов при наличии такой информации».
#:
#: Ключ — стадия и код отказа конвейера, значение — формулировка для читателя. Счётчики отсева
#: существовали и раньше, но уходили только в метрики: на вопрос «что вы отбросили и почему»
#: продукт отвечал числом без имён. Перечень намеренно не полон — стадии, интересные лишь
#: инженеру (`min_df`, `merged`), в отчёт не идут: список из тридцати технических причин прячет
#: пять содержательных.
EXCLUSION_REASONS: Final[Mapping[str, str]] = {
    "mainstream": "уже мейнстрим направления: тема занимает заметную долю его литературы",
    "mainstream_external": (
        "уже массовая за пределами корпуса: открытые источники знают о ней слишком много"
    ),
    "lifecycle_maturing": "зрелая стадия жизненного цикла: рост прекратился или ушёл в патенты",
    "media_only": "медийная видимость без научно-технической основы",
    "stale_evidence": "ни одного источника за последние три года: слабый сигнал без свежего следа не подтверждён",
    "not_technology": "не является названием технологии",
    "relevance": "не относится к запрошенному направлению",
    "credibility": "недостаточно независимых подтверждений",
    "zero_score": "тема не обладает одним из обязательных свойств зарождающейся технологии",
    "term_filter": "общеупотребимый оборот, а не название технологии",
    "suppressed": "скрыта по пометке аналитика",
    "top_n": "не вошла в ТОП-N по баллу",
    "near_duplicate": "повтор темы, уже вошедшей в выдачу под другим названием",
    "rerank": "снята при переранжировании: не по запросу, давно массовая или не технология",
    "jury": "эксперты большинством сочли не слабым сигналом по запросу",
    # Причины второго источника кандидатов живут рядом со своим расчётом, а объявлены здесь один
    # раз: перечень исключений — это контракт с читателем отчёта, и он обязан быть один.
    **PROPOSAL_EXCLUSIONS,
}

#: Сколько имён показывается на причину. Причина без примеров — снова число: аналитик не может
#: проверить, отбросили ли мусор или его тему. Пять достаточно, чтобы увидеть род отброшенного,
#: и мало настолько, чтобы не превратить оговорку в отчёт.
EXCLUSION_EXAMPLE_LIMIT: Final[int] = 5


@dataclass(frozen=True, slots=True)
class Exclusion:
    """Одна причина, по которой кандидаты не попали в отчёт, с числом и примерами."""

    #: Стадия конвейера, она же код причины.
    code: str
    #: Формулировка для читателя.
    reason: str
    count: int
    #: До :data:`EXCLUSION_EXAMPLE_LIMIT` имён, отсортированных детерминированно.
    examples: tuple[str, ...]


@dataclass(slots=True)
class _ExclusionLog:
    """Собирает исключения по ходу прогона: счётчик и несколько имён на причину.

    Ведётся всегда, а не только при включённой трассировке: трассировка отвечает на вопрос об
    известном заранее термине, а это — на вопрос «что вы вообще отбросили», который задают, не
    имея на руках ни одного имени.
    """

    counts: dict[str, int] = field(default_factory=dict)
    examples: dict[str, list[str]] = field(default_factory=dict)

    def record(self, code: str, key: str) -> None:
        """Учесть отказ кандидата ``key`` на стадии ``code``."""
        if code not in EXCLUSION_REASONS:
            return
        self.counts[code] = self.counts.get(code, 0) + 1
        bucket = self.examples.setdefault(code, [])
        if len(bucket) < EXCLUSION_EXAMPLE_LIMIT and key not in bucket:
            bucket.append(key)

    def finish(self) -> tuple[Exclusion, ...]:
        """Причины в порядке убывания числа отброшенного, при равенстве — по коду."""
        rows = [
            Exclusion(
                code=code,
                reason=EXCLUSION_REASONS[code],
                count=count,
                examples=tuple(sorted(self.examples.get(code, ()))),
            )
            for code, count in self.counts.items()
        ]
        rows.sort(key=lambda row: (-row.count, row.code))
        return tuple(rows)


@dataclass(slots=True)
class _Tracer:
    """Collects :class:`CandidateTrace` records for a watch list. No-op when the list is empty."""

    watch: frozenset[str]
    #: Правило имени — чтобы у «никогда не извлекалось» была причина, когда она есть.
    #:
    #: Фильтр отклоняет n-грамму **до** того, как она станет кандидатом, поэтому `drop` для неё не
    #: вызывается никогда, и трасса заканчивалась общим `never_extracted`. Ответ верный и
    #: бесполезный: он одинаков и для формулировки, которой в корпусе нет, и для настоящего имени,
    #: которое отклонило правило. Различать их — и есть весь смысл трассы.
    term_filter: GenericTermFilter | None = None
    records: dict[str, CandidateTrace] = field(default_factory=dict)
    #: watched key -> the canonical key it was merged into
    aliases: dict[str, str] = field(default_factory=dict)
    #: Причины исключения для отчёта. Живут здесь, а не рядом, потому что каждый отказ конвейера
    #: уже проходит через :meth:`drop`: второй список, наполняемый на тех же местах руками,
    #: разошёлся бы с первым в день добавления новой стадии.
    exclusions: _ExclusionLog = field(default_factory=_ExclusionLog)
    #: Ключ кандидата → формулировка, которой термин спросили. Сопоставление идёт по ключу, а в
    #: ответ уходит формулировка аналитика: он спрашивал «speculative decoding», а не «speculative
    #: decod», и увидеть в ответе обрубок стема — значит не узнать свой вопрос.
    labels: dict[str, str] = field(default_factory=dict)

    @classmethod
    def for_terms(cls, terms: frozenset[str], term_filter: GenericTermFilter | None) -> _Tracer:
        """Трассировщик для терминов в любом виде — как их пишет аналитик или уже ключом.

        Ключи кандидатов стеммированы («speculative decod», «federat learn»), а термины приходили
        сырыми, и наблюдение сравнивало одно с другим напрямую. Сравнение не совпадало никогда:
        `/explain` на любой обычный вопрос отвечал «никогда не извлекалась» — в том числе про тему,
        стоящую в том же отчёте третьей. Найдено сравнением с размеченным датасетом (разбор 90).
        """
        labels = {stem_phrase(term.strip().lower()): term.strip() for term in terms if term.strip()}
        return cls(watch=frozenset(labels), term_filter=term_filter, labels=labels)

    @property
    def active(self) -> bool:
        return bool(self.watch)

    def observe(self, key: str, stage: str) -> None:
        """Record that ``key`` reached ``stage`` and is still alive."""
        if not self.active:
            return
        watched = self._watched(key)
        if watched is None:
            return
        self.records[watched] = CandidateTrace(
            key=watched,
            canonical_key=key if key != watched else None,
            stage=stage,
            outcome="alive",
            reason=None,
            detail={},
        )

    def drop(self, key: str, stage: str, reason: str, **detail: float | int | str) -> None:
        """Record that ``key`` was removed at ``stage``.

        Счёт исключений ведётся до проверки списка наблюдения: он нужен всегда, а трасса — только
        когда спросили про конкретный термин.
        """
        self.exclusions.record(stage, key)
        if not self.active:
            return
        watched = self._watched(key)
        if watched is None:
            return
        self.records[watched] = CandidateTrace(
            key=watched,
            canonical_key=key if key != watched else None,
            stage=stage,
            outcome="dropped",
            reason=reason,
            detail=dict(detail),
        )

    def merge(self, member_key: str, canonical_key: str) -> None:
        """Follow a watched term into the candidate it was merged with."""
        if self.active and member_key in self.watch and member_key != canonical_key:
            self.aliases[member_key] = canonical_key

    def finish(self, ranked_keys: frozenset[str]) -> tuple[CandidateTrace, ...]:
        """Close the trace: mark survivors, and report terms never seen at all."""
        if not self.active:
            return ()
        for watched in self.watch:
            effective = self.aliases.get(watched, watched)
            if effective in ranked_keys:
                self.records[watched] = CandidateTrace(
                    key=watched,
                    canonical_key=effective if effective != watched else None,
                    stage="ranked",
                    outcome="ranked",
                    reason=None,
                    detail={},
                )
            elif watched not in self.records:
                label = self._rejected_by_rule(self.labels.get(watched, watched))
                self.records[watched] = CandidateTrace(
                    key=watched,
                    canonical_key=None,
                    stage="term_filter" if label else "extracted",
                    outcome="dropped" if label else "absent",
                    reason=_REJECTION_REASONS.get(label, label) if label else "never_extracted",
                    detail={"rule": label} if label else {},
                )
        return tuple(self._labelled(self.records[key]) for key in sorted(self.records))

    def _labelled(self, trace: CandidateTrace) -> CandidateTrace:
        """Вернуть трассе формулировку, которой термин спросили."""
        label = self.labels.get(trace.key)
        if label is None or label == trace.key:
            return trace
        return replace(trace, key=label)

    def _rejected_by_rule(self, phrase: str) -> str | None:
        """Метка отказа правила имени для этой формулировки; ``None``, если правило её пропускает.

        Спрашивается само правило, а не догадка о нём: ответ «правило отклоняет вот по такой
        причине» верен независимо от того, встречается формулировка в корпусе или нет, и он и есть
        то, чего аналитику не хватало.
        """
        if self.term_filter is None:
            return None
        tokens = phrase.split()
        if not tokens:
            return None
        return self.term_filter.rejects(stem_phrase(phrase), tokens)

    def _watched(self, key: str) -> str | None:
        """The watched term this key currently stands for, if any."""
        if key in self.watch:
            return key
        for watched, canonical in self.aliases.items():
            if canonical == key:
                return watched
        return None


@dataclass(frozen=True, slots=True)
class PipelineRequest:
    """Everything one analysis run needs."""

    normalized_query: str
    documents: tuple[Document, ...]
    params: AnalysisParams
    profile: MethodologyProfile
    window_from: date
    window_to: date
    today: date
    query: str | None = None
    #: Terms to trace through the pipeline — as the analyst writes them or as candidate keys; both
    #: are normalised to keys before matching. Empty disables tracing entirely.
    watch: frozenset[str] = frozenset()


@dataclass(frozen=True, slots=True)
class TrendOutcome:
    """One ranked trend with everything the contract requires."""

    result: EmergenceResult
    evidence: EvidenceSelection
    motivation: Motivation
    definition: str
    case_example: CaseExample | None
    #: Почему тема — слабый сигнал и почему у неё такая уверенность: «заголовок — пояснение».
    explanation: tuple[tuple[str, str], ...] = ()


@dataclass(frozen=True, slots=True)
class PipelineResult:
    """Outcome of a full run, ready to be mapped onto ``DomainAnalyzed``."""

    trends: tuple[TrendOutcome, ...]
    documents_analyzed: int
    candidates_evaluated: int
    truncated: bool
    window_from: date
    window_to: date
    embedding_model_id: str | None
    stage_timings: Mapping[str, float]
    diagnostics: Mapping[str, int]
    #: Per-term explanation of what happened, populated only when the request asked for it.
    traces: tuple[CandidateTrace, ...] = ()
    #: Понял ли конвейер, о каком направлении его спросили.
    #:
    #: ``False`` означает, что корпус размечен предметными кодами, но ни один документ не отнесён к
    #: направлению: формулировки нет в перекрёстном словаре. Отбор тогда возвращается на запасной
    #: путь «лучшие N по сходству», и отчёт перестаёт зависеть от вопроса — но собирается и выглядит
    #: целым. Это не то же самое, что «в направлении ничего не происходит»: разница между «мы не
    #: нашли сигналов» и «мы не поняли вопроса», и цена у этих двух ошибок разная.
    #:
    #: ``True`` по умолчанию: неразмеченный корпус не даёт оснований заявлять непонимание, а ложная
    #: оговорка обесценивает настоящие.
    direction_recognized: bool = True
    #: Ближайшие известные формулировки направления. Заполняются только когда направление не
    #: распознано: без них красная плашка «не распознано» — тупик, а разница между работающим
    #: продуктом и бесполезным здесь часто в одном слове, которое аналитику неоткуда узнать.
    direction_suggestions: tuple[str, ...] = ()
    #: Доля документов темы, отнесённых источниками к запрошенному направлению.
    #:
    #: Величина считалась и раньше — на ней держится сам отбор, — но нигде не сохранялась. Из-за
    #: этого на вопрос «почему эта тема в моём отчёте?» отвечать было нечем: трассировка объясняет,
    #: почему темы **нет**, а обратный вопрос оставался без ответа. Разбор дефекта, на котором это
    #: вскрылось, — `docs/01-analysis/32-foreign-topic-findings.md`.
    #:
    #: Пусто, когда отбор шёл не по предметным кодам: там та же переменная означает косинус к
    #: запросу, а не долю, и выдавать одно за другое нельзя.
    direction_share: Mapping[str, float] = field(default_factory=dict)
    #: Место каждой темы при разумных изменениях весов индикаторов.
    #:
    #: Веса выбраны экспертно, и без этого измерения любой ответ на вопрос «а если бы взвесили
    #: иначе?» сводится к «доверьтесь нам». Считается по всем выжившим кандидатам, а не только по
    #: попавшим в отчёт: место темы определяется теми, кто стоит рядом, включая не вошедших.
    rank_stability: tuple[RankStability, ...] = ()
    #: Из чего сложился балл каждой темы, когда считал движок внешних признаков.
    #:
    #: Пусто у методологии: там балл складывается из индикаторов, и они уже лежат в отчёте
    #: строка за строкой. У движка ``signals`` ответа на вопрос «почему эта тема поднялась» не
    #: было бы вовсе — линейная модель без разложения по признакам читается как оракул.
    signal_scores: tuple[SignalScore, ...] = ()
    #: Ключи тем, имя которых предложила модель, а подтвердили внешние источники.
    #:
    #: Происхождение темы — не служебная подробность: у извлечённой из корпуса и у предложенной
    #: разная природа ошибки. Первая может оказаться обрывком фразы, вторая — правдоподобным
    #: именем, которого никто не употребляет. Аналитик, который этого не видит, доверяет им
    #: одинаково, а верить им одинаково нельзя.
    proposed_keys: frozenset[str] = frozenset()
    #: Почему кандидаты не попали в отчёт: причина словами, число и несколько имён.
    #:
    #: ТЗ требует показывать логику исключения зрелых трендов, стандартов, хайпа и шума. До этого
    #: поля отсев существовал только как счётчики в метриках — то есть как число без имён, которое
    #: нельзя проверить.
    exclusions: tuple[Exclusion, ...] = ()


@dataclass(slots=True)
class _Stopwatch:
    """Accumulates per-stage wall-clock durations for ``stageTimingsMs``."""

    timings: dict[str, float] = field(default_factory=dict)

    def record(self, stage: str, started: float) -> None:
        """Record the elapsed milliseconds of ``stage``."""
        self.timings[stage] = round((time.perf_counter() - started) * 1000.0, 3)


#: Классы источников, которые являются научно-технической основой утверждения, а не вниманием
#: к нему. Новость сообщает, что о технологии написали; препринт, патент, стандарт или
#: репозиторий — что её сделали.
_SUBSTANCE_CLASSES: Final[frozenset[SourceClass]] = frozenset(
    {"PREPRINT", "JOURNAL_ARTICLE", "PATENT", "CODE_REPOSITORY", "STANDARD"}
)

#: Во сколько раз больше ТОП-N кандидатов отдаётся на семантическую проверку.
#:
#: Была двойка — по расчёту «чтобы освободившиеся места заняли непроверенные темы, отсеять
#: пришлось бы половину верхушки». На живом корпусе отсеивается **больше** половины: замер по
#: кибербезопасности — 23 строки из 30 не являются названиями технологий, и в отчёт попадали
#: семь проверенных и восемь непроверенных. Тройка возвращает отчёту проверенную верхушку;
#: цена — примерно минута процессорного времени модели.
#:
#: Живёт в модуле, а не атрибутом класса: любая аннотированная переменная класса создаёт у него
#: изменяемый ``__annotations__``, а структурная проверка запрещает конвейеру держать на классе
#: изменяемое состояние — именно так однажды два одновременных прогона затирали друг другу
#: диагностику.
JUDGE_DEPTH_FACTOR: Final[int] = 3

#: Сколько порций судья читает наперёд, пока решается текущая (см. ``_screen_head``).
JUDGE_PREFETCH: Final[int] = 2

#: Метка-аббревиатура: две-шесть заглавных, возможна цифра («SD», «RAG», «PINN», «5G»).
_ACRONYM_LABEL: Final = re.compile(r"[0-9]?[A-Z][A-Z0-9]{1,5}s?")
#: Разделители слов расшифровки: пробел и дефис («retrieval-augmented generation» — три слова).
_WORD_SPLIT: Final = re.compile(r"[\s\-]+")
#: Служебные слова, которых в аббревиатуре не бывает: «area under **the** curve» → AUC.
_ACRONYM_SKIP_WORDS: Final[frozenset[str]] = frozenset(
    {"of", "the", "and", "for", "to", "in", "on", "with", "a", "an", "by"}
)

#: Во сколько раз больше ТОП-N кандидатов судья вправе прочитать, догоняя выбывших.
#:
#: Одного окна фиксированной глубины не хватает, и это видно на живой выдаче. Замер по
#: искусственному интеллекту 2026-09-18: из первых сорока пяти строк судья убрал двенадцать,
#: освободившиеся места заняли строки с сорок шестой по пятьдесят седьмую — и они в отчёт
#: попали непрочитанными. В ТОП-15 вышли «map», «candidate», «extensive», «fixed»: тот же
#: судья, спрошенный о них отдельно, отвечает «общее слово» и «свойство».
#:
#: Поэтому окно догоняет выбытие: следующая порция спрашивается, пока прочитанных и
#: оставленных строк меньше ТОП-N. Потолок нужен против вырожденного корпуса, где не
#: является технологией вообще ничего: без него анализ уходил бы в модель на неограниченное
#: время. Уткнувшись в потолок при исправных проверках, стадия хвост **не дописывает**: в
#: отчёте остаётся меньше тем, и он помечается как неполный.
#:
#: Десять, а не шесть, — по замеру 2026-09-18 после того, как OpenAlex начал приносить свои работы
#: и пул кандидатов вырос: из первых девяноста судья и внешняя проверка зрелости оставили девять
#: названий технологий, и отчёт не добирал до пятнадцати при живых кандидатах глубже.
#: Оставлять непрочитанное можно, только если проверка **отказала** — это «молчание означает
#: оставить». Исчерпанная глубина — не молчание: прогон по ИИ 2026-09-18, дописывавший хвост,
#: получил на места 10–15 «blocks», «five», «length», «index» (``_screen_head``).
#:
#: Двадцать пять, а не десять, — после перевода судьи на облачную модель (gpt-5.6-luna, разбор 89):
#: она читает сорок пять строк за семнадцать секунд вместо полутора минут, а пул кандидатов после
#: добавления источников стал шире ста пятидесяти строк — прогон по ИИ упирался в потолок с
#: семью темами при живых кандидатах глубже. С локальной моделью на процессоре такая глубина
#: стоит минут; выбор бэкенда — настройка стенда, а не этой константы.
JUDGE_LIMIT_FACTOR: Final[int] = 25
#: Бюджет чтения верхушки сверх ``JUDGE_LIMIT_FACTOR``, в секундах.
#:
#: Условие задачи — не меньше ТОП-N тем в отчёте, и потолок по числу строк его нарушал: прогон по
#: защите ИИ 2026-09-28 прочитал 375 строк, судья оставил девять, и отчёт вышел с девятью темами
#: при тысячах непрочитанных кандидатов. Потолок по числу подбирался трижды (6 → 10 → 25) и каждый
#: раз оказывался мал для следующего корпуса. Время, а не число строк, — то, чем на самом деле
#: платит аналитик: чтение продолжается, пока тем меньше ТОП-N, но не дольше этого бюджета, и
#: двадцать минут ТЗ на весь анализ остаются в силе.
HEAD_SCREEN_BUDGET_SECONDS: Final[float] = 360.0


def _elapsed_share(window_to: date, today: date) -> float:
    """Какая доля последнего года окна прошла к моменту анализа.

    Единица, когда год закончился — тогда ряд наблюдён целиком и приводить нечего.
    """
    year = window_to.year
    end = min(window_to, today)
    if end >= date(year, 12, 31):
        return 1.0
    start = date(year, 1, 1)
    days_in_year = (date(year + 1, 1, 1) - start).days
    elapsed = (end - start).days + 1
    return max(0.05, min(1.0, elapsed / days_in_year))


def _media_only(documents: Sequence[Document]) -> bool:
    """Тему подтверждают только медийные публикации пониженной доверенности и ничего кроме них.

    Профессиональные отраслевые медиа и блоги компаний-разработчиков основанием считаются: ТЗ
    относит их к доверенным (:func:`~horizon_analytics.domain.credibility.is_substantive`).
    """
    return not any(is_substantive(document) for document in documents)


def _zeroed_only_by_weakness(result: EmergenceResult, request: PipelineRequest) -> bool:
    """Whether ``includeMature`` should rescue a topic the geometric mean zeroed.

    Methodology §3.4 promises that a mainstream topic "remains available through the
    *including mature topics* filter". Without this exemption the promise is empty: the loudest
    topic in a direction has ``recent == recent_max`` by definition, so ``weakness`` is exactly 0,
    the weighted geometric mean is 0 (BRULE-4), and the unconditional zero-score drop removes it
    again — the filter is a no-op precisely for the one topic it exists to reveal.

    The exemption is deliberately narrow: it applies only when ``weakness`` is the *sole* zero.
    A topic that is also not novel, or not growing, is genuinely not an emerging trend and stays
    out regardless of the flag.
    """
    if not request.params.include_mature:
        return False
    zeros = [value.name for value in result.indicators if value.value <= 0.0]
    return zeros == ["weakness"]


def _author_team(document: Document) -> frozenset[str]:
    """Авторский коллектив документа: фамилия и первая буква имени каждого автора.

    Ключ грубее полного имени намеренно: «J. Smith» и «John Smith» в разных источниках — один
    человек, и счесть их двумя коллективами значило бы засчитать одну группу дважды.
    """
    team: set[str] = set()
    for author in document.authors:
        words = author.full_name.lower().replace(".", " ").split()
        if words:
            team.add(f"{words[-1]} {words[0][0]}" if len(words) > 1 else words[0])
    return frozenset(team)


def _case_from_naming(evidence: EvidenceSelection, naming: frozenset[str]) -> CaseExample | None:
    """Пример организации из тех источников карточки, где тема названа; нет таких — без примера."""
    positions = [index for index, document in enumerate(evidence.documents) if document.document_id in naming]
    if not positions:
        return None
    case = select_case_example(
        EvidenceSelection(
            items=tuple(evidence.items[index] for index in positions),
            documents=tuple(evidence.documents[index] for index in positions),
        )
    )
    return replace(case, evidence_index=positions[case.evidence_index]) if case is not None else None


class AnalysisPipeline:
    """Executes the ten methodology steps for one snapshot."""

    def __init__(
        self,
        *,
        embedding_provider: EmbeddingProvider,
        narrator: TrendNarrator | None = None,
        term_filter: GenericTermFilter | None = None,
        stopwords: frozenset[str] | None = None,
        direction_lexicon: DirectionLexicon | None = None,
        corpus_cache: CorpusAnalysisCache | None = None,
        technology_judge: TechnologyJudge | None = None,
        maturity_probe: MaturityProbe | None = None,
        rescorer: TopicRescorer | None = None,
        candidates: CandidateSource | None = None,
    ) -> None:
        """Wire the pipeline with its pluggable collaborators.

        ``corpus_cache`` живёт дольше конвейера — он строится на каждый запрос, а разбор корпуса
        зависит только от корпуса. Без сотрудника поведение прежнее: разбор считается заново.

        ``candidates`` — второй источник кандидатов: имена технологий, названные моделью и
        подтверждённые внешними источниками. Без сотрудника кандидаты берутся только из корпуса,
        то есть ровно как раньше.

        ``rescorer`` — точка, в которой второй движок подменяет балл ранжирования своим
        (`docs/BACKLOG.md`, п. 25). Подмена именно здесь, а не после отбора в ТОП-N: балл решает,
        кто в этот ТОП-N попадёт, и переставлять пятнадцать уже отобранных тем значило бы считать
        состав отчёта методологией, а порядок — чем-то другим. Без сотрудника конвейер ведёт себя
        ровно так, как вёл до его появления, — это и есть движок ``methodology``.
        """
        self._embeddings = embedding_provider
        self._narrator: TrendNarrator = narrator if narrator is not None else ExtractiveNarrator()
        self._term_filter = term_filter
        self._stopwords = stopwords if stopwords is not None else load_stopwords()
        self._lexicon = (
            direction_lexicon if direction_lexicon is not None else load_direction_lexicon()
        )
        self._corpus_cache = corpus_cache if corpus_cache is not None else CorpusAnalysisCache()
        # Судья необязателен: без него конвейер ведёт себя ровно так, как вёл до его появления.
        self._judge = technology_judge
        # Внешняя проверка зрелости — тоже: без неё мейнстрим судится только по корпусу.
        self._maturity = maturity_probe
        self._rescorer = rescorer
        self._candidates = candidates

    # ───────────────────────────── public entry point ─────────────────────────────

    def run(
        self, request: PipelineRequest, listener: ProgressListener | None = None
    ) -> PipelineResult:
        """Run the whole pipeline and return the ranked trends."""
        parameters = request.profile.parameters
        term_filter = self._term_filter or GenericTermFilter.default(
            min_chars=parameters.min_term_chars, max_chars=parameters.max_term_chars
        )
        watch = _Stopwatch()
        tracer = _Tracer.for_terms(request.watch, term_filter)

        def notify(stage: ProgressStage, percent: int, message: str | None = None) -> None:
            if listener is not None:
                listener(stage, percent, message)

        # Корпусная секция процесса берётся целиком: провайдер эмбеддингов и разбор корпуса — общее
        # состояние, а инвариант «считаем в пространстве того корпуса, который сейчас разбираем»
        # держится только на протяжении всего прогона. Разбор: docs/01-analysis/72-two-questions-one-vector-space.md.
        with self._corpus_cache.exclusive():
            return self._run_exclusively(request, notify, watch, tracer, parameters, term_filter)

    def _run_exclusively(
        self,
        request: PipelineRequest,
        notify: Callable[[ProgressStage, int, str | None], None],
        watch: _Stopwatch,
        tracer: _Tracer,
        parameters: MethodologyParameters,
        term_filter: GenericTermFilter,
    ) -> PipelineResult:
        """Тело прогона, выполняемое под владением корпусной секцией процесса."""
        drops: dict[str, int] = {}

        # ── steps 1–2 ────────────────────────────────────────────────────────────
        started = time.perf_counter()
        notify("EXTRACTING", 5, "отбор корпуса")
        documents, truncated = self._select_corpus(request)
        if not documents:
            watch.record("extracting", started)
            return PipelineResult(
                trends=(),
                documents_analyzed=0,
                candidates_evaluated=0,
                truncated=truncated,
                window_from=request.window_from,
                window_to=request.window_to,
                embedding_model_id=self._embeddings.model_id,
                stage_timings=dict(watch.timings),
                diagnostics={"documents": 0},
                traces=tracer.finish(frozenset()),
            )

        # ── step 3 ───────────────────────────────────────────────────────────────
        notify("EXTRACTING", 15, "извлечение кандидатов")
        corpus_analysis = self._corpus_cache.analyse(
            documents,
            stopwords=self._stopwords,
            ngram_min=parameters.ngram_min,
            ngram_max=parameters.ngram_max,
        )
        normalizer = corpus_analysis.normalizer
        extraction = corpus_analysis.extraction
        query_stems = self._query_stems(request.normalized_query, normalizer)
        drops["generated"] = extraction.generated
        drops["candidates_raw"] = len(extraction.candidates)

        surviving: list[RawCandidate] = []
        for candidate in extraction.candidates:
            tracer.observe(candidate.key, "extracted")
            if candidate.document_frequency < parameters.min_document_frequency:
                drops["min_df"] = drops.get("min_df", 0) + 1
                tracer.drop(
                    candidate.key,
                    "min_df",
                    "документов меньше минимума",
                    documentFrequency=candidate.document_frequency,
                    minimum=parameters.min_document_frequency,
                )
                continue
            reason = term_filter.rejects(candidate.key, candidate.surface.split(" "))
            if reason is not None:
                drops[reason] = drops.get(reason, 0) + 1
                tracer.drop(candidate.key, "term_filter", reason)
                continue
            surviving.append(candidate)
        drops["candidates_filtered"] = len(surviving)
        watch.record("extracting", started)

        if not surviving:
            return PipelineResult(
                trends=(),
                documents_analyzed=len(documents),
                candidates_evaluated=0,
                truncated=truncated,
                window_from=request.window_from,
                window_to=request.window_to,
                embedding_model_id=self._embeddings.model_id,
                stage_timings=dict(watch.timings),
                diagnostics=dict(sorted(drops.items())),
                traces=tracer.finish(frozenset()),
            )

        # Термхуд и отсев по нему — отдельная стадия, а не хвост извлечения. До этого замера ни один
        # таймер её не покрывал: сумма стадий давала 108 с при 207 с прогона на настоящем корпусе,
        # то есть половина работы была невидима, а панель «Полный цикл, p95» занижала вдвое.
        started = time.perf_counter()
        termhood = compute_termhood(
            surviving,
            extraction.token_statistics,
            c_value_weight=parameters.termhood_cvalue_weight,
            yake_weight=parameters.termhood_yake_weight,
        )
        # A single word is weak evidence of termhood: C-value discounts it (log2(1.1) against
        # log2(2) for a bigram), but the emergence score never reads termhood, so an ordinary word
        # that happens to be recent and rare wins on novelty and weakness alone. Require unigrams
        # to clear a high percentile of the termhood distribution; phrases are exempt, because the
        # phrase itself is the evidence that someone named a thing.
        admitted, unigrams_dropped = AnalysisPipeline._admit_unigrams(
            surviving, termhood, parameters.unigram_termhood_percentile
        )
        drops["unigram_below_termhood"] = unigrams_dropped
        if tracer.active:
            admitted_keys = {item.key for item in admitted}
            for candidate in surviving:
                if candidate.key not in admitted_keys:
                    tracer.drop(
                        candidate.key,
                        "unigram_termhood",
                        "одиночное слово ниже порога термхуда",
                        termhood=round(
                            termhood[candidate.key].termhood if candidate.key in termhood else 0.0,
                            6,
                        ),
                        percentile=parameters.unigram_termhood_percentile,
                    )

        # Бюджет кластеризации: O(n²) ниже не позволяет взять всех, когда корпус велик.
        ranked, over_budget = self._clustering_budget(
            admitted, termhood, documents, request.window_to - timedelta(days=365), parameters
        )
        drops["over_cluster_budget"] = len(over_budget)
        if tracer.active:
            for position, candidate in over_budget:
                tracer.drop(
                    candidate.key,
                    "unigram_termhood",
                    "не вошёл в бюджет кластеризации: ни по термхуду, ни по свежей активности групп",
                    rank=position,
                    budget=parameters.max_terms_clustered,
                )
        ranked.sort(key=lambda item: item.key)

        watch.record("termhood", started)

        # ── steps 4–5 ────────────────────────────────────────────────────────────
        started = time.perf_counter()
        notify("EMBEDDING", 35, "векторизация")
        texts = [document.text for document in documents]
        self._embeddings.fit(texts)
        document_vectors = self._embeddings.embed(texts)
        query_vector = self._embeddings.embed([request.normalized_query])[0]
        document_index = {document.document_id: index for index, document in enumerate(documents)}
        term_vectors = self._term_vectors(ranked, document_vectors, document_index)
        watch.record("embedding", started)

        started = time.perf_counter()
        notify("CLUSTERING", 55, "слияние синонимов и кластеризация")
        merged, merged_vectors = self._merge_synonyms(
            ranked, term_vectors, termhood, parameters, tracer
        )
        drops["candidates_merged"] = len(merged)
        topics, topic_members = self._build_topics(
            merged, merged_vectors, parameters, len(documents), tracer
        )
        topics = self._expand_acronym_labels(topics, normalizer.acronyms)
        drops["topics"] = len(topics)
        drops["generic_head"] = len(merged) - sum(len(topic.members) for topic in topics)

        direction_labels = self._direction_labels(request.normalized_query, self._lexicon)
        subject_relevance = self._subject_relevance(
            documents,
            query_stems,
            direction_labels,
            self._query_phrase(request.normalized_query),
        )
        # Направление не определено: корпус размечен предметными кодами, но ни один документ не
        # попал в направление. Дальше отбор вырождается в «лучшие N по сходству», а сходство при
        # непересекающихся словарях — шум: отчёт соберётся, будет выглядеть целым и не будет
        # зависеть от того, что спросили. Диагностика делает этот случай видимым, потому что сам
        # по себе он ничем не отличается от нормальной работы.
        classified = sum(1 for document in documents if document.topics)
        drops["corpus_classified"] = classified
        drops["direction_resolved"] = len(subject_relevance)
        direction_recognized = classified == 0 or bool(subject_relevance)
        direction_suggestions = (
            ()
            if direction_recognized
            else suggest_directions(
                [
                    stem_token(token.normal)
                    for token in tokenize(normalize_text(request.normalized_query))
                ],
                self._lexicon,
            )
        )
        relevant, off_direction, verdict = self._select_relevant(
            topics,
            query_vector,
            query_stems,
            parameters,
            document_vectors=document_vectors,
            document_index=document_index,
            subject_relevance=subject_relevance,
            corpus_size=len(documents),
            classified=frozenset(document.document_id for document in documents if document.topics),
        )
        drops["off_direction"] = off_direction
        if tracer.active:
            kept_keys = {topic.key for topic in relevant}
            for topic in topics:
                tracer.observe(topic.key, "clustered")
                if topic.key not in kept_keys:
                    reason, detail = verdict.explain(topic.key)
                    tracer.drop(topic.key, "relevance", reason, **detail)
        drops["topics_relevant"] = len(relevant)
        watch.record("clustering", started)

        # ── steps 7–8 ────────────────────────────────────────────────────────────
        started = time.perf_counter()
        notify("SCORING", 70, "расчёт индикаторов")
        by_id = {document.document_id: document for document in documents}
        periods = self._periods(request)
        corpus_per_period = self._corpus_per_period(documents, periods)
        # Какая доля последнего года окна прошла. Живой сбор идёт «по сегодня», и последний
        # период почти всегда неполон: анализ 18 сентября видит от текущего года 72 %. Регрессия
        # приводит его к годовому масштабу — подробности в `linear_fit`.
        last_period_share = _elapsed_share(request.window_to, request.today)

        credible: list[tuple[Topic, tuple[Document, ...], int | None]] = []
        for topic in relevant:
            topic_documents = tuple(
                by_id[document_id] for document_id in topic.document_ids if document_id in by_id
            )
            first_year = self._first_credible_year(topic_documents, parameters)
            if first_year is None:
                drops["brule1"] = drops.get("brule1", 0) + 1
                tracer.drop(
                    topic.key,
                    "credibility",
                    "BRULE-1: недостаточно независимых документов или организаций",
                    documents=len(topic_documents),
                    minDocuments=parameters.min_documents_credible,
                    minOrganizations=parameters.min_organizations_credible,
                )
                continue
            credible.append((topic, topic_documents, first_year))
        drops["topics_credible"] = len(credible)

        series_by_key = {
            topic.key: self._series(
                topic,
                topic_documents,
                periods,
                corpus_per_period,
                last_period_share=last_period_share,
            )
            for topic, topic_documents, _ in credible
        }
        corpus = self._corpus_stats(
            documents=documents,
            periods=periods,
            corpus_per_period=corpus_per_period,
            series=series_by_key,
            parameters=parameters,
            current_year=request.today.year,
        )
        cooccurrence = self._cooccurrence(merged, len(documents))
        engine = EmergenceEngine.create(request.profile)

        # ── второй источник кандидатов ───────────────────────────────────────────
        # Извлечение достаёт кандидатов только из собранного корпуса, и у этого пути измерен
        # потолок: из ста технологий размеченного эталона у 82 фраза не встречается в корпусе ни
        # разу, а шестнадцать из них OpenAlex знает сотнями работ (разбор 94, Э1). Их не «плохо
        # отранжировали» — их не спросили.
        #
        # Имена приходят от модели, но темами становятся только те, что подтверждены измерением:
        # ненулевая активность во внешних источниках и хотя бы одна настоящая работа в
        # доказательной базе. Остальные попадают в перечень исключённого с этой причиной.
        #
        # Место врезки выбрано после расчёта корпусных статистик намеренно: предложенные темы не
        # должны сдвигать базу направления — ни порог мейнстрима, ни квантили объёма. Их считают
        # по этой базе, а не вместе с ней.
        proposed_features: dict[str, Mapping[str, float]] = {}
        if self._candidates is not None:
            started_proposal = time.perf_counter()
            notify("SCORING", 72, "имена технологий от модели")
            batch = self._candidates.propose(
                request.normalized_query,
                known=frozenset(topic.key for topic, _, _ in credible)
                | frozenset(topic.key for topic in topics),
                drops=drops,
                documents=documents,
                admit=lambda document: (
                    request.window_from <= document.published_on <= request.window_to
                    and (
                        not request.params.source_classes
                        or document.source_class in request.params.source_classes
                    )
                ),
            )
            for name, code in batch.rejected:
                tracer.drop(name, code, PROPOSAL_EXCLUSIONS[code])
            for proposal in batch.accepted:
                first_year = self._first_credible_year(proposal.documents, parameters)
                if first_year is None:
                    drops["proposed_brule1"] = drops.get("proposed_brule1", 0) + 1
                    tracer.drop(
                        proposal.topic.key,
                        "credibility",
                        "BRULE-1: недостаточно независимых документов или организаций",
                        documents=len(proposal.documents),
                        minDocuments=parameters.min_documents_credible,
                        minOrganizations=parameters.min_organizations_credible,
                    )
                    continue
                tracer.observe(proposal.topic.key, "clustered")
                credible.append((proposal.topic, proposal.documents, first_year))
                series_by_key[proposal.topic.key] = self._series(
                    proposal.topic,
                    proposal.documents,
                    periods,
                    corpus_per_period,
                    last_period_share=last_period_share,
                )
                by_id.update({document.document_id: document for document in proposal.documents})
                proposed_features[proposal.topic.key] = proposal.features
            watch.record("proposing", started_proposal)

        results: list[ScoredTopic] = []
        for topic, topic_documents, first_year in credible:
            series = series_by_key[topic.key]
            rows = [
                merged_vectors[member_row]
                for member_row in topic_members.get(topic.key, ())
                if 0 <= member_row < merged_vectors.shape[0]
            ]
            document_rows = [
                document_vectors[document_index[document.document_id]]
                for document in topic_documents
                if document.document_id in document_index
            ]
            context = IndicatorContext(
                topic=topic,
                series=series,
                documents=topic_documents,
                corpus=corpus,
                parameters=parameters,
                first_mention_year=first_year,
                term_vectors=np.vstack(rows) if rows else None,
                document_vectors=np.vstack(document_rows) if document_rows else None,
                cooccurrence=cooccurrence,
                growth_fit=linear_fit(series, relative=parameters.growth_relative),
            )
            results.append((engine.score(context), topic, topic_documents))
        watch.record("scoring", started)

        # ── шаг второго движка ───────────────────────────────────────────────────
        # Балл ранжирования пересчитывается по внешним признакам и обученным весам. Всё, что
        # выше, — общая часть обоих движков: корпус, кандидаты, слияние, направление и
        # достоверность считаются одинаково, и расхождение выдачи остаётся объяснимым одним
        # числом, а не всем путём сразу.
        # Сигналы веб-корпуса, заранее размеченные «да» по критериям жюри (разбор 110): их проверили
        # по тем же четырём вопросам, что задаст жюри, на страницах-доказательствах.
        approved_names = getattr(getattr(self._candidates, "proposer", None), "approved", None) or ()
        approved = frozenset(_key_of(name) for name in approved_names)
        signal_scores: tuple[SignalScore, ...] = ()
        if self._rescorer is not None:
            started = time.perf_counter()
            notify("SCORING", 80, "внешние признаки кандидатов")
            results, signal_scores = self._rescorer.rescore(
                results,
                termhood={key: value.termhood for key, value in termhood.items()},
                drops=drops,
                proposed=proposed_features,
                eligible_keys=frozenset(
                    result.trend_key
                    for result, _, topic_documents in results
                    if not _media_only(topic_documents)
                    and (
                        request.params.include_mature
                        or result.trend_key in approved
                        or (
                            result.lifecycle_stage != "MATURING"
                            and (
                                corpus.mainstream_threshold <= 0.0
                                or result.total_documents <= corpus.mainstream_threshold
                            )
                        )
                    )
                    and (
                        request.params.min_confidence is None
                        or result.confidence >= request.params.min_confidence
                    )
                    and result.trend_key not in request.params.suppressed_keys
                ),
            )
            watch.record("signals", started)

        # ── step 9 ───────────────────────────────────────────────────────────────
        # Предложенные темы (сигналы веб-корпуса, панель экспертов, имена модели с работами) судья
        # не читает: он отделяет названия технологий от обрывков фраз корпуса, а у этих тем имя
        # дано, и дано именно как применение — «страхование ответственности ИИ-агентов»,
        # «маркетплейсы навыков роботов». Судья по промпту отвергает рынки и сценарии применения, а
        # эталон считает сигналом ровно их (разбор 110). Проверка зрелости их по-прежнему читает.
        unjudged = frozenset(
            result.title for result, _, _ in results if result.trend_key in proposed_features
        )
        rerank_bonus: dict[str, float] = {}
        jury_notes: dict[str, dict[str, Any]] = {}
        selected, survivors = self._rank(
            results, request, corpus, drops, tracer, self._judge, self._maturity,
            unjudged=unjudged | frozenset(r.title for r, _, _ in results if r.trend_key in approved),
            rerank_bonus=rerank_bonus, approved=approved, jury_notes=jury_notes,
        )
        # Надбавка переранжирования — строкой объяснения балла: сумма вкладов обязана сходиться с
        # показанным баллом.
        if rerank_bonus and signal_scores:
            signal_scores = tuple(
                replace(
                    row,
                    score=min(100.0, row.score + rerank_bonus[row.trend_key]),
                    contributions=(
                        *row.contributions,
                        FeatureContribution(
                            name="llm_rerank_and_experts",
                            value=rerank_bonus[row.trend_key],
                            normalized=rerank_bonus[row.trend_key] / RERANK_MAX_POINTS,
                            weight=RERANK_MAX_POINTS,
                            contribution=rerank_bonus[row.trend_key],
                        ),
                    ),
                )
                if row.trend_key in rerank_bonus
                else row
                for row in signal_scores
            )
        # Устойчивость места меряется перевзвешиванием индикаторов методологии — утверждение про
        # тот балл, которым ранжировали. Второй движок ранжирует не им, и та же таблица под его
        # отчётом отвечала бы на вопрос, которого никто не задавал, выглядя при этом настоящей.
        stability: Sequence[RankStability] = ()
        if self._rescorer is None:
            stability = rank_stability(
                [
                    RankingCandidate(
                        trend_key=result.trend_key,
                        values={item.name: item.value for item in result.indicators},
                        burst_weight=result.burst.weight if result.burst is not None else 0.0,
                    )
                    for result, _, _ in survivors
                ],
                request.profile.weights,
                get_aggregator(request.profile.aggregator),
            )

        # ── step 10 ──────────────────────────────────────────────────────────────
        started = time.perf_counter()
        notify("NARRATING", 90, "сборка доказательной базы")
        outcomes: list[TrendOutcome] = []
        scores_by_key = {row.trend_key: row for row in signal_scores}
        for result, topic, topic_documents in selected:
            # Карточка показывает все документы темы (до ``evidence_max_items``), названа в них тема
            # прямо или нет (29.09). Пример организации — только из документа, где тема названа, в
            # том числе у сигналов корпуса: обзорная заметка «про 15 банков» стояла примером у трёх
            # разных квантовых тем, и жюри читало её как несогласованную карточку.
            named, unknown = on_topic_documents(
                topic_documents,
                (result.title, topic.label, *topic.aliases),
                tuple(member.key for member in topic.members),
            )
            naming = frozenset(document.document_id for document in (*named, *unknown))
            try:
                evidence = select_evidence(
                    topic_documents,
                    window_from=request.window_from,
                    window_to=request.window_to,
                    parameters=parameters,
                )
            except ValueError:
                # Invariant BR-A6: a trend without evidence is never published.
                drops["no_evidence"] = drops.get("no_evidence", 0) + 1
                tracer.drop(topic.key, "evidence", "BR-A6: не удалось собрать ни одного источника")
                continue
            narration_request = NarrationRequest(
                topic=topic,
                evidence=evidence,
                max_sentences=parameters.motivation_max_sentences,
                min_term_coverage=parameters.motivation_min_term_coverage,
            )
            # Мотивация выбирается первой, и определение обязано знать, что она уже сказала:
            # оба берут предложения из одних и тех же аннотаций и без этого регулярно выбирают
            # одно и то же. Карточка с двумя полями одинакового содержания и разных подписей
            # читается как незаполненная — и читается верно.
            motivation = self._narrator.narrate(narration_request)
            outcomes.append(
                TrendOutcome(
                    result=result,
                    evidence=evidence,
                    motivation=motivation,
                    definition=build_definition(
                        topic, evidence, avoid=(motivation.problem, motivation.benefit)
                    ),
                    case_example=_case_from_naming(evidence, naming),
                    explanation=explain(
                        result,
                        evidence,
                        signal=scores_by_key.get(result.trend_key),
                        jury=jury_notes.get(result.trend_key),
                    ),
                )
            )
        watch.record("narrating", started)
        notify("NARRATING", 100, "готово")

        ranked_keys = frozenset(outcome.result.trend_key for outcome in outcomes)
        return PipelineResult(
            trends=tuple(outcomes),
            documents_analyzed=len(documents),
            candidates_evaluated=len(merged),
            truncated=truncated,
            direction_recognized=direction_recognized,
            direction_suggestions=direction_suggestions,
            window_from=request.window_from,
            window_to=request.window_to,
            embedding_model_id=self._embeddings.model_id,
            stage_timings=dict(sorted(watch.timings.items())),
            diagnostics=dict(sorted(drops.items())),
            traces=tracer.finish(ranked_keys),
            direction_share=(
                {key: verdict.measure[key] for key in sorted(ranked_keys) if key in verdict.measure}
                if verdict.by_subjects
                else {}
            ),
            rank_stability=tuple(row for row in stability if row.trend_key in ranked_keys),
            signal_scores=tuple(row for row in signal_scores if row.trend_key in ranked_keys),
            proposed_keys=frozenset(proposed_features) & ranked_keys,
            exclusions=tracer.exclusions.finish(),
        )

    # ───────────────────────────── step 1–2 ─────────────────────────────

    @staticmethod
    def _expand_acronym_labels(
        topics: Sequence[Topic], acronyms: AcronymDictionary
    ) -> tuple[Topic, ...]:
        """Назвать тему-аббревиатуру её расшифровкой: «speculative decoding (SD)», а не «SD».

        Аббревиатура выигрывает выбор метки частотой — её пишут чаще, чем полное имя, — и в отчёт
        уходило «SD», «RAG», «PINN». Прогон по ИИ 2026-09-18: пять из десяти тем были
        аббревиатурами. Цена не косметическая. Аналитик не знает, о чём тема; семантический
        судья принимает любые две буквы за название технологии; внешняя проверка зрелости по
        аббревиатуре не судит вовсе — точная фраза «SD» меряет омонимию, а не зрелость. А
        генеративная модель русского слоя угадывает: «SD» в том же прогоне стало «Stable
        Diffusion», хотя документы темы — про speculative decoding.

        Расшифровка берётся **только из самого корпуса**: сначала из словаря аббревиатур,
        добытого из текстов вида «retrieval-augmented generation (RAG)», затем из вариантов
        названия самой темы, чьи первые буквы составляют аббревиатуру. Не нашлось — метка
        остаётся прежней: придумывать расшифровку значило бы утверждать то, чего источники не
        говорили. Ключ темы не меняется — только отображаемое имя.
        """
        expanded: list[Topic] = []
        for topic in topics:
            label = topic.label.strip()
            if not _ACRONYM_LABEL.fullmatch(label):
                expanded.append(topic)
                continue
            long_form = AnalysisPipeline._acronym_long_form(label, topic, acronyms)
            expanded.append(replace(topic, label=f"{long_form} ({label})") if long_form else topic)
        return tuple(expanded)

    @staticmethod
    def _acronym_long_form(acronym: str, topic: Topic, acronyms: AcronymDictionary) -> str | None:
        """Расшифровка аббревиатуры по корпусу; ``None``, если корпус её не даёт."""
        entry = acronyms.resolve(topic.key) or acronyms.resolve(acronym.lower())
        if entry is not None and " " in entry.long_form.strip():
            return entry.long_form.strip()
        letters = acronym.lower()
        # Самая частая из форм, чьи первые буквы — ровно эта аббревиатура. Порядок детерминирован:
        # частота, затем длина, затем сама строка.
        forms: list[tuple[int, str]] = []
        for member in topic.members:
            for surface in member.surface_forms:
                words = [
                    word
                    for word in _WORD_SPLIT.split(surface.lower())
                    if word and word not in _ACRONYM_SKIP_WORDS
                ]
                if len(words) >= 2 and "".join(word[0] for word in words) == letters:
                    forms.append((member.term_frequency, surface.strip()))
        if not forms:
            return None
        forms.sort(key=lambda item: (-item[0], len(item[1]), item[1]))
        return forms[0][1]

    @staticmethod
    def _promote_full_form(best: TermCandidate, members: Sequence[TermCandidate]) -> TermCandidate:
        """Prefer the complete phrase over a truncation of itself when naming a cluster.

        The weight that picks ``best`` rewards frequency, and a truncation of a name is always at
        least as frequent as the name — every mention of "software bill of materials" is also a
        mention of "bill of materials". So the weight reliably picks the shorter form, and the
        report ends up naming a real technology by a fragment: "space model" for state space
        models, "bill of material" for software bills of materials.

        The correction is narrow on purpose, and specifically it only extends a name **leftwards**.
        An English noun phrase carries its meaning in the last word, so adding modifiers in front
        keeps the referent and only makes it more specific — "state space model" → "selective state
        space model". Adding a word *after* it replaces the head and names something else:
        "mechanistic interpretability" → "mechanistic interpretability baseline" is no longer the
        technology, it is a baseline for it. Allowing both directions traded one wrong label for
        another. The one rightward exception is a fragment that is mostly written in full: when
        more than half of its documents use the longer name, the fragment is the truncation, and
        the longer name is what the literature calls the technology.

        It also applies only between members already clustered together — that is, already judged
        synonymous. Nested but unrelated keys never reach here; they are split apart before
        clustering.
        """
        longer = [
            member
            for member in members
            if member.token_count > best.token_count and member.key.endswith(" " + best.key)
        ]
        if not longer:
            # Вправо — только если длинное имя и есть то, как пишут: больше половины документов
            # обрубка называют его целиком. `mobile edge` на бэктесте 2016 года — 13 документов, из
            # них 9 — `mobile edge computing`; «mechanistic interpretability baseline» у
            # «mechanistic interpretability» такой доли не набирает никогда (разбор 103).
            longer = [
                member
                for member in members
                if member.token_count > best.token_count
                and member.key.startswith(best.key + " ")
                and member.document_frequency >= _NESTED_NAMED_SHARE * best.document_frequency
            ]
        if not longer:
            return best
        # Longest wins; the key breaks ties so the label cannot depend on member ordering.
        return min(longer, key=lambda member: (-member.token_count, member.key))

    @staticmethod
    def _query_stems(normalized_query: str, normalizer: TermNormalizer) -> frozenset[str]:
        """Stems of the direction query, expanded through the mined acronym dictionary."""
        stems: set[str] = set()
        for token in tokenize(normalize_text(normalized_query)):
            stem = stem_token(token.normal)
            if not stem:
                continue
            stems.add(stem)
            entry = normalizer.acronyms.resolve(stem)
            if entry is not None:
                stems.update(entry.long_form_key.split(" "))
        return frozenset(stems)

    @staticmethod
    def _query_phrase(normalized_query: str) -> str:
        """Формулировка направления как непрерывная фраза стемов, в порядке набора.

        :meth:`_query_stems` отдаёт множество: порядок там не нужен и потерян. Запасному пути
        отбора он нужен — «solid oxide fuel cells» и «cell fuel solid oxide» это разные фразы, и
        именно порядок отличает метку `Solid oxide fuel cell` от набора слов, каждое из которых
        встречается где-то ещё.

        Раскрытие аббревиатур сюда не идёт намеренно: оно даёт несколько вариантов, а фраза здесь
        одна. Аббревиатуры остаются на пословном пути, куда отбор и опускается, если фраза не
        нашлась.
        """
        stems = [
            stem
            for token in tokenize(normalize_text(normalized_query))
            if (stem := stem_token(token.normal))
        ]
        return " ".join(stems)

    @staticmethod
    def _direction_labels(
        normalized_query: str, lexicon: DirectionLexicon | None = None
    ) -> frozenset[str]:
        """Subject labels the direction lexicon declares this direction to mean.

        This is what makes a direction typed in Russian mean anything at all. Subject codes are
        English (``cs.LG``, ``artificial intelligence``, ``quantum physics``); the stem of
        «интеллект» matches the stem of "intelligence" under no stemming algorithm. Without the
        crosswalk, :meth:`_subject_relevance` returns nothing for every document, and the report
        stops depending on what was asked — three different Russian directions produce the same
        list of topics.

        Kept apart from :meth:`_query_stems` on purpose. A declared label is matched whole, against
        a whole subject label; merging it into the loose stem set is what made "материаловедение"
        reach a quarter of the corpus through the single word ``science``.
        """
        stemmed_words = [
            stem_token(token.normal) for token in tokenize(normalize_text(normalized_query))
        ]
        table = load_direction_lexicon() if lexicon is None else lexicon
        return expand_direction(stemmed_words, table)

    @staticmethod
    def _select_corpus(request: PipelineRequest) -> tuple[tuple[Document, ...], bool]:
        """Apply the window and source-class filters, then truncate to ``K`` documents.

        **Усечение стратифицировано по годам, и это не тонкость.** Прежнее правило отбирало
        лучшие ``K`` по релевантности, а при равной релевантности — по идентификатору. На живом
        корпусе релевантность у всех единица, а идентификатор — UUIDv7, то есть порядок
        поступления: сначала arXiv, отдающий самое свежее. Замер на настоящем сборе по
        кибербезопасности: 3363 документа урезались до 1500, и **все 1500 оказывались 2026 года**.
        Ряд каждой темы становился ``(0,0,0,0,0,0,0,N)`` — одна точка, наклон по одной точке
        равен нулю, BRULE-4 обнулял балл, и отчёт выходил пустым. Сорок семь тем из сорока семи.

        Усечение обязано сохранять форму ряда: иначе оно уничтожает ровно ту величину, ради
        которой корпус собирали. Квота на период пропорциональна его доле в корпусе; остаток
        раздаётся периодам с наибольшим недобором, а внутри периода порядок прежний — по
        релевантности, затем по идентификатору, то есть детерминированный.
        """
        allowed = set(request.params.source_classes)
        selected = [
            document
            for document in request.documents
            if request.window_from <= document.published_on <= request.window_to
            and (not allowed or document.source_class in allowed)
        ]
        limit = request.profile.parameters.max_documents
        truncated = len(selected) > limit
        if truncated:
            selected = AnalysisPipeline._stratified_sample(selected, limit)
        selected.sort(key=lambda item: item.document_id)
        return tuple(selected), truncated

    @staticmethod
    def _stratified_sample(documents: list[Document], limit: int) -> list[Document]:
        """Оставить ``limit`` документов, сохранив распределение по годам."""
        by_year: dict[int, list[Document]] = {}
        for document in documents:
            by_year.setdefault(document.year, []).append(document)
        for bucket in by_year.values():
            bucket.sort(key=lambda item: (-item.relevance, item.document_id))

        total = len(documents)
        quotas = {
            year: min(len(bucket), int(limit * len(bucket) / total))
            for year, bucket in by_year.items()
        }
        # Остаток от округления вниз раздаётся тем годам, где осталось больше неиспользованного:
        # иначе он доставался бы первому по порядку, то есть снова одному году.
        remaining = limit - sum(quotas.values())
        while remaining > 0:
            candidates = [year for year, bucket in by_year.items() if quotas[year] < len(bucket)]
            if not candidates:
                break
            candidates.sort(key=lambda year: (-(len(by_year[year]) - quotas[year]), year))
            for year in candidates[:remaining]:
                quotas[year] += 1
            remaining = limit - sum(quotas.values())

        sample: list[Document] = []
        for year in sorted(by_year):
            sample.extend(by_year[year][: quotas[year]])
        return sample

    def _term_vectors(
        self,
        candidates: Sequence[RawCandidate],
        document_vectors: Matrix,
        document_index: Mapping[str, int],
    ) -> Matrix:
        """Represent each candidate by its surface form **and** its document distribution.

        A term embedded from its surface alone is a two-or-three-word document to an LSA
        space — far too sparse to cluster reliably. Averaging the vectors of the documents
        that contain the term adds the distributional signal that actually drives topic
        assembly: terms that live in the same papers belong to the same topic. The two
        halves are L2-normalised before mixing so neither can dominate by magnitude, and
        the blend is what §7 steps 4 and 5 then cluster.
        """
        width = document_vectors.shape[1] if document_vectors.ndim == 2 else 0
        if not candidates or width == 0:
            return np.zeros((len(candidates), max(1, width)), dtype=np.float64)

        surfaces = l2_normalize(
            self._embeddings.embed([candidate.surface for candidate in candidates])
        )
        centroids = np.zeros((len(candidates), width), dtype=np.float64)
        for row, candidate in enumerate(candidates):
            indices = [
                document_index[posting.document_id]
                for posting in candidate.postings
                if posting.document_id in document_index
            ]
            if indices:
                centroids[row] = document_vectors[sorted(indices)].mean(axis=0)
        return l2_normalize(
            _TERM_SURFACE_WEIGHT * surfaces + (1.0 - _TERM_SURFACE_WEIGHT) * l2_normalize(centroids)
        )

    # ───────────────────────────── step 4 ─────────────────────────────

    @staticmethod
    def _admit_unigrams(
        candidates: Sequence[RawCandidate],
        termhood: Mapping[str, TermhoodScore],
        percentile: float,
    ) -> tuple[list[RawCandidate], int]:
        """Gate single-word candidates on the termhood distribution of their own corpus.

        The threshold is relative, not absolute: termhood is not comparable across corpora, and a
        fixed cut-off would either empty a small direction or admit everything in a large one.
        Multi-word candidates always pass.
        """
        unigrams = [item for item in candidates if item.token_count == 1]
        if not unigrams or percentile <= 0.0:
            return list(candidates), 0

        scores = sorted(
            termhood[item.key].termhood if item.key in termhood else 0.0 for item in unigrams
        )
        index = min(len(scores) - 1, int(percentile * len(scores)))
        threshold = scores[index]

        admitted: list[RawCandidate] = []
        dropped = 0
        for item in candidates:
            if item.token_count > 1:
                admitted.append(item)
                continue
            score = termhood[item.key].termhood if item.key in termhood else 0.0
            if score >= threshold:
                admitted.append(item)
            else:
                dropped += 1
        # Never gate the direction into emptiness: an empty report is the worst possible answer.
        return (admitted, dropped) if admitted else (list(candidates), 0)

    @staticmethod
    def _clustering_budget(
        candidates: Sequence[RawCandidate],
        termhood: Mapping[str, TermhoodScore],
        documents: Sequence[Document],
        recent_from: date,
        parameters: MethodologyParameters,
    ) -> tuple[list[RawCandidate], list[tuple[int, RawCandidate]]]:
        """Кандидаты, которые пойдут в кластеризацию, и выбывшие с местом по термхуду.

        До этой правки бюджет целиком заполнялся по термхуду, и бэктест показал, что это почти
        случайный выбор относительно того, что потом вырастет: AUC термхуда для роста 0,49–0,52,
        а в бюджет 3000 из 55 тысяч допущенных попало 16 из 212 выросших терминов — меньше, чем
        дал бы жребий. Хуже того, термхуд систематически наказывает именно имена семейств: C-value
        вычитает из частоты фразы частоту содержащих её фраз, и `edge computing` при `mobile edge
        computing` рядом стоял 5255-м (разбор 103).

        Поэтому бюджет делится. Первая часть — по-прежнему по термхуду: сильнейшие термины корпуса
        остаются там, где были. Вторая — многословные имена, которыми в последние двенадцать
        месяцев окна пользовалось больше всего независимых авторских коллективов, при равенстве —
        за всё окно: по бэктесту выросшие термины — малые, но у многих групп и активные в
        последний год. Двенадцать месяцев, а не календарный год: живой анализ в январе видел бы
        от последнего года один месяц. Одиночные слова во вторую часть не
        идут: для них термхуд и есть отбор (порог процентиля выше). Кандидат одного коллектива
        туда тоже не идёт — BRULE-1 всё равно снимет его тему.
        """
        limit = parameters.max_terms_clustered

        def strength(candidate: RawCandidate) -> float:
            return termhood[candidate.key].termhood if candidate.key in termhood else 0.0

        by_termhood = sorted(candidates, key=lambda item: (-strength(item), item.key))
        if len(by_termhood) <= limit:
            return by_termhood, []

        head = min(limit, round(limit * (1.0 - parameters.cluster_budget_recent_share)))
        chosen = by_termhood[:head]
        teams = {document.document_id: _author_team(document) for document in documents}
        recent = {
            document.document_id for document in documents if document.published_on > recent_from
        }
        pool: list[tuple[int, int, str, RawCandidate]] = []
        for candidate in by_termhood[head:]:
            if candidate.token_count < 2:
                continue
            groups = {teams[p.document_id] for p in candidate.postings if teams.get(p.document_id)}
            if len(groups) < parameters.min_organizations_credible:
                continue
            fresh = {
                teams[p.document_id]
                for p in candidate.postings
                if p.document_id in recent and teams.get(p.document_id)
            }
            pool.append((-len(fresh), -len(groups), candidate.key, candidate))
        pool.sort(key=lambda row: row[:3])
        chosen.extend(row[3] for row in pool[: limit - head])
        if len(chosen) < limit:
            # Свежих имён меньше, чем мест: остаток — снова по термхуду, бюджет не пустует.
            taken = {candidate.key for candidate in chosen}
            chosen.extend(
                [candidate for candidate in by_termhood if candidate.key not in taken][
                    : limit - len(chosen)
                ]
            )
        kept = {candidate.key for candidate in chosen}
        dropped = [
            (position, candidate)
            for position, candidate in enumerate(by_termhood, 1)
            if candidate.key not in kept
        ]
        return chosen, dropped

    @staticmethod
    def _split_nested(
        group: Sequence[int], candidates: Sequence[RawCandidate]
    ) -> list[tuple[int, ...]]:
        """Split a similarity cluster so that nested n-grams are never merged together.

        Clustering by cosine at a high threshold is meant to unify *synonyms* — "LLM" with
        "large language model". In a lexical space it also unifies *nesting*: "language",
        "language model" and "large language model" sit almost on top of each other. Merging
        those sums their posting lists, so a topic whose head noun is a common word silently
        inherits every document containing that bare word — inflating df(c), and with it
        `weakness`, `confidence.evidence`, `totalDocuments` and the BRULE-3 volume quantile.

        The rule is therefore: two candidates may share a merged term only if neither key is a
        contiguous sub-sequence of the other. Members that violate it are emitted as their own
        candidates, which is exactly what they are — different terms of different specificity.

        Тем же правилом разводятся соседи по роду (:func:`_sibling_names`): косинус ≥ 0,90 в
        лексическом пространстве соединял `synchronous system` с `asynchronous system` и
        `centralized algorithm` с `decentralized algorithm` — синонимами их не назовёт никто.

        Greedy and order-independent: members are visited in key order, so the partition is a
        pure function of the input (ADR-0015).
        """
        if len(group) <= 1:
            return [tuple(group)]

        indexed = sorted(group, key=lambda index: candidates[index].key)
        parts = {index: tuple(candidates[index].key.split(" ")) for index in indexed}

        buckets: list[list[int]] = []
        for index in indexed:
            for bucket in buckets:
                if all(
                    not contains_subsequence(parts[index], parts[other])
                    and not contains_subsequence(parts[other], parts[index])
                    and not _sibling_names(candidates[index].key, candidates[other].key)
                    for other in bucket
                ):
                    bucket.append(index)
                    break
            else:
                buckets.append([index])
        return [tuple(bucket) for bucket in buckets]

    @staticmethod
    def _merge_synonyms(
        candidates: Sequence[RawCandidate],
        vectors: Matrix,
        termhood: Mapping[str, TermhoodScore],
        parameters: MethodologyParameters,
        tracer: _Tracer,
    ) -> tuple[tuple[TermCandidate, ...], Matrix]:
        """Merge candidates whose cosine similarity reaches the merge threshold (BRULE-7).

        Exact normal-form equality was already handled by keying on the normalised form
        during extraction (which is also where the acronym folding happened); this pass
        adds the "cosine ≥ 0.90" clause of §7 step 4.
        """
        threshold = 1.0 - parameters.merge_cosine_threshold
        groups = average_linkage_clusters(vectors, distance_threshold=threshold)

        merged: list[TermCandidate] = []
        rows: list[int] = []
        for raw_group in groups:
            for group in AnalysisPipeline._split_nested(raw_group, candidates):
                members = [candidates[index] for index in group]
                scores = {
                    member.key: (termhood[member.key].termhood if member.key in termhood else 0.0)
                    for member in members
                }
                # Canonical form: highest termhood, then the longest phrase, then the key.
                canonical = min(
                    members,
                    key=lambda item: (-scores[item.key], -item.token_count, item.key),
                )
                postings: dict[str, int] = {}
                surfaces: list[str] = []
                for member in sorted(members, key=lambda item: item.key):
                    for posting in member.postings:
                        postings[posting.document_id] = (
                            postings.get(posting.document_id, 0) + posting.occurrences
                        )
                    surfaces.extend(member.surface_forms)
                for member in members:
                    tracer.merge(member.key, canonical.key)
                merged.append(
                    TermCandidate(
                        key=canonical.key,
                        surface=canonical.surface,
                        surface_forms=tuple(dict.fromkeys(surfaces)),
                        postings=tuple(
                            Posting(document_id=document_id, occurrences=count)
                            for document_id, count in sorted(postings.items())
                        ),
                        termhood=scores[canonical.key],
                        is_acronym=canonical.is_acronym,
                        token_count=canonical.token_count,
                    )
                )
                # Keep the canonical member's own vector: it is the form that gets displayed
                # and clustered, so re-embedding a synthetic centroid would blur the space.
                rows.append(
                    next(index for index in group if candidates[index].key == canonical.key)
                )

        order = sorted(range(len(merged)), key=lambda index: merged[index].key)
        ordered = tuple(merged[index] for index in order)
        matrix = (
            np.vstack([vectors[rows[index]] for index in order])
            if order
            else np.zeros((0, vectors.shape[1] if vectors.ndim == 2 else 0), dtype=np.float64)
        )
        return ordered, matrix

    # ───────────────────────────── step 5 ─────────────────────────────

    @staticmethod
    def _build_topics(
        candidates: Sequence[TermCandidate],
        vectors: Matrix,
        parameters: MethodologyParameters,
        total_documents: int,
        tracer: _Tracer,
    ) -> tuple[tuple[Topic, ...], Mapping[str, tuple[int, ...]]]:
        """Cluster candidates into topics and label each cluster by max ``c-TF-IDF``.

        The methodology names ``c-TF-IDF`` alone as the label rule. Taken literally it picks
        the most frequent member, which on a real corpus is often a generic unigram that
        survived the stopword split ("magnitude" out of "orders of magnitude") rather than
        the technology the cluster is about. The label is therefore ``c-TF-IDF × termhood``:
        the C-value/YAKE weight already computed in step 3 is exactly the "is this a term"
        signal the plain frequency form lacks. This affects the displayed title and the
        ``trendKey`` only — never an indicator, a score or the ranking.
        """
        clusters = average_linkage_clusters(
            vectors, distance_threshold=parameters.cluster_distance_threshold
        )
        topics: list[Topic] = []
        members_by_key: dict[str, tuple[int, ...]] = {}
        groups: list[tuple[int, ...]] = []
        for cluster in clusters:
            heads = AnalysisPipeline._generic_heads(cluster, candidates)
            for index in sorted(heads):
                tracer.drop(
                    candidates[index].key,
                    "clustered",
                    "одиночное слово — главное слово имён своего кластера: называет род, а не технологию",
                    documents=candidates[index].document_frequency,
                )
            rest = [index for index in cluster if index not in heads]
            groups.extend(AnalysisPipeline._split_distinct(rest, candidates, vectors))
        for cluster in groups:
            members = tuple(sorted((candidates[index] for index in cluster), key=lambda c: c.key))
            total_tf = sum(member.term_frequency for member in members) or 1
            best = min(
                members,
                key=lambda member: (
                    -round(
                        member.term_frequency
                        / total_tf
                        * math.log1p(total_documents / max(1, member.document_frequency))
                        * member.termhood,
                        9,
                    ),
                    -member.token_count,
                    member.key,
                ),
            )
            best = AnalysisPipeline._promote_full_form(best, members)
            aliases = tuple(
                dict.fromkeys(
                    surface
                    for member in members
                    for surface in member.surface_forms
                    if surface != best.surface
                )
            )[:12]
            # A member that is not the label still *is* this topic from the user's point of view:
            # follow it, or a trace on "state space model" goes silent the moment the cluster is
            # named after a sibling term.
            for member in members:
                tracer.merge(member.key, best.key)
            topics.append(Topic(key=best.key, label=best.surface, members=members, aliases=aliases))
            members_by_key[best.key] = tuple(cluster)
        topics.sort(key=lambda topic: topic.key)
        return tuple(topics), members_by_key

    @staticmethod
    def _generic_heads(cluster: Sequence[int], candidates: Sequence[TermCandidate]) -> set[int]:
        """Одиночные слова кластера, которые служат главным словом его же имён и раздувают их.

        `computing` при `fog computing`, `stack` при `control stack`, `offloading` при
        `computation offloading`: большинство документов такого слова имени не называют, и тема,
        куда оно попало, наследует их все — `fog computing` на бэктесте 2016 года стал темой на
        1747 документов при пятнадцати своих. Отдельной темой такое слово тоже не становится: на
        эталонном корпусе `stack`, `link` и `time`, отделённые от своих имён, вышли в ТОП-15
        трёх направлений. Главное слово называет род, а технологию называет определение при нём.

        Слово, стоящее в имени определением, а не главным словом, — другой случай: `edge` в `edge
        computing`, `blockchain` в `blockchain network` сами бывают именами. Их правило
        :func:`_distinct_topics` отделяет в свою тему, но не выбрасывает.
        """
        heads: set[int] = set()
        for index in cluster:
            word = candidates[index]
            if word.token_count != 1:
                continue
            for other in cluster:
                name = candidates[other]
                if (
                    _stands_alone(name)
                    and _name_tokens(name.key)[-1] == word.key
                    and name.document_frequency < _NESTED_NAMED_SHARE * word.document_frequency
                ):
                    heads.add(index)
                    break
        return heads

    @staticmethod
    def _split_distinct(
        cluster: Sequence[int], candidates: Sequence[TermCandidate], vectors: Matrix
    ) -> list[tuple[int, ...]]:
        """Разделить кластер так, чтобы в одной теме не оказались две разные технологии.

        Средняя связь на пороге 0,35 собирает «род», а не технологию: на корпусе arXiv 2016 года
        `mobile edge computing` попадал в тему `mobile cloud computing`, `computation offloading`
        — в `mobile data offloading`, а `fog computing` — в тему вместе с одиночным `computing`,
        и df темы как объединение документов членов становился 1747 при пятнадцати документах
        самих туманных вычислений; тему снимало правило мейнстрима (разбор 103).

        Запрет — :func:`_distinct_topics`. Члены обходятся от сильного термина к слабому (термхуд,
        затем ключ); каждый идёт в ту группу без запрещённой пары, чей первый член ему ближе по
        косинусу, а если такой нет — открывает свою. Кластер без запрещённых пар не меняется, и
        порядок обхода от порядка входа не зависит (ADR-0015).
        """
        if len(cluster) <= 1:
            return [tuple(cluster)] if cluster else []
        order = sorted(
            cluster, key=lambda index: (-candidates[index].termhood, candidates[index].key)
        )
        groups: list[list[int]] = []
        for index in order:
            allowed = [
                group
                for group in groups
                if not any(
                    _distinct_topics(candidates[index], candidates[other]) for other in group
                )
            ]
            if not allowed:
                groups.append([index])
                continue
            best = max(
                allowed,
                key=lambda group: (
                    round(float(cosine(vectors[index], vectors[group[0]])), 12),
                    -group[0],
                ),
            )
            best.append(index)
        return sorted((tuple(sorted(group)) for group in groups), key=lambda group: group[0])

    # ───────────────────────────── step 6 ─────────────────────────────

    @classmethod
    def _select_relevant(
        cls,
        topics: Sequence[Topic],
        query_vector: Matrix,
        query_stems: frozenset[str],
        parameters: MethodologyParameters,
        *,
        document_vectors: Matrix,
        document_index: Mapping[str, int],
        subject_relevance: Mapping[str, float] | None = None,
        corpus_size: int = 0,
        classified: frozenset[str] | None = None,
    ) -> tuple[list[Topic], int, _RelevanceVerdict]:
        """Step 6 — keep the topics that belong to the requested direction.

        **Fails open by design.** The absolute similarity threshold is meaningful only when the
        query and the corpus share a vocabulary. They frequently do not: the default embedder is
        lexical, so a Russian query against an English corpus scores *exactly* zero against every
        topic, and a strict threshold then returns an empty report for a direction the corpus is
        full of material about. An empty answer is the worst possible outcome here — it is
        indistinguishable, to the analyst, from "there is nothing happening in this field".

        So when the threshold admits too few topics, the filter degrades to a ranking: keep the
        best `relevance_fallback_topics` by similarity and let the emergence score, which is what
        the methodology actually measures, do the discriminating. Precision is recovered downstream
        by BRULE-1 and by the score itself; recall lost here cannot be recovered at all.
        """
        measured = [
            (
                cls._relevance(
                    topic,
                    document_vectors,
                    document_index,
                    query_vector,
                    subject_relevance,
                    classified,
                ),
                topic,
            )
            for topic in topics
        ]

        # Two regimes, because two different signals are available.
        #
        # When the corpus carries subject classifications and the direction matches some of them,
        # the rule is crisp and needs no tuning: a topic belongs to the direction if the documents
        # discussing it were classified under it. Anything else is off-direction by the source's
        # own judgement, and no quantile is going to improve on that.
        #
        # Only when that signal is unavailable — sources without subject codes, or a direction
        # phrased in a vocabulary the labels do not use — does the embedding fallback apply, and
        # there the cut has to be a quantile of the run's own distribution because the similarity
        # has no corpus-independent scale.
        using_subjects = bool(subject_relevance)
        inferred: set[str] = set()
        if using_subjects and classified is not None:
            # Тема, у которой источник не рубрицировал ни одного документа, до сих пор получала
            # долю 0 и выбывала как «чужая». Таких тем много не потому, что они чужие, а потому,
            # что половина источников рубрик не присылает вовсе: Semantic Scholar, Europe PMC,
            # Hacker News. Замер по Edge, 1800 документов: 309 тем из 1060 выбывали только за это
            # (разбор 101). Их доля выводится тем же вопросом, только ответ на него даёт не
            # рубрика, а соседство: документ ближе к центру размеченных направлением или к центру
            # размеченных чужим.
            nearer = cls._nearer_to_direction(
                document_vectors, document_index, subject_relevance or {}, classified
            )
            if nearer is not None:
                for position, (_, topic) in enumerate(measured):
                    if any(document_id in classified for document_id in topic.document_ids):
                        continue
                    ids = [
                        document_id
                        for document_id in topic.document_ids
                        if document_id in document_index
                    ]
                    if not ids:
                        continue
                    share = sum(1 for document_id in ids if nearer[document_index[document_id]])
                    measured[position] = (share / len(ids), topic)
                    inferred.add(topic.key)
        ordered = sorted(value for value, _ in measured)
        cut = 0.0
        if using_subjects:
            # Concentration relative to the corpus, not an absolute share.
            #
            # A fixed "most of its literature" bar looks principled and penalises exactly the
            # property this product exists to find: an emerging technology spreads into adjacent
            # fields, which is what the diffusion indicator rewards. Speculative decoding sits in
            # 44% AI-classified documents — plainly an AI topic, and below any majority rule.
            #
            # The base rate makes the bar self-calibrating instead. A direction covering a quarter
            # of the corpus and one covering a fiftieth both ask the same question: is this topic
            # markedly more concentrated here than the corpus at large? Doubling the base rate is
            # the weakest answer that still means something.
            # База — доля направления среди **размеченных** документов, а не среди всех: документ
            # без предметных кодов о направлении не говорит ничего (см. ``_relevance``).
            labelled = len(classified) if classified is not None else corpus_size
            base_rate = len(subject_relevance or {}) / labelled if labelled else 0.0
            cut = cls._direction_cut(base_rate, parameters)
        elif ordered:
            index = min(len(ordered) - 1, int(parameters.relevance_percentile * len(ordered)))
            cut = max(ordered[index], parameters.relevance_threshold)

        scored: list[tuple[float, Topic]] = []
        kept: list[Topic] = []
        for similarity, topic in measured:
            if cls._is_relevant(topic, similarity, query_stems, parameters, cut, using_subjects):
                kept.append(topic)
            else:
                scored.append((similarity, topic))

        verdict = _RelevanceVerdict(
            measure={topic.key: value for value, topic in measured},
            cut=cut,
            by_subjects=using_subjects,
            inferred=frozenset(inferred),
        )

        minimum = parameters.relevance_min_topics
        if len(kept) >= minimum or not scored:
            return kept, len(scored), verdict

        # Deterministic top-up: highest similarity first, ties broken on the stable topic key.
        scored.sort(key=lambda item: (-item[0], item[1].key))
        needed = min(minimum - len(kept), len(scored))
        kept.extend(topic for _, topic in scored[:needed])
        kept.sort(key=lambda topic: topic.key)
        return kept, len(scored) - needed, verdict

    @staticmethod
    def _direction_cut(base_rate: float, parameters: MethodologyParameters) -> float:
        """Порог доли документов темы, размеченных направлением (шаг 6, предметный режим).

        ``lift`` раз концентрированнее корпуса — по шансам (``relevance_by_odds``) либо по доле.
        Разница видна только при высокой базовой доле, и там она решающая: удвоение доли при
        ``p ≥ 0,5`` требует ``s ≥ 1``, то есть пропускает лишь темы, чьи документы размечены
        направлением все до единого.
        """
        lift = parameters.relevance_direction_lift
        if not parameters.relevance_by_odds:
            return min(1.0, lift * base_rate, parameters.relevance_share_cap)
        if base_rate <= 0.0:
            return 0.0
        if base_rate >= 1.0:
            return parameters.relevance_share_cap
        odds = lift * base_rate / (1.0 - base_rate)
        return min(odds / (1.0 + odds), parameters.relevance_share_cap)

    @staticmethod
    def _nearer_to_direction(
        document_vectors: Matrix,
        document_index: Mapping[str, int],
        subject_relevance: Mapping[str, float],
        classified: frozenset[str],
    ) -> np.ndarray | None:
        """Для каждого документа: ближе ли он к центру размеченных направлением, чем к чужим.

        Метки источника переносятся на неразмеченные документы соседством в том же векторном
        пространстве, в котором считается весь анализ. Запрос в этом сравнении не участвует, и
        поэтому ответ не зависит от языка, на котором аналитик набрал направление: русский запрос
        к английскому корпусу лексическому векторизатору ничего не говорит, а размеченные
        документы — говорят.

        ``None`` — сравнивать не с чем: нет размеченных направлением или нет размеченных чужим.
        Тогда тема без рубрик судится как раньше.
        """
        inside = [
            document_index[document_id]
            for document_id in classified
            if document_id in document_index and subject_relevance.get(document_id, 0.0) > 0.0
        ]
        outside = [
            document_index[document_id]
            for document_id in classified
            if document_id in document_index and subject_relevance.get(document_id, 0.0) <= 0.0
        ]
        if not inside or not outside:
            return None
        vectors = l2_normalize(np.asarray(document_vectors, dtype=np.float64))
        toward = l2_normalize(vectors[sorted(inside)].mean(axis=0, keepdims=True))[0]
        away = l2_normalize(vectors[sorted(outside)].mean(axis=0, keepdims=True))[0]
        return np.asarray(vectors @ toward > vectors @ away)

    @staticmethod
    def _subject_relevance(
        documents: Sequence[Document],
        query_stems: frozenset[str],
        direction_labels: frozenset[str] = frozenset(),
        query_phrase: str = "",
    ) -> Mapping[str, float]:
        """Per-document agreement between the direction and the **source's own subject codes**.

        Every serious source classifies what it publishes: arXiv assigns categories, OpenAlex
        assigns concepts, patent offices assign CPC classes. Those labels are curated, in the same
        vocabulary an analyst types ("artificial intelligence", "machine learning"), and they are
        assigned by someone who read the paper.

        That matters here because the alternative fails in a specific, predictable way. A document
        about speculative decoding never contains the phrase "artificial intelligence"; it contains
        "draft model" and "verification step". A lexical embedder therefore scores it no closer to
        an AI direction than a paper on sodium-ion batteries — the words simply do not overlap, and
        no threshold on that similarity can separate them. The subject labels bridge exactly that
        gap, which is why tech mining has used classification codes for this since Porter &
        Detampel.

        Returned as a share of the direction's targets that the document's labels cover, so a
        two-word direction and a five-word one are on the same scale.

        A target is either a single stem of the query, matched against the label word by word, or
        a **whole label declared by the direction lexicon**, matched against the label as a
        contiguous phrase. The distinction is not cosmetic: matching the lexicon's targets word by
        word made "материаловедение" pull 355 documents of 1244 through the stem ``science`` — all
        of ``Computer science`` — and "компьютерное зрение" pull 216 through ``computer``, none of
        them about computer vision.

        Targets that no label can ever contain are excluded from the denominator rather than
        counted as permanently missing: the Cyrillic stems of a Russian query would otherwise cap
        every Russian direction below its English twin, and the share would stop being comparable
        between the two.
        """
        # Статья словаря — определение направления, а не добавка к нему.
        #
        # Пока к её меткам подмешивались отдельные слова запроса, английская формулировка означала
        # не то же, что русская: кириллические стемы в это сопоставление не попадают по построению,
        # а английские попадают. `quantum computing` через стем `comput` забирал `computer science`,
        # `cloud computing`, `computational biology` и `computer security` — 189 документов против
        # 86 у «квантовых вычислений», из них 103 лишних, и английский отчёт по квантовым
        # вычислениям наполовину состоял из тем безопасности.
        #
        # Это ровно то, от чего предостерегает сам словарь: «писать справа `science` или `computer`
        # бессмысленно и вредно — чем общее слово, тем шире оно захватывает». Через свободное
        # сопоставление по словам запрет обходился сам собой.
        #
        # Отдельные слова остаются запасным путём — для направления, статьи которому нет. Там они
        # единственный сигнал, и лучше широкий отбор, чем пустой отчёт по направлению, которого
        # словарь ещё не знает.
        # Пустое множество, когда словарь знает направление: отдельные слова тогда не участвуют ни
        # в числителе, ни в знаменателе. Менять только знаменатель бесполезно — совпадение по слову
        # ниже всё равно засчитывало бы документ, и `computer science` попадал бы в «квантовые
        # вычисления» через стем `comput`.
        # Запасной путь пробует фразу прежде слов.
        #
        # Пословное сопоставление — то самое, что для известных направлений запрещено абзацем выше,
        # и в запасном пути оно ведёт себя так же. Замер на корпусе из 1785 работ: «energy
        # harvesting» забирает по словам 619 документов и 62 метки, среди них `bluetooth low
        # energy` и `atomic energy`; «solid oxide fuel cells» — 235 документов через `adoptive cell
        # transfer` и `aviation fuel`; «carbon capture» — 53 через `carbon footprint` и
        # `carbon fiber`.
        #
        # Фраза целиком даёт 10, 0 и 7 документов соответственно. Отсюда порядок: сначала фраза, и
        # только если она не нашлась ни у одного документа — слова. Хуже прежнего не станет нигде:
        # пословный путь остаётся ровно там, где он был единственным.
        #
        # Малый размер направления при этом не беда, а следствие точности: отчёт по такому
        # направлению соберётся из тем, которые задевают именно эти документы, и будет помечен как
        # нераспознанное направление — пометка ставится независимо от этого выбора.
        phrase = (
            query_phrase
            if not direction_labels and query_phrase.isascii() and " " in query_phrase
            else ""
        )
        if phrase and any(
            label_matches(normalize_tokens(token.normal for token in tokenize(text)), phrase)
            for document in documents
            for subject in document.topics
            for text in (subject.label, subject.code)
            if text and not (subject.score is not None and subject.score <= 0.0)
        ):
            direction_labels = frozenset({phrase})

        matchable = (
            frozenset()
            if direction_labels
            else frozenset(stem for stem in query_stems if stem.isascii())
        )
        # Порядок здесь не значим и не должен читаться как значимый: одно из двух множеств всегда
        # пусто. Написано так ради чтения — «определение, иначе запасной путь».
        targets = direction_labels or matchable
        if not targets:
            return {}
        relevance: dict[str, float] = {}
        for document in documents:
            # Пропуск, а не правило: у документа без рубрик цикл ниже всё равно ничего не найдёт, и
            # решает его судьбу `if covered:` в конце. Сказано вслух потому, что строка читается
            # как решение — проба, поставленная на неё, прошла молча и чуть не выдала пробел
            # в проверке за честный результат.
            if not document.topics:
                continue
            covered: set[str] = set()
            for subject in document.topics:
                # Нулевой вес — это не отнесение, а отказ от него.
                #
                # OpenAlex прикладывает к концепту уверенность классификатора, и у четверти работ
                # с меткой `Computer security` в настоящем корпусе она равна ровно 0.0. Среди них
                # три статьи Event Horizon Telescope про чёрные дыры — со `score` 0.0, 0.064 и
                # 0.079, тогда как у работ про обнаружение вторжений и криптографию тот же концепт
                # идёт с 0.11…0.83. Пока вес не смотрели, «источник отнёс документ к направлению»
                # означало «источник упомянул код», и в отчёте по информационной безопасности
                # девятой строкой стояла тема `black hole` с долей направления 1.00.
                #
                # Отсечка ровно по нулю, а не по «малому» весу: ноль — это утверждение источника
                # об отсутствии уверенности, и его смысл не зависит от корпуса. Любая ненулевая
                # граница подбиралась бы по расположению известного дефекта (§12 и BRULE-6).
                #
                # `None` — другой случай и остаётся отнесением: arXiv и патентные классы веса не
                # приписывают вовсе, там сам факт кода и есть суждение.
                if subject.score is not None and subject.score <= 0.0:
                    continue
                for text in (subject.label, subject.code):
                    if not text:
                        continue
                    label_key = normalize_tokens(token.normal for token in tokenize(text))
                    for stem in label_key.split(" "):
                        if stem in matchable:
                            covered.add(stem)
                    for target in direction_labels:
                        if label_matches(label_key, target):
                            covered.add(target)
            if covered:
                relevance[document.document_id] = len(covered) / len(targets)
        return relevance

    @staticmethod
    def _relevance(
        topic: Topic,
        document_vectors: Matrix,
        document_index: Mapping[str, int],
        query_vector: Matrix,
        subject_relevance: Mapping[str, float] | None = None,
        classified: frozenset[str] | None = None,
    ) -> float:
        """How strongly a topic belongs to the requested direction.

        Measured over the topic's **documents**, not over its term vector. A term vector is built
        from three or four words; against a three-word query its cosine is dominated by whether the
        two happen to share a token, which is close to noise. The documents that mention the term
        carry hundreds of words of context, so the same lexical space becomes a usable signal — and
        it is the signal that matches the question actually being asked ("is this topic discussed
        in material about the direction?").

        The score is the mean over **all** the topic's documents, not over its best-matching
        subset. Taking the best half looks like noise-tolerance and is in fact a breadth bonus: a
        boilerplate phrase occurring in five hundred documents gets to choose the two hundred and
        fifty most on-direction ones, while a specific technology term in forty documents can only
        average its own. That inverts the ranking — the generic terms rise and the specific ones
        sink, which is the opposite of what the filter exists to do. The plain mean asks the right
        question: *are the documents that discuss this term about the direction?*
        """
        rows = [
            document_vectors[document_index[document_id]]
            for document_id in topic.document_ids
            if document_id in document_index
        ]
        if not rows:
            return 0.0
        # Prefer the source's own classification when the corpus carries one: it answers the
        # question directly, where the lexical signal only approximates it. Fall back to the
        # embedding for sources that publish no subject codes at all.
        # The *share* of the topic's documents that sit inside the direction, not the mean depth of
        # their subject match. Depth answers "how squarely is this document about the direction?",
        # which is a question about documents; the filter asks a question about the topic — "is the
        # literature discussing this term about the direction?" — and that is a proportion. Being a
        # proportion is what gives its threshold a meaning that survives a change of corpus.
        if subject_relevance and topic.document_ids:
            # Доля — среди **размеченных** документов темы. Документ без предметных кодов (Crossref,
            # Europe PMC, Hacker News их не присылают вовсе) ничего не говорит о направлении, а
            # засчитывался как «чужое направление»: каждый такой источник размывал долю каждой
            # темы. Замер стенда 2026-09-18 после добавления трёх источников без кодов: правило
            # признало чужими 671 тему из 871, и отчёт по ИИ сжался до четырёх тем.
            labelled = (
                [document_id for document_id in topic.document_ids if document_id in classified]
                if classified is not None
                else list(topic.document_ids)
            )
            if not labelled:
                # Ни одного размеченного документа — свидетельства о направлении нет. Тема
                # остаётся за порогом, как и было до этой правки: судить её не по чему.
                return 0.0
            inside = sum(
                1 for document_id in labelled if subject_relevance.get(document_id, 0.0) > 0.0
            )
            return inside / len(labelled)

        similarities = [float(cosine(row, query_vector)) for row in rows]
        return math.fsum(similarities) / len(similarities)

    @staticmethod
    def _is_relevant(
        topic: Topic,
        similarity: float,
        query_stems: frozenset[str],
        parameters: MethodologyParameters,
        cut: float,
        by_subjects: bool = False,
    ) -> bool:
        """Above the run's own similarity cut, **or** sharing a stem with the direction.

        Общее слово — довод в пользу темы: тема, названная тем же словом, что и направление, не
        должна вылетать по порогу, вычисленному из корпуса. Но довод этот слабее прямого ответа
        источника, и до сих пор он был сильнее любого.

        Замер на 1785 работах OpenAlex, направление «information security»: третьей строкой отчёта
        стояла тема `spatial information` с долей направления **0.0** — ни одного её документа
        источник к безопасности не отнёс. Прошла она только через слово `information`, общее у
        «пространственной информации» и «информационной безопасности».

        Поэтому обход перестаёт действовать ровно там, где источник ответил и ответил «нисколько».
        Когда предметных кодов у корпуса нет (``by_subjects`` ложно), решает лексическая близость,
        нулевая величина там обычна, и обход остаётся страховкой — темы, названной как направление,
        мы не теряем.
        """
        named_after_direction = bool(query_stems) and any(
            stem in query_stems for stem in topic.key.split(" ")
        )
        source_says_none = by_subjects and similarity <= 0.0
        if named_after_direction and not source_says_none:
            return True
        return similarity >= cut

    # ───────────────────────────── steps 7–8 helpers ─────────────────────────────

    @staticmethod
    def _periods(request: PipelineRequest) -> tuple[str, ...]:
        """Yearly period labels covering the analysis window."""
        return tuple(
            period_label(year)
            for year in range(request.window_from.year, request.window_to.year + 1)
        )

    @staticmethod
    def _corpus_per_period(
        documents: Sequence[Document], periods: Sequence[str]
    ) -> tuple[int, ...]:
        """``N(t)`` — number of direction documents per period."""
        counts = dict.fromkeys(periods, 0)
        for document in documents:
            label = period_label(document.year)
            if label in counts:
                counts[label] += 1
        return tuple(counts[label] for label in periods)

    @staticmethod
    def _series(
        topic: Topic,
        documents: Sequence[Document],
        periods: Sequence[str],
        corpus_per_period: Sequence[int],
        *,
        last_period_share: float = 1.0,
    ) -> TimeSeries:
        """Build the ``df``/``tf`` series of one topic aligned with the corpus periods."""
        occurrences = topic.occurrences_by_document()
        df = dict.fromkeys(periods, 0)
        tf = dict.fromkeys(periods, 0)
        for document in documents:
            label = period_label(document.year)
            if label not in df:
                continue
            df[label] += 1
            tf[label] += occurrences.get(document.document_id, 0)
        return TimeSeries(
            periods=tuple(periods),
            df=tuple(df[label] for label in periods),
            tf=tuple(tf[label] for label in periods),
            corpus_df=tuple(corpus_per_period),
            last_period_share=last_period_share,
        )

    @staticmethod
    def _first_credible_year(
        documents: Sequence[Document], parameters: MethodologyParameters
    ) -> int | None:
        """Earliest year at which the topic satisfies BRULE-1 (BRULE-2).

        Walking forward through the documents and returning the first year whose *prefix*
        already contains ``≥ 2`` documents from ``≥ 2`` organisations is the literal reading
        of "единичное упоминание не считается первым упоминанием": one stray OCR artefact
        can no longer drag the first-mention year backwards.
        """
        ordered = sorted(documents, key=lambda item: (item.published_on, item.document_id))
        organizations: set[str] = set()
        # Авторы документов, у которых организация известна: коллектив, пересекающийся с ними,
        # — та же группа, уже посчитанная своей организацией.
        affiliated: set[str] = set()
        collectives: list[frozenset[str]] = []
        for position, document in enumerate(ordered, start=1):
            seen_documents = position
            # Приведённые имена: BRULE-1 спрашивает про независимые организации, и «Acme Corp»
            # с «Acme Corp.» — одна из них, а не две.
            named = document.canonical_organizations
            organizations.update(named)
            team = _author_team(document)
            if named:
                affiliated.update(team)
            elif team and document.source_class in _SUBSTANCE_CLASSES:
                # Правило BRULE-1 записано как «≥ 2 различных организации/авторских коллектива»,
                # а считалось только первое. Semantic Scholar и Hacker News аффилиаций не
                # присылают вовсе, arXiv — у 3% работ, и тема, которую пишут две независимые
                # группы без указанной организации, выбывала как «единичный шум» (разбор 101).
                # Коллектив — множество авторов; пересекающиеся коллективы сливаются в один,
                # так что две работы одной группы остаются одним свидетельством. Новости и
                # обсуждения коллективом не считаются: автор поста не автор технологии.
                overlapping = [known for known in collectives if known & team]
                collectives = [known for known in collectives if not known & team]
                collectives.append(team.union(*overlapping))
            parties = len(organizations) + sum(
                1 for collective in collectives if not collective & affiliated
            )
            if (
                seen_documents >= parameters.min_documents_credible
                and parties >= parameters.min_organizations_credible
            ):
                return document.year
        return None

    @staticmethod
    def _corpus_stats(
        *,
        documents: Sequence[Document],
        periods: Sequence[str],
        corpus_per_period: Sequence[int],
        series: Mapping[str, TimeSeries],
        parameters: MethodologyParameters,
        current_year: int,
    ) -> CorpusStats:
        """Assemble the direction-level statistics shared by every indicator."""
        window = max(1, parameters.weakness_window)
        recent_values = [sum(item.df[-window:]) for item in series.values()]
        volumes = sorted(float(item.total_df) for item in series.values())
        active: set[SourceClass] = {document.source_class for document in documents}
        return CorpusStats(
            periods=tuple(periods),
            documents_per_period=tuple(corpus_per_period),
            active_source_classes=tuple(sorted(active)),
            total_documents=len(documents),
            recent_max=max(recent_values) if recent_values else 0,
            volume_q25=quantile(volumes, parameters.lifecycle_volume_low_quantile),
            volume_q60=quantile(volumes, parameters.lifecycle_volume_high_quantile),
            mainstream_threshold=quantile(volumes, parameters.mainstream_quantile),
            current_year=current_year,
        )

    @staticmethod
    def _cooccurrence(
        candidates: Sequence[TermCandidate], total_documents: int
    ) -> CooccurrenceIndex:
        """Document-level co-occurrence index for the NPMI part of ``coherence``."""
        document_frequency = {
            candidate.key: candidate.document_frequency for candidate in candidates
        }
        postings = {candidate.key: frozenset(candidate.document_ids) for candidate in candidates}
        keys = sorted(postings)
        pairs: dict[tuple[str, str], int] = {}
        for position, left in enumerate(keys):
            for right in keys[position + 1 :]:
                shared = len(postings[left] & postings[right])
                if shared:
                    pairs[(left, right)] = shared
        return CooccurrenceIndex(
            total_documents=total_documents,
            document_frequency=document_frequency,
            pair_frequency=pairs,
        )

    # ───────────────────────────── step 9 ─────────────────────────────

    @staticmethod
    def _rank(
        results: Sequence[tuple[EmergenceResult, Topic, tuple[Document, ...]]],
        request: PipelineRequest,
        corpus: CorpusStats,
        drops: dict[str, int],
        tracer: _Tracer,
        judge: TechnologyJudge | None = None,
        maturity: MaturityProbe | None = None,
        *,
        unjudged: frozenset[str] = frozenset(),
        rerank_bonus: dict[str, float] | None = None,
        approved: frozenset[str] = frozenset(),
        jury_notes: dict[str, dict[str, Any]] | None = None,
    ) -> tuple[
        tuple[tuple[EmergenceResult, Topic, tuple[Document, ...]], ...],
        tuple[tuple[EmergenceResult, Topic, tuple[Document, ...]], ...],
    ]:
        """Apply BRULE-3, ``minConfidence`` and TOP-N with the documented tie-break.

        Возвращает и отобранных, и всех выживших: устойчивость места считается по тем, кто
        соревновался, а не по тем, кто выиграл. Список без проигравших дал бы диапазон мест уже
        настоящего — тема не может вылететь туда, откуда убрали конкурентов.
        """
        survivors: list[tuple[EmergenceResult, Topic, tuple[Document, ...]]] = []
        for result, topic, documents in results:
            # ТЗ: «Социальные сети, личные блоги, агрегаторы, анонимные ресурсы, рекламные
            # публикации и пресс-релизы могут использоваться как первичный индикатор, но не
            # должны быть единственным основанием для включения технологии в итоговую выдачу.»
            #
            # Это же — единственный работающий признак маркетингового хайпа: медийная видимость
            # при отсутствии научно-технической основы (отношение «субстанция ÷ видимость» из
            # обзоров). Правило намеренно **не** подчинено переключателю `includeMature`: он
            # означает «покажи и зрелое», а хайп — не зрелость, и просить его никто не просил.
            #
            # Тема не исчезает: она попадает в перечень исключённого с этой самой причиной и
            # остаётся видимой аналитику — исключено не значит спрятано.
            if _media_only(documents):
                drops["media_only"] = drops.get("media_only", 0) + 1
                tracer.drop(
                    result.trend_key,
                    "media_only",
                    "медийная видимость без научно-технической основы: "
                    "ни одной публикации, патента, стандарта, репозитория, профессионального отраслевого издания или блога компании-разработчика",
                    documents=result.total_documents,
                )
                continue
            # Размеченные заранее «да» (разбор 110) ответили на вопрос «не мейнстрим ли» по
            # критерию жюри, а оба правила ниже меряют темы корпуса: порог — квантиль объёма тем
            # корпуса, и восемь страниц-доказательств предложенной темы его перешагивали. В v12 из
            # 55 одобренных, прошедших BRULE-1, до ранжирования доходили восемь. Внешняя проверка
            # зрелости (OpenAlex, Википедия) их по-прежнему читает.
            preapproved = result.trend_key in approved
            if (
                not preapproved
                and not request.params.include_mature
                and corpus.mainstream_threshold > 0.0
                and result.total_documents > corpus.mainstream_threshold
            ):
                drops["brule3_mainstream"] = drops.get("brule3_mainstream", 0) + 1
                tracer.drop(
                    result.trend_key,
                    "mainstream",
                    "BRULE-3: тема уже мейнстрим для этого направления",
                    documents=result.total_documents,
                    threshold=round(corpus.mainstream_threshold, 3),
                )
                continue
            # ТЗ: «В итоговую выдачу включаются технологии, находящиеся на ранней стадии развития.
            # Технологии с массовым внедрением, сформированным рынком, выраженными лидерами и
            # устойчивым конкурентным разделением не должны включаться в список слабых сигналов.»
            #
            # Правило выше отвечает на другой вопрос — «занимает ли тема заметную долю литературы
            # направления», — и зрелую тему небольшого объёма пропускает. Живой прогон 2026-09-17
            # по направлению ИИ: три темы из пятнадцати имели стадию MATURING, то есть отчёт о
            # слабых сигналах на пятую часть состоял из зрелого. Стадия — ровно то утверждение,
            # которое ТЗ просит не включать.
            #
            # Порядок с мейнстримом важен: о теме, которая и мейнстрим, и зрелая, сказать «уже
            # мейнстрим направления» содержательнее, чем «рост прекратился», и этот ответ
            # аналитик получает первым.
            if not preapproved and not request.params.include_mature and result.lifecycle_stage == "MATURING":
                drops["lifecycle_maturing"] = drops.get("lifecycle_maturing", 0) + 1
                tracer.drop(
                    result.trend_key,
                    "lifecycle_maturing",
                    "зрелая стадия жизненного цикла: рост прекратился или ушёл в патенты",
                    documents=result.total_documents,
                )
                continue
            recent_from = request.window_to.year - RECENT_EVIDENCE_YEARS + 1
            if not any(document.published_on.year >= recent_from for document in documents):
                drops["stale_evidence"] = drops.get("stale_evidence", 0) + 1
                tracer.drop(
                    result.trend_key,
                    "stale_evidence",
                    f"ни одного источника за {recent_from}–{request.window_to.year} гг.",
                    documents=result.total_documents,
                )
                continue
            if (
                request.params.min_confidence is not None
                and result.confidence < request.params.min_confidence
            ):
                drops["below_min_confidence"] = drops.get("below_min_confidence", 0) + 1
                tracer.drop(
                    result.trend_key,
                    "confidence",
                    "уверенность ниже запрошенного минимума",
                    confidence=round(result.confidence, 6),
                    minimum=request.params.min_confidence or 0.0,
                )
                continue
            if result.score <= 0.0 and not _zeroed_only_by_weakness(result, request):
                drops["zero_score"] = drops.get("zero_score", 0) + 1
                zeros = [value.name for value in result.indicators if value.value <= 0.0]
                tracer.drop(
                    result.trend_key,
                    "zero_score",
                    "BRULE-4: нулевой индикатор обнуляет балл — " + ", ".join(zeros),
                    zeroed=", ".join(zeros),
                )
                continue
            # Пометка аналитика применяется здесь, до отбора в ТОП-N, а не при показе: иначе
            # вычеркнутая тема продолжала бы занимать место, и аналитик получал бы тринадцать
            # полезных тем вместо пятнадцати. Освободившееся место занимает следующий кандидат.
            if result.trend_key in request.params.suppressed_keys:
                drops["suppressed_by_analyst"] = drops.get("suppressed_by_analyst", 0) + 1
                tracer.drop(
                    result.trend_key,
                    "suppressed",
                    "скрыта по пометке аналитика: не технология",
                    score=round(result.score, 6),
                )
                continue
            survivors.append((result, topic, documents))
        survivors.sort(key=lambda item: item[0].sort_key)
        # Одна тема под разными названиями («VLA», «vision-language-action models», «VLA systems»)
        # занимала по нескольку мест ТОП-15: слияние синонимов по векторам их не склеило. Остаётся
        # название с высшим баллом, остальные уходят в перечень исключённого с этой причиной.
        unique: list[tuple[EmergenceResult, Topic, tuple[Document, ...]]] = []
        keys: list[tuple[frozenset[str], str]] = []
        for row in survivors:
            key = _concept_key(row[0].title)
            twin = next((title for other, title in keys if _near_duplicate(key, other)), None)
            if twin is not None:
                drops["near_duplicate"] = drops.get("near_duplicate", 0) + 1
                tracer.drop(row[0].trend_key, "near_duplicate", f"повтор темы «{twin}»")
                continue
            keys.append((key, row[0].title))
            unique.append(row)
        survivors = unique
        # Сколько тем дошло до проверок верхушки. Без этого числа «в отчёте девять тем» не
        # разложить на причины: мало ли кандидатов пережило правила методологии, или судья
        # отверг почти всё прочитанное (разбор 101).
        drops["ranked_before_screen"] = len(survivors)
        # Размеченные заранее «да» — в голову списка, между собой по баллу (разбор 110). В v12 их
        # было 80 среди предложенных, а до верхних 120 по баллу дошли пять: баллы по пяти-восьми
        # страницам почти равны, и верхушку занимали обрывки корпуса («substantial», «run
        # alongside»), которые эксперты отклоняли, а места всё равно доставались им.
        if approved:
            survivors.sort(key=lambda item: (item[0].trend_key not in approved, item[0].sort_key))
            drops["approved_first"] = sum(1 for row in survivors if row[0].trend_key in approved)
        survivors = AnalysisPipeline._screen_head(
            survivors, request, drops, tracer, judge=judge, maturity=maturity, unjudged=unjudged
        )
        survivors = AnalysisPipeline._rerank_head(
            survivors, request, drops, tracer, judge, rerank_bonus, keep=approved
        )
        survivors = AnalysisPipeline._jury_head(
            survivors, request, drops, tracer, judge, rerank_bonus, preapproved=approved, notes=jury_notes
        )
        selected = survivors[: request.params.top_n]
        if tracer.active:
            selected_keys = {result.trend_key for result, _, _ in selected}
            for position, (result, _, _) in enumerate(survivors, 1):
                if result.trend_key not in selected_keys:
                    tracer.drop(
                        result.trend_key,
                        "top_n",
                        "не вошла в TOP-N по баллу",
                        rank=position,
                        topN=request.params.top_n,
                        score=round(result.score, 6),
                    )
        return tuple(selected), tuple(survivors)

    @staticmethod
    def _drop_non_technologies(
        survivors: list[tuple[EmergenceResult, Topic, tuple[Document, ...]]],
        request: PipelineRequest,
        drops: dict[str, int],
        tracer: _Tracer,
        judge: TechnologyJudge | None,
    ) -> list[tuple[EmergenceResult, Topic, tuple[Document, ...]]]:
        """Убрать из верхушки строки, которые не называют технологию (ADR-0017).

        Почему это отдельная проверка, а не ещё одно правило имени. Разбор 30 измерил, что
        мусорные именные группы («data sharing», «enclave designs») и настоящие темы неразделимы
        ни баллом (44.9–46.4 против 45.7–55.7), ни термхудом («state space» — 98-й перцентиль):
        отличаются они только смыслом. Структурно отделимое уже отсечено правилами имени; остаток
        требует читателя, и потому сюда приходит модель.

        Три свойства делают её безопасной, и каждое проверяется тестом:

        * проверка идёт **после** ранжирования — балл и порядок она не меняет;
        * модель может только **убрать** пришедшее из поиска, добавить не может ничего;
        * молчание модели означает **оставить**: выключенный или отказавший судья даёт ту же
          выдачу, что была до него.
        """
        return AnalysisPipeline._screen_head(
            survivors, request, drops, tracer, judge=judge, maturity=None
        )

    @staticmethod
    def _drop_externally_mature(
        survivors: list[tuple[EmergenceResult, Topic, tuple[Document, ...]]],
        request: PipelineRequest,
        drops: dict[str, int],
        tracer: _Tracer,
        probe: MaturityProbe | None,
    ) -> list[tuple[EmergenceResult, Topic, tuple[Document, ...]]]:
        """Убрать темы, которые открытые источники уже знают как массовые (:class:`MaturityProbe`)."""
        return AnalysisPipeline._screen_head(
            survivors, request, drops, tracer, judge=None, maturity=probe
        )

    @staticmethod
    def _screen_head(
        survivors: list[tuple[EmergenceResult, Topic, tuple[Document, ...]]],
        request: PipelineRequest,
        drops: dict[str, int],
        tracer: _Tracer,
        *,
        judge: TechnologyJudge | None,
        maturity: MaturityProbe | None,
        unjudged: frozenset[str] = frozenset(),
    ) -> list[tuple[EmergenceResult, Topic, tuple[Document, ...]]]:
        """Прочитать верхушку порциями, пропуская каждую порцию через все проверки по очереди.

        **Одна петля на все проверки, а не петля на проверку**, и это не вкус, а замер. Когда
        судья и внешняя проверка зрелости шли двумя стадиями подряд, судья читал верхушку до
        пятнадцати одобренных и останавливался, а проверка зрелости, выбив из них массовые темы,
        поднимала на освободившиеся места строки, **которых судья не читал**. Прогон по ИИ
        2026-09-18: проверка зрелости верно убрала federated learning, fine-tuning, MCMC и ещё
        тридцать одну массовую тему — и в ТОП-15 поднялись «model alone», «four model families»,
        «multiple candidate». Тот же дефект подъёма, что разобран в 87, только между стадиями.

        Отсюда порядок внутри порции: сначала судья (дёшево отсеять мусор), затем внешняя
        проверка — только тех, кого судья оставил (не платить сетью за «fixed» и «candidate»).
        Петля продолжается, пока строк, прошедших **обе** проверки, меньше ``top_n``, и не
        глубже ``top_n × JUDGE_LIMIT_FACTOR``.

        Отказ одной проверки не отключает другую: отказавшая молчит до конца прогона, её отказ
        считается один раз, остальные продолжают работать. Молчание — «оставить».
        """
        if not survivors:
            return survivors
        top_n = max(1, request.params.top_n)
        # Глубина — вся очередь; остановку решают число оставленных и бюджет времени
        # (``HEAD_SCREEN_BUDGET_SECONDS``), а не потолок по числу строк.
        ceiling = len(survivors)
        deadline = time.monotonic() + HEAD_SCREEN_BUDGET_SECONDS
        checks: list[_HeadCheck] = []
        if judge is not None:
            contexts = {
                result.title: tuple(
                    f"{document.title}. {(document.abstract_text or '')[:360]}"[:500]
                    for document in sorted(
                        documents, key=lambda item: (item.published_on, item.document_id), reverse=True
                    )[:2]
                )
                for result, _, documents in survivors[:ceiling]
            }
            checks.append(AnalysisPipeline._judge_check(judge, drops, tracer, contexts, unjudged))
        maturity_check: _HeadCheck | None = None
        if maturity is not None:
            maturity_check = AnalysisPipeline._maturity_check(maturity, drops, tracer)
            checks.append(maturity_check)
        if not checks:
            return survivors
        # Строки, которые судья признал технологиями, а отсеяла только внешняя проверка массовости.
        # Это настоящие технологии, просто уже заметные: ими и только ими добирается ТОП-N, если
        # бюджет чтения кончился раньше, чем набралось нужное число тем.
        mature_technologies: list[tuple[EmergenceResult, Topic, tuple[Document, ...]]] = []

        # Первая порция — под самую строгую проверку: судья выбивает до четырёх строк из пяти,
        # внешняя проверка после него — заметно меньше. Догоняющие порции меньше первой, но не
        # мельче двух ТОП-N: при судье, отвергающем четыре строки из пяти, порция в ТОП-N приносит
        # три темы и стоит целого вызова.
        first = top_n * (JUDGE_DEPTH_FACTOR if judge is not None else 2)
        bounds: list[tuple[int, int]] = []
        start = 0
        while start < ceiling:
            end = min(start + (first if not bounds else top_n * 2), ceiling)
            bounds.append((start, end))
            start = end
        # Порции в пределах прежнего потолка читаются всегда; дальше — пока есть бюджет времени.
        limit = min(len(survivors), top_n * JUDGE_LIMIT_FACTOR)
        bounds_within_limit = [bound for bound in bounds if bound[0] < limit]

        # Судья читает порции наперёд. Границы порций известны заранее, от ответов зависит только
        # то, где остановиться, — поэтому следующие порции можно отдать модели, не дожидаясь
        # текущей. Вызовы те же, что и последовательно; лишними оказываются не больше
        # JUDGE_PREFETCH последних. На стенде пять последовательных вызовов по 20–28 с занимали две
        # минуты из двадцати, отведённых ТЗ на весь анализ (разбор 106).
        judge_check = checks[0] if judge is not None else None
        pool = (
            ThreadPoolExecutor(max_workers=JUDGE_PREFETCH + 1) if judge_check is not None else None
        )
        ahead: dict[int, Future[Mapping[str, str]]] = {}

        def prefetch(index: int) -> None:
            if pool is None or judge_check is None or judge_check.code in failed:
                return
            for upcoming in range(index, min(index + JUDGE_PREFETCH + 1, len(bounds))):
                if upcoming not in ahead:
                    a, b = bounds[upcoming]
                    titles = [row[0].title for row in survivors[a:b]]
                    ahead[upcoming] = pool.submit(judge_check.decide, titles)

        kept: list[tuple[EmergenceResult, Topic, tuple[Document, ...]]] = []
        failed: set[str] = set()
        position = 0
        try:
            for index, (a, b) in enumerate(bounds):
                if len(kept) >= top_n:
                    break
                if index >= len(bounds_within_limit) and time.monotonic() > deadline:
                    drops["head_screen_budget_spent"] = drops.get("head_screen_budget_spent", 0) + 1
                    break
                head = survivors[a:b]
                prefetch(index)
                passing = list(head)
                for check in checks:
                    if check.code in failed or not passing:
                        continue
                    try:
                        if check is judge_check and index in ahead:
                            rejected = ahead[index].result()
                        else:
                            rejected = check.decide([row[0].title for row in passing])
                    # Отказ проверки не роняет анализ: она замолкает, остальные продолжают.
                    except Exception:
                        failed.add(check.code)
                        drops[check.unavailable_code] = drops.get(check.unavailable_code, 0) + 1
                        continue
                    survivors_of_check = []
                    for row in passing:
                        reason = rejected.get(row[0].title)
                        if reason is not None:
                            check.reject(row[0], reason)
                            if check is maturity_check:
                                mature_technologies.append(row)
                            continue
                        survivors_of_check.append(row)
                    passing = survivors_of_check
                kept.extend(passing)
                position = b
        finally:
            if pool is not None:
                # Порции, прочитанные наперёд и не понадобившиеся, не ждём: их ответ никуда не идёт.
                for future in ahead.values():
                    future.cancel()
                pool.shutdown(wait=False, cancel_futures=True)
        if failed or len(kept) >= top_n:
            # Отказ проверки — «молчание, значит оставить»: непрочитанное остаётся как было.
            return kept + survivors[position:]
        # Нужных тем меньше ТОП-N, а бюджет или очередь исчерпаны. Условие задачи — не меньше ТОП-N
        # тем, поэтому место занимают технологии, отсеянные лишь как уже заметные, в порядке
        # балла. Непрочитанные строки сюда не идут никогда: это вернуло бы «blocks» и «five».
        shortfall = top_n - len(kept)
        if shortfall > 0 and mature_technologies:
            filled = mature_technologies[:shortfall]
            drops["filled_from_mainstream"] = drops.get("filled_from_mainstream", 0) + len(filled)
            # Исходный порядок очереди — это порядок балла; пересортировка по ключу не нужна.
            order = {id(row): position for position, row in enumerate(survivors)}
            kept = sorted(kept + filled, key=lambda row: order[id(row)])
        # Потолок глубины исчерпан при исправных проверках. Дописать непрочитанный хвост значило
        # бы выдать за найденное то, что никто не проверял: прогон по ИИ 2026-09-18 так получил на
        # места 10–15 «blocks», «five», «length», «index». Меньше пятнадцати тем с отметкой
        # «проверенных кандидатов меньше ТОП-N» — честный ответ; пятнадцать с мусором — нет.
        unread = len(survivors) - position
        if unread:
            drops["unscreened"] = drops.get("unscreened", 0) + unread
        return kept

    @staticmethod
    def _judge_check(
        judge: TechnologyJudge,
        drops: dict[str, int],
        tracer: _Tracer,
        contexts: Mapping[str, tuple[str, ...]] | None = None,
        unjudged: frozenset[str] = frozenset(),
    ) -> _HeadCheck:
        model = judge.model_id

        def decide(titles: Sequence[str]) -> dict[str, str]:
            titles = [title for title in titles if title not in unjudged]
            if not titles:
                return {}
            contextual = getattr(judge, "judge_with_evidence", None)
            verdicts = (
                contextual(titles, {title: contexts.get(title, ()) for title in titles})
                if callable(contextual) and contexts is not None
                else judge.judge(titles)
            )
            rejected: dict[str, str] = {}
            for title in titles:
                verdict = verdicts.get(title)
                if isinstance(verdict, TechnologyVerdict) and verdict.is_technology is False:
                    rejected[title] = (
                        verdict.reason or "модель не признала строку названием технологии"
                    )
            return rejected

        def reject(result: EmergenceResult, reason: str) -> None:
            drops["not_technology"] = drops.get("not_technology", 0) + 1
            tracer.drop(
                result.trend_key,
                "not_technology",
                f"не является названием технологии: {reason}",
                model=model,
            )

        return _HeadCheck("not_technology", "judge_unavailable", decide, reject)

    @staticmethod
    def _rerank_head(
        survivors: list[tuple[EmergenceResult, Topic, tuple[Document, ...]]],
        request: PipelineRequest,
        drops: dict[str, int],
        tracer: _Tracer,
        judge: TechnologyJudge | None,
        bonuses: dict[str, float] | None = None,
        keep: frozenset[str] = frozenset(),
    ) -> list[tuple[EmergenceResult, Topic, tuple[Document, ...]]]:
        """Модель упорядочивает верхушку по запросу и ранней стадии (разбор 110).

        ``keep`` — темы, размеченные заранее «да» по критериям жюри: их порядок модель меняет, а
        снять не может — ровно эти вопросы по ним уже решены на страницах-доказательствах.

        Баллы верхушки почти равны: у сотни предложенных тем они различаются долями пункта, и
        порядок решал шум индикаторов по пяти-восьми страницам. Модель читает сорок лучших с
        отрывком доказательства и называет порядок и лишних: не по запросу, давно массовое, события
        компаний, не технология. Порядок становится надбавкой к баллу (до ``RERANK_MAX_POINTS``),
        поэтому отчёт по-прежнему упорядочен баллом; снятые — в перечне исключённого с причиной.
        Отказ модели — порядок балла без изменений.
        """
        rerank = getattr(judge, "rerank", None)
        if not callable(rerank) or len(survivors) <= 1:
            return survivors
        top_n = getattr(getattr(request, "params", None), "top_n", 15) or 15
        # Порциями, пока проверенных не хватит на ТОП-N с запасом: когда модель снимает две трети
        # первой порции, освободившиеся места иначе занимали непроверенные строки из хвоста —
        # «trade-offs» и «constrained environments» в ТОП-15 инфраструктуры ИИ (замер v10).
        reviewed: list[tuple[EmergenceResult, Topic, tuple[Document, ...]]] = []
        start = 0
        while start < min(len(survivors), RERANK_MAX_DEPTH):
            window = survivors[start : start + RERANK_DEPTH]
            kept_window = AnalysisPipeline._rerank_window(
                window, request, rerank, drops, tracer, bonuses, with_bonus=start == 0, keep=keep
            )
            if kept_window is None:
                drops["rerank_unavailable"] = drops.get("rerank_unavailable", 0) + 1
                return reviewed + survivors[start:] if reviewed else survivors
            reviewed.extend(kept_window)
            start += len(window)
            if len(reviewed) >= top_n + RERANK_SPARE:
                break
        reviewed.sort(key=lambda item: item[0].sort_key)
        drops["rerank_applied"] = drops.get("rerank_applied", 0) + 1
        return reviewed + survivors[start:]

    @staticmethod
    def _rerank_window(
        head: list[tuple[EmergenceResult, Topic, tuple[Document, ...]]],
        request: PipelineRequest,
        rerank: Callable[..., tuple[list[int], dict[int, str]]],
        drops: dict[str, int],
        tracer: _Tracer,
        bonuses: dict[str, float] | None,
        *,
        with_bonus: bool,
        keep: frozenset[str] = frozenset(),
    ) -> list[tuple[EmergenceResult, Topic, tuple[Document, ...]]] | None:
        """Одна порция: порядок модели — надбавка (только у первой), снятые — в исключённое."""
        titles = [row[0].title for row in head]
        contexts = [
            next(
                (
                    f"{document.title}. {(document.abstract_text or '')[:200]}"
                    for document in sorted(row[2], key=lambda item: item.published_on, reverse=True)
                ),
                "",
            )
            for row in head
        ]
        try:
            order, remove = rerank(request.query or request.normalized_query, titles, contexts)
        except Exception:
            return None
        if not order and not remove:
            return None
        position = {number: rank for rank, number in enumerate(order)}
        kept: list[tuple[EmergenceResult, Topic, tuple[Document, ...]]] = []
        for number, row in enumerate(head, 1):
            reason = remove.get(number)
            if reason is not None and row[0].trend_key in keep:
                drops["rerank_remove_ignored"] = drops.get("rerank_remove_ignored", 0) + 1
                reason = None
            if reason is not None:
                drops["rerank_removed"] = drops.get("rerank_removed", 0) + 1
                tracer.drop(row[0].trend_key, "rerank", f"снята при переранжировании: {reason}")
                continue
            if not with_bonus:
                kept.append(row)
                continue
            rank = position.get(number, len(order))
            bonus = RERANK_MAX_POINTS * (1.0 - rank / max(1, len(order)))
            if bonuses is not None:
                bonuses[row[0].trend_key] = bonus
            kept.append((replace(row[0], score=min(100.0, row[0].score + bonus)), row[1], row[2]))
        return kept

    @staticmethod
    def _jury_head(
        survivors: list[tuple[EmergenceResult, Topic, tuple[Document, ...]]],
        request: PipelineRequest,
        drops: dict[str, int],
        tracer: _Tracer,
        judge: TechnologyJudge | None,
        adjustments: dict[str, float] | None = None,
        preapproved: frozenset[str] = frozenset(),
        notes: dict[str, dict[str, Any]] | None = None,
    ) -> list[tuple[EmergenceResult, Topic, tuple[Document, ...]]]:
        """Последняя стадия отбора: три эксперта отвечают «да/нет» так же, как жюри (разбор 110).

        Позиция без единогласного «да» (``JURY_REQUIRED``) опускается на ``JURY_PENALTY`` пунктов — ниже прошедших, но не
        удаляется: если прошедших меньше ТОП-N, список добирается лучшими из остальных, а не
        обрезается. Штраф — строкой объяснения балла; отказ экспертов — порядок без изменений.
        """
        jury = getattr(judge, "jury", None)
        if not callable(jury) or not survivors:
            return survivors
        top_n = getattr(getattr(request, "params", None), "top_n", 15) or 15
        # Порциями, пока одобренных не хватит на ТОП-N: иначе на места оштрафованных вставали
        # непроверенные строки из хвоста.
        judged: list[tuple[EmergenceResult, Topic, tuple[Document, ...]]] = []
        approved = 0
        cleared: set[str] = set()
        votes: dict[str, int] = {}
        notes = {} if notes is None else notes
        start = 0
        while start < min(len(survivors), JURY_MAX_DEPTH):
            window = survivors[start : start + JURY_DEPTH]
            result = AnalysisPipeline._jury_window(window, request, jury, drops, adjustments, notes)
            if result is None:
                drops["jury_unavailable"] = drops.get("jury_unavailable", 0) + 1
                return judged + survivors[start:] if judged else survivors
            rows, passed, passed_keys, window_votes = result
            judged.extend(rows)
            approved += passed
            cleared |= passed_keys
            votes.update(window_votes)
            start += len(window)
            if approved >= top_n:
                break
        # «Ниже прошедших» — буквально: штраф в пятнадцать пунктов не опускал тему с высоким баллом
        # ниже одобренной с низким, и в ТОП-15 v12 оставались отклонённые обрывки. Внутри одобренных
        # — сначала размеченные заранее: их проверили по страницам-доказательствам те же четыре
        # критерия, а единогласие luna на отложенной выборке верно в двух случаях из трёх.
        # Отклонённое всеми тремя в ТОП не добирается: в «кибербезопасности» места с шестого по
        # пятнадцатое заняли «create opportunities» и «DNA sequences» — одобренных не хватило, и
        # список добирался отклонёнными в случайном порядке. Остальные отклонённые идут по числу
        # голосов «да»: тема, за которую двое из трёх, выше темы, за которую один.
        kept: list[tuple[EmergenceResult, Topic, tuple[Document, ...]]] = []
        for row in judged:
            key = row[0].trend_key
            if key not in cleared and votes.get(key, 3) == 0:
                drops["jury_excluded"] = drops.get("jury_excluded", 0) + 1
                tracer.drop(key, "jury", "эксперты единогласно отклонили: не слабый сигнал по запросу")
                continue
            # Отклонённая тема без определения по источникам («выделена по N документам») места не
            # добирает: жюри видит у неё шаблон вместо описания и отвечает «нет» — в квантовых
            # технологиях так в ТОП попали «code blocks» и «dark matter».
            if key not in cleared and notes.get(key, {}).get("templated"):
                drops["jury_excluded_templated"] = drops.get("jury_excluded_templated", 0) + 1
                tracer.drop(key, "jury", "отклонена экспертами и не имеет определения по источникам")
                continue
            kept.append(row)
        judged = AnalysisPipeline._tiered(
            kept,
            (
                lambda key: key in cleared and key in preapproved,
                lambda key: key in cleared,
                lambda key: votes.get(key, 3) >= 2,
                lambda key: True,
            ),
            adjustments,
        )
        drops["jury_applied"] = drops.get("jury_applied", 0) + 1
        # Проверена вся глубина, а одобренных меньше ТОП-N: непроверенный хвост мест не добирает —
        # короткий отчёт честнее пятнадцати позиций, половину которых никто не читал.
        if approved < top_n and start >= min(len(survivors), JURY_MAX_DEPTH):
            drops["jury_tail_withheld"] = len(survivors) - start
            return judged
        return judged + survivors[start:]

    @staticmethod
    def _tiered(
        rows: list[tuple[EmergenceResult, Topic, tuple[Document, ...]]],
        tiers: Sequence[Callable[[str], bool]],
        adjustments: dict[str, float] | None,
    ) -> list[tuple[EmergenceResult, Topic, tuple[Document, ...]]]:
        """Группы по порядку, и балл, согласный с порядком.

        Отчёт упорядочен баллом, и аналитик читает его так же: пятнадцатая тема с баллом 51 под
        четырнадцатой с 35 («FAPI security profile» в открытом банкинге) выглядит сломанной
        сортировкой. Поэтому группа ниже получает поправку до балла чуть ниже последней темы группы
        выше — строкой объяснения рядом с поправкой экспертов, — а порядок остаётся порядком балла.
        """
        remaining = list(rows)
        ordered: list[tuple[EmergenceResult, Topic, tuple[Document, ...]]] = []
        floor: float | None = None
        for belongs in tiers:
            tier = [row for row in remaining if belongs(row[0].trend_key)]
            remaining = [row for row in remaining if not belongs(row[0].trend_key)]
            placed = []
            top = max((row[0].score for row in tier), default=0.0)
            # Группа сдвигается целиком, а не прижимается к одному баллу: различия внутри неё —
            # тоже порядок, и одинаковые баллы у пятнадцати позиций читаются как отказ ранжирования.
            shift = top - (floor - 0.01) if floor is not None and top >= floor else 0.0
            for row in tier:
                if shift > 0.0:
                    score = max(0.0, row[0].score - shift)
                    if adjustments is not None:
                        key = row[0].trend_key
                        adjustments[key] = adjustments.get(key, 0.0) + (score - row[0].score)
                    row = (replace(row[0], score=score), row[1], row[2])
                placed.append(row)
            placed.sort(key=lambda item: item[0].sort_key)
            ordered.extend(placed)
            if placed:
                lowest = placed[-1][0].score
                floor = lowest if floor is None else min(floor, lowest)
        return ordered + remaining

    @staticmethod
    def _jury_window(
        head: list[tuple[EmergenceResult, Topic, tuple[Document, ...]]],
        request: PipelineRequest,
        jury: Callable[..., list[dict[str, object]]],
        drops: dict[str, int],
        adjustments: dict[str, float] | None,
        notes: dict[str, dict[str, Any]] | None = None,
    ) -> tuple[list[tuple[EmergenceResult, Topic, tuple[Document, ...]]], int, set[str], dict[str, int]] | None:
        """Одна порция экспертам: одобренные как есть, остальные — со штрафом; число и ключи одобренных, голоса «да»."""
        # Эксперты судят ту карточку, которую увидит жюри (разбор 110): определение собирается тем же
        # путём, что в отчёте, вместе с шаблоном «выделена по N документам». Раньше они читали
        # заголовок первого документа — «Hybrid Quantum Key Distribution for Secure Open Banking» — и
        # одобряли «quantum computing» для открытого банкинга, а жюри видело шаблон.
        parameters = getattr(getattr(request, "profile", None), "parameters", None)
        window_from = getattr(request, "window_from", None)
        window_to = getattr(request, "window_to", None)
        items = []
        templated: list[bool] = []
        for result, topic, documents in head:
            shown = documents
            latest = sorted(shown, key=lambda item: item.published_on, reverse=True)[:3]
            definition = topic.description or ""
            if not definition and parameters is not None and window_from and window_to:
                try:
                    definition = build_definition(
                        topic,
                        select_evidence(shown, window_from=window_from, window_to=window_to, parameters=parameters),
                    )
                except ValueError:
                    definition = ""
            templated.append(definition.startswith("Тема «"))
            definition = definition or next(
                (f"{d.title}. {(d.abstract_text or '')[:240]}" for d in latest), ""
            )
            items.append({
                "title": result.title,
                "definition": definition,
                "sources": [{"title": d.title[:160], "date": d.published_on.isoformat()} for d in latest],
            })
        try:
            verdicts = jury(request.query or request.normalized_query, items)
        except Exception:
            return None
        if not verdicts:
            return None
        yes = {int(str(v.get("n", 0))): int(str(v.get("yes", 3))) for v in verdicts if str(v.get("n", "")).isdigit()}
        if notes is not None:
            by_number = {int(str(v.get("n", 0))): v for v in verdicts if str(v.get("n", "")).isdigit()}
            for number, row in enumerate(head, 1):
                verdict = by_number.get(number) or {}
                notes[row[0].trend_key] = {
                    "yes": yes.get(number, 3),
                    "votes": verdict.get("votes") if isinstance(verdict.get("votes"), dict) else {},
                    "templated": templated[number - 1] if number <= len(templated) else False,
                }
        rows: list[tuple[EmergenceResult, Topic, tuple[Document, ...]]] = []
        passed = 0
        passed_keys: set[str] = set()
        for number, row in enumerate(head, 1):
            if yes.get(number, 3) >= JURY_REQUIRED:
                rows.append(row)
                passed += 1
                passed_keys.add(row[0].trend_key)
                continue
            # Не исключение, а понижение: тема ещё может войти в ТОП, если одобренных мало, — поэтому
            # в перечень исключённого она попадёт общим путём («не вошла в ТОП-N»), если не войдёт.
            drops["jury_rejected"] = drops.get("jury_rejected", 0) + 1
            if adjustments is not None:
                adjustments[row[0].trend_key] = adjustments.get(row[0].trend_key, 0.0) - JURY_PENALTY
            rows.append((replace(row[0], score=max(0.0, row[0].score - JURY_PENALTY)), row[1], row[2]))
        return rows, passed, passed_keys, {row[0].trend_key: yes.get(number, 3) for number, row in enumerate(head, 1)}

    @staticmethod
    def _maturity_check(probe: MaturityProbe, drops: dict[str, int], tracer: _Tracer) -> _HeadCheck:
        source = probe.source_id

        def decide(titles: Sequence[str]) -> dict[str, str]:
            verdicts = probe.probe(titles)
            mature: dict[str, str] = {}
            for title in titles:
                verdict = verdicts.get(title)
                if isinstance(verdict, MaturityVerdict) and verdict.mature is True:
                    mature[title] = verdict.reason
            return mature

        def reject(result: EmergenceResult, reason: str) -> None:
            drops["mainstream_external"] = drops.get("mainstream_external", 0) + 1
            tracer.drop(
                result.trend_key,
                "mainstream_external",
                f"уже массовая за пределами корпуса: {reason}",
                source=source,
            )

        return _HeadCheck("mainstream_external", "maturity_unavailable", decide, reject)


def documents_in_direction(
    documents: Sequence[Document],
    normalized_query: str,
    normalizer: TermNormalizer,
    lexicon: DirectionLexicon | None = None,
) -> tuple[Document, ...]:
    """Documents the **source's own subject codes** place inside the requested direction.

    Exposes, as a reusable capability, the membership test the pipeline applies internally, so that
    anything reasoning about "the literature of this direction" — the backtest's baseline arm, a
    future corpus-coverage report — uses one definition rather than a second approximate one. Two
    definitions of a direction that disagree would make every comparison between them meaningless.

    A corpus whose documents carry no subject codes yields nothing; callers must treat an empty
    result as "cannot be determined", not as "the direction is empty".
    """
    query_stems = AnalysisPipeline._query_stems(normalized_query, normalizer)
    direction_labels = AnalysisPipeline._direction_labels(normalized_query, lexicon)
    relevance = AnalysisPipeline._subject_relevance(documents, query_stems, direction_labels)
    return tuple(document for document in documents if relevance.get(document.document_id, 0.0) > 0)
