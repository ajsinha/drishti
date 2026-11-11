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

"""Home's house in a real browser (HOW_IT_FITS.md "The house"): below today's hero (unchanged: the headline, the tagline, the animation),
the kernel with the running distribution's loaded modules as rooms and empty .dmod sockets for the rest. Drishti All shows every
module its server loaded; Drishti Risk only its own two, with sockets beside them; the product name and tagline come from the
distribution's brand; the How it fits link; no sideways scroll or overlap on a desktop or a phone, in the dark and light themes. One
console per distribution on the stand-in server (ports 17945 and 17946); skipped when Playwright or its Chromium is missing."""
from __future__ import annotations

import threading
import time

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE, FakeBackend  # noqa: E402
from page_walk import layout_faults  # noqa: E402
from scratch_ports import worker_port  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture

LOADED = {"all": ("banking", "market-data", "capitalmarkets", "economy"), "risk": ("banking", "market-data")}
PORTS = {"all": worker_port(17945), "risk": worker_port(17946)}


class Stand(FakeBackend):
    """The stand-in server of one distribution: the modules it loaded, with their titles, as GET /api/v1/modules says them."""

    def __init__(self, loaded):
        super().__init__()
        self.loaded = loaded

    async def modules(self, ident=None):
        answer = await super().modules(ident)
        titles = {"banking": "Banking and risk", "market-data": "Market data", "capitalmarkets": "Capital markets", "economy": "Economy"}
        return {**answer, "modules": [{"id": m, "title": titles.get(m, m), "domain": m} for m in ("core",) + self.loaded]}


@pytest.fixture(scope="module", params=["all", "risk"])
def site(request):
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    name = request.param
    app = create_app(load_settings(CONSOLE / "config", [f"--distribution={name}"]))
    app.state.backend = Stand(LOADED[name])
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=PORTS[name], log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started, "the console did not start"
    yield name, f"http://127.0.0.1:{PORTS[name]}", app
    server.should_exit = True
    thread.join(10)


def _open(browser, base, width, height, theme):  # noqa: F811
    ctx = browser.new_context(viewport={"width": width, "height": height})
    ctx.add_init_script(f"try {{ localStorage.setItem('drishti.theme', '{theme}'); }} catch (e) {{}}")
    pg = ctx.new_page()
    pg.goto(base + "/", wait_until="load")
    return ctx, pg


def test_the_house_shows_the_distributions_loaded_modules(browser, site):  # noqa: F811
    name, base, app = site
    ctx, pg = _open(browser, base, 1440, 900, "terminal")
    try:
        assert pg.locator("#lpHero").count() == 1 and "hasn't told you yet." in pg.locator(".lp-title").inner_text()   # today's hero
        house = pg.locator("[data-house]")
        house.scroll_into_view_if_needed()
        assert "One kernel. Modules plug in." in house.locator("h2").inner_text()
        rooms = house.locator("[data-house-room]").evaluate_all("els => els.map(e => e.dataset.houseRoom)")
        assert sorted(rooms) == sorted(LOADED[name]), rooms
        sockets = house.locator("[data-house-socket]").count()
        assert sockets == max(4, len(rooms)) - len(rooms)                      # Risk: two empty sockets; All: none
        product = app.state.settings.get("ui.product")
        assert product == ("Drishti Risk" if name == "risk" else "Drishti")
        lead = house.locator(".lp-lead").inner_text()
        assert lead.startswith(product + " is one kernel with " + str(len(rooms)) + " modules"), lead
        assert app.state.settings.get("ui.tagline") in house.locator("figcaption").inner_text()
        assert house.locator("[data-house-link]").get_attribute("href") == "/help/how-it-fits"
    finally:
        ctx.close()


@pytest.mark.parametrize("width,height,theme", [(1440, 900, "terminal"), (1440, 900, "light"), (390, 844, "light"), (390, 844, "terminal")])
def test_the_house_lays_out_on_a_desktop_and_a_phone(browser, site, width, height, theme):  # noqa: F811
    _, base, _ = site
    ctx, pg = _open(browser, base, width, height, theme)
    try:
        assert pg.evaluate("document.documentElement.dataset.theme") == theme
        house = pg.locator("[data-house]")
        house.scroll_into_view_if_needed()
        pic = house.locator("[data-house-picture]").bounding_box()
        text = house.locator("h2").bounding_box()
        assert pic["width"] > 0.8 * (width - 32) if width < 600 else pic["width"] > 400, pic       # a phone stacks it under the text
        if width < 600:
            assert pic["y"] >= text["y"] + text["height"]
        assert pic["x"] >= 0 and pic["x"] + pic["width"] <= width + 1
        faults = [f for f in layout_faults(pg) if "sideways" in f or "overlap" in f]
        assert not faults, faults
    finally:
        ctx.close()
