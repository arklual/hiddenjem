"""Решение «слабый сигнал или нет» по живым открытым источникам.

Постановка отличается от конвейера, и различие принципиально. Конвейер ранжирует темы **внутри
направления**: там есть корпус, и всякая величина нормируется на него — «слабость» считается
относительно самой громкой темы направления, мейнстрим — относительно квантиля объёмов. Здесь
корпуса нет: на вход приходит имя технологии, и ответ должен быть абсолютным.

Поэтому пороги здесь абсолютные, и каждый из них **назван словами до того, как измерен**:

* *мейнстрим* — о технологии выходит больше трёх тысяч научных работ за последние три года, либо
  о ней есть статья в Википедии старше пяти лет и длиннее тридцати тысяч знаков. Оба утверждения
  сформулированы так, что их можно оспорить по смыслу, а не по числу: аналитик, называющий
  технологию зарождающейся при трёх тысячах работ в год и зрелой энциклопедической статье, не
  прав независимо от того, что показывает наш замер;
* *доказанность* — след технологии виден хотя бы в двух независимых видах источников;
* *зарождение* — след появился недавно и растёт.

**Пороги не подбирались под датасет.** Это существенно для честности замера: датасет размечен
методологами заказчика и используется здесь **только как разметка для сверки**. Подбор порогов по
нему превратил бы проверку в подгонку, а метрику — в украшение. Единственный источник чисел —
смысл утверждений выше; их устойчивость измеряется отдельно (`--sensitivity`), и если результат
держится только при одном значении порога, об этом будет сказано вслух.
"""

from __future__ import annotations

import math
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Final, Literal

from horizon_analytics.domain.models import clamp01
from horizon_analytics.validation.sources import SourceEvidence

__all__ = [
    "THRESHOLDS",
    "Decision",
    "Thresholds",
    "classify",
    "mainstream_reason",
]

Verdict = Literal["WEAK_SIGNAL", "MAINSTREAM", "INSUFFICIENT_EVIDENCE"]


@dataclass(frozen=True, slots=True)
class Thresholds:
    """Пороги решения. Каждый назван словами в докстринге модуля."""

    #: Научных работ за последние три года, выше которых тема уже не «слабый сигнал».
    mainstream_recent_works: int = 3000
    #: Возраст статьи в Википедии (лет) и её длина (знаков), вместе означающие зрелость.
    mainstream_wikipedia_age: int = 5
    mainstream_wikipedia_length: int = 30_000
    #: Сколько независимых видов источников должны знать о технологии.
    minimum_source_kinds: int = 2
    #: Сколько лет назад след должен был появиться, чтобы тема считалась зарождающейся.
    emergence_window_years: int = 6
    #: Доля свежих следов. Осталась в отчёте как диагностика и порогом больше не служит:
    #: правило «старше окна зарождения» строже и не требует второго условия. Убрать её из
    #: наблюдений было бы потерей — она объясняет, почему тема с давним следом всё-таки жива.
    minimum_recent_share: float = 0.25


THRESHOLDS: Final[Thresholds] = Thresholds()

#: Значение индикатора роста, отвечающее наклону β = 0.20 — порогу стадии «зарождающаяся» в
#: методологии §6. Здесь он означает «тема не долгая ниша, а растущая»; своего числа у этого
#: правила нет, и это принципиально: два места продукта не должны называть рост по-разному.
_EMERGING_GROWTH: Final[float] = 0.20 / math.log(3.0)


@dataclass(frozen=True, slots=True)
class Decision:
    """Вердикт вместе с числами, которые его произвели, и объяснением словами."""

    verdict: Verdict
    #: Уверенность в диапазоне [0,1] — доказательная база, а не вероятность класса.
    confidence: float
    #: Значения индикаторов методологии, посчитанные по абсолютной шкале.
    indicators: Mapping[str, float]
    #: Величины, решившие исход.
    evidence: Mapping[str, float | int | str | None]
    #: Почему именно так — по-русски, одной фразой.
    explanation: str

    @property
    def is_weak_signal(self) -> bool:
        """Классификация, которую сверяют с разметкой."""
        return self.verdict == "WEAK_SIGNAL"


def _source_kinds(evidence: SourceEvidence) -> int:
    """Сколько независимых видов источников знают о технологии.

    Медиа добавлены после первого замера, и без них он систематически терял именно те сигналы,
    ради которых существует: датасет методологов держится на отраслевых публикациях и
    объявлениях компаний — раунд, выход из stealth, первый аудит, — а этого следа нет ни в
    OpenAlex, ни на arXiv, ни в репозиториях. Двадцать положительных строк из ста получали
    вердикт «следов слишком мало» при живой технологии.
    """
    kinds = 0
    if evidence.works_total > 0:
        kinds += 1
    if (evidence.preprints or 0) > 0:
        kinds += 1
    if (evidence.repositories or 0) > 0:
        kinds += 1
    if sum(evidence.stories_by_year.values()) > 0:
        kinds += 1
    if evidence.wikipedia:
        kinds += 1
    # Медиа — вид наравне с наукой, но с оговоркой, которую требует ТЗ: одна площадка даёт одно
    # свидетельство, сколько бы заметок она ни выпустила. Десять сайтов, перепечатавших один
    # релиз, независимыми не становятся, поэтому считаются домены, а не публикации.
    if len(evidence.news_domains) >= 2:
        kinds += 1
    return kinds


def _first_year(evidence: SourceEvidence) -> int | None:
    """Первый **достоверный** год технологии: первый, в котором о ней написали не однажды.

    Правило то же, что BRULE-2 в методологии, и по той же причине. Точная фраза способна совпасть
    с текстом другой эпохи о другом предмете: «in-sensor processing» находит одну работу 1990
    года, «model serving» — одну 1990-го, и единичное совпадение сдвигало возраст темы на
    тридцать лет. Замер первой версии: десять положительных строк из ста получили вердикт
    «известна с 1990-х» при технологии, появившейся в 2024-м.

    Два документа в одном году — минимальная планка, при которой совпадение перестаёт быть
    случайным. Планка не подбиралась по датасету: она уже действует в конвейере, и величина
    обязана значить в обеих постановках одно и то же.

    Когда достоверного года нет вовсе (везде по одному упоминанию), возвращается самый ранний из
    имеющихся: тема с тонким следом должна выглядеть тонкой, а не молодой.
    """
    credible = [year for year, count in evidence.works_by_year.items() if count >= 2]
    credible.extend(year for year, count in evidence.stories_by_year.items() if count >= 2)
    credible.extend(year for year, count in evidence.news_by_year.items() if count >= 2)
    if (evidence.preprints or 0) >= 2 and evidence.preprint_first_year:
        credible.append(evidence.preprint_first_year)
    if (evidence.repositories or 0) >= 2 and evidence.repository_first_year:
        credible.append(evidence.repository_first_year)
    plausible = [year for year in credible if year >= 1990]
    if plausible:
        return min(plausible)
    thin = [year for year, count in evidence.works_by_year.items() if count > 0 and year >= 1990]
    thin.extend(year for year in evidence.stories_by_year if year >= 1990)
    thin.extend(year for year in evidence.news_by_year if year >= 1990)
    return min(thin) if thin else None


def _growth(evidence: SourceEvidence, current_year: int) -> float:
    """Лог-линейный наклон числа работ по годам, нормированный на утроение за год.

    Формула та же, что в методологии §3.2, и это не совпадение: индикатор обязан значить одно и
    то же в обеих постановках, иначе объяснение в карточке и объяснение в отчёте о качестве
    противоречат друг другу.
    """
    window = [
        (
            year,
            evidence.works_by_year.get(year, 0)
            + evidence.stories_by_year.get(year, 0)
            + evidence.news_by_year.get(year, 0),
        )
        for year in range(current_year - 6, current_year + 1)
    ]
    points = [(year, count) for year, count in window if count > 0]
    if len(points) < 2:
        return 0.0
    mean_x = sum(year for year, _ in points) / len(points)
    mean_y = sum(math.log1p(count) for _, count in points) / len(points)
    numerator = sum((year - mean_x) * (math.log1p(count) - mean_y) for year, count in points)
    denominator = sum((year - mean_x) ** 2 for year, _ in points)
    if denominator == 0.0:
        return 0.0
    slope = numerator / denominator
    return clamp01(max(0.0, slope) / math.log(3.0))


def _recent_share(evidence: SourceEvidence, current_year: int) -> float:
    """Доля следов, приходящихся на последние три года."""
    total = (
        evidence.works_total
        + sum(evidence.stories_by_year.values())
        + sum(evidence.news_by_year.values())
    )
    if total == 0:
        return 0.0
    recent = (
        evidence.works_since(current_year - 2)
        + sum(count for year, count in evidence.stories_by_year.items() if year >= current_year - 2)
        + sum(count for year, count in evidence.news_by_year.items() if year >= current_year - 2)
    )
    return recent / total


def _mainstream_reason(
    evidence: SourceEvidence, current_year: int, thresholds: Thresholds
) -> str | None:
    """Почему технология уже не слабый сигнал; ``None`` — оснований нет."""
    recent_works = evidence.works_since(current_year - 2)
    if recent_works >= thresholds.mainstream_recent_works:
        return (
            f"о технологии вышло {recent_works} научных работ за три года "
            f"при пороге {thresholds.mainstream_recent_works}"
        )
    article = evidence.wikipedia.get("en")
    if article is not None:
        created, length = article
        age = current_year - created
        if (
            age >= thresholds.mainstream_wikipedia_age
            and length >= thresholds.mainstream_wikipedia_length
        ):
            return (
                f"о технологии есть энциклопедическая статья {created} года "
                f"объёмом {length // 1000} тыс. знаков"
            )
    return None


def classify(
    evidence: SourceEvidence,
    *,
    current_year: int,
    thresholds: Thresholds = THRESHOLDS,
) -> Decision:
    """Отнести наблюдение к слабым сигналам или объяснить, почему нет."""
    kinds = _source_kinds(evidence)
    first_year = _first_year(evidence)
    age = None if first_year is None else max(0, current_year - first_year)
    novelty = 0.0 if age is None else clamp01(math.exp(-age / 3.0))
    growth = _growth(evidence, current_year)
    recent_share = _recent_share(evidence, current_year)
    recent_works = evidence.works_since(current_year - 2)

    # «Слабость» на абсолютной шкале: десять тысяч работ за три года — тема, о которой говорят все.
    # Логарифм, а не отношение: разница между десятью и сотней работ содержательна, между
    # девятью и десятью тысячами — нет.
    loudness = clamp01(math.log1p(recent_works) / math.log1p(10_000))
    weakness = clamp01(1.0 - loudness)

    diversity = clamp01(kinds / 4.0)
    span = len([count for count in evidence.works_by_year.values() if count > 0])
    confidence = clamp01(
        0.45 * clamp01(math.log1p(kinds) / math.log1p(4))
        + 0.30
        * clamp01(math.log1p(evidence.works_total + (evidence.repositories or 0)) / math.log1p(50))
        + 0.25 * clamp01(span / 4.0)
    )

    indicators = {
        "novelty": round(novelty, 6),
        "growth": round(growth, 6),
        "weakness": round(weakness, 6),
        "diffusion": round(diversity, 6),
    }
    facts: dict[str, float | int | str | None] = {
        "query": evidence.effective_query or evidence.query,
        "worksTotal": evidence.works_total,
        "worksRecent": recent_works,
        "preprints": evidence.preprints,
        "repositories": evidence.repositories,
        "stories": sum(evidence.stories_by_year.values()),
        "wikipediaEn": None if "en" not in evidence.wikipedia else evidence.wikipedia["en"][0],
        "wikipediaEnLength": (
            None if "en" not in evidence.wikipedia else evidence.wikipedia["en"][1]
        ),
        "firstYear": first_year,
        "sourceKinds": kinds,
        "recentShare": round(recent_share, 4),
        "newsArticles": sum(evidence.news_by_year.values()),
        "newsDomains": len(evidence.news_domains),
    }

    # Объём и энциклопедическая статья — тоже утверждения о найденной фразе. Сокращённая фраза
    # называет родителя, и объём его литературы о кандидате не говорит ничего.
    mainstream = (
        _mainstream_reason(evidence, current_year, thresholds)
        if (not evidence.effective_query or evidence.effective_query == evidence.query)
        else None
    )
    if mainstream is not None:
        return Decision(
            verdict="MAINSTREAM",
            confidence=confidence,
            indicators=indicators,
            evidence=facts,
            explanation=f"не слабый сигнал: {mainstream}",
        )

    # Возраст — первый из пяти атрибутов эмерджентности по Rotolo, Hicks & Martin: radical
    # novelty. Технология, чей первый достоверный след старше окна зарождения, слабым сигналом не
    # является, даже если пишут о ней немного: это не зарождение, а долгая ниша. Замер первой
    # версии показал, что без этого правила ISO 20022 (2006), OAuth 2.0 (1998), SIEM (2002) и ещё
    # восемь заведомо не-сигналов проходили как слабые — просто потому, что научных работ о них
    # мало. Собственная формула методологии говорит то же самое: novelty(7 лет) = 0.097.
    #
    # Правило заменило прежнее, требовавшее вдобавок падения доли свежих следов: второе условие
    # смягчало первое ровно там, где оно нужнее всего — у зрелой темы, которую продолжают изучать.
    # Два условия, и оба — из смысла, а не из подбора.
    #
    # Первое: судить о возрасте можно только по той фразе, которую спросили. Когда точная фраза
    # не нашлась нигде и запрос пришлось сократить, найденный след принадлежит более широкому
    # понятию — родителю, а не кандидату. «Quantum-inspired model compression» сокращается до
    # «quantum-inspired», и 2011 год относится к нему, а не к сжатию моделей для edge.
    #
    # Второе: старое имя и новая волна — разные вещи. Тема, которая растёт со скоростью не ниже
    # порога стадии «зарождающаяся» (β > 0.20, то есть growth > ln-нормированные 0.182), не
    # является долгой нишей, сколько бы лет ни было её названию. Порог не новый: он уже
    # разделяет стадии жизненного цикла в методологии §6.
    grown_up = growth >= _EMERGING_GROWTH
    measured_on_the_asked_phrase = (
        not evidence.effective_query or evidence.effective_query == evidence.query
    )
    if (
        age is not None
        and age > thresholds.emergence_window_years
        and measured_on_the_asked_phrase
        and not grown_up
    ):
        return Decision(
            verdict="MAINSTREAM",
            confidence=confidence,
            indicators=indicators,
            evidence=facts,
            explanation=(
                f"не слабый сигнал: первый достоверный след {first_year} года — тема существует "
                f"дольше окна зарождения в {thresholds.emergence_window_years} лет"
            ),
        )

    if kinds < thresholds.minimum_source_kinds:
        return Decision(
            verdict="INSUFFICIENT_EVIDENCE",
            confidence=confidence,
            indicators=indicators,
            evidence=facts,
            explanation=(
                f"следов технологии слишком мало: её знают {kinds} вид(а) источников "
                f"при требуемых {thresholds.minimum_source_kinds}"
            ),
        )

    parts = []
    if age is not None:
        parts.append(f"первый след {first_year} года")
    parts.append(
        f"{recent_works} научных работ за три года при пороге мейнстрима {thresholds.mainstream_recent_works}"
    )
    if "en" not in evidence.wikipedia:
        parts.append("энциклопедической статьи о ней нет")
    if growth > 0:
        parts.append(f"число упоминаний растёт (наклон {growth:.2f} от утроения за год)")
    return Decision(
        verdict="WEAK_SIGNAL",
        confidence=confidence,
        indicators=indicators,
        evidence=facts,
        explanation="слабый сигнал: " + ", ".join(parts),
    )


def summarize(decisions: Sequence[tuple[bool, Decision]]) -> Mapping[str, float]:
    """Precision, recall, F1 и доля верных — по парам «истинная метка, решение».

    Положительный класс — «слабый сигнал»: именно его точность требует ТЗ.
    """
    tp = sum(1 for truth, decision in decisions if truth and decision.is_weak_signal)
    fp = sum(1 for truth, decision in decisions if not truth and decision.is_weak_signal)
    fn = sum(1 for truth, decision in decisions if truth and not decision.is_weak_signal)
    tn = sum(1 for truth, decision in decisions if not truth and not decision.is_weak_signal)
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    total = tp + fp + fn + tn
    return {
        "truePositives": tp,
        "falsePositives": fp,
        "falseNegatives": fn,
        "trueNegatives": tn,
        "precision": precision,
        "recall": recall,
        "f1": f1,
        "accuracy": (tp + tn) / total if total else 0.0,
    }


def mainstream_reason(
    evidence: SourceEvidence, current_year: int, thresholds: Thresholds = THRESHOLDS
) -> str | None:
    """Почему технология уже не слабый сигнал; ``None`` — оснований нет.

    Открыто для продукта: внешняя проверка зрелости на открытом запросе обязана судить тем же
    правилом, которым измерена точность на датасете, а не его копией, которая разойдётся с ним
    при первой правке порога.
    """
    return _mainstream_reason(evidence, current_year, thresholds)
