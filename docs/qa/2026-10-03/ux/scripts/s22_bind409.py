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
W = "/tmp/claude-1000/-home-ashutosh-IdeaProjects-drishti/efa68c11-fb98-4f9a-8faa-96a952090c0e/scratchpad/uxw/"
p = sync_playwright().start(); b = p.chromium.launch(); ctx, pg = new_ctx(b, "author"); attach(pg, "s22")
make_design(pg, "QA bind"); url = pg.url
# --- two tabs: 409
pg2 = ctx.new_page(); attach(pg2, "tab2"); pg2.goto(url); pg2.wait_for_selector("[data-preview] .pnl[data-panel]"); pg2.wait_for_timeout(1500)
pg.locator("[data-palette] li[data-kind=gauge] button").click(); pg.wait_for_timeout(1000)   # tab1 now rev 2
pg2.locator("[data-palette] li[data-kind=markdown] button").scroll_into_view_if_needed(); pg2.locator("[data-palette] li[data-kind=markdown] button").click(); pg2.wait_for_timeout(1500)
print("TAB2 (stale) say:", say(pg2)[:220], "| rev", rev(pg2), "| panels", [x["id"] for x in panels(pg2)])
print("   focus after 409:", pg2.evaluate("()=>document.activeElement.tagName+'.'+document.activeElement.className"), "| live:", live(pg2)[:90])
# --- F1 with unsent YAML
pg.bring_to_front(); tab(pg,"yaml"); pg.click(".CodeMirror"); pg.keyboard.press("Control+End"); pg.keyboard.insert_text("\n# unsent note")
with pg.expect_navigation(): pg.keyboard.press("F1")
pg.go_back(); pg.wait_for_timeout(2500); tab(pg,"yaml"); txt = pg.evaluate("()=>document.querySelector('.CodeMirror').CodeMirror.getValue()"); print("F1 immediately after typing: note survived?", "unsent note" in txt)
# F1 inside an inspector input and canvas
pg.goto(url); pg.wait_for_selector("[data-preview] .pnl[data-panel]"); pg.click("[data-panel=details] .pnl-h"); pg.wait_for_timeout(500)
inp = pg.locator("[data-inspector] .wb-field:has(label:text-is('title')) input").first; inp.fill("Unsent title"); 
with pg.expect_navigation(): pg.keyboard.press("F1")
pg.go_back(); pg.wait_for_timeout(2500); print("F1 mid-inspector edit: title kept?", "Unsent title" in pg.inner_text("[data-preview]"))
# --- file binding
sut = W+"sutras/market"; os.makedirs(sut, exist_ok=True)
pg.goto(url); pg.wait_for_selector("[data-preview] .pnl[data-panel]"); 
pg.once("dialog", lambda d: d.accept("market/qa-bound.v1.sutra.yaml"))
pg.click("[data-ship-menu]"); pg.get_by_role("option", name=re.compile("Bind to a file")).click(); pg.wait_for_timeout(2000); print("bind say:", say(pg)[:160], "| chip:", pg.inner_text("[data-bound-chip]"), "| save btn:", pg.inner_text("[data-save]").strip())
f = sut+"/qa-bound.v1.sutra.yaml"; print("file exists after bind:", os.path.exists(f))
pg.keyboard.press("Control+s"); pg.wait_for_timeout(2000); print("save->", say(pg)[:160], "| file:", os.path.exists(f))
if os.path.exists(f):
    t = open(f).read(); open(f,"w").write(t.replace("title:", "title:", 1) + "\n# edited in IDE\n"); 
    pg.wait_for_timeout(9000); print("IDE edit came back:", say(pg)[:160]); tab(pg,"yaml"); print("   yaml has IDE comment:", "edited in IDE" in pg.evaluate("()=>document.querySelector('.CodeMirror').CodeMirror.getValue()"))
    # clash: edit design, then edit file, then save
    tab(pg,"design"); pg.locator("[data-palette] li[data-kind=markdown] button").click(); pg.wait_for_timeout(800)
    open(f,"a").write("\n# second IDE edit\n"); pg.keyboard.press("Control+s"); pg.wait_for_timeout(1500); print("clash save ->", say(pg)[:260])
b.close()
for e in ERRS:
    if e["type"] in ("pageerror","http500","http409"): print("ERR", e)
