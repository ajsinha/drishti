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

"""tools/sizing_planner.py: the workbook builds, every Results formula names only defined cells, the defaults reproduce the figures the
Explain tab states, and the committed workbook is the fresh build."""
from __future__ import annotations

import os
import re
import shutil
import sys
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import sizing_planner as sp  # noqa: E402

NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}


class SizingPlannerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = sp.ROOT / "data" / "tmp" / f"sizing-planner-test-{os.getpid()}"
        cls.tmp.mkdir(parents=True, exist_ok=True)
        cls.path = cls.tmp / sp.OUT.name
        sp.write(sp.build(), cls.path)
        cls.parts = sp.read(cls.path)
        wb = ET.fromstring(cls.parts["xl/workbook.xml"])
        cls.sheets = [s.get("name") for s in wb.find("m:sheets", NS)]
        cls.names = {d.get("name"): d.text for d in wb.find("m:definedNames", NS)}
        cls.values = {}
        cls.formulas = {}
        for i, name in enumerate(cls.sheets, 1):
            for c in ET.fromstring(cls.parts[f"xl/worksheets/sheet{i}.xml"]).iter("{%s}c" % NS["m"]):
                f, v = c.find("m:f", NS), c.find("m:v", NS)
                ref = f"{name}!${re.match(r'[A-Z]+', c.get('r')).group()}${re.search(r'[0-9]+', c.get('r')).group()}"
                if f is not None:
                    cls.formulas[ref] = f.text
                    cls.values[ref] = v.text if v is not None else None

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def test_the_four_sheets(self):
        self.assertEqual(self.sheets, ["Inputs", "Results", "Coefficients", "Explain"])

    def test_every_results_formula_names_only_defined_cells(self):
        results = {ref: f for ref, f in self.formulas.items() if ref.startswith("Results!")}
        self.assertGreater(len(results), 100)
        for ref, f in results.items():
            missing = sp.names_in(f) - set(self.names)
            self.assertEqual(missing, set(), f"{ref} ={f}")
            self.assertIsNone(re.search(r"(?<![A-Za-z_])\$?[A-Z]{1,3}\$?[0-9]+(?![A-Za-z_0-9])", re.sub(r'"[^"]*"', "", f)), f"{ref} uses a cell reference: {f}")

    def test_results_hard_code_no_constant_but_units(self):
        allowed = {"0", "1", "2", "3", "10", "100", "1024", "25"}  # structure (72·(2T + 3P)), CHAR(10), per cent, MB a GB, the Java version
        for ref, f in self.formulas.items():
            for n in re.findall(r"(?<![A-Za-z_0-9.])([0-9]+(?:\.[0-9]+)?)", re.sub(r'"[^"]*"', "", f)):
                self.assertIn(n, allowed, f"{ref} hard-codes {n}: put it on Coefficients")

    def test_every_name_points_at_a_cell_of_its_sheet(self):
        for name, target in self.names.items():
            self.assertRegex(target, r"^(Inputs|Results|Coefficients)!\$[A-Z]+\$[0-9]+$", name)

    def _same(self, got, expected, name):
        if isinstance(expected, str):
            self.assertEqual(got, expected, name)
        else:
            self.assertAlmostEqual(float(got), expected, delta=max(0.5, abs(expected) * 0.0005), msg=name)

    def test_the_defaults_reproduce_the_explained_figures(self):
        by_name = {n: self.values.get(t) for n, t in self.names.items()}
        for name, expected in {**sp.EXPECTED, **sp.CALIBRATION_EXPECTED}.items():
            self._same(by_name[name], expected, name)

    def test_the_benchmark_example_and_the_calibration_whatever_the_inputs(self):
        ev = sp.evaluate(sp.BENCH_SHAPE)
        for name, expected in {**sp.EXPECTED_BENCH, **sp.CALIBRATION_EXPECTED}.items():
            self._same(ev.value(name), expected, name)

    def test_intraday_scales_with_its_inputs(self):
        none = sp.evaluate({"IntradayBatches": 0, "UpdatesPerBatch": 0})
        self.assertEqual(none.value("HeapIntraday"), 0)
        self.assertEqual(none.value("DiskIntradayLakeGB"), 0)
        half = sp.evaluate({"UpdatesPerBatch": 5_850})
        full = sp.evaluate()
        self.assertLess(half.value("HeapIntradayTrades"), full.value("HeapIntradayTrades"))
        self.assertAlmostEqual(half.value("DiskIntradayLakeGB") * 2, full.value("DiskIntradayLakeGB"), places=6)

    def test_the_explain_tab_states_the_figures(self):
        text = self.parts["xl/worksheets/sheet4.xml"]
        for figure in ("-Xmx10g", "3,584", "24 GB", "450 GB", "8 cores", "-Xmx17g", "6,464", "48 GB", "700 GB", "12 cores", "183.3", "1,602", "2,186", "5,884", "6,797"):
            self.assertIn(figure, text)

    def test_the_command_line_and_the_settings(self):
        by_name = {n: self.values.get(t) for n, t in self.names.items()}
        self.assertTrue(by_name["JvmCommand"].startswith("MALLOC_ARENA_MAX=2 java -Xmx10g -Xms10g -XX:+UseG1GC -XX:G1PeriodicGCInterval=60000"))
        self.assertTrue(by_name["JvmCommand"].endswith(f"-jar drishti-server-{sp._version()}-exec.jar"))
        self.assertNotIn("  ", by_name["JvmCommand"])
        self.assertEqual(by_name["JvmXmx"], "-Xmx10g")
        self.assertNotIn("VERSION", by_name["JvmCommand"])

    def test_every_key_is_documented(self):
        docs = {n: (sp.ROOT / "docs" / n).read_text(encoding="utf-8") for n in
                ("admin/CONFIGURATION.md", "admin/PERFORMANCE.md", "admin/OPERATIONS.md", "architecture/RESOURCES_AND_SCALE.md", "architecture/CLUSTER_DEPLOYMENT.md")}
        conf = docs["admin/CONFIGURATION.md"]
        for name, key, _f, _u, env, src, _w, _c in sp.SETTINGS:
            rows = [line for line in conf.splitlines() if line.startswith(f"| `{key}` |")]
            self.assertTrue(rows, f"{key} is not in CONFIGURATION.md")
            if src == "docs":
                self.assertTrue(any(f"`{env}`" in line for line in rows), f"{key}: CONFIGURATION.md does not name {env}")
            else:
                self.assertEqual(env, "DRISHTI_" + key[len("drishti."):].upper().replace(".", "_").replace("-", ""), key)
        ev = sp.evaluate({"JavaVersion": 25, "Positions": 4_000_000, "Trades": 4_000_000, "Collector": "auto"})
        standard = {"Xmx", "Xms", "UseG1GC"}
        for name, _flag, _f, _cond, _why, src in sp.JVM:
            for value in (sp.evaluate().value(name), ev.value(name)):
                for flag in re.findall(r"-XX:[+-]?([A-Za-z0-9]+)|-X(log:gc|m[xs])|-D([a-z][\w.-]+)", value):
                    flag = next(f for f in flag if f)
                    if flag in standard or src == "suggested":
                        continue
                    self.assertTrue(any(flag in d for d in docs.values()), f"{name}: {flag} is in no configuration document")

    def test_the_yaml_block_is_one_nested_tree(self):
        by_name = {n: self.values.get(t) for n, t in self.names.items()}
        block = by_name["YamlBlock"]
        lines = block.split("\n")
        self.assertEqual(lines[0], "drishti:")
        self.assertEqual(sum(1 for line in lines if line == "  var:"), 1)
        self.assertIn("    hot-dates: 10", lines)
        self.assertIn("      max-disk-gb: 58", lines)
        self.assertNotIn("  ccr:", lines)
        on = sp.evaluate({"ExposureOn": 1}).value("YamlBlock").split("\n")
        self.assertIn("  ccr:", on)
        self.assertIn("      hot-dates: 10", on)

    def test_the_evaluator_follows_excel(self):
        ev = sp.Evaluator({"A_": 2, "B_": "=A_*3", "T_": "auto"})
        self.assertEqual(ev.eval("B_^2+1"), 37)
        self.assertEqual(ev.eval('IF(T_="AUTO","x"&B_,"y")'), "x6")
        self.assertEqual(ev.eval("CEILING(16.01,1)"), 17)
        self.assertEqual(ev.eval("MAX(1,MIN(5,3))"), 3)

    def test_the_committed_workbook_is_current(self):
        self.assertEqual(sp.main(["--check"]), 0, "run python3 tools/sizing_planner.py")


if __name__ == "__main__":
    unittest.main()
