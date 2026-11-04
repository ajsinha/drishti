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

"""tools/bench/pivot_bench.py without servers or large data: where it may write (never /tmp, a tmpfs or the repository; 150 GB free),
the rows (40 columns, the same for the same seed), the day files, a generation that grows a lake by appending days and stops at the
size, the run matrix, and the report's measured and EXTRAPOLATED tables."""
import gzip
import json
import sys
import tempfile
import unittest
from datetime import date
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pivot_bench as pb  # noqa: E402


class PivotBenchTest(unittest.TestCase):
    def test_data_never_goes_to_tmp_a_tmpfs_or_the_repository_and_needs_150_gb_free(self):
        self.assertIn("/tmp", pb.check_data_dir(Path("/tmp/drishti-bench")))
        self.assertIn("/dev/shm", pb.check_data_dir(Path("/dev/shm/x")))
        self.assertIn("inside the repository", pb.check_data_dir(pb.ROOT / "data" / "bench"))
        with mock.patch.object(pb, "filesystem_type", return_value="ext4"), \
                mock.patch.object(pb.shutil, "disk_usage", return_value=mock.Mock(free=120e9)):
            self.assertIn("under 150 GB", pb.check_data_dir(Path("/data/bench")))
            self.assertIn("under 150 GB", pb.check_data_dir(Path("/data/bench"), min_free_gb=10))   # never below the floor
        with mock.patch.object(pb, "filesystem_type", return_value="ext4"), \
                mock.patch.object(pb.shutil, "disk_usage", return_value=mock.Mock(free=400e9)):
            self.assertIsNone(pb.check_data_dir(Path("/data/bench")))
        with mock.patch.object(pb, "filesystem_type", return_value="tmpfs"):
            self.assertIn("memory-backed", pb.check_data_dir(Path("/data/bench")))

    def test_a_row_has_forty_columns_and_the_same_seed_makes_the_same_rows(self):
        import random
        a = pb.trade_row(random.Random(7), 12, date(2026, 10, 30), 1000)
        b = pb.trade_row(random.Random(7), 12, date(2026, 10, 30), 1000)
        self.assertEqual(a, b)
        self.assertEqual(list(a), pb.COLUMNS)
        self.assertEqual(len(pb.COLUMNS), 40)
        self.assertEqual(a["NOTIONAL_USD"], round(a["NOTIONAL"] * pb.FX[a["CURRENCY"]], 2))
        self.assertEqual(a["REGION"], pb.REGIONS[a["DESK"].split("-")[1]])

    def test_a_day_file_is_gzipped_json_lines(self):
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "day.jsonl.gz"
            self.assertEqual(pb.write_day(f, date(2026, 10, 30), 100, 25, 1, 50), 25)
            lines = gzip.open(f, "rt").read().splitlines()
            self.assertEqual(len(lines), 25)
            self.assertEqual(json.loads(lines[0])["TRADE_ID"], "BT-0000000100")
            self.assertTrue(all(json.loads(x)["BUSINESS_DATE"] == "2026-10-30" for x in lines))

    def test_generation_appends_days_until_the_size_and_a_larger_size_continues(self):
        with tempfile.TemporaryDirectory() as d:
            size = {"bytes": 0}
            loaded = []

            def load(f, lake, python):
                loaded.append(f.name)
                size["bytes"] += 1_000

            with mock.patch.object(pb, "check_data_dir", return_value=None), mock.patch.object(pb, "load_day", side_effect=load), \
                    mock.patch.object(pb, "dir_bytes", side_effect=lambda p: size["bytes"]), \
                    mock.patch.object(pb.shutil, "disk_usage", return_value=mock.Mock(free=400e9)):
                first = pb.generate(Path(d) / "bench", 3e-6, rows_per_day=10, log=lambda *_: None)    # 3,000 bytes: three days
                self.assertEqual(first["days"], 3)
                self.assertEqual(first["rows"], 30)
                second = pb.generate(Path(d) / "bench", 5e-6, rows_per_day=10, log=lambda *_: None)   # two more days appended
                self.assertEqual(second["days"], 5)
                self.assertEqual(len(loaded), 5)
                self.assertEqual(len(set(loaded)), 5, "no day is written twice")

    def test_the_plan_runs_one_node_only_up_to_25_gb_and_the_cluster_at_every_size(self):
        self.assertEqual(pb.stages(3), [5, 25, 75])
        self.assertEqual(pb.node_counts(5, 3), [1, 3])
        self.assertEqual(pb.node_counts(75, 3), [3])
        text = pb.plan(3)
        self.assertIn("75 GB lake (25.0 GB a node) | 3 node(s)", text)
        self.assertNotIn("75 GB lake (75.0 GB a node)", text)
        self.assertIn("at least 150 GB free", text)
        self.assertIn("extrapolated, never measured: 100 GB, 1,000 GB, 3,000 GB", text)

    def test_the_report_labels_every_larger_size_extrapolated(self):
        lines = []
        for gb, s in ((5, 2.0), (25, 6.0), (75, 16.0)):
            lines.append({"gb": gb, "nodes": 3, "cache": "cold", "rollups": "without", "view": "V1", "exact_asked": None, "first_s": 0.5,
                          "exact_s": s, "estimate_s": s, "rows_read": 1000, "cells": 10, "error": None})
        lines.append({"gb": 5, "nodes": 3, "cache": "cold", "rollups": "without", "view": "V5", "exact_asked": None, "error": "no function"})
        text = pb.report(lines)
        self.assertIn("### Measured", text)
        self.assertIn("EXTRAPOLATED: a straight line through the measured points above, not measured", text)
        self.assertIn("1,000 GB (EXTRAPOLATED) s", text)
        self.assertIn("| 3 | cold | without | V1 |  | 5, 25, 75 | ≈21 | ≈201 | ≈601 |", text)
        self.assertIn("Not answered: V5", text)


if __name__ == "__main__":
    unittest.main()
