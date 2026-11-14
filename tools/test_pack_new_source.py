#!/usr/bin/env python3
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

"""`pack new --source-for`: a new booking system's file as a source of the existing `trade` (tools/packgen/sourcemap.py, source_module.py).

    python3 -m pytest tools/test_pack_new_source.py -q

A synthetic SwapOne-style file (tools/packgen/swapone_synth.py: invented names, its own field names and enums) -> the proposal gets the
hand-written map's fields right; the edits, the map file and its reading; the source module's files and its fifteen checks (when the
module tool is built); --load writes the rows into a lake table with SOURCE_SYSTEM stamped, read back through the map as canonical trades."""
from __future__ import annotations

import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE / "packgen"))
sys.path.insert(0, str(HERE))
import sourcemap as SM  # noqa: E402
import swapone_synth as SS  # noqa: E402
import module_from_jsonl as MJ  # noqa: E402

TOOL = ROOT / "kernel/drishti-module-runtime/target/drishti-module-tool.jar"


class Fixture(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        base = ROOT / "data" / "tmp"                                     # under the worktree's data/, never committed
        base.mkdir(parents=True, exist_ok=True)
        cls.tmp = tempfile.TemporaryDirectory(dir=base)
        cls.dir = pathlib.Path(cls.tmp.name)
        cls.file = SS.write(cls.dir / "in" / "swapone_trades.2026-11-13.jsonl", 2500, "2026-11-13")
        cls.prof = SM.scan([cls.file])
        cls.map = SM.propose(cls.prof, "trade", "SWAPONE")

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()


class TheProposal(Fixture):
    def test_every_record_is_read(self):
        self.assertEqual(self.prof["rows"], 2500)
        self.assertEqual(self.prof["fields"]["BROKER_REF"].rows, sum(1 for d in SS.trades(2500, "2026-11-13") if "BROKER_REF" in d))

    def test_most_fields_are_mapped_as_a_person_would(self):
        got = {r["to"]: r["from"] for r in self.map["fields"]}
        right = [k for k, v in SS.TRUTH.items() if got.get(k) == v]
        self.assertGreaterEqual(len(right) / len(SS.TRUTH), 0.9, f"wrong: {sorted(set(SS.TRUTH) - set(right))}")
        wrong = [r for r in self.map["fields"] if r["from"] and SS.TRUTH.get(r["to"]) != r["from"]]
        self.assertLessEqual(len(wrong), 3, wrong)
        self.assertTrue(all(r["confidence"] < SM.SURE for r in wrong), "a wrong pair is never sure")

    def test_value_maps_onto_the_enums(self):
        by = {r["to"]: r for r in self.map["fields"]}
        self.assertEqual(by["BUY_SELL_CODE"]["values"], {"PAY": "B", "RECEIVE": "S"})
        self.assertEqual(by["TRADE_STATUS"]["values"], {"LIVE": "VALIDATED", "AMD": "AMENDED", "CXL": "CANCELLED", "MAT": "MATURED"})
        self.assertEqual(by["DELIVERY_VERSUS_PAYMENT"]["values"], {"Y": "YES", "N": "NO"})
        self.assertGreaterEqual(by["TRADE_ID"]["confidence"], SM.SURE)
        self.assertIn("unique in 2,500 rows", by["TRADE_ID"]["why"])

    def test_required_missing_and_native_unmapped_are_listed(self):
        self.assertTrue({"INTERNAL_EXTERNAL_CODE", "TRANSIT", "EVENTS"} <= set(self.map["missing"]))
        self.assertTrue({"CLEARING_HOUSE", "CONFIRM_METHOD"} <= {u["field"] for u in self.map["unmapped"]})
        guess = [r for r in self.map["fields"] if r["from"] == "BROKER_REF"]        # UPPER_SNAKE names score closer: a weak guess at most, never sure
        self.assertTrue(all(r["confidence"] < SM.SURE for r in guess))
        source = next(r for r in self.map["fields"] if r["to"] == "SOURCE_SYSTEM")
        self.assertEqual(source["value"], "SWAPONE")

    def test_the_kind_is_suggested_from_the_overlap(self):
        self.assertEqual(SM.suggest(self.prof)[0]["kind"], "trade")


class TheEditsAndTheFile(Fixture):
    def test_edits_drop_set_and_choose(self):
        m = SM.edit(json.loads(json.dumps(self.map)), {"TRADER_FIRST_NAME": None, "INTERNAL_EXTERNAL_CODE": {"value": "External"},
                                                       "TRANSIT": {"value": "00107"}, "TT_GROUP": {"from": "SW_ONE_DESK_TAG"}})
        by = {r["to"]: r for r in m["fields"]}
        self.assertIsNone(by["TRADER_FIRST_NAME"]["from"])
        self.assertEqual(by["INTERNAL_EXTERNAL_CODE"]["value"], "External")
        self.assertNotIn("INTERNAL_EXTERNAL_CODE", m["missing"])
        self.assertIn("TRADER_NAME", {u["field"] for u in m["unmapped"]})
        with self.assertRaises(SystemExit):
            SM.edit(json.loads(json.dumps(self.map)), {"NOT_A_FIELD": None})

    def test_the_map_file_reads_a_native_record_as_a_canonical_trade(self):
        import yaml
        m = SM.edit(json.loads(json.dumps(self.map)), {"INTERNAL_EXTERNAL_CODE": {"value": "External"}})
        mapping = yaml.safe_load(SM.map_yaml(m))
        self.assertEqual(mapping["set"], {"SOURCE_SYSTEM": "SWAPONE"})
        doc = next(SS.trades(1, "2026-11-13"))
        t = SM.apply(mapping, doc)
        self.assertEqual(t["TRADE_ID"], doc["DEAL_REF"])
        self.assertIn(t["BUY_SELL_CODE"], ("B", "S"))
        self.assertEqual(t["SOURCE_SYSTEM"], "SWAPONE")
        self.assertEqual(t["INTERNAL_EXTERNAL_CODE"], "External")
        self.assertEqual(t["CLEARING_HOUSE"], doc["CLEARING_HOUSE"])          # kept as it is, for the module's section of the page


class TheModule(Fixture):
    def opts(self, *extra):
        return MJ.build_parser().parse_args([str(self.file), "--source-for", "trade", "--system", "SWAPONE", "--out", str(self.dir / "mod" / "source-swapone"),
                                             "--dist", str(self.dir / "dist"), "--force", *extra])

    def test_the_command_line_needs_no_name_and_a_dry_run_writes_nothing(self):
        import drishti as cli
        a = self.opts("--shared-table", "trading.trades_shared", "--dry-run")
        self.assertEqual(MJ.run(a, cli, lambda *_: None), 0)
        self.assertFalse((self.dir / "mod" / "source-swapone").exists())

    def test_the_module_has_the_shape_of_a_source_module(self):
        import yaml
        import source_module as SMOD
        a = self.opts("--shared-table", "trading.trades_shared", "--live", "--write-back")
        n = SMOD.names(a)
        mod = self.dir / "mod" / n["name"]
        SMOD.write_module(a, n, self.map, mod, lambda *_: None)
        m = yaml.safe_load((mod / "module.yaml").read_text())
        self.assertEqual(m["id"], "source-swapone")
        self.assertEqual(m["requires"], {"trading": ">=1.0 <2"})
        self.assertEqual(m["dataload"], "dataload.yaml")
        self.assertEqual(yaml.safe_load((mod / "dataload.yaml").read_text())["dataload"]["rules"][0], {"match": "swapone_*.jsonl*", "pack": "trading", "kind": "trade", "connector": "source-swapone", "date-from": "name"})
        read = yaml.safe_load((mod / "connectors" / "source-swapone.yaml").read_text())
        self.assertEqual(read["settings"]["where"], "SOURCE_SYSTEM = 'SWAPONE'")
        self.assertEqual(read["settings"]["set"], {"SOURCE_SYSTEM": "SWAPONE"})
        self.assertEqual(read["settings"]["map"], "mappings/swapone-trade.yaml")
        live = yaml.safe_load((mod / "connectors" / "source-swapone-live.yaml").read_text())
        self.assertIn("amend-trade", live["writes"])
        self.assertIn("${", live["settings"]["bootstrap-servers"])
        section = yaml.safe_load((mod / m["sections"][0]["fragment"]).read_text())
        self.assertEqual(section["panels"][0]["when"], "$.SOURCE_SYSTEM == 'SWAPONE'")
        self.assertIn("CLEARING_HOUSE", [c["label"] for c in section["panels"][0]["columns"]])
        front = (mod / "docs" / "README.md").read_text().split("---")[1]
        self.assertIn("module: source-swapone", front)
        own = SMOD.names(self.opts())
        self.assertEqual((own["table"], own["shared"]), ("trading.trade_swapone", False))

    @unittest.skipUnless(TOOL.is_file() and shutil.which("java"), "needs the module tool (./mvnw -q package -DskipTests -pl :drishti-module-runtime -am)")
    def test_the_module_passes_the_fifteen_checks(self):
        out = subprocess.run([sys.executable, str(HERE / "drishti.py"), "pack", "new", str(self.file), "--source-for", "trade", "--system", "SWAPONE",
                              "--shared-table", "trading.trades_shared", "--live", "--write-back", "--out", str(self.dir / "chk" / "source-swapone"),
                              "--dist", str(self.dir / "chk-dist"), "--force", "--cycles", "1"], capture_output=True, text=True, cwd=ROOT,
                             env={**os.environ, "TMPDIR": str(self.dir)})
        self.assertEqual(out.returncode, 0, out.stdout[-3000:] + out.stderr[-2000:])
        self.assertIn("all 15 conformance checks passed", out.stdout)


class TheLoad(Fixture):
    def test_load_writes_the_rows_with_the_system_stamped_and_the_map_reads_them_as_trades(self):
        try:
            import deltalake  # noqa: F401
            import pyarrow  # noqa: F401
        except ImportError:
            self.skipTest("needs deltalake and pyarrow (the console's environment)")
        import drishti as cli
        import source_module as SMOD
        import yaml
        lake = self.dir / "lake"
        a = MJ.build_parser().parse_args([str(self.file), "--source-for", "trade", "--system", "SWAPONE", "--shared-table", "trading.trades_shared",
                                          "--lake", str(lake)])
        n = SMOD.names(a)
        a._map = self.map
        self.assertEqual(SMOD.load(a, n, cli, lambda *_: None), 0)
        from deltalake import DeltaTable
        t = DeltaTable(str(lake / "trading" / "trades_shared")).to_pyarrow_table()
        self.assertEqual(t.num_rows, 2500)
        self.assertEqual(set(t.column("SOURCE_SYSTEM").to_pylist()), {"SWAPONE"})
        col = next(c for c in ("doc", "document", "json", "_doc") if c in t.column_names)
        mapping = yaml.safe_load(SM.map_yaml(self.map))
        trades = [SM.apply(mapping, json.loads(d)) for d in t.column(col).to_pylist()[:50]]
        need = [f for f in ("TRADE_ID", "TRADE_DATE", "BUY_SELL_CODE", "CURRENCY", "COUNTERPARTY", "PORTFOLIO", "SOURCE_SYSTEM")]
        self.assertTrue(all(all(x.get(f) not in (None, "") for f in need) for x in trades))


if __name__ == "__main__":
    unittest.main()
