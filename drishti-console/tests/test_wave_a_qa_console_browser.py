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

"""The console fixes of the Wave A experience QA, in Chromium over the real server on scratch ports 18909 and 17909 (the stack of
test_w1_model_designer_browser.py, its jar taken from the main checkout when the worktree has none): Ctrl+S saves the field being typed in and the
Contract tab redraws without an error; the server autosaves the draft and a stale save opens the merge view; a hidden measure names the columns that
still reach it and hides them in one click; the expression editor's own fixes (a missing bracket, = for ==); the tab lists take the arrow keys; a
visual hidden for the reader's role says so without Retry; the free row rule and the Tests designer write the model's YAML.

Skipped when Playwright, Chromium or a built server jar is missing, or a scratch port is in use. Each pytest-xdist worker adds 100 to the ports (tests/scratch_ports.py)."""
from __future__ import annotations

import os
from pathlib import Path

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect  # noqa: E402

import test_w1_model_designer_browser as base  # noqa: E402

MAIN = Path(os.environ.get("DRISHTI_MAIN_CHECKOUT", "/home/ashutosh/IdeaProjects/drishti"))
if not list((base.ROOT / "drishti-server" / "target").glob("drishti-server-*-exec.jar")) and (MAIN / "drishti-server" / "target").exists():
    base.ROOT = MAIN                                   # the jar, the packs and the sample config are read-only inputs; the console under test is this checkout's
from test_w1_model_designer_browser import browser, clean, fetch, sign_in, stack, yaml_tab  # noqa: E402,F401

PORTS = base.worker_ports(18909, 17909)              # this file's own stack: the stack fixture switches the base module to them
CONSOLE_URL = f"http://127.0.0.1:{PORTS[1]}"
MODEL = """rachana: 2
model: {name}
title: {title}
version: 1.0.0
datasets:
  trade: {{dataset: pack.trading.trade@^1, role: fact}}
  desk: {{dataset: pack.banking-core.desk@^1, role: dimension}}
relationships:
  - {{from: trade.desk, to: desk.id}}
measures:
  MTM: {{expr: sum(trade.mtm)}}
"""


def open_model(browser, name, title="QA model"):
    page = sign_in(browser, "wes")
    made = fetch(page, "POST", "/bi/model-api/new", {"title": title, "workspace": "me", "datasets": ["pack.trading.trade", "pack.banking-core.desk"]})
    assert made["status"] == 200, made
    item = made["body"]["href"].rsplit("/", 1)[-1]
    got = fetch(page, "GET", f"/bi/api/items/{item}")
    body = MODEL.format(name=got["body"]["body"].split("model:", 1)[1].split()[0], title=title)
    put = fetch(page, "PUT", f"/bi/api/items/{item}", {"body": body, "baseEtag": got["body"]["etag"]})
    assert put["status"] == 200, put
    page.goto(CONSOLE_URL + made["body"]["href"])
    expect(page.locator('[data-md-root][data-ready="yes"]')).to_have_count(1, timeout=30000)
    return page, item


def saved_body(page, item):
    return fetch(page, "GET", f"/bi/api/items/{item}")["body"]["body"]


def test_ctrl_s_saves_the_field_being_typed_and_the_contract_tab_redraws(browser):
    page, item = open_model(browser, "qa-ctrl-s")
    page.click('[data-tab="contract"]')
    title = page.locator("[data-c-title]")
    title.fill("Counterparty P&L and exposure")                       # still focused: it commits on blur, and Ctrl+S comes first
    page.keyboard.press("Control+s")
    expect(page.locator("[data-state]")).not_to_have_text("Unsaved changes", timeout=20000)
    for _ in range(40):
        if "Counterparty P&L and exposure" in saved_body(page, item):
            break
        page.wait_for_timeout(250)
    assert "Counterparty P&L and exposure" in saved_body(page, item)
    page.fill("[data-c-desc]", "Trades and desks")                    # Tab out of a field into the next one, again and again: the tab redraws under the focus
    page.keyboard.press("Tab")
    page.keyboard.press("Tab")
    page.wait_for_timeout(800)
    clean(page)                                                        # no NotFoundError from drawContract
    page.context.close()


def test_the_draft_is_autosaved_and_a_stale_save_opens_the_merge_view(browser):
    page, item = open_model(browser, "qa-autosave")
    page.click('[data-tab="contract"]')
    page.fill("[data-c-title]", "Autosaved title")
    page.keyboard.press("Tab")
    expect(page.locator("[data-autosaved]")).to_contain_text("Draft saved at", timeout=20000)
    assert "Autosaved title" in saved_body(page, item)
    other = fetch(page, "GET", f"/bi/api/items/{item}")["body"]           # someone else saves meanwhile
    put = fetch(page, "PUT", f"/bi/api/items/{item}", {"body": other["body"].replace("Autosaved title", "Their title"), "baseEtag": other["etag"]})
    assert put["status"] == 200, put
    page.fill("[data-c-desc]", "Mine")
    page.keyboard.press("Tab")
    merge = page.locator("[data-merge]")
    expect(merge).to_be_visible(timeout=20000)
    expect(merge.locator('[data-merge-row="title"]')).to_contain_text("they changed")
    expect(merge.locator('[data-merge-row="description"]')).to_contain_text("you changed")
    page.click("[data-merge-save]")
    expect(page.locator("[data-merge]")).to_have_count(0)
    for _ in range(40):
        body = saved_body(page, item)
        if "Their title" in body and "Mine" in body:
            break
        page.wait_for_timeout(250)
    assert "Their title" in body and "description: Mine" in body, body
    clean(page)
    page.context.close()


def test_a_hidden_measure_names_the_columns_that_still_reach_it(browser):
    page, item = open_model(browser, "qa-hidden")
    page.click('[data-tab="security"]')
    page.fill("[data-hidden-roles]", "desk-head")
    page.check('[data-hidden-pick="MTM"]')
    expect(page.locator("[data-hidden-pick-warn]")).to_contain_text("trade.mtm")
    page.click("[data-hidden-add]")
    reach = page.locator('[data-hidden-entry="0"] [data-hidden-reachable]')
    expect(reach).to_contain_text("trade.mtm", timeout=20000)
    expect(page.locator("[data-hidden-note]")).to_contain_text("not available to their role")
    page.click('[data-hidden-too="0"]')
    expect(page.locator('[data-hidden-entry="0"] [data-hidden-reachable]')).to_have_count(0, timeout=20000)
    assert "trade.mtm" in yaml_tab(page)
    clean(page)
    page.context.close()


def test_the_expression_editor_offers_its_own_fixes_and_the_tabs_take_the_arrow_keys(browser):
    page, item = open_model(browser, "qa-fixes")
    page.focus('[data-tab="canvas"]')
    page.keyboard.press("ArrowRight")
    expect(page.locator('[data-tab="measures"]')).to_have_attribute("aria-selected", "true")
    page.keyboard.press("End")
    expect(page.locator('[data-tab="yaml"]')).to_have_attribute("aria-selected", "true")
    page.keyboard.press("Home")
    expect(page.locator('[data-tab="canvas"]')).to_have_attribute("aria-selected", "true")
    page.click('[data-tab="measures"]')
    page.click("[data-nf] summary")
    expr = page.locator("[data-nf-expr]")
    expr.fill("sum(trade.mtm")
    expr.dispatch_event("input")
    fix = page.locator("[data-nf] [data-xe-fix]")
    expect(fix.first).to_contain_text("sum(trade.mtm)", timeout=20000)
    expr.fill("sum(trade.mtm, where=trade.status = 'Live')")
    expr.dispatch_event("input")
    expect(page.locator("[data-nf] [data-xe-fix]").first).to_contain_text("trade.status ==", timeout=20000)
    page.locator("[data-nf] [data-xe-fix]").first.click()
    assert "trade.status == 'Live'" in expr.input_value()
    clean(page)
    page.context.close()


def test_a_free_row_rule_and_an_expected_value_are_written_to_the_model(browser):
    page, item = open_model(browser, "qa-rules")
    page.click('[data-tab="security"]')
    page.fill("[data-rule-roles]", "desk-head")
    page.select_option("[data-rule-alias]", "trade")
    page.select_option("[data-rule-kind]", "expr")
    page.fill("[data-rule-expr]", "trade.desk in user.desks and trade.status == 'Live'")
    page.click("[data-rule-add]")
    expect(page.locator('[data-rule="trade:0"]')).to_contain_text("trade.status == 'Live'", timeout=20000)
    page.fill("[data-test-name]", "Some MTM")
    page.fill("[data-test-sql]", "SELECT MEASURE('MTM') FROM trade")
    page.select_option("[data-test-how]", "min")
    page.fill("[data-test-a]", "-100000000")
    page.click("[data-test-add]")
    expect(page.locator('[data-model-test="Some MTM"]').first).to_be_visible(timeout=20000)
    page.wait_for_timeout(1500)                                        # the YAML is written by the Check that follows the edit
    text = yaml_tab(page)
    assert "user.desks and trade.status" in text and "Some MTM" in text and "min: -100000000" in text, text
    clean(page)
    page.context.close()


def test_a_visual_hidden_for_the_role_says_so_without_retry(browser):
    page = sign_in(browser, "wes")
    page.goto(CONSOLE_URL + "/bi/r/file:report:trades-overview")
    page.wait_for_function("!!(window.drishtiBi && window.drishtiBi.frame)", timeout=30000)
    out = page.evaluate("""() => { const B = window.drishtiBi, f = B.frame({ id: 'x', type: 'table', title: 'X' }, { onRetry: () => {} });
        document.body.appendChild(f.el); f.error({ code: 'DRS-9163', message: "the measure 'MTM' is not available to your role", detail: "the measure 'MTM' is not available to your role" });
        const a = f.el.textContent, retry = f.el.querySelectorAll('.bi-retry').length;
        f.result({ rowCount: 0, meta: { millis: 1, layer: 'engine', noAccess: ['trade'] } }, (host) => ({ dispose() {}, resize() {} }));
        return { a, retry, b: f.el.textContent }; }""")
    assert "Not available to your role" in out["a"] and out["retry"] == 0, out
    assert "Your role has no rows of trade" in out["b"], out
    page.context.close()
