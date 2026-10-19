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

"""<drishti-report> in Chromium (docs/architecture/ELEMENTS.md section 18; Rupaka phase 7), against a HOST application on another origin with a strict
Content-Security-Policy (tools/elements-demo, report.html), the real server (embedding on, security on, the demo source ticking every 200 ms) and the real console
(tests/bi_live_stack.py): the host's report card draws with the live cards ticking, the trader column reads the mask for a user who holds the raw power in the
console, a click on a bar filters the other visuals and tells the host, the host's filters property sets them, the application's `kinds` decide which reports
open, and the report's own phone layout arranges the page at 390 px in the console, in the element, and in the designer's Phone tab.

Ports (the live stack's, moved to this test's): server 18953, console 17953, host 17952. Skipped when Playwright, Chromium or the built server jar is missing, or a
port is in use. Never run with pytest -n (the ports are fixed)."""
import base64
import json
import os
import subprocess
import sys
import time
import urllib.request

import pytest

os.environ.setdefault("DRISHTI_BI_LIVE_SERVER_PORT", "18953")
os.environ.setdefault("DRISHTI_BI_LIVE_CONSOLE_PORT", "17953")
from scratch_ports import worker_port  # noqa: E402

HOST_PORT = worker_port(os.environ.get("DRISHTI_EMBED_REPORT_HOST_PORT", "17952"))

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

import bi_live_stack as S  # noqa: E402
from conftest import CONSOLE  # noqa: E402

ROOT = CONSOLE.parent
HOST = f"http://127.0.0.1:{HOST_PORT}"
APP = "elements-report-demo"
KEY = json.loads((ROOT / "tools/elements-demo/demo-host-key.json").read_text())
VIOLATIONS = "window.__csp = []; document.addEventListener('securitypolicyviolation', e => window.__csp.push(e.violatedDirective + ' ' + e.blockedURI));"
PHONE_ORDER = ["mtm", "trades", "by-desk", "movers", "notional", "pnl"]
MARK = """([host, vis]) => {
  const el = document.querySelector(host).shadowRoot.querySelector('[data-visual="' + vis + '"] .bi-chart');
  const c = echarts.getInstanceByDom(el), o = c.getOption();
  const horizontal = o.yAxis[0].type === 'category';
  const cats = horizontal ? o.yAxis[0].data : o.xAxis[0].data;
  const lay = c.getModel().getSeriesByIndex(0).getData().getItemLayout(0);
  const r = el.getBoundingClientRect();
  return { category: cats[0], x: r.left + lay.x + lay.width / 2, y: r.top + lay.y + lay.height / 2 };
}"""
CARD = "(sel) => Number(document.querySelector(sel).shadowRoot.querySelector('[data-visual=\"trades\"] .bi-card-value').getAttribute('data-value'))"


def _wait(url, seconds=60):
    end = time.time() + seconds
    while time.time() < end:
        try:
            urllib.request.urlopen(url, timeout=2).read()
            return
        except Exception:  # noqa: BLE001 - not up yet
            time.sleep(0.4)
    raise RuntimeError(f"{url} did not come up")


def register_host() -> str:
    """The host application and the users it signs in, at the server; returns the client secret (shown once)."""
    for name, role in (("viewer", "viewer"), ("author", "author")):
        S.admin("POST", "/admin/users", {"username": name, "displayName": name, "roles": [role], "packs": ["trading", "counterparty-risk", "market-risk"],
                                         "password": f"Embed-Demo-Pass-{name}-1!", "mustChangePassword": False})
    enc = lambda h: base64.urlsafe_b64encode(bytes.fromhex(h if len(h) % 2 == 0 else "0" + h)).rstrip(b"=").decode()  # noqa: E731
    jwks = json.dumps({"keys": [{"kty": "RSA", "kid": KEY["kid"], "n": enc(KEY["n"]), "e": enc(KEY["e"])}]})
    status, made = S.admin("POST", "/admin/embed/apps", {"id": APP, "name": "Elements report demo", "origins": [HOST], "scopes": ["embed:report"],
                                                          "kinds": [f"report:{S.REPORT}"], "jwks": jwks, "subjectTypes": ["jwt"],
                                                          "callsPerMinute": 100000, "userCallsPerMinute": 100000})
    assert status == 201, made
    return made["secret"]


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    if os.environ.get("DRISHTI_EMBED_REPORT_REUSE"):               # a stack and a host started by hand (the same ports): for working on the test, never in the drill
        yield None
        return
    for port in (S.SERVER_PORT, S.CONSOLE_PORT, HOST_PORT):
        if S.listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there")
    mp = pytest.MonkeyPatch()
    mp.setenv("DRISHTI_EMBED_ENABLED", "true")
    mp.setenv("DRISHTI_PUBLIC_URL", S.SERVER)
    mp.setenv("DRISHTI_CONSOLE_URL", S.CONSOLE_URL)
    tmp = tmp_path_factory.mktemp("embed-report")
    host = None
    try:
        s = S.LiveStack(tmp).start()
    except FileNotFoundError as e:
        mp.undo()
        pytest.skip(str(e))
    try:
        secret = register_host()
        host = subprocess.Popen([sys.executable, str(ROOT / "tools/elements-demo/server.py"), f"--port={HOST_PORT}", f"--console={S.CONSOLE_URL}", f"--server={S.SERVER}",
                                 f"--secret={secret}", f"--app={APP}"], stdout=open(tmp / "host.log", "ab"), stderr=subprocess.STDOUT)
        _wait(HOST + "/api/me")
        yield s
    finally:
        if host:
            host.terminate()
            host.wait(10)
        s.close()
        mp.undo()


@pytest.fixture(scope="module")
def browser(stack):
    with sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001
            pytest.skip(f"needs Playwright's Chromium: {str(e)[:100]}")
        yield b
        b.close()


def host_page(browser, path="/report", **ctx):
    context = browser.new_context(**{"viewport": {"width": 1200, "height": 900}, **ctx})
    context.add_init_script(VIOLATIONS)
    page = context.new_page()
    page.errors = []
    page.on("pageerror", lambda e: page.errors.append(str(e)))
    page.goto(HOST + path)
    return page


def ready(page, sel="#rep", timeout=60000):
    page.wait_for_selector(f"{sel}[data-ready=yes]", state="attached", timeout=timeout)
    page.wait_for_function("(s) => !document.querySelector(s).shadowRoot.querySelector('.bi-vis-busy')", arg=sel, timeout=timeout)


def console_page(browser, user, path="/bi", **ctx):
    context = browser.new_context(**{"viewport": {"width": 1400, "height": 900}, **ctx})
    page = context.new_page()
    page.errors = []
    page.on("pageerror", lambda e: page.errors.append(str(e)))
    page.goto(S.CONSOLE_URL + "/login?next=" + path)
    page.fill("#lu", user)
    page.fill("#lp", S.PASSWORD)
    page.click("button[type=submit]")
    page.wait_for_url("**" + path.split("?")[0])
    return page


def events(page, name):
    return page.evaluate("(n) => window.__events.filter(e => e.name === n).map(e => ({ id: e.id, detail: e.detail }))", name)


def clean(page):
    assert page.evaluate("window.__csp") == [], "a policy violation in the host page"
    assert page.errors == [], page.errors
    assert json.loads(urllib.request.urlopen(HOST + "/api/csp-reports").read()) == [], "the host's CSP report endpoint received something"


# ---- the host page under a strict policy -------------------------------------------------------------------------------------------------------------

def test_a_host_page_under_a_strict_policy_draws_the_report_live_and_masked(stack, browser):
    page = host_page(browser)
    ready(page)
    rep = page.locator("#rep")
    expect(rep.locator("[data-page]")).to_have_count(4)                                           # Desk, All trades, Pivot, Chart
    expect(rep.locator('[data-visual="trades"] .bi-card-value')).not_to_have_text("")
    assert page.evaluate("document.getElementById('rep').state") in ("live", "static")
    loaded = events(page, "loaded")
    assert loaded and loaded[0]["detail"]["page"] == "desk" and "by-desk" in loaded[0]["detail"]["visuals"]
    # the trader column of the worst-trades table reads the mask whatever the user's roles: `author` holds the raw power, an embed token never does
    before = len([e for e in events(page, "loaded") if e["id"] == "rep"])
    page.click('[data-login="author"]')
    page.wait_for_function("(n) => window.__events.filter(e => e.name === 'loaded' && e.id === 'rep').length > n", arg=before, timeout=60000)
    ready(page)
    cells = rep.locator('[data-visual="movers"] tbody tr td:nth-child(3)').all_inner_texts()
    assert cells and set(cells) == {"•••"}, cells
    masked = events(page, "masked")
    assert masked and any("trader" in c.lower() for c in masked[-1]["detail"]["columns"])          # trade.TRADER_ID (2.3.0 names)
    # the live source ticks: the report redraws by itself and says so
    page.wait_for_function("() => window.__events.filter(e => e.name === 'tick').length >= 2", timeout=30000)
    assert page.evaluate("document.getElementById('rep').getAttribute('data-state')") == "live"
    expect(rep.locator("[data-live-state]")).to_have_attribute("data-live-state", "live")
    # the page tabs work in the element; a 2.1 Perspective grid is Drishti's own grid of the server's rows (2.3.0), so no WebAssembly under the host's policy
    rep.locator('[data-page="grid"]').click()
    expect(rep.locator('[data-type="perspective-grid"] table tbody tr').first).to_be_visible(timeout=20000)
    assert any(e["detail"]["page"] == "grid" for e in events(page, "page-changed"))
    page.evaluate("document.getElementById('rep').selectPage('desk')")
    expect(rep.locator('[data-visual="trades"]')).to_be_visible()
    # the stylesheet and the page are separate worlds
    assert page.evaluate("getComputedStyle(document.body).fontFamily").startswith("Georgia")
    clean(page)
    page.context.close()


def test_the_same_user_in_the_console_sees_the_trader_the_element_masks(stack, browser):
    page = console_page(browser, "rita", f"/bi/r/{S.REPORT}")
    page.wait_for_selector("[data-ready=yes]", state="attached", timeout=30000)
    page.wait_for_selector('[data-visual="movers"] tbody tr')
    cells = page.locator('[data-visual="movers"] tbody tr td:nth-child(3)').all_inner_texts()
    assert cells and "•••" not in cells, "the console shows a user with the raw power the real traders"
    page.context.close()


def test_a_click_on_a_bar_filters_the_other_visuals_and_tells_the_host_and_the_host_can_set_filters(stack, browser):
    page = host_page(browser)
    ready(page)
    rep = page.locator("#rep")
    total = page.evaluate(CARD, "#rep")
    where = page.evaluate(MARK, ["#rep", "by-desk"])
    rep.locator('[data-visual="by-desk"] .bi-chart').scroll_into_view_if_needed()
    where = page.evaluate(MARK, ["#rep", "by-desk"])
    page.mouse.click(where["x"], where["y"])
    page.wait_for_function("() => window.__events.some(e => e.name === 'filter-changed' && e.detail.selections && e.detail.selections.length)", timeout=20000)
    mine = [e for e in events(page, "filter-changed") if e["detail"]["selections"]][-1]["detail"]
    assert mine["selections"][0]["field"] == "trade.DESK" and mine["selections"][0]["values"] == [where["category"]] and mine["source"] == "user"
    expect(rep.locator('[data-chip="selection"]')).to_have_count(1)
    page.wait_for_function("([sel, total]) => Number(document.querySelector(sel).shadowRoot.querySelector('[data-visual=\"trades\"] .bi-card-value').getAttribute('data-value')) < total",
                           arg=["#rep", total], timeout=20000)
    # the host clears them, then sets its own through the property: the element does what a click would
    page.evaluate("document.getElementById('rep').clearFilters()")
    expect(rep.locator('[data-chip="selection"]')).to_have_count(0)
    page.evaluate("(c) => { document.getElementById('rep').filters = { selections: [{ visual: 'by-desk', field: 'trade.DESK', values: [c] }] }; }", where["category"])
    expect(rep.locator('[data-chip="selection"]')).to_have_count(1)
    assert page.evaluate("document.getElementById('rep').filters.selections[0].values[0]") == where["category"]
    assert events(page, "filter-changed")[-1]["detail"]["source"] == "api"
    clean(page)
    page.context.close()


def test_one_visual_without_chrome(stack, browser):
    page = host_page(browser)
    ready(page, "#one")
    one = page.locator("#one")
    expect(one.locator(".bi-vis")).to_have_count(1)
    expect(one.locator('[data-visual="by-desk"] .bi-chart')).to_be_visible()
    expect(one.locator("[data-pages]")).to_have_count(0)
    clean(page)
    page.context.close()


def test_the_applications_kinds_decide_which_reports_open(stack, browser):
    page = host_page(browser)
    ready(page)
    page.evaluate("""() => { const e = document.createElement('drishti-report'); e.id = 'other'; e.setAttribute('report', 'file:report:live-exposure'); document.body.append(e); }""")
    page.wait_for_function("() => document.getElementById('other').getAttribute('data-state') === 'error'", timeout=30000)
    err = [e for e in events(page, "error") if e["id"] == "other"][0]["detail"]
    assert err["code"] == "DRS-8005" and err["fatal"] is True
    expect(page.locator("#other .dv-error")).to_contain_text("DRS-8005")
    page.evaluate("document.getElementById('other').remove()")
    assert page.locator("#rep [data-visual]").count() > 0, "the permitted report is unaffected"
    page.context.close()


# ---- phone layouts ---------------------------------------------------------------------------------------------------------------------------------

def test_the_report_at_390_pixels_uses_its_phone_layout_in_the_element_and_in_the_console(stack, browser):
    wide = host_page(browser)
    ready(wide)
    assert wide.locator("#rep [data-grid]").get_attribute("data-phone") == "auto"
    assert wide.locator("#rep [data-visual]").count() == 10
    wide.set_viewport_size({"width": 390, "height": 844})                                           # the element's width follows: it swaps to the phone layout by itself
    wide.wait_for_function("() => document.querySelector('#rep').shadowRoot.querySelector('[data-grid]').getAttribute('data-phone') === 'layout'", timeout=20000)
    ready(wide)
    assert wide.evaluate("[...document.querySelector('#rep').shadowRoot.querySelectorAll('.bi-vis')].map(v => v.dataset.visual)") == PHONE_ORDER
    height = wide.evaluate("document.querySelector('#rep').shadowRoot.querySelector('[data-visual=\"by-desk\"]').getBoundingClientRect().height")
    assert abs(height - 320) <= 2, height                                                              # 5 rows of 56 px and 4 gaps of 10 px
    assert wide.evaluate("document.documentElement.scrollWidth") <= 391, "the page scrolls sideways"
    wide.context.close()

    phone = console_page(browser, "rita", f"/bi/r/{S.REPORT}", viewport={"width": 390, "height": 844}, is_mobile=True, has_touch=True)
    phone.wait_for_selector("[data-ready=yes]", state="attached", timeout=30000)
    phone.wait_for_selector('.bi-rgrid[data-phone="layout"] .bi-vis')
    assert phone.evaluate("[...document.querySelectorAll('.bi-rgrid .bi-vis')].map(v => v.dataset.visual)") == PHONE_ORDER
    phone.goto(S.CONSOLE_URL + f"/bi/r/{S.REPORT}/grid")                                           # a page with no phone layout keeps the automatic column
    phone.wait_for_selector(".bi-rgrid .bi-vis")
    assert phone.get_attribute(".bi-rgrid", "data-phone") == "auto"
    phone.context.close()


def test_the_designers_phone_tab_arranges_a_page_with_a_390_pixel_preview(stack, browser):
    body = (ROOT / "config/bi/reports/live-trading-desk.report.yaml").read_text()
    body = body.split("layouts:")[0] + "pages:" + body.split("\npages:", 1)[1]                      # the shipped report without its phone layout: a draft to arrange
    body = body.replace("report: live-trading-desk", "report: phone-draft")
    status, made = S.api("POST", "/bi/workspaces/me/items", "wes", ["live-writer"], {"kind": "report", "title": "Phone draft", "description": "", "body": body})
    assert status in (200, 201), made
    draft = made["id"]
    page = console_page(browser, "wes", f"/bi/design/{draft}")
    page.wait_for_selector("[data-design-root][data-ready=yes]", state="attached", timeout=30000)
    page.click('[data-tab="phone"]')
    rows = page.locator("[data-phone-visual]")
    expect(rows).to_have_count(10)
    assert page.locator('[data-phone-preview] [data-phone-card]').count() == 10
    assert page.evaluate("document.querySelector('[data-phone-preview]').getBoundingClientRect().width") <= 390
    # hide three visuals; move one up with the keyboard-reachable button; drag one; give one a height
    for vis in ("rolling", "losses", "latest"):
        page.check(f'[data-phone-hide="{vis}"]')
    expect(page.locator("[data-phone-preview] [data-phone-card]")).to_have_count(7)
    page.click('[data-phone-up="mtm"]')
    page.click('[data-phone-up="mtm"]')
    page.drag_and_drop('[data-phone-visual="trades"]', '[data-phone-visual="by-desk"]')
    page.fill('[data-phone-rows="by-desk"]', "5")
    page.press('[data-phone-rows="by-desk"]', "Tab")
    order = page.evaluate("[...document.querySelectorAll('[data-phone-visual][data-hidden=no]')].map(r => r.dataset.phoneVisual)")
    assert order[0] == "mtm" and set(order) == {"mtm", "trades", "by-desk", "movers", "notional", "pnl", "last-hour"}, order
    card = page.locator('[data-phone-card="by-desk"]')
    assert card.get_attribute("data-rows") == "5" and abs(card.evaluate("e => e.getBoundingClientRect().height") - 320) <= 2
    page.click('[data-tab="yaml"]')
    page.wait_for_function("() => /layouts:/.test(document.querySelector('.CodeMirror') ? document.querySelector('.CodeMirror').CodeMirror.getValue() : document.querySelector('[data-yaml]').value)", timeout=10000)
    text = page.evaluate("document.querySelector('.CodeMirror') ? document.querySelector('.CodeMirror').CodeMirror.getValue() : document.querySelector('[data-yaml]').value")
    assert "phone:" in text and "hide: [rolling, losses, latest]" in text.replace("  ", " ") or "hide:" in text
    page.click("[data-save]")
    page.wait_for_function("() => document.querySelector('[data-state]').textContent === 'Saved'", timeout=20000)
    # back to automatic removes the entry
    page.click('[data-tab="phone"]')
    page.click("[data-phone-reset]")
    expect(page.locator("[data-phone-hide]:checked")).to_have_count(0)
    page.click('[data-tab="yaml"]')
    page.wait_for_function("() => !/layouts:/.test(document.querySelector('.CodeMirror') ? document.querySelector('.CodeMirror').CodeMirror.getValue() : document.querySelector('[data-yaml]').value)", timeout=10000)
    page.context.close()
    # the saved draft, at 390 px in the console, follows the arrangement the tab wrote
    phone = console_page(browser, "wes", f"/bi/r/{draft}", viewport={"width": 390, "height": 844}, is_mobile=True, has_touch=True)
    phone.wait_for_selector("[data-ready=yes]", state="attached", timeout=30000)
    phone.wait_for_selector('.bi-rgrid[data-phone="layout"] .bi-vis')
    shown = phone.evaluate("[...document.querySelectorAll('.bi-rgrid .bi-vis')].map(v => v.dataset.visual)")
    assert shown[0] == "mtm" and "rolling" not in shown and "losses" not in shown and "latest" not in shown, shown
    phone.context.close()
