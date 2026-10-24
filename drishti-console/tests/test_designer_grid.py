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
Sutra (and on screens with areas), the panels a click opens as cells of their own (A2a ... opens from A2), the column widths, the steps
(a formula typed, merge, split, insert, delete, move, make it a grid) and the routes."""
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
        for k in cell["kids"]:                                            # and every panel a click opens, from its own cell
            child = dg._child(doc["panels"], k)
            assert k["formula"].endswith(" opens from " + k["from"]) and k["ref"].startswith(cell["ref"])
            [call] = dg.parse(dg._opens(k["formula"], k))
            assert dg.apply_call(child, call, dg.reader(doc, g)) == child, k["formula"]
            again = copy.deepcopy(doc)
            dg.set_formula(again, k["ref"], k["formula"])
            assert designer.dump(again) == designer.dump(doc), k["ref"]


def test_the_pack_sutras_have_panels_a_click_opens():
    kids = [k for p in PACK_SUTRAS for c in dg.grid(designer.load(p.read_text()))["cells"] for k in c["kids"]]
    assert kids and all(dg.KID.fullmatch(k["ref"]) for k in kids)


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


KIDS = """rachana: 1
sutra: qa-kids
version: 1
match: { kind: portfolio-trades }
layout:
  areas: ["trades side"]
panels:
  - id: trades
    kind: table
    area: trades
    rows: $.TRADES
    columns: [ { label: Trade, bind: "@.TRADE_ID" } ]
    emits: [ { name: open, on: row-click, payload: { id: "@.TRADE_ID", ref: "@.COUNTERPARTY" } } ]
    children:
      - place: expand-row
        source: { kind: trade, id: "@parent.open.id" }
        panels:
          - { use: cashflow-ladder@1, with: { id: flows, rows: $src.EVENTS, title: "Cash flows" } }
          - { id: legs, kind: table, rows: $src.LEGS, columns: [ { bind: "@.LEG" } ] }
  - { id: note, kind: markdown, area: side, text: "See @flows.open.x" }
"""


def test_a_panel_a_click_opens_is_a_cell_of_its_own():
    doc = designer.load(KIDS)
    assert roundtrip(doc) == designer.dump(doc)
    g = dg.grid(doc)
    a1 = next(c for c in g["cells"] if c["ref"] == "A1")
    assert [k["ref"] for k in a1["kids"]] == ["A1a", "A1b"] and a1["kids"][0]["place"] == "expand-row"
    assert a1["kids"][0]["formula"] == 'cashflow-ladder@1(id: flows, rows: $src.EVENTS, title: Cash flows) opens from A1'
    assert a1["kids"][1]["kept"] == ["columns"]
    b1 = next(c for c in g["cells"] if c["ref"] == "B1")
    assert b1["formula"] == 'markdown(id: note, text: See @A1a.open.x)'           # a child named by its cell, read back to its id
    dg.set_formula(doc, "A1a", "cashflow-ladder(id: flows, rows: $src.EVENTS, title: Flows of @parent.open.id) opens from A1",
                   ["trading/cashflow-ladder@1"])
    flows = doc["panels"][0]["children"][0]["panels"][0]
    assert flows == {"use": "trading/cashflow-ladder@1", "with": {"id": "flows", "rows": "$src.EVENTS", "title": "Flows of @parent.open.id"}}
    dg.set_formula(doc, "A1b", "table(id: legs, title: Legs, rows: $src.LEGS)")          # 'opens from' may be left out
    assert doc["panels"][0]["children"][0]["panels"][1]["columns"] == [{"bind": "@.LEG"}]  # what the formula does not show is kept
    before = copy.deepcopy(doc)
    with pytest.raises(dg.FormulaError, match="move it there in Outline") as e:
        dg.set_formula(doc, "A1b", "table(id: legs) opens from B1")
    assert "table(id: legs) opens from B1"[e.value.start:e.value.end] == "B1"
    with pytest.raises(dg.FormulaError, match="one line"):
        dg.set_formula(doc, "A1b", "table(id: legs)\ntable(id: more)")
    with pytest.raises(designer.DesignerError, match="no cell A1c"):
        dg.set_formula(doc, "A1c", "table(id: x)")
    assert doc == before
    dg.set_formula(doc, "A1b", "")                                                         # emptied: the panel goes
    dg.set_formula(doc, "A1a", "")
    assert "children" not in doc["panels"][0]


DEEP = """rachana: 1
sutra: qa-deep
version: 1
match: { kind: portfolio-trades }
layout:
  areas: ["trades side"]
panels:
  - id: trades
    kind: table
    area: trades
    rows: $.TRADES
    columns: [ { label: Trade, bind: "@.TRADE_ID" } ]
    emits: [ { name: open, on: row-click, payload: { id: "@.TRADE_ID" } } ]
    children:
      - place: detail-below
        source: { kind: trade, id: "@parent.open.id" }
        panels:
          - id: flows
            kind: table
            rows: $src.EVENTS
            columns: [ { bind: "@.EVENT_DATE" } ]
            emits: [ { name: open, on: row-click, payload: { ref: "@.EVENT_ID" } } ]
            children:
              - place: expand-row
                source: { kind: event, id: "@parent.open.ref" }
                panels:
                  - { use: field-list@1, with: { id: event, rows: $src, title: "The event" } }
                  - id: fixings
                    kind: table
                    rows: $src.FIXINGS
                    columns: [ { bind: "@.RATE" } ]
                    children: [ { place: expand-row, panels: [ { id: deeper, kind: kv, rows: $src } ] } ]
          - { id: legs, kind: table, rows: $src.LEGS, columns: [ { bind: "@.LEG" } ] }
  - { id: note, kind: markdown, area: side, text: "See @event.open.x and @flows.open.ref" }
"""


def test_two_levels_of_opened_panels_are_cells():
    doc = designer.load(DEEP)
    assert doc["panels"][0]["children"][0]["panels"][0]["emits"][0]["on"] == "row-click"     # 'on' is a key, not YAML 1.1's true
    assert roundtrip(doc) == designer.dump(doc)
    g = dg.grid(doc)
    a1 = next(c for c in g["cells"] if c["ref"] == "A1")
    assert [(k["ref"], k["level"], k["from"]) for k in a1["kids"]] == [("A1a", 1, "A1"), ("A1a1", 2, "A1a"), ("A1a2", 2, "A1a"), ("A1b", 1, "A1")]
    kid = {k["ref"]: k for k in a1["kids"]}
    assert kid["A1a1"]["formula"] == 'field-list@1(id: event, rows: $src, title: The event) opens from A1a'
    assert kid["A1a"]["kept"] == ["columns", "emits"]                          # its children show as cells
    assert kid["A1a2"]["kept"] == ["columns", "children"]                      # a third level is kept, unchanged
    b1 = next(c for c in g["cells"] if c["ref"] == "B1")
    assert b1["formula"] == 'markdown(id: note, text: See @A1a1.open.x and @A1a.open.ref)'
    for k in a1["kids"]:                                                       # every child cell typed as it reads: the same document
        again = copy.deepcopy(doc)
        dg.set_formula(again, k["ref"], k["formula"])
        assert designer.dump(again) == designer.dump(doc), k["ref"]
    dg.set_formula(doc, "A1a1", "field-list(id: event, rows: $src, title: Event @parent.open.ref)", ["field-list@1"])   # 'opens from' left out
    ev = doc["panels"][0]["children"][0]["panels"][0]["children"][0]["panels"][0]
    assert ev == {"use": "field-list@1", "with": {"id": "event", "rows": "$src", "title": "Event @parent.open.ref"}}
    dg.set_formula(doc, "B1", "markdown(id: note, text: See @A1a2.open.x)")      # @A1a1. and @A1a2. read back as the panel's id
    assert doc["panels"][1]["text"] == "See @fixings.open.x"
    before = copy.deepcopy(doc)
    for other in ("A1", "A1b", "flows-not"):
        text = f"table(id: fixings) opens from {other}"
        with pytest.raises(dg.FormulaError, match="it opens from A1a") as e:
            dg.set_formula(doc, "A1a2", text)
        assert text[e.value.start:e.value.end] == other
    with pytest.raises(designer.DesignerError, match="no cell A1a3 .* a click in A1a opens"):
        dg.set_formula(doc, "A1a3", "table(id: x)")
    with pytest.raises(designer.DesignerError, match="no cell A1b1"):
        dg.set_formula(doc, "A1b1", "table(id: x)")
    assert doc == before
    dg.set_formula(doc, "A1a2", "table(id: fixings, rows: $src.FIX) opens from A1a")
    fx = doc["panels"][0]["children"][0]["panels"][0]["children"][0]["panels"][1]
    assert fx["rows"] == "$src.FIX" and fx["children"][0]["panels"][0]["id"] == "deeper"   # the third level kept
    dg.set_formula(doc, "A1a1", "")                                                       # emptied: the grandchild goes
    dg.set_formula(doc, "A1a1", "")
    assert "children" not in doc["panels"][0]["children"][0]["panels"][0]
    assert [k["ref"] for k in next(c for c in dg.grid(doc)["cells"] if c["ref"] == "A1")["kids"]] == ["A1a", "A1b"]
    assert roundtrip(doc) == designer.dump(doc)


def test_column_widths_in_twelve_units_round_trip():
    doc = designer.load(AREAS)
    dg.set_widths(doc, [8, 4])
    assert doc["layout"]["columns"] == [8, 4] and dg.grid(doc)["widths"] == [8, 4] and dg.grid(doc)["set"]
    assert roundtrip(doc) == designer.dump(doc)
    for bad, words in (([6, 5], "add up to 11"), ([12, 0], "at least 1"), ([6, 6, 0], "each of the 2"), ([6.5, 5.5], "whole units")):
        with pytest.raises(designer.DesignerError, match=words):
            dg.set_widths(doc, bad)
    flow = designer.load(SUTRA)
    with pytest.raises(designer.DesignerError, match="Make it a grid"):
        dg.set_widths(flow, [12])
    doc["layout"].pop("columns")
    assert dg.grid(doc)["widths"] == [6, 6] and not dg.grid(doc)["set"]


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
    assert client.post(f"/designer/grid/op/{draft}", json={**body, "op": "widths", "widths": [9, 3]}).json() == {"ok": True}
    assert "add up to" in client.post(f"/designer/grid/op/{draft}", json={**body, "op": "widths", "widths": [9, 9]}).json()["detail"]
    st = client.get(f"/designer/grid/state/{draft}").json()
    doc = yaml.safe_load(st["yaml"])
    assert doc["layout"]["areas"][0] == "deal-ticket ." and designer.find_panel(doc, "flows")["title"] == "Cash flows"
    assert doc["layout"]["columns"] == [9, 3] and st["grid"]["widths"] == [9, 3]
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
