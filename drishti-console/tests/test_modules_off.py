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

"""The guarantee, console part (docs/architecture/ACCESS_MODEL.md section 6): a module that is off is gone from every door the console
draws, and so is a function the person may not use. The console asks the server (the public about answer's ``modules``; the person's
``GET /api/v1/modules``) and nothing else: pages and routes answer 404, menus, the Admin strip, search, the command line
and its type-ahead, Help and the Build home leave them out, a word typed anyway says "<WORD> is not available to you: ask your
administrator", and the module's static assets are not served (nor to a person whose lens does not use the module). The backend part:
drishti-banking's BankingModuleOffTest and BankingModuleOnTest."""
from __future__ import annotations

import re
from urllib.parse import unquote

from core import modules, plugins
from test_edition import _app, _client

FULL = {"product": "Drishti", "tagline": "Any data. Any domain. One grammar.", "edition": None, "lenses": []}
BANKING_OFF = {**FULL, "modules": ["core", "economy", "capitalmarkets"]}
ALL_ON = {**FULL, "modules": ["core", "banking", "economy", "capitalmarkets"]}
CORE_ASSET = "/static/js/drishti_keys.js"
BANKING_ASSETS = ["/static/js/drishti_var-explorer.js", "/static/css/drishti_var.css", "/static/js/drishti_exposure-explorer.js",
                  "/static/js/drishti_exposure-predeal.js",
                  "/static/js/drishti_mr-lens.js", "/static/elements/drishti-risk/drishti_risk.js"]


def _answer(off_modules=(), functions=(), lenses=None):
    """The person's answer as the server writes it (ModuleAdminController.mine)."""
    loaded = [m for m in ("core", "banking", "economy", "capitalmarkets") if m not in off_modules]
    return {"modules": [{"id": m, "title": m, "domain": m} for m in loaded],
            "off": [{"id": m, "title": m, "domain": m, "reason": "switched off (setting drishti.modules.%s.enabled)" % m} for m in off_modules],
            "lenses": lenses if lenses is not None else [{"id": "world", "title": "World", "modules": ["economy"], "functions": []}],
            "functions": {"off": [{"word": w, "module": mod, "reason": "an administrator switched it off (Admin -> Functions)",
                                   "text": f"{w} is not available to you: ask your administrator"} for w, mod in functions]}}


def _console(backend, monkeypatch, about, answer):
    app = _app(backend, monkeypatch)

    async def mine(ident):
        return answer
    monkeypatch.setattr(backend, "modules", mine, raising=False)
    app.state.editions.learn(about)
    return app


def _menu_links(html: str) -> list[str]:
    nav = html[html.index('class="tbar-menus"'):html.index('class="tbar-tools"')]
    return re.findall(r'class="mega-item[^"]*" href="([^"]+)"', nav)


def _refusal(r) -> str:
    assert r.status_code == 303, r.status_code
    return unquote(r.headers["location"])


def test_banking_off_is_gone_from_every_door(backend, monkeypatch):
    app = _console(backend, monkeypatch, BANKING_OFF, _answer(off_modules=("banking",)))
    c = _client(app)
    t = c.get("/t").text
    links = _menu_links(t)
    assert "/var" not in links and "/exposure" not in links      # Risk (the engines' pages): banking's menu gone
    assert "/admin/scopes" not in t                                                                   # the Admin strip's Saved VaR baskets
    assert '<meta name="drishti-modules" content="capitalmarkets economy">' in t                                     # the Elements loader refuses drishti-risk
    for page in ("/var", "/var/reports", "/exposure", "/predeal", "/frtb", "/simm", "/smat", "/play", "/mcube", "/admin/scopes"):
        assert c.get(page, follow_redirects=False).status_code == 404, page
    for api in ("/api/var/scope?scope=BANK", "/api/exposure/scope?scope=BANK", "/api/frtb/sa", "/api/simm/versions", "/api/pivot/mr/versions",
                "/api/home/trading/me", "/api/home/market/desk"):
        assert c.get(api).status_code == 404, api
    for typed in ("VAR BANK", "SMAT DESK:RATES", "PLAY VAR BANK", "FRSA BANK", "SIMM", "MCUBE", "EXP CPTY CP-X", "PREDEAL CP-X LIKE T1 5M"):
        said = _refusal(c.get("/go", params={"q": typed + " <GO>"}, follow_redirects=False))
        assert said.startswith(f"/t?error={typed.split()[0]}") and "is not available to you: ask your administrator" in said, typed
    ahead = c.get("/api/search", params={"q": "SMAT", "in": "command"}).json()
    assert not any(s.get("mnemonic") in ("SMAT", "VAR", "PLAY") for g in ahead.get("groups", [ahead]) for s in (g.get("items") or []))
    found = c.get("/api/search", params={"q": "VaR explorer", "in": "all"}).text
    assert 'href": "/var"' not in found and '"/var"' not in found                                    # search: no screen of the module
    assert c.get("/help/docs-guides-var-guide").status_code == 404                                    # Help: its guides and tutorials
    assert "Risk measures" not in c.get("/help").text                                                 # and its Help section
    for slug in ("knowledge-risk", "docs-tutorials-12-a-day-of-bank-data"):                           # and its pages in core sections (domain:)
        assert c.get(f"/help/{slug}").status_code == 404, slug
    assert c.get("/help/knowledge-data").status_code == 200
    assert 'data-kind="var-baskets"' not in c.get("/build").text                                      # the Build home: its types
    for asset in BANKING_ASSETS:                                                                      # its files are not served
        assert c.get(asset).status_code == 404, asset
    assert c.get(CORE_ASSET).status_code == 200
    assert c.get("/m/economy/static/1.0.0/js/drishti_world-landing.js").status_code == 200           # another module's still are (hosted)


def test_the_command_lines_examples_are_only_what_the_person_may_type(backend, monkeypatch):
    """The placeholder keeps the examples whose function is on: the server lists a module's functions as off with it (TRD, CPTY)."""
    off = _answer(off_modules=("banking",), functions=(("TRD", "banking"), ("CPTY", "banking")))
    t = _client(_console(backend, monkeypatch, BANKING_OFF, off)).get("/t").text
    assert "TRD MX-" not in t.split('id="cmdInput"')[1].split(">")[0] and "CPTY north" not in t.split('id="cmdInput"')[1].split(">")[0]
    t = _client(_console(backend, monkeypatch, ALL_ON, _answer())).get("/t").text
    assert 'placeholder="TRD MX-20000001, CPTY north' in t
    from core.menus import shown_examples
    assert shown_examples("ECON CAN, USEQ AAPL, LEIC US …  then <GO>", lambda x: not x.startswith("USEQ")) == "ECON CAN, LEIC US …  then <GO>"
    assert shown_examples("TRD 1, CPTY a …  then <GO>", lambda x: False) == ""


def test_banking_on_draws_every_door_again(backend, monkeypatch):
    app = _console(backend, monkeypatch, ALL_ON, _answer())
    c = _client(app)
    t = c.get("/t").text
    assert "/var" in _menu_links(t)
    assert 'content="banking capitalmarkets economy"' in t
    assert c.get("/help/docs-guides-var-guide").status_code == 200
    assert 'data-kind="var-baskets"' in c.get("/build").text
    for asset in BANKING_ASSETS:
        assert c.get(asset).status_code == 200, asset
    said = _refusal(c.get("/go", params={"q": "VAR BANK <GO>"}, follow_redirects=False))
    assert said.startswith("/var") and "not available" not in said


def test_an_older_server_that_says_nothing_hides_nothing(backend, monkeypatch):
    app = _console(backend, monkeypatch, FULL, None)
    c = _client(app)
    assert "/var" in _menu_links(c.get("/t").text)
    assert c.get(BANKING_ASSETS[0]).status_code == 200


def test_capital_markets_off_its_functions_are_gone(backend, monkeypatch):
    words = ("USEQ", "IDX", "EDGAR", "FUND", "CRV")
    app = _console(backend, monkeypatch, {**FULL, "modules": ["core", "banking", "economy"]},
                   _answer(off_modules=("capitalmarkets",), functions=[(w, "capitalmarkets") for w in words]))

    async def suggest(q, user, limit=10):
        return [{"mnemonic": "USEQ", "complete": "USEQ ", "title": "US equities"}, {"mnemonic": "FUND", "complete": "FUND ", "title": "Funds"},
                {"mnemonic": "TRD", "complete": "TRD ", "title": "Trades"}]
    monkeypatch.setattr(backend, "suggest", suggest, raising=False)
    c = _client(app)
    said = _refusal(c.get("/go", params={"q": "USEQ AAPL <GO>"}, follow_redirects=False))
    assert "USEQ is not available to you: ask your administrator" in said
    items = c.get("/api/search", params={"q": "U", "in": "command"}).json()
    shown = [s.get("mnemonic") for g in items.get("groups", [items]) for s in (g.get("items") or [])] if isinstance(items, dict) else \
        [s.get("mnemonic") for s in items]
    assert "USEQ" not in shown and "FUND" not in shown and "TRD" in shown
    assert "/var" in _menu_links(c.get("/t").text)                                                    # banking stays
    assert c.get("/help/docs-guides-funds-guide").status_code == 404                                 # its Help pages (domain: capitalmarkets)
    assert c.get("/help/docs-guides-public-sector-guide").status_code == 200                         # another module's stay


def test_a_single_function_off_is_gone_and_the_rest_stays(backend, monkeypatch):
    app = _console(backend, monkeypatch, ALL_ON, _answer(functions=[("VRES", "banking"), ("SMAT", "banking")]))

    async def suggest(q, user, limit=10):
        return [{"mnemonic": "VRES", "complete": "VRES ", "title": "VaR results"}, {"mnemonic": "VLIM", "complete": "VLIM ", "title": "VaR limits"}]
    monkeypatch.setattr(backend, "suggest", suggest, raising=False)
    c = _client(app)
    t = c.get("/t").text
    assert "VaR results" not in t and "Scenario matrix" not in t                                      # its menu items
    assert "/var" in _menu_links(t)                                                                   # VAR itself stays
    said = _refusal(c.get("/go", params={"q": "VRES DESK:RATES <GO>"}, follow_redirects=False))
    assert said.endswith("VRES is not available to you: ask your administrator")
    assert c.get("/smat").status_code == 404 and c.get("/api/smat/matrix?id=T-1").status_code == 404  # a function's pages and APIs
    items = c.get("/api/search", params={"q": "V", "in": "command"}).json()
    shown = [s.get("mnemonic") for g in items.get("groups", [items]) for s in (g.get("items") or [])] if isinstance(items, dict) else \
        [s.get("mnemonic") for s in items]
    assert "VRES" not in shown and "VLIM" in shown
    assert c.get("/var").status_code == 200


def test_a_function_waiting_for_data_explains_itself(backend, monkeypatch):
    """EXP with no exposure-path bound: its page is here and says what it needs (200, links to Admin -> Data bindings and Help), its
    console API answers 409 DRS-1035, the command line says the same words; nothing answers 404."""
    answer = _answer()
    text = "EXP needs data: exposure-path is not bound; ask your data administrator"
    answer["functions"]["off"] = [{"word": "EXP", "module": "banking", "gate": "data", "reason": text, "text": text}]
    c = _client(_console(backend, monkeypatch, ALL_ON, answer))
    r = c.get("/exposure", follow_redirects=False)
    assert r.status_code == 200, r.status_code
    assert 'data-needs-data="EXP"' in r.text and "needs data" in r.text and "exposure-path is not bound" in r.text
    assert 'href="/admin/bindings"' in r.text and "/help?q=" in r.text
    api = c.get("/api/exposure/scope?scope=BANK")
    assert api.status_code == 409 and api.json()["code"] == "DRS-1035" and "exposure-path" in api.json()["detail"]
    assert _refusal(c.get("/go", params={"q": "EXP <GO>"}, follow_redirects=False)).endswith(text)
    assert c.get("/var").status_code == 200


def test_a_lens_that_does_not_use_a_module_does_not_get_its_assets(backend, monkeypatch):
    app = _console(backend, monkeypatch, {**ALL_ON, "lenses": ["world"]},
                   _answer(lenses=[{"id": "world", "modules": ["economy"]}, {"id": "risk", "modules": ["banking"]}]))

    async def settings(ident=None):
        return {"lens": "world"} if ident is not None and ident.user == "wendy" else {}
    monkeypatch.setattr(backend, "settings", settings, raising=False)
    wendy, alice = _client(app, "wendy"), _client(app)
    for asset in BANKING_ASSETS:
        assert wendy.get(asset).status_code == 404, asset                                             # the World lens: no banking file
        assert alice.get(asset).status_code == 200, asset                                             # the full product: every module on
    assert wendy.get("/m/economy/static/1.0.0/js/drishti_world-landing.js").status_code == 200
    assert wendy.get(CORE_ASSET).status_code == 200


def test_the_registrations_are_the_modules_and_the_core_names_none():
    """C1 to C6 (DOMAIN_MODULES.md section 5): the banking module's console code fills the core's hooks; plugin.yaml lists its routers."""
    plugins.load()
    banking = modules.plugin("banking")
    assert banking is not None and "plugins.banking.routes.var_routes" in banking.routers and "var" in banking.engines
    assert plugins.preset_parser("mr") is not None and plugins.retired_link("var", "VAR-COMM") is not None
    assert modules.asset_module("elements/drishti-risk/drishti_risk.js").id == "banking"
    assert modules.asset_module("js/drishti_keys.js") is None
