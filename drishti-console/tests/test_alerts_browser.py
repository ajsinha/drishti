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

"""Alerts on numbers in Chromium, over the real server and console (tests/alerts_stack.py: the sample report "Trades overview", a masked column, a local fake SMTP; no mail
leaves the machine). Run with the ports of this stack set by the environment, never with pytest -n:

    DRISHTI_BI_REPORT_SERVER_PORT=18951 DRISHTI_BI_REPORT_CONSOLE_PORT=17951 pytest tests/test_alerts_browser.py

rita (who sees every desk and MTM) presses *Alert me* on the MTM card of "Trades overview", asks for an alert when the total falls below -300,000 after a data load, and
checks the number it reads today (-225,000: inside the limit). The ETL adds losing trades and announces the load; a second later the alert fires and the row is in her inbox
(Alerts tab) with the number she is allowed to see; the Alerts page and the history say what was sent. ann, whose MTM is masked, makes the same alert: it never judges the
number and never shows it. On a phone (390 px) the Alerts page, the form, the history and the inbox do not scroll sideways and their targets are 44 px.

Skipped when Playwright, Chromium or the built server jar is missing, or a scratch port is in use."""
from __future__ import annotations

import time

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

import bi_report_stack  # noqa: E402
from alerts_stack import REPORT, AlertsStack  # noqa: E402
from bi_report_helpers import clean, open_report, settled, sign_in  # noqa: E402
from bi_report_stack import CONSOLE_URL  # noqa: E402
from fake_smtp import FakeSmtp  # noqa: E402


@pytest.fixture(scope="module")
def smtp():
    s = FakeSmtp().start()
    yield s
    s.stop()


@pytest.fixture(scope="module")
def stack(tmp_path_factory, smtp):
    for port in (bi_report_stack.SERVER_PORT, bi_report_stack.CONSOLE_PORT):
        if bi_report_stack.listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there")
    s = AlertsStack(tmp_path_factory.mktemp("alerts"), smtp.port)
    try:
        s.start()
    except FileNotFoundError as e:
        pytest.skip(str(e))
    yield s
    s.close()


@pytest.fixture(scope="module")
def browser(stack):
    with sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001
            pytest.skip(f"needs Playwright's Chromium: {str(e)[:100]}")
        yield b
        b.close()


def make_alert(page, name: str) -> None:
    """From the report: Alert me on the MTM card, a limit of -300,000 judged after each data load."""
    open_report(page, REPORT)
    settled(page)
    page.locator('[data-visual="mtm"] [data-act="alert-me"]').click()
    page.wait_for_url("**/alerts/new**")
    assert page.input_value('input[name="report"]') == REPORT and page.input_value('input[name="visual"]') == "mtm"
    page.fill('input[name="name"]', name)
    page.select_option('select[name="condition_type"]', "threshold")
    page.select_option('select[name="op"]', "below")
    page.fill('input[name="value"]', "-300000")
    page.select_option('select[name="trigger_mode"]', "after-load")
    page.click("[data-check-now]")


def test_create_an_alert_fire_it_with_a_load_and_see_it_in_the_inbox(stack, browser):
    page = sign_in(browser, "rita")
    make_alert(page, "MTM floor")
    expect(page.locator("[data-check-out]")).to_contain_text("Right now it reads -225,000", timeout=30000)
    expect(page.locator("[data-check-out]")).to_contain_text("inside the limit")
    # only the fields of the chosen kind are shown
    expect(page.locator('[data-group="cond"][data-when="change"]')).to_be_hidden()
    expect(page.locator('input[name="schedule"]')).to_be_hidden()
    page.click("[data-alert-form] button[type=submit]")
    page.wait_for_url("**/alerts?msg=**")
    row = page.locator('tr[data-alert-row="MTM floor"]')
    expect(row).to_contain_text("below -300,000")
    expect(row.locator("[data-standing]")).to_have_text("armed")
    expect(row).to_contain_text("after-load")

    # the ETL adds losing trades and says the data landed: the alert fires a second later
    stack.add_trades()
    time.sleep(2.5)                                                         # the file connector rescans every second
    status, body = stack.announce()
    assert status in (200, 201), body
    deadline = time.time() + 40
    while time.time() < deadline:
        page.goto(CONSOLE_URL + "/inbox?tab=alert")
        if page.locator(".inbox-row").count() >= 2:
            break
        time.sleep(1)
    inbox = page.locator(".inbox-row", has_text="MTM floor")
    expect(inbox).to_have_count(1)
    expect(inbox).to_contain_text("-725,000")                                # her own number
    expect(inbox.locator("a.inbox-open")).to_have_attribute("href", f"/bi/r/{REPORT}/overview")
    expect(page.locator(".inbox-tabs a.on")).to_have_text("Alerts")
    # the board tile that turned red tells her too, in the same load, and opens the board
    tile = page.locator(".inbox-row", has_text="Desk wall")
    expect(tile).to_contain_text("Mark to market — is now red")
    expect(tile.locator("a.inbox-open")).to_have_attribute("href", "/bi/r/file:report:desk-alerts")

    # the Alerts page: active now; the history says it was sent to the inbox
    page.goto(CONSOLE_URL + "/alerts")
    expect(page.locator('tr[data-alert-row="MTM floor"] [data-standing]')).to_have_text("active")
    expect(page.locator("[data-measure-log]")).to_contain_text("MTM floor")
    page.locator('tr[data-alert-row="MTM floor"]').get_by_role("link", name="Edit and history").click()
    page.wait_for_url("**/alerts/measure/MTM%20floor**")
    hist = page.locator("[data-history] tbody tr").first
    expect(hist).to_contain_text("fired")
    expect(hist).to_contain_text("inbox: ok")

    # pause and resume from the list; the Reports page leads here
    page.goto(CONSOLE_URL + "/alerts")
    page.locator('tr[data-alert-row="MTM floor"]').get_by_role("button", name="Pause").click()
    expect(page.locator('tr[data-alert-row="MTM floor"] [data-standing]')).to_have_text("paused")
    page.locator('tr[data-alert-row="MTM floor"]').get_by_role("button", name="Resume").click()
    page.goto(CONSOLE_URL + "/reports")                                       # Reports is an entry of Views; its page leads here
    expect(page.locator("nav.rep-links").get_by_text("My alerts")).to_be_visible()
    clean(page)
    page.context.close()


def test_the_prefilled_name_is_inside_the_allowed_characters_and_the_form_shows_titles(stack, browser):
    page = sign_in(browser, "rita")
    open_report(page, REPORT)
    settled(page)
    page.locator('[data-visual="mtm"] [data-act="alert-me"]').click()
    page.wait_for_url("**/alerts/new**")
    name = page.input_value('input[name="name"]')
    assert ":" not in name and name[0].isalnum(), name                                                       # "Trades overview: MTM" became "Trades overview - MTM"
    expect(page.locator("[data-name-rule]")).to_contain_text("Letters, digits")
    expect(page.locator("[data-visual-titles]")).to_contain_text("Report: ", timeout=15000)                  # titles beside the ids
    assert page.evaluate("document.querySelector('input[name=name]').checkValidity()") is True
    page.close()


def test_a_number_masked_for_a_person_is_never_judged_or_shown(stack, browser):
    page = sign_in(browser, "ann")                                          # ann's MTM is masked
    make_alert(page, "Ann MTM floor")
    expect(page.locator("[data-check-out]")).to_contain_text("masked for you", timeout=30000)
    assert "725" not in page.inner_text("[data-check-out]") and "225" not in page.inner_text("[data-check-out]")
    page.click("[data-alert-form] button[type=submit]")                    # Save looks first: a number masked for you is never saved as an alert
    expect(page.locator("[data-check-out]")).to_contain_text("Not saved", timeout=30000)
    assert "/alerts/new" in page.url
    page.goto(CONSOLE_URL + "/alerts")
    expect(page.locator('tr[data-alert-row="Ann MTM floor"]')).to_have_count(0)
    page.goto(CONSOLE_URL + "/inbox?tab=alert")
    assert page.locator(".inbox-row").count() == 0, "a masked number sends nothing, and the board tile is masked for her too"
    page.context.close()


def test_the_alert_pages_on_a_phone_do_not_scroll_sideways_and_have_44_px_targets(stack, browser):
    page = sign_in(browser, "rita", next_path="/alerts", viewport={"width": 390, "height": 800})
    problems = []
    for path in ("/alerts", "/alerts/measure/MTM%20floor", "/inbox?tab=alert", "/alerts/new?source=field&kind=trade&id=BBG-1007000&field=mtm"):
        page.goto(CONSOLE_URL + path)
        page.wait_for_load_state("load")
        wide = page.evaluate("document.documentElement.scrollWidth - window.innerWidth")
        if wide > 1:
            problems.append(f"{path} scrolls sideways by {wide} px")
        small = page.evaluate("""() => [...document.querySelectorAll('.adm a, .adm button, .adm input:not([type=hidden]):not([type=checkbox]), .adm select, .adm textarea, .adm summary')]
            .filter(e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0 && r.height < 43.5 && getComputedStyle(e).display !== 'inline'; })
            .map(e => (e.innerText || e.name || e.className || e.tagName).replace(/\\s+/g, ' ').slice(0, 30) + ' ' + Math.round(e.getBoundingClientRect().height))""")
        if small:
            problems.append(f"{path}: tap targets under 44 px: {small}")
    assert not problems, problems
    page.context.close()
