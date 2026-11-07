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

"""The Notebook (docs/guides/USER_GUIDE.md › The Notebook) in Chromium over the real server and console (tests/bi_report_stack.py on ports 18956 and 17956,
security on, a small file lake). A BI author opens the trades model as an extract, runs a SQL cell (Drishti SQL, run by the server), promotes it to a report
draft, runs a Python cell on the server's compute pool (nothing runs in the console or the browser) and sees the sandbox refuse a file, opens the Profile, saves the notebook and opens it again from its
item page; every button the page draws is clicked; the old addresses /bi/explore and /pyconsole land here; the page fits a 390 px phone. The page keeps the
strict policy (no violation, no WebAssembly).

Skipped when Playwright, Chromium, the built server jar or pandas and pyarrow are missing, or a scratch port is in use. Never run with pytest -n (the ports are fixed)."""
import os
import sys

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
        s = bi_report_stack.ReportStack(tmp_path_factory.mktemp("analyse"), delta_root="")
        # Python cells run on the server's compute pool python, in the interpreter that runs this test (it carries the pinned libraries)
        s.server_args += ["--DRISHTI_COMPUTE_ENABLED=true", f"--DRISHTI_PYTHON={sys.executable}",
                          f"--drishti.compute.file={bi_report_stack.ROOT}/config/compute/compute.yaml"]
        s = s.start()
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
    page.goto(CONSOLE_URL + "/bi/notebook")
    page.wait_for_function("() => document.querySelectorAll('[data-dataset] option').length > 1", timeout=30000)
    page.select_option("[data-dataset]", "mq:trades-basic@1")                     # the shipped sample model (2.2)
    page.wait_for_selector("[data-fields] input[name=f]", timeout=30000)
    page.click("[data-open-go]")
    page.wait_for_function("() => /rows,/.test((document.querySelector('[data-info]') || {}).textContent || '')", timeout=60000)            # the line is drawn once the extract is in


def test_sql_and_python_run_on_the_server_and_the_notebook_saves(stack, browser):
    page = sign_in(browser, "wes", "/bi")
    errors = []
    page.on("console", lambda m: errors.append(m.text) if m.type == "error" else None)
    open_extract(page)
    assert int(page.inner_text("[data-info]").split(" rows")[0]) > 0
    assert page.locator("[data-tab=slice]").count() == 0 and page.get_attribute("[data-open-pivot]", "href") == "/pivot"

    # SQL: the starter statement (a field and the model's first measure) run by the server as the reader
    assert page.get_attribute("#bxT-sql", "aria-selected") == "true"
    sql = page.locator("[data-cells=sql] textarea").first.input_value()
    assert "MEASURE(" in sql and "extract" not in sql
    page.click("[data-cells=sql] [data-run]")
    page.wait_for_selector("[data-cells=sql] table", timeout=60000)
    assert page.get_attribute("[data-cells=sql] [data-engine]", "data-engine") == "dsql" and "run by the server" in page.inner_text("[data-cells=sql] [data-engine]")
    assert len(grid(page, "[data-cells=sql]")) >= 1
    page.click("[data-add=sql]")                                                                       # + SQL cell, then Run all, then delete it again
    assert page.locator("[data-cells=sql] .bx-cell").count() == 2
    page.click("[data-run-all=sql]")
    page.wait_for_function("() => document.querySelectorAll('[data-cells=sql] table').length === 2", timeout=60000)
    page.locator("[data-cells=sql] .bx-cell").nth(1).locator("button[aria-label^='Delete cell']").click()
    assert page.locator("[data-cells=sql] .bx-cell").count() == 1
    # promote the SQL cell to a new report draft: the answer is beside the button, with a link to the draft
    page.click("[data-cells=sql] [data-promote-report]")
    expect(page.locator("[data-promoted-link]")).to_be_visible(timeout=30000)

    # PYTHON: written here, run on the server's compute pool over the extract the server reads as this person
    page.click("#bxT-python")
    cell = page.locator("[data-cells=python] .bx-cell").first
    assert cell.locator("[data-runs-on]").inner_text() == "Runs on the server's compute pool"
    assert cell.locator("[data-uc]").count() == 0 and cell.locator("[data-run]").is_enabled()
    cell.locator("textarea").fill("print(len(df))\nresult = df.head(3)")
    cell.locator("[data-run]").click()
    expect(cell.locator("[data-engine=python]")).to_be_visible(timeout=120000)
    assert cell.locator("[data-stdout]").inner_text().strip().isdigit() and cell.locator("[data-result] table").count() == 1
    cell.locator("textarea").fill("open('/etc/hostname').read()")                                     # the sandbox refuses in words, the cell shows it
    cell.locator("[data-run]").click()
    expect(cell.locator("[role=alert]")).to_contain_text("DRS-9073", timeout=120000)
    page.click("[data-add=python]")
    assert page.locator("[data-cells=python] .bx-cell").count() == 2
    page.locator("[data-cells=python] .bx-cell").nth(1).locator("[data-promote=visual]").click()        # promoting writes a draft; nothing runs
    expect(page.locator("[data-cells=python] [data-promoted-link]")).to_be_visible(timeout=30000)
    assert page.locator("[data-cells=python] .bx-cell").nth(1).locator("[data-stdout]").count() == 0

    # PROFILE: every column of the extract, no code of the reader's
    page.click("#bxT-profile")
    page.wait_for_function("() => /columns|rows/.test((document.querySelector('[data-profile-meta]') || {}).textContent || '')", timeout=60000)

    # SAVE as a notebook; the item opens back in the Notebook
    page.fill("input[name=title]", "Desk notional, first look")
    page.click("[data-save-go]")
    page.wait_for_selector("[data-saved]:not([hidden])", timeout=30000)
    page.click("[data-saved]")
    page.wait_for_selector("[data-open-notebook]:not([hidden])", timeout=30000)
    page.click("[data-open-notebook]")
    page.wait_for_function("() => /rows,/.test((document.querySelector('[data-info]') || {}).textContent || '')", timeout=60000)
    assert page.locator("[data-cells=sql] textarea").first.input_value() == sql
    assert page.locator("[data-cells=python] .bx-cell").count() == 2
    assert not [e for e in errors if "Content Security Policy" in e or "WebAssembly" in e], errors
    clean(page)
    page.close()


def test_the_old_addresses_land_on_the_notebook(stack, browser):
    page = sign_in(browser, "wes", "/bi")
    for old in ("/bi/explore", "/pyconsole"):
        page.goto(CONSOLE_URL + old)
        page.wait_for_url("**/bi/notebook**")
        assert page.inner_text("h1") == "Notebook"
    page.click(".bi-rail-link[href='/pivot']")                                                         # the rail's Pivot: the one grid
    page.wait_for_url("**/pivot**")
    clean(page)
    page.close()


def test_the_page_fits_a_phone(stack, browser):
    page = sign_in(browser, "wes", "/bi", viewport={"width": 390, "height": 844})
    open_extract(page)
    for tab in ("python", "profile", "sql"):
        page.click(f"#bxT-{tab}")
        assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1"), tab
    clean(page)
    page.close()
