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

"""Admin -> Engines answers within a time budget whatever the server does (owner 2026-11-09: it took "forever"): the page asks the
server's three answers together, each within PAGE_BUDGET_S, and is drawn without one that is late; the page's script then asks for it
and fills it in, or says the server is not answering. An engines answer slower on purpose than the tests' page-load budget (10 s, owner
2026-11-10): the page still answers within it, so it did not wait for the answer."""
import asyncio
import json
import re
import time

from routes import admin_engines_routes
from test_edition import _app, _client

PAGE_LOAD_BUDGET_S = 10.0          # the tests' page-load budget (owner 2026-11-10); the server's own probe budget stays PAGE_BUDGET_S

ENGINES = {"node": "this server", "installed": 1, "memoryBytes": 0, "dir": "/x",
           "engines": [{"id": "var", "title": "VaR", "installed": True, "state": "running", "on": True, "needs": [], "neededBy": [], "usedBy": [],
                        "source": "file", "memoryBytes": 0, "threads": 2}], "cluster": {"on": False, "peers": {}, "available": {}}}


def _slow_engines(backend, monkeypatch, seconds: float):
    inner = backend.admin

    async def admin(method, path, ident, body=None, **params):
        if path == "/engines" and method == "GET":
            await asyncio.sleep(seconds)                     # a slow probe on the server, on purpose
            return ENGINES
        if path == "/engines/history":
            return {"changes": []}
        return await inner(method, path, ident, body, **params)
    monkeypatch.setattr(backend, "admin", admin)


def _data(html: str) -> dict:
    raw = re.search(r'data-eng-json="([^"]*)"', html).group(1)
    return json.loads(raw.replace("&#34;", '"').replace("&quot;", '"').replace("&amp;", "&"))


def test_the_page_answers_within_its_budget_when_the_engines_answer_is_slow(backend, monkeypatch):
    _slow_engines(backend, monkeypatch, 3 * PAGE_LOAD_BUDGET_S)
    c = _client(_app(backend, monkeypatch))
    c.get("/admin")                                               # the templates compiled once (a first request anywhere)
    t0 = time.monotonic()
    r = c.get("/admin/engines")
    took = time.monotonic() - t0
    assert r.status_code == 200 and took < PAGE_LOAD_BUDGET_S and admin_engines_routes.PAGE_BUDGET_S <= 0.8, took
    d = _data(r.text)
    assert d["pending"] is True and d["engines"] is None          # drawn without it: the script asks again and fills it in


def test_a_prompt_answer_is_drawn_with_the_page(backend, monkeypatch):
    _slow_engines(backend, monkeypatch, 0.0)
    d = _data(_client(_app(backend, monkeypatch)).get("/admin/engines").text)
    assert d["pending"] is False and d["engines"]["engines"][0]["id"] == "var"
