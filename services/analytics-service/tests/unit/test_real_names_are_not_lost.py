"""Потери настоящих названий — исполняемый замер, а не число в документе.

Записка о границах (`docs/01-analysis/30-boundary-filter-findings.md`) сама называет этот пробел:
«списка настоящих названий как постоянной проверки пока нет; он должен появиться в том же изменении,
что и лечение». Лечение состоялось частично — два имени вернулись, — и проверка появляется здесь.

Форма проверки — **заморозка, а не требование нуля**. Восемнадцать имён из списка отбрасываются
по-прежнему, и объявить это ошибкой значило бы держать набор красным месяцами. Замороженное число
устроено иначе: оно фиксирует достигнутое и краснеет при ухудшении, а уменьшать его можно только
сознательно, правкой этой строки вместе с лечением.

Зачем вообще, если число уже записано в трёх документах. Затем, что число в документе не краснеет.
Сегодня оно там разошлось с кодом — правило изменилось накануне, а «20 из 58» осталось в README,
в сценарии демонстрации и в записке. Исполняемый замер такого не допускает.

Список имён живёт **в проверке, а не в продукте**, и это не деталь размещения. Перечень составлен
независимо от фильтра, чтобы измерять потери на именах, которых фильтр не видел; перенеся его в
список исключений, мы получили бы ноль потерь и ни одного измерения.
"""

from __future__ import annotations

import pytest

from horizon_analytics.domain.extraction.blacklist import GenericTermFilter, stem_phrase
from horizon_analytics.domain.extraction.tokenizer import tokenize
from horizon_analytics.tools.boundary_losses import KNOWN_REAL_NAMES as TOOL_NAMES

FILTER = GenericTermFilter.default()

#: Список живёт в измерительном инструменте (`tools/boundary_losses`), а не здесь: тем же списком
#: печатается таблица `make losses`, и две копии разошлись бы — ровно так уже разъезжались числа.
KNOWN_REAL_NAMES = TOOL_NAMES

#: Сколько из них теряется сейчас. Замер 2026-08-10; было 20 до списка устоявшихся имён.
#:
#: Число уменьшают правкой этой строки вместе с лечением — и никогда не увеличивают.
FROZEN_LOSSES = 18

#: Имена, возвращённые списком устоявшихся: они не должны потеряться снова.
RECOVERED = ("large language model", "high performance computing")

#: Контрольные обрывки: без них «улучшить» замер можно было бы, обезвредив правило границы целиком.
CONTROL_FRAGMENTS = (
    "large transaction batches",
    "strong transformer baselines",
    "available cycle life",
    "encrypted memory remains",
)


def rejected(name: str) -> bool:
    return FILTER.rejects(stem_phrase(name), [t.normal for t in tokenize(name)]) is not None


def test_the_number_of_lost_real_names_does_not_grow() -> None:
    lost = [name for name in KNOWN_REAL_NAMES if rejected(name)]

    assert len(lost) <= FROZEN_LOSSES, (
        f"правило границы стало терять больше настоящих названий: {len(lost)} против "
        f"{FROZEN_LOSSES}; потеряны {lost}"
    )


def test_the_frozen_number_is_not_stale() -> None:
    # Заморозка, которая молча перестала соответствовать замеру, — то же самое число в документе,
    # только в коде. Если потерь стало меньше, строку выше правят вместе с лечением.
    lost = [name for name in KNOWN_REAL_NAMES if rejected(name)]

    assert len(lost) == FROZEN_LOSSES, (
        f"потерь стало {len(lost)} — обновите FROZEN_LOSSES в этой проверке и числа в README, "
        "сценарии демонстрации и записке о границах"
    )


@pytest.mark.parametrize("name", RECOVERED)
def test_a_recovered_name_stays_recovered(name: str) -> None:
    assert not rejected(name)


@pytest.mark.parametrize("fragment", CONTROL_FRAGMENTS)
def test_the_rule_still_rejects_fragments(fragment: str) -> None:
    # Обратная сторона замороженного числа: уменьшить потери можно и обезвредив правило целиком,
    # и тогда «улучшение» означало бы, что фильтр перестал фильтровать.
    assert rejected(fragment)
