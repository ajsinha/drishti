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

"""Distributions and lenses in the console (docs/guides/ADMIN_GUIDE.md, the three layers of switches; docs/architecture/LENSES.md): what
exists is the distribution's (learned from the server's about answer); a lens is a workspace one person works in, kept in their settings:
it arranges (Home and its landings, the menus it names first, the placeholder) and never hides anything; the distribution's default lens
is everyone's starting workspace. Without a distribution the console is exactly the full product."""
from __future__ import annotations

import html
import re

import pytest
from fastapi.testclient import TestClient

from conftest import CONSOLE, help_pages
from core.app import create_app
from core.auth import Identity
from core.config import load_settings

# today's top bar (the full product): every item the menus drew before editions, by link
TODAY = ["/t", "/s", "/forms", "/func", "/datamap", "/reports", "/w", "/m", "/alerts", "/build/new", "/build/pack/designer", "/build",
         "/approvals", "/what", "/help/tutorials", "/help/build-guide", "/admin/users", "/admin/roles",
         "/admin/audit", "/admin/packs", "/admin/connectors", "/admin/artifacts", "/admin/loads", "/admin/lakes", "/admin/collab",
         "/admin/embedding", "/admin/net", "/admin/health", "/admin/caches", "/help", "/help/operations#2-install-and-run-on-one-machine", "/about"]
RISK_ABOUT = {"product": "Drishti Risk", "tagline": "VaR and exposure from your own pricing data",
              "edition": {"name": "drishti.risk", "title": "Drishti Risk"}, "lenses": ["risk", "trading", "market"]}   # its default lens: risk
FULL_ABOUT = {"product": "Drishti", "edition": None, "lenses": ["risk", "world", "market", "trading"]}


def _workspace(app, lens: str, *people: str):
    """A full instance offering the four lenses, with these people (alice by default) working in ``lens`` (their settings)."""
    app.state.editions.learn(FULL_ABOUT)
    for who in people or ("alice",):
        app.state.store.setdefault(who, {})["lens"] = lens


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
    assert "/lens/risk" not in links
    assert "/var" in links and "/exposure" in links and "data-engines-menu" not in t   # VaR and Exposure under Risk (the Engines menu removed)
    views = t[t.index('<span class="tbar-menu-l">Views</span>'):t.index('<span class="tbar-menu-l">Risk</span>')]
    assert 'href="/var"' not in views and 'href="/exposure"' not in views             # not in Views: one place each
    assert 'placeholder="TRD MX-20000001, CPTY north, TRD PRODUCT_TYPE=FX_QUANTO …  then &lt;GO&gt;"' in t
    assert c.get("/admin/ai").status_code == 200 and c.get("/admin/compute").status_code == 200
    home = c.get("/").text
    assert 'id="grammar"' in home and 'id="pipeline"' in home and "data-risk-landing" not in home
    assert app.state.settings.get("ui.product") == "Drishti"
    footer = home[home.index('class="site-footer"'):]
    assert "/help/release-notes" in footer and "competitive" not in footer   # the competitive analysis is in the Help menu, administrators only


def test_drishti_risk_starts_everyone_in_the_risk_workspace_which_hides_nothing_it_has(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    assert app.state.editions.learn(RISK_ABOUT)
    c = _client(app)
    t = c.get("/t").text
    links = _menu_links(t)
    assert "/var" in links and "/exposure" in links and "/func" in links and "/datamap" in links and "/forms" in links
    assert "Drishti Risk" in t and "VAR BANK, VAR DESK RATES" in t                     # the brand; the lens's placeholder
    assert 'data-risk-landing="desk"' in c.get("/").text                                # the default lens: the Risk desk first
    for path in ("/admin/ai", "/admin/compute", "/admin/users", "/admin/packs", "/smat"):
        assert c.get(path).status_code == 200, path                                     # the lens hides nothing
    r = c.get("/go", params={"q": "SMAT DESK RATES 30 <GO>"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/smat?id=DESK%3ARATES&days=30"
    r = c.get("/go", params={"q": "ECON CAN <GO>"}, follow_redirects=False)            # what Drishti Risk leaves out does not exist
    assert r.status_code == 303 and "DRS-4023" in r.headers["location"]
    centre = help_pages(c)
    assert 'href="/help/knowledge-risk"' in centre and 'href="/help/bi-guide"' in centre and 'href="/help/knowledge-world"' not in centre
    assert "competitive" not in t[t.index('class="site-footer"'):]


def test_home_offers_the_three_landings_and_remembers_the_choice(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    app.state.editions.learn(RISK_ABOUT)
    c = _client(app)
    home = c.get("/").text
    assert 'data-risk-landing="desk"' in home                                          # A, the default
    assert 'data-home-card="VAR BANK"' in home and 'data-home-card="EXP CPTY:CP-ALDERSHOT"' in home
    assert 'href="/home/landing/command"' in home and 'href="/home/landing/map"' in home
    assert c.post("/home/landing/map", follow_redirects=False).status_code == 303
    assert app.state.store["alice"]["homeLanding"] == "map"
    assert 'data-risk-landing="map"' in c.get("/").text                               # remembered (the person's settings)
    assert 'data-risk-landing="command"' in c.post("/home/landing/command").text
    classic = c.get("/", params={"landing": "today"}).text
    assert "data-risk-landing" not in classic and 'id="grammar"' not in classic and 'id="pipeline"' not in classic
    # another person keeps the default
    assert 'data-risk-landing="desk"' in _client(app, "bob").get("/").text


def test_a_role_mapping_wins_over_the_remembered_choice_only_when_configured(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    app.state.editions.learn(RISK_ABOUT)
    app.state.editions.lenses["risk"].spec["home"]["landing-by-role"] = {"*": "command"}
    c = _client(app)
    c.post("/home/landing/map")
    assert 'data-risk-landing="command"' in c.get("/").text


def test_a_lens_arranges_one_persons_workspace_and_hides_nothing(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    app.state.editions.learn({"product": "Drishti", "edition": None, "lenses": ["risk"]})
    alice, bob = _client(app, "alice"), _client(app, "bob")
    before = alice.get("/t").text
    assert "/lens/risk" in _menu_links(before)                                         # the link, in Views
    assert alice.post("/lens/risk", follow_redirects=False).status_code == 303
    assert app.state.store["alice"]["lens"] == "risk"
    page = alice.get("/t").text                                                         # every page keeps it (a refresh too)
    assert "/lens/leave" in _menu_links(page)
    assert set(_menu_links(before)) - {"/lens/risk"} <= set(_menu_links(page))         # nothing left out
    assert alice.get("/admin/ai").status_code == 200 and alice.get("/admin/compute").status_code == 200
    assert 'data-risk-landing="desk"' in alice.get("/").text and "data-risk-landing" not in bob.get("/").text
    said = [alice.get("/api/search", params={"in": "command", "q": "FO"}).json()["items"] for alice in (alice, bob)]
    assert said[0] == said[1]                                                            # the same commands offered
    alice.post("/lens/leave")                                                           # leaving it: the full product's Home again
    assert "lens" not in app.state.store["alice"] and "data-risk-landing" not in alice.get("/").text


def test_a_distribution_offers_its_lenses_and_leaving_one_returns_to_its_default(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    app.state.editions.learn(RISK_ABOUT)
    c = _client(app)
    links = _menu_links(c.get("/t").text)
    assert "/lens/trading" in links and "/lens/market" in links and "/lens/leave" not in links   # the default lens: nothing to leave
    assert c.post("/lens/trading", follow_redirects=False).status_code == 303 and app.state.store["alice"]["lens"] == "trading"
    assert "/lens/leave" in _menu_links(c.get("/t").text) and 'data-trading-landing' in c.get("/").text
    c.post("/lens/leave")
    assert 'data-risk-landing="desk"' in c.get("/").text                               # back in the distribution's starting workspace
    r = c.post("/lens/nope", follow_redirects=False)
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
    assert m["name"] == "Acme Risk" and m["icons"][0]["src"] in ("/static/img/acme.svg", "/static/img/icons/drishti_icon-192.png")


# ---- Drishti World (2.9.7): the second edition, its landings, and two lenses side by side --------------------------------------------


@pytest.mark.parametrize("lens", ["risk", "world", "market", "trading"])
def test_each_lens_arranges_and_hides_nothing(backend, monkeypatch, lens):
    """The same pages, menu items, help and words inside a lens as outside it; only the order, Home and the placeholder change."""
    app = _app(backend, monkeypatch)
    _workspace(app, lens, "alice")
    alice, bob = _client(app, "alice"), _client(app, "bob")
    mine, theirs = alice.get("/t").text, bob.get("/t").text
    assert {h for h in _menu_links(theirs) if not h.startswith("/lens/")} <= set(_menu_links(mine)), lens
    for path in ("/var", "/exposure", "/datamap", "/build/new", "/admin/ai", "/admin/compute", "/admin/users", "/smat"):
        assert alice.get(path).status_code == bob.get(path).status_code, (lens, path)
    for typed in ("VAR BANK <GO>", "RUPAKA <GO>", "FORM trading <GO>", "ECON CAN <GO>", "DATAMAP <GO>"):
        a = alice.get("/go", params={"q": typed}, follow_redirects=False).headers.get("location", "")
        assert "DRS-4023" not in a, (lens, typed)
    assert set(re.findall(r'href="(/help/[^"#]+)', help_pages(alice))) == set(re.findall(r'href="(/help/[^"#]+)', help_pages(bob))), lens
    edition = app.state.editions.for_request(lens)
    said = f'placeholder="{html.escape(edition.placeholder)}"'                          # the lens's own placeholder (arranging)
    assert edition.lens and said in mine and said not in theirs


def _world_searches(backend, monkeypatch, sent: list):
    async def search(q, ident=None):
        from core import edition
        sent.append((q, edition.headers()))
        return {"kind": "economy", "matched": 213, "rows": [{"ref": {"kind": "economy", "id": "ECON-CAN"}, "values": {"$.NAME": "Canada"}}]}

    monkeypatch.setattr(backend, "search", search, raising=False)


def test_world_home_offers_its_three_landings_and_remembers_the_choice(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    _workspace(app, "world", "alice", "bob")

    async def history(ident=None):
        return ["ECON CAN", "TRD MX-20000001", "VAR BANK", "FUND SPY", "ECON CAN"]

    monkeypatch.setattr(backend, "command_history", history, raising=False)
    c = _client(app)
    home = c.get("/").text
    assert 'data-world-landing="desk"' in home and "data-risk-landing" not in home        # A, the default
    assert "/m/economy/static/1.0.0/js/drishti_world-landing.js" in home and "/m/economy/static/1.0.0/css/drishti_world-landing.css" in home
    assert 'data-home-card="ECON CAN"' in home and 'data-home-card="FUND SPY"' in home
    assert ">World desk<" in home and ">Explorer<" in home and ">World map<" in home
    assert 'href="/home/landing/explorer"' in home and 'href="/home/landing/map"' in home
    assert c.post("/home/landing/explorer", follow_redirects=False).status_code == 303
    assert app.state.store["alice"]["homeLanding"] == "explorer"
    explorer = c.get("/").text                                                          # remembered (the person's settings)
    assert 'data-world-landing="explorer"' in explorer and 'id="wlCmd"' in explorer
    assert "ECG ECG-WLD" in explorer and "DATAMAP" in explorer                         # the chips
    at = explorer.index("data-wl-recents")
    recents = explorer[at:explorer.index("</ul>", at)]
    assert "ECON CAN" in recents and "FUND SPY" in recents and recents.count(">ECON CAN<") == 1   # once each; a lens hides none
    assert 'data-world-landing="map"' in c.post("/home/landing/map").text
    c.post("/home/landing/command")                                                     # a Risk landing is not World's: not kept
    assert app.state.store["alice"]["homeLanding"] == "map"
    assert 'data-world-landing="desk"' in _client(app, "bob").get("/").text             # another person keeps the default


def test_the_landings_read_through_the_search_inside_a_workspace(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    app.state.editions.learn({"product": "Drishti", "edition": None, "lenses": ["risk", "world"]})
    sent: list = []
    _world_searches(backend, monkeypatch, sent)
    c = _client(app)
    assert c.get("/api/home/search", params={"q": "ECON limit 1"}).status_code == 404     # no lens: no landing read
    c.post("/lens/world")
    r = c.get("/api/home/search", params={"q": "ECON where GDP_USD_BN > 0 order by GDP_USD_BN desc limit 12"})
    assert r.status_code == 200 and r.json()["matched"] == 213
    assert sent[-1][0] == "ECON where GDP_USD_BN > 0 order by GDP_USD_BN desc limit 12"
    assert c.get("/api/home/search", params={"q": "VRES limit 1"}).status_code == 200    # a lens hides no command
    for word in ("FORM", "DATAMAP"):                                                     # a word is not a landing's read
        r = c.get("/api/home/search", params={"q": word})
        assert r.status_code == 404 and r.json()["code"] == "DRS-4023", word
    c.post("/lens/risk")
    assert c.get("/api/home/search", params={"q": "ECON limit 1"}).status_code == 200


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
    alice.post("/lens/risk")
    assert app.state.store["alice"]["lens"] == "risk"
    page = alice.get("/t").text
    assert 'data-product="Drishti"' in page and "/var" in _menu_links(page)                 # a lens never renames the product
    assert "Leave Drishti Risk" in page and "Switch to Drishti World" in page and "Open Drishti Risk" not in page
    assert _menu_links(page).index("/lens/leave") < _menu_links(page).index("/lens/world")
    risk_home = alice.get("/").text
    assert 'data-risk-landing="desk"' in risk_home and 'data-lens-link="world"' in risk_home
    alice.post("/home/landing/map")                                                      # chosen in Risk ...
    alice.post("/lens/world")                                                            # ... switching: no need to leave first
    assert app.state.store["alice"]["lens"] == "world"
    page = alice.get("/t").text
    assert "/var" in _menu_links(page) and "/forms" in _menu_links(page)                  # World hides nothing
    assert "Leave Drishti World" in page and "Switch to Drishti Risk" in page
    assert 'data-world-landing="map"' in alice.get("/").text                            # map is both lenses' third landing
    assert alice.get("/var").status_code == 200
    alice.post("/home/landing/explorer")
    alice.post("/lens/risk")
    assert 'data-risk-landing="desk"' in alice.get("/").text                            # World's explorer is not Risk's: its default
    # bob, at the same time, sees the full product with both links
    other = bob.get("/t").text
    assert 'data-product="Drishti"' in other and "/lens/risk" in _menu_links(other) and "/lens/world" in _menu_links(other)
    alice.post("/lens/leave")
    assert "lens" not in app.state.store["alice"]
    back = alice.get("/t").text
    assert "/lens/risk" in _menu_links(back) and "/lens/world" in _menu_links(back) and 'data-product="Drishti"' in back


def test_a_landing_template_must_be_a_home_landing_page():
    from core.edition import Edition
    assert Edition("x", {"home": {"template": "../base.html"}}).landing_template == "home/risk_landing.html"
    assert Edition("x", {"home": {"template": "economy:world_landing.html"}}).landing_template == "economy:world_landing.html"
    assert Edition("x", {"home": {"landings": ["desk", "explorer"], "titles": {"explorer": "Look up"}}}).landing_titles() == \
        {"desk": "Risk desk", "explorer": "Look up"}


# ---- Drishti Market (2.9.7): the third edition, its landings (desk, explorer, quality), and three lenses -------------------------------


@pytest.fixture
def market():
    from plugins.banking import market_landing
    from market_fake import MarketBackend
    market_landing.forget()
    yield MarketBackend()
    market_landing.forget()


def test_market_home_offers_desk_explorer_and_quality_and_remembers_the_choice(market, monkeypatch):
    app = _app(market, monkeypatch)
    _workspace(app, "market")
    c = _client(app)
    home = c.get("/").text
    assert 'data-market-landing="desk"' in home and "data-world-landing" not in home and "data-risk-landing" not in home
    assert "/static/js/drishti_market-landing.js" in home and "/static/css/drishti_market-landing.css" in home
    for card in ("CRV CRV-USD-OIS", "FXV FXV-EURUSD", "IRV IRV-USD", "FIX FIX-SOFR"):
        assert f'data-home-card="{card}"' in home, card
    assert ">Market desk<" in home and ">Explorer<" in home and ">Quality<" in home
    assert 'data-ml-tile="exceptions"' in home and 'href="/home/landing/quality"' in home
    assert c.post("/home/landing/explorer", follow_redirects=False).status_code == 303
    assert app.state.store["alice"]["homeLanding"] == "explorer"
    explorer = c.get("/").text
    assert 'data-market-landing="explorer"' in explorer and 'id="mlCmd"' in explorer
    assert "CRV CRV-CAD-GOVT" in explorer and "CORR CORR-G10FX" in explorer                         # the chips
    for gid in ("rates", "fx", "credit", "equity"):
        assert f'data-ml-group="{gid}"' in explorer, gid
    at = explorer.index("data-ml-recents")
    recents = explorer[at:explorer.index("</ul>", at)]
    assert "CRV CRV-USD-OIS" in recents and "FXV FXV-EURUSD" in recents
    assert recents.count(">CRV CRV-USD-OIS<") == 1                                                    # once each; a lens hides none
    quality = c.post("/home/landing/quality").text
    assert 'data-market-landing="quality"' in quality and 'data-stale-days="2"' in quality
    assert "no update in 2 business days" in quality
    assert "Impact on VaR and valuations comes in a later release" in quality and "VaR at stake" not in quality
    c.post("/home/landing/map")                                                         # a World landing is not Market's: not kept
    assert app.state.store["alice"]["homeLanding"] == "quality"


def test_the_market_figures_are_read_inside_a_workspace(market, monkeypatch):
    app = _app(market, monkeypatch)
    app.state.editions.learn({"product": "Drishti", "edition": None, "lenses": ["risk", "world", "market"]})
    c = _client(app)
    for part in ("desk", "moves", "quality"):                                          # no lens: not a landing's
        r = c.get(f"/api/home/market/{part}")
        assert r.status_code == 404 and r.json()["code"] == "DRS-4023", part
    c.post("/lens/market")
    q = c.get("/api/home/market/quality").json()
    assert len(q["critical"]) == 3 and len(q["warnings"]) == 4 and q["cleanPct"] == pytest.approx(57.1)
    d = c.get("/api/home/market/desk").json()
    assert {t["id"] for t in d["tiles"]} == {"ois10y", "slope", "fxvol", "cds"}
    assert c.get("/api/home/market/moves").json()["mode"] == "sigma"
    assert c.get("/api/home/market/nope").status_code == 404
    assert c.get("/api/home/search", params={"q": "CRV limit 1"}).json()["matched"] == 28


def test_the_stale_threshold_comes_from_the_edition_file(market, monkeypatch):
    app = _app(market, monkeypatch)
    _workspace(app, "market")
    app.state.editions.lenses["market"].spec["quality"] = {"stale-days": 5}
    page = _client(app).post("/home/landing/quality").text
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
    alice.post("/lens/market")
    assert app.state.store["alice"]["lens"] == "market"
    page = alice.get("/t").text
    assert "/var" in _menu_links(page) and "/forms" in _menu_links(page)                  # Market hides nothing
    assert "Leave Drishti Market" in page and "Switch to Drishti Risk" in page and "Switch to Drishti World" in page
    assert "Open Drishti Market" not in page and "Switch to Drishti Market" not in page
    assert 'data-market-landing="desk"' in alice.get("/").text
    alice.post("/home/landing/quality")
    alice.post("/lens/world")                                                           # switching: no need to leave first
    assert 'data-world-landing="desk"' in alice.get("/").text                          # quality is not World's: its default
    assert "Switch to Drishti Market" in alice.get("/t").text
    alice.post("/lens/risk")
    assert 'data-risk-landing="desk"' in alice.get("/").text
    alice.post("/lens/market")
    assert 'data-market-landing="quality"' in alice.get("/").text                      # the choice made in Market is kept
    assert alice.get("/var").status_code == 200
    alice.post("/lens/leave")
    assert "lens" not in app.state.store["alice"]


# ---- The design links follow Build, the distribution's switch: a lens never turns it off, so inside one the Sutra keeps its link ------


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


def test_inside_a_lens_the_sutra_keeps_its_design_link(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    app.state.editions.learn(FULL_ABOUT)
    full = _provenance_html(app, None)
    assert "data-prov-sutra" in full and "/studio?sutra=irs-vanilla%403" in full                  # the full product: the designer link
    for lens in ("risk", "world", "market", "trading"):
        inside = _provenance_html(app, lens)
        assert "data-prov-sutra" in inside and "/studio?sutra=irs-vanilla%403" in inside, lens        # a lens hides nothing
        assert "data-prov-source" in inside, lens


# ---- Drishti Trading (LENSES.md › The design): its surfaces, its three landings, "my" scoping (mapped, a trader record, View as, the role's
# landing) that never widens access, the combined check, and the gating of its routes; four lenses --------------------------------------


@pytest.fixture
def trading():
    from plugins.banking import trading_landing
    from trading_fake import TradingBackend
    trading_landing.forget()
    yield TradingBackend()
    trading_landing.forget()


def _people(app, monkeypatch, roles: dict):
    """Each person's access roles (a viewer unless named): the stand-in server masks TRADER_ID for a role without raw."""
    async def current(cookie, _backend):
        user = cookie or "alice"
        return Identity(user=user, display={"kim": "K. Moreau"}.get(user, user.title()), desk="", roles=roles.get(user, ("viewer",)))

    monkeypatch.setattr(app.state.auth, "current", current)


def test_trading_home_offers_trader_desk_and_explorer_and_remembers_the_choice(trading, monkeypatch):
    app = _app(trading, monkeypatch)
    _workspace(app, "trading")
    c = _client(app)
    home = c.get("/").text
    assert 'data-trading-landing="trader"' in home and "data-market-landing" not in home and "data-risk-landing" not in home
    assert "/static/js/drishti_trading-landing.js" in home and "/static/css/drishti_trading-landing.css" in home
    assert ">Trader desk<" in home and ">Desk head<" in home and ">Explorer<" in home
    assert 'data-tl-tile="pnl"' in home and 'data-tl-check' in home and 'data-home-card="VAR BOOK BOOK-RATES-1"' in home
    assert c.post("/home/landing/desk", follow_redirects=False).status_code == 303
    assert app.state.store["alice"]["homeLanding"] == "desk"
    desk = c.get("/").text
    assert 'data-trading-landing="desk"' in desk and 'data-tl-heat' in desk and 'data-tl-traders' in desk and 'data-tl-pnlvar' in desk
    explorer = c.post("/home/landing/explorer").text
    assert 'data-trading-landing="explorer"' in explorer and 'id="tlCmd"' in explorer and "CTRD CP-ALDERSHOT" in explorer
    at = explorer.index("data-tl-recents")
    recents = explorer[at:explorer.index("</ul>", at)]
    assert "TRD MX-20000001" in recents and "BOOK BOOK-RATES-1" in recents and "CTRD CP-ALDERSHOT" in recents and "VAR BANK" in recents
    assert recents.count(">TRD MX-20000001<") == 1                                                   # once each; a lens hides none
    c.post("/home/landing/quality")                                                     # a Market landing is not Trading's: not kept
    assert app.state.store["alice"]["homeLanding"] == "explorer"


def test_the_first_landing_follows_the_trading_role_and_the_persons_own_choice_wins(trading, monkeypatch):
    app = _app(trading, monkeypatch)
    _workspace(app, "trading", "t.brennan", "drishti-dev-admin")
    app.state.editions.lenses["trading"].spec["trading"]["people"]["t.brennan"] = {"desk": "DESK-RATES", "role": "desk-head"}
    _people(app, monkeypatch, {})
    head = _client(app, "t.brennan")
    assert 'data-trading-landing="desk"' in head.get("/").text                         # a desk head opens on B
    assert 'data-trading-landing="trader"' in _client(app, "drishti-dev-admin").get("/").text   # a trader on A
    head.post("/home/landing/trader")
    assert 'data-trading-landing="trader"' in head.get("/").text                       # their own choice wins
    app.state.editions.lenses["trading"].spec["home"]["landing-by-role"] = {"viewer": "explorer"}   # the RBAC hook, when set, still wins
    assert 'data-trading-landing="explorer"' in head.get("/").text


def test_my_scope_mapped_from_a_record_or_chosen_and_view_as_is_remembered(trading, monkeypatch):
    app = _app(trading, monkeypatch)
    _workspace(app, "trading", "drishti-dev-admin", "kim", "zed", "root")
    _people(app, monkeypatch, {"root": ("admin",)})
    mapped = _client(app, "drishti-dev-admin").get("/api/home/trading/me").json()
    assert mapped["scope"]["id"] == "TRDR-ASHAH" and mapped["scope"]["source"] == "config" and "options" not in mapped
    assert mapped["scope"]["books"] == ["BOOK-RATES-1", "BOOK-RATES-2"] and mapped["check"]
    kim = _client(app, "kim").get("/api/home/trading/me").json()                         # no mapping: the trader record by name
    assert kim["scope"]["id"] == "TRDR-KMOREAU" and kim["scope"]["source"] == "record"
    zed = _client(app, "zed")
    me = zed.get("/api/home/trading/me").json()
    assert me["scope"]["kind"] == "" and me["scope"]["canPick"] and {o["id"] for o in me["options"]["desks"]} == {"DESK-RATES", "DESK-COMM"}
    assert zed.get("/api/home/trading/valuations").json()["available"] is False
    assert zed.get("/home/trading/view-as", params={"as": "desk:DESK-RATES"}, follow_redirects=False).status_code == 303
    assert app.state.store["zed"]["tradingView"] == "desk:DESK-RATES"                   # remembered in their settings
    me = zed.get("/api/home/trading/me").json()
    assert me["scope"]["kind"] == "desk" and me["scope"]["source"] == "choice" and me["scope"]["head"] == "T. Brennan"
    assert 'data-trading-landing="desk"' in zed.get("/").text                          # a desk chosen: the Desk head first
    assert zed.get("/home/trading/view-as", params={"as": "nobody"}, follow_redirects=False).headers["location"].startswith("/t?error=")
    # a mapped person may not repoint themselves; an administrator always may
    dev = _client(app, "drishti-dev-admin")
    r = dev.get("/home/trading/view-as", params={"as": "desk:DESK-RATES"}, follow_redirects=False)
    assert "DRS-4023" in r.headers["location"] and "tradingView" not in app.state.store.get("drishti-dev-admin", {})
    root = _client(app, "root")
    app.state.editions.lenses["trading"].spec["trading"]["people"]["root"] = {"trader": "TRDR-ASHAH"}
    root.get("/home/trading/view-as", params={"as": "trader:TRDR-KMOREAU"})
    me = root.get("/api/home/trading/me").json()
    assert me["scope"]["id"] == "TRDR-KMOREAU" and me["scope"]["canPick"] and me["options"]["traders"]
    root.get("/home/trading/view-as", params={"as": ""})                               # forgotten: back to the mapping
    assert root.get("/api/home/trading/me").json()["scope"]["id"] == "TRDR-ASHAH"


def test_scoping_never_widens_access_every_read_is_the_persons_own(trading, monkeypatch):
    app = _app(trading, monkeypatch)
    _workspace(app, "trading", "zed")
    seen = []
    for name in ("search", "raw", "var", "exposure"):
        real = getattr(trading, name)

        def spy(*a, _real=real, **k):
            ident = k.get("ident") or next((x for x in a if isinstance(x, Identity)), None)
            seen.append((ident.user if ident else None, ident.roles if ident else None))
            return _real(*a, **k)
        monkeypatch.setattr(trading, name, spy)
    _people(app, monkeypatch, {})
    zed = _client(app, "zed")
    zed.get("/home/trading/view-as", params={"as": "desk:DESK-RATES"})
    t = zed.get("/api/home/trading/traders").json()
    assert t["traderMasked"] and all(r["pnl"] is None and r["var"] is None for r in t["rows"])   # TRADER_ID stays masked for a viewer
    for part in ("valuations", "risk", "blotter", "limits", "pnl-var"):
        assert zed.get(f"/api/home/trading/{part}").status_code == 200, part
    assert seen and {u for u, _r in seen} == {"zed"} and {r for _u, r in seen} == {("viewer",)}   # read as zed, with zed's roles only
    assert "__rows" not in zed.get("/api/home/trading/valuations").json()               # what the panels keep is never sent


def test_the_check_answers_once_from_both_engines(trading, monkeypatch):
    app = _app(trading, monkeypatch)
    _workspace(app, "trading", "drishti-dev-admin")
    c = _client(app, "drishti-dev-admin")
    a = c.post("/api/home/trading/check", json={"product": "IR_SWAP", "notional": "250m", "counterparty": "CP-ALDERSHOT", "side": "S"}).json()
    assert a["ok"] and a["var"]["state"] == "near" and a["exposure"]["state"] == "near" and a["template"]["trade"] == "MX-20000010"
    assert a["var"]["before"] == 6_120_000 and a["var"]["after"] == 6_300_000 and a["exposure"]["after"] == 409_200_000
    assert c.post("/api/home/trading/check", json=["x"]).status_code == 400
    assert not c.post("/api/home/trading/check", json={"product": "IR_SWAP"}).json()["ok"]


def test_the_trading_routes_answer_inside_a_workspace(trading, monkeypatch):
    app = _app(trading, monkeypatch)
    app.state.editions.learn(FULL_ABOUT)
    c = _client(app, "drishti-dev-admin")
    body = {"product": "IR_SWAP", "notional": "1m", "counterparty": "CP-ALDERSHOT"}
    for path in ("/api/home/trading/me", "/api/home/trading/valuations"):                # no lens: not a landing's
        r = c.get(path)
        assert r.status_code == 404 and r.json()["code"] == "DRS-4023", path
    assert c.post("/api/home/trading/check", json=body).status_code == 404
    c.post("/lens/trading")
    assert c.get("/api/home/trading/me").json()["scope"]["id"] == "TRDR-ASHAH"
    assert c.post("/api/home/trading/check", json=body).json()["ok"]
    assert c.get("/api/home/trading/nope").status_code == 404
    c.post("/lens/world")                                                                # a lens hides nothing: the reads stay
    assert c.get("/api/home/trading/me").status_code == 200


def test_four_lenses_each_person_opens_any_and_switches_between_them(trading, monkeypatch):
    app = _app(trading, monkeypatch)
    app.state.editions.learn({"product": "Drishti", "edition": None, "lenses": ["risk", "world", "market", "trading"]})
    alice = _client(app, "alice")
    page = alice.get("/t").text
    for name in ("Risk", "World", "Market", "Trading"):
        assert f"/lens/{name.lower()}" in _menu_links(page) and f"Open Drishti {name}" in page, name
    assert 'data-lens-link="trading"' in alice.get("/").text                           # on Home too
    alice.post("/lens/trading")
    assert app.state.store["alice"]["lens"] == "trading"
    page = alice.get("/t").text
    assert "/var" in _menu_links(page) and "/forms" in _menu_links(page)                  # Trading hides nothing
    assert "Leave Drishti Trading" in page and all(f"Switch to Drishti {n}" in page for n in ("Risk", "World", "Market"))
    assert 'data-trading-landing="trader"' in alice.get("/").text
    alice.post("/home/landing/desk")
    alice.post("/lens/market")
    assert 'data-market-landing="desk"' in alice.get("/").text                         # desk is Market's too: kept
    assert "Switch to Drishti Trading" in alice.get("/t").text
    alice.post("/lens/world")
    assert 'data-world-landing="desk"' in alice.get("/").text
    alice.post("/lens/trading")
    assert 'data-trading-landing="desk"' in alice.get("/").text
    alice.post("/lens/leave")
    assert "lens" not in app.state.store["alice"]
