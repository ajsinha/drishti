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

"""Controls of a Sutra (static/js/controls.js, BI_DESIGN.md 8.1) in a real browser (Chromium): every control kind, cascading, the address round
trip, only the panels that read a control re-requested, a live tick keeping the filter (also after a change), a click or Enter on a row or
bar setting a control (cross-filtering), keyboard only, a phone (390 px, touch) and both themes. The console runs in-process against a stand-in
server that evaluates the controls as the real one does (the same rows, the same `where`); skipped when Playwright's Chromium is missing."""
import asyncio
import json
import re
import threading
import time

import pytest

from conftest import CONSOLE

sync_api = pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from test_live_tabs_browser import TickingBackend, _free_port  # noqa: E402

VIEW = "/v/trade/IRS-48213"
SCHEDULE = [(1, "2026-10-15", 100), (2, "2026-10-20", 200), (1, "2026-11-15", 300), (2, "2026-12-20", 400), (1, "2027-01-15", 500), (2, "2027-02-20", 600)]
REGIONS = {"EMEA": ["UK", "DE"], "APAC": ["JP", "AU", "SG"]}
PRESETS = [{"value": "2026-10-10..2027-01-07", "label": "Next 90 days"}, {"value": "2026-10-01..2026-10-31", "label": "This month"}]


def opts(*values):
    return [{"value": v, "label": v} for v in values]


class ControlsBackend(TickingBackend):
    """A view whose `schedule` ladder reads the controls `legs`, `when`, `q`, `size` and `top`; `byleg` selects `legs`; `other` reads none."""

    def __init__(self):
        super().__init__()
        self.asked = []                                   # (controls, panels) of every view request

    def state(self, ctl):
        c = lambda k, d="": ctl.get("ctl." + k, d)  # noqa: E731
        legs = [x for x in c("legs", "1,2").split(",") if x in ("1", "2")] or ["1", "2"]
        region = c("region", "EMEA") if c("region", "EMEA") in REGIONS else "EMEA"
        country = c("country") if c("country") in REGIONS[region] else REGIONS[region][0]
        when = c("when")
        size = c("size")
        top = int(c("top", "25")) if c("top", "25") in ("5", "10", "25") else 25
        return dict(legs=legs, region=region, country=country, when=when, q=c("q"), size=size, top=top, mode=c("mode", "Net") if c("mode") in ("Net", "Gross") else "Net",
                    view=c("view", "A") if c("view") in ("A", "B", "C") else "A", day=c("day"))

    def rows(self, st):
        lo, _, hi = st["when"].partition("..") if st["when"] else ("", "", "")
        out = []
        for leg, date, amount in SCHEDULE:
            if str(leg) not in st["legs"] or (lo and date < lo) or (hi and date > hi) or st["q"].lower() not in f"leg {leg} {date}".lower():
                continue
            slo, _, shi = st["size"].partition("..") if st["size"] else ("", "", "")
            if (slo and amount < float(slo)) or (shi and amount > float(shi)):
                continue
            out.append((leg, date, amount))
        return out[:st["top"]]

    def schedule(self, st, n=0):
        rows = self.rows(st)
        title = "Cashflows" + (f" · paid {st['when']}" if st["when"] else "")
        return {"id": "schedule", "kind": "ladder", "title": title, "area": "main", "data": {
            "columns": ["Leg", "Pay date", "Amount"], "numeric": [False, False, True],
            "rows": [{"cells": [{"text": str(leg)}, {"text": date}, {"text": f"{amount}" + (f" t{n}" if n else "")}]} for leg, date, amount in rows]}}

    def byleg(self):
        return {"id": "byleg", "kind": "table", "title": "By leg", "area": "right", "data": {
            "columns": ["Leg", "Total"], "numeric": [False, True],
            "rows": [{"cells": [{"text": leg}, {"text": str(sum(a for lg, _, a in SCHEDULE if str(lg) == leg))}], "pick": leg} for leg in ("1", "2")]}}

    def controls(self, st):
        return [
            {"id": "legs", "kind": "multi-select", "label": "Legs", "placement": "header", "panel": "schedule", "options": opts("1", "2"), "value": ",".join(st["legs"]),
             "values": st["legs"], "required": False, "remember": True, "reads": ["schedule", "byleg"]},
            {"id": "when", "kind": "date-range", "label": "Paid between", "placement": "strip", "options": PRESETS, "value": st["when"], "required": False, "remember": False,
             "reads": ["schedule"]},
            {"id": "q", "kind": "search", "label": "Find", "placement": "strip", "value": st["q"], "required": False, "remember": False, "reads": ["schedule"]},
            {"id": "size", "kind": "number-range", "label": "Amount", "placement": "strip", "min": "0", "max": "1000", "value": st["size"], "required": False, "remember": False,
             "reads": ["schedule"]},
            {"id": "top", "kind": "top-n", "label": "Show", "placement": "strip", "options": opts("5", "10", "25"), "value": str(st["top"]), "required": True, "remember": False,
             "reads": ["schedule"]},
            {"id": "region", "kind": "dropdown", "label": "Region", "placement": "side", "options": opts(*REGIONS), "value": st["region"], "required": True, "remember": False,
             "reads": ["other"]},
            {"id": "country", "kind": "cascading", "label": "Country", "placement": "side", "parent": "region", "options": opts(*REGIONS[st["region"]]), "value": st["country"],
             "required": True, "remember": False, "reads": ["other"]},
            {"id": "mode", "kind": "toggle", "label": "Basis", "placement": "side", "options": opts("Net", "Gross"), "value": st["mode"], "required": True, "remember": False,
             "reads": ["other"]},
            {"id": "view", "kind": "segmented", "label": "View", "placement": "side", "options": opts("A", "B", "C"), "value": st["view"], "required": True, "remember": False,
             "reads": ["other"]},
            {"id": "day", "kind": "date", "label": "Day", "placement": "side", "value": st["day"], "required": False, "remember": False, "reads": ["other"]},
        ]

    def other(self, st):
        return {"id": "other", "kind": "ladder", "title": f"Other {st['region']} {st['country']} {st['mode']} {st['view']} {st['day']}", "area": "main",
                "data": {"columns": ["A"], "numeric": [False], "rows": [{"cells": [{"text": "x"}]}]}}

    async def view(self, kind, id_, user, controls=None, panels=None):
        self.asked.append((dict(controls or {}), panels))
        vm = await super().view(kind, id_, user)
        st = self.state(controls or {})
        everything = {"schedule": self.schedule(st), "byleg": self.byleg(), "other": self.other(st)}
        vm["panels"] = [p for k, p in everything.items() if panels is None or k in panels]
        vm["controls"] = self.controls(st)
        vm["selects"] = [{"panel": "byleg", "by": "legs", "filter": ["schedule"]}]
        vm["strip"] = [{"label": "Tick", "text": "0"}]
        return vm

    async def stream(self, kind, id_, ident=None, opened=None, controls=None):
        st = self.state(controls or {})
        yield "view", json.dumps({"provenance": {"generation": 1}})
        n = 0
        while True:
            await asyncio.sleep(0.2)
            n += 1
            yield "frame", json.dumps({"seq": n, "generation": n + 1, "p99Ms": 1.0, "latencyMs": 0.5, "patches": [
                {"op": "strip", "index": 0, "cell": {"label": "Tick", "text": f"#{n}"}}, {"op": "panel", "panel": self.schedule(st, n)}]})


@pytest.fixture(scope="module")
def backend():
    return ControlsBackend()


@pytest.fixture(scope="module")
def url(backend):
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = backend
    port = _free_port()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started
    yield f"http://127.0.0.1:{port}"
    server.should_exit = True
    thread.join(10)


@pytest.fixture(scope="module")
def browser():
    with sync_api.sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001
            pytest.skip(f"needs Playwright's Chromium: {str(e).splitlines()[0]}")
        yield b
        b.close()


@pytest.fixture
def page(browser, url, backend):
    ctx = browser.new_context(viewport={"width": 1280, "height": 900}, bypass_csp=True)
    pg = ctx.new_page()
    backend.asked.clear()
    pg.goto(url + VIEW, wait_until="load")
    pg.wait_for_selector("[data-ctl]")
    yield pg
    ctx.close()


def rows(page, panel="schedule"):
    return page.evaluate("(p) => [...document.querySelectorAll('#p-' + p + ' tbody tr')].map(r => [...r.cells].map(c => c.textContent.trim()).join('|'))", panel)


def done(page, action):
    """Runs `action` and waits for the panels it asked for to be swapped in."""
    n = page.evaluate("(window.__drishtiControls || []).length")
    action()
    page.wait_for_function("(n) => (window.__drishtiControls || []).length > n", arg=n, timeout=10000)


def query(page):
    return page.evaluate("location.search")


# ---- the controls are drawn --------------------------------------------------------------------------------------------------

def test_every_kind_is_drawn_in_its_place_with_a_label(page):
    ids = page.evaluate("[...document.querySelectorAll('[data-ctl]')].map(e => e.dataset.ctl)")
    assert ids == ["legs", "when", "q", "size", "top", "region", "country", "mode", "view", "day"] or set(ids) == {"legs", "when", "q", "size", "top", "region", "country", "mode", "view", "day"}
    assert page.locator("#p-schedule .pnl-h [data-ctl='legs']").count() == 1                  # header
    assert page.locator(".vhead [data-ctl='when']").count() == 1 and page.locator(".vhead [data-ctl='q']").count() == 1   # strip
    assert page.locator(".vgrid > .ctl-side [data-ctl='region']").count() == 1                 # side
    assert page.locator("select[id^=ctl-]").first.get_attribute("id")
    # every control can be named by a screen reader
    unnamed = page.evaluate("""() => [...document.querySelectorAll('[data-ctl] select, [data-ctl] input:not([type=checkbox]), [data-ctl] button, [data-ctl] summary')]
        .filter(e => !(e.labels && e.labels.length) && !e.getAttribute('aria-label') && !e.closest('[role=radiogroup]') && e.tagName !== 'BUTTON' && e.tagName !== 'SUMMARY').length""")
    assert unnamed == 0
    assert page.locator("[data-ctl-status][role=status]").count() == 1
    assert page.locator("[role=radiogroup]").get_attribute("aria-labelledby")
    assert page.locator("button[role=switch]").get_attribute("aria-checked") == "false"
    assert len(rows(page)) == 6


def test_a_dropdown_a_multi_select_and_the_search_filter_only_their_panels(page, backend):
    page.evaluate("document.querySelector('#p-other').setAttribute('data-mark', 'kept')")
    page.locator("[data-ctl='legs'] summary").click()
    done(page, lambda: page.locator("[data-ctl='legs'] input[value='2']").uncheck())
    assert [r.split("|")[0] for r in rows(page)] == ["1", "1", "1"]
    assert page.locator("[data-ctl='legs'] summary").inner_text() == "1"
    assert backend.asked[-1][1] == ["schedule", "byleg"] and backend.asked[-1][0] == {"ctl.legs": "1"}      # only the panels that read it were asked for
    assert page.evaluate("document.querySelector('#p-other').getAttribute('data-mark')") == "kept"       # the others did not move
    done(page, lambda: page.locator("[data-ctl='q'] input").fill("2027"))
    assert rows(page) == ["1|2027-01-15|500"]
    assert "ctl.q=2027" in query(page) and "ctl.legs=1" in query(page)
    assert page.locator("[data-ctl-status]").inner_text().startswith("Cashflows")


def test_a_date_range_has_presets_and_ends_a_number_range_bounds_and_top_n_cuts(page, backend):
    done(page, lambda: page.locator("[data-ctl='when'] .ctl-preset").select_option(label="This month"))
    assert [r.split("|")[1] for r in rows(page)] == ["2026-10-15", "2026-10-20"]
    assert page.locator("[data-ctl='when'] .ctl-from").input_value() == "2026-10-01" and page.locator("[data-ctl='when'] .ctl-to").input_value() == "2026-10-31"
    assert page.locator("#p-schedule h3").inner_text() == "Cashflows · paid 2026-10-01..2026-10-31"        # the title reads the control
    done(page, lambda: page.locator("[data-ctl='when'] .ctl-to").fill("2026-10-16"))
    assert rows(page) == ["1|2026-10-15|100"]
    done(page, lambda: page.locator("[data-ctl='when'] .ctl-from").fill(""))
    done(page, lambda: page.locator("[data-ctl='when'] .ctl-to").fill(""))
    assert len(rows(page)) == 6
    assert page.locator("[data-ctl='size'] .ctl-from").get_attribute("placeholder") == "min 0"
    done(page, lambda: page.locator("[data-ctl='size'] .ctl-from").fill("250"))
    assert [r.split("|")[2] for r in rows(page)] == ["300", "400", "500", "600"]
    done(page, lambda: page.locator("[data-ctl='size'] .ctl-to").fill("450"))
    assert [r.split("|")[2] for r in rows(page)] == ["300", "400"]
    done(page, lambda: page.locator("[data-ctl='top'] select").select_option("5"))
    assert len(rows(page)) == 2 and "ctl.top=5" in query(page)


def test_cascading_toggle_segmented_and_date_move_the_other_panel_only(page, backend):
    assert page.locator("[data-ctl='country'] option").all_inner_texts() == ["UK", "DE"]
    done(page, lambda: page.locator("[data-ctl='region'] select").select_option("APAC"))
    assert page.locator("[data-ctl='country'] option").all_inner_texts() == ["JP", "AU", "SG"]     # the child's options moved with its parent
    assert page.locator("[data-ctl='country'] select").input_value() == "JP"
    assert page.locator("#p-other h3").inner_text() == "Other APAC JP Net A"
    # only `other` was asked for and swapped in by the change (the live stream redraws `schedule` on its own ticks, so a mark on its element
    # would not tell the two apart)
    assert backend.asked[-1][1] == ["other"] and page.evaluate("window.__drishtiControls.at(-1).panels") == ["other"]
    done(page, lambda: page.locator("[data-ctl='country'] select").select_option("SG"))
    assert "SG" in page.locator("#p-other h3").inner_text()
    done(page, lambda: page.locator("button[role=switch]").click())
    assert page.locator("button[role=switch]").get_attribute("aria-checked") == "true" and "Gross" in page.locator("#p-other h3").inner_text()
    done(page, lambda: page.locator("[data-ctl='view'] [data-value='C']").click())
    assert page.locator("[data-ctl='view'] [data-value='C']").get_attribute("aria-checked") == "true" and page.locator("#p-other h3").inner_text().endswith(" C")
    done(page, lambda: page.locator("[data-ctl='day'] input").fill("2026-10-09"))
    assert page.locator("#p-other h3").inner_text().endswith("2026-10-09")


def test_the_address_carries_the_values_and_reproduces_the_view(page, url, backend):
    page.locator("[data-ctl='legs'] summary").click()
    done(page, lambda: page.locator("[data-ctl='legs'] input[value='1']").uncheck())
    done(page, lambda: page.locator("[data-ctl='when'] .ctl-preset").select_option(label="Next 90 days"))
    q = query(page)
    assert "ctl.legs=2" in q and "ctl.when=2026-10-10..2027-01-07" in q and "%2C" not in q
    share = page.evaluate("document.querySelector('[data-share]').dataset.share")
    assert share.endswith(q.lstrip("?")) or "ctl.legs=2" in share
    # the schedule panel is live: a tick marks its last cell (" t1", " t2" ...) on either page at any moment, so the rows are compared without it
    tickless = lambda rs: [re.sub(r"\s+t\d+$", "", r) for r in rs]                    # noqa: E731
    expected = tickless(rows(page))
    ctx2 = page.context.browser.new_context(viewport={"width": 1280, "height": 900}, bypass_csp=True)
    other = ctx2.new_page()
    other.goto(url + VIEW + q, wait_until="load")                      # a link: the same rows, the same controls, with no request after the page
    other.wait_for_selector("[data-ctl]")
    assert tickless(rows(other)) == expected
    assert other.locator("[data-ctl='when'] .ctl-from").input_value() == "2026-10-10"
    assert other.locator("[data-ctl='legs'] summary").inner_text() == "2"
    ctx2.close()


# ---- live ----------------------------------------------------------------------------------------------------------------------

def test_a_live_tick_keeps_the_filter_also_after_a_change(browser, url, backend):
    ctx = browser.new_context(viewport={"width": 1280, "height": 900}, bypass_csp=True)
    pg = ctx.new_page()
    pg.goto(url + VIEW + "?ctl.legs=2", wait_until="load")
    pg.wait_for_selector("[data-ctl]")
    pg.wait_for_function("document.querySelector('#p-schedule tbody td:last-child') && /t\\d+/.test(document.querySelector('#p-schedule tbody td:last-child').textContent)", timeout=10000)
    assert {r.split("|")[0] for r in rows(pg)} == {"2"} and len(rows(pg)) == 3                    # a tick rebuilt the panel with the filter
    pg.locator("[data-ctl='legs'] summary").click()
    pg.locator("[data-ctl='legs'] input[value='1']").check()
    pg.locator("[data-ctl='legs'] input[value='2']").uncheck()
    pg.wait_for_function("document.querySelector('#p-schedule tbody') && [...document.querySelectorAll('#p-schedule tbody tr')].every(r => r.cells[0].textContent === '1') && /t\\d+/.test(document.querySelector('#p-schedule tbody td:last-child').textContent)", timeout=10000)
    n = pg.locator("#p-schedule tbody tr").count()
    assert n == 3
    first = pg.locator("#p-schedule tbody td:last-child").first.inner_text()
    pg.wait_for_function("(t) => document.querySelector('#p-schedule tbody td:last-child').textContent !== t", arg=first, timeout=10000)   # it keeps ticking, still leg 1 only
    assert {r.split("|")[0] for r in rows(pg)} == {"1"}
    assert pg.locator("[data-ctl='legs'] summary").inner_text() == "1"                            # the header control survived the live swap
    ctx.close()


# ---- cross-filtering -----------------------------------------------------------------------------------------------------------------

def test_a_click_or_enter_on_a_row_sets_the_control_and_again_clears_it(page, backend):
    row = page.locator("#p-byleg tbody tr[data-pick='2']")
    assert row.get_attribute("tabindex") == "0" and row.get_attribute("aria-selected") == "false"
    done(page, lambda: row.click())
    assert {r.split("|")[0] for r in rows(page)} == {"2"} and "ctl.legs=2" in query(page)
    assert page.locator("#p-byleg tbody tr[data-pick='2']").get_attribute("aria-selected") == "true"
    assert page.locator("[data-ctl='legs'] summary").inner_text() == "2"
    done(page, lambda: page.locator("#p-byleg tbody tr[data-pick='2']").click())
    assert len(rows(page)) == 6
    page.locator("#p-byleg tbody tr[data-pick='1']").focus()
    done(page, lambda: page.keyboard.press("Enter"))
    assert {r.split("|")[0] for r in rows(page)} == {"1"}


# ---- keyboard ------------------------------------------------------------------------------------------------------------------------

def test_every_control_works_from_the_keyboard(page):
    page.locator("[data-ctl='legs'] summary").focus()
    page.keyboard.press("Enter")
    assert page.locator("[data-ctl='legs'] details").evaluate("d => d.open")
    page.wait_for_function("document.activeElement.classList.contains('ms-q')")   # opening puts the focus in the filter box
    page.keyboard.press("Tab")                                               # All
    page.keyboard.press("Tab")                                               # None
    done(page, lambda: page.keyboard.press("Enter"))
    assert len(rows(page)) == 6                                              # none ticked: no filter
    page.keyboard.press("Tab"); page.keyboard.press("Tab")                   # leg 1
    done(page, lambda: page.keyboard.press("Space"))
    assert {r.split("|")[0] for r in rows(page)} == {"1"}
    page.keyboard.press("Escape")
    assert not page.locator("[data-ctl='legs'] details").evaluate("d => d.open")
    assert page.evaluate("document.activeElement.tagName") == "SUMMARY"
    seg = page.locator("[data-ctl='view'] [data-value='A']")
    seg.focus()
    done(page, lambda: page.keyboard.press("ArrowRight"))
    assert page.locator("[data-ctl='view'] [data-value='B']").get_attribute("aria-checked") == "true"
    assert page.evaluate("document.activeElement.dataset.value") == "B"
    sw = page.locator("button[role=switch]")
    sw.focus()
    done(page, lambda: page.keyboard.press("Space"))
    assert sw.get_attribute("aria-checked") == "true"
    page.locator("[data-ctl='top'] select").focus()
    done(page, lambda: page.keyboard.press("ArrowUp"))
    assert page.locator("[data-ctl='top'] select").input_value() == "10"
    focus_ring = page.evaluate("(() => { document.querySelector('[data-ctl=q] input').focus(); return getComputedStyle(document.activeElement).outlineStyle; })()")
    assert focus_ring != "none"


# ---- a phone, and both themes ----------------------------------------------------------------------------------------------------------

def test_on_a_phone_the_controls_are_finger_sized_and_the_page_does_not_scroll_sideways(browser, url, backend):
    ctx = browser.new_context(viewport={"width": 390, "height": 844}, device_scale_factor=2, has_touch=True, is_mobile=True, bypass_csp=True)
    pg = ctx.new_page()
    pg.goto(url + VIEW, wait_until="load")
    pg.wait_for_selector("[data-ctl]")
    small = pg.evaluate("""() => [...document.querySelectorAll('[data-ctl] select, [data-ctl] input:not([type=checkbox]), [data-ctl] summary, [data-ctl] button')]
        .filter(e => e.offsetParent !== null && e.getBoundingClientRect().height < 43.5).map(e => e.tagName + '.' + e.className)""")
    assert small == []
    assert pg.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
    pg.locator("[data-ctl='legs'] summary").tap()
    n = pg.evaluate("(window.__drishtiControls || []).length")
    pg.locator("[data-ctl='legs'] input[value='2']").tap()
    pg.wait_for_function("(n) => (window.__drishtiControls || []).length > n", arg=n, timeout=10000)
    assert len(rows(pg)) == 3
    ctx.close()


def test_the_controls_are_readable_in_both_themes(page):
    def ratio():
        return page.evaluate("""() => { const lum = c => { const [r, g, b] = c.match(/\\d+/g).slice(0, 3).map(Number).map(v => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); }); return 0.2126 * r + 0.7152 * g + 0.0722 * b; };
            return [...document.querySelectorAll('.ctl-in, .ctl-sum, .ctl-seg-b')].map(e => { const s = getComputedStyle(e), a = lum(s.color), b = lum(s.backgroundColor); return (Math.max(a, b) + .05) / (Math.min(a, b) + .05); }); }""")
    for theme in ("light", "dark"):
        page.evaluate("(t) => document.documentElement.setAttribute('data-theme', t)", theme)
        rs = ratio()
        assert rs and min(rs) >= 4.5, (theme, rs)


def test_a_control_that_remembers_restores_the_last_choice_in_this_browser(browser, url, backend):
    ctx = browser.new_context(viewport={"width": 1280, "height": 900}, bypass_csp=True)
    pg = ctx.new_page()
    pg.goto(url + VIEW, wait_until="load")
    pg.wait_for_selector("[data-ctl]")
    pg.locator("[data-ctl='legs'] summary").click()
    done(pg, lambda: pg.locator("[data-ctl='legs'] input[value='1']").uncheck())
    assert pg.evaluate("Object.keys(localStorage).filter(k => k.startsWith('drishti.ctl.'))")       # kept for this browser and sign-in
    again = ctx.new_page()
    again.goto(url + VIEW, wait_until="load")
    again.wait_for_function("(window.__drishtiControls || []).length > 0", timeout=10000)       # one request puts the choice back
    assert {r.split("|")[0] for r in rows(again)} == {"2"} and "ctl.legs=2" in query(again)
    other = browser.new_context(viewport={"width": 1280, "height": 900}, bypass_csp=True)
    fresh = other.new_page()
    fresh.goto(url + VIEW, wait_until="load")
    fresh.wait_for_selector("[data-ctl]")
    assert len(rows(fresh)) == 6                                                                  # another browser starts from the defaults
    other.close()
    ctx.close()
