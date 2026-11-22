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
"""The drop-in lane's own helpers (tools/drillkit/dropin.py): the incompatible JAR it makes, the answer it compares, the result table."""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dropin as D  # noqa: E402


class Helpers(unittest.TestCase):
    def test_the_pin_names_every_route_the_lane_calls(self):
        for key in ("platform", "engines", "ask", "health", "stats", "lifecycle", "packHealth", "packStats", "packLifecycle",
                    "connectorHealth", "connectorStats", "connectorLifecycle", "connectorRead"):
            self.assertIn(key, D.API, key)
        self.assertEqual(len(D.PIN["engineRef"]), 40)

    def test_a_jar_built_for_another_api(self):
        with tempfile.TemporaryDirectory() as t:
            src, dst = Path(t) / "a.jar", Path(t) / "b.jar"
            with zipfile.ZipFile(src, "w") as z:
                z.writestr("META-INF/drishti-engine/wmean.engine.yaml", 'id: wmean\napi: "0.1"\n')
                z.writestr("META-INF/drishti-engine/group-totals.engine.yaml", 'id: group-totals\napi: "0.1"\n')
            D.rewrite_jar(src, dst, "wmean", 'api: "0.1"', 'api: "0.9"')
            with zipfile.ZipFile(dst) as z:
                self.assertIn('"0.9"', z.read("META-INF/drishti-engine/wmean.engine.yaml").decode())
                self.assertIn('"0.1"', z.read("META-INF/drishti-engine/group-totals.engine.yaml").decode())
            with self.assertRaises(SystemExit):
                D.rewrite_jar(src, dst, "wmean", "nothing-here", "x")

    def test_the_recorded_answer_is_found_in_any_nesting_and_compared_exactly(self):
        want = D.PIN["answer"]
        body = {"status": "complete", "tables": {D.PIN["output"]: {"columns": want["columns"], "rows": [["B", 1, 2, 5], ["A", 2, 4, 17.5]]}}}
        self.assertTrue(D.same_figures(D.table_of(body), want))
        body["tables"][D.PIN["output"]]["rows"][1][3] = 17.5000001
        self.assertFalse(D.same_figures(D.table_of(body), want))
        self.assertFalse(D.same_figures(None, want))

    def test_counters_read_since_start(self):
        self.assertEqual(D.counters({"stats": {"sinceStart": {"asked": 3, "answered": 2, "refused": 1}}}), {"asked": 3, "answered": 2, "refused": 1, "failed": 0})

    def test_the_report_fails_on_one_failed_check_and_renders_a_table(self):
        r = D.Report()
        r.add("1 copy", "a", True)
        self.assertTrue(r.passed())
        r.add("2 suspend", "b", False, "why")
        self.assertFalse(r.passed())
        t = r.table()
        self.assertIn("FAIL", t)
        self.assertIn("1 of 2 checks passed", t)

    def test_the_fixture_files_exist(self):
        for f in ("wmean.engine.yaml", "pack.yaml", "dropin-files.connector.yaml", "licence-without-the-product.yaml", "dropin-observation.jsonl"):
            self.assertTrue((D.FIXTURES / f).is_file(), f)
        json.loads((D.HERE / "dropin.json").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
