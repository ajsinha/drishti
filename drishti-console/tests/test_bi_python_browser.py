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

"""Python in a report (docs/guides/PYTHON_BI.md), in Chromium over the real server and console (tests/bi_report_stack.py on ports 18955 and 17955, security on, a small
file lake: two business days of 60 trades). The shipped sample report "Desk P&L risk (Python)" runs a Python measure (the standard deviation of one-day P&L per desk) and a
Python histogram on the server (sandboxed worker); the same two visuals run in the browser (Pyodide in Calc's worker) and give the same figures; the report page keeps the
strict policy (no WebAssembly, no violation) and the designer offers the python visual. Every figure is computed here from the same trades.

Skipped when Playwright, Chromium, the built server jar or pandas and pyarrow are missing, or a scratch port is in use (the browser part also needs Pyodide:
tools/fetch-pyodide.sh). Never run with pytest -n (the ports are fixed)."""
import os
import statistics

import pytest

os.environ.setdefault("DRISHTI_BI_REPORT_SERVER_PORT", "18955")
os.environ.setdefault("DRISHTI_BI_REPORT_CONSOLE_PORT", "17955")
pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
pytest.importorskip("pandas")
pytest.importorskip("pyarrow")
from playwright.sync_api import sync_playwright  # noqa: E402

import bi_report_stack  # noqa: E402
from bi_report_helpers import clean, open_report, sign_in  # noqa: E402
from bi_report_stack import CONSOLE_URL, ROOT, trade  # noqa: E402

DAY = "2026-10-08"
PYODIDE = ROOT / "drishti-console/web/static/vendor/pyodide/pyodide.mjs"
SAMPLE = ROOT / "config/bi/reports/python-desk-risk.report.yaml"


def variant(run: str) -> str:
    """The shipped sample report, pinned to one business day and to one place to run."""
    text = SAMPLE.read_text(encoding="utf-8").replace("report: python-desk-risk", f"report: py-{run}").replace("sample: true", "sample: false")
    text = text.replace("model: trades-basic@1", f"model: trades-basic@1\ndates: {{ from: {DAY}, to: {DAY} }}")
    return text.replace("format: { run: auto }", f"format: {{ run: {run} }}").replace("params: { bins: 24 }, run: auto", f"params: {{ bins: 24 }}, run: {run}")


class Stack(bi_report_stack.ReportStack):
    def prepare(self, work):
        python = work / "config/bi/python"
        python.mkdir(parents=True, exist_ok=True)
        for f in (ROOT / "config/bi/python").glob("*.python.yaml"):
            (python / f.name).write_text(f.read_text(encoding="utf-8"))
        reports = work / "config/bi/reports"
        reports.joinpath("python-desk-risk.report.yaml").write_text(SAMPLE.read_text(encoding="utf-8"))
        for run in ("server", "browser"):
            reports.joinpath(f"py-{run}.report.yaml").write_text(variant(run))


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    for port in (bi_report_stack.SERVER_PORT, bi_report_stack.CONSOLE_PORT):
        if bi_report_stack.listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there")
    try:
        s = Stack(tmp_path_factory.mktemp("pybi"), delta_root="").start()
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


BARS = """() => { const el = document.querySelector('[data-visual="volatility"] .bi-chart'); if (!el) return null;
  const o = echarts.getInstanceByDom(el).getOption(); return { names: o.xAxis[0].data, values: o.series[0].data }; }"""
HIST = """() => { const el = document.querySelector('[data-visual="histogram"] .bi-chart'); if (!el) return null;
  const o = echarts.getInstanceByDom(el).getOption(); return { title: o.title[0].text, counts: o.series[0].data, labels: o.xAxis[0].data }; }"""


def expected():
    """The standard deviation of one-day P&L per desk, and every trade's P&L, for the day: from the same generator the lake was written with."""
    trades = [trade(DAY, i) for i in range(60)]
    per_desk = {}
    for t in trades:
        per_desk.setdefault(t["desk"], []).append(t["pnl1d"])
    return {d: statistics.stdev(v) for d, v in per_desk.items()}, [t["pnl1d"] for t in trades]


def settled(page, timeout=90000):
    page.wait_for_function("() => document.querySelectorAll('.bi-vis-python').length === 3 && !document.querySelector('.bi-vis-busy') && "
                           "[...document.querySelectorAll('.bi-vis-python')].every(v => v.querySelector('.bi-py-foot') || v.classList.contains('bi-vis-failed'))", timeout=timeout)


def check_figures(page, target):
    std, pnl = expected()
    bars = page.evaluate(BARS)
    assert bars["names"] == sorted(std)
    for name, value in zip(bars["names"], bars["values"]):
        assert value == pytest.approx(std[name], rel=1e-9)
    hist = page.evaluate(HIST)
    assert hist["title"] == f"One-day P&L of {len(pnl):,} trades" and sum(hist["counts"]) == len(pnl) and len(hist["counts"]) == 24
    feet = page.eval_on_selector_all(".bi-py-foot", "els => els.map(e => e.dataset.target)")
    assert feet == [target, target, "server"]            # the third visual (Vega-Lite) is pinned to the server
    text = page.locator(".bi-py-foot").first.inner_text()
    assert "desk-pnl-volatility" in text and "rows" in text


def test_the_sample_runs_on_the_server_sandboxed(stack, browser):
    page = sign_in(browser, "rita", "/bi")
    open_report(page, "file:report:py-server")
    settled(page)
    check_figures(page, "server")
    assert page.locator('[data-visual="histogram"] [data-py-kind=echarts]').count() == 1
    page.close()


def test_the_sample_as_shipped_asks_the_server_first(stack, browser):
    page = sign_in(browser, "wes", "/bi")
    page.goto(f"{CONSOLE_URL}/bi/r/file:report:python-desk-risk")
    page.wait_for_selector("[data-ready=yes]", timeout=30000)
    settled(page)
    assert page.eval_on_selector_all(".bi-py-foot", "els => els.map(e => e.dataset.target)") == ["server", "server", "server"]
    clean(page)
    page.close()


def test_the_same_visuals_run_in_the_browser_and_the_page_keeps_the_strict_policy(stack, browser):
    if not PYODIDE.exists():
        pytest.skip("needs the browser Python: tools/fetch-pyodide.sh")
    page = sign_in(browser, "rita", "/bi")
    open_report(page, "file:report:py-browser")
    settled(page, timeout=150000)
    check_figures(page, "browser")
    policy = page.request.get(f"{CONSOLE_URL}/bi/r/file:report:py-browser").headers["content-security-policy"]
    assert "wasm-unsafe-eval" not in policy and "unsafe-eval" not in policy
    worker = page.request.get(f"{CONSOLE_URL}/static/js/calc-worker.js").headers["content-security-policy"]
    assert "wasm-unsafe-eval" in worker
    clean(page)
    page.close()


def test_the_designer_offers_the_python_visual(stack, browser):
    page = sign_in(browser, "wes", "/bi")
    cat = page.request.get(f"{CONSOLE_URL}/bi/api/reports/catalogue").json()
    assert cat["visuals"]["python"]["group"] == "python" and "python" not in cat["reserved"]
    page.close()
