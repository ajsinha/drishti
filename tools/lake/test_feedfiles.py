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
"""feedfiles.py: our own feed files by the load rules (DATA_LAKE_GUIDE.md section 5.2), written deterministically and read back the way
lake build reads them."""
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import archives  # noqa: E402
import feedfiles as FF  # noqa: E402
import filedate  # noqa: E402


def gz(*lines):
    return FF.gz_bytes(list(lines))


class FeedFilesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_one_file_a_date_is_a_dated_name_each_the_rules_accept(self):
        files = FF.write_table(self.dir / "t", "risk-hierarchy", {"2026-09-29": [("cob.jsonl.gz", gz('{"a":1}'))],
                                                                  "2026-09-30": [("cob.jsonl.gz", gz('{"a":2}', '{"a":3}'))]})
        self.assertEqual([f.name for f in files], ["risk-hierarchy.2026-09-29.jsonl.gz", "risk-hierarchy.2026-09-30.jsonl.gz"])
        for f in files:
            n = filedate.parse(f.name)
            self.assertEqual((n.table, n.error), ("risk-hierarchy", None))
        self.assertEqual(list(FF.lines(self.dir / "t")), [("2026-09-29", '{"a":1}'), ("2026-09-30", '{"a":2}'), ("2026-09-30", '{"a":3}')])

    def test_a_date_in_several_files_is_a_tar_of_date_folders_lake_build_reads(self):
        days = {"2026-09-30": [("cob.part-001.jsonl.gz", gz('{"a":1}')), ("stress-cob.jsonl.gz", gz('{"a":9}'))]}
        files = FF.write_table(self.dir / "t", "pnl-vector", days, tar="date")
        self.assertEqual([f.name for f in files], ["pnl-vector-2026-09-30.tar"])
        plan = archives.plan(files[0], layout="feed")
        self.assertEqual(sorted(plan.datasets), ["pnl-vector"])
        self.assertEqual([m.name for m in plan.datasets["pnl-vector"]],
                         ["pnl-vector/2026-09-30/cob.part-001.jsonl.gz", "pnl-vector/2026-09-30/stress-cob.jsonl.gz"])
        got = FF.read_table(self.dir / "t")
        self.assertEqual([p.label.rsplit("/", 1)[-1] for p in got["2026-09-30"]], ["cob.part-001.jsonl.gz", "stress-cob.jsonl.gz"])
        # a date with several files and no tar asked for: one tar of every date
        self.assertEqual([f.name for f in FF.write_table(self.dir / "u", "fx-scenario", days)], ["fx-scenario.tar"])

    def test_the_same_rows_write_the_same_bytes_and_earlier_files_go(self):
        days = {"2026-09-30": [("a.jsonl.gz", gz('{"x":1}')), ("b.jsonl.gz", gz('{"x":2}'))]}
        one = FF.write_table(self.dir / "t", "desk-pnl", days, tar="table")[0].read_bytes()
        (self.dir / "t" / "cob-2026-09-30.jsonl.gz").write_bytes(gz("{}"))          # an older name: removed on the next write
        two = FF.write_table(self.dir / "t", "desk-pnl", days, tar="table")[0].read_bytes()
        self.assertEqual(one, two)
        self.assertEqual(sorted(f.name for f in (self.dir / "t").iterdir()), ["desk-pnl.tar"])

    def test_a_streamed_tar_reads_as_the_written_one(self):
        src = self.dir / "parts"
        src.mkdir()
        for i in (1, 2):
            (src / f"part-{i:03d}.jsonl.gz").write_bytes(gz(f'{{"p":{i}}}'))
        out = FF.pack_tar(self.dir / "pnl-vector-2026-09-30.tar", "pnl-vector", {"2026-09-30": sorted(src.iterdir())})
        self.assertEqual([x for _d, x in FF.lines(out)], ['{"p":1}', '{"p":2}'])
        self.assertEqual(list(src.iterdir()), [], "the parts removed once packed")

    def test_rows_of_several_dates_stream_as_a_tar_of_table_and_date_folders(self):
        """A store loader's standard input (lake build's Iceberg writer, stores.copy): each row in its own date's folder, in chunks."""
        import io
        import tarfile
        buf = io.BytesIO()
        out = FF.RowsTarStream(buf, chunk_rows=2)
        for i, day in enumerate(["2026-09-30", "2026-09-29", "2026-09-30", "2026-09-30"]):
            out.add("trade", day, f'{{"id":"T-{i}","date":"{day}"}}')
        out.close()
        buf.seek(0)
        with tarfile.open(fileobj=buf) as t:
            got = {m.name: t.extractfile(m).read().decode().splitlines() for m in t.getmembers()}
        self.assertEqual(sorted(got), ["trade/2026-09-29/part-00001.jsonl", "trade/2026-09-30/part-00001.jsonl", "trade/2026-09-30/part-00002.jsonl"])
        self.assertEqual(len(got["trade/2026-09-30/part-00001.jsonl"]), 2)
        self.assertTrue(all(f'"date":"{n.split("/")[1]}"' in line for n, lines in got.items() for line in lines))
        with self.assertRaises(ValueError):
            FF.RowsTarStream(io.BytesIO()).add("a.b", "2026-09-30", "{}")

    def test_a_file_without_a_date_or_with_a_bad_name_is_refused(self):
        (self.dir / "t").mkdir()
        (self.dir / "t" / "trades.jsonl.gz").write_bytes(gz("{}"))
        with self.assertRaises(ValueError):
            FF.read_table(self.dir / "t")
        with self.assertRaises(ValueError):
            FF.plain_name("a.b", "2026-09-30")
        with self.assertRaises(ValueError):
            FF.tar_name("pnl-vector", "20260930")


if __name__ == "__main__":
    unittest.main()
