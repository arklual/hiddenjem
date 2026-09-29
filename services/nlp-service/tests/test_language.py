"""Язык оригинала страницы определяется по тексту, а не подставляется «en» (ТЗ: язык источника)."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from horizon_nlp.research.corpus import WebCorpus
from horizon_nlp.research.language import detect_language

BODY = " Lorem." * 40


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        (
            "Банк России запустил пилот цифрового рубля для оплаты по QR-коду в розничных сетях",
            "ru",
        ),
        ("中国首款舱驾融合整车智能体芯片发布，地平线宣布量产计划并与多家车企合作", "zh"),
        (
            "The company announced a pilot of the new protocol with three banks and the regulator",
            "en",
        ),
        (
            "El equipo de fractura autónoma que también trajo Halliburton a Vaca Muerta es el primero del país",
            "es",
        ),
        (
            "A portabilidade de crédito via Open Finance não exige ir ao banco: a transferência é feita pelo aplicativo, também sem custos, já que o Pix está integrado",
            "pt",
        ),
        (
            "Die Bank hat eine neue Plattform für das Open Banking vorgestellt und ist damit nicht allein",
            "de",
        ),
        ("", None),
        ("12345 — 67890", None),
    ],
)
def test_the_language_comes_from_the_text(text: str, expected: str | None) -> None:
    assert detect_language(text) == expected


def test_a_russian_page_with_english_terms_stays_russian() -> None:
    text = "Команда OpenAI представила агентные платежи через API: банки тестируют протокол AP2 в песочнице"

    assert detect_language(text) == "ru"


def test_a_corpus_page_marked_none_gets_its_language_from_the_text(tmp_path: Path) -> None:
    # Сборщик писал в поле lang строку "None"; раньше такая страница показывалась как «en».
    pages = tmp_path / "docs.jsonl"
    rows = [
        {
            "url": "https://habr.com/ru/1",
            "title": "Цифровые активы застройщиков",
            "lang": "None",
            "text": "Застройщики выпускают цифровые финансовые активы на квадратный метр жилья. "
            * 5,
        },
        {
            "url": "https://example.cn/2",
            "title": "舱驾融合芯片",
            "lang": None,
            "text": "地平线发布中国首款舱驾融合整车智能体芯片，计划与多家车企合作量产。" * 8,
        },
        {"url": "https://example.com/3", "title": "Known page", "lang": "de", "text": "x" + BODY},
    ]
    pages.write_text(
        "\n".join(json.dumps(r, ensure_ascii=False) for r in rows) + "\n", encoding="utf-8"
    )

    corpus = WebCorpus.load(pages)

    assert [d.language for d in corpus.documents] == ["ru", "zh", "de"]
