"""Поиск исследователя — открытые источники проекта, а не поисковая система.

Ключа к поисковому API (Brave, Serper, Tavily) у проекта нет, и заводить его ради одного
источника незачем: у сервиса сбора уже есть коннекторы к открытым каталогам, и инструмент
``search`` агента спрашивает те же каталоги теми же адресами. OpenAlex здесь нет намеренно — его
суточный бюджет общий с демонстрационным стендом (разбор 88), и исследование, выбравшее его за
полчаса, оставило бы стенд без научного корпуса до конца суток.

Источники и что они дают агенту:

* ``arxiv`` — препринты, ``export.arxiv.org``, запрос в три секунды;
* ``semanticscholar`` — научный указатель с аннотациями; без ключа общий пул, бывают 429;
* ``crossref`` — метаданные DOI, журнальные и конференционные статьи;
* ``europepmc`` — биомедицина и смежное, с аннотациями;
* ``hackernews`` — ранние обсуждения у разработчиков (Algolia), ссылки на первоисточники;
* ``github`` — репозитории, созданные в окне; без токена десять поисков в минуту;
* ``habr`` — русские технические публикации и блоги компаний, поисковая RSS-лента;
* ``industry`` — шесть профессиональных отраслевых изданий через поисковые ленты WordPress
  (разбор 101): EdgeIR, SiliconANGLE, Plant Engineering, Robohub, CyberScoop, The Fintech Times.

Отказ источника — исключение :class:`SourceUnavailableError`, пустой ответ — пустой список. Агент видит
разницу словами, и счёт по ней уходит в ответ сервиса.

Кроме поиска здесь же — **чтение записей каталогов через их API**. Страница arXiv, Europe PMC,
Semantic Scholar или GitHub, которую агент решил прочитать, читается не как HTML, а как запись
API: аннотация целиком, дата, авторы. Для этих площадок API и есть задокументированный способ
чтения, а HTML Europe PMC и Semantic Scholar собирается скриптом и текста не содержит.
"""

from __future__ import annotations

import hashlib
import re
import time
import xml.etree.ElementTree as ElementTree
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import date, datetime
from typing import Any
from urllib.parse import quote, urlsplit

from horizon_nlp.config import settings
from horizon_nlp.research.corpus import CorpusDocument
from horizon_nlp.research.corpus import corpus as web_corpus
from horizon_nlp.research.web import PageText, WebAccess, parse_date, stable_json

__all__ = [
    "HOST_INTERVALS",
    "INDUSTRY_FEEDS",
    "SOURCES",
    "SearchHit",
    "SourceUnavailableError",
    "Window",
    "catalog_reader",
    "search",
]

#: Поисковые ленты отраслевых изданий — те же, что у коннектора ``industry`` (разбор 101).
INDUSTRY_FEEDS: tuple[str, ...] = (
    "https://www.edgeir.com/search/{q}/feed/rss2/",
    "https://siliconangle.com/search/{q}/feed/rss2/",
    "https://www.plantengineering.com/search/{q}/feed/rss2/",
    "https://robohub.org/search/{q}/feed/rss2/",
    "https://cyberscoop.com/search/{q}/feed/rss2/",
    "https://thefintechtimes.com/search/{q}/feed/rss2/",
)

#: Паузы на хост по правилам площадок; остальным — две секунды по умолчанию.
HOST_INTERVALS: dict[str, float] = {
    "export.arxiv.org": 3.0,
    "arxiv.org": 3.0,
    "api.semanticscholar.org": 1.5,
    "api.github.com": 6.5,
    "habr.com": 10.0,
    "edgeir.com": 10.0,
    "siliconangle.com": 10.0,
    "plantengineering.com": 10.0,
    "robohub.org": 10.0,
    "cyberscoop.com": 10.0,
    "thefintechtimes.com": 10.0,
}

_ATOM = "{http://www.w3.org/2005/Atom}"
_MAX_HITS = 8


class SourceUnavailableError(Exception):
    """Источник не ответил: сеть, HTTP-ошибка, неразобранный ответ. Не путать с «ничего нет»."""


@dataclass(frozen=True, slots=True)
class Window:
    """Окно наблюдения запроса: документы вне него в корпус не идут."""

    start: date
    end: date

    def contains(self, value: date | None) -> bool:
        """Дата в окне; неизвестная дата — не в окне."""
        return value is not None and self.start <= value <= self.end


@dataclass(frozen=True, slots=True)
class SearchHit:
    """Одна запись поисковой выдачи — пока не свидетельство, только адрес, что читать."""

    url: str
    title: str
    published: date | None
    snippet: str
    source: str
    #: Класс документа в терминах корпуса: PREPRINT, JOURNAL_ARTICLE, CODE_REPOSITORY, NEWS.
    source_class: str
    authors: tuple[str, ...] = ()
    #: Организация за публикацией, если источник её называет (издание, владелец репозитория).
    organization: str | None = None
    organization_is_company: bool = False
    doi: str | None = None
    arxiv_id: str | None = None
    venue: str | None = None


@dataclass
class _Context:
    web: WebAccess
    window: Window
    deadline: float
    contact_email: str
    github_token: str = ""
    extra: dict[str, str] = field(default_factory=dict)


def _json(context: _Context, url: str, headers: dict[str, str] | None = None) -> object:
    try:
        response = context.web.get(url, context.deadline, headers=headers)
    except Exception as error:
        raise SourceUnavailableError(f"сеть: {type(error).__name__}") from error
    if response.status_code == 429:
        raise SourceUnavailableError("HTTP 429: лимит источника")
    if response.status_code >= 400:
        raise SourceUnavailableError(f"HTTP {response.status_code}")
    try:
        return response.json()
    except ValueError as error:
        raise SourceUnavailableError("ответ не JSON") from error


def _text(context: _Context, url: str, headers: dict[str, str] | None = None) -> str:
    try:
        response = context.web.get(url, context.deadline, headers=headers)
    except Exception as error:
        raise SourceUnavailableError(f"сеть: {type(error).__name__}") from error
    if response.status_code >= 400:
        raise SourceUnavailableError(f"HTTP {response.status_code}")
    return response.text


def _clean(value: str | None) -> str:
    if not value:
        return ""
    text = re.sub(r"<[^>]+>", " ", value)
    for entity, char in (("&nbsp;", " "), ("&amp;", "&"), ("&lt;", "<"), ("&gt;", ">"), ("&quot;", '"'),
                         ("&#8217;", "'"), ("&#8220;", '"'), ("&#8221;", '"'), ("&#39;", "'")):
        text = text.replace(entity, char)
    return re.sub(r"\s+", " ", text).strip()


def _arxiv_terms(query: str) -> str:
    words = list(re.findall(r"[A-Za-z0-9][A-Za-z0-9\-]+", query))[:6]
    return " AND ".join(f"all:{w}" for w in words) if words else f'all:"{query}"'


def _parse_arxiv(feed: str) -> list[tuple[SearchHit, str]]:
    try:
        root = ElementTree.fromstring(feed)
    except ElementTree.ParseError as error:
        raise SourceUnavailableError("лента arXiv не разбирается") from error
    hits: list[tuple[SearchHit, str]] = []
    for entry in root.findall(f"{_ATOM}entry"):
        identifier = (entry.findtext(f"{_ATOM}id") or "").strip()
        match = re.search(r"arxiv\.org/abs/([^\s]+?)(v\d+)?$", identifier)
        if not match:
            continue
        arxiv_id = match.group(1)
        summary = _clean(entry.findtext(f"{_ATOM}summary"))
        hits.append(
            (
                SearchHit(
                    url=f"https://arxiv.org/abs/{arxiv_id}",
                    title=_clean(entry.findtext(f"{_ATOM}title")),
                    published=parse_date(entry.findtext(f"{_ATOM}published")),
                    snippet=summary[:300],
                    source="arxiv",
                    source_class="PREPRINT",
                    authors=tuple(
                        _clean(a.findtext(f"{_ATOM}name")) for a in entry.findall(f"{_ATOM}author")
                    )[:20],
                    arxiv_id=arxiv_id,
                ),
                summary,
            )
        )
    return hits


def _search_arxiv(context: _Context, query: str) -> list[SearchHit]:
    window = f"submittedDate:[{context.window.start:%Y%m%d}0000 TO {context.window.end:%Y%m%d}2359]"
    url = (
        "https://export.arxiv.org/api/query?search_query="
        + quote(f"({_arxiv_terms(query)}) AND {window}")
        + f"&start=0&max_results={_MAX_HITS}&sortBy=relevance"
    )
    return [hit for hit, _ in _parse_arxiv(_text(context, url))]


def _search_semanticscholar(context: _Context, query: str) -> list[SearchHit]:
    url = (
        "https://api.semanticscholar.org/graph/v1/paper/search?query="
        + quote(query)
        + f"&limit={_MAX_HITS}&publicationDateOrYear={context.window.start}:{context.window.end}"
        + "&fields=title,abstract,url,publicationDate,year,externalIds,authors,venue"
    )
    body = _json(context, url)
    hits: list[SearchHit] = []
    for item in (body.get("data") if isinstance(body, dict) else None) or []:
        ids = item.get("externalIds") or {}
        arxiv_id = ids.get("ArXiv")
        doi = ids.get("DOI")
        link = (
            f"https://arxiv.org/abs/{arxiv_id}" if arxiv_id
            else f"https://doi.org/{doi}" if doi
            else item.get("url") or ""
        )
        if not link:
            continue
        hits.append(
            SearchHit(
                url=link,
                title=_clean(item.get("title")),
                published=parse_date(item.get("publicationDate")) or (
                    date(int(item["year"]), 1, 1) if item.get("year") else None
                ),
                snippet=_clean(item.get("abstract"))[:300],
                source="semanticscholar",
                source_class="PREPRINT" if arxiv_id and not doi else "JOURNAL_ARTICLE",
                authors=tuple(a.get("name", "") for a in item.get("authors") or [] if a.get("name"))[:20],
                doi=doi,
                arxiv_id=arxiv_id,
                venue=item.get("venue") or None,
            )
        )
    return hits


def _crossref_date(item: dict[str, Any]) -> date | None:
    for key in ("published", "published-online", "published-print", "issued", "created"):
        parts = ((item.get(key) or {}).get("date-parts") or [[None]])[0]
        if parts and parts[0]:
            try:
                return date(int(parts[0]), int(parts[1]) if len(parts) > 1 else 1, int(parts[2]) if len(parts) > 2 else 1)
            except (TypeError, ValueError):
                continue
    return None


def _crossref_hit(item: dict[str, Any]) -> SearchHit | None:
    doi = item.get("DOI")
    title = _clean((item.get("title") or [""])[0])
    if not doi or not title:
        return None
    return SearchHit(
        url=f"https://doi.org/{doi}",
        title=title,
        published=_crossref_date(item),
        snippet=_clean(item.get("abstract"))[:300],
        source="crossref",
        source_class="PREPRINT" if item.get("type") == "posted-content" else "JOURNAL_ARTICLE",
        authors=tuple(
            " ".join(p for p in (a.get("given"), a.get("family")) if p) for a in item.get("author") or []
        )[:20],
        doi=doi,
        venue=_clean((item.get("container-title") or [""])[0]) or None,
    )


def _search_crossref(context: _Context, query: str) -> list[SearchHit]:
    url = (
        "https://api.crossref.org/works?query.bibliographic="
        + quote(query)
        + f"&rows={_MAX_HITS}&filter=from-pub-date:{context.window.start},until-pub-date:{context.window.end}"
        + "&select=DOI,title,abstract,published,issued,created,type,author,container-title"
        + f"&mailto={quote(context.contact_email)}"
    )
    body = _json(context, url)
    items = ((body.get("message") or {}).get("items") if isinstance(body, dict) else None) or []
    return [hit for hit in (_crossref_hit(item) for item in items) if hit]


def _europepmc_hit(item: dict[str, Any]) -> tuple[SearchHit, str] | None:
    source, identifier = item.get("source"), item.get("id")
    title = _clean(item.get("title"))
    if not source or not identifier or not title:
        return None
    abstract = _clean(item.get("abstractText"))
    authors = tuple(
        a.get("fullName", "") for a in ((item.get("authorList") or {}).get("author") or []) if a.get("fullName")
    )[:20]
    return (
        SearchHit(
            url=f"https://europepmc.org/article/{source}/{identifier}",
            title=title,
            published=parse_date(item.get("firstPublicationDate")),
            snippet=abstract[:300],
            source="europepmc",
            source_class="PREPRINT" if source == "PPR" else "JOURNAL_ARTICLE",
            authors=authors,
            doi=item.get("doi"),
            venue=((item.get("journalInfo") or {}).get("journal") or {}).get("title"),
        ),
        abstract,
    )


def _search_europepmc(context: _Context, query: str) -> list[SearchHit]:
    expression = f"({query}) AND FIRST_PDATE:[{context.window.start} TO {context.window.end}]"
    url = (
        "https://www.ebi.ac.uk/europepmc/webservices/rest/search?query="
        + quote(expression)
        + f"&format=json&resultType=core&pageSize={_MAX_HITS}"
    )
    body = _json(context, url)
    results = ((body.get("resultList") or {}).get("result") if isinstance(body, dict) else None) or []
    return [pair[0] for pair in (_europepmc_hit(item) for item in results) if pair]


def _search_hackernews(context: _Context, query: str) -> list[SearchHit]:
    start = int(datetime.combine(context.window.start, datetime.min.time()).timestamp())
    end = int(datetime.combine(context.window.end, datetime.max.time()).timestamp())
    url = (
        "https://hn.algolia.com/api/v1/search?query="
        + quote(query)
        + f"&tags=story&hitsPerPage={_MAX_HITS}&numericFilters="
        + quote(f"created_at_i>={start},created_at_i<={end}")
    )
    body = _json(context, url)
    hits: list[SearchHit] = []
    for item in (body.get("hits") if isinstance(body, dict) else None) or []:
        link = item.get("url") or f"https://news.ycombinator.com/item?id={item.get('objectID')}"
        title = _clean(item.get("title"))
        if not title:
            continue
        hits.append(
            SearchHit(
                url=link,
                title=title,
                published=parse_date(item.get("created_at")),
                snippet=f"Hacker News: {item.get('points') or 0} points, {item.get('num_comments') or 0} comments",
                source="hackernews",
                source_class="NEWS",
            )
        )
    return hits


def _github_headers(context: _Context) -> dict[str, str]:
    headers = {"Accept": "application/vnd.github+json"}
    if context.github_token:
        headers["Authorization"] = f"Bearer {context.github_token}"
    return headers


def _search_github(context: _Context, query: str) -> list[SearchHit]:
    url = (
        "https://api.github.com/search/repositories?q="
        + quote(f"{query} created:{context.window.start}..{context.window.end}")
        + f"&per_page={_MAX_HITS}"
    )
    body = _json(context, url, _github_headers(context))
    hits: list[SearchHit] = []
    for item in (body.get("items") if isinstance(body, dict) else None) or []:
        owner = item.get("owner") or {}
        hits.append(
            SearchHit(
                url=item.get("html_url") or "",
                title=item.get("full_name") or "",
                published=parse_date(item.get("created_at")),
                snippet=_clean(item.get("description"))[:300] + f" (★{item.get('stargazers_count', 0)})",
                source="github",
                source_class="CODE_REPOSITORY",
                authors=(str(owner["login"]),) if owner.get("login") else (),
                organization=owner.get("login") if owner.get("type") == "Organization" else None,
                organization_is_company=owner.get("type") == "Organization",
            )
        )
    return [hit for hit in hits if hit.url]


def _rss_items(feed: str) -> list[dict[str, str]]:
    feed = re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f]", "", feed)
    try:
        root = ElementTree.fromstring(feed)
    except ElementTree.ParseError as error:
        raise SourceUnavailableError("ответ не RSS (возможно, проверка на робота)") from error
    items = []
    for item in root.iter("item"):
        items.append(
            {
                "title": _clean(item.findtext("title")),
                "link": (item.findtext("link") or "").strip(),
                "date": item.findtext("pubDate") or "",
                "description": _clean(item.findtext("description")),
                "author": _clean(item.findtext("{http://purl.org/dc/elements/1.1/}creator")),
            }
        )
    return items


def _search_habr(context: _Context, query: str) -> list[SearchHit]:
    url = "https://habr.com/ru/rss/search/?q=" + quote(query) + "&target_type=posts&order=relevance"
    allowed, reason = context.web.robots_verdict(url, context.deadline)
    if not allowed:
        raise SourceUnavailableError(reason)
    hits: list[SearchHit] = []
    for item in _rss_items(_text(context, url)):
        published = parse_date(item["date"])
        if not item["link"] or not item["title"] or not context.window.contains(published):
            continue
        company = re.search(r"habr\.com/(?:ru|en)/companies/([^/]+)/", item["link"])
        hits.append(
            SearchHit(
                url=item["link"].split("?", 1)[0],
                title=item["title"],
                published=published,
                snippet=item["description"][:300],
                source="habr",
                source_class="NEWS",
                authors=(item["author"],) if item["author"] else (),
                organization=company.group(1) if company else None,
                organization_is_company=company is not None,
            )
        )
    return hits[:_MAX_HITS]


def _search_industry(context: _Context, query: str) -> list[SearchHit]:
    """Все шесть изданий параллельно: хосты разные, пауза в десять секунд у каждого своя."""
    phrase = quote(query.lower(), safe="")
    words = [w for w in re.findall(r"\w+", query.lower()) if len(w) >= 3]

    def one(template: str) -> list[SearchHit]:
        url = template.replace("{q}", phrase)
        allowed, reason = context.web.robots_verdict(url, context.deadline)
        if not allowed:
            raise SourceUnavailableError(reason)
        outlet = urlsplit(url).netloc.lower().removeprefix("www.")
        found = []
        for item in _rss_items(_text(context, url)):
            published = parse_date(item["date"])
            text = f"{item['title']} {item['description']}".lower()
            if not item["link"] or not context.window.contains(published):
                continue
            if any(word not in text for word in words):
                continue
            found.append(
                SearchHit(
                    url=item["link"],
                    title=item["title"],
                    published=published,
                    snippet=item["description"][:300],
                    source="industry",
                    source_class="NEWS",
                    authors=(item["author"],) if item["author"] else (outlet,),
                    organization=outlet,
                    organization_is_company=True,
                    venue=outlet,
                )
            )
        return found

    hits: list[SearchHit] = []
    failures: list[str] = []
    with ThreadPoolExecutor(max_workers=len(INDUSTRY_FEEDS)) as pool:
        for template, future in [(t, pool.submit(one, t)) for t in INDUSTRY_FEEDS]:
            try:
                hits.extend(future.result())
            except SourceUnavailableError as error:
                failures.append(f"{urlsplit(template).netloc}: {error}")
            except Exception as error:
                failures.append(f"{urlsplit(template).netloc}: {type(error).__name__}")
    if len(failures) == len(INDUSTRY_FEEDS):
        raise SourceUnavailableError("ни одно издание не ответило: " + "; ".join(failures))
    return hits[: _MAX_HITS * 2]


def _search_webcorpus(context: _Context, query: str) -> list[SearchHit]:
    """Собственный веб-корпус (разбор 110): страницы компаний, пресс-релизы, отраслевые издания."""
    index = web_corpus(settings.webcorpus_path)
    if index is None:
        raise SourceUnavailableError("веб-корпус не подключён (HORIZON_NLP_WEBCORPUS_PATH)")
    return [
        _corpus_hit(document)
        for document, _ in index.search(
            query, start=context.window.start, end=context.window.end, limit=_MAX_HITS
        )
    ]


def _corpus_hit(document: CorpusDocument) -> SearchHit:
    return SearchHit(
        url=document.url,
        title=document.title,
        published=document.published,
        snippet=_clean(document.text[:300]),
        source="webcorpus",
        source_class="NEWS",
        authors=(document.sitename or document.host,),
        organization=document.host,
        organization_is_company=True,
        venue=document.sitename or document.host,
    )


SOURCES: dict[str, Callable[[_Context, str], list[SearchHit]]] = {
    "arxiv": _search_arxiv,
    "semanticscholar": _search_semanticscholar,
    "crossref": _search_crossref,
    "europepmc": _search_europepmc,
    "hackernews": _search_hackernews,
    "github": _search_github,
    "habr": _search_habr,
    "industry": _search_industry,
    "webcorpus": _search_webcorpus,
}


def search(
    source: str,
    query: str,
    *,
    web: WebAccess,
    window: Window,
    deadline: float,
    contact_email: str,
    github_token: str = "",
) -> list[SearchHit]:
    """Спросить один источник. :class:`SourceUnavailableError` — отказ; пустой список — ничего не нашлось."""
    if source not in SOURCES:
        raise SourceUnavailableError(f"неизвестный источник {source!r}")
    if time.monotonic() >= deadline:
        raise SourceUnavailableError("бюджет времени исчерпан")
    context = _Context(web, window, deadline, contact_email, github_token)
    return SOURCES[source](context, query)


def _api_page(url: str, title: str, text: str, published: date | None, record: object, via: str) -> PageText:
    return PageText(
        url=url,
        title=title,
        text=text,
        published=published,
        status=200,
        sha256=hashlib.sha256(stable_json(record)).hexdigest(),
        fetched_at=datetime.now().astimezone().isoformat(timespec="seconds"),
        via=via,
    )


def catalog_reader(
    url: str, *, web: WebAccess, deadline: float, contact_email: str, github_token: str = ""
) -> tuple[PageText | None, SearchHit | None] | None:
    """Прочитать запись каталога через его API, если адрес — страница каталога.

    ``None`` — адрес не каталожный, читать как обычную страницу. Исключение
    :class:`SourceUnavailableError` — каталог не ответил.
    """
    context = _Context(web, Window(date(1900, 1, 1), date(2100, 1, 1)), deadline, contact_email, github_token)
    index = web_corpus(settings.webcorpus_path)
    stored = index.page(url) if index is not None else None
    if stored is not None:
        # Страница уже прочитана сборщиком с проверкой robots.txt — второй раз к сайту не идём.
        record = {"url": stored.url, "fetchedAt": stored.fetched_at, "robots": stored.robots, "sha256": stored.sha256}
        return _api_page(stored.url, stored.title, stored.text, stored.published, record, "webcorpus"), _corpus_hit(stored)
    parts = urlsplit(url)
    host = parts.netloc.lower().removeprefix("www.")
    if host in ("arxiv.org", "export.arxiv.org"):
        match = re.match(r"/(?:abs|pdf|html)/([^/?#]+?)(?:v\d+)?(?:\.pdf)?/?$", parts.path)
        if not match:
            return None
        feed = _text(context, "https://export.arxiv.org/api/query?id_list=" + quote(match.group(1)))
        parsed = _parse_arxiv(feed)
        if not parsed:
            raise SourceUnavailableError("arXiv не знает такой записи")
        hit, summary = parsed[0]
        text = f"{hit.title}\n\n{summary}"
        return _api_page(hit.url, hit.title, text, hit.published, feed, "arxiv-api"), hit
    if host == "europepmc.org":
        match = re.match(r"/(?:article|abstract)/([A-Z]+)/([^/?#]+)", parts.path)
        if not match:
            return None
        expression = f"EXT_ID:{match.group(2)} AND SRC:{match.group(1)}"
        body = _json(
            context,
            "https://www.ebi.ac.uk/europepmc/webservices/rest/search?query="
            + quote(expression)
            + "&format=json&resultType=core&pageSize=1",
        )
        results = ((body.get("resultList") or {}).get("result") if isinstance(body, dict) else None) or []
        pair = _europepmc_hit(results[0]) if results else None
        if not pair:
            raise SourceUnavailableError("Europe PMC не знает такой записи")
        hit, abstract = pair
        return _api_page(hit.url, hit.title, f"{hit.title}\n\n{abstract}", hit.published, results[0], "europepmc-api"), hit
    if host == "semanticscholar.org":
        match = re.search(r"/paper/(?:[^/]+/)?([0-9a-f]{40})", parts.path)
        if not match:
            return None
        body = _json(
            context,
            f"https://api.semanticscholar.org/graph/v1/paper/{match.group(1)}"
            "?fields=title,abstract,url,publicationDate,year,externalIds,authors,venue",
        )
        if not isinstance(body, dict) or not body.get("title"):
            raise SourceUnavailableError("Semantic Scholar не знает такой записи")
        text = f"{_clean(body.get('title'))}\n\n{_clean(body.get('abstract'))}"
        published = parse_date(body.get("publicationDate"))
        hit = SearchHit(
            url=url, title=_clean(body.get("title")), published=published, snippet="",
            source="semanticscholar", source_class="JOURNAL_ARTICLE",
            authors=tuple(a.get("name", "") for a in body.get("authors") or [] if a.get("name"))[:20],
            doi=(body.get("externalIds") or {}).get("DOI"),
        )
        return _api_page(url, hit.title, text, published, body, "semanticscholar-api"), hit
    if host == "github.com":
        match = re.match(r"/([^/]+)/([^/?#]+)/?$", parts.path)
        if not match:
            return None
        owner, name = match.group(1), match.group(2).removesuffix(".git")
        repo = _json(context, f"https://api.github.com/repos/{owner}/{name}", _github_headers(context))
        if not isinstance(repo, dict) or not repo.get("full_name"):
            raise SourceUnavailableError("GitHub не знает такого репозитория")
        try:
            readme = _text(
                context,
                f"https://api.github.com/repos/{owner}/{name}/readme",
                {**_github_headers(context), "Accept": "application/vnd.github.raw"},
            )
        except SourceUnavailableError:
            readme = ""
        owner_info = repo.get("owner") or {}
        topics = ", ".join(repo.get("topics") or [])
        text = "\n\n".join(
            part for part in (repo.get("full_name"), _clean(repo.get("description")), topics, readme[:60000]) if part
        )
        published = parse_date(repo.get("created_at"))
        hit = SearchHit(
            url=repo.get("html_url") or url, title=str(repo["full_name"]), published=published, snippet="",
            source="github", source_class="CODE_REPOSITORY",
            authors=(str(owner_info["login"]),) if owner_info.get("login") else (),
            organization=owner_info.get("login") if owner_info.get("type") == "Organization" else None,
            organization_is_company=owner_info.get("type") == "Organization",
        )
        return _api_page(hit.url, hit.title, text, published, repo, "github-api"), hit
    if host == "doi.org" or host == "dx.doi.org":
        doi = parts.path.lstrip("/")
        body = _json(context, "https://api.crossref.org/works/" + quote(doi) + f"?mailto={quote(contact_email)}")
        item = (body.get("message") if isinstance(body, dict) else None) or {}
        doi_hit = _crossref_hit(item)
        if doi_hit is None:
            raise SourceUnavailableError("Crossref не знает такого DOI")
        hit = doi_hit
        abstract = _clean(item.get("abstract"))
        page = (
            _api_page(url, hit.title, f"{hit.title}\n\n{abstract}", hit.published, item, "crossref-api")
            if len(abstract) >= 200
            else None
        )
        # Запись Crossref без аннотации читать нечего: вызывающий пойдёт на страницу издателя.
        return page, hit
    return None
