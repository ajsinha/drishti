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

"""The Engines menu (2.9.11.a, mock-up engines-menu/A; config/menus.yaml, core/menus.py) and the help hub's engine rule, without a
browser: one top menu in place of the VaR and Exposure menus, a column per running engine with its command word, drawn only for a role
that opens an engine (opens: in menus.yaml; administrators open all); the search box past search-over engines; Admin -> Engines and the
engines not running for administrators only; the same rule refuses an engine's command words in words and drops them from the
type-ahead; Help shows every running engine's pages to anyone who can open Help (signed out too) and leaves out an engine that runs
nowhere (an administrator sees those greyed). The browser side: test_engines_menu_browser.py and test_help_hub_browser.py."""
from __future__ import annotations

import re

import yaml

from conftest import CONSOLE
from core import engines
from core.auth import Identity
from test_edition import RISK_ABOUT, _app, _client

CATALOGUE = yaml.safe_load((CONSOLE / "config" / "engines.yaml").read_text(encoding="utf-8"))["engines"]
ROLES = {"alice": ("*",), "mira": ("market-risk",), "cole": ("credit-risk",), "bea": ("bi-analyst",)}


def _people(app, monkeypatch):
    async def current(cookie, _backend):
        user = cookie or "alice"
        return Identity(user=user, display=user.title(), desk="Risk", roles=ROLES.get(user, ("viewer",)))
    monkeypatch.setattr(app.state.auth, "current", current)


def _top(html: str) -> list[str]:
    nav = html[html.index('class="tbar-menus"'):html.index('class="tbar-tools"')]
    return re.findall(r'<span class="tbar-menu-l">([^<]+)</span>', nav)


def _engines(html: str) -> list[str]:
    return re.findall(r'class="mega-title eng-name">([^<]+)<', html)


def _about(off: set[str]) -> list[dict]:
    return [{"id": e["id"], "title": e["title"], "here": e["id"] not in off, "cluster": e["id"] not in off} for e in CATALOGUE]


def test_the_menus_file_has_one_engines_menu_and_no_var_or_exposure_menu():
    spec = yaml.safe_load((CONSOLE / "config" / "menus.yaml").read_text(encoding="utf-8"))
    ids = [m["id"] for m in spec["menus"]]
    assert ids[:2] == ["views", "engines"] and "var" not in ids and "exposure" not in ids
    eng = next(m for m in spec["menus"] if m["id"] == "engines")
    known = {e["id"] for e in CATALOGUE}
    for col in eng["columns"]:
        assert col["engine"] in known and col.get("word") and col.get("items"), col.get("engine")
        assert all(str(r).split(":", 1)[0] in ("role", "pack") for r in col.get("opens") or []), col["engine"]
    views = next(m for m in spec["menus"] if m["id"] == "views")
    assert not {"var", "exposure"} & {it["id"] for c in views["columns"] for it in c["items"]}   # one place each: the Engines menu


def test_the_pre_deal_check_and_fx_volatility_have_one_menu_item_each():
    """Lean (2.9.11.c): Pre-deal check (XWIF) only under Counterparties, FX volatility (FXV) only under Volatility."""
    spec = yaml.safe_load((CONSOLE / "config" / "menus.yaml").read_text(encoding="utf-8"))
    where: dict[str, list[str]] = {}
    for m in spec["menus"]:
        for col in m.get("columns") or []:
            for it in col.get("items") or []:
                if it.get("cmd"):
                    where.setdefault(it["cmd"], []).append(m["id"])
    assert where["XWIF"] == ["counterparties"] and where["FXV"] == ["volatility"]


def test_an_administrator_sees_every_running_engine_with_its_word_and_admin_engines(backend, monkeypatch):
    engines.reset()
    app = _app(backend, monkeypatch)
    page = _client(app).get("/t").text
    assert _top(page)[:2] == ["Views", "Engines"]
    assert _engines(page) == ["VaR", "Exposure", "FRTB-SA", "SIMM"]
    words = re.findall(r'<code class="eng-word mono">([^<]+)</code>', page)
    assert words == ["VAR", "EXP", "FRSA", "SIMM"]
    assert 'href="/admin/engines"' in page[page.index("data-engines-panel"):] and "data-engines-admin" in page
    assert "data-engines-q" not in page                                                # four engines: no search box (search-over: 4)


def test_a_role_sees_only_the_engines_it_opens_and_no_admin_link(backend, monkeypatch):
    engines.reset()
    app = _app(backend, monkeypatch)
    _people(app, monkeypatch)
    mira = _client(app, "mira").get("/t").text
    assert _engines(mira) == ["VaR", "FRTB-SA", "SIMM"] and "data-engines-admin" not in mira and "data-engines-off" not in mira
    cole = _client(app, "cole").get("/t").text
    assert _engines(cole) == ["Exposure", "SIMM"]


def test_a_role_that_opens_no_engine_has_no_engines_menu_but_reads_every_running_engines_help(backend, monkeypatch):
    """Product owner, 2026-11-03: Help is documentation, not data; only the Engines menu and the commands follow opens:."""
    engines.reset()
    app = _app(backend, monkeypatch)
    _people(app, monkeypatch)
    c = _client(app, "bea")
    page = c.get("/t").text
    assert "Engines" not in _top(page) and "data-engines-menu" not in page and "Admin" not in _top(page)
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


def test_an_engines_commands_follow_the_same_rule_as_its_menu_column(backend, monkeypatch):
    """The command words of an engine the role does not open: refused in words on the command line, left out of the type-ahead
    (core/menus.py engine_refused, the Engines menu's opens:); an engine it opens, and every engine for an administrator, as before."""
    from urllib.parse import unquote

    from core import menus

    engines.reset()
    app = _app(backend, monkeypatch)
    _people(app, monkeypatch)
    mira = _client(app, "mira")                                                         # market risk: VaR, FRTB-SA, SIMM; not Exposure
    for typed in ("EXP CPTY:CP-ALDERSHOT", "XWIF", "exp bank"):
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


def test_an_engine_running_nowhere_leaves_the_menu_and_an_administrator_sees_it_named_not_running(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    engines.learn(_about({"simm"}))
    try:
        page = _client(app).get("/t").text
        assert _engines(page) == ["VaR", "Exposure", "FRTB-SA"]
        off = page[page.index("data-engines-off"):]
        assert "SIMM" in off[:off.index("</div>")] and 'href="/simm"' not in page
        risk = _client(app).get("/help/section/risk").text
        assert "data-help-off" in risk and 'href="/help/docs-guides-simm-guide"' not in risk and "SIMM is off" in risk
        _people(app, monkeypatch)
        mira = _client(app, "mira")
        assert "data-help-off" not in mira.get("/help/section/risk").text                 # not an administrator: left out, not greyed
        assert "data-engines-off" not in mira.get("/t").text
    finally:
        engines.reset()


def test_the_search_box_shows_past_search_over_engines(backend, monkeypatch, tmp_path):
    from core import menus

    engines.reset()
    spec = yaml.safe_load((CONSOLE / "config" / "menus.yaml").read_text(encoding="utf-8"))
    next(m for m in spec["menus"] if m["id"] == "engines")["search-over"] = 2
    (tmp_path / "config").mkdir()
    (tmp_path / "config" / "menus.yaml").write_text(yaml.safe_dump(spec), encoding="utf-8")
    monkeypatch.setattr(menus, "_console_dir", lambda request: tmp_path)
    app = _app(backend, monkeypatch)
    page = _client(app).get("/t").text
    assert "data-engines-q" in page and 'placeholder="Search' in page and "pages of 4 engines" in page


def test_the_risk_edition_draws_engines_in_place_of_var_and_exposure(backend, monkeypatch):
    engines.reset()
    app = _app(backend, monkeypatch)
    app.state.editions.learn(RISK_ABOUT)
    page = _client(app).get("/t").text
    top = [m for m in _top(page) if m not in ("BI", "Build")]
    assert top == ["Views", "Engines", "Admin", "Help"] and "VaR" not in top and "Exposure" not in top
