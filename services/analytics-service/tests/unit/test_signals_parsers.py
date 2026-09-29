"""Разбор ответов источников — на записанных живых ответах, без единого запроса в сеть.

Фикстуры в ``tests/fixtures/signals`` сняты с настоящих API 2026-09-19. Смысл именно в записанных
ответах, а не в придуманных: придуманный JSON проверяет, что код читает то, что написал автор
кода, а записанный — что он читает то, что присылает источник.
"""

from __future__ import annotations

import json
from datetime import date
from pathlib import Path

import pytest

from horizon_analytics.signals.http import SourceUnavailableError
from horizon_analytics.signals.sources import (
    arxiv,
    github,
    hackernews,
    openalex,
    patents,
    wikipedia,
)

FIXTURES = Path(__file__).resolve().parents[1] / "fixtures" / "signals"
YEARS = tuple(range(2018, 2027))


def load(name: str) -> dict:
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


class TestOpenAlex:
    def test_year_series_fills_silent_years_with_zero(self):
        parsed = openalex.parse_year_groups(load("openalex_group_by_year.json"), YEARS)
        series = parsed["works_by_year"]
        assert list(series) == [str(year) for year in YEARS]
        assert series["2024"] == 130
        assert series["2025"] == 350
        # OpenAlex не возвращает группу за год, в котором работ нет; это ноль, а не пропуск.
        assert series["2019"] == 0
        assert series["2020"] == 0

    def test_window_total_and_all_time_differ(self):
        parsed = openalex.parse_year_groups(load("openalex_group_by_year.json"), YEARS)
        assert parsed["works_window_total"] == 1 + 2 + 15 + 130 + 350 + 1079
        # За окном есть хвост 2001–2013 — всего работ больше, чем в окне.
        assert parsed["works_all_time"] == 1592
        assert parsed["works_all_time"] > parsed["works_window_total"]

    def test_recent_share_counts_last_two_years_of_the_window(self):
        parsed = openalex.parse_year_groups(load("openalex_group_by_year.json"), YEARS)
        assert parsed["recent_two_year_works"] == 350 + 1079
        assert parsed["recent_two_year_share"] == pytest.approx(1429 / 1577)

    def test_empty_window_gives_no_share_rather_than_zero(self):
        parsed = openalex.parse_year_groups({"group_by": [], "meta": {"count": 0}}, YEARS)
        # Ноль означал бы «ничего свежего»; на самом деле доля не определена.
        assert parsed["recent_two_year_share"] is None

    def test_institutions_come_from_one_grouping_request(self):
        count, truncated = openalex.parse_group_count(load("openalex_group_by_institution.json"))
        # Страница группировки — двести строк, и ровно двести означает «двести и больше».
        assert count == 200
        assert truncated is True

    def test_short_grouping_is_an_exact_count(self):
        count, truncated = openalex.parse_group_count(load("openalex_group_by_country.json"))
        assert count == 37
        assert truncated is False

    def test_empty_grouping_counts_nothing(self):
        assert openalex.parse_group_count({"group_by": [], "meta": {"groups_count": 0}}) == (
            0,
            False,
        )

    def test_concentration_survives_the_page_ceiling(self):
        payload = load("openalex_group_by_institution.json")
        parsed = openalex.parse_concentration(payload, works=1592)
        # Счётчик организаций у этого термина упёрся в потолок страницы, а доля лидера — нет.
        assert openalex.parse_group_count(payload)[1] is True
        assert parsed["top_institution"] == "Peking University"
        assert 0.0 < parsed["top_institution_share"] < 1.0

    def test_concentration_without_works_is_undefined(self):
        assert openalex.parse_concentration({"group_by": []}, works=0) == {
            "top_institution": None,
            "top_institution_share": None,
        }

    def test_zero_count_groups_are_not_organisations(self):
        payload = {"group_by": [{"key": "a", "count": 3}, {"key": "unknown", "count": 0}]}
        count, _ = openalex.parse_group_count(payload)
        assert count == 1


class TestArxiv:
    def test_total_comes_from_opensearch_header(self):
        assert (
            arxiv.parse_total((FIXTURES / "arxiv_year_2024.atom").read_text(encoding="utf-8"))
            == 109
        )

    def test_broken_feed_is_a_failure_not_a_zero(self):
        with pytest.raises(SourceUnavailableError):
            arxiv.parse_total("<html>503 Service Unavailable</html>")

    def test_feed_without_counter_is_a_failure(self):
        feed = '<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom"><id>x</id></feed>'
        with pytest.raises(SourceUnavailableError):
            arxiv.parse_total(feed)


class TestGitHub:
    def test_page_gives_stars_and_creation_years(self):
        stars, repos = github.parse_page(load("github_search_page1.json"))
        assert len(repos) == 100
        assert stars == sum(repo["stars"] for repo in repos)
        assert stars > 0
        assert all(len(repo["created_year"]) == 4 for repo in repos)

    def test_count_only_response_yields_no_repos_but_keeps_total(self):
        payload = load("github_year_count.json")
        stars, repos = github.parse_page(payload)
        assert payload["total_count"] == 19989
        assert len(repos) == 3
        assert stars == sum(repo["stars"] for repo in repos)

    def test_missing_items_do_not_crash(self):
        assert github.parse_page({"total_count": 0}) == (0, [])


class TestHackerNews:
    def test_window_total_is_read_from_nbhits(self):
        assert hackernews.parse_hits(load("hn_window_hits.json")) == 512

    def test_years_are_bucketed_from_one_response(self):
        payload = load("hn_window_hits.json")
        # Одного запроса хватило: попаданий меньше потолка страницы, значит годы точны.
        assert payload["nbHits"] == len(payload["hits"])
        by_year = hackernews.bucket_hits(payload, YEARS)
        assert sum(by_year.values()) == payload["nbHits"]
        assert by_year["2024"] == 70
        assert by_year["2019"] == 0

    def test_overflowing_window_needs_the_year_by_year_path(self):
        # Потолок Algolia — тысяча попаданий; выше него годы по одному ответу не посчитать.
        assert hackernews.parse_hits(load("hn_window_overflow.json")) > 1000

    def test_hits_outside_the_window_are_dropped(self):
        payload = {"hits": [{"created_at_i": 1000000000}, {"created_at_i": 1704067200}]}
        by_year = hackernews.bucket_hits(payload, YEARS)
        assert by_year["2024"] == 1
        assert sum(by_year.values()) == 1

    def test_response_without_counter_is_a_failure(self):
        with pytest.raises(SourceUnavailableError):
            hackernews.parse_hits({"hits": []})


class TestWikipedia:
    def test_existing_article_gives_first_revision_and_size(self):
        parsed = wikipedia.parse_article(load("wikipedia_article_present.json"))
        assert parsed["exists"] is True
        assert parsed["title"] == "Federated learning"
        assert parsed["created_on"] == "2019-06-08"
        assert parsed["size_bytes"] > 10_000

    def test_missing_article_is_reported_as_absent_not_as_failure(self):
        parsed = wikipedia.parse_article(load("wikipedia_article_missing.json"))
        assert parsed["exists"] is False
        assert parsed["created_on"] is None
        assert parsed["size_bytes"] is None

    def test_pageviews_carry_the_number_of_months_behind_the_sum(self):
        parsed = wikipedia.parse_pageviews(load("wikipedia_pageviews.json"))
        assert parsed["pageviews_12m"] == 67228
        # Без числа месяцев короткий ряд молодой статьи не отличить от падения интереса.
        assert parsed["pageview_months"] == 12

    def test_age_turns_the_creation_date_into_a_number(self):
        # Сама дата в обучающую таблицу не попадёт — она строка; возраст попадёт.
        assert wikipedia.article_age_days("2019-06-08", date(2026, 9, 19)) == 2660
        assert wikipedia.article_age_days(None, date(2026, 9, 19)) is None
        assert wikipedia.article_age_days("не дата", date(2026, 9, 19)) is None

    @pytest.mark.parametrize(
        ("today", "window"),
        [
            (date(2026, 9, 19), ("20250901", "20260831")),
            (date(2026, 1, 5), ("20250101", "20251231")),
            (date(2026, 3, 1), ("20250301", "20260228")),
        ],
    )
    def test_window_is_twelve_complete_months(self, today, window):
        # Текущий месяц не входит: он неполон, и два термина, собранные в разные дни одного
        # месяца, иначе сравнивались бы по разным окнам.
        assert wikipedia.pageview_window(today) == window


class TestPatents:
    def test_source_reports_absence_with_a_reason(self):
        parsed = patents.collect(None, "speculative decoding")  # type: ignore[arg-type]
        assert parsed["available"] is False
        assert parsed["patents_by_year"] is None
        assert "robots.txt" in parsed["reason"]
