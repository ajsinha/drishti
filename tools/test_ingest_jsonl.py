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

"""Unit tests of tools/ingest_jsonl.py: the files target (stdlib only) and, when deltalake is installed, the Delta target. Each file is one
kind and one business date (docs/architecture/DATA_LOAD_RULES.md): --as-of, else its .ready business-date, else its name's date, else the
business today; a document's own date field never decides."""
import json
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ingest_jsonl as IJ  # noqa: E402


def lines(*docs):
    return "".join(json.dumps(d) + "\n" for d in docs)


def args(src, **kw):
    a = IJ.build_parser().parse_args(["--from", str(src), "--domain", "dom", "--key", "id", "--date", "bd"])
    for k, v in kw.items():
        setattr(a, k, v)
    return a


QUIET = lambda *_: None  # noqa: E731


class IngestTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.t = pathlib.Path(self.tmp.name)
        self.src = self.t / "drop"
        self.src.mkdir()
        (self.src / "trade.2026-09-01.jsonl").write_text(lines({"id": "A", "bd": "2026-09-01"}, {"id": "B", "bd": "2026-09-01"}, {"id": "D"}) + "oops\n")
        (self.src / "trade.2026-09-02.jsonl").write_text(lines({"id": "C", "bd": "2026-09-02"}))
        self.root = self.t / "files"

    def tearDown(self):
        self.tmp.cleanup()

    def ids(self, day):
        f = self.root / "dom" / day / "trade.jsonl"
        return sorted(json.loads(x)["id"] for x in f.read_text().splitlines())

    def test_files_layout_idempotent_and_other_dates_untouched(self):
        r = IJ.ingest(args(self.src, store="files", root=self.root, mode="overwrite-dates"), QUIET)
        self.assertEqual((self.ids("2026-09-01"), self.ids("2026-09-02")), (["A", "B", "D"], ["C"]))
        self.assertEqual(len(r.bad), 1)
        row = json.loads((self.root / "dom/2026-09-01/trade.jsonl").read_text().splitlines()[0])
        self.assertEqual((row["domain"], row["kind"], row["date"], json.loads(row["doc"])["id"]), ("dom", "trade", "2026-09-01", "A"))
        again = self.t / "again" / "trade.2026-09-01.jsonl"       # one file, only 09-01, one id changed
        again.parent.mkdir()
        again.write_text(lines({"id": "A", "bd": "2026-09-01"}, {"id": "Z", "bd": "2026-09-01"}))
        a = args(again, store="files", root=self.root, mode="overwrite-dates")
        IJ.ingest(a, QUIET)
        IJ.ingest(a, QUIET)                   # idempotent
        self.assertEqual((self.ids("2026-09-01"), self.ids("2026-09-02")), (["A", "Z"], ["C"]))
        a.mode = None                         # the default: append
        IJ.ingest(a, QUIET)
        self.assertEqual(self.ids("2026-09-01"), ["A", "A", "Z", "Z"])
        a.mode = "replace"
        IJ.ingest(a, QUIET)
        self.assertFalse((self.root / "dom/2026-09-02/trade.jsonl").exists())

    def test_dry_run_writes_nothing_and_folder_input(self):
        r = IJ.ingest(args(self.src, store="files", root=self.root, dry_run=True), QUIET)
        self.assertEqual(sum(r.rows.values()), 4)
        self.assertFalse(self.root.exists())

    def test_the_business_date_is_from_outside_never_a_row(self):
        """--as-of > the .ready file > the name > the business today; a document's own date only counted; the kind is the name's table."""
        drop = self.t / "drop2"
        drop.mkdir()
        (drop / "trade.2026-11-03.jsonl").write_text(lines({"id": "A", "bd": "2026-11-03"}, {"id": "B", "bd": "20261102"}, {"id": "C"}))
        said = []
        r = IJ.ingest(args(drop, store="files", root=self.root), said.append)
        self.assertEqual(self.ids("2026-11-03"), ["A", "B", "C"])
        self.assertEqual(r.other_dates, {str(drop / "trade.2026-11-03.jsonl"): 1})
        self.assertIn("1 row of trade.2026-11-03.jsonl carried another date in bd; loaded under 2026-11-03", said)
        IJ.ingest(args(drop, store="files", root=self.root, as_of="2026-10-01"), QUIET)
        self.assertEqual(self.ids("2026-10-01"), ["A", "B", "C"])
        (drop / "trade.2026-11-03.jsonl.ready").write_text("business-date: 2026-10-02\n")
        IJ.ingest(args(drop, store="files", root=self.root), QUIET)
        self.assertEqual(self.ids("2026-10-02"), ["A", "B", "C"])
        (drop / "trade.2026-11-03.jsonl.ready").unlink()
        (drop / "trade.2026-11-03.jsonl").rename(drop / "trade.jsonl")
        IJ.ingest(args(drop, store="files", root=self.root), QUIET)
        self.assertEqual(self.ids(IJ.business_today().isoformat()), ["A", "B", "C"])
        with self.assertRaises(SystemExit) as e:
            IJ.ingest(args(drop, store="files", root=self.root, date_from="name"), QUIET)
        self.assertIn("date-from name, but the name carries no date", str(e.exception))

    def test_delta_overwrite_dates(self):
        try:
            import deltalake
        except ImportError:
            self.skipTest("deltalake not installed")
        lake = self.t / "lake"
        IJ.ingest(args(self.src, lake=lake, mode="overwrite-dates"), QUIET)
        again = self.t / "again2" / "trade.2026-09-01.jsonl"
        again.parent.mkdir()
        again.write_text(lines({"id": "X", "bd": "2026-09-01"}))
        a = args(again, lake=lake, mode="overwrite-dates")
        IJ.ingest(a, QUIET)
        IJ.ingest(a, QUIET)
        rows = deltalake.DeltaTable(str(lake / "dom" / "trade")).to_pyarrow_table().to_pylist()
        got = sorted((str(r["business_date"]), r["id"]) for r in rows)
        self.assertEqual(got, [("2026-09-01", "X"), ("2026-09-02", "C")])


if __name__ == "__main__":
    unittest.main()
