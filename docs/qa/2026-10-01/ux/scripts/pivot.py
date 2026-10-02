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

"""Pivot tab: panel pivot (allkinds t1) and search pivot over 10k trades: keyboard R/C/V/F on every field, limits,
drill-down, export."""
import sys, time; sys.path.insert(0, __file__.rsplit("/", 1)[0])
from pw import *

def state(pg, host):
    return pg.evaluate("""(h) => { const r = document.querySelector(h); if (!r) return 'no host';
      const z = {}; r.querySelectorAll('.pv-chip').forEach(c => { const k = c.getAttribute('data-zone'); if (k !== 'fields') z[k] = (z[k] ? z[k] + ',' : '') + c.innerText.trim().split('\\n')[0]; });
      const st = r.querySelector('.pv-status'); const g = r.querySelector('table');
      return JSON.stringify(z).slice(0, 400) + ' | status=' + (st ? st.innerText.slice(0,200) : '') + ' | gridrows=' + (g ? g.rows.length : 0); }""", host)

def run_pivot(pg, host, label, keys_per_field=("r", "r", "c", "c", "v", "v", "f", "f", "r", "c", "v")):
    tab = pg.locator(host + " [data-pv-tab]").filter(has_text="Pivot").first
    if not tab.count():
        tab = pg.get_by_role("tab", name="Pivot").first
    t0 = time.time(); tab.click(); pg.wait_for_timeout(2500)
    print(label, "opened in %.1fs:" % (time.time() - t0), state(pg, host))
    chips = pg.locator(host + " .pv-chip")
    n = pg.locator(host + ' .pv-chip[data-zone="fields"]').count(); print("  chips:", n, [chips.nth(i).inner_text().split("\n")[0] for i in range(min(n, 40))])
    shot(pg, "pivot_%s_open" % label)
    # push each field into rows, columns, values, filters in turn (more than the max)
    for i in range(n):
        ch = pg.locator(host + ' .pv-chip[data-zone="fields"]').nth(i)
        if not ch.count():
            break
        k = keys_per_field[i % len(keys_per_field)]
        ch.focus(); t0 = time.time(); pg.keyboard.press(k); pg.wait_for_timeout(700)
        dt = time.time() - t0
        msg = pg.evaluate("(h)=>{const s=document.querySelector(h+' [aria-live]'); return s? s.innerText.slice(0,150):''}", host)
        print("  field %d key %s %.1fs live=%s" % (i, k, dt, msg))
    pg.wait_for_timeout(2000)
    print("  after all:", state(pg, host))
    shot(pg, "pivot_%s_maxed" % label, full=False)
    # drill-down: click first numeric cell
    cell = pg.locator(host + " td.pv-c, " + host + " td[data-r]").first
    if cell.count():
        cell.click(); pg.wait_for_timeout(2500); shot(pg, "pivot_%s_drill" % label)
        print("  drill:", pg.evaluate("()=>{const d=document.querySelector('.pv-pop, dialog[open], .pv-docs'); return d? d.innerText.slice(0,200).replace(/\\n/g,' | '):'no drill popup'}"))
        pg.keyboard.press("Escape")
    # export
    ex = pg.locator(host + " [data-pv-export]").first
    if ex.count():
        try:
            with pg.expect_download(timeout=15000) as d:
                ex.click()
            dl = d.value; print("  export:", dl.suggested_filename)
        except Exception as e:
            print("  export: no download (%s) menu? " % repr(e)[:80])
            items = pg.locator("[data-pv-export]")
            print("   export controls:", items.count(), [items.nth(i).inner_text()[:20] for i in range(items.count())])

with sync_playwright() as p:
    b = p.chromium.launch()
    ctx = b.new_context(viewport={"width": 1600, "height": 1000}, accept_downloads=True)
    pg = ctx.new_page(); attach(pg, "pivot")
    pg.goto(BASE + "/v/trade/MX-20000001"); pg.wait_for_timeout(2500)
    run_pivot(pg, "[data-panel=t1]", "panel_t1")
    pg.goto(BASE + "/v/trade/MX-20000001"); pg.wait_for_timeout(2500)
    run_pivot(pg, "[data-panel=em]", "panel_empty") if pg.locator("[data-panel=em] [data-pv-tab]").count() else print("empty table has no pivot tab")
    pg.goto(BASE + "/s?q=TRD%20where%20mtm%20%3E%20-1e"); pg.wait_for_timeout(500)
    pg.goto(BASE + "/s?q=TRD"); pg.wait_for_timeout(3000)
    shot(pg, "pivot_search_table")
    run_pivot(pg, "main", "search_10k")
    b.close()
for e in ERRS: print("ERR", e)
