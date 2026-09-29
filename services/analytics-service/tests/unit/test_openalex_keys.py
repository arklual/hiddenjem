"""OpenAlex spends BYOK credentials in configured order on quota responses."""

from urllib.error import HTTPError

import httpx

from horizon_analytics.openalex_keys import OpenAlexKeys, keys
from horizon_analytics.signals.http import Fetcher, Pace
from horizon_analytics.signals.sources.openalex import _get
from horizon_analytics.validation import sources


def test_key_ring_advances_only_after_active_key_is_exhausted(monkeypatch):
    monkeypatch.setenv("HORIZON_OPENALEX_API_KEYS", "second,third")
    ring = OpenAlexKeys()
    assert ring.current("first") == "first"
    assert ring.exhausted("first", "first")
    assert ring.current("first") == "second"
    assert ring.exhausted("first", "first")  # a concurrent stale request
    assert ring.current("first") == "second"
    assert ring.exhausted("second", "first")
    assert ring.current("first") == "third"
    assert not ring.exhausted("third", "first")


def test_openalex_retries_with_next_key_on_429(monkeypatch):
    monkeypatch.setenv("HORIZON_OPENALEX_API_KEY", "first")
    monkeypatch.setenv("HORIZON_OPENALEX_API_KEYS", "second")
    seen = []

    def handler(request):
        key = request.headers["Authorization"].removeprefix("Bearer ")
        seen.append(key)
        return httpx.Response(429 if key == "first" else 200, json={"ok": True})

    fetcher = Fetcher(
        pace=Pace(0), client=httpx.Client(transport=httpx.MockTransport(handler))
    )
    try:
        assert _get(fetcher, {"group_by": "publication_year"}) == {"ok": True}
        assert seen == ["first", "second"]
    finally:
        fetcher.close()
        # The shared ring is refreshed by the next test's environment.
        monkeypatch.delenv("HORIZON_OPENALEX_API_KEYS")
        keys.current()


def test_maturity_query_uses_next_key_after_limit(monkeypatch):
    monkeypatch.setenv("HORIZON_OPENALEX_API_KEY", "first")
    monkeypatch.setenv("HORIZON_OPENALEX_API_KEYS", "second")
    sources.set_openalex_api_key("")
    seen = []

    def fetch(_source, _url, **kwargs):
        seen.append(kwargs["api_key"])
        if kwargs["api_key"] == "first":
            raise HTTPError(_url, 429, "limit", {}, None)
        return '{"group_by": [{"key": "2026", "count": 4}]}'

    monkeypatch.setattr(sources, "_fetch", fetch)
    assert sources._openalex_years("test term") == ({2026: 4}, True)
    assert seen == ["first", "second"]
