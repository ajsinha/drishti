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

"""The VaR and search measurements of "Arrow where it pays" (2.9.7, docs/guides/DATA_GUIDE.md › Data formats: JSON Lines is the standard, section 6), over the real server and
console on free scratch ports with the market-risk pack's committed VaR data built into a scratch lake (the stack of
test_var_explorer_browser.py, the demo connector off): the P&L path of the bank and of a desk (509 scenarios), the paths of a node's
children (what the explorer fetches ahead), a desk's contribution table by child, by position and by trade; and a large search (TRD,
limit 1000, the most one search returns) whose answer is drawn on the server. Bytes raw and gzip, the browser's parse time, and click-to-painted with Arrow (the
page's default for the contribution tables by position and by trade) and JSON (?tables=json); the path and a node's children, which the
page reads as JSON (Arrow measured no better), are compared with the same answer written as Arrow by core/arrow_answer.py.
Opt-in: DRISHTI_ARROW_BENCH=1."""
import json
import os
import statistics
import time

import pytest

if not os.environ.get("DRISHTI_ARROW_BENCH"):
    pytest.skip("the Arrow benchmark runs only with DRISHTI_ARROW_BENCH=1", allow_module_level=True)

import arrow_bench  # noqa: E402
from test_var_explorer_browser import painted, stack  # noqa: E402,F401 - the VaR stack
from test_workbench_browser import browser  # noqa: E402,F401

DESK = "NODE:MGMT~DESK-RATES"


def paint_ms(browser, base, scope, suffix, runs=5):
    """The explorer's own click-to-painted (data-paint-ms: headline and P&L path), median of `runs` opens after a warm one."""
    times = []
    for i in range(runs + 1):
        pg = browser.new_page(viewport={"width": 1600, "height": 1000})
        pg.goto(f"{base}/var?scope={scope}{suffix}")
        ms = painted(pg, scope)
        if i:
            times.append(ms)
        pg.close()
    return round(statistics.median(times))


def contrib_ms(browser, base, scope, by, suffix, rows, runs=5):
    """From a click on Contributions (or on By position / By trade) to every row of the table in the page."""
    times = []
    for i in range(runs + 1):
        pg = browser.new_page(viewport={"width": 1600, "height": 1000})
        pg.goto(f"{base}/var?scope={scope}{suffix}")
        painted(pg, scope)
        t0 = time.perf_counter()
        pg.locator('.vx-tab[data-tab="contrib"]').click()
        if by != "child":
            pg.wait_for_function("() => document.querySelectorAll('[data-vx-ctable] tbody tr').length > 1")
            t0 = time.perf_counter()
            pg.locator(f'.vx-segb[data-by="{by}"]').click()
        pg.wait_for_function("(n) => document.querySelectorAll('[data-vx-ctable] tbody tr').length >= n", arg=min(rows, 5000), timeout=120000)
        if i:
            times.append((time.perf_counter() - t0) * 1000)
        pg.close()
    return round(statistics.median(times))


def test_var_payloads_both_ways(stack, browser):  # noqa: F811
    base = str(stack)
    pg = browser.new_page()
    pg.goto(f"{base}/var?scope={DESK}")
    painted(pg, DESK)
    from core import arrow_answer

    def path_both(scope):
        """The path as the page reads it (JSON) and as Arrow would carry it (the console's encoder: 509 values and dates the table)."""
        raw = pg.evaluate("(u) => fetch(u, {headers: {Accept: 'application/json'}}).then(r => r.text())", f"/api/var/scope/path?scope={scope}&page=0").encode()
        return raw, arrow_answer.encode(json.loads(raw), "arrays", "series,scenarioDates")

    for name, scope in (("var-path-bank", "NODE:MGMT~BANK"), ("var-path-desk", DESK)):
        r = arrow_bench.measure_given(pg, *path_both(scope))
        r["jsonPaintedMs"] = paint_ms(browser, base, scope, "")
        arrow_bench.record(name, r)
    # the children's paths a drill fetches ahead (up to 12): the bytes of all of them
    tree = pg.evaluate("fetch('/api/var/tree?hierarchy=MGMT').then(r => r.json())")
    kids = [n["scope"] for n in tree["nodes"] if n.get("parent") == "MGMT~BANK"][:12]
    total = {"jsonBytes": 0, "jsonGzip": 0, "arrowBytes": 0, "arrowGzip": 0, "jsonParseMs": 0, "arrowParseMs": 0, "scopes": len(kids)}
    for s_ in kids:
        r = arrow_bench.measure_given(pg, *path_both(s_), runs=3)
        for k in ("jsonBytes", "jsonGzip", "arrowBytes", "arrowGzip", "jsonParseMs", "arrowParseMs"):
            total[k] = round(total[k] + r[k], 2)
    arrow_bench.record("var-path-children", total)
    for by in ("child", "position", "trade"):
        url = f"/api/var/scope/contributions?scope={DESK}" + ("" if by == "child" else f"&by={by}")
        if by == "child":                                                   # a node's few children: the page asks JSON (measured)
            raw = pg.evaluate("(u) => fetch(u, {headers: {Accept: 'application/json'}}).then(r => r.text())", url).encode()
            r = arrow_bench.measure_given(pg, raw, arrow_answer.encode(json.loads(raw), "records", "rows"))
            r["rows"] = len(json.loads(raw)["rows"])
            r["jsonPaintedMs"] = contrib_ms(browser, base, DESK, by, "", r["rows"])
            arrow_bench.record(f"var-contrib-{by}", r)
            continue
        r = arrow_bench.measure(pg, url, shape="records", at="rows")
        if not r["arrowIsArrow"]:                                            # say why: the column Arrow is not written with here
            d = pg.evaluate(f"fetch('{url}', {{headers: {{Accept: 'application/json'}}}}).then(r => r.json())")
            kinds = {}
            for row in d["rows"]:
                for k, v in row.items():
                    kinds.setdefault(k, set()).add(type(v).__name__)
            r["why"] = {k: sorted(v) for k, v in kinds.items() if "bool" in v or "dict" in v or "list" in v or ("str" in v and ({"int", "float"} & v))}
        rows = pg.evaluate(f"fetch('{url}', {{headers: {{Accept: 'application/json'}}}}).then(r => r.json()).then(d => d.rows.length)")
        r["rows"] = rows
        r["arrowPaintedMs"] = contrib_ms(browser, base, DESK, by, "", rows)
        r["jsonPaintedMs"] = contrib_ms(browser, base, DESK, by, "&tables=json", rows)
        arrow_bench.record(f"var-contrib-{by}", r)
    pg.close()


def test_a_large_search(stack, browser):  # noqa: F811
    """The search page is drawn on the server (HTML): its rows never reach the browser as JSON. Measured: the page's bytes and time,
    and what the same rows would weigh as JSON and as Arrow, to say whether moving it to a browser-drawn table would pay."""
    import gzip
    import urllib.error
    import urllib.parse
    import urllib.request

    from core import arrow_answer

    base = str(stack)
    q = "TRD limit 1000"                                                  # the most one search returns (DRS-4004)
    pg = browser.new_page(viewport={"width": 1600, "height": 1000})
    times = []
    for i in range(4):
        t0 = time.perf_counter()
        resp = pg.goto(f"{base}/s?q={q.replace(' ', '+')}")
        pg.wait_for_load_state("load")
        if i:
            times.append((time.perf_counter() - t0) * 1000)
    html = resp.body()
    try:
        with urllib.request.urlopen(stack.server + "/api/v1/search?q=" + urllib.parse.quote(q), timeout=60) as r:
            raw = r.read()
    except urllib.error.HTTPError as e:                                   # the server's refusal, said in the record
        arrow_bench.record("search-trd-1000", {"htmlBytes": len(html), "pageMs": round(statistics.median(times)), "problem": e.read().decode()[:300]})
        return
    data = json.loads(raw)
    cols = data.get("columns", [])
    flat = {"columns": [{"name": "id"}] + [{"name": c} for c in cols],
            "rows": [[r["ref"]["id"]] + [(v if isinstance(v, (str, int, float)) and not isinstance(v, bool) else (None if v is None else json.dumps(v)))
                                          for v in (r["values"].get(c) for c in cols)] for r in data.get("rows", [])]}
    as_json = json.dumps(flat, separators=(",", ":")).encode()
    as_arrow = arrow_answer.encode(flat, "rows", "rows") or b""
    result = {"rows": len(data.get("rows", [])), "htmlBytes": len(html), "htmlGzip": len(gzip.compress(html, 6)), "pageMs": round(statistics.median(times)),
              "jsonBytes": len(as_json), "jsonGzip": len(gzip.compress(as_json, 6)), "arrowBytes": len(as_arrow), "arrowGzip": len(gzip.compress(as_arrow, 6)) if as_arrow else 0}
    arrow_bench.record("search-trd-1000", result)
    pg.close()
