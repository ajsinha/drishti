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

"""Makes the pictures of the Analyse chapter (docs/tutorials/10-first-bi-report.md chapter 25, USER_GUIDE.md) from the real stack (not a test: run it by hand).

    drishti-console/.venv/bin/python drishti-console/tests/analyse_shots.py <pictures dir>

Ports 18956 (server) and 17956 (console) and the stack of tests/test_bi_explore_browser.py: the Slice tab, a SQL cell, and a Python cell with a chart."""
import os
import sys
import tempfile
from pathlib import Path

os.environ.setdefault("DRISHTI_BI_REPORT_SERVER_PORT", "18956")
os.environ.setdefault("DRISHTI_BI_REPORT_CONSOLE_PORT", "17956")

from playwright.sync_api import sync_playwright  # noqa: E402

import bi_report_stack  # noqa: E402
from bi_report_helpers import sign_in  # noqa: E402
from test_bi_explore_browser import open_extract  # noqa: E402

CHART = ("result = {'title': {'text': 'Notional by desk'}, 'xAxis': {'type': 'category', 'data': list(g['desk'])}, 'yAxis': {'type': 'value'}, "
         "'series': [{'type': 'bar', 'data': [float(v) for v in g['notional']]}]}")


def main(out: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        stack = bi_report_stack.ReportStack(Path(tmp), delta_root="").start()
        try:
            with sync_playwright() as p:
                browser = p.chromium.launch()
                page = sign_in(browser, "wes", "/bi", viewport={"width": 1280, "height": 900})
                open_extract(page)
                page.click('.bx-chip[data-field="desk"] [data-to="rows"]')
                page.click('.bx-chip[data-field="book"] [data-to="columns"]')
                page.click('.bx-chip[data-field="notional"] [data-to="values"]')
                page.wait_for_selector("[data-pivot] table")
                page.locator("#bx").screenshot(path=str(out / "slice.png"))
                page.click("#bxT-sql")
                page.fill("[data-cells=sql] textarea", "SELECT desk, COUNT(*) AS trades, SUM(notional) AS notional\nFROM extract\nGROUP BY desk\nORDER BY notional DESC")
                page.click("[data-cells=sql] [data-run]")
                page.wait_for_selector("[data-cells=sql] table")
                page.locator("[data-pane=sql]").screenshot(path=str(out / "sql.png"))
                page.click("#bxT-python")
                page.fill("[data-cells=python] textarea", "g = df.groupby('desk', as_index=False)['notional'].sum()\n" + CHART)
                page.click("[data-cells=python] [data-run]")
                page.wait_for_selector("[data-cells=python] [data-ready]", timeout=60000)
                page.wait_for_timeout(500)
                page.locator("[data-pane=python]").screenshot(path=str(out / "python.png"))
                page.context.close()
                browser.close()
        finally:
            stack.close()


if __name__ == "__main__":
    main(Path(sys.argv[1]))
