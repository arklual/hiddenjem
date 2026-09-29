"""Воронка отсева: сколько тем теряет конвейер на каждом шаге для живого корпуса направления.

Отвечает на вопрос «почему в отчёте девять тем, а не пятнадцать». Отчёт стенда показывает только
итог; счётчики отсева лежат в диагностике, но на снапшоте, который локально не воспроизвести.
Здесь корпус собирается заново из тех же открытых источников, что у сервиса сбора, и конвейер
прогоняется на нём с печатью каждой ступени.

Два режима:

* ``collect`` — собрать научный корпус направления в JSONL формата ``DocumentIngested`` (тот же,
  что у эталонного корпуса): arXiv, Semantic Scholar, Europe PMC, Crossref, Hacker News — теми же
  запросами, что у сервиса сбора. **OpenAlex не спрашивается**: его суточный бюджет общий со
  стендом. Каждый источник печатает, сколько принёс и **отказал ли** — ноль от отказа неотличим по
  числу документов, и путать их нельзя. Продуктовые источники здесь не повторяются: их собирают
  настоящие коннекторы сервиса сбора живой проверкой ``ProductSourcesLiveCheck``, которая пишет
  такой же JSONL.
* ``run`` — прогнать конвейер методологии на одном или нескольких корпусах и напечатать воронку:
  темы после кластеризации, принадлежности, BRULE-1, мейнстрима, стадии, нулевого балла и сколько
  их дошло до проверок верхушки (``ranked_before_screen``).

Судьи и внешней проверки зрелости здесь нет: первого нет на этой машине, вторая ходит в OpenAlex.
Поэтому воронка отвечает на вопрос «сколько кандидатов доходит **до** судьи», а судья на стенде
признаёт технологиями 7–15% прочитанного (разбор 89). Отсюда и мера: чтобы отчёт уверенно набирал
пятнадцать, до судьи должно доходить больше двухсот тем — и не больше потолка чтения, ТОП-N × 25.

    python -m horizon_analytics.validation.funnel collect --direction edge --out /tmp/funnel
    python -m horizon_analytics.validation.funnel run --direction edge \
        --corpus /tmp/funnel/edge.jsonl --corpus /tmp/live/edge-habr.jsonl
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import time
import uuid
import xml.etree.ElementTree as ET
from collections import Counter
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from datetime import UTC, date, datetime
from pathlib import Path
from typing import Any

import httpx

AGENT = "HorizonBot/1.0 (+https://github.com/horizon; research prototype)"

#: Шесть направлений датасета: запрос так, как его набирал аналитик при сравнении (разбор 90).
#:
#: Цели сбора здесь не записаны — они берутся из перекрёстного словаря тем же путём, что у стенда
#: (`/internal/directions/resolve`, :func:`targets`). Раньше они были переписаны сюда руками, и
#: правка словаря не меняла корпус воронки: проверить новую статью словаря на живом сборе было
#: нечем, кроме стенда (разбор 103).
DIRECTIONS: dict[str, str] = {
    "edge": "периферийные вычисления",
    "ai-security": "защита искусственного интеллекта",
    "industrial-ai": "индустриальный искусственный интеллект",
    "ai-infrastructure": "инфраструктура искусственного интеллекта",
    "robotics": "робототехника",
    "fintech": "финтех",
}


def targets(direction: str) -> tuple[str, ...]:
    """Цели сбора направления — ровно то, что стенд отдаёт сервису сбора перед обращением к источникам."""
    from horizon_analytics.domain.direction_lexicon import expand_raw, load_direction_lexicon
    from horizon_analytics.domain.extraction.normalization import stem_token
    from horizon_analytics.domain.extraction.tokenizer import normalize_text, tokenize

    words = [stem_token(token.normal) for token in tokenize(normalize_text(DIRECTIONS[direction]))]
    return tuple(sorted(expand_raw(words, load_direction_lexicon())))


_CODE = re.compile(r"^[a-z-]{2,12}\.[a-zA-Z-]{2,12}$")


@dataclass
class SourceReport:
    """Что принёс источник и отказал ли он — ноль и отказ обязаны различаться."""

    source: str
    documents: int = 0
    requests: int = 0
    failures: int = 0
    seconds: float = 0.0
    oldest: str | None = None
    newest: str | None = None
    error: str | None = None

    def note_dates(self, documents: Iterable[dict[str, Any]]) -> None:
        """Расширить диапазон дат источника датами пришедших документов."""
        dates = sorted(document["publishedOn"] for document in documents)
        if dates:
            self.oldest = min(filter(None, [self.oldest, dates[0]]))
            self.newest = max(filter(None, [self.newest, dates[-1]]))


def _phrases(targets: Iterable[str]) -> list[str]:
    return [target for target in targets if not _CODE.match(target) and len(target) >= 3]


def _document(
    source: str,
    source_class: str,
    external_id: str,
    title: str,
    abstract: str | None,
    published: date,
    url: str,
    *,
    authors: Iterable[tuple[str, str | None, str | None]] = (),
    topics: Iterable[str] = (),
    doi: str | None = None,
    arxiv_id: str | None = None,
    language: str = "en",
) -> dict[str, Any]:
    identity = uuid.uuid5(uuid.NAMESPACE_URL, f"{source}:{external_id}")
    return {
        "documentId": str(identity),
        "sourceId": source,
        "sourceClass": source_class,
        "externalId": external_id,
        "title": title.strip(),
        "abstractText": abstract,
        "language": language,
        "publishedOn": published.isoformat(),
        "doi": doi,
        "arxivId": arxiv_id,
        "url": url,
        "authors": [
            {"fullName": name, "organizationName": org, "organizationType": kind}
            for name, org, kind in authors
        ],
        "topics": [{"code": topic, "label": topic} for topic in topics],
        "fetchedAt": datetime.now(UTC).isoformat(),
        "dedupKey": _dedup(title, doi, arxiv_id),
    }


def _dedup(title: str, doi: str | None, arxiv_id: str | None) -> str:
    if doi:
        return "doi:" + doi.lower()
    if arxiv_id:
        return "arxiv:" + arxiv_id.split("v")[0]
    return "title:" + hashlib.sha1(re.sub(r"\W+", " ", title.lower()).strip().encode()).hexdigest()


class Collector:
    """Вежливый сборщик: одна сессия, пауза на хост, отказ записывается, а не глотается."""

    def __init__(self, window_from: date, window_to: date) -> None:
        """Одна сессия на весь сбор; окно — то же, что у анализа."""
        self.window_from = window_from
        self.window_to = window_to
        self.client = httpx.Client(
            headers={"User-Agent": AGENT}, timeout=httpx.Timeout(40.0), follow_redirects=False
        )
        self._last: dict[str, float] = {}

    def get(
        self, report: SourceReport, url: str, params: dict[str, Any], pause: float
    ) -> httpx.Response | None:
        """GET с паузой на хост и тремя попытками; отказ записывается в отчёт и даёт ``None``."""
        host = httpx.URL(url).host
        wait = self._last.get(host, 0.0) + pause - time.monotonic()
        if wait > 0:
            time.sleep(wait)
        report.requests += 1
        for attempt in range(3):
            try:
                response = self.client.get(url, params=params)
            except httpx.HTTPError as error:
                report.error = f"{type(error).__name__}: {error}"
                time.sleep(3 * (attempt + 1))
                continue
            finally:
                self._last[host] = time.monotonic()
            if response.status_code == 200:
                return response
            report.error = f"HTTP {response.status_code}"
            if response.status_code not in (429, 500, 502, 503, 504):
                break
            time.sleep(5 * (attempt + 1))
        report.failures += 1
        return None

    def within(self, published: date) -> bool:
        """Попадает ли дата в окно сбора."""
        return self.window_from <= published <= self.window_to

    # ─────────────── научные источники: те же запросы, что у сервиса сбора ───────────────

    def arxiv(
        self, targets: tuple[str, ...], pages: int
    ) -> tuple[list[dict[str, Any]], SourceReport]:
        """arXiv: цели через ИЛИ, свежие первыми — как у коннектора сервиса сбора."""
        report = SourceReport("arxiv")
        clause = " OR ".join(f'all:"{target}"' for target in targets)
        query = (
            f"({clause}) AND submittedDate:[{self.window_from:%Y%m%d}0000 TO "
            f"{self.window_to:%Y%m%d}2359]"
        )
        ns = {"a": "http://www.w3.org/2005/Atom", "x": "http://arxiv.org/schemas/atom"}
        out: list[dict[str, Any]] = []
        for page in range(pages):
            response = self.get(
                report,
                "https://export.arxiv.org/api/query",
                {
                    "search_query": query,
                    "start": page * 100,
                    "max_results": 100,
                    "sortBy": "submittedDate",
                    "sortOrder": "descending",
                },
                3.0,
            )
            if response is None:
                break
            entries = ET.fromstring(response.content).findall("a:entry", ns)
            for entry in entries:
                identifier = (entry.findtext("a:id", "", ns) or "").rsplit("/abs/", 1)[-1]
                published = date.fromisoformat((entry.findtext("a:published", "", ns))[:10])
                authors = []
                for author in entry.findall("a:author", ns):
                    affiliation = author.findtext("x:affiliation", None, ns)
                    authors.append((author.findtext("a:name", "", ns), affiliation, None))
                primary = entry.find("x:primary_category", ns)
                topics = [primary.get("term")] if primary is not None else []
                topics += [
                    category.get("term")
                    for category in entry.findall("a:category", ns)
                    if category.get("term") not in topics
                ]
                out.append(
                    _document(
                        "arxiv",
                        "PREPRINT",
                        identifier,
                        " ".join((entry.findtext("a:title", "", ns)).split()),
                        " ".join((entry.findtext("a:summary", "", ns)).split()),
                        published,
                        f"https://arxiv.org/abs/{identifier}",
                        authors=authors,
                        topics=topics,
                        arxiv_id=identifier,
                    )
                )
            if len(entries) < 100:
                break
        report.documents = len(out)
        report.note_dates(out)
        return out, report

    def semantic_scholar(
        self, targets: tuple[str, ...]
    ) -> tuple[list[dict[str, Any]], SourceReport]:
        """Semantic Scholar, массовый поиск: цели через ``|``, до тысячи работ за запрос."""
        report = SourceReport("semanticscholar")
        phrases = _phrases(targets)
        query = phrases[0] if len(phrases) == 1 else " | ".join(f'"{p}"' for p in phrases)
        response = self.get(
            report,
            "https://api.semanticscholar.org/graph/v1/paper/search/bulk",
            {
                "query": query,
                "publicationDateOrYear": f"{self.window_from}:{self.window_to}",
                "fields": "title,abstract,authors,venue,publicationDate,year,externalIds,url",
            },
            2.0,
        )
        out: list[dict[str, Any]] = []
        if response is not None:
            for paper in response.json().get("data") or []:
                if not paper.get("title"):
                    continue
                raw_date = paper.get("publicationDate")
                published = (
                    date.fromisoformat(raw_date)
                    if raw_date
                    else date(int(paper.get("year") or self.window_from.year), 1, 1)
                )
                ids = paper.get("externalIds") or {}
                arxiv_id = ids.get("ArXiv")
                venue = paper.get("venue") or ""
                preprint = bool(arxiv_id) and (not venue or venue.lower() == "arxiv.org")
                out.append(
                    _document(
                        "semanticscholar",
                        "PREPRINT" if preprint else "JOURNAL_ARTICLE",
                        paper["paperId"],
                        paper["title"],
                        paper.get("abstract"),
                        published,
                        paper.get("url") or "",
                        authors=[
                            (a.get("name") or "", None, None) for a in paper.get("authors") or []
                        ],
                        doi=ids.get("DOI"),
                        arxiv_id=arxiv_id,
                    )
                )
        report.documents = len(out)
        report.note_dates(out)
        return out, report

    def europe_pmc(
        self, targets: tuple[str, ...], pages: int
    ) -> tuple[list[dict[str, Any]], SourceReport]:
        """Europe PMC с аффилиациями: из строки берётся сегмент, называющий учреждение."""
        report = SourceReport("europepmc")
        phrases = _phrases(targets)
        terms = " OR ".join(f'"{p}"' for p in phrases)
        query = f"({terms}) AND FIRST_PDATE:[{self.window_from} TO {self.window_to}]"
        cursor = "*"
        out: list[dict[str, Any]] = []
        institution = re.compile(
            r"universit|college|institut|academy|laborator|centre|center|inc\b|ltd|corporation|gmbh|company",
            re.IGNORECASE,
        )
        for _ in range(pages):
            response = self.get(
                report,
                "https://www.ebi.ac.uk/europepmc/webservices/rest/search",
                {
                    "query": query,
                    "format": "json",
                    "resultType": "core",
                    "pageSize": 200,
                    "cursorMark": cursor,
                },
                1.0,
            )
            if response is None:
                break
            body = response.json()
            for result in body.get("resultList", {}).get("result", []):
                if not result.get("title") or not result.get("firstPublicationDate"):
                    continue
                authors = []
                for author in (result.get("authorList") or {}).get("author", []):
                    org = None
                    details = (author.get("authorAffiliationDetailsList") or {}).get(
                        "authorAffiliation"
                    ) or []
                    if details and details[0].get("affiliation"):
                        for segment in re.split(r"[,;]", details[0]["affiliation"]):
                            if 4 <= len(segment.strip()) <= 200 and institution.search(segment):
                                org = segment.strip().rstrip(".")
                                break
                    authors.append((author.get("fullName") or "", org, None))
                preprint = result.get("source") == "PPR"
                out.append(
                    _document(
                        "europepmc",
                        "PREPRINT" if preprint else "JOURNAL_ARTICLE",
                        f"{result.get('source')}:{result.get('id')}",
                        result["title"],
                        re.sub(r"<[^>]+>", " ", result.get("abstractText") or "") or None,
                        date.fromisoformat(result["firstPublicationDate"]),
                        f"https://europepmc.org/article/{result.get('source')}/{result.get('id')}",
                        authors=authors,
                        doi=result.get("doi"),
                    )
                )
            next_cursor = body.get("nextCursorMark")
            if not next_cursor or next_cursor == cursor:
                break
            cursor = next_cursor
        report.documents = len(out)
        report.note_dates(out)
        return out, report

    def crossref(
        self, targets: tuple[str, ...], pages: int
    ) -> tuple[list[dict[str, Any]], SourceReport]:
        """Crossref: мешок слов целей, только работы с аннотацией, рубрики — как метки."""
        report = SourceReport("crossref")
        cursor = "*"
        out: list[dict[str, Any]] = []
        for _ in range(pages):
            response = self.get(
                report,
                "https://api.crossref.org/works",
                {
                    "query.bibliographic": " ".join(targets),
                    "filter": f"from-pub-date:{self.window_from},until-pub-date:{self.window_to},has-abstract:true",
                    "rows": 100,
                    "cursor": cursor,
                },
                1.5,
            )
            if response is None:
                break
            message = response.json().get("message", {})
            for item in message.get("items", []):
                titles = item.get("title") or []
                parts = (item.get("issued") or {}).get("date-parts") or [[None]]
                if not titles or not parts[0] or parts[0][0] is None:
                    continue
                year, month, day = (parts[0] + [1, 1])[:3]
                published = date(int(year), int(month or 1), int(day or 1))
                if not self.within(published):
                    continue
                authors = []
                for author in item.get("author") or []:
                    name = " ".join(filter(None, [author.get("given"), author.get("family")]))
                    affiliation = author.get("affiliation") or []
                    authors.append(
                        (name or "?", affiliation[0].get("name") if affiliation else None, None)
                    )
                kind = item.get("type")
                source_class = {
                    "posted-content": "PREPRINT",
                    "report": "ANALYST_REPORT",
                    "standard": "STANDARD",
                }.get(kind, "JOURNAL_ARTICLE")
                out.append(
                    _document(
                        "crossref",
                        source_class,
                        item["DOI"],
                        titles[0],
                        re.sub(r"<[^>]+>", " ", item.get("abstract") or "") or None,
                        published,
                        f"https://doi.org/{item['DOI']}",
                        authors=authors,
                        topics=item.get("subject") or [],
                        doi=item["DOI"],
                    )
                )
            next_cursor = message.get("next-cursor")
            if not next_cursor:
                break
            cursor = next_cursor
        report.documents = len(out)
        report.note_dates(out)
        return out, report

    def hacker_news(
        self, targets: tuple[str, ...], pages: int
    ) -> tuple[list[dict[str, Any]], SourceReport]:
        """Hacker News: каждая цель — отдельный запрос точной фразой."""
        report = SourceReport("hackernews")
        start = int(
            datetime(
                self.window_from.year, self.window_from.month, self.window_from.day, tzinfo=UTC
            ).timestamp()
        )
        end = (
            int(
                datetime(
                    self.window_to.year, self.window_to.month, self.window_to.day, tzinfo=UTC
                ).timestamp()
            )
            + 86400
        )
        out: list[dict[str, Any]] = []
        for phrase in _phrases(targets):
            for page in range(pages):
                response = self.get(
                    report,
                    "https://hn.algolia.com/api/v1/search",
                    {
                        "query": f'"{phrase}"',
                        "tags": "story",
                        "advancedSyntax": "true",
                        "numericFilters": f"created_at_i>={start},created_at_i<{end}",
                        "hitsPerPage": 100,
                        "page": page,
                    },
                    0.5,
                )
                if response is None:
                    break
                hits = response.json().get("hits", [])
                for hit in hits:
                    if not hit.get("title"):
                        continue
                    out.append(
                        _document(
                            "hackernews",
                            "NEWS",
                            hit["objectID"],
                            hit["title"],
                            re.sub(r"<[^>]+>", " ", hit.get("story_text") or "") or None,
                            datetime.fromtimestamp(hit["created_at_i"], UTC).date(),
                            f"https://news.ycombinator.com/item?id={hit['objectID']}",
                            authors=[(hit.get("author") or "?", None, None)],
                        )
                    )
                if len(hits) < 100:
                    break
        report.documents = len(out)
        report.note_dates(out)
        return out, report


def collect(direction: str, out_dir: Path) -> Path:
    """Собрать научный корпус направления и напечатать по строке на источник."""
    wanted = targets(direction)
    print(f"{direction}: цели сбора {', '.join(wanted)}", flush=True)
    today = date.today()
    collector = Collector(date(today.year - 6, 1, 1), today)
    steps: list[tuple[str, Callable[[], tuple[list[dict[str, Any]], SourceReport]]]] = [
        ("arxiv", lambda: collector.arxiv(wanted, pages=6)),
        ("semanticscholar", lambda: collector.semantic_scholar(wanted)),
        ("europepmc", lambda: collector.europe_pmc(wanted, pages=3)),
        ("crossref", lambda: collector.crossref(wanted, pages=4)),
        ("hackernews", lambda: collector.hacker_news(wanted, pages=2)),
    ]
    documents: dict[str, dict[str, Any]] = {}
    reports: list[SourceReport] = []
    for name, step in steps:
        started = time.monotonic()
        try:
            batch, report = step()
        except Exception as error:  # отказ источника — запись в отчёт, а не молчаливый ноль
            batch, report = [], SourceReport(
                name, error=f"{type(error).__name__}: {error}", failures=1
            )
        report.seconds = round(time.monotonic() - started, 1)
        reports.append(report)
        fresh = 0
        for document in batch:
            if document["dedupKey"] not in documents:
                documents[document["dedupKey"]] = document
                fresh += 1
        state = (
            "ОТКАЗ"
            if report.failures and not report.documents
            else ("частично" if report.failures else "ok")
        )
        print(
            f"{direction:18} {name:16} {state:9} документов {report.documents:5} новых {fresh:5} "
            f"запросов {report.requests:3} отказов {report.failures} {report.seconds:6.1f} с "
            f"даты {report.oldest}..{report.newest} {report.error or ''}",
            flush=True,
        )
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f"{direction}.jsonl"
    with path.open("w", encoding="utf-8") as handle:
        for document in documents.values():
            handle.write(json.dumps(document, ensure_ascii=False) + "\n")
    (out_dir / f"{path.stem}.sources.json").write_text(
        json.dumps([report.__dict__ for report in reports], ensure_ascii=False, indent=1),
        encoding="utf-8",
    )
    empty = sum(1 for report in reports if report.documents == 0)
    print(
        f"{direction}: {len(documents)} документов, пустых источников {empty} из {len(reports)}",
        flush=True,
    )
    return path


def run(
    corpora: list[Path],
    direction: str,
    top_n: int,
    max_documents: int,
    overrides: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Прогнать конвейер методологии на корпусе и вернуть воронку."""
    from dataclasses import replace

    from horizon_analytics.adapters.corpus.fixture_loader import load_documents
    from horizon_analytics.adapters.embeddings.tfidf_svd import TfidfSvdEmbeddingProvider
    from horizon_analytics.domain.models import AnalysisParams
    from horizon_analytics.domain.pipeline import AnalysisPipeline, PipelineRequest
    from horizon_analytics.domain.scoring.profile import MethodologyProfile

    # Несколько корпусов — научный сбор этого инструмента и выгрузка живой проверки продуктовых
    # коннекторов сервиса сбора; повторы снимаются ключом дедупликации, как в сервисе сбора.
    documents = []
    seen: set[str] = set()
    for corpus in corpora:
        for document in load_documents(corpus):
            key = document.dedup_key or f"{document.source_id}:{document.external_id}"
            if key not in seen:
                seen.add(key)
                documents.append(document)
    query = DIRECTIONS[direction]
    profile = MethodologyProfile.default()
    profile = replace(
        profile,
        parameters=profile.parameters.with_overrides(
            {"maxDocumentsAnalyzed": max_documents, **(overrides or {})}
        ),
    )
    today = date.today()
    request = PipelineRequest(
        normalized_query=query,
        query=query,
        documents=tuple(documents),
        params=AnalysisParams(top_n=top_n, years_window=7),
        profile=profile,
        window_from=date(today.year - 6, 1, 1),
        window_to=today,
        today=today,
    )
    pipeline = AnalysisPipeline(embedding_provider=TfidfSvdEmbeddingProvider())
    started = time.monotonic()
    result = pipeline.run(request)
    diagnostics = dict(result.diagnostics)
    return {
        "direction": direction,
        "documents": result.documents_analyzed,
        "bySource": dict(Counter(document.source_id for document in documents)),
        "seconds": round(time.monotonic() - started, 1),
        "diagnostics": diagnostics,
        "trends": [outcome.result.title for outcome in result.trends],
        # Откуда доказательная база каждой темы ТОП-N: видно, дошли ли продуктовые источники до
        # отчёта или только до корпуса.
        "evidenceSources": {
            outcome.result.title: dict(Counter(item.source_id for item in outcome.evidence.items))
            for outcome in result.trends
        },
    }


def main(argv: list[str] | None = None) -> None:
    """Точка входа: ``collect`` или ``run``."""
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    collect_cmd = sub.add_parser("collect")
    collect_cmd.add_argument(
        "--direction", choices=sorted(DIRECTIONS), action="append", required=True
    )
    collect_cmd.add_argument("--out", type=Path, required=True)
    run_cmd = sub.add_parser("run")
    run_cmd.add_argument("--corpus", type=Path, action="append", required=True)
    run_cmd.add_argument("--direction", choices=sorted(DIRECTIONS), required=True)
    run_cmd.add_argument("--top-n", type=int, default=15)
    # Тысяча восемьсот, а не двенадцать тысяч стенда: на общей машине разбор трёх тысяч документов
    # не помещается в свободную память. Сравнение «до и после» идёт на одной и той же выборке.
    run_cmd.add_argument("--max-documents", type=int, default=1800)
    run_cmd.add_argument("--override", default="{}", help="параметры профиля JSON-объектом")
    args = parser.parse_args(argv)
    if args.command == "collect":
        for direction in args.direction:
            collect(direction, args.out)
    else:
        json.dump(
            run(
                args.corpus,
                args.direction,
                args.top_n,
                args.max_documents,
                json.loads(args.override),
            ),
            sys.stdout,
            ensure_ascii=False,
            indent=1,
        )
        print()


if __name__ == "__main__":
    main()
