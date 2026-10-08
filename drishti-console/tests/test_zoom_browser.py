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

"""Panel zoom in a real browser (Chromium): the button, Z and Esc, focus and aria, the hash, live patches while zoomed, charts that
grow, exclusion with layout mode, and a phone (390 px, touch) where the close button is finger-sized and nothing scrolls sideways.
The console runs in-process against a stand-in server whose view ticks (a strip cell every 200 ms, the `leg2` panel replaced every
third frame); skipped when Playwright's Chromium is not installed."""
import asyncio
import json
import threading
import time

import pytest

from conftest import CONSOLE

sync_api = pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from test_live_tabs_browser import TickingBackend, _free_port  # noqa: E402

VIEW = "/v/trade/IRS-48213"
def leg2(n):
    return {"id": "leg2", "kind": "kv", "title": "Leg 2", "area": "main", "data": {"fields": [{"label": "Tick", "text": str(n)}]}}


class PanelTicking(TickingBackend):
    async def stream(self, kind, id_, ident=None, opened=None):
        yield "view", json.dumps({"provenance": {"generation": 1}})
        n = 0
        while True:
            await asyncio.sleep(0.2)
            n += 1
            patches = [{"op": "strip", "index": 0, "cell": {"label": "Tick", "text": f"{id_} #{n}"}}]
            if n % 3 == 0:
                patches.append({"op": "panel", "panel": leg2(n)})
            yield "frame", json.dumps({"seq": n, "generation": n + 1, "p99Ms": 1.0, "latencyMs": 0.5, "patches": patches})


@pytest.fixture(scope="module")
def url():
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = PanelTicking()
    port = _free_port()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started
    yield f"http://127.0.0.1:{port}"
    server.should_exit = True
    thread.join(10)


@pytest.fixture(scope="module")
def browser():
    with sync_api.sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001
            pytest.skip(f"needs Playwright's Chromium: {str(e).splitlines()[0]}")
        yield b
        b.close()


@pytest.fixture
def page(browser, url):
    ctx = browser.new_context(viewport={"width": 1280, "height": 800}, bypass_csp=True)
    pg = ctx.new_page()
    pg.goto(url + VIEW, wait_until="load")
    pg.wait_for_selector("#p-curve .pnl-zoom-btn")
    yield pg
    ctx.close()


def btn(page, pid="curve"):
    return page.locator(f"#p-{pid} .pnl-zoom-btn")


def test_every_panel_has_a_labelled_zoom_button_next_to_the_help_link(page):
    n = page.evaluate("document.querySelectorAll('section.pnl[data-panel]').length")
    assert n >= 5 and page.locator("section.pnl .pnl-zoom-btn").count() == n
    b = btn(page)
    assert b.get_attribute("aria-label") == "Expand " + page.inner_text("#h-curve").strip()
    assert b.get_attribute("aria-pressed") == "false"
    assert page.evaluate("""(() => { const b = document.querySelector('#p-curve .pnl-zoom-btn'), h = document.querySelector('#p-curve a[href*="/help/panel-kinds"]');
        return b.parentNode === h.parentNode && !!(b.compareDocumentPosition(h) & Node.DOCUMENT_POSITION_FOLLOWING); })()""")


def test_click_zooms_fills_the_view_area_locks_scroll_and_makes_the_rest_inert(page):
    title = page.inner_text("#h-curve").strip()
    before = page.evaluate("document.querySelector('#p-curve .chart').getBoundingClientRect().width")
    btn(page).click()
    assert btn(page).get_attribute("aria-pressed") == "true"
    assert btn(page).get_attribute("aria-label") == "Restore " + title
    box = page.evaluate("""() => { const r = document.getElementById('p-curve').getBoundingClientRect(), bar = document.querySelector('.tbar').getBoundingClientRect(),
        f = document.querySelector('footer.fkeys').getBoundingClientRect();
        return {l: r.left, r: r.right, t: r.top, b: r.bottom, barB: bar.bottom, footT: f.top, w: innerWidth}; }""")
    assert box["l"] == 0 and box["r"] == box["w"] and abs(box["t"] - box["barB"]) <= 1 and abs(box["b"] - box["footT"]) <= 1
    assert page.evaluate("getComputedStyle(document.documentElement).overflow") == "hidden"
    assert page.evaluate("document.activeElement.id") == "p-curve"
    assert page.evaluate("document.getElementById('p-cashflows').inert") is True
    assert page.evaluate("document.getElementById('p-cashflows').getAttribute('aria-hidden')") == "true"
    assert page.evaluate("document.querySelectorAll('.pnl-zoom').length") == 1
    page.wait_for_function("(b) => document.querySelector('#p-curve .chart svg').getBoundingClientRect().width > b + 50", arg=before)
    btn(page).click()                                                       # the same control restores, and the focus returns to it
    assert btn(page).get_attribute("aria-pressed") == "false"
    assert page.evaluate("document.activeElement.classList.contains('pnl-zoom-btn')")
    assert page.evaluate("document.getElementById('p-cashflows').inert") is False
    assert page.evaluate("getComputedStyle(document.documentElement).overflow") != "hidden"
    page.wait_for_function("(b) => Math.abs(document.querySelector('#p-curve .chart svg').getBoundingClientRect().width - b) < 3", arg=before)


def test_only_one_panel_is_zoomed_and_z_and_escape_work_from_the_keyboard(page):
    page.focus("#p-cashflows")
    page.keyboard.press("z")
    assert page.evaluate("document.querySelector('.pnl-zoom').id") == "p-cashflows"
    assert page.evaluate("location.hash") == "#zoom=cashflows"
    btn(page, "cashflows").click()                                           # restored by its button; then another one
    btn(page, "curve").click()
    assert page.evaluate("[...document.querySelectorAll('.pnl-zoom')].map(e => e.id)") == ["p-curve"]
    page.keyboard.press("Escape")
    assert page.evaluate("document.querySelectorAll('.pnl-zoom').length") == 0
    assert page.evaluate("location.hash") == ""
    assert page.evaluate("document.activeElement.classList.contains('pnl-zoom-btn')")
    page.keyboard.press("z")                                                 # focus is on the button, which is inside the panel: Z zooms it
    assert page.evaluate("document.querySelectorAll('.pnl-zoom').length") == 1
    page.keyboard.press("z")
    assert page.evaluate("document.querySelectorAll('.pnl-zoom').length") == 0


def test_escape_closes_the_drawer_first_then_the_zoom(page):
    btn(page, "curve").click()
    page.keyboard.press("F9")                                                # the raw JSON drawer
    page.wait_for_function("!document.getElementById('rawDrawer').hidden")
    page.keyboard.press("Escape")
    assert page.evaluate("document.getElementById('rawDrawer').hidden") is True
    assert page.evaluate("document.querySelectorAll('.pnl-zoom').length") == 1
    page.keyboard.press("Escape")
    assert page.evaluate("document.querySelectorAll('.pnl-zoom').length") == 0


def test_typing_z_in_a_field_does_not_zoom(page):
    page.focus("#p-cashflows")
    page.keyboard.press("z")
    inp = page.locator("#p-cashflows input.tbl-pg-q")
    if inp.count():
        inp.first.focus()
        page.keyboard.type("z")
        assert page.evaluate("document.activeElement.value") == "z"
        assert page.evaluate("document.querySelectorAll('.pnl-zoom').length") == 1


def test_the_hash_restores_on_reload_and_an_unknown_id_is_ignored(page, url):
    btn(page, "curve").click()
    page.reload(wait_until="load")
    page.wait_for_selector("#p-curve.pnl-zoom")
    assert btn(page).get_attribute("aria-pressed") == "true"
    page.goto("about:blank")
    page.goto(url + VIEW + "#zoom=nosuchpanel", wait_until="load")
    page.wait_for_selector("#p-curve .pnl-zoom-btn")
    assert page.evaluate("document.querySelectorAll('.pnl-zoom').length") == 0


def test_live_patches_keep_applying_while_zoomed_even_when_the_panel_is_replaced(page):
    btn(page, "leg2").click()
    page.wait_for_function("document.querySelector('#p-leg2 dd') && document.querySelector('#p-leg2 dd').textContent === '3'")
    first = page.inner_text("#p-leg2 dd")
    page.wait_for_function("(t) => { const e = document.querySelector('#p-leg2 dd'); return e && e.textContent !== t; }", arg=first, timeout=8000)
    assert page.evaluate("document.getElementById('p-leg2').classList.contains('pnl-zoom')")
    assert page.evaluate("document.getElementById('p-leg2').getAttribute('aria-hidden')") is None
    assert page.locator("#p-leg2 .pnl-zoom-btn").get_attribute("aria-pressed") == "true"
    assert page.evaluate("document.getElementById('p-curve').inert") is True
    t0 = page.inner_text(".strip .strip-i dd")
    page.wait_for_function("(t) => document.querySelector('.strip .strip-i dd').textContent !== t", arg=t0, timeout=8000)


def test_a_panel_replaced_by_a_patch_while_another_is_zoomed_stays_inert(page):
    btn(page, "curve").click()
    page.wait_for_function("document.querySelector('#p-leg2 dd') && document.querySelector('#p-leg2 dd').textContent === '3'")
    first = page.inner_text("#p-leg2 dd")
    page.wait_for_function("(t) => document.querySelector('#p-leg2 dd').textContent !== t", arg=first, timeout=8000)
    assert page.evaluate("document.getElementById('p-leg2').inert") is True
    assert page.evaluate("document.getElementById('p-curve').inert") is False


def test_layout_mode_and_zoom_exclude_each_other(page):
    btn(page, "curve").click()
    page.keyboard.press("Alt+l")
    assert page.evaluate("document.querySelectorAll('.pnl-zoom').length") == 0
    assert btn(page, "curve").is_hidden()
    page.focus("#p-curve")
    page.keyboard.press("z")
    assert page.evaluate("document.querySelectorAll('.pnl-zoom').length") == 0


def test_phone_zoom_fills_the_screen_under_the_top_bar_with_a_finger_sized_close_button(browser, url):
    ctx = browser.new_context(viewport={"width": 390, "height": 800}, has_touch=True, is_mobile=True, device_scale_factor=2)
    pg = ctx.new_page()
    try:
        pg.goto(url + VIEW, wait_until="load")
        pg.wait_for_selector("#p-curve .pnl-zoom-btn")
        b = pg.locator("#p-curve .pnl-zoom-btn")
        pg.evaluate("document.getElementById('p-curve').scrollIntoView()")
        b.tap()
        assert pg.evaluate("document.querySelectorAll('.pnl-zoom').length") == 1
        size = b.bounding_box()
        assert size["width"] >= 43.5 and size["height"] >= 43.5, size
        assert pg.evaluate("document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1")
        r = pg.evaluate("(() => { const r = document.getElementById('p-curve').getBoundingClientRect(); return [r.left, r.right, innerWidth, r.top, document.querySelector('.tbar').getBoundingClientRect().bottom]; })()")
        assert r[0] == 0 and r[1] == r[2] and abs(r[3] - r[4]) <= 1
        b.tap()
        assert pg.evaluate("document.querySelectorAll('.pnl-zoom').length") == 0
    finally:
        ctx.close()


def test_print_shows_the_zoomed_panel_alone(page):
    btn(page, "curve").click()
    page.emulate_media(media="print")
    assert page.evaluate("getComputedStyle(document.getElementById('p-cashflows')).display") == "none"
    assert page.evaluate("getComputedStyle(document.getElementById('p-curve')).display") != "none"
    assert page.evaluate("getComputedStyle(document.getElementById('p-curve')).position") == "static"
