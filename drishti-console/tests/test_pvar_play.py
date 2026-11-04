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

"""PLAY PVAR (docs/architecture/PARAMETRIC_VAR.md section 10): the VaR Playground with the parametric measure: the command keeps the scope,
the lens and the ids as PLAY VAR reads them and adds measure=parametric; the page carries the Parametric panel; the grid and the workbook ask
the engine with measure=parametric."""
import pytest

from routes.play_routes import play_command
from test_play import RISK_ABOUT, _app, _server


@pytest.mark.parametrize("typed, where", [
    ("PLAY PVAR", "/play?scope=BANK&measure=parametric"),
    ("PLAY PVAR BANK <GO>", "/play?scope=BANK&measure=parametric"),
    ("PLAY PVAR DESK RATES 10D SUM", "/play?scope=DESK%3ARATES&h=10d&m=sum&measure=parametric"),
    ("PLAY PVAR POS-10442 T-1001", "/play?ids=POS-10442%2CT-1001&measure=parametric"),
])
def test_the_command(typed, where):
    assert play_command(typed.split(), "alice") == where


def test_the_page_grid_and_workbook_ask_for_the_parametric_measure(backend, monkeypatch):
    sent: list = []
    _server(backend, monkeypatch, sent)
    app, c = _app(backend, monkeypatch, RISK_ABOUT)
    r = c.get("/go", params={"q": "PLAY PVAR DESK RATES <GO>"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/play?scope=DESK%3ARATES&measure=parametric"
    page = c.get("/play?scope=DESK:RATES&measure=parametric").text
    assert 'data-measure="parametric"' in page and "data-pg-param" in page and "PLAY PVAR" in page and "Parametric VaR Playground" in page
    assert "data-pg-param" not in c.get("/play?scope=DESK:RATES").text                 # PLAY VAR: historical only
    c.get("/api/play/grid", params={"scope": "DESK:RATES", "measure": "parametric"})
    assert sent[-1][3].get("measure") == "parametric"
    c.get("/api/play/export.xlsx", params={"scope": "DESK:RATES", "measure": "parametric"})
    assert sent[-1][0] == "STREAM" and "measure=parametric" in sent[-1][1]
