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

"""The BI measurements of "Arrow where it pays" (2.9.7, docs/guides/DATA_FORMATS.md section 6), over the real server and console
(tests/bi_report_stack.py on scratch ports, a scratch folder): a lake of DRISHTI_ARROW_BENCH_ROWS trades a day (default 50,000), and two
reports of one table visual each: the trade-level grid at the 5,000 rows an answer in JSON holds by default, and the same at 50,000 (the
server started with json-max-rows raised so JSON can carry it, for the comparison). For each: payload bytes raw and gzip, the browser's parse
time (Arrow JS and drishti-table.js added to the page for the measurement: the report page does not load them, as Arrow did not pay
here), and the page's click-to-painted as it is (JSON). The Arrow click-to-painted figures in the docs were taken once with the report
page wired to Arrow on trial (B.ask asking Arrow, the grid sorting typed columns), a wiring removed after it measured no better.
Opt-in: DRISHTI_ARROW_BENCH=1 (it is heavy; one at a time, never beside another stack)."""
import os
import time

import pytest

if not os.environ.get("DRISHTI_ARROW_BENCH"):
    pytest.skip("the Arrow benchmark runs only with DRISHTI_ARROW_BENCH=1", allow_module_level=True)
pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import sync_playwright  # noqa: E402

import arrow_bench  # noqa: E402
import bi_report_stack  # noqa: E402
from bi_report_helpers import sign_in  # noqa: E402
from bi_report_stack import CONSOLE_URL  # noqa: E402

ROWS = int(os.environ.get("DRISHTI_ARROW_BENCH_ROWS", "50000"))
GRID = """rachana: 2
report: arrow-bench-{n}
version: 1
title: Arrow bench {n}
model: trades-basic@1
pages:
  - id: grid
    title: Grid
    visuals:
      - id: rows
        type: table
        title: Every trade
        limit: {limit}
        at: {{ x: 0, y: 0, w: 12, h: 8 }}
        wells: {{ columns: [trade.TRADE_ID, trade.DESK, trade.PORTFOLIO, trade.PRODUCT_TYPE, trade.CURRENCY, trade.TRADE_DATE, Trades, Notional, MTM] }}
        format: {{ dataBars: [Notional] }}
"""
SIZES = [5000, min(ROWS, 50000)]


def realistic_lake(root, rows=ROWS, day="2026-10-08"):
    """The valuations the samples read, with figures shaped like a book's (12 desks, 60 books, 15 products, 10 currencies, amounts to the
    cent), not the stack's few repeated values: gzip of a JSON answer would otherwise look better than it is on real data."""
    import json
    import random

    rnd = random.Random(7)
    desks = [f"DESK-{x}" for x in ("FX", "FI", "EQD", "RATES", "CREDIT", "INFL", "COMM", "EM", "MUNI", "ABS", "XVA", "TSY")]
    products = ["FX_FORWARD", "FX_SWAP", "FX_OPTION", "GOVT_BOND", "CORP_BOND", "IRS", "OIS", "XCCY_SWAP", "CDS", "EQ_OPTION", "EQ_SWAP", "TRS",
                "REPO", "COMMODITY_SWAP", "INFLATION_SWAP"]
    ccys = ["USD", "EUR", "GBP", "JPY", "CAD", "CHF", "AUD", "SEK", "NOK", "MXN"]
    for sub in ("risk", "trade"):
        (root / sub / day).mkdir(parents=True, exist_ok=True)
    with open(root / "risk" / day / "trade-valuation.jsonl", "w") as f:
        for i in range(rows):
            d = rnd.randrange(len(desks))
            tid = f"ENDUR-{4_000_000 + rnd.randrange(5_000_000):08d}-{i}"
            f.write(json.dumps({"id": "TVAL-" + tid, "VALUATION_ID": "TVAL-" + tid, "TRADE_ID": tid, "SOURCE_SYSTEM": rnd.choice(["ENDUR", "MUREX", "CALYPSO"]),
                                "DESK": desks[d], "PORTFOLIO": f"{desks[d]}-BOOK-{rnd.randrange(5) + 1}", "PRODUCT_TYPE": rnd.choice(products),
                                "CURRENCY": rnd.choice(ccys), "TRADE_STATUS": "Live", "NOTIONAL": round(rnd.lognormvariate(15, 1.2), 2),
                                "MTM": round(rnd.gauss(0, 250_000), 2), "PNL_1D": round(rnd.gauss(0, 20_000), 2), "TRADE_DATE": day}) + "\n")
    (root / "trade" / day / "trade.jsonl").write_text("")


class BenchStack(bi_report_stack.ReportStack):
    def prepare(self, work):
        for n in SIZES:
            (work / "config" / "bi" / "reports" / f"arrow-bench-{n}.report.yaml").write_text(GRID.format(n=n, limit=n + 1))


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    for port in (bi_report_stack.SERVER_PORT, bi_report_stack.CONSOLE_PORT):
        if bi_report_stack.listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there")
    orig = bi_report_stack.write_lake
    bi_report_stack.write_lake = lambda root: realistic_lake(root)
    s = BenchStack(tmp_path_factory.mktemp("arrowbench"), delta_root="")
    s.server_args = ["--drishti.bi.query.json-max-rows=100000"]
    try:
        s.start()
    except FileNotFoundError as e:
        pytest.skip(str(e))
    finally:
        bi_report_stack.write_lake = orig
    yield s
    s.close()


@pytest.fixture(scope="module")
def browser(stack):
    with sync_playwright() as p:
        b = p.chromium.launch()
        yield b
        b.close()


def painted(page, n, timeout=180000):
    page.wait_for_function("(n) => document.querySelectorAll('[data-visual=\"rows\"] tbody tr[data-row]').length >= n", arg=n, timeout=timeout)


@pytest.mark.parametrize("n", SIZES)
def test_a_trade_grid_both_ways(stack, browser, n):
    page = sign_in(browser, "rita")
    rid = f"file:report:arrow-bench-{n}"
    page.goto(f"{CONSOLE_URL}/bi/r/{rid}")
    painted(page, n)
    page.add_script_tag(url="/static/vendor/apache-arrow/21.1.0/Arrow.es2015.min.js")
    page.add_script_tag(url="/static/js/drishti-table.js")
    result = arrow_bench.measure(page, f"/bi/api/reports/{rid.replace(':', '%3A')}/query", "POST", {"page": "grid", "visual": "rows", "state": {}})
    assert result["arrowIsArrow"], result
    times = []
    for _ in range(3):
        t0 = time.perf_counter()
        page.goto(f"{CONSOLE_URL}/bi/r/{rid}")
        painted(page, n)
        times.append((time.perf_counter() - t0) * 1000)
    result["jsonPaintedMs"] = round(sorted(times)[1])
    arrow_bench.record(f"bi-grid-{n}", result)
    page.context.close()
