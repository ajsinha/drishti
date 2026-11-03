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

"""`drishti.py simm ...|play simm`: each command asks the right API with the switches, side and overrides passed through, prints the
server's figures, writes the workbook, and a recipe that does not reproduce exits 1 (a fake Api). `play simm` joins the `play` group
frtbcli makes.

    python3 -m unittest -q tools/test_simmcli.py
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


S = load("simmcli")
F = load("frtbcli")

ANSWER = {"SCOPE": "NS-X", "KIND": "netting set", "NETTING_SET": "NS-X", "COUNTERPARTY": "CP-X", "BUSINESS_DATE": "2026-09-30", "CALCULATION_CURRENCY": "USD",
          "SIMM_VERSION": "TOY-1", "REGULATORY": True, "IM_COLLECT": 426606.46, "IM_POST": 426606.46, "COLLECT_REGIME": "NONE", "POST_REGIME": "NONE",
          "ADDON_COLLECT": 0.0, "ADDON_POST": 0.0, "BY_PRODUCT_CLASS": [{"PRODUCT_CLASS": "RatesFX", "COLLECT": 426606.46, "POST": 426606.46}],
          "BY_RISK_CLASS": [{"PRODUCT_CLASS": "RatesFX", "RISK_CLASS": "InterestRate", "DELTA": 357483.43, "VEGA": 0.0, "CURVATURE": 0.0, "TOTAL": 357483.43}]}


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
        if path.startswith("/api/v1/simm/playground/recipes/"):
            return {**ANSWER, "reproduced": FakeApi.reproduced}
        if path.startswith("/api/v1/simm/playground/recipes"):
            return {"id": "SM-AB12CD34-1", "command": "PLAY SIMM RUN SM-AB12CD34-1", "knownAt": "2026-11-02T00:00:00Z"}
        if path.startswith("/api/v1/simm/playground"):
            return {**ANSWER, "choices": [{"name": "concentration", "value": "on", "default": "on", "rule": "SIMM 7(b)"}],
                    "compare": {"words": "hvr: on → off lowers the margin by 12.00"}}
        if path.startswith("/api/v1/simm/netting-sets"):
            return {"date": "2026-09-30", "defaultVersion": "TOY-1", "computed": 1, "sumCollect": 5.0, "sumPost": 4.0,
                    "rows": [{"nettingSet": "NS-X", "counterparty": "CP-X", "imCollect": 5.0, "imPost": 4.0, "status": "computed"}]}
        if path.startswith("/api/v1/simm/versions"):
            return {"parametersDir": "/x", "default": "TOY-1", "licence": "licensed", "versions": [{"version": "TOY-1", "file": "toy.yaml", "installed": True,
                                                                                                  "sha256": "ab" * 32, "problem": None}]}
        if path.startswith("/api/v1/simm/against"):
            return {"rows": [{"direction": "collect", "drishti": 5.0, "theirs": None, "difference": None, "status": "no call"}], "note": "no connector"}
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
    S.register(groups, srv, ctx)
    a = root.parse_args(list(argv))
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = a.func(a, [])
    return code, out.getvalue()


def query(path: str) -> list:
    return urllib.parse.parse_qsl(path.split("?", 1)[1]) if "?" in path else []


class SimmCli(unittest.TestCase):
    def setUp(self):
        FakeApi.calls = []
        FakeApi.reproduced = "reproduced to the cent: the inputs and every figure equal the recipe's"

    def test_simm_show_passes_the_switches_and_prints_the_figures(self):
        code, out = run("simm", "show", "NS-X", "--concentration", "off", "--override", "InterestRate.across-currencies=0.4", "--version", "TOY-1")
        self.assertEqual(code, 0)
        q = query(FakeApi.calls[-1][1])
        self.assertIn(("nettingSet", "NS-X"), q)
        self.assertIn(("concentration", "off"), q)
        self.assertIn(("version", "TOY-1"), q)
        self.assertIn(("override", "InterestRate.across-currencies=0.4"), q)
        self.assertIn("IM collect 426,606.46", out)
        self.assertIn("357,483.43", out)

    def test_list_versions_and_against(self):
        code, out = run("simm", "list", "--counterparty", "CP-X")
        self.assertIn(("counterparty", "CP-X"), query(FakeApi.calls[-1][1]))
        self.assertIn("NS-X", out)
        code, out = run("simm", "versions")
        self.assertIn("installed, SHA-256 abababababab", out)
        code, out = run("simm", "against", "NS-X")
        self.assertIn("no call", out)

    def test_play_simm_compares_writes_saves_and_replays(self):
        code, out = run("play", "simm", "NS-X", "--compare", "hvr=off", "--side", "post")
        q = query(FakeApi.calls[-1][1])
        self.assertIn(("compare", "hvr=off"), q)
        self.assertIn(("side", "post"), q)
        self.assertIn("lowers the margin", out)
        with tempfile.TemporaryDirectory() as d:
            f = os.path.join(d, "ns.xlsx")
            code, out = run("play", "simm", "NS-X", "--xlsx", f)
            self.assertEqual(Path(f).read_bytes()[:2], b"PK")
        code, out = run("play", "simm", "EXAMPLE", "--save")
        self.assertIn("saved recipe SM-AB12CD34-1", out)
        code, out = run("play", "simm", "--recipe", "SM-AB12CD34-1")
        self.assertEqual(code, 0)
        FakeApi.reproduced = "not reproduced: collect IM: recorded 1.0, now 2.0"
        code, out = run("play", "simm", "--recipe", "SM-AB12CD34-1")
        self.assertEqual(code, 1)
        with self.assertRaises(Err):
            run("play", "simm")
        code, out = run("play", "frtb", "EXAMPLE")        # the play group still holds frtb
        self.assertEqual(code, 0)


if __name__ == "__main__":
    unittest.main()
