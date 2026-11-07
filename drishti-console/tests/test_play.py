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

"""The VaR Playground in the console (PLAY VAR, routes/play_routes.py; VAR_GUIDE.md section 3.10): the command and its forms (a scope in
the VaR explorer's words with its lens and currency, or ids), the page and its grid read from the server as the person, the export and
the empty workbook streamed through, the VaR page's link, FUNC and the type-ahead, and the word only where the edition has it (Drishti
Risk and Trading; not Market or World, DRS-4023). The workbook's own figures against the engine are VarPlaygroundWorkbookTest's and
VarPlaygroundApiTest's (headless LibreOffice, to the cent)."""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from conftest import CONSOLE
from core.app import create_app
from core.auth import Identity
from core.config import load_settings
from plugins.banking.routes.play_routes import play_command
import engine_fakes  # noqa: E402

RISK_ABOUT = {"product": "Drishti Risk", "tagline": "VaR and exposure from your own pricing data", "edition": {"name": "risk", "title": "Drishti Risk"}}
MARKET_ABOUT = {"product": "Drishti Market", "tagline": "Curves", "edition": {"name": "market", "title": "Drishti Market"}}

GRID = {"canonical": "NODE:MGMT~DESK-RATES", "label": "DESK-RATES", "businessDate": "2026-09-30", "currency": "USD", "total": 2, "shown": 2,
        "lens": {"horizon": "1D", "confidence": 0.99, "esConfidence": 0.975}, "positions": [], "figures": {"var": 1.0}, "checks": []}


@pytest.mark.parametrize("typed, where", [
    ("PLAY", "/play"),
    ("PLAY VAR", "/play"),
    ("PLAY VAR BANK", "/play?scope=BANK"),
    ("PLAY VAR DESK RATES <GO>", "/play?scope=DESK%3ARATES"),
    ("PLAY VAR BANK 10D SUM", "/play?scope=BANK&h=10d&m=sum"),
    ("PLAY VAR DESK RATES IN EUR", "/play?scope=DESK%3ARATES&ccy=EUR"),
    ("PLAY VAR POS POS-BOOK-1~SEC-A", "/play?scope=POS%3APOS-BOOK-1~SEC-A"),
    ("PLAY VAR POS-10442 T-1001", "/play?ids=POS-10442%2CT-1001"),
    ("PLAY VAR POS-10442 95", "/play?ids=POS-10442&q=0.95"),
    ("PLAY VAR DESK:RATES + POS:X", "/play?scope=DESK%3ARATES+%2B+POS%3AX"),
    ("PLAY EXP NS-1", None),
    ("VAR BANK", None),
])
def test_the_command_and_its_forms(typed, where):
    assert play_command(typed.split(), "alice") == where


def _app(backend, monkeypatch, about=None, roles=("*",)):
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = backend
    if about:
        assert app.state.editions.learn(about)

    async def current(cookie, _backend):
        return Identity(user=cookie or "alice", display="Alice", desk="Risk", roles=roles)

    monkeypatch.setattr(app.state.auth, "current", current)
    c = TestClient(app)
    c.cookies.set(app.state.auth.cookie, "alice")
    return app, c


class _Upstream:
    """A streamed answer from the stand-in server (the console never holds the workbook whole)."""

    def __init__(self, body: bytes):
        self.body = body
        self.headers = {"content-disposition": 'attachment; filename="var-playground_NODE-MGMT-DESK-RATES_2026-09-30_USD_1D_99.xlsx"'}
        self.closed = False

    async def aiter_bytes(self):
        yield self.body[:2]
        yield self.body[2:]

    async def aclose(self):
        self.closed = True


def _server(backend, monkeypatch, sent: list):
    async def var(verb, path, ident, /, body=None, raw=False, accept="", **params):
        sent.append((verb, path, getattr(ident, "user", None), dict(params)))
        return GRID if path == "/playground" else {"choices": []}

    async def open_download(path, ident):
        sent.append(("STREAM", path, getattr(ident, "user", None), {}))
        return _Upstream(b"PK\x03\x04workbook")

    engine_fakes.patch(monkeypatch, backend, var=var)
    monkeypatch.setattr(backend, "open_download", open_download, raising=False)


def test_the_page_grid_and_export_are_read_as_the_person(backend, monkeypatch):
    sent: list = []
    _server(backend, monkeypatch, sent)
    app, c = _app(backend, monkeypatch, RISK_ABOUT)
    r = c.get("/go", params={"q": "PLAY VAR DESK RATES 10D SUM <GO>"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/play?scope=DESK%3ARATES&h=10d&m=sum"
    page = c.get("/play?scope=DESK:RATES&h=10d&m=sum").text
    assert 'data-play data-scope="DESK:RATES"' in page and 'data-h="10d" data-m="sum"' in page and "drishti_var-playground.js" in page
    assert "Export to Excel" in page and "data-pg-count" in page and "data-pg-formula" in page and "data-pg-explain" in page
    j = c.get("/api/play/grid", params={"scope": "DESK:RATES", "h": "10d", "m": "sum", "q": "0.95", "ccy": "EUR", "view": "pv"})
    assert j.status_code == 200 and j.json()["canonical"] == "NODE:MGMT~DESK-RATES"
    assert sent[-1][:3] == ("GET", "/playground", "alice")
    assert sent[-1][3] == {"scope": "DESK:RATES", "ccy": "EUR", "view": "pv", "horizon": "10d", "method": "sum", "confidence": "0.95"}
    assert c.get("/api/play/grid").status_code == 400                                     # no scope, no ids: said in words
    x = c.get("/api/play/export.xlsx", params={"scope": "DESK:RATES", "count": "all"})
    assert x.status_code == 200 and x.content == b"PK\x03\x04workbook" and "var-playground_" in x.headers["content-disposition"]
    assert sent[-1][0] == "STREAM" and sent[-1][1].startswith("/risk/var/playground.xlsx?") and "count=all" in sent[-1][1]
    t = c.get("/api/play/template.xlsx", params={"positions": 5})
    assert t.status_code == 200 and sent[-1][1] == "/risk/var/playground/template?positions=5&scenarios=509"
    e = c.get("/api/play/estimate", params={"scope": "BANK", "view": "pnl"})
    assert e.status_code == 200 and sent[-1][1] == "/playground/estimate" and sent[-1][3] == {"scope": "BANK"}
    empty = c.get("/play").text
    assert 'name="scope"' in empty and "/api/play/template.xlsx" in empty                # no scope: the form and the empty workbook


def test_the_var_page_link_func_and_the_type_ahead(backend, monkeypatch):
    async def functions(ident=None):
        return {"packs": [{"name": "market-risk", "title": "Market risk", "on": True,
                           "commands": [{"code": "VRES", "label": "VaR result", "kind": "var-result", "example": "VRES DESK:RATES"}]}]}

    monkeypatch.setattr(backend, "functions", functions, raising=False)
    _, viewer = _app(backend, monkeypatch, RISK_ABOUT, roles=("viewer",))                  # a role that does not open VaR (2.9.11.a)
    assert not [x for x in viewer.get("/api/search", params={"in": "command", "q": "PLAY VAR DESK RATES"}).json()["items"] if x.get("mnemonic") == "PLAY"]
    app, c = _app(backend, monkeypatch, RISK_ABOUT, roles=("market-risk",))               # a market-risk role opens VaR: offered
    assert "drishti_play-link.js" in c.get("/var?scope=DESK:RATES").text
    s = [x for x in c.get("/api/search", params={"in": "command", "q": "PLAY VAR DESK RATES"}).json()["items"] if x.get("mnemonic") == "PLAY"]
    assert s and s[0]["href"] == "/play?scope=DESK%3ARATES"
    assert 'data-command="PLAY"' in c.get("/func").text
    app.state.editions.instance.spec["commands"]["words"]["PLAY"] = False
    assert "drishti_play-link.js" not in c.get("/var?scope=DESK:RATES").text
    assert c.get("/play?scope=BANK").status_code == 404


def test_market_has_no_playground(backend, monkeypatch):
    _server(backend, monkeypatch, [])
    app, c = _app(backend, monkeypatch, MARKET_ABOUT)
    for path in ("/play?scope=BANK", "/api/play/grid?scope=BANK", "/api/play/export.xlsx?scope=BANK", "/api/play/template.xlsx"):
        assert c.get(path).status_code == 404, path
    r = c.get("/go", params={"q": "PLAY VAR BANK <GO>"}, follow_redirects=False)
    assert r.status_code == 303 and "DRS-4023" in r.headers["location"]
    assert not [s for s in c.get("/api/search", params={"in": "command", "q": "PLAY VAR BANK"}).json()["items"] if s.get("mnemonic") == "PLAY"]


def test_the_var_page_link_carries_the_pages_date_and_settings():
    """The VaR page's Playground link opens the same view: the page's date (asOf), currency, horizon, measure, confidences and set."""
    js = (CONSOLE / "web" / "static" / "js" / "drishti_play-link.js").read_text(encoding="utf-8")
    for k in ("asOf", "ccy", "h", "m", "q", "esq", "set"):
        assert f'"{k}"' in js, k
