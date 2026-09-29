"""Кэш, темп, сборка и докачка — всё без сети.

Проверяется не «ходит ли код в интернет», а три обещания, на которых держится сбор: повторный
запрос не тратит квоту, отказ одного источника не уносит остальные, прерванный сбор продолжается
с того места, где встал.
"""

from __future__ import annotations

import json
import time
from datetime import date

import httpx
import pytest

from horizon_analytics.signals import collect as cli
from horizon_analytics.signals.cache import SignalCache
from horizon_analytics.signals.collector import (
    FAST_SOURCES,
    REQUESTS_PER_TERM,
    SLOW_SOURCES,
    SOURCE_NAMES,
    TermRequest,
    _SourceSpec,
    collect_signals,
    collect_term,
)
from horizon_analytics.signals.http import Fetcher, Pace, SourceUnavailableError
from horizon_analytics.signals.models import SourceResult, TermSignals

TODAY = date(2026, 9, 19)


def spec(name: str, run) -> _SourceSpec:
    return _SourceSpec(name=name, version=1, pace=Pace(0.0), workers=1, run=run)


class TestCache:
    def test_second_call_does_not_reach_the_source(self, tmp_path):
        cache = SignalCache(tmp_path)
        calls = []

        def run(_fetcher, term):
            calls.append(term)
            return {"works": 7}

        source = spec("openalex", run)
        first = collect_term(source, "speculative decoding", cache=cache, fetcher=None, today=TODAY)
        second = collect_term(
            source, "speculative decoding", cache=cache, fetcher=None, today=TODAY
        )

        assert calls == ["speculative decoding"]
        assert first.data["works"] == 7
        assert second.data["works"] == 7
        assert second.data["_cached"] is True

    def test_cache_key_separates_sources_and_terms(self, tmp_path):
        cache = SignalCache(tmp_path)
        assert cache.path_for("openalex", "a") != cache.path_for("arxiv", "a")
        assert cache.path_for("openalex", "a") != cache.path_for("openalex", "b")
        # Регистр и обрамляющие пробелы — не разные термины.
        assert cache.path_for("openalex", " Speculative Decoding ") == cache.path_for(
            "openalex", "speculative decoding"
        )

    def test_cached_record_keeps_the_day_it_was_collected(self, tmp_path):
        cache = SignalCache(tmp_path)
        source = spec("hackernews", lambda _f, _t: {"mentions": 3})
        collect_term(source, "x", cache=cache, fetcher=None, today=date(2026, 1, 2))
        again = collect_term(source, "x", cache=cache, fetcher=None, today=date(2026, 9, 19))
        assert again.collected_on == date(2026, 1, 2)

    def test_new_parser_version_invalidates_the_record(self, tmp_path):
        cache = SignalCache(tmp_path)
        cache.put("x", SourceResult("arxiv", True, TODAY, version=1, data={"a": 1}))
        assert cache.get("arxiv", "x", version=1) is not None
        assert cache.get("arxiv", "x", version=2) is None

    def test_failures_are_not_cached(self, tmp_path):
        cache = SignalCache(tmp_path)
        attempts = []

        def flaky(_fetcher, term):
            attempts.append(term)
            if len(attempts) == 1:
                raise SourceUnavailableError("HTTP 503")
            return {"ok": 1}

        source = spec("github", flaky)
        first = collect_term(source, "x", cache=cache, fetcher=None, today=TODAY)
        second = collect_term(source, "x", cache=cache, fetcher=None, today=TODAY)

        assert first.ok is False
        assert "503" in (first.error or "")
        # Сетевой сбой одного дня не должен навсегда застрять в выборке.
        assert second.ok is True
        assert attempts == ["x", "x"]

    def test_corrupt_file_is_refetched(self, tmp_path):
        cache = SignalCache(tmp_path)
        path = cache.path_for("arxiv", "x")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('{"term": "x", "ok": tr', encoding="utf-8")
        assert cache.get("arxiv", "x", version=1) is None

    def test_record_about_another_term_is_ignored(self, tmp_path):
        cache = SignalCache(tmp_path)
        path = cache.path_for("arxiv", "x")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps(
                {
                    "term": "совсем другое",
                    "source": "arxiv",
                    "ok": True,
                    "collected_on": "2026-09-19",
                    "version": 1,
                    "data": {"a": 1},
                    "error": None,
                }
            ),
            encoding="utf-8",
        )
        assert cache.get("arxiv", "x", version=1) is None


class TestRetries:
    """403 «превышен лимит» и 403 «доступ запрещён» должны разводиться по разным дорогам."""

    @staticmethod
    def fetcher(responses):
        queue_ = list(responses)

        def handler(_request):
            return queue_.pop(0)

        return Fetcher(
            pace=Pace(0.0),
            attempts=3,
            client=httpx.Client(transport=httpx.MockTransport(handler)),
        )

    def test_exhausted_github_quota_is_waited_out(self):
        throttled = httpx.Response(403, headers={"x-ratelimit-remaining": "0", "Retry-After": "0"})
        fetcher = self.fetcher([throttled, httpx.Response(200, json={"total_count": 5})])
        # GitHub отвечает на исчерпанную квоту 403, а не 429; сдаться здесь — потерять источник.
        assert fetcher.get("https://example.test/search").json() == {"total_count": 5}

    def test_plain_forbidden_is_not_retried(self):
        fetcher = self.fetcher([httpx.Response(403, json={"message": "Bad credentials"})])
        with pytest.raises(SourceUnavailableError, match="403"):
            fetcher.get("https://example.test/search")

    def test_server_error_is_retried_then_given_up_on(self):
        # Retry-After: 0 — источник сам сказал не ждать, иначе тест спал бы минуту.
        unavailable = [httpx.Response(503, headers={"Retry-After": "0"}) for _ in range(3)]
        fetcher = self.fetcher(unavailable)
        with pytest.raises(SourceUnavailableError, match="3 попыток"):
            fetcher.get("https://example.test/search")

    def test_backoff_doubles_and_is_capped(self):
        from horizon_analytics.signals.http import _backoff

        # Ровная пауза не помогает, когда квоту выбрал сосед по IP: нужно дать ей восстановиться.
        assert [_backoff(n) for n in (1, 2, 3, 4)] == [5.0, 10.0, 20.0, 40.0]
        assert _backoff(10) == 60.0

    def test_not_found_fails_at_once(self):
        fetcher = self.fetcher([httpx.Response(404)])
        with pytest.raises(SourceUnavailableError, match="404"):
            fetcher.get("https://example.test/missing")


class TestPace:
    def test_calls_are_spaced_by_the_interval(self):
        pace = Pace(0.05)
        started = time.monotonic()
        for _ in range(4):
            pace.wait()
        # Первый вызов проходит сразу, остальные три ждут по интервалу.
        assert time.monotonic() - started >= 0.15 - 0.02

    def test_defer_pushes_the_next_call_further(self):
        pace = Pace(0.0)
        pace.wait()
        pace.defer(0.08)
        started = time.monotonic()
        pace.wait()
        assert time.monotonic() - started >= 0.06


class TestSourceSets:
    def test_fast_set_excludes_the_rate_limited_sources(self):
        # Живой анализ сотни кандидатов не переживёт десяти запросов в минуту.
        assert set(FAST_SOURCES) | set(SLOW_SOURCES) == set(SOURCE_NAMES)
        assert not set(FAST_SOURCES) & set(SLOW_SOURCES)
        assert set(SLOW_SOURCES) == {"arxiv", "github"}

    def test_fast_sources_cost_at_most_three_requests_per_term(self):
        assert all(REQUESTS_PER_TERM[name] <= 3 for name in FAST_SOURCES)
        assert all(REQUESTS_PER_TERM[name] >= 9 for name in SLOW_SOURCES)


class TestCollection:
    def test_terms_come_back_in_input_order(self, tmp_path):
        terms = [TermRequest("a", area="ai"), TermRequest("b"), TermRequest("c", area="hw")]
        ready: list[str] = []
        result = collect_signals(
            terms,
            cache_dir=tmp_path,
            sources=["patents"],
            today=TODAY,
            on_term=lambda signals: ready.append(signals.term),
        )
        assert [signals.term for signals in result] == ["a", "b", "c"]
        assert sorted(ready) == ["a", "b", "c"]
        assert result[0].area == "ai"
        assert result[1].area is None

    def test_empty_input_is_not_an_error(self, tmp_path):
        assert collect_signals([], cache_dir=tmp_path, sources=["patents"]) == []

    def test_wide_pool_does_not_lose_or_duplicate_terms(self, tmp_path):
        # Пул шире одного потока раздаёт термины из общей очереди: ни один не должен
        # достаться двум потокам и ни один — остаться в очереди.
        terms = [TermRequest(f"t{index}") for index in range(37)]
        seen: list[str] = []
        result = collect_signals(
            terms,
            cache_dir=tmp_path,
            sources=["patents"],
            workers={"patents": 6},
            today=TODAY,
            on_source=lambda term, _result, _cached: seen.append(term),
        )
        assert [signals.term for signals in result] == [term.term for term in terms]
        assert sorted(seen) == sorted(term.term for term in terms)

    def test_service_marker_does_not_leak_into_the_record(self, tmp_path):
        terms = [TermRequest("a")]
        collect_signals(terms, cache_dir=tmp_path, sources=["patents"], today=TODAY)
        again = collect_signals(terms, cache_dir=tmp_path, sources=["patents"], today=TODAY)
        assert "_cached" not in again[0].sources["patents"].data
        assert "_cached" not in json.dumps(again[0].to_json())


class TestRecord:
    def test_failed_source_leaves_no_feature_rather_than_a_zero(self):
        signals = TermSignals(
            term="x",
            area=None,
            collected_on=TODAY,
            sources={
                "openalex": SourceResult("openalex", True, TODAY, 1, {"works_window_total": 12}),
                "github": SourceResult("github", False, TODAY, 1, error="HTTP 503"),
            },
        )
        features = signals.features()
        assert features["openalex.works_window_total"] == 12.0
        # Ноль сказал бы «репозиториев нет»; правда — «GitHub не ответил».
        assert not any(key.startswith("github.") for key in features)
        assert signals.failures == ("github",)

    def test_nested_and_boolean_values_become_columns(self):
        signals = TermSignals(
            term="x",
            area=None,
            collected_on=TODAY,
            sources={
                "github": SourceResult(
                    "github",
                    True,
                    TODAY,
                    1,
                    {"repos_by_year": {"2024": 5}, "stars_exact": False, "title": "не число"},
                )
            },
        )
        features = signals.features()
        assert features["github.repos_by_year.2024"] == 5.0
        assert features["github.stars_exact"] == 0.0
        assert "github.title" not in features


class TestCli:
    def test_terms_are_read_from_a_file_or_from_the_argument(self, tmp_path):
        path = tmp_path / "terms.json"
        path.write_text(json.dumps([{"term": "a", "area": "ai"}]), encoding="utf-8")
        assert cli.load_terms(str(path)) == [TermRequest("a", "ai")]
        assert cli.load_terms('[{"term": "b"}]') == [TermRequest("b", None)]

    def test_empty_term_is_rejected(self):
        with pytest.raises(ValueError, match="пустой term"):
            cli.load_terms('[{"term": "   "}]')

    @staticmethod
    def write_records(path, records):
        path.write_text(
            "".join(json.dumps(record, ensure_ascii=False) + "\n" for record in records)
            + '{"term": "c", broken\n',
            encoding="utf-8",
        )

    def test_resume_skips_what_is_already_in_the_output(self, tmp_path):
        out = tmp_path / "signals.jsonl"
        self.write_records(out, [{"term": "a", "failures": []}, {"term": "b", "failures": []}])
        # Оборванная строка — это недособранный термин, его нужно собрать заново.
        done, kept = cli.already_collected(out)
        assert done == {"a", "b"}
        assert len(kept) == 2

    def test_retry_failed_reopens_records_with_a_dead_source(self, tmp_path):
        out = tmp_path / "signals.jsonl"
        self.write_records(
            out, [{"term": "a", "failures": []}, {"term": "b", "failures": ["arxiv"]}]
        )
        done, kept = cli.already_collected(out, keep_partial=False)
        # Пустое поле, оставшееся от вчерашнего 429, иначе осталось бы пустым навсегда.
        assert done == {"a"}
        assert len(kept) == 1

    def test_missing_output_means_nothing_collected(self, tmp_path):
        assert cli.already_collected(tmp_path / "нет.jsonl") == (set(), [])

    def test_run_writes_a_line_per_term_and_resumes(self, tmp_path, capsys):
        terms = tmp_path / "terms.json"
        terms.write_text(json.dumps([{"term": "a"}, {"term": "b"}]), encoding="utf-8")
        out = tmp_path / "signals.jsonl"
        args = [
            "--terms",
            str(terms),
            "--out",
            str(out),
            "--cache",
            str(tmp_path / "cache"),
            "--sources",
            "patents",
            "--quiet",
        ]
        assert cli.main(args) == 0
        assert len(out.read_text(encoding="utf-8").strip().splitlines()) == 2

        capsys.readouterr()
        assert cli.main(args) == 0
        assert "осталось 0" in capsys.readouterr().err
        assert len(out.read_text(encoding="utf-8").strip().splitlines()) == 2

    def test_unknown_source_is_refused(self, tmp_path):
        out = tmp_path / "signals.jsonl"
        code = cli.main(["--terms", '[{"term": "a"}]', "--out", str(out), "--sources", "scopus"])
        assert code == 2
