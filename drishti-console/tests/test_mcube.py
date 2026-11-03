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

"""The MR cube in the console (routes/mcube_routes.py; docs/guides/MR_CUBE_GUIDE.md): MCUBE and its forms, the page as the pivot screen
on source var with the MR lens (landing A, tabs B and C; one pivot engine, no second grid), the typed view laid over the landing as a
preset, every MR answer read from the server as the person through the same-origin proxy /api/pivot/mr/..., the server's refusals passed
on in words. The figures themselves are the server's tests (VarMrCubeApiTest, VarMrCubeShardTest, MrCubeOffTest)."""
from __future__ import annotations

import pytest

from core.backend import BackendError
from routes.mcube_routes import mcube_command, parse
from test_play import RISK_ABOUT, _app


@pytest.mark.parametrize("typed, where", [
    ("MCUBE", "/mcube"),
    ("MCUBE <GO>", "/mcube"),
    ("MCUBE BANK BY MGMT.DESK,RISK_CLASS MEASURE VAR <GO>", "/mcube?text=BANK+BY+MGMT.DESK%2CRISK_CLASS+MEASURE+VAR"),
    ("MCUBE @asha/morning", "/mcube?view=asha%2Fmorning"),
    ("VAR BANK", None),
])
def test_mcube_and_its_forms(typed, where):
    assert mcube_command(typed.split()) == where


def test_the_typed_view_is_a_preset_of_the_pivot_view():
    v = parse("DESK:RATES BY MGMT.BOOK,CURRENCY MEASURE VAR,COMP_VAR DATE 2026-09-30 SORT desc".split())
    assert v["options"] == {"scope": "DESK:RATES"}
    assert v["rows"] == ["MGMT.BOOK"] and v["columns"] == ["CURRENCY"]
    assert v["values"] == [{"measure": "var()", "as": "VaR"}, {"measure": "comp_var()", "as": "Comp VaR"}]
    assert v["date"] == "2026-09-30" and v["sort"] == {"MGMT.BOOK": {"by": "VaR", "desc": True}}
    assert parse([]) == {}


def test_the_page_is_the_pivot_screen_with_the_mr_lens_and_the_api_is_read_as_the_person(backend, monkeypatch):
    sent: list = []

    async def pivot(verb, path, ident, body=None, raw=False, **params):
        sent.append((verb, path, getattr(ident, "user", None), body, {k: v for k, v in params.items() if v not in (None, "")}))
        if path == "/mr/versions":
            raise BackendError(404, "DRS-1020", "MR cube is not running on this server")
        return {"ok": True, "path": path}

    monkeypatch.setattr(backend, "pivot", pivot, raising=False)
    app, c = _app(backend, monkeypatch, RISK_ABOUT)
    r = c.get("/go", params={"q": "MCUBE DESK:RATES BY MGMT.BOOK <GO>"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/mcube?text=DESK%3ARATES+BY+MGMT.BOOK"
    page = c.get("/mcube").text
    assert "data-pivot-screen" in page and 'data-source="var"' in page and 'data-lens="mr"' in page
    assert "pivot.js" in page and "mr-lens.js" in page and "mr-lens.css" in page and "cube-core.js" not in page
    for marker in ("data-mr-tiles", "data-mr-seg=\"horizon\"", "data-mr-slider", 'data-mr-tab="tail"', 'data-mr-tab="sensitivities"',
                   "data-mr-tail", "data-mr-explain", "data-mr-comp", "data-mr-waterfall", "data-mr-heat", "data-mr-ladder", "data-mr-whatif",
                   "data-pv-grid", "data-pv-fields"):
        assert marker in page, marker
    typed = c.get("/mcube", params={"text": "DESK:RATES BY MGMT.BOOK"}).text
    assert "data-preset=" in typed and "DESK:RATES" in typed
    assert 'data-view-id="asha/morning"' in c.get("/mcube?view=asha/morning").text
    plain = c.get("/pivot", params={"source": "var"}).text
    assert "mr-lens.js" not in plain and "data-lens" not in plain
    view = {"source": "var", "rows": ["MGMT.DESK"], "values": [{"measure": "var()"}]}
    h = c.post("/api/pivot/mr/headline", json={"view": view})
    assert h.status_code == 200 and sent[-1][:4] == ("POST", "/mr/headline", "alice", {"view": view})
    assert c.post("/api/pivot/mr/detail", json={"view": view, "coords": {"MGMT.DESK": "DESK-X"}}).status_code == 200
    assert sent[-1][1] == "/mr/detail"
    assert c.post("/api/pivot/mr/history", json={"view": view}).status_code == 200
    assert c.post("/api/pivot/mr/what-if", json={"view": view, "items": [{"copyOf": "T1", "factor": -1}]}).status_code == 200
    assert sent[-1][1] == "/mr/what-if"
    assert c.post("/api/pivot/mr/nope", json={}).status_code == 404
    c.get("/api/pivot/mr/sensitivities", params={"scope": "BANK", "grid": "BANK-IR-TENOR", "class": "GIRR"})
    assert sent[-1][1] == "/mr/sensitivities" and sent[-1][4] == {"scope": "BANK", "grid": "BANK-IR-TENOR", "class": "GIRR"}
    c.get("/api/pivot/mr/explain", params={"scope": "DESK:RATES", "from": "2026-09-29", "to": "2026-09-30"})
    assert sent[-1][4] == {"scope": "DESK:RATES", "from": "2026-09-29", "to": "2026-09-30"}
    off = c.get("/api/pivot/mr/versions")
    assert off.status_code == 404 and off.json()["code"] == "DRS-1020"
