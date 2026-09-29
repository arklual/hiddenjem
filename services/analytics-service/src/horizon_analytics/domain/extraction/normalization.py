"""Normalisation, stemming, acronym mining and alias merging — methodology §7 step 4.

Contains a compact implementation of the **Snowball Russian stemmer** and a conservative
English suffix stripper. Both are written out here on purpose: ``nltk`` would need a
runtime download and ``spacy`` a model, and either would make the output depend on data
that is not in the repository — a direct violation of ADR-0015.

The English rules are deliberately shallow. For terminology what matters is that
inflections of one term *collide* consistently, not that the stem is a real word:
``embeddings → embedding → embed`` and ``embed → embed`` land on the same key, which is the
whole point.
"""

from __future__ import annotations

import re
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from typing import Final

from horizon_analytics.domain.extraction.tokenizer import Token, normalize_text, tokenize

__all__ = [
    "AcronymDictionary",
    "AcronymEntry",
    "TermNormalizer",
    "mine_acronyms",
    "normalize_tokens",
    "stem_english",
    "stem_russian",
    "stem_token",
]

_RU_VOWELS: Final[frozenset[str]] = frozenset("аеиоуыэюя")
_CYRILLIC: Final[re.Pattern[str]] = re.compile(r"[а-я]", re.IGNORECASE)

# fmt: off
# Snowball ending groups, longest-first inside each group. Kept in the compact
# layout of the reference algorithm so they can be diffed against snowballstem.org.
_PERFECTIVE_GERUND_1: Final[tuple[str, ...]] = ("вшись", "вши", "в")
_PERFECTIVE_GERUND_2: Final[tuple[str, ...]] = ("ывшись", "ившись", "ывши", "ивши", "ыв", "ив")
_ADJECTIVE: Final[tuple[str, ...]] = (
    "ими", "ыми", "его", "ого", "ему", "ому", "ее", "ие", "ые", "ое", "ей", "ий", "ый", "ой",
    "ем", "им", "ым", "ом", "их", "ых", "ую", "юю", "ая", "яя", "ою", "ею",
)
_PARTICIPLE_1: Final[tuple[str, ...]] = ("ющ", "нн", "вш", "ем", "щ")
_PARTICIPLE_2: Final[tuple[str, ...]] = ("ующ", "ивш", "ывш")
_REFLEXIVE: Final[tuple[str, ...]] = ("ся", "сь")
_VERB_1: Final[tuple[str, ...]] = (
    "ешь", "нно", "ете", "йте", "ла", "на", "ли", "ем", "ло", "но", "ет", "ют", "ны", "ть",
    "й", "л", "н",
)
_VERB_2: Final[tuple[str, ...]] = (
    "ейте", "уйте", "ила", "ыла", "ена", "ите", "или", "ыли", "ило", "ыло", "ено", "ует",
    "уют", "ены", "ить", "ыть", "ишь", "ей", "уй", "ил", "ыл", "им", "ым", "ен", "ят", "ит",
    "ыт", "ую", "ю",
)
_NOUN: Final[tuple[str, ...]] = (
    "иями", "ями", "ами", "иях", "ией", "иям", "ием", "иев", "ях", "ах", "ов", "ев", "ие",
    "ье", "еи", "ии", "ей", "ой", "ий", "ям", "ем", "ам", "ом", "ию", "ью", "ия", "ья", "а",
    "е", "и", "й", "о", "у", "ы", "ь", "ю", "я",
)
_SUPERLATIVE: Final[tuple[str, ...]] = ("ейше", "ейш")
_DERIVATIONAL: Final[tuple[str, ...]] = ("ость", "ост")
# fmt: on


def _russian_regions(word: str) -> tuple[int, int]:
    """Return the start offsets of the Snowball ``RV`` and ``R2`` regions."""
    rv = len(word)
    for index, character in enumerate(word):
        if character in _RU_VOWELS:
            rv = index + 1
            break

    def _after_vowel_consonant(start: int) -> int:
        for index in range(start, len(word) - 1):
            if word[index] in _RU_VOWELS and word[index + 1] not in _RU_VOWELS:
                return index + 2
        return len(word)

    r1 = _after_vowel_consonant(0)
    r2 = _after_vowel_consonant(r1) if r1 < len(word) else len(word)
    return rv, r2


def _strip(word: str, endings: Sequence[str], region: int) -> tuple[str, str | None]:
    """Remove the longest matching ending that lies entirely inside ``region``."""
    for ending in sorted(endings, key=len, reverse=True):
        if word.endswith(ending) and len(word) - len(ending) >= region:
            return word[: -len(ending)], ending
    return word, None


def _strip_group1(word: str, endings: Sequence[str], region: int) -> tuple[str, str | None]:
    """Group-1 removal: the ending must be preceded by ``а`` or ``я``."""
    for ending in sorted(endings, key=len, reverse=True):
        if not word.endswith(ending):
            continue
        cut = len(word) - len(ending)
        if cut < region or cut == 0:
            continue
        if word[cut - 1] in ("а", "я"):
            return word[:cut], ending
    return word, None


def stem_russian(word: str) -> str:
    """Snowball Russian stemmer — the reference algorithm, endings longest-first.

    **Not idempotent**, and deliberately so: ``обучение → обучен → обуч``. Iterating to a fixed
    point would over-stem and merge genuinely different technical terms, which costs more than
    it saves. Safety comes from the pipeline instead — every term is stemmed exactly once, when
    it is first extracted, and the resulting key is thereafter treated as opaque. The
    ``test_stemming_is_deliberately_not_idempotent`` case pins this so the property cannot drift
    into an accident.
    """
    lowered = word.lower().replace("ё", "е")
    if len(lowered) <= 2:
        return lowered
    rv, r2 = _russian_regions(lowered)

    # Step 1 — perfective gerund, else reflexive + (adjectival | verb | noun).
    stem, found = _strip_group1(lowered, _PERFECTIVE_GERUND_1, rv)
    if found is None:
        stem, found = _strip(lowered, _PERFECTIVE_GERUND_2, rv)
    if found is None:
        stem, _ = _strip(lowered, _REFLEXIVE, rv)
        adjectival, found = _strip(stem, _ADJECTIVE, rv)
        if found is not None:
            participle, participle_found = _strip_group1(adjectival, _PARTICIPLE_1, rv)
            if participle_found is None:
                participle, participle_found = _strip(adjectival, _PARTICIPLE_2, rv)
            stem = participle
        else:
            verb, verb_found = _strip_group1(stem, _VERB_1, rv)
            if verb_found is None:
                verb, verb_found = _strip(stem, _VERB_2, rv)
            if verb_found is not None:
                stem = verb
            else:
                stem, _ = _strip(stem, _NOUN, rv)

    # Step 2 — a trailing "и" in RV.
    if stem.endswith("и") and len(stem) - 1 >= rv:
        stem = stem[:-1]

    # Step 3 — derivational suffix inside R2.
    stem, _ = _strip(stem, _DERIVATIONAL, r2)

    # Step 4 — "нн" → "н", superlative, soft sign.
    if stem.endswith("нн"):
        stem = stem[:-1]
    else:
        superlative, found = _strip(stem, _SUPERLATIVE, rv)
        if found is not None:
            stem = superlative[:-1] if superlative.endswith("нн") else superlative
    if stem.endswith("ь"):
        stem = stem[:-1]
    return stem or lowered


_EN_VOWELS: Final[frozenset[str]] = frozenset("aeiouy")
_EN_DOUBLE_KEEP: Final[frozenset[str]] = frozenset("lsz")


def _has_vowel(word: str) -> bool:
    """Whether an English stem still contains a vowel."""
    return any(character in _EN_VOWELS for character in word)


def stem_english(word: str) -> str:
    """Conservative English suffix stripper (plural + ``-ing`` / ``-ed``).

    Only the inflections that actually create duplicate technical terms are removed.
    Derivational suffixes (``-ness``, ``-ful``, ``-ly``) are left alone: stripping them
    merges genuinely different terms far more often than it helps.
    """
    lowered = word.lower()
    if len(lowered) <= 3:
        return lowered

    stem = lowered
    if stem.endswith("sses"):
        stem = stem[:-2]
    elif stem.endswith("ies") and len(stem) > 4:
        stem = stem[:-3] + "y"
    elif stem.endswith(("ches", "shes", "xes", "zes", "ses")) and len(stem) > 4:
        stem = stem[:-2]
    elif stem.endswith("s") and not stem.endswith(("ss", "us", "is", "os")) and len(stem) > 3:
        stem = stem[:-1]

    for suffix in ("ing", "ed"):
        # A four-character floor on the remaining stem. With three, "speed" is mistaken for a
        # past tense and becomes "spe" — merging it with nothing and losing a real term. Genuine
        # inflections of technical vocabulary ("training", "learned", "embedded") all leave at
        # least four characters behind.
        if stem.endswith(suffix) and len(stem) - len(suffix) >= 4:
            candidate = stem[: -len(suffix)]
            if not _has_vowel(candidate):
                continue
            if (
                len(candidate) >= 2
                and candidate[-1] == candidate[-2]
                and candidate[-1] not in _EN_DOUBLE_KEEP
                and candidate[-1] not in _EN_VOWELS
            ):
                candidate = candidate[:-1]
            stem = candidate
            break
    return stem or lowered


def stem_token(token: str) -> str:
    """Stem one token, dispatching on script (Cyrillic → Russian, otherwise English)."""
    if _CYRILLIC.search(token):
        return stem_russian(token)
    return stem_english(token)


def normalize_tokens(tokens: Iterable[str]) -> str:
    """Build the canonical key of a term: space-joined stems of its tokens."""
    return " ".join(stem_token(token) for token in tokens if token)


# ───────────────────────────── acronyms (BRULE-7) ─────────────────────────────

#: "Long Form (Acr)" — до шести слов перед скобочным сокращением, начинающимся с прописной.
#:
#: Прописными сокращение быть не обязано, и требование этого стоило продукту настоящих имён.
#: Прежний шаблон принимал только `[A-ZА-Я][A-ZА-Я\d\-]{1,9}`, то есть целиком заглавные, — а
#: `IoT`, `LiDAR`, `GaN`, `SiC`, `mRNA` пишутся смешанным регистром и мимо словаря проходили.
#: Замер на 1785 работах OpenAlex: `internet of things (IoT)` встречается в корпусе, а `iot` в
#: добытом словаре отсутствовал, и извлекатель отдавал вместо имени склейки `things iot` (59
#: документов) и `internet of things iot` (59).
#:
#: Ослабление безопасно не по вере, а по устройству: `_shortest_matching_suffix` ниже проверяет,
#: читается ли сокращение инициалами предшествующих слов, и «(Germany)» после «produced in» этой
#: проверки не проходит. Регулярка только предлагает, решает проверка.
_ACRONYM_RE: Final[re.Pattern[str]] = re.compile(
    r"((?:[^\W\d_][\w\-]*[  ]+){1,6})\(\s*([A-ZА-Я][A-Za-zА-Яа-я\d\-]{1,9}s?)\s*\)",
    re.UNICODE,
)

#: Words that may be skipped when matching an acronym against the long form's initials.
_SKIPPABLE: Final[frozenset[str]] = frozenset(
    {"of", "the", "for", "and", "a", "an", "in", "on", "to", "с", "и", "для", "по", "в", "на"}
)


@dataclass(frozen=True, slots=True)
class AcronymEntry:
    """One mined acronym together with its resolved long form."""

    acronym: str
    long_form: str
    long_form_key: str
    support: int


@dataclass(frozen=True, slots=True)
class AcronymDictionary:
    """Corpus-mined acronym dictionary, keyed by the *normalised* acronym."""

    entries: Mapping[str, AcronymEntry]

    def resolve(self, key: str) -> AcronymEntry | None:
        """Return the entry for a normalised acronym, if any."""
        return self.entries.get(key)

    def canonical_key(self, key: str) -> str:
        """Map an acronym key onto the key of its long form; identity when unknown.

        This is BRULE-7: ``LLM`` and ``large language model`` must end up as one trend.
        """
        entry = self.entries.get(key)
        return entry.long_form_key if entry is not None else key

    def echoes_long_form(self, key: str) -> bool:
        """Кандидат ли это, разрезанный скобкой между полной формой и её сокращением.

        Окно n-грамм не знает про скобки: пройдя по «artificial intelligence (AI)», оно, кроме
        самого термина, выдаёт `artificial intelligence ai` и `intelligence ai`, а по
        «internet of things (IoT)» — `internet of thing iot` и `thing iot`. На настоящем корпусе
        (1785 работ OpenAlex) у каждого из четырёх — по шесть десятков документов, то есть они
        конкурируют с термином, куском которого являются.

        Признак точный, а не эвристический: последний токен — известное сокращение, а всё, что
        перед ним, есть суффикс его же полной формы. `intelligence ai` подходит (`intelligence` —
        суффикс `artificial intelligence`), `explainable ai` не подходит, и именно эту разницу
        правило и обязано видеть: «объяснимый ИИ» — самостоятельное имя, а не эхо.

        Односложный кандидат сюда не попадает: `ai` сам по себе — это термин, и сливает его с
        полной формой :meth:`canonical_key`.
        """
        parts = key.split(" ")
        if len(parts) < 2:
            return False
        entry = self.entries.get(parts[-1])
        if entry is None:
            return False
        long_form = entry.long_form_key.split(" ")
        preceding = parts[:-1]
        return len(preceding) <= len(long_form) and long_form[-len(preceding) :] == preceding

    def __len__(self) -> int:
        """Number of resolved acronyms."""
        return len(self.entries)


def _components(words: Sequence[str]) -> list[str]:
    """Split hyphenated words into their parts: ``sodium-ion`` → ``sodium``, ``ion``.

    Дефис в английском термине соединяет составляющие, и аббревиатура строится по их начальным
    буквам, а не по началам слов через пробел: ``SIB`` — это ``sodium``, ``ion``, ``battery``.
    Пока дефисное слово считалось одним, четыре аббревиатуры эталонного корпуса из восьми
    несливающихся не находили своей полной формы — `post-quantum cryptography`, `sodium-ion
    battery`, `solid-state battery`, `zero-knowledge rollup`.

    Расщепление применяется **только при сопоставлении**: ключ темы по-прежнему строится по
    исходным словам, иначе изменилась бы идентичность кандидатов и с ней — весь снапшот.
    """
    parts: list[str] = []
    for word in words:
        parts.extend(part for part in word.split("-") if part)
    return parts


def _initials_match(acronym: str, words: Sequence[str]) -> bool:
    """Whether ``acronym`` reads off the initials of ``words`` — all of them, in order.

    Skippable function words may be dropped, and the last acronym letter may be a plural ``s``.
    Ничего больше: прежняя версия разрешала взять букву **изнутри** слова, и на этом
    `open-source implementation of sparse autoencoder` читалось как `SAE` — «s» из `source`, «a»
    из `implementation`, «e» из `sparse`. Правило, допускающее буквы откуда угодно, склеивает
    аббревиатуру с окружающим текстом, а не с термином.

    Лишние слова теперь тоже отказ, а не «хватит, буквы кончились»: полная форма обязана
    заканчиваться там же, где аббревиатура, иначе в неё попадает хвост предложения.
    """
    letters = [character for character in acronym.lower() if character.isalnum()]
    if not letters:
        return False
    components = _components(words)
    # Конечное «s» двусмысленно: у `LLMs` это множественное число, у `NAS` — часть аббревиатуры
    # (`neural architecture search`). Проверяются обе трактовки: пока строгое правило отбрасывало
    # лишние слова молча, срезанная буква ничего не стоила, а теперь стоит целого слияния.
    readings = [letters]
    if len(letters) > 1 and letters[-1] == "s":
        readings.append(letters[:-1])
    return any(_reads_as(reading, components) for reading in readings)


def _reads_as(letters: Sequence[str], words: Sequence[str]) -> bool:
    """Каждое слово начинается с очередной буквы; служебные можно пропустить, лишних быть не может."""
    position = 0
    for word in words:
        if not word:
            continue
        if position < len(letters) and word[0] == letters[position]:
            position += 1
        elif word in _SKIPPABLE:
            continue
        else:
            return False
    return position == len(letters)


def _shortest_matching_suffix(acronym: str, words: Sequence[str]) -> list[str] | None:
    """Кратчайший суффикс ``words``, читающийся как ``acronym``; ``None``, если такого нет.

    Кратчайший, а не любой: длинный суффикс тоже может совпасть, если лишние слова пропускаемы, и
    тогда в полную форму попадёт хвост предложения.
    """
    for size in range(2, len(words) + 1):
        candidate = list(words[-size:])
        if _initials_match(acronym, candidate):
            return candidate
    return None


def mine_acronyms(texts: Iterable[str]) -> AcronymDictionary:
    """Mine ``Long Form (ACR)`` patterns from the corpus.

    Ties are resolved by support count first and then lexicographically, so the dictionary
    is a pure function of the (unordered) set of input texts.
    """
    votes: dict[str, dict[str, int]] = {}
    surfaces: dict[str, dict[str, int]] = {}
    for text in texts:
        normalized = normalize_text(text)
        for match in _ACRONYM_RE.finditer(normalized):
            long_form_raw = match.group(1).strip()
            acronym_raw = match.group(2).strip()
            words = [token.normal for token in tokenize(long_form_raw)]
            if len(words) < 2:
                continue
            # Окно ровно той ширины, которой требует аббревиатура, — кратчайший суффикс, который
            # ею читается. Прежняя эвристика брала «нужно букв плюс две» и отдавала в словарь весь
            # захват: `AA → interest in account abstraction`, `SBOM → work on software bill of
            # materials`. Такая полная форма ни с чем не сливается — извлекатель никогда не
            # породит кандидата с приставкой «interest in», — то есть слияние происходило и было
            # бесполезным.
            trimmed = _shortest_matching_suffix(acronym_raw, words)
            if trimmed is None:
                continue
            acronym_key = normalize_tokens([acronym_raw])
            long_key = normalize_tokens(trimmed)
            if not acronym_key or not long_key or acronym_key == long_key:
                continue
            votes.setdefault(acronym_key, {})
            votes[acronym_key][long_key] = votes[acronym_key].get(long_key, 0) + 1
            surfaces.setdefault(long_key, {})
            surface = " ".join(trimmed)
            surfaces[long_key][surface] = surfaces[long_key].get(surface, 0) + 1

    entries: dict[str, AcronymEntry] = {}
    for acronym_key in sorted(votes):
        candidates = votes[acronym_key]
        best_key = min(candidates.items(), key=lambda item: (-item[1], item[0]))[0]
        surface_votes = surfaces.get(best_key, {})
        best_surface = (
            min(surface_votes.items(), key=lambda item: (-item[1], item[0]))[0]
            if surface_votes
            else best_key
        )
        entries[acronym_key] = AcronymEntry(
            acronym=acronym_key,
            long_form=best_surface,
            long_form_key=best_key,
            support=candidates[best_key],
        )
    return AcronymDictionary(entries=entries)


@dataclass(frozen=True, slots=True)
class TermNormalizer:
    """Turns raw token sequences into canonical keys, honouring the acronym dictionary."""

    acronyms: AcronymDictionary

    @classmethod
    def from_texts(cls, texts: Iterable[str]) -> TermNormalizer:
        """Build a normaliser whose acronym dictionary is mined from ``texts``."""
        return cls(acronyms=mine_acronyms(texts))

    def key(self, tokens: Sequence[Token] | Sequence[str]) -> str:
        """Canonical key of a term: stemmed, then folded onto its long form if acronym."""
        words = [
            token.normal if isinstance(token, Token) else str(token).lower() for token in tokens
        ]
        raw = normalize_tokens(words)
        return self.acronyms.canonical_key(raw)

    def display(self, tokens: Sequence[Token]) -> str:
        """Human readable surface of a term, preserving acronym casing."""
        return " ".join(
            token.surface if token.is_upper_surface else token.normal for token in tokens
        )
