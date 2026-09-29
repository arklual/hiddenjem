"""Панель экспертов по подсферам: граф LangGraph поверх веб-корпуса (разбор 110).

Один агент на всё направление читает понемногу обо всём и называет общие места. Здесь направление
делится на подсферы, и каждую ведёт свой эксперт, который читает **только свои** страницы —
найденные BM25 по фразам его подсферы. Так он видит нишу целиком: несколько раундов, пилотов и
запусков про одно и то же, а не по одному упоминанию на тему.

Граф — три шага:

1. **Оркестратор планирует**: делит запрос на 4–6 подсфер и даёт каждой 3–5 поисковых фраз
   (английские, русские, китайские).
2. **Эксперты работают параллельно** (``Send`` на каждую подсферу): BM25 по корпусу, чтение
   отрывков, до шести кандидатов в слабые сигналы. У каждого кандидата — дословные цитаты со
   страниц, которые эксперт читал; цитата сверяется с текстом страницы, и кандидат без
   подтверждённой цитаты отбрасывается. Модель может только подсказать, что найти, — граница
   ТЗ §3.1 проходит здесь так же, как у глубокого исследования.
3. **Оркестратор объединяет**: сливает дубли разных экспертов, отбрасывает мейнстрим и общие
   слова, упорядочивает по «слабости» сигнала. Доказательства слитых кандидатов объединяются.

Результат — имена с адресами страниц-доказательств. Они уходят в ``/propose-technologies`` и
дальше проходят весь путь движка: BRULE-1, мейнстрим, зрелость, балл.
"""

from __future__ import annotations

import json
import operator
import re
import time
from dataclasses import dataclass
from datetime import date
from typing import Annotated, Any, TypedDict

import structlog
from langgraph.graph import END, START, StateGraph
from langgraph.types import Send

from horizon_nlp.research.corpus import CorpusDocument, WebCorpus

__all__ = ["PanelCandidate", "run_panel"]

log = structlog.get_logger(__name__)

_PLAN_SYSTEM = """You are the lead analyst of Horizon, a service that finds WEAK technology signals:
specific technologies or applications at an early market stage (first pilots, seed/Series A
rounds, stealth exits, first products), not mainstream fields.
Split the user's direction into 4-6 distinct sub-areas where weak signals are likely. For each
sub-area give 3-5 short search phrases (2-4 words) that would literally appear in news, press
releases and blog posts about it: English first, plus Russian and Chinese where natural.
Answer with JSON only: {"subspheres": [{"name": "...", "phrases": ["...", "..."]}]}"""

_EXPERT_SYSTEM = """You are an expert analyst for ONE sub-area of a technology direction. You get
numbered documents found for your sub-area. Using ONLY these documents, name up to 6 weak
technology signals: a specific technology, mechanism or application (2-6 words, e.g. "agent
identity and access management", "tokenized deposit networks", "tactile sensing for humanoid
hands") that is at an early stage — pilots, first products, early rounds — and is gaining
traction. Not a company name, not a broad field ("AI", "blockchain", "cybersecurity"), not a
market or a goal.
For each signal give: name_en — 2-5 words, a noun phrase naming the technology or application
the way a trend report would (no parentheses, no commas, no lists, no company or product names,
no "for X and Y" tails; e.g. "agent payment protocols", "on-device LLM accelerators");
name_ru (natural Russian name, same length); trend_ru — ONE Russian sentence
describing the trend: who starts doing what new, instead of what (not a definition);
companies (from the documents); evidence — 1-3 items {"doc": <number>, "quote": "<verbatim
fragment of 6-30 words copied exactly from that document>"}.
Answer with JSON only: {"signals": [...]}"""

_MERGE_SYSTEM = """You are the lead analyst. Experts on different sub-areas proposed numbered
candidate weak signals. Merge duplicates (same technology under different names), drop
mainstream fields, broad categories and company names, and rank the rest from the most
promising weak signal to the least (early stage, growing, several independent sources).
Every name_en must be 2-5 words — a technology or application name, no parentheses, commas or
lists; shorten long names without losing the specific mechanism.
Answer with JSON only: {"signals": [{"members": [<numbers>], "name_en": "...", "name_ru": "...",
"trend_ru": "..."}]} — at most {limit} items."""


@dataclass(frozen=True, slots=True)
class PanelCandidate:
    """Кандидат панели: имя, русское имя, тренд одной фразой, компании и страницы-доказательства."""

    name: str
    name_ru: str
    trend_ru: str
    subsphere: str
    companies: tuple[str, ...]
    evidence: tuple[str, ...]


class _State(TypedDict, total=False):
    query: str
    start: date
    end: date
    limit: int
    subspheres: list[dict[str, Any]]
    findings: Annotated[list[dict[str, Any]], operator.add]
    merged: list[dict[str, Any]]
    trace: Annotated[list[dict[str, Any]], operator.add]


class _ExpertState(TypedDict):
    query: str
    start: date
    end: date
    sub: dict[str, Any]


def _json(answer: str) -> Any:
    start, end = answer.find("{"), answer.rfind("}")
    if start < 0 or end <= start:
        raise ValueError("нет JSON в ответе")
    return json.loads(answer[start : end + 1])


def _squash(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip().lower()


def _excerpt(document: CorpusDocument, phrases: list[str], limit: int = 1600) -> str:
    """Начало страницы и окрестность первого вхождения фразы подсферы."""
    text = document.text
    head = text[:900]
    lowered = text.lower()
    for phrase in phrases:
        position = lowered.find(phrase.lower().split()[0]) if phrase.split() else -1
        if position > 900:
            return (head + " … " + text[max(0, position - 300) : position + 400])[:limit]
    return text[:limit]


def run_panel(
    corpus: WebCorpus,
    complete: Any,
    query: str,
    *,
    start: date,
    end: date,
    limit: int = 30,
    docs_per_expert: int = 12,
) -> tuple[list[PanelCandidate], dict[str, Any]]:
    """Прогнать граф и вернуть кандидатов с доказательствами и след работы.

    ``complete(system, user, max_tokens) -> str`` — вызов модели сервиса; отказ модели на любом
    шаге не роняет граф: план вырождается в одну подсферу с исходным запросом, объединение — в
    слияние по имени.
    """
    started = time.monotonic()

    def plan(state: _State) -> dict[str, Any]:
        try:
            raw = _json(complete(_PLAN_SYSTEM, f"Direction: {state['query']}", 900))
            subspheres = [
                {"name": str(s.get("name") or "")[:120],
                 "phrases": [str(p)[:80] for p in (s.get("phrases") or []) if str(p).strip()][:5]}
                for s in raw.get("subspheres") or []
                if isinstance(s, dict)
            ][:6]
        except Exception as error:
            log.warning("panel.plan_failed", error=str(error))
            subspheres = []
        if not subspheres:
            subspheres = [{"name": state["query"], "phrases": [state["query"]]}]
        return {"subspheres": subspheres, "trace": [{"step": "plan", "subspheres": subspheres}]}

    def fan_out(state: _State) -> list[Send]:
        return [
            Send("expert", {"query": state["query"], "start": state["start"], "end": state["end"], "sub": sub})
            for sub in state["subspheres"]
        ]

    def expert(state: _ExpertState) -> dict[str, Any]:
        sub = state["sub"]
        phrases = [sub["name"], *sub["phrases"]]
        found: dict[str, CorpusDocument] = {}
        for phrase in phrases:
            for document, _ in corpus.search(phrase, start=state["start"], end=state["end"], limit=6):
                found.setdefault(document.url, document)
            if len(found) >= docs_per_expert:
                break
        documents = list(found.values())[:docs_per_expert]
        if not documents:
            return {"trace": [{"step": "expert", "sub": sub["name"], "documents": 0}]}
        listing = "\n\n".join(
            f"[{i}] {d.title} | {d.published} | {d.host} | {d.url}\n{_excerpt(d, sub['phrases'])}"
            for i, d in enumerate(documents, 1)
        )
        user = f"Direction: {state['query']}\nYour sub-area: {sub['name']}\n\nDocuments:\n{listing}"
        try:
            raw = _json(complete(_EXPERT_SYSTEM, user, 2500))
        except Exception as error:
            log.warning("panel.expert_failed", sub=sub["name"], error=str(error))
            return {"trace": [{"step": "expert", "sub": sub["name"], "documents": len(documents), "failed": str(error)[:200]}]}
        findings, rejected = [], 0
        for signal in raw.get("signals") or []:
            if not isinstance(signal, dict) or not str(signal.get("name_en") or "").strip():
                continue
            urls = []
            for item in signal.get("evidence") or []:
                try:
                    document = documents[int(item.get("doc")) - 1]
                except (TypeError, ValueError, IndexError, AttributeError):
                    continue
                quote = _squash(str(item.get("quote") or ""))
                # Цитата обязана стоять на странице дословно: иначе это пересказ модели.
                if len(quote) >= 20 and quote in _squash(document.title + " " + document.text):
                    urls.append(document.url)
            if not urls:
                rejected += 1
                continue
            findings.append({
                "name": str(signal["name_en"]).strip()[:120],
                "name_ru": str(signal.get("name_ru") or "").strip()[:160],
                "trend_ru": str(signal.get("trend_ru") or "").strip()[:400],
                "companies": [str(c)[:80] for c in signal.get("companies") or []][:6],
                "evidence": list(dict.fromkeys(urls)),
                "subsphere": sub["name"],
            })
        return {
            "findings": findings,
            "trace": [{"step": "expert", "sub": sub["name"], "documents": len(documents),
                       "signals": len(findings), "rejectedUnquoted": rejected}],
        }

    def merge(state: _State) -> dict[str, Any]:
        findings = state.get("findings") or []
        if not findings:
            return {"merged": [], "trace": [{"step": "merge", "candidates": 0}]}
        listing = "\n".join(
            f"{i}. {f['name']} — {f['trend_ru']} (sub-area: {f['subsphere']}; sources: {len(f['evidence'])})"
            for i, f in enumerate(findings, 1)
        )
        merged: list[dict[str, Any]] = []
        try:
            raw = _json(complete(_MERGE_SYSTEM.replace("{limit}", str(state["limit"])),
                                 f"Direction: {state['query']}\n\nCandidates:\n{listing}", 3000))
            for group in raw.get("signals") or []:
                members = [findings[int(m) - 1] for m in group.get("members") or []
                           if str(m).isdigit() and 0 < int(m) <= len(findings)]
                if not members:
                    continue
                merged.append({
                    "name": str(group.get("name_en") or members[0]["name"]).strip()[:120],
                    "name_ru": str(group.get("name_ru") or members[0]["name_ru"]).strip()[:160],
                    "trend_ru": str(group.get("trend_ru") or members[0]["trend_ru"]).strip()[:400],
                    "subsphere": members[0]["subsphere"],
                    "companies": list(dict.fromkeys(c for m in members for c in m["companies"]))[:8],
                    "evidence": list(dict.fromkeys(u for m in members for u in m["evidence"])),
                })
        except Exception as error:
            log.warning("panel.merge_failed", error=str(error))
        if not merged:
            by_key: dict[str, dict[str, Any]] = {}
            for f in findings:
                key = _squash(f["name"])
                if key in by_key:
                    by_key[key]["evidence"] = list(dict.fromkeys([*by_key[key]["evidence"], *f["evidence"]]))
                else:
                    by_key[key] = dict(f)
            merged = sorted(by_key.values(), key=lambda f: -len(f["evidence"]))
        merged = merged[: state["limit"]]
        return {"merged": merged, "trace": [{"step": "merge", "candidates": len(findings), "merged": len(merged)}]}

    graph = StateGraph(_State)
    graph.add_node("plan", plan)
    graph.add_node("expert", expert)
    graph.add_node("merge", merge)
    graph.add_edge(START, "plan")
    graph.add_conditional_edges("plan", fan_out, ["expert"])
    graph.add_edge("expert", "merge")
    graph.add_edge("merge", END)
    result = graph.compile().invoke(
        {"query": query, "start": start, "end": end, "limit": limit, "findings": [], "trace": []}
    )
    # BRULE-1 требует двух независимых источников: к страницам эксперта добавляются другие страницы
    # корпуса, где имя кандидата стоит фразой (поиск тот же — фразовый BM25).
    for m in result.get("merged") or []:
        extra = [d.url for d, _ in corpus.search(m["name"], start=start, end=end, limit=4)]
        m["evidence"] = list(dict.fromkeys([*m["evidence"], *extra]))[:8]
    candidates = [
        PanelCandidate(
            name=m["name"], name_ru=m["name_ru"], trend_ru=m["trend_ru"], subsphere=m["subsphere"],
            companies=tuple(m["companies"]), evidence=tuple(m["evidence"]),
        )
        for m in result.get("merged") or []
        if m.get("evidence")
    ]
    stats = {"seconds": round(time.monotonic() - started, 1), "trace": result.get("trace") or []}
    log.info("panel.finished", query=query, candidates=len(candidates), seconds=stats["seconds"])
    return candidates, stats
