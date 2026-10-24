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
"""BI wave B in Chromium, over the real server and console (tests/bi_live_stack.py: security on, the demo ticker, the market-risk pack's 765 valuations; ports 18995 and 17995 unless
DRISHTI_BI_LIVE_SERVER_PORT and DRISHTI_BI_LIVE_CONSOLE_PORT move them): a board page in the report designer (2.4.0: a tile from a report, its alert with a
hysteresis and notify,
the YAML it writes, Save and the draft reopened), its finger-sized controls on a phone, the history line of the live desk, the temp folders on
Admin Health, and an import rolled back from Environments.

Skipped when Playwright, Chromium or the built server jar is missing, or a scratch port is in use. Never run with pytest -n (the ports are fixed)."""
import json

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect  # noqa: E402

import bi_live_stack as S  # noqa: E402
from bi_live_stack import CONSOLE_URL, PASSWORD, REPORT  # noqa: E402
from test_bi_live_browser import browser, clean, sign_in, stack  # noqa: E402,F401  (the module's stack and browser fixtures)

BOARD_REPORT = "file:report:live-trading-desk"


EMPTY_BOARD = "rachana: 1\nboard: {name}\ntitle: {title}\npages:\n  - id: page1\n    title: Page 1\n    tiles: []\n"


def fetch(page, method, path, body=None):
    return page.evaluate("""([m, p, b]) => fetch(p, {method: m, credentials: 'same-origin', headers: {'Content-Type': 'application/json', Accept: 'application/json'},
                              body: b === null ? undefined : JSON.stringify(b)}).then(r => r.json().then(j => ({status: r.status, body: j})))""", [method, path, body])


def design_board(page, title="Wave B wall"):
    """2.4.0: a board is a report with board pages, designed in the report designer; an item made as a board is stored as one."""
    made = fetch(page, "POST", "/bi/api/workspaces/me/items", {"kind": "board", "title": title, "description": "",
                                                               "body": EMPTY_BOARD.format(name=title.lower().replace(" ", "-"), title=title)})
    assert made["status"] in (200, 201), made
    page.goto(f"{CONSOLE_URL}/bi/design/{made['body']['id']}")
    page.wait_for_selector("[data-design-root][data-ready=yes]", timeout=30000)
    return made["body"]["id"]


def add_tile(page, visual="desk/mtm"):
    page.click("[data-design-root] [data-title]")
    page.keyboard.press("Escape")
    page.click('.bi-vtype[data-type="tile"]')
    page.wait_for_function("() => { const v = window.drishtiBi.design.sel(); return v && v.type === 'tile'; }")
    tile = page.evaluate("window.drishtiBi.design.sel().id")
    page.click("[data-ptab='format']")
    page.wait_for_selector(f'[data-tile-report] option[value="{BOARD_REPORT}"]', state="attached", timeout=15000)
    page.select_option("[data-tile-report]", BOARD_REPORT)
    page.wait_for_function("() => document.querySelectorAll('[data-tile-visual] option').length > 1")
    page.select_option("[data-tile-visual]", visual)
    return tile


def yaml_of(page) -> str:
    page.click('[data-tab="yaml"]')
    # the server writes the tab's text a moment after the last change: read it once it is the draft's (data-yaml-current), not before
    page.wait_for_selector('[data-view="yaml"]:not([data-yaml-current="no"])', state="attached", timeout=20000)
    return page.evaluate("document.querySelector('.CodeMirror') ? document.querySelector('.CodeMirror').CodeMirror.getValue() : document.querySelector('[data-yaml]').value")


def set_field(page, selector, value):
    page.fill(selector, value)
    page.locator(selector).press("Tab")


def test_the_board_designer_builds_a_board_with_an_alert_and_a_hysteresis(stack, browser):
    page = sign_in(browser, "wes")
    item = design_board(page)
    tile = add_tile(page)
    expect(page.locator(f'[data-design-root] [data-visual="{tile}"] .bi-card-value')).to_have_count(1, timeout=20000)        # drawn with the report's numbers, as the author
    # the tile's alert: thresholds, the hysteresis, tell the readers
    set_field(page, "[data-tile-alert-on]", "MTM")
    page.select_option("[data-tile-warn-op]", "lt")
    set_field(page, "[data-tile-warn-value]", "150000000")
    page.select_option("[data-tile-crit-op]", "lt")
    set_field(page, "[data-tile-crit-value]", "100000000")
    set_field(page, "[data-tile-hysteresis]", "5000000")
    page.check("[data-tile-notify]")
    yaml = yaml_of(page)                                                                       # the designer wrote the YAML; nobody typed it
    for line in ("layout: board", "type: tile", "report: live-trading-desk", "visual: mtm", "hysteresis: 5000000", "notify: true", "on: MTM"):
        assert line in yaml, (line, yaml)
    page.click('[data-tab="canvas"]')
    page.click("[data-save]")
    page.wait_for_function("() => /^Saved/.test((document.querySelector('[data-save] + .bi-near') || {}).textContent || '')", timeout=20000)
    body = fetch(page, "GET", f"/bi/api/items/{item}")["body"]
    assert body["kind"] == "report" and "hysteresis: 5000000" in body["body"] and "from: { report: " in body["body"], body["body"]
    page.reload()                                                                              # the saved draft opens as it was left
    page.wait_for_selector("[data-design-root][data-ready=yes]", timeout=30000)
    expect(page.locator(f'[data-design-root] [data-visual="{tile}"]')).to_have_count(1)
    clean(page)


def test_the_designer_controls_are_finger_sized_on_a_phone(stack, browser):
    page = sign_in(browser, "wes", has_touch=True)                                           # a touch screen (pointer: coarse), the tile added at desk width
    design_board(page, "Phone wall")
    add_tile(page)
    page.set_viewport_size({"width": 390, "height": 844})
    page.wait_for_timeout(500)
    box = page.locator("[data-save]").bounding_box()
    assert box and box["height"] >= 43.5 and box["width"] >= 43.5, box
    small = page.evaluate("""() => [...document.querySelectorAll('[data-design-root] button, [data-design-root] select, [data-design-root] [role=tab],'
        + ' [data-design-root] input:not([type=checkbox]):not([type=radio]):not([type=hidden])')].filter(e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0
        && getComputedStyle(e).visibility !== 'hidden' && (r.height < 43.5 || r.width < 43.5); }).map(e => e.outerHTML.slice(0, 90) + ' ' + Math.round(e.getBoundingClientRect().width) + 'x' + Math.round(e.getBoundingClientRect().height))""")
    assert small == [], small                                                                  # every control the author can reach on the phone
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
    clean(page)


def test_the_live_desk_draws_the_history_line_from_the_server(stack, browser):
    page = sign_in(browser)
    page.goto(f"{CONSOLE_URL}/bi/r/{REPORT}")
    page.wait_for_selector("[data-ready=yes]", state="attached", timeout=30000)
    page.wait_for_selector('[data-visual="last-hour"] .bi-chart', timeout=30000)
    page.wait_for_function("""() => { const c = echarts.getInstanceByDom(document.querySelector('[data-visual="last-hour"] .bi-chart'));
        return c && c.getOption().series.length >= 2 && c.getOption().series[0].data.length >= 2; }""", timeout=60000)
    assert page.inner_text('[data-visual="last-hour"] .bi-vis-title') == "Net MTM and losses, last hour"
    s, r = S.api("POST", f"/bi/reports/{REPORT}/query", "rita", ["live-risk"], {"page": "desk", "visual": "last-hour", "state": {}})    # the 2.1 live dataset, as its report asks it
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
