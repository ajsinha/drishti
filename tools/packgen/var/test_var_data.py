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

"""The committed VaR demo data (data/testdata/market-risk/, tools/packgen/var/make_var_data.py) is consistent:

- every position nets its trades: its QUANTITY is the signed sum of its trades', its DIRECTION the sign, its vector the sum of their
  vectors; some positions hold both buys and sells;
- every node's stored vector on the business date is the sum of its positions' (and of its trades'), and its VaR and ES the rules';
- a portfolio's positions match its definition (HIERARCHY: its node's books; RULE: the condition over the positions' columns; BASKET:
  its members, a trade member holding that part of its position), and its VaR is the VaR of its members' vectors, each trade once;
- the rule language and the scope's measures agree with the server's (the same hand-made answers as VarMathTest).

    python3 -m pytest tools/packgen/var/test_var_data.py -q          (pyyaml and pytest)
"""
import gzip
import json
import sys
import unittest
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import rules as R  # noqa: E402
import varmath as VM  # noqa: E402

TD = HERE.parents[2] / "data" / "testdata" / "market-risk"


def docs(kind: str) -> list[dict]:
    out = []
    for f in sorted((TD / kind).glob("*.jsonl.gz")):
        with gzip.open(f, "rt", encoding="utf-8") as fh:
            out += [json.loads(line) for line in fh if line.strip()]
    return out


class VarDataTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.positions = {p["POSITION_ID"]: p for p in docs("position")}
        cls.vectors = {v["TRADE_ID"]: v for v in docs("pnl-vector")}
        cls.nodes = {n["NODE_ID"]: n for n in docs("risk-hierarchy")}
        cls.portfolios = {p["PORTFOLIO_ID"]: p for p in docs("portfolio")}
        cls.history = docs("var-node")

    def test_positions_net_their_trades_long_and_short(self):
        both = 0
        for pid, p in self.positions.items():
            trades = [self.vectors[t] for t in p["TRADE_IDS"]]
            self.assertTrue(all(t["POSITION_ID"] == pid for t in trades), pid)
            q = sum(t["QUANTITY"] for t in trades)
            self.assertAlmostEqual(p["QUANTITY"], q, places=2, msg=pid)
            self.assertEqual(p["DIRECTION"], "LONG" if q > 0 else "SHORT" if q < 0 else "FLAT", pid)
            self.assertEqual(p["PNL"], VM.add(t["PNL"] for t in trades), pid)
            longs = sum(1 for t in trades if t["QUANTITY"] > 0)
            self.assertEqual((p["LONG_TRADES"], p["SHORT_TRADES"]), (longs, len(trades) - longs), pid)
            if longs and longs < len(trades):
                both += 1
                # a hedged position: its VaR is less than its trades' VaRs added up, and its trades' components add up to it
                self.assertLess(p["VAR_99"], sum(VM.var(t["PNL"]) for t in trades), pid)
                self.assertAlmostEqual(sum(r["COMPONENT_VAR"] for r in p["TRADE_QUANTITIES"]), p["VAR_99"], delta=len(trades), msg=pid)
            self.assertEqual(p["VAR_99"], round(VM.var(p["PNL"])), pid)
            self.assertEqual(p["ES_975"], round(VM.es(p["PNL"])), pid)
        self.assertGreater(both, 10, "positions holding both buys and sells")
        self.assertGreater(sum(1 for p in self.positions.values() if p["DIRECTION"] == "SHORT"), 10)
        self.assertGreater(len({p["CURRENCY"] for p in self.positions.values()}), 4)
        self.assertGreater(len({p["PRODUCT_TYPE"] for p in self.positions.values()}), 20)

    def test_every_trade_is_in_one_position_and_every_vector_has_509_values(self):
        seen = defaultdict(list)
        for pid, p in self.positions.items():
            for t in p["TRADE_IDS"]:
                seen[t].append(pid)
        self.assertEqual(set(seen), set(self.vectors))
        self.assertTrue(all(len(v) == 1 for v in seen.values()))
        self.assertTrue(all(len(v["PNL"]) == 509 for v in self.vectors.values()))

    def test_node_vectors_are_the_sums_of_their_positions(self):
        cob = max(r["BUSINESS_DATE"] for r in self.history)
        today = {r["NODE_ID"]: r for r in self.history if r["BUSINESS_DATE"] == cob}
        self.assertEqual(set(today), set(self.nodes))
        for nid, n in self.nodes.items():
            books = set(n["BOOKS"])
            want = VM.add(p["PNL"] for p in self.positions.values() if p["TT_BOOK"] in books)
            self.assertEqual(today[nid]["PNL"], want, nid)
            self.assertEqual(today[nid]["VAR_99"], round(VM.var(want)), nid)
            self.assertEqual(today[nid]["ES_975"], round(VM.es(want)), nid)
        self.assertEqual(len({r["BUSINESS_DATE"] for r in self.history}), 250)

    def test_portfolios_hold_what_they_define_and_overlaps_count_once(self):
        types = {p["TYPE"] for p in self.portfolios.values()}
        self.assertEqual(types, {"HIERARCHY", "RULE", "BASKET"})
        for pid, pf in self.portfolios.items():
            members: dict[str, list[str]] = {}
            d = pf["DEFINITION"]
            if pf["TYPE"] == "HIERARCHY":
                books = set(self.nodes[d["NODE"]]["BOOKS"])
                members = {k: p["TRADE_IDS"] for k, p in self.positions.items() if p["TT_BOOK"] in books}
            elif pf["TYPE"] == "RULE":
                rule = R.Rule(d["RULE"])
                members = {k: p["TRADE_IDS"] for k, p in self.positions.items() if rule.matches(p)}
            else:
                for m in d["MEMBERS"]:
                    if "POSITION_ID" in m:
                        members[m["POSITION_ID"]] = self.positions[m["POSITION_ID"]]["TRADE_IDS"]
                    else:
                        pos = self.vectors[m["TRADE_ID"]]["POSITION_ID"]
                        members.setdefault(pos, [])
                        if m["TRADE_ID"] not in members[pos]:
                            members[pos] = members[pos] + [m["TRADE_ID"]]
            self.assertEqual(sorted(x["POSITION_ID"] for x in pf["POSITIONS"]), sorted(members), pid)
            trades = sorted({t for ts in members.values() for t in ts})
            vec = VM.add(self.vectors[t]["PNL"] for t in trades)
            self.assertEqual(pf["VAR_99"], round(VM.var(vec)), pid)
            self.assertEqual(pf["PNL"], [round(x) for x in vec], pid)
            self.assertAlmostEqual(sum(x["COMPONENT_VAR"] for x in pf["POSITIONS"]), pf["VAR_99"], delta=len(pf["POSITIONS"]), msg=pid)
            self.assertEqual(pf["POSITION_COUNT"], len(pf["POSITIONS"]))
            self.assertEqual(pf["LONG_COUNT"] + pf["SHORT_COUNT"], sum(1 for x in pf["POSITIONS"] if x["DIRECTION"] != "FLAT"))
        # two overlapping portfolios: the union counts a shared position once
        a, b = self.portfolios["PF-RATES-DESK"], self.portfolios["PF-ASHA-UNWIND"]
        shared = {x["POSITION_ID"] for x in a["POSITIONS"]} & {x["POSITION_ID"] for x in b["POSITIONS"]}
        self.assertTrue(shared, "the basket overlaps the desk on purpose")

    def test_the_rule_language(self):
        p = {"PRODUCT_GROUP": "Rates", "CURRENCY": "USD", "DIRECTION": "SHORT", "MARKET_VALUE": 2_500_000.0, "TAGS": ["hedge"]}
        self.assertTrue(R.Rule("PRODUCT_GROUP == 'Rates' and CURRENCY in ('USD', 'CAD')").matches(p))
        self.assertTrue(R.Rule("'hedge' in TAGS and not DIRECTION == 'LONG'").matches(p))
        self.assertTrue(R.Rule("MARKET_VALUE > 1000000").matches(p))
        self.assertFalse(R.Rule("COUNTERPARTY == 'CP-X'").matches(p))
        self.assertTrue(R.Rule("COUNTERPARTY != 'CP-X'").matches(p))

    def test_the_measures_follow_the_servers_rules(self):
        v = [0.0] * 100
        for i in range(100):
            v[(i * 37) % 100] = -(i + 1)
        self.assertEqual(VM.var(v, 0.99), 100)
        self.assertEqual(VM.var(v, 0.95), 96)
        self.assertEqual(VM.es(v, 0.95), (100 + 99 + 98 + 97 + 96) / 5)
        self.assertEqual(VM.rank_k(509, 0.99), 6)
        self.assertEqual(VM.rank_k(509, 0.975), 13)


if __name__ == "__main__":
    unittest.main()
