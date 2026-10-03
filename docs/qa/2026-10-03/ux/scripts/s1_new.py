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

import sys, glob; sys.path.insert(0, __file__.rsplit("/", 1)[0])
from pw import *
W = "/tmp/claude-1000/-home-ashutosh-IdeaProjects-drishti/efa68c11-fb98-4f9a-8faa-96a952090c0e/scratchpad/uxw/shipments"
with sync_playwright() as p:
    b = p.chromium.launch(); ctx, pg = new_ctx(b, "author"); attach(pg, "s1")
    print("after login", pg.url)
    # menu path
    nav = pg.evaluate("()=>[...document.querySelectorAll('header a, nav a, .topbar a')].map(a=>a.innerText.trim()+'|'+a.getAttribute('href')).filter(s=>s.length>2).slice(0,60)")
    print(nav)
    shot(pg, "s1-home")
    pg.goto(BASE + "/build/new"); pg.wait_for_timeout(1000); shot(pg, "s1-new", full=True)
    pg.set_input_files("input[data-folder]", W); pg.wait_for_timeout(1500)
    print("status:", pg.inner_text("[data-status]")); 
    pg.fill("[data-name]", "QA shipments"); pg.fill("[data-kind]", "shipment")
    t = time.time(); pg.click("[data-create]")
    pg.wait_for_url("**/build/d/**", timeout=60000); print("create->url", round(time.time()-t,2), pg.url)
    pg.wait_for_selector("[data-preview] .pnl, [data-preview] *", timeout=30000)
    pg.wait_for_timeout(2500); shot(pg, "s1-workbench")
    print("status line:", pg.inner_text("[data-say]")); print("result:", pg.inner_text("[data-result]"))
    print("tabs:", pg.evaluate("()=>[...document.querySelectorAll('[role=tab]')].map(e=>e.innerText)"))
    b.close()
for e in ERRS: print("ERR", e)
