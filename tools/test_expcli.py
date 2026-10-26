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

"""`drishti.py exposure`: every command reaches the right path with the right query or body (a fake Api), a headline, a contributions
table and a what-if print in a few readable lines, and a what-if sends a copy of a booked trade or the candidates of a file. Stdlib only.

    python3 -m unittest -q tools/test_expcli.py
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import tempfile
import unittest
import urllib.parse
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("expcli", Path(__file__).resolve().parent / "expcli.py")
X = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(X)

HEAD = {"canonical": "NSET:NS-ALDERSHOT-NY", "label": "NS-ALDERSHOT-NY · Aldershot Pension Trust", "kind": "netting-set",
        "businessDate": "2026-09-30", "confidence": 0.95, "peakPfe": 353330000, "peakPfeTenor": "2Y", "peakPfeDate": "2028-09-29",
        "peakPfe99": 410000000, "epe": 41230000, "eepe": 220570000, "peakEe": 230000000, "ce": 19000000, "eadImm": 308798000,
        "runId": "XR-2026-09-30-01", "paths": 200, "collateralModel": "lagged", "horizonYears": 1.0, "flags": [],
        "profile": [{"tenor": "1W", "date": "2026-10-07", "ee": 1, "ene": -1, "eee": 1, "pfe": 2, "pfe99": 3, "eeUncoll": 4}],
        "provenance": {"nettingSets": 1, "trades": 305, "tier": "hot file", "computeMs": 3, "cache": "miss", "warnings": []}}


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
        if path.startswith("/api/v1/exposure/scope?"):
            return HEAD
        if path.startswith("/api/v1/exposure/scope/contributions"):
            return {"canonical": "CPTY:CP-ALDERSHOT", "by": "netting-set", "figure": "PFE", "total": 10, "date": "2028-09-29",
                    "rows": [{"label": "NS-ALDERSHOT-NY · Aldershot", "value": 8, "pct": 0.8, "incremental": 5}]}
        if path.startswith("/api/v1/exposure/what-if"):
            return {"canonical": "CPTY:CP-ALDERSHOT", "method": "paths", "flags": [], "runId": "XR-1", "base": {"peakPfe": 10, "epe": 2},
                    "with": {"peakPfe": 12, "epe": 3}, "incremental": {"peakPfe": 2, "epe": 1}}
        return {"ok": True}

    def request(self, method, path, body=None):
        FakeApi.calls.append((method, path, body))
        return 200, b"tenor,date\n"


def parser():
    srv = argparse.ArgumentParser(add_help=False)
    srv.add_argument("--json", action="store_true")
    root = argparse.ArgumentParser()
    groups = root.add_subparsers(dest="group")
    X.register(groups, srv, {"Api": FakeApi, "CliError": Err, "say_json": lambda x: None})
    return root


def run(*argv):
    a = parser().parse_args(["exposure", *argv])
    return a.func(a, [])


class ExposureCli(unittest.TestCase):
    def setUp(self):
        FakeApi.calls = []

    def query(self, i=-1):
        method, path, body = FakeApi.calls[i]
        return method, path.split("?")[0], dict(urllib.parse.parse_qsl(path.split("?")[1])) if "?" in path else {}, body

    def test_each_command_reaches_its_path(self):
        run("show", "CPTY:CP-ALDERSHOT", "--date", "2026-09-30", "--q", "0.99", "--json")
        self.assertEqual(self.query(), ("GET", "/api/v1/exposure/scope", {"scope": "CPTY:CP-ALDERSHOT", "asOf": "2026-09-30", "q": "0.99"}, None))
        run("parts", "NSET:NS-ALDERSHOT-NY", "--by", "trade", "--figure", "EE", "--at", "1Y", "--json")
        self.assertEqual(self.query()[1:3], ("/api/v1/exposure/scope/contributions",
                                             {"scope": "NSET:NS-ALDERSHOT-NY", "by": "trade", "figure": "EE", "date": "1Y"}))
        run("tree", "--json")
        self.assertEqual(self.query()[1], "/api/v1/exposure/tree")
        run("profile", "NSET:NS-X", "--csv")
        self.assertEqual(self.query()[2], {"scope": "NSET:NS-X", "format": "csv"})
        run("distribution", "NSET:NS-X", "--at", "2Y", "--json")
        self.assertEqual(self.query()[2], {"scope": "NSET:NS-X", "bands": "true", "date": "2Y"})
        run("capture", "CPTY:CP-ALDERSHOT", "--note", "before")
        self.assertEqual(self.query()[0::3], ("POST", {"scope": "CPTY:CP-ALDERSHOT", "note": "before"}))
        run("compare", "XCAP-20260930-1")
        self.assertEqual(self.query()[2], {"a": "XCAP-20260930-1", "b": "now"})
        run("official", "--capture", "--date", "2026-09-30")
        self.assertEqual(self.query()[0:3], ("POST", "/api/v1/exposure/official", {"asOf": "2026-09-30"}))
        for c, path in (("status", "/api/v1/exposure/status"), ("retention", "/api/v1/exposure/retention"), ("reload", "/api/v1/exposure/reload")):
            run(c)
            self.assertEqual(FakeApi.calls[-1][1], path)

    def test_a_headline_and_its_parts_print_in_a_few_lines(self):
        out = []
        X.render_headline(HEAD, out.append)
        self.assertEqual(out[0], "EXP NSET:NS-ALDERSHOT-NY  ·  NS-ALDERSHOT-NY · Aldershot Pension Trust  ·  netting-set  ·  2026-09-30")
        self.assertIn("  peak PFE 95%   353,330,000 USD at 2Y (2028-09-29)   PFE 99% 410,000,000", out)
        self.assertTrue(any(line.startswith("Tenor") for line in out))
        rows = []
        X.render_parts(FakeApi().json("GET", "/api/v1/exposure/scope/contributions?scope=x"), 10, rows.append)
        self.assertIn("NS-ALDERSHOT-NY", rows[3])

    def test_a_whatif_copies_a_trade_or_reads_candidates(self):
        self.assertEqual(run("whatif", "CPTY:CP-ALDERSHOT", "--like", "CLY-4000879", "--factor", "-1", "--set", "NS-ALDERSHOT-FRA"), 0)
        body = FakeApi.calls[-1][2]
        self.assertEqual(body["method"], "paths")
        self.assertEqual(body["candidates"][0]["paths"], {"copyOf": "CLY-4000879", "factor": -1.0})
        self.assertEqual(body["candidates"][0]["nettingSet"], "NS-ALDERSHOT-FRA")
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "c.json"
            f.write_text(json.dumps([{"tradeId": "NEW-1", "assetClass": "FX", "notional": 1e7, "maturityDate": "2027-09-30", "mtm": 0}]),
                         encoding="utf-8")
            run("whatif", "CPTY:CP-ALDERSHOT", "--candidates", str(f), "--method", "addon")
            self.assertEqual(FakeApi.calls[-1][2]["method"], "addon")
            self.assertEqual(FakeApi.calls[-1][2]["candidates"][0]["tradeId"], "NEW-1")
        with self.assertRaises(Err):
            run("whatif", "CPTY:CP-ALDERSHOT")
        out = []
        X.render_whatif(FakeApi().json("POST", "/api/v1/exposure/what-if"), out.append)
        self.assertTrue(any(line.startswith("Peak PFE") and line.rstrip().endswith("2") for line in out))


if __name__ == "__main__":
    unittest.main()
