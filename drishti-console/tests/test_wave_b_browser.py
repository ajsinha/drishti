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
"""BI wave B in Chromium, over the real server and console (tests/bi_live_stack.py: security on, the demo ticker, 750 trades; ports 18995 and 17995 unless
DRISHTI_BI_LIVE_SERVER_PORT and DRISHTI_BI_LIVE_CONSOLE_PORT move them): the board designer (a new board, a tile from a report, its alert with a hysteresis, a key to
move it, the YAML as the same draft, Save, and the board in the viewer), its finger-sized controls on a phone, the history line of the live desk, the temp folders on
Admin Health, and an import rolled back from Environments.

Skipped when Playwright, Chromium or the built server jar is missing, or a scratch port is in use. Never run with pytest -n (the ports are fixed)."""
import json

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

import bi_live_stack as S  # noqa: E402
from bi_live_stack import CONSOLE_URL, PASSWORD, REPORT  # noqa: E402
from test_bi_live_browser import browser, clean, sign_in, stack  # noqa: E402,F401  (the module's stack and browser fixtures)

BOARD_REPORT = "file:report:live-trading-desk"


def design_board(page, title="Wave B wall"):
    page.goto(CONSOLE_URL + "/bi/designer")
    page.wait_for_selector("[data-new-board]")
    page.fill("[data-new-board-title]", title)
    page.click("[data-new-board] button[type=submit]")
    page.wait_for_url("**/bi/design/board/**")
    page.wait_for_selector("[data-board-design-root][data-ready='1']")


def add_tile(page, visual="desk/mtm"):
    page.select_option("[data-add-report]", BOARD_REPORT)
    page.wait_for_function("() => document.querySelector('[data-add-visual]').options.length > 0")
    page.select_option("[data-add-visual]", value=visual)
    page.click("[data-add-btn]")
    page.wait_for_selector("[data-tile]")


def test_the_board_designer_builds_a_board_with_an_alert_and_a_hysteresis(stack, browser):
    page = sign_in(browser, "wes")
    design_board(page)
    assert page.inner_text("[data-title]") == "Wave B wall"
    add_tile(page)
    page.wait_for_function("() => document.querySelector('[data-tile]').innerText.includes('Live trading desk')", timeout=15000)       # the server resolves what the tile names
    # the tile's form: title, place, the alert with its hysteresis, tell the readers
    page.wait_for_selector("[data-tile-edit]")
    page.fill("[data-tile-edit] [name=title]", "Net MTM")
    page.select_option("[data-tile-edit] [name=warn_op]", "lt")
    page.fill("[data-tile-edit] [name=warn_value]", "150000000")
    page.select_option("[data-tile-edit] [name=crit_op]", "lt")
    page.fill("[data-tile-edit] [name=crit_value]", "100000000")
    page.fill("[data-tile-edit] [name=hysteresis]", "5000000")
    page.fill("[data-tile-edit] [name=on]", "MTM")
    page.check("[data-tile-edit] [name=notify]")
    page.click("[data-tile-edit] button[type=submit]")
    page.wait_for_function("() => document.querySelector('[data-tile] .bi-btile-alert') !== null")
    assert "hysteresis 5000000" in page.inner_text("[data-tile] .bi-btile-alert")
    # a key moves the focused tile one column; the YAML shows the same draft
    page.locator("[data-tile]").first.focus()
    page.keyboard.press("ArrowRight")
    page.click("[data-tab=yaml]")
    page.wait_for_function("() => /hysteresis: 5000000/.test(document.querySelector('[data-view=yaml]').innerText)")
    yaml = page.evaluate("document.querySelector('.CodeMirror') ? document.querySelector('.CodeMirror').CodeMirror.getValue() : document.querySelector('[data-yaml]').value")
    assert 'report: "file:report:live-trading-desk"' in yaml and "x: 1" in yaml and "notify:" in yaml and "title: Net MTM" in yaml
    # an edit in the YAML shows on the grid
    page.evaluate("(t) => { const cm = document.querySelector('.CodeMirror'); if (cm) { cm.CodeMirror.setValue(t); } else { const ta = document.querySelector('[data-yaml]'); ta.value = t; ta.dispatchEvent(new Event('input')); } }",
                  yaml.replace("title: Net MTM", "title: Renamed in YAML"))
    page.click("[data-tab=canvas]")
    page.wait_for_function("() => document.querySelector('[data-tile] .bi-btile-title').textContent === 'Renamed in YAML'", timeout=10000)
    # save, then the draft is what the board's page shows
    page.click("[data-save]")
    page.wait_for_function("() => /Saved as a draft/.test(document.querySelector('[data-msg]').textContent)")
    item = page.get_attribute("#biCanvas", "data-item")
    page.reload()
    page.wait_for_selector("[data-board-design-root][data-ready='1']")
    assert page.inner_text("[data-tile] .bi-btile-title") == "Renamed in YAML"
    page.click("[data-check]")
    page.wait_for_function("() => document.querySelector('[data-check-sum]').textContent.length > 0")
    assert "No problems" in page.inner_text("[data-check-sum]")
    assert page.get_attribute("[data-view-link]", "href").endswith("/bi/b/" + item.replace("%3A", ":"))
    assert page.get_attribute("[data-item-link]", "href").endswith("/bi/i/" + item.replace("%3A", ":"))
    clean(page)


def test_the_designer_controls_are_finger_sized_on_a_phone(stack, browser):
    page = sign_in(browser, "wes", viewport={"width": 390, "height": 844}, has_touch=True)
    design_board(page, "Phone wall")
    add_tile(page)
    for sel in ("[data-save]", "[data-add-btn]", "[data-undo]", "[data-check]", "[data-tab=yaml]", "[data-tile] [data-act=delete]", "[data-b-title]", "[data-p-delete]"):
        box = page.locator(sel).first.bounding_box()
        assert box["height"] >= 43.5 and box["width"] >= 43.5, (sel, box)
    clean(page)


def test_the_live_desk_draws_the_history_line_from_the_server(stack, browser):
    page = sign_in(browser)
    page.goto(f"{CONSOLE_URL}/bi/r/{REPORT}")
    page.wait_for_selector("[data-ready=yes]", state="attached", timeout=30000)
    page.wait_for_selector('[data-visual="last-hour"] .bi-chart', timeout=30000)
    page.wait_for_function("""() => { const c = echarts.getInstanceByDom(document.querySelector('[data-visual="last-hour"] .bi-chart'));
        return c && c.getOption().series.length >= 2 && c.getOption().series[0].data.length >= 2; }""", timeout=60000)
    assert page.inner_text('[data-visual="last-hour"] .bi-vis-title') == "Net MTM and losses, last hour"
    s, r = S.api("POST", "/bi/datasets/file:dataset:live-trading/query", "rita", ["live-risk"], {"fields": ["history.ts"], "measures": ["MTM history"]})
    assert s == 200 and r["rowCount"] >= 2
    clean(page)


def test_admin_health_shows_the_temp_folders_of_both_processes(stack, browser):
    S.admin("POST", "/admin/users", {"username": "ada", "displayName": "Ada", "email": "ada@desk.test", "roles": ["admin"], "password": PASSWORD, "mustChangePassword": False})
    page = sign_in(browser, "ada")
    page.goto(CONSOLE_URL + "/admin/health")
    page.wait_for_selector("[data-tmp-folders]")
    text = " ".join(page.inner_text("[data-tmp-folders]").split())
    assert "server " in text and "drishti-" in text and "console " in text and "drishti-console-" in text and "MB" in text and "files" in text, text
    clean(page)


def test_an_import_is_rolled_back_from_environments(stack, browser):
    s, ws = S.admin("POST", "/bi/workspaces", {"name": "Wave B Desk"})
    assert s in (200, 201), ws
    body = "report: wave-b-rep\ndataset: file:dataset:live-trading\npages:\n  - id: main\n    title: Main\n    visuals:\n      - { id: n, type: card, title: Trades, at: { x: 0, y: 0, w: 3, h: 2 }, wells: { values: [Trades] } }\n"
    s, rep = S.admin("POST", f"/bi/workspaces/{ws['id']}/items", {"kind": "report", "title": "Wave B report", "body": body})
    assert s in (200, 201), rep
    S.admin("POST", f"/bi/items/{rep['id']}/publish", {"note": "v1"})
    s, bundle = S.admin("GET", f"/bi/bundle?items={rep['id']}&dependencies=false")
    S.admin("PUT", f"/bi/items/{rep['id']}", {"body": body.replace("title: Trades", "title: Trade count"), "message": "retitle"})
    s, done = S.admin("POST", "/bi/bundle/import", {"bundle": bundle, "dryRun": False, "overwrite": True})
    assert s == 200 and done["applied"], done
    page = sign_in(browser, "ada")
    page.goto(CONSOLE_URL + "/bi/environments")
    page.wait_for_selector(f"[data-rollback='{done['importId']}']")
    page.click(f"[data-rollback='{done['importId']}']")
    page.wait_for_function("() => /Rolled back/.test(document.querySelector('[data-msg]').textContent)")
    page.wait_for_function("(id) => document.querySelector(`[data-import-id='${id}']`).textContent.includes('rolled back by')", arg=done["importId"])
    s, now = S.admin("GET", f"/bi/items/{rep['id']}")
    assert "Trade count" in now["body"], "the roll back restores the state before the import: the retitled draft"
    clean(page)
