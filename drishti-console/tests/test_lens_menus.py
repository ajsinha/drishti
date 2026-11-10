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

"""The lens menus (docs/architecture/LENSES.md section 1.4.4, mock-up docs/lenses/mockups/menus/A): each edition's own subject menus in the top bar, named by its menus.top in
order; a full Drishti keeps Views, Engines, BI, Build, Admin and Help (the Engines menu in place of the old VaR and Exposure menus,
2.9.11.a); an item shows only when the edition keeps its command."""
from __future__ import annotations

import re
from urllib.parse import unquote

import yaml

from conftest import CONSOLE
from core.auth import Identity
from test_edition import RISK_ABOUT, _app, _client

ROOT = CONSOLE.parent


def _menus(html: str) -> list[str]:
    nav = html[html.index('class="tbar-menus"'):html.index('class="tbar-tools"')]
    return re.findall(r'<span class="tbar-menu-l">([^<]+)</span>', nav)


def _drawn(html: str) -> list[str]:
    """The top menus, without BI and Build (each follows the person's powers and the features, as before)."""
    return [m for m in _menus(html) if m not in ("BI", "Build")]


def _panel(html: str, label: str) -> str:
    nav = html[html.index('class="tbar-menus"'):html.index('class="tbar-tools"')]
    start = nav.index(f'<span class="tbar-menu-l">{label}</span>')
    end = nav.find('<div class="dropdown tbar-menu"', start)   # a subject menu also says data-subject (the phone's lens menu)
    return nav[start:end if end > 0 else len(nav)]


def _commands(panel: str) -> list[str]:
    return re.findall(r'<code class="mega-cmd mono">([^<]+)</code>', panel)


def test_a_full_drishti_keeps_its_menus_with_risk_after_views(backend, monkeypatch):
    """The Engines menu removed (owner 2026-11-09): the engines' pages live under Risk, drawn in the full product's bar too."""
    app = _app(backend, monkeypatch)
    app.state.editions.learn({"product": "Drishti", "edition": None, "lenses": []})
    page = _client(app).get("/t").text
    assert _drawn(page) == ["Views", "Risk", "Admin", "Help"] and "Build" in _menus(page)
    assert _menus(page)[:2] == ["Views", "Risk"]


def test_the_risk_edition_draws_risk_and_counterparties_with_the_engines_pages(backend, monkeypatch):
    """The Risk menu holds the VaR and exposure engines' pages, each with its command (the Engines menu removed, owner 2026-11-09)."""
    app = _app(backend, monkeypatch)
    app.state.editions.learn(RISK_ABOUT)
    page = _client(app).get("/t").text
    assert _drawn(page) == ["Views", "Risk", "Counterparties", "Admin", "Help"] and "Build" not in _menus(page)   # Build is off in Risk
    risk = _panel(page, "Risk")
    assert re.findall(r'<div class="mega-title">([^<]+)<', risk) == ["Risk on my book", "VaR", "Exposure"]
    cmds = _commands(risk)
    for c in ("VAR BANK", "VAR BANK EXPLAIN", "VRES", "VOFF", "SMAT", "VLIM", "SWIN BANK", "EXP"):
        assert c in cmds, c
    assert 'href="/go?q=SWIN%20BANK%20%3CGO%3E"' in risk and 'href="/smat"' in risk and 'href="/var"' in risk
    assert 'data-fill="EXP CPTY"' in risk and 'href="/t?cmd=EXP%20CPTY"' in risk
    assert "XWIF" not in cmds and "PREDEAL" not in cmds          # one place: Counterparties → Pre-deal check


def test_an_item_for_a_page_this_build_lacks_is_not_drawn(backend, monkeypatch):
    from core.menus import _route_paths

    app = _app(backend, monkeypatch)
    app.state.editions.learn(RISK_ABOUT)
    paths = _route_paths(app.routes)
    assert "/var" in paths and "/t" in paths                       # every included router is walked (FastAPI 0.142 keeps them whole)
    eng = _panel(_client(app).get("/t").text, "Risk")
    assert ("PLAY VAR" in _commands(eng)) == ("/play" in paths)
    assert ("VAR BANK BACKTEST" in _commands(eng)) == ("/var/backtesting" in paths)


def test_a_lens_draws_its_own_subject_menus_for_that_person_only(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    app.state.editions.learn({"product": "Drishti", "edition": None, "lenses": ["risk", "world", "market", "trading"]})
    alice, bob = _client(app), _client(app, "bob")
    expected = {"world": ["Views", "Economies", "Markets", "Companies", "Admin", "Help"],
                "market": ["Views", "Curves", "Volatility", "FX", "Credit", "Admin", "Help"],
                "trading": ["Views", "Markets", "Blotter", "Positions", "Risk", "Counterparties", "Help"]}   # BI and Admin by command
    for lens, menus in expected.items():
        alice.post(f"/lens/{lens}")
        page = alice.get("/t").text
        assert _drawn(page) == menus, lens
        assert _drawn(bob.get("/t").text) == ["Views", "Risk", "Admin", "Help"]
    alice.post("/lens/market")
    page = alice.get("/t").text
    assert _commands(_panel(page, "Curves")) == ["CRV", "REPO", "FIX", "INF"]
    assert _commands(_panel(page, "Volatility")) == ["IRV", "CPV", "FXV", "EQV", "CMDV"]
    assert _commands(_panel(page, "FX")) == ["FX", "FXF"] and _commands(_panel(page, "Credit")) == ["CDS", "BND"]
    alice.post("/lens/world")
    page = alice.get("/t").text
    assert _commands(_panel(page, "Economies")) == ["ECON …", "MACRO", "FCST", "REGE"]
    assert _commands(_panel(page, "Companies")) == ["USEQ", "EDGAR", "LEIC", "FUND"]
    assert _commands(_panel(page, "Markets")) == ["NEWS", "QUOTE", "IDX"]                # World's Markets: news and quotes only
    alice.post("/lens/trading")
    page = alice.get("/t").text
    assert _commands(_panel(page, "Blotter")) == ["TRD", "CTRD", "PTRD", "LTRD"]
    assert _commands(_panel(page, "Risk"))[:7] == ["VAR", "VLIM", "TVAL", "PNLV", "DPNL", "PNL", "SENS"]
    assert _commands(_panel(page, "Counterparties")) == ["CPTY", "NSET", "LIM", "PREDEAL", "EXP", "CCUBE"]   # PREDEAL: Counterparties → Pre-deal check   # CCUBE: the Pivot's preset ccr, under construction


def test_the_trading_lens_has_one_markets_menu_and_no_bi_or_admin_menu(backend, monkeypatch):
    """Product panel A19 (2026-11-06): the Trading lens is configuration. One Markets menu gathers the market-data screens (Curves,
    Volatility, FX and Credit's columns, then equities and commodities), PNL, DPNL, SENS, FXF, IRV, CPV, FIX and REPO are shown, and BI
    and Admin are reached by command only."""
    app = _app(backend, monkeypatch)
    app.state.editions.learn({"product": "Drishti", "edition": None, "lenses": ["trading"]})
    alice = _client(app)
    alice.post("/lens/trading")
    page = alice.get("/t").text
    assert "BI" not in _menus(page) and "Admin" not in _menus(page)
    markets = _panel(page, "Markets")
    assert re.findall(r'<div class="mega-title">([^<]+)<', markets) == ["Rates", "Volatility surfaces", "Foreign exchange", "Credit",
                                                                     "Equities and commodities"]
    assert _commands(markets) == ["CRV", "REPO", "FIX", "IRV", "CPV", "FXV", "FX", "FXF", "CDS", "BND", "EQ", "EQX", "CMD", "CMDC"]
    r = alice.get("/go?q=BI <GO>", follow_redirects=False)                              # BI's own answer (the stand-in holds no BI), not DRS-4023
    assert r.status_code == 303 and "DRS-4023" not in r.headers["location"] and "Rupaka" in unquote(r.headers["location"])
    for typed, to in (("ADMIN", "/admin/users"), ("ADMIN LOADS", "/admin/loads")):
        r = alice.get(f"/go?q={typed} <GO>", follow_redirects=False)
        assert r.status_code == 303 and r.headers["location"] == to, (typed, r.headers.get("location"))
    r = alice.get("/go?q=ADMIN COMPUTE <GO>", follow_redirects=False)                   # a page the lens does not keep: said, not opened
    assert "DRS-4023" in r.headers["location"]


def test_admin_by_command_is_for_administrators_only(backend, monkeypatch):
    app = _app(backend, monkeypatch)

    async def trader(cookie, _backend):
        return Identity(user="tom", display="Tom", desk="Rates", roles=("trader",))
    monkeypatch.setattr(app.state.auth, "current", trader)
    r = _client(app, "tom").get("/go?q=ADMIN <GO>", follow_redirects=False)
    assert r.status_code == 303 and "ADMIN%20is%20for%20administrators" in r.headers["location"]


def test_every_subject_item_runs_a_command_its_edition_shows():
    """Every subject menu an edition names exists, and each of its items' commands is one the edition allows or a word it keeps (else
    the item would never show): the menus never hold what the command line does not."""
    spec = yaml.safe_load((CONSOLE / "config" / "sitemap.yaml").read_text(encoding="utf-8"))
    menus = {m["id"]: m for m in spec["menus"]}
    from core.edition import Edition
    for name in ("risk", "trading", "market", "world"):
        ed = Edition(name, yaml.safe_load(next((ROOT / "config" / "lenses").glob(f"*/{name}.yaml")).read_text(encoding="utf-8")))
        top = ed.menu("top")
        assert top and top[0] == "views", name
        for mid in top:
            assert mid in menus, (name, mid)
            if not menus[mid].get("subject"):
                continue
            gathered = [c for g in menus[mid].get("gather") or [] for c in menus[g]["columns"]]   # the Markets menu's (core/menus.py _columns)
            for col in gathered + menus[mid]["columns"]:
                for it in col["items"]:
                    if not ed.menu_shown(mid, it["id"]):                                # the edition's menus.<id> picks the items it shows
                        continue
                    words = [str(r).split(":", 1)[1] for r in it.get("requires") or [] if str(r).startswith(("cmd:", "word:"))]
                    assert words, (name, it["id"])
                    if not any(str(r).startswith("route:") for r in it.get("requires") or []):
                        assert all(ed.typed_shown(w) for w in words), (name, it["id"])
                    assert it.get("cmd") or it.get("fill"), it["id"]
