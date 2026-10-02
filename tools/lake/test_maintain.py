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
            config = {"lakes": [{"root": tmp, "keep-business-days": 10, "max-drop-share": 0.7, "compact": True, "checkpoint": True, "vacuum-hours": 0}]}
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

    def test_retention_that_would_delete_most_of_a_table_needs_force_drop(self):
        from deltalake import DeltaTable
        with tempfile.TemporaryDirectory() as tmp:
            uri = self.lake(Path(tmp))
            config = {"lakes": [{"root": tmp, "keep-business-days": 10, "compact": False, "checkpoint": False, "vacuum-hours": None}]}
            self.assertEqual(maintain.run(config, dry_run=False, today=date(2026, 9, 30)), 1)      # 19 of 30 days: refused
            self.assertEqual(len(maintain.add_actions(DeltaTable(uri))), 90)                       # nothing deleted
            self.assertEqual(maintain.run(config, dry_run=False, today=date(2026, 9, 30), force_drop=True), 0)
            self.assertEqual(len({a["partition.business_date"] for a in maintain.add_actions(DeltaTable(uri))}), 11)

    def test_retention_counts_back_from_today_never_from_a_future_date_in_the_table(self):
        import pyarrow as pa
        from deltalake import DeltaTable, write_deltalake
        with tempfile.TemporaryDirectory() as tmp:
            uri = self.lake(Path(tmp))
            write_deltalake(uri, pa.table({"id": ["T-TYPO"], "doc": ["{}"], "business_date": pa.array([date(2099, 3, 1)], pa.date32())}),
                            mode="append", partition_by=["business_date"])
            config = {"lakes": [{"root": tmp, "keep-business-days": 25, "compact": False, "checkpoint": False, "vacuum-hours": None}]}
            self.assertEqual(maintain.run(config, dry_run=False, today=date(2026, 9, 30)), 0)
            dates = {str(a["partition.business_date"]) for a in maintain.add_actions(DeltaTable(uri))}
            self.assertEqual(len(dates), 27)                     # 25 business days back from today, the cutoff day, and the typo
            self.assertIn("2099-03-01", dates)

    def test_a_failing_table_does_not_stop_the_others(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.lake(Path(tmp))
            broken = Path(tmp) / "trading" / "broken" / "_delta_log"
            broken.mkdir(parents=True)
            (broken / "00000000000000000000.json").write_text("not delta")
            config = {"lakes": [{"root": tmp, "compact": True, "checkpoint": False, "vacuum-hours": None}]}
            self.assertEqual(maintain.run(config, dry_run=False, today=date(2026, 9, 30)), 1)

    def test_relayout_promotes_sorts_and_is_idempotent(self):
        import pyarrow as pa
        import pyarrow.parquet as pq
        from deltalake import DeltaTable
        from samplegen.layout import Layout
        lay = Layout(["mtm", "counterparty.id"], file_rows=4, row_group_rows=2)
        with tempfile.TemporaryDirectory() as tmp:
            uri = self.lake(Path(tmp))                                  # three unsorted files a day, id and doc only
            before = DeltaTable(uri).to_pyarrow_table()
            self.assertEqual(before.column_names, ["id", "doc", "business_date"])
            dates = business_days(date(2026, 9, 30), 30)
            done = maintain.relayout(uri, lay, dates=f"{dates[-2]}..{dates[-1]}")
            self.assertEqual((done["rewritten"], done["rows"], done["files"]), ([str(dates[-2]), str(dates[-1])], 30, 8))
            dt = DeltaTable(uri)
            acts = maintain.add_actions(dt)
            for d in dates[-2:]:
                files = sorted((a for a in acts if a["partition.business_date"] == d), key=lambda a: a["min.id"])
                self.assertEqual([a["num_records"] for a in files], [4, 4, 4, 3])
                self.assertTrue(all(files[k]["max.id"] < files[k + 1]["min.id"] for k in range(3)))
                for a in files:
                    ids = pq.read_table(str(Path(uri) / a["path"]), columns=["id"]).column("id").to_pylist()
                    self.assertEqual(ids, sorted(ids))
                    meta = pq.ParquetFile(str(Path(uri) / a["path"])).metadata
                    self.assertTrue(all(meta.row_group(i).num_rows <= 2 for i in range(meta.num_row_groups)))
            rows = pa.table(dt.to_pyarrow_table()).to_pylist()
            self.assertEqual(len(rows), 30 * 15)                                                      # nothing lost
            for r in (r for r in rows if r["business_date"] in dates[-2:]):
                self.assertEqual(r["mtm"], float(json.loads(r["doc"])["mtm"]))
                self.assertIsNone(r["counterparty__id"])                                              # not in these documents
            self.assertEqual(maintain.relayout(uri, lay, dates=f"{dates[-2]}..{dates[-1]}")["skipped"], [str(dates[-2]), str(dates[-1])])
            self.assertEqual(DeltaTable(uri).version(), dt.version())                                 # a second run changes nothing
            self.assertEqual(maintain.relayout(uri, lay, dates=str(dates[-1]), force=True)["files"], 4)
            self.assertEqual(len(DeltaTable(uri).to_pyarrow_table()), 30 * 15)

    def test_relayout_command_reads_the_pack_layout(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.lake(Path(tmp) / "lake")
            pack = Path(tmp) / "packs" / "demo"
            pack.mkdir(parents=True)
            (pack / "pack.yaml").write_text("connectors:\n  store:\n    plugin: delta\n    kinds: [trade]\n    settings:\n      domain: trading\n"
                                            "      layout:\n        trade:\n          columns: [mtm]\n          file-rows: 100\n")
            args = ["relayout", "--root", str(Path(tmp) / "lake"), "--domain", "trading", "--packs", str(Path(tmp) / "packs"), "--dates", "2026-09-30"]
            self.assertEqual(maintain.main(args), 0)
            self.assertEqual(maintain.main(args[:-2] + ["--kind", "book"]), 1)                        # no layout for that kind

    def test_nightly_maintenance_keeps_a_laid_out_table_sorted(self):
        from deltalake import DeltaTable
        from samplegen.layout import write_partition
        with tempfile.TemporaryDirectory() as tmp:
            uri = str(Path(tmp) / "trading" / "trade")                  # the trading pack declares a layout for trade
            lay = maintain.layout_of(uri)
            self.assertIsNotNone(lay)
            for n, day in enumerate((date(2026, 9, 29), date(2026, 9, 30))):
                rows = [(f"T-{i:05d}", json.dumps({"tradeId": f"T-{i:05d}", "mtm": i}), {"tradeId": f"T-{i:05d}", "mtm": i})
                        for i in range(0, 40, 2)]
                write_partition(uri, day, rows, lay, "overwrite" if n == 0 else "append")
            # an intraday load: a small file with ids inside the day's range, out of order
            late = [(f"T-{i:05d}", json.dumps({"tradeId": f"T-{i:05d}", "mtm": -i}), {"tradeId": f"T-{i:05d}", "mtm": -i}) for i in (31, 7)]
            write_partition(uri, date(2026, 9, 30), late, lay, "append")
            conf = {**maintain.DEFAULTS, "checkpoint": False, "vacuum-hours": None}
            done = maintain.maintain(uri, conf, date(2026, 9, 30), dry_run=False)
            self.assertEqual(done["compact"]["relaid-dates"], ["2026-09-30"])                   # the untouched day stays
            dt = DeltaTable(uri)
            self.assertEqual(dt.metadata().configuration["delta.dataSkippingStatsColumns"], maintain.stats_columns_of(lay))
            actions = [a for a in maintain.add_actions(dt) if str(a["partition.business_date"]) == "2026-09-30"]
            self.assertEqual(len(actions), 1)                                                  # merged, and in id order
            self.assertEqual((actions[0]["min.id"], actions[0]["max.id"], actions[0]["num_records"]), ("T-00000", "T-00038", 22))
            self.assertNotIn("min.doc", actions[0])                                            # no document in the log
            again = maintain.maintain(uri, conf, date(2026, 9, 30), dry_run=False)
            self.assertEqual(again["compact"]["relaid-dates"], [])

    def test_the_schedule(self):
        from datetime import datetime
        from zoneinfo import ZoneInfo
        ny = ZoneInfo("America/New_York")
        self.assertEqual(maintain.next_run(datetime(2026, 9, 30, 1, 0, tzinfo=ny), "02:30"), datetime(2026, 9, 30, 2, 30, tzinfo=ny))
        self.assertEqual(maintain.next_run(datetime(2026, 9, 30, 3, 0, tzinfo=ny), "02:30"), datetime(2026, 10, 1, 2, 30, tzinfo=ny))
        self.assertEqual(maintain.business_cutoff(date(2026, 10, 5), 1), date(2026, 10, 2))            # Monday back to Friday


if __name__ == "__main__":
    unittest.main()
