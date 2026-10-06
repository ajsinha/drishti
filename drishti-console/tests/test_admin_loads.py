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

"""Admin → Packs → Data loads in the console: the page (history, expectations, settings), its filters, the same-origin settings calls,
the Health line for late data and the inbox link of a load notice."""
from core.backend import BackendError

LOAD = {"id": "k1-1", "pack": "trading", "kind": "trade", "businessDate": "2026-10-06", "status": "ready", "rows": 48213, "rejected": 12, "source": "etl-eod",
        "batchId": "eod-1", "receivedAt": "2026-10-06T21:01:02Z", "receivedBy": "etl", "attempt": 1, "reload": False, "verified": "verified", "entities": 16,
        "alerts": 3, "notices": 2, "durationMs": 41, "done": True, "summary": "trade for 2026-10-06 loaded: 48,213 rows, 12 rejected; 3 alerts fired",
        "steps": [{"name": "record", "status": "ok", "detail": "recorded", "ms": 0}, {"name": "verify", "status": "ok", "detail": "16 trade entities on 2026-10-06", "ms": 4},
                  {"name": "smoke", "status": "skipped", "detail": "not asked for", "ms": 0}]}
# the server leaves out what it does not know (no rows, rejected, entities or source on a failed load)
FAILED = {k: v for k, v in {**LOAD, "id": "k2-1", "status": "failed", "verified": "skipped", "alerts": 0, "notices": 1, "attempt": 2, "note": "disk full",
                            "batchId": "eod-2", "summary": "trade load for 2026-10-06 FAILED: disk full",
                            "steps": [{"name": "record", "status": "ok", "detail": "recorded", "ms": 0}]}.items()
          if k not in ("rows", "rejected", "entities", "source")}
CONFIG = {"pack": "trading", "origin": "override", "kinds": ["trade", "curve"],
          "config": {"notify": {"roles": ["admin"], "users": [], "email": False}, "smoke": 0,
                     "expect": {"trade": {"by": "19:00", "zone": "America/New_York", "calendar": "USNY"}}}}
EXPECT = {"pack": "trading", "origin": "override", "config": CONFIG["config"], "expectations": [
    {"pack": "trading", "kind": "trade", "businessDate": "2026-10-06", "by": "19:00", "zone": "America/New_York", "state": "late", "loadId": None, "landedAt": None},
    {"pack": "trading", "kind": "trade", "businessDate": "2026-10-05", "by": "19:00", "zone": "America/New_York", "state": "on-time", "loadId": "k1-1",
     "landedAt": "2026-10-05T22:30:00Z"}]}


def with_loads(backend, monkeypatch, calls):
    async def admin(method, path, ident, body=None, timeout=None, **params):
        calls.append((method, path, body, params))
        if path == "/loads/trading":
            rows = [LOAD, FAILED]
            if params.get("status"):
                rows = [r for r in rows if r["status"] == params["status"]]
            return {"pack": "trading", "kinds": ["trade", "curve"], "loads": rows}
        if path == "/loads/trading/expectations":
            return EXPECT
        if path == "/loads/trading/config":
            return CONFIG
        if path == "/loads/nope":
            raise BackendError(404, "DRS-5011", "no loaded pack named 'nope'")
        if path == "/status":
            return {}
        return {}
    monkeypatch.setattr(backend, "admin", admin)


def test_the_page_shows_history_steps_expectations_and_the_settings(client, backend, monkeypatch):
    with_loads(backend, monkeypatch, [])
    page = client.get("/admin/packs/trading/loads").text
    for text in ("Data loads", "trade for 2026-10-06 loaded: 48,213 rows, 12 rejected; 3 alerts fired", "48,213", "eod-1", "etl-eod", "verified", "attempt 2",
                 "FAILED: disk full", "3 steps", "16 trade entities on 2026-10-06", "Expectations", "19:00 America/New_York", "late", "on-time", "Expectations settings",
                 'data-config-dialog', 'aria-labelledby="cfgTitle"', "admin-loads.js", "admin-loads.css", "Telling Drishti new data has landed"):
        assert text in page, text
    assert 'role="search"' in page and '<caption class="visually-hidden">' in page and 'scope="col"' in page      # named filter, captions and headers for readers


def test_the_filters_are_sent_to_the_server(client, backend, monkeypatch):
    calls = []
    with_loads(backend, monkeypatch, calls)
    page = client.get("/admin/packs/trading/loads?status=failed&kind=trade&date=2026-10-06").text
    assert ("GET", "/loads/trading", None, {"limit": 200, "kind": "trade", "status": "failed", "date": "2026-10-06"}) in calls
    assert "FAILED: disk full" in page and "48,213 rows" not in page and "Clear" in page


def test_an_unknown_pack_is_a_clean_error_page(client, backend, monkeypatch):
    with_loads(backend, monkeypatch, [])
    assert client.get("/admin/packs/nope/loads").status_code == 404


def test_the_settings_are_saved_and_reset_through_same_origin_calls(client, backend, monkeypatch):
    calls = []
    with_loads(backend, monkeypatch, calls)
    body = {"notify": {"roles": ["risk"], "users": [], "email": True}, "smoke": 2, "expect": {"trade": {"by": "18:30"}}}
    assert client.put("/admin/api/loads/trading/config", json=body).status_code == 200
    assert ("PUT", "/loads/trading/config", body, {}) in calls
    assert client.delete("/admin/api/loads/trading/config").status_code == 200
    assert ("DELETE", "/loads/trading/config", None, {}) in calls


def test_a_refusal_of_the_settings_reaches_the_page_as_a_problem(client, backend, monkeypatch):
    async def admin(method, path, ident, body=None, timeout=None, **params):
        raise BackendError(400, "DRS-5001", "expect.trade.by must be a time like 19:00")
    monkeypatch.setattr(backend, "admin", admin)
    r = client.put("/admin/api/loads/trading/config", json={"expect": {"trade": {"by": "7pm"}}})
    assert r.status_code == 400 and r.json() == {"code": "DRS-5001", "detail": "expect.trade.by must be a time like 19:00"}


def test_the_packs_page_links_to_the_loads_page(client, backend, monkeypatch):
    async def admin(method, path, ident, body=None, timeout=None, **params):
        if path == "/packs":
            return [{"name": "trading", "title": "Trading", "description": "", "version": "1", "loaded": True, "added": False, "enabled": True, "extends": [],
                     "requiredBy": [], "kinds": ["trade"], "connectors": [], "mnemonics": []}]
        if path == "/registry":
            return {"url": "", "configured": False, "packs": []}
        if path == "/packs/history":
            return {"history": [], "kept": {}}
        return {}
    monkeypatch.setattr(backend, "admin", admin)
    assert 'href="/admin/packs/trading/loads"' in client.get("/admin/packs").text


def test_health_says_which_data_is_late(client, backend, monkeypatch):
    async def admin(method, path, ident, body=None, timeout=None, **params):
        if path == "/health":
            return {"status": "DEGRADED", "summary": {"sources": 0, "sourcesDown": 0, "sourcesDegraded": 0, "failedToStart": 0, "packs": 0, "packsWithProblems": 0},
                    "server": {"version": "1", "uptimeSeconds": 5, "heapUsedMb": 1, "heapMaxMb": 2, "threads": 3, "java": "21"}, "sources": [], "failedToStart": {},
                    "packs": [], "overrides": [], "live": {"streams": 0, "topics": 0, "frames": 0, "droppedFrames": 0, "p50Ms": 0, "p99Ms": 0},
                    "dataLate": [{"pack": "my-bank", "kind": "trade", "businessDate": "2026-10-06", "state": "late", "by": "19:00 America/New_York",
                                  "line": "data late: my-bank/trade 2026-10-06"}]}
        return {}
    monkeypatch.setattr(backend, "admin", admin)
    page = client.get("/admin/health").text
    assert "data late: my-bank/trade 2026-10-06" in page and "/admin/packs/my-bank/loads" in page


def test_a_load_notice_in_the_inbox_links_to_the_loads_page_for_those_who_may_open_the_kind():
    from routes.collab_routes import _row_href
    row = {"type": "load", "access": True, "threadId": "my-bank", "kind": "trade", "id": "2026-10-06"}
    assert _row_href(row) == "/admin/packs/my-bank/loads"
    assert _row_href({**row, "access": False}) == ""


def test_the_admin_menu_and_tabs_lead_to_data_loads_and_collaboration(client):
    page = client.get("/admin/packs").text
    assert 'href="/admin/loads"' in page and 'href="/admin/collab"' in page


def test_all_packs_at_a_glance_with_what_needs_attention(client, backend, monkeypatch):
    calls = []
    with_loads(backend, monkeypatch, calls)
    inner = backend.admin

    async def admin(method, path, ident, body=None, timeout=None, **params):
        if path == "/packs":
            return [{"name": "trading", "title": "Trading", "loaded": True}, {"name": "spare", "title": "Spare", "loaded": False}]
        return await inner(method, path, ident, body, timeout, **params)
    monkeypatch.setattr(backend, "admin", admin)
    page = client.get("/admin/loads").text
    assert "Trading" in page and "Spare" not in page, "only loaded packs"
    assert "trade: late" in page and "1 expected load needs attention" in page
    assert "trade 2026-10-06" in page and "48,213 rows" in page and 'href="/admin/packs/trading/loads"' in page
    assert 'aria-current="page"' in page.split("Data loads</a>")[0].rsplit("<a", 1)[1]
