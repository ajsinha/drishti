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

"""QA2-201: no market document of any committed day has a quoted field contradicting another (an FX MID outside its BID/ASK, a bond's
price and yield up on the same day, an equity PRICE that is not its own bar's close, a curve FRONT that is not its first point, a series
point after the day ...), and the fields next to a moving price move too. Stdlib and PyYAML only.

    python3 -m unittest -q tools/testdata/test_market_quotes.py
"""
from __future__ import annotations

import gzip
import json
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
TD = ROOT / "data" / "testdata"
sys.path.insert(0, str(HERE))
import marketdays as MK  # noqa: E402



def days_of(kind: str, key: str, dates: list[str]):
    """Each day's documents of market-data/<kind> as {id: doc}: the snapshot, then each day's changes."""
    state: dict[str, dict] = {}
    for i, day in enumerate(dates):
        for f in sorted((TD / "market-data" / kind).glob(f"{'snapshot' if i == 0 else 'changes'}-{day}*.jsonl.gz")):
            with gzip.open(f, "rt", encoding="utf-8") as fh:
                for line in fh:
                    if line.strip():
                        d = json.loads(line)
                        state[str(d[key])] = d
        yield day, dict(state)


class Guard(unittest.TestCase):
    """The checker itself flags what QA2-201 found."""

    FX = {"PAIR": "FX-AUDUSD", "MID": 0.661268, "BID": 0.66097, "ASK": 0.66103, "HISTORY": [{"DATE": "2026-10-14", "MID": 0.661268}]}
    BOND = {"ISIN": "BND-X", "PRICE": 98.16, "YIELD": 0.057, "Z_SPREAD": 280.2, "MOD_DURATION": 4.35, "HISTORY": [{"DATE": "2026-10-14", "PRICE": 98.16}]}

    def test_mid_outside_bid_ask_is_flagged(self):
        self.assertIn("MID 0.661268 outside BID 0.66097 / ASK 0.66103", MK.contradictions("fx-spot", self.FX))

    def test_price_up_and_yield_up_or_unchanged_is_flagged(self):
        up = {**self.BOND, "PRICE": 99.52, "YIELD": 0.058, "HISTORY": [{"DATE": "2026-10-15", "PRICE": 99.52}]}
        self.assertTrue(any("both up" in x for x in MK.contradictions("bond", up, self.BOND)))
        still = {**self.BOND, "PRICE": 99.52, "HISTORY": [{"DATE": "2026-10-15", "PRICE": 99.52}]}
        self.assertTrue(any("did not" in x for x in MK.contradictions("bond", still, self.BOND)))

    def test_a_series_point_after_the_day_is_flagged(self):
        self.assertTrue(any("after the day" in x for x in MK.contradictions("fx-spot", {**self.FX, "BID": 0.6612, "ASK": 0.6613}, day="2026-10-13")))

    def test_marketdays_moves_bid_ask_and_yield_with_the_price(self):
        hist = [{"DATE": d, "MID": m} for d, m in (("2026-09-28", 1.16), ("2026-09-29", 1.165), ("2026-09-30", 1.174))]
        bh = [{"DATE": d, "PRICE": p} for d, p in (("2026-09-28", 99.0), ("2026-09-29", 99.6), ("2026-09-30", 100.0))]
        docs = {"fx-spot": {"FX-EURUSD": {"PAIR": "FX-EURUSD", "MID": 1.174, "BID": 1.17395, "ASK": 1.17405, "HISTORY": hist}},
                "bond": {"BND-X": {"ISIN": "BND-X", "PRICE": 100.0, "YIELD": 0.0382, "Z_SPREAD": 11.4, "MOD_DURATION": 4.35, "HISTORY": bh}}}
        m = MK.MarketDays(docs, ["2026-09-28", "2026-09-29", "2026-09-30"])
        fx = [m.day("fx-spot", "FX-EURUSD", i) for i in range(3)]
        self.assertEqual([d["MID"] for d in fx], [1.16, 1.165, 1.174])
        for d in fx:
            self.assertEqual(MK.contradictions("fx-spot", d), [], d)
            self.assertAlmostEqual(d["ASK"] - d["BID"], 0.0001, places=6)
        bonds = [m.day("bond", "BND-X", i) for i in range(3)]
        self.assertEqual(bonds[-1], docs["bond"]["BND-X"], "the last day is the document as built")
        self.assertGreater(bonds[0]["YIELD"], bonds[1]["YIELD"])
        self.assertGreater(bonds[1]["YIELD"], bonds[2]["YIELD"])
        self.assertLess(bonds[0]["MOD_DURATION"], 4.35, "a higher yield shortens the duration")
        for a, b in zip(bonds, bonds[1:]):
            self.assertEqual(MK.contradictions("bond", b, a), [])


@unittest.skipUnless((TD / "testdata.yaml").is_file(), "no data/testdata")
class CommittedData(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import yaml
        m = yaml.safe_load((TD / "testdata.yaml").read_text(encoding="utf-8"))
        cls.dates = m["business-dates"]
        cls.days = {ds["kind"]: list(days_of(ds["kind"], ds["key"], cls.dates)) for ds in m["datasets"]
                    if str(ds.get("folder", "")).startswith("market-data/")}

    def test_no_market_document_contradicts_itself_or_the_day_before(self):
        found = []
        for kind, days in self.days.items():
            before: dict[str, dict] = {}
            for day, docs in days:
                for id_, doc in docs.items():
                    for x in MK.contradictions(kind, doc, before.get(id_), day):
                        found.append(f"{kind} {id_} {day}: {x}")
                before = docs
        self.assertEqual(found[:12], [], f"{len(found)} contradictions")

    def test_curves_quote_the_same_spot_as_their_underlier_on_every_day(self):
        found = []
        for (kind, ref, head), (under, uhead) in ((("commodity-curve", "COMMODITY_REF", "FRONT"), ("commodity", "FRONT")),
                                                  (("fx-forward-curve", "FX_SPOT", "SPOT"), ("fx-spot", "MID"))):
            for (day, curves), (_, underliers) in zip(self.days[kind], self.days[under]):
                for id_, c in curves.items():
                    u = underliers.get(c[ref])
                    if u and abs(c[head] - u[uhead]) > 1e-6:
                        found.append(f"{kind} {id_} {day}: {head} {c[head]} but {c[ref]} {uhead} {u[uhead]}")
        self.assertEqual(found[:12], [], f"{len(found)} mismatches")

    def test_the_fields_next_to_a_moving_price_move_too(self):
        for kind, some, fields in (("fx-spot", "FX-AUDUSD", ("MID", "BID", "ASK")),
                                   ("bond", "BND-CACASC059511", ("PRICE", "YIELD", "Z_SPREAD", "MOD_DURATION")),
                                   ("equity", "EQ-CSCA", ("PRICE", "MARKET_CAP")),
                                   ("ir-curve", "CRV-USD-OIS", ("TEN_Y", "SLOPE_2S_10S")),
                                   ("repo-curve", "REPO-USD", ("OVERNIGHT",))):
            series = [docs[some] for _, docs in self.days[kind] if some in docs]
            for f in fields:
                self.assertGreater(len({d[f] for d in series}), 10, f"{kind} {some} {f} over {len(series)} days")


if __name__ == "__main__":
    unittest.main()
