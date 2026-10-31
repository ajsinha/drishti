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

"""The Sutra designer (docs/guides/RACHANA_REFERENCE.md › Screen designer and the Build workbench, section 31): Describe's deterministic parser (many phrasings, unsure and wrong lines), the Sutra it
writes, Customize's steps over the Sutra document, and the routes, off by default (ui.designer) and on: a draft, a step, Done keeping the
user's own page (the page then drawn with ?design=), Reset, Describe and Save."""
import pytest
import yaml

from core import describe, designer, designer_links
from core.backend import BackendError

ALIASES = {"TRD": {"kind": "trade", "label": "Trade"}, "CPTY": {"kind": "counterparty", "label": "Counterparty"},
           "PTRD": {"kind": "portfolio-trades", "label": "Portfolio trades"}, "CTRD": {"kind": "counterparty-trades", "label": "Counterparty trades"},
           "CRV": {"kind": "ir-curve", "label": "Curve"}}


# ---- Describe: the parser ----------------------------------------------------------------------------------------------------------

@pytest.mark.parametrize("line,kind,id_,shows,refresh", [
    ("TRD BOOK-LDN-FX · click a trade → cash flows + CPTY limits · refresh 30s", "portfolio-trades", "BOOK-LDN-FX",
     [("cash-flows", "expand-row"), ("limits", "detail-right")], 30),
    ("TRD BOOK-RATES-2 | pick a trade -> cashflows below | refresh 2m", "portfolio-trades", "BOOK-RATES-2", [("cash-flows", "detail-below")], 120),
    ("PTRD BOOK-RATES-2 ; select the trade => counterparty, credit limit", "portfolio-trades", "BOOK-RATES-2",
     [("counterparty", "detail-right"), ("limits", "detail-right")], 0),
    ("trades BOOK-RATES-2 · click a trade shows the trade details as tabs", "portfolio-trades", "BOOK-RATES-2", [("trade", "tabs")], 0),
    ("TRD CP-NORTHBRIDGE · click a row → cash flows beside", "counterparty-trades", "CP-NORTHBRIDGE", [("cash-flows", "detail-right")], 0),
    ("ctrd CP-NORTHBRIDGE · tap on a deal → payments under the row · update every 15 seconds", "counterparty-trades", "CP-NORTHBRIDGE",
     [("cash-flows", "expand-row")], 15),
    ("CPTY CP-NORTHBRIDGE", "counterparty", "CP-NORTHBRIDGE", [], 0),
    ("CRV CRV-USD-OIS · refresh 1 min", "ir-curve", "CRV-USD-OIS", [], 60),
    ("TRD MX-20000001", "trade", "MX-20000001", [], 0),
])
def test_phrasings_are_understood(line, kind, id_, shows, refresh):
    p = describe.parse(line, ALIASES)
    assert p.ok, p.messages
    assert (p.kind, p.id, p.refresh) == (kind, id_, refresh)
    assert [(s.key, place) for s, place in p.shows] == shows
    assert not any(t.unsure for t in p.tokens)


def test_the_understood_tokens_read_as_plain_words():
    p = describe.parse("TRD BOOK-LDN-FX · click a trade → cash flows + CPTY limits · refresh 30s", ALIASES)
    assert [t.text for t in p.tokens] == ["Trades of book BOOK-LDN-FX", "click a row → Cash flows (under the clicked row)",
                                          "click a row → Counterparty limits", "refresh every 30 s"]
    assert [t.role for t in p.tokens] == ["subject", "show", "show", "refresh"]


@pytest.mark.parametrize("line,unsure_text,choices", [
    ("BOOK-RATES-2 · click a trade → cash flows", "Trades of book BOOK-RATES-2", ["TRD BOOK-RATES-2", "CPTY BOOK-RATES-2"]),
    ("TRD BOOK-RATES-2 · click a trade → limits", "click a row → Counterparty limits", None),
    ("TRD BOOK-RATES-2 · click a trade → cash", "click a row → Cash flows (under the clicked row)", None),
    ("TRD XYZ-1", "Trade XYZ-1", ["PTRD XYZ-1", "CTRD XYZ-1"]),
])
def test_where_the_parser_is_unsure_it_offers_the_fixes(line, unsure_text, choices):
    p = describe.parse(line, ALIASES)
    unsure = [t for t in p.tokens if t.unsure]
    assert [t.text for t in unsure] == [unsure_text]
    assert unsure[0].choices and unsure[0].message
    if choices:
        assert unsure[0].choices == choices


@pytest.mark.parametrize("line,says", [
    ("", "Describe the screen"),
    ("XYZ 1", "I do not know 'XYZ'"),
    ("TRD", "Say which trade"),
    ("TRD BOOK-RATES-2 · pizza", "I did not understand 'pizza'"),
    ("TRD BOOK-RATES-2 · click a trade → pizza", "I do not know how to show 'pizza'"),
    ("CPTY CP-1 · click a trade → cash flows", "has no list of trades to click"),
    ("TRD BOOK-RATES-2 · click a curve → cash flows", "say 'click a trade'"),
    ("TRD BOOK-RATES-2 · refresh 0s", "Refresh between 1 second and a day"),
])
def test_what_is_not_understood_is_said_plainly(line, says):
    p = describe.parse(line, ALIASES)
    assert not p.ok
    assert any(says in m for m in p.messages), p.messages


def test_a_fast_refresh_is_kept_with_a_warning():
    p = describe.parse("TRD BOOK-RATES-2 · refresh 2s", ALIASES)
    assert p.ok and p.refresh == 2 and "heavy" in p.messages[0]


def test_the_sutra_written_is_a_book_desk():
    p = describe.parse("TRD BOOK-RATES-2 · click a trade → cash flows + CPTY + CPTY limits + counterparty · refresh 30s", ALIASES)
    doc = yaml.safe_load(describe.dump(describe.sutra(p, "the line")))
    assert doc["match"] == {"kind": "portfolio-trades"} and doc["refresh"] == 30 and doc["sutra"] == "my-portfolio-trades-book-rates-2"
    picker = doc["panels"][0]
    assert picker["use"] == "row-picker@1" and picker["with"]["event"] == "open"
    assert picker["children"][0]["place"] == "expand-row" and picker["children"][0]["source"]["id"] == "@parent.open.id"
    assert [x.get("id") or x.get("use") for x in doc["panels"][1:]] == ["counterparty", "limit", "data-provenance@1"]   # each once
    assert doc["panels"][2]["source"]["where"] == "@.COUNTERPARTY == @trades.open.ref"


def test_a_single_record_screen_lists_its_fields_and_links():
    doc = describe.sutra(describe.parse("CPTY CP-NORTHBRIDGE", ALIASES), "CPTY CP-NORTHBRIDGE")
    assert [x["use"] for x in doc["panels"]] == ["field-list@1", "linked-entities@1", "data-provenance@1"] and "refresh" not in doc


def test_the_alias_table_comes_from_the_packs_overviews():
    got = describe.aliases_of([{"kinds": [{"kind": "trade", "mnemonic": "TRD", "label": "Trade"}, {"kind": "x"}]},
                               {"mnemonics": {"cpty": {"kind": "counterparty", "label": "Counterparty"}}}])
    assert got == {"TRD": {"kind": "trade", "label": "Trade"}, "CPTY": {"kind": "counterparty", "label": "Counterparty"}}


# ---- Customize: the steps ----------------------------------------------------------------------------------------------------------

SUTRA = """rachana: 1
sutra: irs-fixfloat
version: 3
match: { kind: trade, where: "$.X == 1", priority: 10 }
title: { id: $.TRADE_ID }
panels:
  - { use: trading/deal-ticket@1, with: { rate-fmt: pct4 } }
  - id: flows
    kind: table
    title: Flows
    rows: flatten($.LEGS[*].CASH_FLOWS)
    columns:
      - { label: Flow, bind: "@.FLOW_ID" }
      - { label: Pay, bind: "@.PAYMENT_DATE", fmt: date }
      - { label: Resets, bind: "size(@.RESETS)" }
  - { use: data-provenance@1 }
"""
RECORD = {"TRADE_ID": "MX-1", "NOTIONAL": 5, "LEGS": [{"CASH_FLOWS": [{"FLOW_ID": "F1", "PAYMENT_DATE": "2026-01-01", "AMOUNT": 3.5, "RESETS": []}]}]}


def test_a_users_page_has_its_own_name_and_matches_only_by_kind():
    doc = designer.rename(designer.load(SUTRA), "trade")
    assert doc["sutra"] == "my-irs-fixfloat" and doc["version"] == 1 and doc["match"] == {"kind": "trade"}
    assert designer.dump(doc).startswith("# My page")


def test_move_hide_show_keep_the_hidden_panel_as_it_was():
    doc, keep = designer.load(SUTRA), {}
    designer.move(doc, 2, 0)
    assert doc["panels"][0] == {"use": "data-provenance@1"}
    designer.hide(doc, keep, 0, "How this view was built")
    assert len(doc["panels"]) == 2 and keep["hidden"][0]["title"] == "How this view was built"
    notes = designer.notes_with("my note", keep)
    assert designer.notes_of(notes) == keep and notes.startswith("my note")
    designer.show(doc, keep, 0)
    assert doc["panels"][0] == {"use": "data-provenance@1"} and keep["hidden"] == []
    with pytest.raises(designer.DesignerError):
        designer.show(doc, keep, 0)
    with pytest.raises(designer.DesignerError):
        designer.move(doc, 9, 0)


def test_a_page_keeps_one_panel():
    doc = designer.load("rachana: 1\nsutra: x\nversion: 1\nmatch: {kind: trade}\npanels:\n  - {id: a, kind: links}\n")
    with pytest.raises(designer.DesignerError, match="at least one"):
        designer.hide(doc, {}, 0)


def test_columns_keep_labels_and_formats_and_take_new_fields_from_the_record():
    doc = designer.load(SUTRA)
    assert designer.rows_fields(RECORD, "flatten($.LEGS[*].CASH_FLOWS)") == ["FLOW_ID", "PAYMENT_DATE", "AMOUNT"]
    designer.columns(doc, "flows", ["PAYMENT_DATE", "AMOUNT"])
    cols = designer.find_panel(doc, "flows")["columns"]
    assert cols == [{"label": "Pay", "bind": "@.PAYMENT_DATE", "fmt": "date"}, {"label": "Amount", "bind": "@.AMOUNT"},
                    {"label": "Resets", "bind": "size(@.RESETS)"}]
    with pytest.raises(designer.DesignerError):
        designer.columns(doc, "flows", [])
    with pytest.raises(designer.DesignerError):
        designer.columns(doc, "nope", ["X"])


def test_the_click_sentence_writes_emits_and_children():
    doc = designer.load(SUTRA)
    designer.click(doc, "flows", "detail-right", ["FLOW_ID", "PAYMENT_DATE"], "Flows")
    p = designer.find_panel(doc, "flows")
    assert p["emits"][0]["payload"] == {"FLOW_ID": {"bind": "@.FLOW_ID", "type": "id"}, "PAYMENT_DATE": {"bind": "@.PAYMENT_DATE", "type": "any"}}
    kid = p["children"][0]
    assert kid["place"] == "detail-right" and kid["panels"][0]["columns"][1] == {"label": "Payment date", "bind": "@parent.open.PAYMENT_DATE"}
    designer.click(doc, "flows", "tabs", ["FLOW_ID"], "Flows")
    assert len(p["children"]) == 1 and p["children"][0]["place"] == "tabs"                 # a new sentence replaces the old
    designer.unclick(doc, "flows")
    assert "emits" not in p and "children" not in p
    with pytest.raises(designer.DesignerError):
        designer.click(doc, "flows", "somewhere", ["FLOW_ID"], "Flows")


def test_unpack_writes_a_used_fragment_out():
    doc = designer.load(SUTRA)
    designer.unpack(doc, 0, [{"id": "ticket", "panel": {"id": "ticket", "kind": "kv", "title": "Deal ticket"}}, {"id": "x"}])
    assert doc["panels"][0] == {"id": "ticket", "kind": "kv", "title": "Deal ticket"} and len(doc["panels"]) == 3
    with pytest.raises(designer.DesignerError):
        designer.unpack(doc, 0, [])


KIT = [{"ref": "key-figure@1", "name": "key-figure", "params": {"id": "figure", "title": "Figure", "value": "VALUE", "label": "Figure"},
        "doc": {"summary": "One headline number", "params": {"value": {"type": "expression"}, "label": {"type": "text"}}}},
       {"ref": "cashflow-ladder@1", "name": "cashflow-ladder", "params": {"id": "flows", "rows": "$.CASHFLOWS", "date": "PAYMENT_DATE", "amount": "AMOUNT"},
        "doc": {"summary": "Cash flows down a ladder by date", "tags": ["payments"],
                "params": {"rows": {"type": "expression"}, "date": {"type": "field"}, "amount": {"type": "field"}}}},
       {"ref": "field-list@1", "name": "field-list", "params": {"id": "fields", "rows": "$"}, "doc": {"summary": "Every field of one object"}},
       {"ref": "row-picker@1", "name": "row-picker", "params": {"rows": "$.ROWS"}, "doc": {"summary": "A table of records"}}]


def test_the_kit_is_searched_by_docstring_and_parameters_guessed_from_the_record():
    rec = {"ID": "B1", "TRADES": [{"TRADE_ID": "T1", "PAYMENT_DATE": "2026-01-01", "AMOUNT": 4.0}]}
    got = designer.search(KIT, "payments", rec)
    assert [g["ref"] for g in got] == ["cashflow-ladder@1"]
    assert got[0]["with"] == {"id": "flows", "rows": "$.TRADES"}
    fitting = designer.search(KIT, "", rec)
    assert "row-picker@1" not in [g["ref"] for g in fitting] and fitting[0]["fit"] >= fitting[-1]["fit"]
    doc = designer.load(SUTRA)
    designer.add(doc, "cashflow-ladder@1", {"id": "flows", "rows": "$.TRADES"})
    assert doc["panels"][-1] == {"use": "cashflow-ladder@1", "with": {"id": "flows-2", "rows": "$.TRADES"}}      # unique on the page
    with pytest.raises(designer.DesignerError):
        designer.add(doc, "../../etc", {})


# ---- Customize: what a click shows (linked records) ---------------------------------------------------------------------------------

PACKS = [{"name": "trading", "active": True, "kinds": ["trade", "portfolio-trades"], "links": {"TRADE_ID": {"kind": "trade", "label": "Trade"}}},
         {"name": "core", "active": True, "kinds": ["book"], "links": {"COUNTERPARTY": {"kind": "counterparty", "label": "Counterparty"}}},
         {"name": "counterparty-risk", "active": True, "kinds": ["counterparty", "credit-limit"], "links": {}},
         {"name": "off-pack", "active": False, "kinds": ["x"], "links": {"X_ID": {"kind": "x", "label": "X"}}}]
OVERVIEWS = {"trading": {"kinds": [{"kind": "trade", "mnemonic": "TRD", "label": "Trade"}, {"kind": "portfolio-trades", "mnemonic": "PTRD", "label": "Book"}]},
             "core": {"kinds": []},
             "counterparty-risk": {"kinds": [{"kind": "counterparty", "label": "Counterparty", "columns": ["NAME"]},
                                             {"kind": "credit-limit", "label": "Credit limit", "columns": ["COUNTERPARTY", "LIMIT", "USED"]}]}}
TRADE = {"TRADE_ID": "MX-1", "EVENTS": [{"EVENT_DATE": "2026-01-01", "AMOUNT": 4.0, "LEG": {"x": 1}}]}


def test_a_rows_links_are_offered_in_plain_words_across_packs():
    cat = designer_links.catalog(PACKS, OVERVIEWS)
    assert "X_ID" not in cat["links"] and cat["pack_of"]["credit-limit"] == "counterparty-risk"
    row = {"TRADE_ID": "MX-1", "COUNTERPARTY": "CP-1", "NOTIONAL": 5}
    got = designer_links.choices(row, cat, {"trade": TRADE, "counterparty": {"NAME": "N"}})
    assert [c["label"] for c in got] == ["the row’s details", "the trade itself (trading)", "its events", "its counterparty (counterparty-risk)",
                                         "its counterparty’s credit limits"]
    assert got[2]["spec"] == {"how": "list", "pack": "trading", "kind": "trade", "field": "TRADE_ID", "list": "EVENTS", "columns": ["EVENT_DATE", "AMOUNT"]}
    assert got[4]["spec"]["match"] == "COUNTERPARTY" and got[4]["spec"]["pack"] == "counterparty-risk"
    assert designer_links.first_row({"A": [{"B": [{"C": 1}]}]}, "flatten($.A[*].B)") == {"C": 1}


def test_a_linked_record_child_reads_the_clicked_rows_field():
    doc = designer.load(SUTRA)
    limits = {"how": "where", "pack": "counterparty-risk", "kind": "credit-limit", "field": "COUNTERPARTY", "match": "COUNTERPARTY", "columns": ["LIMIT"]}
    designer.click(doc, "flows", "detail-right", ["FLOW_ID", "PAYMENT_DATE"], "Flows", limits, {})
    p = designer.find_panel(doc, "flows")
    assert list(p["emits"][0]["payload"]) == ["FLOW_ID", "COUNTERPARTY", "PAYMENT_DATE"]                # the field the link reads is sent
    kid = p["children"][0]
    assert kid["source"] == {"pack": "counterparty-risk", "kind": "credit-limit", "where": "@.COUNTERPARTY == @parent.open.COUNTERPARTY", "limit": 20}
    assert kid["panels"][0]["rows"] == "$src" and kid["panels"][0]["columns"] == [{"label": "Limit", "bind": "@.LIMIT"}]
    one = {"how": "one", "pack": "trading", "kind": "trade", "field": "FLOW_ID"}
    designer.click(doc, "flows", "tabs", ["FLOW_ID"], "Flows", one, {})
    assert p["children"][0]["source"]["id"] == "@parent.open.FLOW_ID" and p["children"][0]["panels"][0]["use"] == "field-list@1"
    for bad in ({"how": "one", "pack": "x", "kind": "../etc", "field": "F"}, {"how": "where", "pack": "p", "kind": "k", "field": "F", "match": "a b"},
                {"how": "pizza"}):
        with pytest.raises(designer.DesignerError):
            designer.click(doc, "flows", "tabs", ["FLOW_ID"], "Flows", bad, {})


def test_an_existing_panel_moves_under_the_table_and_back():
    doc, keep = designer.load(SUTRA), {}
    designer.click(doc, "flows", "detail-below", ["FLOW_ID"], "Flows", {"how": "panel", "index": 0}, keep)
    assert len(doc["panels"]) == 2 and designer.find_panel(doc, "flows")["children"][0]["panels"] == [{"use": "trading/deal-ticket@1", "with": {"rate-fmt": "pct4"}}]
    assert keep["moved"]["flows"][0]["at"] == 0
    designer.click(doc, "flows", "detail-below", ["FLOW_ID"], "Flows", {"how": "row"}, keep)          # another sentence: the panel goes back
    assert doc["panels"][0] == {"use": "trading/deal-ticket@1", "with": {"rate-fmt": "pct4"}} and not keep["moved"]
    designer.click(doc, "flows", "detail-below", ["FLOW_ID"], "Flows", {"how": "panel", "index": 2}, keep)
    designer.unclick(doc, "flows", keep)
    assert doc["panels"][2] == {"use": "data-provenance@1"} and "children" not in designer.find_panel(doc, "flows")
    with pytest.raises(designer.DesignerError, match="cannot show itself"):
        designer.click(doc, "flows", "tabs", ["FLOW_ID"], "Flows", {"how": "panel", "index": 1}, keep)


# ---- the routes ---------------------------------------------------------------------------------------------------------------------

@pytest.fixture()
def on(client, backend, monkeypatch):
    monkeypatch.setitem(client.app.state.settings._data.setdefault("ui", {}), "designer", True)
    seen = {"views": []}
    real_view, real_designs = backend.view, backend.designs

    async def view(kind, id_, user, controls=None, panels=None, sutra=None, design=None):
        seen["views"].append(design)
        if kind == "portfolio-trades":                                  # Describe's book: drawn by the server, here only answered
            return {"ref": {"kind": kind, "id": id_}, "panels": [], "provenance": {}}
        vm = await real_view(kind, id_, user, controls, panels)
        vm.setdefault("provenance", {})["layout"] = "Sutra irs-fixfloat v3"
        return vm

    async def designs(method, path, ident, body=None, **params):
        if path.endswith("/blocks"):
            return {"uses": []}
        return await real_designs(method, path, ident, body, **params)

    async def sutra_source(name, version, ident=None):
        return SUTRA

    async def raw(kind, id_, ident=None):
        return RECORD

    async def ui_kit(ident=None):
        return {"fragments": KIT}

    async def packs(ident=None):
        return PACKS

    async def overview(name, ident=None):
        return OVERVIEWS[name]

    pages: dict = {}
    seen["pages"] = pages
    real_send = backend._send

    async def send(method, path, ident=None, **kw):              # the server's /me/pages: one user's flags
        if not path.startswith("/me/pages"):
            return await real_send(method, path, ident, **kw)
        kind = path[len("/me/pages/"):]
        if method == "PUT":
            pages[kind] = kw["json"]["design"]
        elif method == "DELETE":
            pages.pop(kind, None)
        return dict(pages)

    for k, v in {"view": view, "designs": designs, "sutra_source": sutra_source, "raw": raw, "ui_kit": ui_kit, "packs": packs,
                 "pack_overview": overview, "_send": send}.items():
        monkeypatch.setattr(backend, k, v, raising=False)
    from routes import designer_routes
    monkeypatch.setitem(designer_routes._ALIASES, "at", 0.0)
    designer_routes._MIGRATED.clear()
    yield seen


def test_off_by_default_nothing_changes(client):
    assert client.post("/designer/customize", json={"kind": "trade", "id": "IRS-47102"}).status_code == 404
    assert client.get("/designer/new", follow_redirects=False).headers["location"] == "/build/new"
    page = client.get("/v/trade/IRS-47102").text
    assert "data-customize-open" not in page and "designer.js" not in page
    assert "data-describe-offer" not in client.get("/build/new").text


def test_customize_done_reopen_and_reset(client, on):
    page = client.get("/v/trade/IRS-47102").text
    assert "data-customize-open" in page and on["views"][-1] is None                         # the pack's page until a user keeps their own
    r = client.post("/designer/customize", json={"kind": "trade", "id": "IRS-47102"}).json()
    draft = r["draft"]
    assert r["url"] == f"/v/trade/IRS-47102?customize={draft}"
    page = client.get(r["url"]).text
    assert f'data-design="{draft}"' in page and "data-customizing" in page and on["views"][-1] == draft

    st = client.get(f"/designer/state/{draft}?kind=trade&id=IRS-47102").json()
    assert [p["title"] for p in st["parts"]] == ["Deal ticket", "Flows", "Data provenance"]
    assert st["tables"][0]["fields"] == ["FLOW_ID", "PAYMENT_DATE", "AMOUNT"] and st["mine"] is None
    assert "my-irs-fixfloat" in st["yaml"]

    ok = client.post(f"/designer/op/{draft}", json={"op": "hide", "index": 2, "title": "How this view was built", "kind": "trade", "id": "IRS-47102"})
    assert ok.json() == {"ok": True}
    st = client.get(f"/designer/state/{draft}?kind=trade&id=IRS-47102").json()
    assert st["hidden"] == [{"index": 0, "title": "How this view was built"}] and len(st["parts"]) == 2
    bad = client.post(f"/designer/op/{draft}", json={"op": "columns", "panel": "flows", "fields": [], "kind": "trade", "id": "IRS-47102"})
    assert bad.status_code == 400 and "at least one column" in bad.json()["detail"]
    assert client.post(f"/designer/op/{draft}", json={"op": "nope", "kind": "trade", "id": "IRS-47102"}).status_code == 400

    assert client.post(f"/designer/done/{draft}", json={"kind": "trade"}).json() == {"design": draft}
    page = client.get("/v/trade/IRS-47102").text
    assert on["views"][-1] == draft and "dz-own" in page                                   # the page opens with it, for this user
    client.get("/v/trade/IRS-47102?pack=1")
    assert on["views"][-1] is None                                                          # the pack's page is a click away

    again = client.post("/designer/customize", json={"kind": "trade", "id": "IRS-47102"}).json()["draft"]
    st = client.get(f"/designer/state/{again}?kind=trade&id=IRS-47102").json()
    assert st["mine"]["id"] == draft and st["hidden"][0]["title"] == "How this view was built"   # editing goes on from the kept page
    assert client.post(f"/designer/done/{again}", json={"kind": "trade"}).json() == {"design": draft}   # one page a kind, replaced

    assert client.post("/designer/reset", json={"kind": "trade"}).json() == {"ok": True, "removed": True}
    client.get("/v/trade/IRS-47102")
    assert on["views"][-1] is None


def test_refresh_this_panel_every_is_written_on_the_part(client, on):
    draft = client.post("/designer/customize", json={"kind": "trade", "id": "IRS-47102"}).json()["draft"]
    r = client.post(f"/designer/op/{draft}", json={"op": "refresh", "index": 1, "seconds": 30, "kind": "trade", "id": "IRS-47102"})
    assert r.json() == {"ok": True}
    st = client.get(f"/designer/state/{draft}?kind=trade&id=IRS-47102").json()
    assert [p["refresh"] for p in st["parts"]] == [0, 30, 0] and "refresh: 30" in st["yaml"]
    client.post(f"/designer/op/{draft}", json={"op": "refresh", "index": 1, "seconds": 0, "kind": "trade", "id": "IRS-47102"})
    assert "refresh:" not in client.get(f"/designer/state/{draft}?kind=trade&id=IRS-47102").json()["yaml"]
    bad = client.post(f"/designer/op/{draft}", json={"op": "refresh", "index": 1, "seconds": "soon", "kind": "trade", "id": "IRS-47102"})
    assert bad.status_code == 400 and "how often" in bad.json()["detail"]


def test_a_step_the_server_refuses_is_undone(client, on, backend, monkeypatch):
    draft = client.post("/designer/customize", json={"kind": "trade", "id": "IRS-47102"}).json()["draft"]
    before = client.get(f"/designer/state/{draft}?kind=trade&id=IRS-47102").json()["yaml"]

    async def refuse(*a, **k):
        raise BackendError(400, "DRS-2057", "unknown panel")
    monkeypatch.setattr(backend, "view", refuse)
    r = client.post(f"/designer/op/{draft}", json={"op": "move", "index": 0, "to": 1, "kind": "trade", "id": "IRS-47102"})
    assert r.status_code == 400 and "would break the page" in r.json()["detail"]
    monkeypatch.undo()


def test_describe_builds_and_saves(client, on):
    r = client.post("/designer/describe", json={"text": "TRD BOOK-RATES-2 · click a trade → cash flows · refresh 30s"}).json()
    assert r["ok"] and r["kind"] == "portfolio-trades" and "refresh: 30" in r["yaml"] and "refreshes every 30 s" in r["refreshNote"]
    assert r["preview"] == f"/v/portfolio-trades/BOOK-RATES-2?design={r['draft']}&embed=1" and on["views"][-1] == r["draft"]
    again = client.post("/designer/describe", json={"text": "TRD BOOK-RATES-2", "draft": r["draft"]}).json()
    assert again["draft"] == r["draft"]                                                     # the same draft, rewritten
    bad = client.post("/designer/describe", json={"text": "XYZ 1"}).json()
    assert not bad["ok"] and "draft" not in bad and "I do not know 'XYZ'" in bad["messages"][0]
    saved = client.post(f"/designer/describe/save/{r['draft']}", json={"name": "My screen · Trades of book BOOK-RATES-2"}).json()
    assert saved["build"] == f"/build/d/{r['draft']}"
    page = client.get("/designer/new?q=TRD%20BOOK-RATES-2").text
    assert 'value="TRD BOOK-RATES-2"' in page and 'aria-disabled="true"' not in page        # every mode is built
    assert "data-describe-offer" in client.get("/build/new").text


def test_describe_writes_the_notional_in_the_currency_the_book_s_trades_carry(client, on, backend, monkeypatch):
    async def raw(kind, id_, ident=None):
        return {"PORTFOLIO": "BOOK-RATES-2", "TRADES": [{"TRADE_ID": "T-1", "CURRENCY": "EUR"}]} if kind == "portfolio-trades" else {}
    monkeypatch.setattr(backend, "raw", raw)
    r = client.post("/designer/describe", json={"text": "TRD BOOK-RATES-2 · edit trades beside"}).json()
    assert r["ok"] and "type: money" in r["yaml"] and "currency: currency" in r["yaml"] and "bind: CURRENCY" in r["yaml"]

    async def raw_gbp(kind, id_, ident=None):
        return {"PORTFOLIO": "BOOK-RATES-2", "BASE_CURRENCY": "GBP", "TRADES": [{"TRADE_ID": "T-1"}]}
    monkeypatch.setattr(backend, "raw", raw_gbp)
    r = client.post("/designer/describe", json={"text": "TRD BOOK-RATES-2 · edit trades beside"}).json()
    assert "currency: GBP" in r["yaml"] and "bind: CURRENCY" not in r["yaml"]


def test_the_page_is_a_flag_renaming_keeps_it_and_old_pages_are_migrated(client, on, backend):
    draft = client.post("/designer/customize", json={"kind": "trade", "id": "IRS-47102"}).json()["draft"]
    st = client.get(f"/designer/state/{draft}?kind=trade&id=IRS-47102").json()
    shows = st["tables"][0]["shows"]
    assert shows[0]["spec"] == {"how": "row"} and any(c["spec"] == {"how": "panel", "index": 0} for c in shows)
    ok = client.post(f"/designer/op/{draft}", json={"op": "click", "panel": "flows", "place": "tabs", "show": {"how": "panel", "index": 2},
                                                   "kind": "trade", "id": "IRS-47102"})
    assert ok.json() == {"ok": True}
    assert client.post(f"/designer/done/{draft}", json={"kind": "trade"}).json() == {"design": draft}
    assert on["pages"] == {"trade": draft}                                                  # a flag, not a name
    for row in backend._kept().values():                                                    # renamed in Build
        if row["design"]["id"] == draft:
            row["design"]["name"] = "Rates desk view"
    client.get("/v/trade/IRS-47102")
    assert on["views"][-1] == draft                                                        # renamed: still my page
    assert client.post("/designer/reset", json={"kind": "trade"}).json()["removed"] and on["pages"] == {}


def test_a_page_kept_by_name_in_2_7_becomes_a_flag_once(client, on, backend):
    from routes import designer_routes
    draft = client.post("/designer/customize", json={"kind": "trade", "id": "IRS-47102"}).json()["draft"]
    client.post(f"/designer/describe/save/{draft}", json={"name": "My page · trade"})          # how 2.7 kept a page
    designer_routes._MIGRATED.clear()
    client.get("/v/trade/IRS-47102")
    assert on["pages"] == {"trade": draft} and on["views"][-1] == draft
