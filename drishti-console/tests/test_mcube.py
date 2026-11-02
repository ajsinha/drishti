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

"""The MR cube in the console (routes/mcube_routes.py; docs/guides/MR_CUBE_GUIDE.md): MCUBE <view> and MCUBE @owner/name, the page as a
shell (landing A with tabs B and C), every figure read from the server as the person through the same-origin proxy, the export passed
through as a download, the server's refusals passed on in words. The figures themselves are the server's tests (VarCubeApiTest,
CubeEngineTest, VarCubeShardTest)."""
from __future__ import annotations

import pytest

from core.backend import BackendError
from routes.mcube_routes import DEFAULT_TEXT, mcube_command
from test_play import RISK_ABOUT, _app

ANSWER = {"cube": "mr", "text": "MCUBE BANK BY DESK MEASURE VAR", "view": {"scope": "BANK", "rows": ["DESK"]}, "rows": [], "columns": [],
          "cells": [], "total": {"values": {"VAR": 1.0}}}


@pytest.mark.parametrize("typed, where", [
    ("MCUBE", "/mcube"),
    ("MCUBE <GO>", "/mcube"),
    ("MCUBE BANK BY DESK,RISK_CLASS MEASURE VAR <GO>", "/mcube?text=MCUBE+BANK+BY+DESK%2CRISK_CLASS+MEASURE+VAR"),
    ("MCUBE @asha/morning", "/mcube?view=asha%2Fmorning"),
    ("VAR BANK", None),
])
def test_mcube_and_its_forms(typed, where):
    assert mcube_command(typed.split()) == where


def test_the_page_and_the_api_are_read_as_the_person(backend, monkeypatch):
    sent: list = []

    async def cube(verb, path, ident, body=None, raw=False, **params):
        sent.append((verb, path, getattr(ident, "user", None), body, dict(params)))
        if path == "/mr/export":
            return b"KIND,DESK\r\n", {"content-type": "text/csv", "content-disposition": 'attachment; filename="cube.csv"'}
        if path == "/mr/versions":
            raise BackendError(404, "DRS-5603", "the MR cube is not running on this server")
        if path == "/views/morning":
            return None
        return ANSWER

    monkeypatch.setattr(backend, "cube", cube, raising=False)
    app, c = _app(backend, monkeypatch, RISK_ABOUT)
    r = c.get("/go", params={"q": "MCUBE BANK BY DESK MEASURE VAR <GO>"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/mcube?text=MCUBE+BANK+BY+DESK+MEASURE+VAR"
    page = c.get("/mcube").text
    assert "data-mcube" in page and DEFAULT_TEXT in page and "cube-core.js" in page and "mcube.js" in page
    for marker in ("data-mc-tiles", "data-mc-grid", "data-mc-tail", "data-mc-explain", "data-mc-prov", "data-mc-slider", 'data-mc-tab="tail"',
                   'data-mc-tab="sensitivities"', "data-mc-ladder", "data-mc-whatif", "data-mc-heat", "data-mc-waterfall"):
        assert marker in page, marker
    assert 'data-view-id="asha/morning"' in c.get("/mcube?view=asha/morning").text
    q = c.post("/api/mcube/query", json={"scope": "BANK", "rows": ["DESK"]})
    assert q.status_code == 200 and q.json()["cube"] == "mr"
    assert sent[-1][:4] == ("POST", "/mr/query", "alice", {"scope": "BANK", "rows": ["DESK"]})
    assert c.post("/api/mcube/cell", json={"view": {"scope": "BANK"}, "row": "DESK-X"}).status_code == 200
    assert sent[-1][1] == "/mr/cell"
    c.get("/api/mcube/sensitivities", params={"scope": "BANK", "grid": "BANK-IR-TENOR", "class": "GIRR"})
    assert sent[-1][1] == "/mr/sensitivities" and sent[-1][4] == {"scope": "BANK", "grid": "BANK-IR-TENOR", "class": "GIRR"}
    c.get("/api/mcube/explain", params={"scope": "DESK:DESK-RATES", "from": "2026-09-29", "to": "2026-09-30"})
    assert sent[-1][4] == {"scope": "DESK:DESK-RATES", "from": "2026-09-29", "to": "2026-09-30"}
    off = c.get("/api/mcube/versions")
    assert off.status_code == 404 and off.json()["code"] == "DRS-5603"
    x = c.post("/api/mcube/export", params={"format": "csv"}, json={"scope": "BANK"})
    assert x.status_code == 200 and x.content.startswith(b"KIND") and "cube.csv" in x.headers["content-disposition"]
    assert sent[-1][4] == {"format": "csv"}
    assert c.delete("/api/mcube/views/morning").status_code == 204
