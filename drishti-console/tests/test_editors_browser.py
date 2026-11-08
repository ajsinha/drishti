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

"""Build › Tables & models in Chromium (docs/architecture/SHARED_SCREENS.md sections 1 to 4), over a real server and console from the worker's pool
(security on, the default packs and their demo data, a tables folder of the file's own): a click on every button of the shell, the table editor, the
model editor, the Python-driven table editor and the + New steps, each checked for what it does; the live YAML and the visual side follow each
other; the theme and the phone layout; keyboard access; no page error and no policy violation.

Skipped when Playwright, Chromium or the built server jar is missing."""
from __future__ import annotations

import sys

import pytest

from conftest import CONSOLE
from test_workbench_browser import browser, wait  # noqa: F401 - fixtures and helpers
from wb_live import TOKEN_SECRET, _stack

TABLE = """# shipped for the browser test
rachana: 3
table: trading.trade_summary
title: Trades in brief
from: { kind: trade }
columns:
  ID:      { path: $.TRADE_ID, type: string, key: true }
  PRODUCT: { path: $.PRODUCT_TYPE, type: string }
  MTM:     { path: $.QUANTITY, type: decimal }
"""
DESKS = """rachana: 3
table: trading.trade_products
from: { kind: trade }
columns:
  PRODUCT: { path: $.PRODUCT_TYPE, type: string, key: true }
  NAME:    { path: $.PRODUCT_SUB_TYPE, type: string }
"""
MODEL = """rachana: 3
model: trading-brief
title: Trades in brief
tables:
  t: { table: trading.trade_summary, role: fact }
  p: { table: trading.trade_products, role: dimension }
relationships:
  - { from: t.PRODUCT, to: p.PRODUCT }
measures:
  MTM:      { expr: "sum(t.MTM)" }
  Avg MTM:  { expr: "avg(t.MTM)" }
hierarchies:
  Product: [p.PRODUCT]
"""
VIOLATIONS = "window.__csp = []; document.addEventListener('securitypolicyviolation', e => window.__csp.push(e.violatedDirective + ' ' + e.blockedURI));"


def fx(pg, sel, text):
    """A formula field (drishti-inputs' one-line Rachana code editor; the element holds the value) given new text, as a paste."""
    wait(pg, f"!!(document.querySelector({sel!r}) || {{}}).drishtiCode && !!document.querySelector({sel!r}).drishtiCode.cm")
    pg.evaluate("""([s, t]) => { const cm = document.querySelector(s).drishtiCode.cm; cm.focus();
      cm.replaceRange(t, { line: 0, ch: 0 }, { line: cm.lastLine(), ch: cm.getLine(cm.lastLine()).length }, 'paste'); }""", [sel, text])


def fill_yaml(pg, text):
    """Types the whole YAML anew, as a person does (the editor is drishti-inputs' CodeEditor: CodeMirror's +input origin)."""
    pg.wait_for_selector("[data-yaml] .CodeMirror")
    pg.evaluate("t => { const cm = document.querySelector('[data-yaml] .CodeMirror').CodeMirror; cm.focus(); cm.replaceRange(t, {line: 0, ch: 0}, {line: cm.lineCount(), ch: 0}, '+input'); }", text)


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    root = tmp_path_factory.mktemp("tables")
    (root / "trading").mkdir()
    (root / "trading" / "trade_summary.table.yaml").write_text(TABLE)
    (root / "trading" / "trade_products.table.yaml").write_text(DESKS)
    (root / "trading" / "trading_brief.model.yaml").write_text(MODEL)
    yield from _stack(tmp_path_factory, {"DRISHTI_SECURITY_ENABLED": "true", "DRISHTI_TOKEN_SECRET": TOKEN_SECRET,
                                         "DRISHTI_TABLES_ROOT": str(root), "DRISHTI_OFFLINE": "true",
                                         # Python runs on the server's compute pool python, in the interpreter that runs this test (the pinned libraries)
                                         "DRISHTI_COMPUTE_ENABLED": "true", "DRISHTI_PYTHON": sys.executable,
                                         "DRISHTI_COMPUTE_FILE": str(CONSOLE.parent / "config" / "compute" / "compute.yaml")}, auth=True)


def settle(pg):
    pg.evaluate("(o) => window.Drishti.settled(o)", {"timeout": 60000, "animations": False})


@pytest.fixture()
def pg(browser, stack):  # noqa: F811 - the module's browser fixture
    context = browser.new_context(viewport={"width": 1500, "height": 1000})
    context.add_init_script(VIOLATIONS)
    page = context.new_page()
    page.errors = []
    page.on("pageerror", lambda e: page.errors.append(str(e)))
    page.goto(str(stack) + "/login")
    page.fill("input[name=user]", "drishti-dev-admin")
    page.fill("input[name=password]", "drishti-dev-admin123")
    page.click("form button[type=submit], form input[type=submit]")
    page.wait_for_url(lambda u: "/login" not in u)
    page.base = str(stack)
    yield page
    assert page.evaluate("window.__csp || []") == [], "a policy violation"
    assert page.errors == [], page.errors
    context.close()


def open_editors(pg, query=""):
    pg.goto(pg.base + "/build/tables" + query)
    wait(pg, "!!document.querySelector('[data-editors]') && document.querySelector('[data-editors]').getAttribute('aria-busy') === 'false'")
    settle(pg)


def open_object(pg, name):
    pg.click(f"[data-object='{name}']")
    wait(pg, f"document.querySelector('[data-title]').textContent === '{name}'")
    settle(pg)


def yaml(pg):
    return pg.evaluate("window.Drishti.editors.yamlText()")


# ---- an editor's row grids (drishti-grid with cell editing: E.rowGrid) -------------------------------------------------------------


def gcell(pg, key, rid, c):
    """Focuses a cell of an editor's row grid by its row id and column number and starts editing it, as Enter on it does (a checkbox
    turns at once). The grid draws only the rows in view, so the row is found in its model, not in the page."""
    wait(pg, f"!!(document.querySelector(\"[data-rowgrid='{key}']\") || {{}}).grid")
    pg.evaluate("""([k, id, c]) => { const g = document.querySelector(`[data-rowgrid='${k}']`).grid;
      for (let r = 0; r < g.model.rowCount(); r++) { if (g.model.idAt(r) === id) { g.focusCell(r, c); g.startEditing(r, c); return; } }
      throw new Error('no row ' + id + ' in ' + k); }""", [key, str(rid), c])


def row_js(key, rid):
    """A JavaScript test: the row grid holds a row of that id (in its model: drawn or not)."""
    return (f"(() => {{ const b = document.querySelector(\"[data-rowgrid='{key}']\"); if (!b || !b.grid) {{ return false; }} const m = b.grid.model;"
            f" for (let r = 0; r < m.rowCount(); r++) {{ if (m.idAt(r) === {str(rid)!r}) {{ return true; }} }} return false; }})()")


OPEN = ("!!document.querySelector('.dg-editor') && document.querySelector('.dg-editor').style.visibility !== 'hidden'"
        " && (document.querySelector('.dg-editor').contains(document.activeElement) || !!document.activeElement.closest('.di-select-pop'))")      # the cell editor over its cell, with the focus


def gselect(pg, key, rid, c, label):
    gcell(pg, key, rid, c)
    wait(pg, OPEN)
    pg.locator(".di-select-pop").get_by_role("option", name=label, exact=True).click()   # drishti-inputs' Select: picking saves
    wait(pg, "!document.querySelector('.dg-editor')")


def gtext(pg, key, rid, c, text):
    gcell(pg, key, rid, c)
    wait(pg, OPEN)
    pg.fill(".dg-editor input", text)
    pg.keyboard.press("Enter")


def gcode(pg, key, rid, c, text):
    """A formula cell (drishti-inputs' one-line code editor over the cell) given new text, as a paste, then Enter."""
    gcell(pg, key, rid, c)
    wait(pg, "!!document.querySelector('.dg-editor-code .CodeMirror')")
    pg.evaluate("""t => { const cm = document.querySelector('.dg-editor-code .CodeMirror').CodeMirror; cm.focus();
      cm.replaceRange(t, { line: 0, ch: 0 }, { line: cm.lastLine(), ch: cm.getLine(cm.lastLine()).length }, 'paste'); }""", text)
    pg.keyboard.press("Enter")
    wait(pg, "!document.querySelector('.dg-editor')")


# ---- the shell --------------------------------------------------------------------------------------------------------------------


def test_the_shell_lists_objects_opens_one_and_every_header_button_works(pg):
    open_editors(pg)
    assert pg.locator("[data-group='tables'] [data-object]").count() == 2
    assert pg.locator("[data-group='models'] [data-object='trading-brief']").count() == 1
    pg.fill("[data-ed-search]", "products")
    wait(pg, "document.querySelectorAll('[data-group=tables] [data-object]').length === 1")
    pg.fill("[data-ed-search]", "")
    open_object(pg, "trading.trade_summary")
    # a shipped definition: read-only, its YAML below the editor, a preview with totals from the server
    assert "read-only" in pg.inner_text("[data-readonly]")
    assert yaml(pg).startswith("# shipped for the browser test")
    assert pg.is_disabled("[data-act=save]") and pg.is_disabled("[data-act=history]")
    wait(pg, "!!document.querySelector('[data-preview] [data-totals]')")
    assert "unique across every table" in pg.inner_text("[data-checks]") and "source" in pg.inner_text("[data-checks]")
    # Visual / YAML / Both
    pg.click("[data-mode=yaml]")
    assert pg.is_hidden("[data-visual]") and pg.is_visible("[data-yaml]")
    pg.click("[data-mode=visual]")
    assert pg.is_hidden("[data-yaml-panel]") and pg.is_visible("[data-visual]")
    pg.click("[data-mode=both]")
    assert pg.is_visible("[data-visual]") and pg.is_visible("[data-yaml]")
    pg.click("[data-yaml-toggle]")
    assert pg.is_hidden("[data-yaml-body]")
    pg.click("[data-yaml-toggle]")
    assert pg.is_visible("[data-yaml-body]")
    # Validate, Preview, Impact (with its dialog and Close)
    pg.click("[data-act=validate]")
    wait(pg, "/Valid|problem/.test(document.querySelector('[data-msg]').textContent)")
    pg.click("[data-act=preview]")
    settle(pg)
    assert pg.locator("[data-preview] tbody tr").count() > 0
    pg.click("[data-act=impact]")
    wait(pg, "!!document.querySelector('[data-dialog][open] [data-impact]')")
    assert "trading-brief" in pg.inner_text("[data-impact]")
    pg.click("[data-dialog-close]")
    assert pg.is_hidden("[data-dialog]")


def test_the_view_is_remembered_per_person(pg):
    open_editors(pg)
    pg.click("[data-mode=yaml]")
    settle(pg)
    open_editors(pg, "?kind=table&name=trading.trade_summary")
    assert pg.get_attribute("[data-mode=yaml]", "aria-pressed") == "true"
    pg.click("[data-mode=both]")
    settle(pg)


def test_copy_as_a_draft_save_propose_and_history(pg):
    r = pg.request.post(pg.base + "/bi/api/workspaces", data='{"name": "Editors Desk"}', headers={"Content-Type": "application/json"})
    assert r.status in (200, 201, 409), r.text()
    open_editors(pg, "?kind=table&name=trading.trade_summary")
    pg.click("[data-copy]")
    wait(pg, "document.querySelector('[data-title]').textContent === 'trading.trade_summary_copy'")
    settle(pg)
    assert "unsaved" in pg.inner_text("[data-status]")
    assert pg.is_disabled("[data-act=propose]")
    pg.click("[data-act=save]")
    wait(pg, "!!document.querySelector('[data-dialog][open] [data-save-go]')")
    wait(pg, "document.querySelector('[data-save-ws]').component.options.length > 1")
    pg.select_option("[data-save-ws]", label="Editors Desk")
    pg.fill("[data-save-msg]", "a copy to change")
    pg.click("[data-save-go]")
    wait(pg, "/Saved as a draft/.test(document.querySelector('[data-msg]').textContent)")
    assert pg.is_enabled("[data-act=history]")
    pg.click("[data-act=history]")
    wait(pg, "!!document.querySelector('[data-dialog][open] [data-history]')")
    pg.click("[data-dialog-close]")
    pg.click("[data-act=propose]")
    wait(pg, "!!document.querySelector('[data-dialog][open] [data-propose-go]')")
    pg.fill("[data-propose-note]", "please look")
    pg.click("[data-propose-go]")
    wait(pg, "/Proposed for review/.test(document.querySelector('[data-msg]').textContent)")
    assert "Proposed" in pg.inner_text("[data-status]")
    assert pg.locator("[data-group='tables'] [data-object='trading.trade_summary_copy']").count() == 1


# ---- the table editor --------------------------------------------------------------------------------------------------------------


def new_table(pg, name):
    open_editors(pg)
    pg.click("[data-act=new]")
    assert pg.is_visible("[data-newmenu]")
    pg.click("[data-new-kind=table]")
    pg.fill("[data-new-name]", name)
    pg.fill("[data-new-title]", "My trades")
    pg.click("[data-step-next]")
    pg.select_option("[data-new-source]", "kind:trade")
    pg.click("[data-step-next]")
    wait(pg, "document.querySelectorAll('[data-new-col]').length > 0")
    first = pg.get_attribute("[data-new-col] >> nth=0", "data-new-col")
    pg.uncheck(f"[data-new-col='{first}']")
    pg.click("[data-step-back]")
    pg.click("[data-step-next]")
    wait(pg, "document.querySelectorAll('[data-new-col]').length > 0")
    pg.click("[data-step-next]")
    pg.click("[data-step-next]")                  # Open in the editor
    wait(pg, "!!document.querySelector('[data-editor=table] [data-columns]')")
    settle(pg)
    return first


def test_new_table_steps_land_in_the_editor_and_both_sides_follow_each_other(pg):
    dropped = new_table(pg, "mine.trade_view")
    assert f"[data-col-row='{dropped}']" and pg.locator(f"[data-col-row='{dropped}']").count() == 0
    assert "table: mine.trade_view" in yaml(pg)
    # a visual edit shows in the YAML with its line highlighted
    pg.click("[data-add-computed]")                                            # its formula cell opens for typing
    wait(pg, "!!document.querySelector('.dg-editor-code .CodeMirror')")
    pg.keyboard.press("Escape")
    gcode(pg, "columns", "chosen:NEW_COLUMN", 2, "$.QUANTITY / 1000")
    wait(pg, "window.Drishti.editors.yamlText().includes('$.QUANTITY / 1000')")
    settle(pg)
    assert pg.locator("[data-yaml] .di-code[data-hl]").count() == 1            # the line being edited is marked in the YAML
    gtext(pg, "columns", "chosen:NEW_COLUMN", 1, "MTM_K")
    wait(pg, "window.Drishti.editors.yamlText().includes('MTM_K:')")
    settle(pg)
    gselect(pg, "columns", "chosen:MTM_K", 3, "integer")
    wait(pg, "/MTM_K:[\\s\\S]{0,120}integer/.test(window.Drishti.editors.yamlText())")
    gcell(pg, "columns", "chosen:MTM_K", 4)                                    # a checkbox cell turns at once
    wait(pg, "/(keys: \\[[^\\]]*MTM_K|MTM_K:[\\s\\S]{0,160}key: true)/.test(window.Drishti.editors.yamlText())")
    gcell(pg, "columns", "chosen:MTM_K", 4)
    settle(pg)
    # a source column added from the checklist (the grid's Show cell), then shown off again
    avail = pg.evaluate("""() => { const g = document.querySelector("[data-rowgrid='columns']").grid;
      for (let r = 0; r < g.model.rowCount(); r++) { const id = g.model.idAt(r); if (id.startsWith('available:')) { return id.slice(10); } } return null; }""")
    assert avail, "the source offers a column not chosen yet"
    gcell(pg, "columns", f"available:{avail}", 0)
    wait(pg, row_js("columns", f"chosen:{avail}"))
    settle(pg)
    gcell(pg, "columns", f"chosen:{avail}", 0)
    wait(pg, row_js("columns", f"available:{avail}"))
    settle(pg)
    # the filter, with type-ahead from the function catalogue
    fx(pg, "[data-path=filter]", "")
    pg.keyboard.type("abs")
    wait(pg, "[...document.querySelectorAll('.di-complete-pop .di-option-label')].some(e => e.getClientRects().length && e.textContent === 'abs')")
    pg.keyboard.press("Escape")
    fx(pg, "[data-path=filter]", "$.QUANTITY != null")
    wait(pg, "window.Drishti.editors.yamlText().includes('filter:')")
    pg.select_option("[data-materialize]", "on-load")
    wait(pg, "window.Drishti.editors.yamlText().includes('materialize: on-load')")
    settle(pg)
    # a YAML edit shows in the visual side; a broken line is marked and the visual view waits
    text = yaml(pg).replace("title: My trades", "title: Our trades")
    fill_yaml(pg, text)
    wait(pg, "document.querySelector('[data-path=title]').value === 'Our trades'")
    fill_yaml(pg, text + "columns: [\n")
    wait(pg, "/not valid/.test(document.querySelector('[data-yaml-state]').textContent)")
    wait(pg, "document.querySelectorAll('[data-yaml] .di-problem.di-lint-error').length >= 1")       # the problem listed under the editor (its gutter sign may be out of view: CodeMirror draws the lines in view)
    pg.click("[data-yaml] .di-problem button >> nth=0")                                   # a problem leads to its line (the code editor's list)
    pg.click("[data-mode=visual]")
    assert "waits until the YAML reads" in pg.inner_text("[data-msg]")
    fill_yaml(pg, text)
    wait(pg, "/live/.test(document.querySelector('[data-yaml-state]').textContent) && !/not valid/.test(document.querySelector('[data-yaml-state]').textContent)")
    settle(pg)
    # the source picker (the base row's Source cell: a select of the kinds and tables)
    gselect(pg, "sources", "base", 0, "trading.trade_summary")
    wait(pg, "window.Drishti.editors.yamlText().includes('table: trading.trade_summary')")
    settle(pg)
    gselect(pg, "sources", "base", 0, "trade")
    wait(pg, "window.Drishti.editors.yamlText().includes('kind: trade')")
    settle(pg)


def test_text_toggle_and_a_join_with_its_cost(pg):
    new_table(pg, "mine.trade_join")
    # Text: one Rachana statement, read back on the server
    pg.click("[data-table-view=text]")
    wait(pg, "!!document.querySelector('[data-statement]')")
    assert pg.input_value("[data-statement]").startswith("table mine.trade_join from trade")
    st = pg.input_value("[data-statement]").rstrip() + "\nwhere $.QUANTITY > 0\n"
    fx(pg, "[data-statement]", st)
    wait(pg, "/reads/.test(document.querySelector('[data-statement-state]').textContent)")
    wait(pg, "window.Drishti.editors.yamlText().includes('filter:')")
    pg.click("[data-table-view=grid]")
    wait(pg, "!!document.querySelector('[data-columns]')")
    settle(pg)
    # + Look-up: a dialog, a key, a column; the look-up is a row with its rows read now; then removed
    pg.click("[data-add-lookup]")
    wait(pg, "!!document.querySelector('[data-dialog][open] [data-lookup-go]')")
    pg.select_option("[data-lookup-table]", "trading.trade_products")
    fx(pg, "[data-lookup-on]", "$.productType")
    pg.check("[data-lookup-col='NAME']")
    pg.click("[data-lookup-go]")
    wait(pg, "!!document.querySelector(\"[data-lookup='trading.trade_products']\")")
    wait(pg, "window.Drishti.editors.yamlText().includes('related(trading.trade_products.NAME')")
    settle(pg)
    wait(pg, "!!document.querySelector('[data-cost]')")
    pg.click("[data-remove-lookup='trading.trade_products']")
    wait(pg, "!document.querySelector(\"[data-lookup='trading.trade_products']\")")
    settle(pg)


# ---- the model editor --------------------------------------------------------------------------------------------------------------


def test_model_tabs_measure_card_and_access(pg):
    open_editors(pg, "?kind=model&name=trading-brief")
    assert pg.get_attribute("[data-tab=overview]", "aria-selected") == "true"
    assert pg.locator("[data-question]").count() == 4
    pg.click("[data-goto-access]")
    wait(pg, "document.querySelectorAll('[data-access-role]').length > 0")
    pg.click("[data-copy]")                         # shipped: copy it to change it
    wait(pg, "document.querySelector('[data-title]').textContent === 'trading-brief_copy'")
    settle(pg)
    pg.click("[data-tab=overview]")
    pg.fill("[data-question=about] [data-path=model]", "brief-copy")
    wait(pg, "window.Drishti.editors.yamlText().includes('model: brief-copy')")
    pg.fill("[data-question=about] [data-path=title]", "A copy")
    pg.fill("[data-question=about] [data-path=description]", "Trades in brief, copied")
    settle(pg)
    # keyboard: the tabs move with the arrow keys
    pg.focus("[data-tab=overview]")
    pg.keyboard.press("ArrowRight")
    wait(pg, "document.querySelector('[data-tab=tables]').getAttribute('aria-selected') === 'true'")
    pg.click("[data-add-table]")
    wait(pg, "document.querySelectorAll('[data-model-table]').length === 3")
    last = pg.get_attribute("[data-model-table] >> nth=2", "data-model-table")
    gselect(pg, "tables", last, 2, "dimension")                               # the Role cell: a select in drishti-grid's editor
    wait(pg, f"/{last}:[\\s\\S]*role: dimension/.test(window.Drishti.editors.yamlText())")
    pg.click("[data-add-rel]")
    wait(pg, "document.querySelectorAll('[data-rel]').length === 2")
    gcell(pg, "relationships", "1", 2)                                       # Active: a checkbox cell turns at once
    wait(pg, "window.Drishti.editors.yamlText().includes('active: false')")
    pg.click("[data-remove-rel] >> nth=1")
    pg.click("[data-remove-alias] >> nth=2")
    wait(pg, "document.querySelectorAll('[data-model-table]').length === 2")
    pg.click("[data-tab=fields]")
    wait(pg, "document.querySelectorAll('[data-panel=fields] [data-field]').length >= 4")
    pg.click("[data-tab=measures]")
    wait(pg, "!!document.querySelector(\"[data-adds-up='MTM']\") && document.querySelector(\"[data-adds-up='MTM']\").textContent.startsWith('yes')")
    assert "per total" in pg.inner_text("[data-adds-up='Avg MTM']")
    pg.click("[data-measure-card='MTM']")
    wait(pg, "!!document.querySelector('[data-dialog][open] [data-card]')")
    assert "sum" in pg.inner_text("[data-card]")
    pg.click("[data-dialog-close]")
    pg.fill("[data-new-measure-name]", "Rows")
    pg.click("[data-add-measure]")
    wait(pg, "window.Drishti.editors.yamlText().includes('Rows:')")
    gcode(pg, "measures", "Rows", 1, "count(t)")                             # the formula cell: a one-line Rachana code editor
    wait(pg, "window.Drishti.editors.yamlText().includes('count(t)')")
    gtext(pg, "measures", "Rows", 2, "#,##0")
    wait(pg, "window.Drishti.editors.yamlText().includes('#,##0')")
    pg.click("[data-remove-measure] >> nth=2")
    wait(pg, "!document.querySelector(\"[data-measure='Rows']\")")
    pg.click("[data-tab=hierarchies]")
    pg.fill("[data-new-hierarchy-name]", "By product")
    pg.click("[data-add-hierarchy]")
    wait(pg, "window.Drishti.editors.yamlText().includes('By product')")
    gcode(pg, "hierarchies", "By product", 1, "p.PRODUCT, p.NAME")
    wait(pg, "window.Drishti.editors.yamlText().includes('p.NAME')")
    pg.click("[data-remove-hierarchy] >> nth=1")
    pg.click("[data-tab=rules]")
    pg.click("[data-add-rule]")
    wait(pg, "window.Drishti.editors.yamlText().includes('user.desks')")
    rule = pg.evaluate("document.querySelector(\"[data-rowgrid='rules']\").grid.model.idAt(0)")
    gcode(pg, "rules", rule, 2, "t.PRODUCT in user.desks")
    wait(pg, "window.Drishti.editors.yamlText().includes('t.PRODUCT in user.desks')")
    pg.click("[data-remove-rule]")
    wait(pg, "!window.Drishti.editors.yamlText().includes('user.desks')")
    pg.click("[data-tab=masks]")
    pg.check("[data-mask='t.MTM']")
    wait(pg, "window.Drishti.editors.yamlText().includes('masked')")
    pg.uncheck("[data-mask='t.MTM']")
    pg.click("[data-tab=access]")
    wait(pg, "document.querySelectorAll('[data-access-role]').length > 0")
    settle(pg)
    assert pg.locator("[data-measures-preview] tbody tr").count() >= 2


def test_new_model_four_questions(pg):
    open_editors(pg)
    pg.click("[data-act=new]")
    pg.click("[data-new-kind=model]")
    pg.fill("[data-new-name]", "my-brief")
    pg.fill("[data-new-title]", "My brief")
    pg.fill("[data-new-about]", "MTM by product")
    pg.select_option("[data-new-fact]", "trading.trade_summary")
    pg.click("[data-step-next]")
    pg.check("[data-new-dim='trading.trade_products']")
    pg.click("[data-step-next]")
    pg.check("[data-new-measure='MTM']")
    pg.click("[data-step-next]")
    pg.click("[data-step-next]")
    wait(pg, "!!document.querySelector('[data-editor=model]')")
    settle(pg)
    assert "sum(trade_summary.MTM)" in yaml(pg)


# ---- the Python-driven table editor ------------------------------------------------------------------------------------------------


def test_python_table_template_notebook_contract_and_the_sample_run(pg):
    open_editors(pg)
    pg.click("[data-act=new]")
    pg.click("[data-new-kind=python]")
    pg.fill("[data-new-name]", "mine.headroom")
    pg.click("[data-step-next]")
    pg.check("[data-new-start=template]")
    pg.click("[data-step-next]")
    wait(pg, "!!document.querySelector('[data-dialog][open] [data-template]')")
    pg.click("[data-template='group-stats']")
    wait(pg, "document.querySelector('[data-python-code]').value.includes('def build')")
    assert pg.locator("[data-contract-col]").count() == 5
    # the sample run is on: the server runs authored code on its compute pool
    assert not pg.is_disabled("[data-py-run]") and pg.locator("[data-uc='python-table-runs']").count() == 0
    pg.click("[data-py-templates]")
    wait(pg, "!!document.querySelector('[data-dialog][open] [data-templates]')")
    pg.click("[data-dialog-close]")
    pg.click("[data-py-notebook]")
    wait(pg, "!!document.querySelector('[data-dialog][open] [data-cells]')")
    pg.click("[data-cell-add]")
    assert pg.locator("[data-cell]").count() == 3
    assert not pg.is_disabled("[data-cell-run]")
    pg.click("[data-turn-into-table]")
    wait(pg, "document.querySelector('[data-python-code]').value.includes('inputs[\"trade_legs\"]')")
    wait(pg, "!!document.querySelector(\"[data-input='trade_legs']\")")
    pg.click("[data-input-remove='trade_legs']")
    wait(pg, "!document.querySelector(\"[data-input='trade_legs']\")")
    pg.click("[data-input-remove='pnl']")                          # the template's own input (risk.daily_pnl) is not in this catalogue: the run reads every input
    wait(pg, "!document.querySelector(\"[data-input='pnl']\")")
    pg.select_option("[data-input-pick]", "trading.trade_summary")
    pg.click("[data-input-add]")
    wait(pg, "!!document.querySelector(\"[data-input='trade_summary']\")")
    pg.click("[data-py-propose-contract]")
    wait(pg, "!!document.querySelector(\"[data-contract-col='ABS_NOTIONAL']\")")
    settle(pg)
    pg.fill("[data-contract-new]", "EXTRA")
    pg.click("[data-contract-add]")
    wait(pg, "window.Drishti.editors.yamlText().includes('EXTRA')")
    pg.select_option("[data-contract-type='EXTRA']", "integer")
    pg.check("[data-contract-key='EXTRA']")
    pg.check("[data-contract-notnull='EXTRA']")
    wait(pg, "window.Drishti.editors.yamlText().includes('not-null')")
    pg.click("[data-contract-remove='EXTRA']")
    pg.select_option("[data-py-runs]", "on-load")
    pg.fill("[data-py-pool]", "default")
    pg.fill("[data-py-timeout]", "120")
    pg.fill("[data-py-memory]", "1024")
    wait(pg, "window.Drishti.editors.yamlText().includes('on-load')")
    settle(pg)
    assert "Press Sample run" in pg.inner_text("[data-sample-run]")
    # the sample run: the input read as this person, the code run on the pool, the rows checked against the contract (which names columns it does not return)
    pg.fill("[data-python-code]", "def build(inputs, params):\n    t = inputs['trade_summary']\n    return t.groupby('PRODUCT', as_index=False)['MTM'].sum()\n")
    pg.click("[data-py-run]")
    wait(pg, "!!document.querySelector('[data-sample-run=ran]')", 180)
    assert pg.locator("[data-sample-rows] th").all_inner_texts() == ["PRODUCT", "MTM"]
    assert "is not in what the code returned" in pg.inner_text("[data-preview]")
    pg.click("[data-act=save]")
    wait(pg, "!!document.querySelector('[data-dialog][open] [data-save-go]')")
    pg.click("[data-save-go]")
    wait(pg, "/Saved as a draft/.test(document.querySelector('[data-msg]').textContent)")
    assert pg.locator("[data-group='python'] [data-object='mine.headroom']").count() == 1


# ---- themes, phones, keyboard -------------------------------------------------------------------------------------------------------


def test_light_theme_phone_layout_and_keyboard(pg):
    pg.goto(pg.base + "/build/tables?kind=table&name=trading.trade_summary")
    pg.evaluate("document.documentElement.setAttribute('data-theme', 'light')")
    wait(pg, "document.querySelector('[data-title]').textContent === 'trading.trade_summary'")
    settle(pg)
    bg = pg.evaluate("getComputedStyle(document.querySelector('.dd-head')).backgroundColor")
    ink = pg.evaluate("getComputedStyle(document.querySelector('.dd-head')).color")
    assert bg != ink
    # keyboard: Ctrl+Enter validates; + New opens its menu, Escape closes it
    pg.focus("[data-act=new]")
    pg.keyboard.press("Enter")
    assert pg.is_visible("[data-newmenu]")
    pg.keyboard.press("Escape")
    assert pg.is_hidden("[data-newmenu]")
    pg.keyboard.press("Control+Enter")
    wait(pg, "/Valid|problem/.test(document.querySelector('[data-msg]').textContent)")
    # a phone: one column, no sideways scroll
    pg.set_viewport_size({"width": 390, "height": 844})
    settle(pg)
    assert pg.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
    cols = pg.evaluate("getComputedStyle(document.querySelector('[data-grid]')).gridTemplateColumns.split(' ').length")
    assert cols == 1
