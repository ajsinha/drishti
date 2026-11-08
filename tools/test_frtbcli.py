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

"""`drishti.py sens|frtb|play frtb`: each command asks the right API with the switches and overrides passed through, prints the server's
figures, writes the workbook, and a recipe that does not reproduce exits 1 (a fake Api).

    python3 -m unittest -q tools/test_frtbcli.py
"""
from __future__ import annotations

import argparse
import contextlib
import importlib.util
import io
import os
import tempfile
import unittest
import urllib.parse
from pathlib import Path

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("frtbcli", HERE / "frtbcli.py")
X = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(X)

SA = {"scope": "EXAMPLE", "canonical": "EXAMPLE", "date": None, "currency": "USD", "regulatory": True, "binding": "low",
      "classes": [{"class": "GIRR", "measure": "DELTA", "low": 110555.42, "medium": 101286.72, "high": 91079.64}],
      "sbm": {"low": 290555.42, "medium": 281286.72, "high": 271079.64}, "SBM": 290555.42, "DRC": 126177.32, "RRAO": 150000.0, "total": 566732.73}


class Err(Exception):
    def __init__(self, msg, code):
        super().__init__(msg)
        self.code = code


class FakeApi:
    calls: list = []
    reproduced = "reproduced to the cent: the inputs and every figure equal the recipe's"

    @classmethod
    def from_args(cls, a):
        return cls()

    def json(self, method, path, body=None):
        FakeApi.calls.append((method, path))
        if path.startswith("/api/v1/risk/frtb/playground/recipes/"):
            return {**SA, "reproduced": FakeApi.reproduced}
        if path.startswith("/api/v1/risk/frtb/playground/recipes"):
            return {"id": "FR-AB12CD34-1", "command": "PLAY FRTB RUN FR-AB12CD34-1", "knownAt": "2026-11-02T00:00:00Z"}
        if path.startswith("/api/v1/risk/frtb/playground"):
            return {**SA, "choices": [{"name": "girr-correlation", "value": "table", "default": "table", "rule": "MAR21.46"}],
                    "compare": {"words": "Girr-correlation: table → exact raises the total by 24.37"}}
        if path.startswith("/api/v1/risk/frtb/sa/contributions"):
            return {"scope": "BANK", "by": "DESK", "total": 10.0, "addsUp": True, "rows": [{"key": "DESK-A", "contribution": 10.0}]}
        if path.startswith("/api/v1/risk/sensitivities"):
            return {"canonical": "DESK:RATES", "date": "2026-09-30", "currency": "USD", "records": 3,
                    "rows": [{"key": "GIRR USD", "measure": "DV01", "value": -1500.0, "positions": 2}]}
        return SA

    def request(self, method, path, body=None, extra=None):
        FakeApi.calls.append((method, path))
        return 200, b"PK\x03\x04 a workbook"


def run(*argv) -> tuple[int, str]:
    srv = argparse.ArgumentParser(add_help=False)
    srv.add_argument("--json", action="store_true")
    root = argparse.ArgumentParser()
    groups = root.add_subparsers(dest="group")
    X.register(groups, srv, {"Api": FakeApi, "CliError": Err, "say_json": print})
    a = root.parse_args(list(argv))
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = a.func(a, [])
    return code, out.getvalue()


def query(path: str) -> list:
    return urllib.parse.parse_qsl(path.split("?", 1)[1]) if "?" in path else []


class FrtbCli(unittest.TestCase):
    def setUp(self):
        FakeApi.calls = []
        FakeApi.reproduced = "reproduced to the cent: the inputs and every figure equal the recipe's"

    def test_frtb_sa_passes_the_switches_and_prints_the_figures(self):
        code, out = run("frtb", "sa", "EXAMPLE", "--girr-correlation", "exact", "--override", "GIRR.delta.across-buckets=0.6")
        self.assertEqual(code, 0)
        q = query(FakeApi.calls[-1][1])
        self.assertIn(("girrCorrelation", "exact"), q)
        self.assertIn(("override", "GIRR.delta.across-buckets=0.6"), q)
        self.assertIn("total 566,732.73", out)
        self.assertIn("110,555.42", out)

    def test_sens_and_contributions(self):
        code, out = run("sens", "show", "DESK:RATES", "--by", "BUCKET")
        self.assertEqual(code, 0)
        self.assertIn(("by", "BUCKET"), query(FakeApi.calls[-1][1]))
        self.assertIn("GIRR USD", out)
        code, out = run("frtb", "contributions", "BANK", "--by", "DESK")
        self.assertIn("add up: True", out)

    def test_play_frtb_compares_writes_saves_and_replays(self):
        code, out = run("play", "frtb", "EXAMPLE", "--compare", "girr-correlation=exact")
        self.assertIn(("compare", "girr-correlation=exact"), query(FakeApi.calls[-1][1]))
        self.assertIn("raises the total", out)
        with tempfile.TemporaryDirectory() as d:
            f = os.path.join(d, "cases.xlsx")
            code, out = run("play", "frtb", "EXAMPLE", "--xlsx", f)
            self.assertEqual(Path(f).read_bytes()[:2], b"PK")
        code, out = run("play", "frtb", "EXAMPLE", "--save")
        self.assertIn("saved recipe FR-AB12CD34-1", out)
        code, out = run("play", "frtb", "--recipe", "FR-AB12CD34-1")
        self.assertEqual(code, 0)
        FakeApi.reproduced = "not reproduced: Total: recorded 1.0, now 2.0"
        code, out = run("play", "frtb", "--recipe", "FR-AB12CD34-1")
        self.assertEqual(code, 1)
        with self.assertRaises(Err):
            run("play", "frtb")


if __name__ == "__main__":
    unittest.main()
