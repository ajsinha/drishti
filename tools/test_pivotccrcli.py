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
"""`drishti.py pivot ccr board|headline|fan|predeal` (tools/pivotcli.py; EXPOSURE_GUIDE.md section 30): landing A's board as the pivot
engine's view of source ccr (counterparties by tenor bucket, peak PFE and its use), the headline, a scope's fan with the limit and a pre-deal
check, each one call of the server, printed as it answered (a fake Api: nothing is computed here).

    python3 -m unittest -q tools/test_pivotccrcli.py
"""
from __future__ import annotations

import argparse
import contextlib
import importlib.util
import io
import json
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent


def load(name):
    spec = importlib.util.spec_from_file_location(name, HERE / f"{name}.py")
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


P = load("pivotcli")

ANSWER = {"source": "ccr", "title": "CCR cube", "date": "2026-09-30", "cellCount": 4, "computed": {"sets": 4, "fromCache": 0, "queries": 2, "ms": 3},
          "exactness": "exact", "rowDimensions": ["COUNTERPARTY"], "measures": [{"label": "Peak PFE"}],
          "columns": [{"key": "c1", "label": "0-1Y"}, {"key": "g", "label": "Total"}],
          "rows": [{"key": "r1", "label": "CP-1", "depth": 1, "kind": "node"}, {"key": "rg", "label": "Total", "depth": 0, "kind": "grand"}],
          "cells": {"r1|c1": {"values": {"Peak PFE": 1_000_000}}, "r1|g": {"values": {"Peak PFE": 3_000_000}},
                    "rg|c1": {"values": {"Peak PFE": 1_000_000}}, "rg|g": {"values": {"Peak PFE": 3_000_000}}}}
HEADLINE = {"date": "2026-09-30", "confidence": 0.75, "peakPfe": 3e6, "peakTenor": "5M", "epe": 1.25e6, "eepe": 1.4e6, "run": "XR-1",
            "overCount": 1, "over": [{"counterparty": "CP-1", "bucket": "LONG", "use": 3 / 2.2}], "nearCount": 0, "near": [], "warningAt": 0.8}
FAN = {"scope": "CPTY:CP-1", "date": "2026-09-30", "confidence": 0.75, "peakPfe": 3e6, "peakTenor": "5M", "limitNote": "the stored credit limit",
       "bindingTenor": "5M", "minHeadroom": -8e5,
       "points": [{"tenor": "2M", "bucket": "SHORT", "ee": 1.25e6, "pfe": 1e6, "limit": 2.2e6, "headroom": 1.2e6},
                  {"tenor": "5M", "bucket": "LONG", "ee": 2e6, "pfe": 3e6, "limit": 2.2e6, "headroom": -8e5}]}
PREDEAL = {"scope": "CPTY:CP-1", "method": "paths", "base": {"peakPfe": 3e6}, "with": {"peakPfe": 3.5e6}, "incremental": {"peakPfe": 5e5},
           "limit": 2.2e6, "useBefore": 3 / 2.2, "useAfter": 3.5 / 2.2, "headroomAfter": -1.3e6}


class FakeApi:
    calls: list = []

    @classmethod
    def from_args(cls, a):
        return cls()

    def json(self, method, path, body=None):
        FakeApi.calls.append((method, path, body))
        if path.startswith("/api/v1/pivot/ccr/headline"):
            return HEADLINE
        if path.startswith("/api/v1/pivot/ccr/fan"):
            return FAN
        if path.startswith("/api/v1/pivot/ccr/pre-deal"):
            return PREDEAL
        return ANSWER


def run(*argv) -> tuple[int, str]:
    srv = argparse.ArgumentParser(add_help=False)
    srv.add_argument("--json", action="store_true")
    parser = argparse.ArgumentParser()
    groups = parser.add_subparsers(dest="group")
    P.register(groups, srv, {"Api": FakeApi, "say_json": lambda x: print(json.dumps(x))})
    a = parser.parse_args(list(argv))
    out = io.StringIO()
    FakeApi.calls = []
    with contextlib.redirect_stdout(out):
        code = a.func(a)
    return code, out.getvalue()


class PivotCcrCliTest(unittest.TestCase):

    def test_the_board_is_landing_as_view_of_source_ccr(self):
        code, out = run("pivot", "ccr", "board")
        self.assertEqual(code, 0)
        method, path, body = FakeApi.calls[0]
        self.assertEqual((method, path), ("POST", "/api/v1/pivot/query"))
        v = body["view"]
        self.assertEqual((v["source"], v["rows"], v["columns"]), ("ccr", ["COUNTERPARTY"], ["TENOR_BUCKET"]))
        self.assertEqual([x["measure"] for x in v["values"]], ["pfe()", "limit_use()"])
        self.assertIn("3,000,000", out)

    def test_the_board_takes_the_flags_and_the_options(self):
        run("pivot", "ccr", "board", "--rows", "GROUP,COUNTERPARTY", "--values", "epe()", "--option", "q=0.99")
        v = FakeApi.calls[0][2]["view"]
        self.assertEqual(v["rows"], ["GROUP", "COUNTERPARTY"])
        self.assertEqual(v["options"], {"q": "0.99"})

    def test_the_headline_names_the_breach(self):
        code, out = run("pivot", "ccr", "headline")
        self.assertEqual(FakeApi.calls[0][1], "/api/v1/pivot/ccr/headline")
        self.assertIn("over the limit: 1  CP-1 LONG 136%", out)

    def test_the_fan_prints_each_date_with_its_limit_and_headroom(self):
        code, out = run("pivot", "ccr", "fan", "--scope", "CPTY:CP-1", "--q", "0.75")
        self.assertIn("scope=CPTY%3ACP-1", FakeApi.calls[0][1])
        self.assertIn("q=0.75", FakeApi.calls[0][1])
        self.assertIn("-800,000.00", out)
        self.assertIn("binding tenor 5M", out)

    def test_predeal_copies_a_booked_trade_into_a_netting_set(self):
        code, out = run("pivot", "ccr", "predeal", "--scope", "CPTY:CP-1", "--copy", "A", "--into", "NS-X")
        method, path, body = FakeApi.calls[0]
        self.assertEqual((method, path), ("POST", "/api/v1/pivot/ccr/pre-deal"))
        self.assertEqual(body["candidates"][0], {"tradeId": "CAND-1", "paths": {"copyOf": "A", "factor": 1.0}, "nettingSet": "NS-X"})
        self.assertIn("headroom after -1,300,000", out)

    def test_predeal_without_a_trade_is_refused_in_words(self):
        with self.assertRaises(SystemExit) as e:
            run("pivot", "ccr", "predeal", "--scope", "CPTY:CP-1")
        self.assertIn("--copy", str(e.exception))


if __name__ == "__main__":
    unittest.main()
