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

"""How a figure of the server's pivot answer is shown (drishti-grid's measureText, the Pivot screen's pivotFormat; PIVOT_GUIDE.md), driven
only by the answer's own fields (a text value, the measure's unit and show-as); it formats and computes nothing. Run in Node, no browser:
the drawn grid is test_pivot_screen_browser.py's. The grid sets text, never markup, so a text answer is shown as it is."""
from __future__ import annotations

import json
import shutil
import subprocess
from pathlib import Path

import pytest

GRID_JS = Path(__file__).resolve().parents[1] / "web" / "static" / "elements" / "drishti-grid" / "drishti_grid.js"
needs_node = pytest.mark.skipif(shutil.which("node") is None, reason="needs Node to run drishti-grid's bundle")

CASES = [
    ("a<b, Ann & Bo", {"id": "string_agg(TRADER)", "unit": ""}, "a<b, Ann & Bo"),          # string_agg: the text (drawn as text)
    ("Murex", {"id": "mode(SYSTEM)", "unit": ""}, "Murex"),                                 # the mode of names
    (3.0, {"id": "sum(NOTIONAL)", "unit": "money", "showAs": "ntile:4"}, "3"),              # an ntile bucket: a whole number
    (2.0, {"id": "sum(NOTIONAL)", "unit": "money", "showAs": "rank"}, "2"),
    (12345.0, {"id": "nmiss(PRICE)", "unit": "count"}, "12,345"),                           # unit count
    (-4.0, {"id": "count", "unit": "count", "showAs": "diff:1:MONTH"}, "-4"),               # a difference of counts
    (0.1234, {"id": "sum(NOTIONAL)", "unit": "money", "showAs": "pct-grand"}, "12.34%"),
    (0.5, {"id": "corr(A,B)", "unit": ""}, "0.50"),                                         # a pure number keeps its decimals
    (2.5e9, {"id": "sum(NOTIONAL)", "unit": "money"}, "2.50 bn"),
    (7.0, {"id": "count(TRADE_ID)"}, "7"),                                                  # an answer without units: a count by its function
    (None, {"id": "mode(SYSTEM)", "unit": ""}, ""),
]


@needs_node
def test_fmt_shows_text_as_text_and_whole_numbers_without_decimals():
    script = ("const f = require(process.argv[1]).measureText;"
              "console.log(JSON.stringify(JSON.parse(process.argv[2]).map((c) => f(c[0], c[1]))));")
    run = subprocess.run(["node", "-e", script, str(GRID_JS), json.dumps([[v, m] for v, m, _ in CASES])],
                         capture_output=True, text=True, timeout=30)
    assert run.returncode == 0, run.stderr
    assert json.loads(run.stdout) == [want for _, _, want in CASES]
