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

"""Rupaka phase 4 (docs/guides/BI_GOVERNANCE.md), the console's routes: the item page carries the history, review, impact and lineage sections and their script, the
Environments page and its rail entry, the same-origin JSON that reaches the server's history, diff, review, lineage, impact and bundle endpoints (and its refusals
come back with their code), and an inbox row of a BI notice opens the item."""
import pytest
from fastapi.testclient import TestClient

from bi_backend import BiBackend
from conftest import CONSOLE
from core.app import create_app
from core.config import load_settings
from routes.collab_routes import _row_href


def make(powers=("bi", "bi-author", "bi-steward")):
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = BiBackend(powers)
    return TestClient(app), app.state.backend


@pytest.fixture()
def bi():
    return make()


def test_the_item_page_has_the_governance_sections_and_script(bi):
    client, _ = bi
    page = client.get("/bi/i/bi-1").text
    for marker in ("data-sec-history", "data-sec-review", "data-sec-impact", "data-sec-lineage", "data-diff-form", "data-force", "data-warnings", 'name="message"'):
        assert marker in page
    assert "/static/js/bi-govern.js" in page and "/static/js/bi-lib.js" in page
    assert "<script>" not in page.replace("<script src", "") and " onclick=" not in page


def test_environments_page_is_in_the_rail_and_has_the_export_and_import_forms(bi):
    client, _ = bi
    r = client.get("/bi/environments")
    assert r.status_code == 200 and 'data-bi-page="environments"' in r.text
    assert "data-export" in r.text and "data-import" in r.text and "Dry run" in r.text and "/static/js/bi-env.js" in r.text
    assert 'href="/bi/environments"' in client.get("/bi").text
    none, _ = make(powers=())
    assert none.get("/bi/environments").status_code == 403


def test_the_governance_calls_are_passed_on_as_the_caller(bi):
    client, backend = bi
    assert client.get("/bi/api/items/bi-1/history").status_code == 200
    assert client.get("/bi/api/items/bi-1/diff", params={"from": "r1", "to": "draft"}).status_code == 200
    assert ("GET", "/items/bi-1/diff", None, {"from": "r1", "to": "draft"}) in backend.bi_calls
    assert client.get("/bi/api/items/bi-1/review").status_code == 200
    assert client.get("/bi/api/items/bi-1/lineage").status_code == 200
    assert client.get("/bi/api/items/bi-1/impact", params={"remove": "t.b"}).status_code == 200
    assert ("GET", "/items/bi-1/impact", None, {"remove": "t.b"}) in backend.bi_calls
    assert client.post("/bi/api/items/bi-1/history/2/restore", json={}).status_code == 200
    assert client.post("/bi/api/items/bi-1/publish", json={"note": "n", "force": True}).json()["echo"] == {"note": "n", "force": True}
    assert client.get("/bi/api/bundle", params={"workspace": "rates"}).status_code == 200
    r = client.post("/bi/api/bundle/import", json={"bundle": {"format": "drishti-bi-bundle"}, "dryRun": True, "owners": {"a": "b"}})
    assert r.json()["echo"]["dryRun"] is True and r.json()["echo"]["owners"] == {"a": "b"}


def test_the_refusals_of_governance_come_back_with_their_code(bi):
    client, backend = bi
    for code, status in (("DRS-9040", 409), ("DRS-9041", 422), ("DRS-9042", 409)):
        backend.refuse = (code, status, "reason " + code)
        r = client.post("/bi/api/items/bi-1/publish", json={}) if code == "DRS-9040" else client.post("/bi/api/bundle/import", json={"bundle": {}})
        assert r.status_code == status and r.json()["code"] == code and code in r.json()["detail"]


def test_a_bi_notice_in_the_inbox_opens_the_item():
    row = {"type": "bi-review", "kind": "bi", "id": "bi-1a2b", "access": True}
    assert _row_href(row) == "/bi/i/bi-1a2b"
    assert _row_href({"type": "bi-certification", "kind": "bi", "id": "file:report:x"}) == "/bi/i/file:report:x"
    assert _row_href({"type": "bi-decision", "id": None}) == ""
