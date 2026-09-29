"""Одна организация, записанная по-разному, остаётся одной.

BRULE-1 требует «не меньше двух **различных** организаций», и смысл требования — независимость
свидетельств: тема, подтверждённая одной лабораторией, не считается подтверждённой. Сырая аффилиация
этого не даёт. «Acme Corp» и «Acme Corp.» — одна группа и две строки, и правило, которое их
различает, проходит ровно там, где должно отказать.

То же множество считает диффузия (`orgBreadth`): широта охвата росла бы оттого, что источники
записывают одно имя двумя способами.

Приведение одностороннее — оно **сливает** написания и никогда не разделяет, и это существенно.
Лишнее слияние делает BRULE-1 строже, чем нужно, и стоит темы. Лишнее разделение делает правило
мягче, чем оно обещает, — а против этого правило и написано.

Замерить на эталонном корпусе нечего: он синтетический, 199 имён организаций без единого совпадения
написаний. Поэтому проверка построена на случаях, а не на корпусе, и здесь это записано, чтобы
следующий читатель не искал в замере того, чего в нём нет.
"""

from __future__ import annotations

from datetime import date

import pytest

from horizon_analytics.domain.models import Author, Document, canonical_organization


def _document(*organizations: str) -> Document:
    return Document(
        document_id="d1",
        source_id="fixture",
        source_class="PREPRINT",
        external_id="ext-d1",
        title="Заголовок",
        published_on=date(2024, 6, 15),
        url="https://example.org/d1",
        fetched_at=__import__("datetime").datetime(2026, 1, 1, tzinfo=__import__("datetime").UTC),
        authors=tuple(
            Author(full_name=f"Автор {index}", organization_name=name)
            for index, name in enumerate(organizations, start=1)
        ),
    )


class TestSpellingIsNotIndependence:
    @pytest.mark.parametrize(
        ("left", "right"),
        [
            ("Acme Corp", "Acme Corp."),
            ("Google DeepMind", "google  deepmind"),
            ("Acme, Inc.", "Acme Inc"),
            ("ООО «Ромашка»", "Ромашка ООО"),
            ("AT&T Labs", "AT and T Labs"),
            ("МФТИ", "мфти"),
        ],
    )
    def test_two_spellings_are_one_organization(self, left: str, right: str) -> None:
        assert canonical_organization(left) == canonical_organization(right)

    def test_a_document_with_two_spellings_counts_one_organization(self) -> None:
        # Тот самый случай: BRULE-1 увидел бы две организации там, где она одна.
        document = _document("Acme Corp", "Acme Corp.")

        assert len(document.organizations) == 2
        assert len(document.canonical_organizations) == 1


class TestDifferentOrganizationsStayDifferent:
    @pytest.mark.parametrize(
        ("left", "right"),
        [
            ("Institute of Physics", "Institute of Chemistry"),
            ("MIT", "Max Planck Institute"),
            ("Acme Labs", "Acme Research"),
            ("University of Tokyo", "University of Kyoto"),
        ],
    )
    def test_domain_words_are_never_stripped(self, left: str, right: str) -> None:
        # «university», «institute», «lab», «research» различают настоящие организации. Их удаление
        # слило бы «Институт физики» с «Институтом химии» — ошибка в ту сторону, где правило
        # становится неверным, а не строгим.
        assert canonical_organization(left) != canonical_organization(right)

    def test_nothing_is_guessed_without_a_directory(self) -> None:
        # «MIT» и «Massachusetts Institute of Technology» — одна организация, и продукт этого не
        # знает. Сведение их требует справочника, которого нет; без него это угадывание.
        assert canonical_organization("MIT") != canonical_organization(
            "Massachusetts Institute of Technology"
        )


class TestTheCanonicalFormStaysUsable:
    def test_an_empty_or_punctuation_only_name_disappears(self) -> None:
        # Пустое имя не должно превращаться в организацию с пустым названием: оно считалось бы
        # наравне с настоящей.
        assert canonical_organization("   ") == ""
        assert canonical_organization("«»") == ""
        assert _document("Acme Corp", "«»").canonical_organizations == ("acme",)

    def test_a_legal_form_alone_is_not_an_organization(self) -> None:
        assert canonical_organization("ООО") == ""

    def test_the_result_is_sorted_and_distinct(self) -> None:
        document = _document("Beta Ltd", "Acme Corp", "acme")

        assert document.canonical_organizations == ("acme", "beta")

    def test_the_displayed_name_is_left_alone(self) -> None:
        # Приведение — для счёта. Показывать аналитику «acme» вместо «Acme Corp.» значило бы
        # чинить одно и ломать другое.
        assert _document("Acme Corp.").organizations == ("Acme Corp.",)
