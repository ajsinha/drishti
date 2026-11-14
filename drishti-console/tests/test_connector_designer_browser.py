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
"""Build -> New connector, the connector and binding designer, in a browser against a REAL scratch server (the thin kernel jar on a free port, sign-in on):
a folder of JSON Lines files generated under the worktree's data/ is the source. One administrator picks the file connector, sets its folder, tests it (what it
sees), previews real rows (types detected), binds a kind, reviews the two diffs and proposes; the server refuses the proposer's own approval (four eyes); a
second administrator approves through the bindings service, and the kind is served by the new connector, with no restart. Also the layout at desktop and phone
width, the old New connector button's way here, and the secret rule (a typed value is refused). Skipped when Playwright, Chromium, the built jar or a JDK is
missing (wb_live.py)."""
import json
import os
import pathlib
import shutil

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture
import wb_live  # noqa: E402
from wb_live import BROWSER_WAIT_MS, TOKEN_SECRET, _stack  # noqa: E402

pytestmark = pytest.mark.spread
ROOT = CONSOLE.parent
LONG = max(BROWSER_WAIT_MS, 120_000)
KIND = "cdui-note"
SECOND, SECOND_PASSWORD = "cd-second-admin", "cd-second-pass-2026"


_FULL_JAR = wb_live.jar


def _server_jar():
    """The server jar: the full one when built (kernel/drishti-server/target), else the thin kernel's (packaging/drishti-kernel-app/target): a connector and its bindings need no pack."""
    found = _FULL_JAR()
    if found is not None:
        return found
    thin = sorted((ROOT / "packaging" / "drishti-kernel-app" / "target").glob("drishti-kernel-*-exec.jar"), key=lambda p: p.stat().st_mtime)
    return thin[-1] if thin else None


@pytest.fixture(scope="module")
def designer(tmp_path_factory, monkeypatch_module):
    monkeypatch_module.setattr(wb_live, "jar", _server_jar)
    work = tmp_path_factory.mktemp("cdui-server")
    yield from _stack(tmp_path_factory, {"DRISHTI_SECURITY_ENABLED": "true", "DRISHTI_TOKEN_SECRET": TOKEN_SECRET}, work=work, auth=True)


@pytest.fixture(scope="module")
def monkeypatch_module():
    mp = pytest.MonkeyPatch()
    yield mp
    mp.undo()


def sign_in(page, base, user="drishti-dev-admin", password="drishti-dev-admin123"):
    page.context.clear_cookies()
    page.goto(base + "/login")
    page.fill("input[name=user]", user)
    page.fill("input[name=password]", password)
    page.click("form button[type=submit], form input[type=submit]")
    page.wait_for_url(lambda u: "/login" not in u)


def make_files(folder: pathlib.Path) -> pathlib.Path:
    """A generated source: <root>/cdui/2026-07-08/cdui-note.jsonl, six synthetic notes (an id, a book, an amount, a date)."""
    day = folder / "cdui" / "2026-07-08"
    day.mkdir(parents=True, exist_ok=True)
    with open(day / f"{KIND}.jsonl", "w", encoding="utf-8") as w:
        for i in range(1, 7):
            w.write(json.dumps({"id": f"N{i:03d}", "BOOK": "RATES-NY" if i % 2 else "FX-TOR", "AMOUNT": 1000.5 * i, "COUNT": i, "VALUE_DATE": "2026-07-08", "LIVE": i > 3}) + "\n")
    return folder


def test_a_connector_made_tested_previewed_bound_and_approved_by_a_second_administrator(browser, designer):
    base = str(designer)
    folder = make_files(ROOT / "data" / "cdui-test" / pathlib.Path(str(designer.work)).name)
    try:
        ctx = browser.new_context(viewport={"width": 1500, "height": 1000})
        page = ctx.new_page()
        page.set_default_timeout(30_000)
        sign_in(page, base)
        made = page.request.post(base + "/admin/api/users", data=json.dumps({"username": SECOND, "displayName": "Second Admin", "roles": ["admin"], "password": SECOND_PASSWORD}),
                                 headers={"Content-Type": "application/json"})
        assert made.status in (200, 201), made.text()[:300]
        page.goto(base + "/build/connector/designer")
        assert page.locator("[data-conn-designer] .pd-step").count() == 6

        # 1 Connector type: the types installed, from the server
        page.locator("[data-types-grid] [role=row]:has-text('file')").first.wait_for(timeout=LONG)
        page.locator("[data-types-grid] [role=row]", has_text="file").first.click()
        page.wait_for_function("() => document.querySelector('[data-sum=type]').innerText.trim() === 'file'")
        page.click("[data-next]")

        # 2 Settings: a form generated from the type; a secret is only ever a reference
        page.fill("[data-f=name]", "cdui-files")
        page.fill("[data-f=kinds]", KIND)
        page.locator("input[data-setting=root], [data-setting=root] input").first.fill(str(folder))
        page.locator("input[data-setting=domain], [data-setting=domain] input").first.fill("cdui")
        page.click("[data-next]")
        page.locator("[data-pane=test]:not([hidden])").wait_for()

        # 3 Test connection: connected, and what it sees
        page.click("[data-test]")
        page.wait_for_function("() => document.querySelector('[data-test-said]').innerText.includes('Connected')", timeout=LONG)
        page.locator("[data-sees-grid] [role=row]:has-text('2026-07-08')").first.wait_for(timeout=LONG)
        assert "6" in page.locator("[data-sees-grid]").inner_text()
        page.click("[data-next]")

        # 4 Preview data: real rows, types detected
        page.click("[data-preview]")
        page.wait_for_function("() => document.querySelector('[data-preview-said]').innerText.includes('real row')", timeout=LONG)
        fields = page.locator("[data-fields-grid]").inner_text()
        assert "AMOUNT" in fields and "decimal" in fields and "integer" in fields and "date" in fields and "boolean" in fields
        assert "N001" in page.locator("[data-rows-grid]").inner_text()
        page.click("[data-next]")

        # 5 Bind to kinds: now -> after
        page.locator("[data-binds-grid] [role=row]:has-text('%s')" % KIND).first.wait_for()
        assert "unbound" in page.locator("[data-binds-grid]").inner_text()
        assert "cdui-files" in page.locator("[data-binds-grid]").inner_text()
        page.click("[data-next]")

        # 6 Review and apply: the two diffs, then the proposal
        page.locator("[data-diff-file] .di-diff").wait_for(timeout=LONG)
        assert "plugin: file" in page.locator("[data-diff-file]").inner_text() or "file" in page.locator("[data-diff-file]").inner_text()
        assert "cdui-files" in page.locator("[data-diff-bindings]").inner_text() and KIND in page.locator("[data-diff-bindings]").inner_text()
        page.click("[data-apply]")
        assert "Say why" in (page.locator("[data-msg]").inner_text() or "")
        page.fill("[data-f=reason]", "Bind the generated notes folder")
        page.click("[data-apply]")
        page.wait_for_function("() => document.querySelector('[data-eye-a]').innerText.includes('done')", timeout=LONG)
        assert "done" in page.locator("[data-eye-a]").inner_text()
        saved = page.request.get(base + "/admin/connectors/api/cdui-files")
        assert saved.status == 200 and saved.json()["plugin"] == "file"       # the connector file is saved through Admin -> Connectors' API

        # four eyes: the proposer cannot approve his own change
        ctxinfo = page.request.get(base + "/build/connector/api/context").json()
        mine = [p for p in ctxinfo["bindings"]["proposals"] if p["kind"] == KIND]
        assert len(mine) == 1 and mine[0]["proposedBy"] == "drishti-dev-admin"
        own = page.request.post(base + "/admin/api/bindings/approve", data=json.dumps({"id": mine[0]["id"]}), headers={"Content-Type": "application/json"})
        assert own.status >= 400 and "four eyes" in own.text()

        # the second administrator approves it (the bindings service: audited, applied without a restart)
        sign_in(page, base, SECOND, SECOND_PASSWORD)
        done = page.request.post(base + "/admin/api/bindings/approve", data=json.dumps({"id": mine[0]["id"], "comment": "checked the diff"}), headers={"Content-Type": "application/json"})
        assert done.status == 200, done.text()
        after = page.request.get(base + "/build/connector/api/context").json()["bindings"]
        kind = [k for k in after["kinds"] if k["kind"] == KIND][0]
        assert kind["read"] == ["cdui-files"] and kind["state"] == "BOUND", kind          # the kind is served by the new connector
        assert not [p for p in after["proposals"] if p["kind"] == KIND]
        ctx.close()
    finally:
        shutil.rmtree(ROOT / "data" / "cdui-test", ignore_errors=True)


def test_a_typed_secret_is_refused_and_the_old_new_connector_button_comes_here(browser, designer):
    base = str(designer)
    ctx = browser.new_context(viewport={"width": 1500, "height": 900})
    page = ctx.new_page()
    page.set_default_timeout(30_000)
    sign_in(page, base)
    page.goto(base + "/admin/connectors")
    assert page.locator("a[data-new]").get_attribute("href") == "/build/connector/designer"      # one way to make a connector
    page.goto(base + "/admin/connectors?new=somewhere")
    page.wait_for_url("**/build/connector/designer?new=somewhere")
    page.locator("[data-types-grid] [role=row]", has_text="jdbc").first.wait_for(timeout=LONG)
    page.locator("[data-types-grid] [role=row]", has_text="jdbc").first.click()
    page.click("[data-next]")
    page.fill("[data-f=name]", "cdui-db")
    page.fill("[data-f=kinds]", KIND)
    page.locator("input[data-secret], [data-secret] input").first.fill("hunter2")
    page.click("[data-next]")
    page.wait_for_function("() => document.querySelector('[data-settings-msg]').innerText.includes('reference')")
    assert page.locator("[data-pane=settings]:not([hidden])").count() == 1                         # it does not go on with a typed credential
    ctx.close()


def test_the_layout_is_the_pack_designers_at_desktop_and_phone_width(browser, designer):
    ctx = browser.new_context(viewport={"width": 1500, "height": 900})
    page = ctx.new_page()
    sign_in(page, str(designer))
    page.goto(str(designer) + "/build/connector/designer")
    page.locator(".pd-rail").wait_for()
    rail, main = page.locator(".pd-rail").bounding_box(), page.locator(".pd-main").bounding_box()
    assert rail["x"] < main["x"] and rail["width"] < main["width"]                      # the left rail beside the main area
    assert page.evaluate("() => document.documentElement.scrollWidth <= window.innerWidth")
    names = page.locator(".pd-step").all_inner_texts()
    assert [n.split("\n")[0].strip() for n in names] == ["1 Connector type", "2 Settings", "3 Test connection", "4 Preview data", "5 Bind to kinds", "6 Review and apply"] or len(names) == 6
    page.set_viewport_size({"width": 390, "height": 800})
    rail, main = page.locator(".pd-rail").bounding_box(), page.locator(".pd-main").bounding_box()
    assert rail["y"] + rail["height"] <= main["y"] + 1 and abs(rail["x"] - main["x"]) < 2   # stacked: the rail above the pane
    wide = page.evaluate("() => [...document.querySelectorAll('body *')].filter(e => e.getBoundingClientRect().right > window.innerWidth + 1 && !e.closest('.pd-rail ol')).slice(0, 5).map(e => e.tagName + '.' + e.className)")
    assert page.evaluate("() => document.documentElement.scrollWidth <= window.innerWidth + 1"), wide
    ctx.close()
