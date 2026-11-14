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


def _column(html: str, title: str) -> str:
    """One column of the Domains menu (or of the one domain's menu), by its title."""
    nav = html[html.index('class="tbar-menus"'):html.index('class="tbar-tools"')]
    start = nav.index(f'<div class="mega-title">{title}</div>')
    end = nav.find('<div class="mega-col">', start)
    return nav[start:end if end > 0 else len(nav)]


def _column_titles(html: str, label: str) -> list[str]:
    return re.findall(r'<div class="mega-title">([^<]+)<', _panel(html, label))


def _commands(panel: str) -> list[str]:
    return re.findall(r'<code class="mega-cmd mono">([^<]+)</code>', panel)


def test_a_full_drishti_keeps_views_domains_create_admin_and_help(backend, monkeypatch):
    """The top bar (owner 2026-11-14, mock-up docs/lenses/mockups/navigation A): the modules' menus are columns of Domains, BI and Build are Create."""
    app = _app(backend, monkeypatch)
    app.state.editions.learn({"product": "Drishti", "edition": None, "lenses": []})
    page = _client(app).get("/t").text
    assert _menus(page) == ["Views", "Domains", "Create", "Admin", "Help"]


def test_drishti_risk_has_one_risk_menu_with_the_engines_pages(backend, monkeypatch):
    """One domain active: Domains is named Risk. Its columns hold the VaR and exposure engines' pages, each with its command; Build is
    in Create, not a menu of its own."""
    app = _app(backend, monkeypatch)
    app.state.editions.learn(RISK_ABOUT)
    page = _client(app).get("/t").text
    assert _menus(page) == ["Views", "Risk", "Create", "Admin", "Help"]
    risk = _panel(page, "Risk")
    assert {"Risk · VaR", "Risk · Exposure", "Blotter", "Positions", "Counterparties"} <= set(_column_titles(page, "Risk"))
    cmds = _commands(risk)
    for c in ("VAR BANK", "VAR BANK EXPLAIN", "VRES", "VOFF", "SMAT", "VLIM", "SWIN BANK", "EXP"):
        assert c in cmds, c
    assert 'href="/go?q=SWIN%20BANK%20%3CGO%3E"' in risk and 'href="/smat"' in risk and 'href="/var"' in risk
    assert 'data-fill="EXP CPTY"' in risk and 'href="/t?cmd=EXP%20CPTY"' in risk
    assert "XWIF" not in cmds and cmds.count("PREDEAL") == 1 and "PREDEAL" in _commands(_column(page, "Counterparties"))   # one place


def test_an_item_for_a_page_this_build_lacks_is_not_drawn(backend, monkeypatch):
    from core.menus import _route_paths

    app = _app(backend, monkeypatch)
    app.state.editions.learn(RISK_ABOUT)
    paths = _route_paths(app.routes)
    assert "/var" in paths and "/t" in paths                       # every included router is walked (FastAPI 0.142 keeps them whole)
    eng = _panel(_client(app).get("/t").text, "Risk")
    assert ("PLAY VAR" in _commands(eng)) == ("/play" in paths)
    assert ("VAR BANK BACKTEST" in _commands(eng)) == ("/var/backtesting" in paths)


def test_a_lens_puts_its_columns_first_for_that_person_only_and_keeps_the_rest(backend, monkeypatch):
    """A lens arranges Domains' columns (the menus its menus.top names, in its order, then the rest); it never takes one out."""
    app = _app(backend, monkeypatch)
    app.state.editions.learn({"product": "Drishti", "edition": None, "lenses": ["risk", "world", "market", "trading"]})
    alice, bob = _client(app), _client(app, "bob")
    plain = _column_titles(bob.get("/t").text, "Domains")
    expected = {"world": ["Economies", "Markets · Rates", "Companies"],
                "market": ["Curves", "Volatility", "FX", "Credit"],
                "trading": ["Markets · Rates", "Blotter", "Positions", "Risk · Risk on my book", "Counterparties"]}
    for lens, first in expected.items():
        alice.post(f"/lens/{lens}")
        page = alice.get("/t").text
        assert _menus(page) == ["Views", "Domains", "Create", "Admin", "Help"], lens
        cols = _column_titles(page, "Domains")
        assert [c for c in cols if c in first][:len(first)] == [c for c in first if c in cols], lens
        assert set(plain) <= set(cols) | {"Curves", "Volatility", "FX", "Credit"} and len(cols) >= len(plain) - 4, lens   # nothing taken out
        assert _menus(bob.get("/t").text) == ["Views", "Domains", "Create", "Admin", "Help"]
    alice.post("/lens/trading")
    page = alice.get("/t").text
    assert _commands(_column(page, "Blotter")) == ["TRD", "CTRD", "PTRD", "LTRD"]
    assert _commands(_column(page, "Counterparties"))[:6] == ["CPTY", "NSET", "LIM", "PREDEAL", "EXP", "CCUBE"]
    assert {"VAR BANK", "VRES", "EXP"} <= set(_commands(_panel(page, "Domains")))
    alice.post("/lens/world")
    page = alice.get("/t").text
    assert _commands(_column(page, "Economies")) == ["ECON …", "MACRO", "FCST", "REGE"]
    assert _commands(_column(page, "Companies")) == ["USEQ", "EDGAR", "LEIC", "FUND"]


def test_the_trading_lens_has_one_markets_menu_and_hides_nothing(backend, monkeypatch):
    """Product panel A19 (2026-11-06): the Trading lens is configuration. Markets gathers the market-data screens (Curves, Volatility, FX
    and Credit's columns, then equities and commodities) as columns of Domains; Admin stays (a lens never hides)."""
    app = _app(backend, monkeypatch)
    app.state.editions.learn({"product": "Drishti", "edition": None, "lenses": ["trading"]})
    alice = _client(app)
    alice.post("/lens/trading")
    page = alice.get("/t").text
    assert "Admin" in _menus(page)
    markets = [t for t in _column_titles(page, "Domains") if t.startswith("Markets · ")][:5]
    assert markets == ["Markets · Rates", "Markets · Volatility surfaces", "Markets · Foreign exchange", "Markets · Credit",
                       "Markets · Equities and commodities"]
    assert _commands(_column(page, "Markets · Rates"))[:3] == ["CRV", "REPO", "FIX"]
    assert {"INF", "EQV", "CMDV", "CMDC"} <= set(_commands(_panel(page, "Domains")))
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
