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


MATRIX = {"scope": "TRD:T1", "kind": "trade", "currency": "USD", "by": "scenario", "labels": {"var": "VaR 99% 1-day", "es": "ES 97.5% 1-day"},
          "dates": [{"date": "2026-09-29", "source": "engine", "gap": "DRS-5203 refused", "k": None},
                    {"date": "2026-09-30", "source": "open", "gap": None, "var": 1000, "es": 1000, "k": 1, "esCount": 1, "basePv": 1000,
                     "tail": [{"scenario": "2026-08-31", "rank": 1}]}],
          "rows": [{"scenario": "2026-08-31", "2026-09-29": None, "2026-09-30": -1000}, {"scenario": "2026-09-01", "2026-09-29": None, "2026-09-30": 200}]}


EXPLAIN = {"scope": "NODE:MGMT~DESK-RATES", "label": "Rates", "from": "2026-09-29", "to": "2026-09-30", "varFrom": 1000000.0, "varTo": 1100000.0,
           "change": 100000.0, "sumOfSteps": 100000.0, "addsUp": True,
           "sentences": ["VaR of Rates rose by 100,000, from 1.00m on 2026-09-29 to 1.10m on 2026-09-30; the steps below add up to it exactly."],
           "lines": [{"key": "restatement", "label": "Restatement", "effect": 0.0}, {"key": "trades-out", "label": "Trades out", "effect": -50000.0},
                     {"key": "market", "label": "Market", "effect": 120000.0}, {"key": "trades-in", "label": "Trades in", "effect": 30000.0}],
           "states": [{"id": "S0", "what": "the earlier date", "trades": 10, "scenarios": 509, "var": 1000000.0, "varScenario": "2025-12-24"},
                      {"id": "S4", "what": "Trades in", "trades": 11, "scenarios": 509, "var": 1100000.0, "varScenario": "2026-01-02"}],
           "steps": [{"label": "Trades out", "effect": -50000.0, "drivers": {"kind": "trades", "field": "incrementalVar",
                      "rows": [{"id": "T-9", "incrementalVar": -45000.0, "reason": "no vector on 2026-09-30"}], "rest": -5000.0,
                      "restLabel": "the other trades and the interaction"}},
                     {"label": "Market", "effect": 120000.0, "drivers": {"kind": "children", "rows": [
                         {"label": "Swaps", "componentBefore": 600000.0, "componentAfter": 720000.0, "change": 120000.0}]}}],
           "orderFree": {"by": "node", "rows": [{"label": "Swaps", "change": 100000.0}], "total": 100000.0, "addsUp": True},
           "warnings": []}


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
        if path.startswith("/api/v1/var/matrix"):
            return MATRIX
        if path.startswith("/api/v1/var/what-if"):
            return {"businessDate": "2026-09-30", "currency": "USD", "before": {"var": 10}, "after": {"var": 12}, "change": {"var": 2},
                    "items": [{"label": "new swap", "standaloneVar": 3, "incrementalVar": 2, "componentVar": 2}]}
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

    def test_basket_delete_yours_and_an_administrators_with_owner(self):
        run("basket-delete", "rates-hedges")
        self.assertEqual(FakeApi.calls[-1], ("DELETE", "/api/v1/var/baskets/rates-hedges", None))
        run("basket-delete", "old unwind", "--owner", "asha", "--reason", "left the desk", "--version", "3")
        self.assertEqual(self.query()[0:3], ("DELETE", "/api/v1/var/baskets/asha/old%20unwind", {"reason": "left the desk", "version": "3"}))
        run("baskets")
        self.assertEqual(FakeApi.calls[-1][0:2], ("GET", "/api/v1/var/baskets"))
        run("baskets", "--all")
        self.assertEqual(FakeApi.calls[-1][1], "/api/v1/var/admin/baskets")
        run("baskets", "--all", "--owner", "asha")
        self.assertEqual(FakeApi.calls[-1][1], "/api/v1/var/admin/baskets?owner=asha")

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
        run("whatif", "DESK:RATES", "--ccy", "EUR", "--copy", "MX-1=-1")
        self.assertEqual(FakeApi.calls[-1][2], {"scope": "DESK:RATES", "items": [{"copyOf": "MX-1", "factor": -1.0}], "ccy": "EUR"})
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
        cut = []                                                          # rows limited by max-parts: said with the real numbers
        V.render_parts({"canonical": "PORT:BIG", "by": "position", "var": 1, "es": 1, "sumComponent": 1, "truncated": True,
                        "total": 18567, "shown": 5000, "rows": []}, 10, cut.append)
        self.assertIn("(5,000 of 18,567 positions shown (the largest by gross size); totals are over all 18,567)", cut[0])

    def test_the_stress_window_selection_asks_proposes_and_decides(self):
        run("stress-window", "DESK:DESK-RATES", "--date", "2026-09-30", "--length", "125", "--json")
        self.assertEqual(self.query()[1:3], ("/api/v1/var/stress-window", {"scope": "DESK:DESK-RATES", "hierarchy": "MGMT", "length": "125",
                                                                          "asOf": "2026-09-30"}))
        run("stress-window", "--propose", "--first", "2007-11-30", "--note", "Bear and Lehman", "--json")
        self.assertEqual(FakeApi.calls[-1], ("POST", "/api/v1/var/stress-window/proposals",
                                             {"scope": "BANK", "hierarchy": "MGMT", "note": "Bear and Lehman", "first": "2007-11-30"}))
        run("stress-window", "--decide", "SWP-000001", "--decision", "approve", "--json")
        self.assertEqual(FakeApi.calls[-1], ("POST", "/api/v1/var/stress-window/proposals/SWP-000001/decision",
                                             {"decision": "approve", "note": ""}))
        self.assertEqual(run("stress-window", "--decide", "SWP-000001"), 2)                       # no decision named
        run("stress-window", "--proposals", "--json")
        self.assertEqual(FakeApi.calls[-1][0:2], ("GET", "/api/v1/var/stress-window/proposals"))
        out = []
        V.render_stress_window({"node": {"id": "MGMT~BANK"}, "date": "2026-09-30", "length": 250, "confidence": 0.99,
                                "history": {"first": "2007-01-02", "last": "2026-09-29", "days": 4938, "synthetic": True},
                                "current": {"first": "2008-07-01", "last": "2009-06-30", "var": 213637794},
                                "worst": {"first": "2007-11-30", "last": "2008-11-28", "var": 249444106},
                                "alert": {"raised": True, "gap": 0.168, "threshold": 0.05},
                                "candidates": [{"rank": 1, "first": "2007-11-30", "last": "2008-11-28", "var": 249444106, "vsCurrent": 0.168,
                                                "worst": True, "current": False}],
                                "byNode": [{"id": "MGMT~BANK", "current": 213637794, "worst": 249444106, "worstFirst": "2007-11-30", "gap": 0.168}]},
                               out.append)
        self.assertTrue(out[0].startswith("SWIN MGMT~BANK"))
        self.assertIn("ALERT: worst against current +16.8% (threshold 5.0%)", out)
        self.assertTrue(any("249,444,106" in line and "worst" in line for line in out))

    def test_the_scenario_matrix_asks_its_path_prints_gaps_as_dashes_and_writes_csv(self):
        run("matrix", "T1", "--days", "2", "--date", "2026-09-30", "--confidence", "0.95", "--json")
        self.assertEqual(self.query()[1:3], ("/api/v1/var/matrix", {"id": "T1", "days": "2", "confidence": "0.95", "asOf": "2026-09-30"}))
        run("matrix", "POS-1", "--by", "trade", "--scenario", "2026-08-31", "--json")
        self.assertEqual(self.query()[2], {"id": "POS-1", "days": "20", "by": "trade", "scenario": "2026-08-31"})
        out = []
        V.render_matrix(MATRIX, 10, out.append)
        self.assertTrue(out[0].startswith("SMAT TRD:T1"))
        self.assertTrue(any("DRS-5203 refused" in line for line in out))
        self.assertTrue(any(line.startswith("2026-08-31") and "—" in line and "-1,000*" in line for line in out))
        csv = V.matrix_csv(MATRIX).splitlines()
        self.assertEqual(csv[:3], ["scenario,2026-09-29,2026-09-30", "2026-08-31,,-1000", "2026-09-01,,200"])   # a gap is empty, not 0
        self.assertIn("gap,DRS-5203 refused,", csv)

    def test_a_whatif_posts_copies_and_priced_trades_to_the_servers_engine(self):
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "trades.json"
            f.write_text(json.dumps([{"LABEL": "new swap", "PNL": [1.0, -2.0], "FACTOR": 1}]), encoding="utf-8")
            self.assertEqual(run("whatif", "DESK:RATES", "--trades", str(f), "--copy", "BOOK:BOOK-RATES-3=-0.5", "--date", "2026-09-30"), 0)
            self.assertEqual(self.query()[0:3], ("POST", "/api/v1/var/what-if", {"asOf": "2026-09-30"}))
            self.assertEqual(FakeApi.calls[-1][2], {"scope": "DESK:RATES", "items": [
                {"copyOf": "BOOK:BOOK-RATES-3", "factor": -0.5}, {"label": "new swap", "factor": 1, "pnl": [1.0, -2.0]}]})
            f.write_text(json.dumps([{"LABEL": "no vector"}]), encoding="utf-8")
            with self.assertRaises(Err):
                run("whatif", "DESK:RATES", "--trades", str(f))
            with self.assertRaises(Err):
                run("whatif", "DESK:RATES")
            with self.assertRaises(Err):
                run("whatif", "DESK:RATES", "--copy", "MX-1=half")


    def test_explain_asks_its_paths_and_prints_the_waterfall_as_aligned_lines(self):
        run("explain", "DESK:RATES", "--from", "2026-09-29", "--to", "2026-09-30", "--json")
        self.assertEqual(self.query(), ("GET", "/api/v1/var/explain", {"scope": "DESK:RATES", "from": "2026-09-29", "to": "2026-09-30"}, None))
        run("explain", "BANK", "--matrix", "--json")
        self.assertEqual(self.query()[1:3], ("/api/v1/var/explain/matrix", {"scope": "BANK"}))
        run("explain", "BANK", "--confidence", "0.95", "--json")
        self.assertEqual(self.query()[2], {"scope": "BANK", "confidence": "0.95"})
        out = []
        V.render_explain(EXPLAIN, out.append)
        text = "\n".join(out)
        self.assertIn("VaR of Rates rose", out[0])
        wf = out[2:9]                                    # the waterfall: after the sentence and a blank line
        self.assertTrue(wf[0].startswith("VaR 2026-09-29") and wf[-1].startswith("change"), wf)
        for ln in wf:                                    # the labels padded to 14, the amounts right-aligned in 16
            self.assertEqual(ln[14:16], "  ", ln)
            self.assertTrue(ln[16:32].strip() and ln[31] != " ", ln)
        self.assertIn("+120,000  " + "+" * 32, text)
        self.assertIn("-50,000  " + "-" * 13, text)
        self.assertIn("the steps add up: yes", text)
        self.assertIn("T-9", text)
        self.assertIn("the other trades and the interaction", text)
        self.assertIn("adds up: yes", text)
        m = []
        V.render_explain_matrix({"from": "2026-09-29", "to": "2026-09-30", "steps": EXPLAIN["lines"],
                                 "rows": [{"label": "Rates", "depth": 0, "varFrom": 1, "varTo": 2, "change": 1, "effects": {"market": 1}}]}, m.append)
        self.assertIn("Market", m[0])

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

class RegulatoryCli(unittest.TestCase):
    """var backtest, var stats, var report and var official --synthetic-history (VAR_GUIDE.md sections 18.10, 18.11 and 18.14)."""

    def setUp(self):
        FakeApi.calls = []

    def test_each_reaches_its_path(self):
        run("backtest", "DESK:RATES", "--date", "2026-09-30", "--json")
        self.assertEqual(FakeApi.calls[-1][1], "/api/v1/var/backtesting?scope=DESK%3ARATES&asOf=2026-09-30")
        run("stats", "BANK", "--period", "FY2026", "--json")
        self.assertEqual(FakeApi.calls[-1][1], "/api/v1/var/statistics?scope=BANK&period=FY2026&measure=var")
        run("report", "run", "annual-report-var", "FY2026", "--json")
        self.assertEqual(FakeApi.calls[-1][:2], ("POST", "/api/v1/var/reports/annual-report-var/runs?period=FY2026"))
        run("report", "sign", "annual-report-var", "FY2026", "--step", "checker", "--comment", "ok", "--json")
        self.assertEqual(FakeApi.calls[-1], ("POST", "/api/v1/var/reports/annual-report-var/runs/FY2026/signoff",
                                             {"action": "approve", "step": "checker", "comment": "ok"}))
        run("official", "--synthetic-history", "259")
        self.assertEqual(FakeApi.calls[-1][:2], ("POST", "/api/v1/var/official/synthetic-history?days=259"))


if __name__ == "__main__":
    unittest.main()
