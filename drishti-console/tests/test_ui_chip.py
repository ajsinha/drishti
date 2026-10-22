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

"""UI Board P4b, the chip designer's console routes against the stand-in server of ui_chip_backend.py: the chip check, the lifecycle with four
eyes, candidates, the YAML tab in Chip mode, and "Open as board" / "Export Sutra" through P5's converter routes (the thin adapter is gone)."""
import json

import pytest
import yaml
from fastapi.testclient import TestClient

from conftest import CONSOLE
from ui_chip_backend import UiChipBackend

CHIP = {"rachana": 1, "chip": "pnl-strip", "version": 1, "title": "P&L strip", "category": "charts",
        "manual": {"purpose": "The P&L.", "pins": {"rows": "The rows.", "selected": "Out. The id."}, "behaviour": "Click sends selected.", "defaults": "None.",
                   "limits": "1,000 rows.", "examples": [{"title": "A book", "board": "chips:\n  s: { use: pnl-strip@1 }\n"}], "not-for": "A table.", "accessibility": "A list."},
        "pins": {"in": {"rows": {"role": "data", "type": "rowset", "required": True, "shape": {"id": {"type": "id", "required": True}}}}, "out": {"selected": {"type": "id"}}},
        "body": {"panels": [{"id": "strip", "kind": "hbar", "rows": "@rows", "on-select": {"emits": "selected", "value": "@.id"}}]}}


@pytest.fixture
def ui():
    from core.app import create_app
    from core.config import load_settings
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = UiChipBackend()
    with TestClient(app) as c:
        yield c, app.state.backend


def test_the_chip_check_and_the_lifecycle_with_four_eyes(ui):
    c, be = ui
    ok = c.post("/ui/api/chips/check", json=CHIP).json()
    assert ok["ok"] is True and ok["problems"] == []
    bad = dict(CHIP, body={"panels": [{"id": "strip", "kind": "hbar", "rows": "@trades"}]})
    got = c.post("/ui/api/chips/check", json=bad).json()
    assert not got["ok"] and {p["rule"] for p in got["problems"]} >= {"CP-02", "CP-03"}
    assert c.put("/ui/api/chips/pnl-strip/draft", content=yaml.safe_dump(CHIP), headers={"Content-Type": "application/yaml"}).status_code == 200
    assert c.post("/ui/api/chips/pnl-strip/1/submit", json={}).json()["state"] == "in-review"
    r = c.post("/ui/api/chips/pnl-strip/1/approve", json={})
    assert r.status_code == 403 and r.json()["code"] == "DRS-2007"                        # nobody approves their own chip
    be.acting = "bob"
    assert c.post("/ui/api/chips/pnl-strip/1/approve", json={}).json()["state"] == "approved"
    be.acting = None
    assert c.post("/ui/api/chips/pnl-strip/1/publish", json={}).json()["state"] == "published"
    assert [v["state"] for v in c.get("/ui/api/chips/pnl-strip").json()["versions"]] == ["published"]
    v2 = json.loads(json.dumps(CHIP))
    v2.update(version=2, **{"compatible-with": [1]})
    v2["pins"]["out"]["selected"]["type"] = "date"
    got = c.post("/ui/api/chips/check", json=v2).json()
    assert got["previous"] == 1 and got["breaking"] and any(p["rule"] == "CP-06" for p in got["problems"])


def test_candidates_and_the_chip_yaml_tab(ui):
    c, _ = ui
    assert c.get("/ui/api/candidates").json()[0]["chip"] == "local-limits"
    assert c.get("/ui/api/candidates/core/core-book/local-limits").json()["document"]["body"]["panels"][0]["rows"] == "@doc.LIMITS"
    text = "# my chip\nrachana: 1\nchip: x\nversion: 1\npins:\n  in: {}\n"
    out = c.post("/ui/api-yaml/dump", json={"document": dict(yaml.safe_load(text), title="X"), "yaml": text, "kind": "chip"}).json()["yaml"]
    assert out.startswith("# my chip") and "title: X" in out and out.index("title:") < out.index("pins:")


def test_build_new_lists_board_and_chip_examples_and_each_opens_as_a_copy(ui):
    c, be = ui
    page = c.get("/build/new").text
    for name in ("book-trades", "master-detail", "curve-page", "counterparty-page", "economy-map", "filterable-table", "entity-list", "chart-by-spec"):
        assert f'data-ui-example="{name}"' in page
    assert 'href="/build/board/new?example=master-detail"' in page and 'href="/build/chip/new?example=entity-list"' in page
    assert "Screen (Sutra) examples, open as a board" in page
    got = c.get("/build/board-api/example/entity-list").json()
    assert got["kind"] == "chip" and got["document"]["body"]["wires"] == {"doc": "header.doc", "rows": "list.rows"}
    assert c.post("/ui/api/chips/check", json=got["document"]).json()["ok"] is True                 # the composite example passes the check
    assert c.post("/ui/api/chips/check", json=c.get("/build/board-api/example/chart-by-spec").json()["document"]).json()["ok"] is True
    mine = c.get("/build/board-api/example/filterable-table").json()["document"]
    assert mine["chip"] == "filterable-table-mine" and mine["version"] == 1 and "compatible-with" not in mine
    conv = c.get("/build/board-api/example/curve-page").json()
    assert conv["kind"] == "board" and conv["document"]["board"] and ("ui", "POST", "/convert/sutra", {}) in be.calls
    assert c.get("/build/board-api/example/nope").status_code == 404
    assert 'data-example="master-detail"' in c.get("/build/board/new?example=master-detail").text


def test_open_as_board_and_export_sutra_go_through_the_converter(ui):
    c, be = ui
    sutra = "rachana: 1\nsutra: book\nversion: 1\ndescription: Book\nmatch: { kind: book, priority: 10 }\npanels:\n  - { id: limits, kind: table, rows: $.LIMITS }\n"

    async def designs(method, path, ident, body=None, **params):
        if path.endswith("/samples/document"):
            return {"BOOK_ID": "BK-7", "NAME": "Rates"}
        return {"id": "d-1", "name": "Book", "kind": "book", "sutra": sutra, "samples": [{"name": "book.json", "type": "file"}]}
    be.designs = designs
    got = c.get("/build/board-api/from-design/d-1").json()
    assert got["document"]["board"] == "core-book" and got["document"]["chips"]["limits"]["use"] == "local-limits@1"
    # the board-local chips come as rows (chip, nearest store chips, why) for the designer's collapsed notes, not one sentence each
    assert got["locals"][0]["chip"] == "local-limits" and got["locals"][0]["why"]
    assert got["params"] == {"id": "BK-7"}                   # the id parameter prefilled from the design's sample, so data shows at once
    assert ("ui", "POST", "/convert/sutra", {}) in be.calls
    out = c.post("/build/board-api/to-sutra", json={"document": got["document"]}).json()
    assert yaml.safe_load(out["yaml"])["match"]["kind"] == "book"
    none = c.post("/build/board-api/to-sutra", json={"document": {"board": "x", "chips": {}}}).json()
    assert none["yaml"] is None and "parameter id" in none["notes"][0]
