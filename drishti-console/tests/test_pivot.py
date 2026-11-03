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
    assert "data-pivot-screen" in page and "pivot-grid.js" in page and "/static/js/pivot.js" in page and "echarts.min.js" in page
    for marker in ("data-pv-fields", 'data-pv-zone="rows"', 'data-pv-zone="columns"', 'data-pv-zone="values"', 'data-pv-zone="filters"',
                   "data-pv-grid", "data-pv-estimate", "data-pv-pop", "data-pv-chartcard", 'data-pv-export="xlsx"', "data-pv-compare"):
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
