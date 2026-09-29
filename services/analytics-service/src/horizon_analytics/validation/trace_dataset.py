"""Проследить технологии датасета через конвейер на снапшоте отчёта.

Запускается там, где у движка есть доступ к корпусу и моделям (в контейнере воркера), и отвечает на
вопрос сравнения с датасетом «почему этой технологии нет в отчёте» сразу для всего направления:
одним прогоном конвейера со списком наблюдаемых терминов, а не двадцатью вызовами `/explain`, каждый
из которых повторяет анализ.

Прогон идёт с теми же проверками, что и боевой, — семантическим судьёй и внешней проверкой
зрелости, — иначе причина «модель не признала строку технологией» в трассе не появилась бы вовсе.
Судья недетерминирован, поэтому трасса — объяснение повторного прогона, а не записи отчёта; в
сравнении это оговорено.

Вход — JSON на stdin::

    {"snapshotId": "...", "query": "...", "terms": ["automated ai red teaming", ...],
     "parameters": {"maxDocumentsAnalyzed": 5000}, "topN": 15, "yearsWindow": 7}

Выход — JSON на stdout: трасса каждого термина и присутствие его в тексте корпуса.
"""

from __future__ import annotations

import asyncio
import json
import re
import sys
from dataclasses import replace
from datetime import date
from typing import Any

from horizon_analytics.config import Settings
from horizon_analytics.container import build_container
from horizon_analytics.domain.models import AnalysisParams, Document
from horizon_analytics.domain.pipeline import AnalysisPipeline, PipelineRequest
from horizon_analytics.domain.scoring.profile import MethodologyProfile

_WORD = re.compile(r"[a-z0-9]+")
_STOP = frozenset(
    {"a", "an", "the", "of", "for", "and", "in", "on", "with", "to", "as", "by", "via"}
)


def _words(text: str) -> list[str]:
    return [word for word in _WORD.findall(text.lower()) if word not in _STOP]


def presence(term: str, documents: list[Document]) -> dict[str, int]:
    """Сколько документов корпуса содержат фразу целиком и сколько — все её слова.

    Различает «не собрали» и «собрали, но не выделили»: у первого случая нет ни одного документа со
    всеми словами технологии, у второго они есть, а тема из них не сложилась.
    """
    phrase = " ".join(_words(term))
    needed = set(_words(term))
    exact = 0
    all_words = 0
    for document in documents:
        text = " ".join(_words(f"{document.title} {document.abstract_text or ''}"))
        if phrase and phrase in text:
            exact += 1
        if needed and needed <= set(text.split()):
            all_words += 1
    return {"phraseDocuments": exact, "allWordsDocuments": all_words}


async def _trace(payload: dict[str, Any]) -> dict[str, Any]:
    settings = Settings()
    container = build_container(settings)
    documents = list(await container.documents.load_snapshot(payload["snapshotId"]))
    window_from, window_to = await container.documents.snapshot_window(payload["snapshotId"])
    profile = MethodologyProfile.default()
    profile = replace(
        profile, parameters=profile.parameters.with_overrides(payload.get("parameters") or {})
    )
    terms = [str(term).strip().lower() for term in payload["terms"] if str(term).strip()]
    request = PipelineRequest(
        normalized_query=payload["query"],
        query=payload["query"],
        documents=tuple(documents),
        params=AnalysisParams(
            top_n=int(payload.get("topN", 15)), years_window=int(payload.get("yearsWindow", 7))
        ),
        profile=profile,
        window_from=window_from,
        window_to=window_to,
        today=date.today(),
        watch=frozenset(terms),
    )
    pipeline = AnalysisPipeline(
        embedding_provider=container.embeddings,
        corpus_cache=container.corpus_cache,
        technology_judge=container.technology_judge,
        maturity_probe=container.maturity_probe,
    )
    result = await asyncio.to_thread(pipeline.run, request)
    by_key = {trace.key: trace for trace in result.traces}
    out: list[dict[str, Any]] = []
    for term in terms:
        trace = by_key.get(term)
        out.append(
            {
                "term": term,
                "stage": trace.stage if trace else None,
                "outcome": trace.outcome if trace else None,
                "reason": trace.reason if trace else None,
                "canonicalTerm": trace.canonical_key if trace else None,
                "inReport": bool(trace.survived) if trace else False,
                **presence(term, documents),
            }
        )
    # Счётчики отсева по шагам и выдача повторного прогона: без них видно, где потерялась одна
    # технология, но не видно, почему сжалась вся выдача.
    return {
        "documents": len(documents),
        "traces": out,
        "diagnostics": dict(result.diagnostics),
        "trends": [outcome.result.title for outcome in result.trends],
    }


def main() -> None:
    """Прочитать запрос со stdin, напечатать трассы в stdout."""
    payload = json.load(sys.stdin)
    json.dump(asyncio.run(_trace(payload)), sys.stdout, ensure_ascii=False)


if __name__ == "__main__":
    main()
