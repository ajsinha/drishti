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
"""`drishti.py play exp`: asks the right API with the choices passed through, prints the server's figures, lists the export's sizes and
writes the workbook and the template (a fake Api). `play exp` joins the `play` group frtbcli makes.

    python3 -m unittest -q tools/test_expplaycli.py
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


def load(name):
    spec = importlib.util.spec_from_file_location(name, HERE / f"{name}.py")
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


E = load("expplaycli")
F = load("frtbcli")

ANSWER = {"nettingSet": "EXAMPLE-X1", "counterparty": "CP-EXAMPLE", "businessDate": "2026-09-30", "runId": "X1", "calibration": "hand-built", "paths": 5,
          "runPaths": 5, "setTrades": 2, "choices": {"confidences": [0.8, 0.95], "rankRule": "kth", "collateralModel": "lagged", "contributionDate": "6M"},
          "figures": {"ce": 0.4, "horizonYears": 1.0, "epe": 1.2, "eepe": 1.4, "peakEe": 1.6, "peakEeTenor": "6M", "eadImm": 1.96,
                      "peakPfe": [{"q": 0.8, "peakPfe": 2.0, "tenor": "6M"}, {"q": 0.95, "peakPfe": 2.5, "tenor": "9M"}]},
          "profile": [{"tenor": "3M", "t": 0.25, "ee": 0.8, "ene": 0.0, "pfe": [1.0, 1.0], "eee": 0.8}],
          "contributions": {"tenor": "6M", "ee": 1.6, "sum": 1.6, "shown": 2, "of": 2,
                            "rows": [{"label": "A · trade A", "eeShare": 1.1333333, "pct": 70.8}, {"label": "B · trade B", "eeShare": 0.4666667, "pct": 29.2}]},
          "runFigures": {}}
ESTIMATE = {"nettingSet": "NS-X", "setTrades": 600, "paths": 5000, "estimates": [
    {"choice": "50", "cells": 7_000_000, "size": "80 MB", "recalc": "3 min", "apps": ["Excel"], "refused": None},
    {"choice": "All", "cells": 80_000_000, "size": "1 GB", "recalc": "40 min", "apps": [], "refused": "80,000,000 cells: more than the 20,000,000"}]}


class Err(Exception):
    def __init__(self, msg, code):
        super().__init__(msg)
        self.code = code


class FakeApi:
    calls: list = []

    @classmethod
    def from_args(cls, a):
        return cls()

    def json(self, method, path, body=None):
        FakeApi.calls.append((method, path))
        if path.startswith("/api/v1/exposure/playground/estimate"):
            return ESTIMATE
        return ANSWER

    def request(self, method, path, body=None, extra=None):
        FakeApi.calls.append((method, path))
        return 200, b"PK\x03\x04 a workbook"


def run(*argv) -> tuple[int, str]:
    srv = argparse.ArgumentParser(add_help=False)
    srv.add_argument("--json", action="store_true")
    root = argparse.ArgumentParser()
    groups = root.add_subparsers(dest="group")
    ctx = {"Api": FakeApi, "CliError": Err, "say_json": print}
    F.register(groups, srv, ctx)
    E.register(groups, srv, ctx)
    a = root.parse_args(list(argv))
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = a.func(a, [])
    return code, out.getvalue()


def query(path: str) -> list:
    return urllib.parse.parse_qsl(path.split("?", 1)[1]) if "?" in path else []


class ExpPlayCli(unittest.TestCase):
    def setUp(self):
        FakeApi.calls = []

    def test_play_exp_passes_the_choices_and_prints_the_figures(self):
        code, out = run("play", "exp", "EXAMPLE", "--q", "0.8,0.95", "--rule", "interpolated", "--collateral", "current", "--contribution-date", "9M",
                        "--paths", "200", "--date", "2026-09-30")
        self.assertEqual(code, 0)
        self.assertTrue(FakeApi.calls[-1][1].startswith("/api/v1/exposure/playground?"))
        q = query(FakeApi.calls[-1][1])
        for pair in (("nettingSet", "EXAMPLE"), ("q", "0.8,0.95"), ("rule", "interpolated"), ("collateral", "current"), ("date", "9M"), ("paths", "200"),
                     ("asOf", "2026-09-30")):
            self.assertIn(pair, q)
        self.assertIn("EPE 1.2000", out)
        self.assertIn("peak PFE 95% 2.5000 (9M)", out)
        self.assertIn("A · trade A", out)

    def test_the_estimate_the_workbook_and_the_template(self):
        code, out = run("play", "exp", "NS-X", "--estimate")
        self.assertIn("refused: 80,000,000 cells", out)
        self.assertIn("opens in Excel", out)
        with tempfile.TemporaryDirectory() as d:
            f = os.path.join(d, "x.xlsx")
            code, out = run("play", "exp", "NS-X", "--trades", "50", "--xlsx", f)
            self.assertEqual(code, 0)
            self.assertTrue(FakeApi.calls[-1][1].startswith("/api/v1/exposure/playground.xlsx?"))
            self.assertIn(("trades", "50"), query(FakeApi.calls[-1][1]))
            with open(f, "rb") as fh:
                self.assertTrue(fh.read().startswith(b"PK"))
            t = os.path.join(d, "t.xlsx")
            run("play", "exp", "--template", t)
            self.assertEqual(FakeApi.calls[-1][1], "/api/v1/exposure/playground/template")
            self.assertTrue(os.path.exists(t))

    def test_no_netting_set_is_a_usage_error(self):
        with self.assertRaises(Err) as e:
            run("play", "exp")
        self.assertEqual(e.exception.code, 2)


if __name__ == "__main__":
    unittest.main()
