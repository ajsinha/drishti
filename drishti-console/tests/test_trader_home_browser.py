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

"""The trader home in a browser (SHARED_SCREENS.md section 4; drishti-risk's TraderHome on drishti-grid): the console over the stand-in server of
trading_fake.py (TraderHomeBackend), the person mapped to A. Shah's books. The page shows the server's numbers (each equal to the stand-in's
answer), every button and link does what it says (Refresh, Since this morning, Since my last visit, a book, a VaR figure's Explain, Why it
moved, the approvals tile, View as), the server's ticks arrive through the browser's one live channel from a manual feed and the changed
figures update and flash, a person sees only their books, and the page fits a phone without sideways scrolling and asks no other host.
Skipped when Playwright or its Chromium is missing."""
import threading
import time

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE  # noqa: E402
from trading_fake import TraderHomeBackend  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture
from test_page_widths_browser import AUDIT  # noqa: E402
from scratch_ports import worker_port  # noqa: E402

PORT = worker_port(17990)


@pytest.fixture(scope="module")
def home():
    import uvicorn

    from core import market_landing, trading_landing
    from core.app import create_app
    from core.auth import Identity
    from core.config import load_settings

    trading_landing.forget()
    market_landing.forget()
    app = create_app(load_settings(CONSOLE / "config"))
    backend = app.state.backend = TraderHomeBackend()
    app.state.editions.learn({"product": "Drishti Trading", "edition": {"name": "trading", "title": "Drishti Trading"}})
    user = app.state.auth.default.user
    people = app.state.editions.instance.spec["trading"]["people"]
    people[user] = {"trader": "TRDR-ASHAH"}
    people["kim"] = {"trader": "TRDR-KMOREAU"}
    store: dict = {}

    async def settings(ident=None):
        return dict(store.get(ident.user if ident else "", {}))

    async def patch_settings(body, ident=None):
        store.setdefault(ident.user, {}).update({k: v for k, v in body.items() if v is not None})
        return store[ident.user]

    backend.settings, backend.patch_settings = settings, patch_settings
    default_current = app.state.auth.current

    async def current(cookie, b):
        if cookie == "kim":                                     # a second person, told apart by the cookie
            return Identity(user="kim", display="K. Moreau", desk="", roles=("viewer",))
        return await default_current(cookie, b)

    app.state.auth.current = current
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=PORT, log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started, "the console did not start"
    yield f"http://127.0.0.1:{PORT}", backend, store, app
    server.should_exit = True
    thread.join(10)
    trading_landing.forget()
    market_landing.forget()


def _page(ctx):
    page = ctx.new_page()
    page.outside = []
    page.on("request", lambda r: page.outside.append(r.url) if not r.url.startswith((f"http://127.0.0.1:{PORT}", "data:", "blob:")) else None)
    return page


def _text(page, sel):
    return page.locator(sel).first.inner_text().replace("\n", " ").strip()


def _open(page, url):
    page.goto(url + "/home/trader", wait_until="load")
    page.wait_for_function("() => Number(document.querySelector('[data-trader-home]')?.getAttribute('data-th-painted')) >= 1", timeout=20000)


def _cell(page, book, fig):
    return page.locator(f"[data-th-books-grid] .dg-row:has([data-th-book='{book}']) [data-th-fig='{fig}']")


def _explained(backend, scope):
    """The server's Explain was asked for this scope (the drawer's request may land after the drawer shows)."""
    deadline = time.monotonic() + 10
    while not any(e.get("scope") == scope for e in getattr(backend, "explained", [])) and time.monotonic() < deadline:
        time.sleep(0.05)
    assert any(e.get("scope") == scope for e in getattr(backend, "explained", [])), getattr(backend, "explained", None)


def _reset(backend, store):
    from core import market_landing, trading_landing
    trading_landing.forget()
    market_landing.forget()
    backend.book_var.clear()
    from trading_fake import BOOK_VAR
    backend.book_var.update(BOOK_VAR)
    store.clear()


def test_the_home_shows_the_servers_numbers_and_every_button_works(browser, home):
    url, backend, store, _app = home
    _reset(backend, store)
    ctx = browser.new_context(viewport={"width": 1280, "height": 1000})
    page = _page(ctx)
    try:
        _open(page, url)
        api = page.evaluate("() => fetch('/api/home/trading/home').then(r => r.json())")
        assert api["headline"]["var"] == 8_000_000 and api["headline"]["pnl"] == 480_000
        assert _text(page, "[data-th-tile=pnl] [data-th-v]") == "+480k"
        assert _text(page, "[data-th-tile=var] [data-th-v]") == "8.00m"
        assert _text(page, "[data-th-tile=biggest] [data-th-v]") == "USD SOFR swaps 10Y"
        assert _text(page, "[data-th-tile=breaches] [data-th-v]") == "0" and "1 near (BOOK-RATES-1)" in _text(page, "[data-th-tile=breaches] [data-th-s]")
        assert _text(page, "[data-th-tile=approvals] [data-th-v]") == "0"
        # my books: the person's two books, each figure the stand-in's own answer
        page.wait_for_function("() => document.querySelectorAll('[data-th-books-grid] [data-th-book]').length === 2")
        assert sorted(page.locator("[data-th-books-grid] [data-th-book]").all_inner_texts()) == ["BOOK-RATES-1", "BOOK-RATES-2"]
        assert _cell(page, "BOOK-RATES-1", "var").inner_text() == "6.12m" and _cell(page, "BOOK-RATES-1", "limit").inner_text() == "7.40m"
        assert _cell(page, "BOOK-RATES-1", "limitUse").inner_text() == "83%" and _cell(page, "BOOK-RATES-2", "limitUse").inner_text() == "60%"
        assert page.locator("[data-th-books-grid] .th-bar.th-near").count() == 1
        # markets, what changed, the feed
        assert page.locator("[data-th-markets-grid]").get_attribute("data-th-markets") == "4"
        assert "1 new · 1 amended · 1 cancelled" in _text(page, "[data-th-counts]")
        assert page.locator("[data-th-feed] [data-th-item=trade]").count() == 3 and page.locator("[data-th-feed] [data-th-item=limit]").count() == 1
        # Refresh asks again
        painted = int(page.get_attribute("[data-trader-home]", "data-th-painted"))
        page.click("[data-th-refresh]")
        page.wait_for_function(f"() => Number(document.querySelector('[data-trader-home]').getAttribute('data-th-painted')) > {painted}")
        # Since my last visit: the visit was kept after the first paint; set it to midday to see the filter
        user = _app.state.auth.default.user
        deadline = time.monotonic() + 10
        while not store.get(user, {}).get("traderHomeVisit") and time.monotonic() < deadline:   # the page kept this visit after its first paint
            time.sleep(0.05)
        visit = store[user]["traderHomeVisit"]                                 # now: every trade of the day was before it (the filter: test_trader_home.py)
        page.click("[data-th-since=visit]")
        page.wait_for_function("() => document.querySelector('[data-th-since=visit]').getAttribute('aria-pressed') === 'true'")
        page.wait_for_function("() => document.querySelector('[data-th-counts]').textContent.includes('0 cancelled')")
        assert "0 new · 0 amended" in _text(page, "[data-th-counts]") and "your visit at " + visit.replace("T", " ")[:16] in _text(page, "[data-th-counts]")
        page.click("[data-th-since=morning]")
        page.wait_for_function("() => document.querySelector('[data-th-counts]').textContent.includes('1 cancelled')")
        # a VaR figure opens the one Explain, with the book's scope; Why it moved opens it on its Why tab
        _cell(page, "BOOK-RATES-2", "var").click()
        page.locator(".dx-drawer").wait_for(state="visible")
        _explained(backend, "BOOK:BOOK-RATES-2")
        page.keyboard.press("Escape")
        page.click("[data-th-tile=var] [data-th-v]")
        page.wait_for_function("() => document.querySelector('.dx-drawer') && !document.querySelector('.dx-drawer').hidden")
        _explained(backend, "BOOK:BOOK-RATES-1 + BOOK:BOOK-RATES-2")
        page.keyboard.press("Escape")
        page.click("[data-th-why]")
        page.wait_for_function("() => document.querySelector('[data-dx-tab=why]')?.getAttribute('aria-selected') === 'true'")
        page.keyboard.press("Escape")
        assert not page.outside, page.outside
        # the approvals tile opens the Approvals queue; a book opens it position by position (its VaR)
        assert page.get_attribute("[data-th-tile=approvals] [data-th-v]", "href") == "/approvals"
        page.click("[data-th-books-grid] [data-th-book='BOOK-RATES-1']")
        page.wait_for_url("**/var?scope=BOOK*")
        assert "BOOK%3ABOOK-RATES-1" in page.url or "BOOK:BOOK-RATES-1" in page.url
        assert store[_app.state.auth.default.user].get("traderHomeVisit")      # the visit was kept for "since my last visit"
    finally:
        ctx.close()


def test_the_servers_ticks_update_the_figures_live(browser, home):
    url, backend, store, _app = home
    _reset(backend, store)
    ctx = browser.new_context(viewport={"width": 1280, "height": 1000})
    page = _page(ctx)
    try:
        _open(page, url)
        page.wait_for_function("() => document.querySelector('[data-th-live]').getAttribute('data-th-live') === 'live'", timeout=20000)
        assert _cell(page, "BOOK-RATES-2", "var").inner_text() == "3.00m"
        backend.book_var["BOOK-RATES-2"] = (5_500_000, 5_000_000)               # BOOK-RATES-2 now over its limit
        deadline = time.monotonic() + 10
        while backend.tick() == 0 and time.monotonic() < deadline:            # the manual feed: one tick on the open stream
            time.sleep(0.1)
        page.wait_for_function("() => [...document.querySelectorAll(\"[data-th-fig='var']\")].some(e => e.textContent === '5.50m')", timeout=15000)
        cell = page.locator("[data-th-books-grid] .dg-row:has([data-th-book='BOOK-RATES-2']) .dg-cell:has([data-th-fig='var'])")
        assert "dg-flash-up" in (cell.get_attribute("class") or "")
        assert _cell(page, "BOOK-RATES-2", "limitUse").inner_text() == "110%"
        page.wait_for_function("() => document.querySelector('[data-th-tile=breaches] [data-th-v]').textContent === '1'")
        new = page.locator("[data-th-feed] [data-th-id='limit:BOOK-RATES-2:breach']")
        assert new.count() == 1 and new.get_attribute("data-th-new") == ""
        assert backend.live_paths and all(p == "/trading/home/live" for p in backend.live_paths)
        assert not page.outside, page.outside
    finally:
        ctx.close()


def test_a_person_sees_only_their_books(browser, home):
    url, backend, store, app = home
    _reset(backend, store)
    ctx = browser.new_context(viewport={"width": 1280, "height": 1000})
    ctx.add_cookies([{"name": app.state.auth.cookie, "value": "kim", "url": url}])
    page = _page(ctx)
    try:
        _open(page, url)
        page.wait_for_function("() => document.querySelectorAll('[data-th-books-grid] [data-th-book]').length === 1")
        assert page.locator("[data-th-books-grid] [data-th-book]").all_inner_texts() == ["BOOK-RATES-3"]
        assert "BOOK-RATES-1" not in page.locator("[data-trader-home]").inner_text()
        assert _text(page, "[data-th-tile=breaches] [data-th-v]") == "1"
    finally:
        ctx.close()


@pytest.mark.parametrize("width", [390, 1280])
def test_the_home_fits_and_passes_the_width_audit(browser, home, width):
    url, backend, store, _app = home
    _reset(backend, store)
    ctx = browser.new_context(viewport={"width": width, "height": 900}, is_mobile=width < 600, has_touch=width < 600)
    page = _page(ctx)
    try:
        _open(page, url)
        page.wait_for_timeout(200)
        assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
        assert not page.evaluate(AUDIT)
        assert not page.outside, page.outside
    finally:
        ctx.close()
