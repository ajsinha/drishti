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
import json
p, b, ctx, pg = start(); attach(pg, "s5b")
make_design(pg, "QA kinds2")
tab(pg,"problems"); print("PROBLEMS initial:", pg.inner_text("[data-problems]")[:300].replace("\n"," | "))
for kind in ["table","tabs","pivot","graph","candlestick","surface","waterfall","timeline","scatter","line","ladder","hbar","area","histogram","status","links"]:
    tab(pg,"inspector")
    li = pg.locator(f"[data-palette] li[data-kind={kind}] button"); li.scroll_into_view_if_needed(); li.click(); pg.wait_for_timeout(900)
    ins = pg.inner_text("[data-inspector]").replace("\n"," | ")
    i = ins.find("Required"); 
    vals = pg.evaluate("()=>[...document.querySelectorAll('[data-inspector] input[type=text],[data-inspector] input:not([type]),[data-inspector] textarea')].filter(e=>e.value).map(e=>(e.getAttribute('aria-label')||e.id)+'='+e.value.slice(0,50))")
    sel = pg.evaluate("()=>{const e=document.querySelector('[data-preview] .pnl.wb-sel');return e?e.innerText.slice(0,140).replace(/\\n/g,' / '):'(no selection)'}")
    print(f"[{kind}] req: {ins[i:i+80]} | filled: {vals} | draws: {sel}")
tab(pg,"problems"); print("PROBLEMS end:", pg.inner_text("[data-problems]")[:600].replace("\n"," | "))
shot(pg,"s5b-end", full=True)
b.close()
