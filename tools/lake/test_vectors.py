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

"""lake build with a vector column (VAR_GUIDE.md section 29.3): a P&L vector of numbers becomes a list<double> column, out of the
document (which keeps a {"$vector": "PNL"} marker), read back equal to the bit; a vector that is not a list of numbers is a reject. The
same in both formats: Delta (the default) and Iceberg (--format iceberg, written by the server's exec jar or DRISHTI_ICEBERG_CLASSPATH;
skipped without one).

    uv run --with deltalake --with pyarrow --with pyyaml --with pytest python -m pytest tools/lake/test_vectors.py -q
"""
import json
import os
import random
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build  # noqa: E402
import stage  # noqa: E402
import writers  # noqa: E402


def iceberg_loader() -> bool:
    """Whether the Iceberg loader can run here: Java and the server's exec jar (or DRISHTI_ICEBERG_CLASSPATH)."""
    import stores
    try:
        stores.loader_command(Path(tempfile.gettempdir()))
        return True
    except SystemExit:
        return False

N = 509


def vector_doc(i: int) -> dict:
    r = random.Random(i)
    return {"VECTOR_ID": f"PV-MX-{20000000 + i}", "TRADE_ID": f"MX-{20000000 + i}", "BUSINESS_DATE": "2026-10-23", "DESK": "DESK-RATES",
            "TT_BOOK": f"BOOK-RATES-{i % 3 + 1}", "PNL": [round(r.gauss(0, 50_000)) + (0.5 if j % 11 == 0 else 0) for j in range(N)]}


class VectorStageTest(unittest.TestCase):

    def test_the_vector_leaves_the_document_and_is_typed(self):
        st = stage.Stager(None, "VECTOR_ID", "BUSINESS_DATE", None, [], ["DESK"], None, ["PNL"])
        st.add(vector_doc(1), "a:1")
        st.add({**vector_doc(2), "PNL": ["x", 1]}, "a:2")
        st.add({**vector_doc(3), "PNL": None}, "a:3")                # no vector: kept, counted by the reader as failed
        self.assertEqual(st.stats.accepted, 2)
        self.assertEqual(list(st.stats.rejects), ["the vector field 'PNL' is not a list of numbers"])
        self.assertEqual(st.types(), {"DESK": "string", "PNL": "vector"})


@unittest.skipUnless(writers.deltalake_available(), "needs deltalake and pyarrow (uv run --with deltalake --with pyarrow ...)")
class VectorBuildTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        os.environ["DRISHTI_BI_STORE"] = str(self.root / "bi-store")
        self.src = self.root / "v.jsonl"
        self.docs = [vector_doc(i) for i in range(25)]
        self.src.write_text("\n".join(json.dumps(d) for d in self.docs) + "\n", encoding="utf-8")

    def tearDown(self):
        os.environ.pop("DRISHTI_BI_STORE", None)
        self.tmp.cleanup()

    def test_round_trip_to_the_bit(self):
        a = build.build_parser().parse_args(["--from", str(self.src), "--lake", str(self.root / "lake"), "--domain", "risk", "--kind", "pnl-vector",
                                             "--key", "VECTOR_ID", "--date", "BUSINESS_DATE", "--promote", "DESK,TT_BOOK", "--vector", "PNL",
                                             "--engine", "deltalake", "-q", "--no-stamp"])
        code, summary = build.run(a, say=lambda *x, **k: None)
        self.assertEqual(code, 0, summary)
        self.assertEqual(summary["columns"]["promoted"]["PNL"], "vector")
        from deltalake import DeltaTable
        t = DeltaTable(str(self.root / "lake" / "risk" / "pnl-vector")).to_pyarrow_table()
        self.assertEqual(str(t.schema.field("PNL").type), "list<element: double>" if "element" in str(t.schema.field("PNL").type)
                         else "list<item: double>")
        rows = {r["id"]: r for r in t.to_pylist()}
        for d in self.docs:
            row = rows[d["VECTOR_ID"]]
            self.assertEqual(row["PNL"], [float(x) for x in d["PNL"]])            # equal to the bit
            doc = json.loads(row["doc"])
            self.assertEqual(doc["PNL"], {"$vector": "PNL"})                   # not stored twice
            self.assertEqual(doc["TT_BOOK"], d["TT_BOOK"])
        self.assertEqual(writers.existing_layout(str(self.root / "lake" / "risk" / "pnl-vector"))[1]["PNL"], "vector")


class IcebergFormatTest(unittest.TestCase):
    """--format iceberg: the same rows into an Iceberg table, the vector a list<double> column with its missing elements null."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        os.environ["DRISHTI_BI_STORE"] = str(self.root / "bi-store")
        self.src = self.root / "v.jsonl"
        self.docs = [vector_doc(i) for i in range(12)]
        self.docs[3]["PNL"][7] = None                                      # a missing value stays missing
        self.src.write_text("\n".join(json.dumps(d) for d in self.docs) + "\n", encoding="utf-8")

    def tearDown(self):
        os.environ.pop("DRISHTI_BI_STORE", None)
        self.tmp.cleanup()

    def args(self, *extra):
        return build.build_parser().parse_args(["--from", str(self.src), "--lake", str(self.root / "ice"), "--domain", "risk", "--kind",
                                                "pnl-vector", "--key", "VECTOR_ID", "--date", "BUSINESS_DATE", "--promote", "DESK,TT_BOOK",
                                                "--vector", "PNL", "--format", "iceberg", "-q", "--no-stamp", *extra])

    def test_only_overwrite_dates(self):
        code, summary = build.run(self.args("--mode", "append"), say=lambda *x, **k: None)
        self.assertEqual(code, build.EXIT_FAILED)
        self.assertIn("--format iceberg replaces each business date", summary["error"])

    def test_an_object_storage_lake_is_for_iceberg_only(self):
        a = build.build_parser().parse_args(["--from", str(self.src), "--lake", "s3://bucket/lake", "--domain", "risk", "--kind", "pnl-vector",
                                             "--key", "VECTOR_ID", "--date", "BUSINESS_DATE", "-q"])
        code, summary = build.run(a, say=lambda *x, **k: None)
        self.assertEqual(code, build.EXIT_FAILED)
        self.assertIn("is for --format iceberg", summary["error"])

    def test_delta_is_the_default(self):
        a = build.build_parser().parse_args(["--from", str(self.src)])
        self.assertEqual(a.format, "delta")

    @unittest.skipUnless(iceberg_loader(), "needs Java and the server's exec jar (or DRISHTI_ICEBERG_CLASSPATH)")
    def test_round_trip_to_the_bit(self):
        import pyarrow.parquet as pq
        code, summary = build.run(self.args("--partition-by", "DESK"), say=lambda *x, **k: None)
        self.assertEqual(code, 0, summary)
        self.assertEqual(summary["format"], "iceberg")
        table = self.root / "ice" / "risk" / "pnl-vector"
        self.assertTrue((table / "metadata").is_dir())
        t = pq.ParquetDataset(sorted(str(f) for f in (table / "data").rglob("*.parquet"))).read()
        self.assertIn("DESK", t.schema.names)                              # a partition column kept as a column
        rows = {r["id"]: r for r in t.to_pylist()}
        self.assertEqual(len(rows), len(self.docs))
        for d in self.docs:
            row = rows[d["VECTOR_ID"]]
            self.assertEqual(row["PNL"], [None if x is None else float(x) for x in d["PNL"]])
            self.assertEqual(json.loads(row["doc"])["PNL"], {"$vector": "PNL"})
        import ownership
        self.assertIn("risk/pnl-vector", ownership.built(self.root / "ice"))   # Drishti's to maintain (Admin -> Lakes)


if __name__ == "__main__":
    unittest.main()
