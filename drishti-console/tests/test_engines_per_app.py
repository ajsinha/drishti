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
"""Each console app reads the engines its own server said run (core/engines.py State, set per request by core/app.py AppEngines), never
another app's of the same process. The browser lane holds several apps in one worker: the session's real stack (whose server runs the data
inbox off, as shipped, and whose backend watch says so every few seconds) and a stand-in console of another file. When what runs was a
module global, the real stack's next poll hid Admin → Data loads → Inbox in the stand-in console mid-file (2.9.11.b Drill A: 7 of 8
tests of test_dataload_inbox_browser.py failed after a file on the shared live console; alone they passed)."""
from __future__ import annotations

from fastapi.testclient import TestClient

from conftest import CONSOLE
from core import engines
from core.app import create_app
from core.config import load_settings
from test_dataload_inbox import QUIET, with_inbox

DATALOAD_OFF = {"engines": [{"id": "dataload", "title": "Data inbox", "here": False, "cluster": False}]}


def test_an_app_keeps_its_own_engines_when_another_app_of_the_process_learns_its_servers(backend, monkeypatch):
    with_inbox(backend, monkeypatch, [], QUIET)
    mine = create_app(load_settings(CONSOLE / "config"))
    mine.state.backend = backend
    other = create_app(load_settings(CONSOLE / "config"))      # the newer app: as a session's live console made after this one
    other.state.backend = backend
    assert other.state.editions.learn(DATALOAD_OFF)           # its server runs the data inbox nowhere
    assert TestClient(mine).get("/admin/loads/inbox").status_code == 200
    assert TestClient(other).get("/admin/loads/inbox").status_code == 404
    assert mine.state.editions.learn(DATALOAD_OFF)            # its own server says so too: then it hides the page as well
    assert TestClient(mine).get("/admin/loads/inbox").status_code == 404


def test_each_state_is_read_where_it_is_used():
    a, b = engines.new_state(), engines.new_state()
    engines.learn(DATALOAD_OFF["engines"], b)
    token = engines.use(a)
    try:
        assert engines.engine_available("dataload") and engines.path_engine("/admin/loads/inbox") is None
    finally:
        engines.reset_use(token)
    assert not engines.engine_available("dataload")           # outside a request: the newest app's (b)
    assert engines.path_engine("/admin/api/dataload/run")["id"] == "dataload"
    engines.reset(b)
    assert engines.engine_available("dataload")
