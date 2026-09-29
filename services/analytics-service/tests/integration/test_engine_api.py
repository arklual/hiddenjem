"""`POST /internal/analyze` считает единственным движком и отказывает на незнакомом имени.

Ничего не подменено: приложение собрано своей же фабрикой, корпус эталонный, конвейер работает.
Проверяется тот участок, где поле обычно и теряется, — модель HTTP-слоя: у неё своя форма, и всё,
чего в ней нет, она молча отбрасывает. Так уже пропадала пометка «это не технология»: обе половины
были зелёными при неработающей функции.
"""

from __future__ import annotations

from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient

from horizon_analytics.api.app import create_app
from horizon_analytics.config import Settings

CORPUS = Path(__file__).resolve().parents[4] / "fixtures" / "corpus" / "documents.jsonl"
#: Артефакт модели движка. Настоящий по форме, тестовый по содержанию: веса выбраны руками, и файл
#: об этом говорит. Без переменной движок берёт модель, поставляемую с пакетом, — это проверяется
#: отдельно, в `tests/unit/test_engine_selection.py`.
MODEL = Path(__file__).resolve().parents[1] / "fixtures" / "signals_model.json"
#: Кэш внешних признаков, которого нет, плюс запрет ходить в сеть: источники «молчат», темы
#: считаются по корпусной части. Так проверка движка не зависит от чужих серверов.
CACHE = Path(__file__).resolve().parents[1] / "fixtures" / "signals_cache_empty"
SECRET = "engine-test-secret"


def client_for() -> Iterator[TestClient]:
    """Клиент к приложению, собранному своей же фабрикой."""
    assert CORPUS.is_file(), f"эталонный корпус не найден: {CORPUS}"
    assert MODEL.is_file(), f"артефакт модели signals не найден: {MODEL}"
    settings = Settings.model_validate(
        {
            "HORIZON_FIXTURE_CORPUS_PATH": str(CORPUS),
            "HORIZON_ANALYTICS_INTERNAL_SECRET": SECRET,
            "HORIZON_SIGNALS_MODEL": str(MODEL),
            "HORIZON_SIGNALS_CACHE_DIR": str(CACHE),
            "HORIZON_SIGNALS_CACHE_ONLY": "true",
        }
    )
    with TestClient(create_app(settings)) as test_client:
        test_client.headers["X-Internal-Token"] = SECRET
        yield test_client


@pytest.fixture(scope="module")
def client() -> Iterator[TestClient]:
    yield from client_for()


def body(**overrides: Any) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "researchRequestId": "019fd789-0000-7000-8000-000000000001",
        "snapshotId": "fixture",
        "normalizedQuery": "artificial intelligence machine learning",
    }
    payload.update(overrides)
    return payload


@pytest.mark.integration
def test_the_report_is_signed_by_the_engine_the_request_asked_for(client: TestClient) -> None:
    response = client.post("/internal/analyze", json=body(engine="signals"))

    assert response.status_code == 200, response.text
    assert response.json()["result"]["engine"] == "signals"


@pytest.mark.integration
def test_a_request_without_an_engine_is_counted_by_signals(client: TestClient) -> None:
    response = client.post(
        "/internal/analyze", json=body(researchRequestId="019fd789-0000-7000-8000-000000000011")
    )

    assert response.status_code == 200, response.text
    assert response.json()["result"]["engine"] == "signals"


@pytest.mark.integration
def test_the_retired_methodology_name_is_counted_by_signals(client: TestClient) -> None:
    # Так приходят сохранённые направления и клиенты прежнего вида: отказ по ним ронял бы анализ,
    # который есть чем посчитать, а подпись отчёта честно называет движок, которым он посчитан.
    response = client.post(
        "/internal/analyze",
        json=body(researchRequestId="019fd789-0000-7000-8000-000000000012", engine="methodology"),
    )

    assert response.status_code == 200, response.text
    assert response.json()["result"]["engine"] == "signals"


@pytest.mark.integration
def test_an_unknown_engine_is_refused_with_a_message_that_names_the_known_ones(
    client: TestClient,
) -> None:
    # 422, а не 200 с умолчанием: опечатка отправителя, посчитанная как-нибудь, осталась бы
    # незамеченной.
    response = client.post("/internal/analyze", json=body(engine="signal"))

    assert response.status_code == 422, response.text
    problem = response.json()
    assert "signals" in problem["title"]
