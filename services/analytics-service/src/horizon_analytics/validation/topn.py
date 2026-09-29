"""Сравнить ТОП-15 платформы с размеченным датасетом, принятым за эталон.

Датасет — сто слабых сигналов в шести направлениях, по 16–17 на направление. Платформа по каждому
направлению выдаёт свой ТОП-15. Вопрос: сколько технологий эталона она находит, какие из её тем —
технологии эталона и куда пропадают остальные.

Совпадение считается двумя способами, и оба попадают в отчёт раздельно:

* **лексическое** — одни и те же значимые слова в английской формулировке технологии и в названии
  темы. Строгое и проверяемое, но пропускает синонимы («AI red teaming» и «adversarial testing»);
* **по смыслу** — суждение модели (та же, что у судьи продукта) с причиной для каждой пары. Шире,
  но это суждение, а не факт, и поэтому оно подписано.

Промахи объясняются трассой конвейера (``trace_dataset``): на какой стадии технология потерялась и
была ли она в собранном корпусе вообще.

    python -m horizon_analytics.validation.topn --runs runs.tsv --markdown out.md
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from collections.abc import Sequence
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path
from typing import Any

import httpx

from horizon_analytics.validation.dataset import DatasetRow, load_positives
from horizon_analytics.validation.run import _load_saved_queries, _repository_root

_WORD = re.compile(r"[a-z0-9]+")
_STOP = frozenset(
    {"a", "an", "the", "of", "for", "and", "in", "on", "with", "to", "as", "by", "via", "based"}
)


def _stem(word: str) -> str:
    if len(word) > 4 and word.endswith("ies"):
        return word[:-3] + "y"
    if len(word) > 3 and word.endswith("s") and not word.endswith("ss"):
        return word[:-1]
    return word


def content_words(text: str) -> frozenset[str]:
    """Значимые слова в приведённой форме: без служебных, без множественного числа."""
    return frozenset(_stem(word) for word in _WORD.findall(text.lower()) if word not in _STOP)


def lexical_match(phrase: str, title: str) -> bool:
    """Строгое совпадение: название темы содержит все слова технологии либо две трети общих.

    Однословная тема технологию из нескольких слов не покрывает: «agents» содержится в половине
    формулировок датасета и ничего не говорит о том, найдена ли конкретная технология.
    """
    left, right = content_words(phrase), content_words(title)
    if not left or not right:
        return False
    # Все значимые слова технологии есть в названии темы — тема называет её целиком.
    if left <= right:
        return True
    # Иначе — совпадение не меньше двух третей. Половины мало: «satellite edge computing» и
    # «edge computing layer» делят два слова из четырёх, и второе — просто имя направления.
    shared = left & right
    return len(shared) >= 2 and len(shared) / len(left | right) >= 2 / 3


@dataclass
class ItemOutcome:
    """Что стало с одной технологией эталона."""

    number: int
    name: str
    phrase: str
    lexical: list[int] = field(default_factory=list)
    semantic: list[tuple[int, str, str]] = field(default_factory=list)
    trace: dict[str, Any] | None = None

    @property
    def found_lexically(self) -> bool:
        """Название темы совпало со словами формулировки."""
        return bool(self.lexical)

    @property
    def found_same(self) -> bool:
        """Модель признала тему той же технологией."""
        return any(relation == "same" for _, relation, _ in self.semantic)

    @property
    def found_related(self) -> bool:
        """Модель нашла тему той же или непосредственно связанной."""
        return bool(self.semantic)


@dataclass
class AreaOutcome:
    """Сравнение одного направления."""

    area: str
    query: str
    report_id: str
    documents: int
    topics: list[dict[str, Any]]
    items: list[ItemOutcome]
    sources: list[str]


_JUDGE_PROMPT = (
    "Ты сопоставляешь две выдачи о технологиях. Слева — эталонный список слабых технологических "
    "сигналов, справа — темы, найденные системой. Найди пары, где тема системы называет ту же "
    'технологию, что и эталон (relation "same"), либо её прямую составную часть, вариант или '
    'непосредственную предпосылку (relation "related"). Пары без связи не включай. Ответь только '
    'JSON-массивом объектов {"etalon": номер, "topic": номер, "relation": "same"|"related", '
    '"reason": "кратко по-русски"} без пояснений вокруг.'
)
_NOISE = re.compile(r"[^\w\s.,:;()«»\"'/+\-—–%]")


def semantic_matches(
    client: httpx.Client, model: str, items: Sequence[ItemOutcome], topics: Sequence[dict[str, Any]]
) -> list[tuple[int, int, str, str]]:
    """Пары «эталон — тема» по суждению модели: (номер эталона, ранг темы, связь, причина)."""
    left = "\n".join(f"{index}. {item.name} ({item.phrase})" for index, item in enumerate(items, 1))
    right = "\n".join(
        f"{topic['rank']}. {topic['title']}"
        + (f" — {topic['titleRu']}" if topic.get("titleRu") else "")
        for topic in topics
    )
    response = client.post(
        "/chat/completions",
        json={
            "model": model,
            "reasoning_effort": "low",
            "max_completion_tokens": 4096,
            "messages": [
                {"role": "system", "content": _JUDGE_PROMPT},
                {"role": "user", "content": f"Эталон:\n{left}\n\nТемы системы:\n{right}"},
            ],
        },
    )
    response.raise_for_status()
    text = str(response.json()["choices"][0]["message"]["content"] or "")
    start, end = text.find("["), text.rfind("]")
    if start < 0 or end <= start:
        return []
    try:
        raw = json.loads(text[start : end + 1])
    except ValueError:
        return []
    pairs: list[tuple[int, int, str, str]] = []
    for entry in raw if isinstance(raw, list) else []:
        try:
            relation = str(entry["relation"]).lower()
            if relation not in {"same", "related"}:
                continue
            # Прокси модели изредка вставляет в текст посторонние символы (иероглифы) — чистим.
            reason = _NOISE.sub("", str(entry.get("reason", ""))).strip()
            pairs.append((int(entry["etalon"]), int(entry["topic"]), relation, reason))
        except (KeyError, TypeError, ValueError):
            continue
    return pairs


def _trace(command: str, payload: dict[str, Any]) -> dict[str, Any]:
    # Команда задаётся оператором замера явно: трассировка идёт там, где у движка есть корпус.
    completed = subprocess.run(
        command, shell=True, input=json.dumps(payload), capture_output=True, text=True, check=True
    )
    lines = [line for line in completed.stdout.splitlines() if line.startswith('{"documents"')]
    if not lines:
        raise RuntimeError(f"трассировка не вернула результат: {completed.stderr[-400:]}")
    result: dict[str, Any] = json.loads(lines[-1])
    return result


def compare(args: argparse.Namespace) -> list[AreaOutcome]:
    """Собрать сравнение по всем направлениям из файла прогонов."""
    root = _repository_root()
    saved = _load_saved_queries(args.queries or root / "fixtures" / "validation" / "queries.json")
    rows: list[DatasetRow] = load_positives(
        args.dataset or root / "100_слабых_технологических_сигналов_сентябрь_2026.xlsx",
        translations=saved,
    )
    # Повторы соединения: прогон сравнения длится час, и один сброс соединения на четвёртом
    # направлении уже стоил перезапуска.
    api = httpx.Client(base_url=args.api, timeout=120, transport=httpx.HTTPTransport(retries=3))
    cache_dir: Path | None = args.cache_dir
    if cache_dir is not None:
        cache_dir.mkdir(parents=True, exist_ok=True)
    llm: httpx.Client | None = None
    if os.environ.get("HORIZON_NLP_OPENAI_API_KEY"):
        llm = httpx.Client(
            base_url=os.environ["HORIZON_NLP_OPENAI_BASE_URL"].rstrip("/"),
            timeout=300,
            transport=httpx.HTTPTransport(retries=3),
            headers={"Authorization": f"Bearer {os.environ['HORIZON_NLP_OPENAI_API_KEY']}"},
        )
    outcomes: list[AreaOutcome] = []
    for line in Path(args.runs).read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) < 4 or "COMPLETED" not in parts[3]:
            continue
        area, query, _request, status = parts[:4]
        report_id = status.split()[-1]
        # Публичный API открыт: входа в продукте нет, отчёт читается без заголовка Authorization.
        fetched = api.get(f"/api/v1/reports/{report_id}")
        fetched.raise_for_status()
        report = fetched.json()
        topics = [
            {
                "rank": trend["rank"],
                "title": trend["title"],
                "titleRu": (trend.get("localization") or {}).get("title"),
            }
            for trend in report["trends"]
        ]
        items = [
            ItemOutcome(number=row.number, name=row.name, phrase=row.query)
            for row in rows
            if row.area == area
        ]
        for item in items:
            item.lexical = [t["rank"] for t in topics if lexical_match(item.phrase, t["title"])]
        if llm is not None and topics:
            for number, rank, relation, reason in semantic_matches(llm, args.model, items, topics):
                if 1 <= number <= len(items):
                    items[number - 1].semantic.append((rank, relation, reason))
        if args.trace_command:
            cached = cache_dir / f"trace-{report_id}.json" if cache_dir is not None else None
            if cached is not None and cached.is_file():
                traced = json.loads(cached.read_text(encoding="utf-8"))
            else:
                traced = _trace(
                    args.trace_command,
                    {
                        "snapshotId": report["corpusSnapshotId"],
                        "query": query,
                        "terms": [item.phrase for item in items],
                        "parameters": {"maxDocumentsAnalyzed": args.max_documents},
                    },
                )
                if cached is not None:
                    cached.write_text(json.dumps(traced, ensure_ascii=False), encoding="utf-8")
            by_term = {entry["term"]: entry for entry in traced["traces"]}
            for item in items:
                item.trace = by_term.get(item.phrase.strip().lower())
        outcomes.append(
            AreaOutcome(
                area=area,
                query=query,
                report_id=report_id,
                documents=int(report["coverage"]["documentsAnalyzed"]),
                topics=topics,
                items=items,
                sources=list(report["coverage"]["sourcesUsed"]),
            )
        )
        print(f"{area}: тем {len(topics)}, эталон {len(items)}", file=sys.stderr)
    return outcomes


_STAGE_WORDS: dict[str | None, str] = {
    None: "не встретилась как кандидат",
    "extracted": "не выделена как кандидат",
    "min_df": "слишком редкая в корпусе",
    "term_filter": "отсеяна правилом имени",
    "unigram_termhood": "одиночное слово без термхуда",
    "merged": "слита с другой темой",
    "clustered": "поглощена кластером",
    "relevance": "признана чужим направлением",
    "credibility": "мало независимых подтверждений",
    "evidence": "нет свидетельств",
    "mainstream": "мейнстрим направления",
    "confidence": "низкая уверенность",
    "zero_score": "нулевой индикатор",
    "not_technology": "судья: не название технологии",
    "mainstream_external": "массовая по внешним источникам",
    "top_n": "не вошла в ТОП-15 по баллу",
    "ranked": "в выдаче повторного прогона",
}


def _fate(item: ItemOutcome) -> str:
    trace = item.trace
    if trace is None:
        return "—"
    presence = (
        f"в корпусе: фраза {trace['phraseDocuments']}, все слова {trace['allWordsDocuments']}"
    )
    if trace.get("inReport"):
        return f"дошла до выдачи при повторном прогоне; {presence}"
    stage = trace.get("outcome") if trace.get("outcome") in _STAGE_WORDS else trace.get("stage")
    words = _STAGE_WORDS.get(stage, str(stage))
    return f"{words}; {presence}"


def markdown(outcomes: Sequence[AreaOutcome], measured_on: date) -> str:
    """Отчёт сравнения для методологии."""
    total = sum(len(area.items) for area in outcomes)
    lexical = sum(item.found_lexically for area in outcomes for item in area.items)
    same = sum(item.found_same for area in outcomes for item in area.items)
    related = sum(item.found_related for area in outcomes for item in area.items)
    topics = sum(len(area.topics) for area in outcomes)
    matched_topics = sum(
        len(
            {rank for item in area.items for rank, _, _ in item.semantic}
            | {r for i in area.items for r in i.lexical}
        )
        for area in outcomes
    )
    lines = [
        "<!-- Сгенерировано `horizon_analytics.validation.topn`. Руками не править -->",
        f"# Выдача платформы против размеченного датасета ({measured_on.isoformat()})",
        "",
        "Датасет принят за эталон. Платформа запущена по каждому из шести направлений датасета на",
        "живых источниках; её ТОП-15 сопоставлен с технологиями эталона этого направления.",
        "",
        "| Мера | Значение |",
        "| --- | --- |",
        f"| Технологий эталона | {total} |",
        (
            f"| Найдено в ТОП-15: совпадение названий | **{lexical}** ({lexical / total:.0%}) |"
            if total
            else ""
        ),
        (
            f"| Найдено в ТОП-15: та же технология по смыслу | **{same}** ({same / total:.0%}) |"
            if total
            else ""
        ),
        (
            f"| Найдено в ТОП-15: та же или непосредственно связанная | {related} ({related / total:.0%}) |"
            if total
            else ""
        ),
        f"| Тем платформы | {topics} |",
        (
            f"| Из них совпали с эталоном хоть как-то | {matched_topics} ({matched_topics / topics:.0%}) |"
            if topics
            else ""
        ),
        "",
        "«По смыслу» — суждение модели с причиной для каждой пары; «совпадение названий» — строгое",
        "совпадение значимых слов. Судьба пропущенной технологии — трасса повторного прогона",
        "конвейера на том же снапшоте; судья недетерминирован, поэтому трасса объясняет повторный",
        "прогон, а не буквально записанный отчёт.",
        "",
    ]
    for area in outcomes:
        found = sum(item.found_same or item.found_lexically for item in area.items)
        lines += [
            f"## {area.area} — запрос «{area.query}»",
            "",
            f"Документов в анализе: {area.documents}; источники: {', '.join(area.sources)}. "
            f"Тем в выдаче: {len(area.topics)}. Найдено технологий эталона: {found} из {len(area.items)}.",
            "",
            "Выдача платформы: "
            + "; ".join(f"{t['rank']}. {t['title']}" for t in area.topics)
            + ".",
            "",
            "| № | Технология эталона | Формулировка | В ТОП-15 | Судьба |",
            "| --- | --- | --- | --- | --- |",
        ]
        for item in area.items:
            hits: list[str] = []
            if item.lexical:
                hits.append("название: " + ", ".join(f"#{rank}" for rank in item.lexical))
            for rank, relation, reason in item.semantic:
                word = "та же" if relation == "same" else "связанная"
                hits.append(f"{word}: #{rank} ({reason.strip()[:90]})")
            fate = "" if (item.found_same or item.found_lexically) else _fate(item)
            name = item.name.replace("|", "/")
            lines.append(
                f"| {item.number} | {name} | {item.phrase} | {'; '.join(hits) or 'нет'} | {fate} |"
            )
        lines.append("")
    stages: dict[str, int] = {}
    for area in outcomes:
        for item in area.items:
            if item.found_same or item.found_lexically or item.trace is None:
                continue
            trace = item.trace
            if trace.get("inReport"):
                key = "дошла до выдачи при повторном прогоне"
            elif trace["allWordsDocuments"] == 0:
                key = "в собранном корпусе нет ни одного документа со всеми её словами"
            else:
                stage = (
                    trace.get("outcome")
                    if trace.get("outcome") in _STAGE_WORDS
                    else trace.get("stage")
                )
                key = _STAGE_WORDS.get(stage, str(stage))
            stages[key] = stages.get(key, 0) + 1
    if stages:
        lines += [
            "## Куда пропадают технологии эталона",
            "",
            "| Где потеряна | Технологий |",
            "| --- | --- |",
        ]
        for key, count in sorted(stages.items(), key=lambda kv: -kv[1]):
            lines.append(f"| {key} | {count} |")
        lines.append("")
    return "\n".join(line for line in lines if line is not None)


def main(argv: Sequence[str] | None = None) -> None:
    """Точка входа командной строки."""
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--runs", required=True, help="TSV: направление, запрос, запрос-id, статус с id отчёта"
    )
    parser.add_argument("--api", default="http://2.27.20.14:18080")
    parser.add_argument("--dataset", type=Path)
    parser.add_argument("--queries", type=Path)
    parser.add_argument("--model", default="gpt-5.6-luna")
    parser.add_argument(
        "--trace-command", default="", help="команда, читающая JSON трассировки со stdin"
    )
    parser.add_argument("--max-documents", type=int, default=5000)
    parser.add_argument(
        "--cache-dir",
        type=Path,
        help="куда складывать трассы по id отчёта: повторный запуск их не пересчитывает",
    )
    parser.add_argument("--markdown", type=Path)
    parser.add_argument("--json", type=Path)
    args = parser.parse_args(argv)
    outcomes = compare(args)
    text = markdown(outcomes, date.today())
    if args.markdown:
        args.markdown.write_text(text + "\n", encoding="utf-8")
    else:
        print(text)
    if args.json:
        args.json.write_text(
            json.dumps(
                [
                    {
                        "area": a.area,
                        "query": a.query,
                        "reportId": a.report_id,
                        "topics": a.topics,
                        "items": [
                            {
                                "number": i.number,
                                "name": i.name,
                                "phrase": i.phrase,
                                "lexical": i.lexical,
                                "semantic": i.semantic,
                                "trace": i.trace,
                            }
                            for i in a.items
                        ],
                    }
                    for a in outcomes
                ],
                ensure_ascii=False,
                indent=1,
            ),
            encoding="utf-8",
        )


if __name__ == "__main__":
    main()
