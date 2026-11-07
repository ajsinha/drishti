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

"""ISDA SIMM in the console (routes/simm_routes.py; docs/guides/SIMM_GUIDE.md sections 7 and 8): SIMM [<netting set> | CPTY <id>], PLAY SIMM
<netting set> and PLAY SIMM RUN <id>, the pages as shells, every figure read from the server as the person with the switches, side and
overrides passed through, the recipe saved and replayed, the workbook streamed. The figures themselves are the engine's tests
(SimmHandBuiltCasesTest, SimmPlaygroundWorkbookTest, SimmApiTest)."""
from __future__ import annotations

import pytest

from routes.play_routes import play_command
from routes.simm_routes import play_simm_command, simm_command
from test_play import RISK_ABOUT, _app, _Upstream
import engine_fakes  # noqa: E402

ANSWER = {"SCOPE": "EXAMPLE", "KIND": "EXAMPLE", "NETTING_SET": None, "SIMM_VERSION": "TOY-1", "IM_COLLECT": 1, "IM_POST": 1, "BY_PRODUCT_CLASS": [],
          "BY_RISK_CLASS": [], "REGULATORY": False}


@pytest.mark.parametrize("typed, where", [
    ("SIMM", "/simm"),
    ("SIMM NS-ALDERSHOT-LDN <GO>", "/simm?nettingSet=NS-ALDERSHOT-LDN"),
    ("SIMM CPTY cp-aldershot", "/simm?counterparty=CP-ALDERSHOT"),
    ("SIMM DESK RATES", "/simm?nettingSet=DESK%3ARATES"),
    ("SIMM example", "/simm?nettingSet=EXAMPLE"),
    ("FRSA BANK", None),
])
def test_simm_and_its_forms(typed, where):
    assert simm_command(typed.split()) == where


def test_play_simm_and_a_recipe():
    assert play_command("PLAY SIMM NS-CORAL-NY".split(), "alice") == "/play/simm?nettingSet=NS-CORAL-NY"
    assert play_command("PLAY SIMM".split(), "alice") == "/play/simm"
    assert play_simm_command(["RUN", "sm-ab12cd34-xyz"]) == "/play/simm?recipe=SM-AB12CD34-XYZ"


def test_the_pages_and_the_api_are_read_as_the_person(backend, monkeypatch):
    sent: list = []

    async def simm(verb, path, ident, /, **params):
        sent.append((verb, path, getattr(ident, "user", None), dict(params)))
        if path.startswith("/simm/playground/recipes"):
            return {"id": "SM-AB12CD34-1", "command": "PLAY SIMM RUN SM-AB12CD34-1", "knownAt": "2026-11-02T00:00:00Z"} if verb == "POST" else {**ANSWER, "reproduced": "reproduced to the cent"}
        if path == "/simm/netting-sets":
            return {"date": "2026-09-30", "rows": [], "computed": 0, "sumCollect": 0, "sumPost": 0}
        return ANSWER

    async def open_download(path, ident):
        sent.append(("STREAM", path, getattr(ident, "user", None), {}))
        return _Upstream(b"PK\x03\x04workbook")

    engine_fakes.patch(monkeypatch, backend, simm=simm)
    monkeypatch.setattr(backend, "open_download", open_download, raising=False)
    app, c = _app(backend, monkeypatch, RISK_ABOUT)
    r = c.get("/go", params={"q": "SIMM NS-ALDERSHOT-LDN <GO>"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/simm?nettingSet=NS-ALDERSHOT-LDN"
    r = c.get("/go", params={"q": "PLAY SIMM RUN SM-AB12CD34-1 <GO>"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/play/simm?recipe=SM-AB12CD34-1"
    page = c.get("/simm?nettingSet=NS-ALDERSHOT-LDN").text
    assert 'data-simm data-mode="screen" data-ns="NS-ALDERSHOT-LDN"' in page and "drishti_simm.js" in page and "Against the counterparty" in page
    page = c.get("/play/simm?nettingSet=EXAMPLE").text
    assert 'data-mode="play"' in page and "data-sm-choices" in page and "Save recipe" in page and "Export to Excel" in page and "data-sm-side" in page
    j = c.get("/api/simm/playground", params={"nettingSet": "EXAMPLE", "concentration": "off", "side": "post", "compare": "hvr=off",
                                              "override": ["InterestRate.across-currencies=0.4"]})
    assert j.status_code == 200
    assert sent[-1][:3] == ("GET", "/simm/playground", "alice")
    assert sent[-1][3] == {"nettingSet": "EXAMPLE", "concentration": "off", "side": "post", "compare": "hvr=off", "override": ["InterestRate.across-currencies=0.4"]}
    assert c.get("/api/simm/netting-sets", params={"counterparty": "CP-X"}).json()["date"] == "2026-09-30"
    assert sent[-1][1:] == ("/simm/netting-sets", "alice", {"counterparty": "CP-X"})
    assert c.post("/api/simm/recipes", params={"nettingSet": "EXAMPLE"}).json()["id"] == "SM-AB12CD34-1"
    assert c.get("/api/simm/recipes/SM-AB12CD34-1").json()["reproduced"].startswith("reproduced")
    assert c.get("/api/simm/recipes/FR-AB12CD34-1").status_code == 400
    x = c.get("/api/simm/export.xlsx", params={"nettingSet": "EXAMPLE", "side": "post", "override": ["InterestRate.across-currencies=0.4"]})
    assert x.status_code == 200 and x.content.startswith(b"PK")
    assert sent[-1][0] == "STREAM" and sent[-1][1].startswith("/risk/simm/playground.xlsx?") and "side=post" in sent[-1][1] and sent[-1][1].count("override=") == 1
