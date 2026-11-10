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

"""The engines' pages in their task's menu, without a browser (the Engines menu removed, owner 2026-11-09): VaR, Why it moved, exposure,
FRTB-SA, SIMM and their playgrounds are under Risk (config/sitemap.yaml, ``requires: engine:<id>``), drawn when the engine runs in the
cluster and the person's role opens it (the catalogue's ``opens:``, plugins/banking/engines.yaml; administrators open all); the same rule
refuses an engine's command words in words and drops them from the type-ahead; switches and status are Admin -> Engines only. Help shows
every running engine's pages to anyone who can open Help and leaves out an engine that runs nowhere (an administrator sees those greyed).
The browser side: test_engine_pages_browser.py."""
from __future__ import annotations

import re

import yaml

from conftest import CONSOLE
from core import engines
from core.auth import Identity
from test_edition import RISK_ABOUT, _app, _client

CATALOGUE = engines.entries()
ROLES = {"alice": ("*",), "mira": ("market-risk",), "cole": ("credit-risk",), "bea": ("bi-analyst",)}
PAGES = {"var": "VAR BANK", "exposure": "/exposure", "frtb-sa": "FRSA BANK", "simm": "SIMM"}     # one page of each engine, as linked


def _people(app, monkeypatch):
    async def current(cookie, _backend):
        user = cookie or "alice"
        return Identity(user=user, display=user.title(), desk="Risk", roles=ROLES.get(user, ("viewer",)))
    monkeypatch.setattr(app.state.auth, "current", current)


def _top(html: str) -> list[str]:
    nav = html[html.index('class="tbar-menus"'):html.index('class="tbar-tools"')]
    return re.findall(r'<span class="tbar-menu-l">([^<]+)</span>', nav)


def _linked(html: str) -> set[str]:
    """The engines one of whose pages the top bar links."""
    nav = html[html.index('class="tbar-menus"'):html.index('class="tbar-tools"')]
    hrefs = {h.replace("%20", " ").replace("+", " ") for h in re.findall(r'href="([^"]+)"', nav)}
    return {e for e, p in PAGES.items() if any(h == p or h.startswith("/go?q=" + p + " ") for h in hrefs)}


def _about(off: set[str]) -> list[dict]:
    return [{"id": e["id"], "title": e["title"], "here": e["id"] not in off, "cluster": e["id"] not in off} for e in CATALOGUE]


def test_no_engines_menu_and_each_engine_page_lives_under_risk_with_its_engine_rule():
    spec = yaml.safe_load((CONSOLE / "config" / "sitemap.yaml").read_text(encoding="utf-8"))
    assert not [m for m in spec["menus"] if m.get("engines") or m["id"] == "engines"]
    risk = next(m for m in spec["menus"] if m["id"] == "risk")
    assert risk.get("without-lens") is True
    titles = [c["title"] for c in risk["columns"]]
    assert titles == ["Risk on my book", "VaR", "Exposure", "Capital and margin"]
    known = {e["id"] for e in CATALOGUE}
    ids = []
    for col in risk["columns"][1:]:
        for it in col["items"]:
            eng = [r.split(":", 1)[1] for r in it["requires"] if r.startswith("engine:")]
            assert len(eng) == 1 and eng[0] in known, it["id"]
            ids.append(it["id"])
    for want in ("var-explorer", "var-explain", "play-var", "smat", "backtesting", "exp-explorer", "frsa", "play-frtb", "simm", "play-simm"):
        assert want in ids, want
    for e in CATALOGUE:
        assert all(str(r).split(":", 1)[0] in ("role", "pack") for r in e.get("opens") or []), e["id"]


def test_the_pre_deal_check_and_fx_volatility_have_one_menu_item_each():
    """Lean (2.9.11.c): Pre-deal check (PREDEAL, the server's pre-deal service since 2.9.11.g) only under Counterparties, FX volatility
    (FXV) only under Volatility."""
    spec = yaml.safe_load((CONSOLE / "config" / "sitemap.yaml").read_text(encoding="utf-8"))
    where: dict[str, list[str]] = {}
    for m in spec["menus"]:
        for col in m.get("columns") or []:
            for it in col.get("items") or []:
                if it.get("cmd"):
                    where.setdefault(it["cmd"], []).append(m["id"])
    assert where["PREDEAL"] == ["counterparties"] and where["FXV"] == ["volatility"] and "XWIF" not in where


def test_an_administrator_has_every_running_engines_pages_under_risk_and_no_engines_menu(backend, monkeypatch):
    engines.reset()
    app = _app(backend, monkeypatch)
    page = _client(app).get("/t").text
    assert "Engines" not in _top(page) and "Risk" in _top(page) and "data-engines" not in page
    assert _linked(page) == {"var", "exposure", "frtb-sa", "simm"}


def test_a_role_sees_only_the_pages_of_the_engines_it_opens(backend, monkeypatch):
    engines.reset()
    app = _app(backend, monkeypatch)
    _people(app, monkeypatch)
    assert _linked(_client(app, "mira").get("/t").text) == {"var", "frtb-sa", "simm"}
    assert _linked(_client(app, "cole").get("/t").text) == {"exposure", "simm"}


def test_a_role_that_opens_no_engine_has_no_engine_page_but_reads_every_running_engines_help(backend, monkeypatch):
    """Product owner, 2026-11-03: Help is documentation, not data; only the engines' pages and commands follow opens:."""
    engines.reset()
    app = _app(backend, monkeypatch)
    _people(app, monkeypatch)
    c = _client(app, "bea")
    page = c.get("/t").text
    assert _linked(page) == set() and "Admin" not in _top(page)
    hub = c.get("/help").text
    assert 'href="/help/section/risk"' in hub and 'href="/help/section/internal"' not in hub
    assert 'href="/help/docs-guides-var-guide"' in hub                                  # the most used row keeps the VaR guide
    risk = c.get("/help/section/risk").text
    for slug in ("docs-guides-var-guide", "docs-guides-exposure-guide", "docs-guides-frtb-sa-guide", "docs-guides-simm-guide"):
        assert f'href="/help/{slug}"' in risk and c.get(f"/help/{slug}").status_code == 200, slug
    assert "data-help-off" not in risk


def test_a_signed_out_visitor_reads_the_running_engines_help(backend, monkeypatch):
    engines.reset()
    app = _app(backend, monkeypatch)

    async def nobody(cookie, _backend):
        return None
    monkeypatch.setattr(app.state.auth, "current", nobody)
    c = _client(app)
    assert c.get("/help/docs-guides-var-guide").status_code == 200
    assert 'href="/help/docs-guides-exposure-guide"' in c.get("/help/section/risk").text


def test_an_engines_commands_follow_the_same_rule_as_its_pages_in_the_menus(backend, monkeypatch):
    """The command words of an engine the role does not open: refused in words on the command line, left out of the type-ahead
    (core/menus.py engine_refused, the catalogue's opens:); an engine it opens, and every engine for an administrator, as before."""
    from urllib.parse import unquote

    from core import menus

    engines.reset()
    app = _app(backend, monkeypatch)
    _people(app, monkeypatch)
    mira = _client(app, "mira")                                                         # market risk: VaR, FRTB-SA, SIMM; not Exposure
    for typed in ("EXP CPTY:CP-ALDERSHOT", "PLAY EXP", "exp bank"):
        r = mira.get("/go", params={"q": typed + " <GO>"}, follow_redirects=False)
        assert r.status_code == 303 and "Exposure is not opened for your role; ask an administrator." in unquote(r.headers["location"]), typed
    bea = _client(app, "bea")
    for typed, title in (("VAR BANK", "VaR"), ("PLAY VAR BANK", "VaR"), ("SMAT DESK:RATES 20", "VaR"), ("FRSA BANK", "FRTB-SA"),
                         ("PLAY SIMM EXAMPLE", "SIMM")):
        r = bea.get("/go", params={"q": typed + " <GO>"}, follow_redirects=False)
        assert f"{title} is not opened for your role" in unquote(r.headers.get("location", "")), typed
    r = mira.get("/go", params={"q": "VAR BANK <GO>"}, follow_redirects=False)
    assert "not opened for your role" not in unquote(r.headers.get("location", ""))
    assert bea.get("/api/search", params={"in": "command", "q": "VAR B"}).json()["items"] == [] and bea.get("/api/search", params={"in": "command", "q": "PLAY"}).json()["items"] == []
    assert mira.get("/api/search", params={"in": "command", "q": "PLAY"}).json()["items"]                        # her VaR Playground is offered
    admin = _client(app)
    r = admin.get("/go", params={"q": "EXP BANK <GO>"}, follow_redirects=False)
    assert "not opened for your role" not in unquote(r.headers.get("location", ""))

    class _Req:                                                                         # the suggestions filter: one rule, by word
        state = type("S", (), {"identity": Identity("mira", "Mira", "", ("market-risk",)), "pack_switcher": []})()
        app = None
    _Req.app = app
    kept = menus.suggestions_opened(_Req(), [{"mnemonic": "EXP", "complete": "EXP "}, {"mnemonic": "VAR", "complete": "VAR "},
                                             {"mnemonic": "TRD", "complete": "TRD "}])
    assert [s["mnemonic"] for s in kept] == ["VAR", "TRD"]


def test_an_engine_running_nowhere_leaves_the_menus_and_an_administrator_sees_its_help_greyed(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    engines.learn(_about({"simm"}))
    try:
        page = _client(app).get("/t").text
        assert _linked(page) == {"var", "exposure", "frtb-sa"} and 'href="/go?q=SIMM' not in page
        risk = _client(app).get("/help/section/risk").text
        assert "data-help-off" in risk and 'href="/help/docs-guides-simm-guide"' not in risk and "SIMM is off" in risk
        _people(app, monkeypatch)
        assert "data-help-off" not in _client(app, "mira").get("/help/section/risk").text  # not an administrator: left out, not greyed
    finally:
        engines.reset()


def test_the_risk_edition_draws_risk_and_counterparties_in_place_of_engines(backend, monkeypatch):
    engines.reset()
    app = _app(backend, monkeypatch)
    app.state.editions.learn(RISK_ABOUT)
    page = _client(app).get("/t").text
    top = [m for m in _top(page) if m not in ("BI", "Build")]
    assert top == ["Views", "Risk", "Counterparties", "Admin", "Help"]
    assert {"var", "exposure"} <= _linked(page)
