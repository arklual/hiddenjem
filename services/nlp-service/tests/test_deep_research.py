"""Глубокое исследование без сети: robots.txt, проверка имени, цикл агента на записанных ответах.

Живой прогон против Luna и настоящих каталогов описан в разборе 102; здесь — то, что обязано
держаться при любой модели: имя без прочитанной страницы не записывается, документ корпуса
собирается только из полученного текста, отказ источника не выглядит как ноль, а запрет
``robots.txt`` ИИ-краулерам — отказ, а не повод искать обход.
"""

from __future__ import annotations

import json
from collections.abc import Iterator, Sequence
from datetime import date
from pathlib import Path

import httpx
import pytest

from horizon_nlp.research.agent import Budget, DeepResearch, name_supported
from horizon_nlp.research.sources import Window
from horizon_nlp.research.web import HostPacer, RobotsRules, WebAccess, extract_page

FIXTURES = Path(__file__).parent / "fixtures" / "research"
WINDOW = Window(date(2021, 1, 1), date(2026, 9, 27))


# ── robots.txt ──────────────────────────────────────────────────────────────────────────────


def test_a_disallow_naming_an_ai_crawler_refuses_even_when_our_agent_is_allowed() -> None:
    rules = RobotsRules("User-agent: ClaudeBot\nDisallow: /\n\nUser-agent: *\nAllow: /\n")

    assert rules.allows("HorizonBot", "/articles/x")
    assert rules.names("ClaudeBot")
    assert not rules.allows("ClaudeBot", "/articles/x")


def test_wildcards_and_end_anchor_are_honoured() -> None:
    """``urllib.robotparser`` читает ``/*.atom$`` буквально; здесь — как шаблон."""
    rules = RobotsRules("User-agent: *\nDisallow: /*.atom$\nAllow: /search/feed/\nDisallow: /search\n")

    assert not rules.allows("HorizonBot", "/owner/repo/releases.atom")
    assert rules.allows("HorizonBot", "/owner/repo/releases.atom?x=1")
    assert rules.allows("HorizonBot", "/search/feed/rss2/")
    assert not rules.allows("HorizonBot", "/search?q=x")


def test_crawl_delay_is_read_for_the_agent_group() -> None:
    rules = RobotsRules("User-agent: *\nCrawl-delay: 10\nDisallow: /private\n")

    assert rules.crawl_delay("HorizonBot") == 10.0


def _web(handler) -> WebAccess:
    client = httpx.Client(transport=httpx.MockTransport(handler))
    return WebAccess(client, HostPacer(default_interval=0.0), "HorizonBot/1.0 (+mailto:test@example.org)")


def test_a_page_refused_by_robots_is_never_requested() -> None:
    requested: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requested.append(str(request.url))
        if request.url.path == "/robots.txt":
            return httpx.Response(200, text="User-agent: anthropic-ai\nDisallow: /\n")
        return httpx.Response(200, text="<html><body>secret</body></html>")

    outcome = _web(handler).read("https://closed.example/article", deadline=1e12)

    assert outcome.status == "refused"
    assert "anthropic-ai" in outcome.reason
    assert requested == ["https://closed.example/robots.txt"]


def test_a_bot_check_page_is_a_failure_not_a_page() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/robots.txt":
            return httpx.Response(404)
        return httpx.Response(403, text="<html><title>Just a moment...</title>cf-chl</html>")

    outcome = _web(handler).read("https://guarded.example/a", deadline=1e12)

    assert outcome.status == "failed"
    assert "робота" in outcome.reason


def test_the_page_date_comes_from_its_metadata() -> None:
    html = (FIXTURES / "edgeir-article.html").read_text(encoding="utf-8")

    title, text, published = extract_page(html)

    assert title.startswith("Startup ships neuromorphic edge chips")
    assert published == date(2026, 4, 13)
    assert "subscribe" not in text.lower(), "навигация и формы не попадают в текст"


# ── проверка имени ──────────────────────────────────────────────────────────────────────────


@pytest.mark.parametrize(
    ("name", "expected"),
    [
        ("neuromorphic edge chip", True),  # множественное число на странице
        ("split computing", True),
        ("photonic inference processors", False),  # слова «photonic» на странице нет
        ("AI", False),  # слишком коротко, чтобы быть свидетельством
    ],
)
def test_a_name_counts_only_if_the_page_contains_it(name: str, expected: bool) -> None:
    text = "The startup ships neuromorphic edge chips and supports split computing with the cloud."

    assert name_supported(name, text) is expected


# ── цикл агента ─────────────────────────────────────────────────────────────────────────────


class ScriptedModel:
    """Модель, отвечающая заранее записанными ходами. Проверяется цикл, а не модель.

    Ходы ведущей модели и ответы регистратора — два разных сценария: регистратор работает в своём
    потоке, и общий порядок ходов в тесте был бы гонкой.
    """

    def __init__(
        self, turns: Sequence[dict[str, object]], recorder: Sequence[dict[str, object]] = ()
    ) -> None:
        """Запомнить ходы по порядку."""
        self._turns: Iterator[dict[str, object]] = iter(turns)
        self._recorder: Iterator[dict[str, object]] = iter(recorder)
        self.seen: list[list[dict[str, object]]] = []
        self.recorder_seen: list[list[dict[str, object]]] = []

    def chat_with_tools(self, messages, tools, *, timeout, max_completion_tokens=4096, reasoning_effort=None):
        if str(messages[0]["content"]).startswith("You are the recorder"):
            self.recorder_seen.append([dict(m) for m in messages])
            return next(self._recorder, {"content": None, "tool_calls": []}), {"prompt_tokens": 10}
        self.seen.append([dict(m) for m in messages])
        return next(self._turns), {"prompt_tokens": 100, "completion_tokens": 10}


def _call(identifier: str, tool: str, **arguments: object) -> dict[str, object]:
    return {"id": identifier, "type": "function", "function": {"name": tool, "arguments": json.dumps(arguments)}}


def _catalogue(request: httpx.Request) -> httpx.Response:
    host, path = request.url.host, request.url.path
    if path == "/robots.txt":
        if host == "closed.example":
            return httpx.Response(200, text="User-agent: ClaudeBot\nDisallow: /\n")
        return httpx.Response(404)
    if host == "export.arxiv.org":
        return httpx.Response(200, text=(FIXTURES / "arxiv-search.xml").read_text(encoding="utf-8"))
    if host == "www.edgeir.com":
        return httpx.Response(
            200,
            text=(FIXTURES / "edgeir-article.html").read_text(encoding="utf-8"),
            headers={"content-type": "text/html; charset=utf-8"},
        )
    if host == "api.semanticscholar.org":
        return httpx.Response(429, json={"message": "Too Many Requests"})
    return httpx.Response(404)


def _research(model: ScriptedModel) -> DeepResearch:
    return DeepResearch(model, _web(_catalogue), model_name="gpt-5.6-luna", contact_email="test@example.org")


def test_only_fetched_pages_that_name_the_technology_become_documents() -> None:
    model = ScriptedModel(
        [
            {"content": None, "tool_calls": [
                _call("1", "search", source="arxiv", query="neuromorphic edge"),
                _call("2", "search", source="semanticscholar", query="neuromorphic edge"),
            ]},
            {"content": None, "tool_calls": [
                _call("3", "fetch", url="https://arxiv.org/abs/2604.01234v1", reason="primary paper"),
                _call("4", "fetch", url="https://www.edgeir.com/neuromorphic-20260413", reason="first product"),
                _call("5", "fetch", url="https://closed.example/story", reason="news"),
            ]},
            {"content": None, "tool_calls": [
                _call("6", "record_technology", name="neuromorphic edge chips",
                      urls=["https://arxiv.org/abs/2604.01234", "https://www.edgeir.com/neuromorphic-20260413"],
                      evidence_quote="neuromorphic edge chips", why="first silicon"),
                _call("7", "record_technology", name="photonic inference processors",
                      urls=["https://www.edgeir.com/neuromorphic-20260413"], evidence_quote="x", why="y"),
                _call("8", "record_technology", name="quantum edge accelerators",
                      urls=["https://nowhere.example/never-fetched"], evidence_quote="x", why="y"),
            ]},
            {"content": "Recorded: neuromorphic edge chips.", "tool_calls": []},
        ]
    )

    result = _research(model).run("периферийные вычисления", ["edge computing"], WINDOW, Budget(min_sources=1))

    assert result.status == "ok"
    assert [t["name"] for t in result.technologies] == ["neuromorphic edge chips"]
    assert {d.url for d in result.documents} == {
        "https://arxiv.org/abs/2604.01234",
        "https://www.edgeir.com/neuromorphic-20260413",
    }
    paper = next(d for d in result.documents if d.source_class == "PREPRINT")
    assert paper.via == "arxiv-api"
    assert paper.published_on == date(2026, 4, 2)
    assert paper.authors == ("Ada Lovelace", "Alan Turing")
    news = next(d for d in result.documents if d.source_class == "NEWS")
    assert news.organization == "edgeir.com", "страница без авторов — свидетельство площадки"
    for document in result.documents:
        assert "neuromorphic edge chips" in document.excerpt.lower()
        assert "Recorded" not in document.excerpt, "отчёт модели свидетельством не становится"
        assert document.read_reason
    stats = result.stats
    assert stats["searchesOk"] == 1 and stats["searchesFailed"] == 1
    assert stats["pagesFetched"] == 2 and stats["pagesRefused"] == 1
    assert stats["namesRecorded"] == 1 and stats["namesRejected"] == 2
    assert stats["documentsEmitted"] == 2
    tool_answers = [m["content"] for m in model.seen[1] if m.get("role") == "tool"]
    assert any(str(a).startswith("source failed") for a in tool_answers), "отказ назван отказом"


def test_a_run_where_every_search_failed_is_a_failure_not_zero() -> None:
    model = ScriptedModel(
        [
            {"content": None, "tool_calls": [_call("1", "search", source="semanticscholar", query="x y")]},
            {"content": "Nothing found.", "tool_calls": []},
        ]
    )

    result = _research(model).run("финтех", [], WINDOW, Budget(min_sources=1))

    assert result.status == "failed"
    assert result.documents == []


def test_pages_read_without_a_single_recorded_name_are_a_failure_not_zero() -> None:
    # Финтех на стенде: 24 прочитанные страницы, до записи модель не дошла, а прогон назвался
    # успешным с нулём документов — отсутствие ответа выглядело как «ничего не нашлось».
    model = ScriptedModel(
        [
            {"content": None, "tool_calls": [_call("1", "search", source="arxiv", query="neuromorphic edge")]},
            {"content": None, "tool_calls": [
                _call("2", "fetch", url="https://arxiv.org/abs/2604.01234v1", reason="primary paper"),
            ]},
            {"content": "I ran out of time.", "tool_calls": []},
        ]
    )

    result = _research(model).run("финтех", [], WINDOW, Budget(min_sources=1))

    assert result.stats["pagesFetched"] == 1
    assert result.status == "failed"


def test_the_recorder_records_what_the_agent_never_got_to() -> None:
    # Робототехника на стенде 27 сентября: 16–20 прочитанных страниц, ходы по 47 с в раздутом
    # контексте, время вышло до записи — ноль имён. Регистратор читает начала страниц сам.
    model = ScriptedModel(
        [
            {"content": None, "tool_calls": [_call("1", "search", source="arxiv", query="neuromorphic edge")]},
            {"content": None, "tool_calls": [
                _call("2", "fetch", url="https://arxiv.org/abs/2604.01234v1", reason="primary paper"),
                _call("3", "fetch", url="https://www.edgeir.com/neuromorphic-20260413", reason="first product"),
            ]},
            {"content": "I ran out of time.", "tool_calls": []},
        ],
        recorder=[
            {"content": None, "tool_calls": [
                _call("r1", "record_technology", name="neuromorphic edge chips",
                      urls=["https://arxiv.org/abs/2604.01234", "https://www.edgeir.com/neuromorphic-20260413"],
                      evidence_quote="neuromorphic edge chips", why="first silicon"),
                _call("r2", "record_technology", name="quantum edge accelerators",
                      urls=["https://arxiv.org/abs/2604.01234"], evidence_quote="x", why="y"),
            ]},
        ],
    )

    result = _research(model).run("робототехника", [], WINDOW, Budget(min_sources=1))

    assert result.status == "ok"
    assert [t["name"] for t in result.technologies] == ["neuromorphic edge chips"]
    assert result.technologies[0]["by"] == "recorder"
    assert len(result.documents) == 2
    assert result.stats["namesByRecorder"] == 1 and result.stats["recorderPasses"] == 1
    assert result.stats["namesRejected"] == 1, "регистратор проверяется так же, как ведущая модель"
    pages = str(model.recorder_seen[0][1]["content"])
    assert "https://arxiv.org/abs/2604.01234" in pages and "neuromorphic-20260413" in pages
    assert len(model.recorder_seen[0]) == 2, "регистратор не видит истории диалога"


def test_a_full_batch_of_pages_goes_to_the_recorder_before_the_end() -> None:
    model = ScriptedModel(
        [
            {"content": None, "tool_calls": [
                _call("1", "fetch", url="https://arxiv.org/abs/2604.01234v1", reason="primary paper"),
                _call("2", "fetch", url="https://www.edgeir.com/neuromorphic-20260413", reason="first product"),
            ]},
            {"content": None, "tool_calls": [_call("3", "search", source="arxiv", query="other")]},
            {"content": "done", "tool_calls": []},
        ]
    )

    result = _research(model).run("edge", [], WINDOW, Budget(min_sources=1, record_batch=2))

    assert result.stats["recorderPasses"] == 1, "пачка ушла сразу, и в конце дописывать нечего"
    assert len(model.recorder_seen) == 1


def test_search_stops_before_the_time_that_belongs_to_recording() -> None:
    # Ход модели идёт 100 с, бюджет 330, запись — последние 60. После двух ходов до конца поиска
    # остаётся 70 с: третий ход не поместился бы, и он не начинается — время остаётся записи.
    now = [0.0]

    class SlowModel(ScriptedModel):
        def chat_with_tools(self, messages, tools, **kwargs):
            if not str(messages[0]["content"]).startswith("You are the recorder"):
                now[0] += 100.0
            return super().chat_with_tools(messages, tools, **kwargs)

    model = SlowModel(
        [
            {"content": None, "tool_calls": [_call("1", "search", source="arxiv", query="neuromorphic edge")]},
            {"content": None, "tool_calls": [
                _call("2", "fetch", url="https://arxiv.org/abs/2604.01234v1", reason="primary paper"),
            ]},
            {"content": None, "tool_calls": [_call("3", "search", source="arxiv", query="never sent")]},
        ],
        recorder=[
            {"content": None, "tool_calls": [
                _call("r1", "record_technology", name="neuromorphic edge chips",
                      urls=["https://arxiv.org/abs/2604.01234"], evidence_quote="x", why="y"),
            ]},
        ],
    )
    research = DeepResearch(
        model, _web(_catalogue), model_name="gpt-5.6-luna", contact_email="test@example.org",
        clock=lambda: now[0],
    )

    result = research.run("edge", [], WINDOW, Budget(time_budget_seconds=330.0, final_seconds=60.0, min_sources=1))

    assert result.stats["iterations"] == 2
    assert result.stats["stopReason"] == "time"
    assert result.stats["namesRecorded"] == 1
    assert result.status == "ok"


def test_a_refusing_catalogue_api_falls_back_to_the_page_itself() -> None:
    """API GitHub без токена исчерпывает лимит (403) — страница репозитория читается как страница."""

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/robots.txt":
            return httpx.Response(404)
        if request.url.host == "api.github.com":
            return httpx.Response(403, json={"message": "API rate limit exceeded"})
        if request.url.host == "github.com":
            return httpx.Response(
                200,
                text=(FIXTURES / "edgeir-article.html").read_text(encoding="utf-8"),
                headers={"content-type": "text/html; charset=utf-8"},
            )
        return httpx.Response(404)

    model = ScriptedModel(
        [
            {"content": None, "tool_calls": [
                _call("1", "fetch", url="https://github.com/acme/spiking-core", reason="reference code"),
            ]},
            {"content": None, "tool_calls": [
                _call("2", "record_technology", name="neuromorphic edge chips",
                      urls=["https://github.com/acme/spiking-core"], evidence_quote="x", why="y"),
            ]},
            {"content": "done", "tool_calls": []},
        ]
    )
    research = DeepResearch(model, _web(handler), model_name="gpt-5.6-luna", contact_email="test@example.org")

    result = research.run("edge", [], WINDOW, Budget(min_sources=1))

    assert result.stats["pagesFetched"] == 1
    assert result.stats["catalogFallbacks"] == 1
    assert [d.via for d in result.documents] == ["html"]
