"""Removing a panel must be easy (product owner): a trash button in the heading, the inspector, the right-click menu and the command palette,
one undoable operation, a toast with Undo, and Delete still works. Fixtures: test_workbench_browser.py's."""
import json

from test_workbench_browser import browser, open_design, page, settle, state, wait  # noqa: F401 - fixtures and helpers
from wb_live import live_console  # noqa: F401 - the fixture

SAMPLE = json.dumps({"tradeId": "T-1", "mtm": 5})
SUTRA = """rachana: 1
sutra: rm
version: 1
match: { kind: trade }
title: { id: $.tradeId }
# the facts come first
panels:
  # facts
  - { id: facts, kind: kv, title: Facts, columns: [ { label: MTM, bind: $.mtm } ] }
  # a note
  - { id: note, kind: markdown, title: Note, text: hello }
  # the last word
  - { id: last, kind: markdown, title: Last, text: bye }
"""


def ids(page):
    return page.evaluate("window.drishtiWorkbench.canvas.ids()")


def comments(text):
    return [ln.strip() for ln in text.splitlines() if ln.lstrip().startswith("#")]


def check_removed_exactly(page, before, gone):
    after = state(page, "yaml")
    assert f"id: {gone}," not in after and f"id: {gone} " not in after
    keep = [i for i in ("facts", "note", "last") if i != gone]
    assert all(f"id: {i}," in after for i in keep)
    assert set(comments(before)) - set(comments(after)) <= {"# a note", "# facts", "# the last word"}      # only the removed panel's own comment may go
    assert "# the facts come first" in after


def test_the_header_trash_button_removes_one_panel_and_the_toast_undo_brings_it_back(live_console, page):
    open_design(page, live_console, "trash", sutra=SUTRA, files={"t.json": SAMPLE})
    before = state(page, "yaml")
    rev = state(page, "rev")
    panel = page.locator('[data-preview] [data-panel="note"]')
    panel.hover()
    page.get_by_role("button", name="Remove panel Note").click()
    settle(page, rev)
    assert ids(page) == ["facts", "last"]
    check_removed_exactly(page, before, "note")
    toast = page.locator("[data-toast]")
    assert "Removed 'Note'" in toast.inner_text()
    assert "Removed" in page.locator("[data-live]").text_content() or "Removed" in page.locator("[data-say]").text_content()
    rev = state(page, "rev")
    toast.get_by_role("button", name="Undo").click()
    wait(page, f"window.drishtiWorkbench.store.state.opsAt < window.drishtiWorkbench.store.state.opsCount")
    assert ids(page) == ["facts", "note", "last"]                                  # back in its place
    assert state(page, "yaml") == before


def test_the_inspector_removes_the_selected_panel(live_console, page):
    open_design(page, live_console, "inspector rm", sutra=SUTRA, files={"t.json": SAMPLE})
    before = state(page, "yaml")
    page.locator('[data-preview] [data-panel="last"] .pnl-h').click()
    rev = state(page, "rev")
    page.locator("[data-inspector]").get_by_role("button", name="Remove panel Last").click()
    settle(page, rev)
    assert ids(page) == ["facts", "note"]
    check_removed_exactly(page, before, "last")


def test_the_palette_and_the_context_menu_remove_the_selected_panel(live_console, page):
    open_design(page, live_console, "palette rm", sutra=SUTRA, files={"t.json": SAMPLE})
    before = state(page, "yaml")
    page.locator('[data-preview] [data-panel="facts"] .pnl-h').click()
    rev = state(page, "rev")
    page.keyboard.press("Control+k")
    page.keyboard.type("remove panel")
    page.keyboard.press("Enter")
    settle(page, rev)
    assert ids(page) == ["note", "last"]
    check_removed_exactly(page, before, "facts")
    rev = state(page, "rev")
    page.locator('[data-preview] [data-panel="note"] .pnl-h').click(button="right")
    page.get_by_role("option", name="Duplicate").click()
    settle(page, rev)
    assert len(ids(page)) == 3
    rev = state(page, "rev")
    page.locator('[data-preview] [data-panel="last"] .pnl-h').click(button="right")
    page.get_by_role("option", name="Remove panel").click()
    settle(page, rev)
    assert "last" not in ids(page)


def test_delete_still_removes_the_selected_panel(live_console, page):
    open_design(page, live_console, "delete", sutra=SUTRA, files={"t.json": SAMPLE})
    page.locator('[data-preview] [data-panel="note"]').focus()
    rev = state(page, "rev")
    page.keyboard.press("Delete")
    settle(page, rev)
    assert ids(page) == ["facts", "last"]
