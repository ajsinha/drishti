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
        self.assertEqual((pos["class"], pos["storage"]["plugin"]), ("drishti-owned", "var-positions"))
        self.assertEqual([f["name"] for f in pos["fields"] if f["presence"] == "derived"], [])


class TheRetiredStoredVersions(unittest.TestCase):
    """The guard of docs/contracts/README.md section 14: a retired kind never returns, a figure removed from a stored record never comes
    back, no input or fundamental kind (nor any test data or sample) carries a column a compute engine produces, and no connector reads
    the published results (an engine computes from the risk lake's inputs only)."""

    @classmethod
    def setUpClass(cls):
        cls.pol = C.policy()
        cls.manifests = C.manifests()
        cls.testdata = yaml.safe_load((C.TESTDATA / "testdata.yaml").read_text(encoding="utf-8"))

    def docs_of(self, pack: str, kind: str) -> list[dict]:
        samples, tdocs, _ = C.read_documents(C.PACKS / pack, kind, C.TESTDATA)
        return samples + tdocs

    @staticmethod
    def names(doc, path: str = "") -> set[str]:
        """Every field name in a document at any depth, and every dotted path (A.B for a field of a list A's elements)."""
        out = set()
        if isinstance(doc, dict):
            for k, v in doc.items():
                out |= {k, f"{path}{k}"}
                out |= TheRetiredStoredVersions.names(v, f"{path}{k}.")
        elif isinstance(doc, list):
            for x in doc:
                out |= TheRetiredStoredVersions.names(x, path)
        return out

    def test_a_retired_kind_never_returns(self):
        retired = self.pol["retired"]
        self.assertTrue({"var", "var-node", "exposure-profile"} <= set(retired))
        for kind in retired:
            for pack, m in self.manifests.items():
                self.assertNotIn(kind, m.get("kinds") or [], f"{pack} declares the retired kind {kind}")
                self.assertNotIn(kind, m.get("routes") or {}, f"{pack} routes the retired kind {kind}")
                self.assertNotIn(kind, {(v or {}).get("kind") for v in (m.get("mnemonics") or {}).values()}, f"{pack}: a mnemonic of {kind}")
                self.assertFalse((C.PACKS / pack / "samples" / f"{kind}.jsonl").exists(), f"{pack} samples of {kind}")
                self.assertFalse((C.PACKS / pack / "contracts" / f"{kind}.contract.yaml").exists(), f"{pack} contract of {kind}")
                self.assertFalse((C.PACKS / pack / "tests" / kind).exists(), f"{pack} Sutra tests of {kind}")
                sutras = [f for f in (C.PACKS / pack).rglob("*.sutra.yaml") if f"kind: {kind}," in f.read_text(encoding="utf-8")]
                self.assertEqual(sutras, [], f"{pack}: a Sutra of {kind}")
            self.assertNotIn(kind, {d.get("kind") for d in self.testdata["datasets"]}, f"test data of {kind}")
            self.assertFalse(any((C.TESTDATA / p / kind).exists() for p in self.manifests), f"a test-data folder of {kind}")

    def test_a_removed_figure_never_comes_back(self):
        for kind, entry in self.pol["removed"].items():
            pack = next(p for p, m in self.manifests.items() if kind in (m.get("kinds") or []))
            want = {f.replace("[]", "") for f in entry["figures"]}
            for d in self.docs_of(pack, kind):
                # top-level: true, the plain names are the record's own fields (index-membership's members keep their REVENUE rows)
                have = (set(d) | {n for n in self.names(d) if "." in n}) if entry.get("top-level") else self.names(d)
                self.assertEqual(have & want, set(), f"{kind} {d.get(next(iter(d)))} carries a removed figure")
            c = yaml.safe_load(C.contract_file(C.PACKS, pack, kind).read_text(encoding="utf-8"))
            fields = {f["name"] for f in c.get("fields") or []} | {f"{p.replace('[]', '')}.{f['name']}" for p, fs in (c.get("nested") or {}).items()
                                                                   for f in fs}
            self.assertEqual(fields & want, set(), f"the {kind} contract lists a removed figure")

    def test_no_input_or_fundamental_kind_carries_an_engine_column(self):
        engine = {c for cols in self.pol["engine-columns"].values() for c in cols if "[]" not in c}
        paths = {c.replace("[]", "") for cols in self.pol["engine-columns"].values() for c in cols if "[]" in c}   # A[].B: B of A's elements
        checked = 0
        for cmd, f, c in C.contracts():
            if c is None or c.get("class") not in ("input", "fundamental", "drishti-owned"):
                continue
            fields = {x["name"] for x in c.get("fields") or []} | {x["name"] for fs in (c.get("nested") or {}).values() for x in fs}
            nested = {f"{p.replace('[]', '')}.{x['name']}" for p, fs in (c.get("nested") or {}).items() for x in fs}
            self.assertEqual((fields & engine) | (nested & paths), set(), f"the {cmd['kind']} contract ({c['class']}) lists an engine's column")
            for d in self.docs_of(cmd["pack"], cmd["kind"]):
                self.assertEqual(self.names(d) & (engine | paths), set(), f"{cmd['kind']} data carries an engine's column")
            checked += 1
        self.assertGreater(checked, 50)

    def test_no_connector_reads_the_published_results(self):
        """No engine's input is a published result. The one exception is a read-only Delta connector over risk-results whose kinds are all
        computed (var-official and exposure-official-cpty, for BI since 2.9.0): it is left out of the text check, and no reactive connector may read or trigger on its kinds."""
        published = set()
        texts = []
        for p, m in self.manifests.items():
            conns = dict(m.get("connectors") or {})
            for name, c in list(conns.items()):
                kinds = c.get("kinds") or []
                if ((c.get("settings") or {}).get("domain") == "risk-results" and c.get("plugin") == "delta" and kinds
                        and set(kinds) <= set(self.pol["classes"]["computed"])):
                    published |= set(kinds)
                    del conns[name]
            texts.append((p, json.dumps(conns)))
        self.assertIn("var-official", published)
        self.assertIn("exposure-official-cpty", published)
        for p, m in self.manifests.items():
            for name, c in (m.get("connectors") or {}).items():
                if c.get("plugin") != "reactive":
                    continue
                used = {r.get("kind") for r in c.get("reads") or []} | {k for t in c.get("triggers") or [] for k in t.get("kinds") or []}
                self.assertEqual(used & published, set(), f"{p}: the reactive connector {name} reads a published result")
        app = C.ROOT / "drishti-server" / "src" / "main" / "resources" / "application.yaml"
        server = yaml.safe_load(app.read_text(encoding="utf-8"))
        texts.append(("application.yaml", json.dumps(((server.get("drishti") or {}).get("sources") or {}).get("connectors") or {})))
        for where, text in texts:
            self.assertNotIn("risk-results", text, f"{where}: a connector reads risk-results (published results are never an engine's input)")
        self.assertIn("risk-results", (C.ROOT / "drishti-server" / "src" / "main" / "java" / "com" / "ash" / "drishti" / "server" / "var"
                                       / "VarPublisher.java").read_text(encoding="utf-8").replace('"risk-results"', "risk-results"))
        self.assertIn("risk-results", (C.ROOT / "drishti-server" / "src" / "main" / "java" / "com" / "ash" / "drishti" / "server" / "exposure"
                                       / "ExposurePublisher.java").read_text(encoding="utf-8").replace('"risk-results"', "risk-results"))

    def test_the_stored_exposure_profile_does_not_return(self):
        """EXPOSURE_GUIDE.md section 22: exposure-profile is retired; no netting set or counterparty (nor any input or fundamental kind) carries an
        exposure figure; the exposure engine's inputs are inputs and its answer is computed."""
        self.assertEqual(self.pol["retired"]["exposure-profile"]["release"], "2.9.0")
        exposure = set(self.pol["engine-columns"]["exposure"])
        self.assertTrue({"EPE", "EEPE", "PFE_PEAK", "EE_PEAK"} <= exposure)
        for pack, kind in (("counterparty-risk", "netting-set"), ("core", "counterparty")):
            for d in self.docs_of(pack, kind):
                self.assertEqual(self.names(d) & (exposure | {"PROFILE"}), set(), f"{kind} carries an exposure figure")
        by_kind = {cmd["kind"]: c for cmd, f, c in C.contracts()}
        self.assertEqual(by_kind["simulation-run"]["class"], "input")
        self.assertEqual(by_kind["exposure-path"]["class"], "input")
        self.assertEqual(by_kind["exposure-result"]["class"], "computed")
        self.assertNotIn("exposure-profile", by_kind)

    def test_a_grouping_computed_in_place_keeps_no_stored_version(self):
        """GROUPINGS_ANALYTICS_GUIDE.md: LEIG, LEIC, SIC and ECG are computed from the fundamental data by a derived connector since 2.9.2,
        under the same kind, command and ids. Their stored version never returns: no Delta connector serves the kind, no sample, test
        data or test-data folder holds it, no public-data generator writes it, and the contract says computed by the derived connector."""
        cip = self.pol["computed-in-place"]
        self.assertEqual(set(cip), {"lei-group", "lei-country", "sic-sector", "country-group"})
        snap_spec = importlib.util.spec_from_file_location("publicsnap", C.ROOT / "tools" / "testdata" / "publicsnap.py")
        snap = importlib.util.module_from_spec(snap_spec)
        snap_spec.loader.exec_module(snap)
        by_kind = {cmd["kind"]: c for cmd, _, c in C.contracts()}
        for kind, entry in cip.items():
            self.assertEqual(entry["release"], "2.9.2", kind)
            pack = next(p for p, m in self.manifests.items() if kind in (m.get("kinds") or []))
            m = self.manifests[pack]
            self.assertEqual(m["connectors"][m["routes"][kind]]["plugin"], "derived", f"{kind} is not computed by a derived connector")
            for name, conn in m["connectors"].items():
                if conn.get("plugin") != "derived":
                    self.assertNotIn(kind, conn.get("kinds") or [], f"{pack}: {name} serves the retired stored {kind}")
                    self.assertNotIn(kind, ((conn.get("settings") or {}).get("layout") or {}), f"{pack}: {name} lays out {kind}")
            self.assertFalse(any((C.PACKS / pack / "samples").glob(f"{kind}.jsonl*")), f"{pack} samples of {kind}")
            self.assertNotIn(kind, {d.get("kind") for d in self.testdata["datasets"]}, f"test data of {kind}")
            self.assertFalse(any((C.TESTDATA / p / kind).exists() for p in self.manifests), f"a test-data folder of {kind}")
            self.assertNotIn(kind, (snap.PACKS.get(pack) or {}).get("keys", {}), f"the public-data generator of {pack} writes {kind}")
            self.assertEqual((by_kind[kind]["class"], by_kind[kind]["storage"]["plugin"]), ("computed", "derived"), kind)
        self.assertEqual(set(snap.COMPUTED["gleif"]) | set(snap.COMPUTED["us-equities"]), {"lei-group", "lei-country", "sic-sector"})

    def test_correlations_and_ratios_are_computed(self):
        """CORR keeps its factors and computes its correlations (correlation-result, a reactive Java engine); the USEQ ratios and PXH
        analytics are worked out when read (GROUPINGS_ANALYTICS_GUIDE.md sections 15 to 17)."""
        by_kind = {cmd["kind"]: c for cmd, _, c in C.contracts()}
        self.assertEqual((by_kind["correlation-result"]["class"], by_kind["correlation-result"]["storage"]["plugin"]), ("computed", "reactive"))
        self.assertEqual(by_kind["correlation-matrix"]["class"], "fundamental")
        md = self.manifests["market-data"]
        engine = md["connectors"]["correlation-engine"]["engine"]["class"]
        self.assertTrue((C.ROOT / "drishti-compute" / "src" / "main" / "java" / Path(*engine.split("."))).with_suffix(".java").is_file(), engine)
        for d in self.docs_of("market-data", "correlation-matrix"):
            self.assertTrue(d.get("EQUITY_IDS") or d.get("FX_IDS"), d.get("MATRIX_ID"))
        self.assertEqual(self.pol["removed"]["us-company"]["figures"], ["NET_MARGIN", "REVENUE_GROWTH", "FINANCIALS[].NET_MARGIN"])
        analytics = {f["name"] for f in (by_kind["price-history"].get("nested") or {}).get("ANALYTICS") or []}
        self.assertTrue({"RETURN_1M", "VOLATILITY", "BETA", "MAX_DRAWDOWN"} <= analytics)
        self.assertFalse((C.TESTDATA / "us-equities" / "price-history").exists())       # the prices stay live only, never stored
        # the facts' yearly change, the index pages' totals and the equities' beta: computed too (2.9.2)
        self.assertIn("HISTORY[].CHANGE_PCT", self.pol["removed"]["financial-fact"]["figures"])
        self.assertTrue({"REVENUE", "NET_INCOME", "SECTORS", "BY_SECTOR"} <= set(self.pol["removed"]["index-membership"]["figures"]))
        self.assertEqual(self.pol["removed"]["equity"]["figures"], ["BETA"])
        self.assertEqual(self.pol["removed"]["legal-entity"]["figures"], ["BOOKS[].VAR"])     # the books' VaR is the engine's (2.9.2)
        self.assertIn("BOOKS[].VAR", self.pol["engine-columns"]["var"])
        self.assertEqual((by_kind["equity-beta"]["class"], by_kind["equity-beta"]["storage"]["plugin"]), ("computed", "reactive"))
        beta = md["connectors"]["equity-analytics"]["engine"]["class"]
        self.assertTrue((C.ROOT / "drishti-compute" / "src" / "main" / "java" / Path(*beta.split("."))).with_suffix(".java").is_file(), beta)

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
