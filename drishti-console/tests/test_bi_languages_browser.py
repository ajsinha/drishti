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

import os

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

import test_w1_model_designer_browser as w1  # noqa: E402
from bi_stack import listening  # noqa: E402

w1.SERVER_PORT, w1.CONSOLE_PORT = 18912, 17912
w1.SERVER, w1.CONSOLE_URL = f"http://127.0.0.1:{w1.SERVER_PORT}", f"http://127.0.0.1:{w1.CONSOLE_PORT}"
CONSOLE_URL = w1.CONSOLE_URL
SHOTS = os.environ.get("DRISHTI_SHOTS", "")                       # a folder: the documentation's pictures are taken there too (docs/guides/EXPRESSIONS.md)
sign_in, clean, fetch = w1.sign_in, w1.clean, w1.fetch


def shoot(page, selector, name, max_height=760):
    """A picture of one region for the guides (only with DRISHTI_SHOTS set): docs/guides/img/bi-languages/<name>.jpg. Taken from the whole page at its
    natural layout (an element screenshot scrolls, and the sticky top bar would cover the region), at most max_height pixels tall."""
    if SHOTS:
        page.evaluate("window.scrollTo(0, 0)")
        bb = page.locator(selector).first.bounding_box()
        page.screenshot(path=os.path.join(SHOTS, name + ".jpg"), quality=88, full_page=True,
                        clip={"x": bb["x"], "y": bb["y"], "width": bb["width"], "height": min(bb["height"], max_height)})


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
    shoot(page, '[data-step="filter"]', "wizard-filter-completion")
    page.keyboard.press("Enter")
    where.type(" == 'Live'")
    assert where.input_value() == "status == 'Live'"
    where.press("Tab")
    expect(page.locator('[data-step="filter"] [data-xe-ok]')).to_be_visible(timeout=10000)    # the server checks it against the base dataset
    next_step(page, "grain")
    page.check('[data-dedup="latest"]')
    page.select_option("[data-dedup-by]", "tradeDate")
    shoot(page, '[data-step="grain"]', "wizard-grain")
    next_step(page, "derive")
    page.click('[data-derive-add="expr"]')
    page.fill('[data-derive-name="0"]', "notionalM")
    page.locator('[data-derive-name="0"]').press("Tab")
    ex = page.locator('[data-derive-expr="0"]')
    ex.fill("notional / 1000000")
    ex.press("Tab")
    shoot(page, '[data-step="derive"]', "wizard-derive")
    next_step(page, "group")
    page.check("[data-group-on]")
    page.click('[data-group-key="desk"]')
    page.select_option('[data-group-agg="mtm"]', "sum")
    page.fill("[data-group-count]", "trades")
    page.locator("[data-group-count]").press("Tab")
    shoot(page, '[data-step="group"]', "wizard-group")
    next_step(page, "preview")
    next_step(page, "save")
    page.fill("[data-title]", "Live trades by desk")
    page.locator("[data-title]").press("Tab")
    page.click("details:has([data-yaml]) summary")
    shoot(page, '[data-step="save"]', "wizard-save")
    text = page.locator("[data-yaml]").text_content()
    for line in ("extends: pack.trading.trade@^1", "where: status == 'Live'", "keep: latest by tradeDate", "notionalM: {", "group:", "trades: count"):
        assert line in text, (line, text)
    assert "book" not in text.split("where:")[0]                                               # the unticked column is not in the subset
    page.click("[data-save]")
    expect(page.locator("[data-after-open]")).to_be_visible(timeout=20000)
    clean(page)
    page.context.close()


def new_model(page, title):
    """A shared workspace (a private one cannot publish) and a model over trades and desks in it; the designer opened on it."""
    ws = fetch(page, "POST", "/bi/api/workspaces", {"name": title + " desk", "description": ""})
    assert ws["status"] in (200, 201), ws
    made = fetch(page, "POST", "/bi/model-api/new", {"title": title, "workspace": ws["body"]["id"], "datasets": ["pack.trading.trade", "pack.banking-core.desk"]})
    assert made["status"] == 200, made
    page.goto(CONSOLE_URL + made["body"]["href"])
    expect(page.locator('[data-md-root][data-ready="yes"]')).to_have_count(1, timeout=30000)
    page.locator('[data-suggestion="trade.desk->desk.id"] [data-accept]').click()
    expect(page.locator('[data-rel="trade.desk → desk.id"]')).to_be_visible(timeout=15000)
    return made["body"]["id"]


def test_the_model_designer_writes_expressions_field_properties_and_perspectives(browser):
    page = sign_in(browser, "wes")
    new_model(page, "Languages model")
    page.click('[data-tab="measures"]')
    page.select_option("[data-nm-alias]", "trade")
    page.select_option("[data-nm-column]", "mtm")
    page.click("[data-nm-add]")
    expect(page.locator('[data-measure="Sum of mtm"]')).to_be_visible(timeout=15000)
    # the ExpressionEditor: a field, then a measure written as measure('…'), then the chips
    page.click("[data-nf] summary")
    page.fill("[data-nf-name]", "MTM per trade")
    ed = page.locator("[data-nf-expr]")
    ed.click()
    ed.type("Sum")
    expect(page.locator('[data-xe-opt="Sum of mtm"]')).to_be_visible(timeout=5000)
    page.keyboard.press("Enter")
    assert ed.input_value() == "measure('Sum of mtm')"
    ed.type(" / count(trade.tra")
    expect(page.locator('[data-xe-opt="trade.tradeId"]')).to_be_visible(timeout=5000)
    page.click('[data-xe-opt="trade.tradeId"]')
    ed.type(")")
    assert ed.input_value() == "measure('Sum of mtm') / count(trade.tradeId)"
    expect(page.locator('[data-xe-chip="Sum of mtm"]')).to_be_visible()
    shoot(page, "[data-nf]", "expr-editor-measure")
    page.click("[data-nf-add]")
    expect(page.locator('[data-measure="MTM per trade"]')).to_be_visible(timeout=15000)
    # field properties and a perspective
    page.click('[data-tab="fields"]')
    row = page.locator('[data-field-row="trade.mtm"]')
    row.locator('[data-fp="description"]').fill("Mark to market, in the trade's currency")
    row.locator('[data-fp="description"]').press("Tab")
    row.locator('[data-fp="folder"]').fill("Risk")
    row.locator('[data-fp="folder"]').press("Tab")
    page.locator('[data-field-row="trade.book"] [data-fp="hidden"]').check()
    page.fill("[data-persp-new]", "Desk view")
    page.click("[data-persp-add]")
    expect(page.locator('[data-perspective="desk-view"]')).to_be_visible(timeout=15000)
    page.locator('[data-persp-pick="desk-view:trade.desk"]').check()
    page.locator('[data-persp-pick="desk-view:Sum of mtm"]').check()
    shoot(page, "[data-fields-pane]", "model-fields-tab")
    page.wait_for_function("() => window.drishtiModel.yaml.indexOf('measures: [Sum of mtm]') >= 0", timeout=10000)          # the YAML follows each edit once Check has run
    text = w1.yaml_tab(page)
    for line in ("trade.mtm: {description:", "folder: Risk", "trade.book: {hidden: true}", "desk-view: {title: Desk view, fields: [trade.desk], measures: [Sum of mtm]}",
                 "MTM per trade: {expr: measure('Sum of mtm') / count(trade.tradeId)}"):
        assert line in text, (line, text)
    clean(page)
    page.context.close()


def test_the_editor_offers_functions_and_keywords_and_fixes_what_the_server_finds(browser):
    page = sign_in(browser, "wes")
    new_model(page, "Editor model")
    page.click('[data-tab="measures"]')
    page.select_option("[data-nm-alias]", "trade")
    page.select_option("[data-nm-column]", "mtm")
    page.click("[data-nm-add]")
    expect(page.locator('[data-measure="Sum of mtm"]')).to_be_visible(timeout=15000)
    page.click("[data-save]")                                                                   # the server checks names against the saved draft
    expect(page.locator("[data-state]")).to_have_text("Saved draft", timeout=15000)
    page.click("[data-nf] summary")
    ed = page.locator("[data-nf-expr]")
    # a function from the server's catalogue, then its card and its keyword arguments
    ed.click()
    ed.type("su")
    expect(page.locator('[data-xe-opt="sum"]')).to_be_visible(timeout=10000)
    page.click('[data-xe-opt="sum"]')
    expect(page.locator('[data-xe-card="sum"]')).to_contain_text("sum(x, where=None)", timeout=5000)
    ed.type("trade.mtm, wh")
    expect(page.locator('[data-xe-opt="where="]')).to_be_visible(timeout=5000)
    shoot(page, "[data-nf]", "expr-editor-keyword", 420)
    page.click('[data-xe-opt="where="]')
    ed.type("trade.status == 'Live')")
    assert ed.input_value() == "sum(trade.mtm, where=trade.status == 'Live')"
    expect(page.locator("[data-xe-ok]")).to_be_visible(timeout=10000)                          # the server's check: clean
    shoot(page, "[data-nf]", "expr-editor-clean")
    # a bare column in a measure: the server says wrap it, and the fix is a button
    ed.fill("trade.mtm * 2")
    expect(page.locator('[data-xe-problem="DRS-9127"]')).to_be_visible(timeout=10000)
    shoot(page, "[data-nf]", "expr-editor-problem")
    page.locator('[data-xe-problem="DRS-9127"] [data-xe-fix]').click()
    assert ed.input_value() == "sum(trade.mtm) * 2", ed.input_value()
    # the old syntax: the converter's text is the fix
    ed.fill("[Sum of mtm] * 2")
    expect(page.locator('[data-xe-problem="DRS-9100"]')).to_be_visible(timeout=10000)
    page.locator('[data-xe-problem="DRS-9100"] [data-xe-fix]').click()
    assert ed.input_value() == "measure('Sum of mtm') * 2", ed.input_value()
    # Try it: the server's answer on the saved draft, by a suggested field
    page.click("[data-nf] [data-xe-try]")
    expect(page.locator("[data-nf] [data-xe-tried]")).to_be_visible(timeout=15000)
    shoot(page, "[data-nf]", "expr-editor-try")
    clean(page)
    page.context.close()


def test_a_save_on_a_published_model_makes_a_draft_and_publish_follows(browser):
    page = sign_in(browser, "wes")
    new_model(page, "Contract model")
    page.click('[data-tab="measures"]')
    page.select_option("[data-nm-alias]", "trade")
    page.select_option("[data-nm-column]", "mtm")
    page.click("[data-nm-add]")
    expect(page.locator('[data-measure="Sum of mtm"]')).to_be_visible(timeout=15000)
    page.click("[data-save]")
    expect(page.locator("[data-state]")).to_have_text("Saved draft", timeout=15000)
    page.click("[data-publish]")
    expect(page.locator("[data-state]")).to_have_text("Published", timeout=20000)
    # change what the measure computes: the save makes a draft beside the published version; Publish is offered again
    expr = page.locator('[data-m-expr="Sum of mtm"]')
    expr.fill("sum(trade.pnl1d)")
    expr.press("Enter")
    expect(page.locator("[data-state]")).to_have_text("Unsaved changes", timeout=15000)
    page.click("[data-save]")
    expect(page.locator("[data-state]")).to_have_text("Draft changes (v1 published)", timeout=15000)
    expect(page.locator("[data-publish]")).to_be_visible()
    expect(page.locator("[data-publish]")).to_be_enabled()
    # the contract tab names the breaking change and proposes the major version
    page.click('[data-tab="contract"]')
    expect(page.locator('[data-change-level="major"]').first).to_contain_text("changes what Sum of mtm computes", timeout=15000)
    page.click('[data-contract-use="2.0.0"]')
    expect(page.locator("[data-version]")).to_have_text("v2.0.0", timeout=15000)
    expect(page.locator("[data-contract-bump]")).to_contain_text("2.0.0 is right", timeout=15000)
    shoot(page, "[data-contract-pane]", "model-contract-diff")
    clean(page)
    page.context.close()


def test_a_report_on_a_model_has_its_own_measures_and_the_query_as_mq_or_sql(browser):
    page = sign_in(browser, "wes")
    new_model(page, "Query model")
    page.click('[data-tab="measures"]')
    page.select_option("[data-nm-alias]", "trade")
    page.select_option("[data-nm-column]", "mtm")
    page.click("[data-nm-add]")
    expect(page.locator('[data-measure="Sum of mtm"]')).to_be_visible(timeout=15000)
    page.click("[data-save]")
    expect(page.locator("[data-state]")).to_have_text("Saved draft", timeout=15000)
    name = page.evaluate("window.drishtiModel.doc.model")
    page.click("[data-report]")
    page.click("[data-ask-go]")
    page.wait_for_url("**/bi/design/**", timeout=20000)
    expect(page.locator('[data-model-header="model"]')).to_be_visible(timeout=30000)
    # a measure of this report only, in the ExpressionEditor over the model's measures
    page.click("[data-local-measure] summary")
    page.fill("[data-lm-name]", "MTM in thousands")
    lm = page.locator("[data-lm-expr]")
    lm.click()
    lm.type("Sum")
    page.click('[data-xe-opt="Sum of mtm"]')
    lm.type(" / 1000")
    page.click("[data-lm-add]")
    expect(page.locator('[data-field="MTM in thousands"] [data-origin="report"], [data-field="MTM in thousands"]').first).to_be_visible(timeout=15000)
    page.drag_and_drop('[data-field="MTM in thousands"]', "[data-canvas-grid]", target_position={"x": 80, "y": 40})
    page.drag_and_drop('[data-field="trade.desk"]', ".bi-dvis")
    page.locator(".bi-dvis").first.click()
    expect(page.locator(".bi-dvis").first).to_contain_text("3 rows", timeout=15000)              # the report's own measure travels with the query as define
    page.click("[data-visual-query] summary")
    expect(page.locator("[data-visual-query-text]")).to_contain_text(f"model: {name}@1", timeout=15000)
    expect(page.locator("[data-visual-query-text]")).to_contain_text("define:")
    page.click('[data-qp-view="sql"]')
    sql = page.locator("[data-visual-query-text]").text_content()
    assert sql.startswith("SELECT trade.desk") and "FROM   trade" in sql and "GROUP  BY trade.desk" in sql and "measure('Sum of mtm') / 1000" in sql, sql
    shoot(page, "[data-visual-query]", "query-panel-sql")
    page.click("[data-save]")
    expect(page.locator(".bi-near").first).to_contain_text("Saved", timeout=15000)
    body = fetch(page, "GET", "/bi/api/items/" + page.url.rsplit("/", 1)[-1])["body"]["body"]
    assert "measures:" in body and "MTM in thousands" in body, body
    clean(page)
    page.context.close()


def test_a_reports_fields_pane_inherits_the_models_properties_and_perspectives(browser):
    page = sign_in(browser, "wes")
    new_model(page, "Inherit model")
    page.click('[data-tab="measures"]')
    page.select_option("[data-nm-alias]", "trade")
    page.select_option("[data-nm-column]", "mtm")
    page.click("[data-nm-add]")
    expect(page.locator('[data-measure="Sum of mtm"]')).to_be_visible(timeout=15000)
    page.click('[data-tab="fields"]')
    page.locator('[data-field-row="trade.desk"] [data-fp="display"]').fill("Trading desk")
    page.locator('[data-field-row="trade.desk"] [data-fp="display"]').press("Tab")
    page.locator('[data-field-row="trade.mtm"] [data-fp="folder"]').fill("Risk")
    page.locator('[data-field-row="trade.mtm"] [data-fp="folder"]').press("Tab")
    page.locator('[data-field-row="trade.book"] [data-fp="hidden"]').check()
    page.fill("[data-persp-new]", "Desk view")
    page.click("[data-persp-add]")
    page.locator('[data-persp-pick="desk-view:trade.desk"]').check()
    page.locator('[data-persp-pick="desk-view:Sum of mtm"]').check()
    page.wait_for_function("() => window.drishtiModel.yaml.indexOf('measures: [Sum of mtm]') >= 0", timeout=10000)
    page.click("[data-save]")
    expect(page.locator("[data-state]")).to_have_text("Saved draft", timeout=15000)
    page.click("[data-report]")
    page.click("[data-ask-go]")
    page.wait_for_url("**/bi/design/**", timeout=20000)
    expect(page.locator('[data-model-header="model"]')).to_be_visible(timeout=30000)
    expect(page.locator('[data-field="trade.desk"]')).to_contain_text("Trading desk")              # the model's display name, not re-declared
    expect(page.locator('[data-folder="Risk"] [data-field="trade.mtm"]')).to_be_visible()
    expect(page.locator('[data-field="trade.book"]')).to_have_count(0)                            # hidden by the model
    page.select_option("[data-perspective-pick]", "desk-view")
    expect(page.locator('[data-field="trade.currency"]')).to_have_count(0, timeout=10000)
    expect(page.locator('[data-field="trade.desk"]')).to_be_visible()
    expect(page.locator('[data-field="Sum of mtm"]')).to_be_visible()
    shoot(page, ".bi-pane-fields", "report-fields-perspective")
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
