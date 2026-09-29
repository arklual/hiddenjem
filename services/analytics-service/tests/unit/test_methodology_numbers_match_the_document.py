"""Числа методологии в коде совпадают с теми, что названы в документе.

Документ `docs/03-methodology/01-emergence-methodology.md` — это то, что защищают перед комитетом:
на него ссылаются, когда спрашивают «откуда взялся балл». Пока код и документ расходятся молча,
защита опирается на текст, которого больше нет в коде.

Расхождение возможно и незаметно. Проверено пробой (`tools/probe.sh`): изменение
`min_document_frequency` с 2 на 3, `relevance_direction_lift` с 2.0 на 3.0 и порога уверенности с
0.40 на 0.30 не роняло ни одной проверки во всём наборе. Константы методологии не были закреплены
ничем — при том, что каждая из них меняет состав отчёта.

Здесь закрепляются ровно те числа, которые **названы в документе**, и каждое сопровождается ссылкой
на раздел. Это не сплошной снимок настроек: параметр, о котором документ молчит, менять можно, и
запрещать это тестом значило бы заморозить реализацию вместо методологии. Проверяется соответствие
двух источников, а не неизменность одного.

Как менять. Число в коде меняется вместе с числом в документе и версией методологии — иначе отчёты,
посчитанные до и после, сравнивать между собой нельзя, а `methodologyVersion` в контракте обещает
обратное.
"""

from __future__ import annotations

import pytest

from horizon_analytics.domain.scoring.profile import MethodologyParameters, MethodologyProfile

PARAMETERS = MethodologyParameters()
PROFILE = MethodologyProfile.default()


class TestIndicatorWeights:
    """Таблица «Вес по умолчанию w_k» — §«Агрегация», ES = 100 · Π x_k^w_k."""

    @pytest.mark.parametrize(
        ("indicator", "documented"),
        [
            ("novelty", 0.20),
            ("growth", 0.30),
            ("diffusion", 0.15),
            ("weakness", 0.15),
            ("coherence", 0.10),
            ("impact", 0.10),
        ],
    )
    def test_the_weight_matches_the_document(self, indicator: str, documented: float) -> None:
        assert PROFILE.weights[indicator] == pytest.approx(documented)

    def test_the_weights_sum_to_one_as_the_table_states(self) -> None:
        # Строка «Σ 1.00» в таблице. Веса, не дающие единицы, ломают и смысл геометрического
        # среднего: балл перестаёт быть в шкале индикаторов.
        assert sum(PROFILE.weights.values()) == pytest.approx(1.0)


class TestDiffusionAndCoherence:
    """Формулы §«Диффузия» и §«Связность» — веса слагаемых названы прямо."""

    def test_diffusion_weights(self) -> None:
        # diffusion(c) = 0.40·entropy + 0.35·orgBreadth + 0.25·venueBreadth
        assert PARAMETERS.diffusion_entropy_weight == pytest.approx(0.40)
        assert PARAMETERS.diffusion_org_weight == pytest.approx(0.35)
        assert PARAMETERS.diffusion_venue_weight == pytest.approx(0.25)

    def test_coherence_weights(self) -> None:
        # coherence(c) = 0.60 · clamp01(c_emb) + 0.40 · clamp01(c_npmi)
        assert PARAMETERS.coherence_embedding_weight == pytest.approx(0.60)
        assert PARAMETERS.coherence_npmi_weight == pytest.approx(0.40)

    def test_breadth_references(self) -> None:
        # O_ref = 50, V_ref = 25 — оба названы комментарием в формуле охвата.
        assert PARAMETERS.diffusion_org_ref == 50
        assert PARAMETERS.diffusion_venue_ref == 25


class TestConfidenceAndBurst:
    def test_confidence_weights(self) -> None:
        # confidence(c) = 0.35·evidence + 0.25·diversity + 0.20·fit + 0.20·span
        assert PARAMETERS.confidence_evidence_weight == pytest.approx(0.35)
        assert PARAMETERS.confidence_diversity_weight == pytest.approx(0.25)
        assert PARAMETERS.confidence_fit_weight == pytest.approx(0.20)
        assert PARAMETERS.confidence_span_weight == pytest.approx(0.20)

    def test_the_low_evidence_threshold(self) -> None:
        # «confidence < 0.40 → карточка помечается „низкая доказательная база" (BRULE-6)».
        # Число видно аналитику на карточке и в записке для комитета: сдвинув его, мы меняем не
        # расчёт, а то, о чём продукт предупреждает.
        assert PROFILE.confidence_threshold == pytest.approx(0.40)

    def test_the_burst_multiplier(self) -> None:
        # «burst(t) ⇔ r(t) ≥ s · λ0, s = 2.0» — Kleinberg в операционализации методологии.
        assert PARAMETERS.burst_scale == pytest.approx(2.0)
