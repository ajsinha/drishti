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

"""The top bar: Views, Domains, Create, Admin, Help (mock-up docs/lenses/mockups/navigation, option A with Domains). Domains holds a column
for each menu the active modules bring, from their manifests alone; with one domain active it is named after it (Risk). Create holds every
designer and the BI tools. Nothing is listed in the console's code, and each item still follows the person's role."""
from __future__ import annotations

import re
import tempfile
from pathlib import Path

import pytest
import yaml

from conftest import CONSOLE
from core.auth import Identity
from core.menus import spec_of
from core.module_parts import ModuleParts
from module_parts_fake import Registry
from test_distributions import _app, _client
from test_lens_menus import _menus, _panel


def _titles(html: str, label: str) -> list[str]:
    return re.findall(r'<div class="mega-title">([^<]+)<', _panel(html, label))


def _bar(backend, monkeypatch, *argv):
    return _client(_app(backend, monkeypatch, *argv)).get("/t").text


def test_all_has_domains_and_create_and_every_domains_column_is_from_a_manifest(backend, monkeypatch):
    page = _bar(backend, monkeypatch)
    assert _menus(page) == ["Views", "Domains", "Create", "Admin", "Help"]
    cols = _titles(page, "Domains")
    for c in ("Blotter", "Positions", "Counterparties", "Companies", "Economies", "Markets · Rates", "Risk · VaR"):
        assert c in cols, c
    assert len(cols) == len(set(cols))                                         # a menu two modules add to is one column


def test_risk_with_one_domain_shows_its_name_and_not_domains(backend, monkeypatch):
    page = _bar(backend, monkeypatch, "--distribution=risk")
    assert _menus(page) == ["Views", "Risk", "Create", "Admin", "Help"]
    assert "Risk · VaR" in _titles(page, "Risk") and "Blotter" in _titles(page, "Risk")


def test_finance_has_no_risk(backend, monkeypatch):
    page = _bar(backend, monkeypatch, "--distribution=finance")
    assert _menus(page) == ["Views", "Domains", "Create", "Admin", "Help"]
    cols = " ".join(_titles(page, "Domains"))
    assert "Economies" in cols and "Companies" in cols and "Risk" not in cols and "Blotter" not in cols


def test_genomics_shows_its_one_domain_by_name(backend, monkeypatch):
    app = _app(backend, monkeypatch, "--distribution=genomics")
    reg = Registry()
    reg.install(backend, monkeypatch)
    from core import modules
    here = modules.module_on                                                    # this distribution runs the genomics module only (and the stand-in)
    monkeypatch.setattr(modules, "module_on", lambda m: here(m) and str(m or "core") in {"core", "genomics", "greeter"})
    old = app.state.module_parts
    tmp = Path(__file__).resolve().parents[2] / "data" / "tmp"
    tmp.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=tmp) as d:
        parts = ModuleParts(Path(d) / "parts", old.web, old.shipped, 0.0)
        monkeypatch.setattr(app.state, "module_parts", parts)
        monkeypatch.setattr(app.state.editions.modules, "parts", parts)
        reg.state = "off"
        assert _menus(_client(app).get("/t").text) == ["Views", "Create", "Admin", "Help"]  # no module hosted: no Domains
        reg.state = "active"
        page = _client(app).get("/t").text
    assert _menus(page) == ["Views", "greeter", "Create", "Admin", "Help"]                   # the one hosted domain, by its own name


def test_create_holds_the_designers_and_bi_and_the_old_menus_are_gone(backend, monkeypatch):
    page = _bar(backend, monkeypatch)
    assert "BI" not in _menus(page) and "Build" not in _menus(page)
    panel = _panel(page, "Create")
    for href in ("/build/pack/designer", "/build/connector/designer", "/build/new"):
        assert f'href="{href}"' in panel, href
    create = next(m for m in spec_of(CONSOLE)["menus"] if m["id"] == "create")
    assert create["gather"] == ["bi", "build"]                                  # BI's items follow the person's BI powers (none in the stand-in)


def test_a_person_who_is_not_an_administrator_sees_no_admin_menu(backend, monkeypatch):
    app = _app(backend, monkeypatch, "--distribution=risk")

    async def trader(cookie, _backend):
        return Identity(user="tom", display="Tom", desk="Rates", roles=("trader",))
    monkeypatch.setattr(app.state.auth, "current", trader)
    page = _client(app).get("/t").text
    assert "Admin" not in _menus(page) and "Create" in _menus(page)
    assert 'href="/admin/users"' not in page and 'href="/admin/bi"' not in page


def test_the_old_per_module_top_menus_are_not_drawn_beside_domains(backend, monkeypatch):
    for argv in ((), ("--distribution=risk",), ("--distribution=finance",)):
        page = _bar(backend, monkeypatch, *argv)
        assert not {"Blotter", "Positions", "Counterparties", "Curves", "Companies", "Economies", "BI", "Build"} & set(_menus(page)), argv


@pytest.mark.parametrize("name", ["all", "risk", "finance", "genomics"])
def test_every_distribution_names_only_menus_the_sitemap_has(name):
    have = {m["id"] for m in spec_of(CONSOLE)["menus"]}
    d = yaml.safe_load((CONSOLE.parent / "config" / "distributions" / f"drishti.{name}.yaml").read_text(encoding="utf-8"))
    for mid in (d.get("menus") or {}).get("top") or []:
        assert mid in have, (name, mid)
