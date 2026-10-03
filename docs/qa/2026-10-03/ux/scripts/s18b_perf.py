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
p, b, ctx, pg = start()
pg.goto(BASE+"/build"); u = pg.evaluate("()=>[...document.querySelectorAll('a[href^=\"/build/d/\"]')].map(a=>a.href)")
rows = pg.evaluate("()=>[...document.querySelectorAll('table tr')].map(r=>[r.innerText.slice(0,12),(r.querySelector('a[href^=\"/build/d/\"]')||{}).href||''])")
url50 = [h for t,h in rows if t.startswith("QA perf50")][0]
ev=[]
pg.on("request", lambda r: ev.append(("req", r.url.split("/build/")[-1][:40], time.time())) if "/build/designs/" in r.url else None)
pg.on("response", lambda r: ev.append(("res", r.url.split("/build/")[-1][:40], time.time(), r.status)) if "/build/designs/" in r.url else None)
pg.goto(url50); pg.wait_for_selector("[data-preview] .pnl[data-panel]"); pg.wait_for_timeout(3000)
pg.click("[data-panel=details] .pnl-h"); pg.wait_for_timeout(500)
for i in range(4):
    ev.clear(); inp = pg.locator("[data-inspector] .wb-field:has(label:text-is('title')) input").first
    inp.fill(f"Title {i}"); t0=time.time(); inp.press("Tab"); pg.wait_for_timeout(3500)
    out=[]
    for e in ev:
        out.append(f"{e[0]} {e[1].split('?')[0].split('/')[-1] if '/ops' not in e[1] else 'ops'} +{round(e[2]-t0,3)}")
    print(f"edit {i}:", " | ".join(out))
# 50-sample check via /check explicitly
ev.clear(); t0=time.time(); pg.click("[role=tab][data-tab=tests]"); pg.keyboard.press("Control+k"); pg.keyboard.type("run check"); pg.keyboard.press("Enter"); pg.wait_for_timeout(3000)
print("run check:", [(e[0], e[1][-20:], round(e[2]-t0,3)) for e in ev])
b.close()
