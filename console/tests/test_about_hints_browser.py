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

"""About this page, panel and field level, in a real browser against a real server (docs/architecture/CONTEXT_HELP.md, step 4): the `?` on a
panel header opens a popover (Enter/click, Esc closes and the focus goes back), a strip label with an entry shows its meaning on focus and
on hover and is reachable by keyboard (Enter opens the drawer at its entry), table headers get hints, a phone tap pins the tooltip, and
nothing of the page's values is in what the popover says.

Skipped when Playwright, Chromium, the built server jar or a JDK is missing (wb_live.py)."""
import pytest

from test_workbench_browser import browser, page, wait  # noqa: F401 - fixtures and helpers
from wb_live import _stack


@pytest.fixture(scope="module")
def live_console(tmp_path_factory):
    """The console in front of a real server with the packs whose about text this tests (the default stack enables others)."""
    yield from _stack(tmp_path_factory, {"DRISHTI_PACKS": "market-risk,genomics"})

VIEW = "/v/var/VAR-COMM"
VAR_LABEL = "VaR 99% 1D"


def open_view(page, base, view=VIEW):
    page.goto(base + view)
    page.locator(".vtitle .vid").wait_for()
    wait(page, "!!document.querySelector('.about-gloss')")                       # the hints appear once the one explain fetch has come back


def strip_term(page, label):
    return page.locator("dl.strip .strip-i dt.about-gloss", has_text=label).first


def test_a_strip_label_with_an_entry_is_underlined_focusable_and_explained_on_focus(live_console, page):
    open_view(page, live_console)
    dt = strip_term(page, VAR_LABEL)
    assert dt.get_attribute("tabindex") == "0" and dt.get_attribute("data-gloss") == "var99"
    assert "dotted" in dt.evaluate("e => getComputedStyle(e).textDecorationLine + ' ' + getComputedStyle(e).textDecorationStyle")
    dt.focus()                                                                    # keyboard: focus alone shows it
    tip = page.locator("#aboutTip")
    tip.wait_for()
    assert tip.get_attribute("role") == "tooltip" and dt.get_attribute("aria-describedby") == "aboutTip"
    text = tip.inner_text()
    assert "Value at risk, 99%, 1 day" in text and "Unit: USD" in text and "A loss, written as a positive number." in text
    for value in ("10.96", "10,96", "11.0m"):                                      # a field's meaning never carries the value on the page
        assert value not in text, text
    page.keyboard.press("Escape")
    assert tip.is_hidden() and page.evaluate("document.activeElement.getAttribute('data-gloss')") == "var99"


def test_hover_shows_the_tooltip_after_a_short_delay_and_leaving_hides_it(live_console, page):
    open_view(page, live_console)
    dt = strip_term(page, "ES 97.5%")
    dt.hover()
    tip = page.locator("#aboutTip")
    tip.wait_for()
    assert "Expected shortfall, 97.5%" in tip.inner_text() and "FRTB" in tip.inner_text()
    page.mouse.move(5, 400)
    wait(page, "document.getElementById('aboutTip').hidden")


def test_enter_on_a_labelled_field_opens_the_drawer_at_its_entry(live_console, page):
    open_view(page, live_console)
    strip_term(page, VAR_LABEL).focus()
    page.keyboard.press("Enter")
    page.locator("#aboutDrawer").wait_for()
    wait(page, "!!document.querySelector('#aboutDrawer [data-term=\"var99\"]')")
    wait(page, "document.activeElement && document.activeElement.getAttribute('data-term') === 'var99'")
    layer = page.locator("#aboutDrawer [data-layer=\"glossary\"]")
    assert layer.get_attribute("open") is not None
    assert "What each number means" in layer.inner_text() and "Value at risk, 99%, 1 day" in layer.inner_text()


def test_the_panel_question_mark_opens_a_popover_with_its_fields_and_two_links_and_escape_returns_the_focus(live_console, page):
    open_view(page, live_console)
    help_link = page.locator("section[data-panel='scenarios'] a.pnl-help[data-about-help]")
    assert help_link.get_attribute("role") == "button" and help_link.get_attribute("aria-expanded") == "false"
    assert help_link.get_attribute("href").startswith("/help/panel-kinds#")      # without the script it is still today's link
    help_link.focus()
    page.keyboard.press("Enter")
    pop = page.locator(".about-pop")
    pop.wait_for()
    assert help_link.get_attribute("aria-expanded") == "true" and pop.get_attribute("role") == "dialog"
    assert page.evaluate("document.activeElement.classList.contains('about-pop')")
    text = pop.inner_text()
    assert "The markers are -VaR" in text                                         # the pack's own text for this panel
    assert pop.locator("a", has_text="panel").get_attribute("href").startswith("/help/panel-kinds#")
    assert pop.locator("button", has_text="About this page").count() == 1
    page.keyboard.press("Escape")
    assert pop.is_hidden() and page.evaluate("document.activeElement.hasAttribute('data-about-help')")
    assert page.locator("#aboutDrawer").is_hidden()                              # Esc closed the popover only
    assert not page.errors


def test_the_popover_about_link_opens_the_drawer_at_the_panels_text(live_console, page):
    open_view(page, live_console)
    page.locator("section[data-panel='scenarios'] a.pnl-help[data-about-help]").click()
    page.locator(".about-pop button", has_text="About this page").click()
    page.locator("#aboutDrawer").wait_for()
    wait(page, "document.activeElement && document.activeElement.hasAttribute('data-about-panel')")
    assert page.locator(".about-pop").is_hidden()


def test_a_phone_tap_pins_the_tooltip_and_the_popover_fits_the_screen(live_console, browser):
    ctx = browser.new_context(viewport={"width": 390, "height": 800}, has_touch=True, is_mobile=True)
    pg = ctx.new_page()
    try:
        open_view(pg, live_console)
        dt = strip_term(pg, VAR_LABEL)
        dt.scroll_into_view_if_needed()
        dt.tap()
        tip = pg.locator("#aboutTip")
        tip.wait_for()
        pg.wait_for_timeout(700)                                                  # past the hover delay: a tap pinned it, it stays
        assert tip.is_visible() and "More in About this page" in tip.inner_text()
        box = tip.bounding_box()
        assert box["x"] >= 0 and box["x"] + box["width"] <= 391
        pg.locator("body").tap(position={"x": 195, "y": 40})
        assert tip.is_hidden()
        pg.locator("section[data-panel='scenarios'] a.pnl-help[data-about-help]").tap()
        pop = pg.locator(".about-pop")
        pop.wait_for()
        box = pop.bounding_box()
        assert box["x"] >= 0 and box["x"] + box["width"] <= 391 and box["y"] >= 0 and box["y"] + box["height"] <= 801
        assert pg.evaluate("document.documentElement.scrollWidth") <= 392
    finally:
        ctx.close()


def test_table_headers_with_an_entry_get_hints(live_console, page):
    open_view(page, live_console, "/v/variant/VRNT-APOE-E4")
    th = page.locator("section[data-panel='evidence'] thead th.about-gloss", has_text="Review stars")
    assert th.get_attribute("tabindex") == "0"
    th.focus()
    tip = page.locator("#aboutTip")
    tip.wait_for()
    assert "ClinVar review status" in tip.inner_text() and "Unit: stars" in tip.inner_text()
