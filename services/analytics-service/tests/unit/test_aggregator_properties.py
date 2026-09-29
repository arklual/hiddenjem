"""Свойства агрегатора балла — на всём пространстве входов, а не на подобранных примерах.

Формула `ES = 100·Π xᵏ^wᵏ` — ядро методологии: на ней держится и ранжирование, и объяснение балла
аналитику. Проверять её примерами значит проверять те входы, которые автор придумал, — а
интересны ровно те, которых он не придумал: ноль, единица, вес ноль, накопление ошибки на шести
множителях.

`hypothesis` была объявлена в зависимостях и маркер `property` зарегистрирован в конфигурации, но ни
одного такого теста не существовало — README при этом обещал property-based слой. Здесь обещание
становится проверкой.

Свойства выбраны не по учебнику, а по тому, что обещано аналитику и записано в методологии:

* **BRULE-4** — ноль по любому индикатору обнуляет балл. На этом держится «непокомпенсируемость»:
  тема без новизны не может добрать ростом, и это утверждение продаётся жюри.
* **Балл в [0, 100]** — иначе шкала, показанная в интерфейсе, перестаёт что-либо значить.
* **Монотонность** — улучшение индикатора не может понизить балл. Без неё объяснение «этот
  индикатор ограничивает балл» неверно.
* **Разложение сходится с баллом** — интерфейс показывает множители `xᵏ^wᵏ`, и если их произведение
  не равно баллу, то показанное объяснение объясняет не тот балл.
* **Детерминизм (ADR-0015)** — одни и те же входы дают один и тот же балл.
"""

from __future__ import annotations

import math

import pytest
from hypothesis import assume, example, given, settings
from hypothesis import strategies as st

from horizon_analytics.domain.scoring.aggregators import WeightedGeometricAggregator

pytestmark = pytest.mark.property

INDICATORS = ("novelty", "growth", "diffusion", "weakness", "coherence", "impact")

#: Значения индикаторов: движок принимает их из [0, 1], и границы включены намеренно — ноль и
#: единица это и есть интересные случаи.
values = st.fixed_dictionaries(
    {name: st.floats(min_value=0.0, max_value=1.0, allow_nan=False) for name in INDICATORS}
)


#: Веса: неотрицательные и в сумме единица — так их задаёт профиль методологии.
def _normalised(raw: list[float]) -> dict[str, float]:
    total = math.fsum(raw)
    return {name: weight / total for name, weight in zip(INDICATORS, raw, strict=True)}


weights = st.lists(
    st.floats(min_value=0.01, max_value=1.0, allow_nan=False),
    min_size=len(INDICATORS),
    max_size=len(INDICATORS),
).map(_normalised)


@given(values=values, weights=weights)
# Границы задаются явно, а не оставляются на усмотрение генератора: именно на «всех единицах» балл
# упирается в верх шкалы, и подмена, снимающая ограничение, видна только там. Проверка, полагающаяся
# на то, что генератор когда-нибудь дойдёт до угла, ловит нарушение через раз.
@example(
    values=dict.fromkeys(INDICATORS, 1.0),
    weights={name: 1.0 / len(INDICATORS) for name in INDICATORS},
)
@example(
    values=dict.fromkeys(INDICATORS, 0.0),
    weights={name: 1.0 / len(INDICATORS) for name in INDICATORS},
)
@settings(max_examples=200, deadline=None)
def test_the_score_stays_on_its_scale(values: dict[str, float], weights: dict[str, float]) -> None:
    # Шкала показана аналитику как «балл эмерджентности от 0 до 100». Выход за неё делает
    # бессмысленным и шкалу, и сравнение тем между собой.
    outcome = WeightedGeometricAggregator().aggregate(values, weights)

    assert 0.0 <= outcome.score <= 100.0


@given(values=values, weights=weights, zeroed=st.sampled_from(INDICATORS))
@settings(max_examples=200, deadline=None)
def test_a_zero_indicator_zeroes_the_score(
    values: dict[str, float], weights: dict[str, float], zeroed: str
) -> None:
    # BRULE-4. Утверждение «индикаторы не компенсируют друг друга» — то, чем продукт отличается от
    # взвешенной суммы, и оно должно быть верно при любых остальных значениях, а не при удобных.
    values = {**values, zeroed: 0.0}

    outcome = WeightedGeometricAggregator().aggregate(values, weights)

    assert outcome.score == 0.0
    assert zeroed in outcome.zeroed_by


@given(
    values=values,
    weights=weights,
    improved=st.sampled_from(INDICATORS),
    delta=st.floats(min_value=0.001, max_value=1.0, allow_nan=False),
)
@settings(max_examples=200, deadline=None)
def test_improving_an_indicator_never_lowers_the_score(
    values: dict[str, float], weights: dict[str, float], improved: str, delta: float
) -> None:
    # Без монотонности объяснение «балл ограничивает вот этот индикатор» неверно: подтянув его,
    # аналитик мог бы получить балл ниже. Тогда вся вкладка «Методология» вводит в заблуждение.
    better = {**values, improved: min(1.0, values[improved] + delta)}

    aggregator = WeightedGeometricAggregator()
    before = aggregator.aggregate(values, weights).score
    after = aggregator.aggregate(better, weights).score

    assert after >= before - 1e-6


@given(values=values, weights=weights)
@settings(max_examples=200, deadline=None)
def test_the_breakdown_multiplies_back_to_the_score(
    values: dict[str, float], weights: dict[str, float]
) -> None:
    # Интерфейс показывает множители и обещает, что их произведение и есть балл. Разойдясь, они
    # объясняют не тот балл — а объяснение, не сходящееся с числом, хуже отсутствия объяснения.
    assume(all(value > 0.0 for value in values.values()))
    outcome = WeightedGeometricAggregator().aggregate(values, weights)

    product = 1.0
    for item in outcome.breakdown:
        product *= item.multiplier

    assert outcome.score == pytest.approx(100.0 * product, abs=1e-4)


@given(values=values, weights=weights)
@settings(max_examples=100, deadline=None)
def test_the_same_inputs_give_the_same_score(
    values: dict[str, float], weights: dict[str, float]
) -> None:
    # ADR-0015. Проверяется на случайных входах, а не на одном примере: недетерминизм от порядка
    # обхода словаря проявился бы именно на нетипичном наборе.
    aggregator = WeightedGeometricAggregator()

    assert (
        aggregator.aggregate(values, weights).score == aggregator.aggregate(values, weights).score
    )


@given(weights=weights, level=st.floats(min_value=0.01, max_value=1.0, allow_nan=False))
@settings(max_examples=100, deadline=None)
def test_equal_indicators_score_their_own_level(weights: dict[str, float], level: float) -> None:
    # Проверка самой формулы, а не её реализации: при равных значениях взвешенное геометрическое
    # среднее равно этому значению независимо от весов, потому что веса в сумме единица.
    outcome = WeightedGeometricAggregator().aggregate(dict.fromkeys(INDICATORS, level), weights)

    assert outcome.score == pytest.approx(100.0 * level, abs=1e-3)
