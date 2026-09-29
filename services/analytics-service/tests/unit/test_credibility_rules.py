"""Правила уровня доверенности источника (ТЗ: «уровень доверенности либо критерии его определения»).

Проверяются не столько значения, сколько **порядок правил**: хост-признак сильнее класса источника
в обе стороны, и именно это отличает работающее правило от списка ярлыков по типу документа.

Новость на сайте регулятора — первоисточник, а не новость. Статья, распространённая службой
пресс-релизов, остаётся публикацией, но независимым свидетельством не является: десять сайтов,
перепечатавших один релиз, — это один источник, а правило достоверности обязано считать их за один.
"""

from __future__ import annotations

from datetime import date, datetime

import pytest

from horizon_analytics.domain.credibility import (
    CREDIBILITY_LABELS_RU,
    assess_credibility,
    is_substantive,
)
from horizon_analytics.domain.models import Author, Document, SourceClass


def document(
    *,
    source_class: SourceClass = "NEWS",
    url: str = "https://example.com/story",
    title: str = "Заголовок",
    organization_type: str | None = None,
) -> Document:
    authors = (
        (Author(full_name="Автор", organization_name="Org", organization_type=organization_type),)
        if organization_type is not None
        else ()
    )
    return Document(
        document_id="d",
        source_id="s",
        source_class=source_class,
        external_id="e",
        title=title,
        published_on=date(2026, 1, 1),
        url=url,
        fetched_at=datetime(2026, 1, 1),
        authors=authors,
    )


class TestSourceClassSetsTheFloor:
    @pytest.mark.parametrize(
        ("source_class", "expected"),
        [
            ("JOURNAL_ARTICLE", "HIGH"),
            ("PATENT", "HIGH"),
            ("STANDARD", "HIGH"),
            ("PREPRINT", "MEDIUM"),
            ("ANALYST_REPORT", "MEDIUM"),
            ("NEWS", "LOW"),
        ],
    )
    def test_class_decides_when_the_host_says_nothing(self, source_class, expected) -> None:
        assert assess_credibility(document(source_class=source_class)).level == expected


class TestTheHostOutweighsTheClass:
    def test_a_regulators_site_is_a_primary_source_not_a_news_item(self) -> None:
        verdict = assess_credibility(document(url="https://www.nist.gov/news/item"))

        assert verdict.level == "HIGH"
        assert "официальн" in verdict.basis

    def test_a_press_release_wire_is_not_an_independent_account(self) -> None:
        verdict = assess_credibility(
            document(source_class="JOURNAL_ARTICLE", url="https://www.prnewswire.com/x")
        )

        assert verdict.level == "LOW"
        assert not verdict.independent

    def test_a_blog_platform_is_a_first_indicator_only(self) -> None:
        verdict = assess_credibility(document(url="https://medium.com/@someone/post"))

        assert verdict.level == "LOW"
        assert not verdict.independent

    def test_a_lookalike_host_keeps_its_credibility(self) -> None:
        # Сравнение по границе точки, а не по подстроке: «medium.com» встречается внутри
        # «notmedium.company», и понижать доверенность чужому источнику по совпадению букв нельзя.
        verdict = assess_credibility(document(url="https://notmedium.company/post"))

        assert verdict.independent


class TestRepositoryOwnership:
    def test_an_organisation_repository_is_a_source(self) -> None:
        verdict = assess_credibility(
            document(source_class="CODE_REPOSITORY", organization_type="COMPANY")
        )

        assert verdict.level == "MEDIUM"
        assert verdict.independent

    def test_a_personal_repository_is_not(self) -> None:
        verdict = assess_credibility(document(source_class="CODE_REPOSITORY"))

        assert verdict.level == "LOW"
        assert not verdict.independent


class TestPromoLanguage:
    def test_a_release_headline_lowers_the_level(self) -> None:
        verdict = assess_credibility(
            document(title="Acme announces revolutionary quantum platform")
        )

        assert verdict.level == "LOW"
        assert not verdict.independent


class TestTrustedMediaFromTheTermsOfReference:
    """Доверенные медиа по ТЗ (разбор 101).

    ТЗ относит к доверенным «профессиональные отраслевые медиа» и «официальные сайты
    компаний-разработчиков» — отдельно от личных блогов и агрегаторов.
    """

    def test_a_listed_trade_outlet_is_a_source_and_an_arbitrary_site_is_not(self) -> None:
        assert assess_credibility(document(url="https://robotnews.example/x")).level == "LOW"

        verdict = assess_credibility(document(url="https://www.edgeir.com/edge-ai-20260527"))

        assert verdict.level == "MEDIUM"
        assert verdict.independent
        assert "отраслевое медиа" in verdict.basis

    def test_a_company_blog_on_habr_is_the_companys_channel(self) -> None:
        verdict = assess_credibility(
            document(url="https://habr.com/ru/companies/yandex/articles/801119/")
        )

        assert verdict.level == "MEDIUM"
        assert "компании-разработчика" in verdict.basis

    def test_a_personal_post_on_habr_stays_a_personal_blog(self) -> None:
        verdict = assess_credibility(document(url="https://habr.com/ru/articles/1075706/"))

        assert verdict.level == "LOW"
        assert not verdict.independent

    @pytest.mark.parametrize(
        "url",
        [
            "https://www.cbr.ru/press/event/?id=1",
            "https://www.bis.org/about/innovation-hub/project/agora",
            "https://www.fca.org.uk/firms/innovation/regulatory-sandbox/accepted-firms",
        ],
    )
    def test_a_regulator_on_its_bare_domain_is_official(self, url: str) -> None:
        # Хост приходит без «www.», и суффикс «.cbr.ru» когда-то не узнавал сам «cbr.ru».
        assert assess_credibility(document(url=url)).level == "HIGH"

    def test_a_product_launch_card_is_self_presentation(self) -> None:
        assert assess_credibility(document(url="https://www.producthunt.com/posts/x")).level == "LOW"

    def test_a_release_headline_outweighs_the_outlet(self) -> None:
        verdict = assess_credibility(
            document(url="https://siliconangle.com/2026/x", title="Acme announces new platform")
        )

        assert verdict.level == "LOW"


def test_trusted_media_are_grounds_and_other_news_is_not() -> None:
    # Тема без научно-технической основы выбывает. Основа — публикация, код, стандарт и то, что ТЗ
    # само называет доверенным среди медиа.
    assert is_substantive(document(url="https://thefintechtimes.com/x"))
    assert is_substantive(document(url="https://habr.com/ru/companies/sber/articles/1/"))
    assert not is_substantive(document(url="https://habr.com/ru/articles/1/"))
    assert not is_substantive(document(url="https://news.ycombinator.com/item?id=1"))
    assert is_substantive(document(source_class="PREPRINT"))


def test_every_level_has_a_russian_label() -> None:
    # Вся выдача на русском языке — требование ТЗ. Уровень без подписи доехал бы до интерфейса
    # английским ключом перечисления.
    assert set(CREDIBILITY_LABELS_RU) == {"HIGH", "MEDIUM", "LOW"}
    assert all(label and label.strip() for label in CREDIBILITY_LABELS_RU.values())


def test_the_basis_is_always_a_sentence_not_a_label() -> None:
    # Ярлык без основания читатель проверить не может, и ТЗ допускает показывать критерии.
    for source_class in ("JOURNAL_ARTICLE", "PREPRINT", "PATENT", "NEWS", "CODE_REPOSITORY"):
        verdict = assess_credibility(document(source_class=source_class))
        assert len(verdict.basis.split()) >= 3, source_class
