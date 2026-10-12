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

"""The workspace list's delete, the designer's per-visual preview and error words, and several datasets in the designer: what the pages and the vendored
scripts carry (the behaviour is in test_designer_multi_and_delete_browser.py). Route and source checks only: no server, no browser."""
import re

import pytest
from fastapi.testclient import TestClient

from bi_backend import BiBackend
from conftest import CONSOLE
from core.app import create_app
from core.config import load_settings

JS = CONSOLE / "web" / "static" / "js"
TPL = CONSOLE / "web" / "templates" / "bi"


@pytest.fixture()
def client():
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = BiBackend(("bi", "bi-author", "bi-admin"), True)
    return TestClient(app)


def read(path):
    return path.read_text(encoding="utf-8")


def test_the_workspace_page_has_the_bulk_delete_controls(client):
    html = client.get("/bi/w/me").text
    for marker in ("data-select-all", "data-delete-selected", "data-confirm", "data-confirm-list", "data-confirm-yes", "data-new-dataset", "data-need-author"):
        assert marker in html, marker
    assert "Delete selected (0)" in html
    assert " onclick=" not in html                                                      # strict CSP: no inline handlers


def test_the_workspace_script_deletes_one_by_one_with_the_existing_rule_and_names_the_refusals():
    js = read(JS / "bi.js")
    assert "it.can && it.can.delete" in js
    assert "api('DELETE', '/items/' + encodeURIComponent(it.id))" in js
    assert "Skipped" in js and "Deleted" in js and "data-row-delete" in js and "data-pick" in js
    assert "function defaultTitle" in js and "My report" not in js


def test_a_new_report_has_a_distinct_default_title_not_my_report():
    js = read(JS / "bi-design.js")
    assert "My report" not in js and "function defaultTitle" in js
    assert "required" not in re.search(r"<input[^>]*data-new-title[^>]*>", read(TPL / "designer.html")).group(0)


def test_the_new_item_form_asks_a_report_for_its_dataset_and_opens_the_designer():
    js = read(JS / "bi.js")
    assert "'/bi/design/'" in js and "'/bi/design/board/'" in js and "'/bi/datasets/new?workspace='" in js and "'/bi/explore'" in js
    assert "dataset: ' + f.elements.dataset.value" in js


def test_a_report_without_a_dataset_gets_a_picker_in_the_designer():
    js = read(JS / "bi-design.js")
    assert "function ensureDataset" in js and "data-choose-dataset" in js and "'dataset: ' + sel.value" in js


def test_the_designer_preview_leaves_incomplete_visuals_out_and_names_a_visual_in_the_banner():
    js = read(JS / "bi-design-canvas.js")
    assert "function previewQuery" in js and "function incomplete" in js
    assert "report: D.model," not in js                                                 # the whole draft is no longer sent as it is
    assert "' on page ' + " in read(JS / "bi-design.js")


def test_a_drop_says_where_it_fell_and_a_field_already_there_is_not_a_misfit():
    js = read(JS / "bi-design-canvas.js")
    assert "does not fit ' + where" in js and "is already in ' + where" in js
    assert "try another visual or well" not in js


def test_the_designer_loads_the_several_datasets_module_and_its_markup(client):
    html = client.get("/bi/design/bi-1").text
    assert "bi-design-datasets.js" in html and html.index("bi-design-panes.js") < html.index("bi-design-datasets.js")
    for marker in ("data-add-dataset", "data-dataset-picker", "data-links-panel"):
        assert marker in html, marker
    js = read(JS / "bi-design-datasets.js")
    for marker in ("Refused: this visual reads", "fields in another visual, or make a composed dataset", "data-make-composed", "data-link-tick", "data-links-current", "same name and type", "same meaning"):
        assert marker in js, marker
    assert "/workspaces/me/items" in js and "Create the draft and review it" in js        # a composed draft is created only on a click
    assert "innerHTML" not in js


def test_the_canvas_tags_a_visual_that_reads_another_dataset_and_fields_carry_their_dataset():
    canvas, panes = read(JS / "bi-design-canvas.js"), read(JS / "bi-design-panes.js")
    assert "data-dataset-tag" in canvas and "dataset: p.dataset || ''" in canvas
    assert "JSON.stringify({ name, kind, dataset: ref || '' })" in panes and "data-dataset-tree" in panes and "data-remove-dataset" in panes


def test_the_dataset_page_offers_a_new_report_on_it_and_the_designer_start_preselects_it(client):
    assert "data-new-report" in client.get("/bi/d/file:dataset:x").text
    assert "data-no-datasets" in client.get("/bi/designer").text
    assert "URLSearchParams(window.location.search).get('dataset')" in read(JS / "bi-design.js")
