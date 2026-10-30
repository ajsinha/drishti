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


# ---- Drishti World (2.9.7): the second edition, its landings, and two lenses side by side --------------------------------------------
WORLD_ABOUT = {"product": "Drishti World", "tagline": "Economies, markets, companies and funds from public data",
               "edition": {"name": "world", "title": "Drishti World"}}


def test_the_world_edition_follows_its_file_on_every_surface(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    assert app.state.editions.learn(WORLD_ABOUT)
    c = _client(app)
    t = c.get("/t").text
    links = _menu_links(t)
    for shown in ("/t", "/s", "/forms", "/func", "/datamap", "/reports", "/w", "/m", "/alerts"):
        assert shown in links, shown
    for hidden in ("/var", "/exposure", "/build/new", "/admin/collab", "/admin/embedding"):
        assert hidden not in links, hidden
    assert "Drishti World" in t and "ECON CAN, NEWS rates, USEQ AAPL" in t                # branding and the placeholder
    for path in ("/var", "/exposure", "/pyconsole", "/build/new", "/admin/ai", "/admin/compute"):
        assert c.get(path).status_code == 404, path
    assert c.get("/admin/packs").status_code == 200 and c.get("/admin/users").status_code == 200
    for typed in ("VAR BANK <GO>", "RUPAKA <GO>"):                                  # EXP is also a mnemonic: the server refuses it
        r = c.get("/go", params={"q": typed}, follow_redirects=False)
        assert r.status_code == 303 and "DRS-4023" in r.headers["location"], typed
    # help: the packs' Knowledge pages, the BI category kept, Build hidden; the risk Knowledge pages are not part of World
    centre = c.get("/help").text
    for slug in ("knowledge-economies", "knowledge-companies-funds-and-etfs", "knowledge-entities-and-ownership", "bi-guide", "external-data"):
        assert f'href="/help/{slug}"' in centre, slug
    for slug in ("knowledge-market-risk", "knowledge-counterparty-credit-risk", "pack-developer-guide"):
        assert f'href="/help/{slug}"' not in centre and c.get(f"/help/{slug}").status_code == 404, slug


def _world_searches(backend, monkeypatch, sent: list):
    async def search(q, ident=None):
        from core import edition
        sent.append((q, edition.headers()))
        return {"kind": "economy", "matched": 213, "rows": [{"ref": {"kind": "economy", "id": "ECON-CAN"}, "values": {"$.NAME": "Canada"}}]}

    monkeypatch.setattr(backend, "search", search, raising=False)


def test_world_home_offers_its_three_landings_and_remembers_the_choice(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    app.state.editions.learn(WORLD_ABOUT)

    async def history(ident=None):
        return ["ECON CAN", "TRD MX-20000001", "VAR BANK", "FUND SPY", "ECON CAN"]

    monkeypatch.setattr(backend, "command_history", history, raising=False)
    c = _client(app)
    home = c.get("/").text
    assert 'data-world-landing="desk"' in home and "data-risk-landing" not in home        # A, the default
    assert "/static/js/world-landing.js" in home and "/static/css/world-landing.css" in home
    assert 'data-home-card="ECON CAN"' in home and 'data-home-card="FUND SPY"' in home
    assert ">World desk<" in home and ">Explorer<" in home and ">World map<" in home
    assert 'href="/home/landing/explorer"' in home and 'href="/home/landing/map"' in home
    assert c.get("/home/landing/explorer", follow_redirects=False).status_code == 303
    assert app.state.store["alice"]["homeLanding"] == "explorer"
    explorer = c.get("/").text                                                          # remembered (the person's settings)
    assert 'data-world-landing="explorer"' in explorer and 'id="wlCmd"' in explorer
    assert "ECG ECG-WLD" in explorer and "DATAMAP" in explorer                         # the chips
    at = explorer.index("data-wl-recents")
    recents = explorer[at:explorer.index("</ul>", at)]
    assert "ECON CAN" in recents and "FUND SPY" in recents and "TRD" not in recents and "VAR" not in recents
    assert recents.count(">ECON CAN<") == 1
    assert 'data-world-landing="map"' in c.get("/home/landing/map").text
    c.get("/home/landing/command")                                                     # a Risk landing is not World's: not kept
    assert app.state.store["alice"]["homeLanding"] == "map"
    assert 'data-world-landing="desk"' in _client(app, "bob").get("/").text             # another person keeps the default


def test_the_landings_read_through_a_search_that_follows_the_edition(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    app.state.editions.learn({"product": "Drishti", "edition": None, "lenses": ["risk", "world"]})
    sent: list = []
    _world_searches(backend, monkeypatch, sent)
    c = _client(app)
    assert c.get("/api/home/search", params={"q": "ECON limit 1"}).status_code == 404     # no edition, no lens: no landing read
    c.get("/lens/world")
    r = c.get("/api/home/search", params={"q": "ECON where GDP_USD_BN > 0 order by GDP_USD_BN desc limit 12"})
    assert r.status_code == 200 and r.json()["matched"] == 213
    assert sent[-1] == ("ECON where GDP_USD_BN > 0 order by GDP_USD_BN desc limit 12", {"X-Drishti-Lens": "world"})
    for hidden in ("VRES limit 1", "TRD limit 1", "FORM", "DATAMAP"):
        r = c.get("/api/home/search", params={"q": hidden})
        assert r.status_code == 404 and r.json()["code"] == "DRS-4023", hidden
    c.get("/lens/risk")                                                                 # in Risk, the World commands are hidden
    assert c.get("/api/home/search", params={"q": "ECON limit 1"}).status_code == 404


def test_two_lenses_each_person_opens_either_and_switches_between_them(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    app.state.editions.learn({"product": "Drishti", "edition": None, "lenses": ["risk", "world"]})
    alice, bob = _client(app, "alice"), _client(app, "bob")
    page = alice.get("/t").text
    links = _menu_links(page)
    assert "/lens/risk" in links and "/lens/world" in links and "/lens/leave" not in links
    assert "Open Drishti Risk" in page and "Open Drishti World" in page
    home = alice.get("/").text                                                          # on the product's Home too
    assert 'data-lens-link="risk"' in home and 'data-lens-link="world"' in home
    alice.get("/lens/risk")
    assert app.state.store["alice"]["lens"] == "risk"
    page = alice.get("/t").text
    assert 'data-product="Drishti Risk"' in page and "/var" in _menu_links(page)
    assert "Leave Drishti Risk" in page and "Switch to Drishti World" in page and "Open Drishti Risk" not in page
    assert _menu_links(page).index("/lens/leave") < _menu_links(page).index("/lens/world")
    risk_home = alice.get("/").text
    assert 'data-risk-landing="desk"' in risk_home and 'data-lens-link="world"' in risk_home
    alice.get("/home/landing/map")                                                      # chosen in Risk ...
    alice.get("/lens/world")                                                            # ... switching: no need to leave first
    assert app.state.store["alice"]["lens"] == "world"
    page = alice.get("/t").text
    assert 'data-product="Drishti World"' in page and "/var" not in _menu_links(page) and "/forms" in _menu_links(page)
    assert "Leave Drishti World" in page and "Switch to Drishti Risk" in page
    assert 'data-world-landing="map"' in alice.get("/").text                            # map is both editions' third landing
    assert alice.get("/var").status_code == 404
    alice.get("/home/landing/explorer")
    alice.get("/lens/risk")
    assert 'data-risk-landing="desk"' in alice.get("/").text                            # World's explorer is not Risk's: its default
    # bob, at the same time, sees the full product with both links
    other = bob.get("/t").text
    assert 'data-product="Drishti"' in other and "/lens/risk" in _menu_links(other) and "/lens/world" in _menu_links(other)
    alice.get("/lens/leave")
    assert "lens" not in app.state.store["alice"]
    back = alice.get("/t").text
    assert "/lens/risk" in _menu_links(back) and "/lens/world" in _menu_links(back) and 'data-product="Drishti"' in back


def test_a_landing_template_must_be_a_home_landing_page():
    from core.edition import Edition
    assert Edition("x", {"home": {"template": "../base.html"}}).landing_template == "home/risk_landing.html"
    assert Edition("x", {"home": {"template": "home/world_landing.html"}}).landing_template == "home/world_landing.html"
    assert Edition("x", {"home": {"landings": ["desk", "explorer"], "titles": {"explorer": "Look up"}}}).landing_titles() == \
        {"desk": "Risk desk", "explorer": "Look up"}


# ---- Drishti Market (2.9.7): the third edition, its landings (desk, explorer, quality), and three lenses -------------------------------
MARKET_ABOUT = {"product": "Drishti Market", "tagline": "Curves, surfaces and fixings: the bank's market data that feeds risk and valuations",
                "edition": {"name": "market", "title": "Drishti Market"}}


@pytest.fixture
def market():
    from core import market_landing
    from market_fake import MarketBackend
    market_landing.forget()
    yield MarketBackend()
    market_landing.forget()


def test_the_market_edition_follows_its_file_on_every_surface(market, monkeypatch):
    app = _app(market, monkeypatch)
    assert app.state.editions.learn(MARKET_ABOUT)
    c = _client(app)
    t = c.get("/t").text
    links = _menu_links(t)
    for shown in ("/t", "/s", "/forms", "/func", "/datamap", "/reports", "/w", "/m", "/alerts"):
        assert shown in links, shown
    for hidden in ("/var", "/exposure", "/build/new", "/admin/collab", "/admin/embedding"):
        assert hidden not in links, hidden
    assert "Drishti Market" in t and "CRV CRV-USD-OIS, FXV FXV-EURUSD, IRV IRV-USD" in t               # branding and the placeholder
    for path in ("/var", "/exposure", "/pyconsole", "/build/new", "/admin/ai", "/admin/compute"):
        assert c.get(path).status_code == 404, path
    for typed in ("VAR BANK <GO>", "RUPAKA <GO>"):
        r = c.get("/go", params={"q": typed}, follow_redirects=False)
        assert r.status_code == 303 and "DRS-4023" in r.headers["location"], typed
    centre = c.get("/help").text
    for slug in ("knowledge-market-data", "knowledge-data-and-contracts", "user-guide", "data-samples"):
        assert f'href="/help/{slug}"' in centre, slug
    for slug in ("knowledge-market-risk", "knowledge-economies", "knowledge-counterparty-credit-risk", "pack-developer-guide"):
        assert f'href="/help/{slug}"' not in centre and c.get(f"/help/{slug}").status_code == 404, slug


def test_market_home_offers_desk_explorer_and_quality_and_remembers_the_choice(market, monkeypatch):
    app = _app(market, monkeypatch)
    app.state.editions.learn(MARKET_ABOUT)
    c = _client(app)
    home = c.get("/").text
    assert 'data-market-landing="desk"' in home and "data-world-landing" not in home and "data-risk-landing" not in home
    assert "/static/js/market-landing.js" in home and "/static/css/market-landing.css" in home
    for card in ("CRV CRV-USD-OIS", "FXV FXV-EURUSD", "IRV IRV-USD", "FIX FIX-SOFR"):
        assert f'data-home-card="{card}"' in home, card
    assert ">Market desk<" in home and ">Explorer<" in home and ">Quality<" in home
    assert 'data-ml-tile="exceptions"' in home and 'href="/home/landing/quality"' in home
    assert c.get("/home/landing/explorer", follow_redirects=False).status_code == 303
    assert app.state.store["alice"]["homeLanding"] == "explorer"
    explorer = c.get("/").text
    assert 'data-market-landing="explorer"' in explorer and 'id="mlCmd"' in explorer
    assert "CRV CRV-CAD-GOVT" in explorer and "CORR CORR-G10FX" in explorer                         # the chips
    for gid in ("rates", "fx", "credit", "equity"):
        assert f'data-ml-group="{gid}"' in explorer, gid
    at = explorer.index("data-ml-recents")
    recents = explorer[at:explorer.index("</ul>", at)]
    assert "CRV CRV-USD-OIS" in recents and "FXV FXV-EURUSD" in recents
    assert "ECON" not in recents and "VAR" not in recents and "TRD" not in recents and recents.count(">CRV CRV-USD-OIS<") == 1
    quality = c.get("/home/landing/quality").text
    assert 'data-market-landing="quality"' in quality and 'data-stale-days="2"' in quality
    assert "no update in 2 business days" in quality
    assert "Impact on VaR and valuations comes in a later release" in quality and "VaR at stake" not in quality
    c.get("/home/landing/map")                                                         # a World landing is not Market's: not kept
    assert app.state.store["alice"]["homeLanding"] == "quality"


def test_the_market_figures_follow_the_edition_and_the_lens(market, monkeypatch):
    app = _app(market, monkeypatch)
    app.state.editions.learn({"product": "Drishti", "edition": None, "lenses": ["risk", "world", "market"]})
    c = _client(app)
    for part in ("desk", "moves", "quality"):                                          # no edition, no lens: not a landing's
        r = c.get(f"/api/home/market/{part}")
        assert r.status_code == 404 and r.json()["code"] == "DRS-4023", part
    c.get("/lens/world")
    assert c.get("/api/home/market/quality").status_code == 404                        # World shows no curves
    assert c.get("/api/home/search", params={"q": "CRV limit 1"}).status_code == 404
    c.get("/lens/market")
    q = c.get("/api/home/market/quality").json()
    assert len(q["critical"]) == 3 and len(q["warnings"]) == 4 and q["cleanPct"] == pytest.approx(57.1)
    d = c.get("/api/home/market/desk").json()
    assert {t["id"] for t in d["tiles"]} == {"ois10y", "slope", "fxvol", "cds"}
    assert c.get("/api/home/market/moves").json()["mode"] == "sigma"
    assert c.get("/api/home/market/nope").status_code == 404
    assert c.get("/api/home/search", params={"q": "CRV limit 1"}).json()["matched"] == 28
    for hidden in ("ECON limit 1", "VRES limit 1", "TRD limit 1"):
        r = c.get("/api/home/search", params={"q": hidden})
        assert r.status_code == 404 and r.json()["code"] == "DRS-4023", hidden


def test_the_stale_threshold_comes_from_the_edition_file(market, monkeypatch):
    app = _app(market, monkeypatch)
    app.state.editions.learn(MARKET_ABOUT)
    app.state.editions.instance.spec["quality"] = {"stale-days": 5}
    page = _client(app).get("/home/landing/quality").text
    assert 'data-stale-days="5"' in page and "no update in 5 business days" in page
    assert not [e for e in _client(app).get("/api/home/market/quality").json()["warnings"] if e["check"] == "stale"]


def test_three_lenses_each_person_opens_any_and_switches_between_them(market, monkeypatch):
    app = _app(market, monkeypatch)
    app.state.editions.learn({"product": "Drishti", "edition": None, "lenses": ["risk", "world", "market"]})
    alice = _client(app, "alice")
    page = alice.get("/t").text
    links = _menu_links(page)
    assert "/lens/risk" in links and "/lens/world" in links and "/lens/market" in links
    assert "Open Drishti Risk" in page and "Open Drishti World" in page and "Open Drishti Market" in page
    assert 'data-lens-link="market"' in alice.get("/").text
    alice.get("/lens/market")
    assert app.state.store["alice"]["lens"] == "market"
    page = alice.get("/t").text
    assert 'data-product="Drishti Market"' in page and "/var" not in _menu_links(page) and "/forms" in _menu_links(page)
    assert "Leave Drishti Market" in page and "Switch to Drishti Risk" in page and "Switch to Drishti World" in page
    assert "Open Drishti Market" not in page and "Switch to Drishti Market" not in page
    assert 'data-market-landing="desk"' in alice.get("/").text
    alice.get("/home/landing/quality")
    alice.get("/lens/world")                                                           # switching: no need to leave first
    assert 'data-world-landing="desk"' in alice.get("/").text                          # quality is not World's: its default
    assert "Switch to Drishti Market" in alice.get("/t").text
    alice.get("/lens/risk")
    assert 'data-risk-landing="desk"' in alice.get("/").text
    alice.get("/lens/market")
    assert 'data-market-landing="quality"' in alice.get("/").text                      # the choice made in Market is kept
    assert alice.get("/var").status_code == 404
    alice.get("/lens/leave")
    assert "lens" not in app.state.store["alice"]


# ---- The design links follow the lens (product owner, 2026-10-30): inside a lens Build and Studio are off, so a page names its Sutra
# without a link that would only answer 404 ("How this view was built", About this page, a pack's page); the full product keeps them --


def _provenance_html(app, lens):
    from core import edition as ed

    ed.set_lens(lens)
    try:
        p = {"id": "built", "kind": "provenance", "title": "How this view was built",
             "data": {"fields": [{"label": "Layout", "text": "Sutra irs-vanilla v3"}, {"label": "Source", "text": "trade-calypso, gen 7"}]}}
        tpl = app.state.templates.env.from_string('{% from "_macros/panels.html" import panel %}{{ panel(p, ref=ref) }}')
        return tpl.render(p=p, ref={"kind": "trade", "id": "IRS-48213"})
    finally:
        ed.set_lens(None)


def test_inside_a_lens_the_sutra_is_named_without_a_design_link(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    app.state.editions.learn({"product": "Drishti", "edition": None, "lenses": ["risk", "world", "market"]})
    full = _provenance_html(app, None)
    assert "data-prov-sutra" in full and "/studio?sutra=irs-vanilla%403" in full                  # the full product: the designer link
    for lens in ("risk", "world", "market"):
        inside = _provenance_html(app, lens)
        assert "/studio" not in inside and "data-prov-sutra" not in inside, lens                    # no link that would answer 404
        assert "irs-vanilla v3" in inside and "data-prov-source" in inside, lens                    # still named; the source link stays
