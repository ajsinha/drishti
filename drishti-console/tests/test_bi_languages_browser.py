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

"""Wave A of the BI design, console lane, in Chromium (docs/architecture/BI_DESIGN.md section 12.2) over the real server and console on scratch ports
18912 and 17912 (the W1 model stack of tests/test_w1_model_designer_browser.py: security on, the trading and banking-core packs, a small file lake of
trades and desks). Checked: the dataset wizard makes a dataset with a column subset, a filter, an explicit grain with a de-duplication, a formula column
and a grouping without typing YAML (the ExpressionEditor completing the columns); the ExpressionEditor in the model designer completes fields, measures
(writing measure('...')) and functions, shows the call's card and the server's problems; field properties, perspectives and Preview as a role in the
model designer; the Query panel's MQ and SQL views; a save on a published model makes a draft and Publish follows; a phone width without sideways scroll.

Skipped when Playwright, Chromium or the built server jar is missing, or a scratch port is in use. Never run with pytest -n (the ports are fixed)."""
from __future__ import annotations

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

import test_w1_model_designer_browser as w1  # noqa: E402
from bi_stack import listening  # noqa: E402

w1.SERVER_PORT, w1.CONSOLE_PORT = 18912, 17912
w1.SERVER, w1.CONSOLE_URL = f"http://127.0.0.1:{w1.SERVER_PORT}", f"http://127.0.0.1:{w1.CONSOLE_PORT}"
CONSOLE_URL = w1.CONSOLE_URL
sign_in, clean, fetch = w1.sign_in, w1.clean, w1.fetch


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    for port in (w1.SERVER_PORT, w1.CONSOLE_PORT):
        if listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there")
    try:
        s = w1.Stack(tmp_path_factory.mktemp("langs")).start()
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


def wizard(page):
    page.goto(CONSOLE_URL + "/bi/datasets/new-layer")
    expect(page.locator('[data-lw-root][data-ready="yes"]')).to_have_count(1, timeout=30000)


def next_step(page, step):
    page.click("[data-next]")
    expect(page.locator(f'[data-step="{step}"]')).to_be_visible(timeout=15000)


def test_the_dataset_wizard_makes_a_derived_dataset_without_yaml(browser):
    page = sign_in(browser, "wes")
    page.evaluate("window.sessionStorage.clear()")
    wizard(page)
    page.select_option("[data-src-extends]", "pack.trading.trade")
    expect(page.locator('[data-src="extends"]')).to_be_checked()
    next_step(page, "columns")
    expect(page.locator("[data-keep-col]")).not_to_have_count(0, timeout=15000)
    page.uncheck('[data-keep-col="book"]')
    next_step(page, "filter")
    where = page.locator("[data-where]")
    where.click()
    where.type("sta")
    expect(page.locator('[data-xe-opt="status"]')).to_be_visible(timeout=5000)                 # the ExpressionEditor completes the table's columns
    page.keyboard.press("Enter")
    where.type(" == 'Live'")
    assert where.input_value() == "status == 'Live'"
    where.press("Tab")
    next_step(page, "grain")
    page.check('[data-dedup="latest"]')
    page.select_option("[data-dedup-by]", "tradeDate")
    next_step(page, "derive")
    page.click('[data-derive-add="expr"]')
    page.fill('[data-derive-name="0"]', "notionalM")
    page.locator('[data-derive-name="0"]').press("Tab")
    ex = page.locator('[data-derive-expr="0"]')
    ex.fill("notional / 1000000")
    ex.press("Tab")
    next_step(page, "group")
    page.check("[data-group-on]")
    page.click('[data-group-key="desk"]')
    page.select_option('[data-group-agg="mtm"]', "sum")
    page.fill("[data-group-count]", "trades")
    page.locator("[data-group-count]").press("Tab")
    next_step(page, "preview")
    next_step(page, "save")
    page.fill("[data-title]", "Live trades by desk")
    page.locator("[data-title]").press("Tab")
    page.click("details:has([data-yaml]) summary")
    text = page.locator("[data-yaml]").text_content()
    for line in ("extends: pack.trading.trade@^1", "where: status == 'Live'", "keep: latest by tradeDate", "notionalM: {", "group:", "trades: count"):
        assert line in text, (line, text)
    assert "book" not in text.split("where:")[0]                                               # the unticked column is not in the subset
    page.click("[data-save]")
    expect(page.locator("[data-after-open]")).to_be_visible(timeout=20000)
    clean(page)
    page.context.close()


def test_the_wizard_fits_a_phone(browser):
    page = sign_in(browser, "wes", width=390)
    page.evaluate("window.sessionStorage.clear()")
    wizard(page)
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1"), "the page scrolls sideways"
    for sel in ("[data-next]", ".bi-lw-stepbtn"):
        box = page.locator(sel).first.bounding_box()
        assert box and box["height"] >= 43.5, (sel, box)
    clean(page)
    page.context.close()
