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

"""`drishti.py saccr ...|play saccr`: each command asks the right API with the switches passed through, prints the server's figures,
sends a what-if's candidates and writes the workbook (a fake Api). `play saccr` joins the `play` group frtbcli makes.

    python3 -m unittest -q tools/test_saccrcli.py
"""
from __future__ import annotations

import argparse
import contextlib
import importlib.util
import io
import json
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


S = load("saccrcli")
F = load("frtbcli")

ANSWER = {"scope": "CPTY:CP-X", "kind": "counterparty", "businessDate": "2026-09-30", "regulation": "BCBS 1.0",
          "totals": {"EAD": 4776789.04, "RC": 1200000.0, "PFE": 2211992.17, "NETTING_SETS": 1, "ROWS": 1, "EXCLUDED_TRADES": 0, "UNCLASSIFIED_TRADES": 0},
          "sets": [{"nettingSet": "NS-X", "margining": "unmargined", "RC": 1200000.0, "ADD_ON": 2211992.17, "MULTIPLIER": 1.0, "PFE": 2211992.17,
                    "EAD": 4776789.04, "status": "computed", "CAPPED": False}],
          "selected": {"formula": ["EAD = α × (RC + PFE) = 1.4 × 3.41m = 4.78m"]}}


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
        FakeApi.calls.append((method, path, body))
        if path.startswith("/api/v1/saccr/scope/contributions"):
            return {"scope": "CPTY:CP-X", "by": "trade", "EAD": 1.0, "note": "parts do not add", "rows": [
                {"key": "T1~0", "nettingSet": "NS-X", "measure": "d", "value": 5.0, "standaloneEad": 7.0}]}
        if path.startswith("/api/v1/saccr/trade/"):
            return {"tradeId": "T1", "nettingSet": "NS-X", "businessDate": "2026-09-30", "drivers": [
                {"driver": 0, "assetClass": "IR", "hedgingSet": "USD", "subKey": "b2", "SD": 4.423984, "d": 4.4e8, "delta": 1.0, "MF_UNMARGINED": 1.0,
                 "MF_MARGINED": 0.0, "D_UNMARGINED": 4.4e8, "standaloneEad": 9.0}]}
        if path.startswith("/api/v1/saccr/what-if"):
            return {"nettingSet": "NS-X", "eadBefore": 10.0, "eadAfter": 8.0, "incrementalEad": -2.0,
                    "hedgingSets": [{"assetClass": "IR", "hedgingSet": "USD", "addOnBefore": 5.0, "addOnAfter": 3.0}]}
        if path.startswith("/api/v1/saccr/parameters"):
            return {"regulation": "BCBS", "version": "1.0", "file": "built in", "sha256": "ab" * 32, "values": [{"key": "alpha", "value": 1.4, "ref": "CRE52.1"}]}
        return ANSWER

    def request(self, method, path, body=None, extra=None):
        FakeApi.calls.append((method, path, body))
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


class SaccrCli(unittest.TestCase):
    def setUp(self):
        FakeApi.calls = []

    def test_show_passes_the_switches_and_prints_the_figures(self):
        code, out = run("saccr", "show", "CPTY:CP-X", "--set", "NS-X", "--no-cap", "--no-ir-offset", "--strict", "--date", "2026-09-30")
        self.assertEqual(code, 0)
        q = query(FakeApi.calls[-1][1])
        for kv in (("scope", "CPTY:CP-X"), ("nettingSet", "NS-X"), ("cap", "false"), ("irBucketOffset", "false"), ("strict", "true"), ("asOf", "2026-09-30")):
            self.assertIn(kv, q)
        self.assertIn("EAD 4,776,789.04", out)
        self.assertIn("NS-X", out)
        self.assertIn("1.4 × 3.41m", out)

    def test_contributions_trade_parameters(self):
        code, out = run("saccr", "contributions", "CPTY:CP-X", "--by", "trade")
        self.assertIn(("by", "trade"), query(FakeApi.calls[-1][1]))
        self.assertIn("standalone EAD 7.00", out)
        code, out = run("saccr", "trade", "T1")
        self.assertEqual(FakeApi.calls[-1][1], "/api/v1/saccr/trade/T1")
        self.assertIn("SD 4.423984", out)
        code, out = run("saccr", "parameters")
        self.assertIn("CRE52.1", out)

    def test_what_if_sends_the_candidates(self):
        with tempfile.TemporaryDirectory() as d:
            f = os.path.join(d, "c.json")
            Path(f).write_text(json.dumps({"ASSET_CLASS": "IR", "HEDGING_SET": "USD", "MTM": 0}), encoding="utf-8")
            code, out = run("saccr", "what-if", "NS-X", "--candidates", f, "--remove", "T9")
        method, path, body = FakeApi.calls[-1]
        self.assertEqual((method, path), ("POST", "/api/v1/saccr/what-if"))
        self.assertEqual(body["nettingSet"], "NS-X")
        self.assertEqual(body["remove"], ["T9"])
        self.assertEqual(len(body["candidates"]), 1)
        self.assertIn("incremental -2.00", out)
        with self.assertRaises(Err):
            run("saccr", "what-if", "NS-X", "--candidates", "/no/such/file.json")

    def test_play_saccr_writes_the_workbook(self):
        with tempfile.TemporaryDirectory() as d:
            f = os.path.join(d, "x.xlsx")
            code, out = run("play", "saccr", "NSET:NS-X", "--xlsx", f)
            self.assertEqual(Path(f).read_bytes()[:2], b"PK")
        self.assertIn(("scope", "NSET:NS-X"), query(FakeApi.calls[-1][1]))
        code, out = run("play", "saccr")
        self.assertIn(("scope", "EXAMPLE"), query(FakeApi.calls[-1][1]))
        code, out = run("play", "frtb", "EXAMPLE")        # the play group still holds frtb
        self.assertEqual(code, 0)


if __name__ == "__main__":
    unittest.main()
