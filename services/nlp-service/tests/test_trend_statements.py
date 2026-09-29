"""Тема как тренд — одним русским предложением, и только из того, что есть в отчёте.

Проверяется разбор и приёмка ответа модели: число, которого нет во входе, — выдумка, и такое
предложение не доходит до отчёта; шаблон «Тема …» — не тренд; строка, о которой модель промолчала,
остаётся пустой, а не подставленной.
"""

from __future__ import annotations

import pytest

from horizon_nlp import generative as module

ITEM = {
    "direction": "защита искусственного интеллекта",
    "term": "canary tokens",
    "title": "канареечные токены",
    "stage": "Ускоряющаяся",
    "firstYear": 2023,
    "series": "2023 — 4, 2024 — 39, 2025 — 44",
    "fragments": ["Canary tokens detect intrusion when an attacker touches a planted secret."],
}


@pytest.fixture
def client() -> module.GenerativeClient:
    return module.GenerativeClient()


def _answer(monkeypatch: pytest.MonkeyPatch, client: module.GenerativeClient, text: str) -> None:
    monkeypatch.setattr(client, "_complete", lambda *args, **kwargs: text)


def test_a_sentence_about_what_changes_is_kept(monkeypatch, client) -> None:
    _answer(
        monkeypatch,
        client,
        "1: Защитники ИИ-систем начинают расставлять канареечные токены — приманки, которые "
        "выдают взломщика при первом обращении; работ стало 44 против 4 в 2023 году",
    )

    statements, model = client.formulate_trends([ITEM])

    assert statements[0] is not None
    assert statements[0].startswith("Защитники ИИ-систем")
    assert statements[0].endswith(".")
    assert model


def test_an_invented_number_rejects_the_sentence(monkeypatch, client) -> None:
    _answer(
        monkeypatch,
        client,
        "1: Защитники ИИ-систем начинают расставлять канареечные токены, и уже 70 процентов "
        "компаний используют их вместо классических систем обнаружения вторжений",
    )

    statements, _ = client.formulate_trends([ITEM])

    assert statements == (None,)


def test_the_template_is_not_a_trend(monkeypatch, client) -> None:
    _answer(
        monkeypatch,
        client,
        "1: Тема канареечные токены выделена по документам и касается защиты систем от атак",
    )

    statements, _ = client.formulate_trends([ITEM])

    assert statements == (None,)


def test_silence_stays_empty_and_order_is_kept(monkeypatch, client) -> None:
    second = {**ITEM, "term": "trigger inversion", "title": "инверсия триггеров"}
    _answer(
        monkeypatch,
        client,
        "2: Исследователи всё чаще восстанавливают скрытые триггеры бэкдоров в нейросетях, чтобы "
        "обезвредить закладку до того, как модель попадёт в работу",
    )

    statements, _ = client.formulate_trends([ITEM, second])

    assert statements[0] is None
    assert statements[1] is not None and statements[1].startswith("Исследователи")


def test_a_model_failure_is_not_a_report_failure(monkeypatch, client) -> None:
    def boom(*args, **kwargs):
        raise RuntimeError("model down")

    monkeypatch.setattr(client, "_complete", boom)

    statements, _ = client.formulate_trends([ITEM, ITEM])

    assert statements == (None, None)
