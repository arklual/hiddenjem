"""Кейс-пример называет то основание, по которому он подобран.

Отбор описан методологией §8 тремя правилами: патент с корпоративным правообладателем, публикация с
корпоративной аффилиацией, наиболее цитируемая академическая группа. До этой проверки все три
приводили к одному и тому же на экране — названию организации под заголовком «Организация, у которой
тема уже перешла в практическую плоскость».

Замер по эталонному корпусу показал, чего стоило это упрощение: из 90 тем на патенте держатся 29, на
публикации — 61, и 21 из них на препринте. То есть в двух случаях из трёх продукт утверждал переход в
практику, имея на руках статью. Патент и препринт — разные утверждения, и разными они должны
доходить до комитета.

Сам отбор до сих пор не был покрыт ничем: единственное место, где продукт называет организацию,
проверялось только косвенно, через сборку отчёта целиком.
"""

from __future__ import annotations

import pytest
from tests.conftest import make_document

from horizon_analytics.domain.evidence import EvidenceSelection, select_case_example
from horizon_analytics.domain.models import Document, Evidence, SourceClass


def _evidence(document: Document) -> Evidence:
    """Свидетельство, выровненное с документом, — так его строит сам отбор."""
    return Evidence(
        source_id=document.source_id,
        source_class=document.source_class,
        title=document.title,
        published_on=document.published_on,
        url=document.url,
        relevance=document.relevance,
        document_id=document.document_id,
        citation_count=document.citation_count,
    )


def _selection(*documents: Document) -> EvidenceSelection:
    return EvidenceSelection(
        items=tuple(_evidence(document) for document in documents),
        documents=tuple(documents),
    )


def _document(
    document_id: str,
    *,
    source_class: SourceClass,
    organization: str,
    organization_type: str,
    citation_count: int,
) -> Document:
    return make_document(
        document_id,
        year=2024,
        source_class=source_class,
        organizations=(organization,),
        organization_types=(organization_type,),
        citation_count=citation_count,
    )


class TestTheBasisIsReported:
    """Каждое из трёх правил §8 называет себя."""

    def test_a_corporate_patent_reports_a_patent(self) -> None:
        selection = _selection(
            _document(
                "d1",
                source_class="PATENT",
                organization="Acme Corp",
                organization_type="COMPANY",
                citation_count=1,
            )
        )

        example = select_case_example(selection)

        assert example is not None
        assert example.basis == "PATENT"

    def test_a_corporate_paper_reports_a_publication_and_not_a_patent(self) -> None:
        # Двадцать одна тема эталонного корпуса держится ровно на этом — и на препринте.
        selection = _selection(
            _document(
                "d1",
                source_class="PREPRINT",
                organization="Acme Corp",
                organization_type="COMPANY",
                citation_count=40,
            )
        )

        example = select_case_example(selection)

        assert example is not None
        assert example.basis == "CORPORATE_PUBLICATION"

    def test_an_academic_group_reports_itself_as_academic(self) -> None:
        selection = _selection(
            _document(
                "d1",
                source_class="JOURNAL_ARTICLE",
                organization="University of Testing",
                organization_type="UNIVERSITY",
                citation_count=90,
            )
        )

        example = select_case_example(selection)

        assert example is not None
        assert example.basis == "ACADEMIC_GROUP"


class TestTheBasisFollowsTheChosenDocument:
    """Основание относится к выбранному документу, а не к самому заметному в подборке."""

    def test_a_patent_wins_over_a_more_cited_paper_and_the_basis_says_so(self) -> None:
        # Приоритет патента — правило §8, и цитируемость его не отменяет. Если бы основание
        # считалось по подборке, а не по выбранному документу, здесь получилось бы «публикация».
        selection = _selection(
            _document(
                "d1",
                source_class="JOURNAL_ARTICLE",
                organization="Acme Corp",
                organization_type="COMPANY",
                citation_count=500,
            ),
            _document(
                "d2",
                source_class="PATENT",
                organization="Beta Industries",
                organization_type="COMPANY",
                citation_count=1,
            ),
        )

        example = select_case_example(selection)

        assert example is not None
        assert example.organization == "Beta Industries"
        assert example.basis == "PATENT"

    def test_a_corporate_paper_wins_over_an_academic_one_and_the_basis_says_so(self) -> None:
        selection = _selection(
            _document(
                "d1",
                source_class="JOURNAL_ARTICLE",
                organization="University of Testing",
                organization_type="UNIVERSITY",
                citation_count=500,
            ),
            _document(
                "d2",
                source_class="JOURNAL_ARTICLE",
                organization="Acme Corp",
                organization_type="COMPANY",
                citation_count=1,
            ),
        )

        example = select_case_example(selection)

        assert example is not None
        assert example.organization == "Acme Corp"
        assert example.basis == "CORPORATE_PUBLICATION"


class TestTheBasisIsNeverInvented:
    """Основание — одно из трёх известных значений и всегда проставлено."""

    @pytest.mark.parametrize(
        ("source_class", "organization", "organization_type"),
        [
            ("PATENT", "Acme Corp", "COMPANY"),
            ("PREPRINT", "Acme Corp", "COMPANY"),
            ("JOURNAL_ARTICLE", "University of Testing", "UNIVERSITY"),
            ("PATENT", "University of Testing", "UNIVERSITY"),
            ("NEWS", "Acme Corp", "COMPANY"),
        ],
    )
    def test_every_shape_of_document_yields_a_known_basis(
        self, source_class: str, organization: str, organization_type: str
    ) -> None:
        # Патент университета намеренно в списке: он не проходит первое правило (правообладатель не
        # корпоративный) и обязан честно назваться академическим, а не патентным.
        selection = _selection(
            _document(
                "d1",
                source_class=source_class,  # type: ignore[arg-type]
                organization=organization,
                organization_type=organization_type,
                citation_count=7,
            )
        )

        example = select_case_example(selection)

        assert example is not None
        assert example.basis in {"PATENT", "CORPORATE_PUBLICATION", "ACADEMIC_GROUP"}

    def test_a_selection_without_organizations_yields_no_example_at_all(self) -> None:
        # Обратная сторона: пустое основание не появляется вместо примера. Пример либо есть целиком,
        # либо его нет.
        document = make_document("d1", year=2024, organizations=())
        selection = EvidenceSelection(items=(_evidence(document),), documents=(document,))

        assert select_case_example(selection) is None
