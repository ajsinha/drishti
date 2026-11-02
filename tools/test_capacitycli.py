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


"""`drishti.py admin capacity`: the planner the server serves evaluated here as the workbook is (the answers, the warnings, the YAML block,
the command line), --set and --live change the inputs, --xlsx asks the server for the workbook with the inputs given, and a bad --set is a
usage error naming the inputs (a fake Api serving capacity/planner.yaml). PyYAML, as tools/sizing_planner.py.

    python3 -m unittest -q tools/test_capacitycli.py
"""
from __future__ import annotations

import argparse
import contextlib
import importlib.util
import io
import os
import shutil
import sys
import unittest
import urllib.parse
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import sizing_planner as sp  # noqa: E402

SPEC = importlib.util.spec_from_file_location("capacitycli", HERE / "capacitycli.py")
X = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(X)

LIVE = {"measuredAt": "2026-11-01T22:00:00Z", "server": {"heapMaxMb": 8192, "heapAfterGcMb": 4310, "rssMb": 15250, "ramMb": 32768, "cpus": 24, "java": "21.0.5"},
        "var": {"positions": 450000, "newestDate": "2026-10-30", "openDates": 7, "hotDates": 10, "openHeapMb": 1228, "openHeapLimitMb": 819},
        "disk": {"freeBytes": 209 << 30, "totalBytes": 500 << 30, "guardBytes": 50 << 30, "path": "/opt/drishti/data"},
        "load": {"usersActive": 41, "usersPeakToday": 47, "requestsPerSecond": 60.0, "requestsPerSecondPeakToday": 96.0},
        "inputs": {"Positions": 450000, "Trades": 450000, "HotDates": 10, "Users": 47, "PeakRps": 96.0, "JavaVersion": 21, "DataFolder": "/opt/drishti/data"}}


class Err(Exception):
    def __init__(self, msg, code):
        super().__init__(msg)
        self.code = code


class FakeApi:
    calls = []

    @classmethod
    def from_args(cls, a):
        return cls()

    def json(self, method, path, body=None):
        FakeApi.calls.append((method, path))
        if path == "/api/v1/admin/capacity":
            return sp.PLAN
        if path == "/api/v1/admin/capacity/live":
            return LIVE
        raise AssertionError(path)

    def request(self, method, path, body=None, extra=None):
        FakeApi.calls.append((method, path, extra))
        return 200, b"PK\x03\x04 a workbook"


def run(*argv) -> tuple[int, str, object]:
    srv = argparse.ArgumentParser(add_help=False)
    srv.add_argument("--json", action="store_true")
    root = argparse.ArgumentParser()
    groups = root.add_subparsers(dest="group")
    said = []
    X.register(groups, srv, {"Api": FakeApi, "CliError": Err, "say_json": said.append})
    a = root.parse_args(["admin", "capacity", *argv])
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = a.func(a, [])
    return code, out.getvalue(), said[0] if said else None


class CapacityCli(unittest.TestCase):
    def setUp(self):
        FakeApi.calls = []

    def test_the_plan_at_the_defaults_is_the_workbooks(self):
        code, out, _ = run()
        self.assertEqual(code, 0)
        self.assertIn("-Xmx                           10 GB", out)
        self.assertIn("RAM recommended                24 GB", out)
        self.assertIn("disk recommended               450 GB", out)
        self.assertIn("      open-heap-mb: 4032", out)
        self.assertIn("MALLOC_ARENA_MAX=2 java -Xmx10g -Xms10g -XX:+UseG1GC", out)
        self.assertEqual(FakeApi.calls, [("GET", "/api/v1/admin/capacity")])

    def test_set_changes_an_input_and_json_gives_every_result(self):
        code, _, said = run("--set", "Trades=1,000,000", "--set", "Positions=1000000", "--set", "Collector=ZGC", "--json")
        self.assertEqual(code, 0)
        self.assertEqual(said["inputs"], {"Trades": 1_000_000.0, "Positions": 1_000_000.0, "Collector": "ZGC"})
        self.assertEqual(said["results"]["GcChoice"], "ZGC")
        self.assertGreater(said["results"]["XmxGB"], 10)
        self.assertIn("-XX:+UseZGC", said["results"]["JvmCommand"])

    def test_live_puts_the_measured_figures_in_the_inputs_and_prints_them(self):
        code, out, _ = run("--live", "--set", "Users=80")
        self.assertEqual(code, 0)
        self.assertIn("LIVE  measured 2026-11-01T22:00:00Z", out)
        self.assertIn("450,000 positions on 2026-10-30, 7 of 10 dates open", out)
        self.assertIn("209 GB free of 500 GB", out)
        _, _, said = run("--live", "--set", "Users=80", "--json")
        self.assertEqual(said["inputs"]["Trades"], 450000.0)
        self.assertEqual(said["inputs"]["Users"], 80.0)
        self.assertEqual(said["inputs"]["DataFolder"], "/opt/drishti/data")
        self.assertIn("/opt/drishti/data/dumps", said["results"]["JvmCommand"])

    def test_xlsx_asks_the_server_with_the_inputs_given(self):
        tmp = sp.ROOT / "data" / "tmp" / f"capacity-cli-{os.getpid()}"
        tmp.mkdir(parents=True, exist_ok=True)
        try:
            f = tmp / "plan.xlsx"
            code, out, _ = run("--xlsx", str(f), "--inputs", "both", "--set", "Trades=450000")
            self.assertEqual(code, 0)
            self.assertTrue(f.read_bytes().startswith(b"PK"))
            method, path, extra = FakeApi.calls[-1]
            self.assertEqual(path.split("?")[0], "/api/v1/admin/capacity.xlsx")
            self.assertEqual(urllib.parse.parse_qs(path.split("?")[1]), {"inputs": ["both"], "Trades": ["450000"]})
            self.assertEqual(extra, {"Accept": "*/*"})
            self.assertIn("audited as capacity-workbook-downloaded", out)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    def test_a_bad_set_is_a_usage_error_naming_the_inputs(self):
        with self.assertRaises(Err) as e:
            run("--set", "Tardes=1")
        self.assertEqual(e.exception.code, 2)
        self.assertIn("Trades", str(e.exception))
        with self.assertRaises(Err):
            run("--set", "Trades=many")
        with self.assertRaises(Err):
            run("--set", "Trades")


if __name__ == "__main__":
    unittest.main()
