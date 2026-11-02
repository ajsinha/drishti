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

"""The demo position sensitivities and factor dictionary (tools/packgen/sensitivity/make_sensitivity_data.py) are what a bank's
sensitivity batch delivers, consistent with the VaR demo's factor model, and follow their planned contracts:

- every factor name follows the naming standard and is in the dictionary once; its FRTB and SIMM buckets are on the regulatory grids;
- one record per position the server derives from the VaR inputs of the date, every element's factor in the dictionary, no factor and
  measure twice in a record, curvature with its shock, vega with its implied volatility, options only with curvature;
- a hand-built swap: its DV01 split onto the two vertices around its maturity, and its one-sigma P&L the VaR batch's;
- the committed files are what the generator writes, byte for byte.

    python3 -m pytest tools/packgen/sensitivity/test_sensitivity_data.py -q          (pyyaml and pytest)
"""
import gzip
import json
import math
import re
import sys
import unittest
from collections import Counter
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(HERE))
import factor_dictionary as D  # noqa: E402
import make_sensitivity_data as S  # noqa: E402

TD = ROOT / "data" / "testdata"
NAME = re.compile(r"^(IR/CURVE/[A-Z]{3}(/[A-Z]+/\d+[MY])?|IR/BASIS/[A-Z]{3}|INF/[A-Z]+(/\d+Y)?|CR/SPREAD/[A-Z0-9.-]+(/(BOND|CDS)/\d+[MY])?|"
                  r"FX/SPOT/[A-Z]{3}|EQ/(SPOT|REPO)/[A-Z0-9-]+|CM/PRICE/[A-Z]+(/[A-Z_]+/\d+[MY])?|VOL/(IR|FX|EQ|CM|CR)/[A-Z0-9./-]+?/\d+[MY](/\d+Y)?)$")
FRTB_BUCKETS = {"CSR_NS": {str(b) for b in range(1, 19)}, "EQ": {str(b) for b in range(1, 14)}, "COMM": {str(b) for b in range(1, 12)}}
MEASURES = {"DV01", "CS01", "IE01", "DELTA", "VEGA", "CURVATURE_UP", "CURVATURE_DOWN"}


def lines(f: Path) -> list[dict]:
    with gzip.open(f, "rt", encoding="utf-8") as fh:
        return [json.loads(x) for x in fh if x.strip()]


def contract(kind: str) -> dict:
    return yaml.safe_load((ROOT / "config" / "packs" / "market-risk" / "contracts" / f"{kind}.contract.yaml").read_text(encoding="utf-8"))


class FactorDictionary(unittest.TestCase):
    def test_every_name_follows_the_standard_and_is_unique(self):
        names = [r["RISK_FACTOR"] for r in D.factors()]
        self.assertEqual(len(names), len(set(names)))
        self.assertEqual([n for n in names if not NAME.match(n)], [])
        aliases = [a for r in D.factors() for a in r["SOURCE_NAMES"]]
        self.assertEqual(len(aliases), len(set(aliases)), "a source system's name maps to one factor only")

    def test_buckets_on_the_regulatory_grids(self):
        for r in D.factors():
            if r["RISK_CLASS"] in FRTB_BUCKETS:
                self.assertIn(r["FRTB_BUCKET"], FRTB_BUCKETS[r["RISK_CLASS"]], r["RISK_FACTOR"])
            else:                                            # GIRR and FX: one bucket per currency
                self.assertEqual(r["FRTB_BUCKET"], r["QUALIFIER"], r["RISK_FACTOR"])
            self.assertEqual(r["SIMM_RISK_CLASS"], D.SIMM_CLASS[r["RISK_CLASS"]])
            self.assertIn(r["LIQUIDITY_HORIZON"], (10, 20, 40, 60, 120))
            if r["TENOR"] and r["FACTOR_TYPE"] == "CURVE":
                self.assertIn(r["TENOR"], D.GIRR_TENORS)

    def test_the_rows_carry_every_field_of_the_contract(self):
        c = contract("risk-factor")
        fields = {f["name"] for f in c["fields"]}
        for r in S.dictionary_rows("2026-09-30"):
            self.assertLessEqual(set(r), fields, r["RISK_FACTOR"])
        required = {f["name"] for f in c["fields"] if f["presence"] == "required"}
        self.assertEqual([r["RISK_FACTOR"] for r in S.dictionary_rows("2026-09-30") if [k for k in required if r.get(k) in (None, "")]], [])

    def test_a_cash_flow_splits_onto_the_two_vertices_around_it(self):
        self.assertEqual(D.nearest_two(D.GIRR_TENORS, 7.0), [("5Y", 0.6), ("10Y", 0.4)])
        self.assertEqual(D.nearest_two(D.GIRR_TENORS, 0.1), [("3M", 1.0)])
        self.assertEqual(D.nearest_two(D.GIRR_TENORS, 45.0), [("30Y", 1.0)])
        self.assertEqual(D.nearest_two(D.GIRR_TENORS, 5.0), [("5Y", 1.0)])


class HandBuilt(unittest.TestCase):
    """One USD swap and one EUR option, built by hand: the shape of their records."""

    SWAP = {"TRADE_ID": "T-1", "TT_BOOK": "BOOK-RATES-1", "DESK": "DESK-RATES", "LEGAL_ENTITY": "LE-NY", "DIVISION": "DIV-MARKETS",
            "PRODUCT_GROUP": "Rates", "PRODUCT_TYPE": "IRS_FIXFLOAT", "CURRENCY": "USD", "QUANTITY": 1_000_000.0, "TRADE_STATUS": "NEW",
            "MATURITY_DATE": "2033-09-30", "SOURCE_SYSTEM": "MUREX", "INSTRUMENT": "Swap USD 2033-09"}
    OPTION = {**SWAP, "TRADE_ID": "T-2", "TT_BOOK": "BOOK-FX-1", "DESK": "DESK-FX", "PRODUCT_GROUP": "FX", "PRODUCT_TYPE": "FX_OPTION",
              "CURRENCY": "EUR", "QUANTITY": -5_000_000.0, "MATURITY_DATE": "2027-09-30"}

    def setUp(self):
        self.recs = {r["TRADE_IDS"][0]: r for r in S.records([self.SWAP, self.OPTION], "2026-09-30")}

    def test_the_swap_dv01_on_5y_and_10y_and_its_one_sigma_pnl_the_var_batchs(self):
        rec = self.recs["T-1"]
        els = {e["RISK_FACTOR"]: e for e in rec["SENSITIVITIES"]}
        self.assertEqual(sorted(els), ["IR/CURVE/USD/SOFR/10Y", "IR/CURVE/USD/SOFR/5Y"])
        a, b = els["IR/CURVE/USD/SOFR/5Y"]["VALUE"], els["IR/CURVE/USD/SOFR/10Y"]["VALUE"]
        years = S.years_between("2026-09-30", "2033-09-30")
        self.assertAlmostEqual(a / (a + b), 1 - (years - 5) / 5, places=3)
        pos = S.Position(rec["POSITION_ID"], [self.SWAP], "2026-09-30")
        self.assertAlmostEqual(-(a + b) * S.DAILY_SIGMA["IR"], pos.scale, delta=1.0)   # long one unit: −DV01 × 6 bp = one sigma
        self.assertNotIn("CURVATURE_UP", {e["MEASURE"] for e in rec["SENSITIVITIES"]})

    def test_the_short_option_has_vega_and_long_gamma_curvature(self):
        rec = self.recs["T-2"]
        by = {(e["RISK_FACTOR"], e["MEASURE"]): e for e in rec["SENSITIVITIES"]}
        delta = by[("FX/SPOT/EUR", "DELTA")]["VALUE"]
        up, down = by[("FX/SPOT/EUR", "CURVATURE_UP")], by[("FX/SPOT/EUR", "CURVATURE_DOWN")]
        self.assertEqual(up["SHOCK_SIZE"], 0.15)
        linear = delta * 15                                  # the 15% shock, DELTA per 1%
        cvr = -min(up["VALUE"] - linear, down["VALUE"] + linear)
        self.assertLess(cvr, 0, "a short quantity is long gamma in the VaR model: a negative curvature risk")
        self.assertTrue(any(m == "VEGA" and "IMPLIED_VOL" in e for (f, m), e in by.items()))


class CommittedData(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cob = S.cob_of(TD)
        cls.recs = lines(TD / "market-risk" / "position-sensitivity" / f"cob-{cls.cob}.jsonl.gz")
        cls.dic = {r["RISK_FACTOR"]: r for r in lines(TD / "market-risk" / "risk-factor" / f"cob-{cls.cob}.jsonl.gz")}

    def test_the_committed_files_are_what_the_generator_writes(self):
        for rel, data in S.files(TD).items():
            self.assertEqual((TD / rel).read_bytes(), data, f"{rel}: run python3 tools/packgen/sensitivity/make_sensitivity_data.py")

    def test_one_record_per_derived_position_or_per_trade_from_the_trade_grain_system(self):
        terms = S.read_terms(TD, self.cob)
        want = S.positions(terms, S.P.Rules.of_pack(S.PACK_YAML))
        self.assertEqual(sorted({r["POSITION_ID"] for r in self.recs}), sorted(want))
        by_system = {(r["SOURCE_SYSTEM"], r["GRAIN"]) for r in self.recs}
        self.assertEqual(len({s for s, _ in by_system}), len(by_system), "one grain per source system")
        for r in self.recs:
            if r["GRAIN"] == "TRADE":
                self.assertEqual(r["TRADE_IDS"], [r["TRADE_ID"]])
            else:
                self.assertEqual(r["TRADE_IDS"], sorted(str(t["TRADE_ID"]) for t in want[r["POSITION_ID"]]))
        trade_ids = sorted(t for r in self.recs for t in r["TRADE_IDS"])
        self.assertEqual(trade_ids, sorted(str(t["TRADE_ID"]) for ts in want.values() for t in ts), "every live trade once")
        ids = [r["SENSITIVITY_ID"] for r in self.recs]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertTrue(all(i.startswith("SENS-") for i in ids))
        self.assertGreater(len(self.recs), 5000)

    def test_every_element_is_valid_and_in_the_dictionary(self):
        problems = []
        for r in self.recs:
            seen = Counter((e["RISK_FACTOR"], e["MEASURE"]) for e in r["SENSITIVITIES"])
            problems += [f"{r['POSITION_ID']}: {k} twice" for k, n in seen.items() if n > 1]
            for e in r["SENSITIVITIES"]:
                fac = self.dic.get(e["RISK_FACTOR"])
                if fac is None or e["MEASURE"] not in MEASURES or not math.isfinite(e["VALUE"]) or e["VALUE"] == 0:
                    problems.append(f"{r['POSITION_ID']}: {e}")
                    continue
                if e["RISK_CLASS"] != fac["RISK_CLASS"] or e["FRTB_BUCKET"] != fac["FRTB_BUCKET"]:
                    problems.append(f"{r['POSITION_ID']}: {e['RISK_FACTOR']} bucket differs from the dictionary")
                if e["MEASURE"].startswith("CURVATURE") and ("SHOCK_SIZE" not in e or fac["TENOR"]):
                    problems.append(f"{r['POSITION_ID']}: curvature on a curve or spot, with its shock")
                if e["MEASURE"] == "VEGA" and (fac["FACTOR_TYPE"] != "VOL" or "IMPLIED_VOL" not in e):
                    problems.append(f"{r['POSITION_ID']}: vega on a volatility, with the implied volatility")
        self.assertEqual(problems[:10], [])

    def test_curvature_only_on_options_and_every_product_group_has_risk(self):
        for r in self.recs:
            curv = any(e["MEASURE"].startswith("CURVATURE") for e in r["SENSITIVITIES"])
            if curv:
                self.assertTrue(S.F.is_option(r["PRODUCT_TYPE"]), r["POSITION_ID"])
        groups = Counter(r["PRODUCT_GROUP"] for r in self.recs if r["SENSITIVITIES"])
        self.assertGreaterEqual(len(groups), 9)

    def test_records_follow_the_contract(self):
        c = contract("position-sensitivity")
        top = {f["name"] for f in c["fields"]}
        nested = {f["name"] for f in c["nested"]["SENSITIVITIES[]"]}
        required = [f["name"] for f in c["fields"] if f["presence"] == "required"]
        for r in self.recs[:500]:
            self.assertLessEqual(set(r), top)
            self.assertEqual([k for k in required if r.get(k) in (None, "")], [], r["POSITION_ID"])
            for e in r["SENSITIVITIES"]:
                self.assertLessEqual(set(e), nested)


if __name__ == "__main__":
    unittest.main()
