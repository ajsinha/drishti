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

"""The pivot screen in the console (routes/pivot_routes.py; docs/guides/PIVOT_GUIDE.md): PIVOT, PIVOT <table> [date] and
PIVOT @owner/name, the page as a shell (mock-up A: the field list and four zones, the grid, the estimate bar, the provenance popover, the
chart, the Export menu), every figure read from the server as the person through the same-origin proxy, the export passed through as a
download, the server's refusals passed on in words. The figures themselves are the server's and the engine's tests (PivotCoreTest,
FunctionsTest, PivotExportTest, PivotApiTest, VarPivotApiTest)."""
from __future__ import annotations

import asyncio

import pytest

from core.backend import BackendError
from routes.pivot_routes import pivot_command
from test_play import RISK_ABOUT, _app

ANSWER = {"source": "trade", "view": {"source": "trade", "rows": ["productType"]}, "rows": [], "columns": [], "cells": {}, "measures": []}


@pytest.mark.parametrize("typed, where", [
    ("PIVOT", "/pivot"),
    ("PIVOT <GO>", "/pivot"),
    ("PIVOT trade <GO>", "/pivot?source=trade"),
    ("PIVOT trade 2026-10-30", "/pivot?source=trade&date=2026-10-30"),
    ("PIVOT @asha/desk-risk", "/pivot?view=asha%2Fdesk-risk"),
    ("VAR BANK", None),
])
def test_pivot_and_its_forms(typed, where):
    assert pivot_command(typed.split()) == where


def test_the_page_and_the_api_are_read_as_the_person(backend, monkeypatch):
    sent: list = []

    async def pivot(verb, path, ident, body=None, raw=False, **params):
        sent.append((verb, path, getattr(ident, "user", None), body, dict(params)))
        if path == "/export":
            return b"ROW,COLUMN\r\n", {"content-type": "text/csv", "content-disposition": 'attachment; filename="pivot-trade.csv"'}
        if path == "/estimate":
            raise BackendError(404, "DRS-5703", "the pivot engine is switched off on this server (drishti.pivot.enabled)")
        if path == "/views/desk-risk":
            return None
        return ANSWER

    monkeypatch.setattr(backend, "pivot", pivot, raising=False)
    app, c = _app(backend, monkeypatch, RISK_ABOUT)
    r = c.get("/go", params={"q": "PIVOT trade <GO>"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/pivot?source=trade"
    page = c.get("/pivot?source=trade").text
    assert "data-pivot-screen" in page and "/static/elements/drishti-grid/drishti_grid.js" in page and "/static/js/drishti_pivot.js" in page
    assert "echarts.min.js" in page and "drishti_pivot-grid.js" not in page          # the grid is drishti-grid's: its wells, its cells, its Explain
    for marker in ("data-pv-grid", "data-pv-estimate", "data-pv-slicer-add", "data-pv-path", "data-pv-chartcard", 'data-pv-export="xlsx"',
                   "data-pv-compare", "drishti_grid.css"):
        assert marker in page, marker
    assert 'data-view-id="asha/desk-risk"' in c.get("/pivot?view=asha/desk-risk").text
    q = c.post("/api/pivot/query", json={"view": {"source": "trade"}})
    assert q.status_code == 200 and q.json()["source"] == "trade"
    assert sent[-1][:4] == ("POST", "/query", "alice", {"view": {"source": "trade"}})
    c.get("/api/pivot/catalogue", params={"source": "trade", "date": "2026-10-30"})
    assert sent[-1][1] == "/catalogue" and sent[-1][4] == {"source": "trade", "date": "2026-10-30"}
    assert c.post("/api/pivot/expand", json={"view": {}, "path": [["productType", "IRS"]]}).status_code == 200
    assert sent[-1][1] == "/expand"
    assert c.post("/api/pivot/drop-tables", json={}).status_code == 404
    off = c.post("/api/pivot/estimate", json={})
    assert off.status_code == 404 and off.json()["code"] == "DRS-5703"
    x = c.post("/api/pivot/export", json={"view": {"source": "trade"}, "format": "csv"})
    assert x.status_code == 200 and x.content.startswith(b"ROW") and "pivot-trade.csv" in x.headers["content-disposition"]
    assert c.delete("/api/pivot/views/desk-risk").status_code == 204


def test_the_switches_live_pivots_and_cell_comments_are_passed_through_as_the_person(backend, monkeypatch):
    """PIVOT_GUIDE.md: the screen's switches, live pivots, cell comments. The switches are request options the server answers; a live
    pivot's stream and a progressive answer are relayed event by event, a refusal as one error event; comments go to the saved view."""
    sent: list = []

    async def pivot(verb, path, ident, body=None, raw=False, **params):
        sent.append((verb, path, body, dict(params)))
        if path == "/live" and verb == "POST":
            return {"id": "lp-1", "node": "n1", "answer": ANSWER, "options": {"exact": True}}
        if path.endswith("/comments") and verb == "GET":
            return {"id": "asha/desk-risk", "version": 2, "comments": [], "open": 0}
        if path.endswith("/comments") and verb == "POST":
            return {"id": "c-1", "cell": "DESK=RATES", "cellId": "c:abc", "text": body["text"]}
        return {}

    async def sse(path, ident=None, opened=None, node=None):
        sent.append(("SSE", path, node))
        if "lp-gone" in path:
            raise BackendError(404, "DRS-5704", "no live pivot lp-gone")
        yield "answer", '{"id": "lp-1"}'
        yield "patch", '{"id": "lp-1", "cells": {}, "removed": []}'

    async def sse_post(path, ident, body, node=None):
        sent.append(("SSE-POST", path, body))
        yield "approximate", '{"exactness": "approximate"}'
        yield "exact", '{"exactness": "exact"}'

    monkeypatch.setattr(backend, "pivot", pivot, raising=False)
    monkeypatch.setattr(backend, "sse", sse, raising=False)
    monkeypatch.setattr(backend, "sse_post", sse_post, raising=False)
    app, c = _app(backend, monkeypatch, RISK_ABOUT)
    page = c.get("/pivot?source=trade").text
    for marker in ('name="pvExact"', 'value="approximate"', "data-pv-progressive", "data-pv-rollups", "data-pv-live", "data-pv-computed",
                   "/static/js/drishti_pivot-switches.js"):
        assert marker in page, marker
    body = {"view": {"source": "trade"}, "exact": True, "rollups": False}
    r = c.post("/api/pivot/live", json=body)
    assert r.status_code == 200 and r.json()["id"] == "lp-1" and sent[-1][:3] == ("POST", "/live", body)
    # the stream rides the browser's one channel (drishti_channel.js, core/channel.py): pivotlive:<id>@<node>, the node that holds it
    from core.channel import ChannelSession

    async def read(sub):
        up, is_view = ChannelSession(backend, None, lambda e, d: d, limit=4)._open(sub, [])
        return is_view, [e async for e, _ in up]

    assert asyncio.run(read("pivotlive:lp-1@n1")) == (False, ["answer", "patch"]) and ("SSE", "/pivot/live/lp-1/events", "n1") in sent
    with pytest.raises(BackendError):
        asyncio.run(read("pivotlive:lp-gone"))
    assert c.get("/api/pivot/live/lp-1/events").status_code in (404, 405)          # no stream of its own: one channel per browser
    assert c.delete("/api/pivot/live/lp-1").status_code == 204 and sent[-1][:2] == ("DELETE", "/live/lp-1")
    p = c.post("/api/pivot/query/progressive", json={"view": {"source": "trade"}, "exact": "auto", "progressive": True})
    assert p.text.index("event: approximate") < p.text.index("event: exact")
    assert ("SSE-POST", "/pivot/query?progressive=true", {"view": {"source": "trade"}, "exact": "auto", "progressive": True}) in sent
    assert c.get("/api/pivot/views/asha/desk-risk/comments", params={"open": "true"}).json()["version"] == 2
    assert sent[-1][:2] == ("GET", "/views/asha/desk-risk/comments") and sent[-1][3]["open"] == "true"
    added = c.post("/api/pivot/views/asha/desk-risk/comments", json={"coords": {"DESK": "RATES"}, "text": "checked"})
    assert added.json()["cell"] == "DESK=RATES" and sent[-1][2]["coords"] == {"DESK": "RATES"}
    assert c.post("/api/pivot/views/asha/desk-risk/comments/c-1/resolve", json={}).status_code == 200
    assert sent[-1][:2] == ("POST", "/views/asha/desk-risk/comments/c-1/resolve")
    assert c.post("/api/pivot/views/asha/desk-risk/comments/c-1/shred", json={}).status_code == 404
    assert c.delete("/api/pivot/views/asha/desk-risk/comments/c-1").status_code == 200
    assert sent[-1][:2] == ("DELETE", "/views/asha/desk-risk/comments/c-1")
