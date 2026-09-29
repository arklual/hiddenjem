"""Разметка сигналов корпуса по критериям жюри (разбор 110).

Сигналы, заранее размеченные «нет», не предлагаются; «да» идут первыми и на экспертной стадии
получают готовый вердикт вместо голосов модели.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from horizon_nlp.research.corpus import load_signals, signals_for


def _write(path: Path, *records: dict[str, object]) -> str:
    path.write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in records) + "\n", encoding="utf-8")
    return str(path)


def _signal(name: str, evidence: int, jury: bool | None = None) -> dict[str, object]:
    record: dict[str, object] = {
        "name": name, "name_ru": "", "direction": "Финтех", "description_ru": f"{name} описание",
        "companies": [], "evidence": [f"https://example.com/{name}/{i}" for i in range(evidence)],
    }
    if jury is not None:
        record["jury"] = jury
        record["jury_reason"] = "причина"
    return record


def test_rejected_signals_are_not_proposed_and_approved_come_first(tmp_path: Path) -> None:
    path = _write(
        tmp_path / "signals.jsonl",
        _signal("BNPL", 8, jury=False),
        _signal("Unlabelled rails", 6),
        _signal("Interbank tokenized deposits", 2, jury=True),
    )

    chosen = signals_for(load_signals(path), "финтех", 10)

    assert [s.name for s in chosen] == ["Interbank tokenized deposits", "Unlabelled rails"]


def test_the_jury_reuses_corpus_labels(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    from horizon_nlp import api
    from horizon_nlp.config import settings

    path = _write(
        tmp_path / "signals.jsonl",
        _signal("BNPL", 8, jury=False),
        _signal("Interbank tokenized deposits", 2, jury=True),
    )
    monkeypatch.setattr(settings, "webcorpus_signals_path", path)
    asked: list[list[str]] = []

    def fake_jury(complete, query, items):
        asked.append([item["title"] for item in items])
        return [{"n": n, "yes": 2, "votes": {}} for n in range(1, len(items) + 1)], {"seconds": 0.0}

    monkeypatch.setattr(api, "run_jury", fake_jury)
    client = TestClient(api.create_app())

    response = client.post("/jury", json={"query": "финтех", "items": [
        {"title": "interbank tokenized deposits"}, {"title": "Agentic payments"}, {"title": "BNPL"},
    ]})

    body = response.json()
    assert asked == [["Agentic payments"]]
    assert [(v["n"], v["yes"]) for v in body["verdicts"]] == [(1, 3), (2, 2), (3, 0)]
    assert body["model"].endswith("+corpus-labels")


def test_names_read_by_deep_research_are_proposed_with_their_pages(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # Имена, которые агент глубокого исследования подтвердил текстом страниц, раньше терялись при
    # сборе: по «нейроинтерфейсам» 37 имён не дошли до отчёта (разбор 110).
    from types import SimpleNamespace

    from horizon_nlp import api
    from horizon_nlp.config import settings

    monkeypatch.setattr(settings, "webcorpus_signals_path", str(tmp_path / "none.jsonl"))
    monkeypatch.setattr(settings, "panel_enabled", False)
    monkeypatch.setattr(api.generative, "propose_technologies", lambda query, limit: ((), "none"))
    api.remember_research("Нейроинтерфейсы ", [
        SimpleNamespace(url="https://precision.example/fda", technologies=["Thin-film cortical arrays"]),
        SimpleNamespace(url="https://news.example/bci", technologies=["Thin-film cortical arrays", "Speech neuroprostheses"]),
    ])
    client = TestClient(api.create_app())

    body = client.post("/propose-technologies", json={"query": "нейроинтерфейсы", "limit": 10}).json()

    assert body["technologies"][:2] == ["Thin-film cortical arrays", "Speech neuroprostheses"]
    assert body["evidence"]["Thin-film cortical arrays"] == ["https://precision.example/fda", "https://news.example/bci"]
