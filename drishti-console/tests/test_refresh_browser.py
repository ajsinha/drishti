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

"""Refresh and freshness in a real browser (SUTRA_NEXT.md 9), a real server behind the console: a book desk described with
"refresh 2s" (the Sutra designer's Describe writes refresh: 2; views.refresh_min_seconds is 2 here):

* its panels are fetched again on their interval with no reload (the page is the same document), and "updated N s ago" ticks;
* pause, resume and a slower interval on one panel, Pause all, all remembered per person and screen across a reload;
* a hidden tab fetches nothing, and catches up when shown;
* a source that fails says so in words, and a stale source is marked;
* a picked row and what it opened under it survive refreshes; the panels the pick drives follow it;
* two themes and a phone; the screenshots for the guide (docs/guides/img/designer/refresh-controls.jpg, freshness.jpg).

Skipped when Playwright, Chromium, the built server jar or a JDK is missing (wb_live.py)."""
import json
import os
from pathlib import Path

import pytest

from test_workbench_browser import browser, wait  # noqa: F401 - fixtures and helpers
from wb_live import _stack

IMG = Path(__file__).resolve().parents[2] / "docs" / "guides" / "img" / "designer"
LINE = "TRD BOOK-RATES-2 · click a trade → cash flows + CPTY limits · refresh 2s"
ENV = {"DRISHTI_UI_DESIGNER": "true", "DRISHTI_VIEWS_REFRESH_MIN_SECONDS": "2"}


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    old = {k: os.environ.get(k) for k in ENV}
    os.environ.update(ENV)                                   # read when the console's settings load: this console only
    try:
        yield from (str(s) for s in _stack(tmp_path_factory, {}))
    finally:
        for k, v in old.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v


def screen(browser, base, theme="terminal", size=None):
    """The described book desk, opened fresh with the reader's refresh choices for it forgotten; requests to its panels are counted."""
    pg = browser.new_page(viewport=size or {"width": 1500, "height": 950})
    pg.add_init_script("document.addEventListener('DOMContentLoaded', () => { document.documentElement.setAttribute('data-theme', '%s');"
                       " window.__wire = []; document.addEventListener('drishti:wire', e => window.__wire.push(e.detail)); })" % theme)
    pg.goto(base + "/t")
    r = pg.request.post(base + "/designer/describe", data=json.dumps({"text": LINE}), headers={"Content-Type": "application/json"}).json()
    assert r["ok"], r
    pg.fetches = []
    pg.on("request", lambda q: pg.fetches.append(q.url) if "/panels?" in q.url or q.url.endswith("/events") or "/events?" in q.url else None)
    pg.goto(base + r["open"])
    wait(pg, "window.drishtiRefresh && document.querySelectorAll('[data-fresh]').length > 1")
    key = pg.locator("[data-view]").get_attribute("data-screen")
    pg.request.patch(base + "/api/settings", data=json.dumps({"refresh": {key: None}}), headers={"Content-Type": "application/json"})
    pg.goto(base + r["open"])
    wait(pg, "window.drishtiRefresh && document.querySelectorAll('[data-fresh]').length > 1 && document.querySelector('#p-trades tr[data-ev]')")
    pg.evaluate("window.__doc = 1")                          # gone if the page reloads
    pg.fetches.clear()
    return pg


def refreshing(pg):
    return pg.evaluate("Object.keys(drishtiRefresh.state()).filter(k => drishtiRefresh.state()[k].every)")


def fetched(pg, pid):
    return pg.evaluate("id => drishtiRefresh.state()[id].fetched", pid)


def test_panels_refresh_on_their_interval_without_a_reload_and_the_freshness_ticks(stack, browser):
    pg = screen(browser, stack)
    ids = refreshing(pg)
    assert "trades" in ids and pg.evaluate("drishtiRefresh.min") == 2
    assert pg.locator(".fresh-bar [data-refresh-all]").is_visible()
    t0 = fetched(pg, "trades")
    text0 = pg.locator("[data-fresh='trades'] [data-fresh-text]").inner_text()
    assert text0.startswith("updated ") and text0.endswith(" ago")
    wait(pg, "drishtiRefresh.state().trades.fetched > %d" % t0)
    assert pg.evaluate("window.__doc") == 1                   # the same document: no reload
    assert any("/panels?" in u and "trades" in u for u in pg.fetches)
    assert pg.evaluate("document.querySelectorAll('#p-trades').length") == 1
    pg.wait_for_timeout(1100)
    assert pg.locator("[data-fresh='trades'] [data-fresh-text]").inner_text().startswith("updated ")
    first = pg.evaluate("drishtiRefresh.state().trades.fetched")
    wait(pg, "drishtiRefresh.state().trades.fetched > %d" % first)
    pg.close()


def test_pause_resume_a_slower_interval_and_pause_all_are_remembered(stack, browser):
    pg = screen(browser, stack)
    box = "[data-fresh='trades']"
    pg.click(box + " [data-refresh-toggle]")
    assert pg.locator(box + " [data-refresh-toggle]").get_attribute("aria-pressed") == "true"
    assert pg.locator(box + " [data-fresh-text]").inner_text().startswith("paused")
    pg.fetches.clear()
    pg.wait_for_timeout(4500)
    assert not [u for u in pg.fetches if "panels=" in u and "trades" in u.split("panels=")[1]], pg.fetches
    t = fetched(pg, "trades")
    pg.click(box + " [data-refresh-toggle]")                  # resumed: fetched at once
    wait(pg, "drishtiRefresh.state().trades.fetched > %d" % t, seconds=3)
    assert pg.locator(box + " [data-refresh-toggle]").get_attribute("aria-pressed") == "false"
    options = pg.eval_on_selector(box + " [data-refresh-every]", "s => [...s.options].map(o => +o.value)")
    assert options[0] == 2 and min(options) == 2                # never faster than the Sutra's interval or the minimum
    pg.select_option(box + " [data-refresh-every]", "10")
    assert pg.evaluate("drishtiRefresh.state().trades.chosen") == 10
    pg.click(box + " [data-refresh-toggle]")
    pg.click(".fresh-bar [data-refresh-all]")
    assert pg.locator(".fresh-bar [data-refresh-said]").inner_text() == "Refresh paused on this page"
    assert pg.locator("[data-view][data-refresh-paused]").count() == 1
    pg.wait_for_timeout(800)                                  # the choices are saved (debounced)
    pg.screenshot(path=str(IMG / "refresh-controls.jpg"), type="jpeg", quality=84, clip={"x": 0, "y": 0, "width": 1500, "height": 700})
    pg.reload()
    wait(pg, "window.drishtiRefresh && document.querySelector('[data-fresh=\"trades\"] [data-refresh-every]')")
    assert pg.locator(box + " [data-refresh-every]").input_value() == "10"
    assert pg.locator(box + " [data-refresh-toggle]").get_attribute("aria-pressed") == "true"
    assert pg.locator(".fresh-bar [data-refresh-all]").get_attribute("aria-pressed") == "true"
    pg.fetches.clear()
    pg.wait_for_timeout(4500)
    assert not [u for u in pg.fetches if "/panels?" in u], pg.fetches        # the whole page is paused
    pg.click(".fresh-bar [data-refresh-all]")
    wait(pg, "document.querySelector('.fresh-bar [data-refresh-all]').getAttribute('aria-pressed') === 'false'")
    pg.close()


def test_a_hidden_tab_fetches_nothing_and_catches_up_when_shown(stack, browser):
    pg = screen(browser, stack)
    pg.evaluate("Object.defineProperty(document, 'hidden', { configurable: true, get: () => true }); document.dispatchEvent(new Event('visibilitychange'))")
    pg.fetches.clear()
    pg.wait_for_timeout(5000)
    assert not [u for u in pg.fetches if "/panels?" in u], pg.fetches
    t = fetched(pg, "trades")
    pg.evaluate("Object.defineProperty(document, 'hidden', { configurable: true, get: () => false }); document.dispatchEvent(new Event('visibilitychange'))")
    wait(pg, "drishtiRefresh.state().trades.fetched > %d" % t)
    pg.close()


def test_a_failing_source_is_named_and_a_stale_one_marked(stack, browser):
    pg = screen(browser, stack, theme="light")
    pg.route("**/panels?*", lambda route: route.fulfill(status=502, content_type="application/json",
                                                        body=json.dumps({"code": "DRS-1003", "detail": "the source failed"})))
    wait(pg, "document.querySelector('[data-fresh=\"trades\"]').getAttribute('data-state') === 'down'")
    words = pg.locator("[data-fresh='trades'] [data-fresh-text]").inner_text()
    assert "isn’t answering; showing data from " in words, words
    pg.unroute("**/panels?*")

    def stale(route):
        r = route.fetch()
        body = r.json()
        body["provenance"].update(stale=True, updatedAt="2026-10-23T10:42:00Z", staleAfter="PT2M")
        route.fulfill(response=r, body=json.dumps(body))
    pg.route("**/panels?*", stale)
    wait(pg, "document.querySelector('[data-fresh=\"trades\"]').getAttribute('data-state') === 'stale'")
    assert pg.locator("[data-fresh='trades'] [data-fresh-text]").inner_text().startswith("stale")
    pg.screenshot(path=str(IMG / "freshness.jpg"), type="jpeg", quality=84, clip={"x": 0, "y": 0, "width": 1500, "height": 700})
    pg.unroute("**/panels?*")
    wait(pg, "document.querySelector('[data-fresh=\"trades\"]').getAttribute('data-state') === 'ok'")
    pg.close()


def test_a_picked_row_and_what_it_opened_survive_refreshes(stack, browser):
    pg = screen(browser, stack)
    pg.locator("#p-trades tbody tr[data-ev]").nth(0).locator("td").nth(1).click()
    wait(pg, "window.__wire.length === 1 && document.querySelector('tr.wire-kids')")
    driven = pg.evaluate("window.__wire[0].panels")
    t = fetched(pg, "trades")
    wait(pg, "drishtiRefresh.state().trades.fetched > %d" % t)
    t = fetched(pg, "trades")
    wait(pg, "drishtiRefresh.state().trades.fetched > %d" % t)       # two refreshes later
    assert pg.locator("#p-trades tbody tr[aria-selected='true']").count() == 1
    assert pg.evaluate("document.querySelectorAll('#p-trades tbody tr[data-ev]')[0].getAttribute('aria-selected')") == "true"
    assert pg.locator("tr.wire-kids").count() == 1
    refreshed = [p for p in driven if p in refreshing(pg)]
    if refreshed:                                             # the panels the pick drives follow it: sent again as that pick
        wait(pg, "window.performance.getEntriesByType('resource').some(e => e.name.includes('/events'))")
        for p in refreshed:
            assert pg.locator(f"#p-{p} .pnl-wait").count() == 0, p
    assert pg.evaluate("window.__doc") == 1
    pg.close()


def test_a_phone_keeps_the_controls_on_the_page(stack, browser):
    pg = screen(browser, stack, theme="light", size={"width": 390, "height": 844})
    assert pg.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
    assert pg.locator("[data-fresh='trades'] [data-refresh-toggle]").is_visible()
    box = pg.locator("[data-fresh='trades'] [data-refresh-toggle]").bounding_box()
    assert box["x"] >= 0 and box["x"] + box["width"] <= 390
    pg.close()
