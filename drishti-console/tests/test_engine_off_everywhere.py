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

"""An engine that runs on no server disappears everywhere in the console (docs/architecture/COMPUTE_ENGINES.md sections 5.2 and 13,
test_engine_off_everywhere.py): generated from the engine catalogue, for every installed engine with words or pages: its words are unknown
to the edition (word_on, typed_shown, so FUNC, type-ahead, Home cards and chips drop them), the command line says the server's words
(DRS-1020), its pages and playgrounds answer 404, its menu items are not drawn, its Help pages are not listed or reached. The engine coming
back (the about answer says so) brings everything back. The catalogue's console copy is the server's byte for byte (EngineCatalogueTest)."""
from __future__ import annotations

from urllib.parse import unquote

import pytest
import yaml
from fastapi.testclient import TestClient

from conftest import CONSOLE, FakeBackend, help_pages
from core import engines
from core.app import create_app
from core.auth import Identity
from core.config import load_settings

CATALOGUE = engines.entries()
MENUS = yaml.safe_load((CONSOLE / "config" / "sitemap.yaml").read_text(encoding="utf-8"))
LABELS = {str(it.get("id")): str(it.get("label")) for m in MENUS["menus"] for c in m.get("columns") or [] for it in c.get("items") or []}
SHOWN = [e for e in CATALOGUE if e.get("installed") and (e.get("words") or e.get("console"))]


def about(off: set[str]) -> dict:
    return {"product": "Drishti", "edition": None, "lenses": [],
            "engines": [{"id": e["id"], "title": e["title"], "here": e["id"] not in off, "cluster": e["id"] not in off} for e in CATALOGUE]}


def _app(monkeypatch, off: set[str]):
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = FakeBackend()
    app.state.editions.learn(about(off))

    async def current(cookie, _backend):
        return Identity(user=cookie or "alice", display="Alice", desk="Risk", roles=("*", "admin"))

    monkeypatch.setattr(app.state.auth, "current", current)
    c = TestClient(app)
    c.cookies.set(app.state.auth.cookie, "alice")
    return app, c


@pytest.mark.parametrize("entry", SHOWN, ids=[e["id"] for e in SHOWN])
def test_an_engine_off_everywhere_is_gone_from_every_place(entry, monkeypatch):
    app, c = _app(monkeypatch, {entry["id"]})
    ed = app.state.editions.instance
    for word in entry.get("words") or []:
        assert not ed.typed_shown(word + " BANK"), word
        if " " not in word:
            assert not ed.word_on(word) and not ed.command_shown(word), word
        r = c.get("/go", params={"q": word + " BANK <GO>"}, follow_redirects=False)
        assert r.status_code == 303 and "DRS-1020" in unquote(r.headers["location"]), word
        assert f"{entry['title']} is not running on this server" in unquote(r.headers["location"])
    for path in entry.get("console") or []:
        path = path.lstrip("=").rstrip("/") or "/"
        if path.startswith("/api/") or path.startswith("/admin/api/"):
            path = path + ("x" if path.endswith("/") else "/x")
        r = c.get(path, follow_redirects=False)
        assert r.status_code == 404, path
    page = c.get("/t").text
    for mid in entry.get("menus") or []:
        if mid in LABELS:
            assert f">{LABELS[mid]}<" not in page and f"{LABELS[mid]}</" not in page, mid
    lib_page = help_pages(c)
    for slug in entry.get("help") or []:
        assert f'href="/help/{slug}"' not in lib_page, slug
        assert c.get(f"/help/{slug}").status_code == 404, slug
    # it comes back: the about answer says it runs again
    assert app.state.editions.learn(about(set()))
    for word in entry.get("words") or []:
        assert ed.typed_shown(word + " BANK"), word
    for slug in entry.get("help") or []:
        assert c.get(f"/help/{slug}").status_code == 200, slug


def test_nothing_is_hidden_until_the_server_has_said(monkeypatch):
    engines.reset()
    assert engines.engine_available("frtb-sa") and not engines.word_hidden("FRSA")
    assert engines.learn(None) is False
    assert engines.learn(about({"frtb-sa"})["engines"]) is True
    assert engines.word_hidden("FRSA") and not engines.word_hidden("PLAY")
    assert engines.typed_engine("PLAY FRTB EXAMPLE")["id"] == "frtb-sa" and engines.typed_engine("PLAY VAR BANK") is None
    assert engines.path_engine("/api/frtb/sensitivities") is None, "the sensitivities' endpoint stays with its own engine"
    assert engines.path_engine("/play/frtb")["id"] == "frtb-sa" and engines.path_engine("/play") is None
    engines.reset()


def test_a_lens_or_a_tile_of_an_engine_running_nowhere_is_not_offered(monkeypatch):
    engines.reset()
    engines.learn([{"id": "mr-cube", "cluster": False}, {"id": "frtb-sa", "cluster": False}, {"id": "var", "cluster": False}])
    assert engines.lens_hidden("mr-cube") and engines.tile_hidden("VaR of the bank") and not engines.lens_hidden("risk")
    assert engines.menu_hidden("frsa") and not engines.menu_hidden("exp-explorer")
    engines.reset()


def test_the_engines_page_draws_the_servers_answer_and_carries_the_batch(monkeypatch):
    app, c = _app(monkeypatch, set())
    sent = []
    view = {"node": "drishti-main", "dir": "/srv/config/engines", "running": 5, "installed": 6, "memoryBytes": 3 << 30, "heapMaxBytes": 16 << 30,
            "engines": [{"id": "frtb-sa", "title": "FRTB-SA", "about": "", "installed": True, "state": "running", "reason": "", "on": True,
                         "liveOn": True, "restartNeeded": "", "memoryBytes": 640 << 20, "threads": 2, "datesOpen": 1, "inFlight": 0,
                         "needs": ["var", "sensitivities"], "uses": [], "neededBy": [], "usedBy": ["mr-cube"], "source": "engine file",
                         "alias": "", "overridden": False, "file": "/srv/config/engines/frtb-sa.yaml", "fileExists": True, "checksum": "abc",
                         "hides": {"words": ["FRSA", "PLAY FRTB"]}}],
            "cluster": {"on": False, "self": "", "peers": {}, "available": {"frtb-sa": True}}}

    async def admin(method, path, ident, body=None, **params):
        sent.append((method, path, body))
        if path == "/engines" and method == "GET":
            return view
        if path == "/engines/history":
            return {"changes": [{"at": "2026-11-02T09:12:00Z", "engine": "frtb-sa", "change": "off", "who": "a.sinha", "why": "news",
                                 "node": "drishti-main", "outcome": "live", "detail": ""}]}
        if path == "/status":
            return {}
        if path == "/engines" and method == "POST":
            return {**view, "outcomes": [{"engine": "frtb-sa", "on": False, "outcome": "live", "detail": ""}]}
        raise AssertionError(path)

    monkeypatch.setattr(app.state.backend, "admin", admin)
    page = c.get("/admin/engines")
    assert page.status_code == 200
    assert "data-eng-table" in page.text and "drishti-main" in page.text and "/static/js/drishti_admin-engines.js" in page.text
    r = c.post("/admin/api/engines", json={"reason": "capital on var-1", "changes": [{"engine": "frtb-sa", "on": False}], "expected": {"frtb-sa": "abc"}})
    assert r.status_code == 200 and r.json()["outcomes"][0]["outcome"] == "live"
    assert ("POST", "/engines", {"reason": "capital on var-1", "node": "", "changes": [{"engine": "frtb-sa", "on": False}], "withNeeds": False,
                                 "withDependents": False, "expected": {"frtb-sa": "abc"}}) in sent
