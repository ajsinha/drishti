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

"""The one search (2.9.11.f, core/search.py, ``GET /api/search``): one endpoint for the command line's type-ahead (``in=command``),
Help's search box (``in=help``) and the global search box (``in=all``); results ranked and grouped by kind (entities, commands,
screens, Help pages, things you can build), each kind found, and only what the person may see (two people, two answers). The old
type-ahead endpoint is gone. The browser side: test_keymap_browser.py."""
from __future__ import annotations

import pytest

from core import engines
from core.auth import Identity
from core.search import GROUPS, rank
from test_edition import _app, _client

ROLES = {"alice": ("*",), "mira": ("market-risk",), "bea": ("bi-analyst",)}


def _people(app, monkeypatch):
    async def current(cookie, _backend):
        user = cookie or "alice"
        return Identity(user=user, display=user.title(), desk="Risk", roles=ROLES.get(user, ("viewer",)))
    monkeypatch.setattr(app.state.auth, "current", current)


@pytest.fixture
def two(backend, monkeypatch):
    """A console with alice (an administrator) and mira (market risk); the stand-in server's type-ahead answers per person, as the
    server's entitlements do, and its BI catalogue and designs answer for the build group."""
    engines.reset()
    app = _app(backend, monkeypatch)
    _people(app, monkeypatch)
    app.state.bi_on = True

    async def suggest(q, ident=None, limit=None):
        out = [{"type": "entity", "mnemonic": "TRD", "kind": "trade", "id": "IRS-47200", "title": "IRS-47200", "subtitle": "Interest rate swap",
                "complete": "TRD IRS-47200"},
               {"type": "mnemonic", "mnemonic": "TRD", "title": "Trades", "subtitle": "Every trade", "complete": "TRD "}]
        if ident is not None and ident.user == "alice":                    # the server leaves out what mira may not open
            out.append({"type": "entity", "mnemonic": "CPTY", "kind": "counterparty", "id": "CP-SECRET", "title": "CP-SECRET",
                        "subtitle": "Restricted counterparty", "complete": "CPTY CP-SECRET"})
        return out

    async def bi_me(_backend, ident):
        return {"enabled": True, "powers": ["bi"]}

    async def bi(method, path, ident, body=None, **params):
        if path == "/catalogue":
            return {"items": [{"id": "bi-7", "title": "Desk P&L board", "kind": "board", "workspaceName": "Rates", "owner": "alice"}]}
        raise AssertionError(path)

    async def designs(method, path, ident, body=None, **params):
        return {"designs": [{"id": "d1", "name": "Swap blotter draft", "kind": "trade"}]}

    monkeypatch.setattr(backend, "suggest", suggest, raising=False)
    monkeypatch.setattr(backend, "bi", bi, raising=False)
    monkeypatch.setattr(backend, "designs", designs, raising=False)
    monkeypatch.setattr(app.state.bi_me, "get", bi_me)
    yield app
    engines.reset()


def _find(c, q, where="all"):
    r = c.get("/api/search", params={"in": where, "q": q})
    assert r.status_code == 200, r.text
    return r.json()


def test_ranking_puts_the_same_before_the_start_before_a_word_before_inside():
    assert rank("trades", "Trades") == 100
    assert rank("tra", "Trades") == 80
    assert rank("pick", "Search & pick lists") == 60
    assert rank("rade", "Trades") == 40
    assert rank("swap northbridge", "IRS-1", "Interest rate swap · Northbridge") == 20
    assert rank("zzz", "Trades") == 0 and rank("", "Trades") == 0


def test_the_command_line_gets_its_list_in_the_servers_order_tagged_by_kind(client):
    out = _find(client, "TRD IRS-4", "command")
    assert out["where"] == "command" and out["items"] and set(out) >= {"query", "groups", "items", "failed", "ms"}
    assert [s["id"] for s in out["items"]][:2] == ["IRS-47102", "IRS-48213"]                 # the server's order: its ranking
    assert {s["group"] for s in out["items"]} == {"entity"}
    assert sum(len(g["items"]) for g in out["groups"]) == len(out["items"])


def test_every_kind_is_found_grouped_and_ranked(two):
    c = _client(two, "alice")
    out = _find(c, "trade")
    groups = [g["group"] for g in out["groups"]]
    assert set(groups) <= {g for g, _l in GROUPS} and {"entity", "command", "screen", "help"} <= set(groups)
    for g in out["groups"]:
        scores = [s["score"] for s in g["items"]]
        assert scores == sorted(scores, reverse=True), g["group"]                               # best first inside a group
    assert [g["items"][0]["score"] for g in out["groups"]] == sorted((g["items"][0]["score"] for g in out["groups"]), reverse=True)
    picks = _find(c, "pick lists")["items"]
    assert picks[0]["group"] == "screen" and picks[0]["title"] == "Search & pick lists" and picks[0]["href"] == "/s"   # Views › Search & pick lists
    helps = next(g for g in out["groups"] if g["group"] == "help")["items"]
    assert all(s["href"].startswith("/help/") for s in helps)
    built = _find(c, "desk p&l")
    assert any(s["group"] == "build" and s.get("href") == "/bi/i/bi-7" for s in built["items"])  # a published board
    mine = _find(c, "swap blotter")
    assert any(s["group"] == "build" and s.get("href") == "/build/d/d1" for s in mine["items"])   # my own design


def test_two_people_see_different_results(two):
    alice, mira = _client(two, "alice"), _client(two, "mira")
    a, m = _find(alice, "cp-secret"), _find(mira, "cp-secret")
    assert any(s.get("id") == "CP-SECRET" for s in a["items"])
    assert not any(s.get("id") == "CP-SECRET" for s in m["items"])                          # the server's entitlements
    a, m = _find(alice, "competitive analysis"), _find(mira, "competitive analysis")
    assert any(s.get("href") == "/help/competitive-analysis" for s in a["items"])
    assert not any(s.get("href") == "/help/competitive-analysis" for s in m["items"])           # Help's own rule: administrators only
    a, m = _find(alice, "connectors"), _find(mira, "connectors")
    assert any(s["group"] == "screen" and s.get("href", "").startswith("/admin") for s in a["items"])
    assert not any(s["group"] == "screen" and s.get("href", "").startswith("/admin") for s in m["items"])   # the menus' requires: admin


def test_an_engine_the_role_does_not_open_is_neither_suggested_nor_listed(two):
    bea = _client(two, "bea")
    assert _find(bea, "VAR B", "command")["items"] == []
    assert not [s for s in _find(bea, "var explorer")["items"] if s["group"] == "screen" and s.get("href") == "/var"]


def test_helps_box_finds_only_help_pages_and_the_results_page_uses_the_same_search(client):
    out = _find(client, "keyboard", "help")
    assert out["items"] and {s["group"] for s in out["items"]} == {"help"}
    page = client.get("/help/search", params={"q": "keyboard"}).text
    assert out["items"][0]["href"] in page


def test_a_failing_source_is_named_and_the_rest_still_answer(two, backend, monkeypatch):
    async def broken(method, path, ident, body=None, **params):
        raise RuntimeError("down")
    monkeypatch.setattr(backend, "designs", broken, raising=False)
    out = _find(_client(two, "alice"), "trade")
    assert "build" in out["failed"] and out["items"]


def test_a_wrong_where_is_refused_and_the_old_type_ahead_endpoint_is_gone(client):
    assert client.get("/api/search", params={"in": "everything", "q": "x"}).status_code == 400
    assert client.get("/api/suggest", params={"q": "TRD"}).status_code == 404
    assert _find(client, "")["groups"] == []
