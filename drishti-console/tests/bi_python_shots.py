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

"""Makes the pictures of the Python in BI chapter (docs/tutorials/10-first-bi-report.md chapter 24, BI_GUIDE.md) from the real stack (not a test: run it by hand).

    drishti-console/.venv/bin/python drishti-console/tests/bi_python_shots.py <pictures dir>

Ports 18955 (server) and 17955 (console) and the stack of tests/test_bi_python_browser.py: the sample report "Desk P&L risk (Python)" run on the server, run in the browser
(needs Pyodide: tools/fetch-pyodide.sh), and an item page with its Check."""
import os
import sys
import tempfile
from pathlib import Path

os.environ.setdefault("DRISHTI_BI_REPORT_SERVER_PORT", "18955")
os.environ.setdefault("DRISHTI_BI_REPORT_CONSOLE_PORT", "17955")

from playwright.sync_api import sync_playwright  # noqa: E402

from bi_report_helpers import open_report, sign_in  # noqa: E402
from bi_report_stack import CONSOLE_URL  # noqa: E402
from test_bi_python_browser import PYODIDE, Stack, settled  # noqa: E402


def main(out: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        stack = Stack(Path(tmp), delta_root="").start()
        try:
            with sync_playwright() as p:
                browser = p.chromium.launch()
                page = sign_in(browser, "rita", "/bi")
                for run in ("server", "browser"):
                    if run == "browser" and not PYODIDE.exists():
                        break
                    open_report(page, f"file:report:py-{run}")
                    settled(page, timeout=150000)
                    page.wait_for_timeout(600)
                    page.locator("[data-grid]").screenshot(path=str(out / f"report-{run}.png"))
                page.context.close()
                wes = sign_in(browser, "wes", "/bi")
                wes.goto(CONSOLE_URL + "/bi/i/file:python:desk-pnl-volatility")
                wes.wait_for_selector("[data-body]:not(:empty)", timeout=30000)
                wes.get_by_role("button", name="Check the Python").click()
                wes.wait_for_selector("[data-py-check]", timeout=30000)
                wes.screenshot(path=str(out / "item-check.png"))
                wes.context.close()
                browser.close()
        finally:
            stack.close()


if __name__ == "__main__":
    main(Path(sys.argv[1]))
