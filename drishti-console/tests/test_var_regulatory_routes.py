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

"""Regulatory VaR's screens (routes/var_regulatory_routes.py; VAR_GUIDE.md sections 18.10, 18.11 and 18.14): the commands VAR <scope> BACKTEST,
VAR <scope> STATS <period> and VAR REPORT <report> <period> open their pages; the pages are shells; their JSON goes to the server's VaR
engine as the person, with the parameters checked and nothing else passed on; a file comes back as the server made it."""
from routes.var_routes import var_command
from routes.var_regulatory_routes import regulatory_command


def test_the_commands_open_their_pages():
    assert var_command("VAR BANK BACKTEST".split(), "asha") == "/var/backtesting?scope=BANK"
    assert var_command("VAR DESK RATES BACKTEST <GO>".split(), "asha") == "/var/backtesting?scope=DESK%3ARATES"
    assert var_command("VAR NODE MGMT~DIV-MARKETS BACKTEST".split(), "asha") == "/var/backtesting?scope=NODE%3AMGMT~DIV-MARKETS"
    assert var_command("VAR BANK STATS FY2026".split(), "asha") == "/var/statistics?scope=BANK&period=FY2026"
    assert var_command("var bank stats fy2026q1".split(), "asha") == "/var/statistics?scope=BANK&period=FY2026Q1"
    assert var_command("VAR REPORT annual-report-var FY2026".split(), "asha") == "/var/reports/annual-report-var?period=FY2026"
    assert var_command("VAR REPORTS".split(), "asha") == "/var/reports"
    assert var_command("VAR BANK".split(), "asha") == "/var?scope=BANK"                       # the explorer as before
    assert regulatory_command("VAR POS X BACKTEST".split()) is None                           # a position has no official record


def test_the_pages_are_shells(client):
    for url, marker in (("/var/backtesting?scope=BANK", "data-backtesting"), ("/var/statistics?scope=BANK&period=FY2026", "data-statistics"),
                        ("/var/reports", "data-reports"), ("/var/reports/annual-report-var?period=FY2026", 'data-report="annual-report-var"')):
        r = client.get(url)
        assert r.status_code == 200 and marker in r.text, url
    assert client.get("/var/reports/Bad Name").status_code == 404


def test_the_json_goes_to_the_engine_as_the_person(client, backend, monkeypatch):
    seen = []

    async def var(method, path, ident, body=None, raw=False, **params):
        seen.append((method, path, body, params))
        if raw:
            return b"PK\x03\x04", {"content-disposition": 'attachment; filename="x.xlsx"'}
        return {"ok": 1}
    monkeypatch.setattr(backend, "var", var, raising=False)
    assert client.get("/api/var/backtesting?scope=DESK:RATES").json() == {"ok": 1}
    assert seen[-1] == ("GET", "/backtesting", None, {"scope": "DESK:RATES", "asOf": ""})
    r = client.get("/api/var/backtesting?scope=BANK&format=xlsx")
    assert r.status_code == 200 and r.content.startswith(b"PK")
    client.get("/api/var/statistics?scope=BANK&period=FY2026")
    assert seen[-1][1] == "/statistics" and seen[-1][3]["period"] == "FY2026"
    client.post("/api/var/reports/annual-report-var/runs?period=FY2026", json={})
    assert seen[-1][:2] == ("POST", "/reports/annual-report-var/runs")
    client.post("/api/var/reports/annual-report-var/runs/FY2026/signoff", json={"version": 2, "action": "approve", "step": "checker",
                                                                                "comment": "ok", "extra": "dropped"})
    assert seen[-1][2] == {"version": 2, "action": "approve", "step": "checker", "comment": "ok"}
    r = client.get("/api/var/reports/annual-report-var/runs/FY2026/file?format=pdf&draft=true")
    assert r.status_code == 200 and r.headers["content-type"] == "application/pdf"
    assert client.get("/api/var/reports/annual-report-var/runs/FY2026/file?format=exe").status_code == 400
    assert client.get("/api/var/reports/annual-report-var/runs/last-year").status_code == 400
