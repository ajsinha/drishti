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
import ownership  # noqa: E402


def own(uri: str, pack: str = "trading", kind: str = "trade") -> None:
    """Marks a table as built by lake build (its build record), so maintenance may touch it."""
    p = Path(uri)
    ownership.record(p.parent.parent, {"table": uri, "pack": pack, "kind": kind, "run": "test", "finishedAt": "2026-09-30T00:00:00+00:00"})


def actions(uri: str) -> list[dict]:
    """The table's live files as an independent reader (delta-rs, test only: maintenance itself is Drishti Lake's) lists them."""
    import pyarrow as pa
    from deltalake import DeltaTable
    return pa.table(DeltaTable(uri).get_add_actions(flatten=True)).to_pylist()


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
        own(uri)
        return uri

    def test_retention_compaction_checkpoint_and_vacuum(self):
        from deltalake import DeltaTable
        with tempfile.TemporaryDirectory() as tmp:
            uri = self.lake(Path(tmp))
            files_before = len(actions(uri))
            self.assertEqual(files_before, 90)
            config = {"lakes": [{"root": tmp, "keep-business-days": 10, "max-drop-share": 0.7, "compact": True, "checkpoint": True, "vacuum-hours": 0}]}
            self.assertEqual(maintain.run(config, dry_run=True, today=date(2026, 9, 30)), 0)
            self.assertEqual(len(actions(uri)), 90)        # a dry run changes nothing
            self.assertEqual(maintain.run(config, dry_run=False, today=date(2026, 9, 30)), 0)
            dt = DeltaTable(uri)
            import pyarrow as pa
            rows = pa.table(dt.to_pyarrow_table()).to_pylist()
            dates = sorted({r["business_date"] for r in rows})
            self.assertEqual(dates, business_days(date(2026, 9, 30), 11))                             # today and ten business days before it
            self.assertEqual(len(rows), 11 * 15)                                                      # every kept row intact
            self.assertEqual(len(actions(uri)), 11)                   # one file per day
            parquet_on_disk = list(Path(uri).rglob("*.parquet"))
            self.assertLessEqual(len([p for p in parquet_on_disk if "_delta_log" not in str(p)]), 11)  # vacuumed
            self.assertTrue(any(Path(uri, "_delta_log").glob("*.checkpoint.parquet")))

    def test_the_lake_files_settings_are_read_in_this_modules_keys(self):
        import maintconfig
        conf, notes = maintconfig.from_lake_entry({"retention": "2y", "time-travel": "30d",
                                                   "maintenance": {"compaction": "false", "expire-snapshots": "7d", "checkpoint-interval": "20",
                                                                   "colour": "red"}})
        self.assertEqual(conf["keep-business-days"], 2 * maintconfig.BUSINESS_DAYS_A_YEAR)
        self.assertFalse(conf["compact"])
        self.assertEqual(conf["vacuum-hours"], 168)
        self.assertTrue(conf["checkpoint"])
        self.assertEqual(maintconfig.with_time_travel(dict(conf))["vacuum-hours"], 720)   # time travel: never vacuumed below 30 days
        self.assertTrue(any("colour" in n for n in notes) and any("checkpoint-interval" in n for n in notes))
        self.assertEqual(maintconfig.from_lake_entry({"retention": "260d"})[0], {"keep-business-days": 260})
        self.assertEqual(maintconfig.from_lake_entry({})[0], {})

    def test_a_tables_lake_file_settings_win_over_the_maintenance_file(self):
        from deltalake import DeltaTable
        with tempfile.TemporaryDirectory() as tmp:
            uri = self.lake(Path(tmp))
            # the maintenance file keeps every date; the table's lake file (as the one reader dumps it) keeps ten business dates
            lakes = [{"lake": "t", "root": tmp, "tables": [{"path": "trading/trade", "retention": "10", "maintenance": {"vacuum": "0h"}}]}]
            config = {"_lakes": lakes, "lakes": [{"root": tmp, "keep-business-days": None, "max-drop-share": 0.7, "compact": True,
                                                  "checkpoint": True, "vacuum-hours": 168}]}
            report = maintain.run_detailed(config, dry_run=False, today=date(2026, 9, 30), quiet=True)
            self.assertEqual(report["failures"], 0, report)
            self.assertIn("keep-business-days", report["tables"][0]["lake-file"])
            import pyarrow as pa
            dates = sorted({r["business_date"] for r in pa.table(DeltaTable(uri).to_pyarrow_table()).to_pylist()})
            self.assertEqual(len(dates), 11)                                                          # the lake file's retention

    def test_retention_that_would_delete_most_of_a_table_needs_force_drop(self):
        with tempfile.TemporaryDirectory() as tmp:
            uri = self.lake(Path(tmp))
            config = {"lakes": [{"root": tmp, "keep-business-days": 10, "compact": False, "checkpoint": False, "vacuum-hours": None}]}
            self.assertEqual(maintain.run(config, dry_run=False, today=date(2026, 9, 30)), 1)      # 19 of 30 days: refused
            self.assertEqual(len(actions(uri)), 90)                       # nothing deleted
            self.assertEqual(maintain.run(config, dry_run=False, today=date(2026, 9, 30), force_drop=True), 0)
            self.assertEqual(len({a["partition.business_date"] for a in actions(uri)}), 11)

    def test_retention_counts_back_from_today_never_from_a_future_date_in_the_table(self):
        import pyarrow as pa
        from deltalake import write_deltalake
        with tempfile.TemporaryDirectory() as tmp:
            uri = self.lake(Path(tmp))
            write_deltalake(uri, pa.table({"id": ["T-TYPO"], "doc": ["{}"], "business_date": pa.array([date(2099, 3, 1)], pa.date32())}),
                            mode="append", partition_by=["business_date"])
            config = {"lakes": [{"root": tmp, "keep-business-days": 25, "compact": False, "checkpoint": False, "vacuum-hours": None}]}
            self.assertEqual(maintain.run(config, dry_run=False, today=date(2026, 9, 30)), 0)
            dates = {str(a["partition.business_date"]) for a in actions(uri)}
            self.assertEqual(len(dates), 27)                     # today and the 25 business days before it, and the typo
            self.assertIn("2099-03-01", dates)

    def test_a_failing_table_does_not_stop_the_others(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.lake(Path(tmp))
            broken = Path(tmp) / "trading" / "broken" / "_delta_log"
            broken.mkdir(parents=True)
            (broken / "00000000000000000000.json").write_text("not delta")
            own(str(broken.parent))
            config = {"lakes": [{"root": tmp, "compact": True, "checkpoint": False, "vacuum-hours": None}]}
            self.assertEqual(maintain.run(config, dry_run=False, today=date(2026, 9, 30)), 1)

    def test_relayout_sorts_into_the_layouts_files_and_is_idempotent(self):
        import pyarrow as pa
        import pyarrow.parquet as pq
        from deltalake import DeltaTable
        import maintain_iceberg
        with tempfile.TemporaryDirectory() as tmp:
            uri = self.lake(Path(tmp))                                  # three unsorted files a day, id and doc only
            dates = business_days(date(2026, 9, 30), 30)

            def relayout() -> list[str]:
                cmd = maintain.lake_command(uri, {}, date(2026, 9, 30), False, False, 0, False, relayout=True, file_rows=4)
                return json.loads(maintain_iceberg.json_line(cmd, "lake maintain"))["relayout"]["dates"]

            self.assertEqual(relayout(), [str(d) for d in dates])        # every date drifted (three files sharing no order)
            dt = DeltaTable(uri)
            acts = actions(uri)
            for d in dates[-2:]:
                files = sorted((a for a in acts if a["partition.business_date"] == d), key=lambda a: a["min.id"])
                self.assertEqual([a["num_records"] for a in files], [4, 4, 4, 3])
                self.assertTrue(all(files[k]["max.id"] < files[k + 1]["min.id"] for k in range(3)))
                for a in files:
                    ids = pq.read_table(str(Path(uri) / a["path"]), columns=["id"]).column("id").to_pylist()
                    self.assertEqual(ids, sorted(ids))
            rows = pa.table(dt.to_pyarrow_table()).to_pylist()
            self.assertEqual(len(rows), 30 * 15)                                                      # nothing lost
            version = dt.version()
            self.assertEqual(relayout(), [])                                                          # a second run changes nothing
            self.assertEqual(DeltaTable(uri).version(), version)

    def test_relayout_command_reads_the_pack_layout(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.lake(Path(tmp) / "lake")
            pack = Path(tmp) / "packs" / "demo"
            pack.mkdir(parents=True)
            (pack / "pack.yaml").write_text("connectors:\n  store:\n    plugin: delta\n    kinds: [trade]\n    settings:\n      domain: trading\n"
                                            "      layout:\n        trade:\n          columns: [mtm]\n          file-rows: 100\n")
            args = ["relayout", "--root", str(Path(tmp) / "lake"), "--domain", "trading", "--packs", str(Path(tmp) / "packs"), "--dates", "2026-09-30"]
            self.assertEqual(maintain.main(args), 0)
            self.assertEqual(maintain.main(args + ["--force"]), 0)
            self.assertEqual(maintain.main(args + ["--kind", "book"]), 1)                        # no layout for that kind

    def test_nightly_maintenance_keeps_a_laid_out_table_sorted(self):
        from samplegen.layout import write_partition
        with tempfile.TemporaryDirectory() as tmp:
            uri = str(Path(tmp) / "trading" / "trade_endur")            # trade-endur's table: the trading pack declares its layout
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
            day = [a for a in actions(uri) if str(a["partition.business_date"]) == "2026-09-30"]
            self.assertEqual(len(day), 1)                                                      # merged, and in id order
            self.assertEqual((day[0]["min.id"], day[0]["max.id"], day[0]["num_records"]), ("T-00000", "T-00038", 22))
            self.assertNotIn("min.doc", day[0])                                                # no document in the log
            again = maintain.maintain(uri, conf, date(2026, 9, 30), dry_run=False)
            self.assertEqual(again["compact"]["relaid-dates"], [])

    def test_a_per_source_trade_table_partitioned_by_product_type_is_laid_out_by_its_connector(self):
        """The trading pack's tables are trade_endur, trade_calypso and trade_murex (connectors trade-endur ... with
        table.trade), partitioned by business_date and PRODUCT_TYPE: maintenance finds their layout by the table's folder
        (it once looked up the folder as a kind, found none and only compacted, out of id order), and relayout keeps both
        partition columns."""
        import pyarrow as pa
        from deltalake import DeltaTable, write_deltalake
        from samplegen.layout import layouts_by_table
        tables = layouts_by_table("trading")
        # one table per source system (the trade connectors live in the source modules; the trading pack lays out each table)
        self.assertEqual({t: k for t, (k, _l) in tables.items() if k == "trade"},
                         {f"trade_{s}": "trade" for s in ("endur", "calypso", "murex", "wss", "swapone", "ici")})
        with tempfile.TemporaryDirectory() as tmp:
            uri = str(Path(tmp) / "trading" / "trade_calypso")
            lay = maintain.layout_of(uri)
            self.assertIsNotNone(lay)
            self.assertIn("PRODUCT_TYPE", lay.columns)
            day = date(2026, 9, 30)
            for part in range(3):                                       # intraday loads: unsorted, small, both product types
                ids = [f"CA-{(7 * i + part) % 30:05d}" for i in range(10)]
                docs = [json.dumps({"TRADE_ID": i, "PRODUCT_TYPE": "ComSwap" if n % 2 else "ComFut"}) for n, i in enumerate(ids)]
                write_deltalake(uri, pa.table({"id": ids, "doc": docs, "business_date": pa.array([day] * 10, pa.date32()),
                                               "PRODUCT_TYPE": ["ComSwap" if n % 2 else "ComFut" for n in range(10)]}),
                                mode="append", partition_by=["business_date", "PRODUCT_TYPE"])
            own(uri, kind="trade")
            conf = {**maintain.DEFAULTS, "checkpoint": False, "vacuum-hours": None}
            done = maintain.maintain(uri, conf, day, dry_run=False)
            self.assertEqual(done["compact"].get("relaid-dates"), ["2026-09-30"], done["compact"])   # laid out, not a plain compaction
            dt = DeltaTable(uri)
            self.assertEqual(dt.metadata().partition_columns, ["business_date", "PRODUCT_TYPE"])
            rows = dt.to_pyarrow_table().to_pylist()
            self.assertEqual(len(rows), 30)
            self.assertTrue(all(r["PRODUCT_TYPE"] == json.loads(r["doc"])["PRODUCT_TYPE"] for r in rows))
            for a in actions(uri):
                self.assertIn(a["partition.PRODUCT_TYPE"], ("ComSwap", "ComFut"))
                self.assertIsNotNone(a.get("min.id"))                                          # each file its own id range, by statistics
            self.assertEqual(maintain.maintain(uri, conf, day, dry_run=False)["compact"].get("relaid-dates"), [])   # in the layout now

    def test_the_schedule(self):
        from datetime import datetime
        from zoneinfo import ZoneInfo
        ny = ZoneInfo("America/New_York")
        self.assertEqual(maintain.next_run(datetime(2026, 9, 30, 1, 0, tzinfo=ny), "02:30"), datetime(2026, 9, 30, 2, 30, tzinfo=ny))
        self.assertEqual(maintain.next_run(datetime(2026, 9, 30, 3, 0, tzinfo=ny), "02:30"), datetime(2026, 10, 1, 2, 30, tzinfo=ny))


if __name__ == "__main__":
    unittest.main()
