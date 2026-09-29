#!/usr/bin/env python3
"""Deterministic generator of the Horizon *golden corpus*.

The golden corpus is the frozen, versioned document set that the platform ingests
through the regular ``fixture`` :class:`SourceConnector` (see ADR-0015).  It replaces
mocking: demos, deterministic QA and E2E runs use the real backend on a fixed input,
so the whole pipeline — ingestion, candidate extraction, scoring, narration — is
byte-for-byte reproducible.

Determinism guarantees
----------------------
Every byte of ``documents.jsonl`` and ``manifest.json`` is a pure function of this
file's source code.  Concretely:

* No wall clock.  ``fetchedAt`` is derived from the fixed constant
  :data:`FETCH_BASE`; no ``time.time()``, ``datetime.now()`` or ``date.today()``.
* No ``uuid4``.  ``documentId`` is ``uuid5(NAMESPACE, f"{sourceId}:{externalId}")``,
  and :data:`NAMESPACE` is itself a ``uuid5`` of a constant URL.
* Randomness is seeded and *position independent*: each document draws from its own
  :class:`random.Random`, seeded by ``sha256(SEED | topic | year | class | index)``.
  A document therefore never depends on how many documents were produced before it,
  so adding a topic cannot reshuffle the text of unrelated documents.
* No iteration over unordered containers: every mapping is iterated in a declared
  order and every set is sorted before use.
* Output is sorted by ``(publishedOn, sourceId, externalId)``; the manifest hashes the
  lexicographically sorted ``documentId`` list.
* Floats that reach the output are rounded with :func:`round` before serialisation.

Running the script twice — on any machine, in any order — produces identical files.

Usage
-----
    python3 generate_corpus.py                 # regenerate in place
    python3 generate_corpus.py --out-dir DIR   # write elsewhere (determinism check)
    python3 generate_corpus.py --summary       # print distribution tables

Regenerating is a **reviewed change**: bump :data:`CORPUS_VERSION` and update the
snapshot baselines in the same pull request (ADR-0015).

Python 3.12, standard library only, no network access.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import random
import sys
import uuid
from collections import Counter
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Final, Sequence

# --------------------------------------------------------------------------------------
# Determinism constants
# --------------------------------------------------------------------------------------

#: Master seed.  Mixed into every per-document RNG seed; changing it rebuilds the corpus.
SEED: Final[int] = 20260805

#: UUIDv5 namespace for ``documentId``.  Derived from a constant URL so that the value is
#: reproducible from source rather than being an opaque literal.
NAMESPACE: Final[uuid.UUID] = uuid.uuid5(uuid.NAMESPACE_URL, "https://horizon.dev/fixtures/corpus")

#: Semantic version of the corpus itself.  MUST be bumped whenever output changes.
CORPUS_VERSION: Final[str] = "1.0.0"

#: Inclusive publication window covered by the corpus.
YEAR_FIRST: Final[int] = 2018
YEAR_LAST: Final[int] = 2025

#: Reference "now" used for citation ageing.  The corpus is a 2026-01 snapshot.
YEAR_NOW: Final[int] = 2026

#: Fixed crawl instant; per-document jitter stays inside :data:`FETCH_WINDOW_SECONDS`.
FETCH_BASE: Final[datetime] = datetime(2026, 1, 5, 3, 0, 0, tzinfo=timezone.utc)
FETCH_WINDOW_SECONDS: Final[int] = 6 * 3600

#: Patents lag publications by roughly 18 months (methodology §11) — modelled as 2 years.
PATENT_LAG_YEARS: Final[int] = 2

#: BRULE-1 requires >= 2 documents before a year counts as a topic's first year.
MIN_FIRST_YEAR_DOCS: Final[int] = 2

#: Agreed corpus size band; :func:`validate_tables` refuses to drift outside it.
CORPUS_SIZE_BAND: Final[tuple[int, int]] = (900, 1400)

#: Canonical iteration order for source classes (never iterate the mix dict directly).
SOURCE_CLASS_ORDER: Final[tuple[str, ...]] = (
    "PREPRINT",
    "JOURNAL_ARTICLE",
    "PATENT",
    "CODE_REPOSITORY",
    "NEWS",
)

#: Output file names.
DOCUMENTS_FILE: Final[str] = "documents.jsonl"
MANIFEST_FILE: Final[str] = "manifest.json"

#: Path of the JSON Schema the documents must satisfy (recorded in the manifest).
SCHEMA_REF: Final[str] = "contracts/schemas/document-ingested.event.json"


# --------------------------------------------------------------------------------------
# Data model
# --------------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class Topic:
    """A technology topic that the corpus deliberately encodes.

    The fields drive both the *shape* of the generated evidence (how many documents, in
    which years, from which source classes) and its *content* (which sentence fragments
    the extractive narrator will be able to find).

    Attributes:
        slug: Stable identifier, also used for repository names and deterministic seeds.
        term: Canonical surface form of the technology.
        aliases: Alternative surface forms (excluding the acronym).
        acronym: Short form introduced in abstracts as ``Long Form (ACR)``; ``None`` if
            the topic has no established acronym.
        domain: Technology domain; selects organisation, venue and vocabulary pools.
        first_year: Year of the topic's first document in the corpus.
        profile: Growth profile — one of :data:`PROFILE_SHAPES`.
        volume: Exact number of documents generated for the topic.
        class_mix: Key into :data:`CLASS_MIX` describing the source-class distribution.
        has_patents: Whether the topic produces ``PATENT`` documents at all.
        citation_profile: ``low`` / ``medium`` / ``high`` citations-per-year base.
        anchor_orgs: Organisations that appear disproportionately often — they give the
            trend card a stable ``caseExample``.
        problems: 2–4 problem noun phrases for the "however / remains challenging" cues.
        benefits: 2–4 benefit noun phrases for the "enables / outperforms" cues.
        expectation: Human-readable QA expectation, mirrored in README.md.
        solo_first_year: If true the first year gets exactly one document from a single
            organisation, so BRULE-1 must move the detected first year forward.
    """

    slug: str
    term: str
    aliases: tuple[str, ...]
    acronym: str | None
    domain: str
    first_year: int
    profile: str
    volume: int
    class_mix: str
    has_patents: bool
    citation_profile: str
    anchor_orgs: tuple[str, ...]
    problems: tuple[str, ...]
    benefits: tuple[str, ...]
    expectation: str
    solo_first_year: bool = False


@dataclass(frozen=True, slots=True)
class DocPlan:
    """One planned document: the coordinates that seed its deterministic generation."""

    topic: Topic
    year: int
    source_class: str
    index: int


# --------------------------------------------------------------------------------------
# Organisation registry
# --------------------------------------------------------------------------------------

#: name -> (organizationType, ISO-3166 alpha-2 country).  Single source of truth so the
#: same organisation never appears with two different types or countries.
ORGANIZATIONS: Final[dict[str, tuple[str, str]]] = {
    # --- AI / ML -----------------------------------------------------------------
    "Google DeepMind": ("COMPANY", "GB"),
    "OpenAI": ("COMPANY", "US"),
    "Anthropic": ("COMPANY", "US"),
    "Meta AI Research": ("COMPANY", "US"),
    "Microsoft Research": ("COMPANY", "US"),
    "NVIDIA Research": ("COMPANY", "US"),
    "IBM Research": ("COMPANY", "US"),
    "Huawei Noah's Ark Lab": ("COMPANY", "CN"),
    "Alibaba DAMO Academy": ("COMPANY", "CN"),
    "Naver Labs": ("COMPANY", "KR"),
    "Sber AI Lab": ("COMPANY", "RU"),
    "Yandex Research": ("COMPANY", "RU"),
    "Hugging Face": ("COMPANY", "US"),
    "Mistral AI": ("COMPANY", "FR"),
    "Cohere": ("COMPANY", "CA"),
    "Allen Institute for AI": ("NONPROFIT", "US"),
    "EleutherAI": ("NONPROFIT", "US"),
    "Max Planck Institute for Intelligent Systems": ("RESEARCH_INSTITUTE", "DE"),
    "Barcelona Supercomputing Center": ("RESEARCH_INSTITUTE", "ES"),
    "Fraunhofer IIS": ("RESEARCH_INSTITUTE", "DE"),
    # --- Security ----------------------------------------------------------------
    "CrowdStrike": ("COMPANY", "US"),
    "Palo Alto Networks": ("COMPANY", "US"),
    "Cloudflare": ("COMPANY", "US"),
    "Microsoft Security Response Center": ("COMPANY", "US"),
    "Intel Labs": ("COMPANY", "US"),
    "AMD Research": ("COMPANY", "US"),
    "Arm Research": ("COMPANY", "GB"),
    "Isovalent": ("COMPANY", "US"),
    "Sysdig": ("COMPANY", "US"),
    "Chainguard": ("COMPANY", "US"),
    "Trail of Bits": ("COMPANY", "US"),
    "Fortanix": ("COMPANY", "US"),
    "Thales": ("COMPANY", "FR"),
    "Infineon Technologies": ("COMPANY", "DE"),
    "NXP Semiconductors": ("COMPANY", "NL"),
    "Kaspersky": ("COMPANY", "RU"),
    "Positive Technologies": ("COMPANY", "RU"),
    "Fraunhofer AISEC": ("RESEARCH_INSTITUTE", "DE"),
    "CWI Amsterdam": ("RESEARCH_INSTITUTE", "NL"),
    "Linux Foundation": ("NONPROFIT", "US"),
    "OpenSSF": ("NONPROFIT", "US"),
    "Internet Security Research Group": ("NONPROFIT", "US"),
    "BSI": ("GOVERNMENT", "DE"),
    "ANSSI": ("GOVERNMENT", "FR"),
    "Sandia National Laboratories": ("GOVERNMENT", "US"),
    "NIST": ("GOVERNMENT", "US"),
    # --- Quantum -----------------------------------------------------------------
    "IBM Quantum": ("COMPANY", "US"),
    "Google Quantum AI": ("COMPANY", "US"),
    "IonQ": ("COMPANY", "US"),
    "Quantinuum": ("COMPANY", "GB"),
    "PsiQuantum": ("COMPANY", "US"),
    "Rigetti Computing": ("COMPANY", "US"),
    "QuEra Computing": ("COMPANY", "US"),
    "Atom Computing": ("COMPANY", "US"),
    "Pasqal": ("COMPANY", "FR"),
    "Alice & Bob": ("COMPANY", "FR"),
    "IQM Quantum Computers": ("COMPANY", "FI"),
    "Xanadu": ("COMPANY", "CA"),
    "ID Quantique": ("COMPANY", "CH"),
    "Toshiba Europe": ("COMPANY", "GB"),
    "Origin Quantum": ("COMPANY", "CN"),
    "Nokia Bell Labs": ("COMPANY", "FI"),
    "QuTech": ("RESEARCH_INSTITUTE", "NL"),
    "CEA-Leti": ("RESEARCH_INSTITUTE", "FR"),
    "Max Planck Institute of Quantum Optics": ("RESEARCH_INSTITUTE", "DE"),
    "Fraunhofer IAF": ("RESEARCH_INSTITUTE", "DE"),
    "Oak Ridge National Laboratory": ("GOVERNMENT", "US"),
    "Los Alamos National Laboratory": ("GOVERNMENT", "US"),
    # --- Bio / bioinformatics ------------------------------------------------------
    "Moderna": ("COMPANY", "US"),
    "BioNTech": ("COMPANY", "DE"),
    "Illumina": ("COMPANY", "US"),
    "10x Genomics": ("COMPANY", "US"),
    "Ginkgo Bioworks": ("COMPANY", "US"),
    "Recursion Pharmaceuticals": ("COMPANY", "US"),
    "Insilico Medicine": ("COMPANY", "HK"),
    "Prime Medicine": ("COMPANY", "US"),
    "Beam Therapeutics": ("COMPANY", "US"),
    "Emulate": ("COMPANY", "US"),
    "CN Bio Innovations": ("COMPANY", "GB"),
    "Roche": ("COMPANY", "CH"),
    "AstraZeneca": ("COMPANY", "GB"),
    "Novo Nordisk": ("COMPANY", "DK"),
    "Isomorphic Labs": ("COMPANY", "GB"),
    "EvolutionaryScale": ("COMPANY", "US"),
    "Broad Institute": ("RESEARCH_INSTITUTE", "US"),
    "EMBL-EBI": ("RESEARCH_INSTITUTE", "DE"),
    "Wellcome Sanger Institute": ("RESEARCH_INSTITUTE", "GB"),
    "Institut Pasteur": ("RESEARCH_INSTITUTE", "FR"),
    "Weizmann Institute of Science": ("RESEARCH_INSTITUTE", "IL"),
    "NIH": ("GOVERNMENT", "US"),
    # --- Fintech infrastructure ----------------------------------------------------
    "Visa Research": ("COMPANY", "US"),
    "Mastercard": ("COMPANY", "US"),
    "Stripe": ("COMPANY", "US"),
    "Coinbase": ("COMPANY", "US"),
    "Circle": ("COMPANY", "US"),
    "Chainalysis": ("COMPANY", "US"),
    "Consensys": ("COMPANY", "US"),
    "StarkWare": ("COMPANY", "IL"),
    "Matter Labs": ("COMPANY", "CH"),
    "Polygon Labs": ("COMPANY", "AE"),
    "Fireblocks": ("COMPANY", "IL"),
    "R3": ("COMPANY", "GB"),
    "SWIFT": ("COMPANY", "BE"),
    "Ripple": ("COMPANY", "US"),
    "Nubank": ("COMPANY", "BR"),
    "Adyen": ("COMPANY", "NL"),
    "Sber": ("COMPANY", "RU"),
    "Ethereum Foundation": ("NONPROFIT", "CH"),
    "Bank for International Settlements": ("GOVERNMENT", "CH"),
    "European Central Bank": ("GOVERNMENT", "DE"),
    "Bank of England": ("GOVERNMENT", "GB"),
    "Monetary Authority of Singapore": ("GOVERNMENT", "SG"),
    "Banco Central do Brasil": ("GOVERNMENT", "BR"),
    "Bank of Japan": ("GOVERNMENT", "JP"),
    # --- Energy / materials --------------------------------------------------------
    "CATL": ("COMPANY", "CN"),
    "BYD": ("COMPANY", "CN"),
    "LG Energy Solution": ("COMPANY", "KR"),
    "Samsung SDI": ("COMPANY", "KR"),
    "Panasonic Energy": ("COMPANY", "JP"),
    "Toyota Research Institute": ("COMPANY", "JP"),
    "QuantumScape": ("COMPANY", "US"),
    "Solid Power": ("COMPANY", "US"),
    "Northvolt": ("COMPANY", "SE"),
    "Natron Energy": ("COMPANY", "US"),
    "Faradion": ("COMPANY", "GB"),
    "Oxford PV": ("COMPANY", "GB"),
    "First Solar": ("COMPANY", "US"),
    "Hanwha Qcells": ("COMPANY", "KR"),
    "Siemens Energy": ("COMPANY", "DE"),
    "Nel Hydrogen": ("COMPANY", "NO"),
    "Plug Power": ("COMPANY", "US"),
    "Climeworks": ("COMPANY", "CH"),
    "Carbon Engineering": ("COMPANY", "CA"),
    "NuScale Power": ("COMPANY", "US"),
    "Rolls-Royce SMR": ("COMPANY", "GB"),
    "X-energy": ("COMPANY", "US"),
    "NREL": ("GOVERNMENT", "US"),
    "Argonne National Laboratory": ("GOVERNMENT", "US"),
    "Idaho National Laboratory": ("GOVERNMENT", "US"),
    "Lawrence Berkeley National Laboratory": ("GOVERNMENT", "US"),
    "Fraunhofer ISE": ("RESEARCH_INSTITUTE", "DE"),
    "SINTEF": ("RESEARCH_INSTITUTE", "NO"),
    "VTT Technical Research Centre": ("RESEARCH_INSTITUTE", "FI"),
    "AIST": ("RESEARCH_INSTITUTE", "JP"),
    "CSIRO": ("RESEARCH_INSTITUTE", "AU"),
    # --- Shared academia -----------------------------------------------------------
    "Stanford University": ("UNIVERSITY", "US"),
    "MIT": ("UNIVERSITY", "US"),
    "Carnegie Mellon University": ("UNIVERSITY", "US"),
    "UC Berkeley": ("UNIVERSITY", "US"),
    "UC San Diego": ("UNIVERSITY", "US"),
    "University of Washington": ("UNIVERSITY", "US"),
    "Princeton University": ("UNIVERSITY", "US"),
    "Harvard University": ("UNIVERSITY", "US"),
    "Caltech": ("UNIVERSITY", "US"),
    "Cornell University": ("UNIVERSITY", "US"),
    "University of Michigan": ("UNIVERSITY", "US"),
    "Purdue University": ("UNIVERSITY", "US"),
    "University of Maryland": ("UNIVERSITY", "US"),
    "University of Toronto": ("UNIVERSITY", "CA"),
    "University of Waterloo": ("UNIVERSITY", "CA"),
    "ETH Zurich": ("UNIVERSITY", "CH"),
    "EPFL": ("UNIVERSITY", "CH"),
    "University of Oxford": ("UNIVERSITY", "GB"),
    "University of Cambridge": ("UNIVERSITY", "GB"),
    "Imperial College London": ("UNIVERSITY", "GB"),
    "University College London": ("UNIVERSITY", "GB"),
    "TU Delft": ("UNIVERSITY", "NL"),
    "Radboud University": ("UNIVERSITY", "NL"),
    "TU Munich": ("UNIVERSITY", "DE"),
    "KIT": ("UNIVERSITY", "DE"),
    "Ruhr University Bochum": ("UNIVERSITY", "DE"),
    "TU Graz": ("UNIVERSITY", "AT"),
    "University of Innsbruck": ("UNIVERSITY", "AT"),
    "KU Leuven": ("UNIVERSITY", "BE"),
    "Politecnico di Milano": ("UNIVERSITY", "IT"),
    "KTH Royal Institute of Technology": ("UNIVERSITY", "SE"),
    "Chalmers University of Technology": ("UNIVERSITY", "SE"),
    "Karolinska Institutet": ("UNIVERSITY", "SE"),
    "University of Copenhagen": ("UNIVERSITY", "DK"),
    "Tsinghua University": ("UNIVERSITY", "CN"),
    "Peking University": ("UNIVERSITY", "CN"),
    "Fudan University": ("UNIVERSITY", "CN"),
    "Shanghai Jiao Tong University": ("UNIVERSITY", "CN"),
    "University of Science and Technology of China": ("UNIVERSITY", "CN"),
    "Chinese Academy of Sciences": ("RESEARCH_INSTITUTE", "CN"),
    "KAIST": ("UNIVERSITY", "KR"),
    "Ulsan National Institute of Science and Technology": ("UNIVERSITY", "KR"),
    "University of Tokyo": ("UNIVERSITY", "JP"),
    "RIKEN": ("RESEARCH_INSTITUTE", "JP"),
    "National University of Singapore": ("UNIVERSITY", "SG"),
    "Nanyang Technological University": ("UNIVERSITY", "SG"),
    "A*STAR": ("RESEARCH_INSTITUTE", "SG"),
    "Technion": ("UNIVERSITY", "IL"),
    "IIT Bombay": ("UNIVERSITY", "IN"),
    "Moscow Institute of Physics and Technology": ("UNIVERSITY", "RU"),
    "HSE University": ("UNIVERSITY", "RU"),
    "Universidade de São Paulo": ("UNIVERSITY", "BR"),
    "University of Sydney": ("UNIVERSITY", "AU"),
    "University of Adelaide": ("UNIVERSITY", "AU"),
    "Inria": ("RESEARCH_INSTITUTE", "FR"),
}

#: Country names used in NEWS prose (kept in sync with :data:`ORGANIZATIONS`).
COUNTRY_NAMES: Final[dict[str, str]] = {
    "AE": "the United Arab Emirates",
    "AT": "Austria",
    "AU": "Australia",
    "BE": "Belgium",
    "BR": "Brazil",
    "CA": "Canada",
    "CH": "Switzerland",
    "CN": "China",
    "DE": "Germany",
    "DK": "Denmark",
    "ES": "Spain",
    "FI": "Finland",
    "FR": "France",
    "GB": "the United Kingdom",
    "GR": "Greece",
    "HK": "Hong Kong",
    "IL": "Israel",
    "IN": "India",
    "IT": "Italy",
    "JP": "Japan",
    "KR": "South Korea",
    "NL": "the Netherlands",
    "NO": "Norway",
    "RU": "Russia",
    "SE": "Sweden",
    "SG": "Singapore",
    "US": "the United States",
}

# --------------------------------------------------------------------------------------
# Domain pools
# --------------------------------------------------------------------------------------

#: Organisations that may author a document in a given domain (names from ORGANIZATIONS).
DOMAIN_ORGS: Final[dict[str, tuple[str, ...]]] = {
    "ai": (
        "Google DeepMind", "OpenAI", "Anthropic", "Meta AI Research", "Microsoft Research",
        "NVIDIA Research", "IBM Research", "Huawei Noah's Ark Lab", "Alibaba DAMO Academy",
        "Naver Labs", "Sber AI Lab", "Yandex Research", "Hugging Face", "Mistral AI",
        "Cohere", "Allen Institute for AI", "EleutherAI",
        "Max Planck Institute for Intelligent Systems", "Barcelona Supercomputing Center",
        "Fraunhofer IIS", "Inria", "Stanford University", "MIT", "Carnegie Mellon University",
        "UC Berkeley", "University of Washington", "Princeton University", "ETH Zurich",
        "EPFL", "University of Oxford", "University of Cambridge", "Tsinghua University",
        "Peking University", "KAIST", "University of Tokyo", "TU Munich",
        "University of Toronto", "Technion", "IIT Bombay", "National University of Singapore",
        "Moscow Institute of Physics and Technology", "RIKEN", "A*STAR", "NIST",
        "Nokia Bell Labs",
    ),
    "security": (
        "CrowdStrike", "Palo Alto Networks", "Cloudflare", "Microsoft Security Response Center",
        "Intel Labs", "AMD Research", "Arm Research", "Isovalent", "Sysdig", "Chainguard",
        "Trail of Bits", "Fortanix", "Thales", "Infineon Technologies", "NXP Semiconductors",
        "Kaspersky", "Positive Technologies", "Fraunhofer AISEC", "CWI Amsterdam",
        "Linux Foundation", "OpenSSF", "Internet Security Research Group", "BSI", "ANSSI",
        "Sandia National Laboratories", "NIST", "ETH Zurich", "KU Leuven",
        "Ruhr University Bochum", "TU Graz", "University of Michigan", "Purdue University",
        "UC San Diego", "Radboud University", "Tsinghua University",
        "Shanghai Jiao Tong University", "Nanyang Technological University", "Technion",
        "University of Adelaide", "Inria", "IBM Research", "Microsoft Research",
        "UC Berkeley",
    ),
    "quantum": (
        "IBM Quantum", "Google Quantum AI", "IonQ", "Quantinuum", "PsiQuantum",
        "Rigetti Computing", "QuEra Computing", "Atom Computing", "Pasqal", "Alice & Bob",
        "IQM Quantum Computers", "Xanadu", "ID Quantique", "Toshiba Europe", "Origin Quantum",
        "Nokia Bell Labs", "Microsoft Research", "Intel Labs", "QuTech", "CEA-Leti",
        "Max Planck Institute of Quantum Optics", "Fraunhofer IAF", "NIST",
        "Sandia National Laboratories", "Oak Ridge National Laboratory",
        "Los Alamos National Laboratory", "TU Delft", "University of Maryland",
        "Harvard University", "MIT", "Caltech", "University of Innsbruck",
        "University of Science and Technology of China", "University of Sydney",
        "University of Waterloo", "ETH Zurich", "University of Copenhagen",
        "Chalmers University of Technology", "Tsinghua University", "RIKEN",
        "Chinese Academy of Sciences",
    ),
    "bio": (
        "Moderna", "BioNTech", "Illumina", "10x Genomics", "Ginkgo Bioworks",
        "Recursion Pharmaceuticals", "Insilico Medicine", "Prime Medicine",
        "Beam Therapeutics", "Emulate", "CN Bio Innovations", "Roche", "AstraZeneca",
        "Novo Nordisk", "Isomorphic Labs", "EvolutionaryScale", "Google DeepMind",
        "Meta AI Research", "Broad Institute", "EMBL-EBI", "Wellcome Sanger Institute",
        "Institut Pasteur", "Weizmann Institute of Science", "NIH", "RIKEN", "A*STAR",
        "Karolinska Institutet", "Harvard University", "Stanford University", "MIT",
        "University of Cambridge", "ETH Zurich", "KU Leuven", "Peking University",
        "Fudan University", "University of Tokyo", "Universidade de São Paulo",
        "University of Toronto", "Chinese Academy of Sciences",
    ),
    "fintech": (
        "Visa Research", "Mastercard", "Stripe", "Coinbase", "Circle", "Chainalysis",
        "Consensys", "StarkWare", "Matter Labs", "Polygon Labs", "Fireblocks", "R3", "SWIFT",
        "Ripple", "Nubank", "Adyen", "Sber", "IBM Research", "Ethereum Foundation",
        "Linux Foundation", "Bank for International Settlements", "European Central Bank",
        "Bank of England", "Monetary Authority of Singapore", "Banco Central do Brasil",
        "Bank of Japan", "Stanford University", "UC Berkeley", "Imperial College London",
        "University College London", "ETH Zurich", "Technion",
        "National University of Singapore", "HSE University", "Cornell University",
    ),
    "energy": (
        "CATL", "BYD", "LG Energy Solution", "Samsung SDI", "Panasonic Energy",
        "Toyota Research Institute", "QuantumScape", "Solid Power", "Northvolt",
        "Natron Energy", "Faradion", "Oxford PV", "First Solar", "Hanwha Qcells",
        "Siemens Energy", "Nel Hydrogen", "Plug Power", "Climeworks", "Carbon Engineering",
        "NuScale Power", "Rolls-Royce SMR", "X-energy", "NREL", "Argonne National Laboratory",
        "Oak Ridge National Laboratory", "Idaho National Laboratory",
        "Lawrence Berkeley National Laboratory", "Fraunhofer ISE", "SINTEF",
        "VTT Technical Research Centre", "AIST", "CSIRO", "KIT", "TU Delft",
        "KTH Royal Institute of Technology", "Tsinghua University", "Stanford University",
        "MIT", "University of Tokyo", "EPFL",
        "Ulsan National Institute of Science and Technology", "Politecnico di Milano",
        "Chinese Academy of Sciences",
    ),
}

#: Peer-reviewed venues per domain: (name, venue type).
DOMAIN_VENUES: Final[dict[str, tuple[tuple[str, str], ...]]] = {
    "ai": (
        ("NeurIPS", "CONFERENCE"), ("ICML", "CONFERENCE"), ("ICLR", "CONFERENCE"),
        ("ACL", "CONFERENCE"), ("EMNLP", "CONFERENCE"), ("CVPR", "CONFERENCE"),
        ("AAAI", "CONFERENCE"), ("Transactions on Machine Learning Research", "JOURNAL"),
        ("Journal of Machine Learning Research", "JOURNAL"),
        ("Nature Machine Intelligence", "JOURNAL"),
        ("IEEE Transactions on Pattern Analysis and Machine Intelligence", "JOURNAL"),
    ),
    "security": (
        ("USENIX Security Symposium", "CONFERENCE"), ("ACM CCS", "CONFERENCE"),
        ("IEEE Symposium on Security and Privacy", "CONFERENCE"), ("NDSS", "CONFERENCE"),
        ("ACSAC", "CONFERENCE"), ("IEEE EuroS&P", "CONFERENCE"),
        ("IEEE Transactions on Dependable and Secure Computing", "JOURNAL"),
        ("Computers & Security", "JOURNAL"), ("Journal of Cybersecurity", "JOURNAL"),
        ("IEEE Transactions on Information Forensics and Security", "JOURNAL"),
    ),
    "quantum": (
        ("Nature", "JOURNAL"), ("Science", "JOURNAL"), ("Nature Physics", "JOURNAL"),
        ("Physical Review X", "JOURNAL"), ("Physical Review Letters", "JOURNAL"),
        ("PRX Quantum", "JOURNAL"), ("npj Quantum Information", "JOURNAL"),
        ("Quantum", "JOURNAL"), ("Nature Communications", "JOURNAL"),
        ("IEEE International Conference on Quantum Computing and Engineering", "CONFERENCE"),
    ),
    "bio": (
        ("Nature Biotechnology", "JOURNAL"), ("Nature Methods", "JOURNAL"),
        ("Cell", "JOURNAL"), ("Nucleic Acids Research", "JOURNAL"),
        ("Bioinformatics", "JOURNAL"), ("Genome Biology", "JOURNAL"),
        ("Nature Communications", "JOURNAL"), ("eLife", "JOURNAL"),
        ("Lab on a Chip", "JOURNAL"), ("Nature Medicine", "JOURNAL"),
        ("RECOMB", "CONFERENCE"),
    ),
    "fintech": (
        ("ACM Advances in Financial Technologies", "CONFERENCE"),
        ("Financial Cryptography and Data Security", "CONFERENCE"),
        ("IEEE International Conference on Blockchain and Cryptocurrency", "CONFERENCE"),
        ("Ledger", "JOURNAL"), ("Journal of Network and Computer Applications", "JOURNAL"),
        ("Journal of Payments Strategy & Systems", "JOURNAL"),
        ("IEEE Transactions on Network and Service Management", "JOURNAL"),
        ("Journal of Financial Market Infrastructures", "JOURNAL"),
    ),
    "energy": (
        ("Nature Energy", "JOURNAL"), ("Joule", "JOURNAL"),
        ("Advanced Energy Materials", "JOURNAL"),
        ("Energy & Environmental Science", "JOURNAL"), ("ACS Energy Letters", "JOURNAL"),
        ("Journal of Power Sources", "JOURNAL"), ("Applied Energy", "JOURNAL"),
        ("Nature Materials", "JOURNAL"),
        ("International Journal of Hydrogen Energy", "JOURNAL"),
        ("Nuclear Engineering and Design", "JOURNAL"),
    ),
}

#: News outlets per domain: (outlet name, host).
DOMAIN_OUTLETS: Final[dict[str, tuple[tuple[str, str], ...]]] = {
    "ai": (
        ("IEEE Spectrum", "spectrum.ieee.org"), ("MIT Technology Review", "technologyreview.com"),
        ("Wired", "wired.com"), ("The Register", "theregister.com"),
        ("Reuters Technology", "reuters.com"), ("Nikkei Asia", "asia.nikkei.com"),
    ),
    "security": (
        ("Dark Reading", "darkreading.com"), ("SecurityWeek", "securityweek.com"),
        ("The Register", "theregister.com"), ("Ars Technica", "arstechnica.com"),
        ("Reuters Technology", "reuters.com"),
    ),
    "quantum": (
        ("IEEE Spectrum", "spectrum.ieee.org"), ("Nature News", "nature.com"),
        ("Physics World", "physicsworld.com"), ("Science|Business", "sciencebusiness.net"),
        ("Nikkei Asia", "asia.nikkei.com"),
    ),
    "bio": (
        ("Nature News", "nature.com"), ("STAT News", "statnews.com"),
        ("Chemical & Engineering News", "cen.acs.org"),
        ("MIT Technology Review", "technologyreview.com"), ("Science|Business", "sciencebusiness.net"),
    ),
    "fintech": (
        ("Financial Times Technology", "ft.com"), ("CoinDesk", "coindesk.com"),
        ("The Block", "theblock.co"), ("Reuters Technology", "reuters.com"),
        ("American Banker", "americanbanker.com"),
    ),
    "energy": (
        ("Energy Monitor", "energymonitor.ai"), ("IEEE Spectrum", "spectrum.ieee.org"),
        ("Chemical & Engineering News", "cen.acs.org"),
        ("Reuters Technology", "reuters.com"), ("Handelsblatt", "handelsblatt.com"),
    ),
}

#: Patent offices: (office name, patent-number prefix, kind codes).
PATENT_OFFICES: Final[tuple[tuple[str, str, tuple[str, ...]], ...]] = (
    ("USPTO", "US", ("B2", "B2", "B2", "B1", "A1")),
    ("EPO", "EP", ("B1", "A1")),
    ("CNIPA", "CN", ("B", "A")),
    ("JPO", "JP", ("B2",)),
    ("WIPO", "WO", ("A1",)),
)

#: arXiv primary categories per domain.
DOMAIN_ARXIV: Final[dict[str, tuple[str, ...]]] = {
    "ai": ("cs.LG", "cs.CL", "cs.AI", "stat.ML", "cs.CV"),
    "security": ("cs.CR", "cs.SE", "cs.DC", "cs.OS"),
    "quantum": ("quant-ph", "cond-mat.mes-hall", "physics.atom-ph"),
    "bio": ("q-bio.BM", "q-bio.GN", "q-bio.QM", "cs.LG"),
    "fintech": ("cs.CR", "cs.DC", "q-fin.TR", "econ.GN"),
    "energy": ("cond-mat.mtrl-sci", "physics.app-ph", "physics.chem-ph", "physics.soc-ph"),
}

#: OpenAlex-style concept identifiers per domain: (code, label).
DOMAIN_CONCEPTS: Final[dict[str, tuple[tuple[str, str], ...]]] = {
    "ai": (("C154945302", "Artificial intelligence"), ("C119857082", "Machine learning"),
           ("C41008148", "Computer science"), ("C204321447", "Natural language processing")),
    "security": (("C38652104", "Computer security"), ("C48044578", "Cryptography"),
                 ("C41008148", "Computer science"), ("C79974875", "Cloud computing")),
    "quantum": (("C121332964", "Physics"), ("C84114770", "Quantum information"),
                ("C58053490", "Quantum mechanics"), ("C41008148", "Computer science")),
    "bio": (("C104317684", "Genetics"), ("C70721500", "Computational biology"),
            ("C86803240", "Biology"), ("C185592680", "Chemistry")),
    "fintech": (("C162324750", "Economics"), ("C10138342", "Finance"),
                ("C38652104", "Computer security"), ("C41008148", "Computer science")),
    "energy": (("C185592680", "Chemistry"), ("C192562407", "Materials science"),
               ("C127413603", "Engineering"), ("C42360764", "Energy engineering")),
}

#: CPC classification codes per domain: (code, label).
DOMAIN_CPC: Final[dict[str, tuple[tuple[str, str], ...]]] = {
    "ai": (("G06N3/0455", "Neural network architectures"), ("G06N3/084", "Backpropagation"),
           ("G06N20/00", "Machine learning"), ("G06F40/40", "Natural language analysis")),
    "security": (("H04L9/00", "Cryptographic mechanisms"), ("H04L63/1416", "Intrusion detection"),
                 ("G06F21/57", "Platform integrity"), ("H04L9/0852", "Quantum cryptography")),
    "quantum": (("G06N10/70", "Quantum error correction"), ("G06N10/40", "Quantum hardware"),
                ("H04L9/0852", "Quantum key distribution"), ("B82Y10/00", "Nanotechnology for IT")),
    "bio": (("C12N15/11", "Nucleic acid constructs"), ("C12Q1/6869", "Nucleic acid sequencing"),
            ("G16B30/00", "Sequence analysis"), ("B01L3/00", "Microfluidic vessels"),
            ("A61K39/00", "Medicinal preparations")),
    "fintech": (("G06Q20/38", "Payment protocols"), ("H04L9/32", "Authentication"),
                ("G06Q40/04", "Trading and exchange")),
    "energy": (("H01M10/0562", "Solid electrolytes"), ("H01M4/58", "Electrode materials"),
               ("H01L31/0725", "Tandem photovoltaic cells"), ("C25B1/04", "Water electrolysis"),
               ("G21C1/00", "Nuclear reactor types")),
}

#: GitHub-style repository owners per domain.
DOMAIN_REPO_OWNERS: Final[dict[str, tuple[str, ...]]] = {
    "ai": ("huggingface", "google-research", "facebookresearch", "nvidia-labs",
           "eleutherai", "stanford-crfm", "mlfoundations", "allenai"),
    "security": ("cilium", "openssf", "sigstore", "aquasecurity", "open-quantum-safe",
                 "confidential-containers", "trailofbits", "cyclonedx"),
    "quantum": ("qiskit-community", "quantumlib", "xanaduai", "qutech-delft",
                "quera-computing", "pasqal-io"),
    "bio": ("facebookresearch", "broadinstitute", "scverse", "nf-core", "deepmind-bio",
            "biopython"),
    "fintech": ("ethereum", "matter-labs", "starkware-libs", "hyperledger",
                "openzeppelin", "paradigmxyz"),
    "energy": ("nrel", "pybamm-team", "materialsproject", "openenergyplatform", "pvlib"),
}

#: GitHub-style repository topics per domain.
DOMAIN_REPO_TOPICS: Final[dict[str, tuple[str, ...]]] = {
    "ai": ("machine-learning", "deep-learning", "nlp", "pytorch"),
    "security": ("security", "cryptography", "supply-chain", "observability"),
    "quantum": ("quantum-computing", "physics", "simulation"),
    "bio": ("bioinformatics", "computational-biology", "genomics"),
    "fintech": ("blockchain", "cryptography", "payments", "zero-knowledge"),
    "energy": ("energy", "materials-science", "simulation"),
}

#: Primary programming languages used in repository metadata.
REPO_LANGUAGES: Final[tuple[str, ...]] = ("Python", "Rust", "C++", "Go", "TypeScript", "Julia")

# --------------------------------------------------------------------------------------
# Vocabulary slots (per domain) used by the title / abstract templates
# --------------------------------------------------------------------------------------

DOMAIN_SLOTS: Final[dict[str, dict[str, tuple[str, ...]]]] = {
    "ai": {
        "applications": ("production question answering", "code assistance",
                         "multilingual summarisation", "on-device assistants",
                         "enterprise search", "agentic workflows"),
        "artifacts": ("pipeline", "serving stack", "training recipe", "inference engine",
                      "evaluation harness"),
        "settings": ("long-context workloads", "low-latency serving", "multi-tenant clusters",
                     "constrained edge hardware", "streaming inference"),
        "techniques": ("grouped-query attention", "low-rank adaptation",
                       "quantisation-aware training", "curriculum sampling",
                       "knowledge distillation"),
        "aspects": ("sample efficiency", "throughput", "calibration", "memory footprint",
                    "robustness"),
        "metrics": ("end-to-end latency", "peak memory", "training cost", "cost per token",
                    "error rate"),
        "baselines": ("strong transformer baselines", "the published state of the art",
                      "a tuned dense baseline", "prior distillation pipelines"),
        "benchmarks": ("a held-out multilingual benchmark", "long-context retrieval benchmarks",
                       "an internal production trace", "a public instruction-following suite"),
    },
    "security": {
        "applications": ("cloud workload protection", "supply-chain assurance",
                         "critical infrastructure", "identity federation",
                         "regulated data processing"),
        "artifacts": ("deployment", "control plane", "toolchain", "detection pipeline",
                      "reference implementation"),
        "settings": ("multi-cloud estates", "air-gapped environments", "high-throughput ingress",
                     "regulated industries", "legacy protocol stacks"),
        "techniques": ("remote attestation", "formal verification", "policy-as-code",
                       "a hardware root of trust", "differential fuzzing"),
        "aspects": ("attack surface", "false-positive rate", "operational overhead",
                    "auditability", "recovery time"),
        "metrics": ("mean time to detect", "false-positive rate", "handshake overhead",
                    "audit effort"),
        "baselines": ("signature-based detection", "the incumbent perimeter model",
                      "a manually maintained inventory", "agent-based monitoring"),
        "benchmarks": ("a red-team exercise across three estates", "a public vulnerability corpus",
                       "a production telemetry replay"),
    },
    "quantum": {
        "applications": ("fault-tolerant computation", "quantum simulation of molecules",
                         "secure communication links", "optimisation on near-term devices",
                         "quantum sensing"),
        "artifacts": ("device", "control stack", "decoder", "protocol", "fabrication process"),
        "settings": ("cryogenic operation", "noisy intermediate-scale devices",
                     "metropolitan fibre links", "multi-qubit arrays"),
        "techniques": ("dynamical decoupling", "randomised benchmarking", "pulse-level control",
                       "error mitigation", "cryo-CMOS control electronics"),
        "aspects": ("coherence time", "gate fidelity", "readout error", "scalability",
                    "calibration overhead"),
        "metrics": ("logical error rate", "gate infidelity", "calibration time",
                    "qubit overhead"),
        "baselines": ("superconducting transmon baselines", "the reference surface-code decoder",
                      "previously reported devices"),
        "benchmarks": ("a seventy-two-qubit testbed", "randomised benchmarking sequences",
                       "a repetition-code experiment"),
    },
    "bio": {
        "applications": ("therapeutic design", "clinical diagnostics",
                         "agricultural biotechnology", "drug safety screening",
                         "population genomics"),
        "artifacts": ("assay", "pipeline", "construct", "platform", "protocol"),
        "settings": ("primary human cells", "in vivo models", "clinical cohorts",
                     "high-throughput screening", "GMP manufacturing"),
        "techniques": ("lipid nanoparticle delivery", "deep mutational scanning",
                       "single-cell barcoding", "cryo-electron microscopy",
                       "microfluidic patterning"),
        "aspects": ("specificity", "reproducibility", "throughput", "translational relevance",
                    "off-target activity"),
        "metrics": ("off-target rate", "assay variance", "turnaround time", "cost per sample"),
        "baselines": ("established CRISPR nuclease workflows", "bulk sequencing protocols",
                      "animal model readouts"),
        "benchmarks": ("a twelve-donor cohort", "a public reference dataset",
                       "a blinded validation panel"),
    },
    "fintech": {
        "applications": ("cross-border settlement", "retail payments", "custody infrastructure",
                         "market surveillance", "tokenised securities"),
        "artifacts": ("protocol", "settlement layer", "reference implementation",
                      "clearing pipeline", "wallet stack"),
        "settings": ("high-volume retail flows", "regulated custody",
                     "public permissionless networks", "bank-operated infrastructure"),
        "techniques": ("recursive proof composition", "threshold signatures",
                       "deterministic replay", "batch aggregation",
                       "hardware-backed key custody"),
        "aspects": ("settlement finality", "throughput", "custody risk", "compliance overhead",
                    "capital efficiency"),
        "metrics": ("settlement latency", "cost per transaction", "proving time",
                    "reconciliation effort"),
        "baselines": ("correspondent banking rails", "an optimistic rollup baseline",
                      "batch net settlement"),
        "benchmarks": ("a two-million-transaction replay", "a regulated pilot with four banks",
                       "public mainnet traces"),
    },
    "energy": {
        "applications": ("grid-scale storage", "electric mobility",
                         "industrial decarbonisation", "utility-scale generation",
                         "off-grid power"),
        "artifacts": ("cell", "stack", "module", "pilot plant", "process line"),
        "settings": ("pilot-line production", "field deployment", "accelerated ageing tests",
                     "sub-zero operation", "utility-scale installations"),
        "techniques": ("roll-to-roll coating", "atomic layer deposition",
                       "operando spectroscopy", "accelerated cycling protocols",
                       "techno-economic modelling"),
        "aspects": ("cycle life", "energy density", "levelised cost", "manufacturability",
                    "degradation"),
        "metrics": ("capacity fade", "levelised cost", "areal resistance",
                    "energy consumption per tonne"),
        "baselines": ("incumbent lithium-ion cells", "single-junction reference devices",
                      "alkaline electrolysers", "amine scrubbing"),
        "benchmarks": ("a one-thousand-cycle protocol", "a six-month field trial",
                       "an accredited certification lab"),
    },
}

#: Deliberately generic n-grams.  They occur in *every* topic, so a correct pipeline must
#: reject them via the common-term blacklist / low coherence — that is what they are for.
NOISE_SENTENCES: Final[tuple[str, ...]] = (
    "In this paper we describe a novel approach to {aspect}.",
    "Experimental results confirm that the proposed method is practical.",
    "Extensive experiments show a significant improvement over the state of the art.",
    "The proposed method is compared against the state of the art on {benchmark}.",
    "A broader evaluation is left to future work.",
    "Experimental results on {benchmark} support the proposed method.",
)

#: The generic n-grams themselves, recorded in the manifest so tests can assert on them.
NOISE_NGRAMS: Final[tuple[str, ...]] = (
    "novel approach",
    "proposed method",
    "experimental results",
    "state of the art",
    "significant improvement",
    "extensive experiments",
    "future work",
)

# --------------------------------------------------------------------------------------
# Person-name pools (mixed scripts and diacritics keep ensure_ascii=False meaningful)
# --------------------------------------------------------------------------------------

GIVEN_NAMES: Final[tuple[str, ...]] = (
    "Wei", "Ming", "Yuki", "Hiroshi", "Sofia", "José", "Ana", "Lucas", "Mateus", "Anna",
    "Elena", "Dmitry", "Ivan", "Olga", "Jonas", "Lars", "Ingrid", "Émile", "Céline", "Chloé",
    "François", "Antoine", "Marta", "Álvaro", "Carmen", "Nikolaos", "Eleni", "Ahmed",
    "Fatima", "Omar", "Aisha", "Rajesh", "Priya", "Ananya", "Arjun", "Ji-won", "Min-seo",
    "Seung-hoon", "Linh", "Sarah", "Michael", "Emily", "David", "Rachel", "Daniel", "Laura",
    "Thomas", "Julia", "Katarzyna", "Piotr", "Zsófia", "Bence", "Mehmet", "Elif", "Miguel",
    "Isabel", "Nadia", "Yara", "Tomás", "Amara", "Henrik", "Freya", "Noah", "Mia",
)

FAMILY_NAMES: Final[tuple[str, ...]] = (
    "Zhang", "Wang", "Li", "Chen", "Liu", "Yang", "Huang", "Tanaka", "Sato", "Suzuki",
    "Watanabe", "Kim", "Park", "Lee", "Choi", "Silva", "Souza", "Oliveira", "Rossi",
    "Ferrari", "Conti", "Müller", "Schmidt", "Weber", "Fischer", "Hoffmann", "Dubois",
    "Lefèvre", "Moreau", "Bernard", "García", "Rodríguez", "Fernández", "Martínez", "López",
    "Novák", "Kovács", "Nowak", "Kowalski", "Petrov", "Ivanov", "Sokolova", "Volkov",
    "Nielsen", "Andersen", "Larsson", "Lindqvist", "Virtanen", "Korhonen", "Jansen",
    "de Vries", "van Dijk", "O'Connor", "Murphy", "Walsh", "Papadopoulos", "Nikolaidis",
    "Yılmaz", "Demir", "Al-Farsi", "Haddad", "Okafor", "Mensah", "Sharma", "Iyer",
    "Banerjee", "Rao", "Nguyen", "Pham", "Tran",
)

#: Fragments used to synthesise plausible method / system names.
METHOD_PREFIXES: Final[tuple[str, ...]] = (
    "Hyper", "Poly", "Neuro", "Meta", "Omni", "Proto", "Flux", "Quanta", "Helio", "Vertex",
    "Atlas", "Nimbus", "Cascade", "Lattice", "Beacon", "Prism", "Halo", "Orbit", "Tempo",
    "Vantage", "Aurora", "Cinder", "Delta", "Ember",
)
METHOD_SUFFIXES: Final[tuple[str, ...]] = (
    "Net", "Former", "Flow", "Core", "Graph", "Bench", "Kit", "Stack", "Wave", "Loop",
    "Forge", "Scope", "Bridge", "Anchor", "Prime", "Sync", "Lite", "Mesh",
)

# --------------------------------------------------------------------------------------
# Growth profiles and source-class mixes
# --------------------------------------------------------------------------------------

#: Relative document weight per year offset from ``first_year``.  The last value repeats if
#: the topic's span is longer than the shape.
PROFILE_SHAPES: Final[dict[str, tuple[float, ...]]] = {
    "embryonic": (1.0, 2.2, 3.6, 5.0),
    "emerging": (1.0, 1.9, 3.1, 4.6, 6.2, 7.8, 9.2, 10.0),
    "accelerating": (1.0, 2.1, 4.2, 7.6, 12.5, 19.0, 27.5, 38.0),
    "maturing": (1.0, 2.6, 4.6, 6.2, 7.1, 7.4, 7.5, 7.5),
    "declining": (2.0, 4.2, 7.4, 8.0, 5.6, 3.4, 2.0, 1.2),
}

#: Source-class distribution archetypes.  Each mapping sums to 1.0.
CLASS_MIX: Final[dict[str, dict[str, float]]] = {
    "software": {"PREPRINT": 0.34, "JOURNAL_ARTICLE": 0.26, "PATENT": 0.10,
                 "CODE_REPOSITORY": 0.18, "NEWS": 0.12},
    "software_open": {"PREPRINT": 0.42, "JOURNAL_ARTICLE": 0.24, "PATENT": 0.00,
                      "CODE_REPOSITORY": 0.22, "NEWS": 0.12},
    "security": {"PREPRINT": 0.26, "JOURNAL_ARTICLE": 0.28, "PATENT": 0.14,
                 "CODE_REPOSITORY": 0.14, "NEWS": 0.18},
    "hardware": {"PREPRINT": 0.20, "JOURNAL_ARTICLE": 0.36, "PATENT": 0.26,
                 "CODE_REPOSITORY": 0.04, "NEWS": 0.14},
    "biomed": {"PREPRINT": 0.22, "JOURNAL_ARTICLE": 0.42, "PATENT": 0.18,
               "CODE_REPOSITORY": 0.06, "NEWS": 0.12},
    "fintech": {"PREPRINT": 0.20, "JOURNAL_ARTICLE": 0.20, "PATENT": 0.12,
                "CODE_REPOSITORY": 0.26, "NEWS": 0.22},
}

#: Base citations per year of age, by citation profile.
CITATION_BASE: Final[dict[str, float]] = {"low": 1.6, "medium": 5.0, "high": 13.0}

# --------------------------------------------------------------------------------------
# The topic table — the declarative heart of the corpus
# --------------------------------------------------------------------------------------

TOPICS: Final[tuple[Topic, ...]] = (
    # ---------------------------------------------------------------- AI / ML ----------
    Topic(
        slug="large-language-model", term="large language model",
        aliases=("large language models", "foundation model"), acronym="LLM",
        domain="ai", first_year=2019, profile="maturing", volume=112,
        class_mix="software", has_patents=True, citation_profile="high",
        anchor_orgs=("OpenAI", "Google DeepMind", "Meta AI Research"),
        problems=("the memory footprint of long-context inference",
                  "hallucination in open-domain question answering",
                  "the cost of instruction tuning at scale"),
        benefits=("reliable few-shot generalisation across domains",
                  "single-model coverage of dozens of downstream tasks",
                  "controllable generation without task-specific heads"),
        expectation="MAINSTREAM (AI control) — largest recent volume in its domain, weakness 0",
    ),
    Topic(
        slug="retrieval-augmented-generation", term="retrieval-augmented generation",
        aliases=("retrieval augmented generation",), acronym="RAG",
        domain="ai", first_year=2021, profile="accelerating", volume=42,
        class_mix="software", has_patents=True, citation_profile="high",
        anchor_orgs=("Microsoft Research", "Hugging Face", "Cohere"),
        problems=("retrieval drift between the index and the generator",
                  "grounding answers in the retrieved passages",
                  "stale documents in the vector index"),
        benefits=("answer attribution to concrete source passages",
                  "domain adaptation without fine-tuning",
                  "cheap knowledge updates by reindexing"),
        expectation="SECONDARY — strong growth but already loud; alias pair RAG must merge",
    ),
    Topic(
        slug="state-space-model", term="state space model",
        aliases=("structured state space model", "selective state space model"), acronym="SSM",
        domain="ai", first_year=2022, profile="accelerating", volume=24,
        class_mix="software", has_patents=True, citation_profile="high",
        anchor_orgs=("Carnegie Mellon University", "Princeton University", "NVIDIA Research"),
        problems=("quadratic attention cost on million-token sequences",
                  "training instability of long convolutions",
                  "recall of distant tokens in linear-time models"),
        benefits=("linear-time inference over million-token contexts",
                  "constant memory decoding",
                  "hardware-friendly parallel scan training"),
        expectation="TOP weak signal — new (2022), steep growth, moderate volume; alias SSM must merge",
    ),
    Topic(
        slug="speculative-decoding", term="speculative decoding",
        aliases=("draft-and-verify decoding",), acronym=None,
        domain="ai", first_year=2023, profile="accelerating", volume=16,
        class_mix="software", has_patents=True, citation_profile="medium",
        anchor_orgs=("Google DeepMind", "NVIDIA Research", "Hugging Face"),
        problems=("the sequential bottleneck of autoregressive decoding",
                  "draft-target mismatch under distribution shift",
                  "low acceptance rates on long generations"),
        benefits=("lossless acceleration of autoregressive generation",
                  "higher tokens per second at an unchanged output distribution",
                  "better accelerator utilisation during serving"),
        expectation="TOP weak signal — newest AI topic, steep slope, real industry pull",
    ),
    Topic(
        slug="mechanistic-interpretability", term="mechanistic interpretability",
        aliases=("circuit-level interpretability",), acronym=None,
        domain="ai", first_year=2022, profile="emerging", volume=18,
        class_mix="software_open", has_patents=False, citation_profile="medium",
        anchor_orgs=("Anthropic", "EleutherAI", "University of Oxford"),
        problems=("polysemantic neurons that resist single-feature explanations",
                  "the absence of ground truth for circuit-level claims",
                  "scaling causal tracing beyond toy models"),
        benefits=("causal explanations of model behaviour at the circuit level",
                  "targeted editing of learned features",
                  "auditable failure analysis before deployment"),
        expectation="TOP weak signal — novel and growing, held back by impact (no patents)",
    ),
    Topic(
        slug="mixture-of-experts", term="mixture of experts",
        aliases=("sparse mixture of experts",), acronym="MoE",
        domain="ai", first_year=2021, profile="accelerating", volume=27,
        class_mix="software", has_patents=True, citation_profile="high",
        anchor_orgs=("Google DeepMind", "Mistral AI", "Alibaba DAMO Academy"),
        problems=("load imbalance across experts during training",
                  "all-to-all communication overhead on multi-node clusters",
                  "expert collapse under sparse routing"),
        benefits=("constant inference cost as parameter count grows",
                  "specialisation of experts across domains",
                  "training throughput at trillion-parameter scale"),
        expectation="SECONDARY — accelerating; alias MoE, high impact",
    ),
    Topic(
        slug="federated-learning", term="federated learning",
        aliases=("federated optimisation",), acronym="FL",
        domain="ai", first_year=2018, profile="maturing", volume=44,
        class_mix="software", has_patents=True, citation_profile="medium",
        anchor_orgs=("Google DeepMind", "IBM Research", "Nokia Bell Labs"),
        problems=("client drift under non-IID data partitions",
                  "communication rounds over unreliable mobile links",
                  "privacy leakage through shared gradients"),
        benefits=("model training without centralising raw data",
                  "compliance with cross-border data regulation",
                  "on-device personalisation"),
        expectation="MATURING — flat slope, growth ~0, excluded by BRULE-4",
    ),
    Topic(
        slug="neural-architecture-search", term="neural architecture search",
        aliases=("architecture search",), acronym="NAS",
        domain="ai", first_year=2018, profile="declining", volume=30,
        class_mix="software", has_patents=True, citation_profile="medium",
        anchor_orgs=("Google DeepMind", "Huawei Noah's Ark Lab", "TU Munich"),
        problems=("the compute budget of a single search run",
                  "poor transfer of discovered architectures across datasets",
                  "weak correlation between proxy and final accuracy"),
        benefits=("automated discovery of task-specific architectures",
                  "latency-aware models for edge deployment",
                  "removal of manual architecture tuning"),
        expectation="DECLINING — negative slope zeroes growth, must be excluded",
    ),
    Topic(
        slug="diffusion-model", term="diffusion model",
        aliases=("denoising diffusion model", "latent diffusion model"), acronym=None,
        domain="ai", first_year=2020, profile="maturing", volume=46,
        class_mix="software", has_patents=True, citation_profile="high",
        anchor_orgs=("Google DeepMind", "NVIDIA Research", "TU Munich"),
        problems=("the number of denoising steps required at sampling time",
                  "mode coverage on long-tailed datasets",
                  "controllability of the generation trajectory"),
        benefits=("high-fidelity synthesis with stable training",
                  "flexible conditioning on text and layout",
                  "principled likelihood-based training"),
        expectation="SECONDARY — second-largest AI volume, plateauing; weakness bites but does not zero",
    ),
    Topic(
        slug="sparse-autoencoder", term="sparse autoencoder",
        aliases=("dictionary learning for features",), acronym="SAE",
        domain="ai", first_year=2023, profile="embryonic", volume=9,
        class_mix="software_open", has_patents=False, citation_profile="low",
        anchor_orgs=("Anthropic", "EleutherAI"),
        problems=("dictionary collapse when the sparsity penalty is too strong",
                  "feature splitting across dictionary sizes",
                  "the absence of an agreed reconstruction metric"),
        benefits=("monosemantic features extracted from residual streams",
                  "human-readable dictionaries of model features",
                  "steering vectors derived without labels"),
        expectation="EMBRYONIC — 9 documents, 3 source classes; must carry the low-confidence flag (BRULE-6)",
    ),
    # ---------------------------------------------------------------- Security ---------
    Topic(
        slug="post-quantum-cryptography", term="post-quantum cryptography",
        aliases=("quantum-resistant cryptography", "post quantum cryptography"), acronym="PQC",
        domain="security", first_year=2019, profile="accelerating", volume=35,
        class_mix="security", has_patents=True, citation_profile="medium",
        anchor_orgs=("NIST", "Cloudflare", "Thales"),
        problems=("key sizes that break existing protocol assumptions",
                  "side-channel leakage in lattice implementations",
                  "migration of long-lived certificate chains"),
        benefits=("resistance to attacks by cryptographically relevant quantum computers",
                  "drop-in hybrid key exchange for TLS",
                  "standards-aligned key encapsulation"),
        expectation="SECONDARY — steep growth and patents, but loud enough that weakness caps it",
    ),
    Topic(
        slug="confidential-computing", term="confidential computing",
        aliases=("trusted execution environment", "enclave computing"), acronym="TEE",
        domain="security", first_year=2020, profile="accelerating", volume=26,
        class_mix="security", has_patents=True, citation_profile="medium",
        anchor_orgs=("Intel Labs", "AMD Research", "Fortanix"),
        problems=("the size of the trusted computing base in current enclave designs",
                  "attestation across heterogeneous hardware vendors",
                  "the performance overhead of encrypted memory"),
        benefits=("processing of regulated data on untrusted infrastructure",
                  "verifiable remote attestation of workloads",
                  "multi-party analytics without data sharing"),
        expectation="SECONDARY — industry-heavy; alias TEE exercises acronym mining",
    ),
    Topic(
        slug="software-bill-of-materials", term="software bill of materials",
        aliases=("software supply chain inventory",), acronym="SBOM",
        domain="security", first_year=2021, profile="accelerating", volume=22,
        class_mix="security", has_patents=False, citation_profile="low",
        anchor_orgs=("OpenSSF", "Chainguard", "Linux Foundation"),
        problems=("incomplete dependency resolution in transitive graphs",
                  "format drift between SPDX and CycloneDX consumers",
                  "the absence of build-time provenance"),
        benefits=("machine-readable inventories for vulnerability triage",
                  "minutes instead of weeks to answer exposure questions",
                  "provenance attestation across the build pipeline"),
        expectation="TOP weak signal — growth and diffusion high, impact limited (no patents)",
    ),
    Topic(
        slug="zero-trust-architecture", term="zero trust architecture",
        aliases=("zero trust network access",), acronym="ZTA",
        domain="security", first_year=2019, profile="maturing", volume=80,
        class_mix="security", has_patents=True, citation_profile="low",
        anchor_orgs=("Palo Alto Networks", "NIST", "Microsoft Security Response Center"),
        problems=("policy sprawl across identity providers",
                  "latency added by per-request authorisation",
                  "legacy applications that assume network trust"),
        benefits=("continuous verification of every request",
                  "containment of lateral movement after a breach",
                  "uniform policy across cloud and on-premise estates"),
        expectation="MAINSTREAM (security control) — largest recent volume in its domain, weakness 0",
    ),
    Topic(
        slug="adversarial-machine-learning", term="adversarial machine learning",
        aliases=("adversarial robustness",), acronym=None,
        domain="security", first_year=2018, profile="declining", volume=26,
        class_mix="software_open", has_patents=False, citation_profile="medium",
        anchor_orgs=("UC Berkeley", "Purdue University", "KU Leuven"),
        problems=("the gap between certified and empirical robustness",
                  "adaptive attacks that break published defences",
                  "robustness that costs clean accuracy"),
        benefits=("certified robustness bounds under bounded perturbations",
                  "quantified worst-case behaviour before deployment",
                  "attack-aware evaluation protocols"),
        expectation="DECLINING — peaked 2020-2021, negative slope",
    ),
    Topic(
        slug="ebpf-runtime-security", term="eBPF runtime security",
        aliases=("kernel-level runtime security", "eBPF observability"), acronym=None,
        domain="security", first_year=2022, profile="emerging", volume=16,
        class_mix="security", has_patents=True, citation_profile="low",
        anchor_orgs=("Isovalent", "Sysdig", "Cloudflare"),
        problems=("kernel visibility without loadable modules",
                  "verifier limits on program complexity",
                  "event volume from high-throughput nodes"),
        benefits=("kernel-level observability with no application changes",
                  "policy enforcement at syscall granularity",
                  "container-aware detection without sidecars"),
        expectation="TOP weak signal — very new, small, but evidence spans all five source classes",
    ),
    # ---------------------------------------------------------------- Quantum ----------
    Topic(
        slug="quantum-error-correction", term="quantum error correction",
        aliases=("surface code", "fault-tolerant error correction"), acronym="QEC",
        domain="quantum", first_year=2020, profile="accelerating", volume=24,
        class_mix="hardware", has_patents=True, citation_profile="high",
        anchor_orgs=("Google Quantum AI", "IBM Quantum", "TU Delft"),
        problems=("the physical-to-logical qubit overhead",
                  "real-time decoding within the syndrome cycle",
                  "correlated noise that violates independent-error assumptions"),
        benefits=("logical error rates below the physical threshold",
                  "fault-tolerant operation of shallow circuits",
                  "decoders that keep pace with the syndrome stream"),
        expectation="SECONDARY — accelerating with the highest impact in quantum",
    ),
    Topic(
        slug="neutral-atom-qubit", term="neutral atom qubit",
        aliases=("Rydberg atom array", "neutral-atom quantum processor"), acronym=None,
        domain="quantum", first_year=2021, profile="accelerating", volume=17,
        class_mix="hardware", has_patents=True, citation_profile="medium",
        anchor_orgs=("QuEra Computing", "Pasqal", "Harvard University"),
        problems=("atom loss during rearrangement cycles",
                  "crosstalk in dense Rydberg arrays",
                  "gate fidelities limited by laser phase noise"),
        benefits=("thousands of identical qubits in a single trap",
                  "native connectivity through Rydberg blockade",
                  "operation without dilution refrigerators"),
        expectation="TOP weak signal — new hardware line, small but diffuse",
    ),
    Topic(
        slug="quantum-machine-learning", term="quantum machine learning",
        aliases=("variational quantum algorithm",), acronym="QML",
        domain="quantum", first_year=2018, profile="declining", volume=26,
        class_mix="software_open", has_patents=False, citation_profile="medium",
        anchor_orgs=("Xanadu", "University of Waterloo", "Chinese Academy of Sciences"),
        problems=("barren plateaus in variational circuit training",
                  "the cost of loading classical data into quantum states",
                  "the absence of a demonstrated advantage on real data"),
        benefits=("expressive feature maps in high-dimensional Hilbert spaces",
                  "hybrid pipelines that reuse classical optimisers",
                  "kernel methods with quantum-evaluated similarity"),
        expectation="DECLINING — hype curve down; growth 0",
    ),
    Topic(
        slug="topological-qubit", term="topological qubit",
        aliases=("Majorana zero mode qubit",), acronym=None,
        domain="quantum", first_year=2023, profile="embryonic", volume=8,
        class_mix="hardware", has_patents=True, citation_profile="low",
        anchor_orgs=("Microsoft Research", "TU Delft"),
        problems=("unambiguous signatures of Majorana zero modes",
                  "disorder at the semiconductor-superconductor interface",
                  "reproducibility across fabricated devices"),
        benefits=("error protection built into the hardware",
                  "coherence that is topologically shielded",
                  "fewer physical qubits per logical qubit"),
        expectation="BRULE-1 + BRULE-6 case — single 2023 document from one org, so the reliable first year is 2024; scores high but must be flagged low evidence",
        solo_first_year=True,
    ),
    Topic(
        slug="quantum-key-distribution", term="quantum key distribution",
        aliases=("measurement-device-independent QKD",), acronym="QKD",
        domain="quantum", first_year=2018, profile="maturing", volume=72,
        class_mix="hardware", has_patents=True, citation_profile="medium",
        anchor_orgs=("ID Quantique", "Toshiba Europe",
                     "University of Science and Technology of China"),
        problems=("the distance limit imposed by fibre loss",
                  "trusted-node assumptions in long-haul links",
                  "detector side-channel attacks"),
        benefits=("information-theoretic key agreement",
                  "satellite links that bypass fibre loss",
                  "measurement-device-independent protocols"),
        expectation="MAINSTREAM (quantum control) — largest recent volume in its domain, weakness 0",
    ),
    # ---------------------------------------------------------------- Bio -------------
    Topic(
        slug="protein-language-model", term="protein language model",
        aliases=("protein sequence model",), acronym="PLM",
        domain="bio", first_year=2021, profile="accelerating", volume=24,
        class_mix="biomed", has_patents=True, citation_profile="high",
        anchor_orgs=("EvolutionaryScale", "Meta AI Research", "EMBL-EBI"),
        problems=("generalisation to sequences outside the training distribution",
                  "the scarcity of labelled functional annotations",
                  "calibration of confidence for novel folds"),
        benefits=("structure and function prediction from sequence alone",
                  "zero-shot variant effect prediction",
                  "design of binders without co-crystal structures"),
        expectation="TOP weak signal — cross-domain (AI x bio), patents, strong growth",
    ),
    Topic(
        slug="prime-editing", term="prime editing",
        aliases=("search-and-replace genome editing",), acronym=None,
        domain="bio", first_year=2019, profile="accelerating", volume=30,
        class_mix="biomed", has_patents=True, citation_profile="high",
        anchor_orgs=("Prime Medicine", "Broad Institute", "Beam Therapeutics"),
        problems=("editing efficiency in primary human cells",
                  "delivery of large editor constructs in vivo",
                  "unintended insertions at the target locus"),
        benefits=("precise installation of all twelve base-to-base conversions",
                  "correction of pathogenic variants without double-strand breaks",
                  "edits that do not require donor templates"),
        expectation="SECONDARY — accelerating with high impact but 2019 novelty penalty",
    ),
    Topic(
        slug="single-cell-multiomics", term="single-cell multiomics",
        aliases=("single cell multi-omics",), acronym=None,
        domain="bio", first_year=2019, profile="maturing", volume=76,
        class_mix="biomed", has_patents=True, citation_profile="high",
        anchor_orgs=("10x Genomics", "Broad Institute", "Wellcome Sanger Institute"),
        problems=("batch effects across sequencing runs",
                  "sparsity in per-cell measurements",
                  "alignment of modalities measured in different cells"),
        benefits=("joint readout of transcriptome and chromatin state",
                  "cell-type resolution in heterogeneous tissue",
                  "trajectories reconstructed without cell sorting"),
        expectation="MAINSTREAM (bio control) — largest recent volume in its domain, weakness 0",
    ),
    Topic(
        slug="mrna-vaccine-platform", term="mRNA vaccine platform",
        aliases=("messenger RNA vaccine",), acronym=None,
        domain="bio", first_year=2018, profile="declining", volume=30,
        class_mix="biomed", has_patents=True, citation_profile="high",
        anchor_orgs=("Moderna", "BioNTech", "NIH"),
        problems=("cold-chain requirements for lipid nanoparticle formulations",
                  "reactogenicity at higher doses",
                  "durability of the antibody response"),
        benefits=("antigen changes without retooling manufacturing",
                  "clinical candidates within weeks of a sequence release",
                  "scalable cell-free production"),
        expectation="DECLINING — 2020-2021 peak then fall; growth 0 despite huge citations",
    ),
    Topic(
        slug="organ-on-a-chip", term="organ-on-a-chip",
        aliases=("microphysiological system",), acronym="MPS",
        domain="bio", first_year=2019, profile="emerging", volume=24,
        class_mix="biomed", has_patents=True, citation_profile="medium",
        anchor_orgs=("Emulate", "CN Bio Innovations", "Institut Pasteur"),
        problems=("reproducibility across chip fabrication batches",
                  "the absence of an immune compartment",
                  "scaling readouts to screening throughput"),
        benefits=("human-relevant toxicity signals before animal studies",
                  "perfused tissue barriers on a single chip",
                  "patient-derived models for dose selection"),
        expectation="SECONDARY — steady emergence, moderate everything",
    ),
    # ---------------------------------------------------------------- Fintech ----------
    Topic(
        slug="zero-knowledge-rollup", term="zero-knowledge rollup",
        aliases=("validity rollup", "ZK rollup"), acronym="ZKR",
        domain="fintech", first_year=2021, profile="accelerating", volume=24,
        class_mix="fintech", has_patents=True, citation_profile="medium",
        anchor_orgs=("StarkWare", "Matter Labs", "Ethereum Foundation"),
        problems=("proving time for large transaction batches",
                  "the trusted setup required by some proof systems",
                  "data availability guarantees for withdrawn state"),
        benefits=("validity proofs verified in constant time on the base layer",
                  "settlement finality without a challenge window",
                  "fees an order of magnitude below the base layer"),
        expectation="TOP weak signal — code-heavy diffusion, fast growth",
    ),
    Topic(
        slug="account-abstraction", term="account abstraction",
        aliases=("smart contract wallet",), acronym="AA",
        domain="fintech", first_year=2022, profile="emerging", volume=18,
        class_mix="fintech", has_patents=False, citation_profile="low",
        anchor_orgs=("Ethereum Foundation", "Consensys", "Fireblocks"),
        problems=("wallet compatibility across bundler implementations",
                  "gas accounting for sponsored transactions",
                  "recovery flows that reintroduce custodians"),
        benefits=("programmable authorisation policies per account",
                  "sponsored transactions for first-time users",
                  "social recovery without seed phrases"),
        expectation="TOP weak signal by novelty; impact limited (no patents)",
    ),
    Topic(
        slug="central-bank-digital-currency", term="central bank digital currency",
        aliases=("retail digital currency",), acronym="CBDC",
        domain="fintech", first_year=2020, profile="maturing", volume=64,
        class_mix="fintech", has_patents=True, citation_profile="low",
        anchor_orgs=("Bank for International Settlements", "European Central Bank",
                     "Monetary Authority of Singapore"),
        problems=("privacy expectations versus anti-money-laundering duties",
                  "offline payment settlement",
                  "disintermediation of commercial bank deposits"),
        benefits=("programmable settlement in central bank money",
                  "instant cross-border transfers between retail users",
                  "resilience when card networks are unavailable"),
        expectation="MAINSTREAM (fintech control) — largest recent volume in its domain, weakness 0",
    ),
    Topic(
        slug="decentralized-finance", term="decentralized finance",
        aliases=("decentralised finance",), acronym="DeFi",
        domain="fintech", first_year=2018, profile="declining", volume=28,
        class_mix="fintech", has_patents=True, citation_profile="medium",
        anchor_orgs=("Consensys", "Chainalysis", "Cornell University"),
        problems=("oracle manipulation in thinly traded markets",
                  "composability risk across protocol upgrades",
                  "liquidity fragmentation across chains"),
        benefits=("permissionless market making",
                  "collateralisation that is transparent on-chain",
                  "settlement without bilateral credit lines"),
        expectation="DECLINING — 2021 peak then fall",
    ),
    # ---------------------------------------------------------------- Energy -----------
    Topic(
        slug="solid-state-battery", term="solid-state battery",
        aliases=("all-solid-state battery", "solid electrolyte cell"), acronym="SSB",
        domain="energy", first_year=2019, profile="accelerating", volume=40,
        class_mix="hardware", has_patents=True, citation_profile="high",
        anchor_orgs=("Toyota Research Institute", "QuantumScape", "Samsung SDI"),
        problems=("dendrite growth through ceramic separators",
                  "interfacial resistance between electrolyte and cathode",
                  "the stack pressure required for stable cycling"),
        benefits=("energy density beyond 400 Wh/kg",
                  "operation without a flammable liquid electrolyte",
                  "fast charge without lithium plating"),
        expectation="SECONDARY — highest patent ratio in the corpus; impact anchor",
    ),
    Topic(
        slug="sodium-ion-battery", term="sodium-ion battery",
        aliases=("Na-ion cell",), acronym="SIB",
        domain="energy", first_year=2021, profile="accelerating", volume=24,
        class_mix="hardware", has_patents=True, citation_profile="medium",
        anchor_orgs=("CATL", "Faradion", "Natron Energy"),
        problems=("the volumetric energy density gap versus lithium chemistries",
                  "hard-carbon anode capacity fade",
                  "electrolyte stability at low temperature"),
        benefits=("cells built without lithium, cobalt or nickel",
                  "stable operation below minus twenty degrees",
                  "supply chains independent of lithium pricing"),
        expectation="TOP weak signal — new, patent-rich, industrially anchored",
    ),
    Topic(
        slug="perovskite-tandem-solar-cell", term="perovskite tandem solar cell",
        aliases=("perovskite-silicon tandem",), acronym=None,
        domain="energy", first_year=2019, profile="accelerating", volume=72,
        class_mix="hardware", has_patents=True, citation_profile="high",
        anchor_orgs=("Oxford PV", "Fraunhofer ISE", "NREL"),
        problems=("moisture-induced degradation of the perovskite layer",
                  "hysteresis in current-voltage measurements",
                  "scaling spin-coated films to module area"),
        benefits=("certified efficiencies above thirty percent",
                  "solution-processed manufacturing at low temperature",
                  "tandem stacks on existing silicon lines"),
        expectation="MAINSTREAM (energy control) — largest recent volume in its domain, weakness 0",
    ),
    Topic(
        slug="green-hydrogen-electrolysis", term="green hydrogen electrolysis",
        aliases=("PEM water electrolysis", "renewable hydrogen production"), acronym="PEM",
        domain="energy", first_year=2020, profile="emerging", volume=26,
        class_mix="hardware", has_patents=True, citation_profile="medium",
        anchor_orgs=("Nel Hydrogen", "Siemens Energy", "SINTEF"),
        problems=("iridium loading in proton-exchange-membrane stacks",
                  "degradation under intermittent renewable input",
                  "the levelised cost of hydrogen at current stack prices"),
        benefits=("hydrogen produced without fossil feedstock",
                  "load-following operation matched to wind and solar",
                  "stack lifetimes beyond sixty thousand hours"),
        expectation="SECONDARY — industrial, patent-heavy, moderate growth",
    ),
    Topic(
        slug="direct-air-capture", term="direct air capture",
        aliases=("atmospheric carbon removal",), acronym="DAC",
        domain="energy", first_year=2020, profile="emerging", volume=20,
        class_mix="hardware", has_patents=True, citation_profile="medium",
        anchor_orgs=("Climeworks", "Carbon Engineering", "NREL"),
        problems=("the thermal energy required for sorbent regeneration",
                  "sorbent degradation under humid cycling",
                  "capture cost per tonne at plant scale"),
        benefits=("removal of carbon dioxide independent of point sources",
                  "sorbents regenerated below one hundred degrees",
                  "verifiable removal credits with a measured mass balance"),
        expectation="SECONDARY — small and diffuse, but slope too shallow for the top tier",
    ),
    Topic(
        slug="small-modular-reactor", term="small modular reactor",
        aliases=("advanced modular reactor",), acronym="SMR",
        domain="energy", first_year=2019, profile="emerging", volume=24,
        class_mix="hardware", has_patents=True, citation_profile="medium",
        anchor_orgs=("NuScale Power", "Rolls-Royce SMR", "Idaho National Laboratory"),
        problems=("licensing timelines for first-of-a-kind designs",
                  "supply of high-assay low-enriched uranium fuel",
                  "the cost of first-of-a-kind construction"),
        benefits=("factory fabrication instead of on-site construction",
                  "passive safety without operator intervention",
                  "siting close to industrial heat demand"),
        expectation="SECONDARY — news-heavy, government affiliations",
    ),
)

# --------------------------------------------------------------------------------------
# Deterministic primitives
# --------------------------------------------------------------------------------------


def stable_rng(*parts: object) -> random.Random:
    """Return an RNG seeded by :data:`SEED` and the given coordinates.

    The seed is ``sha256(SEED | part | part | ...)``, so a document's content depends only
    on *its own* coordinates and never on generation order or corpus size.

    Args:
        *parts: Coordinates identifying the object being generated.

    Returns:
        A freshly seeded :class:`random.Random`.
    """
    material = "|".join([str(SEED), *(str(p) for p in parts)]).encode("utf-8")
    return random.Random(int.from_bytes(hashlib.sha256(material).digest()[:16], "big"))


def largest_remainder(total: int, weights: Sequence[float]) -> list[int]:
    """Split ``total`` integer items across buckets proportionally to ``weights``.

    Uses the largest-remainder (Hare) method with an index tie-break, so the result is a
    pure function of the inputs and always sums to ``total``.

    Args:
        total: Number of items to distribute (``<= 0`` yields all zeros).
        weights: Non-negative bucket weights.

    Returns:
        One integer per weight, summing to ``total``.
    """
    counts = [0] * len(weights)
    weight_sum = math.fsum(weights)
    if total <= 0 or not weights or weight_sum <= 0.0:
        return counts
    exact = [total * w / weight_sum for w in weights]
    counts = [int(math.floor(value)) for value in exact]
    shortfall = total - sum(counts)
    order = sorted(range(len(weights)), key=lambda i: (-(exact[i] - counts[i]), i))
    for position in range(shortfall):
        counts[order[position]] += 1
    return counts


def iso7064_check_digit(digits: str) -> str:
    """Compute the ISO 7064 MOD 11-2 check character used by ORCID identifiers."""
    total = 0
    for char in digits:
        total = (total + int(char)) * 2
    result = (12 - total % 11) % 11
    return "X" if result == 10 else str(result)


def issn_check_digit(seven_digits: str) -> str:
    """Compute the ISSN check character (MOD 11, weights 8..2)."""
    total = sum(int(char) * (8 - position) for position, char in enumerate(seven_digits))
    result = (11 - total % 11) % 11
    return "X" if result == 10 else str(result)


def sha256_hex(text: str) -> str:
    """Return the hex SHA-256 digest of ``text`` encoded as UTF-8."""
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def day_in_year(year: int, rng: random.Random) -> date:
    """Pick a publication date inside ``year`` (uniform over the real calendar)."""
    start = date(year, 1, 1)
    days = (date(year + 1, 1, 1) - start).days
    return start + timedelta(days=rng.randrange(days))


def issn_for(venue_name: str) -> str:
    """Derive a stable, well-formed ISSN from a venue name."""
    digits = "".join(char for char in sha256_hex(venue_name) if char.isdigit())[:7]
    digits = (digits + "1234567")[:7]
    return f"{digits[:4]}-{digits[4:]}{issn_check_digit(digits)}"


def orcid_for(full_name: str, org: str) -> str:
    """Derive a stable, checksum-valid ORCID identifier for an author."""
    digits = "".join(char for char in sha256_hex(f"{full_name}@{org}") if char.isdigit())
    digits = (digits + "0000000000000000")[:15]
    # Real ORCIDs issued to date live in the 0000-000X-XXXX-XXXX range.
    body = "0000000" + str(1 + int(digits[0]) % 3) + digits[1:8]
    checked = body + iso7064_check_digit(body)
    return f"{checked[0:4]}-{checked[4:8]}-{checked[8:12]}-{checked[12:16]}"


# --------------------------------------------------------------------------------------
# Corpus planning
# --------------------------------------------------------------------------------------


def plan_years(topic: Topic) -> list[tuple[int, int]]:
    """Distribute a topic's documents over calendar years according to its growth profile.

    The first active year is forced to at least :data:`MIN_FIRST_YEAR_DOCS` documents so
    that BRULE-1 (>= 2 documents and >= 2 organisations) accepts it — except for topics
    flagged :attr:`Topic.solo_first_year`, which are shaped to exercise the opposite case.

    Args:
        topic: Topic to plan.

    Returns:
        ``(year, document_count)`` pairs in ascending year order, summing to
        :attr:`Topic.volume`.
    """
    years = list(range(topic.first_year, YEAR_LAST + 1))
    shape = PROFILE_SHAPES[topic.profile]
    weights = [shape[min(offset, len(shape) - 1)] for offset in range(len(years))]
    counts = largest_remainder(topic.volume, weights)

    if topic.solo_first_year:
        while counts[0] > 1 and len(counts) > 1:
            receiver = max(range(1, len(counts)), key=lambda i: (counts[i], i))
            counts[0] -= 1
            counts[receiver] += 1
    else:
        while counts[0] < MIN_FIRST_YEAR_DOCS and len(counts) > 1:
            donor = max(range(1, len(counts)), key=lambda i: (counts[i], i))
            if counts[donor] <= MIN_FIRST_YEAR_DOCS:
                break
            counts[donor] -= 1
            counts[0] += 1
    return list(zip(years, counts, strict=True))


def plan_classes(topic: Topic, year: int, count: int) -> list[str]:
    """Assign source classes to a topic-year's documents.

    Patents are suppressed for the first :data:`PATENT_LAG_YEARS` years of a topic (patent
    publication lag, methodology §11) and their share is folded into journal articles.

    Args:
        topic: Topic being planned.
        year: Calendar year.
        count: Number of documents in that year.

    Returns:
        A list of ``sourceClass`` values of length ``count``, in :data:`SOURCE_CLASS_ORDER`.
    """
    mix = dict(CLASS_MIX[topic.class_mix])
    if not topic.has_patents or year < topic.first_year + PATENT_LAG_YEARS:
        mix["JOURNAL_ARTICLE"] += mix["PATENT"]
        mix["PATENT"] = 0.0
    counts = largest_remainder(count, [mix[name] for name in SOURCE_CLASS_ORDER])
    plan: list[str] = []
    for source_class, quantity in zip(SOURCE_CLASS_ORDER, counts, strict=True):
        plan.extend([source_class] * quantity)
    return plan


def plan_corpus() -> list[DocPlan]:
    """Build the full document plan in a fixed, declaration-driven order."""
    plans: list[DocPlan] = []
    for topic in TOPICS:
        for year, count in plan_years(topic):
            for index, source_class in enumerate(plan_classes(topic, year, count)):
                plans.append(DocPlan(topic=topic, year=year, source_class=source_class,
                                     index=index))
    return plans


# --------------------------------------------------------------------------------------
# External identifiers
# --------------------------------------------------------------------------------------


def make_external_id(plan: DocPlan, published: date, source_id: str, attempt: int,
                     repo_owner: str, outlet_slug: str) -> str:
    """Build a source-plausible external identifier.

    Driven entirely by an ``attempt``-salted RNG, so collision retries never disturb the
    document's other random draws.

    Args:
        plan: Document coordinates.
        published: Publication date (drives arXiv YYMM and patent number ranges).
        source_id: Origin source (``arxiv``, ``crossref``, ``openalex``, ...).
        attempt: Collision-resolution counter.
        repo_owner: Repository owner for ``github``.
        outlet_slug: Outlet slug for ``rss``.

    Returns:
        An identifier in the native format of ``source_id``.
    """
    salt = stable_rng(plan.topic.slug, plan.year, plan.source_class, plan.index, "eid", attempt)
    if source_id == "arxiv":
        return f"{published:%y%m}.{salt.randrange(1000, 99999):05d}"
    if source_id == "crossref":
        return make_doi(plan, published, salt)
    if source_id == "openalex":
        return f"W{salt.randrange(2000000000, 4999999999)}"
    if source_id == "patentsview":
        return make_patent_number(published, salt)
    if source_id == "github":
        return f"{repo_owner}/{make_repo_name(plan.topic, salt)}"
    return f"{outlet_slug}:{published.isoformat()}:{salt.randrange(0, 16 ** 8):08x}"


def make_doi(plan: DocPlan, published: date, rng: random.Random) -> str:
    """Build a plausible DOI such as ``10.1109/tdsc.2024.3391847``."""
    prefix = rng.choice(("10.1038", "10.1109", "10.1145", "10.1016", "10.1002", "10.1021",
                         "10.1103", "10.1093", "10.1007"))
    stem = slugify(plan.topic.term).replace("-", "")[:8]
    return f"{prefix}/{stem}.{published.year}.{rng.randrange(100000, 999999)}"


def make_patent_number(published: date, rng: random.Random) -> str:
    """Build a plausible patent publication number for one of the offices."""
    _office, code, kinds = rng.choice(PATENT_OFFICES)
    kind = rng.choice(kinds)
    if code == "US":
        serial = rng.randrange(10_100_000, 12_400_000)
    elif code == "EP":
        serial = rng.randrange(3_500_000, 4_400_000)
    elif code == "CN":
        serial = rng.randrange(110_000_000, 119_999_999)
    elif code == "JP":
        serial = rng.randrange(6_800_000, 7_500_000)
    else:
        return f"WO{published.year}/{rng.randrange(100000, 999999)}A1"
    return f"{code}{serial}{kind}"


def office_for_patent(patent_number: str) -> str:
    """Map a patent number back to its issuing office name."""
    for office, code, _kinds in PATENT_OFFICES:
        if patent_number.startswith(code):
            return office
    return "USPTO"


def slugify(text: str) -> str:
    """Lowercase ASCII slug: keep alphanumerics, collapse everything else to hyphens."""
    out: list[str] = []
    previous_hyphen = False
    for char in text.lower():
        if char.isascii() and char.isalnum():
            out.append(char)
            previous_hyphen = False
        elif not previous_hyphen:
            out.append("-")
            previous_hyphen = True
    return "".join(out).strip("-")


def make_repo_name(topic: Topic, rng: random.Random) -> str:
    """Build a plausible repository name for a topic."""
    stem = topic.acronym.lower() if topic.acronym else slugify(topic.term)
    stem = stem[:24].strip("-")
    pattern = rng.choice(("{s}-lab", "{s}-kit", "open-{s}", "{s}-bench", "awesome-{s}",
                          "{s}-rs", "py-{s}", "{s}-toolkit", "fast-{s}", "{s}-core"))
    return pattern.format(s=stem)


# --------------------------------------------------------------------------------------
# Text rendering
# --------------------------------------------------------------------------------------

#: Academic title templates.  ``{term}`` is the canonical term or an alias.
TITLE_TEMPLATES: Final[tuple[str, ...]] = (
    "{Method}: {term} for {application}",
    "Towards practical {term} in {setting}",
    "{Term} at scale: {aspect} for {application}",
    "Rethinking {aspect} in {term}",
    "Efficient {term} via {technique}",
    "On the {aspect} of {term}",
    "A modular framework for {term} in {application}",
    "{Term} under {setting}: an empirical study",
    "Scaling {term} with {technique}",
    "{Method}: revisiting {aspect} for {term}",
    "{Technique} improves the {aspect} of {term}",
    "Benchmarking {term} for {application}",
    "{Term} meets {technique}",
    "Measuring {aspect} in {term} deployments",
    "{Method}: an open evaluation of {term}",
)

#: Patent title templates.
PATENT_TITLE_TEMPLATES: Final[tuple[str, ...]] = (
    "System and method for {gerund} {term}",
    "Apparatus for {gerund} {term} using {technique}",
    "{Term} device with improved {aspect} and method of manufacture",
    "Method for {gerund} {term} in {application}",
    "Computer-implemented method for {gerund} {term}",
)

PATENT_GERUNDS: Final[tuple[str, ...]] = (
    "improving", "controlling", "monitoring", "optimizing", "fabricating", "verifying",
    "scheduling", "calibrating", "deploying",
)

#: News headline templates.
NEWS_TITLE_TEMPLATES: Final[tuple[str, ...]] = (
    "{org} unveils {term} platform for {application}",
    "Why {term} is quietly reshaping {application}",
    "Inside the race to make {term} practical",
    "{org} and {org2} partner on {term}",
    "{Term} moves from the lab to the production line",
    "Investors back {term} startups as {application} demand grows",
    "{org} says its {term} pilot cut {metric} by {pct} percent",
)

#: Repository tagline templates.
REPO_TAGLINE_TEMPLATES: Final[tuple[str, ...]] = (
    "reference implementation of {term} for {application}",
    "an open toolkit for {term} experiments",
    "fast {term} kernels and evaluation harness",
    "reproducible {term} baselines and datasets",
    "production-ready {term} components",
)

#: Problem sentences.  Every variant carries at least one narrator problem marker.
PROBLEM_TEMPLATES: Final[tuple[str, ...]] = (
    "However, {problem} remains challenging in {setting}.",
    "Existing {artifact}s are limited by {problem}, which becomes a bottleneck in {setting}.",
    "Current {artifact}s suffer from {problem}, and closing this gap is still an open problem.",
    "However, {aspect} is limited by {problem} and remains challenging outside curated benchmarks.",
    "A key bottleneck is {problem}, which existing {artifact}s do not address.",
    "However, deployments still suffer from {problem}; this remains an open problem in {setting}.",
)

#: Benefit sentences.  Every variant carries at least one narrator benefit marker.
BENEFIT_TEMPLATES: Final[tuple[str, ...]] = (
    "We show that {method} outperforms {baseline} by {pct}% on {benchmark}.",
    "The approach achieves a {speedup}x speedup while reducing {metric} by {pct}%.",
    "Our approach enables {benefit} and reduces {metric} by {pct}% relative to {baseline}.",
    "We show that {method} enables {benefit} and outperforms {baseline} under {setting}.",
    "The method reduces {metric} by {pct}% and achieves {benefit} on {benchmark}.",
    "We show that the design enables {benefit}, which {baseline} cannot provide.",
)

#: Context sentences that open an abstract.
CONTEXT_TEMPLATES: Final[tuple[str, ...]] = (
    "{TermPhrase} has become a central building block of {application}.",
    "{TermPhrase} is increasingly deployed in {application}.",
    "Recent work on {termPhrase} has renewed interest in {aspect} for {application}.",
    "{TermPhrase} promises to change how {application} is engineered.",
    "Interest in {termPhrase} has grown quickly across {application} and adjacent fields.",
)

#: Method sentences that introduce the contribution.
METHOD_TEMPLATES: Final[tuple[str, ...]] = (
    "In this paper we present {method}, a novel approach that combines {technique} with "
    "{technique2}.",
    "We introduce {method}, a proposed method built on {technique}.",
    "This work presents {method}, which applies {technique} to {aspect}.",
    "We describe {method}, a novel approach to {aspect} based on {technique}.",
)

PERCENTAGES: Final[tuple[int, ...]] = (12, 15, 18, 21, 24, 27, 31, 34, 38, 42, 46, 53, 61)
SPEEDUPS: Final[tuple[str, ...]] = ("1.4", "1.8", "2.1", "2.6", "3.1", "3.8", "4.5")


def method_name(rng: random.Random) -> str:
    """Synthesise a plausible system or method name such as ``LatticeForge``."""
    return f"{rng.choice(METHOD_PREFIXES)}{rng.choice(METHOD_SUFFIXES)}"


def surface_term(topic: Topic, rng: random.Random, introduce_acronym: bool) -> str:
    """Return a surface form of the topic term for use in running text.

    When ``introduce_acronym`` is set and the topic has one, the ``Long Form (ACR)``
    pattern is emitted so the acronym-mining rule (methodology §7.4) has material.

    Args:
        topic: Topic being written about.
        rng: Document RNG.
        introduce_acronym: Whether to emit the ``Long Form (ACR)`` pattern.

    Returns:
        A surface form: canonical term, alias, acronym, or ``term (ACR)``.
    """
    if topic.acronym and introduce_acronym:
        return f"{topic.term} ({topic.acronym})"
    choices = [topic.term, topic.term, *topic.aliases]
    if topic.acronym:
        choices.append(topic.acronym)
    return rng.choice(choices)


def sentence_case(text: str) -> str:
    """Capitalise the first character, leaving the rest (acronyms, names) untouched."""
    return text[:1].upper() + text[1:] if text else text


def indefinite_article(text: str) -> str:
    """Return ``an`` before a vowel sound and ``a`` otherwise (good enough for fixtures)."""
    return "an" if text[:1].lower() in "aeiou" else "a"


def slot_values(topic: Topic, rng: random.Random) -> dict[str, str]:
    """Draw one value for each domain vocabulary slot plus topic-specific fragments."""
    slots = DOMAIN_SLOTS[topic.domain]
    techniques = list(slots["techniques"])
    first_technique = rng.choice(techniques)
    remaining = [item for item in techniques if item != first_technique] or techniques
    values = {
        "application": rng.choice(slots["applications"]),
        "artifact": rng.choice(slots["artifacts"]),
        "setting": rng.choice(slots["settings"]),
        "technique": first_technique,
        "technique2": rng.choice(remaining),
        "aspect": rng.choice(slots["aspects"]),
        "metric": rng.choice(slots["metrics"]),
        "baseline": rng.choice(slots["baselines"]),
        "benchmark": rng.choice(slots["benchmarks"]),
        "problem": rng.choice(topic.problems),
        "benefit": rng.choice(topic.benefits),
        "pct": str(rng.choice(PERCENTAGES)),
        "speedup": rng.choice(SPEEDUPS),
    }
    # Title-case variants for slots that can open a sentence or a headline.
    for key in ("application", "setting", "technique", "aspect", "artifact", "benefit"):
        values[key[:1].upper() + key[1:]] = sentence_case(values[key])
    return values


def render_title(plan: DocPlan, values: dict[str, str], method: str, rng: random.Random,
                 org: str, org2: str, repo_name: str) -> str:
    """Render a title appropriate for the document's source class."""
    topic = plan.topic
    term = rng.choice((topic.term, topic.term, *topic.aliases))
    if plan.source_class == "PATENT":
        template = rng.choice(PATENT_TITLE_TEMPLATES)
        return template.format(term=term, Term=sentence_case(term),
                               gerund=rng.choice(PATENT_GERUNDS), **values)
    if plan.source_class == "NEWS":
        template = rng.choice(NEWS_TITLE_TEMPLATES)
        return template.format(term=term, Term=sentence_case(term), org=org, org2=org2,
                               **values)
    if plan.source_class == "CODE_REPOSITORY":
        tagline = rng.choice(REPO_TAGLINE_TEMPLATES).format(term=term, **values)
        return f"{repo_name}: {tagline}"
    template = rng.choice(TITLE_TEMPLATES)
    return template.format(term=term, Term=sentence_case(term), Method=method,
                           method=method, **values)


def render_abstract(plan: DocPlan, values: dict[str, str], method: str, rng: random.Random,
                    org: str, org2: str, repo_name: str, introduce_acronym: bool) -> str:
    """Render an abstract that carries the extractive narrator's cue phrases.

    Every abstract contains at least one problem marker (``however`` / ``remains
    challenging`` / ``is limited by`` / ``suffers from`` / ``bottleneck`` / ``open
    problem``) and at least one benefit marker (``we show`` / ``achieves`` /
    ``outperforms`` / ``reduces ... by`` / ``enables`` / ``speedup``), plus at least one
    deliberately generic n-gram from :data:`NOISE_NGRAMS`.

    Args:
        plan: Document coordinates.
        values: Vocabulary slot values from :func:`slot_values`.
        method: Synthesised method name.
        rng: Document RNG.
        org: Primary organisation.
        org2: Secondary organisation (used by NEWS prose).
        repo_name: Repository name (used by CODE_REPOSITORY prose).
        introduce_acronym: Whether to emit the ``Long Form (ACR)`` pattern.

    Returns:
        A multi-sentence abstract.
    """
    topic = plan.topic
    term_phrase = surface_term(topic, rng, introduce_acronym)
    problem = rng.choice(PROBLEM_TEMPLATES).format(**values)
    benefit = rng.choice(BENEFIT_TEMPLATES).format(method=method, **values)
    noise = rng.choice(NOISE_SENTENCES).format(**values)

    if plan.source_class == "PATENT":
        country = COUNTRY_NAMES[ORGANIZATIONS[org][1]]
        return " ".join((
            f"{sentence_case(indefinite_article(values['artifact']))} "
            f"{values['artifact']} for {topic.term} is disclosed.",
            f"In known systems, {values['problem']} is limited by the available "
            f"{values['aspect']}, and reliable operation remains challenging.",
            f"The disclosed {values['artifact']} comprises a control unit and a "
            f"{values['technique']} stage configured to monitor {values['aspect']} during "
            f"operation.",
            f"The described arrangement enables {values['benefit']} and reduces "
            f"{values['metric']} by up to {values['pct']} percent relative to "
            f"{values['baseline']}.",
            f"Embodiments are described with reference to deployments in {country}.",
        ))

    if plan.source_class == "CODE_REPOSITORY":
        return " ".join((
            f"{repo_name} is an open-source implementation of {term_phrase} for "
            f"{values['application']}, maintained by contributors from {org}.",
            f"The project provides {values['technique']}, reproducible baselines and a "
            f"test suite covering {values['aspect']}.",
            problem,
            benefit,
            "Contributions and issue reports are welcome.",
        ))

    if plan.source_class == "NEWS":
        country = COUNTRY_NAMES[ORGANIZATIONS[org][1]]
        return " ".join((
            f"{org}, based in {country}, said this week that its {term_phrase} programme "
            f"targets {values['application']}.",
            f"The company claims the system enables {values['benefit']} and reduces "
            f"{values['metric']} by {values['pct']}% compared with {values['baseline']}.",
            f"However, {values['problem']} remains challenging before wide deployment, "
            f"analysts caution.",
            f"{org2} announced a competing effort earlier in the year, and standards bodies "
            f"have started to track the area.",
        ))

    context = rng.choice(CONTEXT_TEMPLATES).format(
        TermPhrase=sentence_case(term_phrase), termPhrase=term_phrase, **values)
    method_sentence = rng.choice(METHOD_TEMPLATES).format(method=method, **values)
    return " ".join((context, problem, method_sentence, benefit, noise))


# --------------------------------------------------------------------------------------
# Authors, venues, topics, metrics
# --------------------------------------------------------------------------------------


def pick_organizations(plan: DocPlan, rng: random.Random) -> list[str]:
    """Choose the organisations behind a document.

    Anchor organisations are over-sampled so that trend cards get a stable
    ``caseExample``; co-authoring organisations come from the domain pool, which is what
    gives the ``diffusion.orgBreadth`` indicator something to measure.

    Args:
        plan: Document coordinates.
        rng: Document RNG.

    Returns:
        1–3 organisation names, first one primary.  Never empty.
    """
    topic = plan.topic
    pool = DOMAIN_ORGS[topic.domain]
    if topic.solo_first_year and plan.year == topic.first_year:
        return [topic.anchor_orgs[0]]

    primary = (rng.choice(topic.anchor_orgs) if rng.random() < 0.42 else rng.choice(pool))
    chosen = [primary]
    if plan.source_class == "PATENT":
        corporate = [name for name in pool if ORGANIZATIONS[name][0] == "COMPANY"]
        if corporate and ORGANIZATIONS[primary][0] != "COMPANY":
            chosen = [rng.choice(corporate)]
        extra = 0
    else:
        extra = rng.choice((0, 1, 1, 2))
    for _ in range(extra):
        candidate = rng.choice(pool)
        if candidate not in chosen:
            chosen.append(candidate)
    return chosen


def make_authors(plan: DocPlan, organizations: Sequence[str], rng: random.Random) -> list[dict]:
    """Build the author list.

    NEWS bylines deliberately carry no organisation: a journalist's employer is not a
    research organisation, and counting outlets would inflate both ``orgBreadth`` and the
    ``industry`` component of ``impact``.

    Args:
        plan: Document coordinates.
        organizations: Organisations for this document, primary first.
        rng: Document RNG.

    Returns:
        Author objects matching the ``authors`` item schema.
    """
    if plan.source_class == "NEWS":
        count = rng.choice((1, 1, 2))
        return [
            {
                "fullName": f"{rng.choice(GIVEN_NAMES)} {rng.choice(FAMILY_NAMES)}",
                "orcid": None,
                "organizationName": None,
                "organizationType": None,
                "organizationCountry": None,
            }
            for _ in range(count)
        ]

    if plan.source_class == "CODE_REPOSITORY":
        count = rng.choice((1, 2, 2, 3))
    elif plan.source_class == "PATENT":
        count = rng.choice((1, 2, 2, 3))
    else:
        count = rng.choice((2, 3, 3, 4, 5, 6))

    authors: list[dict] = []
    for position in range(count):
        org = organizations[position % len(organizations)] if position else organizations[0]
        org_type, country = ORGANIZATIONS[org]
        full_name = f"{rng.choice(GIVEN_NAMES)} {rng.choice(FAMILY_NAMES)}"
        authors.append({
            "fullName": full_name,
            "orcid": orcid_for(full_name, org) if rng.random() < 0.55 else None,
            "organizationName": org,
            "organizationType": org_type,
            "organizationCountry": country,
        })
    return authors


def make_venue(plan: DocPlan, rng: random.Random, patent_number: str | None,
               outlet: tuple[str, str] | None) -> dict | None:
    """Build the venue object for a document (``None`` where a venue makes no sense)."""
    if plan.source_class == "PREPRINT":
        return {"name": "arXiv", "type": "PREPRINT_SERVER", "issn": None}
    if plan.source_class == "JOURNAL_ARTICLE":
        name, venue_type = rng.choice(DOMAIN_VENUES[plan.topic.domain])
        return {"name": name, "type": venue_type,
                "issn": issn_for(name) if venue_type == "JOURNAL" else None}
    if plan.source_class == "PATENT":
        return {"name": office_for_patent(patent_number or "US"), "type": "PATENT_OFFICE",
                "issn": None}
    if plan.source_class == "CODE_REPOSITORY":
        return {"name": "GitHub", "type": "CODE_HOST", "issn": None}
    return {"name": (outlet or ("IEEE Spectrum", ""))[0], "type": "NEWS_OUTLET", "issn": None}


def make_topics(plan: DocPlan, rng: random.Random) -> list[dict]:
    """Build source subject codes (arXiv categories, OpenAlex concepts, CPC, repo topics).

    These are the *source's own* rubrics; they are what the domain-relevance filter
    (methodology §7.6) can lean on.  No ground-truth topic label is ever written into a
    document — the mapping lives only in :data:`TOPICS` and the manifest, so the pipeline
    cannot cheat.
    """
    domain = plan.topic.domain
    entries: list[tuple[str, str]] = []
    if plan.source_class == "PREPRINT":
        categories = list(DOMAIN_ARXIV[domain])
        primary = rng.choice(categories)
        entries.append((primary, primary))
        secondary = rng.choice([c for c in categories if c != primary] or categories)
        entries.append((secondary, secondary))
    elif plan.source_class == "PATENT":
        entries.extend(rng.sample(list(DOMAIN_CPC[domain]), k=min(2, len(DOMAIN_CPC[domain]))))
    elif plan.source_class == "CODE_REPOSITORY":
        picks = rng.sample(list(DOMAIN_REPO_TOPICS[domain]),
                           k=min(2, len(DOMAIN_REPO_TOPICS[domain])))
        entries.extend((pick, pick.replace("-", " ")) for pick in picks)
    elif plan.source_class == "NEWS":
        entries.append((f"news:{domain}", domain))
    else:
        entries.extend(rng.sample(list(DOMAIN_CONCEPTS[domain]),
                                  k=min(3, len(DOMAIN_CONCEPTS[domain]))))

    scores = sorted((round(rng.uniform(0.42, 0.98), 4) for _ in entries), reverse=True)
    return [{"code": code, "label": label, "score": score}
            for (code, label), score in zip(entries, scores, strict=True)]


def make_citation_count(plan: DocPlan, rng: random.Random) -> int | None:
    """Model citations as ``base * age`` with per-document dispersion.

    Repositories and news carry no citation count (their reach lives in
    :data:`extraMetrics` instead), which is realistic and keeps ``impact.citationVel``
    grounded in scholarly sources only.
    """
    if plan.source_class in ("CODE_REPOSITORY", "NEWS"):
        return None
    age = max(0, YEAR_NOW - plan.year)
    base = CITATION_BASE[plan.topic.citation_profile]
    if plan.source_class == "PATENT":
        return max(0, int(round(rng.gauss(0.55 * base * min(age, 4) / 3.0, 1.6))))
    expected = base * (age ** 1.15) * rng.uniform(0.35, 2.1)
    if plan.source_class == "JOURNAL_ARTICLE":
        expected *= 1.35
    return max(0, int(round(expected)))


def make_extra_metrics(plan: DocPlan, rng: random.Random) -> dict:
    """Build class-specific provenance metrics (free-form object in the schema)."""
    if plan.source_class == "PREPRINT":
        return {"versionCount": rng.choice((1, 1, 2, 2, 3)),
                "primaryCategory": rng.choice(DOMAIN_ARXIV[plan.topic.domain])}
    if plan.source_class == "JOURNAL_ARTICLE":
        return {"referenceCount": rng.randrange(18, 84),
                "isOpenAccess": rng.random() < 0.58}
    if plan.source_class == "PATENT":
        return {"claimCount": rng.randrange(8, 32), "familySize": rng.randrange(1, 9)}
    if plan.source_class == "CODE_REPOSITORY":
        stars = int(round(rng.lognormvariate(5.2, 1.25)))
        return {"stars": stars, "forks": max(1, stars // rng.randrange(4, 12)),
                "openIssues": rng.randrange(0, 90),
                "primaryLanguage": rng.choice(REPO_LANGUAGES)}
    return {"wordCount": rng.randrange(420, 1500), "outletTier": rng.choice((1, 1, 2, 3))}


# --------------------------------------------------------------------------------------
# Document assembly
# --------------------------------------------------------------------------------------

#: Origin source identifier per source class (``JOURNAL_ARTICLE`` is chosen per document).
SOURCE_ID_BY_CLASS: Final[dict[str, str]] = {
    "PREPRINT": "arxiv",
    "PATENT": "patentsview",
    "CODE_REPOSITORY": "github",
    "NEWS": "rss",
}

#: Maximum number of retries when an external id or a title collides.
MAX_VARIANTS: Final[int] = 12


def normalized_dedup_material(title: str, authors: Sequence[dict], year: int) -> str:
    """Build the ``title|authors|year`` string hashed into ``dedupKey`` (FR-04.3)."""
    normalized_title = " ".join(title.lower().split())
    author_part = ";".join(author["fullName"].lower() for author in authors)
    return f"{normalized_title}|{author_part}|{year}"


def build_document(plan: DocPlan, used_external_ids: set[str], used_titles: set[str]) -> dict:
    """Materialise one document conforming to ``document-ingested.event.json``.

    Independent sub-RNGs are used for identifiers, organisations, metadata and text, so a
    retry in one dimension (say, an external-id collision) cannot perturb another.

    Args:
        plan: Document coordinates.
        used_external_ids: Already-used ``sourceId:externalId`` keys, mutated in place.
        used_titles: Already-used titles, mutated in place.

    Returns:
        A document dictionary with keys in schema declaration order.
    """
    topic = plan.topic
    coordinates = (topic.slug, plan.year, plan.source_class, plan.index)
    meta_rng = stable_rng(*coordinates, "meta")
    org_rng = stable_rng(*coordinates, "org")

    published = day_in_year(plan.year, meta_rng)
    source_id = SOURCE_ID_BY_CLASS.get(
        plan.source_class, "crossref" if meta_rng.random() < 0.6 else "openalex")

    repo_owner = org_rng.choice(DOMAIN_REPO_OWNERS[topic.domain])
    outlet = org_rng.choice(DOMAIN_OUTLETS[topic.domain])
    organizations = pick_organizations(plan, org_rng)
    primary_org = organizations[0]
    secondary_org = organizations[1] if len(organizations) > 1 else org_rng.choice(
        [name for name in DOMAIN_ORGS[topic.domain] if name != primary_org])

    external_id = ""
    for attempt in range(MAX_VARIANTS):
        external_id = make_external_id(plan, published, source_id, attempt, repo_owner,
                                       slugify(outlet[0]))
        if f"{source_id}:{external_id}" not in used_external_ids:
            break
    used_external_ids.add(f"{source_id}:{external_id}")

    repo_name = external_id.split("/", 1)[1] if source_id == "github" else ""

    title = ""
    abstract = ""
    for variant in range(MAX_VARIANTS):
        text_rng = stable_rng(*coordinates, "text", variant)
        values = slot_values(topic, text_rng)
        method = method_name(text_rng)
        introduce_acronym = topic.acronym is not None and text_rng.random() < 0.45
        title = render_title(plan, values, method, text_rng, primary_org, secondary_org,
                             repo_name)
        abstract = render_abstract(plan, values, method, text_rng, primary_org,
                                   secondary_org, repo_name, introduce_acronym)
        if title not in used_titles:
            break
    used_titles.add(title)

    doi: str | None = None
    arxiv_id: str | None = None
    patent_number: str | None = None
    if source_id == "arxiv":
        arxiv_id = external_id
        url = f"https://arxiv.org/abs/{external_id}"
    elif source_id == "crossref":
        doi = external_id
        url = f"https://doi.org/{doi}"
    elif source_id == "openalex":
        doi = make_doi(plan, published, stable_rng(*coordinates, "doi"))
        url = f"https://openalex.org/{external_id}"
    elif source_id == "patentsview":
        patent_number = external_id
        url = f"https://patents.google.com/patent/{external_id}/en"
    elif source_id == "github":
        url = f"https://github.com/{external_id}"
    else:
        url = (f"https://www.{outlet[1]}/{published:%Y/%m}/"
               f"{slugify(title)[:72]}")

    authors = make_authors(plan, organizations, meta_rng)
    venue = make_venue(plan, meta_rng, patent_number, outlet)
    topic_codes = make_topics(plan, meta_rng)
    citation_count = make_citation_count(plan, meta_rng)
    extra_metrics = make_extra_metrics(plan, meta_rng)

    if doi:
        dedup_key = sha256_hex(f"doi:{doi.lower()}")
    elif arxiv_id:
        dedup_key = sha256_hex(f"arxiv:{arxiv_id}")
    else:
        dedup_key = sha256_hex(normalized_dedup_material(title, authors, plan.year))

    fetched_at = FETCH_BASE + timedelta(
        seconds=stable_rng(*coordinates, "fetch").randrange(FETCH_WINDOW_SECONDS))

    document_id = str(uuid.uuid5(NAMESPACE, f"{source_id}:{external_id}"))

    return {
        "documentId": document_id,
        "sourceId": source_id,
        "sourceClass": plan.source_class,
        "externalId": external_id,
        "title": title,
        "abstractText": abstract,
        "language": "en",
        "publishedOn": published.isoformat(),
        "doi": doi,
        "arxivId": arxiv_id,
        "patentNumber": patent_number,
        "url": url,
        "venue": venue,
        "authors": authors,
        "topics": topic_codes,
        "citationCount": citation_count,
        "extraMetrics": extra_metrics,
        "dedupKey": dedup_key,
        "fetchedAt": fetched_at.strftime("%Y-%m-%dT%H:%M:%SZ"),
    }


def build_corpus() -> list[dict]:
    """Generate every document and return them sorted by the canonical output order."""
    used_external_ids: set[str] = set()
    used_titles: set[str] = set()
    documents = [build_document(plan, used_external_ids, used_titles)
                 for plan in plan_corpus()]
    documents.sort(key=lambda doc: (doc["publishedOn"], doc["sourceId"], doc["externalId"]))
    return documents


# --------------------------------------------------------------------------------------
# Output
# --------------------------------------------------------------------------------------


def render_documents(documents: Sequence[dict]) -> str:
    """Serialise documents as JSON Lines (compact separators, UTF-8, trailing newline)."""
    lines = [json.dumps(document, ensure_ascii=False, separators=(",", ":"))
             for document in documents]
    return "\n".join(lines) + "\n"


def content_digest(documents: Sequence[dict]) -> str:
    """SHA-256 над содержимым документов, а не над их идентификаторами.

    Заведена рядом с :func:`content_hash`, а не вместо неё, потому что вопросов два, а величина
    была одна. `content_hash` отвечает «тот же **набор** документов»: идентификатор выводится как
    `uuid5(sourceId:externalId)` и от содержимого не зависит по построению — так пересобранный из
    того же источника документ узнаётся как прежний, и на этом стоит дедупликация. Но ровно поэтому
    он молчит, когда документы пересобраны под теми же идентификаторами: правка генератора, убравшая
    рубрики у 28 новостей, оставила его неизменным (пункт 16 бэклога).

    Здесь — второй вопрос: «те же документы». Хешируется канонический JSON каждого документа в том
    же порядке, в каком они пишутся в файл, поэтому величина совпадает с тем, что читатель увидит.
    """
    canonical = "\n".join(
        json.dumps(document, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        for document in documents
    )
    return sha256_hex(canonical)


def content_hash(documents: Sequence[dict]) -> str:
    """SHA-256 over the concatenation of the lexicographically sorted document ids.

    Document ids are fixed-length UUID strings, so plain concatenation is unambiguous.
    This is the value that ``corpus_snapshots.content_hash`` is compared against.
    """
    return sha256_hex("".join(sorted(document["documentId"] for document in documents)))


def build_manifest(documents: Sequence[dict], documents_bytes: bytes) -> dict:
    """Build ``manifest.json`` — the artefact CI diffs to detect accidental corpus drift."""
    per_class = Counter(document["sourceClass"] for document in documents)
    per_year = Counter(document["publishedOn"][:4] for document in documents)
    per_source = Counter(document["sourceId"] for document in documents)

    topic_counts = Counter()
    for plan in plan_corpus():
        topic_counts[plan.topic.slug] += 1

    return {
        "version": CORPUS_VERSION,
        "generator": "generate_corpus.py",
        "generatorSeed": SEED,
        "namespace": str(NAMESPACE),
        "schemaRef": SCHEMA_REF,
        "documentsFile": DOCUMENTS_FILE,
        "documentCount": len(documents),
        "windowFrom": f"{YEAR_FIRST}-01-01",
        "windowTo": f"{YEAR_LAST}-12-31",
        "contentHash": content_hash(documents),
        # Состав и содержимое — разные вопросы; см. `content_digest`.
        "contentDigest": content_digest(documents),
        "documentsSha256": hashlib.sha256(documents_bytes).hexdigest(),
        "countsBySourceClass": {name: per_class[name] for name in SOURCE_CLASS_ORDER
                                if per_class[name]},
        "countsBySourceId": dict(sorted(per_source.items())),
        "countsByYear": {str(year): per_year[str(year)]
                         for year in range(YEAR_FIRST, YEAR_LAST + 1)},
        "noiseNgrams": list(NOISE_NGRAMS),
        "topics": [
            {
                "slug": topic.slug,
                "term": topic.term,
                "acronym": topic.acronym,
                "domain": topic.domain,
                "firstYear": topic.first_year,
                "profile": topic.profile,
                "docCount": topic_counts[topic.slug],
                "expectation": topic.expectation,
            }
            for topic in TOPICS
        ],
    }


def render_manifest(manifest: dict) -> str:
    """Serialise the manifest with stable formatting (2-space indent, trailing newline)."""
    return json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"


def validate_tables() -> None:
    """Fail fast on table mistakes that would silently distort the corpus."""
    for domain, names in sorted(DOMAIN_ORGS.items()):
        unknown = sorted(set(names) - set(ORGANIZATIONS))
        if unknown:
            raise ValueError(f"domain {domain!r} references unknown organisations: {unknown}")
    for archetype, mix in sorted(CLASS_MIX.items()):
        if abs(math.fsum(mix.values()) - 1.0) > 1e-9:
            raise ValueError(f"class mix {archetype!r} does not sum to 1.0")
        if sorted(mix) != sorted(SOURCE_CLASS_ORDER):
            raise ValueError(f"class mix {archetype!r} has an unexpected class set")
    slugs = [topic.slug for topic in TOPICS]
    if len(set(slugs)) != len(slugs):
        raise ValueError("duplicate topic slugs")
    for topic in TOPICS:
        if topic.profile not in PROFILE_SHAPES:
            raise ValueError(f"topic {topic.slug!r} has unknown profile {topic.profile!r}")
        if topic.domain not in DOMAIN_ORGS:
            raise ValueError(f"topic {topic.slug!r} has unknown domain {topic.domain!r}")
        if not YEAR_FIRST <= topic.first_year <= YEAR_LAST:
            raise ValueError(f"topic {topic.slug!r} has out-of-window first year")
        missing = sorted(set(topic.anchor_orgs) - set(DOMAIN_ORGS[topic.domain]))
        if missing:
            raise ValueError(f"topic {topic.slug!r} anchors outside its domain pool: {missing}")
        if not 2 <= len(topic.problems) <= 4 or not 2 <= len(topic.benefits) <= 4:
            raise ValueError(f"topic {topic.slug!r} needs 2-4 problem/benefit fragments")
    total = sum(topic.volume for topic in TOPICS)
    if not CORPUS_SIZE_BAND[0] <= total <= CORPUS_SIZE_BAND[1]:
        raise ValueError(f"corpus size {total} outside the agreed band {CORPUS_SIZE_BAND}")


def print_summary(manifest: dict) -> None:
    """Print the distribution tables used in the corpus README and in review."""
    print(f"documents        : {manifest['documentCount']}")
    print(f"contentHash      : {manifest['contentHash']}")
    print(f"documentsSha256  : {manifest['documentsSha256']}")
    print("\nby source class:")
    for name, count in manifest["countsBySourceClass"].items():
        print(f"  {name:<16} {count:>5}")
    print("\nby source id:")
    for name, count in manifest["countsBySourceId"].items():
        print(f"  {name:<16} {count:>5}")
    print("\nby year:")
    for year, count in manifest["countsByYear"].items():
        print(f"  {year:<16} {count:>5}")
    print("\nby topic:")
    for entry in manifest["topics"]:
        print(f"  {entry['slug']:<32} {entry['domain']:<9} {entry['firstYear']} "
              f"{entry['profile']:<13} {entry['docCount']:>4}")


def main(argv: Sequence[str] | None = None) -> int:
    """Generate the corpus and write ``documents.jsonl`` and ``manifest.json``."""
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out-dir", type=Path, default=Path(__file__).resolve().parent,
                        help="directory to write into (default: this script's directory)")
    parser.add_argument("--summary", action="store_true",
                        help="print distribution tables after generating")
    args = parser.parse_args(argv)

    validate_tables()
    documents = build_corpus()

    documents_text = render_documents(documents)
    documents_bytes = documents_text.encode("utf-8")
    manifest = build_manifest(documents, documents_bytes)

    out_dir: Path = args.out_dir
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / DOCUMENTS_FILE).write_bytes(documents_bytes)
    (out_dir / MANIFEST_FILE).write_text(render_manifest(manifest), encoding="utf-8")

    if args.summary:
        print_summary(manifest)
    else:
        print(f"{manifest['documentCount']} documents -> {out_dir / DOCUMENTS_FILE}")
        print(f"contentHash {manifest['contentHash']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
