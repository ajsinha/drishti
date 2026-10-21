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

"""The data-as-of line of a report and its visuals (2.6.7 patch; docs/guides/BI_GUIDE.md, "Which date a report reads"): each table reads its latest date on
or before the asked one, the answer's meta.dataAsOf says each table's date, and the line names them when they differ. The browser code runs in Node (skipped
without it); the colours are the theme's own warning token in every theme."""
import json
import re
import shutil
import subprocess
from pathlib import Path

import pytest

STATIC = Path(__file__).resolve().parent.parent / "web" / "static"
node = shutil.which("node")
needs_node = pytest.mark.skipif(node is None, reason="needs Node to run the shared report code")


def line(meta, asked=""):
    prelude = ("global.window = { drishtiBi: { h: () => ({}), visuals: {}, fmt: (v) => String(v), compact: (v) => String(v), label: (x) => x, clear: (x) => x, column: () => null } };"
               "require(%s); const V = window.drishtiBi.visuals;" % json.dumps(str(STATIC / "js" / "bi-visuals.js")))
    out = subprocess.run([node, "-e", prelude + "console.log(JSON.stringify(V.asOfLine(%s, %s)))" % (json.dumps(meta), json.dumps(asked))],
                         capture_output=True, text=True, timeout=30)
    assert out.returncode == 0, out.stderr
    return json.loads(out.stdout)


@needs_node
def test_tables_at_different_dates_are_each_named_with_the_asked_date():
    meta = {"dataAsOf": {"asked": "2026-10-21", "tables": {"trades": "2026-10-16", "prices": "2026-10-20"}, "aligned": False}, "dates": ["2026-10-16", "2026-10-20"]}
    assert line(meta) == "Data as of: prices 2026-10-20, trades 2026-10-16 (asked 2026-10-21)"


@needs_node
def test_one_date_older_than_the_asked_one_says_there_is_no_later_data():
    meta = {"dataAsOf": {"asked": "2026-10-21", "tables": {"trades": "2026-10-16", "counterparties": "2026-10-16"}, "aligned": True}, "dates": ["2026-10-16"]}
    assert line(meta) == "Data as of 2026-10-16 (asked 2026-10-21: no later data)"
    assert line({"dataAsOf": {"asked": None, "tables": {"trades": "2026-10-16"}}, "dates": ["2026-10-16"]}, "2026-10-21") == \
        "Data as of 2026-10-16 (asked 2026-10-21: no later data)", "the page's date when the answer does not say it"
    assert line({"dataAsOf": {"asked": "2026-10-16", "tables": {"trades": "2026-10-16"}}, "dates": ["2026-10-16"]}) == "Data as of 2026-10-16"


@needs_node
def test_an_answer_without_table_dates_reads_its_last_date_and_nothing_dated_says_nothing():
    assert line({"dates": ["2026-10-15", "2026-10-16"]}, "2026-10-16") == "Data as of 2026-10-16"
    assert line({"dates": []}) == ""


def test_the_report_and_its_visuals_show_the_line():
    report = (STATIC / "js" / "bi-report.js").read_text(encoding="utf-8")
    frame = (STATIC / "js" / "bi-frame.js").read_text(encoding="utf-8")
    consume = (STATIC / "js" / "bi-consume.js").read_text(encoding="utf-8")
    assert "B.visuals.asOfLine(" in report and "data-asof-aligned" in report
    assert "bi-asof-foot" in frame and "dataAsOf.aligned === false" in frame, "a report visual and a board tile (both drawn in a frame) say mixed dates"
    assert "C.pinned" in consume and "B.consume.pinned(" in (STATIC / "js" / "bi-report-board.js").read_text(encoding="utf-8"), \
        "a visual pinned to a past date by its report's as-of rule is asked once, not followed"


def test_the_line_uses_the_themes_warning_colour_in_every_theme():
    css = (STATIC / "css" / "bi-report.css").read_text(encoding="utf-8")
    assert re.search(r"\.bi-asof-foot\s*\{[^}]*var\(--d-warn", css)
    assert re.search(r'\[data-asof-note\]\[data-asof-aligned="false"\]\s*\{[^}]*var\(--d-warn', css)
    tokens = (STATIC / "css" / "tokens.css").read_text(encoding="utf-8")
    assert len(re.findall(r"--d-warn:\s*#[0-9a-fA-F]{6}", tokens)) >= 3, "terminal, light and contrast each define the warning colour"
