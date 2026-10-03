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

"""Build workbench, step 7 in a real browser over a real server: an old Studio address lands on the workbench's YAML tab, the command
palette (Ctrl+K) works by keyboard alone and is a labelled combobox/listbox, Ctrl+S says why it cannot save without the author right,
Ctrl+Enter previews, and the Versions tab diffs against the base and an earlier version and restores one as an operation.

Skipped when Playwright, its Chromium, the server jar or JDK 25 is missing (see ``wb_live.py``)."""
import re

import pytest

from test_workbench_browser import browser, open_design, page, settle, state, wait  # noqa: F401 - fixtures and helpers
from wb_live import live_console, post, showcase_json, showcase_sutra  # noqa: F401 - the fixture

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")


def palette(page, text):
    """Opens the palette with Ctrl+K and types ``text``."""
    page.keyboard.press("Control+k")
    page.locator(".wb-menu input[role=combobox]").wait_for()
    page.keyboard.type(text)


def say(page):
    return page.locator("[data-say]").inner_text()


def test_an_old_studio_address_lands_on_the_split_view_of_a_new_design(live_console, page):
    page.goto(f"{live_console}/studio")
    page.locator("[data-start-go]").click()                  # opening the address only asks; the button starts the design
    page.locator("[data-workbench]").wait_for()
    assert re.search(r"/build/d/\w+\?tab=split", page.url)
    wait(page, "window.drishtiWorkbench && window.drishtiWorkbench.tabs.centre.current() === 'design' && document.querySelector('.CodeMirror').offsetParent !== null")
    assert "sutra: all-panels-showcase" in state(page, "yaml")
    assert page.locator("#wbPaneYaml").is_visible() and page.locator("#wbPaneDesign").is_visible()          # side by side: the canvas is in sight
    assert page.locator(".CodeMirror").is_visible()


def test_the_command_palette_works_by_keyboard_and_is_labelled(live_console, page):
    open_design(page, live_console, "palette", sutra=showcase_sutra(), files={"showcase.json": showcase_json()})
    page.locator("[data-preview] [data-panel]").first.focus()
    palette(page, "")
    box = page.locator(".wb-menu")
    assert box.get_attribute("role") == "dialog" and box.get_attribute("aria-label") == "Command palette"
    field = page.locator(".wb-menu input[role=combobox]")
    listbox = page.locator(".wb-menu [role=listbox]")
    assert field.get_attribute("aria-controls") == listbox.get_attribute("id") and field.get_attribute("aria-expanded") == "true"
    assert page.locator(".wb-menu [role=option]").count() > 30, (page.errors, page.locator(".wb-menu").inner_text()[:300])                  # add x20, go to panel x20, tabs, undo, save, ...
    page.keyboard.press("ArrowDown")
    active = field.get_attribute("aria-activedescendant")
    assert active and page.locator(f"#{active}").get_attribute("aria-selected") == "true"
    page.keyboard.press("Escape")
    assert page.locator(".wb-menu").count() == 0
    assert page.evaluate("document.activeElement.hasAttribute('data-panel') || !!document.activeElement.closest('[data-preview]')")   # focus is back

    # add a panel: filter, Enter
    before = len(page.evaluate("window.DrishtiWB.model(window.drishtiWorkbench.store.state.yaml).panels"))
    rev = state(page, "rev")
    palette(page, "add panel: markdown")
    page.keyboard.press("Enter")
    settle(page, rev)
    assert len(page.evaluate("window.DrishtiWB.model(window.drishtiWorkbench.store.state.yaml).panels")) == before + 1

    # undo and redo from the palette
    ops_at = state(page, "opsAt")
    palette(page, "undo")
    page.keyboard.press("Enter")
    wait(page, f"window.drishtiWorkbench.store.state.opsAt < {ops_at}")
    palette(page, "redo")
    page.keyboard.press("Enter")
    wait(page, f"window.drishtiWorkbench.store.state.opsAt === {ops_at}")

    # go to a panel, switch a tab, next and previous sample, run the check
    palette(page, "go to panel: terms")
    page.keyboard.press("Enter")
    wait(page, "window.drishtiWorkbench.canvas.selected() && window.drishtiWorkbench.canvas.selected().id === 'terms'")
    palette(page, "switch to versions")
    page.keyboard.press("Enter")
    assert page.locator("#wbTabVer").get_attribute("aria-selected") == "true"
    palette(page, "switch to yaml")
    page.keyboard.press("Enter")
    assert page.locator("#wbTabYaml").get_attribute("aria-selected") == "true"
    palette(page, "run check")
    page.keyboard.press("Enter")
    assert page.locator("#wbTabTests").get_attribute("aria-selected") == "true"
    wait(page, "document.querySelector('[data-result]').textContent.includes('/')")
    # Ctrl+K again closes it; a nonsense filter says so instead of leaving an empty box
    palette(page, "zzzzzz")
    assert "No command matches" in page.locator(".wb-menu").inner_text()
    page.keyboard.press("Control+k")
    assert page.locator(".wb-menu").count() == 0
    assert not page.errors


def test_palette_proposes_and_opens_the_guide(live_console, page):
    open_design(page, live_console, "palette 2", sutra=showcase_sutra(), files={"showcase.json": showcase_json()})
    palette(page, "save")
    assert "Needs the author right" in page.locator(".wb-menu").inner_text() or "Not allowed here" in page.locator(".wb-menu").inner_text()
    page.keyboard.press("Escape")
    palette(page, "guide")
    page.keyboard.press("Enter")
    page.wait_for_url(re.compile(r"/help/screen-designer"))


def test_ctrl_s_says_why_it_cannot_save_and_ctrl_enter_previews(live_console, page):
    open_design(page, live_console, "keys", sutra=showcase_sutra(), files={"showcase.json": showcase_json()})
    page.locator("[data-preview] [data-panel]").first.focus()
    seen = []
    page.on("dialog", lambda d: seen.append(d.message))
    page.keyboard.press("Control+s")                         # not the browser's save dialog
    wait(page, "document.querySelector('[data-say]').textContent.length > 0")
    text = say(page)
    assert "Designing is open to everyone" in text or "Saved" in text or "Submitted" in text, text
    page.keyboard.press("Control+Enter")
    wait(page, "document.querySelector('[data-say]').textContent.includes('Previewed')")
    # typing in the YAML tab and pressing Ctrl+Enter sends the text first
    page.locator("#wbTabYaml").click()
    page.locator(".CodeMirror").click()
    page.keyboard.press("Control+End")
    rev = state(page, "rev")
    page.keyboard.type("\n# a note from the keyboard\n")
    page.keyboard.press("Control+Enter")
    settle(page, rev)
    assert "a note from the keyboard" in state(page, "yaml")


def test_versions_tab_diffs_against_the_base_and_an_earlier_version_and_restores(live_console, page):
    page.goto(f"{live_console}/build/new")
    option = page.locator("select option", has_text="@").first
    sutra = option.get_attribute("value")
    assert sutra and "@" in sutra
    made = post(page, f"{live_console}/build/designs", {"name": "diffed", "base": sutra})
    assert made["http"] == 201
    post(page, f"{live_console}/build/designs/{made['id']}/files", {"files": [{"name": "s.json", "text": "{}"}]})
    page.goto(f"{live_console}/build/d/{made['id']}?tab=yaml")
    wait(page, "window.drishtiWorkbench && window.drishtiWorkbench.store.state.id")
    page.locator("#wbTabVer").click()
    wait(page, "document.querySelector('[data-versions] .wb-sum').textContent.includes('same as')")
    assert sutra in page.locator("#wbCompareWith option").first.inner_text()
    # two edits, then the diff against the base shows them and against version 1 only the second
    rev = state(page, "rev")
    page.evaluate("window.drishtiWorkbench.store.send([{op: 'text', yaml: window.drishtiWorkbench.store.state.yaml + '\\n# first edit\\n'}])")
    settle(page, rev)
    rev = state(page, "rev")
    page.evaluate("window.drishtiWorkbench.store.send([{op: 'text', yaml: window.drishtiWorkbench.store.state.yaml + '# second edit\\n'}])")
    settle(page, rev)
    wait(page, "document.querySelectorAll('#wbCompareWith option').length >= 3")
    wait(page, "document.querySelector('[data-versions] .wb-diff').textContent.includes('first edit')")
    diff = page.locator("[data-versions] .wb-diff")
    assert "second edit" in diff.inner_text() and diff.locator(".d-add").count() >= 2
    page.evaluate("(() => { const s = document.getElementById('wbCompareWith'); s.value = '1'; s.dispatchEvent(new Event('change')); })()")
    wait(page, "document.querySelector('[data-versions] .wb-diff').textContent.includes('second edit') && !document.querySelector('[data-versions] .wb-diff').textContent.includes('+# first edit')")
    # restore version 1 (as an operation: undo brings the second edit back)
    page.get_by_role("button", name=re.compile("Restore version 1")).click()
    wait(page, "!window.drishtiWorkbench.store.state.yaml.includes('second edit')")
    assert "first edit" in state(page, "yaml")
    page.keyboard.press("Control+z")
    wait(page, "window.drishtiWorkbench.store.state.yaml.includes('second edit')")
    assert not page.errors
