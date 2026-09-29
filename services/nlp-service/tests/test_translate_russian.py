"""Русский слой отчёта — переводом генеративной модели, строка в строку.

Проверяется разбор ответа: порядок и число строк сохраняются, уже русская строка модели не
отдаётся, ответ без кириллицы переводом не считается, отказ модели оставляет оригинал.
"""

from __future__ import annotations

import json

import pytest

from horizon_nlp import generative as module


@pytest.fixture
def client() -> module.GenerativeClient:
    return module.GenerativeClient()


def _echo_translation(system: str, user: str, *, num_predict: int) -> str:
    items = json.loads(user)
    return json.dumps([{"id": item["id"], "text": "перевод: " + item["text"]} for item in items])


def test_order_and_count_are_kept_and_russian_is_left_alone(monkeypatch, client) -> None:
    asked: list[str] = []

    def complete(system: str, user: str, *, num_predict: int) -> str:
        asked.extend(item["text"] for item in json.loads(user))
        return _echo_translation(system, user, num_predict=num_predict)

    monkeypatch.setattr(client, "_complete", complete)

    texts, model = client.translate_russian(["jailbreak attacks", "", "уже по-русски", "RAG pipelines"])

    assert texts == ("перевод: jailbreak attacks", "", "уже по-русски", "перевод: RAG pipelines")
    assert asked == ["jailbreak attacks", "RAG pipelines"]
    assert model


def test_an_answer_without_cyrillic_is_not_a_translation(monkeypatch, client) -> None:
    monkeypatch.setattr(
        client,
        "_complete",
        lambda system, user, *, num_predict: json.dumps([{"id": 0, "text": "jailbreak attacks"}]),
    )

    texts, _ = client.translate_russian(["jailbreak attacks"])

    assert texts == ("jailbreak attacks",)


def test_a_model_failure_keeps_the_original(monkeypatch, client) -> None:
    def boom(system: str, user: str, *, num_predict: int) -> str:
        raise RuntimeError("model down")

    monkeypatch.setattr(client, "_complete", boom)

    texts, _ = client.translate_russian(["canary tokens", "trigger inversion"])

    assert texts == ("canary tokens", "trigger inversion")


def test_long_inputs_are_split_into_batches(monkeypatch, client) -> None:
    calls: list[int] = []

    def complete(system: str, user: str, *, num_predict: int) -> str:
        calls.append(len(json.loads(user)))
        return _echo_translation(system, user, num_predict=num_predict)

    monkeypatch.setattr(client, "_complete", complete)

    texts, _ = client.translate_russian([f"term {index}" for index in range(70)])

    assert sorted(calls) == [10, 30, 30]
    assert texts[69] == "перевод: term 69"
