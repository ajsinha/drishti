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

"""Boards as a report layout (R7, 2.4.0) in the console: the 2.3 board addresses send people to the report viewer and the report designer, a board is made on the
designer's start page as a report with a board page, the report designer has the tile editor and the Studio shell, and the scripts keep the page policy."""
import re

import pytest
from fastapi.testclient import TestClient

from bi_backend import BiBackend
from conftest import CONSOLE
from core.app import CSP, create_app
from core.config import load_settings

STATIC = CONSOLE / "web" / "static"
TEMPLATES = CONSOLE / "web" / "templates" / "bi"


@pytest.fixture()
def client():
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = BiBackend()
    return TestClient(app)


def test_a_two_three_board_address_opens_the_report_viewer_and_the_report_designer(client):
    view = client.get("/bi/b/file:board:desk-wall?kiosk=1", follow_redirects=False)
    assert view.status_code == 307 and view.headers["location"] == "/bi/r/file:report:desk-wall?kiosk=1"
    stored = client.get("/bi/b/bi-1234", follow_redirects=False)
    assert stored.headers["location"] == "/bi/r/bi-1234"
    design = client.get("/bi/design/board/file:board:desk-wall", follow_redirects=False)
    assert design.status_code == 307 and design.headers["location"] == "/bi/design/file:report:desk-wall"
    for gone in ("board.html", "design_board.html"):
        assert not (TEMPLATES / gone).exists(), gone
    for gone in ("js/bi-board.js", "js/bi-design-board.js", "css/bi-design-board.css"):
        assert not (STATIC / gone).exists(), gone


def test_the_report_viewer_draws_board_pages_with_a_kiosk(client):
    page = client.get("/bi/r/file:report:desk-wall")
    assert page.status_code == 200 and page.headers["content-security-policy"] == CSP
    assert "data-kiosk" in page.text and "data-rotate-note" in page.text and "/static/js/bi-report-board.js" in page.text and "/static/js/bi-tooltips.js" in page.text
    viewer = (STATIC / "js" / "bi-report.js").read_text(encoding="utf-8")
    assert "B.reportBoard(" in viewer and "board.load(" in viewer and "bindKiosk" in viewer


def test_the_start_page_makes_a_board_as_a_report_with_a_board_page(client):
    page = client.get("/bi/designer")
    assert page.status_code == 200
    assert "data-board-start" in page.text and "data-new-board" in page.text and "data-board-drafts" in page.text and "/bi/design/theme/new" in page.text
    js = (STATIC / "js" / "bi-design.js").read_text(encoding="utf-8")
    assert "layout: board" in js and "data-new-board" in js and "i.board" in js


def test_the_report_designer_is_in_the_studio_and_edits_tiles(client):
    page = client.get("/bi/design/bi-1")
    assert page.status_code == 200 and page.headers["content-security-policy"] == CSP
    for marker in ("data-studio", "data-studio-rail", "data-studio-tabs", "data-studio-dock", 'data-dock-tab="problems"', 'data-dock-tab="query"'):
        assert marker in page.text, marker
    for script in ("bi-studio.js", "bi-format-pane.js", "bi-format-rules.js", "bi-design-v2.js", "bi-matrix.js", "bi-multiples.js", "bi-visuals-format.js"):
        assert "/static/js/" + script in page.text, script
    v2 = (STATIC / "js" / "bi-design-v2.js").read_text(encoding="utf-8")
    for hook in ("data-tile-report", "data-tile-visual", "'data-tile-' + name + '-op'", "data-page-layout", "data-page-tooltip", "data-interaction", "data-report-theme", "data-board-rotate"):
        assert hook in v2, hook


def test_the_new_scripts_have_no_cdn_inline_code_or_markup_string():
    for name in ("bi-studio.js", "bi-format-pane.js", "bi-format-rules.js", "bi-design-v2.js", "bi-matrix.js", "bi-multiples.js", "bi-visuals-format.js", "bi-tooltips.js",
                 "bi-report-board.js", "bi-theme-designer.js"):
        text = (STATIC / "js" / name).read_text(encoding="utf-8")
        assert "http://" not in text and "https://" not in text and "innerHTML" not in text and "insertAdjacentHTML" not in text and "eval(" not in text, name
        assert not re.search(r"\bon[a-z]+\s*=\s*['\"]", text), name
        assert len(text.splitlines()) < 1500, name
        assert text.startswith("/*\n * Project Drishti"), name
    for tmpl in ("design.html", "designer.html", "design_theme.html", "_studio.html", "report.html"):
        html = (TEMPLATES / tmpl).read_text(encoding="utf-8")
        assert "<script>" not in html and " style=" not in html and not re.search(r"\sonclick=", html), tmpl
    css = (STATIC / "css" / "bi-studio.css").read_text(encoding="utf-8")
    assert "min-height: 44px" in css and "@media" in css and "http" not in css
