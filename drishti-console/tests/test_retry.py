# Project Drishti · Any data. Any domain. One grammar.
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
# All rights reserved.
#
# PROPRIETARY AND CONFIDENTIAL.
#
# This file is the confidential and proprietary property of Ashutosh Sinha.
# Unauthorised copying, use, modification, distribution or disclosure of this
# file, via any medium, is strictly prohibited except with the express prior
# written permission of the copyright holder.
#
# See the LICENSE file in the root of this repository for the full terms.

"""Retry in the console: the backoff schedule, permanent versus transient errors, AI providers and MCP servers that recover (a provider
that could not be set up, an MCP server that lost its connection, files that change with no restart), and the admin pages that show
"failed - next retry in Ns (attempt k)" with a Retry now button that is admin-only and sends JSON only."""
import asyncio

from core import retry
from core.ai import AiRuntime
from core.ai.mcp import McpRegistry
from core.ai.registry import LlmRegistry
from core.ai.types import LlmError
from core.config import Settings


class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


def policy(**kw):
    return retry.RetryPolicy(**{"jitter": 0.0, **kw})


def test_the_schedule_doubles_from_five_seconds_to_five_minutes():
    p = policy()
    assert [p.base_delay(i) for i in range(1, 10)] == [5, 10, 20, 40, 80, 160, 300, 300, 300]
    assert p.base_delay(10 ** 6) == 300


def test_jitter_stays_within_its_fraction_and_never_past_max():
    p = retry.RetryPolicy(initial=10, max=40, jitter=0.2)
    assert p.delay(1, False, 0.0) == 8 and p.delay(1, False, 0.5) == 10 and 11.99 < p.delay(1, False, 0.999999) <= 12
    assert p.delay(9, False, 0.999999) == 40


def test_settings_read_with_an_area_override():
    s = Settings({"retry": {"initial": "2s", "max": "1m", "factor": 3, "mcp": {"initial": "500ms"}, "ai": {"enabled": False}}})
    assert retry.RetryPolicy.of(s, "packs").initial == 2 and retry.RetryPolicy.of(s, "packs").max == 60 and retry.RetryPolicy.of(s, "x").factor == 3
    assert retry.RetryPolicy.of(s, "mcp").initial == 0.5
    assert retry.RetryPolicy.of(s, "ai").enabled is False
    assert retry.RetryPolicy.of(Settings({}), "ai") == retry.RetryPolicy()


def test_a_refused_credential_repeated_becomes_permanent_and_slows_to_max():
    clock = Clock()
    t = retry.RetryTracker(policy(), clock, lambda: 0.5)
    assert t.failed("connection refused").next_at == clock.t + 5 and t.failed("connection refused").next_at == clock.t + 10
    c = retry.RetryTracker(policy(), clock, lambda: 0.5)
    assert [c.failed("HTTP 401 unauthorized", retry.looks_permanent("HTTP 401 unauthorized")).permanent for _ in range(3)] == [False, False, True]
    assert c.status().next_at == clock.t + 300
    c.failed("timeout", False)
    assert not c.status().permanent
    assert retry.looks_permanent("403 Forbidden") and not retry.looks_permanent("connection refused")


def test_due_retry_now_and_recovery():
    clock = Clock()
    t = retry.RetryTracker(policy(), clock, lambda: 0.5)
    t.failed("down")
    assert not t.due() and t.status().next_in(clock.t) == 5
    clock.t += 5
    assert t.due()
    t.failed("down")
    t.retry_now()
    assert t.due() and t.succeeded() == 2 and not t.failing()


def test_the_board_logs_on_powers_of_two_and_reports_recovery():
    events = []
    clock = Clock()
    b = retry.RetryBoard("connector", policy(), clock, lambda *a: events.append(a))
    for _ in range(5):
        b.failed("k", "broker down")
    assert [e[0] for e in events] == ["failed", "failed", "failed"]
    assert b.succeeded("k") and events[-1][0] == "recovered" and not b.succeeded("k")


# ------------------------------------------------------------------------------------------------------------------ AI providers


class Up:
    ok = False


def flaky_factory(config, runtime):
    if not Up.ok:
        raise LlmError("config", "connection refused by the stub")
    return Provider()


class Provider:
    def models(self):
        return []

    async def aclose(self):
        pass


def test_a_provider_that_could_not_be_set_up_is_built_on_the_schedule_and_loads_when_the_stub_is_up(tmp_path):
    (tmp_path / "p.yaml").write_text("type: flaky\nmodels: [{name: m}]\n", encoding="utf-8")
    from core.ai import config as cfgmod
    clock = Clock()
    board = retry.RetryBoard("ai provider", policy(initial=5, max=40), clock)
    Up.ok = False
    reg = LlmRegistry({"flaky": flaky_factory}, lambda: cfgmod.load_providers(str(tmp_path), None), None, "", board)
    reg.reload()
    e = reg.entries()[0]
    assert e.error and "connection refused" in e.error
    s = board.status("p")
    assert s.attempt == 1 and s.next_in(clock.t) == 5
    reg.reload()                                          # too early: not tried again
    assert board.status("p").attempt == 1
    clock.t += 6
    reg.reload()
    assert board.status("p").attempt == 2
    Up.ok = True
    clock.t += 11
    reg.reload()
    assert reg.entries()[0].error is None and reg.entries()[0].usable and board.statuses() == {}


def test_a_provider_file_that_is_bad_is_listed_and_clears_when_fixed(tmp_path):
    from core.ai import config as cfgmod
    (tmp_path / "bad.yaml").write_text("type: [unclosed\n", encoding="utf-8")
    board = retry.RetryBoard("ai provider", policy())
    reg = LlmRegistry({}, lambda: cfgmod.load_providers(str(tmp_path), None), None, "", board)
    reg.reload()
    assert any(k.startswith("file ") for k in board.statuses())
    (tmp_path / "bad.yaml").write_text("type: mock\nmodels: [{name: m}]\n", encoding="utf-8")
    reg.reload()
    assert not [k for k in board.statuses() if k.startswith("file ")]


# ------------------------------------------------------------------------------------------------------------------ MCP servers


class StubClient:
    up = True
    connects = 0

    def __init__(self, cfg):
        self.cfg = cfg

    async def connect(self):
        StubClient.connects += 1
        if not StubClient.up:
            raise LlmError("failed", "stub server is down")

    async def aclose(self):
        pass


def test_an_mcp_server_that_lost_its_connection_reconnects_on_the_schedule(tmp_path):
    from core.ai import config as cfgmod
    (tmp_path / "s.yaml").write_text("transport: stub\n", encoding="utf-8")
    clock = Clock()
    board = retry.RetryBoard("mcp server", policy(initial=5, max=40), clock)
    reg = McpRegistry(lambda: cfgmod.load_mcp(str(tmp_path), None), None, {"stub": lambda c, rt: StubClient(c)}, board)
    reg.reload()
    e = reg.server("s")
    e.calls, e.failures, e.last_error, e.last_was_failure = 1, 1, "stub server is down", True        # a call failed
    StubClient.up = False
    asyncio.run(reg.probe_down())
    assert board.status("s").attempt == 1 and "connection lost" in board.status("s").last_error
    clock.t += 6
    asyncio.run(reg.probe_down())                          # due, still down: another attempt, the wait grows
    assert board.status("s").attempt == 2 and board.status("s").next_in(clock.t) == 10
    StubClient.up = True
    clock.t += 11
    asyncio.run(reg.probe_down())
    assert not e.last_was_failure and board.statuses() == {} and reg.health(e)["status"] == "UP"


def test_a_transport_that_is_not_installed_is_refused_and_slowed(tmp_path):
    from core.ai import config as cfgmod
    (tmp_path / "s.yaml").write_text("transport: late\n", encoding="utf-8")
    board = retry.RetryBoard("mcp server", policy())
    reg = McpRegistry(lambda: cfgmod.load_mcp(str(tmp_path), None), None, {}, board)
    reg.reload()
    assert "no MCP transport" in reg.server("s").error and board.status("s").attempt == 1


# ------------------------------------------------------------------------------------------------------------------ the runtime


def test_the_runtime_picks_up_a_new_provider_file_without_a_restart(tmp_path):
    ai_dir = tmp_path / "ai"
    ai_dir.mkdir()
    ai = AiRuntime(Settings({"ai": {"enabled": True, "dir": str(ai_dir)}}))
    assert ai.registry.entries() == []
    assert asyncio.run(ai.tick()) is False                  # nothing changed, nothing failing: no reload
    (ai_dir / "mock.yaml").write_text("type: mock\ndefault-model: m\nmodels:\n  - name: m\n", encoding="utf-8")
    assert asyncio.run(ai.tick()) is True
    assert [e.config.name for e in ai.registry.entries()] == ["mock"]


# ------------------------------------------------------------------------------------------------------------------ the admin pages

RETRY = {"attempt": 3, "nextRetryInSeconds": 20, "lastError": "connection refused", "permanent": False, "failingSince": None}


def test_the_admin_pages_show_failed_next_retry_in_n_seconds_attempt_k(client, backend, monkeypatch):
    async def admin(method, path, ident, body=None, timeout=None, headers=None, **params):
        if path == "/packs":
            return [{"name": "beta", "title": "Beta", "description": "", "version": "1", "loaded": False, "added": False, "enabled": False,
                     "problem": "'beta' clashes with 'alpha' over kind widget (not loaded)", "retry": RETRY, "kinds": []}]
        if path == "/registry":
            return {"url": "", "configured": False, "packs": []}
        if path == "/packs/history":
            return {"history": [], "kept": {}}
        if path == "/connectors":
            return {"directory": "/c", "watch": "WATCHING", "misnamed": [], "fileProblems": {}, "deprecated": [], "knownKinds": [], "connectors": [
                {"name": "lake", "origin": "file", "plugin": "delta", "kinds": [], "description": "", "enabled": True, "state": "FAILED", "health": None,
                 "updated": None, "etag": "x", "problems": ["connection refused"], "usedBy": [], "template": None, "retry": RETRY}]}
        if path == "/connectors/plugins":
            return {"plugins": []}
        if path == "/ai":
            return {"enabled": True, "dir": "d", "types": [], "providers": [{"name": "corp", "type": "openai", "health": {"status": "ERROR", "detail": "no key"},
                                                                              "models": [], "retry": RETRY}], "fileErrors": {}, "mcpEnabled": True, "mcpDir": "m",
                    "mcpServers": [{"name": "srv", "transport": "streamable-http", "enabled": True, "url": "http://x", "allowedTools": [],
                                    "health": {"status": "DOWN", "detail": "down"}, "retry": RETRY}], "mcpFileErrors": {}}
        if path == "/health":
            return {"status": "DEGRADED", "summary": {"sources": 0, "sourcesDown": 0, "sourcesDegraded": 0, "failedToStart": 1, "packs": 0, "packsWithProblems": 0},
                    "server": {"version": "1", "uptimeSeconds": 5, "heapUsedMb": 1, "heapMaxMb": 2, "threads": 1, "java": "21"}, "sources": [],
                    "failedToStart": {"lake": "connection refused"}, "packs": [], "packsNotLoaded": {"beta": "clash"}, "dataLate": [], "overrides": [],
                    "live": {"streams": 0, "topics": 0, "frames": 0, "droppedFrames": 0}, "sutras": {"hotReload": "WATCHING", "problemFiles": 0},
                    "connectors": {}, "retry": {"packs": {"beta": RETRY}, "connectors": {"lake": RETRY}, "ai": {"corp": RETRY}, "mcp": {}}}
        return {}
    monkeypatch.setattr(backend, "admin", admin)
    expect = "failed &mdash;"
    for url, extra in (("/admin/packs", ["data-pack-problem"]), ("/admin/connectors", ["conn-problem"]), ("/admin/ai", ["corp", "srv", 'data-retry-now="ai"']),
                       ("/admin/health", ['data-retries', 'data-retry-now="all"'])):
        page = client.get(url).text
        assert expect in page and "next retry in" in page and "(attempt 3)" in page and "connection refused" in page, url
        assert "admin-retry.js" in page, url
        for text in extra:
            assert text in page, (url, text)


def test_retry_now_is_admin_only_json_only_and_goes_to_the_server(client, backend, monkeypatch):
    calls = []

    async def admin(method, path, ident, body=None, timeout=None, headers=None, **params):
        calls.append((method, path, params))
        return {"packs": {"loaded": [], "problems": {}}}
    monkeypatch.setattr(backend, "admin", admin)
    r = client.post("/admin/api/retry/packs", json={})
    assert r.status_code == 200 and r.json()["server"] == {"packs": {"loaded": [], "problems": {}}}
    assert calls == [("POST", "/retry/packs", {})]
    assert client.post("/admin/api/retry/connectors", json={"name": "lake"}).status_code == 200 and calls[-1] == ("POST", "/retry/connectors", {"name": "lake"})
    assert client.post("/admin/api/retry/bogus", json={}).status_code == 400
    assert client.post("/admin/api/retry/packs", content=b"x", headers={"content-type": "text/plain"}).status_code in (400, 415)


def test_retry_now_for_ai_tries_the_consoles_own_layer_too(client, backend, monkeypatch, tmp_path):
    ai_dir = tmp_path / "ai"
    ai_dir.mkdir()
    (ai_dir / "p.yaml").write_text("type: nosuchtype\nmodels:\n  - name: m\n", encoding="utf-8")
    client.app.state.ai = AiRuntime(Settings({"ai": {"enabled": True, "dir": str(ai_dir)}}))
    assert client.app.state.ai.retry_ai.statuses()["p"].attempt == 1

    async def admin(method, path, ident, body=None, timeout=None, headers=None, **params):
        return {"ai": 0}
    monkeypatch.setattr(backend, "admin", admin)
    r = client.post("/admin/api/retry/ai", json={})
    assert r.status_code == 200 and r.json()["console"] == 1
    assert client.app.state.ai.retry_ai.statuses()["p"].attempt == 2
    assert any(a.get("outcome") == "retry-now" for a in client.app.state.ai.audit_log)
