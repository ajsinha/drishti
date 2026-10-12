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

"""The top bar at desktop widths (1024 to 1920 px) on the terminal, a BI page and a view: no two of its controls (brand, command
line, business date, Live, the five menus, the tools) overlap, and the page never scrolls sideways. Regression for the Views menu
sitting over the business-date box at 1280 to 1440 px once the bar had five menus. Skipped when Playwright is not installed."""
import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from test_live_tabs_browser import browser, console_url  # noqa: E402,F401 - the shared browser fixtures

WIDTHS = (1024, 1101, 1280, 1366, 1440, 1600, 1920)
PAGES = ("/t", "/bi", "/v/trade/IRS-48213")

BOXES = r"""() => {
  const sel = '.tbar-brand, .tbar .cmd, .tbar .asof, .tbar-menu-btn, .tbar-tools > *';
  const out = [];
  for (const e of document.querySelectorAll(sel)) {
    const r = e.getBoundingClientRect();
    if (r.width < 1 || r.height < 1) continue;
    out.push([(e.className || e.tagName).toString().slice(0, 30) + ' ' + (e.getAttribute('title') || e.textContent || '').trim().slice(0, 14), r.left, r.top, r.right, r.bottom]);
  }
  const d = document.documentElement;
  return {boxes: out, sw: d.scrollWidth, vw: d.clientWidth};
}"""


def _clash(a, b):
    return min(a[3], b[3]) - max(a[1], b[1]) > 1 and min(a[4], b[4]) - max(a[2], b[2]) > 1


@pytest.mark.parametrize("width", WIDTHS)
def test_top_bar_controls_never_overlap(browser, console_url, width):
    ctx = browser.new_context(viewport={"width": width, "height": 800})
    page = ctx.new_page()
    problems = []
    try:
        for url in PAGES:
            page.goto(console_url + url, wait_until="load")
            page.wait_for_timeout(400)
            s = page.evaluate(BOXES)
            bx = s["boxes"]
            assert len(bx) >= 6, f"{url} @ {width}: the top bar did not draw ({len(bx)} controls)"
            for i, a in enumerate(bx):
                for b in bx[i + 1:]:
                    if _clash(a, b):
                        problems.append(f"{url} @ {width}: '{a[0]}' overlaps '{b[0]}'")
            if s["sw"] > s["vw"] + 1:
                problems.append(f"{url} @ {width}: page scrolls sideways ({s['sw']} in {s['vw']})")
    finally:
        ctx.close()
    assert not problems, "\n".join(problems)


@pytest.mark.parametrize("url", ["/t", "/v/trade/IRS-48213", "/bi"])
def test_a_top_bar_menu_stays_open_until_the_person_closes_it(browser, console_url, url):
    """A menu opened with a click stays open (a page's own refresh, a live tick or a stray click handler must not close it)."""
    page = browser.new_page(viewport={"width": 1366, "height": 900})
    try:
        page.goto(console_url + url, wait_until="load")
        btn = page.locator('.tbar-menu-btn[data-bs-toggle="dropdown"]').first
        btn.click()
        page.wait_for_timeout(3000)
        assert btn.get_attribute("aria-expanded") == "true", "the menu closed by itself"
        assert page.locator(".tbar .dropdown-menu.show").count() == 1
    finally:
        page.close()
