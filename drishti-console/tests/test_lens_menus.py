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

"""The lens menus (docs/architecture/LENSES.md section 1.4.4, mock-up docs/lenses/mockups/menus/A): a distribution's menus.top names its top
menus; a lens (a workspace) puts the menus its menus.top names first, in its order, then the rest, and never takes one out; a full
Drishti keeps Views, Risk, BI, Build, Admin and Help. The modules' menus come from their manifests (console.menus); an item shows only
when the distribution keeps its command."""
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


def test_drishti_risk_draws_its_lens_menus_first_with_the_engines_pages(backend, monkeypatch):
    """The Risk menu holds the VaR and exposure engines' pages, each with its command; the risk lens (Drishti Risk's default) puts Risk
    and Counterparties first, then the distribution's other menus (Blotter, Positions, Markets); Build is not in Drishti Risk's bar."""
    app = _app(backend, monkeypatch)
    app.state.editions.learn(RISK_ABOUT)
    page = _client(app).get("/t").text
    assert _drawn(page) == ["Views", "Risk", "Counterparties", "Admin", "Help", "Blotter", "Positions", "Markets"]
    assert "Build" not in _menus(page)
    risk = _panel(page, "Risk")
    assert {"VaR", "Exposure"} <= set(re.findall(r'<div class="mega-title">([^<]+)<', risk))
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


def test_a_lens_puts_its_menus_first_for_that_person_only_and_keeps_the_rest(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    app.state.editions.learn({"product": "Drishti", "edition": None, "lenses": ["risk", "world", "market", "trading"]})
    alice, bob = _client(app), _client(app, "bob")
    expected = {"world": ["Views", "Economies", "Markets", "Companies", "Admin", "Help", "Risk"],
                "market": ["Views", "Curves", "Volatility", "FX", "Credit", "Admin", "Help", "Risk"],
                "trading": ["Views", "Markets", "Blotter", "Positions", "Risk", "Counterparties", "Help", "Admin"]}   # its own first, then the rest
    for lens, menus in expected.items():
        alice.post(f"/lens/{lens}")
        page = alice.get("/t").text
        assert _drawn(page) == menus, lens
        assert "Build" in _menus(page), lens                                             # nothing taken out
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
    assert {"NEWS", "QUOTE", "IDX", "CRV", "EQ"} <= set(_commands(_panel(page, "Markets")))   # World's Markets: every item, none hidden
    alice.post("/lens/trading")
    page = alice.get("/t").text
    assert _commands(_panel(page, "Blotter")) == ["TRD", "CTRD", "PTRD", "LTRD"]
    assert _commands(_panel(page, "Risk"))[:7] == ["VAR", "VLIM", "TVAL", "PNLV", "DPNL", "PNL", "SENS"]   # the lens's order first
    assert {"VAR BANK", "VRES", "EXP"} <= set(_commands(_panel(page, "Risk")))           # then the rest of the menu
    assert _commands(_panel(page, "Counterparties"))[:6] == ["CPTY", "NSET", "LIM", "PREDEAL", "EXP", "CCUBE"]


def test_the_trading_lens_has_one_markets_menu_and_hides_nothing(backend, monkeypatch):
    """Product panel A19 (2026-11-06): the Trading lens is configuration. One Markets menu gathers the market-data screens (Curves,
    Volatility, FX and Credit's columns, then equities and commodities); Admin is drawn after the lens's own menus (a lens never hides)."""
    app = _app(backend, monkeypatch)
    app.state.editions.learn({"product": "Drishti", "edition": None, "lenses": ["trading"]})
    alice = _client(app)
    alice.post("/lens/trading")
    page = alice.get("/t").text
    assert "Admin" in _menus(page) and _menus(page).index("Admin") > _menus(page).index("Counterparties")
    markets = _panel(page, "Markets")
    assert re.findall(r'<div class="mega-title">([^<]+)<', markets)[:5] == ["Rates", "Volatility surfaces", "Foreign exchange", "Credit",
                                                                         "Equities and commodities"]
    assert _commands(markets)[:3] == ["CRV", "REPO", "FIX"] and {"INF", "EQV", "CMDV", "CMDC"} <= set(_commands(markets))
    r = alice.get("/go?q=BI <GO>", follow_redirects=False)                              # BI's own answer (the stand-in holds no BI), not DRS-4023
    assert r.status_code == 303 and "DRS-4023" not in r.headers["location"] and "Rupaka" in unquote(r.headers["location"])
    for typed, to in (("ADMIN", "/admin/users"), ("ADMIN LOADS", "/admin/loads"), ("ADMIN COMPUTE", "/admin/compute")):
        r = alice.get(f"/go?q={typed} <GO>", follow_redirects=False)
        assert r.status_code == 303 and r.headers["location"] == to, (typed, r.headers.get("location"))


def test_admin_by_command_is_for_administrators_only(backend, monkeypatch):
    app = _app(backend, monkeypatch)

    async def trader(cookie, _backend):
        return Identity(user="tom", display="Tom", desk="Rates", roles=("trader",))
    monkeypatch.setattr(app.state.auth, "current", trader)
    r = _client(app, "tom").get("/go?q=ADMIN <GO>", follow_redirects=False)
    assert r.status_code == 303 and "ADMIN%20is%20for%20administrators" in r.headers["location"]


def test_every_lens_menu_exists_and_every_subject_item_runs_a_command():
    """Every menu a lens names exists (the kernel's sitemap with the modules' menus from their manifests), and each subject item runs a
    command it requires (cmd: or word:), so a menu never holds what the command line does not."""
    from core.edition import Edition
    from core.menus import spec_of
    menus = {m["id"]: m for m in spec_of(CONSOLE)["menus"]}
    for name in ("risk", "trading", "market", "world"):
        ed = Edition(name, yaml.safe_load(next((ROOT / "config" / "lenses").glob(f"*/{name}.yaml")).read_text(encoding="utf-8")), lens=True)
        top = ed.menu("top")
        assert top and top[0] == "views", name
        for mid in top:
            assert mid in menus, (name, mid)
    for mid, menu in menus.items():
        if not menu.get("subject"):
            continue
        for col in menu["columns"]:
            for it in col["items"]:
                words = [str(r).split(":", 1)[1] for r in it.get("requires") or [] if str(r).startswith(("cmd:", "word:"))]
                assert words and (it.get("cmd") or it.get("fill")), (mid, it["id"])
