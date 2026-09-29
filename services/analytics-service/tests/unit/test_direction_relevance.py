"""Whether a topic belongs to the requested direction.

This is the rule that decides what a report is *about*, and it failed in a way that was invisible
from the outside: with the old "at least one on-direction document" gate, the computer-security
report was led by speculative decoding and topological qubits. Every case here pins one property
that keeps that from coming back.
"""

from __future__ import annotations

import pytest

from horizon_analytics.domain.models import Posting, TermCandidate, Topic
from horizon_analytics.domain.pipeline import AnalysisPipeline
from horizon_analytics.domain.scoring.profile import MethodologyProfile

PARAMETERS = MethodologyProfile.default().parameters


def topic(key: str, document_ids: list[str]) -> Topic:
    member = TermCandidate(
        key=key,
        surface=key,
        surface_forms=(key,),
        postings=tuple(
            Posting(document_id=document_id, occurrences=1) for document_id in sorted(document_ids)
        ),
        termhood=1.0,
        token_count=len(key.split(" ")),
    )
    return Topic(key=key, label=key, members=(member,))


def relevance_of(topic_documents: list[str], subject_relevance: dict[str, float]) -> float:
    """The relevance the pipeline measures for a topic, given the direction's documents.

    The embedding arguments are real but irrelevant here: they only exist so the subject-code path
    is reached, and the subject path must not consult them.
    """
    document_index = {
        document_id: index for index, document_id in enumerate(sorted(topic_documents))
    }
    document_vectors = [[1.0, 0.0] for _ in document_index]
    return AnalysisPipeline._relevance(
        topic("t", topic_documents),
        document_vectors=document_vectors,
        document_index=document_index,
        query_vector=[0.0, 1.0],
        subject_relevance=subject_relevance,
    )


def share(topic_documents: list[str], in_direction: set[str]) -> float:
    return relevance_of(topic_documents, dict.fromkeys(in_direction, 1.0))


def test_relevance_is_the_share_of_documents_inside_the_direction() -> None:
    # A proportion, not a depth of match: the filter asks a question about the topic ("is the
    # literature discussing this term about the direction?"), and that is a share.
    assert share(["a", "b", "c", "d"], {"a", "b"}) == pytest.approx(0.5)


def test_a_topic_mentioned_once_in_the_direction_scores_near_zero() -> None:
    # This is the exact shape of the defect: an AI topic appearing in one security paper out of
    # sixteen used to clear the gate outright, and then outscored the genuine security topics
    # because its score was computed over all of its documents.
    assert share([f"d{index}" for index in range(16)], {"d0"}) == pytest.approx(1 / 16)


def test_a_topic_entirely_inside_the_direction_scores_one() -> None:
    assert share(["a", "b"], {"a", "b"}) == pytest.approx(1.0)


def test_depth_of_subject_match_does_not_inflate_the_share() -> None:
    # Two documents, one matching the direction squarely and one partially, is still "half of the
    # literature" — otherwise a single strongly-classified document could carry a topic in.
    assert relevance_of(["a", "b"], {"a": 0.2}) == pytest.approx(0.5)


class TestBaseRateThreshold:
    """The cut is a multiple of the corpus base rate, not an absolute share."""

    def test_a_narrow_direction_gets_a_lower_bar_than_a_broad_one(self) -> None:
        # The same question — "markedly more concentrated here than the corpus at large?" — asked of
        # a direction covering a fiftieth of the corpus and one covering a quarter. A fixed share
        # would ask two different questions and call it one rule.
        narrow = min(1.0, PARAMETERS.relevance_direction_lift * (25 / 1244))
        broad = min(1.0, PARAMETERS.relevance_direction_lift * (300 / 1244))

        assert narrow < broad

    def test_a_majority_rule_would_reject_a_genuine_spreading_topic(self) -> None:
        # Speculative decoding sits in 44% AI-classified documents. Any "most of its literature"
        # rule rejects it — and an emerging technology spreading into adjacent fields is precisely
        # what the diffusion indicator exists to reward, so the majority rule penalises the property
        # the product is looking for.
        measured = 0.4375
        base_rate = 178 / 1244

        assert measured < 0.5
        assert measured >= PARAMETERS.relevance_direction_lift * base_rate

    def test_the_bar_never_exceeds_one(self) -> None:
        # A direction covering most of the corpus would otherwise produce an unreachable cut and an
        # empty report, which reads to an analyst as "nothing is happening in this field".
        cut = min(1.0, PARAMETERS.relevance_direction_lift * 0.9)

        assert cut == 1.0


class TestClusterLabel:
    """Which member of a cluster of synonyms gets to name the topic."""

    @staticmethod
    def promote(
        best_key: str, member_keys: list[str], documents: dict[str, int] | None = None
    ) -> str:
        counts = documents or {}
        members = [
            topic(key, [f"d{index}" for index in range(counts.get(key, 1))]).members[0]
            for key in [best_key, *member_keys]
        ]
        best = next(member for member in members if member.key == best_key)
        return AnalysisPipeline._promote_full_form(best, members).key

    def test_prefers_the_full_name_over_a_truncation_of_itself(self) -> None:
        # The weight that picks the label rewards frequency, and a truncation is always at least as
        # frequent as the name it truncates — every mention of "software bill of materials" is also
        # a mention of "bill of materials". Left alone, the report names real technologies by
        # fragments.
        assert (
            self.promote("bill of material", ["software bill of material"])
            == "software bill of material"
        )

    def test_extends_leftwards_only(self) -> None:
        # A modifier in front keeps the head and narrows the meaning; a word after it replaces the
        # head and names something else. "mechanistic interpretability baseline" is a baseline for
        # the technology, not the technology.
        assert (
            self.promote(
                "mechanistic interpretability",
                ["mechanistic interpretability baseline"],
                {"mechanistic interpretability": 12, "mechanistic interpretability baseline": 2},
            )
            == "mechanistic interpretability"
        )

    def test_extends_rightwards_only_a_fragment_mostly_written_in_full(self) -> None:
        # Бэктест 2016 года: `mobile edge` — 13 документов, из них 9 пишут `mobile edge computing`.
        # Обрубок здесь — само имя без главного слова, и назвать им тему значит назвать технологию
        # куском (разбор 103).
        assert (
            self.promote(
                "mobile edge",
                ["mobile edge computing"],
                {"mobile edge": 13, "mobile edge computing": 9},
            )
            == "mobile edge computing"
        )

    def test_picks_the_longest_left_extension(self) -> None:
        assert (
            self.promote("space model", ["state space model", "selective state space model"])
            == "selective state space model"
        )

    def test_leaves_unrelated_members_alone(self) -> None:
        assert self.promote("neutral atom", ["trapped ion qubit"]) == "neutral atom"

    def test_does_not_promote_on_a_partial_token_match(self) -> None:
        # "atom" ending "neutral atom" is a token boundary; "diatom" merely ends with the same
        # letters and is a different word.
        assert self.promote("atom", ["diatom"]) == "atom"


# ───────── общее слово против прямого ответа источника ─────────
#
# Замер на 1785 работах OpenAlex, направление «information security»: третьей строкой отчёта стояла
# `spatial information` с долей направления 0.0 — ни одного её документа источник к безопасности не
# отнёс. Прошла через слово `information`. Разбор: docs/01-analysis/68-a-shared-word-outvoted-the-source.md


def test_a_shared_word_does_not_survive_a_source_that_says_none() -> None:
    """Тема, названная как направление, но не имеющая в нём ни одного документа, не проходит."""
    assert not AnalysisPipeline._is_relevant(
        topic("spatial information", ["d1"]),
        similarity=0.0,
        query_stems=frozenset({"information", "security"}),
        parameters=PARAMETERS,
        cut=0.7,
        by_subjects=True,
    )


def test_a_shared_word_still_saves_a_topic_the_source_placed_inside() -> None:
    """Довод остаётся доводом там, где источник ответил «хоть сколько-то»."""
    assert AnalysisPipeline._is_relevant(
        topic("energy input", ["d1"]),
        similarity=0.2,
        query_stems=frozenset({"renewable", "energy"}),
        parameters=PARAMETERS,
        cut=0.7,
        by_subjects=True,
    )


def test_without_subject_codes_the_shared_word_remains_the_safety_net() -> None:
    """Нулевая лексическая близость — обычное дело; там обход и нужен."""
    assert AnalysisPipeline._is_relevant(
        topic("spatial information", ["d1"]),
        similarity=0.0,
        query_stems=frozenset({"information", "security"}),
        parameters=PARAMETERS,
        cut=0.7,
        by_subjects=False,
    )
