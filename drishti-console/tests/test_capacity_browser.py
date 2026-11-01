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

"""Admin -> Capacity in a real browser (docs/admin/OPERATIONS.md 12.4; RESOURCES_AND_SCALE.md 9.7), over a real server and console:

- the page opens on today against the plan: the four resources with a state in words, their headroom bars, what breaks first, this
  server beside the plan (its Java version measured), growth ×1 to ×3; the Capacity tab is the current one, after Caches;
- Edit the plan shows the planner: typing the trades changes the recommended -Xmx as you type, the field is marked changed, the YAML
  block and the command line follow, Reset to defaults puts it back;
- Download the working gives an .xlsx: both inputs, a Live column stamped with its time, every formula of the template;
- a 390 px phone never scrolls sideways, keeps the four states and what breaks first, and says the desk parts are on a desk;
- dark (terminal) and light both draw their colours; F1 opens the guide's section; no page error.

``DRISHTI_CAPACITY_SHOTS=DIR`` saves the guide's screenshots there (light theme): today.png, plan.png, download.png, phone.png.
Skipped when Playwright, Chromium, the built server jar or a JDK is missing."""
import io
import os
import zipfile
from pathlib import Path

import pytest

from test_workbench_browser import browser, wait  # noqa: F401 - fixtures and helpers
from wb_live import _stack

PHONE = {"width": 390, "height": 844}
SHOTS = os.environ.get("DRISHTI_CAPACITY_SHOTS")


def shot(pg, name, full=False):
    if SHOTS:
        Path(SHOTS).mkdir(parents=True, exist_ok=True)
        pg.screenshot(path=str(Path(SHOTS) / f"{name}.png"), full_page=full)


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    yield from _stack(tmp_path_factory, {"DRISHTI_OFFLINE": "true"})


def open_page(browser, stack, viewport=None, theme=None):
    pg = browser.new_page(viewport=viewport or {"width": 1500, "height": 950}, accept_downloads=True)
    pg.errors = []
    pg.on("pageerror", lambda e: pg.errors.append(str(e)))
    pg.on("console", lambda m: m.type == "error" and "Content Security Policy" in m.text and pg.errors.append(m.text))
    pg.goto(stack.console + "/admin/capacity")
    wait(pg, "document.querySelectorAll('[data-cap-kpi]').length === 4 && /Measured|unavailable/.test(document.querySelector('[data-cap-meta]').textContent)")
    if theme:
        pg.evaluate("t => document.documentElement.setAttribute('data-theme', t)", theme)
    return pg


def test_the_page_opens_on_today_against_the_plan(browser, stack):
    pg = open_page(browser, stack, theme="light" if SHOTS else None)
    shot(pg, "today", full=True)
    assert pg.get_attribute(".adm-tabs a[href='/admin/capacity']", "aria-current") == "page"
    tabs = pg.eval_on_selector_all(".adm-tabs a", "as => as.map(a => a.getAttribute('href'))")
    assert tabs.index("/admin/capacity") == tabs.index("/admin/caches") + 1
    states = pg.eval_on_selector_all("[data-cap-kpi]", "ks => ks.map(k => [k.dataset.capKpi, k.className.split(' ')[1], k.querySelector('.v').textContent])")
    assert [s[0] for s in states] == ["heap", "ram", "disk", "cores"]
    for _id, state, word in states:
        assert state in ("room", "tight", "short", "none") and word.strip(), states
    assert pg.locator("[data-cap-bar]").count() == 4
    assert pg.locator("[data-cap-breaks]").count() == 1 or "No live figures" in pg.inner_text("[data-cap-first]")
    live = pg.inner_text("[data-cap-live-table]")
    assert "Heap max (-Xmx)" in live and "MB" in live
    assert pg.locator("[data-cap-growth-row]").count() == 4
    assert "hl" in pg.get_attribute("[data-cap-growth-row='1.5']", "class")
    pg.click("[data-factor='3']")
    assert "hl" in pg.get_attribute("[data-cap-growth-row='3']", "class")
    pg.focus("[data-cap-bar='heap'] .cap-why")
    assert pg.is_visible("[data-cap-bar='heap'] .cap-tip")
    assert pg.errors == [], pg.errors


def test_typing_an_input_changes_the_recommended_heap(browser, stack):
    pg = open_page(browser, stack)
    pg.click("[data-cap-edit]")
    assert pg.is_visible("[data-cap-view='plan']") and not pg.is_visible("[data-cap-view='today']")
    pg.click("[data-cap-reset]")
    wait(pg, "document.querySelector('[data-cap-answer=heap]').textContent === '-Xmx10g'")
    pg.fill("#cap-in-Trades", "1,000,000")
    pg.fill("#cap-in-Positions", "1000000")
    wait(pg, "document.querySelector('[data-cap-answer=heap]').textContent === '-Xmx19g'")
    if SHOTS:
        pg.evaluate("document.documentElement.setAttribute('data-theme', 'light')")
        shot(pg, "plan", full=True)
    assert "ch" in pg.get_attribute("[data-cap-field='Trades']", "class")
    assert "-Xmx19g -Xms19g" in pg.inner_text("[data-cap-cmd]")
    assert "hot-dates: 10" in pg.inner_text("[data-cap-yaml]")
    pg.fill("#cap-in-HotDates", "12")
    wait(pg, "document.querySelector('[data-cap-yaml]').textContent.indexOf('hot-dates: 12') >= 0")
    pg.reload()
    wait(pg, "document.querySelector('#cap-in-Trades') && document.querySelector('#cap-in-Trades').value === '1,000,000'")
    pg.click("[data-cap-edit]")
    pg.click("[data-cap-reset]")
    wait(pg, "document.querySelector('[data-cap-answer=heap]').textContent === '-Xmx10g'")
    assert pg.errors == [], pg.errors


def test_download_the_working_gives_the_formula_workbook(browser, stack):
    pg = open_page(browser, stack)
    pg.click("[data-cap-download]")
    assert pg.is_visible("[data-cap-dialog]")
    pg.check("[data-cap-dialog] input[value=both]")
    if SHOTS:
        pg.evaluate("document.documentElement.setAttribute('data-theme', 'light')")
        shot(pg, "download")
    with pg.expect_download() as d:
        pg.click("[data-cap-dlg-go]")
    data = open(d.value.path(), "rb").read()
    assert d.value.suggested_filename.startswith("capacity-both-") and d.value.suggested_filename.endswith(".xlsx")
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        inputs = z.read("xl/worksheets/sheet1.xml").decode()
        results = z.read("xl/worksheets/sheet2.xml").decode()
        book = z.read("xl/workbook.xml").decode()
    assert "Live, measured " in inputs and "Measured on this server (not inputs)" in inputs
    assert "<f>CEILING(XmxRawMB/1024,1)</f>" in results
    assert 'definedName name="Trades"' in book and 'fullCalcOnLoad="1"' in book
    assert pg.errors == [], pg.errors


def test_on_a_phone_no_sideways_scroll_and_the_desk_parts_say_where_they_are(browser, stack):
    for theme in ("terminal", "light"):
        pg = open_page(browser, stack, PHONE, theme)
        assert pg.evaluate("document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1"), \
            pg.evaluate("[document.documentElement.scrollWidth, document.documentElement.clientWidth]")
        assert pg.is_visible("[data-cap-kpis]") and pg.is_visible("[data-cap-first]")
        assert not pg.is_visible("[data-cap-download]") and not pg.is_visible("[data-cap-edit]")
        assert pg.is_visible("[data-cap-phone-note]")
        assert pg.get_attribute("details[data-tier='more']", "open") is None
        if theme == "light":
            shot(pg, "phone", full=True)
        pg.close()


def test_dark_and_light_draw_and_f1_opens_the_guide(browser, stack):
    seen = {}
    for theme in ("terminal", "light"):
        pg = open_page(browser, stack, theme=theme)
        seen[theme] = pg.evaluate("""() => {
          const k = getComputedStyle(document.querySelector('.cap-kpi')), t = getComputedStyle(document.querySelector('.cap-track i'));
          return [k.backgroundColor, k.color, t.backgroundColor];
        }""")
        assert all(c and c != "rgba(0, 0, 0, 0)" for c in seen[theme]), (theme, seen[theme])
        if theme == "light":
            pg.keyboard.press("F1")
            pg.wait_for_url("**/help/**")
            assert "#124-admin--capacity-today-against-the-plan" in pg.url
            assert pg.locator("#124-admin--capacity-today-against-the-plan").count() == 1
        assert pg.errors == [], pg.errors
        pg.close()
    assert seen["terminal"][0] != seen["light"][0]
