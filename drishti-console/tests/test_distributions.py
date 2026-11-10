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

"""Distributions on the console (docs/architecture/DISTRIBUTIONS.md): each boots from its file alone; Finance has no VaR command, menu,
Help page or AI tool; Risk has no economy command; market data is in both; All matches today; the old keys still work; two consoles over
one server show two distributions."""
from __future__ import annotations

import re
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from conftest import CONSOLE, help_pages
from core import distribution as dist
from core.app import create_app
from core.auth import Identity
from core.config import load_settings

DIR = CONSOLE.parent / "config" / "distributions"
MODULES = CONSOLE.parent / "modules"   # the modules' own manifests: what a distribution's modules hold
ALL_ABOUT = {"product": "Drishti", "tagline": "Any data. Any domain. One grammar.", "edition": None, "lenses": ["risk", "world", "market", "trading"]}


def _app(backend, monkeypatch, *argv):
    app = create_app(load_settings(CONSOLE / "config", list(argv)))
    app.state.backend = backend

    async def current(cookie, _backend):
        return Identity(user="alice", display="Alice", desk="Risk", roles=("*",))

    monkeypatch.setattr(app.state.auth, "current", current)
    return app


def _client(app):
    c = TestClient(app)
    c.cookies.set(app.state.auth.cookie, "alice")
    return c


def _menu_links(html: str) -> list[str]:
    nav = html[html.index('class="tbar-menus"'):html.index('class="tbar-tools"')]
    return re.findall(r'href="([^"]+)"', nav)


def _commands(client, q: str) -> list[str]:
    return [s.get("mnemonic") for s in client.get("/api/search", params={"in": "command", "q": q}).json()["items"]]


@pytest.mark.parametrize("name,product", [("all", "Drishti"), ("risk", "Drishti Risk"), ("finance", "Drishti Finance"),
                                          ("genomics", "Drishti Genomics")])
def test_each_distribution_boots_from_its_file_alone(name, product):
    d = dist.read(dist.file_of(DIR, name), DIR / dist.KERNEL, MODULES, CONSOLE.parent / "config" / "packs")
    assert d.id == f"drishti.{name}" and d.title == product
    assert d.settings_layer()["ui"]["product"] == product
    assert d.all is (name == "all")
    with pytest.raises(ValueError):
        dist.read(dist.file_of(DIR, "nosuch"), DIR / dist.KERNEL, MODULES, CONSOLE.parent / "config" / "packs")


def test_finance_has_no_var_commands_menus_help_or_ai_tools(backend, monkeypatch):
    app = _app(backend, monkeypatch, "--distribution=finance")
    app.state.editions.learn(ALL_ABOUT)                           # the cluster runs Drishti All: this console stays Finance
    e = app.state.editions.instance
    assert e.distribution and e.title == "Drishti Finance" and app.state.settings.get("ui.product") == "Drishti Finance"
    for c in ("VRES", "VOFF", "PNLV", "EXP", "XPATH", "FRTB", "SACCR", "TRD"):
        assert not e.command_shown(c), c
    for typed in ("VAR BANK", "EXP CPTY:CP-ALDERSHOT", "PLAY VAR", "SMAT DESK RATES", "FRSA BANK"):
        assert not e.typed_shown(typed), typed
    assert e.command_shown("USEQ") and e.command_shown("ECON") and e.typed_shown("PIVOT")
    c = _client(app)
    t = c.get("/t").text
    links = _menu_links(t)
    assert "/var" not in links and "/exposure" not in links and "/frtb" not in links
    assert "Drishti Finance" in t
    r = c.get("/go", params={"q": "VRES DESK:RATES <GO>"}, follow_redirects=False)
    assert r.status_code == 303 and "DRS-4023" in r.headers["location"]
    assert not {"VRES", "PNLV", "VLIM"} & set(_commands(c, "V"))   # the type-ahead (and the AI, which reads the same catalogue)
    centre = help_pages(c)
    assert "Drishti Finance Help" in c.get("/help").text
    for slug in ("var-guide", "docs-guides-var-guide", "exposure-guide", "frtb-sa-guide"):
        assert f'href="/help/{slug}"' not in centre, slug
    assert c.get("/help/var-guide").status_code == 404


def test_finance_has_the_markets_lens_and_market_data_and_no_risk_number(backend, monkeypatch):
    app = _app(backend, monkeypatch, "--distribution=finance")
    app.state.editions.learn(ALL_ABOUT)
    eds = app.state.editions
    assert set(eds.lenses) == {"markets", "world"} and eds.distribution.default_lens == "markets"
    markets = eds.for_request("markets")
    for c in ("USEQ", "FUND", "IDX", "CRV", "FX", "FXV", "CDS"):
        assert markets.command_shown(c), c
    for typed in ("VAR BANK", "VRES DESK:RATES", "EXP CPTY:CP-ALDERSHOT", "PLAY VAR", "FRTB", "SIMM", "SENS"):
        assert not markets.typed_shown(typed) and not eds.instance.typed_shown(typed), typed
    d = eds.distribution
    assert {"capitalmarkets", "economy", "market-data"} <= set(d.modules) and "banking" not in d.modules
    assert {"var", "exposure", "frtb-sa", "simm", "sensitivities", "mr-cube"} <= set(d.features_off)
    assert "market-data" in d.packs and "market-risk" not in d.packs


def test_the_modules_manifests_drive_the_filter():
    known = dist.module_manifests(MODULES)
    assert {"banking", "market-data", "capitalmarkets", "economy"} <= set(known)
    order = list(known)
    assert order.index("market-data") < order.index("banking")                     # a required module first
    assert known["banking"].requires == ["market-data"] and "market-data" not in known["banking"].packs
    assert dist.with_required(["banking"], known) == ["banking", "market-data"]
    assert not (DIR / "modules.yaml").exists()                                        # the hand mapping is gone: one source of truth
    risk = dist.read(DIR / "drishti.risk.yaml", DIR / dist.KERNEL, MODULES, CONSOLE.parent / "config" / "packs")
    assert "market-data" in risk.packs and "world-economy" not in risk.packs and "ECON" in risk.words_off
    with pytest.raises(ValueError, match="has no manifest"):
        dist.read(DIR / "drishti.risk.yaml", DIR / dist.KERNEL, CONSOLE.parent / "no-such", CONSOLE.parent / "config" / "packs")


def test_risk_has_no_economy_commands_and_market_data_is_in_both(backend, monkeypatch):
    risk = _app(backend, monkeypatch, "--distribution=risk")
    e = risk.state.editions.instance
    assert e.title == "Drishti Risk"
    for c in ("ECON", "MACRO", "NEWS", "USEQ", "FUND", "GDELT"):
        assert not e.command_shown(c), c
    assert e.command_shown("VRES") and e.typed_shown("VAR BANK")
    finance = _app(backend, monkeypatch, "--distribution=finance").state.editions.instance
    for c in ("CRV", "FX", "FXV", "EQ", "CDS", "BND"):
        assert e.command_shown(c) and finance.command_shown(c), c
    assert set(risk.state.editions.lenses) == {"risk", "trading", "market"}
    assert risk.state.editions.for_request("trading").outer is e     # a lens inside the distribution never shows more
    assert risk.state.editions.for_request(None) is e
    assert risk.state.settings.get("ui.default_theme") == "terminal-blue" and risk.state.settings.get("ui.default_density") == "compact"


def test_all_matches_today(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    app.state.editions.learn(ALL_ABOUT)
    assert not app.state.editions.instance.active and app.state.editions.distribution.all
    assert app.state.settings.get("ui.product") == "Drishti" and app.state.settings.get("ui.default_theme") == "terminal"
    assert app.state.settings.get("ui.help_title") is None
    c = _client(app)
    assert "/var" in _menu_links(c.get("/t").text)
    assert "<h1>Help</h1>" in c.get("/help").text


def test_the_old_keys_still_work_and_health_names_them(backend, monkeypatch):
    app = _app(backend, monkeypatch, "--distribution=risk", "--ui.product=Acme Risk")
    assert app.state.settings.get("ui.product") == "Acme Risk"           # the site's old key wins, for one release
    assert any(n.startswith("ui.product") for n in dist.deprecated(app.state.settings))
    legacy = _app(backend, monkeypatch)                                  # a server still on drishti.edition: risk
    legacy.state.editions.learn({"product": "Drishti Risk", "edition": {"name": "risk", "title": "Drishti Risk"}})
    assert legacy.state.editions.instance.name == "risk" and not legacy.state.editions.instance.distribution


def test_a_console_without_its_own_distribution_follows_the_servers(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    app.state.editions.learn({"product": "Drishti Finance", "edition": {"name": "drishti.finance", "title": "Drishti Finance"}, "lenses": ["world"]})
    e = app.state.editions.instance
    assert e.distribution and not e.command_shown("VRES") and set(app.state.editions.lenses) == {"world"}


def test_two_consoles_over_one_server_show_two_distributions(backend, monkeypatch):
    risk = _app(backend, monkeypatch, "--distribution=risk")
    finance = _app(backend, monkeypatch, "--distribution=finance")
    for app in (risk, finance):
        app.state.editions.learn(ALL_ABOUT)                           # one cluster, Drishti All on every kernel
    r, f = _client(risk).get("/t").text, _client(finance).get("/t").text
    assert "Drishti Risk" in r and "Drishti Finance" not in r
    assert "Drishti Finance" in f and "Drishti Risk" not in f
    assert "/var" in _menu_links(r) and "/var" not in _menu_links(f)
    assert risk.state.editions.instance.command_shown("VRES") and not finance.state.editions.instance.command_shown("VRES")


def test_admin_white_label_shows_the_distributions_side_by_side(backend, monkeypatch):
    app = _app(backend, monkeypatch, "--distribution=finance")
    page = _client(app).get("/admin/white-label")
    assert page.status_code == 200, page.text[:300]
    t = page.text
    for d in ("drishti.all", "drishti.risk", "drishti.finance", "drishti.genomics"):
        assert f'data-distribution="{d}"' in t, d
    assert t.index('data-distribution="drishti.finance"') < t.index('data-distribution="drishti.risk"')   # the running one first
    assert "data-white-label-running" in t and "Drishti Finance Help" in t and "bin/start-all.sh --distribution finance" in t


def test_a_stack_file_runs_a_risk_and_a_finance_console_over_one_cluster():
    import importlib.util
    spec = importlib.util.spec_from_file_location("drishti_cluster", CONSOLE.parent / "bin" / "lib" / "cluster.py")
    cluster = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(cluster)
    stack = {"nodes": [{"name": "n1", "port": 18480}], "console": {"port": 17480, "servers": ["n1"], "settings": {"A": "1"}},
             "consoles": [{"name": "risk", "port": 17480, "distribution": "risk"}, {"name": "finance", "port": 17481, "distribution": "finance"}]}
    cs = cluster.consoles(stack)
    assert [(c["process"], c["port"], c["distribution"], c["servers"]) for c in cs] == [
        ("console-risk", 17480, "risk", ["n1"]), ("console-finance", 17481, "finance", ["n1"])]
    assert cs[1]["settings"] == {"A": "1"}
    assert [c["process"] for c in cluster.consoles({"nodes": [], "console": {"port": 17480}})] == ["console"]   # the one console, as before


def test_a_path_names_a_sites_own_file(tmp_path: Path):
    own = tmp_path / "acme.yaml"
    own.write_text((DIR / "drishti.finance.yaml").read_text(encoding="utf-8").replace("Drishti Finance", "Acme Markets")
                   .replace("drishti.finance", "drishti.acme"), encoding="utf-8")
    d = dist.read(dist.file_of(DIR, str(own)), DIR / dist.KERNEL, MODULES, CONSOLE.parent / "config" / "packs")
    assert d.title == "Acme Markets" and d.help_title == "Acme Markets Help"
