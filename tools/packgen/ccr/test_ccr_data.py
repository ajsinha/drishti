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

"""The committed exposure inputs (data/testdata/counterparty-risk/{simulation-run,exposure-path}, tools/packgen/ccr/make_ccr_data.py)
are what an upstream exposure simulator delivers and nothing more (docs/contracts/EXP.md sections 3 and 13):

- one run of the business date: its grid strictly increasing after the date, every look-back point mapped to a later primary point,
  13 primary dates (1W .. 10Y);
- one row per live member trade of the eight committed netting sets and grid date up to its maturity plus 10 business days (sparse by
  maturity: no row missing before, none after), every MTM vector of the run's length and finite, whole USD, unique row ids;
- the denormalised netting set, counterparty and legal entity equal the netting set's;
- path values at the first grid date near the trade's MtM today as the model amortises it (the t0 check of EXP.md section 10);
- no figure an engine computes (EE, PFE, EPE ...) on any input; the reference math reproduces EXP.md section 15's worked example.

    python3 -m pytest tools/packgen/ccr/test_ccr_data.py -q          (pyyaml and pytest)
"""
import gzip
import json
import math
import sys
import unittest
from collections import Counter, defaultdict
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(HERE))
import ccrmath as M  # noqa: E402
import make_ccr_data as X  # noqa: E402
import paths as PT  # noqa: E402

TD = ROOT / "data" / "testdata" / "counterparty-risk"
ENGINE_FIGURES = {"EE", "ENE", "EPE", "EEPE", "PFE", "PFE_95", "PFE_99", "PFE_PEAK", "EE_PEAK", "PEAK_PFE", "PEAK_EE", "EAD_IMM", "PROFILE"}


def docs(kind: str) -> list[dict]:
    out = []
    for f in sorted((TD / kind).glob("*.jsonl.gz")):
        with gzip.open(f, "rt", encoding="utf-8") as fh:
            out += [json.loads(line) for line in fh if line.strip()]
    return out


class TheExposureInputs(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        import yaml
        cls.runs = docs("simulation-run")
        cls.rows = docs("exposure-path")
        dates = yaml.safe_load((ROOT / "data" / "testdata" / "testdata.yaml").read_text(encoding="utf-8"))["business-dates"]
        cls.inputs = X.Inputs(ROOT / "data" / "testdata", dates)

    def test_one_run_with_a_good_grid(self):
        self.assertEqual(len(self.runs), 1)
        r = self.runs[0]
        ds = [date.fromisoformat(d) for d in r["GRID_DATES"]]
        self.assertEqual(ds, sorted(set(ds)))
        self.assertGreater(ds[0], date.fromisoformat(r["BUSINESS_DATE"]))
        self.assertEqual(len(r["GRID_ROLE"]), len(ds))
        self.assertEqual(len(r["LOOKBACK_OF"]), len(ds))
        self.assertEqual(r["GRID_ROLE"].count("PRIMARY"), 13)
        for k, (role, of) in enumerate(zip(r["GRID_ROLE"], r["LOOKBACK_OF"])):
            if role == "LOOKBACK":
                self.assertGreater(of, k)
                self.assertEqual(r["GRID_ROLE"][of], "PRIMARY")
                self.assertEqual(PT.CAL.add_business_days(ds[k], r["LOOKBACK_DAYS"]), ds[of])
            else:
                self.assertEqual(of, -1)
        self.assertEqual(r["ROWS"], len(self.rows))
        self.assertEqual(r["CURRENCY"], "USD")
        self.assertIn(r["PRECISION"], ("float32", "float64"))

    def test_rows_are_whole_sparse_by_maturity_and_unique(self):
        r = self.runs[0]
        p, ds = r["PATHS"], [date.fromisoformat(d) for d in r["GRID_DATES"]]
        ids = Counter(x["PATH_ROW_ID"] for x in self.rows)
        self.assertEqual(max(ids.values()), 1)
        by_trade = defaultdict(set)
        for x in self.rows:
            self.assertEqual(len(x["MTM"]), p)
            self.assertTrue(all(isinstance(v, int) for v in x["MTM"]))            # whole USD
            self.assertEqual(x["RUN_ID"], r["RUN_ID"])
            self.assertEqual(x["BATCH_ID"], r["BATCH_ID"])
            self.assertEqual(x["GRID_DATE"], r["GRID_DATES"][x["GRID_INDEX"]])
            self.assertEqual(x["PATH_ROW_ID"], f"{r['RUN_ID']}~{x['TRADE_ID']}~{x['GRID_INDEX']}")
            by_trade[x["TRADE_ID"]].add(x["GRID_INDEX"])
        for x in self.rows:
            until = PT.CAL.add_business_days(date.fromisoformat(x["MATURITY_DATE"]), PT.LOOKBACK_DAYS)
            want = {k for k, d in enumerate(ds) if d <= until}
            self.assertEqual(by_trade[x["TRADE_ID"]], want, x["TRADE_ID"])

    def test_the_sets_and_their_members(self):
        sets = Counter(x["NETTING_SET"] for x in self.rows)
        self.assertEqual(set(sets), set(X.COMMITTED))
        members = {ns: {t["TRADE_ID"] for t in self.inputs.members(ns)} for ns in X.COMMITTED}
        for x in self.rows:
            s = self.inputs.sets[x["NETTING_SET"]]
            self.assertIn(x["TRADE_ID"], members[x["NETTING_SET"]])
            self.assertEqual((x["COUNTERPARTY"], x["LEGAL_ENTITY"]), (s["COUNTERPARTY"], s["LEGAL_ENTITY"]))
        self.assertEqual({x["TRADE_ID"] for x in self.rows}, set().union(*members.values()))
        self.assertLessEqual(len({x["TRADE_ID"] for x in self.rows}), X.MAX_TRADES)
        self.assertIn("Securities financing", {x["ASSET_CLASS"] for x in self.rows if x["NETTING_SET"] == "NS-ALDERSHOT-FRA"})

    def test_paths_start_near_todays_mtm(self):
        cob = date.fromisoformat(self.runs[0]["BUSINESS_DATE"])
        t1 = PT.years(cob, date.fromisoformat(self.runs[0]["GRID_DATES"][0]))
        mtm = {t["TRADE_ID"]: (float(t["MTM"]), float(t["NOTIONAL"]), t["MATURITY"]) for ns in X.COMMITTED for t in self.inputs.members(ns)}
        far = []
        for x in self.rows:
            if x["GRID_INDEX"] != 0:
                continue
            m0, n, mat = mtm[x["TRADE_ID"]]
            a = max(0.0, 1 - t1 / max(PT.years(cob, date.fromisoformat(mat)), 1 / 365))
            mean = sum(x["MTM"]) / len(x["MTM"])
            if abs(mean - a * m0) > max(0.10 * abs(m0), 0.01 * abs(n)):
                far.append(x["TRADE_ID"])
        self.assertEqual(far, [])

    def test_no_engine_figure_on_an_input(self):
        for x in self.runs + self.rows[:200]:
            self.assertEqual(set(x) & ENGINE_FIGURES, set())

    def test_the_reference_math_is_the_contracts(self):
        r = M.worked_example()
        s = r["stats"]
        self.assertEqual([p["ee"] for p in s["profile"]], [1.0, 1.5, 0.75])
        self.assertEqual([p["pfe"][0.75] for p in s["profile"]], [1, 2, 1])
        self.assertAlmostEqual(s["epe"], 1.0)
        self.assertAlmostEqual(s["eepe"], 1.375)
        self.assertEqual(s["peak_pfe"][0.75], (2, 4))
        self.assertEqual(r["contrib_uncoll_t2"], {"A": 2.0, "B": 0.25})
        self.assertEqual(M.rank(200, 0.95), 190)
        c = M.Csa(th_them=10, th_us=5, mta_them=2, ia=1)
        self.assertEqual(M.called(25, c), 14)
        self.assertEqual(M.called(-8, c), -2)

    def test_the_parity_reference_is_up_to_date(self):
        self.assertEqual(X.main(["--check"]), 0)
        ref = json.loads(X.REFERENCE.read_text(encoding="utf-8"))
        self.assertEqual(set(ref["sets"]), set(X.COMMITTED))
        self.assertTrue(all(math.isfinite(v["EPE"]) for v in ref["sets"].values()))


if __name__ == "__main__":
    unittest.main()
