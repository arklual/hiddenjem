"""Ссылка указывает на своё предложение, а не на абзац целиком.

Формулировка «проблемы» и «пользы» собирается из нескольких предложений, и предложения эти берутся
из разных статей. Замер по эталонному корпусу: из 180 формулировок **166 склеены из двух и более
документов**, и в **51** из них второе предложение говорит «the proposed method», «the design» или
«we show» — о системе другого автора.

Склеенный абзац читается как один довод одной работы. Пример из корпуса:

    «The proposed method is compared against the state of the art on an internal production trace.
     We show that HyperBridge enables higher tokens per second…»

Первое предложение — из статьи DeltaNet, второе — из статьи про HyperBridge. Каждое по отдельности
верно и проверяемо; вместе они утверждают то, чего не писал никто, и «the proposed method» молча
становится HyperBridge.

Само извлечение при этом честное: 351 предложение из 351 найдено дословно в том документе, на который
ссылается атрибуция. Дефект был не в извлечении, а в том, что связь предложения с источником
существовала только внутри отбора и наружу не выходила.
"""

from __future__ import annotations

from tests.conftest import make_document, make_topic

from horizon_analytics.domain.evidence import EvidenceSelection
from horizon_analytics.domain.models import Evidence, Motivation
from horizon_analytics.domain.narration.extractive import ExtractiveNarrator, NarrationRequest

PROBLEM_ONE = (
    "Existing serving stacks are limited by recall of distant tokens in linear-time models, "
    "which becomes a bottleneck in streaming inference."
)
PROBLEM_TWO = (
    "Current inference engines suffer from recall of distant tokens in linear-time models, "
    "and closing this gap is still an open problem."
)


def _selection() -> EvidenceSelection:
    """Два документа, каждый со своей формулировкой проблемы по одной и той же теме."""
    documents = (
        make_document("d1", year=2023, title="Первая статья", abstract=PROBLEM_ONE),
        make_document("d2", year=2024, title="Вторая статья", abstract=PROBLEM_TWO),
    )
    items = tuple(
        Evidence(
            source_id=document.source_id,
            source_class=document.source_class,
            title=document.title,
            published_on=document.published_on,
            url=document.url,
            relevance=1.0,
            document_id=document.document_id,
            snippet=document.abstract_text,
        )
        for document in documents
    )
    return EvidenceSelection(items=items, documents=documents)


def _narrate() -> Motivation:
    selection = _selection()
    request = NarrationRequest(
        topic=make_topic("linear-time models", documents=["d1", "d2"]),
        evidence=selection,
    )
    return ExtractiveNarrator().narrate(request)


class TestEverySentenceCarriesItsOwnSource:
    def test_each_attribution_names_the_sentence_it_stands_for(self) -> None:
        motivation = _narrate()

        problems = [a for a in motivation.attributions if a.statement == "problem"]

        assert problems, "формулировка проблемы осталась без атрибуции"
        assert all(a.sentence for a in problems), "атрибуция без предложения — ссылка на весь абзац"

    def test_the_sentence_is_verbatim_in_the_document_it_points_at(self) -> None:
        # Иначе привязка была бы украшением: ссылка есть, но ведёт не туда, откуда взят текст.
        selection = _selection()
        motivation = _narrate()

        for attribution in motivation.attributions:
            assert attribution.sentence is not None
            document = selection.documents[attribution.evidence_index]
            assert attribution.sentence in (document.abstract_text or ""), (
                f"предложение «{attribution.sentence[:60]}» не найдено в документе "
                f"{document.document_id}, на который указывает ссылка"
            )

    def test_the_sentences_reassemble_into_the_statement_shown(self) -> None:
        # Связь в обе стороны: показанный абзац не содержит ничего, кроме привязанных предложений.
        # Без этого можно было бы привязать одно предложение из трёх и выглядеть проверенным.
        motivation = _narrate()

        sentences = [
            a.sentence for a in motivation.attributions if a.statement == "problem" and a.sentence
        ]

        assert sentences, "нечего собирать — проверка выродилась бы в пустую"
        assert " ".join(sentences) == motivation.problem

    def test_two_documents_yield_two_distinct_sources(self) -> None:
        # Тот самый случай, ради которого правка сделана: обе половины абзаца из разных статей.
        motivation = _narrate()

        problems = [a for a in motivation.attributions if a.statement == "problem"]

        # Без этого утверждения фикстура могла бы выродиться в один документ, и проверка стала бы
        # вечнозелёной, продолжая называться проверкой склейки из разных статей.
        assert len(problems) == 2, "фикстура перестала давать склейку из двух документов"
        assert len({a.evidence_index for a in problems}) == 2
        assert len({a.sentence for a in problems}) == 2, "разным ссылкам досталось одно предложение"
