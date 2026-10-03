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

from wb import *
import statistics
D = "/tmp/claude-1000/-home-ashutosh-IdeaProjects-drishti/efa68c11-fb98-4f9a-8faa-96a952090c0e/scratchpad/uxw/ship50"
p, b, ctx, pg = start(); attach(pg, "s18")
def mk(dirp, name):
    pg.goto(BASE+"/build/new"); pg.wait_for_timeout(500); pg.set_input_files("input[data-folder]", dirp); pg.wait_for_timeout(2500)
    pg.fill("[data-name]", name); pg.fill("[data-kind]", "shipment"); t=time.time(); pg.click("[data-create]"); pg.wait_for_url("**/build/d/**", timeout=90000)
    pg.wait_for_selector("[data-preview] .pnl[data-panel]", timeout=60000); return pg.url, round(time.time()-t,2)
url6, t6 = mk("/tmp/claude-1000/-home-ashutosh-IdeaProjects-drishti/efa68c11-fb98-4f9a-8faa-96a952090c0e/scratchpad/uxw/shipments", "QA perf6")
url50, t50 = mk(D, "QA perf50"); print("create->first canvas: 6 samples", t6, "s | 50 samples", t50, "s")
for label, u in (("6", url6), ("50", url50)):
    ts=[]
    for i in range(3):
        t=time.time(); pg.goto(u); pg.wait_for_selector("[data-preview] .pnl[data-panel]", timeout=60000); ts.append(round(time.time()-t,2))
    print(f"reload to first canvas ({label} samples):", ts)
    # nav timing
    print("   dom/load:", pg.evaluate("()=>{const n=performance.getEntriesByType('navigation')[0];return [Math.round(n.domContentLoadedEventEnd),Math.round(n.loadEventEnd)]}"), "scripts:", pg.evaluate("()=>performance.getEntriesByType('resource').filter(r=>r.name.endsWith('.js')).length"), "transfer KB:", pg.evaluate("()=>Math.round(performance.getEntriesByType('resource').reduce((a,r)=>a+r.transferSize,0)/1024)"))
# check-as-you-type latency with 50 samples: edit title in inspector -> time until result status changes/ canvas heading updates
pg.goto(url50); pg.wait_for_selector("[data-preview] .pnl[data-panel]"); pg.wait_for_timeout(3000); print("result at rest:", result(pg))
pg.click("[data-panel=details] .pnl-h"); pg.wait_for_timeout(500)
lat_canvas=[]; lat_check=[]
for i in range(6):
    inp = pg.locator("[data-inspector] .wb-field:has(label:text-is('title')) input").first
    new = f"Title {i}"; inp.fill(new)
    t0=time.time(); inp.press("Tab")
    pg.wait_for_function("(n)=>{const h=document.querySelector('[data-preview] [data-panel=details] .pnl-h h3');return h&&h.textContent.includes(n)}", arg=new, timeout=20000); lat_canvas.append(round(time.time()-t0,2))
    # check: wait until result text contains digits and not 'checking'
    pg.wait_for_function("()=>{const r=document.querySelector('[data-result]').innerText;return /[0-9]+\\/[0-9]+/.test(r)}", timeout=30000); lat_check.append(round(time.time()-t0,2))
print("inspector edit -> canvas redraw (s):", lat_canvas, "median", statistics.median(lat_canvas)); print("inspector edit -> result visible (s):", lat_check, "median", statistics.median(lat_check))
# YAML typing: keystroke to canvas and to check
tab(pg,"yaml"); pg.click(".CodeMirror"); pg.keyboard.press("Control+End")
tt=[]
for i in range(4):
    t0=time.time(); pg.keyboard.type(f"\n# note {i}", delay=15)
    pg.wait_for_function("()=>/Text|matches|Changed|Preview/i.test(document.querySelector('[data-say]').innerText)", timeout=20000)
    tt.append(round(time.time()-t0,2))
print("yaml typing -> status updated (s):", tt)
# Tests tab render time 50x panels
t0=time.time(); tab(pg,"tests"); pg.wait_for_selector("[data-tests] table", timeout=20000); print("tests tab shows matrix in", round(time.time()-t0,2), "s;", pg.locator("[data-tests] td").count(), "cells; table width", pg.evaluate("()=>document.querySelector('[data-tests] table').scrollWidth"), "vs pane", pg.evaluate("()=>document.querySelector('[data-tests]').clientWidth"))
shot(pg,"s18-tests50")
# sample switch latency
t0=time.time(); pg.click("[data-next]"); pg.wait_for_function("()=>document.querySelector('[data-sample-pos]').innerText.includes('2/50')"); pg.wait_for_timeout(100); print("next sample:", round(time.time()-t0,2))
b.close()
