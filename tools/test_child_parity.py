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

"""Storage parity of child tables: the same nested JSON Lines loaded as Delta, Iceberg and files give the same child rows (count, ids, link
values, promoted columns) and the same answers through the BI relationship (a child's link to its parent: a filter on the child reaching the
parent, a sum of the child grouped by a parent field).

    uv run --with deltalake --with pyarrow --with pyyaml --with pytest python -m pytest tools/test_child_parity.py -q

The rules are the pack's ``ingest:`` block (tools/packgen/childkinds.py in Python, com.ash.drishti.lakeload.ChildKinds in the parallel loader);
the documents and the rows they must give are lake/drishti-lakeload/src/test/resources/childkinds, which ChildLoadsTest checks against the Java
split, so the two languages cannot drift. Iceberg and the parallel loader need Java and the server's exec jar (skipped without).
"""
from __future__ import annotations

import argparse
import json
import pathlib
import shutil
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent
FIXTURE = ROOT / "lake" / "drishti-lakeload" / "src" / "test" / "resources" / "childkinds"
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE / "lake"))
sys.path.insert(0, str(HERE / "packgen"))
import childkinds as CK  # noqa: E402
import build  # noqa: E402
import ingest_jsonl as IJ  # noqa: E402

try:
    import deltalake  # noqa: E402
    import pyarrow.parquet as pq  # noqa: E402
except ImportError:
    deltalake = None


def loader_available() -> bool:
    import stores
    try:
        stores.loader_command(pathlib.Path(tempfile.gettempdir()))
        return True
    except SystemExit:
        return False


def parallel_loader() -> bool:
    """Whether the parallel loader can run here: Java and the server's exec jar (or DRISHTI_LAKE_CLASSPATH)."""
    import javaload
    return javaload.command(None, None) is not None


def quiet(*_a, **_k):
    pass


COLUMNS = ("TRADE_ID", "LEG_ID", "CURRENCY", "NOTIONAL")


def cell(c: str, v):
    """A promoted value as every store gives it: the amount a number, an id or a name as text, missing as None."""
    if v is None:
        return None
    return float(v) if c == "NOTIONAL" else str(v)


def shape(rows: list[dict]) -> list[tuple]:
    """(id, date, the promoted columns) per row, in id order."""
    return sorted((str(r["id"]), str(r["business_date"])[:10], *[cell(c, r.get(c)) for c in COLUMNS]) for r in rows)


def delta_rows(table: pathlib.Path) -> list[dict]:
    return deltalake.DeltaTable(str(table)).to_pyarrow_table().to_pylist()


def iceberg_rows(table: pathlib.Path) -> list[dict]:
    files = sorted(str(f) for f in (table / "data").rglob("*.parquet"))
    return pq.ParquetDataset(files, partitioning=None).read().to_pylist()


def file_rows(root: pathlib.Path, domain: str, kind: str) -> list[dict]:
    """The File connector's layout holds documents only: its columns are the document's fields (what a layout would promote)."""
    out = []
    for f in sorted((root / domain).glob(f"*/{kind}.jsonl")):
        for line in f.read_text(encoding="utf-8").splitlines():
            r = json.loads(line)
            doc = json.loads(r["doc"]) if isinstance(r["doc"], str) else r["doc"]
            out.append({"id": r["id"], "business_date": r["date"], **doc})
    return out


def parent_shape(rows: list[dict]) -> list[tuple]:
    return sorted((str(r["id"]), str(r["business_date"])[:10], None if r.get("PRODUCT_TYPE") is None else str(r["PRODUCT_TYPE"])) for r in rows)


def relationship(parents: list[dict], children: list[dict]) -> dict:
    """What the BI model's many-to-one relationship (child.TRADE_ID -> parent id) answers, computed from the stored rows alone: the parents
    having any child in EUR (the filter on the child reaching the parent), the child NOTIONAL summed by the parent's PRODUCT_TYPE, children
    whose parent is missing."""
    by_id = {str(p["id"]): p for p in parents}
    semi = sorted({str(c["TRADE_ID"]) for c in children if c.get("CURRENCY") == "EUR" and str(c["TRADE_ID"]) in by_id})
    sums: dict[str, float] = {}
    for c in children:
        p = by_id.get(str(c["TRADE_ID"]))
        if p is not None and c.get("NOTIONAL") is not None:
            sums[str(p["PRODUCT_TYPE"])] = sums.get(str(p["PRODUCT_TYPE"]), 0.0) + float(c["NOTIONAL"])
    return {"parents with an EUR leg": semi, "notional by product": dict(sorted(sums.items())),
            "orphans": sorted(str(c["id"]) for c in children if str(c["TRADE_ID"]) not in by_id), "legs": len(children)}


class ChildParity(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(dir=pathlib.Path(tempfile.gettempdir()))
        self.dir = pathlib.Path(self.tmp.name)
        self.src = self.dir / "in" / "deal.2026-11-03.jsonl"
        self.src.parent.mkdir()
        shutil.copy(FIXTURE / "deal.2026-11-03.jsonl", self.src)
        self.pack = self.dir / "pack"
        self.pack.mkdir()
        shutil.copy(FIXTURE / "pack.yaml", self.pack / "pack.yaml")
        self.stores: dict[str, tuple[list[dict], list[dict]]] = {}

    def tearDown(self):
        self.tmp.cleanup()

    def ingest(self, **kw):
        a = IJ.build_parser().parse_args(["--from", str(self.src)])
        a.pack, a.kind = self.pack, None
        for k, v in kw.items():
            setattr(a, k, v)
        IJ.ingest(a, quiet)

    def build(self, name: str, *extra):
        lake = self.dir / name
        a = build.build_parser().parse_args(["--from", str(self.src), "--pack", str(self.pack), "--kind", "deal", "--lake", str(lake), "-q",
                                             "--no-stamp", *extra])
        code, summary = build.run(a, say=quiet)
        self.assertEqual(0, code, summary)
        return lake, summary

    # ---- the rules: one declaration, one fixture both languages check -----------------------------------------------------------

    def test_the_python_split_gives_the_shared_fixtures_rows(self):
        ch = CK.of_ingest(__import__("yaml").safe_load((self.pack / "pack.yaml").read_text())["ingest"])["deal"][0]
        for docs, expected in (("deal.2026-11-03.jsonl", "expected-deal-leg.jsonl"), ("edge.jsonl", "expected-edge.jsonl")):
            got = [r for line in (FIXTURE / docs).read_text().splitlines() for r in CK.explode(json.loads(line), ch)]
            want = [json.loads(line) for line in (FIXTURE / expected).read_text().splitlines()]
            self.assertEqual(want, got, docs)
            self.assertEqual([list(r)[:2] for r in want], [list(r)[:2] for r in got])       # the id first, then the link

    # ---- the stores -------------------------------------------------------------------------------------------------------------

    def load_all(self) -> None:
        """Every store this machine can write: the files store, Delta by the Python loader and by lake build, Iceberg by both and the
        parallel loader (Delta and Iceberg)."""
        root = self.dir / "files"
        self.ingest(store="files", root=root, mode="replace")
        self.stores["files"] = (file_rows(root, "deals", "deal"), file_rows(root, "deals", "deal-leg"))
        if deltalake is None:
            return
        lake = self.dir / "delta-ingest"
        self.ingest(store="delta", lake=lake, mode="replace")
        self.stores["delta (data ingest)"] = (delta_rows(lake / "deals" / "deal"), delta_rows(lake / "deals" / "deal-leg"))
        lake, summary = self.build("delta-build", "--engine", "deltalake")
        self.assertEqual({"deal-leg": 5}, {k: v["accepted"] for k, v in summary["children"].items()})
        self.stores["delta (lake build)"] = (delta_rows(lake / "deals" / "deal"), delta_rows(lake / "deals" / "deal-leg"))
        if loader_available():
            lake = self.dir / "iceberg-ingest"
            self.ingest(store="iceberg", lake=lake)
            self.stores["iceberg (data ingest)"] = (iceberg_rows(lake / "deals" / "deal"), iceberg_rows(lake / "deals" / "deal-leg"))
            lake, _ = self.build("iceberg-build", "--format", "iceberg")
            self.stores["iceberg (lake build)"] = (iceberg_rows(lake / "deals" / "deal"), iceberg_rows(lake / "deals" / "deal-leg"))
        if parallel_loader():
            for fmt in ("delta", "iceberg"):
                lake, summary = self.build(f"java-{fmt}", "--engine", "java", "--format", fmt, "--mode", "overwrite-dates")
                read = delta_rows if fmt == "delta" else iceberg_rows
                self.stores[f"{fmt} (parallel loader)"] = (read(lake / "deals" / "deal"), read(lake / "deals" / "deal-leg"))

    def test_every_store_holds_the_same_child_rows_and_answers_the_same(self):
        self.load_all()
        self.assertGreaterEqual(len(self.stores), 1)
        want_ids = ["T1:1", "T1:2", "T2:0", "T2:B", "T5:3"]
        base = None
        for name, (parents, children) in self.stores.items():
            with self.subTest(store=name):
                self.assertEqual(want_ids, sorted(str(r["id"]) for r in children))
                self.assertEqual({"2026-11-03"}, {str(r["business_date"])[:10] for r in children})          # the parent's date
                self.assertEqual(["T1", "T1", "T2", "T2", "T5"], sorted(str(r["TRADE_ID"]) for r in children))        # the link
                self.assertEqual(["T1", "T2", "T3", "T4", "T5"], sorted(str(r["id"]) for r in parents))
                got = (shape(children), parent_shape(parents), relationship(parents, children))
                base = base or got
                self.assertEqual(base[0], got[0])                                       # count, ids, link, promoted columns
                self.assertEqual(base[1], got[1])
                self.assertEqual(base[2], got[2])                                       # the BI relationship's answers
        rel = base[2]
        self.assertEqual(["T1"], rel["parents with an EUR leg"])
        self.assertEqual({"CCS": 21.0, "IRS": 3000.5}, rel["notional by product"])
        self.assertEqual([], rel["orphans"])

    def test_a_child_kind_is_in_every_format_the_parents_layout_in_each(self):
        """Delta and Iceberg child tables carry the id, doc, business_date and the pack's promoted columns for the child kind."""
        self.load_all()
        for name, (_, children) in self.stores.items():
            if name == "files":
                continue
            with self.subTest(store=name):
                cols = set(children[0])
                self.assertTrue({"id", "doc", "business_date", "TRADE_ID", "LEG_ID", "CURRENCY", "NOTIONAL"} <= cols, cols)
                row = next(r for r in children if r["id"] == "T1:1")
                self.assertEqual(1000.5, row["NOTIONAL"])
                self.assertEqual({"LEG_KEY": "T1:1", "TRADE_ID": "T1", "LEG_ID": 1, "CURRENCY": "USD", "NOTIONAL": 1000.5}, json.loads(row["doc"]))

    def test_the_stores_cannot_be_asked_for_a_mode_they_do_not_have(self):
        with self.assertRaises(SystemExit) as e:
            self.ingest(store="iceberg", lake=self.dir / "x", mode="append")
        self.assertIn("--store iceberg replaces each business date", str(e.exception))


if __name__ == "__main__":
    unittest.main()
