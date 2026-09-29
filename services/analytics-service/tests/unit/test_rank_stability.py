"""Устойчивость места к весам: измеряется то, что обещано, и тем же счётом, что в отчёте."""

from __future__ import annotations

from itertools import pairwise

import pytest

from horizon_analytics.domain.models import INDICATOR_NAMES
from horizon_analytics.domain.scoring.aggregators import WeightedGeometricAggregator
from horizon_analytics.domain.scoring.profile import MethodologyProfile
from horizon_analytics.domain.scoring.stability import (
    RankingCandidate,
    RankStability,
    rank_stability,
    reweighting_scenarios,
)

AGGREGATOR = WeightedGeometricAggregator()


def candidate(key: str, **values: float) -> RankingCandidate:
    """Кандидат, у которого все индикаторы средние, кроме названных."""
    filled = {name: values.get(name, 0.5) for name in INDICATOR_NAMES}
    return RankingCandidate(trend_key=key, values=filled, burst_weight=0.0)


def weights() -> dict[str, float]:
    return {str(name): float(value) for name, value in MethodologyProfile.default().weights.items()}


class TestScenarios:
    def test_covers_every_indicator_in_both_directions(self) -> None:
        names = [name for name, _ in reweighting_scenarios(weights())]

        assert names[0] == "equal"
        for indicator in INDICATOR_NAMES:
            assert f"{indicator}:louder" in names
            assert f"{indicator}:quieter" in names

    def test_every_scenario_still_sums_to_one(self) -> None:
        # Иначе сценарий менял бы не важность индикатора, а масштаб балла целиком, и «место при
        # другом взвешивании» означало бы другое.
        for _, perturbed in reweighting_scenarios(weights()):
            assert sum(perturbed.values()) == pytest.approx(1.0)

    def test_keeps_every_weight_positive(self) -> None:
        # Нулевой вес выключил бы индикатор: x⁰ = 1 даже при x = 0, и тема с нулевым индикатором
        # перестала бы обнуляться. Это отменило бы BRULE-4 — правило, а не параметр.
        for _, perturbed in reweighting_scenarios(weights()):
            assert min(perturbed.values()) > 0.0

    def test_is_reproducible_without_a_seed(self) -> None:
        assert reweighting_scenarios(weights()) == reweighting_scenarios(weights())


class TestStability:
    def test_a_topic_leading_on_every_indicator_never_moves(self) -> None:
        leader = candidate(
            "лидер", novelty=0.9, growth=0.9, diffusion=0.9, weakness=0.9, coherence=0.9, impact=0.9
        )
        others = [candidate(f"тема-{index}") for index in range(4)]

        rows = {
            row.trend_key: row for row in rank_stability([leader, *others], weights(), AGGREGATOR)
        }

        assert (rows["лидер"].best, rows["лидер"].worst) == (1, 1)

    def test_a_topic_carried_by_one_indicator_moves_when_that_weight_moves(self) -> None:
        # Ровно тот случай, ради которого всё считается: тема держится на одном показателе, и её
        # место — следствие того, насколько важным этот показатель назначили.
        # Значения подобраны так, что темы стоят вплотную: узкая ведёт при базовом взвешивании и
        # уступает, как только рост объявляют менее важным. Одна высокая оценка при геометрическом
        # среднем далеко не вытягивает — оно для того и выбрано (BRULE-4).
        narrow = candidate(
            "узкая",
            growth=0.99,
            novelty=0.45,
            diffusion=0.45,
            weakness=0.45,
            coherence=0.45,
            impact=0.45,
        )
        broad = candidate(
            "широкая",
            growth=0.52,
            novelty=0.52,
            diffusion=0.52,
            weakness=0.52,
            coherence=0.52,
            impact=0.52,
        )

        rows = {
            row.trend_key: row for row in rank_stability([narrow, broad], weights(), AGGREGATOR)
        }

        assert (rows["узкая"].best, rows["узкая"].worst) == (1, 2)

    def test_the_range_contains_the_place_shown_in_the_report(self) -> None:
        # Базовое взвешивание входит в перебор. Иначе диапазон мог бы не содержать место, которое
        # аналитик видит в соседней колонке, и таблица противоречила бы сама себе.
        pool = [
            candidate("а", novelty=0.9),
            candidate("б", growth=0.9),
            candidate("в", impact=0.9),
        ]

        rows = rank_stability(pool, weights(), AGGREGATOR)

        for place, row in enumerate(rows, start=1):
            assert row.best <= place <= row.worst

    def test_orders_the_answer_by_the_report_ranking(self) -> None:
        pool = [candidate("отстающая", novelty=0.1), candidate("ведущая", novelty=0.9)]

        assert [row.trend_key for row in rank_stability(pool, weights(), AGGREGATOR)] == [
            "ведущая",
            "отстающая",
        ]

    def test_breaks_ties_the_way_the_report_does(self) -> None:
        # При равных баллах порядок решают всплеск, затем новизна, затем ключ (§7, шаг 9). Другой
        # разрыв ничьей дал бы устойчивость не того порядка, который показан в отчёте.
        quiet = RankingCandidate(
            trend_key="аа", values=dict.fromkeys(INDICATOR_NAMES, 0.5), burst_weight=0.0
        )
        bursting = RankingCandidate(
            trend_key="яя", values=dict.fromkeys(INDICATOR_NAMES, 0.5), burst_weight=0.9
        )

        rows = rank_stability([quiet, bursting], weights(), AGGREGATOR)

        assert [row.trend_key for row in rows] == ["яя", "аа"]

    def test_holds_in_top_asks_about_the_worst_case_not_the_usual_one(self) -> None:
        # Тема, обычно занимающая третье место и уезжающая на четвёртое хотя бы в одном сценарии,
        # в ТОП-3 не держится. Утверждать иначе значило бы называть устойчивым то, что зависит от
        # выбора весов, — то есть ровно то, что здесь измеряется.
        row = RankStability(trend_key="пограничная", best=3, worst=4)

        assert row.holds_in_top(4) is True
        assert row.holds_in_top(3) is False

    def test_says_nothing_when_there_is_nothing_to_rank(self) -> None:
        assert rank_stability([], weights(), AGGREGATOR) == ()


class TestWhatTheMeasurementRestsOn:
    """Проверки самих допущений: без них измерение остаётся зелёным, перестав что-либо измерять."""

    def test_answers_in_the_order_of_the_report_not_of_a_scenario(self) -> None:
        # Пара подобрана так, что базовые веса и равные дают разный порядок. Без этого проверка
        # порядка ссылалась бы сама на себя: она сверялась бы с тем же списком, из которого взята,
        # и не заметила бы подмены базового взвешивания любым другим.
        heavy = candidate(
            "тяжёлые-показатели",
            novelty=0.9,
            growth=0.9,
            diffusion=0.35,
            weakness=0.35,
            coherence=0.35,
            impact=0.35,
        )
        even = candidate(
            "ровная",
            novelty=0.52,
            growth=0.52,
            diffusion=0.52,
            weakness=0.52,
            coherence=0.52,
            impact=0.52,
        )

        rows = rank_stability([heavy, even], weights(), AGGREGATOR)

        assert [row.trend_key for row in rows] == ["тяжёлые-показатели", "ровная"]

    def test_every_scenario_really_moves_the_indicator_it_names(self) -> None:
        # Сценарий, не меняющий вес, оставляет перебор из одних копий базового набора: все темы
        # оказываются идеально устойчивыми, и продукт заявляет надёжность, которой не проверял.
        base = weights()
        by_name = dict(reweighting_scenarios(base))

        for indicator in INDICATOR_NAMES:
            louder = by_name[f"{indicator}:louder"][indicator]
            quieter = by_name[f"{indicator}:quieter"][indicator]
            assert louder > base[indicator] > quieter

    def test_moves_only_the_indicator_it_names(self) -> None:
        # Остальные веса обязаны сохранить пропорции между собой: иначе сценарий «рост важнее»
        # менял бы заодно и всё прочее, а объяснение аналитику стало бы неправдой.
        base = weights()
        by_name = dict(reweighting_scenarios(base))
        others = [name for name in INDICATOR_NAMES if name != "growth"]

        perturbed = by_name["growth:louder"]

        for first, second in pairwise(others):
            assert perturbed[first] / perturbed[second] == pytest.approx(base[first] / base[second])
