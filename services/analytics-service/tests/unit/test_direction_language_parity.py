"""Направление, набранное по-английски, отбирает те же документы, что набранное по-русски.

Словарь направлений писался ради русского запроса: метки корпуса английские, и стем «интеллект» не
совпадает со стемом «intelligence». Английский запрос считался решённым сам собой — слова и так на
одном языке с метками. Замер опроверг это дважды, и оба раза по-разному.

**Первое.** Без статьи словаря запрос сопоставлялся с меткой отдельными словами, а со статьёй —
меткой целиком. «Кибербезопасность» давала 209 документов, ``cybersecurity`` — ноль: направление
объявлялось нераспознанным на корпусе, полном материала по теме.

**Второе, найденное этой же проверкой в её первой редакции — вернее, не найденное ею.** Та редакция
сравнивала статьи словаря и была зелёной, пока ``quantum computing`` отбирал 189 документов против
86 у «квантовых вычислений»: к меткам статьи подмешивались отдельные слова запроса, и стем ``comput``
забирал ``computer science``, ``cloud computing``, ``computational biology``, ``computer security``.
Кириллические стемы в это сопоставление не попадают по построению, поэтому расходились именно языки.

Отсюда форма проверки: сравниваются **отобранные документы**, а не то, из чего отбор складывается.
Совпадение промежуточных величин ничего не обещает о результате — ровно это и произошло.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from horizon_analytics.adapters.corpus.fixture_loader import load_documents
from horizon_analytics.domain.extraction.normalization import TermNormalizer
from horizon_analytics.domain.models import Document
from horizon_analytics.domain.pipeline import documents_in_direction

CORPUS = Path(__file__).resolve().parents[4] / "fixtures" / "corpus" / "documents.jsonl"

#: Направления приёмки парами. Пары, а не список: проверяется именно совпадение языков.
PAIRS = (
    ("искусственный интеллект", "artificial intelligence"),
    ("квантовые вычисления", "quantum computing"),
    ("кибербезопасность", "cybersecurity"),
    ("биотехнологии", "biotechnology"),
    ("финтех", "fintech"),
    ("накопители энергии", "energy storage"),
)


@pytest.fixture(scope="module")
def corpus() -> tuple[Document, ...]:
    # Не пропуск, а падение. Корпус лежит в репозитории, и его отсутствие означает сломанный путь,
    # а не отсутствующее окружение. Пропущенная проверка выглядит в отчёте как пройденная — на этом
    # уже терялись целые файлы проверок.
    assert CORPUS.is_file(), f"эталонный корпус не найден: {CORPUS}"
    return tuple(load_documents(CORPUS))


@pytest.fixture(scope="module")
def normalizer(corpus: tuple[Document, ...]) -> TermNormalizer:
    # Один в один с конвейером (`pipeline.py`: ``texts = [document.text ...]``). Реконструкция по
    # другому полю даёт другую разметку, и проверка перестаёт проверять продукт.
    return TermNormalizer.from_texts([document.text for document in corpus])


@pytest.mark.parametrize(("russian", "english"), PAIRS)
def test_both_languages_of_one_direction_select_the_same_documents(
    russian: str, english: str, corpus: tuple[Document, ...], normalizer: TermNormalizer
) -> None:
    selected_ru = {d.document_id for d in documents_in_direction(corpus, russian, normalizer)}
    selected_en = {d.document_id for d in documents_in_direction(corpus, english, normalizer)}

    assert selected_ru == selected_en, (
        f"«{russian}» отобрало {len(selected_ru)} док., «{english}» — {len(selected_en)}: "
        "один вопрос на двух языках должен давать один отчёт"
    )


@pytest.mark.parametrize(("russian", "english"), PAIRS)
def test_neither_language_leaves_the_direction_unresolved(
    russian: str, english: str, corpus: tuple[Document, ...], normalizer: TermNormalizer
) -> None:
    # Пустой отбор означает красную плашку «направление не распознано» и выдачу, не зависящую от
    # вопроса. Для направления приёмки это дефект, а не законный исход.
    assert documents_in_direction(corpus, russian, normalizer), f"«{russian}» ни одного документа"
    assert documents_in_direction(corpus, english, normalizer), f"«{english}» ни одного документа"


def test_an_unknown_direction_still_falls_back_to_matching_by_words(
    corpus: tuple[Document, ...], normalizer: TermNormalizer
) -> None:
    """Запасной путь обязан остаться: направление, статьи которому нет, — не пустой отчёт.

    Свободное сопоставление по словам вредно, когда словарь направление знает: там оно подмешивает
    к курируемому определению всё, что содержит общее слово. Когда не знает — оно единственный
    сигнал, и широкий отбор лучше отчёта, не зависящего от вопроса.
    """
    selected = documents_in_direction(corpus, "observability tooling", normalizer)

    assert selected, "неизвестное направление осталось без единого документа"
