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

"""The Build home (2.9.11.f; docs/architecture/SHARED_SCREENS.md section 1): the rail grouped Data · Screens · Analysis · Work with
counts, the lists with lineage (uses, used by), the first-visit tiles, one search, + New, the registry's domains, a source that fails,
and two people with different roles and lenses seeing different menus and kinds (session isolation)."""
from __future__ import annotations

import re

import pytest
from fastapi.testclient import TestClient

from conftest import CONSOLE
from core.app import create_app
from core.auth import Identity
from core.config import load_settings

OBJECTS = {"tables": [{"kind": "table", "name": "trades-with-limits", "form": "virtual", "connector": "trade-store", "status": "approved",
                       "version": 3, "item": "T1"}],
           "models": [{"kind": "model", "name": "risk-by-desk", "form": "model", "status": "draft", "version": 4, "item": "M1"}],
           "python": [{"kind": "table", "name": "fx-implied-vol", "form": "python", "status": "proposed", "item": "P1"}]}
CATALOGUE = {"items": [{"id": "R1", "title": "VaR by desk", "kind": "report", "model": "risk-by-desk", "publishedAt": "2026-11-06T10:00:00Z",
                        "owner": "mara", "endorsement": "certified"}]}
CONNECTORS = {"connectors": [{"name": "trade-store", "plugin": "delta", "enabled": True}]}


def _app(backend, monkeypatch, people: dict | None = None):
    """A console over the stand-in server with the Build home's answers; ``people``: cookie -> roles (default alice, an administrator)."""
    people = people or {"alice": ("admin",)}
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = backend
    original_admin, original_send, original_bi = backend.admin, backend._send, backend.bi

    async def admin(method, path, ident, body=None, **params):
        if path == "/connectors" and method == "GET":
            return CONNECTORS
        if path == "/lakes/files":
            return {"lakes": [{"name": "demo", "format": "delta", "connectors": ["trade-store"]}]}
        return await original_admin(method, path, ident, body, **params)

    async def send(method, path, ident=None, **kw):
        if path == "/tables/editor/objects":
            return OBJECTS
        return await original_send(method, path, ident, **kw)

    async def bi(method, path, ident, body=None, **params):
        if path == "/catalogue":
            return CATALOGUE
        if path == "/me":
            return {"enabled": True, "powers": ["bi", "bi-author"]}
        if path == "/home":
            return {}
        return await original_bi(method, path, ident, body, **params)

    store: dict[str, dict] = {}

    async def settings(ident=None):
        return dict(store.get(ident.user if ident else "", {}))

    async def patch_settings(body, ident=None):
        mine = store.setdefault(ident.user, {})
        mine.update({k: v for k, v in body.items() if v is not None})
        for k in [k for k, v in body.items() if v is None]:
            mine.pop(k, None)
        return mine

    monkeypatch.setattr(backend, "settings", settings, raising=False)
    monkeypatch.setattr(backend, "patch_settings", patch_settings, raising=False)
    app.state.bi_on = True

    monkeypatch.setattr(backend, "admin", admin)
    monkeypatch.setattr(backend, "_send", send)
    monkeypatch.setattr(backend, "bi", bi)

    async def current(cookie, _backend):
        who = cookie or "alice"
        return Identity(user=who, display=who.title(), desk="Risk", roles=tuple(people.get(who, ("viewer",))))

    monkeypatch.setattr(app.state.auth, "current", current)
    return app


def _client(app, user="alice"):
    c = TestClient(app)
    c.cookies.set(app.state.auth.cookie, user)
    return c


def _rail(page: str) -> dict[str, str]:
    return dict(re.findall(r'data-kind="([\w-]+)".*?data-count>(.*?)</small>', page, re.S))


def _items(page: str) -> list[str]:
    return re.findall(r'<tr data-item="([^"]+)"', page)


def test_the_rail_is_grouped_with_a_count_per_kind(backend, monkeypatch):
    page = _client(_app(backend, monkeypatch)).get("/build").text
    groups = re.findall(r'<h2 class="bh-group">([^<]+)</h2>', page)
    assert groups == ["Data", "Screens", "Analysis", "Work"]
    rail = _rail(page)
    assert rail["connectors"] == "1" and rail["tables"] == "2" and rail["python-tables"] == "1" and rail["reports"] == "1"
    assert rail["sutras"] == "1" and set(rail) >= {"lakes", "packs", "ui-kit", "pivot-views", "functions", "drafts", "review", "recent"}
    assert page.index('data-kind="connectors"') < page.index('data-kind="sutras"') < page.index('data-kind="reports"') < page.index('data-kind="drafts"')


def test_a_kind_lists_its_items_with_lineage_both_ways(backend, monkeypatch):
    c = _client(_app(backend, monkeypatch))
    tables = c.get("/build", params={"type": "tables"}).text
    assert _items(tables) == ["tables:trades-with-limits", "tables:risk-by-desk"]
    row = re.search(r'<tr data-item="tables:risk-by-desk">.*?</tr>', tables, re.S).group(0)
    assert "semantic model" in row and "draft v4" in row and "VaR by desk" in row                   # used by: the report
    assert 'href="/build/tables?kind=model&amp;item=M1"' in row
    row = re.search(r'<tr data-item="tables:trades-with-limits">.*?</tr>', tables, re.S).group(0)
    assert "trade-store" in row                                                                         # uses: the connector
    connectors = c.get("/build", params={"type": "connectors"}).text
    row = re.search(r'<tr data-item="connectors:trade-store">.*?</tr>', connectors, re.S).group(0)
    assert "trades-with-limits" in row and "demo" in row and 'href="/admin/connectors/trade-store"' in row   # used by: a table, a lake
    assert 'aria-current="page"' in connectors.split('data-kind="connectors"')[1].split(">")[0] + ">" or \
        re.search(r'data-kind="connectors"[^>]*aria-current="page"', connectors)


def test_my_drafts_is_the_default_with_the_designs_and_other_drafts(backend, monkeypatch):
    page = _client(_app(backend, monkeypatch)).get("/build").text
    assert "data-designs" in page and "Screen designs" in page                                      # the designs' own actions stay
    assert "Other drafts" in page and "tables:risk-by-desk" in page                                 # a table model draft
    review = _client(_app(backend, monkeypatch)).get("/build", params={"type": "review"}).text
    assert "python-tables:fx-implied-vol" in review


def test_the_task_tiles_show_on_a_first_visit_until_hidden(backend, monkeypatch):
    c = _client(_app(backend, monkeypatch))
    first = c.get("/build").text
    assert "data-tiles" in first and re.findall(r'data-task="(\w+)"', first) == ["bring", "shape", "show", "package"]
    hidden = c.get("/build", params={"tiles": "0"})
    assert "data-tiles" not in hidden.text and "drishti_build_tiles=hide" in hidden.headers.get("set-cookie", "")
    assert "data-tiles" not in c.get("/build").text                                                # the browser keeps the choice
    again = c.get("/build", params={"tiles": "1"})
    assert "data-tiles" in again.text and "data-tiles" in c.get("/build").text


def test_one_search_across_every_kind(backend, monkeypatch):
    c = _client(_app(backend, monkeypatch))
    page = c.get("/build", params={"q": "risk"}).text
    assert set(_items(page)) >= {"tables:risk-by-desk", "reports:VaR by desk"} and "Everything matching" in page
    assert "Nothing you can build matches" in c.get("/build", params={"q": "no-such-thing-anywhere"}).text


def test_new_lists_where_each_kind_starts(backend, monkeypatch):
    page = _client(_app(backend, monkeypatch)).get("/build").text
    news = dict(re.findall(r'<a href="([^"]+)" data-new-kind="([\w-]+)"', page))
    assert news.get("/build/new") == "sutras" and news.get("/build/pack/new") == "packs" and news.get("/build/tables?new=python") == "python-tables"


def test_a_source_that_fails_leaves_its_kind_without_a_count(backend, monkeypatch):
    app = _app(backend, monkeypatch)

    async def broken(method, path, ident=None, **kw):
        raise RuntimeError("down")
    monkeypatch.setattr(backend, "_send", broken)
    c = _client(app)
    page = c.get("/build").text
    assert _rail(page)["tables"].strip().startswith("<span") and c.get("/build").status_code == 200
    assert "data-unavailable" in c.get("/build", params={"type": "tables"}).text


def test_old_homes_redirect_here(backend, monkeypatch):
    c = _client(_app(backend, monkeypatch))
    r = c.get("/studio", follow_redirects=False)
    assert r.status_code == 302 and r.headers["location"] == "/build"
    assert not (CONSOLE / "web" / "templates" / "build" / "designs.html").exists()


def test_two_people_with_different_roles_and_lenses_see_different_menus_and_kinds(backend, monkeypatch):
    """Session isolation: nothing of one person's menus, lens or Build home leaks to another, request after request, either order."""
    app = _app(backend, monkeypatch, {"alice": ("admin",), "bob": ("viewer",)})
    app.state.editions.learn({"product": "Drishti", "edition": None, "lenses": ["risk", "world", "market", "trading"]})
    alice, bob = _client(app, "alice"), _client(app, "bob")
    for _ in range(2):                                                                               # the full product: the role decides
        ha, hb = alice.get("/build").text, bob.get("/build").text
        assert 'data-kind="connectors"' in ha and 'data-kind="connectors"' not in hb               # what one may build follows the role
        a, b = alice.get("/t").text, bob.get("/t").text
        assert 'href="/admin/configuration"' in a and 'href="/admin/configuration"' not in b       # Admin follows the role
        assert alice.get("/admin/users").status_code == 200 and bob.get("/admin/users").status_code == 403
    alice.post("/lens/world")
    bob.post("/lens/market")
    for _ in range(2):                                                                               # each in a lens: the lens decides
        a, b = alice.get("/t").text, bob.get("/t").text
        a_menus = re.findall(r'<span class="tbar-menu-l">([^<]+)</span>', a)
        b_menus = re.findall(r'<span class="tbar-menu-l">([^<]+)</span>', b)
        assert "Economies" in a_menus and "Curves" not in a_menus
        assert "Curves" in b_menus and "Economies" not in b_menus
    bob.post("/lens/leave")
    assert "Economies" in re.findall(r'<span class="tbar-menu-l">([^<]+)</span>', alice.get("/t").text)   # bob leaving moves nobody else
    assert "Curves" not in re.findall(r'<span class="tbar-menu-l">([^<]+)</span>', bob.get("/t").text)


@pytest.mark.parametrize("path", ["/admin/users", "/admin/engines", "/admin/configuration"])
def test_the_admin_tabs_are_the_sitemaps_admin_menu(backend, monkeypatch, path):
    page = _client(_app(backend, monkeypatch)).get(path).text
    tabs = re.findall(r'<a href="([^"]+)"[^>]*data-tab="([\w-]+)"', page.split('class="adm-tabs"')[1].split("</nav>")[0])
    ids = [t for _h, t in tabs]
    assert ids[:2] == ["users", "roles"] and "configuration" in ids and ids[-1] == "account" and "functions" in ids
    assert ("/admin/configuration", "configuration") in tabs


def test_the_bi_rail_is_the_sitemaps_bi_menu(backend, monkeypatch):
    page = _client(_app(backend, monkeypatch)).get("/bi/transform").text
    rail = page.split('class="bi-rail"')[1].split("</nav>")[0]
    ids = re.findall(r'data-rail="([\w-]+)"', rail)
    assert ids[:3] == ["bi-home", "bi-catalogue", "bi-workspace"] and ids[-3:] == ["tables-models", "pivot", "build-home"]
    assert "bi-settings" not in ids                                                                   # rail: false, the top bar's only
    assert re.search(r'data-rail="bi-transform"[^>]*aria-current="page"', rail) and "data-runs-count" in rail
