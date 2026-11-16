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

"""`pack new`'s analysis reads every record (tools/packgen/packprofile.py): no Java needed.

    python3 -m pytest tools/test_pack_new.py -q
"""
from __future__ import annotations

import argparse
import gzip
import json
import pathlib
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE / "packgen"))
import module_from_jsonl as MJ  # noqa: E402

PFJ = MJ.PFJ


def opts(inputs, **kw) -> argparse.Namespace:
    a = MJ.build_parser().parse_args([*map(str, inputs), "--name", "t"])
    for k, v in kw.items():
        setattr(a, k, v)
    return a


def quiet(*_):
    pass


class EveryRecord(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = pathlib.Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_a_combination_in_one_row_near_the_end_of_a_large_file_gets_its_sutra(self):
        n = 120_000
        f = self.dir / "trade.2026-11-02.jsonl.gz"
        with gzip.open(f, "wt", encoding="utf-8") as out:
            for i in range(n):
                pt, ss = ("RARE_SWAP", "ENDUR") if i == n - 7 else (["IRS", "FRA", "FXO"][i % 3], ["MUREX", "CALYPSO"][i % 2])
                doc = {"TRADE_ID": f"T{i}", "PRODUCT_TYPE": pt, "SOURCE_SYSTEM": ss, "NOTIONAL": i * 1.5}
                if i == n - 3:
                    doc["LATE_FIELD"] = {"NESTED": True}                 # a field seen once, near the end
                out.write(json.dumps(doc) + "\n")
        an = PFJ.analyse(opts([f], kind="trade", key="TRADE_ID", match="PRODUCT_TYPE,SOURCE_SYSTEM", workers=1), quiet)
        kp = an.prof.kinds["trade"]
        self.assertEqual(n, an.prof.rows)
        self.assertEqual(n, kp.rows)
        groups = {g.name: g for g in an.groups}
        self.assertIn("trade-rare-swap-endur", groups)
        self.assertEqual(1, groups["trade-rare-swap-endur"].count)
        self.assertEqual("T119993", groups["trade-rare-swap-endur"].samples[0]["TRADE_ID"])
        self.assertEqual(7, len(groups))                               # 6 regular combinations and the rare one
        self.assertEqual(n - 1, sum(g.count for g in an.groups if g.name != "trade-rare-swap-endur"))
        self.assertIn("LATE_FIELD.NESTED", kp.fields)
        self.assertEqual({"boolean": 1}, kp.fields["LATE_FIELD.NESTED"].types)
        self.assertEqual({"number": n}, kp.fields["NOTIONAL"].types)
        self.assertTrue(kp.unique())
        self.assertEqual({"2026-11-02": n}, dict(kp.dates))
        self.assertTrue(all(len(g.samples) <= 5 for g in an.groups))   # only what is shown is a few

    def test_duplicates_per_business_date_across_files_and_min_docs(self):
        (self.dir / "trade.2026-11-02.jsonl").write_text("".join(json.dumps({"ID": f"A{i}", "T": "X"}) + "\n" for i in range(5)))
        (self.dir / "trade.2026-11-03.ndjson").write_text("".join(json.dumps({"ID": f"A{i}", "T": "X"}) + "\n" for i in range(5)))  # next day: fine
        (self.dir / "more.2026-11-03.jsonl").write_text(json.dumps({"ID": "A1", "T": "Y"}) + "\n")      # the same day again: a duplicate
        for workers in (1, 3):
            an = PFJ.analyse(opts([self.dir], kind="trade", key="ID", match="T", min_docs=2, workers=workers), quiet)
            kp = an.prof.kinds["trade"]
            self.assertEqual(11, kp.rows)
            self.assertEqual(1, kp.dup_rows, workers)
            self.assertEqual(["trade-x"], [g.name for g in an.groups])
            self.assertEqual([(("Y",), 1)], an.skipped["trade"])
            self.assertEqual({"2026-11-02": 5, "2026-11-03": 6}, dict(kp.dates))

    def test_inference_from_the_first_rows_names_its_reasons(self):
        f = self.dir / "position.jsonl"
        f.write_text("".join(json.dumps({"POSITION_ID": f"P{i}", "BUSINESS_DATE": f"2026-11-0{1 + i % 3}", "TRADE_DATE": "2026-01-01",
                                         "ASSET_CLASS": ["RATES", "FX", "CREDIT"][i % 3], "QTY": i}) + "\n" for i in range(60)))
        an = PFJ.analyse(opts([f], key=None, date=None, match=None, workers=1), quiet)
        self.assertEqual("POSITION_ID", an.keys["position"])
        self.assertEqual("BUSINESS_DATE", an.dates["position"])         # named as a business date; TRADE_DATE is not one
        self.assertEqual(["ASSET_CLASS"], an.match["position"])
        self.assertEqual(3, len(an.why["position"]))
        an = PFJ.analyse(opts([f], key="POSITION_ID", date="none", match="none", workers=1), quiet)
        self.assertNotIn("position", an.dates)
        self.assertEqual(["position-default"], [g.name for g in an.groups])

    def test_contract_types_and_presence_come_from_every_row(self):
        f = self.dir / "deal.jsonl"
        rows = [{"ID": "D1", "AMT": 1.5, "ON": "2026-11-02", "AT": "2026-11-02T10:00:00Z", "LEGS": [{"R": 1}]},
                {"ID": "D2", "AMT": 2.0, "ON": "2026-11-03", "AT": "2026-11-03T10:00:00Z", "LEGS": [], "OPT": "x"}]
        f.write_text("".join(json.dumps(r) + "\n" for r in rows))
        an = PFJ.analyse(opts([f], key="ID", match="none", workers=1), quiet)
        c = PFJ.contract("deal", an.prof.kinds["deal"], "DEA", "t", "ID", None)
        by = {x["name"]: x for x in c["fields"]}
        self.assertEqual(("id", "amount", "date", "timestamp", "record list"), tuple(by[k]["type"] for k in ("ID", "AMT", "ON", "AT", "LEGS")))
        self.assertEqual("optional", by["OPT"]["presence"])
        self.assertEqual("required", by["AMT"]["presence"])
        sys.path.insert(0, str(HERE))
        import contracts
        self.assertEqual([], contracts.schema_problems(c))


if __name__ == "__main__":
    unittest.main()
