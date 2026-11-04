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
"""The CCR cube in the console (routes/ccube_routes.py; docs/guides/EXPOSURE_GUIDE.md section 30): CCUBE and its forms, the page as the
pivot screen on source ccr with the CCR lens (views A, B and C of the decided mock-ups, A the default; one pivot engine, no second grid),
the typed scope laid over the landing as a filter and a view, every CCR answer read from the server as the person through the same-origin
proxy /api/pivot/ccr/..., the server's refusals passed on in words. The figures are the server's tests (CcrCubeApiTest, CcrPivotProviderTest)."""
from __future__ import annotations

import pytest

from core.backend import BackendError
from routes.ccube_routes import ccube_command, parse
from test_play import RISK_ABOUT, _app


@pytest.mark.parametrize("typed, where", [
    ("CCUBE", "/ccube"),
    ("CCUBE BANK <GO>", "/ccube"),
    ("CCUBE GROUP:GRP-ALDERSHOT <GO>", "/ccube?text=GROUP%3AGRP-ALDERSHOT"),
    ("CCUBE NSET:NS-ALDERSHOT-LDN", "/ccube?text=NSET%3ANS-ALDERSHOT-LDN"),
    ("CCUBE @asha/board", "/ccube?view=asha%2Fboard"),
    ("EXP BANK", None),
])
def test_ccube_and_its_forms(typed, where):
    assert ccube_command(typed.split()) == where


def test_the_typed_scope_is_a_filter_and_a_view():
    v = parse("CPTY:CP-ALDERSHOT BY GROUP,TENOR_BUCKET MEASURE PFE,EPE,LIMIT_USE Q 0.99 COLLATERAL CURRENT DATE 2026-09-30".split())
    assert v["filters"] == {"COUNTERPARTY": {"in": ["CP-ALDERSHOT"]}}
    assert v["rows"] == ["GROUP"] and v["columns"] == ["TENOR_BUCKET"]
    assert v["values"] == [{"measure": "pfe()", "as": "Peak PFE"}, {"measure": "epe()", "as": "EPE"}, {"measure": "limit_use()", "as": "Use"}]
    assert v["options"] == {"q": "0.99", "collateral": "current"} and v["date"] == "2026-09-30"
    assert v["lensScope"] == "CPTY:CP-ALDERSHOT"
    n = parse(["NSET:NS-X"])
    assert n["lensView"] == "B" and n["lensScope"] == "NSET:NS-X" and n["filters"] == {"NETTING_SET": {"in": ["NS-X"]}}
    assert parse("BY CPTY VIEW C".split()) == {"rows": ["COUNTERPARTY"], "columns": [], "lensView": "C"}
    assert parse([]) == {}


def test_the_page_is_the_pivot_screen_with_the_ccr_lens_and_the_api_is_read_as_the_person(backend, monkeypatch):
    sent: list = []

    async def pivot(verb, path, ident, body=None, raw=False, **params):
        sent.append((verb, path, getattr(ident, "user", None), body, {k: v for k, v in params.items() if v not in (None, "")}))
        if path == "/ccr/fan" and params.get("scope") == "CPTY:NOPE":
            raise BackendError(404, "DRS-1001", "no exposure paths for CPTY:NOPE")
        return {"ok": True, "path": path}

    monkeypatch.setattr(backend, "pivot", pivot, raising=False)
    app, c = _app(backend, monkeypatch, RISK_ABOUT)
    r = c.get("/go", params={"q": "CCUBE GROUP:GRP-X <GO>"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/ccube?text=GROUP%3AGRP-X"
    page = c.get("/ccube").text
    assert "data-pivot-screen" in page and 'data-source="ccr"' in page and 'data-lens="ccr"' in page and 'data-lens-view="A"' in page
    assert "pivot.js" in page and "ccr-lens.js" in page and "ccr-lens.css" in page and "mr-lens.js" not in page
    for marker in ("data-ccr-tiles", 'data-ccr-seg="q"', 'data-ccr-seg="collateral"', 'data-ccr-seg="measure"', 'data-ccr-tab="A"',
                   'data-ccr-tab="B"', 'data-ccr-tab="C"', "data-ccr-fan", "data-ccr-tree", "data-ccr-b-tiles", "data-ccr-b-profile",
                   "data-ccr-b-trades", "data-ccr-tray", "data-ccr-c-overlay", "data-ccr-c-group", "data-pv-grid", "data-pv-fields"):
        assert marker in page, marker
    b = c.get("/ccube", params={"text": "NSET:NS-X"}).text
    assert 'data-lens-view="B"' in b and 'data-lens-scope="NSET:NS-X"' in b and "data-preset=" in b and "lensView" not in b
    assert 'data-lens-view="C"' in c.get("/ccube", params={"tab": "c"}).text
    assert 'data-view-id="asha/board"' in c.get("/ccube?view=asha/board").text
    plain = c.get("/pivot", params={"source": "ccr"}).text
    assert "ccr-lens.js" not in plain and 'data-lens="ccr"' not in plain
    view = {"source": "ccr", "rows": ["COUNTERPARTY"], "values": [{"measure": "pfe()"}]}
    h = c.post("/api/pivot/ccr/headline", json={"view": view})
    assert h.status_code == 200 and sent[-1][:4] == ("POST", "/ccr/headline", "alice", {"view": view})
    f = c.get("/api/pivot/ccr/fan", params={"scope": "CPTY:CP-1", "q": "0.99", "asOf": "2026-09-30"})
    assert f.status_code == 200 and sent[-1][1] == "/ccr/fan" and sent[-1][4] == {"scope": "CPTY:CP-1", "q": "0.99", "asOf": "2026-09-30"}
    body = {"scope": "CPTY:CP-1", "method": "paths", "candidates": [{"tradeId": "CAND-1", "paths": {"copyOf": "A", "factor": 1}}]}
    p = c.post("/api/pivot/ccr/pre-deal?asOf=2026-09-30", json=body)
    assert p.status_code == 200 and sent[-1][1] == "/ccr/pre-deal" and sent[-1][3] == body and sent[-1][4] == {"asOf": "2026-09-30"}
    off = c.get("/api/pivot/ccr/fan", params={"scope": "CPTY:NOPE"})
    assert off.status_code == 404 and off.json()["code"] == "DRS-1001"
