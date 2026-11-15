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

"""The market publisher's messages (tools/samplegen/market_stream.py): a price kind's tick carries only its key, its moved prices and the
contract's order time (partial: merge); a curve goes whole with its points moved together and its build time (partial: whole); every
kind it ticks is a live kind of market-store-live with the order field its contract names."""
from __future__ import annotations

import importlib.util
import pathlib
import random
import unittest

import yaml

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SPEC = importlib.util.spec_from_file_location("market_stream", HERE / "market_stream.py")
M = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(M)


class MarketStream(unittest.TestCase):

    def test_a_price_tick_is_a_patch_with_its_quote_time(self):
        row = {"PAIR": "FX-EURUSD", "MID": 1.08, "BID": 1.0799, "ASK": 1.0801, "PAIR_NAME": "EUR/USD", "HISTORY": [{"MID": 1.0}]}
        t = M.tick("fx-spot", row, random.Random(1))
        self.assertEqual(set(t), {"PAIR", "QUOTE_TIME", "MID", "BID", "ASK"})
        self.assertLess(t["BID"], t["ASK"])
        self.assertEqual(row["MID"], t["MID"], "the publisher keeps its own row current")

    def test_a_curve_goes_whole_with_its_points_moved_together(self):
        row = {"CURVE_ID": "CRV-USD-SOFR", "POINTS": [{"TENOR": "1Y", "QUOTE": 0.04}, {"TENOR": "2Y", "QUOTE": 0.05}]}
        t = M.tick("ir-curve", row, random.Random(3))
        self.assertIn("BUILT_AT", t)
        self.assertEqual(len(t["POINTS"]), 2)
        r1, r2 = t["POINTS"][0]["QUOTE"] / 0.04, t["POINTS"][1]["QUOTE"] / 0.05
        self.assertAlmostEqual(r1, r2, places=4)

    def test_every_kind_it_ticks_is_fed_by_the_companion_in_the_order_its_contract_names(self):
        pack = yaml.safe_load((ROOT / "modules/market-data/packs/market-data/pack.yaml").read_text(encoding="utf-8"))
        live = pack["connectors"]["market-store-live"]
        self.assertEqual(live["companion-of"], "market-store")
        for kind, (key, at, prices) in M.KINDS.items():
            self.assertIn(kind, live["kinds"])
            self.assertEqual(live["settings"]["kind.drishti.market." + kind], kind)
            c = yaml.safe_load((ROOT / f"modules/market-data/packs/market-data/contracts/{kind}.contract.yaml").read_text(encoding="utf-8"))
            self.assertEqual(c["key"], [key])
            self.assertEqual(c["live"]["order"], [at])
            self.assertEqual(c["live"]["partial"], "merge" if prices is not None else "whole")


if __name__ == "__main__":
    unittest.main()
