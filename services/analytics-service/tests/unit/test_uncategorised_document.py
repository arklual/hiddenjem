"""Документ без предметных кодов: что с ним происходит сегодня.

Такой документ продукт встретит на первом же живом RSS — `<category>` в формате необязателен, — а
не проверяется этот путь сейчас ничем: в эталонном корпусе документов без кодов 0 из 1244, в
выгрузке OpenAlex коды несут все работы.

Проверка **закрепляет нынешнее поведение, а не одобряет его**. Половина его верна: документ без
кодов не попадает в числитель отбора направления — источник о нём ничего не сказал, и выдумывать за
источник нельзя. Вторая половина — дефект: в знаменателе базовой доли он стоит и потому опускает
порог для всех тем (пункт 14 бэклога, замер: 22 % таких документов опустили порог с 0.704 до 0.575).

Когда знаменатель починят, первый случай останется верным, а различие станет видно по второму.
"""

from __future__ import annotations

from datetime import UTC, date, datetime

from horizon_analytics.domain.models import Document, DocumentTopic
from horizon_analytics.domain.pipeline import AnalysisPipeline

LABELS = frozenset({"computer security"})


def document(document_id: str, *topics: DocumentTopic) -> Document:
    return Document(
        document_id=document_id,
        source_id="rss",
        source_class="NEWS",
        external_id=document_id,
        title=document_id,
        published_on=date(2024, 1, 1),
        url=f"https://example.org/{document_id}",
        fetched_at=datetime(2024, 1, 2, tzinfo=UTC),
        topics=topics,
    )


def relevance(*documents: Document) -> dict[str, float]:
    return dict(AnalysisPipeline._subject_relevance(documents, frozenset(), LABELS))


def test_a_document_without_codes_is_not_placed_in_a_direction() -> None:
    """Источник о нём ничего не сказал — значит и мы не говорим."""
    assert relevance(document("news-1")) == {}


def test_it_does_not_shadow_a_document_that_has_codes() -> None:
    """Соседство с нерубрицированным не должно менять судьбу рубрицированного."""
    categorised = document("paper", DocumentTopic(code="C1", label="Computer security", score=0.5))
    assert relevance(categorised, document("news-1"), document("news-2")) == {"paper": 1.0}


def test_the_empty_category_list_and_a_missing_one_mean_the_same() -> None:
    """`<category>` без значения — не рубрикация, а её отсутствие."""
    blank = document("news-3", DocumentTopic(code="", label="", score=None))
    assert relevance(blank) == {}
