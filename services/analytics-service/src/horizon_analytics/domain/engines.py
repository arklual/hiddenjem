"""Каким движком считается отчёт.

Движок — это целиком способ получить отчёт по корпусу, а не настройка внутри одного способа.
Движок один — ``signals``: общий конвейер (:mod:`horizon_analytics.domain.pipeline`) отбирает
корпус, извлекает кандидатов и считает индикаторы, а балл, которым кандидаты ранжируются, ставит
обученная линейная модель по следам технологии вне корпуса
(:mod:`horizon_analytics.domain.signal_scoring`).

Движок методологии — тот же конвейер с баллом из индикаторов и весов профиля — выведен из продукта.
Конвейер остался: на нём стоит ``signals``, и по нему считают офлайн-инструменты (бэктест, гейты).
Имя ``methodology`` читается как ``signals``: его несут команды, отправленные до перемены, и
сохранённые направления, и отказ по ним ронял бы анализ, который есть чем посчитать.

Выбор сделан ровно в одном месте — :func:`build_engine`: разветвление по имени движка, расползшееся
по сценарию использования, по HTTP-слою и по сериализации, гарантирует, что однажды одна из веток
отстанет от остальных — а отчёт при этом соберётся и будет выглядеть целым.

Неизвестное имя — по-прежнему отказ, а не молчаливый откат к умолчанию: имя, которого нет, — ошибка
отправителя, и считать по нему «как-нибудь» значит скрыть её.
"""

from __future__ import annotations

from collections.abc import Callable, Mapping
from dataclasses import dataclass, field
from pathlib import Path
from typing import Final, Literal, Protocol, get_args

from horizon_analytics.domain.extraction.corpus_cache import CorpusAnalysisCache
from horizon_analytics.domain.narration.base import TrendNarrator
from horizon_analytics.domain.pipeline import (
    AnalysisPipeline,
    PipelineRequest,
    PipelineResult,
    ProgressListener,
)
from horizon_analytics.domain.ports import (
    EmbeddingProvider,
    MaturityProbe,
    SignalsProbe,
    TechnologyJudge,
    TechnologyProposer,
)
from horizon_analytics.domain.signal_scoring import (
    DEFAULT_PROPOSED_LIMIT,
    DEFAULT_TOP_CANDIDATES,
    CandidateSource,
    ProposedCandidates,
    SignalsModel,
    SignalsModelError,
    SignalsRescorer,
    TopicRescorer,
)

__all__ = [
    "DEFAULT_ENGINE",
    "ENGINE_NAMES",
    "AnalysisEngine",
    "EngineContext",
    "EngineName",
    "SignalsConfiguration",
    "SignalsEngine",
    "build_engine",
    "parse_engine",
]

#: Имена движков в том виде, в каком они ходят по контракту: строчными, как в
#: ``analyze-domain.command.json`` и в ``domain-analyzed.event.json``. Отчёт всегда подписан
#: ``signals``.
EngineName = Literal["signals"]

ENGINE_NAMES: Final[tuple[EngineName, ...]] = get_args(EngineName)

#: Единственный движок — он же умолчание для команды, которая движка не назвала.
DEFAULT_ENGINE: Final[EngineName] = "signals"

#: Имена выведенных из продукта движков и чем они считаются теперь. ``methodology`` приходит в
#: командах, отправленных до перемены, и в сохранённых направлениях: отказ по нему ронял бы анализ,
#: который есть чем посчитать, а подпись отчёта всё равно честно скажет ``signals``.
LEGACY_ENGINE_ALIASES: Final[Mapping[str, EngineName]] = {"methodology": "signals"}


def parse_engine(value: object | None) -> EngineName:
    """Привести имя движка к известному.

    Args:
        value: имя из команды или из запроса; ``None`` и пустая строка означают «не просили».

    Raises:
        ValueError: имя задано, но движка с таким именем нет и оно не из выведенных. Отказ
            намеренно громкий: опечатка отправителя, посчитанная умолчанием, осталась бы
            незамеченной.
    """
    if value is None or (isinstance(value, str) and not value.strip()):
        return DEFAULT_ENGINE
    name = str(value).strip().lower()
    if name in LEGACY_ENGINE_ALIASES:
        return LEGACY_ENGINE_ALIASES[name]
    if name not in ENGINE_NAMES:
        known = ", ".join((*ENGINE_NAMES, *LEGACY_ENGINE_ALIASES))
        raise ValueError(f"unknown engine {value!r}: expected one of {known}")
    return name


@dataclass(frozen=True, slots=True)
class SignalsConfiguration:
    """Всё, что нужно движку внешних признаков, и ничего, что нужно конвейеру.

    Отдельная запись, а не три поля в :class:`EngineContext`: конвейер об этих настройках не
    знает, и движок, который однажды будет считать по патентам, добавит свою запись, а не ещё три
    поля в общий контекст.

    Оба сотрудника обязательны, и оба проверяются при сборке движка, а не при первом кандидате:
    молчать о развёртывании без артефакта до середины анализа значит потратить на сбор признаков
    минуты, чтобы потом всё равно отказать.

    Attributes:
        model_path: Путь к артефакту обученной модели: ``HORIZON_SIGNALS_MODEL`` или артефакт,
            поставляемый с пакетом (:data:`horizon_analytics.resources.PACKAGED_SIGNALS_MODEL`).
        probe: Откуда берутся внешние признаки.
        top_candidates: Скольких кандидатов спрашивать снаружи.
        proposer: Кто предлагает имена технологий; ``None`` — только корпусные кандидаты.
        proposed_limit: Сколько имён просить на одно направление.
    """

    model_path: Path | None = None
    probe: SignalsProbe | None = None
    top_candidates: int = DEFAULT_TOP_CANDIDATES
    #: Кто предлагает имена технологий сверх корпусных кандидатов. ``None`` — второго источника
    #: нет, движок считает по корпусу: выключено по умолчанию, чтобы умолчание стенда не
    #: менялось само вместе с появлением новой возможности.
    proposer: TechnologyProposer | None = None
    proposed_limit: int = DEFAULT_PROPOSED_LIMIT


@dataclass(frozen=True, slots=True)
class EngineContext:
    """Сотрудники, из которых собирается движок.

    Не сам конвейер, а то, из чего его строят: движку, который считает иначе, конвейер методологии
    не нужен, и передавать его значило бы обязать каждую реализацию принимать чужой инструмент.
    """

    embedding_provider: EmbeddingProvider
    narrator: TrendNarrator | None = None
    corpus_cache: CorpusAnalysisCache | None = None
    technology_judge: TechnologyJudge | None = None
    maturity_probe: MaturityProbe | None = None
    #: Настройки движка. Пустые — законное состояние для тестов конвейера и офлайн-инструментов:
    #: движок из них не соберётся и скажет почему.
    signals: SignalsConfiguration = field(default_factory=SignalsConfiguration)

    def pipeline(
        self,
        rescorer: TopicRescorer | None = None,
        candidates: CandidateSource | None = None,
    ) -> AnalysisPipeline:
        """Собрать конвейер из этих сотрудников.

        Args:
            rescorer: Кто ставит балл ранжирования вместо индикаторов методологии. ``None`` —
                балл из индикаторов, как у офлайн-инструментов.
            candidates: Второй источник кандидатов. ``None`` — только корпус.
        """
        return AnalysisPipeline(
            embedding_provider=self.embedding_provider,
            narrator=self.narrator,
            corpus_cache=self.corpus_cache,
            technology_judge=self.technology_judge,
            maturity_probe=self.maturity_probe,
            rescorer=rescorer,
            candidates=candidates,
        )


class AnalysisEngine(Protocol):
    """Способ получить отчёт по корпусу."""

    @property
    def name(self) -> EngineName:
        """Имя, которым движок подписывает отчёт."""

    def run(
        self, request: PipelineRequest, listener: ProgressListener | None = None
    ) -> PipelineResult:
        """Посчитать отчёт.

        Вызывается из отдельного потока, вне цикла событий: синхронный и не обращающийся к сети —
        то же требование, что у конвейера методологии.
        """


@dataclass(frozen=True, slots=True)
class SignalsEngine:
    """Скоринг по внешним признакам: корпус общий, балл — из обученной модели.

    Общая часть с конвейером методологии взята целиком и не скопирована: отбор корпуса, извлечение
    кандидатов, слияние синонимов, отнесение к направлению и проверка достоверности — тот же
    конвейер, тот же код. Копия разошлась бы с оригиналом при первой же правке методологии, и
    расхождение читалось бы как «второй движок нашёл другое», хотя нашёл бы он то же самое
    позавчерашним способом.

    Своё у движка ровно одно и названо одним словом: балл, которым кандидаты ранжируются. Его
    ставит линейная модель по признакам технологии вне корпуса — работам OpenAlex, организациям,
    обсуждениям, статье в Википедии — плюс корпусная частота и наклон. Веса прочитаны из
    артефакта, вклад каждого признака сохранён в отчёте: «почему эта тема поднялась» обязано
    иметь ответ, иначе модель — оракул, а отчёт — его пересказ.
    """

    pipeline: AnalysisPipeline
    #: Артефакт, которым посчитан отчёт. Хранится, чтобы версию модели можно было назвать до
    #: прогона — например, в журнале выбора движка.
    model: SignalsModel

    @property
    def name(self) -> EngineName:
        """Подпись отчёта."""
        return "signals"

    def run(
        self, request: PipelineRequest, listener: ProgressListener | None = None
    ) -> PipelineResult:
        """Прогнать общий конвейер с чужим баллом ранжирования."""
        return self.pipeline.run(request, listener)


def _build_signals(context: EngineContext) -> AnalysisEngine:
    """Собрать движок внешних признаков.

    Raises:
        SignalsModelError: артефакта модели нет, он не читается или не совпадает со списком
            признаков; либо не задан источник внешних признаков. Отказ здесь намеренный и
            громкий: откатываться не к чему, а отчёт, посчитанный баллом индикаторов и подписанный
            ``signals``, — ровно та подмена, ради невозможности которой заведено поле ``engine``.
    """
    settings = context.signals
    model = SignalsModel.load(settings.model_path)
    if settings.probe is None:
        raise SignalsModelError(
            "движку signals нужен источник внешних признаков, а он не задан: "
            "EngineContext.signals.probe (в развёртывании — HORIZON_SIGNALS_CACHE_DIR и "
            "сборка LiveSignalsProbe в компоновочном корне)"
        )
    rescorer = SignalsRescorer(
        model=model, probe=settings.probe, top_candidates=settings.top_candidates
    )
    # Второй источник кандидатов необязателен и включается отдельно: корпус всё равно останется
    # основным, а развёртывание без сервиса моделей обязано считать так же, как считало.
    candidates = (
        ProposedCandidates(
            proposer=settings.proposer, probe=settings.probe, limit=settings.proposed_limit
        )
        if settings.proposer is not None
        else None
    )
    return SignalsEngine(pipeline=context.pipeline(rescorer, candidates), model=model)


#: Единственная таблица соответствия «имя → реализация». Полнота проверяется тестом: движок,
#: объявленный в контракте и забытый здесь, падал бы только на запросе именно с ним.
_FACTORIES: Final[Mapping[EngineName, Callable[[EngineContext], AnalysisEngine]]] = {
    "signals": _build_signals,
}


def build_engine(name: object | None, context: EngineContext) -> AnalysisEngine:
    """Выбрать и собрать движок по имени — единственная точка выбора в сервисе."""
    return _FACTORIES[parse_engine(name)](context)
