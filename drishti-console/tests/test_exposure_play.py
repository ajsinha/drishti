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
"""The Exposure Playground in the console (routes/exposure_play_routes.py; docs/guides/EXPOSURE_GUIDE.md section 29): PLAY EXP <netting
set> and PLAY EXP EXAMPLE, the page as a shell, every figure read from the server as the person with the choices passed through, the
workbook and the template streamed. The figures themselves are the engine's tests (ExposurePlaygroundWorkbookTest)."""
from __future__ import annotations

import pytest

from routes.exposure_play_routes import play_exp_command
from routes.play_routes import play_command
from test_play import RISK_ABOUT, _app, _Upstream

ANSWER = {"nettingSet": "EXAMPLE-X1", "counterparty": "CP-EXAMPLE", "businessDate": "2026-09-30", "runId": "X1", "paths": 5, "runPaths": 5,
          "figures": {"epe": 1.2, "eepe": 1.4}, "profile": [], "explainSteps": []}


@pytest.mark.parametrize("words, where", [
    ([], "/play/exp"),
    (["EXAMPLE"], "/play/exp?nettingSet=EXAMPLE"),
    (["ns-aldershot-fra"], "/play/exp?nettingSet=NS-ALDERSHOT-FRA"),
    (["NSET", "NS-ALDERSHOT-FRA", "<GO>"], "/play/exp?nettingSet=NS-ALDERSHOT-FRA"),
    (["NSET:NS-ALDERSHOT-FRA"], "/play/exp?nettingSet=NS-ALDERSHOT-FRA"),
])
def test_play_exp_and_its_forms(words, where):
    assert play_exp_command(words) == where


def test_play_exp_through_the_play_word():
    assert play_command("PLAY EXP NS-ALDERSHOT-FRA <GO>".split(), "alice") == "/play/exp?nettingSet=NS-ALDERSHOT-FRA"
    assert play_command("PLAY EXP".split(), "alice") == "/play/exp"


def test_the_page_and_the_api_are_read_as_the_person(backend, monkeypatch):
    sent: list = []

    async def exposure(verb, path, ident, body=None, raw=False, **params):
        sent.append((verb, path, getattr(ident, "user", None), dict(params)))
        return ANSWER

    async def open_download(path, ident):
        sent.append(("STREAM", path, getattr(ident, "user", None), {}))
        return _Upstream(b"PK\x03\x04workbook")

    monkeypatch.setattr(backend, "exposure", exposure, raising=False)
    monkeypatch.setattr(backend, "open_download", open_download, raising=False)
    app, c = _app(backend, monkeypatch, RISK_ABOUT)
    r = c.get("/go", params={"q": "PLAY EXP EXAMPLE <GO>"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/play/exp?nettingSet=EXAMPLE"
    page = c.get("/play/exp?nettingSet=EXAMPLE").text
    assert 'data-exp-play data-ns="EXAMPLE"' in page and "exposure-play.js" in page and "Export to Excel" in page and "Template workbook" in page
    assert "data-xp-collateral" in page and "data-xp-rule" in page and "data-xp-trades" in page
    empty = c.get("/play/exp").text
    assert "data-exp-play" in empty and "data-xp-rule" not in empty
    j = c.get("/api/exposure-play/grid", params={"nettingSet": "EXAMPLE", "q": "0.8,0.95", "rule": "interpolated", "collateral": "current", "date": "6M",
                                                 "at": "9M", "ignored": "x"})
    assert j.status_code == 200 and j.json()["figures"]["epe"] == 1.2
    assert sent[-1][:3] == ("GET", "/playground", "alice")
    assert sent[-1][3] == {"nettingSet": "EXAMPLE", "q": "0.8,0.95", "rule": "interpolated", "collateral": "current", "date": "6M", "at": "9M"}
    assert c.get("/api/exposure-play/grid").status_code == 400
    assert c.get("/api/exposure-play/estimate", params={"nettingSet": "NS-X", "paths": "200"}).status_code == 200
    assert sent[-1][1:] == ("/playground/estimate", "alice", {"nettingSet": "NS-X", "paths": "200"})
    x = c.get("/api/exposure-play/export.xlsx", params={"nettingSet": "EXAMPLE", "trades": "All", "collateral": "none"})
    assert x.status_code == 200 and x.content.startswith(b"PK")
    assert sent[-1][0] == "STREAM" and sent[-1][1].startswith("/exposure/playground.xlsx?") and "trades=All" in sent[-1][1] and "collateral=none" in sent[-1][1]
    t = c.get("/api/exposure-play/template.xlsx")
    assert t.status_code == 200 and sent[-1][1] == "/exposure/playground/template"
