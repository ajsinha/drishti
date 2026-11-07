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

"""The under-construction registry (docs/reference/UNDER_CONSTRUCTION.md, config/under-construction.yaml): every place it names exists and the
document is current (tools/under_construction.py --check); the console honours each treatment for menu items and help pages (hidden, or
the chip); a page's figure under construction is the server's (tone uc), drawn as the same chip. The Sutra side (every bind and panel
exists, every matched Sutra honours its treatment) is drishti-rachana's UnderConstructionTest."""
from __future__ import annotations

import importlib.util
import re

import pytest

from conftest import CONSOLE
from core import under_construction
from test_edition import _app, _client

ROOT = CONSOLE.parent


def _tool():
    spec = importlib.util.spec_from_file_location("drishti_under_construction", ROOT / "tools" / "under_construction.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


@pytest.mark.static
def test_every_registered_place_exists_and_the_document_is_current():
    tool = _tool()
    items = tool.load()
    assert items and {i["treatment"] for i in items} <= {"hidden", "under-construction"}
    assert tool.problems(items) == []
    assert tool.DOC.read_text(encoding="utf-8") == tool.render(items), "stale: run python3 tools/under_construction.py"
    ids = {i["id"] for i in items}
    assert {"sa-ccr-figures", "cva-figures", "credit-limit-usage", "counterparty-stored-net-mtm", "pre-deal-limit-decision"} <= ids


def test_a_help_page_under_construction_carries_the_chip(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    assert under_construction.of(app).help_marked("docs-contracts-saccr")         # the shipped registry
    page = _client(app).get("/help/docs-contracts-saccr")
    assert page.status_code == 200 and 'class="drishti_uc-banner"' in page.text and "drishti_uc-chip" in page.text
    assert "drishti_under-construction.css" in page.text
    plain = _client(app).get("/help/docs-contracts-lim").text
    assert "drishti_uc-banner" not in plain


def test_menu_items_and_help_pages_honour_their_treatment(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    app.state.under_construction = under_construction.Registry([
        {"id": "a", "treatment": "hidden", "menus": ["fxv"], "help": ["docs-contracts-wwr"]},
        {"id": "b", "treatment": "under-construction", "menus": ["cpty"]},
    ])
    page = _client(app).get("/t").text
    assert not re.search(r'data-cmd="FXV"|/go\?q=FXV|cmd=FXV', page), "a hidden menu item is left out"
    if "Counterparties" in page:
        assert re.search(r"Counterparties <span class=\"drishti_uc-chip\"", page), "an item under construction carries the chip"
    assert _client(app).get("/help/docs-contracts-wwr").status_code == 404           # a hidden help page is not reached by its address


@pytest.mark.static
def test_a_figure_under_construction_is_drawn_as_the_chip():
    css = (CONSOLE / "web" / "static" / "css" / "drishti_under-construction.css").read_text(encoding="utf-8")
    assert ".t-uc" in css and ".drishti_uc-chip" in css and "var(--d-warn)" in css      # theme tokens only
    macros = (CONSOLE / "web" / "templates" / "_macros" / "panels.html").read_text(encoding="utf-8")
    assert "t-{{ c.tone }}" in macros                                                   # tone uc -> class t-uc wherever a cell is drawn
