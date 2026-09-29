"""N-gram candidate generation with stopword boundaries — methodology §7 step 3.

Candidates are contiguous n-grams of length ``1..4`` that never cross a stopword or a
punctuation boundary. Everything is accumulated in dictionaries and then emitted in sorted
key order, so the result never depends on hash iteration order.

The extractor also accumulates the per-token corpus statistics that YAKE needs
(:class:`CorpusTokenStatistics`); doing it in the same pass avoids re-tokenising the whole
corpus for termhood.
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass, field

from horizon_analytics.domain.extraction.normalization import TermNormalizer
from horizon_analytics.domain.extraction.tokenizer import (
    Token,
    normalize_text,
    split_sentences,
    tokenize,
)
from horizon_analytics.domain.models import Document, Posting

__all__ = [
    "CandidateExtraction",
    "CorpusTokenStatistics",
    "RawCandidate",
    "TokenStatistics",
    "extract_candidates",
]


@dataclass(frozen=True, slots=True)
class TokenStatistics:
    """Corpus statistics of a single token — the raw material of the YAKE features."""

    term: str
    frequency: int
    upper_count: int
    capitalized_count: int
    sentence_indices: tuple[int, ...]
    distinct_left: int
    distinct_right: int

    @property
    def sentence_frequency(self) -> int:
        """Number of distinct sentences the token occurs in."""
        return len(self.sentence_indices)

    @property
    def median_sentence_index(self) -> float:
        """Median of the distinct sentence indices.

        YAKE takes the median over *all* occurrence positions; using distinct sentences
        keeps memory bounded by the number of sentences and changes the value only for
        tokens repeated inside one sentence.
        """
        values = self.sentence_indices
        if not values:
            return 0.0
        middle = len(values) // 2
        if len(values) % 2 == 1:
            return float(values[middle])
        return (values[middle - 1] + values[middle]) / 2.0


@dataclass(frozen=True, slots=True)
class CorpusTokenStatistics:
    """Aggregated token statistics of the whole corpus."""

    tokens: Mapping[str, TokenStatistics]
    total_sentences: int
    max_frequency: int
    mean_frequency: float
    std_frequency: float

    def get(self, term: str) -> TokenStatistics | None:
        """Statistics of one token, if it was seen."""
        return self.tokens.get(term)


@dataclass(slots=True)
class _Accumulator:
    """Mutable accumulator for one candidate key during extraction."""

    key: str
    token_count: int
    postings: dict[str, int] = field(default_factory=dict)
    surfaces: dict[str, int] = field(default_factory=dict)
    upper_hits: int = 0


@dataclass(frozen=True, slots=True)
class RawCandidate:
    """A candidate before termhood scoring and before df filtering."""

    key: str
    surface: str
    surface_forms: tuple[str, ...]
    postings: tuple[Posting, ...]
    token_count: int
    is_acronym: bool

    @property
    def document_frequency(self) -> int:
        """Number of distinct documents containing the candidate."""
        return len(self.postings)

    @property
    def term_frequency(self) -> int:
        """Total occurrences across the corpus."""
        return sum(posting.occurrences for posting in self.postings)


@dataclass(frozen=True, slots=True)
class CandidateExtraction:
    """Result of step 3: candidates, token statistics and filter drop-off counters."""

    candidates: tuple[RawCandidate, ...]
    token_statistics: CorpusTokenStatistics
    drops: Mapping[str, int]
    generated: int


#: Function words a technology name may contain *internally*.
#:
#: Treating every stopword as a hard boundary loses an entire family of real names — "internet of
#: things", "software bill of materials", "quality of service", "proof of stake", "denial of
#: service", "infrastructure as code". Worse than losing them: the boundary leaves the fragment
#: before it ("software bill"), which is meaningless, still looks like a term, and competes for a
#: place in the report — so the defect both hides real topics and manufactures fake ones.
#:
#: Kept deliberately small. Every word added here also admits prepositional phrases that are
#: clauses rather than names ("attack on the network"), so this is the set that buys canonical
#: naming patterns and nothing else.
BRIDGING_WORDS: frozenset[str] = frozenset({"of", "as", "a"})


def _chunks(tokens: Sequence[Token], stopwords: frozenset[str]) -> list[list[Token]]:
    """Split a token stream into maximal runs free of stopwords.

    Bridging words survive inside a run; :func:`_is_emittable` then keeps them off the edges of
    any candidate, so they can join two halves of a name without ever becoming one.
    """
    runs: list[list[Token]] = []
    current: list[Token] = []
    for token in tokens:
        bridging = token.normal in BRIDGING_WORDS
        if not bridging and (
            token.normal in stopwords or (not token.is_alphabetic and token.is_numeric)
        ):
            if current:
                runs.append(current)
                current = []
            continue
        # A run may not open with a bridging word: "of materials" is not the start of a name.
        if bridging and not current:
            continue
        current.append(token)
    if current:
        runs.append(current)
    return runs


def _is_emittable(window: Sequence[Token]) -> bool:
    """Whether a window may become a candidate.

    A bridging word is a joint, never an end: "bill of" and "of materials" are fragments, and a
    window that is nothing but function words is not a name at all.
    """
    if window[0].normal in BRIDGING_WORDS or window[-1].normal in BRIDGING_WORDS:
        return False
    return any(token.normal not in BRIDGING_WORDS for token in window)


def extract_candidates(
    documents: Iterable[Document],
    *,
    normalizer: TermNormalizer,
    stopwords: frozenset[str],
    ngram_min: int = 1,
    ngram_max: int = 4,
) -> CandidateExtraction:
    """Generate candidate terms from a corpus.

    Args:
        documents: the snapshot's documents. Iterated in ``document_id`` order internally,
            so the caller's order cannot influence the result.
        normalizer: supplies the canonical key, including acronym folding (BRULE-7).
        stopwords: hard n-gram boundaries.
        ngram_min: shortest candidate, in tokens.
        ngram_max: longest candidate, in tokens.

    Returns:
        Candidates sorted by key, plus the token statistics YAKE will consume.
    """
    ordered = sorted(documents, key=lambda document: document.document_id)
    accumulators: dict[str, _Accumulator] = {}
    token_frequency: dict[str, int] = {}
    token_upper: dict[str, int] = {}
    token_capitalized: dict[str, int] = {}
    token_sentences: dict[str, set[int]] = {}
    token_left: dict[str, set[str]] = {}
    token_right: dict[str, set[str]] = {}
    sentence_counter = 0
    generated = 0

    for document in ordered:
        text = normalize_text(document.text)
        for sentence in split_sentences(text):
            tokens = tokenize(sentence.text)
            if not tokens:
                continue
            sentence_id = sentence_counter
            for position, token in enumerate(tokens):
                normal = token.normal
                token_frequency[normal] = token_frequency.get(normal, 0) + 1
                if token.is_upper_surface:
                    token_upper[normal] = token_upper.get(normal, 0) + 1
                if token.is_capitalized_surface:
                    token_capitalized[normal] = token_capitalized.get(normal, 0) + 1
                token_sentences.setdefault(normal, set()).add(sentence_id)
                left = tokens[position - 1].normal if position > 0 else ""
                right = tokens[position + 1].normal if position + 1 < len(tokens) else ""
                token_left.setdefault(normal, set()).add(left)
                token_right.setdefault(normal, set()).add(right)

            for chunk in _chunks(tokens, stopwords):
                for size in range(ngram_min, ngram_max + 1):
                    for start in range(0, len(chunk) - size + 1):
                        window = chunk[start : start + size]
                        if not _is_emittable(window):
                            continue
                        generated += 1
                        key = normalizer.key(window)
                        if not key:
                            continue
                        # Эхо скобки: «artificial intelligence (AI)» даёт окну не только термин, но
                        # и `artificial intelligence ai` с `intelligence ai`. Это куски того же
                        # имени, разрезанные там, где стояла скобка, а не отдельные термины.
                        if normalizer.acronyms.echoes_long_form(key):
                            continue
                        accumulator = accumulators.get(key)
                        if accumulator is None:
                            accumulator = _Accumulator(key=key, token_count=size)
                            accumulators[key] = accumulator
                        accumulator.postings[document.document_id] = (
                            accumulator.postings.get(document.document_id, 0) + 1
                        )
                        surface = normalizer.display(window)
                        accumulator.surfaces[surface] = accumulator.surfaces.get(surface, 0) + 1
                        if size == 1 and window[0].is_upper_surface:
                            accumulator.upper_hits += 1
            sentence_counter += 1

    statistics = _finalize_token_statistics(
        token_frequency,
        token_upper,
        token_capitalized,
        token_sentences,
        token_left,
        token_right,
        sentence_counter,
    )

    candidates: list[RawCandidate] = []
    for key in sorted(accumulators):
        accumulator = accumulators[key]
        postings = tuple(
            Posting(document_id=document_id, occurrences=count)
            for document_id, count in sorted(accumulator.postings.items())
        )
        surfaces = tuple(
            surface
            for surface, _ in sorted(
                accumulator.surfaces.items(), key=lambda item: (-item[1], item[0])
            )
        )
        total = sum(accumulator.surfaces.values())
        candidates.append(
            RawCandidate(
                key=key,
                surface=surfaces[0] if surfaces else key,
                surface_forms=surfaces,
                postings=postings,
                token_count=accumulator.token_count,
                is_acronym=accumulator.token_count == 1
                and total > 0
                and accumulator.upper_hits / total >= 0.5,
            )
        )

    return CandidateExtraction(
        candidates=tuple(candidates),
        token_statistics=statistics,
        drops={},
        generated=generated,
    )


def _finalize_token_statistics(
    frequency: Mapping[str, int],
    upper: Mapping[str, int],
    capitalized: Mapping[str, int],
    sentences: Mapping[str, set[int]],
    left: Mapping[str, set[str]],
    right: Mapping[str, set[str]],
    total_sentences: int,
) -> CorpusTokenStatistics:
    """Freeze the mutable accumulators into an immutable statistics object."""
    entries: dict[str, TokenStatistics] = {}
    for term in sorted(frequency):
        entries[term] = TokenStatistics(
            term=term,
            frequency=frequency[term],
            upper_count=upper.get(term, 0),
            capitalized_count=capitalized.get(term, 0),
            sentence_indices=tuple(sorted(sentences.get(term, set()))),
            distinct_left=len(left.get(term, set())),
            distinct_right=len(right.get(term, set())),
        )
    counts = [entry.frequency for entry in entries.values()]
    if counts:
        mean = sum(counts) / len(counts)
        variance = sum((value - mean) ** 2 for value in counts) / len(counts)
        std = variance**0.5
        maximum = max(counts)
    else:
        mean, std, maximum = 0.0, 0.0, 0
    return CorpusTokenStatistics(
        tokens=entries,
        total_sentences=max(1, total_sentences),
        max_frequency=maximum,
        mean_frequency=mean,
        std_frequency=std,
    )
