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

"""schemakit: JSON Schema in, a pack plan out. Kinds, keys, links, labels, match columns, strip, synthetic samples, the Sutra edits, the About text
and the pack folder, over a fixture set of schemas (nested, $ref/$defs, allOf, oneOf with a discriminator, enums, formats, arrays, maps).
No Java: the auto-designer is replaced by a canned draft, so these run anywhere."""
import copy
import json
import pathlib
import sys

import pytest
import yaml

CORE = pathlib.Path(__file__).resolve().parents[1] / "core"
sys.path.insert(0, str(CORE))
from schemakit import build, decorate, plan as P, synth  # noqa: E402
from schemakit.resolve import Resolver, SchemaDoc, SchemaError, parse_text  # noqa: E402

FIX = pathlib.Path(__file__).parent / "fixtures" / "schemas"

DRAFT = """rachana: 1
sutra: trade-auto
version: 1
description: Drafted by auto-design from 2 samples. Edit freely.
match: { kind: trade, priority: 1 }
title: { pill: "Trade", id: "$.tradeId" }
strip:
  - { label: "Status", bind: "$.status", tone: "status" }
  - { label: "Mtm", bind: "$.mtm", fmt: "signed0" }
panels:
  - id: details
    kind: kv
    title: "Details"
    columns:
      - { label: "Book ID", bind: "$.bookId" }
      - { label: "Notional", bind: "$.notional", fmt: "amount0" }
      - { label: "Trade date", bind: "$.tradeDate", fmt: "date" }
      - { label: "Secret", bind: "$.hiddenThing" }
  - id: legs
    kind: table
    title: "Legs list"
    rows: "$.legs"
    columns:
      - { label: "PV", bind: "@.pv", fmt: "signed0" }
      - { label: "Rate", bind: "@.rate", fmt: "pct4" }
"""


def schemas(*names):
    return [(f.name, f.read_text()) for f in sorted(FIX.glob("*.json")) if not names or f.stem.split(".")[0] in names]


@pytest.fixture(scope="module")
def plan():
    return P.make_plan(schemas())


def test_kinds_come_from_titles(plan):
    assert [k.kind for k in plan.kinds] == ["book", "counterparty", "instrument", "trade"]
    assert plan.kind("trade").kind_why == "the schema title"


def test_kind_falls_back_to_id_then_file_name():
    s = {"type": "object", "properties": {"id": {"type": "string"}}}
    assert P.make_plan([("My Thing.schema.json", s)]).kinds[0].kind == "my-thing"
    s2 = dict(s, **{"$id": "https://x.org/schemas/ledger-entry.json"})
    k = P.make_plan([("a.json", s2)]).kinds[0]
    assert (k.kind, k.kind_why) == ("ledger-entry", "the schema $id")
    s3 = dict(s, title="A very long descriptive sentence title", **{"x-drishti-kind": "entry"})
    assert P.make_plan([("a.json", s3)]).kinds[0].kind == "entry"


def test_keys_by_rule(plan):
    assert plan.kind("trade").key == "tradeId"          # <kind>Id, required
    assert plan.kind("counterparty").key == "id"        # named id
    assert plan.kind("book").key == "bookId"
    assert not any(k.key_ambiguous for k in plan.kinds)


def test_key_annotation_wins_and_ambiguity_is_reported():
    s = {"title": "Ticket", "type": "object", "required": ["aId", "bId"],
         "properties": {"aId": {"type": "string"}, "bId": {"type": "string"}, "n": {"type": "integer"}}}
    k = P.make_plan([("t.json", s)]).kinds[0]
    assert k.key_ambiguous and k.key_candidates == ["aId", "bId"] and k.key == "aId"
    assert any("ambiguous key" in w for w in k.warnings)
    s2 = copy.deepcopy(s)
    s2["properties"]["bId"]["x-drishti-role"] = "key"
    k2 = P.make_plan([("t.json", s2)]).kinds[0]
    assert (k2.key, k2.key_why, k2.key_ambiguous) == ("bId", "x-drishti-role: key", False)
    k3 = P.make_plan([("t.json", s)], overrides={"ticket": {"key": "bId"}}).kinds[0]
    assert (k3.key, k3.key_why) == ("bId", "chosen by you")


def test_links_by_name_and_annotation(plan):
    t = plan.kind("trade")
    assert {(l.field, l.to) for l in t.links} == {("counterpartyId", "counterparty"), ("bookId", "book")}
    assert [(l.field, l.to) for l in plan.kind("counterparty").links] == [("parentId", "counterparty")]   # x-drishti-link
    assert "kind in this batch" in t.links[0].why
    assert {e["from"] for e in plan.edges()} == {"trade", "counterparty"}


def test_links_from_sample_values():
    a = {"title": "Order", "type": "object", "required": ["orderId"], "properties": {"orderId": {"type": "string"}, "owner": {"type": "string"}}}
    b = {"title": "Person", "type": "object", "required": ["personId"], "properties": {"personId": {"type": "string"}}}
    orders = [{"orderId": f"O{i}", "owner": f"P{i % 4}"} for i in range(8)]
    people = [{"personId": f"P{i}"} for i in range(4)]
    pl = P.make_plan([("a.json", a), ("b.json", b)], {"order": orders, "person": people})
    assert [(l.field, l.to) for l in pl.kind("order").links] == [("owner", "person")]
    assert "values are keys" in pl.kind("order").links[0].why


def test_link_removed_by_user(plan):
    pl = P.make_plan(schemas(), overrides={"trade": {"links": {"bookId": None}}})
    assert [l.field for l in pl.kind("trade").links] == ["counterpartyId"]


def test_discriminator_makes_one_sutra_per_variant(plan):
    i = plan.kind("instrument")
    assert i.match == ["type"] and "discriminator" in i.match_why
    assert [s.name for s in i.sutras] == ["instrument-bond", "instrument-equity", "instrument-default"]
    assert [s.where for s in i.sutras] == ["$.type == 'BOND'", "$.type == 'EQUITY'", ""]
    assert i.sutras[-1].fallback and i.sutras[-1].priority == 1


def test_variant_samples_carry_their_own_fields(plan):
    i = plan.kind("instrument")
    bond = synth.documents(i, plan, 2, i.sutras[0])
    eq = synth.documents(i, plan, 2, i.sutras[1])
    assert all(d["type"] == "BOND" and "coupon" in d and "ticker" not in d for d in bond)
    assert all(d["type"] == "EQUITY" and "ticker" in d and "coupon" not in d for d in eq)


def test_match_annotation_and_options(plan):
    t = plan.kind("trade")
    assert t.match == ["productType"] and t.match_why == "x-drishti-match"
    assert [s.name for s in t.sutras] == ["trade-irs", "trade-fxs", "trade-fut", "trade-default"]
    opts = {o["field"]: o for o in t.match_options}
    assert opts["status"]["sutras"] == 5 and opts["productType"]["values"] == ["IRS", "FXS", "FUT"]
    pl = P.make_plan(schemas(), overrides={"trade": {"match": []}})
    assert [s.name for s in pl.kind("trade").sutras] == ["trade-default"]
    pl2 = P.make_plan(schemas(), overrides={"trade": {"match": "status"}})
    assert len(pl2.kind("trade").sutras) == 5


def test_two_match_columns_cross(plan):
    pl = P.make_plan(schemas(), overrides={"trade": {"match": ["productType", "status"]}})
    names = [s.name for s in pl.kind("trade").sutras]
    assert len(names) == 3 * 4 + 1 and "trade-irs-live" in names
    assert pl.kind("trade").sutras[0].where == "$.productType == 'IRS' && $.status == 'PENDING'"


def test_dates(plan):
    assert plan.kind("trade").date == "tradeDate"
    assert plan.kind("book").date == "businessDate"
    assert plan.kind("counterparty").date is None and "no date field" in plan.kind("counterparty").date_why
    pl = P.make_plan(schemas(), overrides={"trade": {"date": "executedAt"}})
    assert pl.kind("trade").date == "executedAt"


def test_labels_descriptions_and_formats(plan):
    t = plan.kind("trade")
    idx = t.index
    assert idx["notional"].label == "Notional" and idx["notional"].unit == "USD" and idx["notional"].fmt == "amount2" and idx["notional"].firm
    assert idx["tradeDate"].fmt == "date" and idx["executedAt"].fmt == "date"
    assert idx["legs[].pv"].label == "Present value" and idx["legs[].rate"].fmt == "pct2"
    assert idx["mtm"].fmt == "signed0" and idx["mtm"].label == "MTM"
    assert idx["terms.spread"].description.startswith("Spread over the index")
    assert idx["counterpartyId"].label == "Counterparty ID"
    assert t.description.startswith("A bilateral derivative trade")


def test_roles_and_arrays_and_maps(plan):
    idx = plan.kind("trade").index
    assert idx["status"].role == "link" or idx["status"].role == "status"
    assert idx["status"].role == "status" and idx["productType"].role == "dimension"
    assert idx["legs"].role == "table" and idx["legs[].legType"].enum == ["FIXED", "FLOAT"]
    assert idx["curve"].role == "map"
    assert idx["terms"].role == "object" and idx["tradeDate"].role == "date"


def test_enums_become_status_and_dropdown_suggestions(plan):
    t = plan.kind("trade")
    c = {x["field"]: x for x in t.controls}
    assert c["status"] == {"field": "status", "control": "dropdown", "label": "Status", "options": ["PENDING", "LIVE", "MATURED", "CANCELLED"]}
    assert "legs[].legType" not in c    # fields inside arrays are not suggested as page controls


def test_strip_is_status_then_required_described_numbers(plan):
    t = plan.kind("trade")
    assert t.strip[0] == "status"
    assert t.strip[1] == "notional"                       # required and described
    assert "mtm" in t.strip and "terms.spread" in t.strip
    assert len(t.strip) <= 6
    assert plan.kind("book").strip == ["tradeCount", "dv01"]


def test_ref_allof_and_definitions_are_followed(plan):
    assert {"createdBy", "createdAt"} <= set(plan.kind("book").index)           # allOf + $ref
    assert "headOffice.city" in plan.kind("counterparty").index                  # draft-07 definitions
    assert plan.kind("trade").index["notional"].type == "number"                 # $ref to $defs with sibling title


def test_cross_file_ref_and_recursion():
    a = {"title": "Order", "type": "object", "properties": {"id": {"type": "string"}, "customer": {"$ref": "customer.schema.json"}}}
    b = {"title": "Customer", "$id": "customer.schema.json", "type": "object", "properties": {"name": {"type": "string"}, "boss": {"$ref": "#"}}}
    pl = P.make_plan([("order.schema.json", a), ("customer.schema.json", b)])
    assert "customer.name" in pl.kind("order").index
    bad = {"title": "X", "type": "object", "properties": {"id": {"type": "string"}, "y": {"$ref": "nowhere.json#/a"}}}
    pl2 = P.make_plan([("x.json", bad)])
    assert any("not in this batch" in w for w in pl2.warnings)


def test_bad_files_are_reported_not_fatal():
    pl = P.make_plan([("broken.json", "{not json"), ("list.json", "[1,2]"), ("ok.json", {"title": "Ok", "type": "object", "properties": {"id": {"type": "string"}}})])
    assert [k.kind for k in pl.kinds] == ["ok"]
    assert any("broken.json" in w for w in pl.warnings) and any("list.json" in w for w in pl.warnings)


def test_yaml_schema_is_read():
    text = "title: Pet\ntype: object\nrequired: [id]\nproperties:\n  id: {type: string}\n  name: {type: string, description: The pet's name}\n"
    k = P.make_plan([("pet.schema.yaml", text)]).kinds[0]
    assert k.kind == "pet" and k.key == "id" and k.index["name"].description == "The pet's name"
    with pytest.raises(SchemaError):
        parse_text("- 1\n- 2\n", "x.yaml")


def test_kind_name_collisions_get_a_suffix():
    s = {"title": "Same", "type": "object", "properties": {"id": {"type": "string"}}}
    pl = P.make_plan([("a.json", s), ("b.json", s)])
    assert [k.kind for k in pl.kinds] == ["same", "same-2"] and any("already taken" in w for w in pl.warnings)


def test_mnemonics_are_unique_and_avoid_taken_ones():
    pl = P.make_plan(schemas(), existing_mnemonics={"TRA"})
    m = [k.mnemonic for k in pl.kinds]
    assert len(set(m)) == 4 and "TRA" not in m
    pl2 = P.make_plan(schemas(), overrides={"trade": {"mnemonic": "TRD"}})
    assert pl2.kind("trade").mnemonic == "TRD"


def test_samples_infer_a_kind_without_schema_and_real_samples_win():
    docs = [{"deskId": f"D{i}", "region": ["EU", "US"][i % 2], "headcount": 10 + i, "asOf": "2026-01-0%d" % (1 + i % 3)} for i in range(12)]
    pl = P.make_plan(schemas("trade"), {"desk": docs})
    d = pl.kind("desk")
    assert d.key == "deskId" and d.date == "asOf" and d.real and d.index["region"].enum == ["EU", "US"] and d.source == ""
    assert d.template == "delta"
    assert any(o["field"] == "region" for o in d.match_options)


def test_samples_attach_to_the_schema_kind_by_file_name():
    assert P.match_kind("trades", ["trade", "book"]) == "trade"
    assert P.match_kind("counterparties", ["counterparty"]) == "counterparty"
    assert P.match_kind("zebra", ["trade"]) is None


def test_warnings_unmatched_fields_and_undescribed():
    docs = [{"tradeId": "T1", "surprise": 1}, {"tradeId": "T2", "surprise": 2}]
    pl = P.make_plan(schemas("trade"), {"trade": docs})
    assert any("do not describe" in w or "does not describe" in w for w in pl.kind("trade").warnings)
    assert any("no description" in w for w in pl.warnings)


def test_to_dict_is_json():
    d = P.make_plan(schemas()).to_dict()
    json.dumps(d)
    assert d["sutraCount"] == 9 and d["pack"]["name"] and {k["kind"] for k in d["kinds"]} == {"book", "counterparty", "instrument", "trade"}


# ---------------------------------------------------------------------------------------------------- synthetic documents

def valid(doc, node):
    """A tiny validator: types, enums, consts, required, ranges."""
    if node.has_const:
        assert doc == node.const
    if node.enum:
        assert doc in node.enum
    t = node.type
    if t == "string":
        assert isinstance(doc, str)
    elif t == "integer":
        assert isinstance(doc, int) and not isinstance(doc, bool)
    elif t == "number":
        assert isinstance(doc, (int, float))
    elif t == "boolean":
        assert isinstance(doc, bool)
    if isinstance(doc, (int, float)) and not isinstance(doc, bool):
        if node.minimum is not None:
            assert doc >= node.minimum
        if node.maximum is not None:
            assert doc <= node.maximum
    if t == "array":
        assert isinstance(doc, list) and doc
        for x in doc:
            valid(x, node.items)
    if isinstance(doc, dict) and node.props:
        assert node.required <= set(doc)
        for k, v in doc.items():
            if k in node.props:
                valid(v, node.props[k])


def test_synthetic_documents_are_valid_and_deterministic(plan):
    for kp in plan.kinds:
        a = synth.documents(kp, plan, 4)
        assert a == synth.documents(kp, plan, 4)
        for d in a:
            valid(d, kp.node)
    t = synth.documents(plan.kind("trade"), plan, 3)
    assert [d["tradeId"] for d in t] == ["TRA-0001", "TRA-0002", "TRA-0003"]
    assert t[0]["currency"] == "ABC"                       # the schema's pattern ^[A-Z]{3}$
    assert t[0]["tradeDate"] == "2026-01-05" and t[0]["executedAt"].endswith("Z")
    assert list(t[0]["curve"]) == ["1M", "3M", "1Y", "5Y"]  # x-drishti-keys
    assert {l.field: t[0][l.field][:3] for l in plan.kind("trade").links} == {"counterpartyId": "COU", "bookId": "BOO"}


def test_synthetic_links_resolve_to_synthetic_keys(plan):
    book_keys = {d["bookId"] for d, _ in build.catalog_docs(plan.kind("book"), plan)}
    cp_keys = {d["id"] for d, _ in build.catalog_docs(plan.kind("counterparty"), plan)}
    for d in synth.documents(plan.kind("trade"), plan, 5):
        assert d["bookId"] in book_keys and d["counterpartyId"] in cp_keys


def test_schema_examples_are_used():
    s = {"title": "Pet", "type": "object", "required": ["id"], "properties": {"id": {"type": "string", "examples": ["PET-9"]}, "kind": {"type": "string", "examples": ["cat", "dog"]}},
         "examples": [{"id": "PET-7", "kind": "cat"}]}
    pl = P.make_plan([("pet.json", s)])
    items = build.prepare(pl, 3)
    srcs = [x.source for x in items[0].samples]
    assert srcs[0] == "example" and srcs.count("synthetic") == 2
    assert items[0].samples[0].name == "sample-example-1"


# ---------------------------------------------------------------------------------------------------- the Sutra edits

def test_finalize_edits_the_draft(plan):
    t = plan.kind("trade")
    sp = t.sutras[0]                                           # trade-irs
    text, shown = decorate.finalize(DRAFT, t, sp)
    y = yaml.safe_load(text)
    assert y["sutra"] == "trade-irs" and y["match"] == {"kind": "trade", "where": "$.productType == 'IRS'", "priority": 10}
    assert y["title"]["id"] == "$.tradeId"
    assert y["description"].startswith("Trade documents where Product type IRS")
    strip = y["strip"]
    assert [s["bind"] for s in strip][:2] == ["$.status", "$.notional"]
    assert strip[1]["label"] == "Notional (USD)" and strip[1]["fmt"] == "amount2" and strip[1]["emphasis"] is True
    assert strip[0]["tone"] == "status"
    det = y["panels"][0]["columns"]
    assert {"label": "Book ID", "bind": "link($.bookId, 'book')"} == det[0]                  # reference fields link
    assert det[1]["fmt"] == "amount2" and det[1]["label"] == "Notional (USD)"
    assert y["panels"][1]["title"] == "Legs"                                                   # panel title from the field title
    legs = y["panels"][1]["columns"]
    assert legs[0]["label"] == "Present value" and legs[1]["fmt"] == "pct2"
    assert {"notional", "legs[].pv", "bookId", "tradeDate"} <= shown


def test_hidden_fields_leave_the_sutra():
    s = {"title": "Thing", "type": "object", "required": ["id"], "properties": {"id": {"type": "string"}, "hiddenThing": {"type": "string", "x-drishti-hidden": True}, "n": {"type": "number"}}}
    k = P.make_plan([("t.json", s)]).kinds[0]
    text, _ = decorate.finalize(DRAFT.replace("kind: trade", "kind: thing"), k, k.sutras[0])
    assert "hiddenThing" not in text


def test_about_yaml_comes_from_descriptions(plan):
    shown = {"trade": {"notional", "status", "legs[].pv", "tradeDate", "executedAt"}}
    y = yaml.safe_load(decorate.about_yaml(plan, shown))
    t = y["kinds"]["trade"]
    assert y["about"] == 1 and t["title"] == "Trade"
    assert t["about"].startswith("${$.tradeId}: A bilateral derivative trade")
    g = t["glossary"]
    assert g["notional"] == {"term": "Notional", "means": "An amount in the trade currency.", "unit": "USD"}
    assert g["legs.pv"]["term"] == "Present value"
    assert g["executedAt"]["means"].startswith("TODO")
    assert "TODO" in yaml.safe_load(decorate.about_yaml(plan))["kinds"]["book"]["glossary"]["createdBy"]["means"]


def test_values_annotation_goes_to_the_glossary():
    s = {"title": "Thing", "type": "object", "required": ["id"], "properties": {"id": {"type": "string"}, "state": {"enum": ["A", "B"], "x-drishti-values": {"A": "Active", "B": "Blocked"}}}}
    pl = P.make_plan([("t.json", s)])
    g = yaml.safe_load(decorate.about_yaml(pl))["kinds"]["thing"]["glossary"]
    assert g["state"]["values"] == {"A": "Active", "B": "Blocked"}


# ---------------------------------------------------------------------------------------------------- the folder

def fake_drafts(items):
    out = {}
    for it in items:
        out[it.name] = DRAFT.replace("kind: trade", f"kind: {it.kp.kind}").replace("$.tradeId", f"$.{it.kp.key}")
    return out


def test_assemble_writes_a_pack(tmp_path, plan):
    pl = copy.deepcopy(plan)
    pl.pack.update(name="demo-pack", code="DEMO", title="Demo", version="1.0.0")
    items = build.prepare(pl, 3)
    built = build.assemble(pl, items, fake_drafts(items), tmp_path / "demo-pack")
    root = tmp_path / "demo-pack"
    man = yaml.safe_load((root / "pack.yaml").read_text())
    assert man["pack"] == "demo-pack" and man["kinds"] == ["book", "counterparty", "instrument", "trade"]
    assert man["graph"]["fields"]["counterpartyId"]["kind"] == "counterparty"
    assert man["mnemonics"]["TRA"] == {"kind": "trade", "label": "Trade"}
    assert man["columns"]["trade"][0] == "status"
    assert "connectors" not in man                                       # schemas only: the pack serves its samples
    assert (root / "sutras/trade/trade-irs.v1.sutra.yaml").is_file()
    tdir = root / "tests" / "trade-irs"
    assert sorted(p.name for p in tdir.iterdir()) == ["expect.yaml", "sample-synthetic-1.json", "sample-synthetic-2.json", "sample-synthetic-3.json"]
    assert "noErrors: true" in (tdir / "expect.yaml").read_text() and "nonEmpty: [details]" in (tdir / "expect.yaml").read_text()
    cat = json.loads((root / "samples/catalog.json").read_text())
    assert any(c["kind"] == "trade" and c["subtitle"].endswith("(synthetic sample)") for c in cat)
    assert len(built.synthetic) == 9 and built.todo > 0
    assert "sample-synthetic-N.json" in (root / "README.md").read_text()
    about = yaml.safe_load((root / "config/about.yaml").read_text())
    assert "trade" in about["kinds"]


def test_connector_templates_and_ingest_block(tmp_path):
    docs = [{"tradeId": f"T{i}", "tradeDate": "2026-03-0%d" % (1 + i % 3), "productType": ["IRS", "FXS", "FUT"][i % 3], "notional": 1000.0 * i} for i in range(9)]
    pl = P.make_plan(schemas("trade"), {"trade": docs}, {"trade": {"connector": "lake-main"}})
    pl.pack.update(name="p1")
    man = build.pack_manifest(pl)
    assert list(man["connectors"]) == ["lake-main"] and man["routes"] == {"trade": "lake-main"}
    assert man["connectors"]["lake-main"]["plugin"] == "delta" and man["ingest"] == {"trade": {"key": "tradeId", "date": "tradeDate"}}
    pl2 = P.make_plan(schemas("trade"), {"trade": docs}, {"trade": {"template": "file"}})
    pl2.pack.update(name="p1")
    m2 = build.pack_manifest(pl2)
    assert list(m2["connectors"]) == ["p1-store"] and m2["connectors"]["p1-store"]["plugin"] == "file"


def test_real_samples_are_split_across_sutras():
    docs = [{"tradeId": f"T{i}", "productType": ["IRS", "FXS", "FUT"][i % 3], "tradeDate": "2026-03-01", "notional": 5.0 * i} for i in range(30)]
    pl = P.make_plan(schemas("trade"), {"trade": docs})
    items = {i.name: i for i in build.prepare(pl, 4)}
    irs = items["trade-irs"]
    assert [s.source for s in irs.samples] == ["real"] * 4 and all(s.doc["productType"] == "IRS" for s in irs.samples)
    assert len(items["trade-default"].samples) == 4
    # a variant with no real documents is topped up with synthetic ones
    docs2 = [d for d in docs if d["productType"] != "FUT"]
    pl2 = P.make_plan(schemas("trade"), {"trade": docs2})
    fut = {i.name: i for i in build.prepare(pl2, 4)}["trade-fut"]
    assert {s.source for s in fut.samples} == {"synthetic"} and fut.synthetic


def test_bundle_has_the_pack_bundle_format(tmp_path, plan):
    tools = pathlib.Path(__file__).resolve().parents[2] / "tools"
    sys.path.insert(0, str(tools))
    import packbundle
    pl = copy.deepcopy(plan)
    pl.pack.update(name="demo-pack", code="DEMO", title="Demo", version="1.0.0")
    items = build.prepare(pl, 2)
    build.assemble(pl, items, fake_drafts(items), tmp_path / "demo-pack")
    res = build.make_bundle(tmp_path / "demo-pack", tmp_path / "out", packbundle, {"python": "x"}, None)
    assert pathlib.Path(res["archive"]).name == "demo-pack-1.0.0.tar.gz"
    assert (tmp_path / "out" / "demo-pack-1.0.0.tar.gz.sha256").read_text().startswith(res["sha256"])
    man, problems = packbundle.check_manifest(tmp_path / "demo-pack")
    assert problems == [] or all("MANIFEST" in p for p in problems)
