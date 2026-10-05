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

"""About this page in a real browser against a real server (docs/architecture/CONTEXT_HELP.md, step 2): `?` and F1 open the drawer on a
real view, its layers render and the empty ones are absent, Esc closes it and the focus goes back, F1 again leaves for the screen guide,
a phone gets a bottom sheet with the focus held inside, and no value of the document appears in the explanation.

Skipped when Playwright, Chromium, the built server jar or a JDK is missing (wb_live.py)."""
import re

from test_workbench_browser import browser, page, wait  # noqa: F401 - fixtures and helpers
from wb_live import live_console  # noqa: F401 - the fixture

VIEW = "/v/trade/IRS-48213"
DOCUMENT_VALUES = ("Northbridge Capital", "549300XC29H3SPO07Z24", "50,000,000")      # a value shown on the page (the counterparty name), never to appear in the explanation


def open_view(page, base):
    page.goto(base + VIEW)
    page.locator(".vtitle .vid").wait_for()
    return page.locator("#aboutDrawer")


def drawer_text(page):
    wait(page, "!!document.querySelector('#aboutDrawer [data-layer=\"data\"]')")
    return page.locator("#aboutDrawer").inner_text()


def test_question_mark_opens_the_drawer_with_its_layers_and_escape_closes_it(live_console, page):
    drawer = open_view(page, live_console)
    assert drawer.is_hidden()
    requests = []
    page.on("request", lambda r: requests.append(r.url) if "/about" in r.url else None)
    page.locator("[data-about-open]").focus()
    page.keyboard.press("?")                                
    drawer.wait_for()
    assert page.evaluate("document.activeElement.id") == "aboutTitle"       # the focus moves to the heading
    text = drawer_text(page)
    for layer in ("Where the data came from", "Why the page looks like this", "Where next"):
        assert layer in text, text
    if page.locator("#aboutDrawer [data-layer=\"data\"]").get_attribute("open") is None:      # layers 1 and 2 are open first; the data layer opens on a click
        page.locator("#aboutDrawer [data-layer=\"data\"] summary").click()
    assert page.locator("#aboutDrawer [data-layer=\"data\"] .about-health").inner_text() in ("up", "degraded", "down")
    assert page.locator("#aboutDrawer a[href^='/help/panel-kinds#']").count() >= 1
    assert drawer.get_attribute("role") == "dialog" and drawer.get_attribute("aria-modal") == "false"
    assert page.locator("[data-about-open]").get_attribute("aria-expanded") == "true"
    assert len(requests) == 1                                                  # one lazy fetch, on the first open
    for v in DOCUMENT_VALUES:                                                  # nothing of the document, masked or not, is in the explanation
        assert v not in text, v
    page.keyboard.press("Escape")
    assert drawer.is_hidden() and page.evaluate("document.activeElement.hasAttribute('data-about-open')")   # focus returns to the opener
    page.keyboard.press("?")
    drawer.wait_for()
    assert len(requests) == 1                                                  # later opens come from the DOM
    assert not page.errors


def test_f1_opens_the_drawer_and_f1_again_goes_to_the_screen_guide(live_console, page):
    drawer = open_view(page, live_console)
    page.keyboard.press("F1")
    drawer.wait_for()
    assert "/help/" not in page.url                                           # the view stayed
    page.keyboard.press("F1")
    page.wait_for_url(re.compile(r"/help/"))


def test_question_mark_typed_in_a_field_does_not_open_it(live_console, page):
    drawer = open_view(page, live_console)
    page.locator("input[type=text], input:not([type])").first.focus()
    page.keyboard.type("?")
    assert drawer.is_hidden()                                                  # a question mark typed in a text field is just typed


def test_a_phone_gets_a_bottom_sheet_that_holds_the_focus(live_console, browser):
    pg = browser.new_page(viewport={"width": 390, "height": 800})
    try:
        pg.goto(live_console + VIEW)
        pg.locator(".vtitle .vid").wait_for()
        pg.keyboard.press("?")
        drawer = pg.locator("#aboutDrawer")
        drawer.wait_for()
        drawer_text(pg)
        box = drawer.bounding_box()
        assert box["width"] >= 385 and box["y"] > 800 * 0.1 and box["y"] + box["height"] >= 795      # full width, anchored to the bottom
        assert box["height"] <= 800 * 0.86
        assert drawer.get_attribute("aria-modal") == "true"
        for _ in range(40):                                                    # Tab never leaves the sheet
            pg.keyboard.press("Tab")
            assert pg.evaluate("document.getElementById('aboutDrawer').contains(document.activeElement)"), pg.evaluate("document.activeElement.outerHTML.slice(0, 160)")
        pg.keyboard.press("Escape")
        assert drawer.is_hidden()
        assert pg.evaluate("document.documentElement.scrollWidth") <= 392
    finally:
        pg.close()
