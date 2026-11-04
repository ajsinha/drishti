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

"""The business date in a file's name (DATA_GUIDE.md section 2.8): the shared cases (testdata/file-dates/names.json, which the Java
FileDatesTest reads too), then lake build end to end on a small lake: plain, .gz, .zip (a member's own date first) and .tar.gz files,
the rows that carried another date counted and named, --as-of over the name, --date-from field ignoring it.

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


class SharedCasesTest(unittest.TestCase):

    def test_names(self):
        for c in CASES["names"]:
            with self.subTest(name=c["name"]):
                got = filedate.of(c["name"])
                self.assertEqual(got.isoformat() if got else None, c["date"])

    def test_archive_members(self):
        for c in CASES["entries"]:
            with self.subTest(archive=c["archive"], entry=c["entry"]):
                got = filedate.of_member(c["archive"], c["entry"])
                self.assertEqual(got.isoformat() if got else None, c["date"])

    def test_why_a_name_has_no_date(self):
        self.assertIn("is not a calendar date", filedate.find("abc 20261340.jsonl")[1])
        self.assertIn("not at the end", filedate.find("abc 20261103 v2.jsonl")[1])
        self.assertIn("no YYYYMMDD", filedate.find("abc.jsonl")[1])
        self.assertIsNone(filedate.find("abc 20261103.jsonl")[1])

    def test_note_words(self):
        self.assertEqual(filedate.other_date_note(1, "a 20261103.jsonl", "TRADE_DATE", date(2026, 11, 3)),
                         "1 row of a 20261103.jsonl carried another date in TRADE_DATE; loaded under 2026-11-03 from the file name")


try:
    import deltalake  # noqa: F401
    HAVE_LAKE = True
except ImportError:
    HAVE_LAKE = False


def deal(i: int, day: str) -> dict:
    return {"TRADE_ID": 1000 + i, "TRADE_DATE": day, "DESK": ["RATES", "FX"][i % 2], "NOTIONAL": 10.0 * i}


def lines(docs) -> str:
    return "".join(json.dumps(d) + "\n" for d in docs)


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
        os.environ["DRISHTI_BI_STORE"] = str(self.root / "bi")

    def tearDown(self):
        os.environ.pop("DRISHTI_BI_STORE", None)
        self.tmp.cleanup()

    def run_build(self, **kw):
        a = self.build.build_parser().parse_args([])
        a.quiet = True
        d = dict(src=[str(self.src)], lake=str(self.lake), domain="trading", kind="deal", key="TRADE_ID", date="TRADE_DATE", engine="deltalake",
                 no_stamp=True)
        d.update(kw)
        for k, v in d.items():
            setattr(a, k, v)
        said = []
        code, s = self.build.run(a, said.append)
        return code, s, said

    def files(self):
        # a plain file dated by its name: 3 of its 10 rows carry another date in TRADE_DATE
        (self.src / "deals 20261103.jsonl").write_text(lines([deal(i, "2026-11-03" if i >= 3 else "2026-11-02") for i in range(10)]))
        # a .gz dated by its name, every row agreeing
        with gzip.open(self.src / "deals_2026-11-04.jsonl.gz", "wt", encoding="utf-8") as fh:
            fh.write(lines([deal(i, "2026-11-04") for i in range(10, 15)]))
        # a .zip dated 2026-11-05 by its name; one member carries its own date (it wins), the other none (the archive's)
        with zipfile.ZipFile(self.src / "deals 20261105.zip", "w") as z:
            z.writestr("day 20261106.jsonl", lines([deal(i, "2026-11-01") for i in range(20, 24)]))
            z.writestr("rest.jsonl", lines([deal(i, "2026-11-05") for i in range(30, 33)]))
        # a .tar.gz dated by its name
        buf = io.BytesIO()
        with tarfile.open(fileobj=buf, mode="w:gz") as t:
            data = lines([deal(i, "2026-11-07") for i in range(40, 42)]).encode()
            info = tarfile.TarInfo("part.jsonl")
            info.size = len(data)
            t.addfile(info, io.BytesIO(data))
        (self.src / "deals.20261107.tar.gz").write_bytes(buf.getvalue())
        # no date in the name: each row's own date
        (self.src / "deals.jsonl").write_text(lines([deal(i, "2026-10-30") for i in range(50, 52)]))

    def test_name_dates_are_enforced_and_disagreements_counted(self):
        self.files()
        code, s, said = self.run_build(quiet=False)
        self.assertEqual(code, 0, s.get("error"))
        self.assertEqual(s["dateFrom"], "auto")
        self.assertEqual(s["rows"], {"2026-10-30": 2, "2026-11-03": 10, "2026-11-04": 5, "2026-11-05": 3, "2026-11-06": 4, "2026-11-07": 2})
        by_file = {f["file"]: (f["date"], f["otherDateRows"]) for f in s["fileDates"]}
        self.assertEqual(by_file, {"deals 20261103.jsonl": ("2026-11-03", 3), "deals_2026-11-04.jsonl.gz": ("2026-11-04", 0),
                                   "deals 20261105.zip!/day 20261106.jsonl": ("2026-11-06", 4),
                                   "deals 20261105.zip!/rest.jsonl": ("2026-11-05", 0), "deals.20261107.tar.gz!/part.jsonl": ("2026-11-07", 0)})
        self.assertEqual(s["otherDateRows"], 7)
        self.assertIn("3 rows of deals 20261103.jsonl carried another date in TRADE_DATE; loaded under 2026-11-03 from the file name",
                      s["dateNotes"])
        self.assertTrue(any("3 rows of deals 20261103.jsonl carried another date" in x for x in said))
        self.assertTrue(any("business date 2026-11-03 from the name of deals 20261103.jsonl" in x for x in said))
        from deltalake import DeltaTable
        rows = DeltaTable(str(self.lake / "trading" / "deal")).to_pyarrow_table().to_pylist()
        self.assertEqual(len(rows), 26)
        r = next(x for x in rows if x["id"] == "1000")
        self.assertEqual((str(r["business_date"]), json.loads(r["doc"])["TRADE_DATE"]), ("2026-11-03", "2026-11-02"))   # the document kept as sent

    def test_as_of_wins_over_the_name(self):
        self.files()
        code, s, _ = self.run_build(as_of="2026-12-01")
        self.assertEqual(code, 0, s.get("error"))
        self.assertEqual(s["rows"], {"2026-12-01": 26})
        self.assertEqual((s["fileDates"], s["otherDateRows"], s["dateNotes"]), ([], 0, []))

    def test_date_from_field_ignores_names(self):
        self.files()
        code, s, _ = self.run_build(date_from="field")
        self.assertEqual(code, 0, s.get("error"))
        self.assertEqual(s["rows"], {"2026-10-30": 2, "2026-11-01": 4, "2026-11-02": 3, "2026-11-03": 7, "2026-11-04": 5, "2026-11-05": 3,
                                     "2026-11-07": 2})
        self.assertEqual(s["fileDates"], [])

    def test_no_date_field_needs_a_name_date(self):
        (self.src / "deals 20261103.jsonl").write_text(lines([deal(i, "x") for i in range(3)]))
        (self.src / "deals.jsonl").write_text(lines([deal(i, "x") for i in range(3, 5)]))
        code, s, _ = self.run_build(date=None)
        self.assertEqual(code, self.build.EXIT_PARTIAL, s.get("error"))
        self.assertEqual(s["rows"], {"2026-11-03": 3})
        self.assertEqual(s["rejects"]["byReason"], {"no business date (no date field, and the file name carries no date)": 2})
        code, s, _ = self.run_build(date=None, date_from="field")
        self.assertEqual(code, self.build.EXIT_FAILED)
        self.assertIn("give --date FIELD", s["error"])


if __name__ == "__main__":
    unittest.main()
