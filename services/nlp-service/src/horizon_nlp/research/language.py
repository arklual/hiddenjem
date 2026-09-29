"""Язык оригинала страницы — по её тексту.

ТЗ требует показывать у каждого источника язык оригинала. Сборщик корпуса язык не записывал, и
до этого модуля страница без метки получала «en» — в том числе русские и китайские. Здесь язык
определяется по самому тексту: сначала по письменности (кириллица, иероглифы, кана, хангыль), для
латиницы — по частым служебным словам. Если букв нет или признаки не набрались — ``None``:
«язык не определён» честнее выдуманного английского.
"""

from __future__ import annotations

import re
from collections import Counter
from typing import Final

__all__ = ["detect_language"]

_SAMPLE: Final = 4000
_CYRILLIC: Final = re.compile(r"[Ѐ-ӿ]")
_HAN: Final = re.compile(r"[一-鿿㐀-䶿]")
_KANA: Final = re.compile(r"[぀-ヿ]")
_HANGUL: Final = re.compile(r"[가-힯ᄀ-ᇿ]")
_LATIN: Final = re.compile(r"[A-Za-zÀ-ɏ]")
_URL: Final = re.compile(r"https?://\S+|\b[\w.-]+\.(?:com|org|net|io|ai|ru|cn|br|de|fr|es|it)\b\S*")
_WORD: Final = re.compile(r"[a-zà-ÿ]+")

#: Служебные слова, почти не встречающиеся в других языках из списка. Слово, общее для двух языков
#: («de», «a», «que»), не различает их и потому не взято.
_STOPWORDS: Final[dict[str, frozenset[str]]] = {
    "en": frozenset(
        {"the", "and", "of", "to", "is", "that", "with", "for", "this", "are", "was", "from"}
    ),
    "es": frozenset(
        {"el", "los", "las", "del", "y", "es", "una", "por", "con", "para", "su", "como"}
    ),
    "pt": frozenset(
        {
            "das",
            "dos",
            "não",
            "uma",
            "para",
            "são",
            "pelo",
            "pela",
            "também",
            "você",
            "está",
            "ainda",
            "é",
            "sem",
            "seu",
            "sua",
            "já",
            "até",
            "às",
        }
    ),
    "fr": frozenset(
        {"le", "les", "des", "et", "est", "une", "dans", "pour", "sur", "au", "avec", "qui"}
    ),
    "de": frozenset(
        {"der", "die", "und", "das", "ist", "nicht", "mit", "für", "auf", "den", "ein", "eine"}
    ),
    "it": frozenset(
        {
            "il",
            "della",
            "che",
            "sono",
            "gli",
            "nel",
            "alla",
            "anche",
            "questo",
            "delle",
            "degli",
            "è",
        }
    ),
}


def detect_language(text: str | None) -> str | None:
    """Двухбуквенный код языка текста или ``None``, если определить нельзя."""
    if not text:
        return None
    sample = text[:_SAMPLE]
    han = len(_HAN.findall(sample))
    kana = len(_KANA.findall(sample))
    hangul = len(_HANGUL.findall(sample))
    cyrillic = len(_CYRILLIC.findall(sample))
    latin = len(_LATIN.findall(sample))
    letters = han + kana + hangul + cyrillic + latin
    if letters < 20:
        return None
    if kana >= 0.05 * letters:
        return "ja"
    if hangul >= 0.2 * letters:
        return "ko"
    if han >= 0.2 * letters:
        return "zh"
    if cyrillic >= 0.3 * letters:
        return "ru"
    if latin < 0.5 * letters:
        return None
    votes = Counter[str]()
    for word in _WORD.findall(_URL.sub(" ", sample).lower()):
        for language, words in _STOPWORDS.items():
            if word in words:
                votes[language] += 1
    english = votes.pop("en", 0)
    if not votes:
        return "en"
    language, count = votes.most_common(1)[0]
    # Другой язык латиницей — только с явным перевесом: короткое английское описание
    # репозитория с парой случайных совпадений («die», «den») английским и остаётся.
    return language if count >= 3 and count >= 2 * english + 1 else "en"
