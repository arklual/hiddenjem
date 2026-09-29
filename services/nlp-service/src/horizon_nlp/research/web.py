"""Сеть исследователя: вежливость к хостам, robots.txt, чтение страницы в текст.

Всё, что агент глубокого исследования получает из интернета, проходит через этот модуль, и у него
три обязанности, которые нельзя переложить на модель.

**Вежливость.** Пауза на хост — своя для каждого (arXiv просит запрос в три секунды, отраслевые
издания держат ``Crawl-delay: 10``), и ``Crawl-delay`` из ``robots.txt`` может её только удлинить.
Параллельные вызовы инструментов к разным хостам идут одновременно, к одному — по очереди.

**Запрет — это отказ, а не препятствие.** Страница, которую ``robots.txt`` закрывает нашему агенту
или называет поимённо ИИ-краулеров (``AI_CRAWLER_AGENTS``), не читается. Зеркала, кэши, архивы и прокси в обход запрета не используются; страница
проверки на робота — отказ источника, а не задача. Модель получает слово «refused» и идёт к другому
источнику.

**Ноль и отказ различаются.** Каждое чтение заканчивается одним из четырёх исходов — ``ok``,
``refused``, ``failed``, ``skipped``, — и счёт по ним уходит в ответ. Отказ сети, который выглядит
как пустая страница, — ровно та ловушка молчаливо пустых признаков, из-за которой отчёт однажды
выглядел нормальным без половины источников.
"""

from __future__ import annotations

import contextlib
import hashlib
import json
import re
import threading
import time
from dataclasses import dataclass, field
from datetime import date, datetime
from html.parser import HTMLParser
from urllib.parse import urljoin, urlsplit

import httpx
import structlog

__all__ = [
    "AI_CRAWLER_AGENTS",
    "FetchOutcome",
    "HostPacer",
    "PageText",
    "RobotsRules",
    "WebAccess",
    "extract_page",
    "parse_date",
    "stable_json",
]

log = structlog.get_logger(__name__)

#: Имена ИИ-краулеров, запрет которым в ``robots.txt`` соблюдается как запрет нам самим.
AI_CRAWLER_AGENTS: tuple[str, ...] = (
    "ClaudeBot",
    "Claude-User",
    "Claude-SearchBot",
    "Claude-Code",
    "anthropic-ai",
)

#: Признаки страницы проверки на робота. Такая страница — отказ площадки, обходить её нельзя.
_BOT_CHECK = re.compile(
    r"cf-chl|challenge-platform|just a moment\.\.\.|attention required|captcha|"
    r"are you a robot|verify you are human|ddos-guard|access denied",
    re.IGNORECASE,
)

#: Потолок тела ответа: страница больше двух мегабайт — не статья, а приложение или архив.
_MAX_BYTES = 2_000_000

#: Сколько секунд ``Crawl-delay`` исследование готово ждать. Дольше — страница пропускается:
#: соблюсти такую паузу можно, только не читая хост вовсе в пределах бюджета времени.
_MAX_CRAWL_DELAY = 20.0


@dataclass
class HostPacer:
    """Минимальный интервал между запросами к одному хосту.

    Интервал по умолчанию — две секунды; для хостов с известными правилами — свой. ``Crawl-delay``
    из ``robots.txt`` повышает интервал хоста, но не понижает.
    """

    default_interval: float = 2.0
    intervals: dict[str, float] = field(default_factory=dict)
    _next: dict[str, float] = field(default_factory=dict)
    _locks: dict[str, threading.Lock] = field(default_factory=dict)
    _guard: threading.Lock = field(default_factory=threading.Lock)

    def raise_interval(self, host: str, seconds: float) -> None:
        """Удлинить интервал хоста (``Crawl-delay``); укоротить нельзя."""
        with self._guard:
            self.intervals[host] = max(self.interval(host), seconds)

    def interval(self, host: str) -> float:
        """Интервал хоста или его домена верхнего уровня, иначе — по умолчанию."""
        if host in self.intervals:
            return self.intervals[host]
        for known, seconds in self.intervals.items():
            if host.endswith("." + known):
                return seconds
        return self.default_interval

    def wait(self, host: str, deadline: float) -> bool:
        """Дождаться своей очереди к хосту. ``False`` — очередь наступит после дедлайна."""
        with self._guard:
            lock = self._locks.setdefault(host, threading.Lock())
        with lock:
            now = time.monotonic()
            ready = self._next.get(host, 0.0)
            if ready > deadline:
                return False
            if ready > now:
                time.sleep(ready - now)
            self._next[host] = time.monotonic() + self.interval(host)
        return True


class RobotsRules:
    """Правила ``robots.txt`` одного хоста: группы по агентам, самое длинное совпадение побеждает.

    Реализовано то же подмножество стандарта, что у ``RobotsPolicy`` сервиса сбора: ``User-agent``,
    ``Disallow``, ``Allow``, ``*`` и ``$`` в шаблонах, и вдобавок ``Crawl-delay``. Стандартный
    ``urllib.robotparser`` здесь не годится: он читает ``Disallow: /*.atom$`` как буквальный префикс
    и разрешил бы ровно то, что площадка запретила.
    """

    def __init__(self, text: str | None) -> None:
        """Разобрать файл; ``None`` — файла нет, ограничений нет."""
        self.groups: list[tuple[list[str], list[tuple[bool, str]], float | None]] = []
        if not text:
            return
        agents: list[str] = []
        rules: list[tuple[bool, str]] = []
        delay: float | None = None
        seen_rule = False
        for raw in text.splitlines():
            line = raw.split("#", 1)[0].strip()
            if ":" not in line:
                continue
            key, _, value = line.partition(":")
            key = key.strip().lower()
            value = value.strip()
            if key == "user-agent":
                if seen_rule:
                    self.groups.append((agents, rules, delay))
                    agents, rules, delay, seen_rule = [], [], None, False
                agents.append(value.lower())
            elif key in ("allow", "disallow"):
                seen_rule = True
                if value:
                    rules.append((key == "allow", value))
            elif key == "crawl-delay":
                seen_rule = True
                with contextlib.suppress(ValueError):
                    delay = float(value)
        if agents:
            self.groups.append((agents, rules, delay))

    def _group(self, agent: str) -> tuple[list[tuple[bool, str]], float | None] | None:
        token = agent.lower()
        specific = [g for g in self.groups if any(a != "*" and a in token for a in g[0])]
        if specific:
            rules = [rule for g in specific for rule in g[1]]
            delays = [g[2] for g in specific if g[2] is not None]
            return rules, max(delays) if delays else None
        generic = [g for g in self.groups if "*" in g[0]]
        if generic:
            rules = [rule for g in generic for rule in g[1]]
            delays = [g[2] for g in generic if g[2] is not None]
            return rules, max(delays) if delays else None
        return None

    def names(self, agent: str) -> bool:
        """Называет ли файл этого агента поимённо (а не через ``*``)."""
        token = agent.lower()
        return any(any(a != "*" and a in token for a in g[0]) for g in self.groups)

    def allows(self, agent: str, path: str) -> bool:
        """Разрешён ли путь агенту: самое длинное совпадение, при равенстве — ``Allow``."""
        group = self._group(agent)
        if group is None:
            return True
        best_length = -1
        allowed = True
        for allow, pattern in group[0]:
            if _robots_match(pattern, path):
                length = len(pattern)
                if length > best_length or (length == best_length and allow):
                    best_length = length
                    allowed = allow
        return allowed

    def crawl_delay(self, agent: str) -> float | None:
        """``Crawl-delay`` группы агента, если задан."""
        group = self._group(agent)
        return None if group is None else group[1]


def _robots_match(pattern: str, path: str) -> bool:
    regex = re.escape(pattern).replace(r"\*", ".*")
    if regex.endswith(r"\$"):
        regex = regex[:-2] + "$"
    return re.match(regex, path) is not None


@dataclass(frozen=True, slots=True)
class PageText:
    """То, что прочитано со страницы: заголовок, дата, текст и отпечаток полученных байтов."""

    url: str
    title: str
    text: str
    published: date | None
    status: int
    sha256: str
    fetched_at: str
    via: str = "html"


@dataclass(frozen=True, slots=True)
class FetchOutcome:
    """Исход чтения: ``ok``/``refused``/``failed``/``skipped`` и причина словами."""

    status: str
    reason: str
    page: PageText | None = None


class WebAccess:
    """Чтение страниц с соблюдением ``robots.txt`` и пауз на хост."""

    def __init__(self, client: httpx.Client, pacer: HostPacer, user_agent: str) -> None:
        """Держать один клиент, одну вежливость и кэш ``robots.txt`` на всё исследование."""
        self._client = client
        self._pacer = pacer
        self._agent = user_agent
        self._agent_token = user_agent.split("/", 1)[0]
        self._robots: dict[str, RobotsRules | None] = {}
        self._robots_lock = threading.Lock()

    def close(self) -> None:
        """Закрыть соединения: клиент живёт ровно один прогон."""
        self._client.close()

    @property
    def pacer(self) -> HostPacer:
        """Общая вежливость — ею же пользуются поисковые источники."""
        return self._pacer

    def get(self, url: str, deadline: float, *, headers: dict[str, str] | None = None,
            timeout: float = 20.0) -> httpx.Response:
        """GET к API с паузой на хост. Исключение — отказ источника, его ловит вызывающий."""
        host = _host(url)
        if not self._pacer.wait(host, deadline):
            raise TimeoutError("очередь к хосту наступит после окончания бюджета времени")
        remaining = max(1.0, min(timeout, deadline - time.monotonic()))
        merged = {"User-Agent": self._agent}
        merged.update(headers or {})
        return self._client.get(url, headers=merged, timeout=remaining, follow_redirects=True)

    def robots_verdict(self, url: str, deadline: float) -> tuple[bool, str]:
        """Можно ли читать страницу: ``(True, "")`` или ``(False, причина)``.

        Файл, который не отдаётся (404, 410), ограничений не ставит — так говорит стандарт.
        Файл, до которого не достучаться (сеть, 5xx), — по RFC 9309 «полный запрет»: читать сайт,
        правила которого мы не смогли узнать, значит надеяться, что запрета там нет.
        """
        parts = urlsplit(url)
        origin = f"{parts.scheme}://{parts.netloc}"
        with self._robots_lock:
            known = origin in self._robots
            rules = self._robots.get(origin)
        if not known:
            try:
                response = self.get(origin + "/robots.txt", deadline, timeout=10.0)
                if response.status_code >= 500:
                    rules = RobotsRules("User-agent: *\nDisallow: /")
                elif response.status_code >= 400:
                    rules = RobotsRules(None)
                else:
                    rules = RobotsRules(response.text[:500_000])
            except Exception:
                rules = RobotsRules("User-agent: *\nDisallow: /")
            with self._robots_lock:
                self._robots[origin] = rules
        assert rules is not None
        path = parts.path or "/"
        if parts.query:
            path += "?" + parts.query
        if not rules.allows(self._agent_token, path):
            return False, "robots.txt запрещает путь нашему агенту"
        for agent in AI_CRAWLER_AGENTS:
            if rules.names(agent) and not rules.allows(agent, path):
                return False, f"robots.txt запрещает путь агенту {agent}"
        delay = rules.crawl_delay(self._agent_token)
        if delay:
            if delay > _MAX_CRAWL_DELAY:
                return False, f"Crawl-delay {delay:g} с больше бюджета исследования"
            self._pacer.raise_interval(_host(url), delay)
        return True, ""

    def read(self, url: str, deadline: float) -> FetchOutcome:
        """Прочитать страницу целиком в текст, проверяя ``robots.txt`` на каждом переходе."""
        current = url
        for _ in range(5):
            if urlsplit(current).scheme not in ("http", "https"):
                return FetchOutcome("refused", "поддерживаются только http и https")
            allowed, reason = self.robots_verdict(current, deadline)
            if not allowed:
                return FetchOutcome("refused", reason)
            try:
                host = _host(current)
                if not self._pacer.wait(host, deadline):
                    return FetchOutcome("skipped", "бюджет времени исчерпан")
                remaining = max(1.0, min(20.0, deadline - time.monotonic()))
                with self._client.stream(
                    "GET",
                    current,
                    headers={"User-Agent": self._agent, "Accept": "text/html,text/plain;q=0.9,*/*;q=0.5"},
                    timeout=remaining,
                    follow_redirects=False,
                ) as response:
                    if response.is_redirect:
                        location = response.headers.get("location")
                        if not location:
                            return FetchOutcome("failed", "перенаправление без адреса")
                        current = urljoin(current, location)
                        continue
                    body = b""
                    for chunk in response.iter_bytes():
                        body += chunk
                        if len(body) > _MAX_BYTES:
                            return FetchOutcome("failed", "страница больше двух мегабайт")
                    status = response.status_code
                    content_type = response.headers.get("content-type", "").lower()
                    encoding = response.encoding or "utf-8"
            except Exception as error:
                return FetchOutcome("failed", f"сеть: {type(error).__name__}")
            text = body.decode(encoding, errors="replace")
            if status in (401, 403, 429, 503) and _BOT_CHECK.search(text[:20000]):
                return FetchOutcome("failed", f"HTTP {status}: проверка на робота, не обходится")
            if status >= 400:
                return FetchOutcome("failed", f"HTTP {status}")
            if "pdf" in content_type or body[:5] == b"%PDF-":
                return FetchOutcome("failed", "PDF не читается: нужна HTML-страница или аннотация")
            if content_type and not any(kind in content_type for kind in ("html", "text", "xml")):
                return FetchOutcome("failed", f"тип содержимого {content_type.split(';')[0]}")
            if _BOT_CHECK.search(text[:3000]) and len(text) < 20000:
                return FetchOutcome("failed", "страница проверки на робота, не обходится")
            title, extracted, published = extract_page(text) if "html" in content_type or "<html" in text[:2000].lower() else ("", text, None)
            if len(extracted.strip()) < 200:
                return FetchOutcome("failed", "страница без текста (вероятно, собирается скриптом)")
            return FetchOutcome(
                "ok",
                "",
                PageText(
                    url=current,
                    title=title,
                    text=extracted,
                    published=published,
                    status=status,
                    sha256=hashlib.sha256(body).hexdigest(),
                    fetched_at=datetime.now().astimezone().isoformat(timespec="seconds"),
                ),
            )
        return FetchOutcome("failed", "слишком много перенаправлений")


def _host(url: str) -> str:
    netloc = urlsplit(url).netloc.lower().split(":", 1)[0]
    return netloc


_DATE_META = (
    "article:published_time",
    "og:published_time",
    "citation_publication_date",
    "citation_online_date",
    "citation_date",
    "dc.date",
    "dc.date.issued",
    "dcterms.created",
    "date",
    "pubdate",
    "publishdate",
    "parsely-pub-date",
    "sailthru.date",
)

_SKIP_TAGS = frozenset(
    {"script", "style", "noscript", "nav", "header", "footer", "aside", "form", "svg", "button", "select", "template"}
)
_BLOCK_TAGS = frozenset(
    {"p", "div", "br", "li", "h1", "h2", "h3", "h4", "h5", "h6", "tr", "section", "article", "blockquote", "pre", "dd", "dt"}
)


class _Extractor(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []
        self.title = ""
        self.meta: dict[str, str] = {}
        self.jsonld: list[str] = []
        self.times: list[str] = []
        self._skip = 0
        self._in_title = False
        self._in_jsonld = False

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = {k.lower(): (v or "") for k, v in attrs}
        if tag == "meta":
            key = (values.get("property") or values.get("name") or values.get("itemprop") or "").lower()
            if key and values.get("content"):
                self.meta.setdefault(key, values["content"])
            return
        if tag == "time" and values.get("datetime"):
            self.times.append(values["datetime"])
        if tag == "script" and "ld+json" in values.get("type", ""):
            self._in_jsonld = True
            return
        if tag in _SKIP_TAGS:
            self._skip += 1
        elif tag == "title":
            self._in_title = True
        elif tag in _BLOCK_TAGS:
            self.parts.append("\n")

    def handle_endtag(self, tag: str) -> None:
        if tag == "script" and self._in_jsonld:
            self._in_jsonld = False
            return
        if tag in _SKIP_TAGS and self._skip:
            self._skip -= 1
        elif tag == "title":
            self._in_title = False
        elif tag in _BLOCK_TAGS:
            self.parts.append("\n")

    def handle_data(self, data: str) -> None:
        if self._in_jsonld:
            self.jsonld.append(data)
        elif self._in_title:
            self.title += data
        elif not self._skip:
            self.parts.append(data)


def extract_page(html: str) -> tuple[str, str, date | None]:
    """HTML → ``(заголовок, текст, дата публикации)``. Дата — из метаданных страницы, если есть."""
    parser = _Extractor()
    try:
        parser.feed(html)
        parser.close()
    except Exception:
        pass
    lines = [re.sub(r"[ \t\r\f\v ]+", " ", line).strip() for line in "".join(parser.parts).split("\n")]
    text = "\n".join(line for line in lines if line)
    title = (parser.meta.get("og:title") or parser.meta.get("citation_title") or parser.title).strip()
    published: date | None = None
    for key in _DATE_META:
        if key in parser.meta:
            published = parse_date(parser.meta[key])
            if published:
                break
    if published is None:
        for blob in parser.jsonld:
            match = re.search(r'"datePublished"\s*:\s*"([^"]+)"', blob)
            if match:
                published = parse_date(match.group(1))
                if published:
                    break
    if published is None and parser.times:
        published = parse_date(parser.times[0])
    return re.sub(r"\s+", " ", title), text, published


def parse_date(value: str | None) -> date | None:
    """Дата из распространённых форматов: ISO, ``YYYY/MM/DD``, RFC 822 лент, ``YYYY-MM``."""
    if not value:
        return None
    value = value.strip()
    match = re.match(r"(\d{4})[-/](\d{1,2})[-/](\d{1,2})", value)
    if match:
        try:
            return date(int(match.group(1)), int(match.group(2)), int(match.group(3)))
        except ValueError:
            return None
    try:
        from email.utils import parsedate_to_datetime

        return parsedate_to_datetime(value).date()
    except (TypeError, ValueError, IndexError):
        pass
    match = re.match(r"(\d{4})[-/](\d{1,2})$", value)
    if match:
        try:
            return date(int(match.group(1)), int(match.group(2)), 1)
        except ValueError:
            return None
    return None


def stable_json(value: object) -> bytes:
    """Канонический JSON — для отпечатка записи API, прочитанной вместо страницы."""
    return json.dumps(value, ensure_ascii=False, sort_keys=True).encode("utf-8")
