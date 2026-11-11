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


class FailedIds(unittest.TestCase):
    def test_the_loadgroup_suffix_is_dropped_so_the_rerun_finds_the_test(self):
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "out.txt"
            f.write_text("FAILED drishti-console/tests/test_a_browser.py::test_x[1@2]@test_a_browser.py - AssertionError\n"
                         "ERROR drishti-console/tests/test_b_browser.py::test_y - boom\n", encoding="utf-8")
            self.assertEqual(P.failed_ids([f]), ["drishti-console/tests/test_a_browser.py::test_x[1@2]",
                                                 "drishti-console/tests/test_b_browser.py::test_y"])


class LaneEstimate(unittest.TestCase):
    def test_spread_work_fills_the_workers_after_the_groups(self):
        files = [("g1", 100.0), ("g2", 50.0), ("s", 90.0)]
        self.assertAlmostEqual(P.lane_makespan(files, {"s"}, 2), 120.0)          # 100 | 50 + 70 ... then 20 more each: 120 | 120
        self.assertAlmostEqual(P.lane_makespan(files, set(), 2), 140.0)          # by file: 100 | 50 + 90

    def test_the_render_suites_are_spread(self):
        spread = P.spread_files(ROOT / "drishti-console" / "tests")
        self.assertIn("drishti-console/tests/test_render_parity_browser.py", spread)


class QuickChecks(unittest.TestCase):
    """The quick file checks (tests marked static) run in the gate; the lanes leave the files that are only that out."""

    def test_the_gate_finds_the_guards(self):
        console = P.static_files("console")
        for name in ("test_java_launch_guard.py", "test_asset_names.py", "test_branding_guard.py", "test_under_construction.py",
                     "test_browser_rules.py", "test_scratch_ports.py"):
            self.assertIn(f"drishti-console/tests/{name}", console)
        self.assertIn("tools/test_readmes.py", P.static_files("tools"))

    def test_a_file_of_quick_checks_is_in_no_lane_and_a_mixed_file_stays(self):
        with tempfile.TemporaryDirectory() as d:
            t = Path(d)
            (t / "test_scan.py").write_text("import pytest\n\npytestmark = pytest.mark.static\n\ndef test_a():\n    pass\n", encoding="utf-8")
            (t / "test_mixed.py").write_text("import pytest\n\n@pytest.mark.static\ndef test_a():\n    pass\n\ndef test_b(client):\n    pass\n",
                                             encoding="utf-8")
            (t / "test_plain.py").write_text("def test_a():\n    pass\n", encoding="utf-8")
            lanes = P.plan_lanes(t, {}, set())
            names = sorted(Path(p).name for p, _ in lanes["other"] + lanes["browser"])
            self.assertEqual(names, ["test_mixed.py", "test_plain.py"])
            self.assertEqual(sorted(Path(p).name for p in P.static_files("console", t)), ["test_mixed.py", "test_scan.py"])


class Monitor(unittest.TestCase):
    def test_kinds(self):
        self.assertEqual(M.classify("/usr/lib/jvm/java-21/bin/java -Xmx768m -jar kernel/drishti-server/target/drishti-server-2.9.11-exec.jar"), "server_jvm")
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


def test_every_subcommand_can_be_run():
    """Every subcommand sets the `run` default `main` calls (the drill's Java 25 lane failed on one that set `func`)."""
    import argparse
    import drill_plan as dp
    seen = []
    real = argparse.ArgumentParser.parse_args

    def spy(self, *a, **k):
        seen.append(self)
        return real(self, *a, **k)
    for cmd, extra in (("wait-memory", ["--gb", "0", "--limit", "1"]), ("workers", ["--mem-gb", "64", "--threads", "8"])):
        argparse.ArgumentParser.parse_args = spy
        try:
            assert dp.main([cmd, *extra]) == 0
        finally:
            argparse.ArgumentParser.parse_args = real
