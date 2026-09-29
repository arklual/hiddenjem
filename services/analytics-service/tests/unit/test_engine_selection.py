"""Выбор движка: одно место, громкий отказ, честная подпись отчёта.

Проверяется не расчёт, а развилка перед ним: как имя движка доходит от запроса до подписи отчёта,
что происходит с именем выведенного движка методологии и с именем, которого нет вовсе. Сам расчёт
движка — в `test_signals_engine.py`.

Ошибка в развилке ничего не ломает: отчёт соберётся, числа будут настоящими, и единственным её
следом останется имя в поле `engine`. Ровно такие дефекты в этом проекте жили дольше всех —
пометка аналитика, которую молча отбрасывал слой HTTP, и указатель на отчёт, которого никто не
писал.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from datetime import date
from pathlib import Path

import pytest

from horizon_analytics.adapters.embeddings.tfidf_svd import TfidfSvdEmbeddingProvider
from horizon_analytics.application.dto import AnalyzeDomainCommand, build_domain_analyzed
from horizon_analytics.config import Settings
from horizon_analytics.container import build_signals
from horizon_analytics.domain.engines import (
    DEFAULT_ENGINE,
    ENGINE_NAMES,
    EngineContext,
    SignalsConfiguration,
    SignalsEngine,
    build_engine,
    parse_engine,
)
from horizon_analytics.domain.pipeline import PipelineResult
from horizon_analytics.domain.scoring.profile import MethodologyProfile
from horizon_analytics.domain.signal_scoring import SignalsModel
from horizon_analytics.resources import PACKAGED_SIGNALS_MODEL

#: Артефакт модели второго движка — тот же файл, которым пользуется проверка через HTTP.
MODEL = Path(__file__).resolve().parents[1] / "fixtures" / "signals_model.json"


class SilentProbe:
    """Источник, который ни о чём не знает. Законное состояние, а не подмена сети.

    Движку он нужен, чтобы собраться; спрашивать его здесь никто не будет — эти проверки про
    развилку перед расчётом, а не про расчёт.
    """

    @property
    def source_id(self) -> str:
        return "none"

    def features(self, terms: Sequence[str]) -> Mapping[str, Mapping[str, float]]:
        return {}


def context(signals: SignalsConfiguration | None = None) -> EngineContext:
    """Сотрудники движка — настоящие, без подмен: развилка строит то же, что боевой путь."""
    return EngineContext(
        embedding_provider=TfidfSvdEmbeddingProvider(),
        signals=(
            signals
            if signals is not None
            else SignalsConfiguration(model_path=MODEL, probe=SilentProbe())
        ),
    )


def command_payload(**overrides: object) -> dict[str, object]:
    """Команда в форме контракта — минимальная, но валидная."""
    profile = MethodologyProfile.default()
    payload: dict[str, object] = {
        "researchRequestId": "019fd789-0000-7000-8000-000000000001",
        "attempt": 1,
        "snapshotId": "019fd789-0000-7000-8000-000000000002",
        "normalizedQuery": "quantum sensing",
        "parameters": {"topN": 15, "yearsWindow": 7},
        "profile": {
            "profileId": profile.profile_id,
            "methodologyVersion": profile.methodology_version,
            "aggregator": profile.aggregator,
            "weights": dict(profile.weights),
            "parameters": {},
            "confidenceThreshold": profile.confidence_threshold,
        },
    }
    payload.update(overrides)
    return payload


# ───────────────────────────── разбор имени ─────────────────────────────


def test_an_absent_engine_means_the_only_one() -> None:
    # Отсутствие поля — обычное состояние старого отправителя, а не ошибка.
    assert parse_engine(None) == DEFAULT_ENGINE
    assert parse_engine("  ") == DEFAULT_ENGINE


def test_the_only_engine_is_signals() -> None:
    # Движок методологии выведен из продукта: отчёт всегда подписан `signals`.
    assert DEFAULT_ENGINE == "signals"
    assert ENGINE_NAMES == ("signals",)


@pytest.mark.parametrize("legacy", ["methodology", " Methodology "])
def test_the_retired_methodology_name_is_read_as_signals(legacy: str) -> None:
    # Его несут команды, отправленные до перемены, и сохранённые направления. Отказ ронял бы
    # анализ, который есть чем посчитать; подпись отчёта всё равно честно скажет `signals`.
    assert parse_engine(legacy) == "signals"
    assert isinstance(build_engine(legacy, context()), SignalsEngine)


@pytest.mark.parametrize("name", ENGINE_NAMES)
def test_every_declared_engine_can_be_built(name: str) -> None:
    # Движок, объявленный в контракте и забытый в таблице реализаций, падал бы только на запросе
    # именно с ним — то есть у аналитика, а не в сборке.
    assert build_engine(name, context()).name == name


def test_an_unknown_engine_is_refused_and_names_the_known_ones() -> None:
    # Опечатка отправителя, посчитанная умолчанием, осталась бы незамеченной: имя, которого нет, —
    # ошибка, а не просьба «посчитай как-нибудь».
    with pytest.raises(ValueError) as error:
        parse_engine("signal")

    assert "signal" in str(error.value)
    for known in ENGINE_NAMES:
        assert known in str(error.value)


def test_the_engine_signs_the_report_with_its_own_name() -> None:
    engine = build_engine("signals", context())

    assert isinstance(engine, SignalsEngine)
    assert engine.name == "signals"
    assert engine.model.version == "fixture-0.0.0"


# ───────────────────────────── прокидывание по команде ─────────────────────────────


def test_the_command_carries_the_engine_it_was_asked_for() -> None:
    command = AnalyzeDomainCommand.from_dict(command_payload(engine="signals"))

    assert command.engine == "signals"
    assert command.to_dict()["engine"] == "signals"


def test_a_command_without_an_engine_is_counted_by_signals() -> None:
    command = AnalyzeDomainCommand.from_dict(command_payload())

    assert command.engine == "signals"


def test_a_command_sent_before_the_change_is_counted_by_signals() -> None:
    # Команда, отправленная до перемены и доставленная после, — не ошибка команды.
    command = AnalyzeDomainCommand.from_dict(command_payload(engine="methodology"))

    assert command.engine == "signals"
    assert command.to_dict()["engine"] == "signals"


def test_a_command_with_an_unknown_engine_is_refused() -> None:
    # `ValueError` — то, что HTTP-слой превращает в 422, а worker — в `DomainAnalysisFailed`
    # с кодом `INVALID_COMMAND`. Оба ответа называют причину; тихого расчёта нет ни в одном.
    with pytest.raises(ValueError, match="unknown engine"):
        AnalyzeDomainCommand.from_dict(command_payload(engine="llm"))


@pytest.mark.parametrize("value", ["methodology", "signal"])
def test_a_leftover_engine_variable_neither_matters_nor_breaks_startup(
    monkeypatch: pytest.MonkeyPatch, value: str
) -> None:
    # `HORIZON_ENGINE` прежних развёртываний: движок больше не настраивается, и забытая в
    # манифесте переменная не должна ни ронять процесс, ни что-либо переключать.
    monkeypatch.setenv("HORIZON_ENGINE", value)

    assert not hasattr(Settings(), "engine")


# ───────────────────────────── артефакт модели ─────────────────────────────


def test_without_the_variable_the_packaged_model_is_used(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # Движок один, и свежая установка без `HORIZON_SIGNALS_MODEL` отказывала бы в каждом анализе.
    monkeypatch.delenv("HORIZON_SIGNALS_MODEL", raising=False)

    signals = build_signals(Settings())

    assert signals.model_path == PACKAGED_SIGNALS_MODEL
    assert SignalsModel.load(signals.model_path).version


def test_an_explicit_model_wins_over_the_packaged_one(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("HORIZON_SIGNALS_MODEL", str(MODEL))

    assert build_signals(Settings()).model_path == MODEL




# ───────────────────────────── очередь команд ─────────────────────────────


def test_the_worker_may_hold_a_command_longer_than_the_longest_saga() -> None:
    # Анализ идёт внутри цикла опроса; с умолчанием aiokafka (пять минут) брокер исключал бы
    # потребителя посреди анализа, и команда уходила бы на повторную доставку.
    assert Settings().kafka_max_poll_interval_ms >= 40 * 60 * 1000


# ───────────────────────────── подпись отчёта ─────────────────────────────


def _empty_result() -> PipelineResult:
    """Пустой результат: подпись отчёта не зависит от того, что нашёл расчёт."""
    return PipelineResult(
        trends=(),
        documents_analyzed=0,
        candidates_evaluated=0,
        truncated=False,
        window_from=date(2019, 1, 1),
        window_to=date(2026, 1, 1),
        embedding_model_id=None,
        stage_timings={},
        diagnostics={},
    )


def test_the_report_says_which_engine_produced_it() -> None:
    payload = build_domain_analyzed(
        _empty_result(),
        research_request_id="019fd789-0000-7000-8000-000000000001",
        attempt=1,
        snapshot_id="019fd789-0000-7000-8000-000000000002",
        profile=MethodologyProfile.default(),
        engine="signals",
    )

    assert payload["engine"] == "signals"


def test_a_report_built_without_an_engine_is_signed_signals() -> None:
    # Движок один: вызывающий, который о движках не знает, получает ту же подпись.
    payload = build_domain_analyzed(
        _empty_result(),
        research_request_id="019fd789-0000-7000-8000-000000000001",
        attempt=1,
        snapshot_id="019fd789-0000-7000-8000-000000000002",
        profile=MethodologyProfile.default(),
    )

    assert payload["engine"] == DEFAULT_ENGINE
