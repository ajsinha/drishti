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

"""QA1-15: `drishti.py history` prints the market headline in the chart field's format (price2: two places) and the change in percent
with two, not `chg 1.09 (5.25301205%)  avg 21.2912818`. Stdlib only.

    python3 -m unittest -q tools/test_historycmd.py
"""
from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("historycmd", Path(__file__).resolve().parent / "historycmd.py")
H = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(H)

ANSWER = {"ref": {"kind": "equity", "id": "EQ-CSCA"}, "mnemonic": "EQ", "window": {"from": "2026-07-20", "to": "2026-10-20"},
          "columns": [{"path": "PRICE", "label": "Price", "format": "price2"}], "parts": [],
          "chart": [{"path": "PRICE", "label": "Price", "format": "price2"}],
          "summary": {"field": "PRICE", "last": 21.84, "change": 1.0904, "changePct": 5.25301205, "high": 22.1, "highDate": "2026-10-01",
                      "low": 20.3, "lowDate": "2026-08-03", "range": 1.8, "average": 21.2912818},
          "rows": [{"date": "2026-10-20", "values": {"PRICE": 21.84}, "display": {"PRICE": "21.84"}, "changed": []}]}


class HistoryHeadline(unittest.TestCase):
    def lines(self, answer):
        out = []
        H.render(answer, out.append)
        return out

    def test_the_headline_is_in_the_chart_fields_format(self):
        head = next(line for line in self.lines(ANSWER) if line.startswith("PRICE:"))
        self.assertEqual(head, "PRICE: last 21.84  chg +1.09 (+5.25%)  high 22.10 (2026-10-01)  low 20.30 (2026-08-03)  range 1.80  avg 21.29")

    def test_a_field_with_four_places_and_a_missing_number(self):
        answer = {**ANSWER, "chart": [{"path": "PRICE", "format": "price4"}], "summary": {**ANSWER["summary"], "average": None}}
        head = next(line for line in self.lines(answer) if line.startswith("PRICE:"))
        self.assertIn("last 21.8400", head)
        self.assertIn("avg —", head)


if __name__ == "__main__":
    unittest.main()
