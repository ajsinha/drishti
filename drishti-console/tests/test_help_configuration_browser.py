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

"""Help → Configuration in Chromium (2.9.7 C3): the search filters the settings as one types (a key, an environment variable, words),
the area filter keeps one area, the address keeps the search, an administrator sees the values in effect; on a phone (375 px) every
theme draws the page, searched or not, with no sideways scroll. The console runs in-process against a stand-in server, with its data
folder in a scratch directory."""
import os
import threading
import time

import pytest

from conftest import CONSOLE, FakeBackend

sync_api = pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from test_live_tabs_browser import _free_port  # noqa: E402

THEMES = ["terminal", "light", "wallstreet", "blue", "green", "crimson", "crimson-dark", "terminal-green", "terminal-crimson", "terminal-blue"]
SETTINGS = {"settings": [
    {"key": "drishti.history.max-days", "value": "90", "origin": "argument", "source": "--drishti.history.max-days", "secret": False},
    {"key": "drishti.security.secret", "value": "••••", "origin": "env", "source": "DRISHTI_TOKEN_SECRET", "secret": True},
]}


class ConfigBackend(FakeBackend):
    async def admin(self, method, path, ident=None, body=None, **params):
        if path == "/settings":
            return SETTINGS
        return await super().admin(method, path, ident, body, **params)


@pytest.fixture(scope="module")
def url(tmp_path_factory):
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    saved = os.environ.get("DRISHTI_DATA_DIR")
    os.environ["DRISHTI_DATA_DIR"] = str(tmp_path_factory.mktemp("data"))      # never the repository's data/
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = ConfigBackend()
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
    if saved is None:
        os.environ.pop("DRISHTI_DATA_DIR", None)
    else:
        os.environ["DRISHTI_DATA_DIR"] = saved


@pytest.fixture(scope="module")
def browser():
    with sync_api.sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001 - no browser installed
            pytest.skip(f"Chromium is not installed: {e}")
        yield b
        b.close()


def _visible_rows(pg) -> int:
    return pg.evaluate("[...document.querySelectorAll('tr.cfg-row')].filter(r => r.offsetParent !== null).length")


def test_the_search_filters_as_one_types(url, browser):
    ctx = browser.new_context(viewport={"width": 1280, "height": 900})
    pg = ctx.new_page()
    errors = []
    pg.on("pageerror", lambda e: errors.append(str(e)))
    try:
        pg.goto(url + "/help/configuration")
        pg.wait_for_selector("[data-cfg-q]")
        total = _visible_rows(pg)
        assert total > 1500 and pg.is_visible(".cfg-explain")
        pg.fill("[data-cfg-q]", "history max-days")
        pg.locator("[data-cfg-count]", has_text="match").wait_for()
        assert 0 < _visible_rows(pg) < 10 and not pg.is_visible(".cfg-explain")
        assert pg.is_visible("tr.cfg-row[data-hay^='drishti.history.max-days']")
        assert "q=history+max-days" in pg.url or "q=history%20max-days" in pg.url
        cell = pg.inner_text("tr.cfg-row[data-hay^='drishti.history.max-days'] .cfg-eff")      # security off: the acting user is an admin
        assert "90" in cell and "argument" in cell

        pg.fill("[data-cfg-q]", "DRISHTI_PORT")
        pg.wait_for_timeout(300)
        assert pg.is_visible("tr.cfg-row[data-hay^='server.port']")

        pg.fill("[data-cfg-q]", "")
        pg.select_option("[data-cfg-area]", "packs-packyaml")
        pg.wait_for_timeout(300)
        shown = pg.evaluate("[...document.querySelectorAll('tr.cfg-row')].filter(r => r.offsetParent !== null).map(r => r.dataset.area)")
        assert shown and set(shown) == {"packs-packyaml"}
        assert not errors, errors
    finally:
        ctx.close()


@pytest.mark.parametrize("theme", THEMES)
def test_every_theme_fits_a_phone(url, browser, theme):
    ctx = browser.new_context(viewport={"width": 375, "height": 812}, is_mobile=True, has_touch=True)
    pg = ctx.new_page()
    errors = []
    pg.on("pageerror", lambda e: errors.append(str(e)))
    pg.add_init_script(f"document.addEventListener('DOMContentLoaded', () => document.documentElement.setAttribute('data-theme', '{theme}'))")
    try:
        pg.goto(url + "/help/configuration?q=proxy")
        pg.wait_for_selector("tr.cfg-row:not([hidden])")
        wide = pg.evaluate("document.documentElement.scrollWidth - window.innerWidth")
        assert wide <= 1, f"{theme}: {wide} px of horizontal scroll"
        card = pg.evaluate("getComputedStyle(document.querySelector('tr.cfg-row:not([hidden])')).display")
        assert card == "block", card                                                      # a row is a card on a phone
        ink, bg = pg.evaluate("[getComputedStyle(document.body).color, getComputedStyle(document.body).backgroundColor]")
        assert ink != bg
        assert not errors, errors
    finally:
        ctx.close()
