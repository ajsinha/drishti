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

"""The Raw document viewer in a real browser (Chromium): a header metric (2s10s) opens its "over time" popup, and the popup's Raw
document link opens the JSON on the same page, not in a new tab: a tree with the metric's path highlighted, search, copy, a download
named <kind>-<id>-<date>.json, masked values as the server sent them, Escape back to the link, every theme's tokens, and a phone at full
screen. F9's drawer draws the same tree. The console runs in-process against a stand-in server; skipped without Playwright's Chromium."""
import os
import threading
import time

import pytest

from conftest import CONSOLE, FakeBackend

sync_api = pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from test_live_tabs_browser import _free_port  # noqa: E402

THEMES = ("terminal", "light", "wallstreet", "blue", "green", "crimson", "crimson-dark", "terminal-green", "terminal-crimson", "terminal-blue")
VIEW = "/v/trade/IRS-48213"
DOC = {"tradeId": "IRS-48213", "SLOPE_2S_10S": 42.5, "counterparty": {"name": "•••", "lei": "•••"},
       "legs": [{"leg": i, "notional": 1000000 + i} for i in range(120)], "live": True, "note": None}


class RawBackend(FakeBackend):
    async def view(self, kind, id_, user, controls=None, panels=None):
        vm = await super().view(kind, id_, user, controls, panels)
        vm["strip"] = [{"label": "2s10s", "text": "+42.5", "path": "$.SLOPE_2S_10S"}] + vm["strip"]
        return vm

    async def raw(self, kind, id_, ident=None):
        return {"ref": {"kind": kind, "id": id_}, "provenance": {"source": "aero-risk", "generation": 1742}, "data": DOC}

    async def series(self, kind, id_, path, days, ident=None):
        return {"ref": {"kind": kind, "id": id_}, "path": path, "label": "2s10s", "dated": True,
                "points": [{"date": "2026-10-16", "value": 41.0, "dataDate": "2026-10-16", "source": "lake", "generation": 1741},
                           {"date": "2026-10-19", "value": 42.5, "dataDate": "2026-10-19", "source": "lake", "generation": 1742}]}


@pytest.fixture(scope="module")
def url():
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = RawBackend()
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
        except Exception as e:  # noqa: BLE001 - no browser installed
            pytest.skip(f"Chromium is not installed: {e}")
        yield b
        b.close()


def _page(browser, theme, width=1400, height=900):
    ctx = browser.new_context(viewport={"width": width, "height": height}, accept_downloads=True)
    pg = ctx.new_page()
    errors = []
    pg.on("pageerror", lambda e: errors.append(str(e)))
    pg.add_init_script(f"document.addEventListener('DOMContentLoaded', () => document.documentElement.setAttribute('data-theme', '{theme}'))")
    return ctx, pg, errors


def _open_viewer(pg, url):
    pg.goto(url + VIEW)
    pg.click('dd[data-path="$.SLOPE_2S_10S"]')                                     # the header metric
    pg.wait_for_selector(".hist-dlg [data-raw-open]")
    pages = len(pg.context.pages)
    pg.click(".hist-dlg [data-raw-open]")
    pg.wait_for_selector('.raw-dlg[data-rv-ready="1"]')
    assert len(pg.context.pages) == pages                                          # no new tab
    assert pg.url.endswith(VIEW)


@pytest.mark.parametrize("theme", ["terminal", "light"])
def test_raw_document_opens_on_the_page_at_the_metric(url, browser, theme):
    ctx, pg, errors = _page(browser, theme)
    try:
        _open_viewer(pg, url)
        hit = pg.locator('.raw-dlg [data-jp="$.SLOPE_2S_10S"] > .jt-l')
        assert "jt-hit" in hit.get_attribute("class") and "jt-sel" in hit.get_attribute("class")
        assert "42.5" in hit.inner_text()
        pg.wait_for_function("""() => { const r = document.querySelector('.raw-dlg [data-jp="$.SLOPE_2S_10S"]').getBoundingClientRect(),
            b = document.querySelector('.raw-dlg [data-rv-tree]').getBoundingClientRect(); return r.top >= b.top && r.bottom <= b.bottom; }""")
        assert pg.inner_text('.raw-dlg [data-jp="$.counterparty.name"] .jt-mask') == "•••"   # masked, as sent
        keys = pg.eval_on_selector_all('.raw-dlg [data-jp="$"] > .jt-c > .jt-n', "ns => ns.map(n => n.getAttribute('data-jp'))")
        assert keys == ["$.tradeId", "$.SLOPE_2S_10S", "$.counterparty", "$.legs", "$.live", "$.note"]   # the document's order
        assert pg.locator('.raw-dlg [data-jp="$.legs"] > .jt-c > .jt-n').count() == 50   # the long array is cut at 50
        pg.click('.raw-dlg [data-jp="$.legs"] > .jt-l .jt-t')                      # collapses
        assert pg.get_attribute('.raw-dlg [data-jp="$.legs"]', "aria-expanded") == "false"
        assert "120 items" in pg.inner_text('.raw-dlg [data-jp="$.legs"] > .jt-l')
        pg.click('.raw-dlg [data-jp="$.legs"] > .jt-l .jt-t')                      # and opens again
        pg.click(".raw-dlg [data-jt-more]")
        assert pg.locator('.raw-dlg [data-jp="$.legs"] > .jt-c > .jt-n').count() == 120
        pg.fill(".raw-dlg [data-rv-search]", "1000007")                           # search: values (and keys)
        pg.wait_for_function("() => document.querySelector('.raw-dlg [data-rv-count]').textContent === '1 match'")
        assert pg.locator(".raw-dlg .jt-hit").count() == 1 and pg.locator('.raw-dlg [data-jp="$.legs[7].notional"]').count() == 1
        assert pg.locator('.raw-dlg [data-jp="$.tradeId"]').count() == 0
        pg.fill(".raw-dlg [data-rv-search]", "")
        pg.wait_for_selector('.raw-dlg [data-jp="$.tradeId"]')
        with pg.expect_download() as dl:
            pg.click(".raw-dlg [data-rv-download]")
        assert dl.value.suggested_filename == "trade-IRS-48213-2026-10-19.json"
        pg.keyboard.press("Escape")                                                # closes the viewer only, focus back on the link
        pg.wait_for_function("() => !document.querySelector('.raw-dlg').open")
        assert pg.evaluate("document.querySelector('.hist-dlg').open") is True
        assert pg.evaluate("document.activeElement.hasAttribute('data-raw-open')") is True
        if theme == "terminal" and os.environ.get("DRISHTI_SHOT_DIR"):
            pg.click(".hist-dlg [data-raw-open]")
            pg.wait_for_selector('.raw-dlg[data-rv-ready="1"]')
            pg.screenshot(path=os.path.join(os.environ["DRISHTI_SHOT_DIR"], "raw-viewer.png"))
        assert not errors, errors
    finally:
        ctx.close()


def test_every_theme_colours_the_tree_from_its_tokens_and_a_phone_is_full_screen(url, browser):
    ctx, pg, errors = _page(browser, "terminal", width=390, height=800)
    try:
        _open_viewer(pg, url)
        assert pg.evaluate("document.querySelector('.raw-dlg').getBoundingClientRect().width") == 390
        assert pg.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
        for theme in THEMES:
            got = pg.evaluate("""t => { document.documentElement.setAttribute('data-theme', t);
                const s = getComputedStyle(document.documentElement), probe = document.createElement('i');
                document.body.appendChild(probe);
                const css = v => { probe.style.color = s.getPropertyValue(v).trim(); return getComputedStyle(probe).color; };
                const out = { key: getComputedStyle(document.querySelector('.raw-dlg .jt-key')).color, accent: css('--d-accent'),
                  num: getComputedStyle(document.querySelector('.raw-dlg .jt-num')).color, link: css('--d-link'),
                  bg: getComputedStyle(document.querySelector('.raw-dlg')).backgroundColor, ink: getComputedStyle(document.querySelector('.raw-dlg')).color };
                probe.remove(); return out; }""", theme)
            assert got["key"] == got["accent"] and got["num"] == got["link"], (theme, got)
            assert got["bg"] != got["ink"] and got["key"] != got["bg"], (theme, got)
        assert not errors, errors
    finally:
        ctx.close()


def test_f9_draws_the_same_tree_and_opens_the_viewer(url, browser):
    ctx, pg, errors = _page(browser, "light")
    try:
        pg.goto(url + VIEW)
        pg.keyboard.press("F9")
        pg.wait_for_selector('#rawDrawer [data-jp="$.SLOPE_2S_10S"]')
        pg.click("#rawDrawer [data-raw-full]")
        pg.wait_for_selector('.raw-dlg[data-rv-ready="1"]')
        assert pg.evaluate("document.getElementById('rawDrawer').hidden") is True
        assert not errors, errors
    finally:
        ctx.close()
