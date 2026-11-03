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
"""FVA in the console (routes/fva_routes.py; docs/guides/FVA_GUIDE.md sections 5 and 6): FVA [<scope>] opens the one FVA screen on the view
its scope asks (A a netting set, B wider scopes, C a counterparty), PLAY FVA <scope> the playground; the pages are shells, every figure read
from the server as the person with the choices passed through, the workbooks streamed. The figures themselves are the engine's tests
(FvaMathTest, FvaApiTest, FvaPlaygroundWorkbookTest)."""
from __future__ import annotations

import pytest

from routes.fva_routes import fva_command, play_fva_command, scope_of
from routes.play_routes import play_command
from test_play import RISK_ABOUT, _app, _Upstream

ANSWER = {"scope": "NSET:NS-X", "label": "NS-X · Alpha", "businessDate": "2026-09-30", "runId": "XR-1", "figures": {"fca": -4750.0, "fba": 3000.0, "fva": -1750.0},
          "terms": [], "nettingSets": [], "flags": ["REAL_WORLD_RUN"]}


@pytest.mark.parametrize("typed, where", [
    ("FVA", "/fva?scope=BANK&view=b"),
    ("FVA BANK <GO>", "/fva?scope=BANK&view=b"),
    ("FVA NSET:NS-ALDERSHOT-FRA", "/fva?scope=NSET%3ANS-ALDERSHOT-FRA&view=a"),
    ("FVA NSET NS-ALDERSHOT-FRA <GO>", "/fva?scope=NSET%3ANS-ALDERSHOT-FRA&view=a"),
    ("FVA CPTY CP-ALDERSHOT", "/fva?scope=CPTY%3ACP-ALDERSHOT&view=c"),
    ("FVA GROUP:GRP-ALDERSHOT", "/fva?scope=GROUP%3AGRP-ALDERSHOT&view=b"),
    ("FVA NS-ALDERSHOT-FRA", "/fva?scope=NSET%3ANS-ALDERSHOT-FRA&view=a"),
    ("SIMM NS-X", None),
])
def test_fva_and_its_forms(typed, where):
    assert fva_command(typed.split()) == where


def test_play_fva():
    assert play_command("PLAY FVA NSET:NS-X".split(), "alice") == "/play/fva?scope=NSET%3ANS-X"
    assert play_command("PLAY FVA CPTY CP-X".split(), "alice") == "/play/fva?scope=CPTY%3ACP-X"
    assert play_fva_command([]) == "/play/fva"
    assert scope_of([]) == "BANK"


def test_the_pages_and_the_api_are_read_as_the_person(backend, monkeypatch):
    sent: list = []

    async def fva(verb, path, ident, /, **params):
        sent.append((verb, path, getattr(ident, "user", None), dict(params)))
        return ANSWER

    async def open_download(path, ident):
        sent.append(("STREAM", path, getattr(ident, "user", None), {}))
        return _Upstream(b"PK\x03\x04workbook")

    monkeypatch.setattr(backend, "fva", fva, raising=False)
    monkeypatch.setattr(backend, "open_download", open_download, raising=False)
    app, c = _app(backend, monkeypatch, RISK_ABOUT)
    r = c.get("/go", params={"q": "FVA CPTY CP-ALDERSHOT <GO>"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/fva?scope=CPTY%3ACP-ALDERSHOT&view=c"
    r = c.get("/go", params={"q": "PLAY FVA NSET:NS-X <GO>"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/play/fva?scope=NSET%3ANS-X"
    page = c.get("/fva", params={"scope": "NSET:NS-X"}).text
    assert 'data-fva data-mode="screen" data-scope="NSET:NS-X" data-view="a"' in page and "fva.js" in page
    assert "Netting sets by FVA" in page and ">Counterparty<" in page
    page = c.get("/fva", params={"scope": "BANK", "view": "c"}).text
    assert 'data-view="c"' in page
    page = c.get("/play/fva", params={"scope": "NSET:NS-X"}).text
    assert 'data-mode="play"' in page and "data-fv-choices" in page and "Export to Excel" in page and "data-fv-terms" in page
    j = c.get("/api/fva/scope", params={"scope": "CPTY:CP-X", "fba": "off", "spread": "0.006", "eePoint": "mid", "junk": "x"})
    assert j.status_code == 200 and j.json()["figures"]["fva"] == -1750.0
    assert sent[-1] == ("GET", "/fva/scope", "alice", {"scope": "CPTY:CP-X", "eePoint": "mid", "fba": "off", "spread": "0.006"})
    assert c.get("/api/fva/playground", params={"scope": "NSET:NS-X"}).status_code == 200
    assert sent[-1][1] == "/fva/playground"
    x = c.get("/api/fva/export.xlsx", params={"scope": "NSET:NS-X", "survival": "on"})
    assert x.status_code == 200 and x.content.startswith(b"PK")
    assert sent[-1][0] == "STREAM" and sent[-1][1].startswith("/fva/playground.xlsx?") and "survival=on" in sent[-1][1]
    x = c.get("/api/fva/example.xlsx")
    assert x.status_code == 200 and sent[-1][1] == "/fva/playground/template"
