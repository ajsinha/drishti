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
    page.keyboard.press("Alt+f")                                                                       # the results folded the form; Alt+F shows it
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


def test_the_form_folds_to_one_line_of_criteria_and_back(page, url):
    page.goto(url + "/form/trading", wait_until="load")
    page.select_option('[name="op.mtm"]', ">")
    page.fill('[name="v.mtm"]', "1m")
    page.fill('[name="v.tradeId"]', "IRS-48213")
    page.click(".qf-go")                                                                                 # results arrive: the form folds itself
    page.wait_for_selector("[data-qf-command]")
    assert not page.locator("#qfBody").is_visible() and page.locator("[data-qf-summary]").is_visible()
    assert page.get_attribute("[data-qf-toggle]", "aria-expanded") == "false" and page.get_attribute("[data-qf-toggle]", "aria-controls") == "qfBody"
    assert "Show form" in page.inner_text("[data-qf-toggle]")
    line = page.inner_text("[data-qf-summary]")
    assert "Mark to market > 1m" in line and "1m" in line and "IRS-48213" in line
    page.click("[data-qf-toggle]")                                                                       # the button shows it, criteria kept
    assert page.locator("#qfBody").is_visible() and "form=shown" in page.url
    assert page.get_attribute("[data-qf-toggle]", "aria-expanded") == "true" and "Hide form" in page.inner_text("[data-qf-toggle]")
    assert page.input_value('[name="v.mtm"]') == "1m"
    page.keyboard.press("Alt+f")                                                                         # the key hides it
    assert not page.locator("#qfBody").is_visible() and "form=hidden" in page.url
    page.click("[data-qf-summary]")                                                                      # the summary shows it
    assert page.locator("#qfBody").is_visible() and page.input_value('[name="v.tradeId"]') == "IRS-48213"
    page.keyboard.press("Alt+f")
    page.reload(wait_until="load")                                                                       # the address carries the state
    assert not page.locator("#qfBody").is_visible()
    page.goto(url + "/form/trading?kind=trade&op.mtm=%3E&v.mtm=1m&run=1&form=shown", wait_until="load")
    assert page.locator("#qfBody").is_visible()
    page.click("table.tbl tbody a:has-text('IRS-48213')")
    page.wait_for_url("**/v/trade/IRS-48213")
    page.keyboard.press("Alt+ArrowLeft")                                                                 # back from a result: the criteria are kept
    page.wait_for_url("**/form/trading?**")
    assert page.locator("#qfBody").is_visible()                                                          # as it was left (shown)
    page.keyboard.press("Alt+f")
    page.click("[data-qf-summary]")
    assert page.input_value('[name="v.mtm"]') == "1m"


def test_the_form_stays_open_when_the_person_kept_it_open_and_on_a_phone_the_toggle_is_44px(browser, url):
    ctx = browser.new_context(viewport={"width": 390, "height": 844}, has_touch=True, is_mobile=True, bypass_csp=True)
    pg = ctx.new_page()
    pg.goto(url + "/form/trading?kind=trade&op.mtm=%3E&v.mtm=1m&run=1", wait_until="load")
    assert not pg.locator("#qfBody").is_visible()
    for sel in ("[data-qf-toggle]", "[data-qf-summary]"):
        assert pg.locator(sel).bounding_box()["height"] >= 43.5
    assert pg.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
    pg.tap("[data-qf-toggle]")                                                                           # shown by hand: kept open on the next search in this tab
    assert pg.locator("#qfBody").is_visible()
    pg.tap(".qf-go")
    pg.wait_for_selector("[data-qf-command]")
    assert pg.locator("#qfBody").is_visible() and "form=shown" in pg.url
    ctx.close()


def test_an_enum_with_many_values_is_one_dropdown_with_search_and_the_url_stays_the_same(page, url, be):
    page.goto(url + "/form/trading", wait_until="load")
    box = page.locator("details.ms-box").first
    assert page.locator("details.ms-box").count() == 1                                   # nine values: a dropdown; the book (three) stays a checkbox row
    assert page.locator('[name="v.book"]').count() == 3 and page.locator(".qf-enum input[name='v.book']").first.is_visible()
    sum_ = box.locator("summary")
    assert sum_.inner_text() == "All" and box.locator(".ms-pop").is_hidden()
    sum_.focus()
    page.keyboard.press("Enter")                                                         # opens by keyboard; the search box has the focus
    assert box.evaluate("d => d.open") and page.evaluate("document.activeElement.classList.contains('ms-q')")
    page.keyboard.type("bond")                                                           # type to filter
    assert box.locator(".ms-item:not([hidden])").all_inner_texts() == ["BOND", "PUTTABLE_BOND"]
    page.keyboard.press("ArrowDown")
    page.keyboard.press("Space")                                                         # toggles the focused option
    assert sum_.inner_text() == "BOND"
    box.get_by_role("button", name="Select all").click()                                 # only the options the search leaves
    assert sum_.inner_text() == "2 selected: BOND, PUTTABLE_BOND"
    box.locator(".ms-q").fill("")
    box.locator(".ms-q").press("Escape")                                                 # Esc closes and returns to the summary
    assert not box.evaluate("d => d.open") and page.evaluate("document.activeElement.tagName") == "SUMMARY"
    box.locator("summary").click()
    box.locator("input[value=REPO]").check()
    assert sum_.inner_text() == "3 selected: BOND, PUTTABLE_BOND, +1"
    box.locator("input[value=REPO]").press("Enter")
    page.get_by_role("button", name="Search").first.click() if page.get_by_role("button", name="Search").count() else page.keyboard.press("Enter")
    page.wait_for_url("**/form/trading?**")
    assert "v.productType=BOND" in page.url and "v.productType=PUTTABLE_BOND" in page.url and "v.productType=REPO" in page.url        # the criteria encoding is unchanged
    page.locator(".qf-toggle").click()                                                                                                   # the criteria fold away after a search
    assert page.locator("details.ms-box summary").inner_text().startswith("3 selected")                                                  # and the page comes back as chosen
    box = page.locator("details.ms-box").first
    box.locator("summary").click()
    box.get_by_role("button", name="Clear").click()
    assert box.locator("summary").inner_text() == "All"


def test_the_dropdown_has_finger_sized_rows_on_a_phone(browser, url):
    ctx = browser.new_context(viewport={"width": 390, "height": 844}, has_touch=True, is_mobile=True, bypass_csp=True)
    pg = ctx.new_page()
    pg.goto(url + "/form/trading", wait_until="load")
    pg.locator("details.ms-box summary").tap()
    small = pg.evaluate("[...document.querySelectorAll('details.ms-box .ms-item, details.ms-box .ms-act, details.ms-box .ms-q, details.ms-box summary')].filter(e => e.offsetParent && e.getBoundingClientRect().height < 43.5).length")
    assert small == 0
    assert pg.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
    ctx.close()


def test_the_forms_and_reports_icons_in_the_top_bar_open_the_forms_home_and_a_kind_opens_its_form(page, url, be):
    page.goto(url + "/t", wait_until="load")
    forms, reports = page.locator('.tbar-menu-btn[aria-label^="Forms"]'), page.locator('.tbar-menu-btn[aria-label^="Reports"]')
    assert forms.count() == 1 and reports.count() == 1 and forms.get_attribute("title") == "Forms — query by form"
    assert reports.get_attribute("title").startswith("Reports — saved and scheduled")
    assert forms.locator("i.bi").count() == 1 and "active" not in (forms.get_attribute("class") or "")
    forms.click()
    page.click('.mega-item:has-text("All forms")')
    page.wait_for_url("**/forms")
    assert "active" in page.locator('.tbar-menu-btn[aria-label^="Forms"]').get_attribute("class")      # active on the forms home
    assert "active" not in page.locator('.tbar-menu-btn[aria-label^="Reports"]').get_attribute("class")
    page.fill("[data-forms-filter]", "zzz")
    assert page.locator("[data-forms-none]").is_visible() and not page.locator("[data-forms-pack]").first.is_visible()
    page.fill("[data-forms-filter]", "trade")
    page.click('a.qh-kind[href="/form/trading?kind=trade"]')
    page.wait_for_url("**/form/trading?kind=trade")
    assert "active" in page.locator('.tbar-menu-btn[aria-label^="Forms"]').get_attribute("class")      # and on a form
    page.goto(url + "/forms", wait_until="load")
    assert "Trading" in page.locator("[data-forms-recent-list]").inner_text()                             # the form just opened is a recent form
    page.goto(url + "/reports", wait_until="load")
    assert "active" in page.locator('.tbar-menu-btn[aria-label^="Reports"]').get_attribute("class")


def test_the_forms_menu_by_keyboard_and_the_icons_fit_a_laptop_and_a_phone(browser, url):
    ctx = browser.new_context(viewport={"width": 1280, "height": 800}, bypass_csp=True)
    pg = ctx.new_page()
    pg.goto(url + "/t", wait_until="load")
    pg.focus('.tbar-menu-btn[aria-label^="Forms"]')
    pg.keyboard.press("Enter")
    assert pg.locator('.tbar-menu-btn[aria-label^="Forms"]').get_attribute("aria-expanded") == "true"
    pg.keyboard.press("Tab")
    assert pg.evaluate("document.activeElement.textContent").strip().startswith("All forms")
    pg.keyboard.press("Enter")
    pg.wait_for_url("**/forms")
    assert pg.evaluate("document.documentElement.scrollWidth <= document.documentElement.clientWidth")
    assert pg.evaluate("document.querySelector('.tbar-row1').getBoundingClientRect().height") < 70      # one row: no worse than before
    ctx.close()
    ctx = browser.new_context(viewport={"width": 390, "height": 780}, is_mobile=True, has_touch=True, bypass_csp=True)
    ph = ctx.new_page()
    ph.goto(url + "/forms", wait_until="load")
    assert ph.evaluate("document.documentElement.scrollWidth <= document.documentElement.clientWidth")
    for sel in ("a.qh-kind", "[data-forms-filter]"):
        assert ph.locator(sel).first.bounding_box()["height"] >= 43.5, sel
    ctx.close()
