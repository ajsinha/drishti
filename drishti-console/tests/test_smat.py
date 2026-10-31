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

"""The scenario matrix in the console (SMAT, routes/smat_routes.py; VAR_GUIDE.md section 3.9): the command and its spaced forms, the
page and its JSON, Arrow, CSV and Arrow file read from the server as the person, gaps said in the export (never zeros), the links on the
entity pages and the VaR page offered only where the word is (Drishti Risk and Trading; not Market or World, DRS-4023), FUNC and the
type-ahead. The server's own logic (the values against the stored vectors, PV to P&L, ranks, gaps, masks) is VarMatrixApiTest's."""
from __future__ import annotations

import csv
import io

import pytest
from fastapi.testclient import TestClient

from conftest import CONSOLE
from core import arrow_answer
from core.app import create_app
from core.auth import Identity
from core.config import load_settings
from routes.smat_routes import smat_command, to_csv

RISK_ABOUT = {"product": "Drishti Risk", "tagline": "VaR and exposure from your own pricing data", "edition": {"name": "risk", "title": "Drishti Risk"}}
MARKET_ABOUT = {"product": "Drishti Market", "tagline": "Curves", "edition": {"name": "market", "title": "Drishti Market"}}
WORLD_ABOUT = {"product": "Drishti World", "tagline": "Economies", "edition": {"name": "world", "title": "Drishti World"}}

#: a matrix as the server answers it: three dates, the oldest a gap (refused), a trade's P&L vector
ANSWER = {
    "id": "T1", "scope": "TRD:T1", "kind": "trade", "label": "T1", "entity": "T1", "supplied": False, "currency": "USD", "days": 3, "asked": 3,
    "maxDays": 60, "confidence": 0.99, "esConfidence": 0.975, "horizon": 1, "window": "all", "by": "scenario",
    "labels": {"var": "VaR 99% 1-day", "es": "ES 97.5% 1-day", "basePv": "Base PV"},
    "dates": [
        {"date": "2026-09-28", "source": "engine", "gap": "DRS-5203 the VaR inputs of 2026-09-28 are not loaded: 2 batch ids", "code": "DRS-5203",
         "scenarios": 0},
        {"date": "2026-09-29", "source": "lake", "gap": None, "scenarios": 2, "var": 800.0, "es": 800.0, "k": 1, "esCount": 1,
         "varScenario": "2026-08-31", "basePv": 1000.0, "tail": [{"scenario": "2026-08-31", "rank": 1, "pnl": -800.0}],
         "batch": "B-2026-09-29-01", "link": "/v/pnl-vector/PNL-T1-HS-20"},
        {"date": "2026-09-30", "source": "open", "gap": None, "scenarios": 2, "var": 1000.0, "es": 1000.0, "k": 1, "esCount": 1,
         "varScenario": "2026-08-31", "basePv": 1000.0, "tail": [{"scenario": "2026-08-31", "rank": 1, "pnl": -1000.0}]},
    ],
    "rows": [
        {"scenario": "2026-08-31", "2026-09-28": None, "2026-09-29": -800.0, "2026-09-30": -1000.0},
        {"scenario": "2026-09-01", "2026-09-28": None, "2026-09-29": 160.0, "2026-09-30": 200.0},
    ],
}


@pytest.mark.parametrize("typed, where", [
    ("SMAT", "/smat"),
    ("SMAT T1", "/smat?id=T1"),
    ("SMAT T1 30 <GO>", "/smat?id=T1&days=30"),
    ("smat POS-BOOK-1~SEC-A 5", "/smat?id=POS-BOOK-1~SEC-A&days=5"),
    ("SMAT DESK:RATES 20", "/smat?id=DESK%3ARATES&days=20"),
    ("SMAT DESK RATES", "/smat?id=DESK%3ARATES"),
    ("SMAT BOOK BOOK-RATES-1 30", "/smat?id=BOOK%3ABOOK-RATES-1&days=30"),
    ("SMAT POS POS-BOOK-1~SEC-A", "/smat?id=POS-BOOK-1~SEC-A"),
    ("SMAT TRD MX-20000274 10", "/smat?id=MX-20000274&days=10"),
    ("SMAT BANK", "/smat?id=BANK"),
    ("SMAT DESK RATES 20 IN EUR", "/smat?id=DESK%3ARATES&days=20&ccy=EUR"),
    ("SMAT PNL-T1-HS-509", "/smat?id=PNL-T1-HS-509"),
    ("VAR BANK", None),
])
def test_the_command_and_its_spaced_forms(typed, where):
    assert smat_command(typed.split()) == where


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


def _var(backend, monkeypatch, sent: list):
    """The stand-in server's VaR engine: the matrix above, as JSON (the console writes Arrow itself when the server answers JSON)."""
    async def var(method, path, ident, body=None, raw=False, accept="", **params):
        sent.append((method, path, getattr(ident, "user", None), accept, dict(params)))
        if path != "/matrix":
            return {}
        if raw:
            import json
            return json.dumps(ANSWER).encode(), {"content-type": "application/json"}
        return ANSWER

    monkeypatch.setattr(backend, "var", var, raising=False)


def test_the_page_and_its_answers_are_read_as_the_person(backend, monkeypatch):
    sent: list = []
    _var(backend, monkeypatch, sent)
    app, c = _app(backend, monkeypatch, RISK_ABOUT)
    r = c.get("/go", params={"q": "SMAT T1 3 <GO>"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/smat?id=T1&days=3"
    page = c.get("/smat?id=T1&days=3").text
    assert 'data-smat data-target="T1" data-days="3"' in page and "scenario-matrix.js" in page and "Arrow.es2015.min.js" in page
    assert "Only tail scenarios" in page and "Change vs day before" in page and 'data-sm-csv' in page
    j = c.get("/api/smat/matrix", params={"id": "T1", "days": "3", "confidence": "0.95"})
    assert j.status_code == 200 and j.json()["scope"] == "TRD:T1"
    assert sent[-1][:3] == ("GET", "/matrix", "alice") and sent[-1][4] == {"id": "T1", "days": "3", "confidence": "0.95"}
    a = c.get("/api/smat/matrix", params={"id": "T1", "days": "3"}, headers={"Accept": arrow_answer.MEDIA})
    assert a.headers["content-type"].startswith(arrow_answer.MEDIA) and sent[-1][3] == arrow_answer.MEDIA
    back = arrow_answer.decode(a.content)
    assert back["rows"] == ANSWER["rows"] and back["dates"] == ANSWER["dates"]          # nulls stay nulls, never zeros
    assert c.get("/api/smat/matrix").status_code == 400                                # no id: said in words


def test_the_csv_and_arrow_exports_say_the_gaps_and_never_write_zeros(backend, monkeypatch):
    _var(backend, monkeypatch, [])
    app, c = _app(backend, monkeypatch, RISK_ABOUT)
    r = c.get("/api/smat/matrix.csv", params={"id": "T1", "days": "3"})
    assert r.status_code == 200 and "attachment" in r.headers["content-disposition"] and "scenario-matrix-TRD-T1-2026-09-30.csv" in r.headers["content-disposition"]
    rows = list(csv.reader(io.StringIO(r.text)))
    assert rows[0] == ["scenario", "2026-09-28", "2026-09-29", "2026-09-30"]
    assert rows[1] == ["2026-08-31", "", "-800.0", "-1000.0"]                         # the gap's cell empty, not 0
    by = {row[0]: row[1:] for row in rows}
    assert by["VaR 99% 1-day"] == ["", "800.0", "1000.0"] and by["Base PV"] == ["", "1000.0", "1000.0"]
    assert by["source"] == ["engine", "lake", "open"] and by["gap"][0].startswith("DRS-5203")
    f = c.get("/api/smat/matrix.arrow", params={"id": "T1"})
    assert f.headers["content-type"].startswith(arrow_answer.MEDIA) and ".arrow" in f.headers["content-disposition"]
    assert arrow_answer.decode(f.content)["rows"] == ANSWER["rows"]
    assert to_csv({**ANSWER, "by": "trade", "rows": [{"trade": "T1", "2026-09-30": -1.5}], "dates": [{"date": "2026-09-30", "total": -1.5}]}) \
        .splitlines()[:3] == ["trade,2026-09-30", "T1,-1.5", "total,-1.5"]


@pytest.mark.parametrize("about", [MARKET_ABOUT, WORLD_ABOUT])
def test_market_and_world_have_no_scenario_matrix(backend, monkeypatch, about):
    _var(backend, monkeypatch, [])
    app, c = _app(backend, monkeypatch, about)
    for path in ("/smat?id=T1", "/api/smat/matrix?id=T1", "/api/smat/matrix.csv?id=T1", "/api/smat/matrix.arrow?id=T1"):
        r = c.get(path)
        assert r.status_code == 404, path
    assert "SMAT is not part of" in c.get("/api/smat/matrix?id=T1").json()["detail"]
    r = c.get("/go", params={"q": "SMAT T1 <GO>"}, follow_redirects=False)
    assert r.status_code == 303 and "DRS-4023" in r.headers["location"]
    assert not [s for s in c.get("/api/suggest", params={"q": "SMAT T1"}).json() if s.get("mnemonic") == "SMAT"]
    assert "data-smat-link" not in c.get("/v/trade/IRS-48213").text                  # no link that would only answer DRS-4023


def test_the_links_follow_the_edition_and_the_lens(backend, monkeypatch):
    app, c = _app(backend, monkeypatch, RISK_ABOUT)
    view = c.get("/v/trade/IRS-48213").text
    assert 'data-smat-link href="/smat?id=IRS-48213"' in view
    assert "smat-link.js" in c.get("/var?scope=DESK:RATES").text                      # the VaR page's link, added to its header strip
    s = [x for x in c.get("/api/suggest", params={"q": "SMAT DESK RATES 20"}).json() if x.get("mnemonic") == "SMAT"]
    assert s and s[0]["href"] == "/smat?id=DESK%3ARATES&days=20"
    # the same instance with the word switched off (a site's edition.commands.words.SMAT: off): no link, no script, the word refused
    app.state.editions.instance.spec["commands"]["words"]["SMAT"] = False
    assert "data-smat-link" not in c.get("/v/trade/IRS-48213").text
    assert "smat-link.js" not in c.get("/var?scope=DESK:RATES").text
    assert c.get("/smat?id=T1").status_code == 404


def test_func_lists_smat_with_the_market_risk_pack_where_the_word_is(backend, monkeypatch):
    async def functions(ident=None):
        return {"packs": [{"name": "market-risk", "title": "Market risk", "on": True,
                           "commands": [{"code": "VRES", "label": "VaR result", "kind": "var-result", "example": "VRES DESK:RATES"}]}]}

    monkeypatch.setattr(backend, "functions", functions, raising=False)
    app, c = _app(backend, monkeypatch, RISK_ABOUT, roles=("viewer",))
    page = c.get("/func").text
    assert 'data-command="SMAT"' in page and "SMAT DESK:RATES 20" in page
    app2, c2 = _app(backend, monkeypatch, MARKET_ABOUT, roles=("viewer",))
    assert 'data-command="SMAT"' not in c2.get("/func").text


def test_a_market_or_world_lens_on_a_full_instance_hides_it_for_that_person_only(backend, monkeypatch):
    from test_edition import _app as edition_app, _client as edition_client

    app = edition_app(backend, monkeypatch)
    app.state.editions.learn({"product": "Drishti", "edition": None, "lenses": ["risk", "market", "world"]})
    alice, bob = edition_client(app, "alice"), edition_client(app, "bob")
    for lens in ("market", "world"):
        assert alice.get(f"/lens/{lens}", follow_redirects=False).status_code == 303
        assert "data-smat-link" not in alice.get("/v/trade/IRS-48213").text, lens
        assert alice.get("/smat?id=T1").status_code == 404, lens
        r = alice.get("/go", params={"q": "SMAT T1 <GO>"}, follow_redirects=False)
        assert "DRS-4023" in r.headers["location"], lens
    assert alice.get("/lens/risk", follow_redirects=False).status_code == 303
    assert "data-smat-link" in alice.get("/v/trade/IRS-48213").text and alice.get("/smat?id=T1").status_code == 200
    assert "data-smat-link" in bob.get("/v/trade/IRS-48213").text                     # bob, on the full product, has it throughout
