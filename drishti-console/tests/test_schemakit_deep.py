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

"""Build -> New pack on deep documents known only from their data (docs/guides/DATA_GUIDE.md, "A deep trading JSON"): the synthetic
commodity book (tools/packgen/comswap_synth.py: deal -> LEGS -> CASH_FLOWS -> RESETS, EVENTS sometimes absent, five product types in one file)
and, to show the rules are generic, a structured note of another shape (tools/packgen/note_synth.py: note -> couponPeriods -> observations ->
fixings). Keys of any case, blanks as missing, readable labels, the snapshot date, one Sutra per type, anonymised samples, pages per value, the
deep design with its drill levels, and the BI tables of every level."""
import json
import pathlib
import random
import sys

import pytest
import yaml

from core.schemakit import anonymise as A, bi as BI, build as B, decorate as D, plan as P

TOOLS = pathlib.Path(__file__).resolve().parents[2] / "tools"
sys.path.insert(0, str(TOOLS / "packgen"))
sys.path.insert(0, str(TOOLS))
import comswap_synth  # noqa: E402
import note_synth  # noqa: E402

DRAFT = 'rachana: 1\nsutra: x\nversion: 1\ndescription: x\nmatch: { kind: x }\ntitle: { pill: x, id: "$.x" }\npanels: []\n'


def deals(n=40, mix=False, seed=7):
    return list(comswap_synth.Generator(seed).deals(n, mix))


def finalized(pl, kind, name=None):
    item = next(i for i in B.prepare(pl) if i.kp.kind == kind and (name is None or i.name == name))
    text, _ = D.finalize(DRAFT, item.kp, item.sp, pl)
    return text, yaml.safe_load(text)


def test_upper_snake_ids_blanks_labels_and_the_snapshot_date():
    pl = P.make_plan([], A.anonymise({"comswap": deals()}))
    k = pl.kind("comswap")
    assert k.key == "TRADE_ID" and "trade" in k.key_why                 # an integer id, UPPER_SNAKE, chosen over TRANSACTION_ID
    f = k.index
    assert f["TRADE_ID"].role == "id" and f["TRADE_ID"].fmt == "" and f["TRADE_ID"].label == "Trade ID"
    assert f["BUY_SELL_CODE"].label == "Buy sell code" and f["COUNTERPARTY_UEN"].label == "Counterparty UEN"
    assert f["LEGS"].label == "Legs" and f["LEGS[].CASH_FLOWS"].label == "Cash flows"
    assert f["SOURCE_UPDATE_TIME"].hidden and f["TT_BOOK"].hidden and f["SOURCE_UPDATE_TIME"].fill == 0      # "" everywhere: no value
    assert not any(x.enum == [""] for x in k.fields)
    assert f["START_DATE"].role == "date" and f["TRADE_STATUS"].role == "status"
    assert f["TRANSIT"].role != "number"                                 # digits written as text: a code, kept as text
    assert f["LEGS[].CASH_FLOWS[].RATE"].role == "number"                # a rate written as text: a measure
    assert k.date is None and k.snapshot and "snapshot" in k.date_why   # TRADE_DATE differs per deal: not a business date
    assert k.template == "delta" and k.strip[:3] == ["TRADE_STATUS", "BUY_SELL_CODE", "QUANTITY"]
    man = B.pack_manifest(pl)
    assert man["ingest"]["comswap"] == {"key": "TRADE_ID", "snapshot": True}


def test_one_file_of_five_types_gives_exactly_five_sutras_named_after_them():
    docs = deals(60, mix=True, seed=11)
    types = {d["PRODUCT_TYPE"] for d in docs}
    assert types == {"ComSwap", "ComFut", "Cash", "Commodity", "CallNot"}
    pl = P.make_plan([], A.anonymise({"deal": docs}))
    k = pl.kind("deal")
    assert k.match == ["PRODUCT_TYPE"] and "type column" in k.match_why
    assert sorted(s.name for s in k.sutras) == ["callnot", "cash", "comfut", "commodity", "comswap"]   # one per type, no catch-all
    assert sum(k.match_counts["PRODUCT_TYPE"].values()) == 60 and k.match_counts["PRODUCT_TYPE"]["CallNot"] >= 1
    # counts over EVERY row (as the page sends them) find a type the kept sample lacks, and a blank type brings the catch-all back
    counts = {"deal": {"PRODUCT_TYPE": {"ComSwap": 2000, "ComFut": 900, "Cash": 700, "Commodity": 650, "CallNot": 12, "Rare": 1, "": 3}}}
    pl2 = P.make_plan([], A.anonymise({"deal": docs}), counts=counts)
    names = [s.name for s in pl2.kind("deal").sutras]
    assert "rare" in names and names[-1] == "deal-default"
    assert any("3 document(s) have no PRODUCT_TYPE" in w for w in pl2.warnings)


def test_each_type_is_designed_from_its_own_rows():
    pl = P.make_plan([], A.anonymise({"deal": deals(60, mix=True, seed=11)}))
    _, swap = finalized(pl, "deal", "comswap")
    _, cash = finalized(pl, "deal", "cash")
    assert any(p["id"] == "events" for p in swap["panels"])             # only swaps have events
    assert not any(p["id"] == "events" for p in cash["panels"])
    assert swap["match"]["where"] == "$.PRODUCT_TYPE == 'ComSwap'"


def test_the_deep_design_puts_legs_side_by_side_and_drills_from_cash_flows_into_resets():
    pl = P.make_plan([], A.anonymise({"comswap": deals()}))
    text, s = finalized(pl, "comswap")
    legs = next(p for p in s["panels"] if p["id"] == "legs")
    assert legs["kind"] == "tabs" and legs["layout"] == "columns" and legs["each"] == "$.LEGS"
    assert legs["tabTitle"].startswith("'Leg ' + @.LEG_ID")
    terms = legs["panels"][0]
    assert terms["kind"] == "kv" and any("link(@.INDEX.NAME, 'comswap-index')" in c["bind"] for c in terms["columns"])
    assert any(c["label"] == "Index name" for c in terms["columns"])     # INDEX.NAME reads with its object's name
    schedule = next(p for p in s["panels"] if p["id"] == "legs-cash-flows")
    assert schedule["kind"] == "tabs" and schedule["each"] == "$.LEGS" and "layout" not in schedule   # a whole row wide, a leg at a time
    flows = schedule["panels"][0]
    assert flows["kind"] == "table" and flows["rows"] == "@.CASH_FLOWS" and flows["levels"][0]["rows"] == "@.RESETS"
    assert flows["root"].startswith("'Comswap ' + $.TRADE_ID + ' › '")
    assert any(c.get("total") for c in flows["columns"])
    ev = next(p for p in s["panels"] if p["id"] == "events")
    assert ev["empty"] == "No events"
    pay = next(p for p in s["panels"] if p["id"] == "payments")
    assert pay["kind"] == "timeline" and pay["rows"] == "flatten($.LEGS[*].CASH_FLOWS)"
    assert s["title"]["id"] == "$.TRADE_ID" and "comswap-counterparty" in s["title"]["with"]
    assert "SOURCE_UPDATE_TIME" not in text and "TT_BOOK" not in text    # blank everywhere: never shown
    assert {k.kind for k in pl.kinds} >= {"comswap-counterparty", "comswap-portfolio", "comswap-index"}


def test_a_four_level_document_of_another_shape_drills_through_every_level():
    rng = random.Random(5)
    notes = [note_synth.note(i, rng) for i in range(30)]
    pl = P.make_plan([], A.anonymise({"note": notes}))
    k = pl.kind("note")
    assert k.key == "noteId" and k.index["isin"].label == "ISIN"
    _, s = finalized(pl, "note")
    cp = next(p for p in s["panels"] if p["id"] == "couponperiods")
    assert cp["kind"] == "table" and cp["rows"] == "$.couponPeriods"     # 12 periods: one table that drills, not 12 tabs
    assert [lv["rows"] for lv in cp["levels"]] == ["@.observations", "@.fixings"]
    assert any(c["bind"] == "size(@.observations)" for c in cp["columns"])                   # counts at every level
    assert any(c["bind"] == "size(@.fixings)" for c in cp["levels"][0]["columns"])
    barrier = k.index["couponBarrier"]
    assert barrier.fmt == "price4"                                       # 0.7 is not rounded to 1
    tables = BI.dataset(k, pl, "notes-store")["tables"]
    fix = next(t for n, t in tables.items() if t["source"].get("each") == "couponPeriods[].observations[].fixings")
    assert fix["columns"]["noteId"]["path"] == "$.noteId"


def test_anonymised_samples_keep_no_real_value(tmp_path):
    docs = deals(30)
    real = {d["COUNTERPARTY"] for d in docs} | {d["PORTFOLIO"] for d in docs} | {d["TRADER_ID"] for d in docs} | {d["TRANSACTION_ID"] for d in docs}
    pl = P.make_plan([], A.anonymise({"comswap": docs}), settings={"anonymised": True}, pack_name="cs")
    pl.pack.update(name="cs")
    items = B.prepare(pl)
    B.assemble(pl, items, {i.name: DRAFT for i in items}, tmp_path / "cs")
    blob = "\n".join(p.read_text(encoding="utf-8") for p in (tmp_path / "cs").rglob("*") if p.is_file())
    leaked = sorted(v for v in real if v in blob)
    assert not leaked, leaked
    assert "Anonymised" in (tmp_path / "cs" / "README.md").read_text()
    again = A.anonymise({"comswap": docs})                               # deterministic: the same pack twice
    assert again == A.anonymise({"comswap": docs})
    keys = [d["TRADE_ID"] for d in again["comswap"]]
    assert len(set(keys)) == len(keys)                                   # ids stay unique


def test_samples_cover_documents_with_and_without_the_optional_arrays():
    docs = deals(80)
    picked = B.covering(docs, 5, "TRADE_ID")
    states = {("EVENTS" in d) and bool(d["EVENTS"]) for d in picked} | {"EVENTS" in d for d in picked}
    assert True in states and False in states


def test_the_bi_dataset_has_a_table_per_level_with_the_keys_above():
    pl = P.make_plan([], A.anonymise({"comswap": deals()}), pack_name="cs")
    k = pl.kind("comswap")
    d = BI.dataset(k, pl, "cs-store")
    t = d["tables"]
    assert t["legs"]["source"]["each"] == "LEGS"
    resets = t["resets"]
    assert resets["source"]["each"] == "LEGS[].CASH_FLOWS[].RESETS"
    assert resets["columns"]["TRADE_ID"]["path"] == "$.TRADE_ID" and resets["columns"]["LEG_ID"]["path"] == "^^.LEG_ID"
    assert resets["columns"]["FLOW_ID"]["path"] == "^.FLOW_ID"
    assert {"from": "cash_flows.id", "to": "comswap.id", "cardinality": "many-to-one"} in d["relationships"]
    assert d["measures"]["Leg notional"]["expr"] == "sum(legs.NOTIONAL)"


def test_the_command_line_reads_a_json_array_element_by_element(tmp_path):
    import sutragen
    f = tmp_path / "book.json"
    docs = deals(12, mix=True)
    f.write_text(json.dumps(docs, indent=2))
    got = [d for _, d, _ in sutragen.read_records(sutragen.input_files([f]))]
    assert got == docs
    assert sutragen.input_files([tmp_path]) == [f]                     # a folder's .json arrays are data too


@pytest.mark.parametrize("name,expect", [("TRADE_ID", "Trade ID"), ("NY_CURVE_FLAG", "NY curve flag"), ("LEGS", "Legs"), ("tradeDate", "Trade date"),
                                         ("RATE", "Rate"), ("isin", "ISIN"), ("dv01", "DV01")])
def test_humanize(name, expect):
    assert P.humanize(name) == expect
