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

"""Makes the pictures of the pivot screen (PIVOT_GUIDE.md, tutorial 33) from the stand-in server's synthetic trades (not a test: run it
by hand).

    drishti-console/.venv/bin/python drishti-console/tests/pivot_shots.py [pictures dir]

Default folder: docs/guides/img/pivot. The screen by desk in the Terminal theme (dark), desk by currency in the light theme, and the
export panel."""
import sys
from pathlib import Path

from playwright.sync_api import sync_playwright

from pivot_screen_backend import PivotBackend
from table_chart_backend import serve

OUT = Path(__file__).resolve().parents[2] / "docs" / "guides" / "img" / "pivot"


def theme(page, name):
    page.evaluate("n => { document.documentElement.setAttribute('data-theme', n); document.dispatchEvent(new CustomEvent('drishti:theme', { detail: n })); }", name)
    page.wait_for_timeout(150)


def main(out: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    url, _, stop = serve(PivotBackend())
    try:
        with sync_playwright() as p:
            browser = p.chromium.launch()
            page = browser.new_context(viewport={"width": 1440, "height": 860}, bypass_csp=True).new_page()
            page.goto(url + "/pivot?source=trade", wait_until="load")
            page.wait_for_selector("[data-pv-grid] table.pv-table td.pv-c")
            theme(page, "terminal")
            page.screenshot(path=str(out / "pivot-screen-terminal.png"))
            print(out / "pivot-screen-terminal.png")
            theme(page, "light")
            page.click('[data-pv-fields] .pv-field[data-field="CURRENCY"]')
            page.keyboard.press("c")
            page.wait_for_selector("[data-pv-grid] thead th.pv-colhead")
            page.wait_for_timeout(200)
            page.screenshot(path=str(out / "pivot-screen-columns-light.png"))
            print(out / "pivot-screen-columns-light.png")
            page.click("[data-pv-export-open]")
            page.wait_for_timeout(150)
            page.locator("[data-pv-export]").screenshot(path=str(out / "pivot-export-panel.png"))
            print(out / "pivot-export-panel.png")
            browser.close()
    finally:
        stop()


if __name__ == "__main__":
    main(Path(sys.argv[1]) if len(sys.argv) > 1 else OUT)
