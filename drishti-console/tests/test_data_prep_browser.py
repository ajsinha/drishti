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

"""Wave C (2.4.0) acceptance in Chromium over the real server and console (tests/bi_report_stack.py on ports 18961 and 17961, security on, a small file lake:
and prep-store, a file lake of two business days of 60 synthetic trades in the trading pack's names). docs/architecture/BI_DESIGN.md section 12.2, Wave C, accepted when:

(a) **a Transform reading a connector is built step by step with a preview at each step and no YAML typing**: in /bi/transform an author adds the pack kind
    trade read straight through trading-store, then steps from the snippets (filter, group and total) by filling forms; after every step the preview shows
    its rows and a profile of each column, and the figures equal a computation from the same trades; the YAML is written for them and read back as the same
    steps; saving makes two drafts;
(b) **the Notebook profiles a masked extract with the editor's panel**: an analyst's extract arrives with PRICE masked, and the Profile says so.

Skipped when Playwright, Chromium, the built server jar, pandas or pyarrow are missing, or a scratch
port is in use. Never run with pytest -n (the ports are fixed). DRISHTI_DOC_SHOTS=<folder> also saves the documentation pictures there."""
import os
import re
from collections import defaultdict
from pathlib import Path

import pytest

os.environ.setdefault("DRISHTI_BI_REPORT_SERVER_PORT", "18961")
os.environ.setdefault("DRISHTI_BI_REPORT_CONSOLE_PORT", "17961")
pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
pytest.importorskip("pandas")
pytest.importorskip("pyarrow")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

import bi_report_stack  # noqa: E402
from bi_report_helpers import clean, sign_in  # noqa: E402
from bi_report_stack import CONSOLE_URL, ROOT  # noqa: E402

DAY = "2026-10-08"
BOOKS = ["BOOK-FX-1", "BOOK-FI-1", "BOOK-EQ-1"]
PREP_STORE = """plugin: file
kinds: [trade]
settings:
  root: {root}
  rescan-seconds: 3600
  layout:
    trade:
      columns: [TRADE_ID, TRADE_DATE, PORTFOLIO, CURRENCY, QUANTITY, PRICE, PRODUCT_TYPE, COUNTERPARTY]
"""
PREP_DATASET = """rachana: 2
dataset: prep-trades
version: 1.0.0
title: Prep trades
source: { connector: prep-store, kind: trade }
grain: { keys: [TRADE_ID] }
columns:
  TRADE_ID:  { type: string, role: key }
  PORTFOLIO: { type: string }
  CURRENCY:  { type: string }
  QUANTITY:  { type: decimal, aggregate: sum }
  PRICE:     { type: decimal, aggregate: max }
  TRADE_DATE: { type: string }
security:
  masked: [PRICE]
"""
PREP_MODEL = """rachana: 2
model: prep-model
version: 1.0.0
title: Prep model
datasets:
  trades: { dataset: prep-trades, role: fact }
measures:
  Quantity: { expr: "sum(trades.QUANTITY)" }
"""


def prep_trade(day: str, i: int) -> dict:
    """A synthetic trade in the trading pack's production-shaped names (TRADE_ID, PORTFOLIO, ...), read through the connector prep-store."""
    return {"id": f"PRP-{day[5:7]}{day[8:]}-{i:03d}", "TRADE_ID": f"PRP-{day[5:7]}{day[8:]}-{i:03d}", "TRADE_DATE": day, "PORTFOLIO": BOOKS[i % 3],
            "CURRENCY": ["USD", "EUR"][i % 2], "QUANTITY": 1000.0 * (1 + i % 5), "PRICE": 1.0 + (i % 4) * 0.25, "PRODUCT_TYPE": "FX_FORWARD", "COUNTERPARTY": f"CP-{i % 7}"}


class PrepStack(bi_report_stack.ReportStack):
    """The report stack with one more connector, prep-store (a file lake of synthetic trades), and a model over it whose PRICE is masked without raw."""

    def prepare(self, work):
        import json
        lake = self.tmp / "prep-lake"
        for day in ("2026-10-07", DAY):
            (lake / day).mkdir(parents=True, exist_ok=True)
            (lake / day / "trade.jsonl").write_text("".join(json.dumps(prep_trade(day, i)) + "\n" for i in range(60)))
        (work / "config" / "connectors").mkdir(parents=True, exist_ok=True)
        (work / "config" / "connectors" / "prep-store.yaml").write_text(PREP_STORE.format(root=lake))
        # the kind's route serves the same trades, so the editor offers their UPPER_SNAKE fields (the pack dataset of kind trade is read through trade-endur)
        (work / "config" / "connectors" / "trade-endur.yaml").write_text(PREP_STORE.format(root=lake))
        (work / "config" / "bi" / "datasets" / "prep-trades.dataset.yaml").write_text(PREP_DATASET)
        (work / "config" / "bi" / "models").mkdir(parents=True, exist_ok=True)
        (work / "config" / "bi" / "models" / "prep-model.model.yaml").write_text(PREP_MODEL)
SHOTS = os.environ.get("DRISHTI_DOC_SHOTS")


def shot(page, name, locator=None):
    if SHOTS:
        Path(SHOTS).mkdir(parents=True, exist_ok=True)
        page.set_viewport_size({"width": 1500, "height": 1400})                                   # tall enough that the top bar never covers the part shot
        (locator or page.locator("#biCanvas")).screenshot(path=str(Path(SHOTS) / name))
        page.set_viewport_size({"width": 1600, "height": 900})


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    for port in (bi_report_stack.SERVER_PORT, bi_report_stack.CONSOLE_PORT):
        if bi_report_stack.listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there")
    try:
        s = PrepStack(tmp_path_factory.mktemp("prep"), delta_root="").start()
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


def fx_value():
    """The value (quantity times price) of the book BOOK-FX-1 on the latest day, per currency, from the same generator the lake was written with."""
    total = defaultdict(float)
    for i in range(60):
        t = prep_trade(DAY, i)
        if t["PORTFOLIO"] == "BOOK-FX-1":
            total[t["CURRENCY"]] += t["QUANTITY"] * t["PRICE"]
    return dict(total)


def ran(page, before: int, timeout=60000) -> int:
    page.wait_for_function(f"() => Number(document.getElementById('tx').getAttribute('data-ran') || 0) > {before} && document.getElementById('tx').getAttribute('data-running') === 'false'",
                           timeout=timeout)
    return int(page.locator("#tx").get_attribute("data-ran"))


def state(page):
    return page.evaluate("() => JSON.parse(JSON.stringify({ spec: window.Drishti.transform.spec, run: window.Drishti.transform.run }))")


def add_snippet(page, sid, fill):
    page.click("[data-open-snippets]")
    page.select_option("[data-snippet]", sid)
    fill()
    page.click("[data-snippet-add]")


def open_kind_input(page, fields, connector="prep-store", name="trades"):
    page.wait_for_selector("#tx[data-loaded=true]")
    page.select_option("[data-ikind]", "dataset:pack.trading.trade")
    page.wait_for_selector("#tx[data-fields-for='dataset:pack.trading.trade']")
    page.fill("[data-iconnector]", connector)
    for box in page.locator("[data-ifields] input[name=f]").all():               # the fields this Transform reads
        if (box.get_attribute("value") in fields) != box.is_checked():
            box.click()
    page.fill("[data-iname]", name)
    page.click("[data-iadd]")


STEPS = ["Start from trades", "Filter rows by values (PORTFOLIO)", "Calculated column (QUANTITY; PRICE)", "Group and total (CURRENCY; VALUE)"]


def test_a_transform_reading_a_connector_is_built_step_by_step_without_yaml(stack, browser):
    page = sign_in(browser, "wes", "/bi/transform")
    page.fill("[data-title]", "FX book value, cleaned")
    fields = ["TRADE_ID", "TRADE_DATE", "PORTFOLIO", "CURRENCY", "QUANTITY", "PRICE"]
    open_kind_input(page, fields)
    n = ran(page, 0)
    s = state(page)
    got = s["spec"]["inputs"][0]["input"]
    assert {k: v for k, v in got.items() if k != "fields"} == {"kind": "trade", "pack": "trading", "connector": "prep-store"} and sorted(got["fields"]) == sorted(fields)
    assert s["run"]["complete"] and s["run"]["inputs"][0]["rows"] == 60 and s["run"]["inputs"][0]["asOf"] == DAY, s["run"]["inputs"][0]   # the connector's newest day
    expect(page.locator("[data-step='0'] [data-step-state]")).to_contain_text("60 rows")
    expect(page.locator("[data-prows] table tbody tr")).to_have_count(20)                     # the first 20 rows of the step's frame
    shot(page, "01-input.png", page.locator(".tx-inputs"))

    add_snippet(page, "filter-values", lambda: (page.select_option("[data-param=column]", "PORTFOLIO"), page.fill("[data-param=values]", "BOOK-FX-1")))
    n = ran(page, n)
    expect(page.locator("[data-step='1'] [data-step-state]")).to_contain_text("20 rows")
    page.click("[data-ptab=profile]")
    book = page.locator("[data-profile-col=PORTFOLIO]")
    expect(book).to_contain_text("BOOK-FX-1")
    expect(book).to_contain_text("×20")
    expect(page.locator("[data-profile-col=QUANTITY] svg.tx-hist")).to_be_visible()           # a histogram of the numbers
    expect(page.locator("[data-profile-col=TRADE_ID] .tx-key")).to_be_visible()               # every row its own value: a key
    shot(page, "02-profile.png", page.locator(".tx-work"))

    add_snippet(page, "calculated-column", lambda: (page.select_option("[data-param=left]", "QUANTITY"), page.select_option("[data-param=op]", "*"),
                                                    page.select_option("[data-param=right]", "PRICE"), page.fill("[data-param=out]", "VALUE")))
    n = ran(page, n)
    add_snippet(page, "group-total", lambda: (page.click("[data-param=by] input[value=CURRENCY]"), page.click("[data-param=columns] input[value=VALUE]")))
    n = ran(page, n)
    page.click("[data-ptab=rows]")
    s = state(page)
    assert [st["label"] for st in s["spec"]["steps"]] == STEPS
    assert [st["rows"] for st in s["run"]["steps"]] == [60, 20, 20, 2]
    got = {r[0]: r[1] for r in s["run"]["steps"][-1]["head"]["rows"]}
    assert got == pytest.approx(fx_value())                                                   # equal to a computation from the same trades
    shot(page, "03-steps.png", page.locator(".tx-work"))

    page.click("[data-step='1'] .tx-pick")                                                     # back to a step: its own rows again
    expect(page.locator("[data-prows] table tbody tr")).to_have_count(20)
    page.click("[data-step='3'] .tx-pick")

    page.click("[data-yaml-box] summary")                                                      # the YAML was written, not typed
    python = page.locator("[data-yaml-python]").input_value()
    dataset = page.locator("[data-yaml-dataset]").input_value()
    assert "interface: transform" in python and "# step: Filter rows by values (PORTFOLIO)" in python and "return df" in python
    assert "kind: trade" in dataset and "connector: prep-store" in dataset and "python: fx-book-value-cleaned" in dataset
    shot(page, "04-yaml.png", page.locator("[data-yaml-box]"))
    page.click("[data-yaml-read]")                                                             # read back as the same steps
    ran(page, n)
    assert [st["label"] for st in state(page)["spec"]["steps"]] == STEPS

    page.click("[data-save]")
    expect(page.locator("[data-save-msg]")).to_contain_text("Saved as two drafts")
    expect(page.locator("[data-open-python]")).to_be_visible()
    assert "item=" in page.url and "dataset=" in page.url
    reopened = page.url
    clean(page)
    page.goto(reopened)                                                                       # the saved drafts open as the same steps
    ran(page, 0)
    assert [st["label"] for st in state(page)["spec"]["steps"]] == STEPS
    page.close()


def test_a_failing_step_says_where_and_keeps_the_steps_before(stack, browser):
    page = sign_in(browser, "wes", "/bi/transform")
    open_kind_input(page, ["TRADE_ID", "PORTFOLIO", "QUANTITY"])
    n = ran(page, 0)
    page.click("[data-open-code]")
    page.fill("[data-code-label]", "A typo")
    page.locator("[data-code]").evaluate("(e, t) => { e.value = t; e.dispatchEvent(new Event('input', { bubbles: true })); e.dispatchEvent(new Event('change', { bubbles: true })); }", 'df = df[df["NO_SUCH_COLUMN"] > 0]')   # the code area is drishti-inputs' code editor: typed through its text area
    page.click("[data-code-add]")
    ran(page, n)
    expect(page.locator("[data-step='1']")).to_have_class(re.compile("tx-failed"))
    expect(page.locator("[data-step-error]")).to_contain_text("KeyError")
    expect(page.locator("[data-step='0'] [data-step-state]")).to_contain_text("60 rows")
    clean(page)
    page.close()


def test_the_notebook_profiles_the_extract_with_the_editors_panel(stack, browser):
    page = sign_in(browser, "ann", "/bi/notebook")                                             # no raw power: PRICE is masked for her
    page.wait_for_function("() => [...document.querySelectorAll('[data-dataset] option')].some(o => o.value === 'mq:prep-model@1')", timeout=30000)
    page.select_option("[data-dataset]", "mq:prep-model@1")
    page.wait_for_selector("[data-fields] input[name=f]")
    page.check("[data-fields] input[value='trades.PRICE']")                                   # a masked field is not ticked for her: tick it
    page.click("[data-open-go]")
    expect(page.locator("[data-info]")).to_contain_text("rows")
    page.click("[data-tab=profile]")
    expect(page.locator("[data-profile-col=PORTFOLIO]")).to_contain_text("BOOK-FX-1")
    expect(page.locator("[data-profile-col=PRICE] .tx-masked")).to_be_visible()
    expect(page.locator("[data-profile-meta]")).to_contain_text("Masked for you: PRICE")
    shot(page, "07-analyse-profile.png", page.locator("#bxP-profile"))
    clean(page)
    page.close()
