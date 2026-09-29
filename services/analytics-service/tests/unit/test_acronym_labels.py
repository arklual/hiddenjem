"""Тема-аббревиатура называется расшифровкой из самого корпуса.

Прогон по ИИ 2026-09-18: пять из десяти тем отчёта назывались аббревиатурами — «RAG», «SD»,
«SFT», «PINN», «AUC». Аналитик не знает, о чём тема; судья принимает любые две буквы за
технологию; внешняя проверка зрелости по аббревиатуре не судит; русский слой угадывает — «SD»
стало «Stable Diffusion», хотя документы темы про speculative decoding.
"""

from __future__ import annotations

from horizon_analytics.domain.extraction.normalization import AcronymDictionary, AcronymEntry
from horizon_analytics.domain.models import Posting, TermCandidate, Topic
from horizon_analytics.domain.pipeline import AnalysisPipeline


def _topic(label: str, *forms: str) -> Topic:
    members = tuple(
        TermCandidate(
            key=form.lower(),
            surface=form,
            surface_forms=(form,),
            postings=(Posting(document_id="d1", occurrences=1),),
            termhood=1.0,
            token_count=len(form.split()),
        )
        for form in (label, *forms)
    )
    return Topic(key=label.lower(), label=label, members=members)


EMPTY = AcronymDictionary(entries={})


def test_the_corpus_dictionary_names_the_topic() -> None:
    dictionary = AcronymDictionary(
        entries={
            "rag": AcronymEntry(
                acronym="RAG",
                long_form="retrieval-augmented generation",
                long_form_key="retriev augment gener",
                support=5,
            )
        }
    )

    [topic] = AnalysisPipeline._expand_acronym_labels([_topic("RAG")], dictionary)

    assert topic.label == "retrieval-augmented generation (RAG)"
    assert topic.key == "rag", "ключ темы не должен меняться — только имя"


def test_a_variant_whose_initials_spell_the_acronym_names_the_topic() -> None:
    [topic] = AnalysisPipeline._expand_acronym_labels(
        [_topic("GPU", "graphics processing units", "gpus")], EMPTY
    )

    assert topic.label == "graphics processing units (GPU)"


def test_function_words_do_not_break_the_initials() -> None:
    [topic] = AnalysisPipeline._expand_acronym_labels(
        [_topic("AUC", "area under the curve")], EMPTY
    )

    assert topic.label == "area under the curve (AUC)"


def test_no_expansion_in_the_corpus_leaves_the_label() -> None:
    """Придумывать расшифровку — утверждать то, чего источники не говорили."""
    [topic] = AnalysisPipeline._expand_acronym_labels([_topic("SD", "stable models")], EMPTY)

    assert topic.label == "SD"


def test_an_ordinary_label_is_untouched() -> None:
    [topic] = AnalysisPipeline._expand_acronym_labels([_topic("hybrid retrieval")], EMPTY)

    assert topic.label == "hybrid retrieval"
