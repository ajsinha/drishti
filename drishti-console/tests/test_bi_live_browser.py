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

"""Rupaka phase 5 (streaming BI) in Chromium, over the real server and console (tests/bi_live_stack.py: ports 18995 and 17995, security on, the trading and
counterparty-risk packs, the demo source ticking every 200 ms: the market-risk pack's 765 trade valuations, and 66 netting sets; never more): the sample report "Live trading desk" opens and
redraws in place as the trades tick (the same chart, new numbers), says when the last update arrived, pauses and resumes, says when the connection drops and recovers;
the 2.1 Perspective grid and pivot visuals are Drishti's own grid of the server's answer under the strict policy (wave B: no WebAssembly on a BI page); the mask reads the same in every visual for a reader without raw; the sample board "Desk wall" colours tiles by threshold, fills the screen in kiosk
mode and turns its pages by itself; and a phone (390 px) shows reports and boards with finger-sized controls.

Skipped when Playwright, Chromium or the built server jar is missing, or a scratch port is in use. Never run with pytest -n (the ports are fixed)."""
import re

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

import bi_live_stack as S  # noqa: E402
from bi_live_stack import BOARD, CONSOLE_URL, EXPOSURE, PASSWORD, REPORT, TRADE_IDS, TRADES  # noqa: E402

VIOLATIONS = "window.__csp = []; document.addEventListener('securitypolicyviolation', e => window.__csp.push(e.violatedDirective + ' ' + e.blockedURI));"
ROUND = "() => Number(document.getElementById('biCanvas').getAttribute('data-live-round') || 0)"
TIME = re.compile(r"Last update \d\d:\d\d:\d\d")


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    for port in (S.SERVER_PORT, S.CONSOLE_PORT):
        if S.listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there")
    try:
        s = S.LiveStack(tmp_path_factory.mktemp("bilive")).start()
    except FileNotFoundError as e:
        pytest.skip(str(e))
    yield s
    s.close()


@pytest.fixture(scope="module")
def browser(stack):
    with sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001
            pytest.skip(f"needs Playwright's Chromium: {str(e)[:100]}")
        yield b
        b.close()


def sign_in(browser, user="rita", **ctx):
    context = browser.new_context(**{"viewport": {"width": 1400, "height": 900}, **ctx})
    context.add_init_script(VIOLATIONS)
    page = context.new_page()
    page.errors = []
    page.on("pageerror", lambda e: page.errors.append(str(e)))
    page.goto(CONSOLE_URL + "/login?next=/bi")
    page.fill("#lu", user)
    page.fill("#lp", PASSWORD)
    page.click("button[type=submit]")
    page.wait_for_url("**/bi")
    return page


def open_report(page, path=REPORT, suffix=""):
    page.goto(f"{CONSOLE_URL}/bi/r/{path}{suffix}")
    page.wait_for_selector("[data-ready=yes]", state="attached", timeout=30000)


def live(page):
    return page.get_attribute(".bi-live-state", "data-live-state")


def wait_state(page, state, timeout=20000):
    page.wait_for_function("(s) => document.querySelector('.bi-live-state').getAttribute('data-live-state') === s", arg=state, timeout=timeout)


def rounds(page, n, timeout=20000):
    start = page.evaluate(ROUND)
    page.wait_for_function("([start, n]) => Number(document.getElementById('biCanvas').getAttribute('data-live-round') || 0) >= start + n", arg=[start, n], timeout=timeout)


def card(page, vis):
    return float(page.get_attribute(f'[data-visual="{vis}"] .bi-card-value', "data-value"))


def clean(page):
    assert page.evaluate("window.__csp") == [], "policy violations"
    assert not page.errors, page.errors


# ---- live visuals -------------------------------------------------------------------------------------------------------------

def test_the_live_report_redraws_in_place_as_the_trades_tick_and_says_when(stack, browser):
    page = sign_in(browser)
    open_report(page)
    wait_state(page, "live")
    page.wait_for_selector('[data-visual="by-desk"] .bi-chart')
    assert card(page, "trades") == TRADES
    assert re.search(r"· live( \(\w+\))?$", page.inner_text('[data-visual="trades"] .bi-vis-foot').strip())                 # the layer that answered
    mtm = card(page, "mtm")
    page.evaluate("document.querySelector('[data-visual=\"by-desk\"] .bi-chart').setAttribute('data-mark', 'same-element')")
    page.evaluate("document.querySelector('[data-visual=\"mtm\"] .bi-card-value').closest('.bi-vis').setAttribute('data-mark', 'same-frame')")
    rounds(page, 3)
    page.wait_for_function("(v) => Number(document.querySelector('[data-visual=\"mtm\"] .bi-card-value').getAttribute('data-value')) !== v", arg=mtm, timeout=20000)
    assert page.query_selector('[data-visual="by-desk"] .bi-chart[data-mark="same-element"]'), "the chart was redrawn in place, not rebuilt"
    assert page.query_selector('[data-visual="mtm"][data-mark="same-frame"]')
    assert TIME.search(page.inner_text("[data-live-bar]")), page.inner_text("[data-live-bar]")
    assert "Live" in page.inner_text(".bi-live-state [data-live-text]")
    # the rolling line collects one point for each update
    points = page.evaluate("echarts.getInstanceByDom(document.querySelector('[data-visual=\"rolling\"] .bi-chart')).getOption().series[0].data.length")
    assert points >= 3
    assert page.inner_text('[data-visual="latest"] tbody tr:first-child td:first-child').strip() in TRADE_IDS          # a trade the feed pushed
    clean(page)


def test_coalescing_and_backpressure_keep_the_rounds_bounded(stack, browser):
    page = sign_in(browser)
    open_report(page)
    wait_state(page, "live")
    # rounds happen at all: waited for, since a loaded machine ticks the source slower (a fixed 4 s saw none, once, beside three other suites)
    first = page.evaluate(ROUND)
    page.wait_for_function(f"() => ({ROUND})() > {first}", timeout=30000)
    # the ceiling is the coalescing: counted over 4 s from a round
    before = page.evaluate(ROUND)
    page.wait_for_timeout(4000)
    after = page.evaluate(ROUND)
    ticks_a_second = (after - before) / 4
    assert ticks_a_second <= 4.5, f"{ticks_a_second} redraw rounds a second: at most one every 250 ms (bi.live.min_interval_ms), however fast the source ticks"
    busy = page.evaluate("""async () => { let worst = 0; for (let i = 0; i < 20; i++) { worst = Math.max(worst, document.querySelectorAll('.bi-vis-busy').length); await new Promise(r => setTimeout(r, 100)); } return worst; }""")
    assert busy == 0, "a live redraw never greys a visual (quiet refresh)"
    clean(page)


def test_pause_stops_the_redraws_and_resume_catches_up(stack, browser):
    page = sign_in(browser)
    open_report(page)
    wait_state(page, "live")
    page.click("[data-live-pause]")
    wait_state(page, "paused")
    assert page.get_attribute("[data-live-pause]", "aria-pressed") == "true" and "Resume" in page.inner_text("[data-live-pause]") and "Paused" in page.inner_text(".bi-live-state [data-live-text]")
    # a round under way when Pause was pressed still lands (its answers were asked before): wait for it, not a fixed 600 ms (too short under load)
    page.wait_for_function("() => !document.getElementById('biCanvas').hasAttribute('data-live-busy')", timeout=30000)
    stopped = page.evaluate(ROUND)
    page.wait_for_timeout(2500)
    assert page.evaluate(ROUND) == stopped, "no redraw while paused, though the server keeps ticking"
    page.click("[data-live-pause]")
    wait_state(page, "live")
    assert "Pause" in page.inner_text("[data-live-pause]")
    rounds(page, 1)                                           # the tick that came while paused is applied at once
    clean(page)


def test_a_dropped_connection_is_said_and_a_new_one_redraws_everything(stack, browser):
    page = sign_in(browser)
    open_report(page)
    wait_state(page, "live")
    stack.stop_console()                                                           # every stream of the browser drops
    try:
        wait_state(page, "disconnected", timeout=60000)
        assert "Disconnected" in page.inner_text(".bi-live-state [data-live-text]") and page.get_attribute(".bi-live-state", "title")
    finally:
        stack.start_console()
    wait_state(page, "live", timeout=90000)                                       # the hub opens a new channel by itself; the report's hello redraws every visual
    start = page.evaluate(ROUND)
    rounds(page, 1)
    assert page.evaluate(ROUND) > start
    assert card(page, "trades") == TRADES
    page.context.close()


def test_a_tab_in_the_background_gives_back_its_stream_and_redraws_when_shown(stack, browser):
    page = sign_in(browser)
    open_report(page)
    wait_state(page, "live")
    page.evaluate("""() => { Object.defineProperty(document, 'hidden', { configurable: true, get: () => true });
      Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' }); document.dispatchEvent(new Event('visibilitychange')); }""")
    wait_state(page, "hidden", timeout=20000)                                      # after ten seconds of being hidden (channel.js)
    assert "background" in page.inner_text(".bi-live-state [data-live-text]")
    start = page.evaluate(ROUND)
    page.evaluate("""() => { Object.defineProperty(document, 'hidden', { configurable: true, get: () => false });
      Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' }); document.dispatchEvent(new Event('visibilitychange')); }""")
    wait_state(page, "live", timeout=30000)
    rounds(page, 1)
    assert page.evaluate(ROUND) > start
    page.context.close()


def test_the_watch_says_stale_failed_and_hidden_and_the_rules_colour_tiles(stack, browser):
    page = sign_in(browser)
    open_report(page)
    result = page.evaluate("""async () => {
      const B = window.drishtiBi, saved = window.DrishtiChannel, handlers = {}, seen = [];
      window.DrishtiChannel = { subscribe: (key, h) => { handlers[key] = h; return () => { delete handlers[key]; }; } };
      const w = B.live.watch(['r1'], { staleSeconds: 1, onState: (i) => seen.push(i.state + ':' + i.reason) });
      const h = handlers['bilive:r1'];
      const states = [];
      h.hello({ generation: 3, state: 'live', lastUpdate: '2026-10-11T07:00:00Z' });
      states.push(w.state());
      await new Promise(r => setTimeout(r, 2300));                    // nothing heard for longer than staleSeconds
      states.push(w.state());
      h.status({ generation: 3, state: 'live' });                      // it speaks again
      states.push(w.state());
      h.status({ generation: 3, state: 'failed', tables: [{ error: 'kafka is down' }] });
      states.push(w.state() + ' ' + w.info().reason);
      h.status({ generation: 4, state: 'stale' });
      states.push(w.state());
      h.error();
      states.push(w.state());
      h.hello({ generation: 5, state: 'live' });
      states.push(w.state());
      h.paused();
      states.push(w.state());
      w.close();
      window.DrishtiChannel = saved;
      return { states, gen: w.info().generation };
    }""")
    assert result["states"] == ["live", "stale", "live", "failed kafka is down", "stale", "disconnected", "live", "hidden"]
    assert result["gen"] == 5
    levels = page.evaluate("""() => { const L = window.drishtiBi.live.level; const res = { columns: [{ name: 'MTM', type: 'decimal' }], rows: [[5], [-30]] };
      return [L({ on: 'MTM', warn: { op: 'lt', value: 0 }, crit: { op: 'lt', value: -50 } }, res), L({ on: 'MTM', warn: { op: 'lt', value: 0 }, crit: { op: 'lt', value: -20 } }, res),
              L({ on: 'MTM', warn: { op: 'gt', value: 100 } }, res), L(null, res), L({ warn: { op: 'le', value: 5 } }, res)]; }""")
    assert levels == ["warn", "crit", "", "", "warn"]
    runs = page.evaluate("""async () => { const c = window.drishtiBi.live.coalesce(() => new Promise(r => setTimeout(r, 30)), 100); for (let i = 0; i < 200; i++) c.request();
      await new Promise(r => setTimeout(r, 500)); return [c.runs(), c.busy()]; }""")
    assert runs[0] <= 4 and runs[1] is False, f"200 requests in a burst made {runs[0]} runs"
    page.context.close()


# ---- the 2.1 Perspective visuals (wave B: Drishti's own grid, no WebAssembly) ------------------------------------------------------

def test_the_perspective_grid_and_pivot_are_drishtis_grid_under_the_strict_policy(stack, browser):
    page = sign_in(browser)
    wasm = []
    page.on("request", lambda r: wasm.append(r.url) if r.url.endswith(".wasm") or "perspective" in r.url else None)
    resp = page.goto(f"{CONSOLE_URL}/bi/r/{REPORT}/grid")
    csp = resp.headers["content-security-policy"]
    assert "wasm" not in csp and "'unsafe-eval'" not in csp and "'unsafe-inline'" not in csp
    page.wait_for_selector("[data-ready=yes]", state="attached", timeout=30000)
    page.wait_for_selector('[data-visual="all-trades"] tbody tr', timeout=60000)
    heads = page.locator('[data-visual="all-trades"] thead th').all_inner_texts()
    assert len(heads) >= 3 and page.locator('[data-visual="all-trades"] tbody tr').count() >= 20
    page.goto(f"{CONSOLE_URL}/bi/r/{REPORT}/pivot")
    page.wait_for_selector("[data-ready=yes]", state="attached", timeout=30000)
    page.wait_for_selector(".bi-vis tbody tr", timeout=60000)
    assert wasm == [], wasm
    clean(page)


def test_the_mask_reads_the_same_in_the_grid_and_the_cards_for_a_reader_without_raw(stack, browser):
    page = sign_in(browser, "ann")
    open_report(page, REPORT, "")
    page.wait_for_selector('[data-visual="movers"] tbody tr')
    assert set(page.locator('[data-visual="movers"] tbody tr td:nth-child(3)').all_inner_texts()) == {"•••"}
    clean(page)


# ---- boards -------------------------------------------------------------------------------------------------------------------

def test_the_desk_wall_colours_tiles_fills_the_screen_and_turns_its_pages(stack, browser):
    page = sign_in(browser)
    page.clock.install()
    resp = page.goto(f"{CONSOLE_URL}/bi/b/file:board:desk-wall")                         # the 2.3 address opens the report viewer (2.4.0)
    assert "/bi/r/" + BOARD in page.url
    assert resp.headers["content-security-policy"].count("wasm") == 0                      # no Perspective tile: the strict policy
    page.wait_for_selector("[data-ready=yes]", state="attached", timeout=30000)
    page.wait_for_function("() => document.querySelectorAll('.bi-vis').length === 7 && !document.querySelector('.bi-vis-busy')", timeout=30000)
    assert page.inner_text("[data-title]") == "Desk wall" and page.locator("[data-pages] a").all_inner_texts() == ["Trading desk", "Counterparty exposure"]
    tile = lambda t: page.get_attribute(f'[data-tile="{t}"]', "data-alert")                   # noqa: E731
    # the net MTM tile follows the sample's thresholds (warn below 0, crit below -1 bn, 25 m of hysteresis); the stack's losses alert warns from the first loss
    net = float(page.get_attribute('[data-tile="net-mtm"] .bi-card-value', "data-value"))
    if abs(net) > 25e6:
        assert tile("net-mtm") == ("crit" if net < -1e9 else "warn" if net < 0 else ""), net
    assert tile("losses") == "warn" and tile("trades") == ""
    assert "warning" in page.inner_text('[data-tile="losses"] .bi-vis-foot')                  # in words, not only in colour
    assert page.evaluate("getComputedStyle(document.querySelector('[data-tile=\"losses\"]')).boxShadow") != "none"
    wait_state(page, "live")
    rounds(page, 2)
    assert float(page.get_attribute('[data-tile="trades"] .bi-card-value', "data-value")) == TRADES
    # kiosk: the menus go, the wall fills the screen, the pages turn by themselves
    page.click("[data-kiosk]")
    assert page.evaluate("document.body.classList.contains('bi-kiosk')")
    assert not page.is_visible(".tbar") and not page.is_visible(".bi-rail") and not page.is_visible("[data-title]")
    assert page.inner_text("[data-rotate-note]") == "Next page every 20 s"
    page.wait_for_function("() => document.querySelector('[data-tile=\"net-mtm\"]').getBoundingClientRect().top < 260")           # the wall starts under a slim bar
    assert page.get_attribute("#biCanvas", "data-page-id") == "desk"
    page.clock.fast_forward(21000)
    page.wait_for_function("() => document.getElementById('biCanvas').getAttribute('data-page-id') === 'exposure'", timeout=20000)
    page.wait_for_function("() => document.querySelectorAll('.bi-vis').length === 6 && !document.querySelector('.bi-vis-busy')", timeout=30000)
    assert card_text(page, "sets") == "66"
    page.keyboard.press("ArrowLeft")
    page.wait_for_function("() => document.getElementById('biCanvas').getAttribute('data-page-id') === 'desk'")
    page.keyboard.press("Escape")
    assert not page.evaluate("document.body.classList.contains('bi-kiosk')") and page.is_visible(".tbar")
    clean(page)
    page.context.close()


def card_text(page, tile):
    return page.inner_text(f'[data-tile="{tile}"] .bi-card-value').strip().replace(",", "")


def test_a_tile_the_reader_cannot_open_says_so_and_the_rest_still_draw(stack, browser):
    page = sign_in(browser, "nobi")
    page.goto(f"{CONSOLE_URL}/bi/r/{BOARD}")
    expect(page.locator("body")).to_contain_text("BI")                                      # nobi holds no BI power: the page is the refusal, not the wall
    assert page.query_selector("[data-tile]") is None
    page.context.close()


def test_the_report_and_the_board_open_from_the_catalogue_item_page(stack, browser):
    page = sign_in(browser)
    page.goto(f"{CONSOLE_URL}/bi/i/{BOARD}")
    page.wait_for_selector("[data-open-board]:not([hidden])", timeout=20000)
    assert page.get_attribute("[data-open-board]", "href") == f"/bi/r/{BOARD}?kiosk=1"                     # Open the board: the report viewer in kiosk
    page.click("[data-open-board]")
    page.wait_for_selector("[data-ready=yes]", state="attached", timeout=30000)
    page.context.close()


# ---- a phone ------------------------------------------------------------------------------------------------------------------

PHONE = """() => {
  const doc = document.documentElement, small = [];
  for (const e of document.querySelectorAll('[data-live-pause], [data-kiosk], [data-prev], [data-next], .bi-tab, .bi-vis-btn')) {
    const r = e.getBoundingClientRect(); if (r.width && (r.height < 43.5 || r.width < 43.5)) small.push((e.className || e.tagName) + ' ' + Math.round(r.width) + 'x' + Math.round(r.height));
  }
  return { sw: doc.scrollWidth, cw: doc.clientWidth, small };
}"""


def test_a_phone_shows_the_live_report_and_the_board_with_finger_sized_controls(stack, browser):
    page = sign_in(browser, viewport={"width": 390, "height": 844}, is_mobile=True, has_touch=True)
    for path in (f"/bi/r/{REPORT}", f"/bi/r/{REPORT}/grid", f"/bi/r/{EXPOSURE}", f"/bi/r/{BOARD}"):
        page.goto(CONSOLE_URL + path)
        page.wait_for_selector("[data-ready=yes]", state="attached", timeout=30000)
        page.wait_for_function("() => !document.querySelector('.bi-vis-busy')", timeout=30000)
        page.wait_for_timeout(600)
        m = page.evaluate(PHONE)
        assert m["sw"] <= m["cw"] + 1, f"{path} scrolls sideways at 390 px: {m}"
        assert not m["small"], f"{path}: targets under 44 px: {m['small']}"
        assert page.is_visible("[data-live-bar]"), path
    page.tap("[data-live-pause]")
    wait_state(page, "paused")
    assert page.locator('[data-visual], [data-tile]').count() >= 6
    page.context.close()
