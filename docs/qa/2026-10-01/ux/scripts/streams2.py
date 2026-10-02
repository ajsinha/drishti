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
with sync_playwright() as p:
    b = p.chromium.launch()
    ctx = b.new_context(viewport={"width": 1200, "height": 800})
    pages = []
    for i in range(1, 7):
        pg = ctx.new_page(); pg.goto(BASE + "/v/trade/MX-2000000%d" % i, wait_until="domcontentloaded"); pages.append(pg)
    time.sleep(3)
    print("visibility:", [pg.evaluate("document.visibilityState") for pg in pages])
    pg7 = ctx.new_page(); t0 = time.time()
    try:
        pg7.goto(BASE + "/t", wait_until="domcontentloaded", timeout=60000); print("7th tab loaded /t in %.1fs" % (time.time() - t0))
    except Exception as e:
        print("7th tab /t did not load within 60s")
    # close one live tab: does the 7th proceed?
    pages[0].close(); t0 = time.time()
    try:
        pg7.goto(BASE + "/t", wait_until="domcontentloaded", timeout=30000); print("after closing one tab: loaded in %.1fs" % (time.time() - t0))
    except Exception:
        print("still stuck")
    b.close()
