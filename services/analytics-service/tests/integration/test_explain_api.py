"""`POST /internal/explain` against the real application and the real fixture corpus.

Nothing here is mocked: the app is built by its own factory, the corpus is the golden one, and the
pipeline actually runs. That is the point — the endpoint's value is that its answer matches what the
analysis really did, and a test that stubbed the pipeline would assert the opposite of the feature.
"""

from __future__ import annotations

from collections.abc import Iterator
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from horizon_analytics.api.app import create_app
from horizon_analytics.application.explain_term import MAX_TERMS
from horizon_analytics.config import Settings

CORPUS = Path(__file__).resolve().parents[4] / "fixtures" / "corpus" / "documents.jsonl"
CACHE = Path(__file__).resolve().parents[1] / "fixtures" / "signals_cache_empty"
SECRET = "explain-test-secret"


@pytest.fixture(scope="module")
def client() -> Iterator[TestClient]:
    # Падение, а не пропуск. Корпус лежит в репозитории и отслеживается git — его отсутствие
    # означает сломанный путь, а не отсутствующее окружение. Пропущенная проверка выглядит в
    # отчёте пройденной: в этом проекте так уже терялись целые файлы проверок, когда путь
    # разъезжался на один каталог.
    assert CORPUS.is_file(), f"эталонный корпус не найден: {CORPUS}"
    # Populated by alias: the field is bound to its environment variable name.
    settings = Settings.model_validate(
        {
            "HORIZON_FIXTURE_CORPUS_PATH": str(CORPUS),
            "HORIZON_ANALYTICS_INTERNAL_SECRET": SECRET,
            # Объяснение повторяет прогон движка, а движок спрашивает внешние признаки. Пустой кэш
            # и запрет ходить в сеть: источники «молчат», и проверка не зависит от чужих серверов.
            "HORIZON_SIGNALS_CACHE_DIR": str(CACHE),
            "HORIZON_SIGNALS_CACHE_ONLY": "true",
        }
    )
    with TestClient(create_app(settings)) as test_client:
        test_client.headers["X-Internal-Token"] = SECRET
        yield test_client


def explain(client: TestClient, terms: list[str], **overrides: object) -> dict[str, object]:
    body: dict[str, object] = {
        "researchRequestId": "019fd789-0000-7000-8000-000000000001",
        "snapshotId": "fixture",
        "normalizedQuery": "artificial intelligence machine learning",
        "terms": terms,
        **overrides,
    }
    response = client.post("/internal/explain", json=body)
    assert response.status_code == 200, response.text
    payload: dict[str, object] = response.json()
    return payload


@pytest.mark.integration
def test_explains_why_a_term_is_absent_by_naming_the_stage_that_removed_it(
    client: TestClient,
) -> None:
    # A term that plainly does not belong to the AI direction: the answer must say *where* it was
    # dropped, not merely that it is missing. "Not in the report" is the question, not the answer.
    payload = explain(client, ["quantum error correction"])

    traces = payload["traces"]
    assert isinstance(traces, list) and traces, "термин должен быть прослежен, а не потерян молча"
    trace = traces[0]
    assert trace["stage"] in payload["stages"]
    assert trace["outcome"] != "ranked"
    assert trace["reason"], "у отказа должна быть причина, иначе трассировка бесполезна"


@pytest.mark.integration
def test_reports_the_stage_vocabulary_so_a_client_need_not_hardcode_it(client: TestClient) -> None:
    # The client renders the journey from this list. Hardcoding it there would let the UI fall
    # silently behind the day the pipeline gains a stage.
    payload = explain(client, ["speculative decoding"])

    stages = payload["stages"]
    assert isinstance(stages, list)
    assert stages[0] == "extracted" and stages[-1] == "ranked"


@pytest.mark.integration
def test_a_term_that_reaches_the_report_is_reported_as_present(client: TestClient) -> None:
    payload = explain(client, ["speculative decoding"])

    traces = payload["traces"]
    assert isinstance(traces, list) and traces
    trace = traces[0]
    # Whatever the verdict, `inReport` and the stage must agree — a client shows one and trusts both.
    assert trace["inReport"] is (trace["stage"] == "ranked")


@pytest.mark.integration
def test_rejects_more_terms_than_one_replay_may_answer(client: TestClient) -> None:
    # Each request replays a full analysis; without the bound one call could cost as much as a
    # hundred reports.
    response = client.post(
        "/internal/explain",
        json={
            "researchRequestId": "019fd789-0000-7000-8000-000000000001",
            "snapshotId": "fixture",
            "normalizedQuery": "artificial intelligence",
            "terms": [f"term {index}" for index in range(MAX_TERMS + 1)],
        },
    )

    assert response.status_code == 422


@pytest.mark.integration
def test_rejects_an_empty_term_list(client: TestClient) -> None:
    response = client.post(
        "/internal/explain",
        json={
            "researchRequestId": "019fd789-0000-7000-8000-000000000001",
            "snapshotId": "fixture",
            "normalizedQuery": "artificial intelligence",
            "terms": [],
        },
    )

    assert response.status_code == 422


@pytest.mark.integration
def test_readiness_probe_answers_ok(client: TestClient) -> None:
    """Regression: every endpoint taking the container dependency once returned 422.

    ``ContainerDep`` was a type alias local to the application factory, and with
    ``from __future__ import annotations`` FastAPI resolves annotations against the *module*
    namespace — it could not see the alias, so it treated the parameter as a required query string.
    The visible symptom was a readiness probe that never passed, which reads as an unhealthy
    deployment rather than as a bug, so nothing pointed at the cause.
    """
    response = client.get("/health/ready")

    assert response.status_code == 200
    assert response.json()["status"] == "ok"


class TestKnownDirections:
    """Список направлений, который видит аналитик перед пустым полем ввода.

    Существует ради самого трудного момента: система знает, какие формулировки распознаёт, и до
    этого эндпоинта их не показывала — аналитик гадал, а неудачная догадка стоила полного прогона,
    заканчивавшегося оговоркой «направление не распознано».
    """

    def test_the_list_comes_from_the_same_lexicon_the_selection_uses(
        self, client: TestClient
    ) -> None:
        # Второй список, собранный где-то ещё, разошёлся бы с первым и начал бы предлагать
        # формулировки, которые на деле не распознаются, — то есть врал бы ровно в том месте, ради
        # которого заведён.
        from horizon_analytics.domain.direction_lexicon import load_direction_lexicon

        response = client.get("/internal/directions")

        assert response.status_code == 200
        expected = {entry.surface for entry in load_direction_lexicon().values()}
        assert set(response.json()["directions"]) == expected

    def test_the_order_is_stable(self, client: TestClient) -> None:
        # Список, меняющий порядок от запуска к запуску, выглядит осмысленным, хотя смысла в этом
        # порядке нет.
        directions = client.get("/internal/directions").json()["directions"]

        assert directions == sorted(directions)

    def test_the_list_is_closed_to_outsiders(self, client: TestClient) -> None:
        # Внутренний контур закрыт токеном: маршрут не для браузера аналитика, а для соседней
        # службы, которая этот список ему и покажет.
        response = client.get("/internal/directions", headers={"X-Internal-Token": "wrong-secret"})

        assert response.status_code in (401, 403, 404)


class TestResolveDirection:
    """Соотнесение направления с кодами корпуса — шаг, которого не хватало сбору.

    Словарь применялся на отборе тем, то есть уже после сбора. Сам же сбор искал слова русского
    запроса в англоязычных документах и не находил ничего: «искусственный интеллект» — заглавное
    направление словаря — заканчивался отказом «не найдено ни одного документа» за секунду.
    """

    def test_russian_direction_resolves_to_corpus_codes(self, client: TestClient) -> None:
        response = client.post(
            "/internal/directions/resolve", json={"query": "искусственный интеллект"}
        )

        assert response.status_code == 200
        body = response.json()
        assert body["recognized"] is True
        # Цели отдаются как написаны в словаре, а не стеммированными: сбор ищет слова в тексте
        # документов, где они стоят целиком, и «artifici intellig» не нашло бы ничего.
        assert "artificial intelligence" in body["targets"]
        assert "cs.AI" in body["targets"]

    def test_targets_are_ordered(self, client: TestClient) -> None:
        # Порядок задан явно (ADR-0015): один и тот же запрос обязан давать один и тот же сбор.
        targets = client.post(
            "/internal/directions/resolve", json={"query": "квантовые вычисления"}
        ).json()["targets"]

        assert targets == sorted(targets)

    def test_case_and_inflection_do_not_matter(self, client: TestClient) -> None:
        # Обе стороны словаря стеммируются, поэтому падеж и число значения не имеют — иначе
        # «квантовых вычислений» было бы другим направлением, чем «квантовые вычисления».
        first = client.post(
            "/internal/directions/resolve", json={"query": "Квантовых вычислений"}
        ).json()
        second = client.post(
            "/internal/directions/resolve", json={"query": "квантовые вычисления"}
        ).json()

        assert first["targets"] == second["targets"]
        assert first["recognized"] is True

    def test_unknown_direction_returns_suggestions_instead_of_targets(
        self, client: TestClient
    ) -> None:
        # Тупик — худший исход: аналитик не узнает, чем его формулировка не подошла. Подсказки
        # показываются ровно тогда, когда целей нет: рядом с найденным ответом они были бы шумом.
        body = client.post(
            "/internal/directions/resolve", json={"query": "квантовый компьютинг"}
        ).json()

        assert body["recognized"] is False
        assert body["targets"] == []
        assert body["suggestions"]

    def test_direction_outside_the_corpus_vocabulary_is_honest_about_it(
        self, client: TestClient
    ) -> None:
        # Слова, которого нет ни в словаре, ни рядом с ним, придумывать нельзя: подсказка наугад
        # хуже её отсутствия, потому что выглядит как знание.
        body = client.post("/internal/directions/resolve", json={"query": "harness"}).json()

        assert body["recognized"] is False
        assert body["targets"] == []

    def test_the_route_is_closed_to_outsiders(self, client: TestClient) -> None:
        response = client.post(
            "/internal/directions/resolve",
            json={"query": "искусственный интеллект"},
            headers={"X-Internal-Token": "wrong-secret"},
        )

        assert response.status_code in (401, 403, 404)
