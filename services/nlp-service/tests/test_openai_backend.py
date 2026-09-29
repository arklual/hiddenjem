"""Облачный бэкенд генеративной модели: формат запроса, разбор ответа, повтор при отказе.

Облачная модель нужна ради скорости: локальная на процессоре стенда читает около пятнадцати строк
за двадцать секунд, и петля семантической проверки упиралась в потолок (разбор 89). Выбор
бэкенда статический и раскрытый (ТЗ §3.1) — здесь проверяется, что он действительно работает так,
как объявлен, без сети.
"""

from __future__ import annotations

import json

import httpx
import pytest

from horizon_nlp import generative as module
from horizon_nlp.config import settings


@pytest.fixture
def openai_client(monkeypatch: pytest.MonkeyPatch) -> tuple[module.GenerativeClient, list[dict]]:
    monkeypatch.setattr(settings, "generative_backend", "openai")
    monkeypatch.setattr(settings, "openai_base_url", "https://api.example.test/v1")
    monkeypatch.setattr(settings, "openai_api_key", "test-key")
    monkeypatch.setattr(settings, "generative_model", "gpt-5.6-luna")
    monkeypatch.setattr(module.time, "sleep", lambda _: None)
    sent: list[dict] = []
    replies = iter(
        [
            httpx.Response(429, json={"error": "busy"}),
            httpx.Response(
                200,
                json={
                    "choices": [{"message": {"content": "1: ДА — метод\n2: НЕТ — общее слово"}}],
                    "usage": {"prompt_tokens": 10, "completion_tokens": 20},
                },
            ),
        ]
    )

    def handler(request: httpx.Request) -> httpx.Response:
        sent.append({"path": request.url.path, "auth": request.headers.get("authorization"),
                     "body": json.loads(request.content)})
        return next(replies)

    client = module.GenerativeClient()
    client._client = httpx.Client(
        base_url="https://api.example.test/v1",
        transport=httpx.MockTransport(handler),
        headers={"Authorization": "Bearer test-key"},
    )
    return client, sent


def test_the_judge_goes_to_chat_completions_and_survives_a_429(openai_client) -> None:
    client, sent = openai_client

    verdicts = client.judge_technologies(["speculative decoding", "fixed"])

    assert [verdict.is_technology for verdict in verdicts] == [True, False]
    assert len(sent) == 2, "429 обязан повторяться один раз"
    assert sent[-1]["path"] == "/v1/chat/completions"
    assert sent[-1]["auth"] == "Bearer test-key"
    assert sent[-1]["body"]["model"] == "gpt-5.6-luna"
    assert sent[-1]["body"]["messages"][0]["role"] == "system"


def test_the_judge_reads_source_fragments_without_changing_candidate_identity(openai_client) -> None:
    client, sent = openai_client
    verdicts = client.judge_technologies(
        ["distributed ledger storage", "financial infrastructure"],
        {"distributed ledger storage": ["Paper: a content-addressed distributed ledger storage protocol"]},
    )

    prompt = sent[-1]["body"]["messages"][1]["content"]
    assert "content-addressed distributed ledger storage protocol" in prompt
    assert [verdict.candidate for verdict in verdicts] == [
        "distributed ledger storage", "financial infrastructure"
    ]


def test_the_answer_budget_leaves_room_for_reasoning(openai_client) -> None:
    """Модели gpt-5 тратят часть бюджета на рассуждение; тот же потолок обрезал бы ответ судьи."""
    client, sent = openai_client

    client.judge_technologies(["speculative decoding", "fixed"])

    assert sent[-1]["body"]["max_completion_tokens"] > 1024


def test_the_backend_without_a_key_refuses_to_start(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "generative_backend", "openai")
    monkeypatch.setattr(settings, "openai_base_url", "")
    monkeypatch.setattr(settings, "openai_api_key", "")

    with pytest.raises(ValueError):
        module.GenerativeClient()


def test_a_dropped_connection_is_retried(monkeypatch: pytest.MonkeyPatch) -> None:
    # Обрыв связи («Connection reset by peer») остановил глубокое исследование «юридических
    # технологий» на второй минуте из пяти с половиной (стенд, 29.09).
    monkeypatch.setattr(settings, "generative_backend", "openai")
    monkeypatch.setattr(settings, "openai_base_url", "https://api.example.test/v1")
    monkeypatch.setattr(settings, "openai_api_key", "test-key")
    monkeypatch.setattr(module.time, "sleep", lambda _: None)
    calls: list[int] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(1)
        if len(calls) == 1:
            raise httpx.ConnectError("Connection reset by peer", request=request)
        return httpx.Response(200, json={"choices": [{"message": {"content": "ok"}}], "usage": {}})

    client = module.GenerativeClient()
    client._client = httpx.Client(base_url="https://api.example.test/v1", transport=httpx.MockTransport(handler))

    assert client.complete_text("system", "user", 16) == "ok"
    assert len(calls) == 2
