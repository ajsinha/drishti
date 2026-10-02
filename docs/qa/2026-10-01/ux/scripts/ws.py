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

"""QUICKSTART step 9 (finance pack): open /w, click Credit desk, panes, follow, dividers, save."""
import sys, time; sys.path.insert(0, __file__.rsplit("/", 1)[0])
from pw import *
SIZES = "()=>[...document.querySelectorAll('[data-grid] iframe')].map(e=>{const r=e.getBoundingClientRect(); return Math.round(r.width)+'x'+Math.round(r.height)}).join(' ')"
with sync_playwright() as p:
    b = p.chromium.launch()
    pg = b.new_page(viewport={"width": 1600, "height": 1000}); attach(pg, "ws")
    pg.goto(BASE + "/w"); pg.wait_for_timeout(1500)
    print("starters:", pg.inner_text("main")[:400].replace("\n", " | "))
    pg.get_by_text("Credit desk").first.click(); pg.wait_for_timeout(5000)
    print("url:", pg.url, "| panes:", pg.locator("[data-grid] iframe").count(), "| sizes:", pg.evaluate(SIZES))
    shot(pg, "ws_credit_desk")
    seps = pg.locator("[role=separator]")
    print("separators:", seps.count(), [seps.nth(i).get_attribute("aria-label") for i in range(seps.count())], [seps.nth(i).get_attribute("aria-valuenow") for i in range(seps.count())])
    if seps.count():
        s = seps.first; bx = s.bounding_box()
        pg.mouse.move(bx["x"] + bx["width"] / 2, bx["y"] + bx["height"] / 2); pg.mouse.down()
        pg.mouse.move(bx["x"] - 3000, bx["y"] + bx["height"] / 2, steps=12); pg.mouse.up(); pg.wait_for_timeout(800)
        print("after drag far left:", pg.evaluate(SIZES)); shot(pg, "ws_div_far_left")
        s.focus()
        for i in range(60): pg.keyboard.press("ArrowRight")
        print("after 60 ArrowRight:", pg.evaluate(SIZES), "valuenow", s.get_attribute("aria-valuenow")); shot(pg, "ws_div_keys")
    # follow: click a trade link inside pane 1
    fr = pg.frame_locator("[data-grid] iframe").first
    lk = fr.locator("a[href^='/v/trade/']").first
    if lk.count():
        txt = lk.inner_text(); lk.click(); pg.wait_for_timeout(3000)
        print("clicked", txt, "-> pane srcs:", pg.evaluate("()=>[...document.querySelectorAll('[data-grid] iframe')].map(f=>f.getAttribute('src')).join(' | ')"))
    # Alt+1..4
    pg.keyboard.press("Alt+2"); pg.wait_for_timeout(300)
    print("Alt+2 focus:", pg.evaluate("()=>document.activeElement.tagName + ' ' + (document.activeElement.getAttribute('title')||'')"))
    # save
    nm = pg.locator("[data-name]")
    if nm.count():
        nm.fill("QA desk"); pg.locator("[data-save]").first.click(); pg.wait_for_timeout(1500)
        print("save:", pg.evaluate("()=>document.querySelector('[data-msg]').innerText"))
    pg.goto(BASE + "/w/QA%20desk"); pg.wait_for_timeout(3000); print("reopen sizes:", pg.evaluate(SIZES))
    # 390 px
    pg.set_viewport_size({"width": 390, "height": 844}); pg.wait_for_timeout(1500)
    print("390 sizes:", pg.evaluate(SIZES), pg.evaluate("()=>[document.documentElement.scrollWidth, innerWidth]"))
    shot(pg, "ws_390")
    pg.set_viewport_size({"width": 1600, "height": 1000})
    pg.goto(BASE + "/"); pg.wait_for_timeout(1500)
    pg.goto(BASE + "/go?q=TRD%20IRS-48213"); pg.wait_for_timeout(2500); print("IRS-48213:", pg.url, pg.inner_text("main")[:80].replace("\n", " | "))
    b.close()
for e in ERRS:
    if e["type"] != "requestfailed": print("ERR", e)
