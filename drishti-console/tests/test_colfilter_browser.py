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

"""Excel-style column filters (static/js/colfilter.js, RUPAKA.md 8.1) in a real browser (Chromium): the logic (value types and
conditions, run in the page), the menu for every filter type, filters that combine, the footer and the live region, filters that survive
a live patch, the address round trip, keyboard only, a phone (390 px, touch), masked values, tree rows and pivots. The console runs
in-process against a stand-in server whose view carries tables built for the purpose; skipped when Playwright's Chromium is missing."""
import asyncio
import json
import threading
import time

import pytest

from conftest import CONSOLE

sync_api = pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from test_live_tabs_browser import TickingBackend, _free_port  # noqa: E402

VIEW = "/v/trade/IRS-48213"
TODAY = "2026-10-14"                                  # a Wednesday: the calendar the date conditions are pinned to


def cell(text):
    return {"text": text}


def table(cols, numeric, rows, **extra):
    return {"columns": cols, "numeric": numeric, "rows": [{"cells": [cell(c) for c in r]} for r in rows], **extra}


DESKS = ["Rates", "Rates", "FX", "FX", "Credit", "Credit", "Rates", "FX", "Equity", "Equity", "Rates", "Credit"]
AMOUNTS = ["1,000,000", "250,000", "5m", "30,205,543", "−12,000", "750k", "2.5m", "90,000", "400,000", "1m", "10m", ""]
DATES = ["2026-10-14", "2026-10-13", "2026-10-12", "2026-10-09", "2026-10-07", "2026-09-30", "2026-09-15", "2026-10-01", "2026-10-14", "2025-12-31", "2026-10-10", "2026-10-02"]
OWNERS = ["alice", "bob", "•••", "carol", "•••", "alice", "dave", "bob", "•••", "carol", "alice", ""]


def trades(rows=None):
    rows = rows or [[f"T{i + 1:02d}", DESKS[i], AMOUNTS[i], DATES[i], OWNERS[i]] for i in range(12)]
    return {"id": "tbl", "kind": "table", "title": "Trades", "area": "main", "data": table(["Id", "Desk", "Notional", "Booked", "Owner"], [False, False, True, False, False], rows)}


def big():
    rows = [[f"G{i % 6}", str(i), f"2026-01-{i % 28 + 1:02d}"] for i in range(60)]
    return {"id": "big", "kind": "table", "title": "Many", "area": "main", "data": table(["Group", "Value", "Day"], [False, True, False], rows)}


def tree_row(name, size, *kids):
    return {"cells": [cell(name), cell(size)], "highlight": False, "children": list(kids)}


def tree():
    rows = [tree_row("Group", "999", tree_row("Desk A", "100", tree_row("Book 1", "30"), tree_row("Book 2", "20")), tree_row("Desk B", "50", tree_row("Book 3", "5"))),
            tree_row("Loose", "7")]
    return {"id": "org", "kind": "table", "title": "Org", "area": "main", "data": {"columns": ["Name", "Size"], "numeric": [False, True], "search": True, "expand": 3, "rows": rows}}


def pivot():
    def r(label, vals, total):
        return {"label": label, "cells": [cell(v) for v in vals], "values": [float(v) for v in vals], "total": cell(total)}
    return {"id": "pvt", "kind": "pivot", "title": "By desk", "area": "main",
            "data": {"by": "desk", "columns": ["USD", "EUR"], "agg": "sum", "heat": False, "more": 0,
                     "rows": [r("Rates", ["10", "5"], "15"), r("FX", ["7", "1"], "8"), r("Credit", ["3", "0"], "3")], "totals": [cell("20"), cell("6"), cell("26")]}}


class FilterBackend(TickingBackend):
    """A view with a table of trades (numbers, dates, text, blanks, masks), a long table, a tree and a pivot; `rows` is what the live patch sends."""

    def __init__(self):
        super().__init__()
        self.queued = []                                       # panels to patch in; each open stream sends the ones added after it began

    async def view(self, kind, id_, user):
        vm = await super().view(kind, id_, user)
        vm["panels"] = [trades(), big(), tree(), pivot()]
        return vm

    async def stream(self, kind, id_, ident=None, opened=None):
        yield "view", json.dumps({"provenance": {"generation": 1}})
        n, sent = 0, len(self.queued)
        while True:
            await asyncio.sleep(0.15)
            n += 1
            patches = [{"op": "strip", "index": 0, "cell": {"label": "Tick", "text": f"#{n}"}}]
            patches += [{"op": "panel", "panel": p} for p in self.queued[sent:]]
            sent = len(self.queued)
            yield "frame", json.dumps({"seq": n, "generation": n + 1, "p99Ms": 1.0, "latencyMs": 0.5, "patches": patches})


@pytest.fixture(scope="module")
def backend():
    return FilterBackend()


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


def push(page, backend, panel):
    """Sends a replaced panel down the view's stream (once the stream is running), and waits until the page has swapped its table in."""
    page.wait_for_function("document.querySelector('.strip') && document.querySelector('.strip').textContent.includes('#')", timeout=10000)
    page.evaluate("(id) => document.querySelector('#p-' + id + ' table').setAttribute('data-old', '1')", panel["id"])
    backend.queued.append(panel)
    page.wait_for_function("(id) => { const t = document.querySelector('#p-' + id + ' table'); return t && !t.hasAttribute('data-old'); }", arg=panel["id"], timeout=10000)


def pin_today(page):
    page.evaluate(f"window.drishtiModules.colFilter.today = () => Date.parse('{TODAY}') / 864e5")


@pytest.fixture
def page(browser, url):
    ctx = browser.new_context(viewport={"width": 1280, "height": 900}, bypass_csp=True)
    pg = ctx.new_page()
    pg.goto(url + VIEW, wait_until="load")
    pg.wait_for_selector("#p-tbl .cf-btn", state="attached")
    pin_today(pg)
    yield pg
    ctx.close()


def col(page, name, panel="tbl"):
    """The text of one column's cells in the rows that are shown."""
    return page.evaluate("""([p, name]) => { const t = document.querySelector('#p-' + p + ' table'), i = [...t.tHead.rows[0].cells].findIndex(h => h.dataset.cfName === name || h.textContent.trim() === name);
        return [...t.tBodies[0].rows].filter(r => !r.hidden).map(r => r.cells[i].textContent.replace(/[▸▾]/g, '').trim()); }""", [panel, name])


def ids(page):
    return col(page, "Id")


def menu_for(page, name, panel="tbl"):
    page.locator(f"#p-{panel} th", has_text=name).first.locator(".cf-btn").click()
    m = page.locator(".cf-menu")
    m.wait_for()
    return m


def condition(page, name, label, a=None, b=None, panel="tbl"):
    m = menu_for(page, name, panel)
    m.locator(".cf-op").select_option(label=label)
    if a is not None:
        m.locator(".cf-a").fill(a)
    if b is not None:
        m.locator(".cf-b").fill(b)
    m.get_by_role("button", name="OK").click()
    page.wait_for_selector(".cf-menu", state="detached")


def tick(page, names, name, panel="tbl"):
    """Filter a column to exactly these values from its checklist."""
    m = menu_for(page, name, panel)
    allbox = m.locator(".cf-all input")
    if allbox.is_checked():
        allbox.uncheck()
    else:
        allbox.check()
        allbox.uncheck()
    for n in names:
        m.locator(".cf-item", has=page.locator(f"text='{n}'")).locator("input").check()
    m.get_by_role("button", name="OK").click()
    page.wait_for_selector(".cf-menu", state="detached")


# ---- the logic (run in the page: there is no JS test runner) -------------------------------------------------------------------

def test_value_types_are_detected_from_the_cells(page):
    r = page.evaluate("""() => { const c = window.drishtiModules.colFilter;
        return [c.detect(['1', '2.5', '1m', '−3', '', '•••']), c.detect(['2026-01-02', '2026-03-04 10:00', '']), c.detect(['a', 'b', '1']), c.detect([]),
                c.detect(['1', '2', '3', '4', 'n/a']), c.kindOf('•••'), c.kindOf(' '), c.kindOf('12%'), c.kindOf('2026-13-40')]; }""")
    assert r == ["number", "date", "text", "text", "number", "mask", "blank", "num", "text"]


def test_number_conditions(page):
    r = page.evaluate("""() => { const c = window.drishtiModules.colFilter, col = ['1,000', '250k', '5m', '−12,000', '', '•••', 'n/a'];
        const ok = (op, a, b) => { const cond = {op, a, b}, x = c.context(cond, col); return col.map(t => c.test('number', cond, t, x)); };
        return { eq: ok('eq', '250000'), ne: ok('ne', '250k'), gt: ok('gt', '1000'), ge: ok('ge', '1000'), lt: ok('lt', '0'), le: ok('le', '1000'),
                 bt: ok('bt', '0', '300k'), top: ok('top', '2'), bot: ok('bot', '1'), avga: ok('avga'), avgb: ok('avgb') }; }""")
    assert r["eq"] == [False, True, False, False, False, False, False]
    assert r["ne"] == [True, False, True, True, True, False, True]                  # a blank or a word is "not equal", as in Excel; a masked value never matches
    assert r["gt"] == [False, True, True, False, False, False, False]
    assert r["ge"] == [True, True, True, False, False, False, False]
    assert r["lt"] == [False, False, False, True, False, False, False]
    assert r["le"] == [True, False, False, True, False, False, False]
    assert r["bt"] == [True, True, False, False, False, False, False]
    assert r["top"] == [False, True, True, False, False, False, False]
    assert r["bot"] == [False, False, False, True, False, False, False]
    assert r["avga"][2] and not r["avga"][3]                                         # the average of 1000, 250000, 5m, -12000 is 1.31m
    assert r["avgb"][0] and not r["avgb"][2]


def test_top_n_takes_the_n_largest_and_bottom_n_the_n_smallest(page):
    r = page.evaluate("""() => { const c = window.drishtiModules.colFilter, col = ['5', '9', '1', '7', '3'];
        const run = (op, a) => { const cond = {op, a}, x = c.context(cond, col); return col.filter(t => c.test('number', cond, t, x)); };
        return [run('top', '2'), run('bot', '2'), run('top', '99'), run('top', '')]; }""")
    assert r == [["9", "7"], ["1", "3"], ["5", "9", "1", "7", "3"], ["5", "9", "1", "7", "3"]]


def test_date_conditions_against_a_pinned_today(page):
    r = page.evaluate("""() => { const c = window.drishtiModules.colFilter, ds = ['2026-10-14', '2026-10-13', '2026-10-12', '2026-10-09', '2026-10-07', '2026-09-30', '2026-09-14', '2026-09-15', '2025-12-31', ''];
        const run = (op, a, b) => { const cond = {op, a, b}, x = c.context(cond, ds); return ds.filter(t => c.test('date', cond, t, x)); };
        return { today: run('today'), yest: run('yest'), thisweek: run('thisweek'), lastweek: run('lastweek'), thismonth: run('thismonth'), lastmonth: run('lastmonth'),
                 last7: run('last7'), last30: run('last30'), bt: run('bt', '2026-10-09', '2026-10-13'), bf: run('bf', '2026-10-01'), af: run('af', '2026-10-12') }; }""")
    assert r["today"] == ["2026-10-14"] and r["yest"] == ["2026-10-13"]
    assert r["thisweek"] == ["2026-10-14", "2026-10-13", "2026-10-12"]                 # Monday 12 to Sunday 18 October
    assert r["lastweek"] == ["2026-10-09", "2026-10-07"]                                 # Monday 5 to Sunday 11 October
    assert "2026-10-07" in r["thismonth"] and "2026-09-30" not in r["thismonth"]
    assert r["lastmonth"] == ["2026-09-30", "2026-09-14", "2026-09-15"]
    assert r["last7"] == ["2026-10-14", "2026-10-13", "2026-10-12", "2026-10-09"]                 # 8 to 14 October
    assert "2026-09-14" not in r["last30"] and "2026-09-15" in r["last30"] and "2025-12-31" not in r["last30"]
    assert r["bt"] == ["2026-10-13", "2026-10-12", "2026-10-09"]
    assert r["bf"] == ["2026-09-30", "2026-09-14", "2026-09-15", "2025-12-31"]
    assert r["af"] == ["2026-10-14", "2026-10-13"]


def test_text_conditions_and_a_masked_value_never_matches(page):
    r = page.evaluate("""() => { const c = window.drishtiModules.colFilter, col = ['Alice Smith', 'bob', '', '•••'];
        const run = (op, a) => { const cond = {op, a}; return col.map(t => c.test('text', cond, t, {})); };
        return { eq: run('eq', 'BOB'), ne: run('ne', 'bob'), ct: run('ct', 'ICE'), nc: run('nc', 'ice'), bw: run('bw', 'ali'), ew: run('ew', 'mith') }; }""")
    assert r["eq"] == [False, True, False, False] and r["ne"] == [True, False, True, False]
    assert r["ct"] == [True, False, False, False] and r["nc"] == [False, True, True, False]
    assert r["bw"] == [True, False, False, False] and r["ew"] == [True, False, False, False]


def test_the_compact_url_form_round_trips(page):
    r = page.evaluate("""() => { const c = window.drishtiModules.colFilter, fs = [{ v: ['a b', 'c,d', 'e~f', 'g:h'], b: true, m: false }, { v: [], b: false, m: true },
        { c: { op: 'gt', a: '1m' } }, { c: { op: 'bt', a: '2026-01-01', b: '2026-02-01' } }, { c: { op: 'today' } }, { c: { op: 'top', a: '5' } }, { c: { op: 'ct', a: 'x~y,z' } }];
        return fs.map(f => [c.encode(f), JSON.stringify(c.decode(c.encode(f))) === JSON.stringify(f)]); }""")
    assert all(ok for _, ok in r), r
    assert r[2][0] == "gt:1m" and r[4][0] == "today" and r[3][0] == "bt:2026-01-01~2026-02-01"
    assert page.evaluate("window.drishtiModules.colFilter.decode('')") is None


# ---- the menu --------------------------------------------------------------------------------------------------------------

def test_every_header_has_a_funnel_that_opens_a_menu_with_counts_select_all_and_blanks(page):
    assert page.locator("#p-tbl th .cf-btn").count() == 5
    b = page.locator("#p-tbl th", has_text="Desk").locator(".cf-btn")
    assert b.get_attribute("aria-label") == "Filter Desk" and b.get_attribute("aria-haspopup") == "dialog" and b.get_attribute("aria-expanded") == "false"
    b.click()
    m = page.locator(".cf-menu")
    assert b.get_attribute("aria-expanded") == "true" and m.get_attribute("role") == "dialog"
    items = m.locator(".cf-list .cf-item")
    assert items.all_inner_texts() == ["Credit\n3", "Equity\n2", "FX\n3", "Rates\n4"]
    assert m.locator(".cf-all").inner_text().strip() == "(Select all)"
    assert [x.strip() for x in m.locator(".cf-sort .cf-act").all_inner_texts()] == ["Sort A→Z", "Sort Z→A"]
    assert [o.strip() for o in m.locator(".cf-op option").all_inner_texts()][:3] == ["(no condition)", "Equals", "Does not equal"]


def test_a_checklist_filter_hides_rows_and_the_funnel_shows_what_it_does(page):
    tick(page, ["FX", "Equity"], "Desk")
    assert ids(page) == ["T03", "T04", "T08", "T09", "T10"]
    b = page.locator("#p-tbl th", has_text="Desk").locator(".cf-btn")
    assert "cf-on" in b.get_attribute("class") and b.get_attribute("title").startswith("Filtered: 2 values: Equity, FX")
    assert "filtered" in b.get_attribute("aria-label")
    assert page.locator("#p-tbl th", has_text="Desk").locator(".bi-funnel-fill").count() == 1
    assert page.locator("#p-tbl .tbl-pg-info").inner_text() == "5 of 12 rows (filtered)"
    assert page.locator(".cf-live").inner_text().strip() == "5 of 12 rows (filtered)"


def test_the_checklist_search_leaves_its_results_ticked_and_select_all_follows_the_search(page):
    m = menu_for(page, "Id")
    m.locator(".cf-q").fill("t0")
    assert [x for x in m.locator(".cf-item:not([hidden]) .cf-t").all_inner_texts()] == ["(Select all)"] + [f"T0{i}" for i in range(1, 10)]
    m.get_by_role("button", name="OK").click()
    assert len(ids(page)) == 9 and page.locator(".cf-btn.cf-on").count() == 1


def test_blanks_and_masked_values_are_their_own_entries_and_a_masked_value_is_never_listed(page):
    m = menu_for(page, "Owner")
    texts = m.locator(".cf-item .cf-t").all_inner_texts()
    assert "(Blanks)" in texts and "(masked)" in texts and "•••" not in texts
    assert m.locator(".cf-item", has_text="(masked)").locator(".cf-n").inner_text() == "3"
    assert m.locator(".cf-item", has_text="(Blanks)").locator(".cf-n").inner_text() == "1"
    m.get_by_role("button", name="Cancel").click()
    tick(page, ["(masked)"], "Owner")
    assert ids(page) == ["T03", "T05", "T09"]
    assert col(page, "Owner") == ["•••", "•••", "•••"]
    tick(page, ["(Blanks)", "bob"], "Owner")
    assert ids(page) == ["T02", "T08", "T12"]


def test_a_masked_value_never_satisfies_a_condition(page):
    condition(page, "Owner", "Contains", "•")
    assert ids(page) == []
    condition(page, "Owner", "Does not equal", "zed")
    assert "T03" not in ids(page) and "T05" not in ids(page) and "T01" in ids(page)


def test_number_conditions_in_the_menu(page):
    condition(page, "Notional", "Greater than", "1m")
    assert ids(page) == ["T03", "T04", "T07", "T11"]
    condition(page, "Notional", "Between", "100k", "1m")
    assert ids(page) == ["T01", "T02", "T06", "T09", "T10"]
    condition(page, "Notional", "Top N", "3")
    assert ids(page) == ["T03", "T04", "T11"]
    condition(page, "Notional", "Bottom N", "2")
    assert ids(page) == ["T05", "T08"]
    condition(page, "Notional", "Above average")
    assert ids(page) == ["T03", "T04", "T11"]
    b = page.locator("#p-tbl th", has_text="Notional").locator(".cf-btn")
    assert b.get_attribute("title").startswith("Filtered: Above average")


def test_date_conditions_in_the_menu(page):
    m = menu_for(page, "Booked")
    assert [o.strip() for o in m.locator(".cf-op option").all_inner_texts()][1:4] == ["Today", "Yesterday", "This week"]
    m.get_by_role("button", name="Cancel").click()
    assert [x.strip() for x in menu_for(page, "Booked").locator(".cf-sort .cf-act").all_inner_texts()] == ["Sort oldest→newest", "Sort newest→oldest"]
    page.keyboard.press("Escape")
    condition(page, "Booked", "Today")
    assert ids(page) == ["T01", "T09"]
    condition(page, "Booked", "This week")
    assert ids(page) == ["T01", "T02", "T03", "T09"]
    condition(page, "Booked", "Last 7 days")
    assert ids(page) == ["T01", "T02", "T03", "T04", "T09", "T11"]
    condition(page, "Booked", "Before", "2026-10-01")
    assert ids(page) == ["T06", "T07", "T10"]
    m = menu_for(page, "Booked")
    m.locator(".cf-op").select_option(label="Between")
    assert m.locator(".cf-a").get_attribute("type") == "date" and m.locator(".cf-b").is_visible()
    m.get_by_role("button", name="Cancel").click()


def test_text_conditions_in_the_menu(page):
    condition(page, "Desk", "Begins with", "ra")
    assert ids(page) == ["T01", "T02", "T07", "T11"]
    condition(page, "Desk", "Ends with", "dit")
    assert ids(page) == ["T05", "T06", "T12"]
    condition(page, "Desk", "Does not contain", "x")
    assert "T03" not in ids(page) and "T01" in ids(page)
    condition(page, "Desk", "Equals", "equity")
    assert ids(page) == ["T09", "T10"]


def test_a_condition_without_its_value_is_refused_and_a_number_must_be_a_number(page):
    m = menu_for(page, "Notional")
    m.locator(".cf-op").select_option(label="Greater than")
    m.get_by_role("button", name="OK").click()
    assert m.locator(".cf-err").inner_text() == "Enter a value." and m.is_visible()
    m.locator(".cf-a").fill("abc")
    m.get_by_role("button", name="OK").click()
    assert "number" in m.locator(".cf-err").inner_text()
    m.get_by_role("button", name="Cancel").click()
    assert ids(page) == [f"T{i:02d}" for i in range(1, 13)]


def test_filters_combine_across_columns_and_with_the_quick_filter(page):
    tick(page, ["Rates", "FX"], "Desk")
    condition(page, "Notional", "Greater than", "500k")
    assert ids(page) == ["T01", "T03", "T04", "T07", "T11"]
    page.fill("#p-tbl .tbl-pg-q", "2026-10-1")
    assert ids(page) == ["T01", "T03", "T11"]
    page.fill("#p-tbl .tbl-pg-q", "")
    # the checklist of a column lists only what the other filters leave
    m = menu_for(page, "Desk")
    assert m.locator(".cf-list .cf-t").all_inner_texts() == ["Credit", "Equity", "FX", "Rates"]
    assert m.locator(".cf-item", has_text="Rates").locator(".cf-n").inner_text() == "3"
    m.get_by_role("button", name="Cancel").click()


def test_the_footer_the_clear_button_and_the_clear_key(page):
    info = page.locator("#p-tbl .tbl-pg-info")
    assert info.inner_text() == "1–12 of 12" and page.locator("#p-tbl .tbl-clear").is_hidden()
    tick(page, ["Rates"], "Desk")
    assert info.inner_text() == "4 of 12 rows (filtered)" and page.locator("#p-tbl .tbl-clear").is_visible()
    page.locator("#p-tbl .tbl-clear").click()
    assert info.inner_text() == "1–12 of 12" and page.locator("#p-tbl .cf-btn.cf-on").count() == 0
    tick(page, ["Rates"], "Desk")
    condition(page, "Notional", "Greater than", "1m")
    page.fill("#p-tbl .tbl-pg-q", "T")
    page.locator("#p-tbl th", has_text="Id").focus()
    page.keyboard.press("Alt+Shift+X")
    assert info.inner_text() == "1–12 of 12" and page.input_value("#p-tbl .tbl-pg-q") == "" and len(ids(page)) == 12
    assert page.evaluate("location.search") == ""


def test_a_change_of_filters_is_announced_on_the_table_for_a_server_paged_one(page):
    page.evaluate("window.__events = []; document.addEventListener('drishti:column-filter', e => window.__events.push(e.detail))")
    tick(page, ["FX"], "Desk")
    condition(page, "Notional", "Greater than", "1m")
    page.locator("#p-tbl .tbl-clear").click()
    ev = page.evaluate("window.__events")
    assert ev[0] == {"panel": "tbl", "filters": {"Desk": "in:FX"}}
    assert ev[1] == {"panel": "tbl", "filters": {"Desk": "in:FX", "Notional": "gt:1m"}}
    assert ev[-1] == {"panel": "tbl", "filters": {}}


def test_the_menu_sorts_by_value(page):
    m = menu_for(page, "Notional")
    m.get_by_role("button", name="Sort smallest→largest").click()
    assert col(page, "Notional")[:3] == ["−12,000", "90,000", "250,000"] and col(page, "Notional")[-1] == ""
    assert page.locator("#p-tbl th", has_text="Notional").get_attribute("aria-sort") == "ascending"
    m = menu_for(page, "Notional")
    assert m.get_by_role("button", name="Sort smallest→largest").get_attribute("aria-pressed") == "true"
    m.get_by_role("button", name="Sort largest→smallest").click()
    assert col(page, "Notional")[0] == "30,205,543"


def test_a_filter_works_with_paging_and_clears_to_the_full_set(page):
    info = page.locator("#p-big .tbl-pg-info")
    assert info.inner_text().startswith("1–25 of 60")
    tick(page, ["G1", "G2"], "Group", "big")
    assert info.inner_text() == "20 of 60 rows (filtered)"
    condition(page, "Value", "Greater than", "30", panel="big")
    assert info.inner_text().startswith("10 of 60 rows (filtered)")
    assert len(col(page, "Value", "big")) == 10
    page.locator("#p-big .tbl-clear").click()
    assert info.inner_text().startswith("1–25 of 60")


def test_a_filter_survives_a_live_patch_and_rows_enter_and_leave_it(page, backend):
    tick(page, ["Rates"], "Desk")
    assert ids(page) == ["T01", "T02", "T07", "T11"]
    rows = [[f"T{i + 1:02d}", DESKS[i], AMOUNTS[i], DATES[i], OWNERS[i]] for i in range(12)]
    rows[0][1] = "FX"                                           # T01 stops matching
    rows[2][1] = "Rates"                                        # T03 starts matching
    rows.append(["T13", "Rates", "1", "2026-10-14", "zed"])    # a new row that matches
    push(page, backend, trades(rows))
    page.wait_for_function("document.querySelectorAll('#p-tbl .tbl-pg-info')[0].textContent.startsWith('5 of 13')", timeout=5000)
    assert ids(page) == ["T02", "T03", "T07", "T11", "T13"]
    assert page.locator("#p-tbl th", has_text="Desk").locator(".cf-btn").get_attribute("class").count("cf-on") == 1
    assert page.evaluate("location.search").startswith("?f.tbl.Desk=")


def test_an_open_menu_stays_open_through_a_live_patch_and_applies_to_the_new_table(page, backend):
    m = menu_for(page, "Desk")
    push(page, backend, trades())
    assert m.is_visible()
    m.locator(".cf-all input").uncheck()
    m.locator(".cf-item", has_text="Credit").locator("input").check()
    m.get_by_role("button", name="OK").click()
    assert ids(page) == ["T05", "T06", "T12"]


# ---- the address, the keyboard, a phone -----------------------------------------------------------------------------------

def test_filters_are_in_the_address_and_a_reload_or_a_shared_link_reproduces_them(page, url):
    tick(page, ["FX", "Credit"], "Desk")
    condition(page, "Notional", "Greater than", "100k")
    q = page.evaluate("location.search")
    assert "f.tbl.Desk=in%3ACredit%2CFX" in q
    assert "f.tbl.Notional=gt" in q
    expect_ids = ids(page)
    page.reload(wait_until="load")
    page.wait_for_selector("#p-tbl .cf-btn", state="attached")
    assert ids(page) == expect_ids and page.locator("#p-tbl .cf-btn.cf-on").count() == 2
    assert page.locator("#p-tbl .tbl-pg-info").inner_text().endswith("rows (filtered)")
    other = page.context.new_page()
    other.goto(url + VIEW + q, wait_until="load")
    other.wait_for_selector("#p-tbl .cf-btn", state="attached")
    assert ids(other) == expect_ids
    page.locator("#p-tbl .tbl-clear").click()
    assert page.evaluate("location.search") == ""


def test_a_bad_or_unknown_filter_in_the_address_is_ignored(browser, url):
    ctx = browser.new_context(viewport={"width": 1280, "height": 900}, bypass_csp=True)
    pg = ctx.new_page()
    pg.goto(url + VIEW + "?f.tbl.Nope=eq:1&f.nopanel.Desk=in:FX&f.tbl.Desk=%00%00&f.tbl=eq:1", wait_until="load")
    pg.wait_for_selector("#p-tbl .cf-btn", state="attached")
    assert len(ids(pg)) == 12 and pg.locator("#p-tbl .cf-btn.cf-on").count() == 0
    ctx.close()


def test_the_remember_switch_is_off_unless_the_page_asks_for_it(browser, url):
    ctx = browser.new_context(viewport={"width": 1280, "height": 900}, bypass_csp=True)
    pg = ctx.new_page()
    pg.goto(url + VIEW, wait_until="load")
    pg.wait_for_selector("#p-tbl .cf-btn", state="attached")
    tick(pg, ["Rates"], "Desk")
    assert pg.evaluate("localStorage.getItem('drishti.columnFilters')") is None            # off by default: nothing is kept
    pg.goto(url + VIEW, wait_until="load")
    pg.wait_for_selector("#p-tbl .cf-btn", state="attached")
    assert len(ids(pg)) == 12                                                               # and a fresh visit starts unfiltered
    pg.evaluate("localStorage.setItem('drishti.rememberFilters', 'on')")                  # the per-user switch, on
    tick(pg, ["FX"], "Desk")
    assert "FX" in pg.evaluate("localStorage.getItem('drishti.columnFilters')")
    pg.goto(url + VIEW, wait_until="load")                                                  # no filter in the address: the remembered one returns
    pg.wait_for_selector("#p-tbl .cf-btn", state="attached")
    assert ids(pg) == ["T03", "T04", "T08"] and "f.tbl.Desk" in pg.evaluate("location.search")
    pg.locator("#p-tbl .tbl-clear").click()
    assert pg.evaluate("localStorage.getItem('drishti.columnFilters')") in (None, "{}")
    ctx.close()


def test_keyboard_only_open_search_tick_apply_and_escape(page):
    h = page.locator("#p-tbl th", has_text="Desk").locator(".cf-btn")
    h.focus()
    page.keyboard.press("Enter")
    m = page.locator(".cf-menu")
    m.wait_for()
    assert page.evaluate("document.activeElement.classList.contains('cf-act')")        # focus moves into the menu
    page.keyboard.press("Escape")
    page.wait_for_selector(".cf-menu", state="detached")
    assert page.evaluate("document.activeElement === document.querySelector('#p-tbl th:nth-child(2) .cf-btn')")
    page.keyboard.press("Space")
    m.wait_for()
    for _ in range(3):
        page.keyboard.press("Tab")                                                       # sort buttons, then the condition box
    m.locator(".cf-q").focus()
    page.keyboard.press("ArrowDown")                                                     # into the list: Select all
    assert page.evaluate("document.activeElement.closest('.cf-all') !== null")
    page.keyboard.press("Space")                                                         # clears everything
    page.keyboard.press("ArrowDown")                                                     # Credit
    page.keyboard.press("ArrowDown")                                                     # Equity
    page.keyboard.press("ArrowDown")                                                     # FX
    page.keyboard.press("Space")
    page.keyboard.press("Enter")                                                         # Enter applies
    page.wait_for_selector(".cf-menu", state="detached")
    assert ids(page) == ["T03", "T04", "T08"]
    # type-ahead jumps to the value that begins with the letters typed
    page.locator("#p-tbl th", has_text="Desk").locator(".cf-btn").focus()
    page.keyboard.press("Enter")
    m.wait_for()
    m.locator(".cf-all input").focus()
    page.keyboard.type("eq")
    assert page.evaluate("document.activeElement.closest('.cf-item').textContent.startsWith('Equity')")
    page.keyboard.press("Tab")
    for _ in range(40):                                                                  # Tab stays inside the menu
        page.keyboard.press("Tab")
        assert page.evaluate("document.activeElement.closest('.cf-menu') !== null")
    page.keyboard.press("Escape")


def test_escape_closes_the_menu_first_and_only_then_the_zoom(page):
    page.locator("#p-tbl .pnl-zoom-btn").click()
    page.wait_for_selector("#p-tbl.pnl-zoom")
    menu_for(page, "Desk")
    page.keyboard.press("Escape")
    page.wait_for_selector(".cf-menu", state="detached")
    assert page.evaluate("document.querySelector('#p-tbl').classList.contains('pnl-zoom')")
    page.keyboard.press("Escape")
    page.wait_for_function("!document.querySelector('#p-tbl').classList.contains('pnl-zoom')")


def test_a_zoomed_table_fits_its_height_with_a_filter_on(page):
    page.locator("#p-big .pnl-zoom-btn").click()
    page.wait_for_selector("#p-big.pnl-zoom")
    tick(page, ["G0", "G1", "G2"], "Group", "big")
    page.wait_for_timeout(400)
    info = page.locator("#p-big .tbl-pg-info").inner_text()
    assert info.startswith("30 of 60 rows (filtered)")
    box = page.evaluate("(() => { const b = document.querySelector('#p-big .pnl-b'); return b.scrollHeight <= b.clientHeight + 1; })()")
    assert box
    menu_for(page, "Value", "big")
    r = page.evaluate("(() => { const m = document.querySelector('.cf-menu').getBoundingClientRect(); return m.left >= 0 && m.right <= innerWidth && m.top >= 0 && m.bottom <= innerHeight; })()")
    assert r
    page.keyboard.press("Escape")


def test_screen_reader_labels_and_a_polite_live_region(page):
    tick(page, ["Rates"], "Desk")
    live = page.locator(".cf-live")
    assert live.get_attribute("aria-live") == "polite" and live.get_attribute("role") == "status"
    assert "4 of 12 rows (filtered)" in live.inner_text()
    m = menu_for(page, "Notional")
    assert m.get_attribute("aria-label") == "Filter Notional"
    assert m.locator(".cf-q").get_attribute("aria-label") == "Search the values of Notional"
    assert m.locator(".cf-list").get_attribute("aria-label") == "Values of Notional"
    m.locator(".cf-op").select_option(label="Between")
    assert m.locator(".cf-a").get_attribute("aria-label") == "From" and m.locator(".cf-b").get_attribute("aria-label") == "To"
    m.locator(".cf-op").select_option(label="Top N")
    assert m.locator(".cf-a").get_attribute("aria-label") == "How many items" and m.locator(".cf-a").input_value() == "10"
    page.keyboard.press("Escape")


def test_the_funnel_is_shown_on_hover_and_focus_for_a_mouse_and_a_heading_click_still_sorts(page):
    b = page.locator("#p-tbl th", has_text="Notional").locator(".cf-btn")
    op = lambda: float(page.evaluate("getComputedStyle(document.querySelector('#p-tbl th:nth-child(3) .cf-btn')).opacity"))
    page.mouse.move(5, 5)
    assert op() == 0
    page.locator("#p-tbl th", has_text="Notional").hover()
    assert op() == 1
    page.locator("#p-tbl th", has_text="Notional").click(position={"x": 8, "y": 8})
    assert page.locator("#p-tbl th", has_text="Notional").get_attribute("aria-sort") == "ascending"
    b.focus()
    page.keyboard.press("Enter")
    assert page.locator(".cf-menu").is_visible() and page.locator("#p-tbl th", has_text="Notional").get_attribute("aria-sort") == "ascending"
    page.keyboard.press("Escape")


# ---- tree rows and pivots -------------------------------------------------------------------------------------------------

def test_tree_rows_keep_a_parent_when_any_child_matches_and_open_it(page):
    assert page.locator("#p-org .tbl-tree .cf-btn").count() == 2
    condition(page, "Size", "Less than", "10", panel="org")
    shown = col(page, "Name", "org")
    assert shown == ["Group", "Desk B", "Book 3", "Loose"]                              # Book 3 and Loose match; Group and Desk B stay as its ancestors, open
    assert page.locator("#p-org .tr-count").inner_text() == "2 of 7 rows (filtered)"
    assert page.locator("#p-org .tbl-tree .tr-tog").first.get_attribute("aria-expanded") == "true"
    condition(page, "Size", "Equals", "30", panel="org")
    assert col(page, "Name", "org") == ["Group", "Desk A", "Book 1"]
    page.fill("#p-org .tr-q", "book")
    assert col(page, "Name", "org") == ["Group", "Desk A", "Book 1"]
    page.fill("#p-org .tr-q", "loose")
    assert col(page, "Name", "org") == []
    page.locator("#p-org .tbl-clear").click()
    assert col(page, "Name", "org")[0] == "Group" and page.locator("#p-org .tr-count").inner_text() == ""
    assert page.locator("#p-org .tbl-tree .cf-on").count() == 0


def test_tree_filters_are_in_the_address_and_survive_a_live_patch(page, backend):
    tick(page, ["Book 1", "Book 3"], "Name", "org")
    assert col(page, "Name", "org") == ["Group", "Desk A", "Book 1", "Desk B", "Book 3"]
    assert "f.org.Name=in" in page.evaluate("location.search")
    push(page, backend, tree())
    page.wait_for_function("document.querySelector('#p-org .tbl-tree .cf-on')", timeout=5000)
    assert col(page, "Name", "org") == ["Group", "Desk A", "Book 1", "Desk B", "Book 3"]


def test_a_pivot_has_column_filters_on_its_rows_and_values(page):
    assert page.locator("#p-pvt th .cf-btn").count() == 4
    assert col(page, "Desk", "pvt") == ["Rates", "FX", "Credit"]
    condition(page, "USD", "Greater than", "5", panel="pvt")
    assert col(page, "Desk", "pvt") == ["Rates", "FX"]
    tick(page, ["FX"], "Desk", "pvt")
    assert col(page, "Desk", "pvt") == ["FX"]
    assert page.locator("#p-pvt .tbl-pg-info").inner_text() == "1 of 3 rows (filtered)"


# ---- a phone ---------------------------------------------------------------------------------------------------------------

@pytest.fixture
def phone(browser, url):
    ctx = browser.new_context(viewport={"width": 390, "height": 844}, has_touch=True, is_mobile=True, device_scale_factor=2, bypass_csp=True)
    pg = ctx.new_page()
    pg.goto(url + VIEW, wait_until="load")
    pg.wait_for_selector("#p-tbl .cf-btn", state="attached")
    pin_today(pg)
    yield pg
    ctx.close()


def test_on_a_phone_the_funnel_is_always_shown_finger_sized_and_the_menu_fits(phone):
    b = phone.locator("#p-tbl th", has_text="Desk").locator(".cf-btn")
    box = b.bounding_box()
    assert box["width"] >= 44 and box["height"] >= 44
    assert float(phone.evaluate("getComputedStyle(document.querySelector('#p-tbl .cf-btn')).opacity")) == 1
    b.tap()
    m = phone.locator(".cf-menu")
    m.wait_for()
    r = m.bounding_box()
    assert r["x"] >= 0 and r["x"] + r["width"] <= 390 and r["y"] >= 0 and r["y"] + r["height"] <= 844
    for sel in (".cf-item", ".cf-act", ".cf-op", ".cf-q"):
        assert m.locator(sel).first.bounding_box()["height"] >= 43.5, sel
    m.locator(".cf-all input").tap()
    m.locator(".cf-item", has_text="FX").locator("input").tap()
    m.get_by_role("button", name="OK").tap()
    phone.wait_for_selector(".cf-menu", state="detached")
    assert ids(phone) == ["T03", "T04", "T08"]
    assert phone.evaluate("document.documentElement.scrollWidth <= innerWidth + 1")
    assert phone.locator("#p-tbl .tbl-clear").is_visible()
    phone.locator("#p-tbl .tbl-clear").tap()
    assert len(ids(phone)) == 12


def test_on_a_phone_a_condition_menu_is_usable_and_nothing_scrolls_sideways(phone):
    phone.locator("#p-tbl th", has_text="Notional").locator(".cf-btn").tap()
    m = phone.locator(".cf-menu")
    m.locator(".cf-op").select_option(label="Between")
    m.locator(".cf-a").fill("100k")
    m.locator(".cf-b").fill("1m")
    m.get_by_role("button", name="OK").tap()
    assert ids(phone) == ["T01", "T02", "T06", "T09", "T10"]
    assert phone.evaluate("document.documentElement.scrollWidth <= innerWidth + 1")
