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

"""The lens menus (docs/lenses/README.md, mock-up menus/A): each edition's own subject menus in the top bar, named by its menus.top in
order; a full Drishti keeps Views, BI, Build, Admin and Help; an item shows only when the edition keeps its command."""
from __future__ import annotations

import re

import yaml

from conftest import CONSOLE
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


def test_a_full_drishti_keeps_its_five_menus(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    app.state.editions.learn({"product": "Drishti", "edition": None, "lenses": []})
    menus = _menus(_client(app).get("/t").text)
    assert _drawn(_client(app).get("/t").text) == ["Views", "Admin", "Help"] and "Build" in menus


def test_the_risk_edition_draws_var_and_exposure_menus_between_views_and_bi(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    app.state.editions.learn(RISK_ABOUT)
    page = _client(app).get("/t").text
    assert _drawn(page) == ["Views", "VaR", "Exposure", "Admin", "Help"] and "Build" not in _menus(page)   # Build is off in Risk
    var = _panel(page, "VaR")
    assert "Value at risk" in var and "Check and report" in var
    cmds = _commands(var)
    for c in ("VAR BANK", "VRES", "VOFF", "SMAT", "HIER", "SWIN BANK"):
        assert c in cmds, c
    assert cmds.index("HIER") < cmds.index("SWIN BANK")
    assert 'href="/go?q=SWIN%20BANK%20%3CGO%3E"' in var and 'href="/smat"' in var
    exposure = _panel(page, "Exposure")
    assert 'data-fill="EXP CPTY"' in exposure and 'href="/t?cmd=EXP%20CPTY"' in exposure and "XWIF" in _commands(exposure)


def test_an_item_for_a_page_this_build_lacks_is_not_drawn(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    app.state.editions.learn(RISK_ABOUT)
    paths = {getattr(r, "path", "") for r in app.routes}
    var = _panel(_client(app).get("/t").text, "VaR")
    assert ("PLAY VAR" in _commands(var)) == ("/play" in paths)
    assert ("VAR BANK BACKTEST" in _commands(var)) == ("/var/backtesting" in paths)


def test_a_lens_draws_its_own_subject_menus_for_that_person_only(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    app.state.editions.learn({"product": "Drishti", "edition": None, "lenses": ["risk", "world", "market", "trading"]})
    alice, bob = _client(app), _client(app, "bob")
    expected = {"world": ["Views", "Economies", "Markets", "Companies", "Admin", "Help"],
                "market": ["Views", "Curves", "Volatility", "FX", "Credit", "Admin", "Help"],
                "trading": ["Views", "Blotter", "Positions", "Risk", "Counterparties", "Admin", "Help"]}
    for lens, menus in expected.items():
        alice.get(f"/lens/{lens}")
        page = alice.get("/t").text
        assert _drawn(page) == menus, lens
        assert _drawn(bob.get("/t").text) == ["Views", "Admin", "Help"]
    alice.get("/lens/market")
    page = alice.get("/t").text
    assert _commands(_panel(page, "Curves")) == ["CRV", "REPO", "FIX", "INF"]
    assert _commands(_panel(page, "Volatility")) == ["IRV", "CPV", "FXV", "EQV", "CMDV"]
    assert _commands(_panel(page, "FX")) == ["FX", "FXF", "FXV"] and _commands(_panel(page, "Credit")) == ["CDS", "BND"]
    alice.get("/lens/world")
    page = alice.get("/t").text
    assert _commands(_panel(page, "Economies")) == ["ECON …", "MACRO", "FCST", "REGE"]
    assert _commands(_panel(page, "Companies")) == ["USEQ", "EDGAR", "LEIC", "FUND"]
    alice.get("/lens/trading")
    page = alice.get("/t").text
    assert _commands(_panel(page, "Blotter")) == ["TRD", "CTRD", "PTRD", "LTRD"]
    assert _commands(_panel(page, "Risk")) == ["VAR", "VLIM", "TVAL", "PNLV"]
    assert _commands(_panel(page, "Counterparties")) == ["CPTY", "NSET", "LIM", "XWIF", "EXP"]


def test_every_subject_item_runs_a_command_its_edition_shows():
    """Every subject menu an edition names exists, and each of its items' commands is one the edition allows or a word it keeps (else
    the item would never show): the menus never hold what the command line does not."""
    spec = yaml.safe_load((CONSOLE / "config" / "menus.yaml").read_text(encoding="utf-8"))
    menus = {m["id"]: m for m in spec["menus"]}
    from core.edition import Edition
    for name in ("risk", "trading", "market", "world"):
        ed = Edition(name, yaml.safe_load((ROOT / "config" / "editions" / f"{name}.yaml").read_text(encoding="utf-8")))
        top = ed.menu("top")
        assert top and top[0] == "views", name
        for mid in top:
            assert mid in menus, (name, mid)
            if not menus[mid].get("subject"):
                continue
            for col in menus[mid]["columns"]:
                for it in col["items"]:
                    words = [str(r).split(":", 1)[1] for r in it.get("requires") or [] if str(r).startswith(("cmd:", "word:"))]
                    assert words, (name, it["id"])
                    if not any(str(r).startswith("route:") for r in it.get("requires") or []):
                        assert all(ed.typed_shown(w) for w in words), (name, it["id"])
                    assert it.get("cmd") or it.get("fill"), it["id"]
