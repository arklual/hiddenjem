"""Value objects and their invariants."""

from __future__ import annotations

import math
from dataclasses import replace
from datetime import date

import pytest
from tests.conftest import make_candidate, make_document, make_series, make_topic

from horizon_analytics.domain.models import (
    AnalysisParams,
    Author,
    TimeSeries,
    Venue,
    as_source_class,
    clamp01,
    period_label,
    quantile,
    safe_div,
    sorted_unique,
)


class TestClamp01:
    def test_passes_through_the_unit_interval(self) -> None:
        assert clamp01(0.5) == 0.5

    @pytest.mark.parametrize("value", [-1.0, -0.0001, 0.0])
    def test_clamps_below(self, value: float) -> None:
        assert clamp01(value) == 0.0

    @pytest.mark.parametrize("value", [1.0, 1.0001, 1e9])
    def test_clamps_above(self, value: float) -> None:
        assert clamp01(value) == 1.0

    def test_nan_becomes_zero(self) -> None:
        assert clamp01(float("nan")) == 0.0


class TestSafeDiv:
    def test_divides(self) -> None:
        assert safe_div(1.0, 4.0) == 0.25

    def test_zero_denominator_returns_default(self) -> None:
        assert safe_div(1.0, 0.0, default=0.7) == 0.7

    def test_non_finite_denominator_returns_default(self) -> None:
        assert safe_div(1.0, float("inf")) == 0.0


class TestDocument:
    def test_year_comes_from_published_on(self) -> None:
        assert make_document("d1", year=2024).year == 2024

    def test_text_joins_title_and_abstract(self) -> None:
        document = make_document("d1", year=2024, title="Title", abstract="Body.")
        assert document.text == "Title. Body."

    def test_text_falls_back_to_title(self) -> None:
        assert make_document("d1", year=2024, title="Only").text == "Only"

    def test_organizations_are_sorted_and_deduplicated(self) -> None:
        document = make_document("d1", year=2024, organizations=("B Lab", "A Lab", "B Lab"))
        assert document.organizations == ("A Lab", "B Lab")

    def test_blank_organizations_are_dropped(self) -> None:
        document = make_document("d1", year=2024).__class__(
            document_id="d",
            source_id="s",
            source_class="PREPRINT",
            external_id="e",
            title="t",
            published_on=date(2024, 1, 1),
            url="u",
            fetched_at=make_document("d1", year=2024).fetched_at,
            authors=(Author(full_name="A", organization_name="   "),),
        )
        assert document.organizations == ()

    def test_venue_key_uses_the_venue_name(self) -> None:
        assert make_document("d1", year=2024, venue="Nature").venue_key == "Nature"

    def test_venue_key_falls_back_to_the_source(self) -> None:
        document = make_document("d1", year=2024, venue=None, source_id="arxiv")
        assert document.venue_key == "source:arxiv"

    def test_venue_with_blank_name_falls_back(self) -> None:
        base = make_document("d1", year=2024)
        # `Document` is a slots dataclass and has no `__dict__`; `replace` is the supported way
        # to derive a variant and it also re-runs `__post_init__` validation.
        document = replace(base, venue=Venue(name="  "))
        assert document.venue_key.startswith("source:")


class TestTermCandidate:
    def test_document_ids_are_sorted(self) -> None:
        candidate = make_candidate("term", documents=["z", "a", "m"])
        assert candidate.document_ids == ("a", "m", "z")

    def test_frequencies(self) -> None:
        candidate = make_candidate("term", documents=["a", "b"], occurrences=3)
        assert candidate.document_frequency == 2
        assert candidate.term_frequency == 6


class TestTopic:
    def test_document_ids_union_the_members(self) -> None:
        topic = make_topic("t", documents=["b", "a"])
        assert topic.document_ids == ("a", "b")
        assert topic.document_frequency == 2

    def test_occurrences_by_document_sums_members(self) -> None:
        topic = make_topic("t", documents=["a"]).__class__(
            key="t",
            label="t",
            members=(
                make_candidate("t", documents=["a"], occurrences=2),
                make_candidate("u", documents=["a", "b"], occurrences=1),
            ),
        )
        assert topic.occurrences_by_document() == {"a": 3, "b": 1}


class TestTimeSeries:
    def test_rejects_misaligned_components(self) -> None:
        with pytest.raises(ValueError, match="identical length"):
            TimeSeries(periods=("2020",), df=(1, 2), tf=(1,), corpus_df=(10,))

    def test_rate_is_df_over_corpus(self) -> None:
        series = make_series([5], corpus=[10])
        assert series.rate(0) == 0.5

    def test_rate_is_zero_for_an_empty_period(self) -> None:
        assert make_series([0], corpus=[0]).rate(0) == 0.0

    def test_periods_with_data(self) -> None:
        assert make_series([0, 1, 0, 3]).periods_with_data() == 2


class TestQuantile:
    def test_empty(self) -> None:
        assert quantile([], 0.5) == 0.0

    def test_single_value(self) -> None:
        assert quantile([7.0], 0.9) == 7.0

    def test_matches_linear_interpolation(self) -> None:
        values = [1.0, 2.0, 3.0, 4.0]
        assert quantile(values, 0.0) == 1.0
        assert quantile(values, 1.0) == 4.0
        assert math.isclose(quantile(values, 0.5), 2.5)


class TestAnalysisParams:
    def test_defaults_are_in_contract_range(self) -> None:
        params = AnalysisParams()
        assert params.top_n == 15
        assert params.years_window == 7

    @pytest.mark.parametrize("top_n", [4, 51])
    def test_rejects_out_of_range_top_n(self, top_n: int) -> None:
        with pytest.raises(ValueError, match="topN"):
            AnalysisParams(top_n=top_n)

    @pytest.mark.parametrize("window", [2, 16])
    def test_rejects_out_of_range_window(self, window: int) -> None:
        with pytest.raises(ValueError, match="yearsWindow"):
            AnalysisParams(years_window=window)

    def test_rejects_out_of_range_min_confidence(self) -> None:
        with pytest.raises(ValueError, match="minConfidence"):
            AnalysisParams(min_confidence=1.5)


class TestHelpers:
    def test_sorted_unique_drops_empty_values(self) -> None:
        assert sorted_unique(["b", "", "a", "b"]) == ("a", "b")

    def test_as_source_class_narrows(self) -> None:
        assert as_source_class("PATENT") == "PATENT"

    def test_as_source_class_rejects_unknown(self) -> None:
        with pytest.raises(ValueError, match="unknown source class"):
            as_source_class("BLOG")

    def test_period_label(self) -> None:
        assert period_label(2024) == "2024"
