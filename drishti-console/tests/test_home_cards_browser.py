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

"""Your Home in a real browser, a real server behind (docs/guides/USER_GUIDE.md, "Your Home"): the page is drawn by the Drishti Elements painter, a
card's own buttons are there and on a phone nothing scrolls sideways. The ranking, the layouts and the isolation
of two people are the console tests' (test_home_cards.py)."""
from test_designer_browser import page_in, stack  # noqa: F401 - the stack and pages
from test_workbench_browser import browser, wait  # noqa: F401


def test_your_home_is_drawn_with_each_cards_own_buttons(browser, stack):
    pg = page_in(browser, "dark", {"width": 1440, "height": 950})
    pg.goto(f"{stack}/home")
    wait(pg, "document.querySelector('[data-home-cards] .dv-view')")
    assert pg.locator("[data-home-cards] .dv-btn").count() > 0, "each card has its own buttons"
    assert pg.evaluate("document.querySelector('[data-home-cards]').getAttribute('data-layout')") in ("calm", "cockpit")
    pg.close()


def test_your_home_on_a_phone_does_not_scroll_sideways(browser, stack):
    pg = page_in(browser, "light", {"width": 390, "height": 800})
    pg.goto(f"{stack}/home")
    wait(pg, "document.querySelector('[data-home-cards] .dv-view')")
    assert pg.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
    pg.close()
