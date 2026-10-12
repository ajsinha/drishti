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



def test_admins_see_and_purge_caches(client):
    page = client.get("/admin/caches").text
    assert "trading-stream" in page and 'data-purge="all"' in page and "diskMb" in page
    r = client.post("/admin/api/caches/trading-stream/purge")
    assert r.status_code == 200 and r.json()["purged"] == ["trading-stream"]


def test_the_health_page_shows_connectors_packs_and_live(client, backend, monkeypatch):
    data = {"status": "DEGRADED", "summary": {"sources": 3, "sourcesDown": 1, "failedToStart": 1, "packs": 2, "packsWithProblems": 1},
            "server": {"version": "1.9.0", "uptimeSeconds": 3725, "java": "21", "heapUsedMb": 300, "heapMaxMb": 4000, "threads": 60, "cpus": 8},
            "sources": [{"name": "trading-store", "status": "DOWN", "health": "DOWN: connection refused (reconnecting)", "kinds": ["trade"], "live": False,
                         "dated": True, "search": True, "reads": {"reads": 12, "found": 9, "notHeld": 1, "errors": 2, "lastError": "SQLException: refused",
                                                                   "lastErrorAt": "2026-09-30T20:01:02Z", "p50Ms": 1.2, "p99Ms": 9.5}, "cache": {},
                         "pool": {"type": "jdbc", "exempt": False, "max": 20, "open": 20, "inUse": 20, "idle": 0, "waiting": 3, "longestBorrowMillis": 41500,
                                  "leaksDetected": 1, "timeouts": 2}},
                        {"name": "demo", "status": "UP", "health": "UP", "kinds": ["trade"], "live": True, "dated": False, "search": True,
                         "reads": {"reads": 0}, "cache": {"entries": 36}}],
            "failedToStart": {"feed-fred": "no API key"},
            "identityPool": {"type": "identity", "exempt": False, "max": 20, "open": 3, "inUse": 1, "idle": 2, "waiting": 0, "longestBorrowMillis": -1,
                             "leaksDetected": 0, "timeouts": 0},
            "poolsAttention": ["trading-store: every connection is in use and callers are waiting (raise pool-size, or find what holds connections)"],
            "packs": [{"name": "trading", "title": "Trading", "version": "1.0.0", "requires": ["market-data"], "kinds": 1, "sutras": 125,
                       "sutraProblems": [{"file": "rates/x.v1.sutra.md", "problems": ["DRS-2021 unknown panel kind"]}], "connectors": ["trading-store"],
                       "connectorsDown": ["trading-store"], "connectorsOff": [], "status": "DEGRADED"}],
            "live": {"streams": 2, "topics": 3, "frames": 120, "droppedFrames": 0, "p50Ms": 3.1, "p99Ms": 11.0}}

    async def admin(method, path, ident, body=None, **params):
        assert path == "/health"
        return data
    monkeypatch.setattr(backend, "admin", admin)
    page = client.get("/admin/health").text
    assert "DEGRADED" in page and "2 of 3 connectors up" in page.replace("<b>", "").replace("</b>", "")
    assert "trading-store" in page and "connection refused (reconnecting)" in page and "SQLException: refused" in page
    assert "feed-fred" in page and "no API key" in page and "unknown panel kind" in page and "open streams" in page
    flat = " ".join(__import__("re").sub(r"<[^>]+>", "", page).split())
    assert "20/20 in use · 0 idle · 3 waiting · longest borrow 41.5 s · 1 leak warning · 2 timeouts" in flat      # the connector's pool
    assert "Identity database pool (PostgreSQL): 1/20 in use · 2 idle · 0 waiting · longest borrow n/a" in flat
    assert "data-pool-attention" in page and "every connection is in use" in page
    body = client.get("/admin/health", params={"partial": 1}).text
    assert "<html" not in body and "trading-store" in body
    assert "data-health-body" not in body and "setInterval" in client.get("/static/js/admin.js").text


def test_a_degraded_connector_is_shown_with_what_it_cannot_read(client, backend, monkeypatch):
    """DATA-18: a connector that serves but cannot read a table says which, in amber."""
    data = {"status": "DEGRADED", "summary": {"sources": 1, "sourcesDown": 0, "sourcesDegraded": 1, "failedToStart": 0, "packs": 0,
                                              "packsWithProblems": 0},
            "server": {"version": "1.13.0", "uptimeSeconds": 60, "java": "25", "heapUsedMb": 300, "heapMaxMb": 4000, "threads": 60, "cpus": 8},
            "sources": [{"name": "trading-store", "status": "DEGRADED", "health": "DEGRADED: cannot read trade 2026-09-30: LZ4 (engine: native)",
                         "kinds": ["trade"], "live": False, "dated": True, "search": True, "reads": {"reads": 0}, "cache": {}}],
            "failedToStart": {}, "packs": [], "overrides": [],
            "live": {"streams": 0, "topics": 0, "frames": 0, "droppedFrames": 0, "p50Ms": 0, "p99Ms": 0}}

    async def admin(method, path, ident, body=None, **params):
        return data
    monkeypatch.setattr(backend, "admin", admin)
    page = client.get("/admin/health").text
    assert "hl-warn" in page and "cannot read trade 2026-09-30" in page
    assert "1 degraded" in page.replace("<b>", "").replace("</b>", "")
