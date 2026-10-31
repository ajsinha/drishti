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
        run("reload")
        self.assertEqual(FakeApi.calls[-1][0:2], ("POST", "/api/v1/var/reload"))
        run("reload", "--date", "2026-09-29")                           # 2.9.7 (O3): one date only
        self.assertEqual(FakeApi.calls[-1][1], "/api/v1/var/reload?date=2026-09-29")

    def test_shards_lists_the_map_and_drains(self):
        run("shards", "--date", "2026-09-30", "--json")
        self.assertEqual(self.query()[0:3], ("GET", "/api/v1/var/shards", {"asOf": "2026-09-30"}))
        run("shards", "--drain")
        self.assertEqual(FakeApi.calls[-1], ("POST", "/api/v1/var/shards/drain", {"on": True}))
        run("shards", "--undrain", "--json")
        self.assertEqual(FakeApi.calls[-1][2], {"on": False})
        out = []
        V.render_shards({"enabled": True, "origin": "pack market-risk", "key": ["LEGAL_ENTITY", "DESK"], "targetShardMb": 2048, "replicas": 2,
                         "self": "n-a", "members": [{"node": "n-a", "self": True, "owner": True, "inView": True}],
                         "map": {"businessDate": "2026-09-30", "digest": "abc", "shards": [{"shard": "S0", "range": "LE-A/DESK-X..LE-A/DESK-Y",
                                                                                      "rows": 5, "owners": ["n-a", "n-b"], "ready": ["n-a"]}]}},
                        out.append)
        self.assertTrue(any("S0" in line and "n-a, n-b" in line for line in out), out)
        out = []
        V.render_shards({"enabled": False, "origin": "default"}, out.append)
        self.assertIn("off", out[0])

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

    def test_every_answer_through_a_lens(self):
        """2.9.7 (VAR_GUIDE.md section 18.6): --horizon, --method, --confidence, --es-confidence on show, parts, path, history, tree and
        capture; show with --window keeps the 2.9.4 comparison."""
        run("show", "BANK", "--horizon", "10d", "--method", "sum", "--confidence", "95")
        self.assertEqual(self.query()[2], {"scope": "BANK", "horizon": "10d", "method": "sum", "confidence": "95.0"})
        run("show", "BANK", "--window", "250", "--confidence", "0.9")
        self.assertEqual(self.query()[2], {"scope": "BANK", "window": "250", "confidence": "0.9"})
        for c in ("parts", "path", "history"):
            run(c, "DESK:RATES", "--horizon", "10d", "--es-confidence", "0.95", "--json")
            self.assertEqual({k: self.query()[2].get(k) for k in ("horizon", "esConfidence")}, {"horizon": "10d", "esConfidence": "0.95"}, c)
        run("tree", "--horizon", "10d", "--method", "sqrt10", "--json")
        self.assertEqual(self.query()[2], {"hierarchy": "MGMT", "horizon": "10d", "method": "sqrt10"})
        run("capture", "BANK", "--horizon", "10d", "--method", "sum")
        self.assertEqual(self.query()[3], {"scope": "BANK", "note": "", "horizon": "10d", "method": "sum"})
        out = []
        V.render_headline({**HEAD, "var": 274544283, "k": 5, "scenarios": 500, "limitComparable": False, "limit": 1, "limitNote":
                           "limit is for 1-day 99%: not comparable", "lens": {"label": "10-day (sum)", "method": "SUM", "describe": "x"},
                           "backtest": {"applicable": False, "note": "backtesting is 1-day"}}, out.append)
        self.assertIn("  horizon    10-day (sum)   (x)", out)
        self.assertIn("  VaR 99%   274,544,283 USD   (the 5th worst of 500 overlapping windows)", out)
        self.assertTrue(any("not comparable" in line for line in out))
        self.assertIn("  backtest   backtesting is 1-day", out)

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


class IntradayTest(unittest.TestCase):
    """`drishti.py var intraday` (docs/guides/VAR_GUIDE.md section 8.4): a file's kind from its rows, its dates moved to another business
    date, a dry run that writes nothing, and the headline's intraday line."""

    ROWS = [{"VALUE_TYPE": "PV", "GRAIN": "TRADE", "TRADE_ID": "1000003", "CURRENCY": "EUR", "SOURCE_SYSTEM": "ENDUR",
             "SCENARIO_PV_ID": "SPV-1000003-HS-509-I-2026-09-30-001", "BUSINESS_DATE": "2026-09-30",
             "VALUATION_DATE": "2026-09-30", "BASE_VALUATION_DATE": "2026-09-30", "COMPUTED_AT": "2026-09-30T23:05:00Z",
             "BATCH_ID": "I-2026-09-30-001", "RUN_KIND": "INTRADAY", "BASE_PV": 1.0, "SCENARIO_PV": [1.0, 2.0]}]

    def test_the_kind_and_the_moved_dates(self):
        self.assertEqual(V.intraday_kind(self.ROWS), "scenario-pv")
        self.assertEqual(V.intraday_kind([{"VALUE_TYPE": "PNL", "PNL": [1.0], "VECTOR_ID": "PV-1"}]), "pnl-vector")
        with self.assertRaises(ValueError):
            V.intraday_kind(self.ROWS + [{"VALUE_TYPE": "PNL", "PNL": [1.0]}])
        moved = V.move_dates(self.ROWS, "2026-10-27")[0]
        self.assertEqual(moved["BUSINESS_DATE"], "2026-10-27")
        self.assertEqual(moved["COMPUTED_AT"], "2026-10-27T23:05:00Z")
        self.assertEqual(moved["BATCH_ID"], "I-2026-10-27-001")
        self.assertEqual(moved["SCENARIO_PV"], [1.0, 2.0])
        self.assertEqual(self.ROWS[0]["BUSINESS_DATE"], "2026-09-30")              # the file's own rows untouched

    def test_a_dry_run_writes_nothing(self):
        with tempfile.TemporaryDirectory() as tmp:
            f = Path(tmp) / "batch.jsonl"
            f.write_text("".join(json.dumps(r) + "\n" for r in self.ROWS), encoding="utf-8")
            said = []
            self.assertEqual(V.load_intraday(str(f), str(Path(tmp) / "lake"), "2026-10-27", None, None, True, said.append), 0)
            self.assertIn("1 scenario-pv rows (1 marked RUN_KIND INTRADAY), business date 2026-10-27", said[0])
            self.assertFalse((Path(tmp) / "lake").exists())

    def test_the_headline_says_intraday(self):
        said = []
        V.render_headline({**HEAD, "intraday": {"trades": 5, "amended": 3, "added": 1, "cancelled": 1, "batchCount": 2,
                                                  "lastAt": "2026-09-30T23:10:00Z"}}, said.append)
        self.assertTrue(any("intraday   5 trades updated" in s for s in said), said)


    def test_a_mixed_file_is_routed_by_value_type_and_convert_adds_the_fields(self):
        rows = [{"VALUE_TYPE": "PV", "GRAIN": "TRADE", "TRADE_ID": "1", "SCENARIO_PV": [1.0]},
                {"VALUE_TYPE": "PNL", "GRAIN": "POSITION", "POSITION_ID": "P", "PNL": [2.0]}]
        self.assertEqual({k: len(v) for k, v in V.route(rows).items()}, {"scenario-pv": 1, "pnl-vector": 1})
        with self.assertRaisesRegex(ValueError, "line 1: VALUE_TYPE is None"):
            V.route([{"TRADE_ID": "1", "PNL": [1.0]}])
        with tempfile.TemporaryDirectory() as tmp:
            old = Path(tmp) / "old.jsonl"
            old.write_text(json.dumps({"VECTOR_ID": "PV-1", "TRADE_ID": "1", "PNL": [1.0]}) + "\n", encoding="utf-8")
            self.assertEqual(run("convert", str(old), "--set", "VALUE_TYPE=PNL", "--set", "GRAIN=TRADE", "--set", "CURRENCY=USD",
                                 "--set", "SOURCE_SYSTEM=MUREX"), 0)
            new = json.loads((Path(tmp) / "old.converted.jsonl").read_text(encoding="utf-8"))
            self.assertEqual(list(new)[:5], ["VALUE_TYPE", "GRAIN", "TRADE_ID", "CURRENCY", "SOURCE_SYSTEM"])
            self.assertEqual(run("convert", str(old), "--infer", "--out", str(Path(tmp) / "x.jsonl")), 1)   # no CURRENCY nor SOURCE_SYSTEM
            self.assertEqual(json.loads((Path(tmp) / "x.jsonl").read_text(encoding="utf-8"))["GRAIN"], "TRADE")

if __name__ == "__main__":
    unittest.main()
