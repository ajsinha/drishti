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
    pg = b.new_page(viewport={"width": 1600, "height": 1000}); attach(pg, "calcvar")
    pg.goto(BASE + "/v/var/VAR-RATES"); pg.wait_for_timeout(2000)
    pg.keyboard.press("Alt+c"); pg.wait_for_timeout(1000)
    sel = pg.locator("[data-calc-snippets]")
    opts = sel.locator("option").all_inner_texts()
    print("snippets offered on VAR:", len(opts), opts[:8])
    pick = [o for o in opts if o.startswith("VaR and expected shortfall")]
    pg.on("dialog", lambda d: d.accept()); sel.select_option(label=pick[0]); pg.wait_for_timeout(800)
    print("editor:", pg.evaluate("() => { const cm = document.querySelector('#calcDrawer .CodeMirror'); return cm ? cm.CodeMirror.getValue().slice(0,300) : document.querySelector('[data-calc-code]').value.slice(0,300); }"))
    pg.click("[data-calc-run]")
    for i in range(60):
        pg.wait_for_timeout(500)
        st = pg.evaluate("() => document.querySelector('[data-calc-status]').innerText")
        if st.startswith("Done") or st.startswith("Failed"): break
    print("status:", st)
    out = pg.evaluate("() => document.querySelector('[data-calc-out]').innerText")
    for s in ["9,611,219", "9,802,031", "10,860,677", "57%", "16,740,000"]:
        print("  contains", s, s in out)
    print(out[:1500])
    shot(pg, "calc_var_snippet")
    b.close()
