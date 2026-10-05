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

"""The workbench's forms in a real browser: the inspector (an option, the title, a list, expression completion), the YAML tab as an
operation, the Problems tab (a refused edit with its line, a field the samples rarely have) and auto-design as a revision undo can
take back. Fixtures and helpers are test_workbench_browser.py's."""
import json

from test_workbench_browser import browser, drag, kinds, open_design, page, settle, state, wait  # noqa: F401 - fixtures and helpers
from wb_live import BROWSER_WAIT_MS, live_console, showcase_json, showcase_sutra  # noqa: F401 - the fixture


def test_the_inspector_edits_options_the_title_and_lists_and_completes_expressions(live_console, page):
    open_design(page, live_console, "inspector", sutra=showcase_sutra(), files={"showcase.json": showcase_json()})
    page.locator('[data-preview] [data-panel="terms"] .pnl-h').click()
    rev = state(page, "rev")
    heading = page.get_by_label("title", exact=True)
    heading.fill("Bond terms, edited")
    heading.blur()
    settle(page, rev)
    assert "Bond terms, edited" in page.locator('[data-preview] [data-panel="terms"]').inner_text()
    # a list option: reorder the first two columns, then remove one
    labels = lambda: page.evaluate("window.DrishtiWB.model(window.drishtiWorkbench.store.state.yaml).panels.filter(p => p.id === 'terms')[0].values.columns.map(c => c.label)")
    first = labels()
    rev = state(page, "rev")
    page.get_by_role("button", name="Move down: columns 1").click()
    settle(page, rev)
    assert labels()[:2] == [first[1], first[0]]
    rev = state(page, "rev")
    page.get_by_role("button", name="Remove columns 1").click()
    settle(page, rev)
    assert len(labels()) == len(first) - 1
    # an expression field completes shape paths
    page.locator('[data-preview] [data-panel="coupons"] .pnl-h').click()
    rows = page.get_by_label("rows *")
    rows.fill("$.cou")
    page.locator(".wb-expr .wb-menu-i").first.wait_for()
    assert page.locator(".wb-expr .wb-menu-i").first.inner_text().startswith("$.cou")
    page.keyboard.press("Enter")
    assert page.get_by_label("rows *").input_value().startswith("$.cou")
    # the title region has its own form
    page.locator("[data-preview] .vtitle").click()
    pill = page.get_by_label("Pill (text, may hold ${…})")
    rev = state(page, "rev")
    pill.fill("Pill edited")
    pill.blur()
    settle(page, rev)
    assert "Pill edited" in page.locator("[data-preview] .vtitle").inner_text()
    assert not page.errors, page.errors


def test_typing_in_the_yaml_tab_is_an_operation_and_a_refused_edit_is_a_problem_with_a_line(live_console, page):
    open_design(page, live_console, "yaml", sutra=showcase_sutra(), files={"showcase.json": showcase_json()})
    page.get_by_role("tab", name="YAML").click()
    rev = state(page, "rev")
    page.evaluate("document.querySelector('.CodeMirror').CodeMirror.replaceRange('# a note typed in the YAML tab\\n', {line: 14, ch: 0})")
    settle(page, rev)
    assert "# a note typed in the YAML tab" in state(page, "yaml")
    assert page.evaluate("document.querySelector('.CodeMirror').CodeMirror.getValue()").count("# a note typed in the YAML tab") == 1
    page.evaluate("document.querySelector('.CodeMirror').CodeMirror.replaceRange('  - id: broken\\n    kind: nonsense\\n', {line: 40, ch: 0})")
    page.get_by_role("tab", name="Problems").click()
    page.locator(".wb-problem-b", has_text="nonsense").first.wait_for(timeout=15000)
    assert "# a note typed in the YAML tab" in state(page, "yaml") and "nonsense" not in state(page, "yaml")      # the design kept its last good Sutra
    assert "nonsense" in page.evaluate("document.querySelector('.CodeMirror').CodeMirror.getValue()")              # and your text is still yours
    page.locator(".wb-problem-b", has_text="nonsense").first.click()
    assert page.get_by_role("tab", name="YAML").get_attribute("aria-selected") == "true"
    assert page.evaluate("document.querySelector('.CodeMirror').CodeMirror.getCursor().line") >= 40


RARE_SUTRA = """rachana: 1
sutra: rare-test
version: 1
match: { kind: trade }
title: { id: $.tradeId }
panels:
  - id: facts
    kind: kv
    columns:
      - { label: Rare, bind: $.rare }
"""


def test_the_problems_tab_warns_about_a_field_the_samples_rarely_have(live_console, page):
    docs = {"a.json": json.dumps({"tradeId": "A"}), "b.json": json.dumps({"tradeId": "B"}), "c.json": json.dumps({"tradeId": "C", "rare": 1})}
    open_design(page, live_console, "rare", sutra=RARE_SUTRA, files=docs)
    page.get_by_role("tab", name="Problems").click()
    page.locator(".wb-problem-b", has_text="$.rare").first.wait_for(timeout=15000)
    assert "33%" in page.locator("[data-problems]").inner_text()
    page.locator(".wb-problem-b", has_text="$.rare").first.click()
    assert page.evaluate("window.drishtiWorkbench.canvas.selected().id") == "facts"


def test_a_field_dropped_on_a_panel_that_cannot_take_it_says_why(live_console, page):
    open_design(page, live_console, "why not", sutra=showcase_sutra(), files={"showcase.json": showcase_json()})
    note = page.locator('[data-preview] [data-panel="note"]')
    note.scroll_into_view_if_needed()
    b = note.bounding_box()
    rev = state(page, "rev")
    drag(page, page.locator(".wb-field-row", has_text="currency").first, b["x"] + b["width"] / 2, b["y"] + b["height"] / 2)
    wait(page, "document.querySelector('[data-say]').textContent.includes('take these roles')")
    assert "DRS-5022" in page.locator("[data-say]").inner_text() and state(page, "rev") == rev
    assert "Problems" in page.get_by_role("tab", name="Problems").inner_text()


def test_phone_width_theme_and_the_sample_switcher_in_the_status_bar(live_console, page):
    docs = {"a.json": json.dumps({"tradeId": "A"}), "b.json": json.dumps({"tradeId": "B"})}
    open_design(page, live_console, "bar", sutra=RARE_SUTRA.replace("$.rare", "$.tradeId"), files=docs)
    assert "sample 1/2" in page.locator("[data-sample-pos]").inner_text()
    page.locator("[data-next]").click()
    wait(page, "document.querySelector('[data-sample-pos]').textContent.includes('sample 2/2')")
    wait(page, "document.querySelector('[data-preview]').textContent.includes('B')")
    page.locator("[data-prev]").click()
    wait(page, "document.querySelector('[data-sample-pos]').textContent.includes('sample 1/2')")
    page.locator('[data-width="phone"]').click()
    assert page.evaluate("!!document.querySelector('.wb-phone')") and page.locator('[data-width="phone"]').get_attribute("aria-pressed") == "true"
    assert page.evaluate("document.querySelector('.wb-frame').getBoundingClientRect().width") < 420
    page.locator('[data-width="desktop"]').click()
    assert page.evaluate("!document.querySelector('.wb-phone')")
    options = page.locator("[data-theme-pick] option").all_inner_texts()
    assert len(options) >= 4
    choice = page.locator("[data-theme-pick] option").nth(2).get_attribute("value")
    page.select_option("[data-theme-pick]", choice)
    assert page.evaluate("document.documentElement.getAttribute('data-theme')") == choice


def test_clicking_the_canvas_shows_the_inspector_but_a_matrix_cell_does_not_leave_the_tests_tab(live_console, page):
    open_design(page, live_console, "tabs", sutra=showcase_sutra(), files={"showcase.json": showcase_json()})
    page.get_by_role("tab", name="Problems").click()
    page.locator('[data-preview] [data-panel="terms"] .pnl-h').click()
    assert page.get_by_role("tab", name="Inspector").get_attribute("aria-selected") == "true"
    page.get_by_role("tab", name="Tests").click()
    page.locator(".wb-matrix").wait_for(timeout=BROWSER_WAIT_MS)
    page.locator('.wb-matrix tbody tr:nth-child(2) button').first.click()
    assert page.get_by_role("tab", name="Tests").get_attribute("aria-selected") == "true"
    assert page.evaluate("window.drishtiWorkbench.canvas.selected().id") == "ops"


def test_auto_design_is_a_revision_and_undo_brings_the_old_sutra_back(live_console, page):
    docs = {"a.json": json.dumps({"tradeId": "A", "book": "rates", "notional": 5}), "b.json": json.dumps({"tradeId": "B", "book": "fx", "notional": 7})}
    open_design(page, live_console, "auto", files=docs)
    start, rev = state(page, "yaml"), state(page, "rev")
    page.locator("[data-autodesign]").click()
    page.get_by_role("dialog", name="Replace the Sutra?").get_by_role("button", name="Auto-design").click()          # an in-page question, not confirm()
    settle(page, rev)
    assert state(page, "yaml") != start and page.locator("[data-draft]").is_visible()
    rev = state(page, "rev")
    page.locator("[data-undo]").click()
    settle(page, rev)
    assert state(page, "yaml") == start
