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

"""The drill planner's worker sizing, per-file budgets and lane estimate, and the memory monitor's summary
(docs/architecture/TEST_HARNESS.md). Standard library only: ``python3 -m unittest tools/drillkit/test_drill_plan.py``."""
from __future__ import annotations

import csv
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import drill_monitor as M  # noqa: E402
import drill_plan as P  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]


class WorkerSizing(unittest.TestCase):
    def test_memory_decides_within_the_caps(self):
        self.assertEqual(P.browser_workers(mem_gb=49, threads=24, per_worker_gb=2.5, reserve_gb=24), 10)
        self.assertEqual(P.browser_workers(mem_gb=200, threads=24, per_worker_gb=2.5, reserve_gb=24), 12)   # the ports' cap
        self.assertEqual(P.browser_workers(mem_gb=200, threads=8, per_worker_gb=2.5, reserve_gb=24), 6)     # threads less two
        self.assertEqual(P.browser_workers(mem_gb=10, threads=24, per_worker_gb=2.5, reserve_gb=24), 1)     # never none

    def test_the_cap_is_the_scratch_ports_cap(self):
        text = (ROOT / "drishti-console" / "tests" / "scratch_ports.py").read_text(encoding="utf-8")
        low = int(text.split("LOW, STRIDE = ")[1].split(",")[0])
        stride = int(text.split("LOW, STRIDE = ")[1].split(",")[1].split()[0])
        self.assertEqual(P.MAX_WORKERS, (32768 - low) // stride)


class Budgets(unittest.TestCase):
    def test_files_over_the_budget_are_flagged_and_listed_ones_keep_their_reason(self):
        rows = P.over_budget({"a.py": 500.0, "b.py": 100.0, "c.py": 900.0}, 300, {"a.py": "one server for its four tests"})
        self.assertEqual(rows, [("c.py", 900.0, ""), ("a.py", 500.0, "one server for its four tests")])

    def test_the_exceptions_file_reads_path_and_reason(self):
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "b.txt"
            f.write_text("# header\n\nx/test_a.py  # its reason\n", encoding="utf-8")
            self.assertEqual(P.load_exceptions(f), {"x/test_a.py": "its reason"})

    def test_the_committed_exceptions_name_files_that_exist(self):
        for name in P.load_exceptions(ROOT / "tools" / "drill-budgets.txt"):
            self.assertTrue((ROOT / name).exists(), name)


class LaneEstimate(unittest.TestCase):
    def test_spread_work_fills_the_workers_after_the_groups(self):
        files = [("g1", 100.0), ("g2", 50.0), ("s", 90.0)]
        self.assertAlmostEqual(P.lane_makespan(files, {"s"}, 2), 120.0)          # 100 | 50 + 70 ... then 20 more each: 120 | 120
        self.assertAlmostEqual(P.lane_makespan(files, set(), 2), 140.0)          # by file: 100 | 50 + 90

    def test_the_render_suites_are_spread(self):
        spread = P.spread_files(ROOT / "drishti-console" / "tests")
        self.assertIn("drishti-console/tests/test_render_parity_browser.py", spread)


class Monitor(unittest.TestCase):
    def test_kinds(self):
        self.assertEqual(M.classify("/usr/lib/jvm/java-21/bin/java -Xmx768m -jar drishti-server/target/drishti-server-2.9.11-exec.jar"), "server_jvm")
        self.assertEqual(M.classify("/usr/lib/jvm/java-21/bin/java -cp surefire.jar"), "other_jvm")
        self.assertEqual(M.classify("/x/chrome-headless-shell --type=renderer"), "chromium")
        self.assertEqual(M.classify("python run_drishti_web.py --server.port=1"), "console")

    def test_the_summary_takes_the_peaks(self):
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "m.csv"
            with f.open("w", newline="") as fh:
                w = csv.DictWriter(fh, fieldnames=M.FIELDS)
                w.writeheader()
                for t, avail, n in ((0, 40.0, 2), (5, 30.0, 5), (10, 35.0, 3)):
                    row = {k: 0 for k in M.FIELDS}
                    row.update(t=t, avail_gb=avail, server_jvm_n=n, server_jvm_gb=n * 0.9)
                    w.writerow(row)
            s = M.summarise(f)
            self.assertEqual((s["min_avail_gb"], s["max_server_jvm_n"], s["seconds"]), (30.0, 5, 10))

    def test_a_sample_reads_this_machine(self):
        row = M.sample()
        self.assertGreater(row["avail_gb"], 0)


if __name__ == "__main__":
    unittest.main()
