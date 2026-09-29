"""Extractive narrator — methodology §8, ADR-0010.

Sentences are selected from the abstracts of the topic's evidence documents by cue phrase,
scored as

``score = marker weight × topic-term coverage × document authority``

and the best one or two per part are quoted verbatim with an attribution to the evidence
item they came from. No text is ever generated: a hallucinated argument in front of an
investment committee is an unacceptable risk, and quoting is the only construction that is
traceable by design.
"""

from __future__ import annotations

import difflib
import math
import re
from collections.abc import Sequence
from dataclasses import dataclass
from typing import ClassVar, Final, Literal

from horizon_analytics.domain.evidence import EvidenceSelection
from horizon_analytics.domain.extraction.normalization import stem_token
from horizon_analytics.domain.extraction.tokenizer import normalize_text, split_sentences, tokenize
from horizon_analytics.domain.models import Motivation, MotivationAttribution, Topic, clamp01
from horizon_analytics.domain.narration.base import NarrationRequest
from horizon_analytics.domain.scoring.profile import prior_authority

__all__ = [
    "BENEFIT_MARKERS",
    "PROBLEM_MARKERS",
    "ExtractiveNarrator",
    "SentenceCandidate",
    "build_definition",
]

#: Problem cue phrases with their weights (methodology §8). Longer, more specific cues
#: outrank generic ones so that "remains challenging" beats a bare "however".
PROBLEM_MARKERS: Final[tuple[tuple[str, float], ...]] = (
    ("remains challenging", 1.0),
    ("remain challenging", 1.0),
    ("is limited by", 1.0),
    ("are limited by", 1.0),
    ("suffers from", 1.0),
    ("suffer from", 1.0),
    ("open problem", 1.0),
    ("bottleneck", 0.9),
    ("is still an open", 0.9),
    ("remains an open", 0.9),
    ("fails to", 0.85),
    ("fail to", 0.85),
    ("do not scale", 0.85),
    ("does not scale", 0.85),
    ("computationally expensive", 0.85),
    ("prohibitively", 0.85),
    ("difficult to", 0.8),
    ("hard to", 0.8),
    ("lack of", 0.8),
    ("lacks", 0.75),
    ("limitation", 0.75),
    ("drawback", 0.75),
    ("challenge", 0.7),
    ("however", 0.6),
    ("nevertheless", 0.6),
    ("остается проблемой", 1.0),
    ("остаются проблемой", 1.0),
    ("узкое место", 1.0),
    ("не позволяет", 0.9),
    ("не позволяют", 0.9),
    ("ограничен", 0.85),
    ("ограничивает", 0.85),
    ("недостатком", 0.8),
    ("недостаток", 0.8),
    ("сложность", 0.7),
    ("проблема", 0.7),
    ("однако", 0.6),
    ("тем не менее", 0.6),
)

#: Benefit cue phrases with their weights (methodology §8).
BENEFIT_MARKERS: Final[tuple[tuple[str, float], ...]] = (
    ("outperforms", 1.0),
    ("outperform", 1.0),
    ("state-of-the-art", 0.95),
    ("state of the art", 0.95),
    ("speedup", 0.95),
    ("speed-up", 0.95),
    ("achieves", 0.9),
    ("achieve", 0.9),
    ("we show", 0.9),
    ("we demonstrate", 0.9),
    ("we propose", 0.8),
    ("enables", 0.9),
    ("enable", 0.9),
    ("reduces", 0.9),
    ("reduce", 0.85),
    ("improves", 0.9),
    ("improve", 0.85),
    ("increases", 0.85),
    ("without sacrificing", 0.9),
    ("orders of magnitude", 0.95),
    ("significantly", 0.7),
    ("превосходит", 1.0),
    ("позволяет", 0.9),
    ("позволяют", 0.9),
    ("сокращает", 0.9),
    ("снижает", 0.9),
    ("повышает", 0.9),
    ("ускоряет", 0.9),
    ("обеспечивает", 0.85),
    ("демонстрирует", 0.8),
    ("предлагается", 0.7),
)

_MIN_SENTENCE_CHARS: Final[int] = 30
_MAX_SENTENCE_CHARS: Final[int] = 400


#: Доля более короткого предложения, которую должен покрывать общий непрерывный кусок, чтобы два
#: предложения считались одним утверждением.
#:
#: Порог не выведен из распределения: разделения в нём нет, шкала непрерывна. Он поставлен по
#: наблюдению — среди пар, которые при чтении оказались осмысленно разными, ни одна не делила
#: непрерывным куском больше 0.61 своих слов: общей у них была только рамка фразы («we show that the
#: design enables …»), а содержательная часть различалась. Пары, которые при чтении оказались одним
#: утверждением, делили 0.71 и выше — у них совпадало именно тело, а различался хвост.
#:
#: 0.70 стоит выше наблюдаемого максимума законных пар с запасом. Значение сознательно
#: консервативно: пропустить повтор дешевле, чем выбросить второе, действительно иное утверждение.
_SAME_STATEMENT_RUN = 0.70

_WORDS = re.compile(r"[\w-]+", re.UNICODE)


def _says_the_same(candidate: str, chosen: str) -> bool:
    """Говорят ли два предложения одно и то же.

    Мерой служит длиннейший общий непрерывный кусок в словах, а не сходство целиком. Так отделяется
    «то же тело, другой хвост» от «та же рамка, другое содержание»: у второго общее — только рамка,
    и она коротка.

    Сравнение по словам, а не по символам: посимвольная мера считает совпадением общие окончания и
    служебные слова, а они в научных аннотациях одинаковы почти везде.
    """
    left = [word.lower() for word in _WORDS.findall(candidate)]
    right = [word.lower() for word in _WORDS.findall(chosen)]
    if not left or not right:
        return False
    match = difflib.SequenceMatcher(None, left, right).find_longest_match(
        0, len(left), 0, len(right)
    )
    return match.size / min(len(left), len(right)) >= _SAME_STATEMENT_RUN


@dataclass(frozen=True, slots=True)
class SentenceCandidate:
    """One quotable sentence with its score decomposition."""

    text: str
    evidence_index: int
    marker: str
    marker_weight: float
    coverage: float
    authority: float
    score: float


def _match_marker(lowered: str, markers: Sequence[tuple[str, float]]) -> tuple[str, float]:
    """Best matching cue phrase in a sentence, or ``("", 0.0)``."""
    best_marker = ""
    best_weight = 0.0
    for marker, weight in markers:
        if marker in lowered and (
            weight > best_weight or (weight == best_weight and marker < best_marker)
        ):
            best_marker = marker
            best_weight = weight
    return best_marker, best_weight


@dataclass(frozen=True, slots=True)
class ExtractiveNarrator:
    """Selects problem and benefit sentences from the topic's evidence."""

    provider: ClassVar[str] = "extractive"

    def narrate(self, request: NarrationRequest) -> Motivation:
        """Build the motivation of one trend by quoting its sources."""
        topic_stems = self._topic_stems(request)
        problems: list[SentenceCandidate] = []
        benefits: list[SentenceCandidate] = []

        for index, document in enumerate(request.evidence.documents):
            if not document.abstract_text:
                continue
            authority = prior_authority(document.source_class)
            citations = document.citation_count or 0
            authority = clamp01(0.5 * authority + 0.5 * math.log1p(citations) / math.log1p(200))
            text = normalize_text(document.abstract_text)
            for sentence in split_sentences(text):
                body = sentence.text.strip()
                if not _MIN_SENTENCE_CHARS <= len(body) <= _MAX_SENTENCE_CHARS:
                    continue
                if not _stands_on_its_own(body):
                    continue
                lowered = body.lower()
                coverage = self._coverage(body, topic_stems)
                if coverage < request.min_term_coverage:
                    continue
                for markers, bucket in (
                    (PROBLEM_MARKERS, problems),
                    (BENEFIT_MARKERS, benefits),
                ):
                    marker, weight = _match_marker(lowered, markers)
                    if not marker:
                        continue
                    score = weight * (0.25 + 0.75 * coverage) * (0.25 + 0.75 * authority)
                    bucket.append(
                        SentenceCandidate(
                            text=body,
                            evidence_index=index,
                            marker=marker,
                            marker_weight=weight,
                            coverage=coverage,
                            authority=authority,
                            score=round(score, 9),
                        )
                    )

        problem_pick = self._best(problems, request.max_sentences)
        benefit_pick = self._best(benefits, request.max_sentences)

        attributions: list[MotivationAttribution] = []
        problem_text = self._join(problem_pick, attributions, "problem")
        benefit_text = self._join(benefit_pick, attributions, "benefit")

        if not problem_text:
            problem_text = self._fallback(request, "problem")
        if not benefit_text:
            benefit_text = self._fallback(request, "benefit")

        return Motivation(
            problem=problem_text,
            benefit=benefit_text,
            attributions=tuple(attributions),
        )

    @staticmethod
    def _topic_stems(request: NarrationRequest) -> frozenset[str]:
        """Stems of every member term of the topic, used for the coverage factor."""
        stems: set[str] = set()
        for member in request.topic.members:
            stems.update(member.key.split(" "))
        for alias in request.topic.aliases:
            stems.update(stem_token(token.normal) for token in tokenize(alias))
        return frozenset(stem for stem in stems if stem)

    @staticmethod
    def _coverage(sentence: str, topic_stems: frozenset[str]) -> float:
        """Share of the topic's stems present in the sentence."""
        if not topic_stems:
            return 0.0
        sentence_stems = {stem_token(token.normal) for token in tokenize(sentence)}
        hits = len(topic_stems & sentence_stems)
        return clamp01(hits / len(topic_stems))

    @staticmethod
    def _best(candidates: Sequence[SentenceCandidate], limit: int) -> tuple[SentenceCandidate, ...]:
        """Top ``limit`` candidates, deduplicated, in a fully deterministic order.

        Дедупликация не по равенству строк. Равенство ловит только буквальный повтор, а в мотивации
        рядом оказывались два предложения, различающиеся хвостом:

            «We show that the design enables linear-time inference over million-token contexts,
             which the published state of the art cannot provide.»
            «We show that the design enables linear-time inference over million-token contexts,
             which prior distillation pipelines cannot provide.»

        Формально это разные строки; для читателя — одно утверждение, напечатанное дважды. Мотивация
        — первое, что аналитик читает в карточке тренда, и повтор в ней сразу выдаёт машину.
        """
        ordered = sorted(
            candidates,
            key=lambda item: (-item.score, item.evidence_index, item.text),
        )
        picked: list[SentenceCandidate] = []
        for candidate in ordered:
            if any(_says_the_same(candidate.text, chosen.text) for chosen in picked):
                continue
            picked.append(candidate)
            if len(picked) >= max(1, limit):
                break
        return tuple(picked)

    @staticmethod
    def _join(
        picked: Sequence[SentenceCandidate],
        attributions: list[MotivationAttribution],
        statement: Literal["problem", "benefit"],
    ) -> str:
        """Concatenate the picked sentences and register their attributions."""
        if not picked:
            return ""
        for candidate in picked:
            attributions.append(
                MotivationAttribution(
                    statement=statement,
                    evidence_index=candidate.evidence_index,
                    # Предложение записывается вместе со ссылкой. Восстановить его потом разбиением
                    # склейки по точке нельзя: точка стоит и внутри «e.g.», и в «1.8x», и в
                    # сокращениях названий. Разбиение ошибётся молча и подпишет чужой источник.
                    sentence=candidate.text,
                )
            )
        return " ".join(candidate.text for candidate in picked)

    @staticmethod
    def _fallback(request: NarrationRequest, statement: str) -> str:
        """Factual Russian fallback when no cue sentence exists.

        ``motivation.problem`` and ``motivation.benefit`` are required by the contract with
        ``minLength: 1``, so a card without cue phrases still needs text. The fallback
        states *measured facts about the corpus* rather than inventing an argument — it is
        still, strictly, non-generative.
        """
        count = len(request.evidence.items)
        title = request.topic.label
        classes = len({item.source_class for item in request.evidence.items})
        if statement == "problem":
            return (
                f"В абстрактах источников по теме «{title}» нет явных формулировок проблемы; "
                f"тема подтверждается {count} документами из {classes} классов источников — "
                "постановка задачи требует ручной проверки первоисточников."
            )
        return (
            f"В абстрактах источников по теме «{title}» нет явных формулировок преимущества; "
            f"подтверждающая база — {count} документов из {classes} классов источников, "
            "ссылки приведены в разделе «Доказательства»."
        )


#: Слова, которыми предложение объявляет себя продолжением чужой мысли.
#:
#: Предложение, открывающееся «However» или «Moreover», ссылается на предшествующее, которого
#: аналитик не увидит: «However, deployments still suffer from draft-target mismatch» в роли
#: определения не объясняет тему, а обрывает спор на середине. Так в отчёте по золотому корпусу
#: выглядели определения тем 2, 6, 8, 10, 12 и 15.
#:
#: Правило сначала применялось только к определению — на том основании, что оно «единственное поле,
#: стоящее вырванным без соседей». Выдача это опровергла: в карточке и в записке проблема подаётся
#: дословной цитатой, и другого контекста у неё нет ровно так же. На четырёх направлениях со связки
#: начинались 29 формулировок проблемы из 60; связка «however» вдобавок сама является признаком
#: проблемы (вес 0.6), поэтому отбор тянулся к таким предложениям, а не отталкивался от них.
#:
#: Цена расширения измерена и оказалась нулевой: ни одна тема не скатилась в шаблон — годные
#: самостоятельные предложения в источниках были и раньше, отбор просто их не предпочитал.
#:
#: Обрезать связку вместо отбрасывания нельзя: продукт обещает дословность («перевод перестал бы
#: быть цитатой»), а фраза со снятым первым словом уже не то, что написано в источнике.
#:
#: Список короткий и намеренно консервативный: отбрасывается только то, что не может открывать
#: самостоятельную мысль ни при каком содержании. Слишком широкий список отсеял бы годные
#: предложения и вернул карточку к сухому шаблону там, где источник сказал по делу.
_CONTINUATION_OPENERS: Final = (
    "however",
    "moreover",
    "furthermore",
    "therefore",
    "thus",
    "hence",
    "nevertheless",
    "nonetheless",
    "instead",
    "conversely",
    "meanwhile",
    "besides",
    "additionally",
    "consequently",
    "accordingly",
    "otherwise",
)


def _stands_on_its_own(sentence: str) -> bool:
    """Может ли предложение быть процитировано без предыдущего.

    Проверяется только первое слово: связка в середине фразы — часть рассуждения, а вот вынесенная
    в начало она объявляет всё предложение продолжением. Это не оценка качества текста, а вопрос о
    том, сохраняет ли цитата смысл в отрыве от источника.
    """
    words = _WORDS.findall(sentence)
    if not words:
        return False
    return words[0].lower() not in _CONTINUATION_OPENERS


#: Признаки проблемы, по которым можно судить о назначении предложения целиком.
#:
#: Из `PROBLEM_MARKERS` берутся только сильные (вес ≥ 0.9). Слабые — «however», «challenge»,
#: «difficult to» — встречаются в середине обычных предложений и назначения не выдают: правило по
#: ним выбросило бы годное определение «A linear-time model keeps constant memory; however, its
#: recall depends on the state size», на чём эта проверка и упала при первой редакции.
_STRONG_PROBLEM_MARKERS: Final = tuple(
    (marker, weight) for marker, weight in PROBLEM_MARKERS if weight >= 0.9
)


def build_definition(
    topic: Topic, evidence: EvidenceSelection, *, avoid: Sequence[str] = ()
) -> str:
    """Extract a one-sentence definition of the trend from its own sources.

    The chosen sentence is the earliest sentence of the most authoritative document that
    actually mentions the topic. As everywhere in ADR-0010, the text is quoted, never generated.
    The fallback states only measured facts (period, document count, aliases).

    Прежняя редакция обосновывала выбор словами «там статьи и дают определение». Замер это
    опроверг: определительных предложений в аннотациях эталонного корпуса три процента, и половина
    из них — описания репозиториев. Аннотации своих терминов не определяют, и требовать
    определительной формы значило бы оставить почти все карточки на шаблоне. Поэтому берётся первое
    упоминание — как ближайшее к вводу термина, а не как определение по форме.

    ``avoid`` — то, что карточка уже сказала в другом месте. Определение и мотивация выбирали
    предложения независимо и из одних и тех же аннотаций, поэтому регулярно выбирали одно и то же:
    в отчёте по золотому корпусу тема ранга 2 получала определением ровно ту фразу, которая ниже
    стояла проблемой. Аналитик читает такую карточку как сломанную — и справедливо: два поля с
    разными подписями и одинаковым содержимым означают, что одно из них не заполнено.

    Отбрасывается не только точное совпадение: мерой служит тот же длиннейший общий кусок, что и
    внутри самой мотивации, — иначе достаточно другого хвоста, чтобы повтор вернулся.

    Если после исключения не осталось ничего, определением становится сводка измеренных фактов.
    Это не потеря: она честно говорит, по скольким документам и за какой период тема выделена, —
    в отличие от предложения, которое аналитик уже прочитал двумя строками ниже.
    """
    # Предложенная тема: имя дано как применение и дословно в аннотациях почти не встречается —
    # определением становится описание, написанное по её же страницам-доказательствам.
    if topic.description:
        return topic.description
    stems = frozenset(stem for member in topic.members for stem in member.key.split(" ") if stem)
    best: tuple[float, int, int, str] | None = None
    for index, document in enumerate(evidence.documents):
        if not document.abstract_text:
            continue
        authority = prior_authority(document.source_class)
        for sentence in split_sentences(normalize_text(document.abstract_text)):
            body = sentence.text.strip()
            if not 40 <= len(body) <= 320:
                continue
            sentence_stems = {stem_token(token.normal) for token in tokenize(body)}
            if not stems or not stems.issubset(sentence_stems):
                continue
            if not _stands_on_its_own(body):
                continue
            # Предложение с признаком проблемы определением быть не может: карточка показала бы
            # проблему под подписью «Определение», а строкой ниже — другую проблему под подписью
            # «Проблема». Два поля с разными подписями и одинаковым родом содержимого читаются как
            # незаполненные — и читаются верно.
            #
            # Проверка не заменяет `avoid`: тот отбрасывает то же самое предложение, а это —
            # предложение того же вида. На эталонном корпусе таких было семь из тридцати
            # содержательных определений.
            if _match_marker(body.lower(), _STRONG_PROBLEM_MARKERS)[0]:
                continue
            if any(said and _says_the_same(body, said) for said in avoid):
                continue
            key = (-authority, sentence.index, index, body)
            if best is None or key < best:
                best = key
    if best is not None:
        return best[3]

    years = sorted({document.published_on.year for document in evidence.documents})
    span = f"{years[0]}–{years[-1]}" if len(years) > 1 else str(years[0]) if years else "—"
    aliases = f"; варианты названия: {', '.join(topic.aliases[:3])}" if topic.aliases else ""
    return (
        f"Тема «{topic.label}» выделена по {len(evidence.items)} документам "
        f"за {span} гг.{aliases}"
    )
