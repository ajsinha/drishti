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

"""`drishti.py var`: every command reaches the right path with the right query or body (a fake Api), a headline and a contributions table
print in a few readable lines, and a what-if makes a sandbox of the trades file and asks the Python engine. Stdlib only.

    python3 -m unittest -q tools/test_varcli.py
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import tempfile
import unittest
import urllib.parse
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("varcli", Path(__file__).resolve().parent / "varcli.py")
V = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(V)

HEAD = {"canonical": "DESK:RATES", "label": "Rates", "kind": "node", "businessDate": "2026-09-30", "var": 74633680, "es": 98000000,
        "k": 6, "esCount": 13, "scenarios": 509, "confidence": 0.99, "esConfidence": 0.975, "limit": 106000000, "limitUse": 0.704,
        "children": 3, "childKind": "book", "sumStandalone": 90000000, "diversification": 15366320,
        "backtest": {"light": "green", "exceptions": 1, "days": 250, "kupiecPValue": 0.31, "christoffersenPValue": 0.9},
        "provenance": {"trades": 1487, "positions": 1300, "hidden": 0, "vectors": "pnl-vector · batch B-1", "tier": "node aggregate (float64)",
                       "method": "historical", "computeMs": 1, "cache": "miss", "warnings": []}}


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
        FakeApi.calls.append((method, path, body))
        if path.startswith("/api/v1/var/scope?"):
            return HEAD
        if path.startswith("/api/v1/var/scope/contributions"):
            return {"canonical": "DESK:RATES", "by": "book", "var": 1, "es": 2, "sumComponent": 1,
                    "rows": [{"label": "BOOK-RATES-1", "standaloneVar": 5, "componentVar": 1, "pct": 1.0, "incrementalVar": 1, "componentEs": 2}]}
        if path == "/api/v1/compute/sandboxes":
            return {"id": "sbx-1"}
        if path.startswith("/api/v1/compute/var-whatif"):
            return {"state": "ready", "requestId": "req-1", "result": {"BEFORE_VAR": 10, "AFTER_VAR": 12, "CHANGE_VAR": 2}}
        return {"ok": True}


def parser():
    srv = argparse.ArgumentParser(add_help=False)
    srv.add_argument("--json", action="store_true")
    root = argparse.ArgumentParser()
    groups = root.add_subparsers(dest="group")
    V.register(groups, srv, {"Api": FakeApi, "CliError": Err, "say_json": lambda x: None})
    return root


def run(*argv):
    a = parser().parse_args(["var", *argv])
    return a.func(a, [])


class VarCli(unittest.TestCase):
    def setUp(self):
        FakeApi.calls = []

    def query(self, i=-1):
        method, path, body = FakeApi.calls[i]
        return method, path.split("?")[0], dict(urllib.parse.parse_qsl(path.split("?")[1])) if "?" in path else {}, body

    def test_each_command_reaches_its_path(self):
        run("show", "BANK - DESK:CREDIT", "--date", "2026-09-30")
        self.assertEqual(self.query(), ("GET", "/api/v1/var/scope", {"scope": "BANK - DESK:CREDIT", "asOf": "2026-09-30"}, None))
        run("parts", "DESK:RATES", "--by", "position", "--scenario", "2026-03-20")
        self.assertEqual(self.query()[2], {"scope": "DESK:RATES", "by": "position", "scenario": "2026-03-20"})
        run("path", "DESK:RATES", "--size", "10", "--json")
        self.assertEqual(self.query()[1:3], ("/api/v1/var/scope/path", {"scope": "DESK:RATES", "page": "0", "size": "10"}))
        run("tree", "--hierarchy", "LEGAL", "--json")
        self.assertEqual(self.query()[1:3], ("/api/v1/var/tree", {"hierarchy": "LEGAL"}))
        run("capture", "DESK:RATES", "--note", "before")
        self.assertEqual(self.query()[0::3], ("POST", {"scope": "DESK:RATES", "note": "before"}))
        run("compare", "CAP-20260930-1")
        self.assertEqual(self.query()[2], {"a": "CAP-20260930-1", "b": "now"})
        run("basket", "hedges", "WHERE:'hedge' in TAGS", "--share", "role:risk")
        self.assertEqual(FakeApi.calls[-1][2]["sharedWith"], ["role:risk"])
        run("official", "--capture", "--date", "2026-09-30")
        self.assertEqual(self.query()[0:3], ("POST", "/api/v1/var/official", {"asOf": "2026-09-30"}))
        run("status")
        self.assertEqual(FakeApi.calls[-1][1], "/api/v1/var/status")

    def test_every_figure_in_a_reporting_currency(self):
        run("show", "BANK", "--ccy", "EUR")
        self.assertEqual(self.query(), ("GET", "/api/v1/var/scope", {"scope": "BANK", "ccy": "EUR"}, None))
        run("parts", "DESK:RATES", "--ccy", "CAD")
        self.assertEqual(self.query()[2]["ccy"], "CAD")
        run("tree", "--ccy", "GBP", "--json")
        self.assertEqual(self.query()[1:3], ("/api/v1/var/tree", {"hierarchy": "MGMT", "ccy": "GBP"}))
        run("capture", "TRD:X-1", "--ccy", "EUR")
        self.assertEqual(self.query()[0:3], ("POST", "/api/v1/var/captures", {"ccy": "EUR"}))
        run("official", "--ccy", "EUR")
        self.assertEqual(self.query()[0:3], ("GET", "/api/v1/var/official", {"ccy": "EUR"}))
        run("whatif", "DESK:RATES", "--ccy", "EUR", "--stress-window", "0")
        self.assertEqual(FakeApi.calls[-1][2]["params"], {"stress-window": 0, "ccy": "EUR"})
        out = []
        V.render_headline({**HEAD, "currency": "EUR", "provenance": {**HEAD["provenance"], "fx": {
            "reporting": "EUR", "crosses": "through USD", "rates": {"EUR": {"source": "supplied", "base": 1.174, "reporting": True}}}}}, out.append)
        self.assertIn("  VaR 99%   74,633,680 EUR   (the 6th worst of 509)", out)
        self.assertIn("  in EUR, crosses through USD; FX EUR supplied (1.174 USD)", out)

    def test_a_headline_and_its_parts_print_in_a_few_lines(self):
        out = []
        V.render_headline(HEAD, out.append)
        self.assertEqual(out[0], "VAR DESK:RATES  ·  Rates  ·  node  ·  2026-09-30")
        self.assertIn("  VaR 99%   74,633,680 USD   (the 6th worst of 509)", out)
        self.assertTrue(any("green  1 exceptions in 250 days" in line for line in out))
        self.assertTrue(any("node aggregate (float64)" in line for line in out))
        rows = []
        V.render_parts(FakeApi().json("GET", "/api/v1/var/scope/contributions?scope=x"), 10, rows.append)
        self.assertTrue(rows[1].startswith("Part"))
        self.assertIn("BOOK-RATES-1", rows[3])

    def test_a_whatif_makes_a_sandbox_of_the_trades_and_asks_the_python_engine(self):
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "trades.json"
            f.write_text(json.dumps([{"LABEL": "new swap", "PNL": [1.0, -2.0], "FACTOR": 1}]), encoding="utf-8")
            self.assertEqual(run("whatif", "DESK:RATES", "--trades", str(f), "--stress-window", "0"), 0)
            self.assertEqual(FakeApi.calls[0][1], "/api/v1/compute/sandboxes")
            self.assertEqual(FakeApi.calls[0][2]["connector"], "var-whatif")
            self.assertEqual(FakeApi.calls[1][2], {"id": "DESK:RATES", "params": {"stress-window": 0, "sandbox": "sbx-1"}, "wait": 10000})
            f.write_text(json.dumps([{"LABEL": "no vector"}]), encoding="utf-8")
            with self.assertRaises(Err):
                run("whatif", "DESK:RATES", "--trades", str(f))


if __name__ == "__main__":
    unittest.main()
