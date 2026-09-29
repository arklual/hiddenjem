"""Разбор корпуса помнится между вопросами — и от этого ничего не меняется.

Нормализатор и извлечение кандидатов зависят только от корпуса и параметров разбора; направление в
них не участвует. Аналитик спрашивает по одному снапшоту несколько направлений, и каждый вопрос
считал их заново: 10.4 с из 21.7 с повторного запроса.

Экономия допустима ровно постольку, поскольку ответ от неё не меняется, — это здесь и проверяется.
Разбор: ``docs/01-analysis/71-the-corpus-parsed-once-per-question.md``.
"""

from __future__ import annotations

from datetime import UTC, date, datetime

from horizon_analytics.domain.extraction.blacklist import load_stopwords
from horizon_analytics.domain.extraction.corpus_cache import CorpusAnalysisCache
from horizon_analytics.domain.models import Document

STOPWORDS = load_stopwords()


def document(document_id: str, text: str) -> Document:
    return Document(
        document_id=document_id,
        source_id="openalex",
        source_class="JOURNAL_ARTICLE",
        external_id=document_id,
        title=text,
        abstract_text=text,
        published_on=date(2024, 1, 1),
        url=f"https://example.org/{document_id}",
        fetched_at=datetime(2024, 1, 2, tzinfo=UTC),
    )


CORPUS = [
    document("d1", "solid state batteries with sulfide electrolytes for energy storage"),
    document("d2", "network security and intrusion detection in industrial systems"),
    document("d3", "graph neural networks for molecular property prediction"),
]


def analyse(cache: CorpusAnalysisCache, documents: list[Document], *, ngram_max: int = 4):
    return cache.analyse(documents, stopwords=STOPWORDS, ngram_min=1, ngram_max=ngram_max)


def test_the_same_corpus_is_parsed_once() -> None:
    """Тот же корпус — тот же разбор, буквально тот же объект."""
    cache = CorpusAnalysisCache()
    assert analyse(cache, CORPUS) is analyse(cache, CORPUS)


def test_a_different_corpus_is_parsed_again() -> None:
    cache = CorpusAnalysisCache()
    first = analyse(cache, CORPUS)
    second = analyse(cache, [*CORPUS, document("d4", "quantum error correction surface codes")])
    assert first is not second
    assert first.extraction.candidates != second.extraction.candidates


def test_changed_extraction_parameters_are_part_of_the_key() -> None:
    """Иначе смена профиля методологии молча вернула бы кандидатов от прежних параметров."""
    cache = CorpusAnalysisCache()
    assert analyse(cache, CORPUS, ngram_max=4) is not analyse(cache, CORPUS, ngram_max=2)


def test_order_of_the_corpus_counts_as_a_difference() -> None:
    """От порядка зависит словарь нормализатора, значит он входит в отпечаток."""
    cache = CorpusAnalysisCache()
    assert analyse(cache, CORPUS) is not analyse(cache, list(reversed(CORPUS)))


def test_a_remembered_parse_equals_a_fresh_one() -> None:
    """Главное свойство: помнить — это не «почти то же самое»."""
    remembered = CorpusAnalysisCache()
    analyse(remembered, [*CORPUS[:2]])
    reused = analyse(remembered, CORPUS)
    fresh = analyse(CorpusAnalysisCache(), CORPUS)
    assert [candidate.key for candidate in reused.extraction.candidates] == [
        candidate.key for candidate in fresh.extraction.candidates
    ]
    assert reused.extraction.token_statistics == fresh.extraction.token_statistics
