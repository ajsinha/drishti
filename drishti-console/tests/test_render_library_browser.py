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

"""The client-side renderer's behaviour (docs/architecture/CLIENT_RENDERER.md): formats, tones and the DOM rules as units; a hostile
ViewModel; a streamed (GenUI) screen that ends equal to a one-shot render; live patches and the stream; in-place navigation with its
back stack and the navigate event; sorting the way the console sorts; drill-downs, tree rows and tabs by keyboard; 44 px targets on a
phone; the playground. Every page runs under the console's CSP on one made-up origin; no request may leave it and window.open is never
called in place."""
import copy
import json
import math

import pytest

import render_cases as cases
import render_harness as H

sync_api = pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
M = f"/static/render/{H.render_dist.VERSION}/m/"


@pytest.fixture(scope="module")
def browser():
    with sync_api.sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001
            pytest.skip(f"needs Playwright's Chromium: {str(e).splitlines()[0]}")
        yield b
        b.close()


@pytest.fixture(scope="module")
def site():
    s = H.Site()
    for name, vm in {**cases.edge_cases(), **cases.fixture_views()}.items():
        s.add_vm(name, vm)
    return s


@pytest.fixture
def lab(browser, site):
    page = browser.new_page(viewport={"width": 1280, "height": 900})
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.route("**/*", site.handle)
    before = len(site.requests)
    page.goto(H.ORIGIN + "/render-test/lab.html")
    page.wait_for_function("() => window.__lab === true")
    yield page
    try:
        assert errors == [], errors
        assert page.evaluate("() => window.__csp") == []
        assert [u for u in site.requests[before:] if not u.startswith(H.ORIGIN + "/")] == []
    finally:
        page.close()


def render(page, name, opts=None):
    page.evaluate("async ([n, o]) => { window.h = window.R.render(document.getElementById('host'), await window.vm(n), o); await window.h.ready; }", [name, opts or {}])
    H.settle(page)


def view(page):
    return page.locator("#host .view")


# ---- units: formats, tones and the DOM rules ---------------------------------------------------------------------------------------
NUMBERS = [74.0, 73.2, 0.1, 1e16, 1e17, 1.5e-5, 1e-4, 123456.789, -0.5, 2.0 ** 53, 1 / 3]
HALVES = [(0.5, 0), (1.5, 0), (2.5, 0), (-2.5, 0), (2.675, 2), (0.125, 2), (36.45, 1), (33.35, 1)]


def test_formats_follow_python_and_jinja(lab):
    got = lab.evaluate("""async ([nums, halves]) => {
      const F = await import('%sformat.js');
      return { floats: nums.map(F.pyFloat), rounds: halves.map(([x, n]) => F.pyRound(x, n)), share: [F.share(1, 3), F.share(1, 2), F.share(-2, 8)],
        compact: [1750000, -1.2e9, 250000, 12345, 3.14159, NaN].map(F.compact), heat: [F.heat(-2, -2, 7), F.heat(7, -2, 7), F.heat(2.5, -2, 7), F.heat(1, 1, 1)],
        tones: [F.toneClass({tone: 'neg'}), F.toneClass({}), F.knownTone('pos'), F.knownTone('sparkly')], cap: F.capitalize('not active (OFF)') };
    }""" % M, [NUMBERS, HALVES])
    assert got["floats"] == [repr(x) if not math.isinf(x) else "inf" for x in NUMBERS]
    assert got["rounds"] == [round(x, n) for x, n in HALVES]
    assert got["share"] == ["33.3", "50.0", "25.0"]
    assert got["compact"] == ["1.75m", "−1.2bn", "250k", "12.3k", "3.14", ""]
    assert got["heat"] == [0, 9, round(4.5 / 9 * 9), 5]
    assert got["tones"] == [" t-neg", "", True, False]
    assert got["cap"] == "Not active (off)"


def test_the_dom_rules_refuse_markup_handlers_styles_and_script_urls(lab):
    got = lab.evaluate("""async () => {
      const D = await import('%sdom.js');
      const urls = ['javascript:alert(1)', ' JaVaScRiPt:x', '\\u0001javascript:x', 'data:text/html,x', 'vbscript:x', '//evil.example/x', '/v/trade/X', '?dr.p=1', '#x', 'https://www.sec.gov/x', 'http://a/b'];
      const el = D.h('span', { onclick: 'alert(1)', style: 'color:red', title: 't', 'data-x': '1', 'aria-label': 'a', href: 'javascript:alert(1)', srcdoc: 'x' }, '<img src=x onerror=alert(1)>');
      return { urls: urls.map((u) => D.safeUrl(u)), names: ['onclick', 'ONLOAD', 'style', 'srcdoc', 'src', 'href', 'data-ev', 'aria-pressed', 'class', 'formaction'].map(D.attrAllowed),
        attrs: [...el.attributes].map((a) => a.name).sort(), children: el.childNodes.length, kind: el.firstChild.nodeType, imgs: el.querySelectorAll('img').length };
    }""" % M)
    assert got["urls"] == [None, None, None, None, None, None, "/v/trade/X", "?dr.p=1", "#x", "https://www.sec.gov/x", "http://a/b"]
    assert got["names"] == [False, False, False, False, False, True, True, True, True, False]
    assert got["attrs"] == ["aria-label", "data-x", "title"]
    assert got["children"] == 1 and got["kind"] == 3 and got["imgs"] == 0


def test_sorting_reads_values_the_way_the_console_does(lab):
    texts = ["1.5m", "−30,205,543", "250k", "4.25%", "2026-10-02", "abc", "−1.2bn", "12bp", "", "Zed"]
    got = lab.evaluate("""async (texts) => {
      const B = await import('%sbehave.js');
      return texts.slice().sort((a, b) => B.compareValues(B.sortValue(a), B.sortValue(b)));
    }""" % M, texts)
    assert got == ["−1.2bn", "−30,205,543", "4.25%", "12bp", "250k", "1.5m", "2026-10-02", "", "abc", "zed".capitalize()]


def test_a_hostile_view_model_draws_text_only(lab, site):
    vm = cases.showcase()
    evil = '<img src=x onerror="window.__pwned=1"><script>window.__pwned=1</script>'
    vm["title"]["id"] = evil
    vm["strip"][0]["text"] = evil
    vm["panels"][0]["data"]["fields"][0] = {"label": evil, "text": "javascript:window.__pwned=1", "link": {"kind": "javascript:alert(1)//", "id": "x\"><b>"}}
    vm["panels"].append({"id": "x\" onmouseover=\"window.__pwned=1", "kind": "markdown", "title": evil, "area": "main", "inferred": False, "empty": False, "data": {"text": evil}})
    site.add_vm("hostile", vm)
    render(lab, "hostile")
    got = lab.evaluate("""() => { const r = document.getElementById('host').shadowRoot;
      return { scripts: r.querySelectorAll('script, img, iframe').length, pwned: !!window.__pwned, hrefs: [...r.querySelectorAll('a[href]')].map((a) => a.getAttribute('href')).filter((h) => /^javascript/i.test(h)),
        handlers: [...r.querySelectorAll('*')].some((e) => [...e.attributes].some((a) => /^on/i.test(a.name))), title: r.querySelector('h1').textContent }; }""")
    assert got == {"scripts": 0, "pwned": False, "hrefs": [], "handlers": False, "title": evil}
    # the only style attributes are the CSSOM widths the renderer sets itself (bars, gauges, grid cells), never from data
    assert lab.evaluate("() => [...document.getElementById('host').shadowRoot.querySelectorAll('[style]')].every((e) => /^(width|--sg-po|grid|height|left|top)/.test(e.getAttribute('style').trim()) || e.closest('.chart, .xchart'))")


# ---- GenUI: a streamed screen ends as a one-shot render -----------------------------------------------------------------------
@pytest.mark.parametrize("name", ["showcase", "grid-areas", "states"])
def test_a_streamed_view_model_ends_equal_to_a_one_shot_render(browser, site, name):
    vm = cases.edge_cases()[name]
    stubs = [{k: p[k] for k in ("id", "kind", "title", "area", "span", "height") if k in p} for p in vm["panels"][:-2]]
    skeleton = {**copy.deepcopy(vm), "panels": stubs}
    late = vm["panels"][-2:]
    messages = [{"type": "skeleton", "view": skeleton}]
    for p in vm["panels"][:-2][::-1]:                                      # bound in any order: here the last first
        messages.append({"type": "panel-replace", "panel": p})
    for i, p in enumerate(late):                                           # panels the skeleton did not announce
        messages.append({"type": "panel-add", "panel": p, "after": (vm["panels"][-3] if i == 0 else late[0])["id"]})
    messages.append({"type": "done", "provenance": vm["provenance"]})
    site.add_vm(f"stream-{name}", vm)
    site.pages[f"/render-test/vm/stream-{name}-messages.json"] = json.dumps(messages)
    reads = []
    for mode in ("once", "stream"):
        page = browser.new_page(viewport={"width": 1280, "height": 900})
        page.route("**/*", site.handle)
        page.goto(H.ORIGIN + "/render-test/lab.html")
        page.wait_for_function("() => window.__lab === true")
        if mode == "once":
            render(page, f"stream-{name}", {"tables": False})
        else:
            busy = page.evaluate("""async (n) => {
              const ms = await window.vm(n + '-messages');
              async function* gen() { for (const m of ms) { await new Promise((r) => setTimeout(r, 5)); yield m; } }
              window.h = window.R.render(document.getElementById('host'), gen(), { tables: false });
              await new Promise((r) => setTimeout(r, 1));
              const first = document.getElementById('host').shadowRoot.querySelector('.view');
              const was = first ? first.getAttribute('aria-busy') : 'none';
              await window.h.ready;
              return [was, document.getElementById('host').shadowRoot.querySelector('.view').getAttribute('aria-busy')];
            }""", f"stream-{name}")
            assert busy[1] is None, busy
            H.settle(page)
            assert any(e[0] == "ready" for e in page.evaluate("() => window.__events"))
        reads.append(H.read(page))
        page.close()
    a, b = reads
    for k in ("aria", "lines", "cells", "links", "tones", "charts"):
        assert a[k] == b[k], f"{name}: {k} differs after streaming"


# ---- live: patches and the stream ----------------------------------------------------------------------------------------------
def test_patches_change_the_strip_a_panel_and_the_banners_in_place(lab):
    render(lab, "showcase")
    lab.evaluate("() => { window.keep = document.getElementById('host').shadowRoot.querySelector('#p-terms'); }")
    sc = cases.showcase()
    metric = next(p for p in sc["panels"] if p["id"] == "headline")
    metric["data"]["value"]["text"] = "−1,300,000"
    metric["data"]["value"]["tone"] = "neg"
    frame = {"seq": 1, "generation": 2, "patches": [
        {"op": "strip", "index": 3, "cell": {"label": "MTM (USD)", "text": "−1,300,000", "tone": "neg", "emphasis": True}},
        {"op": "panel", "panel": metric},
        {"op": "provenance", "provenance": {**sc["provenance"], "generation": 2, "stale": True, "source": "ledger", "updatedAt": "2026-10-30T09:00:00Z", "staleAfter": "PT5M"}},
        {"op": "sparkle", "x": 1}]}
    changed = lab.evaluate("(f) => window.h.patch(f)", frame)
    assert changed == {"strip": [3], "panels": ["headline"]}
    r = lab.evaluate("""() => { const s = document.getElementById('host').shadowRoot;
      return { strip: s.querySelector('[data-r-strip="3"] dd').textContent, tone: s.querySelector('[data-r-strip="3"] dd').className,
        metric: s.querySelector('#p-headline .metric-v').textContent, banner: !!s.querySelector('.stale-banner'), same: s.querySelector('#p-terms') === window.keep,
        gen: s.querySelector('.view').getAttribute('data-generation') }; }""")
    assert r == {"strip": "−1,300,000", "tone": "mono t-neg emph live-cell", "metric": "−1,300,000", "banner": True, "same": True, "gen": "2"}
    lab.evaluate("() => window.h.patch({ seq: 2, patches: [{ op: 'deleted', at: '2026-10-30T10:00:00Z' }, { op: 'strip', index: 0, cell: { text: 'IGNORED' } }] })")
    assert lab.locator("#host .r-deleted-banner").count() == 1
    assert "IGNORED" not in view(lab).inner_text()


def test_subscribe_follows_the_console_s_raw_stream(lab, site):
    sc = cases.showcase()
    frame = {"seq": 1, "generation": 5, "patches": [{"op": "strip", "index": 0, "cell": {"label": "Notional", "text": "USD 26,000,000"}}]}
    site.pages["/api/stream/trade/SAMPLE?raw=1"] = f"event: view\ndata: {json.dumps(sc)}\n\nevent: frame\ndata: {json.dumps(frame)}\n\n"
    render(lab, "showcase")
    lab.evaluate("() => { window.stop = window.h.subscribe(); }")
    lab.wait_for_function("() => document.getElementById('host').shadowRoot.querySelector('[data-r-strip=\"0\"] dd').textContent === 'USD 26,000,000'")
    lab.evaluate("() => window.stop()")
    assert any(u.endswith("/api/stream/trade/SAMPLE?raw=1") for u in site.requests)


# ---- navigation in place ---------------------------------------------------------------------------------------------------------
def _target(site):
    t = cases.fixture_views()["view_trade_IRS-48213"]
    site.pages["/api/view/counterparty/CP-NORTHBRIDGE"] = json.dumps(t)
    return t


def test_an_entity_link_opens_in_place_with_a_back_stack(lab, site):
    t = _target(site)
    render(lab, "links-and-tones")
    lab.locator("#host .with-cp a").click()
    lab.wait_for_function("() => window.h.depth === 1")
    H.settle(lab)
    assert view(lab).locator("h1.vid").inner_text() == t["title"]["id"]
    ev = [e for e in lab.evaluate("() => window.__events") if e[0] == "navigate"]
    assert ev and ev[0][1]["kind"] == "counterparty" and ev[0][1]["id"] == "CP-NORTHBRIDGE" and ev[0][1]["target"] == "entity"
    assert lab.url.endswith("/render-test/lab.html")                       # the host's address and window are untouched
    lab.locator("#host [data-r-back]").click()
    lab.wait_for_function("() => window.h.depth === 0")
    assert view(lab).locator("h1.vid").inner_text() == "DEMO-BOND-1"
    lab.locator("#host .with-cp a").click()
    lab.wait_for_function("() => window.h.depth === 1")
    lab.locator("#host [data-r-back]").focus()
    lab.keyboard.press("Alt+ArrowLeft")                                    # the keyboard's Back, inside the container
    lab.wait_for_function("() => window.h.depth === 0")
    assert lab.evaluate("() => window.__opened") == []


@pytest.mark.parametrize("mode", ["host", "cancelled", "new-window"])
def test_the_host_may_take_navigation(lab, site, mode):
    _target(site)
    if mode == "cancelled":
        lab.evaluate("() => { window.__cancel = ['navigate']; }")
    render(lab, "links-and-tones", {"navigation": "host" if mode == "host" else "new-window" if mode == "new-window" else "in-place"})
    lab.locator("#host .with-cp a").click()
    lab.wait_for_timeout(300)
    assert view(lab).locator("h1.vid").inner_text() == "DEMO-BOND-1" and lab.evaluate("() => window.h.depth") == 0
    assert [e[0] for e in lab.evaluate("() => window.__events")].count("navigate") == 1
    assert len(lab.evaluate("() => window.__opened")) == (1 if mode == "new-window" else 0)


def test_console_and_outside_links_are_events_by_default(lab):
    render(lab, "links-and-tones")
    lab.locator("#host #p-terms .pnl-help").click()
    lab.locator("#host #p-kvl a.ext").click()
    ev = [e[1] for e in lab.evaluate("() => window.__events") if e[0] == "navigate"]
    assert [e["target"] for e in ev] == ["console", "outside"]
    assert lab.url.endswith("/render-test/lab.html") and lab.evaluate("() => window.__opened") == []


def test_rows_and_bars_with_events_say_so(lab):
    render(lab, "links-and-tones")
    lab.locator("#host #p-cells tbody tr").first.click(position={"x": 400, "y": 5})
    ev = lab.evaluate("() => window.__events")
    rc = [e[1] for e in ev if e[0] == "row-click"]
    assert rc and rc[0]["panel"] == "cells" and rc[0]["event"] == "row-1" and rc[0]["pick"] == "MX-20000001"
    assert [e[1] for e in ev if e[0] == "select"][0]["value"] == "MX-20000001"


# ---- tables, drills, trees, tabs -------------------------------------------------------------------------------------------------
def test_a_column_sorts_as_the_console_sorts_it(browser, site):
    vm = cases.more_shapes()
    site.add_vm("sorting", vm)
    html = H.console_html(vm).replace("</body>", '<script src="/static/js/colfilter.js"></script><script src="/static/js/tables.js"></script></body>')
    site.add_console("sorting", html)
    orders = []
    for side in ("console", "library"):
        page = browser.new_page(viewport={"width": 1280, "height": 900})
        page.route("**/*", site.handle)
        if side == "console":
            page.goto(H.ORIGIN + "/render-test/console/sorting.html")
            H.settle(page)
            page.locator("#p-big thead th").nth(1).click(position={"x": 8, "y": 8})
            rows = page.locator("#p-big tbody tr:not([hidden])")
        else:
            page.goto(H.ORIGIN + "/render-test/lab.html")
            page.wait_for_function("() => window.__lab === true")
            render(page, "sorting", {"tables": True})
            page.locator("#host #p-big thead th").nth(1).locator(".tbl-sort").click()
            rows = page.locator("#host #p-big tbody tr:not([hidden])")
        orders.append([rows.nth(i).locator("td").nth(1).inner_text() for i in range(rows.count())])
        page.close()
    assert orders[0] == orders[1] and len(orders[1]) == 25
    assert lab_free_sorted(orders[1])


def lab_free_sorted(texts):
    vals = [float(t.replace("−", "-").replace(",", "")) for t in texts]
    return vals == sorted(vals)


def test_drill_tree_and_tabs_work_in_place_by_keyboard(lab):
    render(lab, "more-shapes")
    lab.locator("#host #p-drill a.dr-open").first.click()
    assert lab.locator("#host #p-drill .dr-lv:not([hidden]) caption").first.text_content().startswith("Books")
    assert lab.locator("#host #p-drill [data-dr-crumbs] li").count() == 2
    lab.locator("#host #p-drill .dr-lv:not([hidden]) a.dr-open").first.focus()
    lab.keyboard.press("Escape")
    assert lab.locator("#host #p-drill [data-dr-crumbs] li").count() == 1
    assert lab.url.endswith("/render-test/lab.html")
    tab = lab.locator("#host #p-tabin [role=tab]").first
    tab.focus()
    lab.keyboard.press("ArrowRight")
    assert lab.locator("#host #p-tabin [role=tab]").nth(1).get_attribute("aria-selected") == "true"
    assert lab.locator("#host #p-tabin #tabin-pane-1").is_visible()
    render(lab, "showcase")
    tog = lab.locator("#host #p-tree .tr-tog").first
    shown = lab.locator("#host #p-tree tbody tr:not([hidden])").count()
    tog.focus()
    lab.keyboard.press("Enter")
    assert lab.locator("#host #p-tree tbody tr:not([hidden])").count() > shown       # expand: 1 shows the desks closed
    assert tog.get_attribute("aria-expanded") == "true"


def test_targets_are_44_px_on_a_phone(browser, site):
    page = browser.new_page(viewport={"width": 390, "height": 844})
    page.route("**/*", site.handle)
    page.goto(H.ORIGIN + "/render-test/lab.html")
    page.wait_for_function("() => window.__lab === true")
    render(page, "showcase", {"tables": True})
    small = page.evaluate("""() => [...document.getElementById('host').shadowRoot.querySelectorAll('.pnl-help, .tr-tog, .tab-head, .md-btn, .tbl-sort, .tbl-pg-b')]
      .filter((e) => e.checkVisibility()).map((e) => [e.className, Math.round(e.getBoundingClientRect().width), Math.round(e.getBoundingClientRect().height)])
      .filter(([, w, h]) => w < 44 || h < 44)""")
    assert small == []
    assert page.evaluate("() => document.scrollingElement.scrollWidth - window.innerWidth") <= 1
    page.close()


def test_the_playground_renders_a_view_of_this_console(browser, site):
    site.pages["/api/view/trade/IRS-48213"] = json.dumps(cases.fixture_views()["view_trade_IRS-48213"])
    # the playground opens on an entity the console says the server holds (no made-up default), and shows the person's lens
    site.pages["/render/demo/start"] = json.dumps({"ref": {"kind": "trade", "id": "IRS-48213"}, "lens": None, "message": "The first trade this server holds that you may open."})
    page = browser.new_page(viewport={"width": 1280, "height": 900})
    page.route("**/*", site.handle)
    before = len(site.requests)
    page.goto(H.ORIGIN + f"/static/render/{H.render_dist.VERSION}/demo.html")
    page.locator("#screen .view h1.vid").wait_for()
    assert page.locator("#screen .view h1.vid").inner_text() == "IRS-48213"
    assert "render(document.getElementById('risk')" in page.locator("[data-code]").inner_text()
    assert "No lens" in page.locator("[data-lens]").inner_text()
    assert page.evaluate("() => (window.__csp || []).length") == 0
    assert [u for u in site.requests[before:] if not u.startswith(H.ORIGIN + "/")] == []
    page.close()


def test_the_playground_says_why_it_has_nothing_to_draw_and_names_the_lens(browser, site):
    site.pages["/render/demo/start"] = json.dumps({"ref": None, "lens": {"name": "world", "title": "Drishti World"},
                                                   "message": "Nothing to draw yet: this server holds no country you may open in the Drishti World lens."})
    page = browser.new_page(viewport={"width": 1280, "height": 900})
    page.route("**/*", site.handle)
    page.goto(H.ORIGIN + f"/static/render/{H.render_dist.VERSION}/demo.html")
    page.locator("#screen [data-none]").wait_for()
    assert "holds no country" in page.locator("#screen [data-none]").inner_text()
    assert "inside the Drishti World lens" in page.locator("[data-lens]").inner_text()
    page.close()
