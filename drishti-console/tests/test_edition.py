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

"""Editions and lenses in the console (docs/architecture/EDITIONS.md): with the server's edition learned from its about answer the console
follows config/editions/risk.yaml on every surface (menus, footer, the command line's words and placeholder, Home and its landings, help,
Admin, branding); without one it is exactly the full product; a lens does the same for one person only, kept in their settings."""
from __future__ import annotations

import re

import pytest
from fastapi.testclient import TestClient

from conftest import CONSOLE
from core.app import create_app
from core.auth import Identity
from core.config import load_settings

# today's top bar (the full product): every item the menus drew before editions, by link
TODAY = ["/t", "/s", "/forms", "/func", "/datamap", "/reports", "/w", "/m", "/alerts", "/build/new", "/build/pack/new", "/build",
         "/build/reviews", "/review/changes", "/what", "/help/tutorials", "/help/pack-developer-guide", "/admin/users", "/admin/roles",
         "/admin/audit", "/admin/packs", "/admin/connectors", "/admin/artifacts", "/admin/loads", "/admin/lakes", "/admin/collab",
         "/admin/embedding", "/admin/net", "/admin/health", "/admin/caches", "/help", "/help/install-and-run", "/about"]
RISK_ABOUT = {"product": "Drishti Risk", "tagline": "VaR and exposure from your own pricing data", "edition": {"name": "risk", "title": "Drishti Risk"}}


def _app(backend, monkeypatch, users: dict | None = None):
    """A fresh console over the stand-in server, its people told apart by a cookie (alice by default), each with their own settings."""
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = backend
    store: dict[str, dict] = {}

    async def settings(ident=None):
        return dict(store.get(ident.user if ident else "", {}))

    async def patch_settings(body, ident=None):
        mine = store.setdefault(ident.user, {})
        for k, v in body.items():
            if v is None:
                mine.pop(k, None)
            else:
                mine[k] = v
        return mine

    monkeypatch.setattr(backend, "settings", settings, raising=False)
    monkeypatch.setattr(backend, "patch_settings", patch_settings, raising=False)

    async def current(cookie, _backend):
        return Identity(user=cookie or "alice", display=(cookie or "alice").title(), desk="Risk", roles=("*",))

    monkeypatch.setattr(app.state.auth, "current", current)
    app.state.store = store
    return app


def _client(app, user="alice"):
    c = TestClient(app)
    c.cookies.set(app.state.auth.cookie, user)
    return c


def _menu_links(html: str) -> list[str]:
    nav = html[html.index('class="tbar-menus"'):html.index('class="tbar-tools"')]
    return re.findall(r'class="mega-item[^"]*" href="([^"]+)"', nav)


def test_without_an_edition_the_console_is_the_full_product(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    app.state.editions.learn({"product": "Drishti", "tagline": "Any data. Any domain. One grammar.", "edition": None, "lenses": []})
    c = _client(app)
    t = c.get("/t").text
    links = _menu_links(t)
    for href in TODAY:
        assert href in links, href
    assert "/var" not in links and "/exposure" not in links and "/lens/risk" not in links
    assert 'placeholder="TRD MX-20000001, CPTY north, TRD PRODUCT_TYPE=FX_QUANTO …  then &lt;GO&gt;"' in t
    assert c.get("/admin/ai").status_code == 200 and c.get("/admin/compute").status_code == 200
    home = c.get("/").text
    assert 'id="grammar"' in home and 'id="pipeline"' in home and "data-risk-landing" not in home
    assert app.state.settings.get("ui.product") == "Drishti"
    footer = home[home.index('class="site-footer"'):]
    assert "/about/competitive" in footer


def test_the_risk_edition_follows_its_file_on_every_surface(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    assert app.state.editions.learn(RISK_ABOUT)
    c = _client(app)
    t = c.get("/t").text
    links = _menu_links(t)
    assert "/var" in links and "/exposure" in links and "/func" in links and "/datamap" in links
    for hidden in ("/forms", "/build/new", "/build/reviews", "/help/tutorials", "/admin/collab", "/admin/embedding"):
        assert hidden not in links, hidden
    assert "Drishti Risk" in t and "VAR BANK, VAR DESK RATES" in t                     # branding and the placeholder
    # words and features switched off: pages answer 404, typed words are refused
    for path in ("/forms", "/pyconsole", "/build/new", "/admin/ai", "/admin/embedding", "/admin/edits", "/admin/compute"):
        assert c.get(path).status_code == 404, path
    for path in ("/admin/users", "/admin/packs"):
        assert c.get(path).status_code == 200, path
    r = c.get("/go", params={"q": "FORM trading <GO>"}, follow_redirects=False)
    assert r.status_code == 303 and "DRS-4023" in r.headers["location"]
    r = c.get("/go", params={"q": "RUPAKA <GO>"}, follow_redirects=False)
    assert "DRS-4023" in r.headers["location"]
    assert not [s for s in c.get("/api/suggest", params={"q": "FORM"}).json() if s.get("mnemonic") == "FORM"]
    # Admin's tab strip follows admin.pages
    users = c.get("/admin/users").text
    tabs = users[users.index('class="adm-tabs"'):]
    assert 'href="/admin/users"' in tabs and 'href="/admin/ai"' not in tabs and 'href="/admin/compute"' not in tabs
    # help: the BI and Build categories hidden, and a guide that is not listed is not reached by its address
    centre = c.get("/help").text
    assert 'href="/help/install-and-run"' in centre and 'href="/help/knowledge-market-risk"' in centre
    assert 'href="/help/bi-guide"' not in centre and 'href="/help/pack-developer-guide"' not in centre
    assert 'href="/help/knowledge-economies"' not in centre                           # a Knowledge page of a pack the edition leaves out
    for slug in ("bi-guide", "pack-developer-guide", "knowledge-economies"):
        assert c.get(f"/help/{slug}").status_code == 404, slug
    assert c.get("/help/install-and-run").status_code == 200
    from core.guides import Library
    from routes.common import packs as _packs  # noqa: F401
    lib = Library(CONSOLE, CONSOLE / "config" / "help.yaml", app.state.docs_dir, [])
    unlisted = next(s for s, g in lib.guides.items() if not g.listed)
    assert c.get(f"/help/{unlisted}").status_code == 404                              # help.unlisted-slugs: refuse
    # the footer is data: the competitive analysis link is not in the edition's footer
    assert "/about/competitive" not in t[t.index('class="site-footer"'):]


def test_home_offers_the_three_landings_and_remembers_the_choice(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    app.state.editions.learn(RISK_ABOUT)
    c = _client(app)
    home = c.get("/").text
    assert 'data-risk-landing="desk"' in home                                          # A, the default
    assert 'data-home-card="VAR BANK"' in home and 'data-home-card="EXP CPTY:CP-ALDERSHOT"' in home
    assert 'href="/home/landing/command"' in home and 'href="/home/landing/map"' in home
    assert c.get("/home/landing/map", follow_redirects=False).status_code == 303
    assert app.state.store["alice"]["homeLanding"] == "map"
    assert 'data-risk-landing="map"' in c.get("/").text                               # remembered (the person's settings)
    assert 'data-risk-landing="command"' in c.get("/home/landing/command").text
    classic = c.get("/", params={"landing": "today"}).text
    assert "data-risk-landing" not in classic and 'id="grammar"' not in classic and 'id="pipeline"' not in classic
    # another person keeps the default
    assert 'data-risk-landing="desk"' in _client(app, "bob").get("/").text


def test_a_role_mapping_wins_over_the_remembered_choice_only_when_configured(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    app.state.editions.learn(RISK_ABOUT)
    app.state.editions.instance.spec["home"]["landing-by-role"] = {"*": "command"}
    c = _client(app)
    c.get("/home/landing/map")
    assert 'data-risk-landing="command"' in c.get("/").text


def test_a_lens_focuses_one_person_and_leaves_everyone_else_alone(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    app.state.editions.learn({"product": "Drishti", "edition": None, "lenses": ["risk"]})
    alice, bob = _client(app, "alice"), _client(app, "bob")
    assert "/lens/risk" in _menu_links(alice.get("/t").text)                            # the link, in Views
    assert alice.get("/lens/risk", follow_redirects=False).status_code == 303
    assert app.state.store["alice"]["lens"] == "risk"
    page = alice.get("/t").text                                                         # every page keeps it (a refresh too)
    assert 'data-product="Drishti Risk"' in page and "/lens/leave" in _menu_links(page) and "/forms" not in _menu_links(page)
    assert alice.get("/forms").status_code == 404 and alice.get("/admin/ai").status_code == 404
    assert 'data-risk-landing="desk"' in alice.get("/").text
    sent = {}

    async def suggest(q, ident=None, limit=None):
        from core import edition
        sent.update(edition.headers())
        return []

    monkeypatch.setattr(backend, "suggest", suggest, raising=False)
    alice.get("/api/suggest", params={"q": "VRE"})
    assert sent == {"X-Drishti-Lens": "risk"}                                           # the server filters her commands and BI too
    # bob, at the same time, sees the full product
    other = bob.get("/t").text
    assert "/forms" in _menu_links(other) and "/lens/risk" in _menu_links(other) and 'data-product="Drishti"' in other
    assert bob.get("/admin/ai").status_code == 200
    sent.clear()
    bob.get("/api/suggest", params={"q": "VRE"})
    assert sent == {}
    # leaving it: the full product again
    alice.get("/lens/leave")
    assert "lens" not in app.state.store["alice"]
    assert alice.get("/admin/ai").status_code == 200 and "/lens/risk" in _menu_links(alice.get("/t").text)


def test_an_instance_with_an_edition_shows_no_lens_link_and_refuses_one(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    app.state.editions.learn({**RISK_ABOUT, "lenses": ["risk"]})
    c = _client(app)
    assert not [h for h in _menu_links(c.get("/t").text) if h.startswith("/lens/")]
    r = c.get("/lens/risk", follow_redirects=False)
    assert "DRS-4023" in r.headers["location"]


@pytest.mark.parametrize("key", ["ui.logo", "ui.favicon"])
def test_logo_and_icons_come_from_configuration(backend, monkeypatch, key):
    app = create_app(load_settings(CONSOLE / "config", [f"--{key}=/static/img/acme.svg", "--ui.product=Acme Risk"]))
    app.state.backend = backend
    c = TestClient(app)
    page = c.get("/").text
    if key == "ui.logo":
        assert 'class="brand-mark brand-logo" src="/static/img/acme.svg"' in page
    else:
        assert '<link rel="icon" href="/static/img/acme.svg">' in page
    m = c.get("/manifest.webmanifest").json()
    assert m["name"] == "Acme Risk" and m["icons"][0]["src"] in ("/static/img/acme.svg", "/static/img/icons/icon-192.png")
