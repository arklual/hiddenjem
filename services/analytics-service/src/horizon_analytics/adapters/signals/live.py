"""Реализация :class:`~horizon_analytics.domain.ports.SignalsProbe` поверх открытых источников.

Здесь сходятся две уже существующие вещи: пакетный сбор признаков
(:func:`horizon_analytics.signals.collect_signals`) и перевод собранного в вектор модели
(:func:`horizon_analytics.signals.features.external_features`). Своего у адаптера — три решения.

* **Только быстрые источники.** OpenAlex, Википедия и Hacker News тратят на термин один-три
  запроса; полторы сотни кандидатов проходят за минуту-две, и это укладывается в анализ. arXiv и
  GitHub считают лимит в запросах в минуту — полторы сотни терминов стоили бы два с половиной
  часа, и признак «а сколько по теме репозиториев» обошёлся бы дороже всего отчёта.
* **Кэш обязателен.** Второй анализ того же направления не платит за сбор ничего, а прогон на
  предзаполненном кэше воспроизводим до последнего знака — на этом стоит проверка движка.
* **Ничего не бросает.** Отдельный источник отказывает внутри сбора и оседает в поле; сюда
  долетает только то, что сломалось насмерть, — и даже это означает «темы посчитаны по корпусной
  части», а не «анализ не состоялся». Отчёт без внешних признаков хуже полного, но он выходит и
  честно помечен.
"""

from __future__ import annotations

from collections.abc import Callable, Mapping, Sequence
from datetime import UTC, date, datetime, time
from pathlib import Path
from typing import Any, Final

from horizon_analytics.domain.models import Author, Document, SourceClass, Venue
from horizon_analytics.observability import get_logger
from horizon_analytics.signals import (
    DEFAULT_YEARS,
    FAST_SOURCES,
    TermRequest,
    TermSignals,
    collect_signals,
)
from horizon_analytics.signals.examples import collect_examples
from horizon_analytics.signals.features import external_features
from horizon_analytics.signals.sources import openalex

__all__ = ["DEFAULT_BUDGET_SECONDS", "DEFAULT_TERM_BUDGET_SECONDS", "LiveSignalsProbe"]

_LOGGER = get_logger(__name__)

#: Потолок времени на весь опрос внешних источников в одном анализе.
#:
#: Пять минут — это вчетверо больше замеренного времени полутора сотен кандидатов по быстрым
#: источникам (`REQUESTS_PER_TERM`) и вчетверо меньше того, за что аналитик перестаёт ждать
#: отчёт. Число выбрано так, чтобы оно срабатывало только при настоящей беде, — и чтобы при ней
#: анализ всё-таки заканчивался.
DEFAULT_BUDGET_SECONDS: Final[float] = 300.0

#: Потолок на один термин у одного источника. Три запроса по сорок пять секунд с повторами — это
#: почти четыре минуты на один термин из полутораста, то есть бюджет этапа, съеденный одним
#: кандидатом.
DEFAULT_TERM_BUDGET_SECONDS: Final[float] = 30.0

#: Тип работы OpenAlex → класс источника методологии. Список закрыт, и незнакомый тип попадает в
#: ``JOURNAL_ARTICLE``: OpenAlex — каталог научной литературы, и всё, что он отдаёт по запросу
#: работ, научная публикация того или иного рода. Отдельный класс для «неизвестно» был бы
#: честнее, но его нет в контракте, а заводить его ради трёх редких типов — менять контракт ради
#: разметки, которую никто не читает.
_SOURCE_CLASSES: Final[Mapping[str, SourceClass]] = {
    "preprint": "PREPRINT",
    "article": "JOURNAL_ARTICLE",
    "review": "JOURNAL_ARTICLE",
    "book-chapter": "JOURNAL_ARTICLE",
    "proceedings-article": "JOURNAL_ARTICLE",
    "report": "ANALYST_REPORT",
    "standard": "STANDARD",
}


class LiveSignalsProbe:
    """Внешние признаки по фразам: кэш перед сетью, отказ — пропущенный признак."""

    def __init__(
        self,
        *,
        cache_dir: Path,
        sources: Sequence[str] = FAST_SOURCES,
        years: Sequence[int] = DEFAULT_YEARS,
        cache_only: bool = False,
        budget_seconds: float = DEFAULT_BUDGET_SECONDS,
        term_budget_seconds: float = DEFAULT_TERM_BUDGET_SECONDS,
        today: Callable[[], date] = date.today,
        collect: Callable[..., list[TermSignals]] = collect_signals,
        examples: Callable[..., dict[str, list[dict[str, Any]]]] = collect_examples,
    ) -> None:
        """Настроить сбор.

        Args:
            cache_dir: Каталог кэша — общий для всех анализов развёртывания.
            sources: Какие источники спрашивать; по умолчанию быстрые.
            years: Окно наблюдения — то же, в котором собиралась обучающая выборка. Разойдясь с
                ней, признаки перестанут значить то, на чём учились веса.
            cache_only: Не ходить в сеть: воспроизводимый прогон по уже собранному.
            budget_seconds: Потолок времени на весь опрос. Он не «на всякий случай»: анализ
                обязан закончиться, а чужой сервер ничего нам не обещал. Кандидаты, до которых
                не дошла очередь, считаются без внешних признаков и помечены — ровно как те, о
                ком источник промолчал.
            term_budget_seconds: Потолок на один термин у одного источника. Без него один
                повисший термин съедает бюджет всех остальных.
            today: Дата сбора; подменяется в тестах.
            collect: Пакетный сбор — параметр компоновки, а не подмены: боевой путь остаётся
                :func:`~horizon_analytics.signals.collect_signals`.
            examples: Сбор доказательной базы; по той же причине параметр, а не константа.
        """
        self._cache_dir = Path(cache_dir)
        self._sources = tuple(sources)
        self._years = tuple(years)
        self._cache_only = cache_only
        self._budget = budget_seconds
        self._term_budget = term_budget_seconds
        self._today = today
        self._collect = collect
        self._examples = examples

    @property
    def source_id(self) -> str:
        """Какие источники спрошены — уходит в журнал рядом с числом собранных тем."""
        return "+".join(self._sources) if self._sources else "none"

    def works(self, terms: Sequence[str]) -> Mapping[str, Sequence[Document]]:
        """Несколько настоящих работ на фразу — доказательная база темы, предложенной моделью.

        Спрашивается отдельным запросом и только о подтверждённых именах: счётчики признаков
        отвечают «сколько», а отчёту нужно «что именно», со ссылкой, датой и авторами.
        """
        wanted = [term for term in dict.fromkeys(terms) if term.strip()]
        if not wanted:
            return {}
        try:
            collected = self._examples(
                wanted,
                cache_dir=self._cache_dir,
                years=self._years,
                today=self._today(),
                cache_only=self._cache_only,
                # Доказательная база собирается после признаков и из того же бюджета этапа не
                # вычитается: имён здесь единицы, а запрос на имя — один.
                budget_seconds=self._budget,
                term_budget_seconds=self._term_budget,
            )
        # Та же причина, что и у сбора признаков: отсутствие доказательств означает тему без
        # доказательств — она не будет опубликована, — а не отказ анализа.
        except Exception as error:
            _LOGGER.warning("signals.works_failed", error=str(error), terms=len(wanted))
            return {}
        # Момент сбора берётся у часов пробы, а не у системных: два прогона по одному кэшу
        # обязаны давать одинаковые документы, иначе воспроизводимость держится на том, что
        # никто не сравнивает.
        fetched_at = datetime.combine(self._today(), time(), tzinfo=UTC)
        out: dict[str, Sequence[Document]] = {}
        for term, rows in collected.items():
            documents = tuple(
                document
                for document in (_document(row, fetched_at=fetched_at) for row in rows)
                if document
            )
            if documents:
                out[term] = documents
        _LOGGER.info("signals.works", terms=len(wanted), found=len(out))
        return out

    def features(self, terms: Sequence[str]) -> Mapping[str, Mapping[str, float]]:
        """Собрать признаки для фраз; фраза без данных в ответе просто не появится."""
        wanted = [term for term in dict.fromkeys(terms) if term.strip()]
        if not wanted or not self._sources:
            return {}
        try:
            collected = self._collect(
                [TermRequest(term) for term in wanted],
                cache_dir=self._cache_dir,
                years=self._years,
                today=self._today(),
                sources=self._sources,
                cache_only=self._cache_only,
                budget_seconds=self._budget,
                term_budget_seconds=self._term_budget,
            )
        # Сбор ловит отказы источников сам; сюда долетает только сломавшееся насмерть — и это
        # по-прежнему не повод отменять анализ. Голая `Exception` намеренна: перечислить всё, чем
        # способны упасть пять чужих API, нельзя, а любой пропущенный вид отказа означал бы отчёт,
        # не вышедший вовсе.
        except Exception as error:
            _LOGGER.warning("signals.collect_failed", error=str(error), terms=len(wanted))
            return {}
        out: dict[str, Mapping[str, float]] = {}
        failed = 0
        for item in collected:
            values = external_features(item)
            failed += int(not values)
            if values:
                out[item.term] = values
        _LOGGER.info(
            "signals.collected",
            source=self.source_id,
            terms=len(wanted),
            without_features=failed,
            cache_only=self._cache_only,
        )
        return out


def _document(row: Mapping[str, Any], *, fetched_at: datetime) -> Document | None:
    """Собрать документ из одной работы OpenAlex; неполную работу — отбросить.

    Отбрасывается молча и намеренно: работа без даты или без имени не годится в доказательство,
    а достроить недостающее — значит выдумать наблюдение. Если таких окажутся все, тема останется
    без доказательной базы и не будет опубликована; это правильный исход, а не потеря.
    """
    identifier = str(row.get("id") or "").strip()
    title = str(row.get("title") or "").strip()
    published = str(row.get("published_on") or "").strip()
    if not identifier or not title or len(published) != 10:
        return None
    try:
        published_on = date.fromisoformat(published)
    except ValueError:
        return None
    venue = str(row.get("venue") or "").strip()
    authors = tuple(
        Author(
            full_name=str(item.get("name") or "").strip(),
            organization_name=str(item.get("organization") or "").strip() or None,
            organization_country=str(item.get("country") or "").strip() or None,
        )
        for item in (row.get("authors") or [])
        if isinstance(item, Mapping) and str(item.get("name") or "").strip()
    )
    return Document(
        # Ключ источника в идентификаторе не для красоты: документ приходит не из снапшота, и
        # совпадение с корпусным идентификатором подменило бы один документ другим.
        document_id=f"{openalex.EXAMPLES_NAME}:{identifier}",
        source_id="openalex",
        source_class=_SOURCE_CLASSES.get(str(row.get("type") or "").strip(), "JOURNAL_ARTICLE"),
        external_id=identifier,
        title=title,
        published_on=published_on,
        url=str(row.get("url") or "").strip(),
        fetched_at=fetched_at,
        abstract_text=str(row.get("abstract") or "").strip() or None,
        language="en",
        doi=str(row.get("doi") or "").strip() or None,
        venue=Venue(name=venue) if venue else None,
        authors=authors,
        citation_count=int(row.get("cited_by_count") or 0),
        # Тема найдена точной фразой: работа относится к ней целиком, а не «отчасти». Это не
        # оценка, а свойство запроса.
        relevance=1.0,
    )
