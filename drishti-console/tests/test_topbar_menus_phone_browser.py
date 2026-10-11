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

"""Every top-bar menu opened by a tap on a phone (360, 390 and 412 px wide, a touch device) on a few pages, a BI page and the
terminal among them: the open panel lies fully inside the screen (16 px gutters, scrolling inside itself when long), the page does not
scroll sideways, every item is at least 44 px tall, and Escape and a tap outside close it. Regression for a menu opening off the left
edge of a phone and dragging the page sideways. Skipped when Playwright or its Chromium is not installed."""
import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from test_live_tabs_browser import browser, console_url  # noqa: E402,F401 - the shared browser fixtures

WIDTHS = (360, 390, 412)
PAGES = ("/t", "/bi", "/bi/catalogue", "/bi/r/file:report:counterparty-risk", "/bi/b/file:board:desk-wall", "/admin/users", "/help")
GUTTER = 16

BUTTONS = "() => [...document.querySelectorAll('.tbar [data-bs-toggle=dropdown]')].map((b, i) => { b.dataset.tbi = i; return i; })"
STATE = r"""(i) => {
  const b = document.querySelector('.tbar [data-tbi="' + i + '"]'), m = b.parentElement.querySelector('.dropdown-menu'), doc = document.documentElement;
  const r = m.getBoundingClientRect(), items = [...m.querySelectorAll('a.mega-item, a.dropdown-item, button.dropdown-item, label.pack-item')]
    .filter(e => e.getBoundingClientRect().width);
  return {shown: m.classList.contains('show'), left: r.left, right: r.right, top: r.top, bottom: r.bottom, vw: doc.clientWidth, vh: window.innerHeight,
          sw: doc.scrollWidth, sx: window.scrollX, title: b.getAttribute('title') || '',
          small: items.map(e => [(e.textContent || '').trim().slice(0, 24), e.getBoundingClientRect().height]).filter(x => x[1] < 43.5)};
}"""


@pytest.mark.parametrize("width", WIDTHS)
def test_every_top_bar_menu_fits_a_phone(browser, console_url, width):
    ctx = browser.new_context(viewport={"width": width, "height": 780}, is_mobile=True, has_touch=True, device_scale_factor=2)
    page = ctx.new_page()
    problems = []
    try:
        for url in PAGES:
            page.goto(console_url + url, wait_until="load")
            page.wait_for_timeout(300)
            for i in page.evaluate(BUTTONS):
                where = f"{url} @ {width} px, menu #{i}"
                page.tap(f'.tbar [data-tbi="{i}"]')
                page.wait_for_timeout(350)
                s = page.evaluate(STATE, i)
                where += f" ({s['title'][:24]})"
                if not s["shown"]:
                    problems.append(f"{where}: did not open")
                    continue
                if s["left"] < GUTTER - 1 or s["right"] > s["vw"] - GUTTER + 1:
                    problems.append(f"{where}: panel {s['left']:.0f}..{s['right']:.0f} outside the {GUTTER} px gutters of {s['vw']}")
                if s["bottom"] > s["vh"] + 1:
                    problems.append(f"{where}: panel runs {s['bottom'] - s['vh']:.0f} px below the screen")
                if s["sw"] > s["vw"] + 1 or s["sx"] > 0:
                    problems.append(f"{where}: page scrolls sideways ({s['sw']} in {s['vw']}, x {s['sx']})")
                if s["small"]:
                    problems.append(f"{where}: items under 44 px: {s['small'][:3]}")
                page.keyboard.press("Escape")
                page.wait_for_timeout(200)
                if page.evaluate(STATE, i)["shown"]:
                    problems.append(f"{where}: Escape did not close it")
                page.tap(f'.tbar [data-tbi="{i}"]')
                page.wait_for_timeout(250)
                page.touchscreen.tap(width - 4, 760)           # outside: the page below the bar
                page.wait_for_timeout(250)
                if page.evaluate(STATE, i)["shown"]:
                    problems.append(f"{where}: a tap outside did not close it")
    finally:
        ctx.close()
    assert not problems, "\n".join(problems)
