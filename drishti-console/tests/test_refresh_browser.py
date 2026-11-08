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

"""Refresh and freshness in a real browser (RACHANA_REFERENCE.md 9), a real server behind the console: a book desk described with
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

import select_helpers

from test_workbench_browser import browser, wait  # noqa: F401 - fixtures and helpers
from wb_live import _stack
from docshots import docs_img

IMG = docs_img("guides/img/designer")                 # docs/guides/img/designer only when DRISHTI_SHOTS is set (docshots.py)
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
    wait(pg, "window.Drishti.refresh && document.querySelectorAll('[data-fresh]').length > 1")
    key = pg.locator("[data-view]").get_attribute("data-screen")
    pg.request.patch(base + "/api/settings", data=json.dumps({"refresh": {key: None}}), headers={"Content-Type": "application/json"})
    pg.goto(base + r["open"])
    wait(pg, "window.Drishti.refresh && document.querySelectorAll('[data-fresh]').length > 1 && document.querySelector('#p-trades tr[data-ev]')")
    pg.evaluate("window.__doc = 1")                          # gone if the page reloads
    pg.fetches.clear()
    return pg


def refreshing(pg):
    return pg.evaluate("Object.keys(Drishti.refresh.state()).filter(k => Drishti.refresh.state()[k].every)")


def fetched(pg, pid):
    return pg.evaluate("id => Drishti.refresh.state()[id].fetched", pid)


def said_by_its_age(pg, pid):
    """The panel's freshness line says what its age calls for (drishti_refresh.js): "updated N s ago" up to twice its interval, "stale ·
    updated N s ago" past it. Its text and its age are read at one moment. Asserting "updated" alone failed whenever a refresh round took
    over a second on a loaded machine (2 s interval, a 1 s tick): the page then rightly said stale. The line is painted once a second, so
    an age within a second of the bound may read either way."""
    text, age, every = pg.evaluate("""id => { const p = Drishti.refresh.state()[id];
      return [document.querySelector('[data-fresh="' + id + '"] [data-fresh-text]').textContent, Date.now() - p.fetched, Math.max(p.floor, p.chosen || p.floor)]; }""", pid)
    bound = 2 * every * 1000
    assert text.endswith(" ago"), text
    if age <= bound - 1000:
        assert text.startswith("updated "), (text, age, bound)
    elif age > bound + 1000:
        assert text.startswith("stale · updated "), (text, age, bound)
    else:
        assert text.startswith(("updated ", "stale · updated ")), (text, age, bound)


def test_panels_refresh_on_their_interval_without_a_reload_and_the_freshness_ticks(stack, browser):
    pg = screen(browser, stack)
    ids = refreshing(pg)
    assert "trades" in ids and pg.evaluate("Drishti.refresh.min") == 2
    assert pg.locator(".fresh-bar [data-refresh-all]").is_visible()
    t0 = fetched(pg, "trades")
    said_by_its_age(pg, "trades")
    wait(pg, "Drishti.refresh.state().trades.fetched > %d" % t0)
    assert pg.evaluate("window.__doc") == 1                   # the same document: no reload
    assert any("/panels?" in u and "trades" in u for u in pg.fetches)
    assert pg.evaluate("document.querySelectorAll('#p-trades').length") == 1
    # the refetched answer lands and the label says so (waited on, not read after a fixed pause: under a loaded lane the panel's
    # answer can take seconds, and the label read too early said "stale · updated 6 s ago")
    wait(pg, "((document.querySelector(\"[data-fresh='trades'] [data-fresh-text]\") || {}).innerText || '').startsWith('updated ')")
    # the line ticks by itself: a second later it says another age, and still the one its age calls for
    before = pg.locator("[data-fresh='trades'] [data-fresh-text]").inner_text()
    wait(pg, "document.querySelector(\"[data-fresh='trades'] [data-fresh-text]\").textContent !== %s" % json.dumps(before))
    said_by_its_age(pg, "trades")
    first = pg.evaluate("Drishti.refresh.state().trades.fetched")
    wait(pg, "Drishti.refresh.state().trades.fetched > %d" % first)
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
    wait(pg, "Drishti.refresh.state().trades.fetched > %d" % t, seconds=3)
    assert pg.locator(box + " [data-refresh-toggle]").get_attribute("aria-pressed") == "false"
    options = [int(v) for v in select_helpers.values(pg, box + " [data-refresh-every]")]
    assert options[0] == 2 and min(options) == 2                # never faster than the Sutra's interval or the minimum
    pg.select_option(box + " [data-refresh-every]", "10")
    assert pg.evaluate("Drishti.refresh.state().trades.chosen") == 10
    pg.click(box + " [data-refresh-toggle]")
    pg.click(".fresh-bar [data-refresh-all]")
    assert pg.locator(".fresh-bar [data-refresh-said]").inner_text() == "Refresh paused on this page"
    assert pg.locator("[data-view][data-refresh-paused]").count() == 1
    pg.wait_for_timeout(800)                                  # the choices are saved (debounced)
    pg.screenshot(path=str(IMG / "refresh-controls.jpg"), type="jpeg", quality=84, clip={"x": 0, "y": 0, "width": 1500, "height": 700})
    pg.reload()
    wait(pg, "window.Drishti.refresh && document.querySelector('[data-fresh=\"trades\"] [data-refresh-every]')")
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
    wait(pg, "Drishti.refresh.state().trades.fetched > %d" % t)
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


def test_a_panel_with_its_own_source_says_its_sources_freshness_and_the_others_the_pages(stack, browser):
    """A panel with its own source: (its provenance in the panels the page asks for) is stale, amber, by that source's own
    stale-after and names it when the refresh fails; the other panels go by the page's provenance (fresh here) and name the page's source."""
    pg = screen(browser, stack)
    other = next(p for p in refreshing(pg) if p != "trades")
    page_source = pg.locator("[data-view]").get_attribute("data-source") or "The source"
    own = {"source": "limits-store", "kind": "credit-limit", "fetchedAt": "2026-10-23T10:42:00Z", "updatedAt": "2026-10-23T10:40:00Z",
           "staleAfter": "PT2M", "stale": False}

    def own_source(route):
        r = route.fetch()
        body = r.json()
        body["provenance"].update(stale=False, staleAfter=None)
        for p in body.get("panels", []):
            if p["id"] == "trades":
                p["provenance"] = own                       # stale by its own clock: updated long before its two minutes
        route.fulfill(response=r, body=json.dumps(body))
    pg.route("**/panels?*", own_source)
    wait(pg, "document.querySelector('[data-fresh=\"trades\"] [data-fresh-text]').textContent.includes('limits-store')")
    assert pg.locator("[data-fresh='trades']").get_attribute("data-state") == "stale"
    words = pg.locator("[data-fresh='trades'] [data-fresh-text]").inner_text()
    assert words.startswith("stale · limits-store last had new data "), words
    t = fetched(pg, other)
    wait(pg, "Drishti.refresh.state()[%s].fetched > %d" % (json.dumps(other), t))
    assert pg.locator(f"[data-fresh='{other}']").get_attribute("data-state") == "ok"
    assert pg.evaluate("Drishti.refresh.state().trades.prov.source") == "limits-store"
    pg.unroute("**/panels?*")

    pg.route("**/panels?*", lambda route: route.fulfill(status=502, content_type="application/json",
                                                        body=json.dumps({"code": "DRS-1003", "detail": "the source failed"})))
    wait(pg, "document.querySelector('[data-fresh=\"trades\"]').getAttribute('data-state') === 'down'"
             " && document.querySelector('[data-fresh=\"%s\"]').getAttribute('data-state') === 'down'" % other)
    assert pg.locator("[data-fresh='trades'] [data-fresh-text]").inner_text().startswith("limits-store isn’t answering; showing data from ")
    said = pg.locator(f"[data-fresh='{other}'] [data-fresh-text]").inner_text()
    assert said.startswith(page_source + " isn’t answering") and "limits-store" not in said, said
    pg.unroute("**/panels?*")
    pg.close()


def test_a_picked_row_and_what_it_opened_survive_refreshes(stack, browser):
    pg = screen(browser, stack)
    pg.locator("#p-trades tbody tr[data-ev]").nth(0).locator("td").nth(1).click()
    wait(pg, "window.__wire.length === 1 && document.querySelector('tr.wire-kids')")
    driven = pg.evaluate("window.__wire[0].panels")
    t = fetched(pg, "trades")
    wait(pg, "Drishti.refresh.state().trades.fetched > %d" % t)
    t = fetched(pg, "trades")
    wait(pg, "Drishti.refresh.state().trades.fetched > %d" % t)       # two refreshes later
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
