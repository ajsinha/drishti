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

"""Makes the pictures of the pivot screen (BI_GUIDE.md section 2, tutorial 7, Part A) from the stand-in server's synthetic trades (not a
test: run it by hand).

    drishti-console/.venv/bin/python drishti-console/tests/pivot_shots.py [pictures dir]

Default folder: docs/guides/img/pivot. The screen as it opens (the View picker in front of the compact line) in the Terminal theme, the
Rows picker, a value's functions, the View picker and the panes behind Customize."""
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
            page.wait_for_selector("[data-pv-grid] .dg-cell[data-cell]")
            theme(page, "terminal")
            page.screenshot(path=str(out / "pivot-fields-views.png"))
            page.locator("[data-pv-title]").click()
            page.keyboard.press("r")
            page.wait_for_selector(".di-fp-pop")
            page.wait_for_timeout(150)
            page.screenshot(path=str(out / "pivot-fields-rows-picker.png"))
            page.keyboard.press("Escape")
            page.click(".dg-zone[data-well='values'] .dg-vchip[data-field='NOTIONAL'] .dg-chip-menu")
            page.wait_for_timeout(150)
            page.screenshot(path=str(out / "pivot-fields-value-menu.png"))
            page.keyboard.press("Escape")
            page.locator("[data-pv-title]").click()
            page.keyboard.press("Shift+V")
            page.wait_for_selector(".dg-views-pop .di-option")
            page.wait_for_timeout(150)
            page.screenshot(path=str(out / "pivot-fields-view-picker.png"))
            page.keyboard.press("Escape")
            page.locator("[data-pv-title]").click()
            page.keyboard.press("\\")
            page.wait_for_selector("[data-pv-grid] .dg-fieldpane")
            page.wait_for_timeout(200)
            page.screenshot(path=str(out / "pivot-fields-panes.png"))
            for n in ("pivot-fields-views", "pivot-fields-rows-picker", "pivot-fields-value-menu", "pivot-fields-view-picker", "pivot-fields-panes"):
                print(out / (n + ".png"))
            browser.close()
    finally:
        stop()


if __name__ == "__main__":
    main(Path(sys.argv[1]) if len(sys.argv) > 1 else OUT)
