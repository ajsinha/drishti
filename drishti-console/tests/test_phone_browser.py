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

"""The phone display and the lens menus' phone form in a real browser (MOBILE_DISPLAY.md, LX1 option C), at 390 px and 1280 px, in the
dark (terminal) and light themes:

* a phone gets the phone page (must, then glance in its compact form, More on this page, On desktop) without scrolling sideways, each
  control at least 44 px; a More row loads its panel in place, Show all brings the whole panel, Show everything the whole page;
* the desktop is unchanged: every panel, no phone page;
* in Drishti Trading the subject menus fold into one Trading menu on a phone, and stand in the bar on a desktop (Drishti Risk has none
  since its Engines menu took the place of VaR and Exposure: test_engines_menu_browser.py).

DRISHTI_PHONE_SHOTS=<folder> also saves the screenshots the user guide shows. Skipped when Playwright or its Chromium is missing."""
import os
import threading
import time
from pathlib import Path

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE  # noqa: E402
from test_live_tabs_browser import TickingBackend, _free_port, browser, console_url  # noqa: E402,F401 - the shared browser fixtures

PAGE = "/v/sutra/all-panels-showcase"
THEMES = ("terminal", "light")
SHOTS = os.environ.get("DRISHTI_PHONE_SHOTS")

# the page scrolling sideways, and the phone page's own controls smaller than a finger
AUDIT = r"""() => {
  const d = document.documentElement, out = [];
  if (d.scrollWidth > d.clientWidth + 1) out.push('scrolls sideways: ' + d.scrollWidth + ' in ' + d.clientWidth);
  for (const e of document.querySelectorAll('.ph-row, .ph-all, .ph-btn, .ph-why summary, .tbar-lens-btn, .mega-lens .mega-item')) {
    const r = e.getBoundingClientRect();
    if (r.width && r.height && getComputedStyle(e).visibility !== 'hidden' && Math.min(r.width, r.height) < 43.5) out.push('small: ' + e.className + ' ' + Math.round(r.width) + 'x' + Math.round(r.height));
  }
  return out;
}"""


def _context(browser, width, theme):
    phone = width < 640
    ctx = browser.new_context(viewport={"width": width, "height": 844 if phone else 900}, is_mobile=phone, has_touch=phone)
    ctx.add_init_script(f"try {{ localStorage.setItem('drishti.theme', '{theme}'); }} catch (e) {{}}")
    return ctx


def _open(page, url):
    """Opens a page as a first visit does: drishti_phone.js learns the screen and, on an entity page drawn for another class, draws it once more."""
    page.goto(url, wait_until="load")
    page.wait_for_timeout(400)
    page.wait_for_load_state("load")


def _shot(page, name):
    if SHOTS:
        Path(SHOTS).mkdir(parents=True, exist_ok=True)
        page.screenshot(path=str(Path(SHOTS) / f"{name}.png"), full_page=False)


@pytest.fixture(scope="module")
def risk_console():
    """A Drishti Risk console over the stand-in server: the edition whose subject menus (VaR, Exposure) fold on a phone."""
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = TickingBackend()
    app.state.editions.learn({"product": "Drishti Risk", "edition": {"name": "risk", "title": "Drishti Risk"}})
    port = _free_port()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", timeout_graceful_shutdown=2))
    threading.Thread(target=server.run, daemon=True).start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started, "the Risk console did not start"
    yield f"http://127.0.0.1:{port}"
    server.should_exit = True


@pytest.fixture(scope="module")
def trading_console():
    """A Drishti Trading console over the stand-in server: the edition whose subject menus (Markets, Blotter, Positions, Risk, Counterparties)
    fold on a phone."""
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = TickingBackend()
    app.state.editions.learn({"product": "Drishti Trading", "edition": {"name": "trading", "title": "Drishti Trading"}})
    port = _free_port()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", timeout_graceful_shutdown=2))
    threading.Thread(target=server.run, daemon=True).start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started, "the Trading console did not start"
    yield f"http://127.0.0.1:{port}"
    server.should_exit = True


@pytest.mark.parametrize("theme", THEMES)
def test_a_phone_gets_the_phone_page_and_loads_more_in_place(browser, console_url, theme):
    ctx = _context(browser, 390, theme)
    page = ctx.new_page()
    try:
        _open(page, console_url + PAGE)
        assert page.locator("[data-phone-page='phone']").count() == 1, "the phone page was not drawn"
        assert page.locator("#p-terms").is_visible()                             # must
        assert page.locator("#p-coupons[data-phone-compact]").count() == 1      # glance, compact
        assert page.locator("#p-grid").count() == 0 and page.locator("#p-built").count() == 0   # desk and more are not sent
        assert page.locator(".ph-spark").count() >= 1                            # a chart at a glance is a sparkline
        problems = page.evaluate(AUDIT)
        assert not problems, problems
        _shot(page, f"01-phone-page-{theme}")
        page.locator("#p-pnl").scroll_into_view_if_needed()                     # a glance chart and a glance table
        _shot(page, f"02-glance-{theme}")
        page.locator(".ph-more").scroll_into_view_if_needed()
        _shot(page, f"03-more-on-this-page-{theme}")
        page.locator("[data-phone-load='built']").click()
        page.locator(".ph-more #p-built").wait_for(state="visible")
        assert page.locator("[data-phone-load='built']").count() == 0
        rows = page.locator("#p-coupons tbody tr").count()
        page.locator("[data-phone-all='coupons']").click()
        page.locator("#p-coupons:not([data-phone-compact])").wait_for(state="attached")
        assert page.locator("#p-coupons tbody tr").count() > rows
        assert not page.evaluate(AUDIT)
        page.locator("[data-phone-everything]").click()
        page.wait_for_load_state("load")
        page.locator("[data-phone-back]").wait_for(state="visible")
        assert page.locator("#p-grid").count() == 1                              # everything, for the session
        page.locator("[data-phone-back]").click()
        page.wait_for_load_state("load")
        page.locator("[data-phone-page='phone']").wait_for(state="attached")
    finally:
        ctx.close()


@pytest.mark.parametrize("theme", THEMES)
def test_the_desktop_page_is_unchanged(browser, console_url, theme):
    ctx = _context(browser, 1280, theme)
    page = ctx.new_page()
    try:
        _open(page, console_url + PAGE)
        assert page.locator("[data-phone-page]").count() == 0
        for pid in ("terms", "versions", "grid", "built", "note"):
            assert page.locator(f"#p-{pid}").count() == 1, pid
        assert page.locator("[data-phone-compact]").count() == 0
        assert page.evaluate("document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1")
    finally:
        ctx.close()


@pytest.mark.parametrize("theme", THEMES)
def test_the_lens_menus_fold_into_one_on_a_phone(browser, trading_console, theme):
    ctx = _context(browser, 390, theme)
    page = ctx.new_page()
    try:
        _open(page, trading_console + "/t")
        lens = page.locator(".tbar-lens-btn")
        assert lens.is_visible() and lens.inner_text().strip().startswith("Trading")
        assert not page.locator(".tbar-menu[data-subject]").first.is_visible()   # Blotter ... Counterparties are inside it
        lens.click()
        panel = page.locator(".mega-lens")
        panel.wait_for(state="visible")
        titles = [t.strip() for t in panel.locator(".mega-subject").all_text_contents()]
        assert titles == ["Markets", "Blotter", "Positions", "Risk", "Counterparties"]
        assert panel.locator("a.mega-item").first.is_visible()
        assert not page.evaluate(AUDIT)
    finally:
        ctx.close()


@pytest.mark.parametrize("theme", THEMES)
def test_the_subject_menus_stand_in_the_bar_on_a_desktop(browser, trading_console, theme):
    ctx = _context(browser, 1280, theme)
    page = ctx.new_page()
    try:
        _open(page, trading_console + "/t")
        assert page.locator(".tbar-lens-btn").count() == 0
        assert page.locator(".tbar-menu[data-subject]").count() == 5
        assert page.locator(".tbar-menu[data-subject]").first.is_visible()
        page.set_viewport_size({"width": 390, "height": 844})                   # narrowed: the lens menu comes in
        page.wait_for_timeout(500)
        assert page.locator(".tbar-lens-btn").is_visible()
        page.set_viewport_size({"width": 1280, "height": 900})                  # widened: it goes out again
        page.wait_for_timeout(500)
        assert page.locator(".tbar-lens-btn").count() == 0
    finally:
        ctx.close()


@pytest.mark.parametrize("theme", THEMES)
def test_the_var_page_and_the_risk_landing_on_a_phone(browser, risk_console, theme):
    ctx = _context(browser, 390, theme)
    page = ctx.new_page()
    try:
        _open(page, risk_console + "/var?scope=BANK")
        assert page.locator("[data-phone-page=phone]").count() == 1
        assert not page.locator(".vx-tab[data-tab=contrib]").is_visible()        # more: under More on this page
        assert not page.locator("[data-vx-whatif]").is_visible()                 # desk: named under On desktop
        assert not page.evaluate(AUDIT), page.evaluate(AUDIT)
        page.locator(".ph-parts").scroll_into_view_if_needed()
        _shot(page, f"05-var-page-{theme}")
        page.locator("[data-phone-reveal=contrib]").click()
        page.locator(".vx-tab[data-tab=contrib]").wait_for(state="visible")
        assert page.locator(".vx-tab[data-tab=contrib]").get_attribute("aria-selected") == "true"
        _open(page, risk_console + "/")
        assert page.locator("[data-phone-page=phone]").count() == 1
        assert not page.evaluate(AUDIT), page.evaluate(AUDIT)
        _shot(page, f"06-risk-landing-{theme}")
    finally:
        ctx.close()


@pytest.mark.parametrize("theme", THEMES)
def test_the_var_page_on_a_desktop_is_unchanged(browser, risk_console, theme):
    ctx = _context(browser, 1280, theme)
    page = ctx.new_page()
    try:
        _open(page, risk_console + "/var?scope=BANK")
        assert page.locator("[data-phone-tier]").count() == 0 and page.locator(".ph-parts").count() == 0
        assert page.locator(".vx-tab[data-tab=contrib]").is_visible()
        assert page.locator("[data-vx-whatif]").is_visible()                       # What-if… in the toolbar
    finally:
        ctx.close()
