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

"""The data contracts (tools/contracts.py, docs/contracts/README.md section 16): every command of every pack has a contract that is
valid, matches its pack and its data and is described by current pages; a draft is read from a pack and its data, never invented;
the checks catch a missing, invalid or drifted contract and stale pages. A small pack in a temporary folder for the cases.

    python3 -m unittest -q tools/test_contracts.py
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path

import yaml

TOOLS = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("drishti_contracts", TOOLS / "contracts.py")
C = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(C)

PACK = {
    "pack": "mini", "version": "1.0.0", "title": "Mini", "kinds": ["widget", "gadget"],
    "mnemonics": {"WID": {"kind": "widget", "label": "Widget"}, "GAD": {"kind": "gadget", "label": "Gadget"}},
    "graph": {"id-patterns": [{"pattern": "^W-", "kind": "widget"}, {"pattern": "^G-", "kind": "gadget"}],
              "fields": {"GADGET": {"kind": "gadget", "label": "Gadget"}}},
    "connectors": {"mini-store": {"plugin": "delta", "kinds": ["widget", "gadget"],
                                  "settings": {"root": "x", "domain": "mini", "layout": {"widget": {"columns": ["WIDGET_ID", "COLOUR"], "vectors": ["PNL"]}}}}},
    "routes": {"widget": "mini-store", "gadget": "mini-store"},
}
ABOUT = {"about": 1, "kinds": {"widget": {"title": "Widget", "glossary": {
    "COLOUR": {"term": "Colour", "means": "The widget's colour.", "values": {"red": "Red.", "blue": "Blue."}},
    "PRICE": {"term": "Price", "means": "What it costs.", "unit": "USD"}}}}}


def widgets(n: int = 14) -> list[dict]:
    return [{"WIDGET_ID": f"W-{i}", "COLOUR": "red" if i % 2 else "blue", "PRICE": 10.5 + i, "COUNT_OF_PARTS": i,
             "GADGET": f"G-{i % 3}", "NOTE": "x" if i % 2 else "", "PNL": [1.0, -2.0, 3.0],
             "PARTS": [{"PART": "bolt", "QTY": 2}], "_meta": {"source": "widget-system"}} for i in range(n)]


class MiniPack:
    """A packs folder with one pack (mini: widget with samples and a glossary, gadget with samples) and an empty test-data folder."""

    def __enter__(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="drishti-contracts-")
        root = Path(self.tmp.name)
        self.packs, self.testdata = root / "packs", root / "testdata"
        d = self.packs / "mini"
        (d / "samples").mkdir(parents=True)
        (d / "config").mkdir()
        self.testdata.mkdir()
        (d / "pack.yaml").write_text(yaml.safe_dump(PACK, sort_keys=False), encoding="utf-8")
        (d / "config" / "about.yaml").write_text(yaml.safe_dump(ABOUT, sort_keys=False), encoding="utf-8")
        self.write("widget", widgets())
        self.write("gadget", [{"GADGET_ID": f"G-{i}", "NAME": f"Gadget {i}"} for i in range(3)])
        (d / "samples" / "catalog.json").write_text(json.dumps([{"kind": "gadget", "id": "G-0"}]), encoding="utf-8")
        return self

    def write(self, kind: str, docs: list[dict]) -> None:
        (self.packs / "mini" / "samples" / f"{kind}.jsonl").write_text("".join(json.dumps(x) + "\n" for x in docs), encoding="utf-8")

    def draft(self, kind: str) -> dict:
        return C.Draft(kind, self.packs, self.testdata, pol={}).build()

    def save(self, kind: str, contract: dict | None = None) -> Path:
        f = C.contract_file(self.packs, "mini", kind)
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(C.dump(contract or self.draft(kind)), encoding="utf-8")
        return f

    def __exit__(self, *exc):
        self.tmp.cleanup()


def field(contract: dict, name: str, path: str = "") -> dict:
    fields = contract["fields"] if not path else contract["nested"][path]
    return next(f for f in fields if f["name"] == name)


class TheRepository(unittest.TestCase):
    def test_every_command_has_a_valid_contract_that_matches_its_pack_and_data(self):
        self.assertEqual(C.check(), [])

    def test_the_generated_pages_are_current(self):
        for page, text in C.render_docs().items():
            self.assertTrue(page.is_file(), f"{page} is missing: run drishti.py contract docs")
            self.assertEqual(page.read_text(encoding="utf-8"), text, f"{page} is stale: run drishti.py contract docs")

    def test_the_product_owners_decisions_are_in_the_contracts(self):
        pol = C.policy()
        by_kind = {cmd["kind"]: c for cmd, _, c in C.contracts()}
        for kind, entry in pol["retiring"].items():
            c = by_kind[kind]
            self.assertEqual((c["status"], c["class"], c["retiring-in"], c["replaced-by"]),
                             ("retiring", "computed", str(entry["release"]), "docs/contracts/" + entry["contract"]), kind)
        for kind, entry in pol["split"].items():
            c = by_kind[kind]
            self.assertEqual(c["status"], "active", kind)
            for path in entry["figures"]:
                parent, _, name = path.rpartition(".")
                f = field(c, name, parent)
                self.assertEqual((f["presence"], f.get("retiring-in")), ("derived", str(entry["release"])), f"{kind} {path}")
        pos = by_kind["position"]
        self.assertEqual([f["name"] for f in pos["fields"] if f["presence"] == "derived"], ["VAR_99", "ES_975", "COMPONENT_VAR"])

    def test_every_file_starts_with_the_licence(self):
        for f in sorted(C.PACKS.glob("*/contracts/*.contract.yaml")):
            self.assertIn("PROPRIETARY AND CONFIDENTIAL", f.read_text(encoding="utf-8")[:800], f)


class Drafts(unittest.TestCase):
    def test_a_draft_reads_types_coverage_glossary_links_and_patterns_from_the_pack(self):
        with MiniPack() as m:
            c = m.draft("widget")
            self.assertEqual(C.schema_problems(c), [])
            self.assertEqual((c["kind"], c["mnemonic"], c["class"], c["status"], c["source"]), ("widget", "WID", "fundamental", "active", "widget-system"))
            self.assertEqual(c["id-pattern"], ["^W-"])
            self.assertEqual(c["storage"]["columns"], ["WIDGET_ID", "COLOUR"])
            self.assertEqual(c["storage"]["vectors"], ["PNL"])
            self.assertEqual(field(c, "COLOUR")["enum"], ["red", "blue"])                    # from the glossary: a fact
            self.assertEqual((field(c, "PRICE")["type"], field(c, "PRICE")["unit"]), ("amount", "USD"))
            self.assertEqual(field(c, "COUNT_OF_PARTS")["type"], "integer")
            self.assertEqual(field(c, "NOTE")["presence"], "optional")                       # empty in half the documents
            self.assertEqual(field(c, "WIDGET_ID")["presence"], "required")
            self.assertEqual((field(c, "GADGET")["link"], field(c, "GADGET")["pattern"]), ("gadget", "^G-"))
            self.assertEqual(field(c, "PNL")["type"], "vector")
            self.assertEqual(field(c, "QTY", "PARTS[]")["type"], "integer")
            self.assertNotIn("_meta", [f["name"] for f in c["fields"]])
            self.assertIn({"field": "GADGET", "kind": "gadget"}, c["links"])
            rules = {r["rule"] for r in c["validation"]}
            self.assertTrue({"required", "unique", "enum", "link", "range", "vector-length"} <= rules)
            self.assertTrue(any("100% coverage in 14 documents" in r for r in c["review"]))
            self.assertTrue(any("NOTE" in r and "no description" in r for r in c["review"]))

    def test_what_cannot_be_read_goes_to_review_not_into_the_contract(self):
        with MiniPack() as m:
            c = m.draft("gadget")
            self.assertEqual(c["key"], ["GADGET_ID"])                                        # from the catalogue's example id
            self.assertTrue(any("catalogue" in r for r in c["review"]))
            self.assertEqual(c["cadence"], {"frequency": "static", "due": "to be confirmed"})

    def test_a_retiring_kind_marks_its_figures_derived(self):
        with MiniPack() as m:
            pol = {"retiring": {"widget": {"release": "2.9.0", "contract": "X.md", "measure": "the X engine", "figures": ["PRICE"]}}}
            c = C.Draft("widget", m.packs, m.testdata, pol=pol).build()
            self.assertEqual((c["status"], c["class"], c["retiring-in"], c["replaced-by"]), ("retiring", "computed", "2.9.0", "docs/contracts/X.md"))
            self.assertEqual(field(c, "PRICE")["presence"], "derived")
            self.assertIn("removed when retired", field(c, "PRICE")["note"])


class Checks(unittest.TestCase):
    def check(self, m) -> list[str]:
        return C.check(m.packs, m.testdata)

    def test_a_command_without_a_contract_fails(self):
        with MiniPack() as m:
            m.save("widget")
            self.assertTrue(any("GAD (gadget): no contract" in p for p in self.check(m)))
            m.save("gadget")
            self.assertEqual(self.check(m), [])

    def test_an_invalid_contract_fails(self):
        with MiniPack() as m:
            m.save("gadget")
            c = m.draft("widget")
            c["class"] = "made-up"
            field(c, "PRICE")["type"] = "money"
            m.save("widget", c)
            probs = self.check(m)
            self.assertTrue(any("class" in p and "made-up" in p for p in probs), probs)
            self.assertTrue(any("money" in p for p in probs), probs)

    def test_a_contract_that_drifted_from_its_data_fails(self):
        with MiniPack() as m:
            m.save("widget")
            m.save("gadget")
            docs = widgets()
            for d in docs:
                d["NEW_FIELD"] = 1
                d["COLOUR"] = "green"
                del d["PRICE"]
            m.write("widget", docs)
            probs = "\n".join(self.check(m))
            self.assertIn("NEW_FIELD is in the data but not in the contract", probs)
            self.assertIn("PRICE is in the contract but not in the data", probs)
            self.assertIn("COLOUR has values outside its enum", probs)

    def test_a_contract_without_a_command_fails(self):
        with MiniPack() as m:
            m.save("widget")
            m.save("gadget")
            orphan = C.contract_file(m.packs, "mini", "gizmo")
            orphan.write_text(C.contract_file(m.packs, "mini", "gadget").read_text(encoding="utf-8"), encoding="utf-8")
            self.assertTrue(any("gizmo" in p and "no command" in p for p in self.check(m)))

    def test_the_pages_say_they_are_generated_and_list_every_command(self):
        with MiniPack() as m:
            m.save("widget")
            m.save("gadget")
            pages = C.render_docs(m.packs)
            index = pages[C.DOCS / "README.md"]
            self.assertIn("Generated by tools/contracts.py docs", index)
            self.assertIn("[mini](mini.md#wid-widget-widget)", index)
            page = pages[C.DOCS / "mini.md"]
            self.assertIn("## WID: Widget (`widget`)", page)
            self.assertIn("**To review", page)


class Commands(unittest.TestCase):
    def run_main(self, *argv) -> tuple[int, str]:
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
            rc = C.main(list(argv))
        return rc, out.getvalue()

    def test_bootstrap_writes_once_and_list_show_check_answer(self):
        with MiniPack() as m:
            args = ["--packs", str(m.packs), "--testdata", str(m.testdata)]
            self.assertEqual(self.run_main("bootstrap", "--all", "--write", *args)[0], 0)
            self.assertEqual(self.run_main("bootstrap", "WID", "--write", *args)[0], 1)      # exists: --force replaces it
            rc, out = self.run_main("list", *args)
            self.assertEqual(rc, 0)
            self.assertIn("2 commands, 2 contracts", out)
            rc, out = self.run_main("show", "wid", "--json", *args)
            self.assertEqual(json.loads(out)["kind"], "widget")
            self.assertEqual(self.run_main("check", *args), (0, "2 commands, each with a valid contract that matches its pack and its data\n"))


if __name__ == "__main__":
    unittest.main()
