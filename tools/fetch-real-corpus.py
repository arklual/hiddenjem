#!/usr/bin/env python3
"""Выгрузка настоящих работ из OpenAlex — вход для замеров на неподобранных данных.

Не эталонный корпус и не его замена. У эталонного есть манифест, хэш и обещание побитового
повторения (`fixtures/corpus/`); он отвечает на вопрос «воспроизводимо ли». Эта выгрузка отвечает
на другой — «работает ли на данных, которых никто не подбирал», — и повторяется лишь приблизительно:
OpenAlex живая база. Подменять одно другим нельзя ни в какую сторону.

На этой выгрузке сделаны замеры разборов 61…78. Параметры и две оговорки, каждая из которых уже
стоила отозванного вывода, разобраны в `docs/01-analysis/79-how-to-reproduce-the-real-corpus.md`:

* сортировка не задаётся — `sort=cited_by_count:desc` смещает корпус к устоявшимся работам, то есть
  против того, ради чего продукт существует;
* концепты работы берутся целиком — обрезка до пяти сильнейших исказила замер настолько, что вывод
  пришлось отзывать (разбор 63).

Адрес для «вежливого пула» берётся из `HORIZON_CONNECTOR_CONTACT_EMAIL`; без него источник рано или
поздно отвечает 429 (ранбук «Источник отвечает 429»).

Запуск: `python3 tools/fetch-real-corpus.py <каталог>` — пишет `documents.jsonl`.
"""
"""Собрать небольшой настоящий корпус из OpenAlex в формате эталонного.

Вежливость: контактный адрес в User-Agent и в mailto (так просит OpenAlex), не более
десяти страниц, пауза между запросами. Объём намеренно мал: цель — ответить «вот прогон»,
а не собрать производственный корпус.
"""
import hashlib
import json
import os
import sys
import time
import urllib.parse
import urllib.request
import uuid
from datetime import UTC, datetime
from pathlib import Path

MAILTO = os.environ.get("HORIZON_CONNECTOR_CONTACT_EMAIL", "ops@horizon.example")
AGENT = f"HorizonBot/1.0 (+https://horizon.dev; mailto:{MAILTO})"
OUT = Path(sys.argv[1] if len(sys.argv) > 1 else "real-corpus") / "documents.jsonl"

def fetch(url):
    """Один запрос к API с вежливым User-Agent."""
    req = urllib.request.Request(url, headers={"User-Agent": AGENT, "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)

def abstract_of(work):
    """Аннотация, восстановленная из инвертированного индекса OpenAlex."""
    idx = work.get("abstract_inverted_index")
    if not idx:
        return ""
    positions = {}
    for word, places in idx.items():
        for p in places:
            positions[p] = word
    return " ".join(positions[k] for k in sorted(positions))[:2000]

def org_type(inst):
    """Тип организации в терминах нашей модели: от него зависит индикатор разнообразия."""
    kind = (inst.get("type") or "").upper()
    return {"EDUCATION": "UNIVERSITY", "COMPANY": "COMPANY", "GOVERNMENT": "GOVERNMENT",
            "FACILITY": "LAB", "NONPROFIT": "NONPROFIT"}.get(kind, "OTHER")

def convert(work):
    """Работа OpenAlex → документ в форме события `document-ingested`."""
    doi = (work.get("doi") or "").replace("https://doi.org/", "") or None
    authors = []
    for a in (work.get("authorships") or [])[:8]:
        insts = a.get("institutions") or [{}]
        inst = insts[0]
        authors.append({
            "fullName": (a.get("author") or {}).get("display_name") or "Unknown",
            "orcid": ((a.get("author") or {}).get("orcid") or "").replace("https://orcid.org/", "") or None,
            "organizationName": inst.get("display_name"),
            "organizationType": org_type(inst),
            "organizationCountry": inst.get("country_code"),
        })
    loc = (work.get("primary_location") or {}).get("source") or {}
    external = (work.get("id") or "").rsplit("/", 1)[-1]
    body = f"{work.get('title') or ''}{doi or external}"
    return {
        "documentId": str(uuid.uuid5(uuid.NAMESPACE_URL, work.get("id") or external)),
        "sourceId": "openalex",
        "sourceClass": "JOURNAL_ARTICLE" if (work.get("type") == "article") else "PREPRINT",
        "externalId": external,
        "title": (work.get("title") or "").strip(),
        "abstractText": abstract_of(work),
        "language": work.get("language") or "en",
        "publishedOn": work.get("publication_date"),
        "doi": doi,
        "arxivId": None,
        "patentNumber": None,
        "url": work.get("id"),
        "venue": {"name": loc.get("display_name") or "unknown", "type": "JOURNAL", "issn": None},
        "authors": authors,
        "topics": [{"code": c.get("id", "").rsplit("/", 1)[-1], "label": c.get("display_name"),
                    "score": round(float(c.get("score") or 0), 4)} for c in (work.get("concepts") or [])],
        "citationCount": int(work.get("cited_by_count") or 0),
        "extraMetrics": {"referenceCount": int(work.get("referenced_works_count") or 0),
                         "isOpenAccess": bool((work.get("open_access") or {}).get("is_oa"))},
        "dedupKey": hashlib.sha256(body.encode()).hexdigest(),
        # Момент выгрузки в UTC: у документа он часть данных, а не украшение.
        "fetchedAt": datetime.now(tz=UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }

QUERIES = [
    ("computer security", "C38652104"),
    ("renewable energy", "C188573790"),
    ("biotechnology", "C150903083"),
]
seen, out = set(), []
for label, concept in QUERIES:
    cursor = "*"
    for _page in range(6):
        params = urllib.parse.urlencode({
            "filter": f"concepts.id:{concept},from_publication_date:2019-01-01,has_abstract:true",
            "per-page": "100", "cursor": cursor, "mailto": MAILTO,
        })
        data = fetch(f"https://api.openalex.org/works?{params}")
        for work in data.get("results", []):
            doc = convert(work)
            if doc["documentId"] in seen or not doc["title"] or not doc["abstractText"]:
                continue
            seen.add(doc["documentId"])
            out.append(doc)
        cursor = (data.get("meta") or {}).get("next_cursor")
        if not cursor:
            break
        time.sleep(1.0)
    print(f"{label}: всего {len(out)}")

OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text("\n".join(json.dumps(d, ensure_ascii=False) for d in out) + "\n", encoding="utf-8")
print("записано:", OUT, len(out), "документов")
