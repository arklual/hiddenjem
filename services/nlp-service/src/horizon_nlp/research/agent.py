"""Цикл агента глубокого исследования: поиск → чтение целиком → размышление → запись.

Модель ведёт исследование сама — решает, что искать, что читать и когда остановиться, — но три
вещи ей не доверены и живут здесь, в коде.

**Бюджет.** Число ходов модели, число прочитанных страниц и время. Сага сбора ограничена
дедлайном, и исследование, не знающее своего потолка, забирало бы время у остальных источников.
Поиск и чтение кончаются за ``final_seconds`` до конца бюджета; остаток принадлежит записи.

**Запись по ходу, а не в конце.** Имена записывает не только ведущая модель, но и регистратор:
каждые ``record_batch`` прочитанных страниц уходят отдельному короткому вызову той же модели —
только начала этих страниц и ``record_technology``, без истории диалога. Он идёт параллельно с
поиском, и первые имена появляются после первой пачки чтений, а не в последнюю минуту. Раньше
запись откладывалась на конец и делалась в диалоге на 80–125 тысяч токенов, где ход Luna шёл
47–55 с: на стенде 27 сентября робототехника дважды прочитала 16–20 страниц за 327 с и не
записала ни одного имени. Короткий контекст регистратора — несколько тысяч токенов.

**Свидетельство.** Имя технологии записывается, только если модель сослалась на страницы,
прочитанные в этом прогоне, и имя на этих страницах действительно есть — проверяется по тексту,
который получил сервис, а не по цитате, которую привела модель. Документом корпуса становится
страница, подтвердившая хотя бы одно имя; её текст — дословные отрывки прочитанного. Итоговый
отчёт модели хранится в ответе для аудита и не становится ничем.

**Счёт.** Каждый поиск — ``ok``, ``empty`` или ``failed``; каждое чтение — ``ok``, ``refused``,
``failed`` или ``skipped``. Доля отказов чтения пишется в журнал и уходит в ответ: отказ, который
выглядит как ноль, — ловушка, в которую проект уже попадал.
"""

from __future__ import annotations

import json
import re
import threading
import time
from collections import Counter
from collections.abc import Callable, Sequence
from concurrent.futures import Future, ThreadPoolExecutor
from concurrent.futures import wait as wait_futures
from dataclasses import dataclass, field
from datetime import date
from typing import Any, Protocol
from urllib.parse import urlsplit, urlunsplit

import structlog

from horizon_nlp.research.prompt import (
    RECORDER_PROMPT,
    SYSTEM_PROMPT,
    TOOLS,
    recorder_message,
    task_message,
)
from horizon_nlp.research.sources import (
    SearchHit,
    SourceUnavailableError,
    Window,
    catalog_reader,
    search,
)
from horizon_nlp.research.web import FetchOutcome, PageText, WebAccess

__all__ = [
    "Budget",
    "DeepResearch",
    "ResearchDocument",
    "ResearchResult",
    "name_supported",
]

log = structlog.get_logger(__name__)


class ToolModel(Protocol):
    """То, что агенту нужно от модели: один ход диалога с инструментами."""

    def chat_with_tools(
        self,
        messages: Sequence[dict[str, object]],
        tools: Sequence[dict[str, object]],
        *,
        timeout: float,
        max_completion_tokens: int = 4096,
        reasoning_effort: str | None = None,
    ) -> tuple[dict[str, object], dict[str, int]]:
        """Сообщение модели и расход токенов."""
        ...


@dataclass(frozen=True, slots=True)
class Budget:
    """Потолки одного прогона."""

    max_iterations: int = 40
    max_fetches: int = 30
    time_budget_seconds: float = 330.0
    min_sources: int = 10
    #: Сколько знаков страницы отдаётся модели за одно чтение; дальше — ``start_index``.
    page_chars: int = 5000
    #: Сколько секунд в конце бюджета отдано только записи: поиск и чтение к этому моменту
    #: остановлены, регистратор дочитывает то, что ещё не записано.
    final_seconds: float = 60.0
    #: Сколько новых прочитанных страниц отправляются регистратору одной пачкой.
    record_batch: int = 4
    #: Сколько знаков начала страницы видит регистратор: заголовок, аннотация, первые абзацы.
    recorder_page_chars: int = 2500
    #: Сколько последних результатов инструментов модель видит целиком. Старые страницы
    #: сворачиваются до начала: каждый ход отправляет модели весь диалог, и без свёртки расход
    #: токенов растёт квадратично (первый живой прогон: 168 тысяч токенов за 22 хода и восемь
    #: страниц). Свёрнутую страницу можно перечитать — из кэша, без бюджета страниц.
    keep_full_results: int = 6


@dataclass
class _Read:
    """Прочитанная страница, причина чтения и имена, которые она подтвердила."""

    page: PageText
    hit: SearchHit | None
    reason: str
    origin: str
    supports: list[str] = field(default_factory=list)


@dataclass(frozen=True, slots=True)
class ResearchDocument:
    """Документ корпуса: прочитанная страница, подтвердившая хотя бы одно имя."""

    url: str
    title: str
    published_on: date
    source_class: str
    origin: str
    via: str
    host: str
    language: str
    excerpt: str
    sha256: str
    fetched_at: str
    http_status: int
    authors: tuple[str, ...]
    organization: str | None
    organization_is_company: bool
    doi: str | None
    arxiv_id: str | None
    venue: str | None
    read_reason: str
    technologies: tuple[str, ...]


@dataclass
class ResearchResult:
    """Итог прогона: документы, имена, след и счёт."""

    status: str
    documents: list[ResearchDocument]
    technologies: list[dict[str, object]]
    trace: list[dict[str, object]]
    stats: dict[str, object]
    report: str


_STOPWORDS = frozenset(
    {"of", "the", "and", "for", "with", "based", "on", "in", "to", "a", "an", "via", "using", "by", "at"}
)


def _normalize(text: str) -> str:
    return " " + " ".join(re.sub(r"[^\w]+", " ", text.lower()).split()) + " "


def _variants(token: str) -> tuple[str, ...]:
    forms = {token}
    if token.endswith("s") and len(token) > 3:
        forms.add(token[:-1])
    else:
        forms.add(token + "s")
    if token.endswith("ies"):
        forms.add(token[:-3] + "y")
    if token.endswith("y"):
        forms.add(token[:-1] + "ies")
    return tuple(forms)


def name_supported(name: str, text: str) -> bool:
    """Есть ли имя на странице: фразой целиком или всеми значимыми словами.

    Проверка намеренно грубая и в одну сторону: страница, где названы все слова имени, — это
    страница о ней или рядом с ней; страница, где нет хотя бы одного, — точно не свидетельство.
    Множественное число допускается: «neuromorphic chip» и «neuromorphic chips» — одно имя.
    """
    haystack = _normalize(text)
    phrase = _normalize(name)
    if len(phrase.strip()) < 3:
        return False
    if phrase in haystack:
        return True
    tokens = [t for t in phrase.split() if t not in _STOPWORDS and (len(t) >= 3 or any(c.isdigit() for c in t))]
    if not tokens:
        return False
    return all(any(f" {form} " in haystack for form in _variants(token)) for token in tokens)


def _canonical_url(url: str) -> str:
    parts = urlsplit(url.strip())
    return urlunsplit((parts.scheme.lower(), parts.netloc.lower(), parts.path, parts.query, ""))


def _language(text: str) -> str:
    cyrillic = len(re.findall(r"[а-яА-ЯёЁ]", text[:4000]))
    latin = len(re.findall(r"[a-zA-Z]", text[:4000]))
    return "ru" if cyrillic > latin else "en"


def _excerpt(text: str, names: Sequence[str], limit: int = 4000) -> str:
    """Дословные отрывки прочитанного: начало страницы и окрестности упоминаний имён.

    Только текст, полученный сервисом, — ни слова от модели. Начало нужно, чтобы документ читался
    как документ (о чём страница), окрестности — чтобы извлечение терминов увидело само имя.
    """
    if len(text) <= limit:
        return text.strip()
    lead = text[:1200].strip()
    pieces = [lead]
    used = [(0, len(lead))]
    lowered = text.lower()
    for name in names:
        tokens = [t for t in _normalize(name).split() if t not in _STOPWORDS]
        anchor = tokens[0] if tokens else name.lower()
        found = 0
        start = 0
        while found < 2:
            position = lowered.find(name.lower(), start)
            if position < 0:
                position = lowered.find(anchor, start)
            if position < 0:
                break
            left, right = max(0, position - 350), min(len(text), position + 450)
            start = right
            if any(left < end and right > begin for begin, end in used):
                found += 1
                continue
            used.append((left, right))
            pieces.append(text[left:right].strip())
            found += 1
    excerpt = " … ".join(piece for piece in pieces if piece)
    return excerpt[:limit]


_COMPACT_CHARS = 700


def _compact(messages: list[dict[str, object]], *, keep: int) -> None:
    """Свернуть результаты инструментов старше последних ``keep`` до начала текста."""
    positions = [i for i, message in enumerate(messages) if message.get("role") == "tool"]
    for index in positions[:-keep] if keep > 0 else positions:
        content = str(messages[index].get("content") or "")
        if len(content) > _COMPACT_CHARS and not content.endswith("[folded]"):
            messages[index]["content"] = (
                content[:_COMPACT_CHARS]
                + "\n[older result folded to save context; fetch the same URL again to re-read it "
                "at no cost] [folded]"
            )


class DeepResearch:
    """Один прогон исследования по направлению."""

    def __init__(
        self,
        model: ToolModel,
        web: WebAccess,
        *,
        model_name: str,
        contact_email: str,
        github_token: str = "",
        reasoning_effort: str | None = None,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        """Модель, сеть и то, как представляться каталогам."""
        self._model = model
        self._web = web
        self._model_name = model_name
        self._contact_email = contact_email
        self._github_token = github_token
        self._reasoning_effort = reasoning_effort
        self._clock = clock

    def run(
        self, direction: str, targets: Sequence[str], window: Window, budget: Budget
    ) -> ResearchResult:
        """Провести исследование в пределах бюджета."""
        run = _Run(self, direction, list(targets), window, budget)
        try:
            return run.execute()
        finally:
            self._web.close()


class _Run:
    """Состояние одного прогона. Экземпляр живёт ровно одно исследование."""

    def __init__(
        self, owner: DeepResearch, direction: str, targets: list[str], window: Window, budget: Budget
    ) -> None:
        self.o = owner
        self.direction = direction
        self.targets = targets
        self.window = window
        self.budget = budget
        self.started = owner._clock()
        self.deadline = self.started + budget.time_budget_seconds
        #: Конец поиска и чтения: дальше время принадлежит записи.
        self.explore_deadline = self.deadline - min(budget.final_seconds, budget.time_budget_seconds / 3)
        #: Длительность каждого хода модели: по ней считается запас на завершение.
        self.turn_seconds: list[float] = []
        self.hits: dict[str, SearchHit] = {}
        self.reads: dict[str, _Read] = {}
        self.searches: dict[tuple[str, str], str] = {}
        self.search_counts: Counter[str] = Counter()
        self.search_by_source: dict[str, Counter[str]] = {}
        self.fetch_counts: Counter[str] = Counter()
        self.fetch_reasons: Counter[str] = Counter()
        self.technologies: dict[str, dict[str, object]] = {}
        self.rejected_names: list[dict[str, object]] = []
        self.trace: list[dict[str, object]] = []
        self.tokens = Counter[str]()
        self.iterations = 0
        #: Запись идёт из нескольких потоков: ведущая модель и регистратор.
        self.lock = threading.RLock()
        #: Прочитанные страницы, ещё не отданные регистратору, — ключи ``self.reads``.
        self.unrecorded: list[str] = []
        self.recorder_counts: Counter[str] = Counter()
        #: После итога запись закрыта: опоздавший ответ регистратора не меняет уже собранный ответ.
        self.closed = False

    # ── инструменты ─────────────────────────────────────────────────────────────────────────

    def _remaining(self) -> float:
        return self.deadline - self.o._clock()

    def tool_search(self, source: str, query: str) -> str:
        query = " ".join(str(query).split())[:200]
        key = (source, query.lower())
        if key in self.searches:
            return self.searches[key]
        per_source = self.search_by_source.setdefault(source, Counter())
        try:
            hits = search(
                source,
                query,
                web=self._web_access(),
                window=self.window,
                deadline=self.explore_deadline,
                contact_email=self.o._contact_email,
                github_token=self.o._github_token,
            )
        except SourceUnavailableError as error:
            self.search_counts["failed"] += 1
            per_source["failed"] += 1
            self.trace.append({"tool": "search", "source": source, "query": query, "outcome": "failed", "reason": str(error)})
            answer = f"source failed: {error}. This is an outage, not an empty result; try another source."
            self.searches[key] = answer
            return answer
        except Exception as error:  # сбой разбора внутри источника — тоже отказ, а не ноль
            self.search_counts["failed"] += 1
            per_source["failed"] += 1
            self.trace.append({"tool": "search", "source": source, "query": query, "outcome": "failed", "reason": type(error).__name__})
            return f"source failed: {type(error).__name__}; try another source."
        outcome = "ok" if hits else "empty"
        self.search_counts[outcome] += 1
        per_source[outcome] += 1
        self.trace.append({"tool": "search", "source": source, "query": query, "outcome": outcome, "hits": len(hits)})
        if not hits:
            answer = f"0 results from {source} for this query in the observation window."
            self.searches[key] = answer
            return answer
        lines = []
        for number, hit in enumerate(hits, 1):
            self.hits.setdefault(_canonical_url(hit.url), hit)
            when = hit.published.isoformat() if hit.published else "date unknown"
            snippet = f" — {hit.snippet}" if hit.snippet else ""
            lines.append(f"{number}. {hit.title} | {hit.url} | {when}{snippet}")
        answer = "\n".join(lines)
        self.searches[key] = answer
        return answer

    def _web_access(self) -> WebAccess:
        return self.o._web

    def _slice(self, read: _Read, start: int) -> str:
        text = read.page.text
        start = max(0, min(start, len(text)))
        end = min(len(text), start + self.budget.page_chars)
        when = (read.hit.published if read.hit and read.hit.published else read.page.published)
        header = (
            f"Title: {read.page.title or (read.hit.title if read.hit else '')}\n"
            f"URL: {read.page.url}\nPublished: {when.isoformat() if when else 'unknown'}\n"
            f"Characters {start}-{end} of {len(text)}\n\n"
        )
        tail = (
            f"\n\n[{len(text) - end} more characters; call fetch with start_index={end} to continue]"
            if end < len(text)
            else ""
        )
        return header + text[start:end] + tail

    def tool_fetch(self, url: str, reason: str, start_index: int = 0) -> str:
        url = str(url).strip()
        canonical = _canonical_url(url)
        if canonical in self.reads:
            return self._slice(self.reads[canonical], int(start_index or 0))
        if self.fetch_counts["ok"] >= self.budget.max_fetches:
            self.fetch_counts["skipped"] += 1
            self.trace.append({"tool": "fetch", "url": url, "reason": reason, "outcome": "skipped", "why": "page budget"})
            return "skipped: the page budget of this run is exhausted. Record what you have found."
        if self.explore_deadline - self.o._clock() <= 5:
            self.fetch_counts["skipped"] += 1
            self.trace.append({"tool": "fetch", "url": url, "reason": reason, "outcome": "skipped", "why": "time budget"})
            return "skipped: the time budget of this run is exhausted."
        hit = self.hits.get(canonical)
        outcome: FetchOutcome | None = None
        try:
            catalog = catalog_reader(
                url,
                web=self._web_access(),
                deadline=self.explore_deadline,
                contact_email=self.o._contact_email,
                github_token=self.o._github_token,
            )
        except SourceUnavailableError as error:
            # API каталога не ответило — страница читается как страница, с теми же правилами
            # robots.txt. Живой прогон 2026-09-27: API GitHub без токена исчерпало часовой лимит
            # (403), и треть чтений отказывала, хотя сами страницы репозиториев открыты.
            catalog = None
            catalog_error = str(error)
        else:
            catalog_error = ""
        if catalog is not None:
            page, catalog_hit = catalog
            hit = hit or catalog_hit
            if page is not None:
                outcome = FetchOutcome("ok", "", page)
        if outcome is None:
            outcome = self._web_access().read(url, self.explore_deadline)
            if catalog_error:
                self.fetch_counts["catalogFallback"] += 1
                if outcome.status != "ok":
                    outcome = FetchOutcome(outcome.status, f"{outcome.reason} (API каталога: {catalog_error})")
        self.fetch_counts[outcome.status] += 1
        if outcome.status != "ok":
            self.fetch_reasons[outcome.reason] += 1
        entry: dict[str, object] = {"tool": "fetch", "url": url, "reason": reason, "outcome": outcome.status}
        if outcome.status != "ok" or outcome.page is None:
            entry["why"] = outcome.reason
            self.trace.append(entry)
            word = {"refused": "refused", "skipped": "skipped"}.get(outcome.status, "failed")
            advice = (
                " The site forbids automated agents; do not try to work around it."
                if outcome.status == "refused"
                else ""
            )
            return f"{word}: {outcome.reason}.{advice}"
        page = outcome.page
        entry.update({"via": page.via, "characters": len(page.text), "sha256": page.sha256})
        self.trace.append(entry)
        origin = hit.source if hit else "link"
        read = _Read(page=page, hit=hit, reason=str(reason)[:500], origin=origin)
        with self.lock:
            self.reads[canonical] = read
            if _canonical_url(page.url) != canonical:
                self.reads.setdefault(_canonical_url(page.url), read)
            self.unrecorded.append(canonical)
        return self._slice(read, int(start_index or 0))

    def tool_record(
        self, name: str, urls: Sequence[str], evidence_quote: str, why: str, *, by: str = "agent"
    ) -> str:
        with self.lock:
            if self.closed:
                return "rejected: the run is over."
            return self._record(name, urls, evidence_quote, why, by)

    def _record(self, name: str, urls: Sequence[str], evidence_quote: str, why: str, by: str) -> str:
        name = " ".join(str(name).split())
        if not name or len(name) > 80 or not 1 <= len(name.split()) <= 8:
            self.rejected_names.append({"name": name, "reason": "name_shape"})
            return "rejected: a technology name is 1-8 words, at most 80 characters."
        supported: list[str] = []
        problems: list[str] = []
        for url in list(dict.fromkeys(str(u) for u in (urls or [])))[:8]:
            read = self.reads.get(_canonical_url(url))
            if read is None:
                problems.append(f"{url}: not fetched in this run")
                continue
            if not name_supported(name, read.page.title + "\n" + read.page.text):
                problems.append(f"{url}: the page does not contain the name")
                continue
            supported.append(read.page.url)
        if not supported:
            self.rejected_names.append({"name": name, "reason": "no_supporting_page", "problems": problems})
            self.trace.append({"tool": "record_technology", "name": name, "outcome": "rejected", "problems": problems, "by": by})
            return "rejected: " + "; ".join(problems or ["no urls"]) + ". Cite pages you fetched that name it."
        key = _normalize(name).strip()
        record = self.technologies.get(key)
        if record is None:
            record = {"name": name, "urls": [], "why": str(why)[:500], "quote": str(evidence_quote)[:300], "by": by}
            self.technologies[key] = record
        urls_list: list[str] = record["urls"]  # type: ignore[assignment]
        for url in supported:
            if url not in urls_list:
                urls_list.append(url)
            read = self.reads[_canonical_url(url)]
            if record["name"] not in read.supports:
                read.supports.append(str(record["name"]))
        self.trace.append({
            "tool": "record_technology", "name": name, "outcome": "recorded", "urls": supported, "by": by,
            "atSeconds": round(self.o._clock() - self.started, 1),
        })
        note = f" Not accepted: {'; '.join(problems)}." if problems else ""
        return f"recorded '{name}' with {len(supported)} supporting page(s).{note}"

    # ── цикл ────────────────────────────────────────────────────────────────────────────────

    def _call_tool(self, call: dict[str, Any], allowed: set[str], *, by: str = "agent") -> str:
        function = call.get("function") or {}
        name = function.get("name", "")
        try:
            arguments = json.loads(function.get("arguments") or "{}")
        except ValueError:
            return "error: arguments are not valid JSON."
        if name not in allowed:
            return f"error: tool {name} is not available now; only {', '.join(sorted(allowed))}."
        try:
            if name == "search":
                return self.tool_search(str(arguments.get("source", "")), str(arguments.get("query", "")))
            if name == "fetch":
                return self.tool_fetch(
                    str(arguments.get("url", "")), str(arguments.get("reason", "")), int(arguments.get("start_index") or 0)
                )
            if name == "record_technology":
                urls = arguments.get("urls") or []
                return self.tool_record(
                    str(arguments.get("name", "")),
                    [str(u) for u in (urls if isinstance(urls, list) else [urls])],
                    str(arguments.get("evidence_quote", "")),
                    str(arguments.get("why", "")),
                    by=by,
                )
        except Exception as error:
            log.warning("research.tool_error", tool=name, error=repr(error))
            return f"error: {type(error).__name__}"
        return f"error: unknown tool {name}"

    # ── регистратор ─────────────────────────────────────────────────────────────────────────

    def _take_unrecorded(self, *, force: bool) -> list[list[str]]:
        """Пачки прочитанных страниц для регистратора; без ``force`` — только полные."""
        size = max(1, self.budget.record_batch)
        with self.lock:
            if not self.unrecorded or (not force and len(self.unrecorded) < size):
                return []
            taken, self.unrecorded = self.unrecorded, []
        return [taken[i : i + size] for i in range(0, len(taken), size)]

    def _record_pass(self, keys: list[str]) -> None:
        """Один вызов регистратора: начала страниц пачки и только ``record_technology``."""
        pages = []
        for key in keys:
            read = self.reads[key]
            title = read.page.title or (read.hit.title if read.hit else "")
            pages.append((read.page.url, title, read.page.text[: self.budget.recorder_page_chars]))
        messages: list[dict[str, object]] = [
            {"role": "system", "content": RECORDER_PROMPT.format(direction=self.direction)},
            {"role": "user", "content": recorder_message(pages)},
        ]
        record_only = [tool for tool in TOOLS if tool["function"]["name"] == "record_technology"]  # type: ignore[index]
        timeout = max(5.0, min(90.0, self.deadline - self.o._clock() - 2.0))
        try:
            message, usage = self.o._model.chat_with_tools(
                messages, record_only, timeout=timeout, reasoning_effort=self.o._reasoning_effort
            )
        except Exception as error:
            with self.lock:
                self.recorder_counts["failed"] += 1
            log.warning("research.recorder_failed", error=f"{type(error).__name__}: {error}"[:300], pages=len(keys))
            return
        with self.lock:
            self.tokens.update(usage)
            self.recorder_counts["ok"] += 1
        raw_calls = message.get("tool_calls")
        for call in list(raw_calls) if isinstance(raw_calls, list) else []:
            self._call_tool(call, {"record_technology"}, by="recorder")

    def _submit_records(self, pool: ThreadPoolExecutor, pending: list[Future[None]], *, force: bool) -> None:
        for batch in self._take_unrecorded(force=force):
            pending.append(pool.submit(self._record_pass, batch))

    # ── цикл ────────────────────────────────────────────────────────────────────────────────

    def execute(self) -> ResearchResult:
        budget = self.budget
        system = SYSTEM_PROMPT.format(
            min_sources=budget.min_sources,
            max_fetches=budget.max_fetches,
            minutes=max(1, round(budget.time_budget_seconds / 60)),
        )
        messages: list[dict[str, object]] = [
            {"role": "system", "content": system},
            {
                "role": "user",
                "content": task_message(
                    self.direction, self.targets, self.window.start.isoformat(), self.window.end.isoformat()
                ),
            },
        ]
        all_tools = {"search", "fetch", "record_technology"}
        nudged = False
        report = ""
        stop_reason = "iterations"
        llm_error: str | None = None
        recorder = ThreadPoolExecutor(max_workers=2)
        recording: list[Future[None]] = []
        try:
            with ThreadPoolExecutor(max_workers=4) as pool:
                while self.iterations < budget.max_iterations:
                    left = self.explore_deadline - self.o._clock()
                    # Ход, который заведомо не кончится до конца поиска, не начинается: его
                    # результат опоздал бы, а время ушло бы у записи.
                    if left <= 5 or (self.turn_seconds and left < max(self.turn_seconds)):
                        stop_reason = "time"
                        break
                    if self.fetch_counts["ok"] >= budget.max_fetches:
                        stop_reason = "pages"
                        break
                    _compact(messages, keep=budget.keep_full_results)
                    turn_started = self.o._clock()
                    try:
                        message, usage = self.o._model.chat_with_tools(
                            messages,
                            TOOLS,
                            timeout=max(10.0, min(120.0, left)),
                            reasoning_effort=self.o._reasoning_effort,
                        )
                    except Exception as error:
                        llm_error = f"{type(error).__name__}: {error}"[:300]
                        log.warning("research.model_failed", error=llm_error, iteration=self.iterations)
                        stop_reason = "model_error"
                        break
                    self.iterations += 1
                    self.turn_seconds.append(self.o._clock() - turn_started)
                    with self.lock:
                        self.tokens.update(usage)
                    raw_calls = message.get("tool_calls")
                    calls: list[dict[str, Any]] = list(raw_calls) if isinstance(raw_calls, list) else []
                    assistant: dict[str, object] = {"role": "assistant", "content": message.get("content") or ""}
                    if calls:
                        assistant["tool_calls"] = calls
                    messages.append(assistant)
                    if not calls:
                        report = str(message.get("content") or "")
                        fetched = self.fetch_counts["ok"]
                        if not nudged and fetched < budget.min_sources and left > budget.final_seconds * 2:
                            nudged = True
                            messages.append(
                                {
                                    "role": "user",
                                    "content": (
                                        f"You have read {fetched} pages; the minimum is {budget.min_sources}. "
                                        "Continue the research: search other sources and sub-areas, fetch "
                                        "and record."
                                    ),
                                }
                            )
                            continue
                        stop_reason = "model_finished"
                        break
                    futures = [pool.submit(self._call_tool, call, all_tools) for call in calls]
                    for call, future in zip(calls, futures, strict=True):
                        messages.append(
                            {"role": "tool", "tool_call_id": call.get("id", ""), "content": future.result()}
                        )
                    self._submit_records(recorder, recording, force=False)
            # Поиск окончен: всё прочитанное, что ещё не у регистратора, уходит ему, и остаток
            # бюджета — ожидание записи.
            self._submit_records(recorder, recording, force=True)
            wait_futures(recording, timeout=max(0.0, self._remaining() - 2.0))
        finally:
            with self.lock:
                self.closed = True
                late = sum(1 for future in recording if not future.done())
            recorder.shutdown(wait=False, cancel_futures=True)
        if late:
            self.recorder_counts["late"] += late
        return self._result(report, stop_reason, llm_error)

    # ── итог ────────────────────────────────────────────────────────────────────────────────

    def _result(self, report: str, stop_reason: str, llm_error: str | None) -> ResearchResult:
        documents: list[ResearchDocument] = []
        dropped: Counter[str] = Counter()
        seen: set[int] = set()
        for read in self.reads.values():
            if id(read) in seen or not read.supports:
                continue
            seen.add(id(read))
            hit = read.hit
            published = (hit.published if hit and hit.published else None) or read.page.published
            if published is None:
                dropped["no_date"] += 1
                continue
            if not self.window.contains(published):
                dropped["outside_window"] += 1
                continue
            host = urlsplit(read.page.url).netloc.lower().removeprefix("www.")
            source_class = hit.source_class if hit else "NEWS"
            authors = hit.authors if hit and hit.authors else ()
            organization = hit.organization if hit else None
            is_company = hit.organization_is_company if hit else False
            if not authors and source_class == "NEWS":
                # Страница без авторов — свидетельство площадки: организация — её хост, как у
                # отраслевых изданий. Иначе BRULE-1 не увидит, что десять страниц одного сайта —
                # одна организация.
                authors, organization, is_company = (host,), host, True
            title = read.page.title or (hit.title if hit else "") or host
            documents.append(
                ResearchDocument(
                    url=read.page.url,
                    title=title[:1000],
                    published_on=published,
                    source_class=source_class,
                    origin=read.origin,
                    via=read.page.via,
                    host=host,
                    language=_language(read.page.text),
                    excerpt=_excerpt(read.page.text, read.supports),
                    sha256=read.page.sha256,
                    fetched_at=read.page.fetched_at,
                    http_status=read.page.status,
                    authors=tuple(a for a in authors if a)[:30],
                    organization=organization,
                    organization_is_company=is_company,
                    doi=hit.doi if hit else None,
                    arxiv_id=hit.arxiv_id if hit else None,
                    venue=hit.venue if hit else None,
                    read_reason=read.reason,
                    technologies=tuple(read.supports),
                )
            )
        emitted_names = {name for document in documents for name in document.technologies}
        technologies = [
            {**record, "emitted": record["name"] in emitted_names} for record in self.technologies.values()
        ]
        attempted = sum(self.fetch_counts[k] for k in ("ok", "refused", "failed"))
        searches = sum(self.search_counts.values())
        answered = self.search_counts["ok"] + self.search_counts["empty"]
        wall = round(self.o._clock() - self.started, 1)
        stats: dict[str, object] = {
            "model": self.o._model_name,
            "iterations": self.iterations,
            "searches": searches,
            "searchesOk": self.search_counts["ok"],
            "searchesEmpty": self.search_counts["empty"],
            "searchesFailed": self.search_counts["failed"],
            "searchesBySource": {s: dict(c) for s, c in self.search_by_source.items()},
            "pagesFetched": self.fetch_counts["ok"],
            "pagesRefused": self.fetch_counts["refused"],
            "pagesFailed": self.fetch_counts["failed"],
            "pagesSkipped": self.fetch_counts["skipped"],
            "catalogFallbacks": self.fetch_counts["catalogFallback"],
            "failedFetchShare": round(self.fetch_counts["failed"] / attempted, 3) if attempted else 0.0,
            "fetchProblems": dict(self.fetch_reasons.most_common(12)),
            "documentsEmitted": len(documents),
            "documentsDropped": dict(dropped),
            "namesRecorded": len(self.technologies),
            "namesRejected": len(self.rejected_names),
            "namesWithDocuments": len(emitted_names),
            "firstNameAtSeconds": next(
                (e["atSeconds"] for e in self.trace if e.get("outcome") == "recorded" and "atSeconds" in e), None
            ),
            "namesByRecorder": sum(1 for record in self.technologies.values() if record.get("by") == "recorder"),
            "recorderPasses": self.recorder_counts["ok"],
            "recorderFailed": self.recorder_counts["failed"],
            "recorderLate": self.recorder_counts["late"],
            "promptTokens": self.tokens["prompt_tokens"],
            "completionTokens": self.tokens["completion_tokens"],
            "wallSeconds": wall,
            "stopReason": stop_reason,
        }
        if llm_error:
            stats["modelError"] = llm_error
        # Отказ и ноль различаются. Отказ — модель не ответила ни разу, ни один поиск не получил
        # ответа или ни одна попытка чтения не удалась: у такого прогона нет права сказать
        # «ничего не нашлось». Ноль — всё отвечало, но подтверждённых имён нет.
        #
        # Прочитанные страницы без единого записанного имени — тоже отказ, а не ноль: модель не
        # дошла до записи, и «ничего не нашлось» было бы неправдой. Именно так финтех на стенде
        # отдал ноль документов после 24 прочитанных страниц со статусом ok (разбор 106).
        # Прогон, выпустивший документы, отказом не бывает: каждый из них — прочитанная страница с
        # проверенным именем, чем бы ни кончились остальные поиски.
        failed = not documents and (
            self.iterations == 0
            or searches == 0
            or answered == 0
            or (attempted > 0 and self.fetch_counts["ok"] == 0)
            or (self.fetch_counts["ok"] > 0 and not self.technologies and not self.rejected_names)
        )
        status = "failed" if failed else "ok"
        log.info("research.finished", direction=self.direction, status=status, **{
            k: v for k, v in stats.items() if isinstance(v, int | float | str)
        })
        return ResearchResult(
            status=status,
            documents=documents,
            technologies=technologies,
            trace=self.trace + [{"rejectedName": item} for item in self.rejected_names],
            stats=stats,
            report=report[:6000],
        )
