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

"""Tests of `drishti.py convert` (tools/convertcmd.py): CSV, a JSON array, one object, JSON Lines, gzip and a folder of per-entity
.json files into JSON Lines; the file connector's reading of a CSV feed kept with --rows-document."""
from __future__ import annotations

import contextlib
import gzip
import io
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import convertcmd as C  # noqa: E402


def lines(p: pathlib.Path) -> list[dict]:
    opener = gzip.open if p.name.endswith(".gz") else open
    with opener(p, "rt", encoding="utf-8") as f:
        return [json.loads(x) for x in f]


class Convert(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.d = pathlib.Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def run_cli(self, *args) -> subprocess.CompletedProcess:
        return subprocess.run([sys.executable, str(HERE / "drishti.py"), "convert", *map(str, args)], capture_output=True, text=True, timeout=120)

    def test_csv_rows_become_typed_lines(self):
        src = self.d / "deals.csv"
        src.write_text('TRADE_ID,NOTIONAL,LIVE,NOTE,QTY\nT-1,1000.5,true,"a, b",3\nT-2,,false,,-2\n\n', encoding="utf-8")
        r = self.run_cli(src)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(lines(self.d / "deals.jsonl"), [{"TRADE_ID": "T-1", "NOTIONAL": 1000.5, "LIVE": True, "NOTE": "a, b", "QTY": 3},
                                                          {"TRADE_ID": "T-2", "NOTIONAL": None, "LIVE": False, "NOTE": None, "QTY": -2}])

    def test_a_csv_feed_as_the_file_connector_served_it(self):
        src = self.d / "SOFR-HISTORY.csv"
        src.write_text("date,rate,volume_bn\n2026-09-01,3.95,1910\n", encoding="utf-8")
        C.convert(src, str(self.d / "fixing.jsonl"), rows_document=True, say=lambda m: None)
        self.assertEqual(lines(self.d / "fixing.jsonl"), [{"id": "SOFR-HISTORY", "rows": [{"date": "2026-09-01", "rate": 3.95, "volume_bn": 1910}]}])

    def test_a_json_array_one_object_and_gzip(self):
        arr = self.d / "book.json"
        arr.write_text(json.dumps([{"id": "A", "LEGS": [{"N": 1}]}, {"id": "B"}], indent=2), encoding="utf-8")
        C.convert(arr, str(self.d / "out" / "book.jsonl.gz"), say=lambda m: None)
        self.assertEqual(lines(self.d / "out" / "book.jsonl.gz"), [{"id": "A", "LEGS": [{"N": 1}]}, {"id": "B"}])
        one = self.d / "one.json"
        one.write_text('{\n  "id": "X",\n  "v": 2\n}\n', encoding="utf-8")
        C.convert(one, None, say=lambda m: None)
        self.assertEqual(lines(self.d / "one.jsonl"), [{"id": "X", "v": 2}])
        packed = self.d / "packed.json.gz"
        with gzip.open(packed, "wt", encoding="utf-8") as f:
            f.write(json.dumps([{"id": 1}, {"id": 2}]))
        C.convert(packed, None, say=lambda m: None)
        self.assertEqual(lines(self.d / "packed.jsonl"), [{"id": 1}, {"id": 2}])

    def test_a_folder_of_per_entity_files_keeps_their_ids(self):
        kind = self.d / "settlement"
        kind.mkdir()
        (kind / "CL-DEC26.json").write_text('{"settle": 71.15}', encoding="utf-8")
        (kind / "CL-JAN27.json").write_text('{"id": "own", "settle": 70.1}', encoding="utf-8")
        C.convert(kind, None, say=lambda m: None)
        self.assertEqual(lines(self.d / "settlement.jsonl"), [{"id": "CL-DEC26", "settle": 71.15}, {"id": "own", "settle": 70.1}])

    def test_standard_output_and_refusals(self):
        src = self.d / "a.json"
        src.write_text('[{"x": 1}]', encoding="utf-8")
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            C.convert(src, "-")
        self.assertEqual(out.getvalue(), '{"x":1}\n')
        C.convert(src, None, say=lambda m: None)
        with self.assertRaises(ValueError):                    # an existing output is replaced only with --force
            C.convert(src, None, say=lambda m: None)
        C.convert(src, None, force=True, say=lambda m: None)
        with self.assertRaises(ValueError):
            C.convert(self.d / "a.jsonl", str(self.d / "a.jsonl"), force=True)
        bad = self.d / "bad.ndjson"
        bad.write_text('{"x": 1}\nnot json\n', encoding="utf-8")
        r = self.run_cli(bad)
        self.assertEqual(r.returncode, 1)
        self.assertIn("bad.ndjson:2", r.stderr)
        self.assertEqual(self.run_cli(self.d / "missing.csv").returncode, 2)
        self.assertFalse((self.d / "bad.jsonl.part").exists() or (self.d / "bad.jsonl").exists())


if __name__ == "__main__":
    unittest.main()
