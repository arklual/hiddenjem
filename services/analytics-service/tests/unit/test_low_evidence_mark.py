"""BRULE-6: пометка «низкая доказательная база» — и что она действительно ставится.

Порог 0.40 закреплён отдельной проверкой чисел методологии. Само поведение не проверялось ничем:
подмена по всему набору, включая эталонный корпус, показала, что пометку можно отключить целиком —
и в расчёте уверенности, и в результате движка — не уронив ни одного теста.

Это механизм честности продукта, а не украшение. Пометка говорит аналитику: балл посчитан верно, но
опирается на слишком малое или однородное свидетельство, и решение по нему принимать рано. Без неё
все темы выглядят одинаково надёжными — включая ту, что держится на двух препринтах одной
лаборатории.

Проверяется поведение по обе стороны порога и то, что диагностика согласована с пометкой: два
источника одной истины расходятся молча, а расходятся они здесь между тем, что читает человек, и
тем, что читает интерфейс.
"""

from __future__ import annotations

from horizon_analytics.domain.models import TimeSeries
from horizon_analytics.domain.scoring.confidence import compute_confidence
from horizon_analytics.domain.scoring.profile import MethodologyProfile

PROFILE = MethodologyProfile.default()
PARAMETERS = PROFILE.parameters
THRESHOLD = PROFILE.confidence_threshold


def confidence(
    *,
    documents: int,
    source_classes: int,
    r_squared: float,
    periods: int,
    with_data: int | None = None,
):
    """Уверенность темы. ``with_data`` — в скольких периодах тема вообще встречается.

    Пустые периоды — не деталь оснастки, а суть слабого сигнала: тема, мелькнувшая однажды за
    восемь лет, и тема, растущая все восемь, отличаются именно этим. Ряд из одного периода даёт
    полное покрытие и поднимает уверенность выше порога — на этом первая редакция проверки и
    оступилась.
    """
    filled = periods if with_data is None else with_data
    series = TimeSeries(
        periods=tuple(str(2018 + index) for index in range(periods)),
        df=tuple(1 if index < filled else 0 for index in range(periods)),
        tf=tuple(1 if index < filled else 0 for index in range(periods)),
        corpus_df=tuple(100 for _ in range(periods)),
    )
    return compute_confidence(
        document_frequency=documents,
        source_class_count=source_classes,
        r_squared=r_squared,
        series=series,
        parameters=PARAMETERS,
        threshold=THRESHOLD,
    )


class TestLowEvidenceMark:
    def test_a_thin_topic_is_marked(self) -> None:
        # Два документа одного класса источников, никакого роста и один период — ровно тот случай,
        # ради которого правило написано.
        result = confidence(documents=2, source_classes=1, r_squared=0.0, periods=8, with_data=1)

        assert result.value < THRESHOLD
        assert result.diagnostics["lowEvidence"] is True
        assert "низкая доказательная база" in result.explanation

    def test_a_well_supported_topic_is_not_marked(self) -> None:
        # Обратная граница, и она важнее первой: правило, помечающее всё подряд, гасит само себя —
        # оговорка под каждой темой перестаёт читаться.
        result = confidence(documents=200, source_classes=5, r_squared=0.95, periods=8)

        assert result.value >= THRESHOLD
        assert result.diagnostics["lowEvidence"] is False
        assert "низкая доказательная база" not in result.explanation

    def test_the_mark_follows_the_threshold_and_not_a_hard_coded_verdict(self) -> None:
        # Порог приходит параметром, и пометка обязана следовать за ним. Иначе «порог 0.40» в
        # документации и поведение продукта — два разных утверждения.
        series = TimeSeries(
            periods=tuple(str(2018 + index) for index in range(8)),
            df=(1,) + (0,) * 7,
            tf=(1,) + (0,) * 7,
            corpus_df=(100,) * 8,
        )
        common = {
            "document_frequency": 2,
            "source_class_count": 1,
            "r_squared": 0.0,
            "series": series,
            "parameters": PARAMETERS,
        }

        strict = compute_confidence(**common, threshold=1.0)
        lenient = compute_confidence(**common, threshold=0.0)

        assert strict.diagnostics["lowEvidence"] is True
        assert lenient.diagnostics["lowEvidence"] is False

    def test_the_explanation_agrees_with_the_diagnostics(self) -> None:
        # Человек читает объяснение, интерфейс — диагностику. Два источника одной истины расходятся
        # молча, и расхождение здесь означало бы, что отчёт спорит сам с собой.
        for documents, classes in ((2, 1), (200, 5)):
            result = confidence(
                documents=documents, source_classes=classes, r_squared=0.5, periods=4
            )
            marked_in_text = "низкая доказательная база" in result.explanation

            assert marked_in_text is result.diagnostics["lowEvidence"]
