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

"""Rupaka phase 2 in Chromium, over the real server and console (tests/bi_data_stack.py: ports 18989 and 17989, security on, a small file lake read through
the connector the shipped sample ``trades-basic`` names): the datasets list shows the sample's dataset, its page (2.2: grain, columns) opens from the old link, a
model query comes back from the real engine with numbers that add up, the second run is a cache hit, a refusal has its code, a phone (390 px) has no sideways
scroll, and no policy violation occurs.

Skipped when Playwright, Chromium or the built server jar is missing, or a scratch port is in use. Never run with pytest -n (the ports are fixed)."""
import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

import bi_data_stack  # noqa: E402
from bi_data_stack import CONSOLE_URL, PASSWORD, trade  # noqa: E402

DATASET = "file:dataset:trades-basic-trade"       # the dataset the sample model trades-basic reads
MODEL = "trades-basic@1"


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    for port in (bi_data_stack.SERVER_PORT, bi_data_stack.CONSOLE_PORT):
        if bi_data_stack.listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there")
    try:
        s = bi_data_stack.DataStack(tmp_path_factory.mktemp("bidata"), delta_root="").start()
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


VIOLATIONS = "window.__csp = []; document.addEventListener('securitypolicyviolation', e => window.__csp.push(e.violatedDirective + ' ' + e.blockedURI));"


def sign_in(browser, user, **ctx):
    context = browser.new_context(**{"viewport": {"width": 1400, "height": 900}, **ctx})
    context.add_init_script(VIOLATIONS)
    page = context.new_page()
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.goto(CONSOLE_URL + "/login?next=/bi/datasets")
    page.fill("#lu", user)
    page.fill("#lp", PASSWORD)
    page.click("button[type=submit]")
    page.wait_for_url("**/bi/datasets")
    page.errors = errors
    return page


def clean(page):
    assert page.evaluate("window.__csp") == [], "a policy violation"
    assert page.errors == [], page.errors


def expected_mtm_by_desk(day="2026-10-08"):
    out = {}
    for i in range(60):
        t = trade(day, i)
        out[t["desk"]] = out.get(t["desk"], 0) + t["mtm"]
    return out


def ask(page, mq):
    """One model query from the page, as the signed-in person (the console's same-origin proxy)."""
    return page.evaluate("""async (q) => { const r = await fetch('/bi/api/mq', { method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, body: JSON.stringify(q) }); return { status: r.status, body: await r.json() }; }""", mq)


def test_the_list_shows_the_sample_and_its_page_shows_the_dataset(stack, browser):
    page = sign_in(browser, "rita")
    card = page.locator(f'[data-datasets] [data-id="{DATASET}"]')
    expect(card).to_be_visible()
    expect(card).to_contain_text("sample")
    card.locator("h3 a").click()
    page.wait_for_url("**/bi/ds/**")                              # a model-layer dataset has its own page since 2.2 (the old link is sent there)
    expect(page.locator("main h1")).to_contain_text("Trades, the basics: trade")
    expect(page.locator("main")).to_contain_text("snapshot")
    for col in ("TRADE_ID", "DESK", "NOTIONAL", "MTM"):
        expect(page.locator("main table").first).to_contain_text(col)
    clean(page)


def test_a_model_query_runs_on_the_engine_and_adds_up(stack, browser):
    page = sign_in(browser, "rita")
    q = {"mq": 1, "model": MODEL, "select": {"fields": ["trade.DESK"], "measures": ["MTM", "Trades"]}}
    a = ask(page, q)
    assert a["status"] == 200, a
    got = {r[0]: int(r[1]) for r in a["body"]["rows"]}
    assert got == expected_mtm_by_desk() and a["body"]["meta"]["layer"] == "columns"
    again = ask(page, q)                                          # the same query again: the server's cache answers
    assert again["body"]["meta"]["cached"] is True
    clean(page)


def test_a_filter_and_a_refusal_are_answered_with_their_code(stack, browser):
    page = sign_in(browser, "rita")
    one = ask(page, {"mq": 1, "model": MODEL, "select": {"fields": ["trade.CURRENCY"], "measures": ["Trades"]},
                     "where": [{"field": "trade.DESK", "op": "in", "values": ["DESK-FX"]}]})
    assert one["status"] == 200 and one["body"]["rowCount"] >= 1, one
    bad = ask(page, {"mq": 1, "model": MODEL, "select": {"fields": ["trade.nothing"], "measures": []}})
    assert bad["status"] == 422 and bad["body"]["code"].startswith("DRS-91"), bad
    old = page.evaluate("""async () => (await fetch('/bi/api/datasets/file:dataset:trades-basic/query', { method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ measures: ['MTM'] }) })).status""")
    assert old in (404, 410)                                      # the 2.1 dataset query is gone: 410 names the model query to send
    clean(page)


def test_a_phone_has_no_sideways_scroll_and_finger_sized_targets(stack, browser):
    page = sign_in(browser, "rita", viewport={"width": 390, "height": 844}, has_touch=True, is_mobile=True)
    page.goto(CONSOLE_URL + "/bi/d/" + DATASET)
    page.wait_for_url("**/bi/ds/**")
    expect(page.locator("main table").first).to_contain_text("TRADE_ID")
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1"), "the page scrolls sideways"
    for b in page.locator("main .btn-pill:visible, main a.btn-pill:visible").all():
        box = b.bounding_box()
        assert box["height"] >= 43, box
    clean(page)


def test_a_reader_without_the_bi_power_is_refused(stack, browser):
    context = browser.new_context()
    page = context.new_page()
    page.goto(CONSOLE_URL + "/login?next=/t")
    page.fill("#lu", "nobi")
    page.fill("#lp", PASSWORD)
    page.click("button[type=submit]")
    page.wait_for_url("**/t")
    assert page.goto(CONSOLE_URL + "/bi/datasets").status == 403
    assert page.goto(CONSOLE_URL + "/bi/d/" + DATASET).status == 403
    assert "BI is not available to you" in page.content()
