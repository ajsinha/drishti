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

"""BI with AI in Chromium, over the real server and console with the MOCK provider (tests/bi_report_stack.py on ports 18957 and 17957: security on, a small file lake read
through the connector the shipped dataset ``trades-basic`` names; the server's AI layer on with one mock provider whose scripted answers make everything deterministic):
Ask the data on the sample report "Trades overview" (a table, the bar the engine's answer suggests, the query the model chose), Explain on a card (lineage and the
engine's breakdown), and Describe a report in the designer (a draft the report checker passed, put into the YAML tab, nothing saved).

Skipped when Playwright, Chromium or the built server jar is missing, or a scratch port is in use. Never run with pytest -n (the ports are fixed)."""
import os

os.environ.setdefault("DRISHTI_BI_REPORT_SERVER_PORT", "18957")
os.environ.setdefault("DRISHTI_BI_REPORT_CONSOLE_PORT", "17957")

import pytest  # noqa: E402

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

import bi_report_stack  # noqa: E402
from bi_report_helpers import sign_in  # noqa: E402
from bi_report_stack import CONSOLE_URL, trade  # noqa: E402

REPORT = "file:report:trades-overview"
DAY = "2026-10-08"
DATES = '"dates":{"from":"2026-10-08","to":"2026-10-08"}'
MOCK = """type: mock
models:
  - name: m
mock:
  rules:
    - match: "TASK: draft-report"
      reply: |
        rachana: 1
        report: drafted
        version: 1
        title: Drafted from words
        model: trades-basic@1
        pages:
          - id: main
            title: Main
            visuals:
              - { id: by-desk, type: bar, title: Notional by desk, at: { x: 0, y: 0, w: 6, h: 4 }, wells: { axis: [trade.desk], values: [Notional] }, alt: Bar chart of notional by desk }
    - match: "QUESTION: [^\\n]*desk"
      reply: '{"fields":["trade.desk"],"measures":["Notional"],%s,"orderBy":[{"by":"Notional","desc":true}]}'
""" % DATES


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    for port in (bi_report_stack.SERVER_PORT, bi_report_stack.CONSOLE_PORT):
        if bi_report_stack.listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there")
    tmp = tmp_path_factory.mktemp("biai")
    (tmp / "ai").mkdir()
    (tmp / "ai" / "mock.yaml").write_text(MOCK)
    s = bi_report_stack.ReportStack(tmp, delta_root="")
    s.server_args = ["--drishti.ai.enabled=true", f"--drishti.ai.dir={tmp / 'ai'}", "--drishti.ai.default-model=mock/m", "--drishti.ai.watch=off"]
    try:
        s.start()
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


def shot(page, name):
    """The documentation picture, only when DRISHTI_SHOTS is set."""
    if os.environ.get("DRISHTI_SHOTS"):
        folder = bi_report_stack.ROOT / "docs" / "guides" / "img" / "bi-ai"
        folder.mkdir(parents=True, exist_ok=True)
        page.evaluate("window.scrollTo(0, 0)")
        page.screenshot(path=str(folder / (name + ".png")))


def desks():
    out = {}
    for i in range(60):
        t = trade(DAY, i)
        out[t["desk"]] = out.get(t["desk"], 0) + t["notional"]
    return out


def test_ask_the_data_and_explain_a_number_on_a_report(browser):
    page = sign_in(browser, "wes", "/bi/r/" + REPORT)
    page.wait_for_selector("[data-ready=yes]", timeout=30000)
    ask = page.locator("[data-ai-ask]")
    expect(ask).to_be_visible()
    page.fill("[data-ai-q]", "notional by desk")
    page.click("[data-ai-ask] button[type=submit]")
    expect(page.locator("[data-ai-answer] .bi-ai-table tbody tr")).to_have_count(3, timeout=20000)
    got = {r.locator("td").nth(0).inner_text(): r.locator("td").nth(1).inner_text() for r in page.locator("[data-ai-answer] .bi-ai-table tbody tr").all()}
    assert set(got) == set(desks())
    assert "Shown as bar" in page.locator(".bi-ai-shown").inner_text()
    page.locator(".bi-ai-query summary").click()
    assert '"trade.desk"' in page.locator("[data-ai-query]").inner_text()
    assert page.locator("[data-ai-summary]").count() == 0                         # labels-only: the model never saw the answer, so it wrote nothing about it
    shot(page, "bi-ai-ask")

    page.locator('[data-visual="notional"] [data-act="explain"]').click()
    panel = page.locator("[data-ai-explain]")
    expect(panel).to_be_visible(timeout=20000)
    expect(page.locator("[data-ai-lineage]")).to_contain_text("Measure Notional")
    expect(page.locator("[data-ai-lineage]")).to_contain_text("trading-store")
    rows = page.locator('[data-ai-breakdown="trade.desk"] tbody tr')
    expect(rows).to_have_count(3)
    assert page.locator("[data-ai-value]").inner_text().replace(",", "").replace(" ", "")  # the figure of the card, computed by the engine
    shot(page, "bi-ai-explain")
    page.keyboard.press("Escape")
    expect(panel).to_be_hidden()
    assert page.evaluate("window.__csp") == []                                     # no policy violation: no inline script, handler or style
    assert page.errors == []


@pytest.mark.xfail(strict=True, reason='server: the AI draft and suggest endpoints resolve only a dataset (BiReportService.datasetFor), not the model a 2.2 report reads (DRS-9034 for trades-basic@1); the console sends the model')
def test_describe_a_report_in_the_designer(browser):
    page = sign_in(browser, "wes", "/bi/design/" + REPORT)
    page.wait_for_selector("[data-ready=yes]", timeout=30000)
    page.click("[data-ai-draft]")
    page.fill("[data-ai-request]", "notional by desk as a bar chart")
    page.click("[data-ai-go]")
    expect(page.locator("[data-ai-yaml]")).to_be_visible(timeout=20000)
    assert "Drafted from words" in page.locator("[data-ai-yaml]").input_value()
    assert "passed the report check" in page.locator("[data-ai-msg]").inner_text()
    shot(page, "bi-ai-describe")
    page.click("[data-ai-use]")
    page.wait_for_selector('[data-view="yaml"]:not([hidden])')
    page.wait_for_function("() => (document.querySelector('.CodeMirror') ? document.querySelector('.CodeMirror').CodeMirror.getValue() : document.querySelector('[data-yaml]').value).includes('Drafted from words')")
    assert page.errors == []
