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

import sys, time; sys.path.insert(0, __file__.rsplit("/", 1)[0])
from pw import *
F = "()=>document.activeElement.tagName + ' ' + (document.activeElement.getAttribute('title')||document.activeElement.getAttribute('aria-label')||'')"
with sync_playwright() as p:
    b = p.chromium.launch()
    pg = b.new_page(viewport={"width": 1600, "height": 1000}); attach(pg, "ws2")
    pg.goto(BASE + "/w/Credit%20desk?template=Credit%20desk"); pg.wait_for_timeout(4000)
    pg.locator("h1, .ws-title, body").first.click(position={"x": 5, "y": 5}) if False else None
    for k in ["Alt+3", "Alt+2", "Alt+1", "Alt+3"]:
        pg.keyboard.press(k); pg.wait_for_timeout(400); print(k, "->", pg.evaluate(F))
    print("controls:", pg.evaluate("()=>[...document.querySelectorAll('[data-save],[data-saveas]')].map(e=>e.tagName+':'+e.innerText.trim()).join(' | ')"))
    pg.on("dialog", lambda d: (print("dialog:", d.message[:120]), d.accept("QA desk")))
    sa = pg.locator("[data-saveas]")
    if sa.count(): sa.first.click(); pg.wait_for_timeout(2000)
    print("msg:", pg.evaluate("()=>document.querySelector('[data-msg]').innerText"), pg.url)
    b.close()
for e in ERRS:
    if e["type"] != "requestfailed": print("ERR", e)
