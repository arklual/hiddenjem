"""Карточка собирается из документов, где тема названа (разбор 110).

В ТОП-15 v11 у двух третей карточек пример не содержал ни слова из названия: у «On-Device LLM
Accelerators» — сделка Anyscale и Nscale, у «Consumer AI Glasses» — обзор 3D-печати. Жюри такую
карточку отклоняет по критерию «описание согласуется с названием», как бы хороша ни была тема.
"""

from __future__ import annotations

from tests.conftest import make_document

from horizon_analytics.domain.evidence import on_topic_documents
from horizon_analytics.domain.extraction.normalization import stem_token


def _doc(document_id: str, title: str, abstract: str | None = None):
    return make_document(document_id, year=2026, title=title, abstract=abstract)


def test_a_page_about_something_else_is_not_named() -> None:
    about = _doc("a", "Rockchip RK1820 coprocessors run large language models on device",
                 "The on-device LLM accelerators plug into existing SoCs.")
    other = _doc("b", "Anyscale signs definitive agreement to join Nscale", "What this means for customers.")

    named, unknown = on_topic_documents([about, other], ["On-Device LLM Accelerators"])

    assert [d.document_id for d in named] == ["a"]
    assert unknown == ()


def test_an_acronym_matches_its_expansion() -> None:
    page = _doc("a", "New accelerators for on-device large language models ship in 2026")

    named, _ = on_topic_documents([page], ["On-Device LLM Accelerators"])

    assert named == (page,)


def test_a_long_name_tolerates_one_missing_word() -> None:
    page = _doc("a", "HKMA pilots interbank tokenized deposits with six banks")

    named, _ = on_topic_documents([page], ["Interbank tokenized deposit networks"])

    assert named == (page,)


def test_one_shared_word_is_not_enough() -> None:
    page = _doc("a", "Consumer robots arrive in stores")

    named, _ = on_topic_documents([page], ["Consumer AI Glasses"])

    assert named == ()


def test_a_member_key_of_a_mined_topic_names_the_document() -> None:
    page = _doc("a", "Spiking neural networks on a microcontroller")
    key = " ".join(stem_token(word) for word in ("spiking", "neural", "networks"))

    named, _ = on_topic_documents([page], ["Neuromorphic MCU"], member_keys=[key])

    assert named == (page,)


def test_a_page_without_latin_script_cannot_be_checked() -> None:
    page = _doc("a", "地平线发布中国首款舱驾融合整车智能体芯片")

    named, unknown = on_topic_documents([page], ["Unified Automotive Agent Chip"])

    assert named == ()
    assert unknown == (page,)


def test_the_card_keeps_every_document_but_takes_the_case_from_one_that_names_the_topic() -> None:
    # Карточка показывает все документы темы (29.09), а пример организации — только из документа,
    # где тема названа: иначе у «On-Device LLM Accelerators» примером стояла сделка Anyscale.
    from horizon_analytics.domain.evidence import EvidenceSelection
    from horizon_analytics.domain.models import Evidence
    from horizon_analytics.domain.pipeline import _case_from_naming

    other = make_document("a", year=2026, title="Anyscale joins Nscale", organizations=("Anyscale Inc",),
                          organization_types=("COMPANY",), citation_count=50)
    about = make_document("b", year=2026, title="On-device LLM accelerators ship", organizations=("Rockchip Ltd",),
                          organization_types=("COMPANY",), citation_count=1)
    documents = (other, about)
    selection = EvidenceSelection(
        items=tuple(Evidence(source_id=d.source_id, source_class=d.source_class, title=d.title,
                             published_on=d.published_on, url=d.url, relevance=1.0, document_id=d.document_id,
                             citation_count=d.citation_count) for d in documents),
        documents=documents,
    )

    case = _case_from_naming(selection, frozenset({"b"}))

    assert case is not None and case.evidence_index == 1
    assert _case_from_naming(selection, frozenset()) is None
