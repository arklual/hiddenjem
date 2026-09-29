"""Бюджет кластеризации и сборка тем не теряют отдельные имена технологий.

Бэктест на корпусе arXiv cs.DC + cs.NI, замороженном на конце 2016 года (11 288 работ): `edge
computing` (10 документов, через пять лет доля ×8,6) не дошёл до кластеризации — бюджет 3000
заполнялся по термхуду, а C-value вычитает из частоты фразы частоту содержащих её `mobile edge
computing`. `mobile edge computing` растворился в теме `mobile cloud computing`, `fog computing`
стал темой на 1747 документов вместе с одиночным `computing`. Разбор:
``docs/01-analysis/103-the-budget-that-chose-by-chance.md``.
"""

from __future__ import annotations

from dataclasses import replace
from datetime import date

import numpy as np
from tests.conftest import make_candidate, make_document

from horizon_analytics.domain.extraction.candidates import RawCandidate
from horizon_analytics.domain.extraction.termhood import TermhoodScore
from horizon_analytics.domain.models import Author, Posting
from horizon_analytics.domain.pipeline import AnalysisPipeline, _distinct_topics, _sibling_names
from horizon_analytics.domain.scoring.profile import MethodologyParameters


def test_siblings_of_one_kind_are_different_technologies() -> None:
    assert _sibling_names("mobile edge comput", "mobile cloud comput")
    assert _sibling_names("fog comput", "edge comput")
    assert _sibling_names("computation offload", "mobile data offload")


def test_refinements_reorderings_and_abbreviations_are_not_siblings() -> None:
    assert not _sibling_names("mobile comput", "mobile cloud comput")
    assert not _sibling_names("access network", "network access")
    assert not _sibling_names("sdn network", "software defin network")
    assert not _sibling_names("software-defin network", "software defin network")


def test_a_bare_head_word_does_not_join_the_name_it_is_part_of() -> None:
    computing = make_candidate("comput", documents=[f"d{i}" for i in range(200)])
    fog = make_candidate("fog comput", documents=[f"d{i}" for i in range(15)])
    edge = make_candidate("edge comput", documents=[f"d{i}" for i in range(10)])
    mobile_edge = make_candidate("mobile edge comput", documents=[f"d{i}" for i in range(9)])

    assert _distinct_topics(computing, fog)
    # Уточнение, которым пишут большинство документов короткого имени, — та же тема.
    assert not _distinct_topics(edge, mobile_edge)


def test_names_sharing_a_word_but_not_documents_are_two_topics() -> None:
    fog = make_candidate("fog comput", documents=[f"d{i}" for i in range(15)])
    paradigm = make_candidate("comput paradigm", documents=[f"d{i}" for i in range(10, 140)])
    offloading = make_candidate("computation offload", documents=[f"o{i}" for i in range(11)])
    traffic = make_candidate("offload traffic", documents=[f"o{i}" for i in range(9, 29)])

    assert _distinct_topics(fog, paradigm)
    assert _distinct_topics(offloading, traffic)


def test_names_brought_together_by_documents_stay_together() -> None:
    # Без общего слова сходство дали документы — ради этого кластеризация и существует. Запрет для
    # любых двух имён раскрыл в финтехе десятки оборотов вроде `go beyond` (разбор 103).
    plane = make_candidate("data plane", documents=[f"s{i}" for i in range(40)])
    sdn = make_candidate("software defin network", documents=[f"s{i}" for i in range(30, 200)])
    control = make_candidate("control plane", documents=[f"s{i}" for i in range(30, 60)])

    assert not _distinct_topics(plane, sdn)
    assert not _distinct_topics(control, sdn)


def test_a_cluster_is_split_by_names_and_keeps_everything_else() -> None:
    members = [
        make_candidate("comput", documents=[f"d{i}" for i in range(200)], termhood=0.9),
        make_candidate("fog comput", documents=[f"d{i}" for i in range(15)], termhood=0.6),
        make_candidate("mobile cloud comput", documents=[f"m{i}" for i in range(34)], termhood=0.7),
        make_candidate(
            "mobile edge comput", documents=[f"m{i}" for i in range(25, 34)], termhood=0.5
        ),
    ]
    vectors = np.eye(4)

    groups = AnalysisPipeline._split_distinct(range(4), members, vectors)

    assert sorted(len(group) for group in groups) == [1, 1, 1, 1]
    assert sorted(i for group in groups for i in group) == [0, 1, 2, 3]


def _raw(key: str, documents: list[str]) -> RawCandidate:
    return RawCandidate(
        key=key,
        surface=key,
        surface_forms=(key,),
        postings=tuple(Posting(document_id=d, occurrences=1) for d in documents),
        token_count=len(key.split(" ")),
        is_acronym=False,
    )


def test_the_budget_keeps_a_recent_name_used_by_many_groups() -> None:
    documents = [
        replace(
            make_document(f"d{i}", year=2016 if i < 10 else 2012),
            authors=(Author(full_name=f"Person Author{i}"),),
        )
        for i in range(40)
    ]
    strong = [_raw(f"strong term {i}", [f"d{j}" for j in range(10, 14)]) for i in range(4)]
    edge = _raw("edge comput", [f"d{i}" for i in range(10)])
    candidates = [*strong, edge]
    termhood = {
        item.key: TermhoodScore(
            key=item.key,
            termhood=0.9 if item in strong else 0.1,
            c_value=0.0,
            yake=0.0,
            yake_quality=0.0,
        )
        for item in candidates
    }

    only_termhood = MethodologyParameters(max_terms_clustered=4, cluster_budget_recent_share=0.0)
    kept, dropped = AnalysisPipeline._clustering_budget(
        candidates, termhood, documents, date(2015, 12, 31), only_termhood
    )
    assert "edge comput" not in {item.key for item in kept}
    assert [item.key for _, item in dropped] == ["edge comput"]

    shared = MethodologyParameters(max_terms_clustered=4, cluster_budget_recent_share=0.5)
    kept, _ = AnalysisPipeline._clustering_budget(
        candidates, termhood, documents, date(2015, 12, 31), shared
    )
    assert "edge comput" in {item.key for item in kept}
    assert len(kept) == 4
