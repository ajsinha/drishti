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

"""ui.boards (DRISHTI_UI_BOARDS), off by default: off, Build is the screen (Sutra) designer as before the UI Board work (New screen, My designs,
the screen examples, no board or chip buttons) and the board and chip pages answer "not enabled"; on, the Board and Chip designer is offered."""
import pytest
from fastapi.testclient import TestClient

from conftest import CONSOLE
from core.app import create_app
from core.config import Settings, load_settings

BOARD_PAGES = ("/build/board/new", "/build/board/my-board", "/build/chip/new", "/build/chip/my-chip", "/ui/b/my-board", "/ui", "/ui/new",
               "/ui/chip-editor", "/build/board-api/examples")


def _console(backend, on=None):
    data = load_settings(CONSOLE / "config").as_dict()
    if on is not None:
        data.setdefault("ui", {})["boards"] = on
    app = create_app(Settings(data))
    app.state.backend = backend
    return TestClient(app)


@pytest.fixture()
def off(backend):
    backend.design_rows = {}
    backend.builder_allowed = True
    return _console(backend)                                     # the shipped default


@pytest.fixture()
def on(backend):
    backend.design_rows = {}
    backend.builder_allowed = True
    return _console(backend, True)


def _menu(page: str) -> str:
    return page.split('class="tbar-menus"')[1].split("</nav>")[0]


def test_the_switch_is_off_by_default():
    assert str(load_settings(CONSOLE / "config").get("ui.boards")).lower() in ("false", "")


def test_off_the_build_menu_is_new_screen_and_my_designs(off):
    menu = _menu(off.get("/build").text)
    assert "New screen" in menu and "My designs" in menu and "Examples" in menu
    assert ">Designer<" not in menu and "Board designer guide" not in menu and "/help/ui-board-guide" not in menu


def test_off_new_is_the_screen_designer_with_its_examples_only(off):
    page = off.get("/build/new").text
    assert "<h1>New screen</h1>" in page and 'id="examples"' in page and "Open as my copy" in page and 'data-as="screen"' in page
    for gone in ("New board", "New chip", "data-new-board", "/build/board/new", "/build/chip/new", "data-ui-examples", "Board and chip examples",
                 'data-as="board"', "Open as a board"):
        assert gone not in page, gone


def test_off_my_designs_and_the_screen_designer_have_no_board_buttons(off):
    off.post("/build/designs", json={"name": "Rates"})
    page = off.get("/build").text
    assert "<h1>My designs</h1>" in page and "Open as board" not in page and "data-ub-boards" not in page and "New board" not in page
    assert "ui-boards.js" not in page
    d = off.post("/build/designs", json={"name": "Mine"}).json()
    page = off.get(f"/build/d/{d['id']}").text
    for gone in ("Open as board", "/build/board/", "Export Sutra", "Make a chip", "data-ub-make-chip"):
        assert gone not in page, gone


@pytest.mark.parametrize("path", BOARD_PAGES)
def test_off_the_board_and_chip_pages_are_not_enabled(off, path):
    r = off.get(path, follow_redirects=False)
    assert r.status_code == 404 and "not enabled on this console (ui.boards)" in r.text, path


def test_on_everything_is_offered(on):
    menu = _menu(on.get("/build").text)
    assert "New screen" not in menu and "Board designer guide" in menu and "/help/ui-board-guide" in menu
    page = on.get("/build/new").text
    assert "<h1>Designer</h1>" in page and "New board" in page and "New chip" in page and 'data-as="board"' in page
    assert "<h1>Boards</h1>" in on.get("/build").text
    d = on.post("/build/designs", json={"name": "Mine"}).json()
    assert "Open as board" in on.get(f"/build/d/{d['id']}").text
    assert on.get("/build/board/new").status_code == 200 and on.get("/build/chip/new").status_code == 200
