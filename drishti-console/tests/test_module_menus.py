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

"""A module's menus come from its manifest (console.menus) and are in the top bar only while the module is active: in the running
distribution, installed and loaded, and switched on at this site. Otherwise the menu is absent (not greyed), and it goes or comes back
live, at the next page, when the module is unloaded, removed, loaded or switched (docs/guides/ADMIN_GUIDE.md, the three layers)."""
from __future__ import annotations

from pathlib import Path

import yaml

from core import menus
from test_modules_off import ALL_ON, BANKING_OFF, _answer, _console
from test_edition import _client

RISK = '<div class="mega-title">Counterparties</div>'                          # a Risk-domain column of Domains
FINANCE = {"product": "Drishti Finance", "edition": {"name": "drishti.finance", "title": "Drishti Finance"}, "lenses": []}
CONSOLE = Path(__file__).resolve().parent.parent


def test_the_sitemap_keeps_the_kernels_pages_and_the_modules_bring_their_menus():
    kernel = {m["id"] for m in yaml.safe_load((CONSOLE / "config" / "sitemap.yaml").read_text(encoding="utf-8"))["menus"]}
    assert kernel == {"views", "domains", "create", "bi", "build", "admin", "help", "me"}
    merged = {m["id"]: m for m in menus.spec_of(CONSOLE)["menus"]}
    assert merged["risk"]["_module"] == "banking" and merged["companies"]["_module"] == "capitalmarkets"
    assert merged["markets"]["_module"] == "market-data"                       # the one Markets menu, with the columns others add
    assert {c.get("_module") for c in merged["markets"]["columns"]} >= {"market-data", "economy", "capitalmarkets"}


def test_risk_is_in_the_bar_only_while_banking_is_active(backend, monkeypatch):
    app = _console(backend, monkeypatch, ALL_ON, _answer())
    c = _client(app)
    assert RISK in c.get("/t").text                                             # banking on
    off = _client(_console(backend, monkeypatch, BANKING_OFF, _answer(off_modules=("banking",))))
    assert RISK not in off.get("/t").text                                       # switched off at this site: absent, not greyed
    # removed (the server's module list no longer has it), then installed again: live, at the next page
    eds = app.state.editions
    known = dict(eds.server_modules or {"banking": object(), "market-data": object(), "economy": object(), "capitalmarkets": object()})
    eds.server_modules = {k: v for k, v in known.items() if k != "banking"}
    assert RISK not in c.get("/t").text
    eds.server_modules = known
    assert RISK in c.get("/t").text
    # unloaded on the server (its about answer stops listing it), then loaded again
    app.state.editions.learn({**ALL_ON, "modules": ["core", "economy", "capitalmarkets", "market-data"]})
    assert RISK not in c.get("/t").text
    app.state.editions.learn({**ALL_ON, "modules": ["core", "banking", "economy", "capitalmarkets", "market-data"]})
    assert RISK in c.get("/t").text


def test_risk_is_not_in_drishti_finance(backend, monkeypatch):
    app = _console(backend, monkeypatch, FINANCE, _answer())
    assert app.state.editions.distribution.id == "drishti.finance"
    page = _client(app).get("/t").text
    assert RISK not in page and '<span class="tbar-menu-l">Views</span>' in page
