"""The `DomainAnalyzed` event we publish must satisfy the schema we publish.

`contracts/schemas/` is what another team builds a consumer against — a BI feed, a notification
service, a model-training job. Nothing kept the producer honest against it: the schema existed, a
fixture to load it existed, and no test used either. A contract nobody checks is a contract that
drifts, and it drifts silently because our own consumer changes in the same commit as the producer.

The event here is built by the real pipeline over the real corpus, not hand-written. A hand-written
payload would prove the schema matches the payload the test author imagined, which is the one thing
never in doubt.
"""

from __future__ import annotations

import json
from datetime import date
from pathlib import Path
from typing import Any

import pytest
from jsonschema import Draft202012Validator

from horizon_analytics.adapters.corpus.fixture_loader import load_documents
from horizon_analytics.adapters.embeddings.tfidf_svd import TfidfSvdEmbeddingProvider
from horizon_analytics.application.dto import build_domain_analyzed
from horizon_analytics.domain.models import AnalysisParams
from horizon_analytics.domain.pipeline import AnalysisPipeline, PipelineRequest
from horizon_analytics.domain.scoring.profile import MethodologyProfile

CORPUS = Path(__file__).resolve().parents[4] / "fixtures" / "corpus" / "documents.jsonl"
SCHEMAS = Path(__file__).resolve().parents[4] / "contracts" / "schemas"


@pytest.fixture(scope="module")
def analyzed_event() -> dict[str, Any]:
    """A `DomainAnalyzed` payload produced by the real pipeline over the golden corpus."""
    # Падение, а не пропуск. Корпус лежит в репозитории и отслеживается git — его отсутствие
    # означает сломанный путь, а не отсутствующее окружение. Пропущенная проверка выглядит в
    # отчёте пройденной: в этом проекте так уже терялись целые файлы проверок, когда путь
    # разъезжался на один каталог.
    assert CORPUS.is_file(), f"эталонный корпус не найден: {CORPUS}"

    documents = load_documents(CORPUS)
    profile = MethodologyProfile.default()
    request = PipelineRequest(
        normalized_query="artificial intelligence machine learning",
        query="artificial intelligence machine learning",
        documents=tuple(documents),
        params=AnalysisParams(top_n=15, years_window=8),
        profile=profile,
        window_from=date(2018, 1, 1),
        window_to=date(2025, 12, 31),
        today=date(2026, 1, 1),
    )
    result = AnalysisPipeline(embedding_provider=TfidfSvdEmbeddingProvider()).run(request)
    return build_domain_analyzed(
        result,
        research_request_id="019fd789-0000-7000-8000-000000000001",
        attempt=1,
        snapshot_id="019fd789-0000-7000-8000-000000000002",
        profile=profile,
    )


@pytest.fixture(scope="module")
def schema() -> dict[str, Any]:
    return json.loads((SCHEMAS / "domain-analyzed.event.json").read_text(encoding="utf-8"))


@pytest.mark.integration
def test_the_published_event_satisfies_the_published_schema(
    analyzed_event: dict[str, Any], schema: dict[str, Any]
) -> None:
    errors = sorted(
        Draft202012Validator(schema).iter_errors(analyzed_event),
        key=lambda error: list(error.absolute_path),
    )

    assert not errors, "\n".join(
        f"{'/'.join(str(part) for part in error.absolute_path) or '<корень>'}: {error.message}"
        for error in errors
    )


@pytest.mark.integration
def test_the_event_carries_the_trends_a_consumer_subscribes_for(
    analyzed_event: dict[str, Any],
) -> None:
    # A payload that validates while being empty would pass the contract and be useless to the
    # consumer the contract exists for.
    assert analyzed_event["trends"], "событие без трендов удовлетворяет схеме, но бесполезно"


@pytest.mark.integration
def test_every_case_example_says_on_what_it_rests(analyzed_event: dict[str, Any]) -> None:
    # Основание кейс-примера считается в домене и легко теряется при сериализации: схема разрешает
    # его отсутствие ради ранее сохранённых отчётов, поэтому пропажу поля не поймает ни она, ни
    # проверка отбора. А без основания карточка снова утверждает практическое применение там, где
    # есть только препринт, — из 90 тем эталонного корпуса таких 21.
    examples = [
        trend["caseExample"] for trend in analyzed_event["trends"] if trend.get("caseExample")
    ]

    assert examples, "ни одной темы с кейс-примером — проверка ниже стала бы вечнозелёной"
    assert all(
        example.get("basis") in {"PATENT", "CORPORATE_PUBLICATION", "ACADEMIC_GROUP"}
        for example in examples
    ), "кейс-пример опубликован без основания отбора"


@pytest.mark.integration
def test_the_event_is_serialisable_as_published(analyzed_event: dict[str, Any]) -> None:
    # It travels as JSON over Kafka. A value the pipeline produces but `json` cannot encode — a
    # numpy scalar, a date — fails at publish time, in production, on a payload nobody saw.
    encoded = json.dumps(analyzed_event, ensure_ascii=False)

    assert json.loads(encoded) == analyzed_event


@pytest.mark.integration
def test_every_published_schema_is_itself_valid() -> None:
    # Ten schemas are published; a malformed one silently accepts anything, which is worse than
    # having no schema because a consumer team would trust it.
    #
    # Утверждение здесь — сам вызов: `check_schema` бросает на негодной схеме. Сказано вслух,
    # потому что проверка без `assert` читается как забытая, а забытая проверка зелена всегда.
    for path in sorted(SCHEMAS.glob("*.json")):
        Draft202012Validator.check_schema(json.loads(path.read_text(encoding="utf-8")))
