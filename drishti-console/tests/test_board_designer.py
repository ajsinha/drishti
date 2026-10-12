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
"""The board designer (docs/guides/BOARDS.md, "Designing a board"): its page and its start, the calls its script makes pass through the BI proxy, the links to it
from the board and the item, and the script carries no CDN address, inline code or markup string. The behaviour in a browser is in test_board_designer_browser.py."""
import re
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from bi_backend import BiBackend
from conftest import CONSOLE
from core.app import CSP, create_app
from core.config import load_settings

STATIC = CONSOLE / "web" / "static"


class Backend(BiBackend):
    def __init__(self):
        super().__init__()
        self.calls = []

    async def bi(self, method, path, ident, body=None, **params):
        self.calls.append((method, path, body))
        if path == "/boards/validate":
            return {"valid": True, "problems": [], "board": {"rachana": 1, "board": "b", "title": "B", "pages": [{"id": "p", "title": "P", "tiles": []}]}, "tiles": {}}
        if path == "/boards/format":
            return {"yaml": "rachana: 1\nboard: b\n"}
        return await super().bi(method, path, ident, body, **params)


@pytest.fixture()
def client():
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = Backend()
    return TestClient(app)


def test_the_designer_page_is_a_shell_the_script_draws(client):
    page = client.get("/bi/design/board/file:board:desk-wall")
    assert page.status_code == 200 and page.headers["content-security-policy"] == CSP
    for marker in ("data-board-design-root", "data-board-grid", 'data-tab="yaml"', "data-add-form", "data-tile-form", "data-b-rotate", "data-save", "data-item-link"):
        assert marker in page.text, marker
    assert 'data-item="file:board:desk-wall"' in page.text
    assert "/static/js/bi-design-board.js" in page.text and "/static/css/bi-design-board.css" in page.text
    assert "/static/js/bi-design.js" not in page.text, "the report designer's script is not loaded on a board"


def test_the_start_page_lists_boards_and_makes_a_new_one(client):
    page = client.get("/bi/designer")
    assert page.status_code == 200
    assert "data-board-start" in page.text and "data-new-board" in page.text and "data-board-drafts" in page.text and "/static/js/bi-design-board.js" in page.text


def test_the_script_reaches_the_board_calls_through_the_proxy(client):
    ok = client.post("/bi/api/boards/validate", json={"yaml": "board: b\n"})
    assert ok.status_code == 200 and ok.json()["valid"] is True
    fmt = client.post("/bi/api/boards/format", json={"board": {"board": "b"}})
    assert fmt.status_code == 200 and fmt.json()["yaml"].startswith("rachana: 1")
    sent = [c for c in client.app.state.backend.calls if c[1] in ("/boards/validate", "/boards/format")]
    assert [c[1] for c in sent] == ["/boards/validate", "/boards/format"] and sent[1][2] == {"board": {"board": "b"}}


def test_the_board_and_the_item_link_to_the_designer(client):
    assert "data-design" in client.get("/bi/b/file:board:desk-wall").text
    bi_js = (STATIC / "js" / "bi.js").read_text(encoding="utf-8")
    assert "'/bi/design/board/'" in bi_js
    assert "'/bi/design/board/'" in (STATIC / "js" / "bi-board.js").read_text(encoding="utf-8")


def test_the_script_has_no_cdn_inline_code_or_markup_string():
    text = (STATIC / "js" / "bi-design-board.js").read_text(encoding="utf-8")
    assert "http://" not in text and "https://" not in text and "innerHTML" not in text and "insertAdjacentHTML" not in text and "eval(" not in text
    assert not re.search(r"\bon[a-z]+\s*=\s*['\"]", text), "no inline handler"
    for tmpl in ("design_board.html", "designer.html"):
        html = (CONSOLE / "web" / "templates" / "bi" / tmpl).read_text(encoding="utf-8")
        assert "<script>" not in html and " style=" not in html and not re.search(r"\sonclick=", html), tmpl
    css = (STATIC / "css" / "bi-design-board.css").read_text(encoding="utf-8")
    assert "min-height: 44px" in css and "@media" in css and "http" not in css
    assert len(text.splitlines()) < 1500
