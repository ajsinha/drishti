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

"""Analyse (docs/guides/ANALYSE.md) in Chromium over the real server and console (tests/bi_report_stack.py on ports 18956 and 17956, security on, a small file lake).
A BI author opens the trades dataset as an extract, slices it (desk by notional), runs a SQL cell and a Python cell (on the server, with quant) and checks that the three
agree, saves a notebook, and the page fits a 390 px phone. The page keeps the strict policy (no violation, no WebAssembly).

Skipped when Playwright, Chromium, the built server jar or pandas and pyarrow are missing, or a scratch port is in use. Never run with pytest -n (the ports are fixed)."""
import os

import pytest

os.environ.setdefault("DRISHTI_BI_REPORT_SERVER_PORT", "18956")
os.environ.setdefault("DRISHTI_BI_REPORT_CONSOLE_PORT", "17956")
pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
pytest.importorskip("pandas")
pytest.importorskip("pyarrow")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

import bi_report_stack  # noqa: E402
from bi_report_helpers import clean, sign_in  # noqa: E402
from bi_report_stack import CONSOLE_URL  # noqa: E402


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    for port in (bi_report_stack.SERVER_PORT, bi_report_stack.CONSOLE_PORT):
        if bi_report_stack.listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there")
    try:
        s = bi_report_stack.ReportStack(tmp_path_factory.mktemp("analyse"), delta_root="").start()
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


def grid(page, selector):
    """A rendered table as rows of text."""
    return page.eval_on_selector_all(selector + " tbody tr", "rows => rows.map(r => [...r.children].map(c => c.textContent))")


def open_extract(page):
    page.goto(CONSOLE_URL + "/bi/explore")
    page.wait_for_function("() => document.querySelectorAll('[data-dataset] option').length > 1", timeout=30000)
    page.select_option("[data-dataset]", "mq:trades-basic@1")                     # the shipped sample model (2.2)
    page.wait_for_selector("[data-fields] input[name=f]", timeout=30000)
    page.click("[data-open-go]")
    page.wait_for_function("() => /rows,/.test((document.querySelector('[data-info]') || {}).textContent || '')", timeout=60000)            # the line is drawn once the extract is in


def test_slice_sql_and_python_agree_and_the_notebook_saves(stack, browser):
    page = sign_in(browser, "wes", "/bi")
    open_extract(page)
    rows = int(page.inner_text("[data-info]").split(" rows")[0])
    assert rows > 0

    # SLICE: desk in rows, notional summed in values
    page.click('.bx-chip[data-field="DESK"] [data-to="rows"]')
    page.click('.bx-chip[data-field="NOTIONAL"] [data-to="values"]')
    page.wait_for_selector("[data-pivot] table")
    pivot = {r[0]: float(r[1]) for r in grid(page, "[data-pivot]")}
    assert len(pivot) >= 2 and sum(pivot.values()) > 0

    # SQL: the same sums from the extract
    page.click("#bxT-sql")
    page.fill('[data-cells=sql] textarea', "SELECT DESK, SUM(NOTIONAL) AS n FROM extract GROUP BY DESK ORDER BY DESK")
    page.click('[data-cells=sql] [data-run]')
    page.wait_for_selector("[data-cells=sql] table")
    sql = {r[0]: float(r[1]) for r in grid(page, "[data-cells=sql]")}
    assert sql == pytest.approx(pivot)
    # promoting a query with implicit aliases (no AS) works, and the message links the draft
    page.fill('[data-cells=sql] textarea', "SELECT DESK d, SUM(NOTIONAL) n FROM extract GROUP BY DESK")
    page.click('[data-cells=sql] [data-promote-report]')
    expect(page.locator("[data-promoted-link]")).to_be_visible(timeout=30000)
    said = page.inner_text('[data-cells=sql] [data-promote-report] + .bi-near')                     # the answer is beside the button
    assert "not read" not in said and "left out" not in said.replace("ORDER BY and LIMIT were left out", "")
    page.fill('[data-cells=sql] textarea', "SELECT DESK, SUM(NOTIONAL) AS n FROM extract GROUP BY DESK ORDER BY DESK")

    # PYTHON on the server: pandas over df, quant through the stand-in engine
    page.click("#bxT-python")
    page.fill('[data-cells=python] textarea',
              "print(round(quant.price('black_scholes', spot=100, strike=100, rate=0.05, vol=0.2, years=1), 4))\nresult = df.groupby('DESK')['NOTIONAL'].sum().reset_index()")
    page.click('[data-cells=python] [data-run]')
    page.wait_for_selector("[data-cells=python] table", timeout=60000)
    assert page.inner_text("[data-cells=python] [data-stdout]").strip() == "10.4506"
    assert page.get_attribute("[data-cells=python] [data-py-foot]", "data-py-foot") == "server"
    py = {r[0]: float(r[1]) for r in grid(page, "[data-cells=python]")}
    assert py == pytest.approx(pivot)

    # SAVE as a notebook; the item opens back in Analyse
    page.fill("input[name=title]", "Desk notional, first look")
    page.click("[data-save-go]")
    page.wait_for_selector("[data-saved]:not([hidden])", timeout=30000)
    page.click("[data-saved]")
    page.wait_for_selector("[data-open-analyse]:not([hidden])", timeout=30000)
    page.click("[data-open-analyse]")
    page.wait_for_function("() => /rows,/.test((document.querySelector('[data-info]') || {}).textContent || '')", timeout=60000)            # the line is drawn once the extract is in
    page.wait_for_selector("[data-pivot] table")
    assert {r[0]: float(r[1]) for r in grid(page, "[data-pivot]")} == pytest.approx(pivot)
    assert page.locator("[data-cells=sql] textarea").input_value().startswith("SELECT DESK")
    clean(page)
    page.close()


def test_the_page_fits_a_phone(stack, browser):
    page = sign_in(browser, "wes", "/bi", viewport={"width": 390, "height": 844})
    open_extract(page)
    page.click('.bx-chip[data-field="DESK"] [data-to="rows"]')
    page.wait_for_selector("[data-pivot] table")
    for tab in ("sql", "python", "slice"):
        page.click(f"#bxT-{tab}")
        assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1"), tab
    clean(page)
    page.close()
