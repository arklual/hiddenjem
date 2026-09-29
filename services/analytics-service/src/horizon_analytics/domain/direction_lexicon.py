"""Перекрёстный словарь направления: формулировка аналитика → предметные метки корпуса.

Отнесение темы к направлению держится на предметных кодах источников (методология §12), а они
на английском: ``cs.LG``, ``artificial intelligence``, ``quantum physics``. Аналитик набирает
направление по-русски. Стем «интеллект» не совпадает со стемом «intelligence» ни при каком
алгоритме стемминга, поэтому без перекрёстного словаря отнесение к направлению для русского
запроса пусто — и отчёт перестаёт зависеть от того, что спросили.

Словарь, а не перевод: машинный перевод недетерминирован и внешний, а ADR-0015 требует, чтобы один
и тот же запрос давал один и тот же отчёт. Курируемый crosswalk к контролируемому словарю —
стандартный приём tech mining, он проверяем и правится вручную.

**Цель — фраза, а не мешок слов.** Первая версия разбивала правую часть на отдельные стемы, и
«материаловедение» через стем ``science`` забирало 355 документов из 1244 — всё, что размечено
``Computer science``; «компьютерное зрение» через ``computer`` забирало 216 документов и ни одного
по компьютерному зрению. Целевая метка сопоставляется целиком и по границам слов.
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from functools import lru_cache
from importlib.resources import files
from types import MappingProxyType

from horizon_analytics.domain.extraction.blacklist import stem_phrase

__all__ = [
    "MAX_PHRASE_WORDS",
    "DirectionEntry",
    "DirectionLexicon",
    "expand",
    "expand_raw",
    "label_matches",
    "load_direction_lexicon",
    "parse_direction_lexicon",
    "suggest",
]

_RESOURCE_PACKAGE = "horizon_analytics.domain.resources"
_RESOURCE_NAME = "direction_lexicon.txt"

#: Наибольшее число слов в левой части статьи. Ограничивает перебор n-грамм запроса; статья длиннее
#: никогда бы не нашлась, поэтому парсер такую отвергает вслух, а не выключает молча.
MAX_PHRASE_WORDS = 4


@dataclass(frozen=True, slots=True)
class DirectionEntry:
    """Одна статья словаря.

    ``surface`` хранится ради подсказки: аналитику, чьё направление не распознано, показывают
    ближайшие известные формулировки, и показывать их надо так, как их пишет человек, а не так, как
    их видит стеммер. «квантов вычислен» — не подсказка.

    ``labels`` стеммированы — ими размечен корпус, и сравниваются они со стеммированными метками
    документов. ``raw_labels`` хранит те же цели как написаны в файле, и нужны они не здесь, а
    раньше: сбор корпуса ищет слова запроса в тексте документов, где слова стоят целиком. Отдать
    туда ``artifici intellig`` значит не найти ни одного документа про ``artificial intelligence``.
    """

    surface: str
    labels: frozenset[str]
    raw_labels: frozenset[str] = frozenset()


#: Ключ — стеммированная формулировка аналитика.
DirectionLexicon = Mapping[str, DirectionEntry]


class LexiconError(ValueError):
    """Статья, которую нельзя применить. Ресурс правится руками, и молчать здесь нельзя."""


def parse_direction_lexicon(content: str) -> DirectionLexicon:
    """Разобрать ресурс: ``формулировка = метка | метка | ~метка``, ``#`` — комментарий.

    Метка с ``~`` участвует в отборе тем и не уходит в сбор: её нет в ``raw_labels``.

    Обе стороны стеммируются тем же алгоритмом, что и остальной конвейер, поэтому падеж и число
    в записи значения не имеют: «квантовых вычислений» и «квантовые вычисления» дают один ключ.
    """
    entries: dict[str, tuple[str, set[str], set[str]]] = {}
    for number, raw in enumerate(content.splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        source, separator, target = line.partition("=")
        if not separator:
            # Строка без разделителя — опечатка в ручной правке, а не статья. Остальной файл
            # применяется: одна кривая строка не должна стоить всех прочих.
            continue
        key = stem_phrase(source.strip())
        if not key:
            continue
        if len(key.split(" ")) > MAX_PHRASE_WORDS:
            raise LexiconError(
                f"{_RESOURCE_NAME}:{number}: формулировка длиннее {MAX_PHRASE_WORDS} слов "
                f"никогда не будет найдена в запросе: {source.strip()!r}"
            )
        forms = [form.strip() for form in target.split("|") if form.strip()]
        # `~` — цель только для отбора: ею размечен корпус, но искать её в источниках нельзя.
        # Код `q-fin.GN` у arXiv — хорошая метка работы о финтехе и плохой поисковый запрос: поиск
        # по коду отдаёт всю рубрику, три тысячи работ общей финансовой экономики, и шестьсот
        # самых свежих из них вытесняют из корпуса всё остальное (разбор 103).
        labels = {
            stemmed for form in forms if (stemmed := stem_phrase(form.removeprefix("~").strip()))
        }
        searchable = [form for form in forms if not form.startswith("~")]
        if labels:
            entry = entries.setdefault(key, (source.strip(), set(), set()))
            entry[1].update(labels)
            entry[2].update(searchable)
    return MappingProxyType(
        {
            key: DirectionEntry(surface, frozenset(labels), frozenset(raw_labels))
            for key, (surface, labels, raw_labels) in entries.items()
        }
    )


@lru_cache(maxsize=1)
def load_direction_lexicon() -> DirectionLexicon:
    """Словарь из упакованного ресурса ``direction_lexicon.txt``.

    Результат кешируется на процесс и потому отдаётся только для чтения: соседи по модулю
    (``load_stopwords`` и прочие) возвращают ``frozenset``, неизменяемый по-настоящему, а
    изменяемый словарь в общем кеше — прямой путь к отчёту, зависящему от порядка вызовов.
    """
    content = files(_RESOURCE_PACKAGE).joinpath(_RESOURCE_NAME).read_text(encoding="utf-8")
    return parse_direction_lexicon(content)


def expand(stemmed_words: Sequence[str], lexicon: DirectionLexicon) -> frozenset[str]:
    """Целевые метки всех статей, найденных в стеммированном запросе.

    Пустая строка в последовательности — разрыв, а не отсутствие: если слово отфильтровали, его
    соседи не становятся соседями. Иначе ``["квантов", "", "криптограф"]`` совпало бы со статьёй
    «квантовая криптография», которой в запросе не было.

    Найденная статья не поглощает свои части: «квантовая криптография» и «криптография» — разные
    записи, и запрос, содержащий первую, законно относится к обеим. Поглощение дало бы направление
    уже, чем спросил аналитик.
    """
    found: set[str] = set()
    for run in _unbroken_runs(stemmed_words):
        for size in range(min(MAX_PHRASE_WORDS, len(run)), 0, -1):
            for start in range(len(run) - size + 1):
                entry = lexicon.get(" ".join(run[start : start + size]))
                if entry is not None:
                    found.update(entry.labels)
    return frozenset(found)


def expand_raw(stemmed_words: Sequence[str], lexicon: DirectionLexicon) -> frozenset[str]:
    """То же, что :func:`expand`, но цели возвращаются как написаны в словаре.

    Эти цели уходят в сбор корпуса, а не в отбор тем. Разница существенная: отбор сравнивает
    стеммированные метки со стеммированными, а сбор ищет слова в тексте документа, где они стоят
    целиком. Одна и та же статья поэтому нужна в двух видах — стеммированном и исходном.
    """
    found: set[str] = set()
    for run in _unbroken_runs(stemmed_words):
        for size in range(min(MAX_PHRASE_WORDS, len(run)), 0, -1):
            for start in range(len(run) - size + 1):
                entry = lexicon.get(" ".join(run[start : start + size]))
                if entry is not None:
                    found.update(entry.raw_labels)
    return frozenset(found)


def label_matches(label_key: str, target: str) -> bool:
    """Совпадает ли целевая метка с меткой документа — целиком и по границам слов.

    ``«materi scienc»`` не совпадает с ``«comput scienc»``, хотя общее слово есть. Именно это
    отличие отделяет материаловедение от всей информатики.
    """
    return f" {label_key} ".find(f" {target} ") >= 0


def _unbroken_runs(stemmed_words: Sequence[str]) -> Iterable[list[str]]:
    """Непрерывные отрезки последовательности, разделённые пустыми элементами."""
    run: list[str] = []
    for word in stemmed_words:
        if word:
            run.append(word)
        elif run:
            yield run
            run = []
    if run:
        yield run


def suggest(
    stemmed_words: Sequence[str], lexicon: DirectionLexicon, limit: int = 3
) -> tuple[str, ...]:
    """Ближайшие известные формулировки для направления, которого в словаре нет.

    Нужна там, где иначе тупик. Аналитик пишет «квантовый компьютинг», словарь знает «квантовый
    компьютер», и без подсказки разница между работающим продуктом и бесполезным — одно слово,
    которое неоткуда узнать.

    Мера близости — доля общих стемов (Жаккар), а не редакционное расстояние: направления
    расходятся словами, а не буквами, и «квантовые вычисления» ближе к «квантовому компьютеру», чем
    к «квантованию», хотя побуквенно наоборот.

    Порядок детерминирован (ADR-0015): при равной близости решает сама формулировка, а не порядок
    обхода словаря. Статьи без единого общего стема не предлагаются вовсе — подсказка наугад хуже
    её отсутствия, потому что выглядит как знание.
    """
    query = {word for word in stemmed_words if word}
    if not query:
        return ()
    scored: list[tuple[float, str]] = []
    for key, entry in lexicon.items():
        candidate = set(key.split(" "))
        shared = query & candidate
        if not shared:
            continue
        scored.append((len(shared) / len(query | candidate), entry.surface))
    scored.sort(key=lambda item: (-item[0], item[1]))
    return tuple(surface for _, surface in scored[:limit])
