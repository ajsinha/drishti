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
"""Build -> New pack, the Pack designer, in a browser against a REAL scratch server (the repackaged jar on a free port, sign-in on): a small
multi-file JSON Lines set (tests/pdui_data.py: three files, one gzipped, six PRODUCT_TYPE x SOURCE_SYSTEM combinations, one rare) is dropped, every
record is read on the server, the combinations are seen with their counts (the rare one marked), a Sutra's YAML is edited and its live screen
changes, the module is finalized (the fifteen checks) and deployed to the server, and the screen of a trade opens. Also the layout at desktop and
phone width, and the old URL's redirect. Skipped when Playwright, Chromium, the built server jar or a JDK is missing (wb_live.py)."""
import os
import pathlib

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture
from wb_live import BROWSER_WAIT_MS, TOKEN_SECRET, _stack  # noqa: E402

import pdui_data  # noqa: E402

pytestmark = pytest.mark.spread
ROOT = CONSOLE.parent
LONG = max(BROWSER_WAIT_MS, 240_000)                    # the read, the draft and the fifteen checks are real work


@pytest.fixture(scope="session")
def designer(tmp_path_factory):
    work = tmp_path_factory.mktemp("pdui-server")
    keep = {k: os.environ.get(k) for k in ("DRISHTI_DATA_DIR", "DRISHTI_PACK_DESIGNER_SITE", "DRISHTI_FILES_ROOT")}
    os.environ["DRISHTI_DATA_DIR"] = str(work / "data")                 # the jobs write the files store where the scratch server reads it
    os.environ["DRISHTI_FILES_ROOT"] = str(work / "data" / "files")
    os.environ["DRISHTI_PACK_DESIGNER_SITE"] = str(work / "config")     # the bindings the designer writes are the scratch server's
    try:
        yield from _stack(tmp_path_factory, {"DRISHTI_SECURITY_ENABLED": "true", "DRISHTI_TOKEN_SECRET": TOKEN_SECRET}, work=work, auth=True)
    finally:
        for k, v in keep.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v


def sign_in(page, base):
    page.goto(base + "/login")
    page.fill("input[name=user]", "drishti-dev-admin")
    page.fill("input[name=password]", "drishti-dev-admin123")
    page.click("form button[type=submit], form input[type=submit]")
    page.wait_for_url(lambda u: "/login" not in u)


def test_the_old_url_redirects_to_the_designer(browser, designer):
    ctx = browser.new_context()
    page = ctx.new_page()
    sign_in(page, str(designer))
    page.goto(str(designer) + "/build/pack/new")
    assert page.url.endswith("/build/pack/designer")
    assert page.locator("[data-pack-designer]").count() == 1 and page.locator(".pd-step").count() == 6
    ctx.close()


def test_files_in_combinations_a_live_screen_that_follows_the_yaml_a_module_deployed(browser, designer, tmp_path):
    base = str(designer)
    files = pdui_data.make(ROOT / "data" / "pdui-test" / tmp_path.name)     # generated under the worktree's data/, never committed
    try:
        ctx = browser.new_context(viewport={"width": 1500, "height": 950})
        page = ctx.new_page()
        page.set_default_timeout(30_000)
        sign_in(page, base)
        page.goto(base + "/build/pack/designer")
        page.evaluate("sessionStorage.clear()")
        page.goto(base + "/build/pack/designer")

        # 1 Data: three files dropped in one go; the server reads every record
        page.set_input_files("[data-pane=data] .di-file-drop input[type=file]:not([webkitdirectory])", [str(f) for f in files])
        page.locator("[data-total]:has-text('3 files')").wait_for(timeout=LONG)
        page.wait_for_function("() => document.querySelector('[data-sum=data]').innerText.includes('163 rows')", timeout=LONG)

        # 2 Kind and keys: the combinations with their whole-file counts, the rare one marked; the ID is checked over every file
        page.locator("[data-pane=kind]:not([hidden])").wait_for(timeout=LONG)
        assert "unique over all 163 rows" in page.locator("[data-key-check]").inner_text()
        page.fill("[data-f=kind]", "trade")
        page.fill("[data-f=kindPrefix]", "pdui-")
        page.click("[data-ms-match] .di-ms-control")                    # the discriminators: any combination of columns, chosen in the multi-select
        page.click(".di-ms-pop [data-act=clear]")
        for column in ("PRODUCT_TYPE", "SOURCE_SYSTEM"):
            page.locator(".di-ms-pop .di-option", has_text=column).first.click()
        page.keyboard.press("Escape")
        page.click("[data-reanalyse]")
        page.wait_for_function("() => document.querySelector('[data-combos-grid]').innerText.includes('SOURCE_SYSTEM=ENDUR') && document.querySelector('[data-combos-grid]').innerText.includes('pdui-trade-')", timeout=LONG)
        page.locator("[data-combos-grid]").wait_for()
        combos = page.locator("[data-combos-grid]").inner_text()
        assert "PRODUCT_TYPE=IRS, SOURCE_SYSTEM=ENDUR" in combos and "60" in combos and "rare" in combos and "CAPFLOOR" in combos

        # 3 Source: the file connector, written as a binding
        page.click("[data-go=source]")
        page.fill("[data-f=name]", "pdui-trades")

        # 4 Design: the Sutras with their counts, one's YAML beside its live screen, re-rendered as the YAML is edited
        page.click("[data-go=design]")
        page.locator("[data-sutras-grid] [role=row]:has-text('FXFWD')").first.wait_for()
        page.locator("[data-sutras-grid] [role=row]:has-text('IRS')").first.click()
        page.wait_for_function("() => document.querySelector('[data-sutra-name]').innerText.includes('irs')", timeout=LONG)
        page.locator("[data-live-screen] .studio-view").wait_for(timeout=LONG)
        before = page.locator("[data-live-screen]").inner_text()
        assert "T0" in before                                           # a real trade id is in the screen
        yaml = page.evaluate("() => window.Drishti.elements.inputs && document.querySelector('.CodeMirror').CodeMirror.getValue()")
        assert "sutra:" in yaml
        page.evaluate("""() => { const cm = document.querySelector('.CodeMirror').CodeMirror; cm.setValue(cm.getValue().replace('title: "Details"', 'title: "Edited by the designer"')); }""")
        page.wait_for_function("() => document.querySelector('[data-live-screen]').innerText.includes('Edited by the designer')", timeout=LONG)
        assert page.locator("[data-sutras-grid]").inner_text().count("edited") >= 1

        # 5 Finalize: the module and its fifteen checks
        page.click("[data-go=finalize]")
        page.click("[data-finalize]")
        page.locator("[data-download]:not([hidden])").wait_for(timeout=LONG * 2)
        assert "15 of 15 checks passed" in page.locator("[data-sum=finalize]").inner_text()

        # 6 Deploy: the one module deploy
        page.click("[data-go=deploy]")
        page.click("[data-install]")                                    # one node: the modules folder, auto-deploy installs it
        page.locator("[data-open]:not([hidden])").wait_for(timeout=LONG)
        assert "is installed" in page.locator("[data-deploy-msg]").inner_text()
        href = page.locator("[data-open]").get_attribute("href")
        import time
        deadline, r = time.monotonic() + 120, None
        while time.monotonic() < deadline:                              # auto-deploy checks and loads the module within seconds
            r = page.request.get(base + href)
            if r.status == 200:
                break
            time.sleep(2)
        assert r.status == 200, (href, r.status, r.text()[:300])
        page.goto(base + "/admin/modules?module=pdui-trades")
        assert "pdui-trades" in page.locator("body").inner_text()
        ctx.close()
    finally:
        import shutil
        shutil.rmtree(ROOT / "data" / "pdui-test" / tmp_path.name, ignore_errors=True)


def test_the_layout_at_desktop_and_phone_width(browser, designer):
    ctx = browser.new_context(viewport={"width": 1500, "height": 900})
    page = ctx.new_page()
    sign_in(page, str(designer))
    page.goto(str(designer) + "/build/pack/designer")
    rail = page.locator(".pd-rail").bounding_box()
    main = page.locator(".pd-main").bounding_box()
    assert rail["x"] < main["x"] and rail["width"] < main["width"]            # a rail beside the main area
    assert page.evaluate("() => document.documentElement.scrollWidth <= window.innerWidth")
    for step in ("kind", "design"):                                              # a pane is reachable by its rail button only after the data: the rail stays
        assert page.locator(f"[data-go={step}]").count() == 1
    page.set_viewport_size({"width": 390, "height": 800})
    rail = page.locator(".pd-rail").bounding_box()
    main = page.locator(".pd-main").bounding_box()
    assert rail["y"] + rail["height"] <= main["y"] + 1 and abs(rail["x"] - main["x"]) < 2   # stacked: the rail above the pane
    wide = page.evaluate("() => [...document.querySelectorAll('body *')].filter(e => e.getBoundingClientRect().right > window.innerWidth + 1 && !e.closest('.pd-rail ol')).slice(0, 5).map(e => e.tagName + '.' + e.className)")
    assert page.evaluate("() => document.documentElement.scrollWidth <= window.innerWidth + 1"), wide
    page.emulate_media(color_scheme="dark")
    assert page.evaluate("() => getComputedStyle(document.body).backgroundColor") != ""
    ctx.close()
