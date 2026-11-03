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

"""The SA-CCR demo input (tools/packgen/ccr/make_saccr_data.py): up to date, one row per live derivative and driver, no SFT or cash
product classified, no computed figure on an input row, and the reference implementation equal to the design's hand-built cases
(docs/architecture/COUNTERPARTY_ENGINES.md section 9)."""
from __future__ import annotations

import gzip
import json
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(HERE))
import make_saccr_data as X  # noqa: E402
import saccrmath as S  # noqa: E402

COMPUTED = {"SUPERVISORY_DURATION", "SUPERVISORY_DELTA", "MATURITY_FACTOR", "ADJUSTED_NOTIONAL", "EFFECTIVE_NOTIONAL", "MATURITY_BUCKET",
            "SUPERVISORY_FACTOR", "ADD_ON", "REPLACEMENT_COST", "MULTIPLIER", "PFE", "EAD", "RWA"}


def rows() -> list[dict]:
    out = []
    for f in sorted((ROOT / "data" / "testdata" / X.FOLDER).glob("*.jsonl.gz")):
        with gzip.open(f, "rt", encoding="utf-8") as fh:
            out += [json.loads(ln) for ln in fh if ln.strip()]
    return out


def ir(d, n, s, e, h="USD"):
    return dict(ASSET_CLASS="IR", HEDGING_SET=h, TRANSACTION_TYPE="LINEAR", DIRECTION=d, D_NOTIONAL=n, S=s, E=e, M=e)


class TheSaccrInputs(unittest.TestCase):

    def test_up_to_date(self):
        self.assertEqual(X.main(["--check"]), 0)

    def test_rows_are_unique_derived_and_carry_no_figure(self):
        rs = rows()
        self.assertGreater(len(rs), 1000)
        self.assertEqual(len({r["SACCR_ROW_ID"] for r in rs}), len(rs))
        for r in rs:
            self.assertEqual(r["DERIVED_BY"], "rules")
            self.assertFalse(COMPUTED & set(r), r["SACCR_ROW_ID"])
            self.assertIn(r["ASSET_CLASS"], ("IR", "FX", "CREDIT", "EQUITY", "COMMODITY"))
        self.assertTrue(any(r["DRIVER"] == 1 for r in rs), "a complex trade with a second driver")

    def test_no_sft_or_cash_product_is_classified(self):
        products = {r["PRODUCT_TYPE"] for r in rows()}
        for p in ("REPO", "REVERSE_REPO", "SEC_LENDING", "GOVT_BOND", "DEPOSIT", "TERM_LOAN", "CLN"):
            self.assertNotIn(p, products)

    def test_the_reference_math_equals_the_design_cases(self):
        self.assertEqual(round(S.ead({"V": 1.2e6}, [ir("LONG", 1e8, 0, 5)])["EAD"]), 4_776_789)
        s2 = S.ead({"V": -2e6}, [ir("LONG", 2e8, 0, .5), ir("SHORT", 1e8, 0, 3), ir("LONG", 5e7, 0, 10)])
        self.assertEqual(round(s2["EAD"]), 919_867)
        fx = [dict(ASSET_CLASS="FX", HEDGING_SET="EURUSD", TRANSACTION_TYPE="LINEAR", DIRECTION="LONG", D_NOTIONAL=4e7, M=.25)]
        s5 = S.ead(dict(V=3e6, C=1e6, NICA=1e6, TH=5e6, MTA=5e5, MARGINED=True, MPOR_DAYS=10, MARGIN_FREQUENCY_DAYS=1), [ir("LONG", 1e8, 0, .75)] + fx)
        self.assertEqual(round(s5["EAD"]), 4_366_244)
        self.assertTrue(s5["CAPPED"])

    def test_the_reference_marks_the_incomplete_set(self):
        ref = json.loads(X.REFERENCE.read_text(encoding="utf-8"))
        self.assertTrue(all(ref["sets"][ns]["unclassified"] for ns in X.UNCLASSIFIED))
        self.assertTrue(any(v.get("STATUS") == "NO_DERIVATIVES" for v in ref["sets"].values()))


if __name__ == "__main__":
    unittest.main()
