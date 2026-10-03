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
import re
EX = ["all-panels-showcase","exposure-profile","linked-sources","market-charts","operations-status","pivot-row-groups","pnl-explain","relationships","risk-distribution","tree-table"]
p, b, ctx, pg = start(); attach(pg, "s13")
for e in EX:
    t=time.time(); example(pg, e)
    meta = pg.inner_text(".wb-meta").replace("\n"," ")[:110]
    prob = pg.inner_text("[role=tab][data-tab=problems]").replace("\n"," "); res = result(pg)
    pans = panels(pg)
    errs = pg.locator("[data-preview] .pnl-err, [data-preview] .pnl.err").count()
    notes_tab = pg.locator("text=Notes").count()
    # problems list
    tab(pg,"problems"); pt = pg.inner_text("[data-problems]").replace("\n"," | ")[:200]
    # empty panels in the screen
    empties = pg.evaluate("()=>[...document.querySelectorAll('[data-preview] .pnl')].filter(p=>/No data|not found|Error|error/i.test(p.innerText.slice(0,200))).map(p=>p.dataset.panel+':'+p.innerText.replace(/\\n/g,' ').slice(0,60))")
    print(f"{e:22s} h1={pg.inner_text('.wb-title')!r} {prob!r} {res!r} panels={len(pans)} errpanels={errs} | {pt} | empties={empties[:4]} | {meta}")
    shot(pg, "s13-"+e)
    # README: help link
    r = pg.request.get(BASE+"/help/examples"); 
b.close(); p.stop()
# help pages
p2, b2, c2, pg2 = start()
pg2.goto(BASE+"/help/examples"); pg2.wait_for_timeout(1000)
links = pg2.evaluate("()=>[...document.querySelectorAll('main a, article a')].map(a=>a.getAttribute('href')).filter(h=>h&&!h.startsWith('#'))")
print("help/examples links:", len(links), links[:25])
bad=[]
for h in sorted(set(links)):
    if h.startswith("http"): continue
    u = h if h.startswith("/") else "/help/"+h
    r = pg2.request.get(BASE+u)
    if r.status>=400: bad.append((h,r.status))
print("dead links on examples help:", bad)
b2.close()
for e in ERRS:
    if e["type"] in("pageerror",): print("ERR", e)
