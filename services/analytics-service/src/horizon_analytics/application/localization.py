"""Русский слой аналитической выдачи.

ТЗ формулирует требование дважды и в двух разных местах: «Интерфейс сервиса и вся
аналитическая выдача должны быть представлены на русском языке» и «Для зарубежного материала
сервис должен предоставить русскоязычное резюме или интерпретацию, сохранив оригинальное
название, ссылку, дату публикации и язык источника. При использовании автоматического перевода
или генеративного резюме это должно быть отмечено возле источника».

Отсюда три решения, которые определяют устройство этого модуля.

**Оригинал не заменяется, а дополняется.** Русское название лежит рядом с английским, а не
вместо него: ТЗ прямо требует сохранить оригинальное название, и аналитик, который пойдёт
проверять источник, должен искать в нём то слово, которое там написано.

**Модель называется.** Каждая русская строка несёт имя модели, которая её произвела, и вид
обработки — машинный перевод или генеративный пересказ. Это и требование ТЗ §3.1 о раскрытии
выбора модели, и единственный способ дать читателю понять, чему верить: перевод названия и
пересказ абстракта — разные по надёжности вещи.

**Разные строки переводятся разными моделями, и это измерено.** Названия технологий уходят в
генеративную модель, остальное — в модель-переводчик. Замер на десяти названиях: переводчик
даёт три приемлемых перевода из десяти («software bill of materials» → «чек материалов»),
генеративная модель — восемь. На связном тексте разница обратная по цене: генерация на
процессоре идёт со скоростью около шести токенов в секунду, и пересказ пятнадцати карточек
стоил бы минуты показа.

Отказ моделей означает отсутствие русского слоя, а не отказ отчёта: поле ``localization``
просто не заполняется, и интерфейс показывает оригинал.
"""

from __future__ import annotations

import re
from collections.abc import Mapping, Sequence
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field

from horizon_analytics.adapters.nlp.http import HttpNlpClient
from horizon_analytics.domain.pipeline import PipelineResult, TrendOutcome
from horizon_analytics.observability import get_logger

__all__ = ["LocalizationService", "TrendLocalization"]

_LOGGER = get_logger(__name__)

#: Вид обработки, который отмечается возле строки. ТЗ различает «автоматический перевод» и
#: «генеративное резюме», и различие несёт разную надёжность: перевод обязан сохранить смысл
#: исходной строки, пересказ — только опираться на неё.
TRANSLATION_MACHINE = "MACHINE"
TRANSLATION_GENERATIVE = "GENERATIVE"

def _mode_of(model: str | None) -> str:
    """Вид обработки по модели, которая ответила: переводчик Marian — машинный, иначе генеративный.

    Сервис моделей переводит на русский генеративной моделью, а к переводчику возвращается, только
    если она недоступна; подпись возле текста обязана назвать то, что было на самом деле.
    """
    return TRANSLATION_MACHINE if (model or "").startswith("Helsinki-NLP") else TRANSLATION_GENERATIVE


#: Стадии по-русски — для входа модели, которая формулирует тренд.
_STAGE_RU = {
    "EMBRYONIC": "зачаточная",
    "EMERGING": "зарождающаяся",
    "ACCELERATING": "ускоряющаяся",
    "MATURING": "зрелая",
}

#: Кириллица в строке. Строку, которая уже по-русски, переводить нельзя ни с какого языка.
_CYRILLIC = re.compile(r"[а-яёА-ЯЁ]")


@dataclass(frozen=True, slots=True)
class TrendLocalization:
    """Русские строки одной карточки вместе с раскрытием их происхождения."""

    title: str | None = None
    #: Модель и вид обработки названия.
    title_model: str | None = None
    title_mode: str | None = None
    definition: str | None = None
    problem: str | None = None
    benefit: str | None = None
    #: Русские названия источников, по порядку элементов ``evidence``.
    evidence_titles: tuple[str, ...] = ()
    #: Модель и вид обработки всего остального (связный текст).
    text_model: str | None = None
    text_mode: str | None = None
    #: Тема, сформулированная как тренд, — одно русское предложение о том, что меняется. Всегда
    #: генеративный пересказ по фрагментам источников и ряду по годам; модель называется рядом.
    statement: str | None = None
    statement_model: str | None = None

    @property
    def empty(self) -> bool:
        """Нечего показывать: ни одна модель не ответила."""
        return (
            self.title is None
            and self.definition is None
            and not self.evidence_titles
            and self.statement is None
        )

    def to_dict(self) -> dict[str, object]:
        """Форма для контракта ``DomainAnalyzed``; пустые поля не публикуются."""
        payload: dict[str, object] = {}
        if self.title:
            payload["title"] = self.title
            payload["titleModel"] = self.title_model
            payload["titleMode"] = self.title_mode
        if self.definition:
            payload["definition"] = self.definition
        if self.problem:
            payload["problem"] = self.problem
        if self.benefit:
            payload["benefit"] = self.benefit
        if self.evidence_titles:
            payload["evidenceTitles"] = list(self.evidence_titles)
        if self.definition or self.problem or self.benefit or self.evidence_titles:
            payload["textModel"] = self.text_model
            payload["textMode"] = self.text_mode
        if self.statement:
            payload["statement"] = self.statement
            payload["statementModel"] = self.statement_model
        return payload


@dataclass(slots=True)
class LocalizationService:
    """Собирает русский слой для готового результата конвейера.

    Порядок вызовов фиксирован и минимален по числу обращений к моделям: один запрос на все
    названия и один — на весь связный текст отчёта. Пятнадцать отдельных запросов стоили бы
    пятнадцать прогревов контекста, а выигрыша не дали бы никакого.
    """

    client: HttpNlpClient
    #: Переводить ли связный текст. Отдельный выключатель: перевод названий дешёв и почти всегда
    #: полезен, а перевод абстрактов моделью-переводчиком заметно грубее и кому-то может быть не
    #: нужен.
    translate_text: bool = True
    #: Формулировать ли темы трендами. Отдельный выключатель: это третий вызов генеративной
    #: модели, и его можно отключить, не теряя переводов.
    formulate_trends: bool = True
    _cache: dict[str, str] = field(default_factory=dict)

    def localize(
        self, result: PipelineResult, *, direction: str = ""
    ) -> Mapping[str, TrendLocalization]:
        """Вернуть ``{trend_key: локализация}``; при отказе моделей — пустое отображение."""
        outcomes = list(result.trends)
        if not outcomes:
            return {}
        text_model: str | None = None
        translated_text: tuple[str, ...] = ()
        segments: list[str] = []
        spans: list[tuple[int, int]] = []
        to_translate: list[str] = []
        if self.translate_text:
            for outcome in outcomes:
                start = len(segments)
                segments.extend(self._text_segments(outcome))
                spans.append((start, len(segments)))
            # Уже русские строки в переводчик не идут: он их портит. Место в списке
            # сохраняется — разбор обратно идёт по срезу, и сдвиг переставил бы поля местами.
            to_translate = [text if self._needs_translation(text) else "" for text in segments]
        # Названия (генеративная модель) и тексты (машинный переводчик) — разные модели и
        # независимые вызовы: одновременно, а не друг за другом. На стенде они шли 14 с и 40 с
        # подряд (разбор 106).
        # Формулировка трендов — третий независимый вызов, тоже параллельно: она опирается на
        # английские фрагменты и ряд по годам, а не на перевод, и ждать его ей незачем.
        with ThreadPoolExecutor(max_workers=3) as pool:
            trends = (
                pool.submit(
                    self.client.trend_statements,
                    [self._trend_item(outcome, direction) for outcome in outcomes],
                )
                if self.formulate_trends
                else None
            )
            names = pool.submit(
                self.client.title_case_terms, [outcome.result.title for outcome in outcomes]
            )
            texts = (
                pool.submit(self.client.translate, to_translate, source="en", target="ru")
                if self.translate_text
                else None
            )
            titles, title_model = names.result()
            statements: tuple[str | None, ...] = ()
            statement_model: str | None = None
            if trends is not None:
                statements, statement_model = trends.result()
            if texts is not None:
                rendered, text_model = texts.result()
                translated_text = tuple(
                    rendered[index] if to_translate[index] else "" for index in range(len(segments))
                )
        localized: dict[str, TrendLocalization] = {}
        for index, outcome in enumerate(outcomes):
            key = outcome.result.trend_key
            title = titles[index] if index < len(titles) else None
            if title is not None and title.strip() == outcome.result.title.strip():
                # Модель вернула вход без изменений — значит, она не ответила по этой строке.
                # Показывать «русское название», совпадающее с английским, значит утверждать
                # перевод, которого не было.
                title = None
            definition = problem = benefit = None
            evidence_titles: tuple[str, ...] = ()
            if self.translate_text and text_model is not None and index < len(spans):
                start, end = spans[index]
                chunk = translated_text[start:end]
                if len(chunk) == end - start and chunk:
                    definition, problem, benefit, *rest = chunk
                    evidence_titles = tuple(rest)
            statement = statements[index] if index < len(statements) else None
            localized[key] = TrendLocalization(
                title=title,
                title_model=title_model if title else None,
                title_mode=TRANSLATION_GENERATIVE if title else None,
                definition=definition or None,
                problem=problem or None,
                benefit=benefit or None,
                evidence_titles=evidence_titles,
                text_model=text_model,
                text_mode=_mode_of(text_model),
                statement=statement,
                statement_model=statement_model if statement else None,
            )
        filled = sum(0 if row.empty else 1 for row in localized.values())
        _LOGGER.info(
            "localization.built",
            trends=len(outcomes),
            localized=filled,
            titleModel=title_model,
            textModel=text_model,
            statements=sum(1 for row in localized.values() if row.statement),
        )
        return localized

    @staticmethod
    def _trend_item(outcome: TrendOutcome, direction: str) -> dict[str, object]:
        """Всё, из чего модель вправе сложить предложение-тренд: факты темы и её источники.

        Ряд — последние четыре периода, неполный помечен: иначе «в 2026 году — 12» читается как
        спад. Фрагменты — названия и отрывки ключевых документов и извлечённые предложения о
        проблеме и преимуществе, дословно, на языке оригинала.
        """
        series = outcome.result.series
        points = list(zip(series.periods, series.df, strict=False))[-4:]
        rendered = []
        for position, (period, count) in enumerate(points):
            partial = position == len(points) - 1 and series.last_period_share < 1.0
            rendered.append(f"{period}{' (неполный)' if partial else ''} — {count}")
        fragments: list[str] = []
        for text in (outcome.motivation.problem, outcome.motivation.benefit):
            if text and text.strip():
                fragments.append(text.strip())
        for item in outcome.evidence.items[:4]:
            snippet = (item.snippet or "").strip()
            fragments.append(f"{item.title}. {snippet}" if snippet else item.title)
        return {
            "direction": direction,
            "term": outcome.result.title,
            "stage": _STAGE_RU.get(outcome.result.lifecycle_stage, outcome.result.lifecycle_stage),
            "firstYear": outcome.result.first_mention_year or None,
            "series": ", ".join(rendered),
            "fragments": fragments[:6],
        }

    @staticmethod
    def _needs_translation(text: str) -> bool:
        """Нужно ли переводить строку.

        Определение темы собирается шаблоном и уже по-русски: «Тема «security profile» выделена
        по 8 документам за 2021–2026 гг.». Отдать её переводчику с английского — получить
        «:: «Профиль безопасности» Teharda prickyona opo 8 dokikycomenium»: модель обязана
        что-то выдать и транслитерирует то, чего не понимает. Замер живого прогона: так
        выглядели все пятнадцать определений отчёта.

        Признак грубый намеренно. Любая кириллица означает, что строка уже частично русская, а
        частичный перевод хуже отсутствия перевода: он портит понятную половину.
        """
        return bool(text) and not _CYRILLIC.search(text)

    @staticmethod
    def _text_segments(outcome: TrendOutcome) -> Sequence[str]:
        """Строки одной карточки в фиксированном порядке: определение, проблема, преимущество, источники.

        Порядок несущий: он же разбирается обратно по срезу. Отдавать их словарём нельзя —
        сервис перевода принимает список и обязан сохранить порядок, а не ключи.
        """
        return [
            outcome.definition or "",
            outcome.motivation.problem or "",
            outcome.motivation.benefit or "",
            *[item.title for item in outcome.evidence.items],
        ]
