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

"""Many live streams: N tabs with live views in one browser profile, then a workspace with 4 panes; is the console
still responsive (suggestions, navigation)?"""
import sys, time, json; sys.path.insert(0, __file__.rsplit("/", 1)[0])
from pw import *
IDS = ["MX-20000001", "MX-20000002", "MX-20000003", "MX-20000004", "MX-20000005"]
with sync_playwright() as p:
    b = p.chromium.launch()
    ctx = b.new_context(viewport={"width": 1400, "height": 900})
    pages = []
    for i in IDS:
        pg = ctx.new_page(); attach(pg, "tab" + i); pg.goto(BASE + "/v/trade/" + i, wait_until="domcontentloaded"); pages.append(pg)
    time.sleep(4)
    last = pages[-1]
    for k in range(3):
        t0 = time.time()
        try:
            r = last.evaluate("async () => { const t = performance.now(); const r = await fetch('/api/suggest?q=TRD%20MX-2000001'); await r.text(); return Math.round(performance.now() - t); }")
        except Exception as e:
            r = repr(e)[:100]
        print("suggest from tab 5 (4 other live tabs): %s ms" % r)
    # is tab 1 still ticking?
    a = pages[0].inner_text("main"); time.sleep(5); bb = pages[0].inner_text("main")
    print("tab1 ticking:", a != bb, "| tab9 ticking:", end=" ")
    a = last.inner_text("main"); time.sleep(5); print(a != last.inner_text("main"))
    t0 = time.time(); last.goto(BASE + "/v/netting-set/NS-SUMMIT-NY", wait_until="load"); print("navigate tab9: %.1fs" % (time.time() - t0))
    for pg in pages[:-1]: pg.close()
    # workspace with four panes
    pg = last
    pg.goto(BASE + "/w"); pg.wait_for_timeout(1500)
    print("w index:", pg.inner_text("main")[:300].replace("\n", " | "))
    pg.goto(BASE + "/w/QA"); pg.wait_for_timeout(2000)
    print("ws url:", pg.url)
    sel = pg.locator("[data-layout]")
    if sel.count():
        opts = sel.locator("option").all_inner_texts(); print("layouts:", opts)
        sel.select_option(index=len(opts) - 1); pg.wait_for_timeout(800)
    ins = pg.locator("[data-pane-input]")
    print("panes:", ins.count())
    for i in range(ins.count()):
        ins.nth(i).fill("TRD " + IDS[i]); ins.nth(i).press("Enter"); pg.wait_for_timeout(1500)
    pg.wait_for_timeout(3000)
    shot(pg, "ws_four")
    # drag the first divider
    div = pg.locator(".ws-div, [role=separator]").first
    if div.count():
        bx = div.bounding_box(); pg.mouse.move(bx["x"] + bx["width"] / 2, bx["y"] + bx["height"] / 2); pg.mouse.down()
        pg.mouse.move(bx["x"] - 2000, bx["y"] + bx["height"] / 2, steps=10); pg.mouse.up(); pg.wait_for_timeout(800)
        shot(pg, "ws_divider_far_left")
        print("divider dragged far left; sizes:", pg.evaluate("()=>[...document.querySelectorAll('[data-grid] > *')].map(e=>Math.round(e.getBoundingClientRect().width)).join(',')"))
        div.focus()
        for k in range(50): pg.keyboard.press("ArrowRight")
        print("after 50 x ArrowRight:", pg.evaluate("()=>[...document.querySelectorAll('[data-grid] > *')].map(e=>Math.round(e.getBoundingClientRect().width)).join(',')"))
        shot(pg, "ws_divider_keys")
    nm = pg.locator("[data-name]")
    if nm.count(): nm.fill("QA four <b>")
    pg.locator("[data-save]").first.click(); pg.wait_for_timeout(1500)
    print("save msg:", pg.evaluate("()=>document.querySelector('[data-msg]').innerText"))
    nm.fill("QA four"); pg.locator("[data-save]").first.click(); pg.wait_for_timeout(1500)
    print("save msg2:", pg.evaluate("()=>document.querySelector('[data-msg]').innerText"))
    lt = pg.evaluate("()=>performance.getEntriesByType('longtask').length")
    b.close()
for e in ERRS:
    if e["type"] != "requestfailed": print("ERR", e)
