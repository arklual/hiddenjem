"""Отнесение к направлению по кодам источника: вес кода — часть суждения.

Правило §12 опирается на то, что «источник сам отнёс документ к этой рубрике». У OpenAlex рубрика
приходит с уверенностью классификатора, и уверенность бывает нулевой: в корпусе из 1785 настоящих
работ у четверти документов с меткой ``Computer security`` ``score`` равен ровно 0.0, а среди них
три статьи Event Horizon Telescope про чёрные дыры. Пока вес не смотрели, тема ``black hole``
выходила в отчёт по информационной безопасности с долей направления 1.00.

Разбор: ``docs/01-analysis/64-a-label-the-source-did-not-mean.md``.
"""

from __future__ import annotations

from datetime import UTC, date, datetime

from horizon_analytics.domain.models import Document, DocumentTopic
from horizon_analytics.domain.pipeline import AnalysisPipeline

LABELS = frozenset({"computer security"})


def document(document_id: str, *topics: DocumentTopic) -> Document:
    return Document(
        document_id=document_id,
        source_id="openalex",
        source_class="JOURNAL_ARTICLE",
        external_id=document_id,
        title=document_id,
        published_on=date(2024, 1, 1),
        url=f"https://example.org/{document_id}",
        fetched_at=datetime(2024, 1, 2, tzinfo=UTC),
        topics=topics,
    )


def relevance(*documents: Document) -> dict[str, float]:
    return dict(AnalysisPipeline._subject_relevance(documents, frozenset(), LABELS))


def test_zero_confidence_is_not_a_classification() -> None:
    """Ноль — утверждение источника об отсутствии уверенности, а не слабое «да»."""
    astrophysics = document(
        "eht", DocumentTopic(code="C38652104", label="Computer security", score=0.0)
    )
    assert relevance(astrophysics) == {}


def test_any_positive_confidence_still_counts() -> None:
    """Отсечка ровно по нулю: 0.0644 у той же астрофизики отсекать было бы подгонкой порога."""
    weak = document(
        "weak", DocumentTopic(code="C38652104", label="Computer security", score=0.0644)
    )
    assert relevance(weak) == {"weak": 1.0}


def test_a_source_without_scores_still_classifies() -> None:
    """Источник без весов относит по-прежнему: у arXiv и патентных классов веса нет вовсе."""
    preprint = document("arxiv", DocumentTopic(code="cs.CR", label=None, score=None))
    measured = AnalysisPipeline._subject_relevance((preprint,), frozenset(), frozenset({"cs cr"}))
    assert dict(measured) == {"arxiv": 1.0}


def test_a_zero_scored_label_does_not_shadow_a_scored_one() -> None:
    """Отбрасывается рубрика, а не документ: у работы обычно несколько кодов."""
    mixed = document(
        "mixed",
        DocumentTopic(code="C41008148", label="Computer science", score=0.0),
        DocumentTopic(code="C38652104", label="Computer security", score=0.42),
    )
    assert relevance(mixed) == {"mixed": 1.0}
