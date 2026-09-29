"""Scoring rules of the historical backtest.

The pipeline run inside a backtest is covered by the golden tests; what is only tested here is the
*scoring*, which is where a backtest quietly becomes a vanity metric. Each case below pins one
decision that keeps the number meaningful.
"""

from __future__ import annotations

import pytest

from horizon_analytics.tools.backtest import DEFAULT_HIT_LIFT, ArmResult, TopicOutcome, _outcome


class _Index:
    """A mention index stub: the scoring only ever asks it for a term's publication years."""

    def __init__(self, years: dict[str, tuple[int, ...]]) -> None:
        self._years = years

    def years_for(self, key: str) -> tuple[int, ...]:
        return self._years.get(key, ())


def outcome_for(
    years: tuple[int, ...], *, cutoff: int = 2021, corpus_lift: float = 2.0
) -> TopicOutcome:
    return _outcome("t", 1, 50.0, _Index({"t": years}), cutoff, corpus_lift)


def test_lift_is_growth_relative_to_the_corpus_not_absolute_growth() -> None:
    # Two documents before, and the corpus as a whole tripled: keeping pace means six after.
    # Nine after is therefore ×1.5, not ×4.5 — the corpus's own growth is not the topic's credit.
    result = outcome_for((2020, 2021) + (2023,) * 9, corpus_lift=3.0)

    assert result.documents_before == 2
    assert result.documents_after == 9
    assert result.lift == pytest.approx(1.5)


def test_a_topic_that_merely_keeps_pace_with_its_corpus_is_not_a_hit() -> None:
    # The bar has to sit above ×1.0 or it measures the calendar: on a growing corpus, "grew" is
    # what every topic does.
    result = outcome_for((2020, 2021, 2023, 2024), corpus_lift=1.0)

    assert result.lift == pytest.approx(1.0)
    assert not result.hit


def test_a_topic_that_outgrows_its_corpus_by_the_threshold_is_a_hit() -> None:
    before = (2020, 2021)
    after = (2023,) * int(2 * DEFAULT_HIT_LIFT)
    result = outcome_for(before + after, corpus_lift=1.0)

    assert result.hit


def test_a_topic_with_no_prior_literature_scores_zero_rather_than_infinity() -> None:
    # Dividing by an empty baseline would make any brand-new term the best result in the run.
    result = outcome_for((2023, 2024))

    assert result.documents_before == 0
    assert result.lift == 0.0
    assert not result.hit


def test_doubling_years_is_measured_from_the_cutoff() -> None:
    # Three before; the third post-cutoff document lands in 2024, three years after the freeze.
    result = outcome_for((2019, 2020, 2021, 2022, 2023, 2024))

    assert result.doubling_years == 3


def test_doubling_years_is_unknown_when_the_topic_never_doubled() -> None:
    # None means "it did not take off", which must stay distinguishable from "it took off at once".
    result = outcome_for((2019, 2020, 2021, 2023))

    assert result.doubling_years is None


def _arm(*lifts: float) -> ArmResult:
    return ArmResult(
        name="a",
        outcomes=tuple(
            TopicOutcome(
                key=f"t{index}",
                rank=index,
                score=0.0,
                documents_before=1,
                documents_after=1,
                lift=lift,
                doubling_years=None,
            )
            for index, lift in enumerate(lifts, 1)
        ),
    )


def test_hit_rate_counts_only_topics_clearing_the_threshold() -> None:
    arm = _arm(2.0, 2.0, 0.5, 0.1)

    assert arm.hit_rate == pytest.approx(0.5)


def test_median_lift_resists_a_single_runaway_topic() -> None:
    # The mean would let one ×100 outlier carry the arm; the median reports the typical pick.
    arm = _arm(0.5, 0.6, 0.7, 100.0)

    assert arm.median_lift == pytest.approx(0.65)


def test_an_empty_arm_scores_zero_rather_than_dividing_by_nothing() -> None:
    empty = ArmResult(name="a", outcomes=())

    assert empty.hit_rate == 0.0
    assert empty.median_lift == 0.0
    assert empty.median_lead_years is None


def test_median_lead_ignores_topics_that_never_doubled() -> None:
    # Treating "never" as a large number would invent data; excluding it reports the lead time over
    # the picks that did take off, which is the claim being made.
    arm = ArmResult(
        name="a",
        outcomes=(
            TopicOutcome("a", 1, 0.0, 1, 1, 1.0, 2),
            TopicOutcome("b", 2, 0.0, 1, 1, 1.0, None),
            TopicOutcome("c", 3, 0.0, 1, 1, 1.0, 4),
        ),
    )

    assert arm.median_lead_years == pytest.approx(3.0)
