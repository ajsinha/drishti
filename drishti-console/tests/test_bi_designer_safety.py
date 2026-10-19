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

"""Designers and workspaces: the pages carry the controls that make saving safe (stale-save choices, Publish, panes, wizard overview, recipients search) and
the proxy passes a stale save's base state and a force through unchanged.
"""
import pytest
from fastapi.testclient import TestClient

from bi_backend import BiBackend
from conftest import CONSOLE
from core.app import create_app
from core.config import load_settings

STATIC = CONSOLE / "web" / "static"


class Backend(BiBackend):
    def __init__(self):
        super().__init__()
        self.calls = []

    async def bi(self, method, path, ident, body=None, **params):
        self.calls.append((method, path, body))
        return await super().bi(method, path, ident, body, **params)


@pytest.fixture()
def client():
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = Backend()
    return TestClient(app)


def test_the_report_designer_has_a_publish_action_pane_tabs_and_a_collapsible_filters_pane(client):
    page = client.get("/bi/design/file:report:trades-overview")
    assert page.status_code == 200
    for marker in ("data-publish", "data-publish-label", "data-mpanes", "data-hide-filters", "data-dmain", "data-readonly", "data-published-note"):
        assert marker in page.text, marker


def test_the_item_page_offers_the_wizard_for_a_dataset_and_the_wizard_page_takes_an_edit_id(client):
    assert "data-open-wizard" in client.get("/bi/i/file:dataset:trades-basic").text
    wiz = client.get("/bi/datasets/new?edit=file:dataset:trades-basic")
    assert wiz.status_code == 200 and 'data-edit="file:dataset:trades-basic"' in wiz.text


def test_the_subscribe_page_searches_recipients_and_keeps_the_bi_links(client):
    page = client.get("/reports/subscribe?type=report&id=file:report:trades-overview&title=Trades")
    assert page.status_code == 200
    for marker in ("data-find-people", "data-find-list", "dist-binav", "/bi/catalogue", "dist-check", "/static/js/subscribe-recipients.js", "/static/js/people.js"):
        assert marker in page.text, marker


def test_the_save_helpers_name_the_stale_code_and_the_three_choices():
    lib = (STATIC / "js" / "bi-lib.js").read_text(encoding="utf-8")
    assert "DRS-9007" in lib and "baseEtag" in lib
    for choice in ("data-stale-reload", "data-stale-copy", "data-stale-overwrite"):
        assert choice in lib, choice
    # every editor saves through the guarded helper, none sends a bare PUT of its body
    for name in ("bi-design.js", "bi-theme-designer.js", "bi-model.js", "bi-wizard-steps.js"):
        assert "B.saveItem(" in (STATIC / "js" / name).read_text(encoding="utf-8"), name


def test_a_force_and_a_base_state_pass_through_the_proxy_unchanged(client):
    client.post("/bi/api/items/bi-1/propose", json={"force": True, "note": "go"})
    client.put("/bi/api/items/bi-1", json={"body": "x", "baseEtag": "2026-10-12T10:00:00Z"})
    sent = {(c[0], c[1]): c[2] for c in client.app.state.backend.calls}
    assert sent[("POST", "/items/bi-1/propose")] == {"force": True, "note": "go"}
    assert sent[("PUT", "/items/bi-1")] == {"body": "x", "baseEtag": "2026-10-12T10:00:00Z"}
