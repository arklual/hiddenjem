"""Параметры профиля оркестратора обязаны доходить до движка.

Проверка существует потому, что обратное однажды уже случилось и было невидимо. Профиль
методологии живёт в базе оркестратора (миграция ``V2__seed_default_methodology_profile.sql``) и
хранит ключи вида ``orgRef``; поля движка называются по формулам методологии —
``diffusion_org_ref``. Перевода между ними не существовало, а ``with_overrides`` по контракту
молча игнорирует неизвестный ключ, — и все десять ключей профиля отбрасывались. Совпадение чисел
в сиде и в умолчаниях движка скрывало это полностью: настройка профиля просто не работала.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

from horizon_analytics.domain.scoring.profile import (
    RETIRED_PARAMETERS,
    MethodologyParameters,
    unmapped_parameters,
)


def _repository_root() -> Path:
    """Корень репозитория: каталог, в котором лежат контракты."""
    for candidate in Path(__file__).resolve().parents:
        if (candidate / "contracts").is_dir():
            return candidate
    raise AssertionError("корень репозитория не найден: нет каталога contracts")


def _seeded_parameters() -> dict[str, float]:
    """Параметры профиля ровно в том виде, в каком их заводит миграция оркестратора."""
    migration = (
        _repository_root()
        / "services"
        / "trends-service"
        / "src"
        / "main"
        / "resources"
        / "db"
        / "migration"
        / "V2__seed_default_methodology_profile.sql"
    )
    assert migration.is_file(), f"миграция сида профиля не найдена: {migration}"
    text = migration.read_text(encoding="utf-8")
    match = re.search(r"'(\{\"tau\".*?\})'::jsonb", text, re.DOTALL)
    assert match, "в миграции не найден JSON параметров профиля"
    seeded: dict[str, float] = json.loads(match.group(1))
    return seeded


def test_every_seeded_parameter_reaches_the_engine() -> None:
    """Каждый ключ сида — либо поле движка, либо отменённый параметр с объяснением."""
    seeded = _seeded_parameters()

    assert seeded, "сид профиля пуст: проверять нечего"
    assert unmapped_parameters(seeded) == ()


def test_a_changed_profile_value_changes_the_engine() -> None:
    """Значение из профиля действительно подменяет умолчание, а не игнорируется."""
    defaults = MethodologyParameters()

    tuned = defaults.with_overrides({"orgRef": 80, "minOrganizations": 3, "tau": 5.0})

    assert tuned.diffusion_org_ref == 80
    assert tuned.min_organizations_credible == 3
    assert tuned.novelty_tau == 5.0


def test_a_retired_parameter_does_not_come_back() -> None:
    """Отменённый параметр не возвращается в поле, которое методология выключила намеренно."""
    assert "relevanceThreshold" in RETIRED_PARAMETERS

    tuned = MethodologyParameters().with_overrides({"relevanceThreshold": 0.25})

    assert tuned.relevance_threshold == 0.0


def test_an_unknown_parameter_is_named() -> None:
    """Незнакомый ключ не роняет движок, но перестаёт быть невидимым."""
    tuned = MethodologyParameters().with_overrides({"совершенноНовыйПараметр": 1})

    assert tuned == MethodologyParameters()
    assert unmapped_parameters({"совершенноНовыйПараметр": 1}) == ("совершенноНовыйПараметр",)


def test_the_corpus_cap_can_be_raised_from_the_profile() -> None:
    """Предел усечения корпуса настраивается профилем — и не путается с пределом сбора.

    ``maxDocuments`` команды сбора ограничивает, сколько документов собрать (5000);
    ``maxDocumentsAnalyzed`` профиля — сколько прочитать при анализе (1500). Одно имя на две
    величины стоило бы либо неполноты корпуса, либо убийства воркера по памяти.
    """
    tuned = MethodologyParameters().with_overrides({"maxDocumentsAnalyzed": 3000})

    assert tuned.max_documents == 3000
    assert unmapped_parameters({"maxDocuments": 5000}) == ("maxDocuments",)
