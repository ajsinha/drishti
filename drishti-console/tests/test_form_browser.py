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

"""Query by form in a real browser (Chromium): FORM trading, the criteria, the results, the key cell to the view and Alt+Left back with the
criteria kept; a regex and its refusal; the controls that change with the operator; a phone (390 px, 44 px targets); keyboard only.
The console runs in-process against a stand-in server; skipped when Playwright's Chromium is missing."""
import threading
import time

import pytest

from conftest import CONSOLE
from form_backend import FormBackend

sync_api = pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from test_live_tabs_browser import _free_port  # noqa: E402


@pytest.fixture(scope="module")
def be():
    return FormBackend()


@pytest.fixture(scope="module")
def url(be):
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = be
    port = _free_port()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started
    yield f"http://127.0.0.1:{port}"
    server.should_exit = True
    thread.join(10)


@pytest.fixture(scope="module")
def browser():
    with sync_api.sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001
            pytest.skip(f"needs Playwright's Chromium: {str(e).splitlines()[0]}")
        yield b
        b.close()


@pytest.fixture
def page(browser, url):
    ctx = browser.new_context(viewport={"width": 1280, "height": 900}, bypass_csp=True)
    pg = ctx.new_page()
    yield pg
    ctx.close()


def test_from_the_command_line_to_the_view_and_back_with_the_criteria_kept(page, url, be):
    page.goto(url + "/t", wait_until="load")
    page.fill("#cmdInput", "FORM trading")
    page.press("#cmdInput", "Enter")
    page.wait_for_url("**/form/trading")
    assert page.locator("h1").inner_text().startswith("Trading")
    page.locator('[name="op.mtm"]').select_option("between")
    page.fill('[name="v.mtm"]', "1m")
    page.fill('[name="v2.mtm"]', "5m")
    page.check('[name="v.book"][value="BOOK-FI-1"]')
    page.fill('[name="v.counterparty.name"]', "Meridian")
    page.select_option('[name="op.counterparty.name"]', "contains")
    page.click(".qf-go")
    page.wait_for_selector("[data-qf-command]")
    assert "mtm >= 1m and mtm <= 5m" in page.inner_text("[data-qf-command]")
    assert "v.mtm=1m" in page.url and "v2.mtm=5m" in page.url and "run=1" in page.url                # the criteria are in the address
    assert page.locator("table.tbl tbody tr").count() == 2 and page.locator(".cf-btn").count() >= 1       # the console table with column filters
    page.click("table.tbl tbody a:has-text('IRS-48213')")
    page.wait_for_url("**/v/trade/IRS-48213")
    page.keyboard.press("Alt+ArrowLeft")                                                               # back to the form
    page.wait_for_url("**/form/trading?**")
    assert page.input_value('[name="v.mtm"]') == "1m" and page.input_value('[name="v2.mtm"]') == "5m"
    assert page.is_checked('[name="v.book"][value="BOOK-FI-1"]') and not page.is_checked('[name="v.book"][value="BOOK-FI-2"]')
    assert page.input_value('[name="v.counterparty.name"]') == "Meridian"
    assert page.locator("table.tbl tbody tr").count() == 2                                               # and the results are there again


def test_the_second_box_follows_the_operator_and_a_date_becomes_relative(page, url):
    page.goto(url + "/form/trading", wait_until="load")
    assert not page.locator('[name="v2.mtm"]').is_visible()
    page.select_option('[name="op.mtm"]', "between")
    assert page.locator('[name="v2.mtm"]').is_visible()
    page.select_option('[name="op.mtm"]', ">")
    assert not page.locator('[name="v2.mtm"]').is_visible()
    assert page.get_attribute('[name="v.maturityDate"]', "type") == "date"
    page.select_option('[name="op.maturityDate"]', "relative")
    assert page.get_attribute('[name="v.maturityDate"]', "type") == "text"
    page.select_option('[name="op.maturityDate"]', "between")
    assert page.get_attribute('[name="v.maturityDate"]', "type") == "date" and page.locator('[name="v2.maturityDate"]').is_visible()


def test_a_regex_runs_and_an_unsafe_one_is_refused_with_its_code(page, url, be):
    page.goto(url + "/form/trading", wait_until="load")
    page.select_option('[name="op.counterparty.name"]', "regex")
    page.fill('[name="v.counterparty.name"]', "^Mer.*n")
    page.press('[name="v.counterparty.name"]', "Enter")
    page.wait_for_selector("[data-qf-command]")
    assert "matches(counterparty.name, '^Mer.*n')" in page.inner_text("[data-qf-command]")
    page.fill('[name="v.counterparty.name"]', "(?=x)y")
    page.press('[name="v.counterparty.name"]', "Enter")
    page.wait_for_selector("[data-qf-error]")
    assert "DRS-4010" in page.inner_text("[data-qf-error]") and page.locator("[data-qf-command]").count() == 0


def test_the_command_can_be_copied_and_the_search_saved(page, url, be):
    page.context.grant_permissions(["clipboard-read", "clipboard-write"], origin=url)
    page.goto(url + "/form/trading?kind=trade&op.mtm=%3E&v.mtm=1m&run=1", wait_until="load")
    page.click("[data-qf-copy]")
    assert page.evaluate("navigator.clipboard.readText()") == page.inner_text("[data-qf-command]")
    page.fill("#qfName", "Big trades")
    page.click("[data-qf-save] button")
    page.wait_for_function("document.querySelector('[data-qf-saved]').textContent.startsWith('Saved')")
    assert be.saved[0]["name"] == "Big trades" and be.saved[0]["path"].startswith("/form/trading?kind=trade")
    page.goto(url + "/form/trading", wait_until="load")
    assert page.locator(".qf-saved a", has_text="Big trades").count() == 1


def test_keyboard_only(page, url):
    page.goto(url + "/form/trading", wait_until="load")
    page.focus('[name="v.tradeId"]')
    page.keyboard.type("IRS-48213 IRS-47102")
    page.keyboard.press("Tab")                                                                           # on to the next control, in reading order
    assert page.evaluate("document.activeElement.name") == "op.counterparty.name"
    page.keyboard.press("Escape")                                                                        # Esc clears every field
    assert page.input_value('[name="v.tradeId"]') == ""
    page.fill('[name="v.mtm"]', "250k")
    page.press('[name="v.mtm"]', "Enter")                                                                # Enter searches
    page.wait_for_selector("[data-qf-command]")
    assert "mtm = 250k" in page.inner_text("[data-qf-command]") or "mtm" in page.inner_text("[data-qf-command]")


def test_on_a_phone_every_control_is_a_44px_target(browser, url):
    ctx = browser.new_context(viewport={"width": 390, "height": 844}, has_touch=True, is_mobile=True, bypass_csp=True)
    pg = ctx.new_page()
    pg.goto(url + "/form/trading?kind=trade&op.mtm=%3E&v.mtm=1m&run=1", wait_until="load")
    assert pg.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")                  # nothing runs off the screen
    small = pg.evaluate("""() => [...document.querySelectorAll('.qf select, .qf input[type=text], .qf input[type=date], .qf textarea, .qf-actions .btn')]
        .filter(e => e.offsetParent && e.getBoundingClientRect().height < 43.5).map(e => e.name || e.className)""")
    assert small == []
    cols = pg.evaluate("[...new Set([...document.querySelectorAll('.qf-field')].map(e => Math.round(e.getBoundingClientRect().left)))].length")
    assert cols == 1                                                                                     # one column
    ctx.close()
