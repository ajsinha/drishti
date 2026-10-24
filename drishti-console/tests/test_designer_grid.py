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

"""The designer's Grid mode (SCREEN_DESIGNER.md section 31): the formula parser, the Sutra ⇄ cells ⇄ formulas round trip on every pack
Sutra (and on screens with areas), the steps (a formula typed, merge, split, insert, delete, move, make it a grid) and the routes."""
import copy
import re
from pathlib import Path

import pytest
import yaml

from core import designer, designer_grid as dg
from test_designer import SUTRA, on  # noqa: F401 - the designer routes' fixture

ROOT = Path(__file__).resolve().parents[2]
PACK_SUTRAS = sorted((ROOT / "config").glob("**/*.sutra.yaml"))
AREAS = """rachana: 1
sutra: qa-book-grid
version: 1
match: { kind: portfolio-trades }
layout:
  areas:
    - "head   head"
    - "trades detail"
  columns: [7, 5]
  split: { between: [trades, detail] }
  tabs: [detail]
  phone: [head, detail, trades]
panels:
  - { id: pay7, kind: metric, title: "Payments, next 7 days", value: $.PAY_7D, fmt: signed0, area: head, span: 4 }
  - id: trades
    kind: table
    title: Trades (${size($.TRADES)})
    area: trades
    rows: $.TRADES
    columns: [ { label: Trade, bind: "@.TRADE_ID" } ]
    emits: [ { name: open, on: row-click, payload: { trade: "@.TRADE_ID" } } ]
  - { id: terms, use: field-list@1, with: { id: terms, rows: $src, title: "Terms, as booked" }, area: detail,
      source: { kind: trade, id: "@trades.open.trade" } }
  - { id: book, kind: kv, title: The book, area: detail, columns: [ { label: Trades, bind: $.TRADE_COUNT } ], when: "lookup($.X, 'A', 1) != null" }
"""


def roundtrip(doc):
    return designer.dump(dg.rebuild(doc, dg.grid(doc)))


@pytest.mark.parametrize("path", PACK_SUTRAS, ids=lambda p: p.name)
def test_every_pack_sutra_round_trips_as_a_one_column_grid(path):
    doc = designer.load(path.read_text())
    g = dg.grid(doc)
    assert g["mode"] == "flow" and g["cols"] == 1 and len(g["cells"]) == len(doc["panels"])
    assert roundtrip(doc) == designer.dump(doc)
    for cell in g["cells"]:                                               # and every formula reads back to its panel
        for i, f in zip(cell["panels"], cell["formulas"]):
            [call] = dg.parse(f)
            assert dg.apply_call(doc["panels"][i], call, dg.reader(doc, g)) == doc["panels"][i], f


def test_a_screen_with_areas_round_trips_and_reads_as_a_spreadsheet():
    doc = designer.load(AREAS)
    assert roundtrip(doc) == designer.dump(doc)
    g = dg.grid(doc)
    assert (g["mode"], g["rows"], g["cols"], g["widths"]) == ("areas", 2, 2, [7, 5])
    cells = {c["range"]: c for c in g["cells"]}
    assert set(cells) == {"A1:B1", "A2", "B2"}
    assert cells["A1:B1"]["formula"] == 'metric(id: pay7, title: "Payments, next 7 days", value: $.PAY_7D, fmt: signed0)'
    assert cells["B2"]["formulas"][0] == 'field-list@1(id: terms, rows: $src, title: "Terms, as booked"; id: terms, source: {kind: trade, id: @A2.open.trade})'
    assert cells["A2"]["kept"] == [["columns", "emits"]] and cells["A1:B1"]["grid"] == [{"span": 4}]


def test_the_parser_reads_values_exactly_and_points_at_the_part_to_fix():
    [c] = dg.parse("B2 = cashflow-ladder@1(id: flows, rows: $src.CASHFLOWS, n: 3, on: true, x: null, w: 1.5, tags: [a, \"b, c\"]; when: BOOK = 'X')")
    assert c["use"] and c["head"] == "cashflow-ladder@1"
    assert c["args"] == {"id": "flows", "rows": "$src.CASHFLOWS", "n": 3, "on": True, "x": None, "w": 1.5, "tags": ["a", "b, c"]}
    assert c["opts"] == {"when": "BOOK = 'X'"}
    [k] = dg.parse("=table(rows: lookup($.P, 'T', 1).DF, title: Trades (${n}))")
    assert not k["use"] and k["opts"] == {"rows": "lookup($.P, 'T', 1).DF", "title": "Trades (${n})"}
    for v in ("12", "true", "a, b", " x", "@trades.open.x", "{x}", "it's", 'say "hi"', "a;b", "", "1.0", 7, 2.5, False, None, ["1", 2]):
        assert dg.readable(v), v
    for text, words, part in (("table(rows: $.X", "not closed", "(rows: $.X"), ("table rows", "( is wanted", "rows"),
                              ("table(rows $.X)", "colon", "rows"), ("field-list@1(a: (1, b: 2)", "not closed", "(a: (1, b: 2)"),
                              ("table(a: 1, a: 2)", "twice", "a:"), ("table(a: \"x)", "quote", "\"x)"), ("table(a: 1) extra", "follow", "extra"),
                              ("123", "starts with", "123")):
        with pytest.raises(dg.FormulaError, match=re.escape(words)) as e:
            dg.parse(text)
        assert text[e.value.start:e.value.end].strip() == part, (text, e.value.start, e.value.end)


def test_a_formula_changes_its_panel_and_keeps_what_it_does_not_show():
    doc = designer.load(AREAS)
    dg.set_formula(doc, "A2", "table(id: trades, title: All trades, rows: $.TRADES)")
    t = designer.find_panel(doc, "trades")
    assert t["title"] == "All trades" and t["columns"] and t["emits"] and t["area"] == "trades"
    assert list(t)[:4] == ["id", "kind", "title", "area"]                                   # the panel's own key order
    dg.set_formula(doc, "B2", "field-list@1(id: terms, rows: $src; id: terms, source: {kind: trade, id: @A2.open.trade})\nmetric(id: n, value: $.N)")
    terms = designer.find_panel(doc, "terms")
    assert terms["source"] == {"kind": "trade", "id": "@trades.open.trade"} and "title" not in terms["with"]
    assert [p.get("id") for p in doc["panels"] if p.get("area") == "detail"] == ["terms", "n"]      # the second line: a panel more
    with pytest.raises(dg.FormulaError, match="no single panel"):
        dg.set_formula(doc, "A1", "metric(id: pay7, value: @Z9.open.x)")
    before = copy.deepcopy(doc)
    with pytest.raises(dg.FormulaError):
        dg.set_formula(doc, "A1", "metric(id: pay7")
    assert doc == before                                                                     # the cell keeps its last good state
    dg.set_formula(doc, "A2", "trades-table(id: trades)", ["core/row-picker@1", "trading/trades-table@2"])
    assert next(p for p in doc["panels"] if dg.panel_id(p) == "trades")["use"] == "trading/trades-table@2"


def test_merge_split_insert_delete_and_move():
    doc = designer.load(AREAS)
    dg.insert(doc, "row", 1)                                    # between head (across) and trades|detail: an empty row
    assert doc["layout"]["areas"] == ["head   head", ". .", "trades detail"]
    dg.insert(doc, "col", 1)                                    # inside head: head grows over it
    assert doc["layout"]["areas"] == ["head head head", ". . .", "trades . detail"] and doc["layout"]["columns"] == [4, 4, 4]
    dg.set_formula(doc, "B2", "markdown(id: note, text: hello)")
    assert doc["layout"]["areas"][1] == ". note ." and designer.find_panel(doc, "note")["area"] == "note"
    with pytest.raises(designer.DesignerError, match="cuts the area"):
        dg.merge(doc, "A1", "B2")
    dg.merge(doc, "B2", "B3")
    assert doc["layout"]["areas"] == ["head head head", ". note .", "trades note detail"]
    dg.split(doc, "B2")
    assert doc["layout"]["areas"][2] == "trades . detail"
    dg.move(doc, "B2", "A2")                                    # onto an empty cell: the note moves there
    assert doc["layout"]["areas"][1] == "note . ."
    dg.move(doc, "A3", "C3")                                    # trades and detail change places
    assert designer.find_panel(doc, "trades")["area"] == "detail" and designer.find_panel(doc, "terms")["area"] == "trades"
    with pytest.raises(designer.DesignerError, match="move or clear"):
        dg.delete(doc, "row", 1)
    dg.set_formula(doc, "A2", "")
    assert designer.find_panel(doc, "note") is None
    dg.delete(doc, "row", 1)
    dg.delete(doc, "col", 1)
    assert doc["layout"]["areas"] == ["head head", "trades detail"] and doc["layout"]["columns"] == [6, 6]


def test_a_flowing_screen_is_one_column_and_can_become_a_grid():
    doc = designer.load(SUTRA)
    dg.move(doc, "A3", "A1")
    assert doc["panels"][0] == {"use": "data-provenance@1"}
    dg.set_formula(doc, "A4", "markdown(id: note, text: hi)")                        # a row past the end: a panel added there
    assert doc["panels"][3]["id"] == "note"
    with pytest.raises(designer.DesignerError, match="Make it a grid"):
        dg.merge(doc, "A1", "A2")
    dg.make_grid(doc)
    assert doc["layout"]["areas"] == ["data-provenance", "deal-ticket", "flows", "note"]
    assert dg.grid(doc)["mode"] == "areas" and roundtrip(doc) == designer.dump(doc)


# ---- the routes -------------------------------------------------------------------------------------------------------------------

def test_grid_routes(client, on):  # noqa: F811
    assert 'href="/designer/grid' in client.get("/designer/new").text                     # Grid enabled in the mode switcher
    r = client.get("/designer/grid?kind=trade&id=IRS-47102", follow_redirects=False)
    assert r.status_code == 303 and "design=" in r.headers["location"]
    draft = r.headers["location"].split("design=")[1]
    page = client.get(r.headers["location"]).text
    assert "data-grid" in page and f'data-draft="{draft}"' in page and "designer-grid.js" in page
    st = client.get(f"/designer/grid/state/{draft}").json()
    assert st["grid"]["mode"] == "flow" and st["grid"]["cells"][1]["formula"].startswith("table(id: flows")
    assert any(f["ref"] for f in st["suggest"]["fragments"]) and st["suggest"]["panels"][1]["cell"] == "A2"
    body = {"kind": "trade", "id": "IRS-47102"}
    ok = client.post(f"/designer/grid/op/{draft}", json={**body, "op": "formula", "cell": "A2",
                                                         "text": "table(id: flows, title: Cash flows, rows: flatten($.LEGS[*].CASH_FLOWS))"})
    assert ok.json() == {"ok": True}
    bad = client.post(f"/designer/grid/op/{draft}", json={**body, "op": "formula", "cell": "A2", "text": "table(id: flows"})
    assert bad.status_code == 400 and bad.json()["start"] == 5 and "not closed" in bad.json()["detail"]
    assert client.post(f"/designer/grid/op/{draft}", json={**body, "op": "make-grid"}).json() == {"ok": True}
    assert client.post(f"/designer/grid/op/{draft}", json={**body, "op": "insert", "axis": "col", "at": 1}).json() == {"ok": True}
    st = client.get(f"/designer/grid/state/{draft}").json()
    doc = yaml.safe_load(st["yaml"])
    assert doc["layout"]["areas"][0] == "deal-ticket ." and designer.find_panel(doc, "flows")["title"] == "Cash flows"
    assert client.post(f"/designer/grid/op/{draft}", json={**body, "op": "nope"}).status_code == 400


def test_the_default_mode_is_a_preference(client, on, backend, monkeypatch):  # noqa: F811
    got = {}

    async def settings(ident=None):
        return {"designerMode": got.get("designerMode", "customize")}

    async def patch(changes, ident=None):
        got.update(changes)
        return {"designerMode": got["designerMode"]}
    monkeypatch.setattr(backend, "settings", settings, raising=False)
    monkeypatch.setattr(backend, "patch_settings", patch, raising=False)
    assert client.post("/designer/mode", json={"mode": "grid"}).json() == {"mode": "grid"}
    assert client.post("/designer/mode", json={"mode": "spreadsheet"}).status_code == 400
    r = client.get("/designer", follow_redirects=False)
    assert r.headers["location"] == "/designer/grid"
