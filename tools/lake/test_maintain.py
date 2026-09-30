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


"""Lake maintenance keeps a lake bounded: old business dates go, small files merge, the data kept is intact.

    uv run --with deltalake --with pyarrow --with pyyaml python -m unittest tools/lake/test_maintain.py
"""
import json
import sys
import tempfile
import unittest
from datetime import date, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import maintain  # noqa: E402


def business_days(end: date, n: int) -> list[date]:
    out, d = [], end
    while len(out) < n:
        if d.weekday() < 5:
            out.append(d)
        d -= timedelta(days=1)
    return sorted(out)


class MaintainTest(unittest.TestCase):

    def lake(self, root: Path) -> str:
        import pyarrow as pa
        from deltalake import write_deltalake
        uri = str(root / "trading" / "trade")
        for d in business_days(date(2026, 9, 30), 30):
            for part in range(3):                                  # three small writes a day, as intraday loads leave
                ids = [f"T-{part}-{i}" for i in range(5)]
                write_deltalake(uri, pa.table({"id": ids, "doc": [json.dumps({"tradeId": i, "mtm": part}) for i in ids],
                                               "business_date": pa.array([d] * 5, pa.date32())}), mode="append", partition_by=["business_date"])
        return uri

    def test_retention_compaction_checkpoint_and_vacuum(self):
        from deltalake import DeltaTable
        with tempfile.TemporaryDirectory() as tmp:
            uri = self.lake(Path(tmp))
            files_before = len(maintain.add_actions(DeltaTable(uri)))
            self.assertEqual(files_before, 90)
            config = {"lakes": [{"root": tmp, "keep-business-days": 10, "compact": True, "checkpoint": True, "vacuum-hours": 0}]}
            self.assertEqual(maintain.run(config, dry_run=True, today=date(2026, 9, 30)), 0)
            self.assertEqual(len(maintain.add_actions(DeltaTable(uri))), 90)        # a dry run changes nothing
            self.assertEqual(maintain.run(config, dry_run=False, today=date(2026, 9, 30)), 0)
            dt = DeltaTable(uri)
            import pyarrow as pa
            rows = pa.table(dt.to_pyarrow_table()).to_pylist()
            dates = sorted({r["business_date"] for r in rows})
            cutoff = maintain.business_cutoff(date(2026, 9, 30), 10)
            self.assertTrue(all(d >= cutoff for d in dates))                                          # old dates deleted
            self.assertEqual(len(dates), 11)                                                          # the cutoff day and after
            self.assertEqual(len(rows), 11 * 15)                                                      # every kept row intact
            self.assertEqual(len(maintain.add_actions(dt)), 11)                   # one file per day
            parquet_on_disk = list(Path(uri).rglob("*.parquet"))
            self.assertLessEqual(len([p for p in parquet_on_disk if "_delta_log" not in str(p)]), 11)  # vacuumed
            self.assertTrue(any(Path(uri, "_delta_log").glob("*.checkpoint.parquet")))

    def test_a_failing_table_does_not_stop_the_others(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.lake(Path(tmp))
            broken = Path(tmp) / "trading" / "broken" / "_delta_log"
            broken.mkdir(parents=True)
            (broken / "00000000000000000000.json").write_text("not delta")
            config = {"lakes": [{"root": tmp, "compact": True, "checkpoint": False, "vacuum-hours": None}]}
            self.assertEqual(maintain.run(config, dry_run=False, today=date(2026, 9, 30)), 1)

    def test_the_schedule(self):
        from datetime import datetime
        from zoneinfo import ZoneInfo
        ny = ZoneInfo("America/New_York")
        self.assertEqual(maintain.next_run(datetime(2026, 9, 30, 1, 0, tzinfo=ny), "02:30"), datetime(2026, 9, 30, 2, 30, tzinfo=ny))
        self.assertEqual(maintain.next_run(datetime(2026, 9, 30, 3, 0, tzinfo=ny), "02:30"), datetime(2026, 10, 1, 2, 30, tzinfo=ny))
        self.assertEqual(maintain.business_cutoff(date(2026, 10, 5), 1), date(2026, 10, 2))            # Monday back to Friday


if __name__ == "__main__":
    unittest.main()
