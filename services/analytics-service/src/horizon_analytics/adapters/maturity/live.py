"""Проверка зрелости по живым открытым источникам: OpenAlex и английская Википедия.

Правило — то же, которым измерена точность на размеченном датасете
(:func:`horizon_analytics.validation.classifier.mainstream_reason`): больше трёх тысяч научных
работ за три года либо статья в Википедии старше пяти лет и длиннее тридцати тысяч знаков.
Продукт и замер точности обязаны судить одинаково: иначе 0.80 на датасете ничего не говорит о
том, что увидит аналитик на открытом запросе.

Две оговорки, обе — в пользу «оставить».

* **Короткая аббревиатура по числу работ не судится.** Точная фраза `MCP` находит и Model
  Context Protocol, и десяток других MCP — число работ меряет омонимию, а не зрелость. Для неё
  остаётся только правило Википедии, у которого страница неоднозначности отсекается порогом
  длины.
* **Бюджет времени.** Проверка идёт сетью на каждую тему верхушки. Кончился бюджет — остальные
  темы не проверены и остаются: медленный OpenAlex не должен ни ронять анализ, ни молча
  выбрасывать темы.
"""

from __future__ import annotations

import re
import time
from collections.abc import Callable, Mapping, Sequence
from datetime import date

from horizon_analytics.domain.pipeline import MaturityVerdict
from horizon_analytics.observability import get_logger
from horizon_analytics.openalex_keys import keys
from horizon_analytics.validation.classifier import THRESHOLDS, mainstream_reason
from horizon_analytics.validation.sources import (
    SourceEvidence,
    collect_maturity_evidence,
    set_contact_email,
    set_openalex_api_key,
)

__all__ = ["LiveMaturityProbe"]

_LOGGER = get_logger(__name__)

#: Одно слово заглавными до пяти знаков — аббревиатура, у которой точный поиск меряет омонимию.
_SHORT_ACRONYM = re.compile(r"^[0-9]?[A-Z][A-Z0-9]{1,4}$")
#: Хвост «(SD)» у названия, раскрытого движком из корпуса: искать нужно расшифровку, а точная
#: фраза со скобкой не найдёт ничего и выдаст незрелость там, где её нет.
_ACRONYM_SUFFIX = re.compile(r"\s*\([0-9]?[A-Z][A-Z0-9]{1,5}s?\)\s*$")


class LiveMaturityProbe:
    """Реализация :class:`horizon_analytics.domain.ports.MaturityProbe` поверх живых источников."""

    def __init__(
        self,
        *,
        contact_email: str,
        budget_seconds: float = 120.0,
        openalex_api_key: str = "",
        today: Callable[[], date] = date.today,
        collect: Callable[[str], SourceEvidence] | None = None,
        cache_ttl_seconds: float = 24 * 3600.0,
    ) -> None:
        """Настроить проверку; ``collect`` подменяется в тестах, чтобы не ходить в сеть.

        Число работ в OpenAlex спрашивается, только если задан ключ: без ключа суточный бюджет —
        сто поисковых запросов на адрес, и проверка, спрашивающая о каждой теме, съедала его за
        один-два анализа, оставляя без OpenAlex сам сбор. Без ключа судит одна Википедия: на
        размеченных данных она ловит 53 из 74 зрелых против 61 вместе с OpenAlex.
        """
        set_contact_email(contact_email)
        set_openalex_api_key(openalex_api_key)
        self._uses_works = bool(keys.current(openalex_api_key))
        self._budget = budget_seconds
        self._today = today
        self._collect: Callable[[str], SourceEvidence] = collect or (
            lambda phrase: collect_maturity_evidence(phrase, works=self._uses_works)
        )
        # Кэш в процессе: одни и те же названия приходят в каждом анализе направления, а каждый
        # вопрос OpenAlex стоит денег из суточного бюджета. Сутки — срок, за который ответ о
        # зрелости не меняется.
        self._cache: dict[str, tuple[float, SourceEvidence]] = {}
        self._cache_ttl = cache_ttl_seconds

    @property
    def source_id(self) -> str:
        """Какие источники спрошены — уходит в трассу рядом с причиной исключения."""
        return "openalex+wikipedia.en" if self._uses_works else "wikipedia.en"

    def probe(self, titles: Sequence[str]) -> Mapping[str, object]:
        """Вернуть вердикты для тех названий, которые удалось проверить в пределах бюджета."""
        deadline = time.monotonic() + self._budget
        current_year = self._today().year
        verdicts: dict[str, MaturityVerdict] = {}
        skipped = 0
        for title in titles:
            if time.monotonic() >= deadline:
                skipped += 1
                continue
            phrase = _ACRONYM_SUFFIX.sub("", title).strip()
            if not phrase:
                continue
            evidence = self._cached(phrase)
            if _SHORT_ACRONYM.match(phrase):
                # Число работ по аббревиатуре меряет омонимию, а не зрелость — см. докстринг.
                evidence.works_by_year = {}
            if "wikipedia.en" in evidence.unavailable and (
                not self._uses_works or "openalex" in evidence.unavailable
            ):
                continue
            reason = mainstream_reason(evidence, current_year, THRESHOLDS)
            verdicts[title] = MaturityVerdict(mature=reason is not None, reason=reason or "")
        if skipped:
            # Не ошибка — оговорка: непроверенные темы остались в отчёте, и об этом должно быть
            # видно в журнале, иначе «проверено по внешним источникам» читалось бы как «всё».
            _LOGGER.warning("maturity.budget_exhausted", skipped=skipped, budget=self._budget)
        return verdicts

    def _cached(self, phrase: str) -> SourceEvidence:
        """Свидетельства о фразе — из кэша, если он свежий, иначе из источников."""
        now = time.monotonic()
        hit = self._cache.get(phrase)
        if hit is not None and now - hit[0] < self._cache_ttl:
            return hit[1]
        evidence = self._collect(phrase)
        # Отказ источника не кэшируется: следующий анализ обязан спросить снова.
        if not evidence.unavailable or (
            evidence.unavailable == ("openalex",) and not self._uses_works
        ):
            self._cache[phrase] = (now, evidence)
        return evidence
