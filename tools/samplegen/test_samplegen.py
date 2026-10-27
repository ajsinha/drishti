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

"""Checks the sample generator against known values and the finance samples against themselves.
Run: python3 -m unittest tools/samplegen/test_samplegen.py"""
import pathlib
import sys
import unittest
from datetime import date

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from samplegen import ids, legs  # noqa: E402
from samplegen.curves import Curve  # noqa: E402
from samplegen.dates import Calendar, holiday_lines, schedule, year_fraction  # noqa: E402
sys.path.insert(0, str(ROOT / "tools" / "packgen" / "common"))
import packsamples  # noqa: E402  (samples/<kind>.jsonl)


class Identifiers(unittest.TestCase):
    def test_check_digits(self):
        self.assertTrue(ids.lei_valid(ids.lei("anything")))
        self.assertEqual(ids.isin("US", "91282CJL6"), "US91282CJL63")      # a US Treasury note
        self.assertEqual(ids.cusip("03783310"), "037833100")                 # Apple Inc.
        self.assertEqual(len(ids.upi("Rates", "IRS")), 12)


class Conventions(unittest.TestCase):
    def test_calendar_and_schedule(self):
        ny = Calendar.of("USNY")
        self.assertFalse(ny.is_business_day(date(2026, 11, 26)))            # Thanksgiving
        self.assertEqual(ny.adjust(date(2026, 10, 3)), date(2026, 10, 5))
        periods = schedule(date(2026, 10, 2), date(2031, 10, 2), "Annual", ny)
        self.assertEqual(periods[0], (date(2026, 10, 2), date(2027, 10, 4)))
        self.assertAlmostEqual(year_fraction(date(2026, 1, 31), date(2026, 2, 28), "30/360"), 28 / 360)

    def test_holidays_are_named_and_match_the_java_calendar(self):
        # the same table as BusinessCalendarTest.GOLDEN (drishti-common): a trailing * marks an observed substitute
        golden = {("USNY", 2026): "01-01,01-19,02-16,05-25,06-19,07-03*,09-07,10-12,11-11,11-26,12-25",
                  ("USNY", 2027): "01-01,01-18,02-15,05-31,06-18*,07-05*,09-06,10-11,11-11,11-25,12-24*",
                  ("GBLO", 2026): "01-01,04-03,04-06,05-04,05-25,08-31,12-25,12-28*",
                  ("GBLO", 2027): "01-01,03-26,03-29,05-03,05-31,08-30,12-24*,12-28*",
                  ("EUTA", 2026): "01-01,04-03,04-06,05-01,12-25", ("EUTA", 2027): "01-01,03-26,03-29",
                  ("JPTO", 2026): "01-01,01-02,02-11,04-29,05-04,05-05,11-03,11-23,12-31",
                  ("JPTO", 2027): "01-01,02-11,04-29,05-03,05-04,05-05,11-03,11-23,12-31",
                  ("CATO", 2026): "01-01,02-16,04-03,05-18,07-01,08-03,09-07,09-30,10-12,11-11,12-25,12-28*",
                  ("CATO", 2027): "01-01,02-15,03-26,05-24,07-01,08-02,09-06,09-30,10-11,11-11,12-27*,12-28*"}
        for (centre, year), want in golden.items():
            lines = holiday_lines(centre, year)
            self.assertEqual(",".join(h["date"][5:] + ("*" if h["observed"] else "") for h in lines), want, (centre, year))
            self.assertTrue(all(h["name"] and h["name"] != "Public holiday" and h["year"] == year for h in lines))
        ny = {h["date"]: h for h in holiday_lines("USNY", 2026)}
        self.assertEqual(ny["2026-07-03"]["name"], "Independence Day (observed)")
        self.assertEqual(ny["2026-11-26"]["weekday"], "Thursday")

    def test_fixed_leg_matches_the_mockup(self):
        c = Curve("USD", "USD", date(2026, 9, 30), ["1Y", "10Y"], [0.037, 0.038])
        leg = legs.fixed_leg(1, True, 5e7, "USD", 0.0385, date(2026, 10, 2), date(2031, 10, 2), "Annual", "ACT/360",
                             Calendar.of("USNY"), c, date(2026, 9, 30))
        self.assertEqual([f["amount"] for f in leg["cashflows"]], [-1962430.56, -1946388.89, -1951736.11, -1951736.11, -1951736.11])


class FinanceSamples(unittest.TestCase):
    def test_swaps_reprice_to_their_mtm(self):
        for id_, d in packsamples.read(ROOT / "examples/packs/finance/samples")["trade"].items():
            if id_.startswith("IRS-4") and "legs" in d:
                pv = sum(leg["pv"] for leg in d["legs"])
                self.assertLess(abs(pv - d["mtm"]), max(100, abs(d["mtm"]) * 0.01), d["tradeId"])

    def test_every_trade_is_fully_booked(self):
        trades = packsamples.read(ROOT / "examples/packs/finance/samples")["trade"]
        self.assertEqual(len(trades), 16)
        for d in trades.values():
            for block in ("execution", "lifecycle", "regulatory", "settlementInstructions", "valuation", "legalEntity"):
                self.assertIn(block, d, f"{d['tradeId']} lacks {block}")
            self.assertTrue(ids.lei_valid(d["counterparty"]["lei"]))


if __name__ == "__main__":
    unittest.main()
