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

"""The committed stress scenario set (tools/packgen/var/stress.py; docs/guides/VAR_GUIDE.md section 18.8):

- one stress set ``HS-STRESS-2008`` on the business date beside the normal ``HS-509``: ``SET_TYPE: STRESS``, labelled synthetic, the 250
  business days of 2008-07-01 to 2009-06-30 in date order;
- every trade of the normal set once more under the stress set, in the same form (P&L vector or scenario PVs) with the same terms, run and
  batch, 250 finite values; a scenario-PV trade keeps its base PV and its currency has the stress set's scenario FX;
- the stress files apart from the normal set's (``stress-…``), so the normal set's readers and figures are unchanged;
- the bank's stressed VaR is above its VaR.

    python3 -m pytest tools/packgen/var/test_stress_data.py -q
"""
import math
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import stress as ST  # noqa: E402
import varmath as VM  # noqa: E402
from test_var_data import docs  # noqa: E402


class StressDataTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.sets = docs("scenario-set", stress=True)
        cls.pnl = docs("pnl-vector", stress=True)
        cls.spv = docs("scenario-pv", stress=True)
        cls.fx = {r["CURRENCY"]: r for r in docs("fx-scenario", stress=True)}
        cls.normal = {r["TRADE_ID"]: r for r in docs("pnl-vector") + docs("scenario-pv")}

    def test_one_synthetic_stress_set_of_250_days_2008_07_01_to_2009_06_30(self):
        self.assertEqual(len(self.sets), 1)
        s = self.sets[0]
        self.assertEqual((s["SCENARIO_SET_ID"], s["SET_TYPE"], s["SYNTHETIC"]), (ST.SET, "STRESS", True))
        days = s["SCENARIO_DATES"]
        self.assertEqual((len(days), days[0], days[-1], s["SCENARIO_COUNT"]), (250, "2008-07-01", "2009-06-30", 250))
        self.assertEqual(days, sorted(set(days)))
        self.assertIn("synthetic", s["DESCRIPTION"])
        self.assertEqual([d["SCENARIO_SET_ID"] for d in docs("scenario-set")], ["HS-509"])     # the normal set is unchanged

    def test_every_trade_once_more_in_its_own_form_with_its_terms(self):
        seen = set()
        for r in self.pnl + self.spv:
            t = r["TRADE_ID"]
            self.assertNotIn(t, seen)
            seen.add(t)
            n = self.normal[t]
            self.assertEqual(r["SCENARIO_SET_ID"], ST.SET)
            for k in ("VALUE_TYPE", "GRAIN", "CURRENCY", "SOURCE_SYSTEM", "TT_BOOK", "DESK", "LEGAL_ENTITY", "TRADE_STATUS", "BATCH_ID",
                      "RUN_KIND", "QUANTITY"):
                self.assertEqual(r.get(k), n.get(k), f"{t} {k}")
            values = r["PNL"] if "PNL" in r else r["SCENARIO_PV"]
            self.assertEqual(len(values), 250, t)
            self.assertTrue(all(isinstance(x, (int, float)) and math.isfinite(x) for x in values), t)
            if "PNL" in r:
                self.assertEqual(r["VECTOR_ID"], f"PNL-{t}-{ST.SET}")
            else:
                self.assertEqual((r["SCENARIO_PV_ID"], r["BASE_PV"]), (f"SPV-{t}-{ST.SET}", n["BASE_PV"]))
                self.assertTrue(r["CURRENCY"] == "USD" or len(self.fx[r["CURRENCY"]]["RATES"]) == 250, t)
        self.assertEqual(seen, set(self.normal))

    def test_the_bank_loses_more_under_the_stress_set(self):
        def usd(r):
            if "PNL" in r:
                return [float(x) for x in r["PNL"]]
            c = r["CURRENCY"]
            r0 = 1.0 if c == "USD" else self.fx[c]["RATE"]
            return [p * (1.0 if c == "USD" else self.fx[c]["RATES"][j]) - r["BASE_PV"] * r0 for j, p in enumerate(r["SCENARIO_PV"])]
        bank = VM.add(usd(r) for r in self.pnl + self.spv)
        self.assertEqual(len(bank), 250)
        self.assertGreater(VM.var(bank), 1.5 * 112_041_646)          # the normal set's VaR of the bank (VAR_GUIDE.md section 18.6)


class HistoryDataTest(unittest.TestCase):
    """The long-history P&L of today's portfolio per node (history.py, kind pnl-history; VAR_GUIDE.md section 18.9): every MGMT and LEGAL
    node above the books, every business day since 2007, the stress set's and the normal set's days carried as they are (so the
    configured window gives the bank's stressed VaR and the last 509 days its VaR), and a node the sum of its children."""

    @classmethod
    def setUpClass(cls):
        cls.rows = {r["NODE_ID"]: r for r in docs("pnl-history")}
        cls.nodes = {r["NODE_ID"]: r for r in docs("risk-hierarchy")}

    def test_one_row_per_node_above_the_books_every_day_since_2007(self):
        want = {n for n, r in self.nodes.items() if r["LEVEL"] != "book"}
        self.assertEqual(set(self.rows), want)
        for r in self.rows.values():
            self.assertEqual((r["FIRST_DAY"], r["HISTORY_ID"], r["CURRENCY"], r["SYNTHETIC"]), ("2007-01-02", "PNLH-" + r["NODE_ID"], "USD", True))
            self.assertEqual(len(r["HISTORY_DATES"]), r["DAY_COUNT"])
            self.assertEqual(len(r["PNL"]), r["DAY_COUNT"])
            self.assertEqual(r["HISTORY_DATES"], sorted(set(r["HISTORY_DATES"])))
            self.assertGreater(r["DAY_COUNT"], 4800)

    def test_the_stress_window_and_the_last_509_days_are_the_engines(self):
        bank = self.rows["MGMT~BANK"]
        days, pnl = bank["HISTORY_DATES"], bank["PNL"]
        at = days.index("2008-07-01")
        self.assertAlmostEqual(VM.var(pnl[at:at + 250]), 213_637_794, delta=1.0)    # the bank's stressed VaR (VAR_GUIDE.md section 18.8)
        self.assertAlmostEqual(VM.var(pnl[-509:]), 112_041_646, delta=1.0)          # the bank's VaR (section 18.6)
        self.assertEqual(days[-1], docs("scenario-set")[0]["SCENARIO_DATES"][-1])

    def test_a_node_is_the_sum_of_its_children(self):
        bank = self.rows["MGMT~BANK"]["PNL"]
        kids = [self.rows[n]["PNL"] for n, r in self.nodes.items() if r.get("PARENT_ID") == "MGMT~BANK"]
        total = VM.add(kids)
        self.assertTrue(all(abs(a - b) < 1.0 for a, b in zip(bank, total)))
