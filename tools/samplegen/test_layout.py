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

"""The pack-declared Delta layout: promoted columns, id-sorted files of file-rows, row groups, statistics; the lake
and the bulk trading book written in it.

    uv run --with deltalake --with pyarrow --with pyyaml python -m unittest tools/samplegen/test_layout.py
"""
import contextlib
import io
import json
import pathlib
import sys
import tempfile
import unittest
from datetime import date

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from samplegen import bulk_trades, layout  # noqa: E402
from samplegen.dates import Calendar  # noqa: E402
from samplegen.lake import write_tables  # noqa: E402
from samplegen.layout import Layout  # noqa: E402

DAY = date(2026, 9, 30)


def actions(uri: str) -> list[dict]:
    import pyarrow as pa
    from deltalake import DeltaTable
    return pa.table(DeltaTable(uri).get_add_actions(flatten=True)).to_pylist()


def rows_of(uri: str) -> list[dict]:
    import pyarrow as pa
    from deltalake import DeltaTable
    return pa.table(DeltaTable(uri).to_pyarrow_table()).to_pylist()


def row_groups(uri: str, action: dict) -> list[int]:
    import pyarrow.parquet as pq
    meta = pq.ParquetFile(str(pathlib.Path(uri) / action["path"])).metadata
    return [meta.row_group(i).num_rows for i in range(meta.num_row_groups)]


class Settings(unittest.TestCase):
    def test_nested_and_flattened_forms(self):
        nested = {"domain": "trading", "layout": {"trade": {"columns": ["mtm", "counterparty.id"], "sort-by": "id", "file-rows": 10,
                                                            "row-group-rows": 4}}}
        flat = {"domain": "trading", "layout.trade.columns": "mtm, counterparty.id", "layout.trade.file-rows": "10",
                "layout.trade.row-group-rows": "4"}
        want = Layout(["mtm", "counterparty.id"], "id", 10, 4)
        self.assertEqual(Layout.from_settings(nested, "trade"), want)
        self.assertEqual(Layout.from_settings(flat, "trade"), want)
        self.assertIsNone(Layout.from_settings(nested, "book"))

    def test_the_trading_pack_declares_the_trade_layout(self):
        lay = layout.layouts_for_domain("trading")["trade"]
        self.assertEqual((lay.sort_by, lay.file_rows, lay.row_group_rows), ("id", 250_000, 1_000))
        self.assertIn("counterparty.id", lay.columns)
        self.assertEqual(layout.layouts_for_domain("reference"), {})


class Promotion(unittest.TestCase):
    def test_names_values_and_types(self):
        self.assertEqual(layout.column_name("counterparty.id"), "counterparty__id")
        self.assertEqual(layout.column_name("risk.dv01"), "risk__dv01")
        lay = Layout(["notional", "risk.dv01", "counterparty.id", "flag", "mixed", "missing"])
        docs = [{"notional": 5, "risk": {"dv01": 1.5}, "counterparty": {"id": "CP-A"}, "flag": True, "mixed": 7},
                {"notional": 2.5, "risk": None, "counterparty": {"id": "CP-B"}, "flag": False, "mixed": {"a": [1, 2]}},
                {"notional": None, "counterparty": "flat", "mixed": "text"}]
        rows = [layout.promote(d, lay) for d in docs]
        self.assertEqual(rows[0], {"notional": 5, "risk__dv01": 1.5, "counterparty__id": "CP-A", "flag": True, "mixed": 7, "missing": None})
        self.assertIsNone(rows[2]["counterparty__id"])                      # a path through a non-object is missing
        types = layout.infer_types({n: [r[n] for r in rows] for n in rows[0]})
        self.assertEqual(types, {"notional": "double", "risk__dv01": "double", "counterparty__id": "string", "flag": "string",
                                 "mixed": "string", "missing": "string"})
        t = layout.arrow_table(DAY, ["a", "b", "c"], ["{}"] * 3, {n: [r[n] for r in rows] for n in rows[0]}, types)
        self.assertEqual(str(t.schema.field("notional").type), "double")
        self.assertEqual(t.column("notional").to_pylist(), [5.0, 2.5, None])
        self.assertEqual(t.column("flag").to_pylist(), ["true", "false", None])
        self.assertEqual(t.column("mixed").to_pylist(), ["7", '{"a":[1,2]}', "text"])
        self.assertEqual(t.column("missing").to_pylist(), [None, None, None])


class Writing(unittest.TestCase):
    def test_sorted_files_of_file_rows_with_row_groups_and_statistics(self):
        lay = Layout(["mtm", "counterparty.id"], file_rows=10, row_group_rows=4)
        docs = {f"T-{n:03d}": {"tradeId": f"T-{n:03d}", "mtm": n * 10, "counterparty": {"id": f"CP-{n % 3}"}} for n in range(25)}
        rows = [(i, json.dumps(d), d) for i, d in reversed(list(docs.items()))]
        with tempfile.TemporaryDirectory() as tmp:
            uri = str(pathlib.Path(tmp) / "trade")
            self.assertEqual(layout.write_partition(uri, DAY, rows, lay, "append"), 3)
            self.assertEqual(layout.write_partition(uri, date(2026, 9, 29), rows, lay, "append"), 3)
            acts = actions(uri)
            for day in ("2026-09-29", "2026-09-30"):
                files = sorted((a for a in acts if str(a["partition.business_date"]) == day), key=lambda a: a["min.id"])
                self.assertEqual([a["num_records"] for a in files], [10, 10, 5])
                self.assertEqual([(a["min.id"], a["max.id"]) for a in files], [("T-000", "T-009"), ("T-010", "T-019"), ("T-020", "T-024")])
                self.assertEqual([(a["min.mtm"], a["max.mtm"]) for a in files], [(0.0, 90.0), (100.0, 190.0), (200.0, 240.0)])
                self.assertTrue(all(a["min.counterparty__id"] and a["null_count.mtm"] == 0 for a in files))
                for a in files:
                    self.assertTrue(all(n <= 4 for n in row_groups(uri, a)))
            got = rows_of(uri)
            self.assertEqual(len(got), 50)
            by_id = {r["id"]: r for r in got if r["business_date"] == DAY}
            self.assertEqual(by_id["T-007"]["mtm"], 70.0)
            self.assertEqual(by_id["T-007"]["counterparty__id"], "CP-1")
            self.assertEqual(json.loads(by_id["T-007"]["doc"]), docs["T-007"])
            # replacing one date leaves the other alone
            layout.write_partition(uri, DAY, rows[:4], lay, "overwrite-partition")
            self.assertEqual(len(rows_of(uri)), 29)

    def test_a_kind_without_layout_keeps_id_doc_and_date(self):
        with tempfile.TemporaryDirectory() as tmp:
            uri = str(pathlib.Path(tmp) / "book")
            layout.write_partition(uri, DAY, [("B-1", "{}", {}), ("B-0", "{}", {})], None, "append")
            self.assertEqual(sorted(rows_of(uri)[0]), ["business_date", "doc", "id"])


class Lake(unittest.TestCase):
    def test_write_tables_promotes_columns_and_restates_with_them(self):
        lay = Layout(["mtm", "counterparty.id", "risk.dv01"], file_rows=4, row_group_rows=2)
        docs = {f"T-{n}": {"tradeId": f"T-{n}", "mtm": 100 + n, "counterparty": {"id": "CP-A"}, "risk": {"dv01": n}} for n in range(6)}
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stdout(io.StringIO()):
            write_tables(pathlib.Path(tmp), {"trade": docs, "book": {"B-1": {"bookId": "B-1"}}}, DAY, 3, Calendar.of("USNY"), {"trade": lay})
            uri = str(pathlib.Path(tmp) / "trade")
            got = rows_of(uri)
            self.assertEqual(len(got), 18)
            for r in got:
                doc = json.loads(r["doc"])
                self.assertEqual(r["mtm"], float(doc["mtm"]))
                self.assertEqual(r["counterparty__id"], doc["counterparty"]["id"])
                self.assertEqual(r["risk__dv01"], float(doc["risk"]["dv01"]))
            restated = [r for r in got if "restated" in json.loads(r["doc"])]
            self.assertEqual(len(restated), 1)
            self.assertEqual((restated[0]["id"], restated[0]["business_date"], restated[0]["counterparty__id"]), ("T-0", DAY, "CP-A"))
            self.assertEqual(restated[0]["mtm"], 100.0)
            newest = sorted((a for a in actions(uri) if a["partition.business_date"] == DAY), key=lambda a: a["min.id"])
            self.assertEqual([(a["min.id"], a["max.id"], a["num_records"]) for a in newest], [("T-0", "T-3", 4), ("T-4", "T-5", 2)])
            self.assertEqual(sorted(rows_of(str(pathlib.Path(tmp) / "book"))[0]), ["business_date", "doc", "id"])


class BulkTrades(unittest.TestCase):
    def test_a_small_book_in_sorted_disjoint_files(self):
        import pyarrow.parquet as pq
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stdout(io.StringIO()):
            (pathlib.Path(tmp) / "reference").mkdir()
            self.assertEqual(bulk_trades.main(["--trades", "3000", "--days", "2", "--root", tmp, "--file-rows", "1000", "--workers", "2"]), 0)
            uri = str(pathlib.Path(tmp) / "trading" / "trade")
            acts = actions(uri)
            self.assertEqual(len(acts), 6)
            for day in {a["partition.business_date"] for a in acts}:
                files = sorted((a for a in acts if a["partition.business_date"] == day), key=lambda a: a["min.id"])
                self.assertEqual([a["num_records"] for a in files], [1000, 1000, 1000])
                self.assertTrue(all(files[k]["max.id"] < files[k + 1]["min.id"] for k in range(2)))
                for a in files:
                    ids = pq.read_table(str(pathlib.Path(uri) / a["path"]), columns=["id"]).column("id").to_pylist()
                    self.assertEqual(ids, sorted(ids))
                    self.assertTrue(all(n <= 10_000 for n in row_groups(uri, a)))
            got = {r["id"]: r for r in rows_of(uri) if r["business_date"] == date(2026, 9, 30)}
            self.assertEqual(len(got), 3000)
            self.assertIn("MX-20000001", got)
            self.assertEqual(got["MX-20000001"]["sourceSystem"], "Murex")                         # samples carry their system too
            self.assertEqual(json.loads(got["CLY-4000000"]["doc"])["assetClass"], "Credit")              # a clone stays in its template's system
            self.assertEqual(got["MX-30000000"]["sourceSystem"], "Murex")
            self.assertEqual(json.loads(got["MX-30000000"]["doc"])["sourceTradeId"], "30000000")
            self.assertEqual(got["MX-30000000"]["notional"], float(json.loads(got["MX-30000000"]["doc"])["notional"]))


if __name__ == "__main__":
    unittest.main()
