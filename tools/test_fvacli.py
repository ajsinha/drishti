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
"""`drishti.py fva ...|play fva`: each command asks the right API with the choices passed through, prints the server's figures (never
computes one), and writes the workbook; `play fva` joins the `play` group frtbcli makes (a fake Api).

    python3 -m unittest -q tools/test_fvacli.py
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


V = load("fvacli")
F = load("frtbcli")

ANSWER = {"scope": "CPTY:CP-1", "label": "Alpha Fund", "businessDate": "2026-09-30", "runId": "XR-1", "calibration": "REAL_WORLD", "flags": ["REAL_WORLD_RUN"],
          "figures": {"fca": -6250.0, "fba": 3000.0, "fva": -3250.0}, "epeReading": {"flatSpread": True, "spread": 0.005, "life": 1.0, "epeLife": 1.25e6,
                                                                                    "product": 6250.0},
          "nettingSetCount": 2, "setsShown": True,
          "nettingSets": [{"nettingSet": "NS-X", "counterparty": "CP-1", "csa": "VM, 10d", "fca": -4750.0, "fba": 3000.0, "fva": -1750.0},
                          {"nettingSet": "NS-Y", "counterparty": "CP-1", "csa": "none", "fca": -1500.0, "fba": 0.0, "fva": -1500.0}],
          "terms": [{"k": 1, "date": "2026-12-12", "t": 0.2, "dt": 0.2, "df": 1.0, "sb": 0.005, "ee": 1.25e6, "ene": -2.5e5, "fca": -1250.0, "fba": 250.0}]}


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
        if path.startswith("/api/v1/fva/status"):
            return {"fundingSpread": "CRV-USD-FUNDING", "lendingSpread": "symmetric", "discountCurve": "CRV-USD-OIS", "eePoint": "end", "fba": True,
                    "fundingSurvival": False, "requireRiskNeutral": False, "businessDate": "2026-09-30", "flags": [],
                    "fundingCurve": {"id": "CRV-USD-FUNDING", "source": "drishti.ccr.fva.curves", "points": [{"tenor": "1Y", "t": 1.0, "spread": 0.0045}]}}
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
    V.register(groups, srv, ctx)
    a = root.parse_args(list(argv))
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = a.func(a, [])
    return code, out.getvalue()


def query(path: str) -> list:
    return urllib.parse.parse_qsl(path.split("?", 1)[1]) if "?" in path else []


class FvaCli(unittest.TestCase):
    def setUp(self):
        FakeApi.calls = []

    def test_fva_show_passes_the_choices_and_prints_the_server_figures(self):
        code, out = run("fva", "show", "CPTY:CP-1", "--spread", "0.006", "--fba", "off", "--ee-point", "mid", "--date", "2026-09-30")
        self.assertEqual(code, 0)
        path = FakeApi.calls[-1][1]
        self.assertTrue(path.startswith("/api/v1/fva/scope?"))
        q = query(path)
        for pair in (("scope", "CPTY:CP-1"), ("spread", "0.006"), ("fba", "off"), ("eePoint", "mid"), ("asOf", "2026-09-30")):
            self.assertIn(pair, q)
        self.assertIn("FVA -3,250.00", out)
        self.assertIn("NS-X", out)
        self.assertIn("REAL_WORLD_RUN", out)
        self.assertIn("CVA (planned)", out)

    def test_fva_sets_defaults_to_the_bank_and_status_prints_the_curve(self):
        code, out = run("fva", "sets")
        self.assertIn(("scope", "BANK"), query(FakeApi.calls[-1][1]))
        self.assertIn("NS-Y", out)
        code, out = run("fva", "status")
        self.assertIn("CRV-USD-FUNDING", out)
        self.assertIn("1Y 0.0045", out)

    def test_play_fva_prints_the_terms_and_writes_the_workbooks(self):
        code, out = run("play", "fva", "NSET:NS-X")
        self.assertTrue(FakeApi.calls[-1][1].startswith("/api/v1/fva/playground?"))
        self.assertIn("2026-12-12", out)
        with tempfile.TemporaryDirectory() as d:
            f = os.path.join(d, "x.xlsx")
            code, out = run("play", "fva", "NSET:NS-X", "--xlsx", f, "--mode", "paths")
            self.assertTrue(FakeApi.calls[-1][1].startswith("/api/v1/fva/playground.xlsx?"))
            self.assertIn(("mode", "paths"), query(FakeApi.calls[-1][1]))
            self.assertEqual(Path(f).read_bytes()[:2], b"PK")
            code, out = run("play", "fva", "--example", "--xlsx", f)
            self.assertEqual(FakeApi.calls[-1][1], "/api/v1/fva/playground/template")
        with self.assertRaises(Err):
            run("play", "fva")


if __name__ == "__main__":
    unittest.main()
