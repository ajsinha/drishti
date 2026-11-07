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

"""Feeds (DATA_GUIDE.md section 2.8): the shared cases (testdata/file-dates/names.json, which the Java FileDatesTest reads too), then
``lake build`` end to end on a small lake: plain files dated by their names, by --as-of, by a .ready file and by the business today; a
row's own date only counted; an empty file ignored; a tar of two tables and two dates; a refused tar; the table taken from the name.

    uv run --with deltalake --with pyarrow --with pyyaml --with pytest python -m pytest tools/lake/test_filedate.py -q
"""
import gzip
import io
import json
import os
import sys
import tarfile
import tempfile
import unittest
import zipfile
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import filedate  # noqa: E402

CASES = json.loads((HERE / "testdata" / "file-dates" / "names.json").read_text(encoding="utf-8"))
TODAY = date.fromisoformat(CASES["today"])


def d(text):
    return date.fromisoformat(text) if text else None


class SharedCasesTest(unittest.TestCase):

    def test_names(self):
        for c in CASES["names"]:
            with self.subTest(name=c["name"]):
                n = filedate.parse(c["name"], TODAY)
                self.assertEqual(n.tar, c.get("tar", False))
                if c.get("error"):
                    self.assertIsNotNone(n.error)
                    self.assertIn(c["error"], n.error)
                else:
                    self.assertIsNone(n.error)
                    self.assertEqual((n.table, n.date), (c.get("table"), d(c.get("date"))))

    def test_decide(self):
        for c in CASES["decide"]:
            with self.subTest(case=c):
                r = filedate.decide(d(c["given"]), d(c["name"]), c["dateFrom"], TODAY, c.get("file", "f.jsonl"))
                if c.get("error"):
                    self.assertIn(c["error"], r.error)
                else:
                    self.assertEqual((r.date, r.source, filedate.words(r)), (d(c["date"]), c["source"], c["words"]))

    def test_business_today(self):
        for c in CASES["businessToday"]:
            self.assertEqual(filedate.business_today(d(c["today"])), d(c["business"]))

    def test_tar_layouts(self):
        for c in CASES["tars"]:
            with self.subTest(case=c["case"]):
                lay = filedate.tar_layout([tuple(e) for e in c["entries"]], TODAY, c["known"])
                self.assertEqual({t: {k.isoformat(): v for k, v in ds.items()} for t, ds in lay.tables.items()}, c["tables"])
                self.assertEqual(lay.errors, c["errors"])
                self.assertEqual(lay.ignored, c["ignored"])


try:
    import deltalake  # noqa: F401
    HAVE_LAKE = True
except ImportError:
    HAVE_LAKE = False

PACK = """pack: book
connectors:
  book-lake:
    plugin: delta
    kinds: [deal, position]
    settings: {domain: trading}
ingest:
  deal: {key: TRADE_ID, date: TRADE_DATE}
  position: {key: POS_ID}
"""


def deal(i: int, day: str | None) -> dict:
    out = {"TRADE_ID": 1000 + i, "DESK": ["RATES", "FX"][i % 2], "NOTIONAL": 10.0 * i}
    if day:
        out["TRADE_DATE"] = day
    return out


def lines(docs) -> bytes:
    return "".join(json.dumps(x) + "\n" for x in docs).encode()


def tar_bytes(members: dict, gz: bool = True) -> bytes:
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz" if gz else "w") as t:
        for name, data in members.items():
            info = tarfile.TarInfo(name)
            info.size = len(data)
            t.addfile(info, io.BytesIO(data))
    return buf.getvalue()


@unittest.skipUnless(HAVE_LAKE, "needs deltalake and pyarrow")
class LakeBuildTest(unittest.TestCase):
    """lake build (the Python path) on a small lake under a temporary folder."""

    def setUp(self):
        import build
        self.build = build
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.src = self.root / "drop"
        self.src.mkdir()
        self.lake = self.root / "lake"
        self.packs = self.root / "packs"
        (self.packs / "book").mkdir(parents=True)
        (self.packs / "book" / "pack.yaml").write_text(PACK)
        self._packs_root, build.PACKS_ROOT = build.PACKS_ROOT, self.packs
        os.environ["DRISHTI_BI_STORE"] = str(self.root / "bi")

    def tearDown(self):
        self.build.PACKS_ROOT = self._packs_root
        os.environ.pop("DRISHTI_BI_STORE", None)
        self.tmp.cleanup()

    def run_build(self, src=None, **kw):
        a = self.build.build_parser().parse_args([])
        d_ = dict(src=[str(src or self.src)], lake=str(self.lake), engine="deltalake", no_stamp=True, quiet=False)
        d_.update(kw)
        for k, v in d_.items():
            setattr(a, k, v)
        said = []
        code, s = self.build.run(a, said.append)
        return code, s, "\n".join(said)

    def rows(self, table="deal"):
        from deltalake import DeltaTable
        return DeltaTable(str(self.lake / "trading" / table)).to_pyarrow_table().to_pylist()

    def test_dated_names_today_and_counts(self):
        (self.src / "deal.2026-11-03.jsonl").write_bytes(lines([deal(i, "2026-11-03" if i >= 3 else "2026-11-02") for i in range(10)]))
        with gzip.open(self.src / "deal.2026-11-02.jsonl.gz", "wb") as fh:
            fh.write(lines([deal(i, "2026-10-01") for i in range(10, 15)]))
        with zipfile.ZipFile(self.src / "deal.2026-10-30.zip", "w") as z:
            z.writestr("a.jsonl", lines([deal(i, None) for i in range(20, 23)]))
        (self.src / "deal.jsonl").write_bytes(lines([deal(i, "2026-10-29") for i in range(30, 32)]))
        (self.src / "deal.2026-11-01.jsonl").write_bytes(b"")
        code, s, said = self.run_build(pack=str(self.packs / "book"), kind="deal")
        self.assertEqual(code, 0, s.get("error"))
        today = filedate.business_today().isoformat()
        self.assertEqual(s["mode"], "append")
        self.assertEqual(s["rows"], {"2026-10-30": 3, "2026-11-02": 5, "2026-11-03": 10, today: 2})
        by_file = {f["file"]: (f["date"], f["source"], f["otherDateRows"]) for f in s["fileDates"]}
        self.assertEqual(by_file["deal.2026-11-03.jsonl"], ("2026-11-03", "name", 3))
        self.assertEqual(by_file["deal.2026-11-02.jsonl.gz"], ("2026-11-02", "name", 5))
        self.assertEqual(by_file["deal.2026-10-30.zip!/a.jsonl"], ("2026-10-30", "name", 0))
        self.assertEqual(by_file["deal.jsonl"], (today, "today", 2))
        self.assertEqual(s["emptyFiles"], ["deal.2026-11-01.jsonl"])
        self.assertIn("3 rows of deal.2026-11-03.jsonl carried another date in TRADE_DATE; loaded under 2026-11-03", s["dateNotes"])
        self.assertIn("business date 2026-11-03 from the file name: deal.2026-11-03.jsonl", said)
        self.assertIn("empty, ignored: deal.2026-11-01.jsonl", said)
        r = next(x for x in self.rows() if x["id"] == "1000")
        self.assertEqual((str(r["business_date"]), json.loads(r["doc"])["TRADE_DATE"]), ("2026-11-03", "2026-11-02"))

    def test_an_old_table_without_load_columns_gains_them(self):
        """A table built before the load columns (the lakes already built): an append adds _load_id and _source_file (schema merge), its
        old rows null, its files kept; a replace of a date works too."""
        import shutil
        from deltalake import DeltaTable, write_deltalake
        (self.src / "deal.2026-11-02.jsonl").write_bytes(lines([deal(i, None) for i in range(3)]))
        code, s, _ = self.run_build(pack=str(self.packs / "book"), kind="deal")
        self.assertEqual(code, 0, s.get("error"))
        path = self.lake / "trading" / "deal"
        t = DeltaTable(str(path)).to_pyarrow_table()
        old = t.drop_columns(["_load_id", "_source_file"])
        shutil.rmtree(path)
        write_deltalake(str(path), old, partition_by=["business_date"])               # as the loader wrote it before
        self.assertNotIn("_load_id", [f.name for f in DeltaTable(str(path)).schema().fields])
        before = set(DeltaTable(str(path)).file_uris())
        (self.src / "deal.2026-11-02.jsonl").write_bytes(lines([deal(i, None) for i in range(2, 5)]))
        code, s, _ = self.run_build(pack=str(self.packs / "book"), kind="deal")
        self.assertEqual(code, 0, s.get("error"))
        load = next(iter(s["loads"]))
        rows = self.rows()
        self.assertEqual(sorted((r["id"], r["_load_id"] or "") for r in rows),
                         sorted([("1000", ""), ("1001", ""), ("1002", ""), ("1002", load), ("1003", load), ("1004", load)]))
        self.assertTrue(before <= set(DeltaTable(str(path)).file_uris()), "an append rewrites no old file")
        (self.src / "deal.2026-11-02.jsonl.ready").write_text("mode: replace\n")
        code, s, _ = self.run_build(pack=str(self.packs / "book"), kind="deal")
        self.assertEqual(code, 0, s.get("error"))
        self.assertEqual(sorted(r["id"] for r in self.rows()), ["1002", "1003", "1004"])

    def test_the_java_loader_adds_the_load_columns_and_deltalake_reads_them(self):
        """A table built before the load columns, appended by the Java loader (its metadata commit adds _load_id and _source_file, no
        file rewritten), read back with the deltalake package the tools use: the schema has the two columns, the old rows null, the
        new rows their load id and file, every row there. Needs the loader (the server's exec jar, or DRISHTI_LAKE_CLASSPATH)."""
        import shutil
        import javaload
        from deltalake import DeltaTable, write_deltalake
        if javaload.command(None, None) is None:
            self.skipTest("no Java lake loader (build the server's exec jar, or set DRISHTI_LAKE_CLASSPATH)")
        (self.src / "deal.2026-11-02.jsonl").write_bytes(lines([deal(i, None) for i in range(3)]))
        code, s, _ = self.run_build(pack=str(self.packs / "book"), kind="deal")
        self.assertEqual(code, 0, s.get("error"))
        path = self.lake / "trading" / "deal"
        old = DeltaTable(str(path)).to_pyarrow_table().drop_columns(["_load_id", "_source_file"])
        shutil.rmtree(path)
        write_deltalake(str(path), old, partition_by=["business_date"])               # a table as the loaders wrote it before
        version, before = DeltaTable(str(path)).version(), set(DeltaTable(str(path)).file_uris())
        (self.src / "deal.2026-11-02.jsonl").write_bytes(lines([deal(i, None) for i in range(2, 6)]))
        code, s, said = self.run_build(pack=str(self.packs / "book"), kind="deal", engine="java")
        self.assertEqual(code, 0, f"{s.get('error')}\n{said}")
        load = next(iter(s["loads"]))
        t = DeltaTable(str(path))
        self.assertGreaterEqual(t.version(), version + 2, "a metadata commit, then the append")
        self.assertTrue({"_load_id", "_source_file"} <= {f.name for f in t.schema().fields})
        self.assertTrue(before <= set(t.file_uris()), "no old file rewritten")
        rows = t.to_pyarrow_table().to_pylist()
        self.assertEqual(len(rows), 7)
        self.assertEqual(sorted((r["id"], r["_load_id"] or "", r["_source_file"] or "") for r in rows),
                         sorted([("1000", "", ""), ("1001", "", ""), ("1002", "", "")]
                                + [(str(1000 + i), load, "deal.2026-11-02.jsonl") for i in range(2, 6)]))

    def test_as_of_and_ready_marker(self):
        f = self.src / "deal.2026-11-03.jsonl"
        f.write_bytes(lines([deal(i, "2026-11-03") for i in range(4)]))
        (self.src / "deal.2026-11-03.jsonl.ready").write_text("business-date: 2026-11-01\nmode: replace\n")
        code, s, _ = self.run_build(pack=str(self.packs / "book"), kind="deal")
        self.assertEqual(code, 0, s.get("error"))
        self.assertEqual((s["rows"], s["mode"]), ({"2026-11-01": 4}, "overwrite-dates"))
        self.assertEqual((s["fileDates"][0]["source"], s["fileDates"][0]["dateText"]), ("ready", "2026-11-01 from the .ready file"))
        code, s, _ = self.run_build(pack=str(self.packs / "book"), kind="deal", as_of="2026-10-31")
        self.assertEqual(s["rows"], {"2026-10-31": 4})

    def test_table_from_the_name_and_refusals(self):
        (self.src / "deal.2026-11-03.jsonl").write_bytes(lines([deal(i, None) for i in range(3)]))
        code, s, _ = self.run_build()
        self.assertEqual(code, 0, s.get("error"))
        self.assertEqual((s["kind"], s["rows"]), ("deal", {"2026-11-03": 3}))
        odd = self.root / "odd"
        odd.mkdir()
        (odd / "mystery.2026-11-03.jsonl").write_bytes(lines([deal(1, None)]))
        code, s, _ = self.run_build(src=odd)
        self.assertEqual(code, self.build.EXIT_FAILED)
        self.assertIn("mystery: no pack, kind or table of that name (known: book/deal, book/position)", s["error"])
        (odd / "mystery.2026-11-03.jsonl").rename(odd / "deal.v2.jsonl")
        code, s, _ = self.run_build(src=odd)
        self.assertIn("'v2' is neither the date", s["error"])
        code, s, _ = self.run_build(src=odd, pack=str(self.packs / "book"), kind="deal", table="dael")
        self.assertIn("--table dael: not a table of the pack", s["error"])
        code, s, _ = self.run_build(src=self.src, pack=str(self.packs / "book"), kind="deal", date_from="name", as_of=None)
        self.assertEqual(code, 0, s.get("error"))
        (self.src / "deal.jsonl").write_bytes(lines([deal(9, None)]))
        code, s, _ = self.run_build(src=self.src, pack=str(self.packs / "book"), kind="deal", date_from="name")
        self.assertEqual(code, self.build.EXIT_FAILED)
        self.assertIn("deal.jsonl: date-from name, but the name carries no date", s["error"])

    def test_a_wrong_date_in_a_name_is_refused_never_today(self):
        """A plain name whose date part is not a calendar date, or is in the future, is refused with the reason, also when the table is
        given (--kind) and when --as-of is: never filed under the business today or another date (DATA_LAKE_GUIDE.md section 5.2)."""
        bad = self.root / "bad"
        bad.mkdir()
        for name, why in (("deal.2026-13-03.jsonl", "2026-13-03 is not a calendar date"), ("deal.2999-01-04.jsonl", "2999-01-04 is in the future")):
            for f in bad.iterdir():
                f.unlink()
            (bad / name).write_bytes(lines([deal(1, None)]))
            for kw in ({}, {"pack": str(self.packs / "book"), "kind": "deal"}, {"pack": str(self.packs / "book"), "kind": "deal", "as_of": "2026-11-02"}):
                with self.subTest(name=name, kw=kw):
                    code, s, _ = self.run_build(src=bad, **kw)
                    self.assertEqual(code, self.build.EXIT_FAILED, s)
                    self.assertIn(why, s["error"])
        self.assertFalse((self.lake / "trading" / "deal").exists(), "nothing written")

    def test_plan_says_what_would_happen_and_writes_nothing(self):
        (self.src / "deal.2026-11-03.jsonl").write_bytes(lines([deal(1, None)]))
        (self.src / "deal.jsonl").write_bytes(lines([deal(2, None)]))
        (self.src / "deal.ready").write_text("business-date: 2026-11-01\n")
        code, s, said = self.run_build(plan=True, lake=None)
        self.assertEqual((code, s["status"]), (0, "plan"))
        by = {r["file"]: (r["table"], r["kind"], r["dateText"], r["mode"]) for r in s["plan"]}
        self.assertEqual(by["deal.2026-11-03.jsonl"], ("deal", "deal", "2026-11-03 from the file name", "append"))
        self.assertEqual(by["deal.jsonl"], ("deal", "deal", "2026-11-01 from the .ready file", "append"))
        self.assertIn("deal.jsonl: table deal of pack book, business date 2026-11-01 from the .ready file, mode append (deal.ready)", said)
        self.assertFalse(self.lake.exists())

    def test_a_tar_of_two_tables_and_two_dates(self):
        (self.src / "feed.tar.gz").write_bytes(tar_bytes({
            "deal/2026-11-02/a.jsonl": lines([deal(i, "2026-11-02") for i in range(3)]),
            "deal/2026-11-03/a.jsonl": lines([deal(i, "2026-11-01") for i in range(3, 7)]),
            "position/2026-11-03/p.jsonl": lines([{"POS_ID": f"P{i}", "N": i} for i in range(2)]),
            "__MACOSX/deal/._a.jsonl": b"x"}))
        code, s, _ = self.run_build(pack=str(self.packs / "book"))
        self.assertEqual(code, 0, s.get("error"))
        self.assertEqual(sorted(s["tables"]), ["deal", "position"])
        self.assertEqual(s["tables"]["deal"]["rows"], {"2026-11-02": 3, "2026-11-03": 4})
        self.assertEqual(s["tables"]["deal"]["otherDateRows"], 4)
        self.assertEqual(s["tables"]["position"]["rows"], {"2026-11-03": 2})
        self.assertEqual(len(self.rows("position")), 2)
        code, s, _ = self.run_build(pack=str(self.packs / "book"), as_of="2026-11-01")
        self.assertIn("a .tar archive names its own tables and dates", s["error"])

    def test_a_bad_tar_is_refused_whole(self):
        (self.src / "feed.tar").write_bytes(tar_bytes({"deal/latest/a.jsonl": lines([deal(1, None)]),
                                                       "deal/2026-11-03/a.jsonl": lines([deal(2, None)]),
                                                       "nobody/2026-11-03/a.jsonl": lines([deal(3, None)])}, gz=False))
        code, s, _ = self.run_build(pack=str(self.packs / "book"))
        self.assertEqual(code, self.build.EXIT_FAILED)
        self.assertIn("feed.tar: refused, nothing was loaded", s["error"])
        self.assertIn("deal/latest/a.jsonl: the folder latest is not a date", s["error"])
        self.assertFalse((self.lake / "trading" / "deal").exists())


if __name__ == "__main__":
    unittest.main()
