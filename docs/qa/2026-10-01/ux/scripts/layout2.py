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

import sys; sys.path.insert(0, __file__.rsplit("/", 1)[0])
from pw import *
SCEN = sys.argv[1] if len(sys.argv) > 1 else "hideall"
with sync_playwright() as p:
    b = p.chromium.launch()
    pg = b.new_page(viewport={"width": 1600, "height": 1000})
    def onreq(r):
        if "/api/layout" in r.url: print("REQ", r.method, r.url, (r.post_data or "")[:600])
    def onresp(r):
        if "/api/layout" in r.url: print("RESP", r.status, r.text()[:600])
    pg.on("request", onreq); pg.on("response", onresp)
    pg.goto(BASE + "/v/trade/" + (sys.argv[2] if len(sys.argv) > 2 else "MX-20000001")); pg.wait_for_timeout(2500)
    pg.keyboard.press("Alt+l"); pg.wait_for_timeout(600)
    els = pg.locator(".pnl[data-panel]").all()
    if SCEN == "hideall":
        for el in els: el.focus(); pg.keyboard.press("h")
    elif SCEN == "one":
        els[0].focus(); pg.keyboard.press("ArrowDown")
    elif SCEN == "hideone":
        els[0].focus(); pg.keyboard.press("h")
    elif SCEN == "tall":
        els[1].focus()
        for i in range(30): pg.keyboard.press("Shift+ArrowDown")
        print("live:", pg.evaluate("() => document.querySelector('[data-layout-live]').innerText"))
    elif SCEN == "right":
        els[0].focus(); pg.keyboard.press("ArrowRight")
    elif SCEN == "first":
        els[0].focus()
        for k in ["ArrowDown", "ArrowDown", "Shift+ArrowLeft"] + ["-"] * 15 + ["Shift+ArrowDown"] * 30 + ["ArrowRight"]: pg.keyboard.press(k)
    elif SCEN == "narrow":
        els[0].focus()
        for i in range(15): pg.keyboard.press("-")
    pg.click("[data-layout-save]"); pg.wait_for_timeout(1500)
    print("msg:", pg.evaluate("() => document.querySelector('[data-layout-msg]').innerText"))
    shot(pg, "layout2_" + SCEN)
    b.close()
