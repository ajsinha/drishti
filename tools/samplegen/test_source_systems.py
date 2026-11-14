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

"""The mocked source systems (source_systems.py): native styles that differ, a shared table, deterministic bytes, nothing the VaR reads."""
from __future__ import annotations

import gzip
import json
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))
from samplegen import source_systems as SS  # noqa: E402

DATES = ["2026-07-08", "2026-07-09", "2026-07-10", "2026-07-13"]


class SourceSystemsTest(unittest.TestCase):
    def build(self, root: Path):
        return SS.datasets(root, DATES, 40)

    def test_each_system_has_its_own_native_style_and_one_shared_table(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[2] / "data") as t:
            entries = {e["folder"]: e for e in self.build(Path(t))}
            self.assertEqual(sorted(entries), ["trades/ici", "trades/swapone", "trades/wss"])
            keys = {}
            for folder, e in entries.items():
                self.assertEqual((e["table"], e["partition-by"], e["mode"]), ("trades_shared", ["SOURCE_SYSTEM"], "overwrite-partitions"))
                first = Path(t) / folder / f"trades_shared.{DATES[0]}.jsonl.gz"
                doc = json.loads(gzip.open(first, "rt").readline())
                keys[folder] = (e["key"], sorted(doc)[:3])
                self.assertIn(e["key"], doc)
                self.assertEqual(doc["SOURCE_SYSTEM"], folder.split("/")[1].upper())
                self.assertNotIn("TRADE_ID", doc)                           # native: the mapping has real work to do
            self.assertEqual(len({v[0] for v in keys.values()}), 3)
            self.assertEqual(len({tuple(v[1]) for v in keys.values()}), 3)

    def test_deterministic_and_the_book_changes_day_by_day(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[2] / "data") as a, \
                tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[2] / "data") as b:
            ea, eb = self.build(Path(a)), self.build(Path(b))
            self.assertEqual(ea, eb)
            for f in Path(a).rglob("*.jsonl.gz"):
                self.assertEqual(f.read_bytes(), (Path(b) / f.relative_to(a)).read_bytes())
            self.assertEqual(ea[0]["days"][0]["kind"], "snapshot")
            self.assertTrue(all(d["kind"] == "changes" for d in ea[0]["days"][1:]))

    def test_no_positions_no_scenario_pvs_no_real_names(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[2] / "data") as t:
            self.build(Path(t))
            self.assertEqual(sorted(p.name for p in Path(t).iterdir()), ["trades"])       # only trades: nothing for the VaR
            text = "".join(gzip.open(f, "rt").read() for f in Path(t).rglob("*.jsonl.gz")).upper()
            for real in ("BMO", "JPMORGAN", "GOLDMAN", "CITIGROUP", "BARCLAYS"):
                self.assertNotIn(real, text)


if __name__ == "__main__":
    unittest.main()
