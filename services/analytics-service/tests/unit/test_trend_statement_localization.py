"""Тема, сформулированная как тренд, доходит до карточки — и только когда модель справилась.

Шаг русского слоя собирает для модели факты темы (ряд по годам с неполным последним периодом,
стадию, направление) и её источники, а ответ кладёт рядом с переводом, называя модель. Отказ
модели не трогает остальной русский слой: переводы названий и текстов приходят как раньше.
"""

from __future__ import annotations

from types import SimpleNamespace
from typing import Any

from horizon_analytics.application.localization import LocalizationService


class FakeNlp:
    """Ответы сервиса моделей, записанные заранее; запоминает, что у него спросили."""

    def __init__(self, statements: tuple[str | None, ...] | None) -> None:
        self.statements = statements
        self.trend_items: list[dict[str, Any]] = []

    def title_case_terms(self, terms):
        return tuple(f"ру:{term}" for term in terms), "title-model"

    def translate(self, texts, *, source, target):
        return tuple(texts), "marian"

    def trend_statements(self, items):
        self.trend_items = list(items)
        if self.statements is None:
            return tuple(None for _ in items), None
        return self.statements, "gpt-test"


def _outcome(key: str) -> SimpleNamespace:
    series = SimpleNamespace(
        periods=("2022", "2023", "2024", "2025", "2026"),
        df=(0, 4, 39, 44, 12),
        last_period_share=0.74,
    )
    return SimpleNamespace(
        result=SimpleNamespace(
            trend_key=key,
            title=key,
            series=series,
            lifecycle_stage="ACCELERATING",
            first_mention_year=2023,
        ),
        motivation=SimpleNamespace(problem="Attackers bypass guardrails.", benefit=""),
        evidence=SimpleNamespace(
            items=(SimpleNamespace(title="Canary tokens in practice", snippet="Planted secrets reveal intruders."),)
        ),
        definition="Тема выделена по документам",
    )


def _result(*keys: str) -> SimpleNamespace:
    return SimpleNamespace(trends=tuple(_outcome(key) for key in keys))


def test_statement_reaches_the_card_with_its_model() -> None:
    nlp = FakeNlp(("Защитники начинают расставлять приманки, которые выдают взломщика.", None))
    service = LocalizationService(client=nlp)  # type: ignore[arg-type]

    localized = service.localize(_result("canary tokens", "trigger inversion"), direction="защита ИИ")

    first = localized["canary tokens"].to_dict()
    assert first["statement"].startswith("Защитники")
    assert first["statementModel"] == "gpt-test"
    assert "statement" not in localized["trigger inversion"].to_dict()


def test_the_model_sees_facts_and_sources_not_just_the_term() -> None:
    nlp = FakeNlp((None,))
    LocalizationService(client=nlp).localize(_result("canary tokens"), direction="защита ИИ")  # type: ignore[arg-type]

    item = nlp.trend_items[0]
    assert item["direction"] == "защита ИИ"
    assert item["stage"] == "ускоряющаяся"
    # Последние четыре периода; текущий помечен неполным, иначе «12» читается как спад.
    assert item["series"] == "2023 — 4, 2024 — 39, 2025 — 44, 2026 (неполный) — 12"
    assert "Attackers bypass guardrails." in item["fragments"]
    assert "Canary tokens in practice. Planted secrets reveal intruders." in item["fragments"]


def test_a_model_failure_keeps_the_rest_of_the_russian_layer() -> None:
    service = LocalizationService(client=FakeNlp(None))  # type: ignore[arg-type]

    localized = service.localize(_result("canary tokens"))

    row = localized["canary tokens"]
    assert row.statement is None
    assert row.title == "ру:canary tokens"
