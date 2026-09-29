"""Направление, которого словарь не знает: фраза прежде слов.

Статья словаря есть не у всякой формулировки — и не должна быть, иначе продукт умеет ровно то, что
кто-то успел вписать. Для незнакомых направлений отбор идёт по словам запроса, и ведёт себя это
так же плохо, как пословное сопоставление вело себя для известных: на корпусе из 1785 работ
OpenAlex «energy harvesting» забирало 619 документов через `bluetooth low energy` и `atomic
energy`, «carbon capture» — 53 через `carbon footprint` и `carbon fiber`.

Фразой те же формулировки забирают 10 и 7 документов. Правило: сначала фраза целиком, слова —
только если фраза не нашлась ни у одного документа.

Разбор: ``docs/01-analysis/67-the-fallback-that-kept-the-old-rule.md``.
"""

from __future__ import annotations

from datetime import UTC, date, datetime

from horizon_analytics.domain.models import Document, DocumentTopic
from horizon_analytics.domain.pipeline import AnalysisPipeline


def document(document_id: str, *labels: str) -> Document:
    return Document(
        document_id=document_id,
        source_id="openalex",
        source_class="JOURNAL_ARTICLE",
        external_id=document_id,
        title=document_id,
        published_on=date(2024, 1, 1),
        url=f"https://example.org/{document_id}",
        fetched_at=datetime(2024, 1, 2, tzinfo=UTC),
        topics=tuple(DocumentTopic(code=label, label=label, score=0.5) for label in labels),
    )


def relevance(direction: str, *documents: Document) -> dict[str, float]:
    phrase = AnalysisPipeline._query_phrase(direction)
    return dict(
        AnalysisPipeline._subject_relevance(
            documents, frozenset(phrase.split(" ")), frozenset(), phrase
        )
    )


CAPTURE = document("capture", "Carbon capture and storage")
FOOTPRINT = document("footprint", "Carbon footprint")
FIBER = document("fiber", "Carbon fiber")


def test_the_phrase_wins_over_its_separate_words() -> None:
    """`carbon footprint` и `carbon fiber` — не улавливание углерода."""
    assert relevance("carbon capture", CAPTURE, FOOTPRINT, FIBER) == {"capture": 1.0}


def test_words_still_work_where_the_phrase_finds_nothing() -> None:
    """Хуже прежнего нигде: пословный путь остаётся там, где он был единственным."""
    measured = relevance(
        "solid oxide fuel cells", FOOTPRINT, document("cell", "Alkaline fuel cell")
    )
    assert set(measured) == {"cell"}


def test_a_one_word_direction_keeps_the_word_path() -> None:
    """Фраза из одного слова — это и есть слово; отдельного пути ей не нужно."""
    assert set(relevance("photovoltaics", document("pv", "Photovoltaics"))) == {"pv"}


def test_a_zero_scored_label_cannot_switch_the_path_on() -> None:
    """Отказ источника от суждения не должен решать, каким путём пойдёт отбор."""
    refused = Document(
        document_id="refused",
        source_id="openalex",
        source_class="JOURNAL_ARTICLE",
        external_id="refused",
        title="refused",
        published_on=date(2024, 1, 1),
        url="https://example.org/refused",
        fetched_at=datetime(2024, 1, 2, tzinfo=UTC),
        topics=(DocumentTopic(code="C1", label="Carbon capture and storage", score=0.0),),
    )
    assert set(relevance("carbon capture", refused, FOOTPRINT)) == {"footprint"}
