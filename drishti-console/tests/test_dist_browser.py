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

"""Distribution in Chromium, over the real server and console (tests/bi_report_stack.py, with a masked dataset and a local fake SMTP: tests/fake_smtp.py; no
mail leaves the machine). Run with the ports of this stack set by the environment, never with pytest -n:

    DRISHTI_BI_REPORT_SERVER_PORT=18950 DRISHTI_BI_REPORT_CONSOLE_PORT=17950 pytest tests/test_dist_browser.py

wes (a BI author) opens the sample report "Trades overview" and exports the page as Excel (typed sheets) and PDF; subscribes rita (sees everything) and hana (a
desk head, one desk) and ann (an analyst whose MTM is masked), presses Run now, and the fake SMTP holds three different mails, each with that person's own
figures; the subscription list shows the run; pausing and resuming change it; a phone shows the list with tap targets of 44 px.

Skipped when Playwright, Chromium or the built server jar is missing, or a scratch port is in use."""
from __future__ import annotations

import io
import re
import time
import zipfile

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

import bi_report_stack  # noqa: E402
from bi_report_helpers import clean, open_report, settled, sign_in  # noqa: E402
from bi_report_stack import CONSOLE_URL  # noqa: E402
from dist_stack import REPORT, DistStack  # noqa: E402
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
    s = DistStack(tmp_path_factory.mktemp("dist"), smtp.port)
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


def test_export_subscribe_run_now_and_the_list(stack, browser, smtp, tmp_path):
    page = sign_in(browser, "wes")
    open_report(page, REPORT)
    settled(page)

    # -- export the page: Excel with typed sheets, PDF paged
    expect(page.locator("[data-export]")).to_be_visible()
    with page.expect_download() as d:
        page.click('[data-export-fmt="xlsx"]')
    xlsx = tmp_path / d.value.suggested_filename
    d.value.save_as(xlsx)
    assert xlsx.name.endswith(".xlsx")
    with zipfile.ZipFile(xlsx) as z:
        names = z.namelist()
        workbook = z.read("xl/workbook.xml").decode()
        assert "About" in workbook and "MTM by desk" in workbook and "xl/worksheets/sheet3.xml" in names
        sheets = [z.read(n).decode() for n in names if n.startswith("xl/worksheets/sheet")]
    assert any("DESK-FX" in s and re.search(r'<c r="B\d+" s="[23]"><v>-?\d', s) for s in sheets), "a desk sheet with numbers typed as numbers"
    with page.expect_download() as d:
        page.click('[data-export-fmt="pdf"]')
    pdf = tmp_path / "page.pdf"
    d.value.save_as(pdf)
    assert pdf.read_bytes().startswith(b"%PDF-1.4") and d.value.suggested_filename.endswith(".pdf")
    page.select_option("[data-export-scope]", "by-desk")
    with page.expect_download() as d:
        page.click('[data-export-fmt="csv"]')
    csv = tmp_path / "one.csv"
    d.value.save_as(csv)
    text = csv.read_text(encoding="utf-8-sig")
    assert text.startswith("DESK,MTM") and "DESK-FX" in text and "Trades overview" not in text.splitlines()[0]
    clean(page)

    # -- subscribe from the report, with the screen's state (the whole page)
    page.select_option("[data-export-scope]", "")
    page.click("[data-subscribe]")
    page.wait_for_url("**/reports/subscribe**")
    assert "Trades overview" in page.inner_text("main, .adm")
    page.fill('input[name="name"]', "Morning desk pack")
    page.fill('input[name="recipients"]', "rita, hana, ann")
    page.select_option('select[name="format"]', "html")
    page.click("[data-when] summary")                                                   # the schedule text sits under Advanced
    page.fill('input[name="schedule"]', "business-days 07:30")
    page.click("[data-subscribe-form] button[type=submit]")
    page.wait_for_url("**/reports?msg=**")
    row = page.locator('tr[data-row-name="Morning desk pack"]')
    expect(row).to_contain_text("business-days 07:30")
    expect(row).to_contain_text("rita, hana, ann")

    # -- run now: three people, three renders
    row.get_by_role("button", name="Run now").click()
    expect(page.locator(".adm-msg")).to_contain_text("Morning desk pack: 3 sent")
    deadline = time.time() + 20
    while time.time() < deadline and not all(smtp.to(a) for a in ("rita@desk.test", "hana@desk.test", "ann@desk.test")):
        time.sleep(0.1)
    rita, hana, ann = [FakeSmtp.html(smtp.to(f"{u}@desk.test")[0]) for u in ("rita", "hana", "ann")]
    assert "DESK-FX" in rita and "DESK-FI" in rita and "DESK-EQD" in rita
    assert "DESK-FX" in hana and "DESK-FI" not in hana and "DESK-EQD" not in hana, "a desk head's mail holds her desk only"
    assert "DESK-FI" in ann and rita != ann and hana != ann, "three different mails"
    assert re.search(r"-?\d[\d,]*", rita) and "Mark to market" in rita
    assert page.locator('tr[data-row-name="Morning desk pack"] [data-last-status]').inner_text() == "ok"
    page.locator('tr[data-row-name="Morning desk pack"] summary').click()
    expect(page.locator('tr[data-row-name="Morning desk pack"] .dist-runs')).to_contain_text("rita@desk.test · sent")

    # -- pause and resume; the menu entry
    row.get_by_role("button", name="Pause").click()
    expect(page.locator(".adm-msg")).to_contain_text("Paused Morning desk pack")
    expect(page.locator('tr[data-row-name="Morning desk pack"]')).to_contain_text("paused")
    page.locator('tr[data-row-name="Morning desk pack"]').get_by_role("button", name="Resume").click()
    expect(page.locator(".adm-msg")).to_contain_text("Resumed Morning desk pack")
    page.click('.tbar-menu-btn[title^="Views"]')               # Reports lives in Views (one top bar)
    expect(page.locator(".mega-panel.show, .dropdown-menu.show").locator('a[href="/reports"]').first).to_be_visible()
    clean(page)

    # -- a PDF subscription mails the PDF
    smtp.messages.clear()
    page.goto(CONSOLE_URL + "/reports/subscribe?type=report&id=" + REPORT + "&page=overview&title=PDF+pack")
    page.fill('input[name="recipients"]', "rita")
    page.select_option('select[name="format"]', "pdf")
    page.click("[data-subscribe-form] button[type=submit]")
    page.wait_for_url("**/reports?msg=**")
    page.locator('tr[data-row-name="PDF pack"]').get_by_role("button", name="Run now").click()
    expect(page.locator(".adm-msg")).to_contain_text("PDF pack: 1 sent")
    deadline = time.time() + 20
    while time.time() < deadline and not smtp.to("rita@desk.test"):
        time.sleep(0.1)
    files = FakeSmtp.attachments(smtp.to("rita@desk.test")[0])
    assert files and files[0][0].endswith(".pdf") and files[0][1].startswith(b"%PDF-")

    # -- the same on a Sutra view
    page.goto(CONSOLE_URL + "/v/trade/BBG-1007000?ctl.x=1")
    if page.locator("[data-export-menu]").count():
        page.click("[data-export-menu] button")
        with page.expect_download() as d:
            page.click('[data-export-fmt="xlsx"]')
        assert d.value.suggested_filename.endswith(".xlsx")
    page.context.close()


def test_the_list_on_a_phone_has_tap_targets_of_44_px(stack, browser):
    page = sign_in(browser, "wes", next_path="/reports", viewport={"width": 390, "height": 800})
    page.goto(CONSOLE_URL + "/reports")
    buttons = page.locator(".dist-act, .dist-link")
    small = [b.inner_text() for b in buttons.all() if b.is_visible() and (b.bounding_box() or {"height": 99})["height"] < 43.5]
    assert not small, f"tap targets under 44 px: {small}"
    page.context.close()


def test_a_reader_who_may_not_open_the_report_is_refused_the_file(stack, browser):
    page = sign_in(browser, "nobi", next_path="/bi")
    r = page.request.get(CONSOLE_URL + "/dist/bi/" + REPORT + ".xlsx?page=overview")
    assert r.status in (403, 404) and "DRS-" in r.text()
    page.context.close()
