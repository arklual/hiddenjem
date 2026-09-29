"""Качество подгонки не растёт оттого, что данных мало.

Прямая проходит через две точки точно: остатка нет по построению, и ``R² = 1.0`` там означает не
«рост описан хорошо», а «описывать было нечего». Компонент `fit` входит в уверенность с весом 0.20,
то есть продукт был тем увереннее, чем меньше у него данных.

Замер по эталонному корпусу подтвердил это числами:

| точек в ряду | тем | медианный сырой R² |
| --- | ---: | ---: |
| 2–3 | 20 | **0.992** |
| 6 и больше | 23 | 0.789 |

Поправка стандартная — скорректированный R² по степеням свободы. Ничего не подбирается: регрессия
тратит два параметра, и при двух точках остаточных степеней свободы ноль.
"""

from __future__ import annotations

from horizon_analytics.domain.models import TimeSeries
from horizon_analytics.domain.scoring.confidence import compute_confidence
from horizon_analytics.domain.scoring.profile import MethodologyProfile


def _series(counts: tuple[int, ...]) -> TimeSeries:
    periods = tuple(str(2018 + index) for index in range(len(counts)))
    return TimeSeries(
        periods=periods,
        df=counts,
        tf=counts,
        corpus_df=tuple(100 for _ in counts),
    )


def _confidence(counts: tuple[int, ...], r_squared: float) -> dict[str, object]:
    profile = MethodologyProfile.default()
    result = compute_confidence(
        document_frequency=sum(counts),
        source_class_count=2,
        r_squared=r_squared,
        series=_series(counts),
        parameters=profile.parameters,
        threshold=profile.confidence_threshold,
    )
    return dict(result.diagnostics)


class TestAPerfectFitOnTwoPointsIsWorthNothing:
    def test_two_points_give_no_credit_for_fit(self) -> None:
        # Ровно тот случай: R² = 1.0 по построению, а степеней свободы ноль.
        assert _confidence((3, 5), r_squared=1.0)["fit"] == 0.0

    def test_a_single_period_gives_no_credit_either(self) -> None:
        assert _confidence((4,), r_squared=1.0)["fit"] == 0.0

    def test_the_raw_value_stays_visible_next_to_the_corrected_one(self) -> None:
        # Иначе разбор индикатора роста показывал бы R² = 1.0, а уверенность — «подгонка 0.000»,
        # и объяснить расхождение читателю было бы нечем.
        diagnostics = _confidence((3, 5), r_squared=1.0)

        assert diagnostics["rSquaredRaw"] == 1.0
        assert diagnostics["fit"] == 0.0


class TestALongSeriesKeepsWhatItEarned:
    def test_a_perfect_fit_on_many_points_stays_perfect(self) -> None:
        assert _confidence((1, 2, 4, 8, 16, 32, 64), r_squared=1.0)["fit"] == 1.0

    def test_the_correction_costs_more_when_points_are_fewer(self) -> None:
        # Свойство поправки, а не подобранное число: одна и та же неточность подгонки весит тем
        # больше, чем короче ряд.
        short = _confidence((1, 2, 4), r_squared=0.9)["fit"]
        long = _confidence((1, 2, 4, 8, 16, 32), r_squared=0.9)["fit"]

        assert isinstance(short, float) and isinstance(long, float)
        assert short < long < 0.9 + 1e-9

    def test_the_correction_never_leaves_the_unit_interval(self) -> None:
        for counts, r2 in (((1, 2, 4), 0.0), ((1, 2, 4, 8), 0.05), ((5, 5, 5, 5, 5), 1.0)):
            value = _confidence(counts, r_squared=r2)["fit"]
            assert isinstance(value, float)
            assert 0.0 <= value <= 1.0
