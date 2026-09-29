"""Кусок имени, разрезанный скобкой, — не отдельный термин.

Окно n-грамм не знает про скобки: пройдя по «artificial intelligence (AI)», оно, кроме самого
термина, выдаёт `artificial intelligence ai` и `intelligence ai`. На настоящем корпусе (1785 работ
OpenAlex) у каждого из четырёх таких — по шесть десятков документов, то есть они конкурируют с
термином, куском которого являются.

Правило вычёркивает 1437 различных ключей корпуса, и все просмотрены: `machine learn ml`,
`unman aerial vehicle uav`, `federat learn fl`, `reactive oxygen specy ros` — одно семейство.

Разбор: ``docs/01-analysis/75-a-name-cut-by-a-bracket.md``.
"""

from __future__ import annotations

import pytest

from horizon_analytics.domain.extraction.normalization import AcronymDictionary, AcronymEntry


def dictionary() -> AcronymDictionary:
    return AcronymDictionary(
        entries={
            "ai": AcronymEntry("ai", "artificial intelligence", "artificial intelligence", 62),
            "iot": AcronymEntry("iot", "internet of things", "internet of thing", 64),
            "ml": AcronymEntry("ml", "machine learning", "machine learn", 25),
        }
    )


@pytest.mark.parametrize(
    "key",
    [
        "artificial intelligence ai",
        "intelligence ai",
        "internet of thing iot",
        "of thing iot",
        "thing iot",
        "machine learn ml",
        "learn ml",
    ],
)
def test_an_echo_of_the_long_form_is_not_a_term(key: str) -> None:
    assert dictionary().echoes_long_form(key)


@pytest.mark.parametrize(
    "key",
    [
        "explainable ai",  # самостоятельное имя, а не эхо
        "ai",  # сам термин; его сливает canonical_key
        "edge ai",
        "artificial intelligence",
        "quantum ml",
        "machine learn",
        "green iot",
    ],
)
def test_a_real_name_ending_in_an_acronym_survives(key: str) -> None:
    assert not dictionary().echoes_long_form(key)


def test_an_unknown_acronym_is_not_guessed() -> None:
    """Правило опирается на добытый словарь, а не на форму слова."""
    assert not dictionary().echoes_long_form("support vector svm")


def test_a_longer_prefix_than_the_long_form_is_not_an_echo() -> None:
    """«novel artificial intelligence ai» длиннее полной формы — окно захватило лишнее слово."""
    assert not dictionary().echoes_long_form("novel artificial intelligence ai")


def test_extraction_does_not_emit_the_echo() -> None:
    """Правило должно стоять в извлечении: фильтр имён до этих ключей не достаёт."""
    from tests.conftest import make_document

    from horizon_analytics.domain.extraction.blacklist import load_stopwords
    from horizon_analytics.domain.extraction.candidates import extract_candidates
    from horizon_analytics.domain.extraction.normalization import TermNormalizer

    texts = [
        "Artificial intelligence (AI) improves grid forecasting in every region studied.",
        "Artificial intelligence (AI) supports demand response across the network.",
        "Explainable AI remains the open question for operators of the network.",
    ]
    documents = [
        make_document(f"d{index}", year=2024, title="Study", abstract=text)
        for index, text in enumerate(texts)
    ]
    normalizer = TermNormalizer.from_texts(texts)
    extraction = extract_candidates(
        documents, normalizer=normalizer, stopwords=load_stopwords(), ngram_min=1, ngram_max=4
    )
    keys = {candidate.key for candidate in extraction.candidates}
    assert "artificial intelligence" in keys, "сам термин обязан остаться"
    assert "intelligence ai" not in keys
    assert "artificial intelligence ai" not in keys
