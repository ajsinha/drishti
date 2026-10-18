#!/usr/bin/env python3
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

"""tools/bench/trades.py, small: two systems, two days, a few hundred trades into a temporary lake, every table it makes there."""
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import trades  # noqa: E402


class TradesTest(unittest.TestCase):
    def test_a_small_book_is_generated_and_loaded_day_by_day(self):
        with tempfile.TemporaryDirectory() as d:
            lake, tmp = Path(d) / "delta", Path(d) / "tmp"
            code = trades.main(["--lake", str(lake), "--tmp", str(tmp), "--days", "2", "--min", "200", "--max", "300",
                                "--systems", "endur,calypso", "--counterparties", "50", "--parallel"])
            self.assertEqual(code, 0)
            from deltalake import DeltaTable
            endur = DeltaTable(str(lake / "trading" / "trade_endur")).to_pyarrow_table()
            self.assertGreaterEqual(endur.num_rows, 400)                               # two days of 200-300
            self.assertEqual(sorted(str(x) for x in set(endur.column("business_date").to_pylist())), ["2026-10-15", "2026-10-16"])
            self.assertIn("NOTIONAL", endur.column_names)                              # the pack's flattened column
            rows = endur.select(["TRADE_ID", "business_date", "TRADE_VERSION", "NOTIONAL"]).to_pylist()
            by_id = {}
            for r in rows:
                by_id.setdefault(r["TRADE_ID"], {})[str(r["business_date"])] = (r["TRADE_VERSION"], r["NOTIONAL"])
            both = [v for v in by_id.values() if len(v) == 2]
            self.assertGreater(len(both), 150)                                         # the same trades on both days
            self.assertTrue(any(v["2026-10-15"] != v["2026-10-16"] for v in both))     # and some of them changed
            self.assertTrue((lake / "trading" / "trade_calypso" / "_delta_log").is_dir())
            ns = DeltaTable(str(lake / "credit" / "netting-set")).to_pyarrow_table()
            self.assertEqual(ns.num_rows, 2 * 150)                                     # 50 counterparties x 3 legal entities x 2 days
            self.assertEqual(list(tmp.glob("*.jsonl")) + list(tmp.glob("*.log")), [])                            # each day's file removed after its load


if __name__ == "__main__":
    unittest.main()
