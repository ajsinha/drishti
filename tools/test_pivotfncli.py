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

"""Tests of `drishti.py pivot functions|explain|whatif` (docs/guides/PIVOT_GUIDE.md) against an in-process stand-in for the server: the
harness report printed, a save, an approval's path and body, an explanation whose steps are printed with the residual, and a what-if's
overlay built from the flags."""
import json
import pathlib
import sys
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from test_drishti_cli import FakeServer, run  # noqa: E402

HARNESS = {"definition": {"ref": "fn:asha/wret@0"}, "harness": {"function": "fn:asha/wret@0", "kind": "mergeable", "passed": True, "onePass": 0.01,
           "rows": 3, "nsPerRow": 120.0, "stateBytes": 60, "checks": [{"check": "batches and splits", "passed": True, "detail": "35 runs"}]},
           "playground": {"steps": [{"what": "part 1 (rows 1 to 1)", "value": 0.02, "bytes": 60}]}}
EXPLAIN = {"words": "sum(NOTIONAL) moved from 10 to 13 (+3).", "method": "each member's own change", "difference": 3.0,
           "steps": [{"member": "USD", "cause": "changed", "step": 2.0, "cumulative": 2.0}, {"member": "EUR", "cause": "new", "step": 1.0,
                                                                                                 "cumulative": 3.0}],
           "residual": {"value": 0.0, "name": "rounding (floating point)"}}


class PivotFunctions(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.httpd = ThreadingHTTPServer(("127.0.0.1", 0), FakeServer)
        cls.url = f"http://127.0.0.1:{cls.httpd.server_address[1]}"
        threading.Thread(target=cls.httpd.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()

    def setUp(self):
        FakeServer.requests, FakeServer.routes = [], {}
        self.env = {"DRISHTI_SERVER": self.url, "DRISHTI_TOKEN": "drk_secret"}
        self.dir = tempfile.TemporaryDirectory()
        d = pathlib.Path(self.dir.name)
        (d / "wret.json").write_text(json.dumps({"name": "wret", "language": "formula", "code": "sum(r * w) / sum(w)", "inputs": ["r", "w"]}))
        (d / "rows.csv").write_text("r,w,g\n0.01,1,A\n0.02,2,B\n-0.01,3,A\n")
        self.fn, self.rows = str(d / "wret.json"), str(d / "rows.csv")

    def tearDown(self):
        self.dir.cleanup()

    def body(self, i=-1):
        return json.loads(FakeServer.requests[i][5] or b"{}")

    def test_test_prints_the_harness_and_sends_the_sample(self):
        FakeServer.routes[("POST", "/api/v1/pivot/functions/test")] = (200, HARNESS)
        code, out, _ = run("pivot", "functions", "test", self.fn, "--sample", self.rows, "--group", "g", env=self.env)
        self.assertEqual(0, code)
        self.assertIn("PASSED: fn:asha/wret@0 (mergeable)", out)
        self.assertIn("batches and splits", out)
        b = self.body()
        self.assertEqual(3, len(b["sample"]))
        self.assertEqual(0.02, b["sample"][1]["r"])
        self.assertEqual("g", b["group"])

    def test_approving_is_not_here_any_more(self):
        code, _, _ = run("pivot", "functions", "approve", "asha/wret@1", "--reason", "reviewed", env=self.env)
        self.assertEqual(2, code)                                         # it is `drishti.py approvals approve function asha:wret@1`

    def test_explain_prints_steps_that_add_up(self):
        FakeServer.routes[("POST", "/api/v1/explain")] = (200, {"kind": "pivot", "why": {"available": True, "answer": EXPLAIN}})
        code, out, _ = run("pivot", "explain", "--source", "trade", "--rows", "DESK", "--values", "sum(NOTIONAL)", "--cell", "DESK=RATES",
                           "--other-cell", "DESK=FX", "--by", "CURRENCY", env=self.env)
        self.assertEqual(0, code)
        self.assertIn("moved from 10 to 13", out)
        self.assertRegex(out, r"EUR\s+new")
        b = self.body()
        self.assertEqual({"DESK": "RATES"}, b["from"])
        self.assertEqual({"DESK": "FX"}, b["to"])
        self.assertEqual("CURRENCY", b["by"])
        self.assertEqual("trade", b["view"]["source"])
        self.assertEqual(("pivot", "cell", "why"), (b["kind"], b["measure"], b["part"]))      # the one Explain, not the old pivot/explain

    def test_whatif_builds_the_overlay_from_the_flags(self):
        FakeServer.routes[("POST", "/api/v1/pivot/whatif")] = (200, {"diff": {"*|*": {"delta": {"S": 5.0}}}})
        code, out, _ = run("pivot", "whatif", "--view", "asha/desk-risk", "--exclude", "BOOK=B-17", "--scale", "PRICE=1.1@DESK=RATES",
                           env=self.env)
        self.assertEqual(0, code)
        self.assertIn("1 cell(s) change", out)
        w = self.body()["whatIf"]
        self.assertEqual({"BOOK": ["B-17"]}, w["exclude"])
        self.assertEqual([{"field": "PRICE", "scale": 1.1, "where": {"DESK": ["RATES"]}}], w["changes"])


if __name__ == "__main__":
    unittest.main()
