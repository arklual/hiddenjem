"""BRULE-1 и BRULE-2: когда упоминание считается достоверным и когда — первым.

Первое правило методологии, и до сих пор ни один тест его не держал. Подмена по **всему** набору,
включая эталонный корпус, прошла незамеченной: требование «не менее двух организаций» можно было
удалить, и всё оставалось зелёным.

Цена ошибки здесь тише прочих и дороже многих. Правило отвечает на вопрос «когда тема впервые
достоверно упомянута», а от года первого упоминания напрямую зависит индикатор новизны — то есть
место темы в отчёте. Ослабленное правило не ломает ничего видимого: отчёт собирается, выглядит
целым и просто оказывается о другом.

Смысл правила словами документа: «единичное упоминание не считается первым упоминанием». Одна
статья одной организации — это заявка, а не сигнал; две независимые организации — уже наблюдение,
которое можно проверить.
"""

from __future__ import annotations

from dataclasses import replace

from tests.conftest import make_document

from horizon_analytics.domain.models import Author
from horizon_analytics.domain.pipeline import AnalysisPipeline
from horizon_analytics.domain.scoring.profile import MethodologyProfile

PARAMETERS = MethodologyProfile.default().parameters


def first_year(*documents: object) -> int | None:
    return AnalysisPipeline._first_credible_year(list(documents), PARAMETERS)  # type: ignore[arg-type]


class TestCredibility:
    def test_two_documents_from_one_organization_are_not_credible(self) -> None:
        # Две публикации одной лаборатории — это по-прежнему одна точка зрения. Правило требует
        # независимости источников, а не количества файлов.
        assert (
            first_year(
                make_document("a", year=2021, organizations=("Acme Research Lab",)),
                make_document("b", year=2022, organizations=("Acme Research Lab",)),
            )
            is None
        )

    def test_two_organizations_make_the_mention_credible(self) -> None:
        assert (
            first_year(
                make_document("a", year=2021, organizations=("Acme Research Lab",)),
                make_document("b", year=2022, organizations=("Beta Institute",)),
            )
            == 2022
        )

    def test_a_single_document_is_never_credible_however_many_organizations_it_names(self) -> None:
        # Обратная граница правила: одна статья с двумя аффилиациями удовлетворяет требованию по
        # организациям, но не по документам. Без этой проверки правило можно было бы «выполнить»
        # одной публикацией с длинным списком авторов.
        assert (
            first_year(
                make_document("a", year=2021, organizations=("Acme Research Lab", "Beta Institute"))
            )
            is None
        )

    def test_a_lone_early_mention_does_not_drag_the_first_year_backwards(self) -> None:
        # Ради чего правило и написано: одна ранняя статья 2015 года не делает тему «известной с
        # 2015-го». Достоверным становится год, в котором набралось достаточно независимых
        # свидетельств, — здесь 2023-й.
        assert (
            first_year(
                make_document("early", year=2015, organizations=("Acme Research Lab",)),
                make_document("b", year=2023, organizations=("Beta Institute",)),
                make_document("c", year=2024, organizations=("Gamma Labs",)),
            )
            == 2023
        )

    def test_the_answer_does_not_depend_on_the_order_documents_arrive_in(self) -> None:
        # Детерминизм (ADR-0015): порядок документов в памяти не является данными.
        shuffled = first_year(
            make_document("c", year=2024, organizations=("Gamma Labs",)),
            make_document("early", year=2015, organizations=("Acme Research Lab",)),
            make_document("b", year=2023, organizations=("Beta Institute",)),
        )

        assert shuffled == 2023

    def test_nothing_at_all_is_not_credible(self) -> None:
        assert first_year() is None


def team_paper(document_id: str, year: int, *names: str, source_class: str = "PREPRINT") -> object:
    """Работа без аффилиаций — такой её отдают Semantic Scholar и почти всегда arXiv."""
    base = make_document(
        document_id, year=year, organizations=(), source_class=source_class  # type: ignore[arg-type]
    )
    return replace(base, authors=tuple(Author(full_name=name) for name in names))


class TestAuthorCollectives:
    """BRULE-1 записано как «≥ 2 различных организации/авторских коллектива» (разбор 101).

    Считалась только первая половина. Semantic Scholar и Hacker News аффилиаций не присылают вовсе,
    arXiv — у 3% работ, и на живом корпусе Edge правило отсеивало 94 темы из 337 — в том числе те,
    о которых писали независимые группы, просто не назвавшие организацию.
    """

    def test_two_disjoint_teams_without_affiliations_are_credible(self) -> None:
        assert (
            first_year(
                team_paper("a", 2024, "Wei Zhang", "Li Wang"),
                team_paper("b", 2025, "Maria Rossi", "Jan Novak"),
            )
            == 2025
        )

    def test_papers_sharing_an_author_are_one_collective(self) -> None:
        # Две работы одной группы — одно свидетельство, даже если состав соавторов сменился.
        assert (
            first_year(
                team_paper("a", 2024, "Wei Zhang", "Li Wang"),
                team_paper("b", 2025, "W. Zhang", "Maria Rossi"),
            )
            is None
        )

    def test_a_news_poster_is_not_a_collective(self) -> None:
        # Автор поста в обсуждении — не автор технологии: две заметки разных людей на агрегаторе
        # по-прежнему не делают тему достоверной.
        assert (
            first_year(
                team_paper("a", 2024, "alice", source_class="NEWS"),
                team_paper("b", 2025, "bob", source_class="NEWS"),
            )
            is None
        )

    def test_a_collective_already_counted_by_its_organization_is_not_counted_again(self) -> None:
        affiliated = replace(
            make_document("a", year=2024, organizations=("Acme Research Lab",)),
            authors=(Author(full_name="Wei Zhang", organization_name="Acme Research Lab"),),
        )

        assert first_year(affiliated, team_paper("b", 2025, "Wei Zhang", "Li Wang")) is None
        assert first_year(affiliated, team_paper("b", 2025, "Maria Rossi")) == 2025
