"""Deterministic RU + EN tokenisation and sentence splitting.

The tokenizer is intentionally boring: one compiled regular expression, one Unicode
normalisation form, no language detection, no learned model. Given identical bytes in it
produces identical tokens on any machine and any Python 3.12 build — the precondition for
every determinism claim downstream.
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass
from typing import Final

__all__ = [
    "Sentence",
    "Token",
    "is_year_like",
    "normalize_text",
    "split_sentences",
    "tokenize",
]

#: A word is a run of *at least two* letters, optionally glued by inner hyphens/apostrophes,
#: possibly carrying trailing digits ("gpt-4", "5g", "bert-base"); or a bare number.
#:
#: The two-letter floor is deliberate. Single letters are never technology terms, but they are
#: everywhere in scientific prose — variable names, initials, list markers, the "T" of a
#: truncated title. Admitted as tokens they generate spurious n-grams ("t alpha beta") that go
#: on to dilute C-value's nesting average and quietly distort termhood ranking.
_TOKEN_RE: Final[re.Pattern[str]] = re.compile(
    # word of two or more letters, with hyphenated continuations: "transformer", "bert-base", "gpt-4"
    r"[^\W\d_]{2,}(?:[-'’][^\W_]+)*(?:\d+[A-Za-z]*)?"
    # single letter only when it is genuinely part of a compound: "x-ray", "e-mail"
    r"|[^\W\d_](?:[-'’][^\W_]+)+(?:\d+[A-Za-z]*)?"
    # digit-led technology names: "5g", "3d"
    r"|\d+[^\W\d_]+"
    # bare number
    r"|\d+(?:[.,]\d+)*",
    re.UNICODE,
)

#: Abbreviations after which a period does *not* end a sentence.
_NON_TERMINAL: Final[frozenset[str]] = frozenset(
    {
        "e.g",
        "i.e",
        "et al",
        "etc",
        "fig",
        "eq",
        "ref",
        "cf",
        "vs",
        "approx",
        "dr",
        "prof",
        "no",
        "vol",
        "pp",
        "т.е",
        "т.д",
        "т.п",
        "рис",
        "табл",
        "см",
        "др",
        "проф",
        "акад",
    }
)

_SENTENCE_BOUNDARY: Final[re.Pattern[str]] = re.compile(r"(?<=[.!?…])[\s ]+")

_WHITESPACE: Final[re.Pattern[str]] = re.compile(r"[\s ]+")


@dataclass(frozen=True, slots=True)
class Token:
    """One token with both its surface form and its lowercase normal form."""

    surface: str
    normal: str
    start: int
    end: int

    @property
    def is_alphabetic(self) -> bool:
        """True when the token contains at least one letter."""
        return any(character.isalpha() for character in self.normal)

    @property
    def is_numeric(self) -> bool:
        """True when the token is a bare number."""
        return self.normal.replace(".", "").replace(",", "").isdigit()

    @property
    def is_upper_surface(self) -> bool:
        """True when the surface form is fully upper case and at least two letters long.

        Used both by the acronym miner and by the YAKE casing feature.
        """
        letters = [character for character in self.surface if character.isalpha()]
        return len(letters) >= 2 and all(character.isupper() for character in letters)

    @property
    def is_capitalized_surface(self) -> bool:
        """True when the surface form starts with an upper case letter."""
        return bool(self.surface) and self.surface[0].isupper()


@dataclass(frozen=True, slots=True)
class Sentence:
    """One sentence of a document abstract, with its character offsets."""

    text: str
    start: int
    end: int
    index: int


def normalize_text(text: str) -> str:
    """Apply NFKC normalisation, unify the Russian ``ё`` and collapse whitespace.

    ``ё → е`` matches what the Snowball Russian stemmer assumes and removes an entire class
    of spurious duplicate terms.
    """
    normalized = unicodedata.normalize("NFKC", text)
    normalized = normalized.replace("ё", "е").replace("Ё", "Е")
    return _WHITESPACE.sub(" ", normalized).strip()


#: Письменности без пробелов между словами: китайская, японская, корейская. ``[^\W\d_]{2,}``
#: склеивает в них целое предложение в один «токен», и тот становится кандидатом-абзацем. Такие
#: документы переводятся на английский при сборе (разбор 93); непереведённый остаток — тот, на
#: который не хватило бюджета перевода, — терминов не даёт, а не даёт мусора.
_UNSEGMENTED_SCRIPT: Final[re.Pattern[str]] = re.compile(
    r"[\u3040-\u30ff\u3400-\u9fff\uac00-\ud7af]"
)


def tokenize(text: str) -> tuple[Token, ...]:
    """Split ``text`` into tokens, preserving surface forms and offsets."""
    tokens: list[Token] = []
    for match in _TOKEN_RE.finditer(text):
        surface = match.group(0)
        if _UNSEGMENTED_SCRIPT.search(surface):
            continue
        tokens.append(
            Token(
                surface=surface,
                normal=surface.lower().replace("’", "'"),
                start=match.start(),
                end=match.end(),
            )
        )
    return tuple(tokens)


def split_sentences(text: str) -> tuple[Sentence, ...]:
    """Split ``text`` into sentences, guarding a small list of abbreviations.

    Good enough for the extractive narrator (ADR-0010), which only needs to quote a
    sentence verbatim, and fully deterministic — unlike any statistical splitter.
    """
    if not text.strip():
        return ()
    sentences: list[Sentence] = []
    cursor = 0
    index = 0
    for match in _SENTENCE_BOUNDARY.finditer(text):
        end = match.start()
        chunk = text[cursor:end]
        if _ends_with_abbreviation(chunk):
            continue
        stripped = chunk.strip()
        if stripped:
            sentences.append(Sentence(text=stripped, start=cursor, end=end, index=index))
            index += 1
        cursor = match.end()
    tail = text[cursor:].strip()
    if tail:
        sentences.append(Sentence(text=tail, start=cursor, end=len(text), index=index))
    return tuple(sentences)


def _ends_with_abbreviation(chunk: str) -> bool:
    """Whether ``chunk`` ends with a known non-terminal abbreviation."""
    stripped = chunk.rstrip()
    if not stripped.endswith("."):
        return False
    tail = stripped[:-1]
    lowered = tail.lower()
    for abbreviation in _NON_TERMINAL:
        if lowered.endswith(abbreviation):
            preceding = lowered[: -len(abbreviation)]
            if not preceding or not preceding[-1].isalnum():
                return True
    # A single trailing capital letter is an initial ("J. Smith"), not a sentence end.
    return len(tail) >= 1 and tail[-1].isupper() and (len(tail) == 1 or not tail[-2].isalpha())


def is_year_like(value: str) -> bool:
    """Whether a token looks like a calendar year and must not become a term."""
    if not value.isdigit() or len(value) != 4:
        return False
    number = int(value)
    return 1500 <= number <= 2200
