"""Имена технологий направления: второй источник кандидатов аналитического движка.

Здесь проверяется только разбор ответа модели. Граница ТЗ §3.1 — «выдача не формируется
исключительно на основании знаний языковой модели» — проходит не здесь, а в движке: имя
публикуется лишь после того, как открытые источники показали по нему измеренную активность.
Этот сервис обязан сделать ровно две вещи честно: отдать имена в том виде, в каком их примет
поиск, и промолчать, когда модель ответила не по делу.
"""

from __future__ import annotations

import pytest

from horizon_nlp import generative as module


@pytest.fixture
def client() -> module.GenerativeClient:
    return module.GenerativeClient()


def _answer(monkeypatch: pytest.MonkeyPatch, client: module.GenerativeClient, text: str) -> None:
    monkeypatch.setattr(client, "_complete", lambda *args, **kwargs: text)


def test_names_come_back_in_the_order_the_model_gave_them(monkeypatch, client) -> None:
    _answer(
        monkeypatch,
        client,
        '["photonic processors", "analog compute-in-memory", "event-based vision sensors"]',
    )

    names, model = client.propose_technologies("фотоника", 10)

    assert names == (
        "photonic processors",
        "analog compute-in-memory",
        "event-based vision sensors",
    )
    assert model


def test_repeats_and_one_word_names_are_dropped(monkeypatch, client) -> None:
    # Одно слово находит всё подряд, и подтвердить его «активностью» может что угодно — ровно та
    # ошибка, ради невозможности которой и заведена проверка источниками.
    _answer(
        monkeypatch,
        client,
        '["photonic processors", "Photonic Processors", "NPU", "analog compute-in-memory"]',
    )

    names, _ = client.propose_technologies("фотоника", 10)

    assert names == ("photonic processors", "analog compute-in-memory")


def test_the_limit_is_respected(monkeypatch, client) -> None:
    _answer(monkeypatch, client, '["photonic processors", "analog compute-in-memory"]')

    names, _ = client.propose_technologies("фотоника", 1)

    assert names == ("photonic processors",)


def test_an_unparsable_answer_is_silence(monkeypatch, client) -> None:
    # Пустой список означает «движок считает по корпусу, как считал», а не «кандидатов нет».
    _answer(monkeypatch, client, "Вот несколько идей: photonic processors и другие")

    names, _ = client.propose_technologies("фотоника", 10)

    assert names == ()


def test_a_failing_model_is_silence_too(monkeypatch, client) -> None:
    def explode(*args: object, **kwargs: object) -> str:
        raise RuntimeError("модель недоступна")

    monkeypatch.setattr(client, "_complete", explode)

    names, _ = client.propose_technologies("фотоника", 10)

    assert names == ()


def test_an_empty_direction_is_not_asked_about(monkeypatch, client) -> None:
    def explode(*args: object, **kwargs: object) -> str:
        raise AssertionError("модель не должна спрашиваться о пустом направлении")

    monkeypatch.setattr(client, "_complete", explode)

    assert client.propose_technologies("   ", 10)[0] == ()
    assert client.propose_technologies("фотоника", 0)[0] == ()
