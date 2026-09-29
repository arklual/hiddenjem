"""Проверка «цель словаря существует у источника» — её правило совпадения.

Сама проверка ходит в сеть и потому в гейт не входит (`make lexicon-targets`). Правило, по
которому она решает «нашлось / не нашлось», сети не требует и проверяется здесь: без этого у
инструмента, найденного нужным после четырёх выдуманных целей, не было бы ни одного случая.

Разбор: ``docs/01-analysis/66-four-targets-that-did-not-exist.md``.
"""

from __future__ import annotations

import importlib.util
from pathlib import Path

TOOL = Path(__file__).resolve().parents[4] / "tools" / "check-lexicon-targets.py"


def load():  # тип модуля-скрипта неинтересен
    spec = importlib.util.spec_from_file_location("check_lexicon_targets", TOOL)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


CATALOGUE = {
    "cryptography": ["Quantum cryptography", "Public-key cryptography"],
    "renewable energy": ["Renewable energy", "Variable renewable energy"],
    "financial technology": ["Financial market", "Technology acceptance model"],
    "technology economics": [],
}


def test_a_target_found_only_inside_a_longer_concept_counts() -> None:
    """`cryptography` существует через `Quantum cryptography` — это уточнение, а не отсутствие."""
    dead, checked = load().dead_targets(["cryptography"], CATALOGUE.__getitem__)
    assert (dead, checked) == ([], 1)


def test_a_target_nothing_matches_is_reported() -> None:
    """`financial technology` возвращает похожие имена и не совпадает ни с одним."""
    dead, _ = load().dead_targets(["financial technology"], CATALOGUE.__getitem__)
    assert dead == ["financial technology"]


def test_an_empty_answer_is_reported() -> None:
    dead, _ = load().dead_targets(["technology economics"], CATALOGUE.__getitem__)
    assert dead == ["technology economics"]


def test_every_target_is_checked_and_counted() -> None:
    dead, checked = load().dead_targets(CATALOGUE, CATALOGUE.__getitem__)
    assert checked == 4
    assert dead == ["financial technology", "technology economics"]


def test_codes_of_other_sources_are_not_asked_of_openalex() -> None:
    """`cs.CR`, `q-bio.GN`, `q-fin.TR` — словарь arXiv; спрашивать их у OpenAlex бессмысленно.

    Проверяется форма, а не знакомые префиксы: список префиксов был первым и пропускал `q-fin.TR`,
    потому что перечислял только те архивы, что попались на глаза при написании.
    """
    wanted = load().targets()
    assert wanted, "цели словаря не разобрались"
    codes = [target for target in wanted if "." in target or target == "quant-ph"]
    assert not codes, f"коды чужих источников попали в запрос к OpenAlex: {codes}"
