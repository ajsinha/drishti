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

"""Wave A of the BI design, console lane, in Chromium (docs/architecture/BI_DESIGN.md section 12.2) over the real server and console on scratch ports
18916 and 17916 (the W1 model stack of tests/test_w1_model_designer_browser.py: security on, the trading and core packs, a small file lake of
trades and desks). Checked, on a table and a model of the table layer seeded through the API (the dataset wizard and the model designer are
gone: Build › Tables & models writes them): a report's own measure in the ExpressionEditor and the Query panel's MQ and SQL views; a report's
Fields pane inherits the model's field properties and perspectives.

Skipped when Playwright, Chromium or the built server jar is missing, or a scratch port is in use. Each pytest-xdist worker adds 100 to the ports (tests/scratch_ports.py)."""
from __future__ import annotations

import os
import re
import sys
from pathlib import Path

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

import test_w1_model_designer_browser as w1  # noqa: E402
from bi_stack import listening  # noqa: E402

PORTS = w1.worker_ports(18916, 17916)       # this file's own ports (plus the pytest-xdist worker offset): the stack fixture switches w1 to them
CONSOLE_URL = f"http://127.0.0.1:{PORTS[1]}"
# the server runs Python on its compute pool python, in this Python (it carries the pinned libraries)
os.environ.setdefault("DRISHTI_COMPUTE_ENABLED", "true")
os.environ.setdefault("DRISHTI_PYTHON", sys.executable)
os.environ.setdefault("DRISHTI_COMPUTE_FILE", str(Path(__file__).resolve().parents[2] / "config" / "compute" / "compute.yaml"))
CAPTURES = os.environ.get("DRISHTI_CAPTURES", "")                 # a folder: the texts the guides quote (YAML written, SQL shown) are kept there
SHOTS = os.environ.get("DRISHTI_SHOTS", "")                       # a folder: the documentation's pictures are taken there too (docs/guides/RACHANA_REFERENCE.md)
sign_in, clean, fetch = w1.sign_in, w1.clean, w1.fetch


def capture(name, text):
    if CAPTURES:
        Path(CAPTURES, name).write_text(text)


def shoot(page, selector, name, max_height=760):
    """A picture of one region for the guides (only with DRISHTI_SHOTS set): docs/guides/img/bi-languages/<name>.jpg. Taken from the whole page at its
    natural layout (an element screenshot scrolls, and the sticky top bar would cover the region), at most max_height pixels tall."""
    if SHOTS:
        page.evaluate("window.scrollTo(0, 0)")
        bb = page.locator(selector).first.bounding_box()
        page.screenshot(path=os.path.join(SHOTS, name + ".jpg"), quality=88, full_page=True,
                        clip={"x": bb["x"], "y": bb["y"], "width": bb["width"], "height": min(bb["height"], max_height)})


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    w1.use_ports(PORTS)
    for port in (w1.SERVER_PORT, w1.CONSOLE_PORT):
        if listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there")
    try:
        s = w1.Stack(tmp_path_factory.mktemp("langs")).start()
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


def new_model(page, title, extra=""):
    """A shared workspace (a private one cannot publish), a table of the trades and a model over it, both published (tables and models are written in
    Build › Tables & models; here they are seeded through the API), and a report started on the model, open in the designer. Returns the model's name."""
    ws = fetch(page, "POST", "/bi/api/workspaces", {"name": title + " desk", "description": ""})
    assert ws["status"] in (200, 201), ws
    slug = re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")
    table = (f"rachana: 3\ntable: bi.{slug.replace('-', '_')}_trade\nversion: 1.0.0\ntitle: {title} trades\nfrom: {{ kind: trade }}\nkeys: [id]\ncolumns:\n"
             '  DESK:      { path: "$.DESK", type: string }\n  PORTFOLIO: { path: "$.PORTFOLIO", type: string }\n'
             '  CURRENCY:  { path: "$.CURRENCY", type: string }\n  MTM:       { path: "$.MTM", type: decimal }\n')
    model = (f"rachana: 3\nmodel: {slug}\nversion: 1.0.0\ntitle: {title}\ntables:\n  trade: {{ table: bi.{slug.replace('-', '_')}_trade, role: fact }}\n"
             'measures:\n  Sum of MTM: { expr: "sum(trade.MTM)" }\n' + extra)
    for kind, body in (("table", table), ("model", model)):
        it = fetch(page, "POST", "/bi/api/workspaces/" + ws["body"]["id"] + "/items", {"kind": kind, "title": title + " " + kind, "description": "", "body": body})
        assert it["status"] in (200, 201), it
        pub = fetch(page, "POST", "/bi/api/items/" + it["body"]["id"] + "/publish", {"note": "first"})
        assert pub["status"] == 200, pub
    rep = fetch(page, "POST", "/bi/model-api/report", {"model": slug + "@1", "title": title + " report", "workspace": ws["body"]["id"]})
    assert rep["status"] == 200, rep
    page.goto(CONSOLE_URL + rep["body"]["href"])
    page.wait_for_url("**/bi/design/**", timeout=20000)
    return slug


def test_a_report_on_a_model_has_its_own_measures_and_the_query_as_mq_or_sql(browser):
    page = sign_in(browser, "wes")
    name = new_model(page, "Query model")
    expect(page.locator('[data-model-header="model"]')).to_be_visible(timeout=30000)
    # a measure of this report only, in the ExpressionEditor over the model's measures
    page.click("[data-local-measure] summary")
    page.fill("[data-lm-name]", "MTM in thousands")
    lm = page.locator("[data-lm-expr]")
    lm.click()
    lm.type("Sum")
    page.click('[data-xe-opt="Sum of MTM"]')
    lm.type(" / 1000")
    page.click("[data-lm-add]")
    expect(page.locator('[data-field="MTM in thousands"] [data-origin="report"], [data-field="MTM in thousands"]').first).to_be_visible(timeout=15000)
    page.click("[data-local-measure] summary")                                                   # close the form: the Fields pane stops moving under the pointer
    page.locator('[data-field="MTM in thousands"]').scroll_into_view_if_needed()
    page.wait_for_timeout(500)
    page.drag_and_drop('[data-field="MTM in thousands"]', "[data-canvas-grid]", target_position={"x": 80, "y": 40})
    page.drag_and_drop('[data-field="trade.DESK"]', ".bi-dvis")
    page.locator(".bi-dvis").first.click()
    expect(page.locator(".bi-dvis").first).to_contain_text("3 rows", timeout=15000)              # the report's own measure travels with the query as define
    page.locator("[data-visual-query] summary").first.click()
    expect(page.locator("[data-visual-query-text]").first).to_contain_text(f"model: {name}@1", timeout=15000)   # the Fields pane's (the Studio dock has the same)
    expect(page.locator("[data-visual-query-text]").first).to_contain_text("define:")
    page.locator('[data-qp-view="sql"]').first.click()
    sql = page.locator("[data-visual-query-text]").first.text_content()
    capture("query-panel.sql", sql)
    assert sql.startswith("SELECT trade.DESK") and "FROM   trade" in sql and "GROUP  BY trade.DESK" in sql and "MEASURE('Sum of MTM') / 1000 AS" in sql, sql
    # Run as SQL: the server answers the same rows; the plan is the visual's when the question is the same (here a model measure only)
    page.locator("[data-qp-run]").first.click()
    expect(page.locator("[data-qp-rows]").first).to_have_attribute("data-qp-rows", "3", timeout=15000)
    shoot(page, "[data-visual-query]", "query-panel-sql")
    capture("query-panel-run.txt", page.locator("[data-qp-out]").first.text_content())
    page.click("[data-save]")
    expect(page.locator(".bi-near").first).to_contain_text("Saved", timeout=15000)
    body = fetch(page, "GET", "/bi/api/items/" + page.url.rsplit("/", 1)[-1])["body"]["body"]
    assert "measures:" in body and "MTM in thousands" in body, body
    # the same question as a visual, a model query and SQL: one plan hash (BI_DESIGN.md 12.2, acceptance 2)
    mq = fetch(page, "POST", "/bi/api/mq", {"mq": 1, "model": f"{name}@1", "select": {"fields": ["trade.DESK"], "measures": ["Sum of MTM"]}})
    sq = fetch(page, "POST", "/bi/api/sql", {"model": f"{name}@1", "sql": "SELECT trade.DESK, MEASURE('Sum of MTM') AS \"Sum of MTM\" FROM trade GROUP BY trade.DESK"})
    assert mq["status"] == 200 and sq["status"] == 200, (mq, sq)
    assert mq["body"]["meta"]["plan"] == sq["body"]["meta"]["plan"], (mq["body"]["meta"], sq["body"]["meta"])
    assert sorted(map(tuple, mq["body"]["rows"])) == sorted(map(tuple, sq["body"]["rows"]))
    clean(page)
    page.context.close()


def test_a_reports_fields_pane_inherits_the_models_properties_and_perspectives(browser):
    page = sign_in(browser, "wes")
    new_model(page, "Inherit model", "fields:\n  trade.DESK: { display: Trading desk }\n  trade.MTM: { folder: Risk }\n  trade.PORTFOLIO: { hidden: true }\n"
                                      "perspectives:\n  desk-view: { title: Desk view, fields: [trade.DESK], measures: [Sum of MTM] }\n")
    expect(page.locator('[data-model-header="model"]')).to_be_visible(timeout=30000)
    expect(page.locator('[data-field="trade.DESK"]')).to_contain_text("Trading desk")              # the model's display name, not re-declared
    expect(page.locator('[data-folder="Risk"] [data-field="trade.MTM"]')).to_be_visible()
    expect(page.locator('[data-field="trade.PORTFOLIO"]')).to_have_count(0)                            # hidden by the model
    page.select_option("[data-perspective-pick]", "desk-view")
    expect(page.locator('[data-field="trade.CURRENCY"]')).to_have_count(0, timeout=10000)
    expect(page.locator('[data-field="trade.DESK"]')).to_be_visible()
    expect(page.locator('[data-field="Sum of MTM"]')).to_be_visible()
    shoot(page, ".bi-pane-fields", "report-fields-perspective")
    clean(page)
    page.context.close()


BUCKET = """rachana: 1
python: notional-size
title: Notional size
description: Big or small, by the trade's notional (a Python column of the dataset wizard).
version: 1
interface: transform
inputs: [rows]
packages: [pandas]
target: server
source: |
  def run(rows, params):
      out = rows.copy()
      out[params["column"]] = (out["NOTIONAL"] >= 4000000).map({True: "big", False: "small"})
      return out
"""


SMALL_TARGETS = """(sel) => [...document.querySelectorAll(sel)].filter(e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0 && r.height < 43.5; })
    .map(e => (e.getAttribute('data-xe-opt') || e.getAttribute('data-tab') || e.textContent || e.tagName).trim().slice(0, 30) + ' ' + Math.round(e.getBoundingClientRect().height))"""

