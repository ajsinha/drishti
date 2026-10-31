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

"""Makes the pictures of Chart any table (USER_GUIDE.md, RACHANA_REFERENCE.md) from the stand-in server's synthetic data (not a test: run
it by hand).

    drishti-console/.venv/bin/python drishti-console/tests/table_chart_shots.py [pictures dir]

Default folder: docs/guides/img/table-chart. A line by month and a donut in the Terminal theme (dark), stacked bars split by
currency and a combo on two axes in the light theme, and the phone sheet."""
import sys
from pathlib import Path

from playwright.sync_api import sync_playwright

from table_chart_backend import serve

OUT = Path(__file__).resolve().parents[2] / "docs" / "guides" / "img" / "table-chart"


def theme(page, name):
    page.evaluate("n => { document.documentElement.setAttribute('data-theme', n); document.dispatchEvent(new CustomEvent('drishti:theme', { detail: n })); }", name)
    page.wait_for_timeout(150)


def open_chart(page, url, panel="flows"):
    page.goto(url + "/v/trade/CHART-1", wait_until="load")
    page.wait_for_selector(f"#p-{panel} [data-table-chart]")
    page.click(f"#p-{panel} [data-table-chart]")
    page.wait_for_selector("dialog.tc-dlg[open] .tc-canvas svg path", state="attached")
    page.wait_for_timeout(200)


def shot(page, path):
    page.locator("dialog.tc-dlg").screenshot(path=str(path))
    print(path)


def main(out: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    url, _, stop = serve()
    try:
        with sync_playwright() as p:
            browser = p.chromium.launch()
            ctx = browser.new_context(viewport={"width": 1440, "height": 860}, bypass_csp=True)
            page = ctx.new_page()
            open_chart(page, url)
            theme(page, "terminal")
            page.select_option('[data-tc-k="bucket"]', "month")
            page.click('[data-tc-k="add"]')
            page.select_option('[data-tc-k="s1-axis"]', "right")
            page.wait_for_timeout(200)
            shot(page, out / "line-by-month-terminal.png")
            page.click('[data-tc-type="pie"]')
            page.check('[data-tc-k="donut"]')
            page.wait_for_timeout(200)
            shot(page, out / "donut-terminal.png")
            page.keyboard.press("Escape")
            open_chart(page, url)
            theme(page, "light")
            page.select_option('[data-tc-k="x"]', "Desk")
            page.click('[data-tc-type="bar"]')
            page.select_option('[data-tc-k="split"]', "Currency")
            page.check('[data-tc-k="bars"]')
            page.wait_for_timeout(200)
            shot(page, out / "stacked-bars-light.png")
            page.select_option('[data-tc-k="split"]', "")
            page.click('[data-tc-type="combo"]')
            page.select_option('[data-tc-k="x"]', "Pay date")
            page.select_option('[data-tc-k="bucket"]', "quarter")
            page.wait_for_timeout(200)
            shot(page, out / "combo-two-axes-light.png")
            ctx.close()
            phone = browser.new_context(viewport={"width": 390, "height": 844}, has_touch=True, is_mobile=True, bypass_csp=True)
            page = phone.new_page()
            open_chart(page, url)
            theme(page, "terminal-blue")
            page.select_option('[data-tc-k="bucket"]', "month")
            page.wait_for_timeout(200)
            page.screenshot(path=str(out / "phone-terminal-blue.png"))
            print(out / "phone-terminal-blue.png")
            phone.close()
            browser.close()
    finally:
        stop()


if __name__ == "__main__":
    main(Path(sys.argv[1]) if len(sys.argv) > 1 else OUT)
