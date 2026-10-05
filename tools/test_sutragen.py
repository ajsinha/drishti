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

"""Unit tests of tools/sutragen.py (no JVM): reading, grouping, quoting, sampling and the YAML edit."""
import json
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import sutragen as SG  # noqa: E402


class Opts:
    key, date, match, skip_missing, samples, min_docs, priority = "tradeId", None, "kind=a", False, 2, 1, 10
    fallback, name_prefix = True, ""


class SutragenTest(unittest.TestCase):
    def test_literal_quoting(self):
        self.assertEqual(SG.literal("it's"), "'it\\'s'")
        self.assertEqual(SG.literal("a\\b"), "'a\\\\b'")
        self.assertEqual((SG.literal(5), SG.literal(True), SG.literal(None), SG.literal(1.5)), ("5", "true", "null", "1.5"))

    def test_options(self):
        self.assertEqual(SG.parse_scalar("trade=tradeId,cp=cpId"), (None, {"trade": "tradeId", "cp": "cpId"}))
        self.assertEqual(SG.parse_scalar("id"), ("id", {}))
        self.assertEqual(SG.parse_match("a,b"), (["a", "b"], {}))
        self.assertEqual(SG.parse_match("t=a,b;c=x"), ([], {"t": ["a", "b"], "c": ["x"]}))

    def test_envelope_file_and_folder_inputs(self):
        with tempfile.TemporaryDirectory() as t:
            t = pathlib.Path(t)
            (t / "trade.jsonl").write_text('{"tradeId":"T1","a":"x"}\n{"tradeId":"T2","a":"y"}\nnot json\n')
            (t / "mixed.jsonl").write_text(json.dumps({"kind": "book", "id": "B1", "doc": json.dumps({"id": "B1"})}) + "\n")
            bad = []
            self.assertEqual(sorted(SG.load_folder(t)), ["book", "trade"])
            one = SG.load_folder([t / "trade.jsonl"], kind="deal")
            self.assertEqual(list(one), ["deal"])
            self.assertEqual(len(one["deal"]), 2)
            self.assertEqual(len(list(SG.read_records([t / "trade.jsonl"], bad.append))), 2)
            self.assertIn("not JSON", bad[0])

    def test_groups_where_and_samples(self):
        docs = [{"tradeId": f"T{i}", "a": "x" if i % 2 else "it's", "d": f"2026-09-{10 + i % 3}"} for i in range(6)]
        docs.append({"tradeId": "T9"})
        o = Opts()
        o.match, o.date = "a", "d"
        groups = SG.build_groups({"kind": docs}, o)
        wheres = sorted(g.where for g in groups)
        self.assertEqual(wheres, ["", "$.a == 'it\\'s'", "$.a == 'x'", "$.a == null"])
        self.assertTrue(all(len(g.samples) <= 2 for g in groups))
        o.match = "d"       # the date field is never part of the match
        self.assertEqual({g.where for g in SG.build_groups({"kind": docs}, o)}, {""})

    def test_pick_spans_dates_recent_first(self):
        docs = [{"id": f"I{i}", "d": f"2026-09-{10 + i}"} for i in range(5)]
        self.assertEqual([d["d"] for d in SG.pick(docs, 2, "id", "d")], ["2026-09-14", "2026-09-13"])

    def test_edit_yaml(self):
        g = SG.Group("trade", ["a"], ("x",), [])
        g.name, g.priority, g.key = "trade-x", 10, "tradeId"
        text = 'rachana: 1\nsutra: trade-auto\ndescription: d\nmatch: { kind: trade, priority: 1 }\ntitle: { pill: "T", id: "$.id" }\n'
        out = SG.edit_yaml(text, g)
        self.assertIn("sutra: trade-x", out)
        self.assertIn('match: { kind: trade, where: "$.a == \'x\'", priority: 10 }', out)
        self.assertIn('id: "$.tradeId"', out)


if __name__ == "__main__":
    unittest.main()
