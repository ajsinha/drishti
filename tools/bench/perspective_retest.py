#!/usr/bin/env python3
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

"""The Perspective retest of Rupaka phase 5 (BI_DESIGN.md › Rūpaka: the original design and every phase as built, section 20): the questions phase 0 asked, asked again of the real report page.

    python tools/bench/perspective_retest.py [--rows 10000] [--seconds 8]

Starts the scratch stack of ``drishti-console/tests/bi_report_stack.py`` (the built server jar, security on, a file lake of ``--rows`` trades a day; never more than 10,000
locally), puts a report with one Perspective grid on it, and prints Markdown tables of: the Arrow answer (server time, bytes) and the time to a drawn grid; the browser's
JS heap; Perspective applying updates to the loaded table at 0, 100, 500 and 1,000 rows a second (frames a second, the longest frame, frames over 50 ms); the policy
(violations counted on the page); and whether a second tab opened 0, 6 and 12 s after the first draws its grid, in Chromium, Chrome, Firefox and WebKit (the ones this
machine has). Ports 18990 and 17990; one heavy job at a time; everything is removed afterwards."""
from __future__ import annotations

import argparse
import json
import shutil
import statistics
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "drishti-console" / "tests"))
sys.path.insert(0, str(ROOT / "drishti-console"))

REPORT = """rachana: 1
report: perspective-bench
dataset: trades-basic
title: Perspective bench
pages:
  - id: grid
    title: Grid
    visuals:
      - { id: g, type: perspective-grid, title: Trades, at: { x: 0, y: 0, w: 12, h: 12 }, wells: { columns: [trade.tradeId, trade.desk, trade.book, trade.productType, trade.currency, trade.status, trade.notional, trade.mtm, trade.pnl1d] } }
"""

VIOLATIONS = "window.__csp = []; document.addEventListener('securitypolicyviolation', e => window.__csp.push(e.violatedDirective + ' ' + e.blockedURI));"

# updates to the loaded table at a steady rate, frames timed with requestAnimationFrame
RUN = """async ([rate, seconds]) => {
  const viewer = document.querySelector('perspective-viewer');
  const table = await viewer.getTable();
  const size = await table.size();
  const schema = await table.schema();
  const ids = (await (await table.view({ columns: ['id'] })).to_columns()).id;
  const frames = [];
  let last = performance.now(), stop = false, applied = 0, offered = 0, inflight = 0;
  const frame = (t) => { frames.push(t - last); last = t; if (!stop) requestAnimationFrame(frame); };
  requestAnimationFrame(frame);
  const every = 20, per = Math.max(1, Math.round(rate * every / 1000));
  const timer = rate ? setInterval(() => {
    if (inflight > 4) return;
    const rows = [];
    for (let i = 0; i < per; i++) { rows.push({ id: ids[Math.floor(Math.random() * ids.length)], mtm: Math.round((Math.random() - 0.5) * 2e6) }); }
    offered += per; inflight++;
    table.update(rows).then(() => { applied += per; }).finally(() => { inflight--; });
  }, every) : 0;
  await new Promise((r) => setTimeout(r, seconds * 1000));
  clearInterval(timer); stop = true;
  await new Promise((r) => setTimeout(r, 200));
  frames.shift();
  frames.sort((a, b) => a - b);
  return { rate, size, applied, offered, fps: frames.length / seconds, p95: frames[Math.floor(frames.length * 0.95)] || 0, max: frames[frames.length - 1] || 0, over50: frames.filter((f) => f > 50).length,
           heap: performance.memory ? performance.memory.usedJSHeapSize / 1048576 : null };
}"""

TAB = "(() => { const e = document.querySelector('.bi-psp'); return e ? e.dataset.psp : 'none' })()"


def sign_in(page, console, user, password):
    page.goto(f"{console}/login?next=/bi")
    page.fill("#lu", user)
    page.fill("#lp", password)
    page.click("button[type=submit]")
    page.wait_for_url("**/bi")


def second_tab(playwright, name, console, user, password, report):
    """Whether a second tab of one browser profile opened 0, 6 and 12 s after the first draws its grid."""
    kind = {"Chromium": ("chromium", {}), "Chrome": ("chromium", {"channel": "chrome"}), "Firefox": ("firefox", {}), "WebKit": ("webkit", {})}[name]
    try:
        browser = getattr(playwright, kind[0]).launch(**kind[1])
    except Exception as e:  # noqa: BLE001
        return name, "not installed here: " + str(e).splitlines()[0][:60], []
    try:
        ctx = browser.new_context(viewport={"width": 1400, "height": 900})
        first = ctx.new_page()
        sign_in(first, console, user, password)
        first.goto(f"{console}/bi/r/{report}/grid")
        first.wait_for_selector("[data-psp=ready], [data-psp=fallback]", state="attached", timeout=60000)
        states = [first.evaluate(TAB)]
        for wait in (0, 6, 6):
            time.sleep(wait)
            tab = ctx.new_page()
            tab.goto(f"{console}/bi/r/{report}/grid")
            tab.wait_for_selector("[data-psp=ready], [data-psp=fallback]", state="attached", timeout=60000)
            states.append(tab.evaluate(TAB))
        return name, browser.version, states
    finally:
        browser.close()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--rows", type=int, default=10_000, help="trades a day in the lake (at most 10,000)")
    ap.add_argument("--seconds", type=int, default=8)
    args = ap.parse_args()
    if args.rows > 10_000:
        print("refusing: local runs hold at most 10,000 rows a day")
        return 2
    import bi_report_stack as S
    from playwright.sync_api import sync_playwright

    real = S.write_lake
    S.write_lake = lambda root, days=("2026-10-07", "2026-10-08"), rows=60: real(root, days, args.rows)         # the lake of this run
    tmp = Path(tempfile.mkdtemp(prefix="psp-retest-"))
    stack = S.ReportStack(tmp, delta_root="").start()
    try:
        folder = tmp / "server" / "config" / "bi" / "reports"
        (folder / "perspective-bench.report.yaml").write_text(REPORT)
        time.sleep(2.5)                                                                                         # the watched folder picks the file up
        report = "file:report:perspective-bench"
        with sync_playwright() as p:
            browser = p.chromium.launch(args=["--enable-precise-memory-info"])
            ctx = browser.new_context(viewport={"width": 1400, "height": 900})
            ctx.add_init_script(VIOLATIONS)
            page = ctx.new_page()
            sign_in(page, S.CONSOLE_URL, "rita", S.PASSWORD)
            t0 = time.time()
            page.goto(f"{S.CONSOLE_URL}/bi/r/{report}/grid")
            page.wait_for_selector("[data-psp=ready]", state="attached", timeout=120000)
            ready = (time.time() - t0) * 1000
            page.wait_for_timeout(1000)
            answer = page.evaluate("""async () => { const t = performance.now(); const r = await fetch('/bi/api/reports/file:report:perspective-bench/query?format=arrow', { method: 'POST',
              headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ page: 'grid', visual: 'g', state: {} }) }); const b = await r.arrayBuffer();
              return { ms: performance.now() - t, bytes: b.byteLength, rows: Number(r.headers.get('x-bi-rows')) } }""")
            rows = page.evaluate("document.querySelector('perspective-viewer').getTable().then(t => t.size())")
            heap = page.evaluate("performance.memory.usedJSHeapSize / 1048576")
            print(f"\n## Perspective retest: {rows:,} rows in the grid (Chromium {browser.version})\n")
            print("| Step | Result |\n|---|---|")
            print(f"| the visual's Arrow answer over HTTP, warm | {answer['ms']:.0f} ms, {answer['bytes']:,} bytes, {answer['rows']:,} rows |")
            print(f"| navigation to a drawn grid (cold page, libraries cached by the first sign-in only) | {ready:.0f} ms |")
            print(f"| JS heap with the grid drawn | {heap:.1f} MB |")
            print("\n| Updates/s offered | Rows applied/s | Frames/s | Frame p95 / max (ms) | Frames over 50 ms | Rows in table | JS heap (MB) |\n|---:|---:|---:|---:|---:|---:|---:|")
            for rate in (0, 100, 500, 1000):
                r = page.evaluate(RUN, [rate, args.seconds])
                print(f"| {rate:,} | {r['applied'] / args.seconds:,.0f} | {r['fps']:.1f} | {r['p95']:.1f} / {r['max']:.1f} | {r['over50']} | {r['size']:,} | {r['heap']:.1f} |")
            violations = page.evaluate("window.__csp")
            print(f"\nPolicy violations on the page: {len(violations)} {json.dumps(violations)[:200]}")
            browser.close()
            print("\n| Browser | Version | First tab | Second tab at 0 s | at 6 s | at 12 s |\n|---|---|---|---|---|---|")
            for name in ("Chromium", "Chrome", "Firefox", "WebKit"):
                n, version, states = second_tab(p, name, S.CONSOLE_URL, "rita", S.PASSWORD, report)
                print(f"| {n} | {version} | " + " | ".join(states) + " |" if states else f"| {n} | {version} | | | | |")
    finally:
        stack.close()
        shutil.rmtree(tmp, ignore_errors=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
