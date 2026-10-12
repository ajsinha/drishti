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
counterparty-risk packs, the demo source ticking every 200 ms: 750 trades, 360 of them live, and 66 netting sets; never more): the sample report "Live trading desk" opens and
redraws in place as the trades tick (the same chart, new numbers), says when the last update arrived, pauses and resumes, says when the connection drops and recovers;
the Perspective grid and pivot load under the narrow policy with no violation, take masked Arrow, and apply streaming rows by key; when WebAssembly is blocked the grid
is the plain table; the mask reads the same in every visual for a reader without raw; the sample board "Desk wall" colours tiles by threshold, fills the screen in kiosk
mode and turns its pages by itself; and a phone (390 px) shows reports and boards with finger-sized controls.

Skipped when Playwright, Chromium or the built server jar is missing, or a scratch port is in use. Never run with pytest -n (the ports are fixed)."""
import importlib.util
import re
from pathlib import Path

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

import bi_live_stack as S  # noqa: E402
from bi_live_stack import BOARD, CONSOLE_URL, EXPOSURE, PASSWORD, REPORT  # noqa: E402

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
    assert card(page, "trades") == 750
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
    assert page.inner_text('[data-visual="latest"] tbody tr:first-child td:first-child').startswith(("MX-", "CLY-", "END-", "IMG-", "BBG-", "WSS-"))
    clean(page)


def test_coalescing_and_backpressure_keep_the_rounds_bounded(stack, browser):
    page = sign_in(browser)
    open_report(page)
    wait_state(page, "live")
    before = page.evaluate(ROUND)
    page.wait_for_timeout(4000)
    after = page.evaluate(ROUND)
    ticks_a_second = (after - before) / 4
    assert 0 < ticks_a_second <= 4.5, f"{ticks_a_second} redraw rounds a second: at most one every 250 ms (bi.live.min_interval_ms), however fast the source ticks"
    # the floor only says rounds happen at all: a loaded machine ticks the source slower; the ceiling is the coalescing
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
    page.wait_for_timeout(600)
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
    assert card(page, "trades") == 750
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


# ---- Perspective --------------------------------------------------------------------------------------------------------------

def test_the_perspective_grid_and_pivot_load_under_the_policy_and_apply_streaming_rows(stack, browser):
    page = sign_in(browser)
    resp = page.goto(f"{CONSOLE_URL}/bi/r/{REPORT}/grid")
    csp = resp.headers["content-security-policy"]
    assert "'wasm-unsafe-eval'" in csp and "'unsafe-eval'" not in csp and "'unsafe-inline'" not in csp and "worker-src 'self'" in csp
    page.wait_for_selector("[data-ready=yes]", state="attached", timeout=30000)
    page.wait_for_selector(".bi-psp[data-psp=ready]", state="attached", timeout=60000)
    size = page.evaluate("document.querySelector('perspective-viewer').getTable().then(t => t.size())")
    assert size == 750
    assert page.evaluate("document.querySelector('perspective-viewer').save().then(c => c.plugin)") == "Datagrid"
    assert "Live" in page.inner_text(".bi-live-state [data-live-text]")
    # the rows that changed arrive by key: the table keeps its 750 rows and says how many changed
    page.wait_for_function("() => Number(document.querySelector('.bi-psp').getAttribute('data-psp-changed')) > 0", timeout=20000)
    assert page.get_attribute(".bi-psp", "data-psp-rows") == "750"
    assert "Perspective" in page.inner_text('[data-visual="all-trades"] .bi-vis-foot') and "live" in page.inner_text('[data-visual="all-trades"] .bi-vis-foot')
    # the grid shows the trade's own columns, as the table names them
    cols = page.evaluate("document.querySelector('perspective-viewer').save().then(c => c.columns)")
    assert cols[:3] == ["tradeId", "desk", "book"] and "trader" in cols
    clean(page)
    page.goto(f"{CONSOLE_URL}/bi/r/{REPORT}/pivot")
    page.wait_for_selector(".bi-psp[data-psp=ready]", state="attached", timeout=60000)
    config = page.evaluate("document.querySelector('perspective-viewer').save()")
    assert config["group_by"] == ["desk"] and config["split_by"] == ["currency"] and config["aggregates"] == {"mtm": "sum", "notional": "sum"}
    clean(page)


def test_the_mask_reads_the_same_in_the_grid_and_the_cards_for_a_reader_without_raw(stack, browser):
    page = sign_in(browser, "ann")
    open_report(page, REPORT, "/grid")
    page.wait_for_selector(".bi-psp[data-psp=ready]", state="attached", timeout=60000)
    traders = page.evaluate("document.querySelector('perspective-viewer').getTable().then(t => t.view({ columns: ['trader'] })).then(v => v.to_columns()).then(c => [...new Set(c.trader)])")
    assert traders == ["•••"]
    page.wait_for_function("() => Number(document.querySelector('.bi-psp').getAttribute('data-psp-changed')) >= 0 && document.querySelector('.bi-psp').hasAttribute('data-psp-changed')", timeout=20000)
    assert page.evaluate("document.querySelector('perspective-viewer').getTable().then(t => t.view({ columns: ['trader'] })).then(v => v.to_columns()).then(c => [...new Set(c.trader)])") == ["•••"], "still masked after updates"
    open_report(page, REPORT, "")
    page.wait_for_selector('[data-visual="movers"] tbody tr')
    assert set(page.locator('[data-visual="movers"] tbody tr td:nth-child(3)').all_inner_texts()) == {"•••"}
    clean(page)


def test_the_grid_is_the_plain_table_when_webassembly_cannot_start(stack, browser):
    page = sign_in(browser)
    page.route("**/perspective-viewer.wasm", lambda route: route.abort())
    open_report(page, REPORT, "/grid")
    page.wait_for_selector(".bi-psp[data-psp=fallback]", state="attached", timeout=60000)
    note = page.inner_text("[data-psp-note]")
    assert "Perspective could not start in this tab" in note and "plain table" in page.inner_text('[data-visual="all-trades"] .bi-vis-foot')
    assert page.locator(".bi-psp-plain tbody tr").count() >= 100
    assert not page.errors or all("wasm" in e.lower() or "perspective" in e.lower() or "fetch" in e.lower() for e in page.errors), page.errors
    rounds(page, 1)                                                                 # and it follows the live table the same way
    page.context.close()


def test_two_tabs_of_one_browser_both_draw_the_grid(stack, browser):
    page = sign_in(browser)
    open_report(page, REPORT, "/grid")
    page.wait_for_selector(".bi-psp[data-psp=ready], .bi-psp[data-psp=fallback]", state="attached", timeout=60000)
    page.wait_for_timeout(6000)                                                     # RUPAKA_POC.md section 8: a second tab that opens five seconds or more after the first
    second = page.context.new_page()
    second.goto(f"{CONSOLE_URL}/bi/r/{REPORT}/grid")
    second.wait_for_selector("[data-ready=yes]", state="attached", timeout=30000)
    second.wait_for_selector(".bi-psp[data-psp=ready], .bi-psp[data-psp=fallback]", state="attached", timeout=60000)
    assert second.get_attribute(".bi-psp", "data-psp") == "ready", "the second tab trap of Perspective 3.8.0 (RUPAKA_POC.md section 8) is back: the grid fell back to the plain table"
    assert second.evaluate("document.querySelector('perspective-viewer').getTable().then(t => t.size())") == 750
    page.context.close()


def test_the_style_hash_list_covers_the_real_pages(stack, browser):
    """A library update that writes another style makes this fail with the hash to add (tools/perspective-style-hashes.py prints the whole list)."""
    spec = importlib.util.spec_from_file_location("psp_hashes", Path(__file__).resolve().parents[2] / "tools" / "perspective-style-hashes.py")
    tool = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(tool)
    measured = tool.measure(CONSOLE_URL, "rita", PASSWORD, browser=browser)
    missing = [h for h in measured if h not in tool.STYLE_HASHES]
    assert not missing, f"Perspective {tool.PERSPECTIVE_VERSION} writes styles that core/perspective_csp.py STYLE_HASHES does not allow: {missing}"
    assert measured, "no style was measured: the probe saw nothing"


def test_the_designer_shows_what_a_perspective_visual_will_be(stack, browser):
    page = sign_in(browser)
    open_report(page)
    text = page.evaluate("""() => { const host = document.createElement('div'); document.body.appendChild(host);
      window.drishtiBi.visuals.draw(host, { id: 'g', type: 'perspective-grid', wells: { columns: ['trade.desk', 'trade.mtm'] } }, { interactive: false }, { columns: [], rows: [] }); return host.textContent; }""")
    assert "Perspective grid" in text and "Shown in the report viewer" in text and "trade.desk" in text
    page.context.close()


# ---- boards -------------------------------------------------------------------------------------------------------------------

def test_the_desk_wall_colours_tiles_fills_the_screen_and_turns_its_pages(stack, browser):
    page = sign_in(browser)
    page.clock.install()
    resp = page.goto(f"{CONSOLE_URL}/bi/b/{BOARD}")
    assert resp.headers["content-security-policy"].count("wasm") == 0                      # no Perspective tile: the strict policy
    page.wait_for_selector("[data-ready=yes]", state="attached", timeout=30000)
    page.wait_for_function("() => document.querySelectorAll('.bi-vis').length === 7 && !document.querySelector('.bi-vis-busy')", timeout=30000)
    assert page.inner_text("[data-title]") == "Desk wall" and page.locator("[data-pages] a").all_inner_texts() == ["Trading desk", "Counterparty exposure"]
    tile = lambda t: page.get_attribute(f'[data-tile="{t}"]', "data-alert")                   # noqa: E731
    assert tile("net-mtm") == "warn" and tile("losses") == "warn" and tile("trades") == ""
    assert "warning" in page.inner_text('[data-tile="net-mtm"] .bi-vis-foot')                 # in words, not only in colour
    assert page.evaluate("getComputedStyle(document.querySelector('[data-tile=\"net-mtm\"]')).boxShadow") != "none"
    wait_state(page, "live")
    rounds(page, 2)
    assert float(page.get_attribute('[data-tile="trades"] .bi-card-value', "data-value")) == 750
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
    page.goto(f"{CONSOLE_URL}/bi/b/{BOARD}")
    expect(page.locator("body")).to_contain_text("BI")                                      # nobi holds no BI power: the page is the refusal, not the wall
    assert page.query_selector("[data-tile]") is None
    page.context.close()


def test_the_report_and_the_board_open_from_the_catalogue_item_page(stack, browser):
    page = sign_in(browser)
    page.goto(f"{CONSOLE_URL}/bi/i/{BOARD}")
    page.wait_for_selector("[data-open-board]:not([hidden])", timeout=20000)
    assert page.get_attribute("[data-open-board]", "href") == f"/bi/b/{BOARD}"
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
    for path in (f"/bi/r/{REPORT}", f"/bi/r/{REPORT}/grid", f"/bi/r/{EXPOSURE}", f"/bi/b/{BOARD}"):
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
