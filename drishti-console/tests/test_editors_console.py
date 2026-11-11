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

"""Build › Tables & models, console lane (HOW_IT_FITS.md): the page is a shell with its scripts and needs the bi power; the
editor API is passed on as the person, its paths an allow-list; the Python run's chip follows the under-construction registry; the menu and
the BI rail link to it; nothing per person is kept in the console. The browser side is tests/test_editors_browser.py."""
from bi_backend import BiBackend
from conftest import CONSOLE
from core import under_construction
from core.app import create_app
from core.config import load_settings
from fastapi.testclient import TestClient

SCRIPTS = ("drishti_editors.js", "drishti_editor-new.js", "drishti_editor-table.js", "drishti_editor-model.js", "drishti_editor-python.js",
           "drishti_editor-formula.js", "drishti_editors.css")


class EditorBackend(BiBackend):
    def __init__(self, powers=("bi", "bi-author")):
        super().__init__(powers)
        self.sent = []

    async def _send(self, method, path, ident=None, **kw):
        if path.startswith("/tables/editor/"):
            self.sent.append((method, path, kw.get("params"), kw.get("json")))
            return {"ok": True, "path": path}
        return await super()._send(method, path, ident, **kw)


def make(powers=("bi", "bi-author")):
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = EditorBackend(powers)
    return TestClient(app), app.state.backend


def test_the_page_is_a_shell_with_its_scripts_and_needs_bi():
    client, _ = make()
    r = client.get("/build/tables?kind=table&name=trading.trade_summary")
    assert r.status_code == 200
    for s in SCRIPTS:
        assert s in r.text
    # the header, the modes and the resizable panes are drawn by drishti-designer's DesignerShell in the page's [data-editors] (the kit)
    assert "data-editors" in r.text and '{% include "_designer_kit.html" %}' not in r.text and "drishti-designer" in r.text
    shell = (CONSOLE / "web" / "static" / "elements" / "drishti-designer" / "src" / "drishti_shell.js").read_text(encoding="utf-8")
    for act in ("validate", "preview", "history", "impact", "save", "propose"):
        assert f"id: '{act}'" in shell
    assert "'data-act': 'new'" in shell and "'data-mode': m" in shell and "'data-resize': 'panel'" in shell
    assert 'data-name="trading.trade_summary"' in r.text
    assert make(powers=())[0].get("/build/tables").status_code == 403


def test_the_editor_api_is_passed_on_as_an_allow_list():
    client, backend = make()
    assert client.get("/build/tables/api/objects").status_code == 200
    assert client.get("/build/tables/api/object?kind=table&name=a.b&junk=1").status_code == 200
    assert client.post("/build/tables/api/check", json={"kind": "table", "yaml": "rachana: 3\n"}).status_code == 200
    assert client.put("/build/tables/api/prefs", json={"mode": "yaml"}).status_code == 200
    assert client.post("/build/tables/api/python/notebook", json={"cells": ["x = 1"]}).status_code == 200
    sent = {(m, p) for m, p, _, _ in backend.sent}
    assert ("GET", "/tables/editor/objects") in sent and ("POST", "/tables/editor/check") in sent and ("PUT", "/tables/editor/prefs") in sent
    params = [x for m, p, x, _ in backend.sent if p == "/tables/editor/object"][0]
    assert params == {"kind": "table", "name": "a.b"}
    assert client.get("/build/tables/api/check").status_code == 404            # the wrong method
    assert client.post("/build/tables/api/admin/anything", json={}).status_code == 404
    assert client.post("/build/tables/api/check", content=b"x", headers={"Content-Type": "text/plain"}).status_code == 415


def test_the_python_run_follows_the_servers_runtime_and_the_menu_and_rail_link_here():
    client, backend = make()
    page = client.get("/build/tables").text                                   # the fake server answers {ok: true}: no runs key, so Python is off here
    assert 'data-python-runs="false"' in page and "/tables/editor/python/runtime" in [p for _, p, _, _ in backend.sent]
    assert "python-table-runs" not in (CONSOLE.parent / "config/under-construction.yaml").read_text(encoding="utf-8")
    run = client.post("/build/tables/api/python/run", json={"yaml": "table: a.b\n", "source": "def build(inputs, params):\n    return None\n"})
    assert run.status_code == 200 and backend.sent[-1][1] == "/tables/editor/python/run"
    assert 'href="/build/tables"' in client.get("/bi/catalogue").text
    assert under_construction.Registry([{"id": "x"}]).marked("x") and not under_construction.Registry([]).marked("x")
