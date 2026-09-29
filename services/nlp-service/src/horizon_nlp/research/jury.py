"""Экспертная стадия перед финалом: три эксперта голосуют «да/нет» по критериям жюри (разбор 110).

Жюри оценивает ТОП-15 так: эксперт отвечает «да» или «нет» на каждую позицию, порог — 75% «да».
Слепая оценка по тем же критериям показала, на чём позиции проваливаются: не по теме, мейнстрим,
общее понятие или событие компании, описание не согласуется с названием. Здесь три эксперта с разными
ролями смотрят на те же поля, что увидит жюри, — название, определение, источники с датами — и
отвечают параллельно (граф LangGraph, ``Send`` на каждого). Позиция проходит, если «да» сказали двое.

Отказ модели — «оставить»: молчание экспертов не должно удалять тему.
"""

from __future__ import annotations

import json
import operator
import time
from typing import Annotated, Any, TypedDict

import structlog
from langgraph.graph import END, START, StateGraph
from langgraph.types import Send

__all__ = ["run_jury"]

log = structlog.get_logger(__name__)

_COMMON = """You are one of three experts of Gazprombank.Tech judging the TOP list of a weak technology
signals radar for the query below. For EVERY numbered item answer yes or no strictly on your own
criterion (other experts check the rest). If unsure — no.
Answer JSON only: {"verdicts": [{"n": <number>, "ok": true|false, "reason": "<=10 words"}]}"""

EXPERTS: dict[str, str] = {
    "domain": _COMMON + """
Your criterion — RELEVANCE: an expert in the query's field would accept the item as belonging to
this field (not a neighbouring field, not a generic IT/ML topic that merely touches it).""",
    # Разбор 110: на 821 позиции с разметкой «как жюри» этот эксперт пропускал мейнстрим 2026 года
    # (47 из 69 ложных «да»). Правила зрелости и примеры подняли точность единогласных «да» с 0,52
    # до 0,67 на направлениях, примеры которых он не видел; поимённый список — знания о шести
    # направлениях эталона и соседних, заготовленные заранее.
    "market": _COMMON + """
Your criterion — WEAK SIGNAL in 2026 (the radar runs in late 2026). Say yes only for a specific
technology, mechanism, product class or business application that is still EARLY: prototypes,
pilots, first products from a few startups, early funding rounds.
Say NO if any of these holds:
- MAINSTREAM BY 2026: it ships as a standard feature of major open-source frameworks or cloud
  products; many vendors already sell it as a category; big tech runs it in production at scale;
  it has been an active research topic since before 2022 (thousands of papers); it is a textbook
  term. Already mainstream, under any name:
  AI security — prompt-injection detection or defenses, jailbreak detection, LLM firewalls and
  guardrails, (automated) AI red teaming, data/model poisoning and backdoor attacks in general,
  membership inference, model extraction, watermarking in general, differential privacy,
  federated learning, machine unlearning, confidential computing / TEE;
  robotics — VLA models, diffusion policies, action chunking or action tokens, imitation
  learning, sim-to-real, cross-embodiment foundation models, embodied AI in general, quadrupeds,
  dexterous hands in general, SLAM, event cameras, cobots, AMRs;
  AI infrastructure — KV-cache compression/reuse/management, prefix caching, prefill-decode
  disaggregation, speculative decoding, chunked prefill, quantization and pruning, tensor/context
  parallelism, vLLM-style LLM serving, HBM, NPUs or inference ASICs in general;
  industrial AI — digital twins, predictive maintenance, visual inspection and anomaly
  detection, industrial IoT, PINNs and neural operators, MES, CAD, graph neural networks;
  edge — edge inference in general, split inference/learning, federated edge learning, task
  offloading, 5G/MEC/O-RAN, model quantization, LoRA, llama.cpp, rugged edge AI modules;
  fintech — stablecoin payments, asset tokenization and RWA in general, DeFi lending and AMMs,
  ML fraud detection and AML monitoring, behavioural biometrics, instant/QR payments and UPI,
  embedded finance, BNPL, open banking APIs, CBDC in general, decentralized identity.
- A narrow variant, new name or sub-component of such an established technique — unless the
  item itself names a genuinely new mechanism that appeared in 2024-2026.
- A generic concept, problem area or phrase fragment ('control plane', 'agent security',
  'settlement protocol'), a single company, product or model name, a company event (acquisition,
  consolidation, funding news).
Judge the NAME with its definition; if the definition does not show what is new — no.
Calibration examples judged by the jury's standard:
- KV-cache compression → no: standard in vLLM/TensorRT-LLM.
- LLM firewall → no: established category with many vendors.
- Cross-embodiment robot foundation models → no: mainstream robot learning since 2023.
- AI voice debt collection → no: banks have used voice bots for years.
- action tokens → no: standard component of VLA policies since RT-1/RT-2.
- Autonomous Agent Swarm Security → no: a problem area, not a technology.
- Off-grid modular AI power → no: multi-billion deals, not an early stage.
- Agent Memory Poisoning → yes: new attack class on AI-agent memory, 2026 sources.
- Licensable General-Purpose NPUs → yes: IP-licensed GPNPUs, Quadric round and Ceva deal in 2026.
- Waterless two-phase cooling → yes: early deployments, ZutaCore round in 2026.
- Autonomous Refinery Operations → yes: refineries moving from APC to autonomous operation, early.
- Interbank tokenized deposit networks → yes: Agorá prototype and HKMA pilot.
- Physical self-play post-training in simulation → yes: first results in 2026, early stage.""",
    "evidence": _COMMON + """
Your criterion — EVIDENCE: the definition and the listed sources (titles, dates) are about THIS
item and support it; at least one source is dated 2024-2026. Say no if the definition is a
template, about something else, or the sources are unrelated to the name.""",
}


class _State(TypedDict, total=False):
    query: str
    listing: str
    size: int
    votes: Annotated[list[dict[str, Any]], operator.add]


def run_jury(complete: Any, query: str, items: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """Голоса экспертов по каждой позиции: ``[{"n", "yes", "votes": {роль: (ok, причина)}}]``.

    ``items`` — ``{"title", "definition", "sources": [{"title", "date"}]}``; ``complete(system, user,
    max_tokens)`` — вызов модели.
    """
    started = time.monotonic()
    listing = "\n\n".join(
        f"{i}. {item.get('title', '')}\n   Definition: {str(item.get('definition') or '')[:400]}\n"
        + "\n".join(f"   Source: {s.get('title', '')[:120]} ({s.get('date', '')})" for s in (item.get("sources") or [])[:3])
        for i, item in enumerate(items, 1)
    )

    def fan_out(state: _State) -> list[Send]:
        return [Send("expert", {"role": role, "query": state["query"], "listing": state["listing"],
                                "size": state["size"]}) for role in EXPERTS]

    def expert(state: dict[str, Any]) -> dict[str, Any]:
        role = state["role"]
        try:
            answer = complete(EXPERTS[role], f"Query: {state['query']}\n\nItems:\n{state['listing']}",
                              60 * state["size"] + 400)
            data = json.loads(answer[answer.find("{") : answer.rfind("}") + 1])
            verdicts = {int(v["n"]): (bool(v.get("ok")), str(v.get("reason") or "")[:120])
                        for v in data.get("verdicts") or [] if str(v.get("n", "")).isdigit()}
        except Exception as error:
            log.warning("jury.expert_failed", role=role, error=str(error))
            verdicts = {}
        return {"votes": [{"role": role, "verdicts": verdicts}]}

    graph = StateGraph(_State)
    graph.add_node("expert", expert)
    graph.add_conditional_edges(START, fan_out, ["expert"])
    graph.add_edge("expert", END)
    result = graph.compile().invoke({"query": query, "listing": listing, "size": len(items), "votes": []})
    answered = [v for v in result.get("votes") or [] if v["verdicts"]]
    out = []
    for n in range(1, len(items) + 1):
        votes = {v["role"]: v["verdicts"][n] for v in answered if n in v["verdicts"]}
        # Эксперт, не высказавшийся о позиции, считается «да»: молчание не удаляет тему.
        yes = sum(1 for ok, _ in votes.values() if ok) + (len(EXPERTS) - len(votes))
        out.append({"n": n, "yes": yes, "votes": {role: list(v) for role, v in votes.items()}})
    stats = {"seconds": round(time.monotonic() - started, 1), "experts": len(answered), "items": len(items)}
    log.info("jury.finished", query=query, **stats, passed=sum(1 for o in out if o["yes"] >= 2))
    return out, stats
