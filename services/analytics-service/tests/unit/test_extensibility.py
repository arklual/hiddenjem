"""Executable proof of the extension points `docs/02-architecture/05-extensibility.md` claims.

That document tells whoever receives the final requirements what a change will cost. A claim like
"a new indicator is one class and a weight" is worth nothing unless something fails when it stops
being true — and it *would* stop being true silently, because nothing else exercises the path.

So each test here performs the documented procedure end to end and asserts it works. If a future
change makes an extension point more expensive than advertised, this suite goes red before the
document goes stale.
"""

from __future__ import annotations

from dataclasses import replace
from typing import ClassVar, cast

import pytest
from tests.conftest import make_document, make_series, make_topic

from horizon_analytics import container
from horizon_analytics.adapters.embeddings.tfidf_svd import TfidfSvdEmbeddingProvider
from horizon_analytics.config import Settings
from horizon_analytics.container import build_embedding_provider
from horizon_analytics.domain import models
from horizon_analytics.domain.models import CorpusStats, IndicatorName
from horizon_analytics.domain.scoring import aggregators
from horizon_analytics.domain.scoring import profile as profile_module
from horizon_analytics.domain.scoring.engine import EmergenceEngine
from horizon_analytics.domain.scoring.indicators import (
    IndicatorContext,
    IndicatorValue,
    build_indicators,
)
from horizon_analytics.domain.scoring.profile import MethodologyParameters, MethodologyProfile


class PatentPressureIndicator:
    """A seventh indicator, standing in for whatever the final requirements ask for."""

    # The name is outside the `IndicatorName` union on purpose: widening that Literal is a real
    # step of the procedure, and the cast documents it rather than hiding it.
    name: ClassVar[IndicatorName] = cast(IndicatorName, "patent_pressure")

    def compute(self, context: IndicatorContext) -> IndicatorValue:
        share = min(1.0, len(context.documents) / 10)
        return IndicatorValue(
            name=self.name, value=share, explanation="доля патентов", diagnostics={}
        )


def context_for(parameters: object, document_count: int = 4) -> IndicatorContext:
    documents = tuple(
        make_document(f"d{index}", year=2024, title="T", abstract="topic text")
        for index in range(document_count)
    )
    return IndicatorContext(
        topic=make_topic("neutral atom", documents=[d.document_id for d in documents]),
        series=make_series([1, 2, 4, 8]),
        documents=documents,
        corpus=CorpusStats(
            periods=("2020", "2021", "2022", "2023"),
            documents_per_period=(100, 100, 100, 100),
            active_source_classes=tuple({d.source_class for d in documents}),
            total_documents=400,
            recent_max=50,
            volume_q25=2.0,
            volume_q60=8.0,
            mainstream_threshold=40.0,
            current_year=2024,
        ),
        parameters=cast(MethodologyParameters, parameters),
        first_mention_year=2020,
    )


@pytest.fixture
def profile_with_seventh(monkeypatch: pytest.MonkeyPatch) -> MethodologyProfile:
    """Carry out the whole documented procedure for adding an indicator, then hand back a profile.

    The order of the two halves is the lesson. The known-name tuple has to be widened — the engine,
    the aggregator and the profile all validate against it — but the *existing* profile has to be
    read before that happens, because once "patent_pressure" is a known name, a profile without a
    weight for it is invalid. A document that said "just implement the class" would send whoever
    receives the final requirements down a path that fails on the first run.
    """
    weight = 0.10
    base = MethodologyProfile.default()
    scaled = {name: value * (1.0 - weight) for name, value in base.weights.items()}
    scaled["patent_pressure"] = weight

    widened = (*models.INDICATOR_NAMES, cast(IndicatorName, "patent_pressure"))
    monkeypatch.setattr(models, "INDICATOR_NAMES", widened)
    monkeypatch.setattr(profile_module, "INDICATOR_NAMES", widened)
    monkeypatch.setattr(aggregators, "INDICATOR_NAMES", widened)

    return replace(base, weights=scaled)


class TestNewIndicator:
    """Documented cost: widen the name tuple, implement the class, register it, give it a weight.

    Four edits, all inside analytics-service. No migration, no contract change, no Java change, no
    frontend change — each of those is asserted below or in the sibling suites.
    """

    def test_the_engine_scores_with_an_indicator_it_was_not_written_for(
        self, profile_with_seventh: MethodologyProfile
    ) -> None:
        engine = EmergenceEngine(
            profile=profile_with_seventh,
            indicators=(*build_indicators(), PatentPressureIndicator()),
        )

        result = engine.score(context_for(profile_with_seventh.parameters))

        assert any(indicator.name == "patent_pressure" for indicator in result.indicators)
        assert 0.0 <= result.score <= 100.0

    def test_the_weight_travels_in_the_profile_and_needs_no_migration(
        self, profile_with_seventh: MethodologyProfile
    ) -> None:
        # The Java side validates only that weights sum to 1, not their names, so activating a new
        # indicator in a running system is `POST /api/v1/methodology/profiles` — no schema change.
        assert "patent_pressure" in profile_with_seventh.weights
        assert sum(profile_with_seventh.weights.values()) == pytest.approx(1.0)

    def test_a_seventh_indicator_still_obeys_the_zeroing_rule(
        self, profile_with_seventh: MethodologyProfile
    ) -> None:
        # BRULE-4: the weighted geometric mean means any zero indicator zeroes the score. An
        # extension point that quietly exempted new indicators from the core rule would be worse
        # than no extension point.
        class AlwaysZero:
            name: ClassVar[IndicatorName] = cast(IndicatorName, "patent_pressure")

            def compute(self, context: IndicatorContext) -> IndicatorValue:
                return IndicatorValue(name=self.name, value=0.0, explanation="ноль", diagnostics={})

        engine = EmergenceEngine(
            profile=profile_with_seventh,
            indicators=(*build_indicators(), AlwaysZero()),
        )

        assert engine.score(context_for(profile_with_seventh.parameters)).score == pytest.approx(
            0.0
        )


class TestStandardIndicatorsUnchanged:
    """The six standard indicators are a fixed, ordered tuple — determinism depends on it."""

    def test_order_is_fixed_rather_than_derived_from_a_mapping(self) -> None:
        # Iteration order must never depend on hashing (methodology §9), so this is pinned here
        # as well as in the golden corpus: a reordering changes tie-breaks, not just output shape.
        names = [indicator.name for indicator in build_indicators()]

        assert names == ["novelty", "growth", "diffusion", "weakness", "coherence", "impact"]

    def test_default_weights_sum_to_one(self) -> None:
        assert sum(MethodologyProfile.default().weights.values()) == pytest.approx(1.0)


class TestEmbeddingProviderSwap:
    """Documented cost of row 2: implement the port, point one environment variable at it."""

    @staticmethod
    def provider_for(**env: str) -> object:
        settings = Settings.model_validate({"HORIZON_EMBEDDING_PROVIDER": "tfidf-svd", **env})
        return build_embedding_provider(settings)

    def test_the_default_provider_is_the_deterministic_one(self) -> None:
        assert isinstance(self.provider_for(), TfidfSvdEmbeddingProvider)

    def test_an_http_provider_is_selected_by_configuration_alone(self) -> None:
        # No code change and no rebuild: this is the whole of what the document promises for row 2.
        provider = self.provider_for(
            HORIZON_EMBEDDING_PROVIDER="http", HORIZON_EMBEDDING_URL="http://embeddings:8080/embed"
        )

        assert type(provider).__name__ == "HttpEmbeddingProvider"

    def test_an_unusable_provider_degrades_instead_of_taking_the_service_down(self) -> None:
        # ADR-0009: a misconfigured optional provider must not stop analyses from running at all.
        provider = self.provider_for(HORIZON_EMBEDDING_PROVIDER="http")

        assert isinstance(provider, TfidfSvdEmbeddingProvider)

    def test_the_degradation_is_announced_rather_than_silent(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        # Degrading quietly would be worse than failing: the operator asked for a specific model,
        # and substituting another changes every score in every report while looking like success.
        # A missing URL is a typo in a deployment manifest, not a decision anyone made.
        warned: list[str] = []
        monkeypatch.setattr(container._LOGGER, "warning", lambda event, **_: warned.append(event))

        self.provider_for(HORIZON_EMBEDDING_PROVIDER="http")

        assert "embeddings.http_url_missing" in warned

    def test_the_seed_reaches_the_default_provider(self) -> None:
        # Determinism (ADR-0015) is a property of the wiring, not only of the algorithm: two runs
        # configured alike must embed alike.
        first = self.provider_for(HORIZON_EMBEDDING_SEED="4242")
        second = self.provider_for(HORIZON_EMBEDDING_SEED="4242")

        texts = ("нейроморфные вычисления", "квантовая коррекция ошибок")
        assert [list(row) for row in first.embed(texts)] == [
            list(row) for row in second.embed(texts)
        ]
