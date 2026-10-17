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

"""Wave B, console lane, in Chromium over the real server and console (the W1 model stack of tests/test_w1_model_designer_browser.py on this file's own ports
18926 and 17926 plus the pytest-xdist offset: security on, the trading and banking-core packs, a small file lake of trades and desks). The acceptance of
docs/architecture/BI_DESIGN.md section 12.2 for the console lane:

* no BI page computes: no WebAssembly, Pyodide, DuckDB-Wasm or Perspective file is requested, and no page's policy allows 'wasm-unsafe-eval';
* a %-of-total measure built in the UI (Quick calculation), equal to an independent computation of the same rows;
* a parameter slicer (a what-if shock made in the Calculations tab) changes a report's numbers by exactly the shock;
* a live report follows pushed answers: its state chip says Live, and a data refresh redraws the visual through the standing query's stream;
* the Explain panel under a visual shows the tiers, the layers and the times.

Skipped when Playwright, Chromium or the built server jar is missing, or a scratch port is in use. Each pytest-xdist worker adds 100 to the ports."""
from __future__ import annotations

import json
import re

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

import test_w1_model_designer_browser as w1  # noqa: E402
from bi_stack import listening  # noqa: E402

PORTS = w1.worker_ports(18926, 17926)
CONSOLE_URL = f"http://127.0.0.1:{PORTS[1]}"
sign_in, clean, fetch = w1.sign_in, w1.clean, w1.fetch
COMPUTE = re.compile(r"\.wasm(\?|$)|pyodide|duckdb|perspective|calc-worker|bi_driver", re.I)


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    w1.use_ports(PORTS)
    for port in (w1.SERVER_PORT, w1.CONSOLE_PORT):
        if listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there")
    try:
        s = w1.Stack(tmp_path_factory.mktemp("waveb")).start()
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


@pytest.fixture(scope="module")
def made(browser, stack):
    """A model over trades and desks with Sum of mtm, its % of total made with Quick calculation, a what-if parameter and a shocked measure, published;
    and a report on it (a table by desk). Built in the UI, as a designer would."""
    page = sign_in(browser, "wes")
    ws = fetch(page, "POST", "/bi/api/workspaces", {"name": "Wave B desk", "description": ""})
    assert ws["status"] in (200, 201), ws
    m = fetch(page, "POST", "/bi/model-api/new", {"title": "Wave B model", "workspace": ws["body"]["id"], "datasets": ["pack.trading.trade", "pack.banking-core.desk"]})
    assert m["status"] == 200, m
    page.goto(CONSOLE_URL + m["body"]["href"])
    expect(page.locator('[data-md-root][data-ready="yes"]')).to_have_count(1, timeout=30000)
    page.locator('[data-suggestion="trade.desk->desk.id"] [data-accept]').click()
    page.click('[data-tab="measures"]')
    page.select_option("[data-nm-alias]", "trade")
    page.select_option("[data-nm-column]", "mtm")
    page.click("[data-nm-add]")
    expect(page.locator('[data-measure="Sum of mtm"]')).to_be_visible(timeout=15000)
    # % of total, with no formula typed: the Quick calculation writes share_of(..., over=ALL)
    expect(page.locator("[data-quick-measure]")).to_be_visible(timeout=15000)
    page.select_option("[data-qm-base]", "Sum of mtm")
    page.select_option("[data-qm-calc]", "share-all")
    expect(page.locator("[data-qm-expr]")).to_have_text("share_of(measure('Sum of mtm'), over=ALL)")
    page.fill("[data-qm-name]", "MTM share")
    page.click("[data-qm-add]")
    expect(page.locator('[data-measure="MTM share"]')).to_be_visible(timeout=15000)
    # a what-if parameter in the Calculations tab, and a measure shocked by it
    page.click('[data-tab="calc"]')
    page.fill("[data-p-new]", "shock")
    page.select_option("[data-p-new-kind]", "number")
    page.click("[data-p-add]")
    expect(page.locator('[data-param="shock"]')).to_be_visible(timeout=15000)
    page.click('[data-tab="measures"]')
    page.select_option("[data-qm-base]", "Sum of mtm")
    page.select_option("[data-qm-calc]", "whatif")
    page.select_option("[data-qm-what]", "shock")
    page.fill("[data-qm-name]", "MTM shocked")
    page.click("[data-qm-add]")
    expect(page.locator('[data-measure="MTM shocked"]')).to_be_visible(timeout=15000)
    page.click("[data-save]")
    expect(page.locator("[data-state]")).to_have_text("Saved draft", timeout=15000)
    page.click("[data-publish]")
    expect(page.locator("[data-state]")).to_have_text("Published", timeout=30000)
    doc = page.evaluate("window.drishtiModel.doc")
    assert doc["measures"]["MTM share"] == {"expr": "share_of(measure('Sum of mtm'), over=ALL)", "format": "pct"}
    assert doc["measures"]["MTM shocked"]["expr"] == "measure('Sum of mtm') * (1 + param.shock)" and doc["parameters"]["shock"]["kind"] == "number"
    name = doc["model"]
    body = ("rachana: 2\nreport: wave-b-desks\nversion: 1\ntitle: Wave B desks\nmodel: " + name + "@1\npages:\n  - id: main\n    title: Main\n    visuals:\n"
            "      - { id: desks, type: table, title: MTM by desk, at: { x: 0, y: 0, w: 12, h: 6 }, wells: { columns: [trade.desk, Sum of mtm, MTM share, MTM shocked] } }\n")
    r = fetch(page, "POST", "/bi/api/workspaces/" + ws["body"]["id"] + "/items", {"kind": "report", "title": "Wave B desks", "body": body})
    assert r["status"] in (200, 201), r
    rid = r["body"]["id"]
    p = fetch(page, "POST", "/bi/api/items/" + rid + "/publish", {})
    assert p["status"] in (200, 201), p
    clean(page)
    page.context.close()
    return {"model": name, "report": rid}


def rows_of(page):
    page.wait_for_selector('[data-visual="desks"] tbody tr', timeout=30000)
    return page.eval_on_selector_all('[data-visual="desks"] tbody tr', "rs => rs.map(r => [...r.children].map(c => c.textContent.trim()))")


def num(text):
    t = text.replace(",", "").replace("%", "").replace("−", "-").strip()
    return float(t) if t not in ("", "—", "-") else 0.0


def test_no_bi_page_loads_a_compute_engine(browser, made):
    page = sign_in(browser, "wes")
    seen = []
    page.on("request", lambda r: seen.append(r.url) if COMPUTE.search(r.url) else None)
    for path in ("/bi", "/bi/explore", "/bi/r/" + made["report"], "/bi/models", "/bi/designer"):
        resp = page.goto(CONSOLE_URL + path)
        csp = resp.headers["content-security-policy"]
        assert "wasm" not in csp and "unsafe-eval" not in csp, (path, csp)
        page.wait_for_timeout(600)
        assert page.evaluate("typeof window.loadPyodide === 'undefined' && !window.duckdb && !customElements.get('perspective-viewer')"), path
    assert seen == [], seen
    clean(page)
    page.context.close()


def test_a_percent_of_total_built_in_the_ui_equals_an_independent_computation(browser, made):
    page = sign_in(browser, "wes")
    a = fetch(page, "POST", "/bi/api/mq", {"mq": 1, "model": made["model"] + "@1", "select": {"fields": ["trade.desk"], "measures": ["Sum of mtm", "MTM share"]}})
    assert a["status"] == 200, a
    rows = a["body"]["rows"]
    total = sum(r[1] for r in rows)
    assert len(rows) == 3 and total != 0
    for desk, mtm, share in rows:
        assert abs(share - mtm / total) < 1e-9, (desk, mtm, share, total)
    clean(page)
    page.context.close()


def test_a_parameter_slicer_shocks_the_report(browser, made):
    page = sign_in(browser, "wes")
    page.goto(CONSOLE_URL + "/bi/r/" + made["report"])
    page.wait_for_selector("[data-ready=yes]", state="attached", timeout=30000)
    before = rows_of(page)
    expect(page.locator('[data-param-bar] [data-param="shock"]')).to_be_visible(timeout=20000)
    page.evaluate("""() => { const r = document.querySelector('[data-param="shock"]'); r.value = '0.5'; r.dispatchEvent(new Event('input')); r.dispatchEvent(new Event('change')); }""")
    expect(page.locator('[data-chip="param"]')).to_contain_text("shock: 0.5", timeout=15000)
    page.wait_for_function("""(b) => { const r = [...document.querySelectorAll('[data-visual="desks"] tbody tr')].map(x => x.children[3].textContent.trim()); return r.length && r.join('|') !== b; }""",
                           arg="|".join(r[3] for r in before), timeout=30000)
    after = rows_of(page)
    for b, x in zip(sorted(before), sorted(after)):
        assert b[0] == x[0] and abs(num(x[3]) - num(b[1]) * 1.5) <= max(1.0, abs(num(b[1])) * 1e-6), (b, x)          # shocked = mtm * (1 + 0.5)
        assert num(x[1]) == num(b[1])                                                                                   # the unshocked measure is as it was
    clean(page)
    page.context.close()


def test_a_live_report_follows_pushed_answers_with_its_state_chip(browser, made, stack):
    page = sign_in(browser, "wes")
    page.goto(CONSOLE_URL + "/bi/r/" + made["report"])
    page.wait_for_selector("[data-ready=yes]", state="attached", timeout=30000)
    rows_of(page)
    chip = page.locator('[data-visual="desks"] .bi-state-chip')
    expect(chip).to_have_attribute("data-state", re.compile("live|tick"), timeout=30000)
    expect(chip).to_contain_text(re.compile("Live|Every few seconds"))
    # new trades land in the lake and the dataset is refreshed: the server's generation bus pushes the new answer, nothing polls
    lake = stack.tmp / "lake" / "2026-10-08" / "trade.jsonl"
    with open(lake, "a") as f:
        for i in range(3):
            f.write(json.dumps({"id": f"BBG-PUSH{i}", "tradeId": f"BBG-PUSH{i}", "desk": "DESK-FX", "book": "BOOK-0", "productType": "FX_FORWARD", "currency": "USD", "status": "Live",
                                "notional": 1_000_000.0, "mtm": 1_000_000, "pnl1d": 0, "tradeDate": "2026-10-08"}) + "\n")
    pushed0 = int(page.get_attribute("#biCanvas", "data-pushed") or 0)
    note = page.inner_text("[data-asof-note]")
    day = (re.search(r"\d{4}-\d\d-\d\d", note) or [None])[0] or "2026-10-08"
    s, r = w1.admin("POST", "/packs/trading/loads", {"kind": "trade", "businessDate": day, "status": "ready", "rows": 63, "batchId": "wave-b-push-" + day})
    assert s in (200, 201), r                                                                        # the data-load signal: the layer of that day is read again
    page.wait_for_function("(n) => Number(document.getElementById('biCanvas').getAttribute('data-pushed') || 0) > n", arg=pushed0, timeout=60000)
    assert page.locator('[data-visual="desks"]').get_attribute("data-updated")
    expect(chip).to_have_attribute("data-state", re.compile("live|tick|throttled"))
    clean(page)
    page.context.close()


def test_the_explain_panel_shows_tiers_layers_and_times(browser, made):
    page = sign_in(browser, "wes")
    page.goto(CONSOLE_URL + "/bi/r/" + made["report"])
    page.wait_for_selector("[data-ready=yes]", state="attached", timeout=30000)
    rows_of(page)
    page.click('[data-visual="desks"] [data-act="explain-plan"]')
    page.wait_for_selector('[data-visual="desks"] .bi-plan-panel:not([data-explain="loading"])', state="attached", timeout=30000)
    panel = page.locator('[data-visual="desks"] .bi-plan-panel')
    assert panel.get_attribute("data-explain") == "ready", panel.text_content()
    expect(panel.locator("[data-tier]").first).to_be_visible()
    expect(panel.locator("[data-explain-times]")).to_contain_text("ms")
    assert panel.locator("[data-explain-op]").count() >= 2
    assert "SecureScan" in panel.locator("[data-explain-text]").text_content() or panel.locator('[data-explain-op="SecureScan"]').count() >= 1
    clean(page)
    page.context.close()
