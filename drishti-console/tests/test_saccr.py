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

"""SA-CCR in the console (routes/saccr_routes.py; docs/guides/SACCR_GUIDE.md sections 7 and 8): SACCR [<netting set> | CPTY <id> | LE:<id>
| BANK | EXAMPLE] and PLAY SACCR <netting set>, the retired stored ids opening their netting set, one page with three views over one answer
read from the server as the person, the workbook streamed. The figures are the engine's tests (SaccrHandBuiltCasesTest, SaccrServiceTest,
SaccrPlaygroundWorkbookTest); the page computes nothing (its script holds no SA-CCR formula)."""
from __future__ import annotations

from pathlib import Path

import pytest

from routes.play_routes import play_command
from routes.saccr_routes import play_saccr_command, saccr_command, scope_of
from test_play import RISK_ABOUT, _app, _Upstream

ANSWER = {"scope": "NSET:NS-ALDERSHOT-NY", "kind": "netting set", "businessDate": "2026-09-30", "regulation": "BCBS 1.0",
          "totals": {"EAD": 1.0, "NETTING_SETS": 1}, "sets": [], "selected": None, "provenance": {}}


@pytest.mark.parametrize("typed, where", [
    ("SACCR", "/saccr?scope=BANK&view=B"),
    ("SACCR NS-ALDERSHOT-NY <GO>", "/saccr?scope=NSET%3ANS-ALDERSHOT-NY&view=A"),
    ("SACCR CPTY cp-aldershot", "/saccr?scope=CPTY%3ACP-ALDERSHOT&view=C"),
    ("SACCR LE:LE-NY", "/saccr?scope=LE%3ALE-NY&view=B"),
    ("SACCR example", "/saccr?scope=EXAMPLE&view=B"),
    ("SACCR SACCR-ALDERSHOT-NY", "/saccr?scope=NSET%3ANS-ALDERSHOT-NY&view=A"),
    ("FRSA BANK", None),
])
def test_saccr_and_its_forms(typed, where):
    assert saccr_command(typed.split()) == where


def test_play_saccr():
    assert play_command("PLAY SACCR NS-CORAL-NY".split(), "alice") == "/play/saccr?scope=NSET%3ANS-CORAL-NY"
    assert play_command("PLAY SACCR".split(), "alice") == "/play/saccr?scope=EXAMPLE"
    assert scope_of(["EXAMPLE:S5"]) == "EXAMPLE:S5"


def test_the_script_draws_and_computes_nothing():
    js = (Path(__file__).resolve().parents[1] / "web" / "static" / "js" / "saccr.js").read_text(encoding="utf-8")
    for formula in ("Math.exp", "Math.sqrt", "1.4 *", "0.05 +", "0.95"):
        assert formula not in js, formula


def test_the_page_and_the_api_are_read_as_the_person(backend, monkeypatch):
    sent: list = []

    async def saccr(verb, path, ident, /, **params):
        sent.append((verb, path, getattr(ident, "user", None), dict(params)))
        return ANSWER

    async def open_download(path, ident):
        sent.append(("STREAM", path, getattr(ident, "user", None), {}))
        return _Upstream(b"PK\x03\x04workbook")

    monkeypatch.setattr(backend, "saccr", saccr, raising=False)
    monkeypatch.setattr(backend, "open_download", open_download, raising=False)
    app, c = _app(backend, monkeypatch, RISK_ABOUT)
    r = c.get("/go", params={"q": "SACCR NS-ALDERSHOT-NY <GO>"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/saccr?scope=NSET%3ANS-ALDERSHOT-NY&view=A"
    r = c.get("/go", params={"q": "PLAY SACCR NS-ALDERSHOT-NY <GO>"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/play/saccr?scope=NSET%3ANS-ALDERSHOT-NY"
    page = c.get("/saccr", params={"scope": "CPTY:CP-ALDERSHOT", "view": "C"}).text
    assert 'data-saccr data-mode="screen" data-scope="CPTY:CP-ALDERSHOT" data-view="C"' in page and "saccr.js" in page
    assert "A · EAD waterfall" in page and "B · Netting sets" in page and "C · Sets side by side" in page
    page = c.get("/play/saccr", params={"scope": "EXAMPLE"}).text
    assert 'data-mode="play"' in page and "Export to Excel" in page and "data-sc-grid" in page
    j = c.get("/api/saccr/scope", params={"scope": "CPTY:CP-ALDERSHOT", "nettingSet": "NS-ALDERSHOT-NY", "cap": "false"})
    assert j.status_code == 200 and j.json()["regulation"] == "BCBS 1.0"
    assert sent[-1] == ("GET", "/saccr/scope", "alice", {"scope": "CPTY:CP-ALDERSHOT", "nettingSet": "NS-ALDERSHOT-NY", "cap": "false"})
    c.get("/api/saccr/contributions", params={"scope": "BANK", "by": "asset-class"})
    assert sent[-1][1:] == ("/saccr/scope/contributions", "alice", {"scope": "BANK", "by": "asset-class"})
    c.get("/api/saccr/trade/MX-30001436")
    assert sent[-1][1] == "/saccr/trade/MX-30001436"
    x = c.get("/api/saccr/export.xlsx", params={"scope": "NSET:NS-ALDERSHOT-NY", "irBucketOffset": "false"})
    assert x.status_code == 200 and x.content.startswith(b"PK")
    assert sent[-1][0] == "STREAM" and sent[-1][1].startswith("/saccr/playground.xlsx?") and "irBucketOffset=false" in sent[-1][1]
