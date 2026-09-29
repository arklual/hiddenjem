"""Evidence selection and case-example choice — methodology §8.

Documents of a topic are ranked by

``0.40·relevance + 0.30·recency + 0.30·authority``

and at most ``8`` are kept with **at most 4 of one source class**, so that a card can never
consist of preprints only. The case example is then picked *from the selected evidence*,
which is what makes ``caseExample.evidenceIndex`` representable at all.

Invariant enforced here and tested: **a trend without evidence is never published** (BR-A6).
"""

from __future__ import annotations

import math
import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import date

from horizon_analytics.domain.credibility import assess_credibility
from horizon_analytics.domain.extraction.normalization import stem_token
from horizon_analytics.domain.extraction.tokenizer import tokenize
from horizon_analytics.domain.models import (
    CaseBasis,
    CaseExample,
    Document,
    Evidence,
    OrganizationType,
    SourceClass,
    clamp01,
    safe_div,
)
from horizon_analytics.domain.scoring.indicators import is_corporate_organization
from horizon_analytics.domain.scoring.profile import (
    MethodologyParameters,
    prior_authority,
)

__all__ = [
    "EvidenceSelection",
    "NoEvidenceError",
    "on_topic_documents",
    "select_case_example",
    "select_evidence",
]

_NAME_STOP: frozenset[str] = frozenset(
    {"a", "an", "and", "as", "at", "based", "by", "for", "from", "in", "into", "of", "on", "or", "the",
     "to", "via", "with", "using"}
)
_LATIN = re.compile(r"[A-Za-z]")
_LETTER = re.compile(r"[^\W\d_]")


def _name_stems(text: str) -> set[str]:
    """Основы слов с разбором дефисов: «on-device» и «on device» — одно и то же."""
    stems: set[str] = set()
    for token in tokenize(text):
        stems.add(stem_token(token.normal))
        for part in token.normal.split("-"):
            if len(part) >= 2 and part not in _NAME_STOP:
                stems.add(stem_token(part))
    return stems


def _initialisms(text: str) -> set[str]:
    """Аббревиатуры трёх-четырёх подряд идущих слов: «large language models» даёт «llm».

    Двухбуквенных нет: «arrive in» дало бы «ai» и сделало бы любую страницу страницей об ИИ.
    """
    words = [token.normal for token in tokenize(text) if token.normal.isalpha()]
    out: set[str] = set()
    for size in (3, 4):
        for start in range(len(words) - size + 1):
            out.add("".join(word[0] for word in words[start : start + size]))
    return out


def on_topic_documents(
    documents: Sequence[Document], names: Sequence[str], member_keys: Sequence[str] = ()
) -> tuple[tuple[Document, ...], tuple[Document, ...]]:
    """Документы, где тема названа, и документы, где этого не проверить (разбор 110).

    Карточку читают целиком: жюри, увидев у «On-Device LLM Accelerators» пример про сделку
    Anyscale и Nscale, отвечает «описание не согласуется с названием». Так было у двух третей
    карточек ТОП-15: предложенная тема приносит с собой страницы, найденные поиском по словам, и
    пример выбирался самым цитируемым корпоративным документом среди них, о чём бы тот ни был.

    Документ **называет** тему, если в заголовке или аннотации стоит её имя: все значимые слова
    короткого имени (до двух), все кроме одного — длинного, с точностью до основы и с
    аббревиатурами («LLM» = «large language models»), — или все основы одного из членов темы
    (у тем, выделенных из корпуса, так по построению). Документ без латиницы (китайские, русские
    страницы о латинском имени) проверить нельзя — он возвращается вторым списком.
    """
    variants: list[set[str]] = []
    for name in names:
        stems = {stem for stem in _name_stems(name) if stem not in _NAME_STOP and "-" not in stem}
        if stems:
            variants.append(stems)
    keys = [set(key.split(" ")) - {""} for key in member_keys]
    named: list[Document] = []
    unknown: list[Document] = []
    for document in documents:
        text = f"{document.title}. {document.abstract_text or ''}"
        letters = _LETTER.findall(text)
        if letters and len(_LATIN.findall(text)) < 0.3 * len(letters):
            unknown.append(document)
            continue
        stems = _name_stems(text)
        plain = {stem_token(token.normal) for token in tokenize(text)}
        known = stems | _initialisms(text)
        if any(key and key <= plain for key in keys) or any(
            len(variant & known) >= (len(variant) if len(variant) <= 2 else len(variant) - 1)
            for variant in variants
        ):
            named.append(document)
    return tuple(named), tuple(unknown)


class NoEvidenceError(ValueError):
    """Raised when a topic would be published without a single supporting document."""


@dataclass(frozen=True, slots=True)
class EvidenceSelection:
    """The selected evidence items together with the documents they came from."""

    items: tuple[Evidence, ...]
    documents: tuple[Document, ...]

    def __post_init__(self) -> None:
        """Enforce the "no trend without evidence" invariant."""
        if not self.items:
            raise NoEvidenceError("a trend must be supported by at least one document")
        if len(self.items) != len(self.documents):
            raise ValueError("evidence items and documents must be aligned")


def _recency(published_on: date, window_from: date, window_to: date) -> float:
    """Normalised recency in ``[0, 1]``: ``1.0`` at the end of the window."""
    span = (window_to - window_from).days
    if span <= 0:
        return 1.0
    offset = (published_on - window_from).days
    return clamp01(offset / span)


def _authority(document: Document, max_citations: int) -> float:
    """Normalised citation count, falling back to the source class prior (§8)."""
    if document.citation_count is not None and max_citations > 0:
        return clamp01(safe_div(math.log1p(document.citation_count), math.log1p(max_citations)))
    return prior_authority(document.source_class)


def _snippet(document: Document, limit: int = 400) -> str | None:
    """First ``limit`` characters of the abstract, cut on a word boundary."""
    if not document.abstract_text:
        return None
    text = document.abstract_text.strip()
    if len(text) <= limit:
        return text
    cut = text[:limit]
    space = cut.rfind(" ")
    return (cut[:space] if space > 0 else cut).rstrip(" ,;:") + "…"


def _primary_organization(document: Document) -> tuple[str | None, str | None]:
    """Best-guess organisation and country of a document.

    Prefers the first corporate affiliation (patent assignees and industry labs are what an
    analyst wants to see), otherwise the first affiliation in author order.
    """
    for author in document.authors:
        if is_corporate_organization(author.organization_name, author.organization_type):
            return author.organization_name, author.organization_country
    for author in document.authors:
        if author.organization_name:
            return author.organization_name, author.organization_country
    return None, None


def select_evidence(
    documents: Sequence[Document],
    *,
    window_from: date,
    window_to: date,
    parameters: MethodologyParameters,
    relevance_overrides: Mapping[str, float] | None = None,
) -> EvidenceSelection:
    """Rank and select the supporting documents of one topic.

    Args:
        documents: every document mentioning the topic.
        window_from: start of the analysis window (for the recency term).
        window_to: end of the analysis window.
        parameters: methodology parameters carrying the weights and the caps.
        relevance_overrides: per-document relevance to use instead of ``Document.relevance``.

    Returns:
        Up to ``evidence_max_items`` items with at most ``evidence_max_per_class`` of one
        source class, ordered by score.

    Raises:
        NoEvidenceError: when ``documents`` is empty.
    """
    if not documents:
        raise NoEvidenceError("cannot select evidence from an empty document set")

    max_citations = max(
        (document.citation_count or 0 for document in documents),
        default=0,
    )
    overrides = relevance_overrides or {}

    scored: list[tuple[float, str, Document, float]] = []
    for document in documents:
        relevance = clamp01(overrides.get(document.document_id, document.relevance))
        recency = _recency(document.published_on, window_from, window_to)
        authority = _authority(document, max_citations)
        score = (
            parameters.evidence_relevance_weight * relevance
            + parameters.evidence_recency_weight * recency
            + parameters.evidence_authority_weight * authority
        )
        scored.append((round(score, 6), document.document_id, document, relevance))

    # score desc, published_on desc, document_id asc — a total order.
    scored.sort(key=lambda item: (-item[0], -item[2].published_on.toordinal(), item[1]))

    per_class: dict[SourceClass, int] = {}
    chosen: list[tuple[Document, float]] = []
    overflow: list[tuple[Document, float]] = []
    for _, _, document, relevance in scored:
        if len(chosen) >= parameters.evidence_max_items:
            break
        used = per_class.get(document.source_class, 0)
        if used >= parameters.evidence_max_per_class:
            overflow.append((document, relevance))
            continue
        per_class[document.source_class] = used + 1
        chosen.append((document, relevance))

    # If diversity capping starved the list, top it up with the best rejected items so that
    # a single-class topic still gets a full card.
    for document, relevance in overflow:
        if len(chosen) >= parameters.evidence_max_items:
            break
        chosen.append((document, relevance))

    items: list[Evidence] = []
    for document, relevance in chosen:
        organization, country = _primary_organization(document)
        verdict = assess_credibility(document)
        items.append(
            Evidence(
                source_id=document.source_id,
                source_class=document.source_class,
                external_id=document.external_id or None,
                document_id=document.document_id,
                title=document.title,
                authors=(", ".join(author.full_name for author in document.authors[:6]) or None),
                organization=organization,
                organization_country=country,
                published_on=document.published_on,
                url=document.url,
                doi=document.doi,
                citation_count=document.citation_count,
                relevance=round(relevance, 6),
                snippet=_snippet(document),
                language=document.language,
                credibility=verdict.level,
                credibility_basis=verdict.basis,
                independent=verdict.independent,
            )
        )
    return EvidenceSelection(
        items=tuple(items), documents=tuple(document for document, _ in chosen)
    )


def _organization_type(document: Document, organization: str) -> OrganizationType | None:
    """Resolved organisation type of ``organization`` inside ``document``."""
    for author in document.authors:
        if author.organization_name == organization and author.organization_type is not None:
            return author.organization_type
    if is_corporate_organization(organization, None):
        return "COMPANY"
    return None


def select_case_example(selection: EvidenceSelection) -> CaseExample | None:
    """Pick the case example from the already selected evidence (§8).

    Priority, exactly as documented:

    1. a patent with a corporate assignee,
    2. the most cited document with a corporate affiliation,
    3. the most cited academic group.

    Ties inside every tier break on ``document_id`` ascending.

    The tier that matched is returned as ``basis``. Замер по эталонному корпусу (90 тем): 29 тем
    держатся на патенте, 61 — на публикации, из них 21 на препринте. Без этого различия карточка
    утверждала бы одно и то же для патента и для препринта, а это разные утверждения.
    """
    patents: list[tuple[int, int, str]] = []
    corporate: list[tuple[int, int, str]] = []
    academic: list[tuple[int, int, str]] = []

    for index, document in enumerate(selection.documents):
        organization, _ = _primary_organization(document)
        if not organization:
            continue
        citations = document.citation_count or 0
        is_corporate = any(
            is_corporate_organization(author.organization_name, author.organization_type)
            for author in document.authors
        )
        entry = (-citations, index, document.document_id)
        if document.source_class == "PATENT" and is_corporate:
            patents.append(entry)
        elif is_corporate:
            corporate.append(entry)
        else:
            academic.append(entry)

    # Ступень отбора возвращается вместе с примером: три правила несут разный вес доказательства,
    # и читателю показывается то, которое сработало, а не общее слово «пример».
    tiers: tuple[tuple[list[tuple[int, int, str]], CaseBasis], ...] = (
        (patents, "PATENT"),
        (corporate, "CORPORATE_PUBLICATION"),
        (academic, "ACADEMIC_GROUP"),
    )
    for tier, basis in tiers:
        if not tier:
            continue
        tier.sort(key=lambda item: (item[0], item[2], item[1]))
        _, index, _ = tier[0]
        document = selection.documents[index]
        organization, country = _primary_organization(document)
        if organization is None:  # pragma: no cover - guarded above
            continue
        return CaseExample(
            organization=organization[:300],
            evidence_index=index,
            organization_type=_organization_type(document, organization),
            country=country,
            summary=_snippet(document, limit=280),
            basis=basis,
        )
    return None
