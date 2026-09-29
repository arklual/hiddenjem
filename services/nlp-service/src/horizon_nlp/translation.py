"""Перевод на русский язык — Marian (opus-mt), локально, детерминированно.

Почему отдельная модель-переводчик, а не генеративная. ТЗ требует, чтобы вся аналитическая
выдача была на русском, а для зарубежного материала — русскоязычное резюме с сохранением
оригинального названия. Это две разные задачи, и вторая — не первая: генеративная модель на
запрос «переведи название технологии» отвечает пересказом, а на процессоре делает это со
скоростью около трёх токенов в секунду. Marian переводит пятнадцать названий за секунды,
жадным поиском, то есть воспроизводимо: один и тот же отчёт переводится одинаково.

Поиск с балансировкой (beam) сознательно выключен: он даёт чуть более гладкий текст и ломает
воспроизводимость на границах батча — строка, попавшая в батч другого размера, переводится
иначе. Для названий и коротких резюме выигрыш не стоит потери свойства.
"""

from __future__ import annotations

import re
import threading
from collections.abc import Sequence
from dataclasses import dataclass
from typing import Any, Final

import structlog

from horizon_nlp.config import settings

__all__ = ["TranslationResult", "Translator", "protect_acronyms", "translator"]

log = structlog.get_logger(__name__)

#: Аббревиатура латиницей: две-восемь заглавных, возможна цифра.
_ACRONYM = re.compile(r"\b[0-9]?[A-Z][A-Z0-9]{1,7}\b")

#: Аббревиатуры с устоявшимся русским видом. Подставляются **после** перевода, а не оставляются
#: модели: на «AI safety benchmarks» она даёт то «ИИ», то «АИ» в зависимости от остатка фразы, и
#: предсказать это нельзя. Список короткий нарочно — в нём только то, что в русской технической
#: литературе пишется по-русски всегда.
_CANONICAL: Final[dict[str, str]] = {"AI": "ИИ", "IT": "ИТ"}

#: Придуманная моделью аббревиатура: заглавные кириллицей, от двух до шести букв. Именно так
#: выглядят «ЛММ», «МКП», «УЗМ», «ЛУЗР» — то, чего в русском языке нет.
_INVENTED = re.compile(r"\b[А-ЯЁ]{2,6}\b")

#: Настоящие русские аббревиатуры, которые под ту же форму подходят. Список не претендует на
#: полноту: он нужен, чтобы восстановление не тронуло то, что модель перевела правильно.
_REAL_RUSSIAN: Final[frozenset[str]] = frozenset(
    {"ИИ", "ИТ", "ЭВМ", "ГОСТ", "СУБД", "ПО", "БД", "ОС", "СМИ", "РФ", "США", "ЕС", "ООН"}
)

#: Как защитить аббревиатуру. Выбрано замером, а не на глаз: Marian сохраняет одни формы и
#: уничтожает другие. Замер 2026-09-18 на opus-mt-en-ru, настоящие определения тем:
#:
#: * без защиты — `LLM` → «ЛММ» в одном месте и «УЗМ» в другом, `MCP` → «МКП»: модель придумывает
#:   русскую аббревиатуру, которой не существует;
#: * подстановка `__0__` → «___0_», «____ __»: разрушается полностью;
#: * подстановка `ZZQ0` держится в середине фразы и **транслитерируется в начале**: «ЗЦКВ0
#:   трубопроводы» — то есть ровно там, где её потерю труднее всего заметить;
#: * кавычки вокруг самой аббревиатуры держатся везде: «Трубопроводы RAG на кластерах GPU»,
#:   «агентов на основе LLM», «сети "5G"», «впрыска "SQL"». Модель то оставляет кавычки, то
#:   убирает их сама — обе формы приводятся к одной после перевода.
#:
#: Кавычки выигрывают ещё и тем, что не требуют обратной таблицы: аббревиатура остаётся собой, и
#: потерянная защита означает потерянную кавычку, а не потерянное слово.
_QUOTES: Final[tuple[str, str, str]] = ('"{token}"', "«{token}»", "\u201c{token}\u201d")


def protect_acronyms(text: str) -> tuple[str, tuple[str, ...]]:
    """Защитить латинские аббревиатуры кавычками; вернуть текст и список защищённых.

    Зачем это нужно. Модель-переводчик обучена переводить слова, и аббревиатуру она переводит
    тоже — по буквам. Ни одной из получающихся русских аббревиатур не существует, и главное — по
    ним нельзя вернуться к источнику, а вся ценность выдачи в проверяемости.

    Защищаются все, включая те, у которых есть русский вид: `AI` подставляется как «ИИ» уже
    после перевода (:data:`_CANONICAL`), потому что сама модель выдаёт то «ИИ», то «АИ» — от
    остатка фразы, и предсказать это нельзя.
    """
    protected: list[str] = []

    def substitute(match: re.Match[str]) -> str:
        token = match.group(0)
        # Уже закавыченную источником аббревиатуру второй раз не закавычиваем: вложенные кавычки
        # модель воспроизводит как попало, и вычистить их потом нечем.
        before = text[match.start() - 1] if match.start() else ""
        after = text[match.end()] if match.end() < len(text) else ""
        if token not in protected:
            protected.append(token)
        if before in '"«\u201c' and after in '"»\u201d':
            return token
        return f'"{token}"'

    return _ACRONYM.sub(substitute, text), tuple(protected)


def _restore(text: str, protected: tuple[str, ...]) -> tuple[str, int]:
    """Снять добавленные кавычки и вернуть аббревиатуры; второе значение — сколько потеряно.

    Кавычки держат аббревиатуру в подавляющем большинстве случаев, но не во всех: замер показал
    «агентов на основе системы «ЛУЗР»» там, где в источнике стояло `LLM-based`. Поэтому есть
    вторая ступень — обратная замена придуманной аббревиатуры.

    Она срабатывает только при однозначном соответствии: одна потерянная аббревиатура и одна
    придуманная в тексте. При двух и более сопоставление было бы догадкой, а догадка в выдаче,
    которая обещает проверяемость, хуже пропуска.
    """
    for token in protected:
        for form in _QUOTES:
            text = text.replace(form.format(token=token), token)
    missing = [token for token in protected if token not in text]
    if len(missing) == 1:
        invented = [
            found
            for found in dict.fromkeys(_INVENTED.findall(text))
            if found not in _REAL_RUSSIAN
        ]
        if len(invented) == 1:
            text = text.replace(invented[0], missing[0])
            missing = []
    for token, russian in _CANONICAL.items():
        if token in protected:
            text = re.sub(rf"\b{token}\b", russian, text)
    return text, len(missing)


@dataclass(frozen=True, slots=True)
class TranslationResult:
    """Перевод строк вместе с раскрытием модели, которая его сделала."""

    texts: tuple[str, ...]
    model: str
    #: Сколько строк отдано из кэша, а не переведено заново. Видно в логе и в ответе: без этого
    #: числа латентность прогонов скачет без объяснения.
    cached: int


class Translator:
    """Ленивая обёртка над двумя направлениями перевода.

    Модель одна на направление и живёт всё время процесса: загрузка весов стоит секунды, и
    платить их на каждом запросе значит превратить показ в ожидание.
    """

    def __init__(self) -> None:
        """Подготовить пустые слоты; веса грузятся по требованию либо при старте."""
        # `Any`, а не `object`: типов у transformers нет, и объявить их точнее нечем.
        # Строгая проверка при `object` запрещала бы вызывать то, что здесь и вызывается.
        self._models: dict[str, tuple[Any, Any]] = {}
        self._cache: dict[tuple[str, str], str] = {}
        self._lock = threading.Lock()

    def _model_name(self, source: str, target: str) -> str:
        if source == "en" and target == "ru":
            return settings.translate_en_ru_model
        if source == "ru" and target == "en":
            return settings.translate_ru_en_model
        raise ValueError(f"направление перевода {source}→{target} не настроено")

    def load(self, source: str, target: str) -> tuple[Any, Any]:
        """Вернуть (токенизатор, модель), загрузив их при первом обращении."""
        name = self._model_name(source, target)
        with self._lock:
            if name in self._models:
                return self._models[name]
            from transformers import (
                AutoModelForSeq2SeqLM,
                AutoTokenizer,
            )  # локальный импорт: torch

            log.info("translation.load", model=name)
            tokenizer = AutoTokenizer.from_pretrained(name)
            model = AutoModelForSeq2SeqLM.from_pretrained(name)
            model.eval()
            self._models[name] = (tokenizer, model)
            return self._models[name]

    def translate(
        self, texts: Sequence[str], *, source: str, target: str
    ) -> TranslationResult:
        """Перевести строки, сохранив порядок; пустые строки остаются пустыми."""
        name = self._model_name(source, target)
        items = list(texts)
        out: list[str | None] = [None] * len(items)
        pending: list[tuple[int, str]] = []
        cached = 0
        for index, text in enumerate(items):
            stripped = (text or "").strip()
            if not stripped:
                out[index] = ""
                continue
            key = (name, stripped)
            hit = self._cache.get(key)
            if hit is not None:
                out[index] = hit
                cached += 1
            else:
                pending.append((index, stripped))
        if pending:
            tokenizer, model = self.load(source, target)
            import torch  # локальный импорт: держим стартовое время сервиса низким

            batch = settings.translate_batch_size
            lost_total = 0
            for start in range(0, len(pending), batch):
                chunk = pending[start : start + batch]
                masked = [protect_acronyms(text) for _, text in chunk]
                encoded = tokenizer(
                    [text for text, _ in masked],
                    return_tensors="pt",
                    padding=True,
                    truncation=True,
                    max_length=settings.translate_max_tokens,
                )
                with torch.inference_mode():
                    generated = model.generate(
                        **encoded,
                        num_beams=1,
                        do_sample=False,
                        max_new_tokens=settings.translate_max_tokens,
                    )
                decoded = tokenizer.batch_decode(generated, skip_special_tokens=True)
                for (index, text), (_, protected), translated in zip(
                    chunk, masked, decoded, strict=True
                ):
                    value, lost = _restore(translated.strip(), protected)
                    lost_total += lost
                    # Кэш хранит готовый перевод: добавленные кавычки в него не попадают.
                    self._cache[(name, text)] = value
                    out[index] = value
            if lost_total:
                # Потерянная аббревиатура означает текст без неё — хуже, чем с ней, но лучше
                # выдуманной русской. Молчать об этом нельзя: число объясняет, почему перевод
                # выглядит короче оригинала.
                log.warning("translation.acronyms_lost", count=lost_total, model=name)
        return TranslationResult(
            texts=tuple(value or "" for value in out), model=name, cached=cached
        )


translator = Translator()
