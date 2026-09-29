"""Расчёт движка ``signals``: балл кандидата по внешним признакам и обученным весам.

Чем это отличается от методологии. Методология судит тему по корпусу: индикаторы зарождения
считаются по тем полутора тысячам документов, которые собраны под этот запрос. Внешние признаки
отвечают на тот же вопрос другими данными — сколько работ по фразе видит OpenAlex, из скольких
организаций они выходят, говорят ли о ней инженеры, завела ли Википедия статью. Числа получаются
другие, а не уточнённые те же, и поэтому это второй движок, а не настройка первого
(`docs/BACKLOG.md`, п. 25).

Три решения, из которых собран этот модуль.

* **Веса живут в артефакте, а не в коде.** Умолчания «пока не обучили» не существует: выдуманный
  вес выглядит в отчёте ровно так же, как обученный, и отличить одно от другого по выдаче нельзя.
  Нет артефакта — движок не собирается (:class:`SignalsModelError`), а не считает чем попало.
* **Список признаков объявлен один раз** (:data:`FEATURES`) и сверяется с артефактом по именам
  **и порядку**. Перепутанные местами два веса дают правдоподобный отчёт с переставленными
  темами — отказ, который не виден ничем, кроме несовпадения имён здесь.
* **Отсутствие признака — это не ноль.** Источник, который не ответил, оставляет признак
  отсутствующим: его вклад в балл равен нулю, но в объяснении темы он помечен как неизвестный.
  Подменив отсутствие нулём, мы научили бы модель считать недоступность источника свойством
  технологии.

Признаки корпуса считаются здесь же и всегда: тема, про которую внешние источники промолчали,
получает балл по корпусной части, а не выбывает из отчёта. Пометка об этом уходит в
:class:`SignalScore` и дальше в отчёт.
"""

from __future__ import annotations

import json
import logging
import math
import re
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass, replace
from datetime import date
from pathlib import Path
from types import MappingProxyType
from typing import Final, Literal, Protocol

from horizon_analytics.domain.extraction.blacklist import load_generic_terms, stem_phrase
from horizon_analytics.domain.models import (
    Document,
    EmergenceResult,
    Posting,
    TermCandidate,
    Topic,
    safe_div,
)
from horizon_analytics.domain.ports import SignalsProbe, TechnologyProposer
from horizon_analytics.domain.scoring.indicators import linear_fit

__all__ = [
    "CORPUS_FEATURES",
    "DEFAULT_PROPOSED_LIMIT",
    "DEFAULT_TOP_CANDIDATES",
    "EXTERNAL_FEATURES",
    "FEATURES",
    "MODEL_SCHEMA",
    "PROPOSAL_EXCLUSIONS",
    "CandidateSource",
    "FeatureContribution",
    "FeatureWeight",
    "ProposedBatch",
    "ProposedCandidates",
    "ProposedTopic",
    "ScoredTopic",
    "SignalScore",
    "SignalsModel",
    "SignalsModelError",
    "SignalsRescorer",
    "TopicRescorer",
    "TrendOrigin",
    "corpus_features",
    "external_search_phrase",
]

#: Идентификатор формата артефакта. Растёт, когда меняется разбор, а не когда переобучают веса:
#: артефакт другой версии обязан быть отвергнут, а не прочитан наполовину.
MODEL_SCHEMA: Final[str] = "horizon.signals-model/1"

#: Признаки, которые считаются по корпусу и потому есть у **каждого** кандидата всегда.
#:
#: ``documents`` — сколько документов направления упоминают тему; ``documents_last_year`` — сколько
#: из них в последнем периоде окна; ``recent_two_year_share`` — доля двух последних периодов;
#: ``growth_slope`` — наклон той же регрессии ``ln(df + 1) = α + β·t``, которой методология меряет
#: рост. Наклон взят у методологии намеренно: это единственное место, где оба движка смотрят на
#: одно и то же число, и расхождение в выдаче становится объяснимым, а не загадочным.
CORPUS_FEATURES: Final[tuple[str, ...]] = (
    "corpus.documents",
    "corpus.documents_last_year",
    "corpus.recent_two_year_share",
    "corpus.growth_slope",
)

#: Признаки из открытых источников. Имена совпадают с полями, которые складывает
#: :mod:`horizon_analytics.signals`, кроме двух производных — ``works_last_year`` и
#: ``mentions_last_year``: годовые ряды ``works_by_year`` и ``mentions_by_year`` привязаны к
#: календарю, и как вектор постоянной длины их брать нельзя — выборка, собранная в январе, имела
#: бы на колонку меньше, чем собранная в декабре. Поэтому из ряда берётся последний год окна.
EXTERNAL_FEATURES: Final[tuple[str, ...]] = (
    "openalex.works_window_total",
    "openalex.works_last_year",
    "openalex.recent_two_year_share",
    "openalex.distinct_institutions",
    "openalex.top_institution_share",
    "hackernews.mentions_window_total",
    "hackernews.mentions_last_year",
    "hackernews.recent_two_year_share",
    "wikipedia.exists",
    "wikipedia.age_days",
    "wikipedia.size_bytes",
    "wikipedia.pageviews_12m",
)

#: Полный вектор признаков в том порядке, в каком его ждёт артефакт. Единственное место, где этот
#: порядок объявлен: обучение читает его отсюда же, и рассогласоваться им негде.
FEATURES: Final[tuple[str, ...]] = CORPUS_FEATURES + EXTERNAL_FEATURES

#: Сколько кандидатов спрашивать у внешних источников. Сто пятьдесят — это примерно две минуты по
#: быстрым источникам (`horizon_analytics.signals.REQUESTS_PER_TERM`) и весь верх ранжирования:
#: ниже сто пятидесятого места по корпусной частоте тема в отчёт из пятнадцати строк не попадала
#: ни разу. Остальные кандидаты считаются по корпусной части — они не исчезают.
DEFAULT_TOP_CANDIDATES: Final[int] = 150

_LOG = logging.getLogger(__name__)

#: Ниже этой доли опрошенных кандидатов с внешними признаками прогон считается слепым.
#: Порог не подобран, а взят по опыту отказов: когда источники отвечают, признаки есть у
#: подавляющего большинства (замер на стенде — 150 из 150). Значения вроде четверти бывают
#: только при отказе: исчерпан суточный лимит каталога, закрыт доступ на запись в кэш,
#: источник отвечает 403. Во всех трёх случаях движок продолжает работать и выдаёт темы
#: с одинаковым баллом — и по отчёту это неотличимо от честного результата, если не сказать
#: об этом словами (разборы 97 и 100).
BLIND_EXTERNAL_SHARE: Final[float] = 0.25

#: Преобразования, которыми артефакт вправе сжимать признак перед нормировкой. Список закрыт:
#: имя преобразования, которого здесь нет, — это отказ, а не «примем как есть».
_TRANSFORMS: Final[Mapping[str, object]] = {
    "identity": float,
    "log1p": lambda value: math.log1p(max(0.0, float(value))),
    "signed_log1p": lambda value: math.copysign(math.log1p(abs(float(value))), float(value)),
}

#: Связки «сумма весов → балл отчёта». Балл контракта лежит в [0, 100], и способ туда попасть —
#: свойство обученной модели, а не константа кода.
_LINKS: Final[tuple[str, ...]] = ("logistic", "identity")

#: Хвост «(SD)» у названия, раскрытого конвейером из корпуса. Во внешние источники уходит
#: расшифровка: точная фраза со скобкой не находит ничего, и тема получила бы «источник молчит»
#: там, где источник просто не спрашивали.
_ACRONYM_SUFFIX: Final = re.compile(r"\s*\([0-9]?[A-Z][A-Z0-9]{1,5}s?\)\s*$")

#: Строка ранжирования: результат, тема и её документы — то, чем конвейер оперирует между
#: расчётом индикаторов и отбором в ТОП-N.
ScoredTopic = tuple[EmergenceResult, Topic, tuple[Document, ...]]

#: Откуда взялось имя темы. ``corpus`` — извлечено из собранных документов; ``model`` — названо
#: генеративной моделью и подтверждено внешними источниками. Разница видна аналитику в отчёте, и
#: это не украшение: у двух происхождений разная природа ошибки, и доверять им нужно по-разному.
TrendOrigin = Literal["corpus", "model"]

#: Сколько имён просить у модели на одно направление. Шестьдесят — примерно вдвое больше, чем
#: технологий эталона на направление, и минута опроса внешних источников сверх обычного сбора.
DEFAULT_PROPOSED_LIMIT: Final[int] = 60


#: Источник, чьи статьи поднимают тему в ранжировании. alphaXiv отбирает работы по вопросу «что
#: меняется в направлении» среди статей, которые уже читают и обсуждают исследователи, — это
#: независимое от частоты в корпусе свидетельство того, что строка называет живой метод, а не
#: случайное сочетание слов.
ALPHAXIV_SOURCE: Final[str] = "alphaxiv"
#: Прибавка к баллу за каждую отдельную статью alphaXiv по теме, в пунктах из 100.
ALPHAXIV_POINTS_PER_PAPER: Final[float] = 6.0
#: Потолок прибавки: три статьи и больше — уже не новость, а подтверждение, и дальше балл
#: должен решать не их число, а признаки модели.
ALPHAXIV_MAX_POINTS: Final[float] = 18.0


#: Короче этого вариант названия совпадением не считается: «AU», «RL», «SD» встречаются в текстах
#: о чём угодно, и тема про мимику лица получала прибавку за статьи об атаках на детекторы.
_MIN_NAME_LENGTH: Final[int] = 5
_PARENTHETICAL = re.compile(r"\s*\([^)]*\)")


def topic_names(title: str, aliases: Sequence[str] = ()) -> tuple[str, ...]:
    """Варианты названия темы, по которым статья считается «про неё».

    Скобочное сокращение отделяется («action unit (AU)» → «action unit»), короткие варианты
    отбрасываются: совпадение по ним ничего не доказывает.
    """
    names: list[str] = []
    for raw in (title, *aliases):
        name = _PARENTHETICAL.sub("", raw or "").strip().lower()
        if len(name) >= _MIN_NAME_LENGTH and name not in names:
            names.append(name)
    return tuple(names)


def _mentions(document: Document, names: Sequence[str]) -> bool:
    text = f"{document.title} {document.abstract_text or ''}".lower()
    return any(re.search(rf"(?<![a-z0-9]){re.escape(name)}(?![a-z0-9])", text) for name in names)


def alphaxiv_papers(documents: Sequence[Document], names: Sequence[str] | None = None) -> int:
    """Сколько разных статей alphaXiv подтверждают тему — то есть называют её сами.

    Документ попадает в тему через кластеризацию и синонимы, и статья alphaXiv рядом с темой ещё
    не значит, что она о ней: прогон 2026-09-28 дал теме «action unit (AU)» прибавку за статьи об
    очистке от атак (ODPure, AMRM-Pure), и тема про мимику лица встала в верхушку отчёта о защите
    ИИ. Засчитывается только статья, в заголовке или аннотации которой есть название темы.
    """
    papers = {
        document.document_id
        for document in documents
        if document.source_id == ALPHAXIV_SOURCE and (names is None or _mentions(document, names))
    }
    return len(papers)


#: Доля балла методологии в итоговом балле движка ``signals``.
#:
#: Артефакт обучен только на внешних признаках: веса корпусных нулевые, а внешние признаки
#: собираются лишь у верхушки кандидатов. Все остальные получали ровно 100·σ(смещения) = 46,67, и
#: ТОП-15 решался разрешением равенств, а не моделью. Балл методологии (новизна, рост, диффузия,
#: достоверность) посчитан у каждой темы — он и держит порядок там, где модели нечего сказать, а
#: внешние признаки сдвигают его там, где они есть. Нейтральная модельная часть — не ноль, а
#: σ(смещения): отсутствие данных не наказывает тему.
METHODOLOGY_SHARE: Final[float] = 0.5


def blend_with_methodology(model_score: float, methodology_score: float) -> float:
    """Итоговый балл 0..100: доля методологии плюс доля модели по внешним признакам."""
    methodology = min(100.0, max(0.0, float(methodology_score)))
    return METHODOLOGY_SHARE * methodology + (1.0 - METHODOLOGY_SHARE) * float(model_score)


#: Надбавка предложенной теме за независимые подтверждения — в пунктах за удвоение числа хостов
#: среди страниц-доказательств, с потолком (разбор 110). Предложенных тем до сотни, и их баллы по
#: методологии и модели почти равны: порядок решали доли пункта. Тема, которую подтверждают восемь
#: изданий и пресс-центров, сильнее темы с двумя; ТЗ требует независимых подтверждений. Корпусным
#: темам надбавки нет: у них десятки документов, и она подняла бы как раз массовые.
SUPPORT_POINTS_PER_DOUBLING: Final[float] = 3.0
SUPPORT_MAX_POINTS: Final[float] = 8.0


def support_bonus(hosts: int) -> float:
    """Надбавка за независимые хосты: 0 при одном, +3 за каждое удвоение, не больше потолка."""
    if hosts <= 1:
        return 0.0
    return min(SUPPORT_MAX_POINTS, SUPPORT_POINTS_PER_DOUBLING * math.log2(hosts))


def alphaxiv_bonus(papers: int) -> float:
    """Прибавка к баллу за статьи alphaXiv — в пунктах, с потолком."""
    return min(ALPHAXIV_MAX_POINTS, ALPHAXIV_POINTS_PER_PAPER * max(0, papers))


class SignalsModelError(RuntimeError):
    """Артефакт модели отсутствует, не читается или не совпадает со списком признаков."""


def external_search_phrase(title: str) -> str:
    """Название темы в том виде, в каком его ищут во внешних источниках."""
    return _ACRONYM_SUFFIX.sub("", title).strip()


@dataclass(frozen=True, slots=True)
class FeatureContribution:
    """Вклад одного признака в балл темы — строка объяснения «почему эта тема поднялась».

    Attributes:
        name: Имя признака из :data:`FEATURES`.
        value: Наблюдённое значение; ``None`` — признака не было.
        normalized: Значение после преобразования и нормировки; ``0.0`` при отсутствии.
        weight: Вес из артефакта.
        contribution: ``weight × normalized`` — слагаемое суммы.
    """

    name: str
    value: float | None
    normalized: float
    weight: float
    contribution: float

    @property
    def present(self) -> bool:
        """Был ли признак наблюдён."""
        return self.value is not None


@dataclass(frozen=True, slots=True)
class SignalScore:
    """Балл одной темы со всем, из чего он сложился."""

    trend_key: str
    #: Фраза, которой тему искали снаружи, — не всегда совпадает с заголовком темы.
    term: str
    score: float
    #: Сумма весов до связки. Хранится, потому что по баллу после связки не видно, насколько
    #: уверенно модель высказалась: 51 и 99 различаются, а 0.04 и 4.6 — различаются сильнее.
    raw: float
    model_version: str
    #: Были ли у темы хоть какие-то внешние признаки. ``False`` означает, что балл собран только
    #: по корпусу: источник промолчал, отказал или тема не попала в бюджет опроса.
    external: bool
    missing: tuple[str, ...]
    contributions: tuple[FeatureContribution, ...]


@dataclass(frozen=True, slots=True)
class FeatureWeight:
    """Одна строка артефакта: вес признака и как привести его к масштабу обучения."""

    name: str
    weight: float
    transform: str
    center: float
    scale: float

    def normalize(self, value: float) -> float:
        """Преобразовать и нормировать наблюдённое значение."""
        function = _TRANSFORMS[self.transform]
        return (float(function(value)) - self.center) / self.scale  # type: ignore[operator]


@dataclass(frozen=True, slots=True)
class SignalsModel:
    """Линейная модель, прочитанная из артефакта.

    Проверка артефакта намеренно придирчива и вся — при загрузке. Модель, у которой не сошлись
    имена признаков, посчитает балл и не пожалуется: перепутанные веса дают такой же отчёт, только
    с другим порядком тем, и узнать об этом из выдачи нельзя.
    """

    version: str
    trained_on: date
    link: str
    bias: float
    weights: tuple[FeatureWeight, ...]
    #: Метрика на отложенной части: чем именно мерили, сколько получилось и на скольких примерах.
    holdout: Mapping[str, object]
    source: str

    @classmethod
    def load(cls, path: Path | str | None) -> SignalsModel:
        """Прочитать артефакт с диска.

        Raises:
            SignalsModelError: путь не задан, файла нет, он не разбирается или не совпадает со
                списком признаков. Во всех случаях — отказ с названной причиной, а не тихий
                откат к методологии: аналитик, попросивший ``signals`` и получивший методологию,
                узнать об этом не может ничем.
        """
        if path is None or not str(path).strip():
            raise SignalsModelError(
                "движку signals нужен артефакт модели, а путь к нему не задан: "
                "переменная окружения HORIZON_SIGNALS_MODEL или EngineContext.signals.model_path"
            )
        file = Path(path)
        if not file.is_file():
            raise SignalsModelError(f"артефакт модели signals не найден: {file}")
        try:
            payload = json.loads(file.read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            raise SignalsModelError(
                f"артефакт модели signals не читается ({file}): {error}"
            ) from error
        return cls.from_json(payload, source=str(file))

    @classmethod
    def from_json(cls, payload: object, *, source: str) -> SignalsModel:
        """Разобрать и проверить содержимое артефакта."""
        if not isinstance(payload, Mapping):
            raise SignalsModelError(f"артефакт модели signals ({source}) — не объект JSON")
        schema = str(payload.get("schema") or "")
        if schema != MODEL_SCHEMA:
            raise SignalsModelError(
                f"артефакт модели signals ({source}) объявлен как {schema!r}, "
                f"а разбирается только {MODEL_SCHEMA!r}"
            )
        link = str(payload.get("link") or "")
        if link not in _LINKS:
            raise SignalsModelError(
                f"артефакт модели signals ({source}): связка {link!r}, "
                f"известны {', '.join(_LINKS)}"
            )
        rows = payload.get("features")
        if not isinstance(rows, Sequence) or isinstance(rows, str | bytes):
            raise SignalsModelError(f"артефакт модели signals ({source}): поле features не список")
        weights = tuple(_weight(row, source=source) for row in rows)
        _check_feature_order(tuple(item.name for item in weights), source=source)
        holdout = payload.get("holdout")
        if not isinstance(holdout, Mapping) or "metric" not in holdout or "value" not in holdout:
            raise SignalsModelError(
                f"артефакт модели signals ({source}): нет метрики на отложенной части "
                "(holdout.metric, holdout.value) — модель без замера нельзя ни принять, ни "
                "сравнить со следующей"
            )
        return cls(
            version=str(payload.get("version") or ""),
            trained_on=_parse_date(payload.get("trained_on"), source=source),
            link=link,
            bias=_finite(payload.get("bias"), field="bias", source=source),
            weights=weights,
            holdout=dict(holdout),
            source=source,
        )

    def score(
        self, features: Mapping[str, float]
    ) -> tuple[float, float, tuple[FeatureContribution, ...], tuple[str, ...]]:
        """Посчитать балл по наблюдённым признакам.

        Returns:
            ``(балл 0..100, сумма до связки, вклады признаков, имена отсутствующих признаков)``.
        """
        contributions: list[FeatureContribution] = []
        missing: list[str] = []
        total = self.bias
        for item in self.weights:
            raw = features.get(item.name)
            if raw is None or not math.isfinite(float(raw)):
                missing.append(item.name)
                contributions.append(
                    FeatureContribution(
                        name=item.name,
                        value=None,
                        normalized=0.0,
                        weight=item.weight,
                        contribution=0.0,
                    )
                )
                continue
            normalized = item.normalize(float(raw))
            contribution = item.weight * normalized
            total += contribution
            contributions.append(
                FeatureContribution(
                    name=item.name,
                    value=float(raw),
                    normalized=normalized,
                    weight=item.weight,
                    contribution=contribution,
                )
            )
        return _apply_link(total, self.link), total, tuple(contributions), tuple(missing)


class TopicRescorer(Protocol):
    """Замена балла ранжирования — точка расширения конвейера под второй движок."""

    def rescore(
        self,
        results: Sequence[ScoredTopic],
        *,
        termhood: Mapping[str, float],
        drops: dict[str, int],
        proposed: Mapping[str, Mapping[str, float]] = MappingProxyType({}),
        eligible_keys: frozenset[str] | None = None,
    ) -> tuple[list[ScoredTopic], tuple[SignalScore, ...]]:
        """Вернуть те же строки с новым баллом и объяснение балла для каждой.

        ``proposed`` — уже собранные признаки тем, предложенных моделью: их не спрашивают заново,
        и корпусная часть вектора у них остаётся неизвестной, а не нулевой.
        """
        ...


@dataclass(frozen=True, slots=True)
class SignalsRescorer:
    """Ранжирование по внешним признакам: кого спросить, что спросить и как сложить.

    Опрашиваются не все кандидаты, а верхушка по корпусной частоте и термхуду: сбор стоит сети, и
    тратить её на кандидата, который не попадёт и в первую сотню, незачем. Непрошенный кандидат
    не выбывает — он получает балл по корпусной части с пометкой, что внешних данных у него нет.
    Это та же пометка, что и у кандидата, о котором источник промолчал, и это правильно: разницы
    между «не спросили» и «не ответили» для читателя отчёта нет.
    """

    model: SignalsModel
    probe: SignalsProbe
    top_candidates: int = DEFAULT_TOP_CANDIDATES

    def rescore(
        self,
        results: Sequence[ScoredTopic],
        *,
        termhood: Mapping[str, float],
        drops: dict[str, int],
        proposed: Mapping[str, Mapping[str, float]] = MappingProxyType({}),
        eligible_keys: frozenset[str] | None = None,
    ) -> tuple[list[ScoredTopic], tuple[SignalScore, ...]]:
        """Посчитать балл каждой строки по обученной модели.

        Тема, предложенная моделью, считается по тем же весам и тому же списку признаков, но её
        корпусная часть отсутствует: темы нет в корпусе, и подставить туда ноль значило бы
        утверждать «ноль документов направления» вместо «неизвестно». Ровно та же подмена, что и
        с молчащим источником, и она запрещена по той же причине.
        """
        corpus_rows = [
            row for row in results
            if row[0].trend_key not in proposed
            and (eligible_keys is None or row[0].trend_key in eligible_keys)
        ]
        asked = self._select(corpus_rows, termhood)
        phrases = {key: external_search_phrase(title) for key, title in asked}
        # Повторы убираются, порядок сохраняется: две темы могут раскрыться в одну и ту же
        # фразу, и спрашивать источник дважды об одном — впустую потраченный лимит.
        asked_phrases = list(dict.fromkeys(phrases.values()))
        collected = self.probe.features(asked_phrases) if asked_phrases else {}

        rescored: list[ScoredTopic] = []
        scores: list[SignalScore] = []
        with_external = 0
        boosted = 0
        for result, topic, documents in results:
            phrase = phrases.get(result.trend_key, external_search_phrase(result.title))
            if result.trend_key in proposed:
                outside = dict(proposed[result.trend_key])
                features = dict(outside)
            else:
                outside = dict(collected.get(phrase) or {}) if result.trend_key in phrases else {}
                features = {**corpus_features(result), **outside}
            model_score, raw, contributions, missing = self.model.score(features)
            score = blend_with_methodology(model_score, result.score)
            contributions = (
                *contributions,
                FeatureContribution(
                    name="methodology_score",
                    value=float(result.score),
                    normalized=min(100.0, max(0.0, float(result.score))) / 100.0,
                    weight=METHODOLOGY_SHARE,
                    contribution=score - (1.0 - METHODOLOGY_SHARE) * model_score,
                ),
            )
            # alphaXiv — отдельное слагаемое поверх модели, а не её признак: артефакт обучен без
            # него, и подмешать новое значение в сумму весов значило бы молча изменить модель.
            # Прибавка записывается строкой объяснения рядом с признаками модели.
            papers = alphaxiv_papers(documents, topic_names(result.title, result.aliases))
            if papers:
                bonus = alphaxiv_bonus(papers)
                score = min(100.0, score + bonus)
                contributions = (
                    *contributions,
                    FeatureContribution(
                        name="alphaxiv_papers",
                        value=float(papers),
                        normalized=float(papers),
                        weight=ALPHAXIV_POINTS_PER_PAPER,
                        contribution=bonus,
                    ),
                )
                boosted += 1
            if result.trend_key in proposed:
                hosts = len({_canonical_url(d.url).split("/")[2] for d in documents if "://" in d.url})
                support = support_bonus(hosts)
                if support > 0:
                    score = min(100.0, score + support)
                    contributions = (
                        *contributions,
                        FeatureContribution(
                            name="independent_sources",
                            value=float(hosts),
                            normalized=float(hosts),
                            weight=SUPPORT_POINTS_PER_DOUBLING,
                            contribution=support,
                        ),
                    )
            external = any(name in outside for name in EXTERNAL_FEATURES)
            with_external += int(external)
            scores.append(
                SignalScore(
                    trend_key=result.trend_key,
                    term=phrase,
                    score=score,
                    raw=raw,
                    model_version=self.model.version,
                    external=external,
                    missing=missing,
                    contributions=contributions,
                )
            )
            rescored.append((replace(result, score=score), topic, documents))
        drops["signals_scored"] = len(rescored)
        drops["signals_asked"] = len(phrases)
        drops["signals_with_external"] = with_external
        drops["signals_alphaxiv_boosted"] = boosted
        if phrases and with_external < BLIND_EXTERNAL_SHARE * len(phrases):
            # Не исключение: движок обязан довести прогон до конца и отдать хоть какой-то
            # порядок. Но отчёт должен нести признак того, что порядок получен почти без
            # внешних данных, иначе равные баллы читаются как настоящий вывод.
            drops["signals_blind"] = 1
            _LOG.warning(
                "внешние признаки собраны лишь у %s из %s опрошенных кандидатов: "
                "ранжирование идёт почти без них",
                with_external,
                len(phrases),
            )
        return rescored, tuple(scores)

    def _select(
        self, results: Sequence[ScoredTopic], termhood: Mapping[str, float]
    ) -> list[tuple[str, str]]:
        """Кого спрашивать снаружи: верхушка по корпусной частоте, при равенстве — по термхуду."""
        ordered = sorted(
            results,
            key=lambda row: (
                -row[1].document_frequency,
                -termhood.get(row[0].trend_key, 0.0),
                row[0].trend_key,
            ),
        )
        return [(result.trend_key, result.title) for result, _, _ in ordered[: self.top_candidates]]


@dataclass(frozen=True, slots=True)
class ProposedTopic:
    """Имя, предложенное моделью и подтверждённое источниками.

    Три части и все три обязательны. ``topic`` — как тема называется; ``documents`` — настоящие
    работы, найденные по этому имени, без них тему публиковать нельзя (BR-A6); ``features`` —
    измеренные внешние признаки, по которым её будет ранжировать модель. Корпусных признаков в
    ``features`` нет и быть не может: темы нет в корпусе.
    """

    topic: Topic
    documents: tuple[Document, ...]
    features: Mapping[str, float]


@dataclass(frozen=True, slots=True)
class ProposedBatch:
    """Что вышло из одного обращения к модели: принятые темы и отвергнутые имена с причиной.

    Отвергнутые возвращаются вместе с принятыми, а не теряются в счётчике: перечень исключённого
    в отчёте отвечает на вопрос «что вы вообще отбросили», и предложенное моделью — именно то, о
    чём этот вопрос задают первым.
    """

    accepted: tuple[ProposedTopic, ...] = ()
    #: ``(имя, код причины)``; код — один из :data:`PROPOSAL_EXCLUSIONS`.
    rejected: tuple[tuple[str, str], ...] = ()


#: Причины, по которым предложенное имя не стало темой. Коды уходят в перечень исключённого.
PROPOSAL_EXCLUSIONS: Final[Mapping[str, str]] = {
    "proposed_generic_name": (
        "предложено широкое направление или результат без конкретного технического механизма"
    ),
    "proposed_no_evidence": (
        "предложено моделью, но открытые источники не показали ни одной измеренной величины: "
        "ни работ за окно наблюдения, ни упоминаний"
    ),
    "proposed_no_sources": (
        "предложено моделью, активность измерена, но не нашлось ни одной работы, которую можно "
        "показать источником: тема без доказательной базы не публикуется"
    ),
}


class CandidateSource(Protocol):
    """Второй источник кандидатов — точка расширения конвейера под движок ``signals``."""

    def propose(
        self,
        direction: str,
        *,
        known: frozenset[str],
        drops: dict[str, int],
        documents: Sequence[Document] = (),
        admit: Callable[[Document], bool] | None = None,
    ) -> ProposedBatch:
        """Предложить темы, которых нет среди ``known``, и подтвердить их источниками."""
        ...


@dataclass(frozen=True, slots=True)
class ProposedCandidates:
    """Имена от модели, пропущенные через проверку измеренными свидетельствами.

    Здесь проходит граница ТЗ §3.1 — «не допускается формирование итоговой выдачи исключительно на
    основании знаний языковой модели без подтверждённого поиска и анализа открытых источников».
    Модель здесь может только **предложить, о чём спросить**. Дальше имя обязано пройти две
    проверки подряд, и каждая — измерение, а не суждение:

    * **измеренная активность.** Хотя бы один внешний источник ответил, и в ответе есть ненулевая
      величина: работы в OpenAlex за окно наблюдения либо упоминания. Статья в Википедии при нуле
      работ и нуле упоминаний активностью не считается — это след названия, а не технологии.
    * **доказательная база.** Нашлась хотя бы одна настоящая работа, которую можно показать в
      отчёте со ссылкой, датой и авторами. Тема без источников не публикуется — тот же инвариант
      BR-A6, что и у корпусных тем.

    Имя, не прошедшее проверку, не исчезает молча: оно попадает в перечень исключённого с этой
    самой причиной. Аналитик видит, что модель предложила и что источники не подтвердили, — и это
    ровно то, из чего складывается доверие к остальному.
    """

    proposer: TechnologyProposer
    probe: SignalsProbe
    limit: int = DEFAULT_PROPOSED_LIMIT

    def propose(
        self,
        direction: str,
        *,
        known: frozenset[str],
        drops: dict[str, int],
        documents: Sequence[Document] = (),
        admit: Callable[[Document], bool] | None = None,
    ) -> ProposedBatch:
        """Спросить модель, проверить источниками, собрать темы из найденных работ.

        ``admit`` — правило допуска документа в корпус анализа (окно лет и классы источников из
        параметров отчёта). Страницы, подтянутые у сервиса моделей, и работы внешних источников
        проходят его так же, как собранный корпус: иначе тема со страницами-новостями попадала бы в
        отчёт, где аналитик новости выключил.

        Имена из веб-корпуса (разбор 110) приходят с адресами страниц, на которых сборщик их
        прочитал. Если эти страницы есть в корпусе анализа, они и есть доказательная база: страница
        собрана с проверкой robots.txt, дата и текст — её собственные. Такому имени внешняя
        активность в OpenAlex не нужна — рыночные сигналы там почти не видны; признаки для
        ранжирования всё равно измеряются.
        """
        with_evidence = getattr(self.proposer, "propose_with_evidence", None)
        corpus_evidence: Mapping[str, Sequence[str]] = {}
        if callable(with_evidence):
            raw_names, corpus_evidence = with_evidence(direction, self.limit)
        else:
            raw_names = self.proposer.propose(direction, self.limit)
        names = [name.strip() for name in dict.fromkeys(raw_names) if name and name.strip()]
        # Описания имён из корпуса и панели: определение карточки вместо шаблона.
        details: Mapping[str, str] = getattr(self.proposer, "details", None) or {}
        drops["proposed_asked"] = len(names)
        if not names:
            return ProposedBatch()
        unseen = [name for name in names if _key_of(name) not in known]
        drops["proposed_already_known"] = len(names) - len(unseen)
        generic = [name for name in unseen if stem_phrase(name) in load_generic_terms()]
        drops["proposed_generic_name"] = len(generic)
        fresh = [name for name in unseen if name not in generic]
        if not fresh:
            return ProposedBatch(rejected=tuple((name, "proposed_generic_name") for name in generic))

        by_url = {_canonical_url(document.url): document for document in documents if document.url}
        pages: dict[str, tuple[Document, ...]] = {}
        for name in fresh:
            matched = {
                by_url[_canonical_url(url)].document_id: by_url[_canonical_url(url)]
                for url in corpus_evidence.get(name, ())
                if _canonical_url(url) in by_url
            }
            found = tuple(matched.values())
            if found:
                pages[name] = found
        # Страницы, не попавшие в собранный корпус анализа, — у сервиса моделей по адресам.
        fetch = getattr(self.proposer, "evidence_documents", None)
        missing = [
            url
            for name in fresh
            if name not in pages
            for url in corpus_evidence.get(name, ())
            if _canonical_url(url) not in by_url
        ]
        if missing and callable(fetch):
            for document in fetch(list(dict.fromkeys(missing))):
                if admit is None or admit(document):
                    by_url.setdefault(_canonical_url(document.url), document)
            for name in fresh:
                matched = {
                    by_url[_canonical_url(url)].document_id: by_url[_canonical_url(url)]
                    for url in corpus_evidence.get(name, ())
                    if _canonical_url(url) in by_url
                }
                if matched:
                    pages[name] = tuple(matched.values())
        drops["proposed_from_webcorpus"] = len(pages)

        # Внешние признаки спрашиваются только у имён без страниц корпуса: им нужна измеренная
        # активность, чтобы стать темой. Именам со страницами она не нужна, а сотня запросов к
        # OpenAlex за анализ съедала его суточный бюджет (стенд, 29.09) — и вместе с ним проверку
        # зрелости у всех остальных тем.
        features = self.probe.features([name for name in fresh if name not in pages])
        confirmed = [name for name in fresh if name in pages or _has_activity(features.get(name))]
        rejected = [(name, "proposed_generic_name") for name in generic]
        rejected.extend((name, "proposed_no_evidence") for name in fresh if name not in confirmed)
        drops["proposed_without_evidence"] = len(fresh) - len(confirmed)
        if not confirmed:
            drops["proposed_accepted"] = 0
            return ProposedBatch(rejected=tuple(rejected))

        works = self.probe.works([name for name in confirmed if name not in pages])
        accepted: list[ProposedTopic] = []
        for name in confirmed:
            documents = pages.get(name) or tuple(
                work for work in works.get(name) or () if admit is None or admit(work)
            )
            if not documents:
                rejected.append((name, "proposed_no_sources"))
                continue
            accepted.append(
                ProposedTopic(
                    topic=_topic_from(name, documents, details.get(name)),
                    documents=documents,
                    features={
                        key: value
                        for key, value in (features.get(name) or {}).items()
                        if key in EXTERNAL_FEATURES
                    },
                )
            )
        drops["proposed_without_sources"] = len(confirmed) - len(accepted)
        drops["proposed_accepted"] = len(accepted)
        return ProposedBatch(accepted=tuple(accepted), rejected=tuple(rejected))


#: Признаки, ненулевое значение которых считается измеренной активностью технологии.
_ACTIVITY_FEATURES: Final[tuple[str, ...]] = (
    "openalex.works_window_total",
    "hackernews.mentions_window_total",
)


def _has_activity(features: Mapping[str, float] | None) -> bool:
    """Ответил ли хоть один источник и есть ли в ответе ненулевая величина."""
    if not features:
        return False
    return any(float(features.get(name, 0.0)) > 0.0 for name in _ACTIVITY_FEATURES)


def _canonical_url(url: str) -> str:
    """Адрес без фрагмента, завершающей косой черты, www и регистра — для сверки страниц."""
    return url.split("#")[0].rstrip("/").lower().replace("://www.", "://")


def _key_of(name: str) -> str:
    """Ключ темы: имя в нижнем регистре со схлопнутыми пробелами.

    Нормализация здесь грубее корпусной (без стемминга) намеренно: она сравнивает предложенное имя
    с уже найденными кандидатами, и ошибиться безопаснее в сторону «считать своим» — лишний
    двойник в отчёте виден, а потерянная тема нет.
    """
    return " ".join(name.lower().split())


def _topic_from(name: str, documents: Sequence[Document], description: str | None = None) -> Topic:
    """Собрать тему из найденных работ: вхождения считаются, а не назначаются."""
    phrase = " ".join(name.split())
    lowered = phrase.lower()
    postings = tuple(
        Posting(
            document_id=document.document_id,
            # Ноль вхождений невозможен по построению — работа найдена точной фразой, — но если
            # источник вернул её по синониму, единица честнее нуля: документ о теме есть.
            occurrences=max(1, document.text.lower().count(lowered)),
        )
        for document in sorted(documents, key=lambda item: item.document_id)
    )
    key = _key_of(phrase)
    return Topic(
        key=key,
        label=phrase,
        description=description or None,
        members=(
            TermCandidate(
                key=key,
                surface=phrase,
                surface_forms=(phrase,),
                postings=postings,
                # Термхуд меряется по корпусу, которого у этой темы нет. Ноль означает «не
                # измерялся»: он не участвует ни в одном сравнении, кроме очереди на опрос
                # внешних источников, а туда предложенные темы и не попадают.
                termhood=0.0,
                token_count=len(phrase.split(" ")),
            ),
        ),
    )


def corpus_features(result: EmergenceResult) -> dict[str, float]:
    """Корпусная часть вектора признаков — она есть у каждого кандидата всегда."""
    series = result.series
    total = float(sum(series.df))
    recent = float(sum(series.df[-2:]))
    return {
        "corpus.documents": float(result.total_documents),
        "corpus.documents_last_year": float(series.df[-1]) if series.df else 0.0,
        "corpus.recent_two_year_share": safe_div(recent, total),
        "corpus.growth_slope": float(linear_fit(series).slope),
    }


def _select_error(names: Sequence[str], *, source: str) -> str:
    """Текст отказа, из которого видно, чем артефакт отличается от списка признаков."""
    expected = list(FEATURES)
    unknown = [name for name in names if name not in expected]
    absent = [name for name in expected if name not in names]
    details: list[str] = []
    if unknown:
        details.append("лишние: " + ", ".join(unknown))
    if absent:
        details.append("недостающие: " + ", ".join(absent))
    if not details:
        first = next(
            (
                f"{index}: ожидался {expected[index]}, стоит {names[index]}"
                for index in range(len(expected))
                if expected[index] != names[index]
            ),
            "",
        )
        details.append(f"порядок нарушен на позиции {first}")
    return (
        f"артефакт модели signals ({source}) не совпадает со списком признаков "
        f"horizon_analytics.domain.signal_scoring.FEATURES — " + "; ".join(details)
    )


def _check_feature_order(names: tuple[str, ...], *, source: str) -> None:
    """Имена и порядок обязаны совпасть с :data:`FEATURES` — иначе веса лягут не на те признаки."""
    if names != FEATURES:
        raise SignalsModelError(_select_error(names, source=source))


def _weight(row: object, *, source: str) -> FeatureWeight:
    """Разобрать одну строку списка признаков артефакта."""
    if not isinstance(row, Mapping):
        raise SignalsModelError(f"артефакт модели signals ({source}): строка features — не объект")
    name = str(row.get("name") or "")
    transform = str(row.get("transform") or "")
    if transform not in _TRANSFORMS:
        raise SignalsModelError(
            f"артефакт модели signals ({source}): признак {name!r} просит преобразование "
            f"{transform!r}, известны {', '.join(sorted(_TRANSFORMS))}"
        )
    scale = _finite(row.get("scale"), field=f"{name}.scale", source=source)
    if scale == 0.0:
        raise SignalsModelError(
            f"артефакт модели signals ({source}): у признака {name!r} нормировка scale = 0 — "
            "деление на ноль при первом же кандидате"
        )
    return FeatureWeight(
        name=name,
        weight=_finite(row.get("weight"), field=f"{name}.weight", source=source),
        transform=transform,
        center=_finite(row.get("center"), field=f"{name}.center", source=source),
        scale=scale,
    )


def _finite(value: object, *, field: str, source: str) -> float:
    """Число артефакта обязано быть конечным: ``NaN`` в весе обнуляет балл молча."""
    try:
        number = float(value)  # type: ignore[arg-type]
    except (TypeError, ValueError) as error:
        raise SignalsModelError(
            f"артефакт модели signals ({source}): поле {field} — не число ({value!r})"
        ) from error
    if not math.isfinite(number):
        raise SignalsModelError(
            f"артефакт модели signals ({source}): поле {field} не конечно ({value!r})"
        )
    return number


def _parse_date(value: object, *, source: str) -> date:
    """Дата обучения: без неё нельзя сказать, насколько модель отстала от источников."""
    try:
        return date.fromisoformat(str(value))
    except ValueError as error:
        raise SignalsModelError(
            f"артефакт модели signals ({source}): дата обучения {value!r} не разбирается "
            "(ожидается ГГГГ-ММ-ДД)"
        ) from error


def _apply_link(total: float, link: str) -> float:
    """Перевести сумму весов в балл контракта — число от нуля до ста."""
    if link == "logistic":
        return 100.0 * _sigmoid(total)
    return 100.0 * min(1.0, max(0.0, total))


def _sigmoid(value: float) -> float:
    """Логистическая функция, устойчивая к большим по модулю аргументам."""
    if value >= 0.0:
        return 1.0 / (1.0 + math.exp(-value))
    exponent = math.exp(value)
    return exponent / (1.0 + exponent)
