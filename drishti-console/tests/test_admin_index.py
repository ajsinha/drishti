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

"""Admin -> Connectors -> Index: the connector page carries the Index section, and the console proxies the status and the reload to the server as
same-origin JSON (only kind and date reach the server; a refusal keeps its code). The Health page offers Reload index for a connector that has one."""
from core.backend import BackendError
from test_admin_detail_pages import CONNECTOR, serve

STATUS = {"enabled": True, "reload": {"running": False}, "auto": {"enabled": True, "everySeconds": 600},
          "days": [{"kind": "trade", "date": "2026-10-05", "ids": 3, "tier": "disk", "bytes": 900, "builtAt": None, "building": False, "lastError": None}]}


def _index(backend, monkeypatch, calls, refuse=None):
    inner = backend.admin

    async def admin(method, path, ident, body=None, timeout=None, headers=None, **params):
        if "/index" in path:
            calls.append((method, path, body))
            if refuse:
                raise refuse
            return STATUS
        return await inner(method, path, ident, body, timeout, headers, **params)
    monkeypatch.setattr(backend, "admin", admin)


def test_the_connector_page_has_the_index_section_and_its_script(client, backend, monkeypatch):
    serve(backend, monkeypatch)
    page = client.get("/admin/connectors/trading-lake").text
    for text in ("data-index-panel", "Reload all", "data-index-table", "admin-index.js", "data-index-auto"):
        assert text in page, text


def test_status_and_reload_are_proxied_and_only_kind_and_date_go_through(client, backend, monkeypatch):
    serve(backend, monkeypatch)
    calls = []
    _index(backend, monkeypatch, calls)
    assert client.get("/admin/connectors/api/trading-lake/index").json()["enabled"] is True
    r = client.post("/admin/connectors/api/trading-lake/index/reload", json={"kind": "trade", "date": "2026-10-05", "evil": "x"})
    assert r.status_code == 200
    assert calls[-1] == ("POST", "/connectors/trading-lake/index/reload", {"kind": "trade", "date": "2026-10-05"})
    client.post("/admin/connectors/api/trading-lake/index/reload", json={})
    assert calls[-1][2] == {}


def test_a_second_reload_is_refused_with_its_code_and_a_non_json_body_is_rejected(client, backend, monkeypatch):
    serve(backend, monkeypatch)
    _index(backend, monkeypatch, [], refuse=BackendError(409, "DRS-5032", "a reload is already running (1 of 3 days)"))
    r = client.post("/admin/connectors/api/trading-lake/index/reload", json={})
    assert r.status_code == 409 and r.json()["code"] == "DRS-5032" and "already running" in r.json()["detail"]
    assert client.post("/admin/connectors/api/trading-lake/index/reload", content="kind=x", headers={"content-type": "text/plain"}).status_code in (400, 415)


def test_health_offers_reload_index_for_a_connector_with_an_index(client, backend, monkeypatch):
    serve(backend, monkeypatch)
    inner = backend.admin

    async def admin(method, path, ident, body=None, timeout=None, headers=None, **params):
        if path == "/health":
            return {"status": "OK", "summary": {"sources": 1, "sourcesDown": 0, "sourcesDegraded": 0, "failedToStart": 0, "packs": 0, "packsWithProblems": 0},
                    "server": {"version": "1", "uptimeSeconds": 5, "heapUsedMb": 1, "heapMaxMb": 2, "threads": 3, "java": "21"}, "packs": [], "failedToStart": {}, "live": {"streams": 0, "topics": 0, "frames": 0}, "tmp": {},
                    "sources": [{"name": "trading-lake", "status": "UP", "live": True, "dated": True, "search": True, "stale": False, "kinds": ["trade"], "reads": {}, "cache": {"indexDays": 5, "indexNextCheck": "2026-10-12T10:00:00Z"}}]}
        if path == "/connectors":
            return {"connectors": [CONNECTOR]}
        return await inner(method, path, ident, body, timeout, headers, **params)
    monkeypatch.setattr(backend, "admin", admin)
    page = client.get("/admin/health?partial=1").text
    assert "Reload index" in page and 'data-connector="trading-lake"' in page and "next check 2026-10-12 10:00:00 UTC" in page
