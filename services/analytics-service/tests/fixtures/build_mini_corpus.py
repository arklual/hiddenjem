"""Generator of the local stand-in corpus used until the golden corpus lands.

Run with ``python tests/fixtures/build_mini_corpus.py`` to regenerate
``tests/fixtures/mini_corpus.jsonl``. The output is fully deterministic: ids are UUIDv5
derived from the document key, and nothing here reads the clock or a random source.

The corpus is designed so that the methodology has something to measure:

* ``retrieval augmented generation`` — young, fast growing, many organisations (the
  archetypal weak signal);
* ``neural operator`` — young, growing, narrow (low diffusion);
* ``quantum error correction`` — growing with patents and industry (high impact);
* ``convolutional neural network`` — old and loud (mainstream, BRULE-3);
* ``spiking neural network`` — decaying (``growth = 0`` ⇒ BRULE-4 zeroes the score);
* ``федеративное обучение`` — Russian-language coverage of federated learning.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any
from uuid import NAMESPACE_URL, uuid5

OUTPUT = Path(__file__).resolve().parent / "mini_corpus.jsonl"

ORGANIZATIONS: dict[str, tuple[str, str]] = {
    "Stanford University": ("UNIVERSITY", "US"),
    "MIT": ("UNIVERSITY", "US"),
    "ETH Zurich": ("UNIVERSITY", "CH"),
    "Tsinghua University": ("UNIVERSITY", "CN"),
    "Moscow Institute of Physics and Technology": ("UNIVERSITY", "RU"),
    "DeepFlow Technologies Inc": ("COMPANY", "US"),
    "Nimbus Labs Ltd": ("COMPANY", "GB"),
    "Quantum Forge Corp": ("COMPANY", "DE"),
    "Yandex Research": ("COMPANY", "RU"),
    "Fraunhofer Institute": ("RESEARCH_INSTITUTE", "DE"),
    "National Institute of Standards": ("GOVERNMENT", "US"),
    "Open Signal Foundation": ("NONPROFIT", "NL"),
}

VENUES = [
    "Transactions on Machine Learning",
    "Journal of Computational Physics",
    "Proceedings of the Conference on Learning Systems",
    "Quantum Information Processing",
    "Известия высших учебных заведений",
    "IEEE Access",
]


def _doc(
    key: str,
    *,
    year: int,
    title: str,
    abstract: str,
    source_class: str,
    orgs: list[str],
    citations: int | None,
    venue: str | None,
    language: str = "en",
    patent_number: str | None = None,
) -> dict[str, Any]:
    """Build one ``DocumentIngested`` payload."""
    return {
        "documentId": str(uuid5(NAMESPACE_URL, f"horizon-mini/{key}")),
        "sourceId": {"PATENT": "patentsview", "NEWS": "rss", "CODE_REPOSITORY": "github"}.get(
            source_class, "arxiv"
        ),
        "sourceClass": source_class,
        "externalId": key,
        "title": title,
        "abstractText": abstract,
        "language": language,
        "publishedOn": f"{year}-06-15",
        "doi": f"10.5555/{key}",
        "patentNumber": patent_number,
        "url": f"https://example.org/{key}",
        "venue": {"name": venue} if venue else None,
        "authors": [
            {
                "fullName": f"{name.split()[0]} Researcher",
                "organizationName": name,
                "organizationType": ORGANIZATIONS[name][0],
                "organizationCountry": ORGANIZATIONS[name][1],
            }
            for name in orgs
        ],
        "citationCount": citations,
        "dedupKey": key,
        "fetchedAt": "2026-08-05T00:00:00Z",
    }


#: Filler sentences rotated per document so that abstracts differ.
#: Identical abstracts would collapse the effective rank of the LSA space and make every
#: term vector in the corpus look alike — an artefact of the fixture, not of the method.
FILLER = [
    "Experiments cover {a} public collections and {b} deployment regimes.",
    "The ablation isolates the contribution of stage {a} in the {b}-stage cascade.",
    "Latency is measured over {a} runs on {b} commodity server nodes.",
    "We release {a} configuration files covering {b} reproducible settings.",
    "A user study with {a} domain analysts spanning {b} organisations validates the output.",
    "The analysis covers {a} cold-start workloads and {b} steady-state workloads.",
    "Memory consumption grows from {a} to {b} gigabytes as shards are added.",
    "Results hold across {a} independent seeds and {b} batch sizes.",
    "We compare against {a} published baselines under a budget of {b} GPU hours.",
    "The failure analysis catalogues {a} distinct modes across {b} families.",
    "Throughput scales to {a} concurrent clients before degrading beyond {b} requests.",
    "An error analysis on {a} sampled cases explains {b} percent of the residual gap.",
]


def _filler(seed: int, count: int = 2) -> str:
    """Deterministic filler sentences chosen by index — no randomness anywhere.

    Numbers vary per document on purpose: verbatim-repeated sentences would create
    high-frequency n-grams that look like genuine terminology to the extractor, which is an
    artefact of the fixture rather than a property of the method.
    """
    return " ".join(
        FILLER[(seed * 5 + offset) % len(FILLER)].format(
            a=3 + (seed * 7 + offset * 11) % 90, b=2 + (seed * 13 + offset * 5) % 60
        )
        for offset in range(count)
    )


def _problem_en(seed: int) -> str:
    """Problem sentence with a per-document phrasing."""
    variants = [
        "However, current systems remain challenging to deploy because retrieval quality degrades on long documents.",
        "However, the index update cost is prohibitively high for corpora above ten million passages.",
        "Grounding quality is limited by the recall of the first-stage retriever, which remains an open problem.",
        "Deployment suffers from stale indexes, and incremental refresh is a bottleneck at production scale.",
    ]
    return variants[seed % len(variants)]


def _benefit_en(seed: int) -> str:
    """Benefit sentence with a per-document phrasing."""
    variants = [
        "We show that the proposed pipeline outperforms dense baselines and reduces answer latency by 40 percent.",
        "The method achieves a 2.3x speedup over the strongest published baseline without sacrificing factual accuracy.",
        "We demonstrate that hybrid indexing enables sub-second responses on a single node.",
        "Our approach improves grounded answer precision by nine points on the held-out split.",
    ]
    return variants[seed % len(variants)]


def build() -> list[dict[str, Any]]:
    """Assemble the whole corpus."""
    records: list[dict[str, Any]] = []
    org_names = list(ORGANIZATIONS)

    # ── retrieval augmented generation: young, fast growth, broad diffusion ──
    rag_counts = {2022: 1, 2023: 2, 2024: 4, 2025: 7, 2026: 9}
    classes = ["PREPRINT", "JOURNAL_ARTICLE", "CODE_REPOSITORY", "NEWS", "PATENT"]
    index = 0
    for year, count in rag_counts.items():
        for position in range(count):
            source_class = classes[(index + position) % len(classes)]
            orgs = [org_names[(index + position) % len(org_names)]]
            if position % 2 == 0:
                orgs.append(org_names[(index + position + 5) % len(org_names)])
            records.append(
                _doc(
                    f"rag-{year}-{position}",
                    year=year,
                    title=(
                        "Retrieval Augmented Generation (RAG) for enterprise knowledge bases"
                        if position == 0
                        else "Scaling retrieval augmented generation with hybrid indexes"
                    ),
                    abstract=(
                        "Retrieval augmented generation combines a dense retriever with a "
                        "large language model to ground answers in a document corpus. "
                        f"{_problem_en(index + position)} {_benefit_en(index + position + 1)} "
                        f"{_filler(index + position)}"
                    ),
                    source_class=source_class,
                    orgs=orgs,
                    citations=max(0, 30 - (year - 2022) * 6 + position),
                    venue=VENUES[(index + position) % len(VENUES)],
                    patent_number=(f"US{year}{position:04d}" if source_class == "PATENT" else None),
                )
            )
        index += count

    # ── neural operator: young, growing, narrow (two organisations only) ──
    for year, count in {2023: 1, 2024: 2, 2025: 3, 2026: 4}.items():
        for position in range(count):
            records.append(
                _doc(
                    f"neuralop-{year}-{position}",
                    year=year,
                    title="Neural operator learning for partial differential equations",
                    abstract=(
                        "Neural operator methods learn mappings between function spaces and "
                        "solve parametric partial differential equations. However, training "
                        "remains challenging because the resolution invariance is limited by "
                        "the spectral truncation. We show that the operator achieves a "
                        "speedup of two orders of magnitude over classical solvers. "
                        f"{_filler(year * 3 + position)}"
                    ),
                    source_class="JOURNAL_ARTICLE" if position % 2 else "PREPRINT",
                    orgs=["ETH Zurich" if position % 2 else "Fraunhofer Institute"],
                    citations=12 + position,
                    venue="Journal of Computational Physics",
                )
            )

    # ── quantum error correction: growing, patent heavy, industry heavy ──
    for year, count in {2022: 1, 2023: 2, 2024: 3, 2025: 4, 2026: 5}.items():
        for position in range(count):
            is_patent = position % 3 == 0
            records.append(
                _doc(
                    f"qec-{year}-{position}",
                    year=year,
                    title="Quantum error correction with surface codes at scale",
                    abstract=(
                        "Quantum error correction protects logical qubits against decoherence. "
                        "However, the syndrome decoding throughput is a bottleneck for "
                        "real hardware. We demonstrate a decoder that enables sub-microsecond "
                        "latency and reduces the logical error rate by an order of magnitude. "
                        f"{_filler(year + position * 2)}"
                    ),
                    source_class="PATENT" if is_patent else "JOURNAL_ARTICLE",
                    orgs=(
                        ["Quantum Forge Corp", "DeepFlow Technologies Inc"]
                        if is_patent
                        else ["MIT", "National Institute of Standards"]
                    ),
                    citations=40 - position * 2,
                    venue="Quantum Information Processing",
                    patent_number=(f"EP{year}{position:04d}" if is_patent else None),
                )
            )

    # ── convolutional neural network: old, loud, mainstream ──
    for year, count in {2019: 8, 2020: 9, 2021: 9, 2022: 8, 2023: 7, 2024: 6}.items():
        for position in range(count):
            records.append(
                _doc(
                    f"cnn-{year}-{position}",
                    year=year,
                    title="Convolutional neural network architectures for image recognition",
                    abstract=(
                        "Convolutional neural network models dominate image recognition. "
                        "However, the inference cost is limited by memory bandwidth. "
                        "We show that pruning improves throughput on commodity hardware. "
                        f"{_filler(year * 2 + position)}"
                    ),
                    source_class="JOURNAL_ARTICLE" if position % 2 else "PREPRINT",
                    orgs=[org_names[position % len(org_names)]],
                    citations=200 - position * 5,
                    venue=VENUES[position % len(VENUES)],
                )
            )

    # ── spiking neural network: decaying ──
    for year, count in {2019: 5, 2020: 4, 2021: 3, 2022: 2, 2023: 1}.items():
        for position in range(count):
            records.append(
                _doc(
                    f"snn-{year}-{position}",
                    year=year,
                    title="Spiking neural network hardware for low power inference",
                    abstract=(
                        "Spiking neural network accelerators promise low power inference. "
                        "However, training suffers from the non-differentiable spike "
                        "function. We show that surrogate gradients enable competitive "
                        "accuracy on small benchmarks. "
                        f"{_filler(year + position)}"
                    ),
                    source_class="PREPRINT",
                    orgs=[org_names[position % len(org_names)], "Open Signal Foundation"],
                    citations=30 - position,
                    venue="IEEE Access",
                )
            )

    # ── Russian-language federated learning ──
    for year, count in {2023: 1, 2024: 2, 2025: 3, 2026: 3}.items():
        for position in range(count):
            records.append(
                _doc(
                    f"fedru-{year}-{position}",
                    year=year,
                    title="Федеративное обучение моделей на распределенных данных",
                    abstract=(
                        "Федеративное обучение позволяет обучать модели без передачи "
                        "персональных данных на центральный сервер. Однако сходимость "
                        "остается проблемой при неоднородных выборках, и накладные расходы "
                        "на связь ограничены пропускной способностью канала. Предлагаемый "
                        "метод сокращает объем передаваемых данных и превосходит базовые "
                        f"решения по точности. Эксперименты проведены на {3 + position} наборах "
                        f"данных с числом клиентов от {8 + position * 3} до {16 + position * 8}."
                    ),
                    source_class="JOURNAL_ARTICLE" if position % 2 else "PREPRINT",
                    orgs=[
                        (
                            "Moscow Institute of Physics and Technology"
                            if position % 2
                            else "Yandex Research"
                        )
                    ],
                    citations=8 + position,
                    venue="Известия высших учебных заведений",
                    language="ru",
                )
            )

    records.sort(key=lambda item: str(item["documentId"]))
    return records


def main() -> None:
    """Write the corpus to disk."""
    records = build()
    with OUTPUT.open("w", encoding="utf-8") as handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")
    print(f"wrote {len(records)} documents to {OUTPUT}")


if __name__ == "__main__":
    main()
