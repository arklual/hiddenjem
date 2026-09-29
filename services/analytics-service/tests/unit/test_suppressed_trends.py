"""Пометка аналитика «это не технология» возвращается в конвейер.

Существует потому, что измерением установлена граница метода, а не недоработка списка слов.
«concrete source passages», «retrieved passages», «stale documents» — грамматически правильные
именные группы, отличающиеся от имени технологии только смыслом. Замеры (docs/01-analysis/
30-boundary-filter-findings.md): балл эмерджентности даёт мусору 44.9–46.4 против 45.7–55.7 у
настоящих тем — распределения перекрываются полностью; термхуд ставит обрывок «state space» на 98-й
перцентиль, выше всех настоящих тем. Ни одна из мер их не отделяет, и пятая мера не отделит: они
отличаются смыслом.

Человек отличает их с одного взгляда. Продукт умел записать этот взгляд и показать его на том же
отчёте — и забыть: ни один путь анализа обратную связь не читал. Аналитик вычёркивал тему, а
следующий прогон ставил её на то же место.

Ключевое решение — **где** применять пометку. До отбора в ТОП-N, а не при показе: иначе вычеркнутая
тема продолжает занимать место, и аналитик получает тринадцать полезных тем вместо пятнадцати.
"""

from __future__ import annotations

from horizon_analytics.application.dto import AnalyzeDomainCommand
from horizon_analytics.domain.models import AnalysisParams


def command_payload(**parameters: object) -> dict[str, object]:
    return {
        "researchRequestId": "019fd789-0000-7000-8000-000000000001",
        "attempt": 1,
        "snapshotId": "fixture",
        "normalizedQuery": "искусственный интеллект",
        "parameters": {"topN": 15, "yearsWindow": 7, **parameters},
        "profile": {
            "profileId": "019fd789-0000-7000-8000-0000000000ff",
            "methodologyVersion": "em-1.0.0",
            "aggregator": "WEIGHTED_GEOMETRIC",
            "weights": {
                "novelty": 0.2,
                "growth": 0.2,
                "diffusion": 0.15,
                "weakness": 0.15,
                "coherence": 0.15,
                "impact": 0.15,
            },
            "parameters": {},
            "confidenceThreshold": 0.35,
        },
    }


class TestTheMarkReachesTheEngine:
    def test_the_command_carries_the_suppressed_keys(self) -> None:
        command = AnalyzeDomainCommand.from_dict(
            command_payload(suppressedTrendKeys=["stale document", "retriev passage"])
        )

        assert command.params.suppressed_keys == frozenset({"stale document", "retriev passage"})

    def test_a_command_without_the_field_stays_valid(self) -> None:
        # Отправитель, не знающий про поле, обязан работать как раньше: пометок просто нет.
        command = AnalyzeDomainCommand.from_dict(command_payload())

        assert command.params.suppressed_keys == frozenset()

    def test_duplicates_and_order_do_not_matter(self) -> None:
        # Множество, а не список: два аналитика, пометившие одно и то же, не должны давать разный
        # запрос — иначе отчёт перестанет быть воспроизводимым (ADR-0015).
        first = AnalyzeDomainCommand.from_dict(command_payload(suppressedTrendKeys=["b", "a"]))
        second = AnalyzeDomainCommand.from_dict(
            command_payload(suppressedTrendKeys=["a", "b", "a"])
        )

        assert first.params.suppressed_keys == second.params.suppressed_keys

    def test_the_serialised_command_is_byte_stable(self) -> None:
        # Множество не имеет порядка, а сериализация обязана быть воспроизводимой: без сортировки
        # один и тот же запрос давал бы разные байты от прогона к прогону, потому что порядок
        # обхода множества строк зависит от затравки хеша процесса.
        #
        # Ключей намеренно восемь, а не три. С тремя порядок множества совпадает с сортированным
        # каждый шестой раз, и подмена «сериализовать без сортировки» прошла проверку незамеченной —
        # тест был не несущим ровно в том месте, ради которого написан.
        keys = ["zeta", "alpha", "mu", "theta", "beta", "omega", "delta", "kappa"]
        command = AnalyzeDomainCommand.from_dict(command_payload(suppressedTrendKeys=keys))

        assert command.to_dict()["parameters"]["suppressedTrendKeys"] == sorted(keys)

    def test_an_empty_mark_list_is_not_serialised(self) -> None:
        # Пустое поле в полезной нагрузке — шум, который придётся объяснять читателю журнала.
        command = AnalyzeDomainCommand.from_dict(command_payload())

        assert "suppressedTrendKeys" not in command.to_dict()["parameters"]


class TestTheHttpLayerDoesNotSwallowTheField:
    """Слой HTTP имеет свою модель и молча отбрасывает всё, чего в ней нет.

    Первая версия правки добавила поле в разбор команды и в домен, но не в модель запроса. Всё
    собиралось, все типы сходились, тесты разбора были зелёными — а пометка до конвейера не
    доходила. Поймано пробой поведением: «помеченные ушли: False».

    Проверка существует ровно затем, чтобы этот разрыв нельзя было завести снова: она идёт от
    внешней границы, а не от внутренней.
    """

    def test_the_request_model_forwards_the_keys_to_the_command(self) -> None:
        from horizon_analytics.api.schemas import AnalyzeRequest

        request = AnalyzeRequest.model_validate(
            {
                "researchRequestId": "019fd789-0000-7000-8000-000000000001",
                "snapshotId": "fixture",
                "normalizedQuery": "искусственный интеллект",
                "parameters": {"topN": 15, "suppressedTrendKeys": ["stale document"]},
            }
        )

        payload = request.to_command_dict()

        assert payload["parameters"]["suppressedTrendKeys"] == ["stale document"]


class TestTheParameterIsPartOfTheRunNotOfTheView:
    def test_analysis_params_default_to_no_marks(self) -> None:
        assert AnalysisParams().suppressed_keys == frozenset()

    def test_the_parameter_survives_construction_with_marks(self) -> None:
        params = AnalysisParams(suppressed_keys=frozenset({"a"}))

        assert params.suppressed_keys == frozenset({"a"})
