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

"""The client-side renderer in a real browser (docs/guides/INTEGRATION_GUIDE.md sections 5 to 14): render() over Drishti Elements' painter
(drishti-views paint()), Drishti's host part around it. What the host part owes: a hostile ViewModel draws as text only; every case of
VIEWMODEL.md draws with no page error, no CSP violation, no request off the console's origin and no sideways scroll at 390 px; live frames
and the console's raw stream are applied in place by the painter; in-place navigation with its own back stack and the navigate event,
which the host may take; a row with a pick says so; the playground. Every page runs under the console's CSP on one made-up origin, and
window.open is never called in place. The panels themselves (tables, sorting, tabs, charts, formats) are tested by Drishti Elements."""
import json

import pytest

import render_cases as cases
import render_harness as H

sync_api = pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
M = f"/static/render/{H.render_dist.VERSION}/m/"      # the host part


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


def _open(browser, site, width=1280, height=900):
    page = browser.new_page(viewport={"width": width, "height": height})
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.on("console", lambda m: errors.append(m.text) if m.type == "error" and "Failed to load resource" not in m.text else None)
    page.on("response", lambda r: errors.append(f"{r.status} {r.url}") if r.status >= 400 else None)
    page.route("**/*", site.handle)
    page.goto(H.ORIGIN + "/render-test/lab.html")
    page.wait_for_function("() => window.__lab === true")
    return page, errors


@pytest.fixture
def lab(browser, site):
    before = len(site.requests)
    page, errors = _open(browser, site)
    yield page
    try:
        assert errors == [], errors
        assert page.evaluate("() => window.__csp") == []
        assert [u for u in site.requests[before:] if not u.startswith(H.ORIGIN + "/")] == []
        assert page.evaluate("() => window.__opened") == []
    finally:
        page.close()


def render(page, name, opts=None):
    page.evaluate("async ([n, o]) => { window.h = window.R.render(document.getElementById('host'), await window.vm(n), o); await window.h.ready; }", [name, opts or {}])
    H.settle(page)


def view(page):
    return page.locator("#host .dv-view")


def title(page):
    return view(page).locator(".dv-id").inner_text()


# ---- the host part's own elements and a hostile ViewModel ---------------------------------------------------------------------------
def test_the_host_part_s_elements_refuse_markup_handlers_styles_and_script_urls(lab):
    got = lab.evaluate("""async () => {
      const D = await import('%sdrishti_dom.js');
      const el = D.h('span', { onclick: 'alert(1)', style: 'color:red', title: 't', 'data-x': '1', 'aria-label': 'a', href: 'javascript:alert(1)', srcdoc: 'x' }, '<img src=x onerror=alert(1)>');
      return { attrs: [...el.attributes].map((a) => a.name).sort(), children: el.childNodes.length, kind: el.firstChild.nodeType, imgs: el.querySelectorAll('img').length };
    }""" % M)
    assert got == {"attrs": ["aria-label", "data-x", "title"], "children": 1, "kind": 3, "imgs": 0}


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
        handlers: [...r.querySelectorAll('*')].some((e) => [...e.attributes].some((a) => /^on/i.test(a.name))), title: r.querySelector('.dv-id').textContent }; }""")
    assert got == {"scripts": 0, "pwned": False, "hrefs": [], "handlers": False, "title": evil}


# ---- every case draws, cleanly, at both widths -------------------------------------------------------------------------------------
@pytest.mark.parametrize("width", [1280, 390])
def test_every_case_draws_under_the_policy_without_errors_or_sideways_scroll(browser, site, width):
    page, errors = _open(browser, site, width, 844)
    before = len(site.requests)
    try:
        for name in {**cases.edge_cases(), **cases.fixture_views()}:
            render(page, name, {"theme": "terminal"})
            n = page.evaluate("() => document.getElementById('host').shadowRoot.querySelectorAll('.dv-panel').length")
            assert n >= 1, name
            assert page.evaluate("() => document.scrollingElement.scrollWidth - window.innerWidth") <= 1, name
        assert errors == [] and page.evaluate("() => window.__csp") == []
        assert [u for u in site.requests[before:] if not u.startswith(H.ORIGIN + "/")] == []
    finally:
        page.close()


def test_the_sheet_is_adopted_and_no_page_stylesheet_is_loaded(lab):
    render(lab, "showcase", {"theme": "terminal"})
    got = lab.evaluate("""() => ({ adopted: document.getElementById('host').shadowRoot.adoptedStyleSheets.length,
      pageSheets: [...document.querySelectorAll('link[rel~=stylesheet]')].map((l) => l.href).filter((h) => !h.endsWith('/render-test/page.css')),
      bg: getComputedStyle(document.getElementById('host')).backgroundColor })""")
    assert got["adopted"] == 1 and got["pageSheets"] == [] and got["bg"] not in ("", "rgba(0, 0, 0, 0)")


# ---- live: frames and the stream ----------------------------------------------------------------------------------------------
def test_frames_change_the_strip_and_a_panel_in_place(lab):
    render(lab, "showcase")
    lab.evaluate("() => { window.keep = document.getElementById('host').shadowRoot.querySelector('[data-panel=\"terms\"]'); }")
    sc = cases.showcase()
    metric = next(p for p in sc["panels"] if p["id"] == "headline")
    metric["data"]["value"]["text"] = "−1,300,000"
    frame = {"seq": 1, "generation": 2, "patches": [
        {"op": "strip", "index": 3, "cell": {"label": "MTM (USD)", "text": "−1,300,000", "tone": "neg", "emphasis": True}},
        {"op": "panel", "panel": metric}, {"op": "sparkle", "x": 1}]}
    assert lab.evaluate("(f) => window.h.patch(f)", frame) == {"strip": [3], "panels": ["headline"]}
    lab.wait_for_function("() => document.getElementById('host').shadowRoot.querySelector('.dv-fig[data-index=\"3\"] dd').textContent === '−1,300,000'")
    r = lab.evaluate("""() => { const s = document.getElementById('host').shadowRoot;
      return { metric: s.querySelector('[data-panel="headline"]').textContent.includes('−1,300,000'), same: s.querySelector('[data-panel="terms"]') === window.keep,
        gen: document.getElementById('host').getAttribute('data-generation') }; }""")
    assert r == {"metric": True, "same": True, "gen": "2"}
    assert [e[1]["changed"] for e in lab.evaluate("() => window.__events") if e[0] == "tick"] == [{"strip": [3], "panels": ["headline"]}]
    lab.evaluate("() => window.h.patch({ seq: 2, patches: [{ op: 'deleted', at: '2026-10-30T10:00:00Z' }] })")
    lab.locator("#host .dv-deleted").wait_for()
    assert any(e[0] == "deleted" for e in lab.evaluate("() => window.__events"))


def test_subscribe_follows_the_console_s_raw_stream(lab, site):
    sc = cases.showcase()
    frame = {"seq": 1, "generation": 5, "patches": [{"op": "strip", "index": 0, "cell": {"label": "Notional", "text": "USD 26,000,000"}}]}
    site.pages["/api/stream/trade/SAMPLE?raw=1"] = f"event: view\ndata: {json.dumps(sc)}\n\nevent: frame\ndata: {json.dumps(frame)}\n\n"
    render(lab, "showcase")
    lab.evaluate("() => { window.stop = window.h.subscribe(); }")
    lab.wait_for_function("() => document.getElementById('host').shadowRoot.querySelector('.dv-fig[data-index=\"0\"] dd').textContent === 'USD 26,000,000'")
    lab.evaluate("() => window.stop()")
    assert any(u.endswith("/api/stream/trade/SAMPLE?raw=1") for u in site.requests)


# ---- navigation in place ---------------------------------------------------------------------------------------------------------
def _target(site):
    t = cases.fixture_views()["view_trade_IRS-48213"]
    site.pages["/api/view/counterparty/CP-NORTHBRIDGE"] = json.dumps(t)
    return t


def _cp(page):
    return page.locator("#host .dv-title a.dv-link")


def test_an_entity_link_opens_in_place_with_a_back_stack(lab, site):
    t = _target(site)
    render(lab, "links-and-tones", {"live": False})                     # the opened trade is live: its stream is the next test's
    assert _cp(lab).get_attribute("href") == "/v/counterparty/CP-NORTHBRIDGE"      # a real link: open in a new tab works
    _cp(lab).click()
    lab.wait_for_function("() => window.h.depth === 1")
    H.settle(lab)
    assert title(lab) == t["title"]["id"]
    ev = [e for e in lab.evaluate("() => window.__events") if e[0] == "navigate"]
    assert ev and ev[0][1]["kind"] == "counterparty" and ev[0][1]["id"] == "CP-NORTHBRIDGE" and ev[0][1]["target"] == "entity"
    assert lab.url.endswith("/render-test/lab.html")                       # the host's address and window are untouched
    lab.locator("#host [data-r-back]").click()
    lab.wait_for_function("() => window.h.depth === 0")
    assert title(lab) == "DEMO-BOND-1"
    _cp(lab).click()
    lab.wait_for_function("() => window.h.depth === 1")
    lab.locator("#host [data-r-back]").focus()
    lab.keyboard.press("Alt+ArrowLeft")                                    # the keyboard's Back, inside the container
    lab.wait_for_function("() => window.h.depth === 0")


@pytest.mark.parametrize("mode", ["host", "cancelled", "new-window"])
def test_the_host_may_take_navigation(browser, site, mode):
    _target(site)
    page, errors = _open(browser, site)
    try:
        if mode == "cancelled":
            page.evaluate("() => { window.__cancel = ['navigate']; }")
        render(page, "links-and-tones", {"navigation": "host" if mode == "host" else "new-window" if mode == "new-window" else "in-place"})
        _cp(page).click()
        page.wait_for_timeout(300)  # wait-ok: proving that nothing happens
        assert title(page) == "DEMO-BOND-1" and page.evaluate("() => window.h.depth") == 0 and page.url.endswith("/render-test/lab.html")
        assert [e[0] for e in page.evaluate("() => window.__events")].count("navigate") == 1
        assert len(page.evaluate("() => window.__opened")) == (1 if mode == "new-window" else 0)
        assert errors == []
    finally:
        page.close()


def test_a_row_with_a_pick_says_so(lab):
    render(lab, "links-and-tones")
    lab.locator("#host [data-panel=\"cells\"] tbody tr[data-pick]").first.click()
    rc = [e[1] for e in lab.evaluate("() => window.__events") if e[0] == "row-click"]
    assert rc and rc[0]["panel"] == "cells" and rc[0]["pick"] == "MX-20000001"


# ---- the playground ---------------------------------------------------------------------------------------------------------------
def test_the_playground_renders_a_view_of_this_console(browser, site):
    site.pages["/api/view/trade/IRS-48213"] = json.dumps(cases.fixture_views()["view_trade_IRS-48213"])
    # the playground opens on an entity the console says the server holds (no made-up default), and shows the person's lens
    site.pages["/render/demo/start"] = json.dumps({"ref": {"kind": "trade", "id": "IRS-48213"}, "lens": None, "message": "The first trade this server holds that you may open."})
    page = browser.new_page(viewport={"width": 1280, "height": 900})
    page.route("**/*", site.handle)
    before = len(site.requests)
    page.goto(H.ORIGIN + f"/static/render/{H.render_dist.VERSION}/demo.html")
    page.locator("#screen .dv-view .dv-id").wait_for()
    assert page.locator("#screen .dv-view .dv-id").inner_text() == "IRS-48213"
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
