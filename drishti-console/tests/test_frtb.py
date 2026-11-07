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

"""FRTB-SA in the console (routes/frtb_routes.py; docs/guides/FRTB_SA_GUIDE.md sections 7 and 8): FRSA <scope>, PLAY FRTB <scope> and
PLAY FRTB RUN <id>, the pages as shells, every figure read from the server as the person with the switches and overrides passed through,
the recipe saved and replayed, the workbook streamed. The figures themselves are the engine's tests (FrtbHandBuiltCasesTest,
FrtbPlaygroundWorkbookTest, FrtbApiTest)."""
from __future__ import annotations

import pytest

from routes.frtb_routes import frsa_command, play_frtb_command
from routes.play_routes import play_command
from test_play import RISK_ABOUT, _app, _Upstream
import engine_fakes  # noqa: E402

SA = {"scope": "EXAMPLE", "canonical": "EXAMPLE", "date": None, "currency": "USD", "regulatory": True, "binding": "low", "reported": "low",
      "classes": [], "sbm": {"low": 1, "medium": 1, "high": 1}, "SBM": 1, "DRC": None, "RRAO": None, "total": 1, "stack": []}


@pytest.mark.parametrize("typed, where", [
    ("FRSA", "/frtb"),
    ("FRSA BANK <GO>", "/frtb?scope=BANK"),
    ("FRSA DESK RATES", "/frtb?scope=DESK%3ARATES"),
    ("FRSA DESK:DESK-RATES + BOOK:BOOK-FX-1", "/frtb?scope=DESK%3ADESK-RATES+%2B+BOOK%3ABOOK-FX-1"),
    ("FRSA EXAMPLE", "/frtb?scope=EXAMPLE"),
    ("VAR BANK", None),
])
def test_frsa_and_its_forms(typed, where):
    assert frsa_command(typed.split()) == where


def test_play_frtb_and_a_recipe():
    assert play_command("PLAY FRTB DESK RATES".split(), "alice") == "/play/frtb?scope=DESK%3ARATES"
    assert play_command("PLAY FRTB".split(), "alice") == "/play/frtb"
    assert play_frtb_command(["RUN", "fr-ab12cd34-xyz"]) == "/play/frtb?recipe=FR-AB12CD34-XYZ"


def test_the_pages_and_the_api_are_read_as_the_person(backend, monkeypatch):
    sent: list = []

    async def frtb(verb, path, ident, /, **params):
        sent.append((verb, path, getattr(ident, "user", None), dict(params)))
        if path.startswith("/frtb/playground/recipes"):
            return {"id": "FR-AB12CD34-1", "command": "PLAY FRTB RUN FR-AB12CD34-1", "knownAt": "2026-11-02T00:00:00Z"} if verb == "POST" else {**SA, "reproduced": "reproduced to the cent"}
        return SA

    async def open_download(path, ident):
        sent.append(("STREAM", path, getattr(ident, "user", None), {}))
        return _Upstream(b"PK\x03\x04workbook")

    engine_fakes.patch(monkeypatch, backend, frtb=frtb)
    monkeypatch.setattr(backend, "open_download", open_download, raising=False)
    app, c = _app(backend, monkeypatch, RISK_ABOUT)
    r = c.get("/go", params={"q": "FRSA DESK RATES <GO>"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/frtb?scope=DESK%3ARATES"
    r = c.get("/go", params={"q": "PLAY FRTB RUN FR-AB12CD34-1 <GO>"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/play/frtb?recipe=FR-AB12CD34-1"
    page = c.get("/frtb?scope=DESK:RATES").text
    assert 'data-frtb data-mode="capital" data-scope="DESK:RATES"' in page and "drishti_frtb-sa.js" in page and "data-fr-stack" in page and "Scenarios" in page
    page = c.get("/play/frtb?scope=EXAMPLE").text
    assert 'data-mode="play"' in page and "data-fr-choices" in page and "Save recipe" in page and "Export to Excel" in page
    j = c.get("/api/frtb/playground", params={"scope": "EXAMPLE", "girr-correlation": "exact", "override": ["GIRR.delta.across-buckets=0.6"],
                                               "compare": "curvature-source=gamma"})
    assert j.status_code == 200
    assert sent[-1][:3] == ("GET", "/frtb/playground", "alice")
    assert sent[-1][3] == {"scope": "EXAMPLE", "compare": "curvature-source=gamma", "girrCorrelation": "exact", "override": ["GIRR.delta.across-buckets=0.6"]}
    assert c.post("/api/frtb/recipes", params={"scope": "EXAMPLE"}).json()["id"] == "FR-AB12CD34-1"
    assert c.get("/api/frtb/recipes/FR-AB12CD34-1").json()["reproduced"].startswith("reproduced")
    assert c.get("/api/frtb/recipes/nope").status_code == 400
    x = c.get("/api/frtb/export.xlsx", params={"scope": "EXAMPLE", "girr-correlation": "exact"})
    assert x.status_code == 200 and x.content == b"PK\x03\x04workbook"
    assert sent[-1][0] == "STREAM" and sent[-1][1].startswith("/risk/frtb/playground.xlsx?") and "girrCorrelation=exact" in sent[-1][1]
    for path in ("/api/frtb/sa", "/api/frtb/explain", "/api/frtb/contributions", "/api/frtb/sensitivities", "/api/frtb/risk-factors"):
        assert c.get(path, params={"scope": "BANK"}).status_code == 200
