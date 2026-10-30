"""The console's own entity page drawn by the client-side renderer (ui.render: client; docs/architecture/CLIENT_RENDERER.md section 16)
against the same page drawn by the server's macros (ui.render: server), in a real browser on a stand-in server (scratch port).

Every view is opened twice, ``?render=server`` and ``?render=client``, and compared as the phase 1 parity suite compares (the accessible
tree, the visible text, every cell, link and tone, every chart's series), at 390 and 1280 px, with no script error, no CSP violation and no
sideways scroll. Then each behaviour the console adds to a view is used in both modes and must end the same: the per-column filter funnels
(and their address), Chart any table, the drill-down's address and Back, Customize, the download menu, About this page (drawn by the
library from JSON on a client page), the Pivot tab's grid, the nested-pivot grid, a control, and a live tick. Skipped when Playwright or
its Chromium is missing."""
import copy
import json
import re

import pytest

sync_api = pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

import render_cases as cases  # noqa: E402
from conftest import FIXTURES  # noqa: E402
from table_chart_backend import ChartBackend, serve  # noqa: E402
from test_render_parity_browser import INTENTIONAL, explain, normalised  # noqa: E402,F401
import render_harness as H  # noqa: E402

WIDTHS = (390, 1280)
MODES = ("server", "client")
EDGES = cases.edge_cases()


class RenderBackend(ChartBackend):
    """The chart tests' stand-in server, plus the edge-case ViewModels of VIEWMODEL.md (kind ``case``) and an explanation for every view."""

    async def view(self, kind, id_, user, controls=None, panels=None):
        if kind == "case" and id_ in EDGES:
            vm = copy.deepcopy(EDGES[id_])
            vm["provenance"] = dict(vm.get("provenance") or {}, live=False)
            return vm
        return await super().view(kind, id_, user, controls, panels)

    async def explain(self, kind, id_, user, generation=None, locale=None, accept_language=None):
        ex = json.loads((FIXTURES / "explain_trade_IRS-48213.json").read_text())
        ex["ref"] = {"kind": kind, "id": id_}
        if generation and generation < ex["generation"]:
            ex["newer"] = True
        return ex


@pytest.fixture(scope="module")
def console():
    url, backend, stop = serve(RenderBackend())
    yield url
    stop()


@pytest.fixture(scope="module")
def browser():
    with sync_api.sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001 - no browser installed: skip
            pytest.skip(f"needs Playwright's Chromium: {str(e).splitlines()[0]}")
        yield b
        b.close()


CSP = "window.__csp = []; document.addEventListener('securitypolicyviolation', (e) => window.__csp.push(e.violatedDirective + ' ' + e.blockedURI));"


def open_page(browser, url, width=1280):
    ctx = browser.new_context(viewport={"width": width, "height": 900})
    page = ctx.new_page()
    errors, reqs = [], []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.on("request", lambda r: reqs.append(r.url))
    page.add_init_script(CSP)
    page.goto(url, wait_until="load")
    page.wait_for_selector(".view .pnl", state="attached", timeout=20000)
    settle(page)
    page.__errors = errors
    page.__reqs = reqs
    return ctx, page


def settle(page):
    """The page's charts and map shapes drawn (a console page keeps its live channel open, so the network is never idle)."""
    page.wait_for_function("() => !document.querySelector('.chart:not(.pnl-empty), .xchart:not(.pnl-empty)') || [...document.querySelectorAll('.chart, .xchart')].every((el) => el.classList.contains('pnl-empty') || (window.echarts && window.echarts.getInstanceByDom(el)))", timeout=15000)
    page.wait_for_timeout(400)
    page.evaluate("() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)))")


def read(page) -> dict:
    out = page.evaluate(H.READ_JS)
    out["aria"] = page.locator(".view[data-view]").first.aria_snapshot()
    return out


def addr(url, mode, extra=""):
    return url + ("&" if "?" in url else "?") + "render=" + mode + (("&" + extra) if extra else "")


def both(browser, url, width=1280):
    """{mode: read} for one view drawn both ways."""
    got = {}
    for mode in MODES:
        ctx, page = open_page(browser, addr(url, mode), width)
        try:
            assert page.locator("[data-render-host]").count() == (1 if mode == "client" else 0), f"{mode}: the wrong renderer drew {url}"
            got[mode] = read(page)
            got[mode]["errors"] = page.__errors
        finally:
            ctx.close()
    return got


VIEWS = ["trade/IRS-48213", "trade/FXS-20931", "trade/CFT-77120", "trade/IRS-47102", "netting-set/NS-NORTH-01", "trade/CHART-1"] + [f"case/{k}" for k in EDGES]


@pytest.mark.parametrize("width", WIDTHS)
@pytest.mark.parametrize("view", VIEWS)
def test_the_page_is_the_same_drawn_by_the_server_or_the_library(browser, console, view, width):
    got = both(browser, f"{console}/v/{view}", width)
    a, b = normalised(got["server"]), normalised(got["client"])
    diffs = {k: (a[k], b[k]) for k in a if a[k] != b[k]}
    assert not got["client"]["errors"], got["client"]["errors"]
    assert not got["client"]["csp"], got["client"]["csp"]
    assert got["client"]["overflow"] <= max(1, got["server"]["overflow"]), f"the client page scrolls sideways by {got['client']['overflow']} px"
    assert not diffs, f"{view} at {width}px differs:\n" + explain(diffs)


# ---- the behaviours: each used in both modes, the same outcome -----------------------------------------------------------------------
def each_mode(browser, url, width=1280):
    for mode in MODES:
        ctx, page = open_page(browser, addr(url, mode), width)
        try:
            yield mode, page
            assert not page.__errors, f"{mode}: {page.__errors}"
        finally:
            ctx.close()


def test_the_column_funnels_filter_the_same_rows_and_keep_the_address(browser, console):
    seen = {}
    for mode, page in each_mode(browser, f"{console}/v/trade/CHART-1"):
        page.locator("#p-flows th .cf-btn").nth(1).click()                          # the Currency column's funnel
        menu = page.locator(".cf-menu")
        menu.wait_for(state="visible", timeout=5000)
        menu.locator("label", has_text="Select all").locator("input").uncheck()
        menu.locator("label", has_text="EUR").locator("input").check()
        menu.locator("button", has_text="OK").click()
        page.wait_for_timeout(200)
        rows = page.locator("#p-flows tbody tr:visible")
        seen[mode] = (rows.count(), sorted(set(page.locator("#p-flows tbody tr:visible td:nth-child(2)").all_inner_texts())), "f.flows." in page.url)
    assert seen["server"] == seen["client"] and seen["client"][1] == ["EUR"] and seen["client"][2], seen


def test_chart_any_table_opens_the_same_chart(browser, console):
    seen = {}
    for mode, page in each_mode(browser, f"{console}/v/trade/CHART-1"):
        page.locator("#p-flows button[data-table-chart]").first.click()
        page.wait_for_selector("dialog.tc-dlg[open] .tc-canvas svg path", state="attached", timeout=15000)
        seen[mode] = page.evaluate("() => ({ sum: document.querySelector('.tc-sum').textContent, label: document.querySelector('.tc-canvas').getAttribute('aria-label') })")
    assert seen["server"] == seen["client"] and seen["client"]["sum"], seen


def test_a_drill_keeps_its_level_in_the_address_and_back_goes_up(browser, console):
    seen = {}
    for mode, page in each_mode(browser, f"{console}/v/case/more-shapes"):
        drill = page.locator("[data-drill]").first
        pid = drill.get_attribute("data-drill")
        drill.locator("a[data-dr-open]").first.click()
        page.wait_for_function(f"() => location.search.includes('dr.{pid}=')", timeout=5000)
        level = page.evaluate("() => location.search")
        crumbs = drill.locator("[data-dr-crumbs]").inner_text()
        page.go_back()
        page.wait_for_function(f"() => !location.search.includes('dr.{pid}=')", timeout=5000)
        top = drill.locator(".dr-lv.on").get_attribute("data-dr-path")
        seen[mode] = (re.sub(r"render=\w+&?", "", level), crumbs, top)
    assert seen["server"] == seen["client"] and seen["client"][2] == "", seen


def test_the_download_menu_names_the_same_files(browser, console):
    seen = {}
    for mode, page in each_mode(browser, f"{console}/v/trade/IRS-48213"):
        seen[mode] = page.evaluate("() => [...document.querySelectorAll('[data-export-panel]')].map((a) => [a.closest('.pnl').id, a.textContent.trim(), new URL(a.href).pathname, a.hidden])")
    assert seen["server"] == seen["client"] and seen["client"] and not any(x[3] for x in seen["client"]), seen


def test_about_this_page_says_the_same_and_is_drawn_from_json_on_a_client_page(browser, console):
    seen, asked = {}, {}
    for mode, page in each_mode(browser, f"{console}/v/trade/IRS-48213"):
        page.locator("[data-about-open]").first.click()
        page.wait_for_selector("#aboutDrawer .about-card", timeout=10000)
        page.wait_for_timeout(200)
        seen[mode] = [re.sub(r"\s+", " ", x).strip() for x in page.locator("#aboutDrawer [data-about-body]").inner_text().split("\n") if x.strip()]
        asked[mode] = [u for u in page.__reqs if "/about" in u]
    assert seen["server"] == seen["client"], "\n".join(f"{x!r} | {y!r}" for x, y in zip(seen["server"], seen["client"]) if x != y)
    assert any("format=json" in u for u in asked["client"]) and not any("format=json" in u for u in asked["server"]), asked


def test_the_pivot_tab_builds_the_same_grid(browser, console):
    seen = {}
    for mode, page in each_mode(browser, f"{console}/v/netting-set/NS-NORTH-01"):
        page.locator("#p-trades [data-pv-tab='pivot']").click()
        page.wait_for_selector("#p-trades .pv-host table", timeout=10000)
        page.wait_for_timeout(200)
        seen[mode] = page.locator("#p-trades .pv-host table").first.inner_text()
    assert seen["server"] == seen["client"] and "Interest rate swap" in seen["client"], seen


def test_a_nested_pivot_is_drawn_as_the_same_grid(browser, console):
    seen = {}
    for mode, page in each_mode(browser, f"{console}/v/case/showcase"):
        host = page.locator("#p-grid .pv-tree-host")
        seen[mode] = (host.locator("table").first.get_attribute("class"), host.inner_text())
    assert seen["server"] == seen["client"], seen


def test_customize_and_layout_mode_find_the_drawn_panels(browser, console):
    seen = {}
    for mode, page in each_mode(browser, f"{console}/v/trade/IRS-48213"):
        btn = page.locator("[data-layout-open]")
        if not btn.count() or btn.is_disabled():
            pytest.skip("layout mode is not offered by this stand-in")
        btn.click()
        page.wait_for_selector(".view.lay-on, [data-layout-on], .lay-bar", timeout=5000)
        seen[mode] = page.evaluate("() => [...document.querySelectorAll('.pnl[data-panel]')].map((p) => [p.id, !!p.querySelector('.lay-grip, [data-lay-handle], .lay-tools')])")
    assert seen["server"] == seen["client"] and all(x[1] for x in seen["client"]), seen


def test_the_client_page_embeds_its_view_model_and_needs_no_second_request(browser, console):
    ctx = browser.new_context()
    page = ctx.new_page()
    asked = []
    page.on("request", lambda r: asked.append(r.url))
    try:
        page.goto(addr(f"{console}/v/trade/IRS-48213", "client"), wait_until="load")
        page.wait_for_selector(".view .pnl")
        boot = json.loads(page.locator("#drishti-vm").text_content())
        assert boot["vm"]["ref"] == {"kind": "trade", "id": "IRS-48213"} and boot["vm"]["panels"]
        assert not [u for u in asked if "/api/view/" in u], "first paint must not fetch the view again"
        assert page.locator("[data-render-chrome]").count() == 0, "the chrome is placed and its holder gone"
        assert page.locator(".vhead > nav[data-crumbs]").count() == 1 and page.locator(".vtitle [data-share]").count() == 1
    finally:
        ctx.close()


# ---- live and controls: the library's subscribe on the page's channel; panels a control swaps drawn from their JSON ----------------
@pytest.fixture(scope="module")
def controls_console():
    from test_controls_browser import VIEW, ControlsBackend
    url, backend, stop = serve(ControlsBackend())
    yield url + VIEW, backend
    stop()


def test_a_live_tick_and_a_control_move_the_same_panels_either_way(browser, controls_console):
    url, _backend = controls_console
    seen = {}
    for mode, page in each_mode(browser, url):
        page.wait_for_function("() => /#([3-9]|\\d\\d)/.test((document.querySelector('.strip dd') || {}).textContent)", timeout=20000)   # strip patches
        page.wait_for_function("() => / t\\d+/.test((document.querySelector('#p-schedule tbody') || {}).textContent)", timeout=20000)      # panel patches
        assert page.locator("#p-schedule [data-ctl='legs']").count() == 1, f"{mode}: the header's control stays through a tick"
        page.evaluate("document.querySelector('#p-other').setAttribute('data-mark', 'kept')")
        page.locator("[data-ctl='legs'] summary").click()
        n = page.evaluate("(window.__drishtiControls || []).length")
        page.locator("[data-ctl='legs'] input[value='2']").uncheck()
        page.wait_for_function("(n) => (window.__drishtiControls || []).length > n", arg=n, timeout=10000)
        page.wait_for_timeout(600)
        legs = page.evaluate("() => [...document.querySelectorAll('#p-schedule tbody tr')].map((r) => r.cells[0].textContent.trim())")
        kept = page.evaluate("document.querySelector('#p-other').getAttribute('data-mark')")
        drawn = page.evaluate("() => !!document.querySelector('#p-schedule').__drishti")       # drawn by the library from its JSON
        seen[mode] = (sorted(set(legs)), kept, "ctl.legs=1" in page.url, drawn)
    assert seen["server"][:3] == seen["client"][:3] == (["1"], "kept", True), seen
    assert seen["client"][3] and not seen["server"][3], "the client page draws a swapped or ticked panel from its JSON"
