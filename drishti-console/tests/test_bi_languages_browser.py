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
18916 and 17916 (the W1 model stack of tests/test_w1_model_designer_browser.py: security on, the trading and core packs, a small file lake of
trades and desks). Checked: the dataset wizard makes a dataset with a column subset, a filter, an explicit grain with a de-duplication, a formula column
and a grouping without typing YAML (the ExpressionEditor completing the columns); the ExpressionEditor in the model designer completes fields, measures
(writing measure('...')) and functions, shows the call's card and the server's problems; field properties, perspectives and Preview as a role in the
model designer; the Query panel's MQ and SQL views; a save on a published model makes a draft and Publish follows; a phone width without sideways scroll.

Skipped when Playwright, Chromium or the built server jar is missing, or a scratch port is in use. Each pytest-xdist worker adds 100 to the ports (tests/scratch_ports.py)."""
from __future__ import annotations

import os
import sys
from pathlib import Path

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

import test_w1_model_designer_browser as w1  # noqa: E402
from bi_stack import listening  # noqa: E402

PORTS = w1.worker_ports(18916, 17916)       # this file's own ports (plus the pytest-xdist worker offset): the stack fixture switches w1 to them
CONSOLE_URL = f"http://127.0.0.1:{PORTS[1]}"
# the server runs a Python column through the console's sandboxed runner, started with this Python (it has pandas)
os.environ.setdefault("DRISHTI_BI_TRANSFORM_PYTHON", sys.executable)
os.environ.setdefault("DRISHTI_BI_TRANSFORM_RUNNER", str(Path(__file__).resolve().parents[1] / "core" / "transform_cli.py"))
CAPTURES = os.environ.get("DRISHTI_CAPTURES", "")                 # a folder: the texts the guides quote (YAML written, SQL shown) are kept there
SHOTS = os.environ.get("DRISHTI_SHOTS", "")                       # a folder: the documentation's pictures are taken there too (docs/guides/EXPRESSIONS.md)
sign_in, clean, fetch = w1.sign_in, w1.clean, w1.fetch


def capture(name, text):
    if CAPTURES:
        Path(CAPTURES, name).write_text(text)


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
    w1.use_ports(PORTS)
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
    page.uncheck('[data-keep-col="PORTFOLIO"]')
    next_step(page, "filter")
    where = page.locator("[data-where]")
    where.click()
    where.type("TRADE_ST")
    expect(page.locator('[data-xe-opt="TRADE_STATUS"]')).to_be_visible(timeout=5000)                 # the ExpressionEditor completes the table's columns
    shoot(page, '[data-step="filter"]', "wizard-filter-completion")
    page.keyboard.press("Enter")
    where.type(" == 'VALIDATED'")
    assert where.input_value() == "TRADE_STATUS == 'VALIDATED'"
    where.press("Tab")
    expect(page.locator('[data-step="filter"] [data-xe-ok]')).to_be_visible(timeout=10000)    # the server checks it against the base dataset
    next_step(page, "grain")
    page.check('[data-dedup="latest"]')
    page.select_option("[data-dedup-by]", "TRADE_DATE")
    shoot(page, '[data-step="grain"]', "wizard-grain")
    next_step(page, "derive")
    page.click('[data-derive-add="expr"]')
    page.fill('[data-derive-name="0"]', "notionalM")
    page.locator('[data-derive-name="0"]').press("Tab")
    ex = page.locator('[data-derive-expr="0"]')
    ex.fill("NOTIONAL / 1000000")
    ex.press("Tab")
    shoot(page, '[data-step="derive"]', "wizard-derive")
    next_step(page, "group")
    page.check("[data-group-on]")
    page.click('[data-group-key="DESK"]')
    page.select_option('[data-group-agg="MTM"]', "sum")
    page.fill("[data-group-count]", "trades")
    page.locator("[data-group-count]").press("Tab")
    shoot(page, '[data-step="group"]', "wizard-group")
    next_step(page, "preview")
    next_step(page, "save")
    page.fill("[data-title]", "Live trades by desk")
    page.locator("[data-title]").press("Tab")
    page.click("details:has([data-yaml]) summary")
    expect(page.locator("[data-yaml]")).to_contain_text("dataset: live-trades-by-desk", timeout=10000)        # the name follows the title until it is typed
    expect(page.locator("[data-problems-save] .bi-ok")).to_contain_text("Checks clean", timeout=10000)        # the server reads every derivation key
    shoot(page, '[data-step="save"]', "wizard-save")
    text = page.locator("[data-yaml]").text_content()
    capture("wizard.yaml", text)
    for line in ("dataset: live-trades-by-desk", "extends: pack.trading.trade@^1", "where: TRADE_STATUS == 'VALIDATED'", "keep: latest by TRADE_DATE", "notionalM: {", "group:", "trades: count"):
        assert line in text, (line, text)
    assert "PORTFOLIO" not in text.split("where:")[0]                                               # the unticked column is not in the subset
    page.click("[data-save]")
    expect(page.locator("[data-after-open]")).to_be_visible(timeout=20000)
    clean(page)
    page.context.close()


def new_model(page, title):
    """A shared workspace (a private one cannot publish) and a model over trades and desks in it; the designer opened on it."""
    ws = fetch(page, "POST", "/bi/api/workspaces", {"name": title + " desk", "description": ""})
    assert ws["status"] in (200, 201), ws
    made = fetch(page, "POST", "/bi/model-api/new", {"title": title, "workspace": ws["body"]["id"], "datasets": ["pack.trading.trade", "pack.core.desk"]})
    assert made["status"] == 200, made
    page.goto(CONSOLE_URL + made["body"]["href"])
    expect(page.locator('[data-md-root][data-ready="yes"]')).to_have_count(1, timeout=30000)
    page.locator('[data-suggestion="trade.DESK->desk.id"] [data-accept]').click()
    expect(page.locator('[data-rel="trade.DESK → desk.id"]')).to_be_visible(timeout=15000)
    return made["body"]["id"]


def test_the_model_designer_writes_expressions_field_properties_and_perspectives(browser):
    page = sign_in(browser, "wes")
    new_model(page, "Languages model")
    page.click('[data-tab="measures"]')
    page.select_option("[data-nm-alias]", "trade")
    page.select_option("[data-nm-column]", "MTM")
    page.click("[data-nm-add]")
    expect(page.locator('[data-measure="Sum of MTM"]')).to_be_visible(timeout=15000)
    # the ExpressionEditor: a field, then a measure written as measure('…'), then the chips
    page.click("[data-nf] summary")
    page.fill("[data-nf-name]", "MTM per trade")
    ed = page.locator("[data-nf-expr]")
    ed.click()
    ed.type("Sum")
    expect(page.locator('[data-xe-opt="Sum of MTM"]')).to_be_visible(timeout=5000)
    page.keyboard.press("Enter")
    assert ed.input_value() == "measure('Sum of MTM')"
    ed.type(" / count(trade.TRA")
    expect(page.locator('[data-xe-opt="trade.TRADE_ID"]')).to_be_visible(timeout=5000)
    page.click('[data-xe-opt="trade.TRADE_ID"]')
    ed.type(")")
    assert ed.input_value() == "measure('Sum of MTM') / count(trade.TRADE_ID)"
    expect(page.locator('[data-xe-chip="Sum of MTM"]')).to_be_visible()
    shoot(page, "[data-nf]", "expr-editor-measure")
    page.click("[data-nf-add]")
    expect(page.locator('[data-measure="MTM per trade"]')).to_be_visible(timeout=15000)
    # field properties and a perspective
    page.click('[data-tab="fields"]')
    row = page.locator('[data-field-row="trade.MTM"]')
    row.locator('[data-fp="description"]').fill("Mark to market, in the trade's currency")
    row.locator('[data-fp="description"]').press("Tab")
    row.locator('[data-fp="folder"]').fill("Risk")
    row.locator('[data-fp="folder"]').press("Tab")
    page.locator('[data-field-row="trade.PORTFOLIO"] [data-fp="hidden"]').check()
    page.fill("[data-persp-new]", "Desk view")
    page.click("[data-persp-add]")
    expect(page.locator('[data-perspective="desk-view"]')).to_be_visible(timeout=15000)
    page.locator('[data-persp-pick="desk-view:trade.DESK"]').check()
    page.locator('[data-persp-pick="desk-view:Sum of MTM"]').check()
    shoot(page, "[data-fields-pane]", "model-fields-tab")
    page.wait_for_function("() => window.drishtiModel.yaml.indexOf('measures: [Sum of MTM]') >= 0", timeout=10000)          # the YAML follows each edit once Check has run
    text = w1.yaml_tab(page)
    capture("model-fields.yaml", text)
    for line in ("trade.MTM: {description:", "folder: Risk", "trade.PORTFOLIO: {hidden: true}", "desk-view: {title: Desk view, fields: [trade.DESK], measures: [Sum of MTM]}",
                 "MTM per trade: {expr: measure('Sum of MTM') / count(trade.TRADE_ID)}"):
        assert line in text, (line, text)
    # Security: a field that does not exist for a role (hidden-for), checked clean by the server; Preview as asks the server, never shows yours
    page.click('[data-tab="security"]')
    page.fill("[data-hidden-roles]", "desk-head")
    page.locator('[data-hidden-pick="trade.NOTIONAL"]').check()
    page.click("[data-hidden-add]")
    expect(page.locator('[data-hidden-entry="0"]')).to_contain_text("desk-head", timeout=15000)
    page.wait_for_function("() => window.drishtiModel.yaml.indexOf('hidden-for') >= 0", timeout=10000)
    assert "hidden-for: [{roles: [desk-head], fields: [trade.NOTIONAL]}]" in page.evaluate("window.drishtiModel.yaml"), page.evaluate("window.drishtiModel.yaml")
    expect(page.locator("[data-valid]")).to_have_text("Valid", timeout=15000)
    shoot(page, "[data-hidden-for]", "model-hidden-for")
    page.click("[data-save]")
    expect(page.locator("[data-state]")).to_have_text("Saved draft", timeout=15000)
    page.select_option("[data-preview-role]", "desk-head")
    page.click("[data-preview-as]")
    expect(page.locator("[data-preview-as-error], [data-previewed-as]").first).to_be_visible(timeout=15000)
    clean(page)
    page.context.close()


def test_the_editor_offers_functions_and_keywords_and_fixes_what_the_server_finds(browser):
    page = sign_in(browser, "wes")
    new_model(page, "Editor model")
    page.click('[data-tab="measures"]')
    page.select_option("[data-nm-alias]", "trade")
    page.select_option("[data-nm-column]", "MTM")
    page.click("[data-nm-add]")
    expect(page.locator('[data-measure="Sum of MTM"]')).to_be_visible(timeout=15000)
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
    ed.type("trade.MTM, wh")
    expect(page.locator('[data-xe-opt="where="]')).to_be_visible(timeout=5000)
    shoot(page, "[data-nf]", "expr-editor-keyword", 420)
    page.click('[data-xe-opt="where="]')
    ed.type("trade.TRADE_STATUS == 'VALIDATED')")
    assert ed.input_value() == "sum(trade.MTM, where=trade.TRADE_STATUS == 'VALIDATED')"
    expect(page.locator("[data-xe-ok]")).to_be_visible(timeout=10000)                          # the server's check: clean
    shoot(page, "[data-nf]", "expr-editor-clean")
    # a bare column in a measure: the server says wrap it, and the fix is a button
    ed.fill("trade.MTM * 2")
    expect(page.locator('[data-xe-problem="DRS-9127"]')).to_be_visible(timeout=10000)
    shoot(page, "[data-nf]", "expr-editor-problem")
    page.locator('[data-xe-problem="DRS-9127"] [data-xe-fix]').click()
    assert ed.input_value() == "sum(trade.MTM) * 2", ed.input_value()
    # the old syntax: the converter's text is the fix
    ed.fill("[Sum of MTM] * 2")
    expect(page.locator('[data-xe-problem="DRS-9100"]')).to_be_visible(timeout=10000)
    page.locator('[data-xe-problem="DRS-9100"] [data-xe-fix]').click()
    assert ed.input_value() == "measure('Sum of MTM') * 2", ed.input_value()
    expect(page.locator("[data-xe-ok]")).to_be_visible(timeout=10000)                          # the fix is checked again: clean
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
    page.select_option("[data-nm-column]", "MTM")
    page.click("[data-nm-add]")
    expect(page.locator('[data-measure="Sum of MTM"]')).to_be_visible(timeout=15000)
    page.click("[data-save]")
    expect(page.locator("[data-state]")).to_have_text("Saved draft", timeout=15000)
    page.click("[data-publish]")
    expect(page.locator("[data-state]")).to_have_text("Published", timeout=20000)
    # change what the measure computes: the save makes a draft beside the published version; Publish is offered again
    expr = page.locator('[data-m-expr="Sum of MTM"]')
    expr.fill("sum(trade.PNL_1D)")
    expr.press("Enter")
    expect(page.locator("[data-state]")).to_have_text("Unsaved changes", timeout=15000)
    page.click("[data-save]")
    expect(page.locator("[data-state]")).to_have_text("Draft changes (v1 published)", timeout=15000)
    expect(page.locator("[data-publish]")).to_be_visible()
    expect(page.locator("[data-publish]")).to_be_enabled()
    # the contract tab names the breaking change and proposes the major version
    page.click('[data-tab="contract"]')
    expect(page.locator('[data-change-level="major"]').first).to_contain_text("changes what Sum of MTM computes", timeout=15000)
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
    page.select_option("[data-nm-column]", "MTM")
    page.click("[data-nm-add]")
    expect(page.locator('[data-measure="Sum of MTM"]')).to_be_visible(timeout=15000)
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
    page.click('[data-xe-opt="Sum of MTM"]')
    lm.type(" / 1000")
    page.click("[data-lm-add]")
    expect(page.locator('[data-field="MTM in thousands"] [data-origin="report"], [data-field="MTM in thousands"]').first).to_be_visible(timeout=15000)
    page.click("[data-local-measure] summary")                                                   # close the form: the Fields pane stops moving under the pointer
    page.locator('[data-field="MTM in thousands"]').scroll_into_view_if_needed()
    page.wait_for_timeout(500)
    page.drag_and_drop('[data-field="MTM in thousands"]', "[data-canvas-grid]", target_position={"x": 80, "y": 40})
    page.drag_and_drop('[data-field="trade.DESK"]', ".bi-dvis")
    page.locator(".bi-dvis").first.click()
    expect(page.locator(".bi-dvis").first).to_contain_text("3 rows", timeout=15000)              # the report's own measure travels with the query as define
    page.locator("[data-visual-query] summary").first.click()
    expect(page.locator("[data-visual-query-text]").first).to_contain_text(f"model: {name}@1", timeout=15000)   # the Fields pane's (the Studio dock has the same)
    expect(page.locator("[data-visual-query-text]").first).to_contain_text("define:")
    page.locator('[data-qp-view="sql"]').first.click()
    sql = page.locator("[data-visual-query-text]").first.text_content()
    capture("query-panel.sql", sql)
    assert sql.startswith("SELECT trade.DESK") and "FROM   trade" in sql and "GROUP  BY trade.DESK" in sql and "MEASURE('Sum of MTM') / 1000 AS" in sql, sql
    # Run as SQL: the server answers the same rows; the plan is the visual's when the question is the same (here a model measure only)
    page.locator("[data-qp-run]").first.click()
    expect(page.locator("[data-qp-rows]").first).to_have_attribute("data-qp-rows", "3", timeout=15000)
    shoot(page, "[data-visual-query]", "query-panel-sql")
    capture("query-panel-run.txt", page.locator("[data-qp-out]").first.text_content())
    page.click("[data-save]")
    expect(page.locator(".bi-near").first).to_contain_text("Saved", timeout=15000)
    body = fetch(page, "GET", "/bi/api/items/" + page.url.rsplit("/", 1)[-1])["body"]["body"]
    assert "measures:" in body and "MTM in thousands" in body, body
    # the same question as a visual, a model query and SQL: one plan hash (BI_DESIGN.md 12.2, acceptance 2)
    mq = fetch(page, "POST", "/bi/api/mq", {"mq": 1, "model": f"{name}@1", "select": {"fields": ["trade.DESK"], "measures": ["Sum of MTM"]}})
    sq = fetch(page, "POST", "/bi/api/sql", {"model": f"{name}@1", "sql": "SELECT trade.DESK, MEASURE('Sum of MTM') AS \"Sum of MTM\" FROM trade GROUP BY trade.DESK"})
    assert mq["status"] == 200 and sq["status"] == 200, (mq, sq)
    assert mq["body"]["meta"]["plan"] == sq["body"]["meta"]["plan"], (mq["body"]["meta"], sq["body"]["meta"])
    assert sorted(map(tuple, mq["body"]["rows"])) == sorted(map(tuple, sq["body"]["rows"]))
    clean(page)
    page.context.close()


def test_an_sql_view_dataset_in_the_wizard_and_an_analyse_cell_on_the_server(browser):
    page = sign_in(browser, "wes")
    new_model(page, "SQL model")
    page.click('[data-tab="measures"]')
    page.select_option("[data-nm-alias]", "trade")
    page.select_option("[data-nm-column]", "MTM")
    page.click("[data-nm-add]")
    expect(page.locator('[data-measure="Sum of MTM"]')).to_be_visible(timeout=15000)
    page.click("[data-save]")
    expect(page.locator("[data-state]")).to_have_text("Saved draft", timeout=15000)
    ref = page.evaluate("window.drishtiModel.doc.model") + "@1"
    # the wizard: an SQL view over the model, checked by the server, previewed, saved
    page.evaluate("window.sessionStorage.clear()")
    wizard(page)
    page.check('[data-src="sql"]')
    page.select_option("[data-src-sql-model]", ref)
    page.fill("[data-src-sql]", "SELECT desk.NAME AS desk, MEASURE('Sum of MTM') AS mtm FROM trade JOIN desk GROUP BY desk.NAME")
    page.click("[data-sql-check]")
    expect(page.locator("[data-sql-ok]")).to_contain_text("desk", timeout=15000)
    shoot(page, '[data-step="root"]', "wizard-sql-view")
    next_step(page, "preview")
    page.click("[data-preview-go]")
    expect(page.locator("[data-preview-table] tbody tr")).to_have_count(3, timeout=20000)
    shoot(page, '[data-step="preview"]', "wizard-sql-preview")
    next_step(page, "save")
    page.fill("[data-title]", "Desk MTM")
    page.locator("[data-title]").press("Tab")
    expect(page.locator("[data-yaml]")).to_contain_text("source: {sql:", timeout=10000)
    capture("wizard-sql.yaml", page.locator("[data-yaml]").text_content())
    page.click("[data-save]")
    expect(page.locator("[data-after-open]")).to_be_visible(timeout=20000)
    # Analyse: an extract of the model; a new SQL cell is Drishti SQL over it, run by the server
    page.goto(CONSOLE_URL + "/bi/explore")
    page.wait_for_function("() => document.querySelectorAll('[data-dataset] option').length > 1", timeout=30000)
    page.select_option("[data-dataset]", "mq:" + ref)
    page.wait_for_selector("[data-fields] input[name=f]", timeout=30000)
    page.click("[data-open-go]")
    page.wait_for_function("() => /rows,/.test(document.querySelector('[data-info]').textContent)", timeout=60000)
    page.click("#bxT-sql")
    cell = page.locator("[data-cells=sql] textarea").first
    expect(page.locator("[data-cells=sql] [data-sql-engine]").first).to_have_attribute("data-sql-engine", "server")
    assert cell.input_value().startswith("SELECT ") and "MEASURE('Sum of MTM')" in cell.input_value(), cell.input_value()
    capture("analyse-starter.sql", cell.input_value())
    page.click("[data-cells=sql] [data-run]")
    expect(page.locator('[data-cells=sql] [data-engine="dsql"]')).to_contain_text("Drishti SQL, run by the server over " + ref, timeout=20000)
    shoot(page, "[data-cells=sql] .bx-cell", "analyse-dsql-cell")
    clean(page)
    page.context.close()


def test_a_reports_fields_pane_inherits_the_models_properties_and_perspectives(browser):
    page = sign_in(browser, "wes")
    new_model(page, "Inherit model")
    page.click('[data-tab="measures"]')
    page.select_option("[data-nm-alias]", "trade")
    page.select_option("[data-nm-column]", "MTM")
    page.click("[data-nm-add]")
    expect(page.locator('[data-measure="Sum of MTM"]')).to_be_visible(timeout=15000)
    page.click('[data-tab="fields"]')
    page.locator('[data-field-row="trade.DESK"] [data-fp="display"]').fill("Trading desk")
    page.locator('[data-field-row="trade.DESK"] [data-fp="display"]').press("Tab")
    page.locator('[data-field-row="trade.MTM"] [data-fp="folder"]').fill("Risk")
    page.locator('[data-field-row="trade.MTM"] [data-fp="folder"]').press("Tab")
    page.locator('[data-field-row="trade.PORTFOLIO"] [data-fp="hidden"]').check()
    page.fill("[data-persp-new]", "Desk view")
    page.click("[data-persp-add]")
    page.locator('[data-persp-pick="desk-view:trade.DESK"]').check()
    page.locator('[data-persp-pick="desk-view:Sum of MTM"]').check()
    page.wait_for_function("() => window.drishtiModel.yaml.indexOf('measures: [Sum of MTM]') >= 0", timeout=10000)
    page.click("[data-save]")
    expect(page.locator("[data-state]")).to_have_text("Saved draft", timeout=15000)
    page.click("[data-report]")
    page.click("[data-ask-go]")
    page.wait_for_url("**/bi/design/**", timeout=20000)
    expect(page.locator('[data-model-header="model"]')).to_be_visible(timeout=30000)
    expect(page.locator('[data-field="trade.DESK"]')).to_contain_text("Trading desk")              # the model's display name, not re-declared
    expect(page.locator('[data-folder="Risk"] [data-field="trade.MTM"]')).to_be_visible()
    expect(page.locator('[data-field="trade.PORTFOLIO"]')).to_have_count(0)                            # hidden by the model
    page.select_option("[data-perspective-pick]", "desk-view")
    expect(page.locator('[data-field="trade.CURRENCY"]')).to_have_count(0, timeout=10000)
    expect(page.locator('[data-field="trade.DESK"]')).to_be_visible()
    expect(page.locator('[data-field="Sum of MTM"]')).to_be_visible()
    shoot(page, ".bi-pane-fields", "report-fields-perspective")
    clean(page)
    page.context.close()


def test_the_wizards_preview_answers_a_filtered_grouped_dataset(browser):
    page = sign_in(browser, "wes")
    page.evaluate("window.sessionStorage.clear()")
    wizard(page)
    page.select_option("[data-src-extends]", "pack.trading.trade")
    next_step(page, "columns")
    expect(page.locator("[data-keep-col]")).not_to_have_count(0, timeout=15000)
    next_step(page, "filter")
    page.fill("[data-where]", "TRADE_STATUS == 'VALIDATED'")
    page.locator("[data-where]").press("Tab")
    next_step(page, "grain")
    next_step(page, "derive")
    next_step(page, "group")
    page.check("[data-group-on]")
    page.click('[data-group-key="DESK"]')
    next_step(page, "preview")
    page.click("[data-preview-go]")                                                              # the draft saved, then its first rows from the server
    page.click("[data-confirm-go]")                                                            # the server previews a saved draft: asked first
    expect(page.locator("[data-preview-table] tbody tr")).to_have_count(3, timeout=20000)       # grouped by desk: three desks
    shoot(page, '[data-step="preview"]', "wizard-preview")
    clean(page)
    page.context.close()


def test_the_wizards_preview_answers_a_grouped_dataset_with_a_worked_out_column(browser):
    page = sign_in(browser, "wes")
    page.evaluate("window.sessionStorage.clear()")
    wizard(page)
    page.select_option("[data-src-extends]", "pack.trading.trade")
    next_step(page, "columns")
    expect(page.locator("[data-keep-col]")).not_to_have_count(0, timeout=15000)
    next_step(page, "filter")
    next_step(page, "grain")
    next_step(page, "derive")
    page.click('[data-derive-add="expr"]')
    page.fill('[data-derive-name="0"]', "notionalM")
    page.locator('[data-derive-name="0"]').press("Tab")
    page.fill('[data-derive-expr="0"]', "NOTIONAL / 1000000")
    page.locator('[data-derive-expr="0"]').press("Tab")
    next_step(page, "group")
    page.check("[data-group-on]")
    page.click('[data-group-key="DESK"]')
    next_step(page, "preview")
    page.click("[data-preview-go]")
    page.click("[data-confirm-go]")                                                            # the server previews a saved draft: asked first
    expect(page.locator("[data-preview-table] tbody tr")).to_have_count(3, timeout=20000)
    expect(page.locator("[data-preview-table] thead")).to_contain_text("notionalM")
    shoot(page, '[data-step="preview"]', "wizard-preview-grouped")
    clean(page)
    page.context.close()


BUCKET = """rachana: 1
python: notional-size
title: Notional size
description: Big or small, by the trade's notional (a Python column of the dataset wizard).
version: 1
interface: transform
inputs: [rows]
packages: [pandas]
target: server
source: |
  def run(rows, params):
      out = rows.copy()
      out[params["column"]] = (out["NOTIONAL"] >= 4000000).map({True: "big", False: "small"})
      return out
"""


def test_the_wizard_adds_a_python_column(browser):
    page = sign_in(browser, "wes")
    ws = fetch(page, "POST", "/bi/api/workspaces", {"name": "Python columns desk", "description": ""})["body"]["id"]
    item = fetch(page, "POST", f"/bi/api/workspaces/{ws}/items", {"kind": "python", "title": "Notional size", "description": "", "body": BUCKET})["body"]
    assert fetch(page, "POST", f"/bi/api/items/{item['id']}/publish", {"note": "test"})["status"] == 200
    page.evaluate("window.sessionStorage.clear()")
    wizard(page)
    page.select_option("[data-src-extends]", "pack.trading.trade")
    next_step(page, "columns")
    expect(page.locator("[data-keep-col]")).not_to_have_count(0, timeout=15000)
    next_step(page, "filter")
    next_step(page, "grain")
    next_step(page, "derive")
    page.click('[data-derive-add="python"]')
    page.fill('[data-derive-name="0"]', "size")
    page.locator('[data-derive-name="0"]').press("Tab")
    page.select_option('[data-derive-python="0"]', label="Notional size")
    page.locator('[data-derive-input="NOTIONAL"]').check()
    shoot(page, '[data-step="derive"]', "wizard-python-column")
    next_step(page, "group")
    next_step(page, "preview")
    page.click("[data-preview-go]")
    page.click("[data-confirm-go]")                                                            # the server previews a saved draft: asked first
    expect(page.locator("[data-preview-table] thead")).to_contain_text("size", timeout=30000)
    cells = page.eval_on_selector_all("[data-preview-table] tbody tr", "rows => rows.map(r => [...r.children].map(c => c.textContent))")
    heads = page.eval_on_selector_all("[data-preview-table] thead th", "hs => hs.map(h => h.textContent)")
    col = [i for i, h in enumerate(heads) if h == "size" or h.endswith(".size")][0]                  # the column as the dataset names it
    assert {r[col] for r in cells} <= {"big", "small"} and cells, (heads, cells[:3])
    clean(page)
    page.context.close()


SMALL_TARGETS = """(sel) => [...document.querySelectorAll(sel)].filter(e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0 && r.height < 43.5; })
    .map(e => (e.getAttribute('data-xe-opt') || e.getAttribute('data-tab') || e.textContent || e.tagName).trim().slice(0, 30) + ' ' + Math.round(e.getBoundingClientRect().height))"""


def test_the_new_designer_parts_fit_a_phone(browser):
    page = sign_in(browser, "wes", width=390)
    new_model(page, "Phone model")
    for tab in ("measures", "fields", "security", "contract"):
        page.click(f'[data-tab="{tab}"]')
        page.wait_for_timeout(400)
        wide = page.evaluate("document.documentElement.scrollWidth - window.innerWidth")
        assert wide <= 1, f"the {tab} tab scrolls sideways by {wide} px"
    page.click('[data-tab="measures"]')
    page.click("[data-nf] summary")
    ed = page.locator("[data-nf-expr]")
    ed.click()
    ed.type("trade.")
    expect(page.locator(".bi-xe-opt").first).to_be_visible(timeout=10000)
    small = page.evaluate(SMALL_TARGETS, "[data-nf] .bi-xe-opt, [data-nf] .bi-xe-help, [data-nf] .bi-xe-try, [data-nf] button")
    assert not small, small
    page.keyboard.press("Escape")
    page.click('[data-tab="fields"]')
    small = page.evaluate(SMALL_TARGETS, "[data-fields-pane] input:not([type=checkbox]), [data-fields-pane] select, [data-fields-pane] summary, [data-fields-pane] button")
    assert not small, small
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
