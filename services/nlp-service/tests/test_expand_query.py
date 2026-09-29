"""Разложение направления на узкие поисковые запросы.

Сбор спрашивает источники словами направления и получает его центр, а слабый сигнал живёт на краю
(разбор 90). Здесь проверяется, что разложение даёт то, что примет каталог, и молчит, когда модель
ответила не по делу: пустой набор означает «собирать как раньше», а не «собирать по мусору».
"""

from __future__ import annotations

import pytest

from horizon_nlp import generative as module


@pytest.fixture
def client(monkeypatch: pytest.MonkeyPatch) -> module.GenerativeClient:
    return module.GenerativeClient()


def _answer(monkeypatch: pytest.MonkeyPatch, client: module.GenerativeClient, text: str) -> None:
    monkeypatch.setattr(client, "_complete", lambda *args, **kwargs: text)


def test_queries_come_with_their_group(monkeypatch, client) -> None:
    _answer(
        monkeypatch,
        client,
        '[{"query": "analog in-memory computing", "group": "emerging"},'
        ' {"query": "satellite edge inference", "group": "edge"}]',
    )

    queries, _ = client.expand_query("периферийные вычисления", ["edge computing"], 12)

    assert queries == (
        ("analog in-memory computing", "emerging", "en"),
        ("satellite edge inference", "edge", "en"),
    )


def test_target_repeats_and_one_word_queries_are_dropped(monkeypatch, client) -> None:
    """Опорный термин уже спрошен, а одно слово находит всё подряд."""
    _answer(
        monkeypatch,
        client,
        '[{"query": "edge computing", "group": "core"}, {"query": "NPU", "group": "core"},'
        ' {"query": "tinyML model compression", "group": "core"}]',
    )

    queries, _ = client.expand_query("периферийные вычисления", ["edge computing"], 12)

    assert [q for q, _, _ in queries] == ["tinyML model compression"]


def test_an_unparsable_answer_is_silence(monkeypatch, client) -> None:
    _answer(monkeypatch, client, "Вот несколько идей: edge AI, TinyML")

    queries, _ = client.expand_query("периферийные вычисления", [], 12)

    assert queries == ()


def test_the_limit_is_respected(monkeypatch, client) -> None:
    items = ", ".join(f'{{"query": "technology number {i}", "group": "core"}}' for i in range(20))
    _answer(monkeypatch, client, f"[{items}]")

    queries, _ = client.expand_query("направление", [], 5)

    assert len(queries) == 5


def test_symbols_are_cleaned(monkeypatch, client) -> None:
    _answer(monkeypatch, client, '[{"query": "\\"x402\\" payments protocol!", "group": "edge"}]')

    queries, _ = client.expand_query("финтех", [], 12)

    assert queries == (("x402 payments protocol", "edge", "en"),)


def test_russian_and_chinese_follow_their_subtopic(monkeypatch, client) -> None:
    """Подтема идёт тремя языками подряд: при нехватке бюджета она теряет язык, а не себя."""
    _answer(
        monkeypatch,
        client,
        '[{"query": "federated learning orchestration", "group": "edge",'
        ' "ru": "федеративное обучение; оркестрация", "zh": "联邦学习；编排"},'
        ' {"query": "analog in-memory computing", "group": "emerging", "ru": "", "zh": "存内计算"}]',
    )

    queries, _ = client.expand_query("периферийные вычисления", [], 12, ["en", "ru", "zh"])

    assert queries == (
        ("federated learning orchestration", "edge", "en"),
        ('"федеративное обучение" OR "оркестрация"', "edge", "ru"),
        ('"联邦学习" OR "编排"', "edge", "zh"),
        ("analog in-memory computing", "emerging", "en"),
        ('"存内计算"', "emerging", "zh"),
    )


def test_a_variant_in_the_wrong_script_is_dropped(monkeypatch, client) -> None:
    """Английское слово в поле ru повторило бы английский запрос, а не нашло русскую литературу."""
    _answer(
        monkeypatch,
        client,
        '[{"query": "tinyML model compression", "group": "core",'
        ' "ru": "tinyML model compression", "zh": "TinyML"}]',
    )

    queries, _ = client.expand_query("периферийные вычисления", [], 12, ["en", "ru", "zh"])

    assert queries == (("tinyML model compression", "core", "en"),)


def test_the_limit_counts_subtopics_not_languages(monkeypatch, client) -> None:
    items = ", ".join(
        f'{{"query": "technology number {i}", "group": "core", "ru": "технология {i}"}}'
        for i in range(10)
    )
    _answer(monkeypatch, client, f"[{items}]")

    queries, _ = client.expand_query("направление", [], 3, ["en", "ru"])

    assert len(queries) == 6


def test_documents_come_back_translated_by_id(monkeypatch, client) -> None:
    _answer(
        monkeypatch,
        client,
        '[{"id": "b", "title": "Federated learning for IoT", "abstract": "We study FL."},'
        ' {"id": "a", "title": "Федеративное обучение", "abstract": ""},'
        ' {"id": "zzz", "title": "Invented", "abstract": ""}]',
    )

    items, _ = client.translate_documents(
        [("a", "Федеративное обучение", ""), ("b", "联邦学习与物联网", "我们研究联邦学习。")]
    )

    # «a» вернулся непереведённым, «zzz» не спрашивали — оба отброшены.
    assert items == (("b", "Federated learning for IoT", "We study FL."),)


def test_long_abstracts_are_cut_before_translation(monkeypatch, client) -> None:
    seen: list[str] = []

    def complete(system, user, **kwargs):
        seen.append(user)
        return "[]"

    monkeypatch.setattr(client, "_complete", complete)

    client.translate_documents([("a", "Заголовок", "слово " * 2000)])

    assert len(seen[0]) < client.TRANSLATE_ABSTRACT_CHARS + 200
