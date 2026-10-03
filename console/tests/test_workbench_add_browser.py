"""Adding a panel must be obvious (product owner): a header button on every tab, a chooser of all 20 kinds, the palette's Add from any tab,
Studio addresses opening with the canvas in sight, a grip to drag by, and a way in for an empty design. Fixtures: test_workbench_browser.py's."""
import json

from test_workbench_browser import browser, drag, open_design, page, settle, state, wait  # noqa: F401 - fixtures and helpers
from wb_live import live_console, new_design, post, showcase_json, showcase_sutra  # noqa: F401 - the fixture

SAMPLE = json.dumps({"tradeId": "T-1", "mtm": 5, "pnlHistory": [{"date": "2026-01-01", "pnl": 1}, {"date": "2026-01-02", "pnl": 3}]})
SUTRA = "rachana: 1\nsutra: add\nversion: 1\nmatch: { kind: trade }\ntitle: { id: $.tradeId }\n# first, the facts\npanels:\n  - { id: facts, kind: kv, title: Facts, columns: [ { label: MTM, bind: $.mtm } ] }\n  - { id: note, kind: markdown, title: Note, text: hello }\n"
EMPTY = "rachana: 1\nsutra: empty\nversion: 1\nmatch: { kind: trade }\ntitle: { id: $.tradeId }\npanels: []\n"


def settled_selection(page, kind):
    wait(page, "!!document.querySelector('[data-preview] .wb-sel[data-panel]')")
    return page.evaluate("document.querySelector('[data-preview] .wb-sel[data-panel]').dataset.kind") == kind


def test_a_studio_address_opens_with_the_canvas_and_yaml_and_the_header_button_adds_a_draggable_panel(live_console, page):
    page.goto(live_console + "/studio?example=pnl-explain")
    wait(page, "window.drishtiWorkbench && window.drishtiWorkbench.store.state.id")
    page.locator("[data-preview] [data-panel]").first.wait_for()
    assert "tab=split" in page.url
    assert page.locator("[data-preview]").is_visible() and page.locator(".CodeMirror").is_visible()          # the canvas is in sight
    before = state(page, "yaml")
    comments = [ln for ln in before.splitlines() if ln.lstrip().startswith("#")]
    rev = state(page, "rev")
    page.get_by_role("button", name="Add panel", exact=True).click()
    page.keyboard.type("line")
    page.locator("[role=option]").first.click()
    settle(page, rev)
    assert settled_selection(page, "line")
    new = page.evaluate("document.querySelector('[data-preview] .wb-sel[data-panel]').dataset.panel")
    grip = page.locator(f'[data-preview] [data-panel="{new}"] .wb-grip')
    assert grip.count() == 1 and "Drag to move" in page.locator(f'[data-preview] [data-panel="{new}"] .pnl-h').get_attribute("title")
    ids0 = page.evaluate("window.drishtiWorkbench.canvas.ids()")
    first_id = ids0[ids0.index(new) - 1]                                   # the panel just above it (both are in view)
    first = page.locator(f'[data-preview] [data-panel="{first_id}"]')
    first.scroll_into_view_if_needed()
    nb = page.locator(f'[data-preview] [data-panel="{new}"] .pnl-h')
    nb.scroll_into_view_if_needed()
    fb = first.bounding_box()
    rev = state(page, "rev")
    nb = page.locator(f'[data-preview] [data-panel="{new}"] .pnl-h').bounding_box()
    page.mouse.move(nb["x"] + 12, nb["y"] + 8)
    page.mouse.down()
    page.mouse.move(nb["x"] + 20, nb["y"] - 20, steps=4)
    page.mouse.move(fb["x"] + 20, fb["y"] + 30, steps=8)
    page.mouse.up()
    settle(page, rev)
    ids = page.evaluate("window.drishtiWorkbench.canvas.ids()")
    assert ids.index(new) < ids.index(first_id)
    assert [ln for ln in state(page, "yaml").splitlines() if ln.lstrip().startswith("#")] == comments        # the comments stay


def test_the_header_button_and_the_palette_add_from_the_yaml_tab(live_console, page):
    open_design(page, live_console, "from yaml", sutra=SUTRA, files={"t.json": SAMPLE})
    page.get_by_role("tab", name="YAML").click()
    assert page.get_by_role("button", name="Add panel", exact=True).is_visible()
    rev = state(page, "rev")
    page.get_by_role("button", name="Add a gauge panel").click()                  # the palette's Add, on the YAML tab
    settle(page, rev)
    assert page.get_by_role("tab", name="Design").get_attribute("aria-selected") == "true"
    assert settled_selection(page, "gauge")
    assert page.locator("[data-preview] .wb-sel").is_visible()
    page.get_by_role("tab", name="YAML").click()
    rev = state(page, "rev")
    page.get_by_role("button", name="Add panel", exact=True).click()
    page.keyboard.type("timeline")
    page.keyboard.press("Enter")                                                    # the keyboard path
    settle(page, rev)
    assert page.get_by_role("tab", name="Design").get_attribute("aria-selected") == "true"
    assert settled_selection(page, "timeline")


def test_the_palette_is_a_filterable_grid_of_all_twenty_kinds(live_console, page):
    open_design(page, live_console, "palette", sutra=SUTRA, files={"t.json": SAMPLE})
    assert page.locator("#wbPalette").inner_text() == "Add a panel"
    assert page.locator("[data-palette] .wb-kind").count() == 20
    page.get_by_label("Filter the panel kinds").fill("candle")
    assert page.locator("[data-palette] .wb-kind:not([hidden])").count() == 1


def test_an_empty_design_offers_to_add_the_first_panel(live_console, page):
    made = post(page, live_console + "/build/designs", {"name": "blank", "kind": "trade"})
    post(page, f"{live_console}/build/designs/{made['id']}/files", {"files": [{"name": "t.json", "text": SAMPLE}]})
    page.goto(f"{live_console}/build/d/{made['id']}")
    wait(page, "window.drishtiWorkbench && window.drishtiWorkbench.store.state.id")
    button = page.locator("[data-first-panel]")
    button.wait_for()
    rev = state(page, "rev")
    button.click()
    page.keyboard.type("kv")
    page.keyboard.press("Enter")
    settle(page, rev)
    assert settled_selection(page, "kv")
    assert page.locator("[data-first-panel]").count() == 0
    assert "refs" not in page.evaluate("window.drishtiWorkbench.canvas.ids()")
