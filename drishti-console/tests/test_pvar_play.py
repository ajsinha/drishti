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

"""The VaR Playground's Parametric tab (docs/architecture/PARAMETRIC_VAR.md section 10): the page has two tabs, VaR (the grid) and
Parametric (measure=parametric, the same link otherwise); the Parametric tab carries the panel; the grid and the workbook ask the engine
with measure=parametric. PLAY PVAR is gone: no command, no menu item."""
from plugins.banking.routes.play_routes import play_command
from test_play import RISK_ABOUT, _app, _server


def test_play_pvar_is_not_a_command():
    assert play_command("PLAY PVAR BANK".split(), "alice") in (None, "/play")


def test_the_parametric_tab_asks_for_the_parametric_measure(backend, monkeypatch):
    sent: list = []
    _server(backend, monkeypatch, sent)
    app, c = _app(backend, monkeypatch, RISK_ABOUT)
    plain = c.get("/play?scope=DESK:RATES&h=10d").text
    assert "data-pg-tabs" in plain and "data-pg-param" not in plain                  # the VaR tab: the grid, historical only
    assert 'href="/play?scope=DESK%3ARATES&amp;h=10d&amp;measure=parametric"' in plain or 'href="/play?scope=DESK:RATES&amp;h=10d&amp;measure=parametric"' in plain
    page = c.get("/play?scope=DESK:RATES&h=10d&measure=parametric").text
    assert 'data-measure="parametric"' in page and "data-pg-param" in page and "pg-on-parametric" in page
    assert 'aria-selected="true" data-pg-tab-parametric' in page and "PLAY PVAR" not in page
    c.get("/api/play/grid", params={"scope": "DESK:RATES", "measure": "parametric"})
    assert sent[-1][3].get("measure") == "parametric"
    c.get("/api/play/export.xlsx", params={"scope": "DESK:RATES", "measure": "parametric"})
    assert sent[-1][0] == "STREAM" and "measure=parametric" in sent[-1][1]
