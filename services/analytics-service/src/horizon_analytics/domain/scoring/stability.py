"""Устойчивость состава ТОП-N к весам индикаторов.

Веса шести индикаторов выбраны экспертно (§4). Это законный выбор, но всё же выбор, и он ничем
не подкреплён извне: другой методолог расставил бы иначе. Пока продукт молчит об этом, любой ответ
на вопрос «а если бы взвесили по-другому — список был бы тем же?» звучит как «доверьтесь нам».

Здесь на него отвечают измерением. Балл темы пересобирается при наборе разумных возмущений весов,
кандидаты переупорядочиваются, и для каждой темы записывается, какое место она занимает в лучшем и
худшем случае. Тема, остающаяся в ТОП-N при любом взвешивании, — вывод; тема, вылетающая при
удвоении одного веса, — следствие того, как считали. Разница существенна для решения, и скрывать её
означало бы продавать точность, которой нет.

**Почему перебор, а не Монте-Карло.** Случайные наборы весов дали бы красивое «в 87 % прогонов» и
потребовали бы зерна, оговорок о распределении и веры в него. Тринадцать именованных сценариев
воспроизводимы без зерна и каждый объясняется одной фразой: «равные веса», «рост вдвое важнее».
Аналитик может оспорить сценарий — с распределением Дирихле спорить нечем.

**Почему веса нигде не обнуляются.** Нулевой вес выключил бы индикатор целиком: ``x⁰ = 1`` даже при
``x = 0``, и тема с нулевым индикатором перестала бы обнуляться. Это отменило бы BRULE-4 — правило
методологии, а не параметр. Возмущения меняют важность индикатора, но не отменяют ни одного.

**Почему место, а не балл.** Балл нормирован внутри своего корпуса и между направлениями не
сравним (§12); разброс балла ничего не сказал бы читателю. Решение принимают по составу списка,
поэтому измеряется именно место.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass

from horizon_analytics.domain.models import INDICATOR_NAMES, IndicatorName
from horizon_analytics.domain.scoring.aggregators import ScoreAggregator

__all__ = [
    "RankStability",
    "RankingCandidate",
    "rank_stability",
    "reweighting_scenarios",
]

#: Во сколько раз сценарий меняет вес одного индикатора.
_LOUDER = 2.0
_QUIETER = 0.5


@dataclass(frozen=True, slots=True)
class RankingCandidate:
    """Кандидат в ТОП-N со всем, что нужно для повторного упорядочивания.

    Значения индикаторов уже посчитаны конвейером и от весов не зависят — меняется только то, как
    их складывают. Поэтому пересчёт не требует ни корпуса, ни векторов, ни повторного извлечения.
    """

    trend_key: str
    values: Mapping[IndicatorName, float]
    #: Вес всплеска и новизна участвуют в разрешении ничьих (§7, шаг 9) и должны участвовать здесь
    #: тоже: иначе устойчивость измерялась бы у другого порядка, чем показывает отчёт.
    burst_weight: float


@dataclass(frozen=True, slots=True)
class RankStability:
    """Место темы в лучшем и худшем из рассмотренных взвешиваний."""

    trend_key: str
    best: int
    worst: int

    def holds_in_top(self, top_n: int) -> bool:
        """Остаётся ли тема в ТОП-N при любом из сценариев."""
        return self.worst <= top_n


def reweighting_scenarios(
    weights: Mapping[IndicatorName, float],
) -> tuple[tuple[str, Mapping[IndicatorName, float]], ...]:
    """Именованные наборы весов: равные плюс по два на каждый индикатор.

    Порядок фиксирован и не зависит от порядка обхода словаря — от него зависит воспроизводимость
    результата, а она в этом продукте является требованием, а не удобством (ADR-0015).
    """
    names = tuple(name for name in INDICATOR_NAMES if name in weights)
    if not names:
        return ()
    equal = 1.0 / len(names)
    scenarios: list[tuple[str, Mapping[IndicatorName, float]]] = [
        ("equal", dict.fromkeys(names, equal))
    ]
    for name in names:
        for label, factor in (("louder", _LOUDER), ("quieter", _QUIETER)):
            perturbed = {other: float(weights[other]) for other in names}
            perturbed[name] = perturbed[name] * factor
            total = sum(perturbed.values())
            if total <= 0.0:  # pragma: no cover - профиль с нулевой суммой весов невозможен
                continue
            scenarios.append(
                (f"{name}:{label}", {key: value / total for key, value in perturbed.items()})
            )
    return tuple(scenarios)


def rank_stability(
    candidates: Sequence[RankingCandidate],
    weights: Mapping[IndicatorName, float],
    aggregator: ScoreAggregator,
    *,
    floor: float = 1e-9,
) -> tuple[RankStability, ...]:
    """Диапазон места каждого кандидата по всем сценариям взвешивания.

    Возвращается в порядке базового взвешивания, чтобы результат читался рядом с самим отчётом.
    Базовый набор включён в сценарии: без него тема могла бы получить диапазон, не содержащий её
    собственного места в отчёте, и таблица противоречила бы соседней.
    """
    if not candidates:
        return ()
    orders = [_order(candidates, weights, aggregator, floor=floor)]
    for _, perturbed in reweighting_scenarios(weights):
        orders.append(_order(candidates, perturbed, aggregator, floor=floor))

    places: dict[str, list[int]] = {candidate.trend_key: [] for candidate in candidates}
    for order in orders:
        for position, trend_key in enumerate(order, start=1):
            places[trend_key].append(position)
    return tuple(
        RankStability(trend_key=key, best=min(places[key]), worst=max(places[key]))
        for key in orders[0]
    )


def _order(
    candidates: Sequence[RankingCandidate],
    weights: Mapping[IndicatorName, float],
    aggregator: ScoreAggregator,
    *,
    floor: float,
) -> tuple[str, ...]:
    """Упорядочить кандидатов по правилу отчёта: балл ↓, всплеск ↓, новизна ↓, ключ ↑."""
    keyed = sorted(
        (
            -aggregator.aggregate(candidate.values, weights, floor=floor).score,
            -candidate.burst_weight,
            -float(candidate.values.get("novelty", 0.0)),
            candidate.trend_key,
        )
        for candidate in candidates
    )
    return tuple(row[3] for row in keyed)
