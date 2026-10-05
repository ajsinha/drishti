"""QA round 2 (UX), part two, in a real browser over a real server: notes (UX-06), phone width (UX-08), keyboard (UX-10), the palette
(UX-17), one live region (UX-18), a pending edit before F1 (UX-22). Fixtures are test_workbench_browser.py's."""
import json

from test_workbench_browser import browser, open_design, page, settle, state, wait  # noqa: F401 - fixtures and helpers
from wb_live import live_console, post, showcase_json, showcase_sutra  # noqa: F401 - the fixture

LICENCE = "<!--\n  Project Drishti · Any data. Any domain. One grammar.\n  Copyright (c) 2026 Ashutosh Sinha.\n  PROPRIETARY AND CONFIDENTIAL.\n-->\n"
SAMPLE = json.dumps({"shipmentId": "S-1", "carrier": "Maersk"})
SUTRA = "rachana: 1\nsutra: ux\nversion: 1\nmatch: { kind: shipment }\ntitle: { id: $.shipmentId }\npanels:\n  - { id: facts, kind: kv, title: Facts, columns: [ { label: Carrier, bind: $.carrier } ] }\n"


def test_the_notes_are_visible_editable_and_the_licence_comment_is_not_shown(live_console, page):
    id_ = open_design(page, live_console, "notes", sutra=SUTRA, files={"s.json": SAMPLE}, kind="shipment")
    page.request.patch(f"{live_console}/build/designs/{id_}", data=json.dumps({"notes": LICENCE + "# Shipments\n\nWhat to **look for**:\n- the carrier\n"}),
                       headers={"Content-Type": "application/json"})
    page.goto(f"{live_console}/build/d/{id_}?tab=notes")
    wait(page, "window.drishtiWorkbench && window.drishtiWorkbench.store.state.id")
    area = page.get_by_label("Notes (markdown)")
    assert "Copyright" not in area.input_value() and area.input_value().startswith("# Shipments")
    view = page.locator("[data-notes-view]")
    assert view.locator("h2").inner_text() == "Shipments" and view.locator("strong").inner_text() == "look for" and view.locator("li").count() == 1
    assert "Copyright" not in view.inner_text()
    area.fill("# Shipments\n\nNew words.")
    page.locator("[data-notes-view]").click()
    area.blur()
    page.wait_for_timeout(1500)
    saved = page.request.get(f"{live_console}/build/designs/{id_}").json()["notes"]
    assert saved == "# Shipments\n\nNew words." and "Copyright" not in saved


def test_an_example_copy_has_notes_without_the_licence_comment(live_console, page):
    page.goto(live_console + "/build/new#examples")
    page.locator('[data-example="tree-table"]').click()
    page.wait_for_url("**/build/d/*")
    notes = page.request.get(page.url.replace("/build/d/", "/build/designs/")).json()["notes"]
    assert notes.strip() and "PROPRIETARY" not in notes and not notes.lstrip().startswith("<!--")


def test_the_workbench_and_my_designs_do_not_scroll_sideways_on_a_phone(live_console, page):
    id_ = open_design(page, live_console, "phone", sutra=showcase_sutra(), files={"showcase.json": showcase_json()})
    page.set_viewport_size({"width": 390, "height": 800})
    audit = "() => { const d = document.documentElement; return [d.scrollWidth, d.clientWidth]; }"
    for tab in ("design", "yaml", "summary", "notes"):
        page.goto(f"{live_console}/build/d/{id_}?tab={tab}")
        wait(page, "window.drishtiWorkbench && window.drishtiWorkbench.store.state.id")
        page.wait_for_timeout(500)
        width, client = page.evaluate(audit)
        assert width <= client + 1, f"{tab}: {width} px in {client}"
    for url in ("/build", "/build/new", "/build/reviews"):
        page.goto(live_console + url)
        page.wait_for_timeout(300)
        width, client = page.evaluate(audit)
        assert width <= client + 1, f"{url}: {width} px in {client}"


def test_a_skip_link_and_a_chord_reach_the_canvas_and_escape_leaves_the_inspector(live_console, page):
    open_design(page, live_console, "keys", sutra=showcase_sutra(), files={"showcase.json": showcase_json()})
    on_panel = "document.activeElement && document.activeElement.matches && document.activeElement.matches('[data-preview] .pnl[data-panel]')"
    page.locator("[data-skip-canvas]").focus()
    page.keyboard.press("Enter")
    wait(page, on_panel)
    page.locator("[data-add-menu]").focus()
    page.keyboard.press("g")
    page.keyboard.press("c")
    wait(page, on_panel)
    page.keyboard.press("Enter")                                   # into the inspector
    wait(page, "document.activeElement.closest('[data-inspector]')")
    page.keyboard.press("Escape")
    wait(page, on_panel)
    page.keyboard.press("Control+k")
    page.keyboard.type("go to canvas")
    assert page.locator('[role=option]', has_text="Go to canvas").count() >= 1
    page.keyboard.press("Escape")


def test_the_palette_finds_a_sample_by_its_file_name_without_the_folder(live_console, page):
    id_ = open_design(page, live_console, "folder", sutra=SUTRA, files={"shipments/shp-2.json": SAMPLE, "shipments/shp-3.json": SAMPLE}, kind="shipment")
    page.keyboard.press("Control+k")
    page.keyboard.type("go to sample: shp-2")
    assert page.locator('[role=option]', has_text="shp-2").count() == 1


def test_one_live_region_speaks_each_message(live_console, page):
    open_design(page, live_console, "live", sutra=SUTRA, files={"s.json": SAMPLE}, kind="shipment")
    assert page.locator("[role=status][data-say], [aria-live][data-say]").count() == 0
    assert page.locator("[aria-live=polite][data-live]").count() == 1


def test_a_pending_inspector_edit_is_sent_before_f1_takes_the_page(live_console, page):
    id_ = open_design(page, live_console, "f1", sutra=SUTRA, files={"s.json": SAMPLE}, kind="shipment")
    page.locator('[data-preview] [data-panel="facts"] .pnl-h').click()
    title = page.get_by_label("title", exact=True)
    title.fill("Typed just before F1")
    page.keyboard.press("F1")
    page.wait_for_url("**/help/**")
    got = page.request.get(f"{live_console}/build/designs/{id_}").json()["sutra"]
    assert "Typed just before F1" in got
