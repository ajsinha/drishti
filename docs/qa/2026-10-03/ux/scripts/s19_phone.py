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
p = sync_playwright().start(); b = p.chromium.launch(); ctx, pg = new_ctx(b, "author", 390, 844); attach(pg, "s19")
make_design(pg, "QA phone")
pos = lambda s: pg.evaluate("(s)=>{const e=document.querySelector(s);if(!e)return null;const r=e.getBoundingClientRect();return [Math.round(r.top+scrollY),Math.round(r.height),Math.round(r.width),getComputedStyle(e).position]}", s)
for s in ("[data-left]","[data-preview]","[data-inspector]",".wb-right",".wb-bar","[data-undo]","[data-palette]"): print(s, pos(s))
print("page height", pg.evaluate("()=>document.documentElement.scrollHeight"), "viewport 844")
# tap-select panel and see where inspector goes
pg.click("[data-panel=details] .pnl-h"); pg.wait_for_timeout(600); print("after select scrollY:", pg.evaluate("()=>scrollY"), "inspector top", pos("[data-inspector]"))
# drag from palette at phone width (touch not tested), try Add button
pg.locator("[data-palette] li[data-kind=gauge] button").scroll_into_view_if_needed(); pg.locator("[data-palette] li[data-kind=gauge] button").click(); pg.wait_for_timeout(900); print("add at phone:", say(pg)[:70], "| focus:", pg.evaluate("()=>document.activeElement.tagName"))
shot(pg,"s19-phone-full", full=True)
# yaml tab overflow cause
tab(pg,"yaml"); pg.wait_for_timeout(500)
print("yaml overflow:", pg.evaluate("()=>({sw:document.documentElement.scrollWidth,cw:document.documentElement.clientWidth,cm:(()=>{const e=document.querySelector('.CodeMirror');return e&&[e.getBoundingClientRect().width,e.scrollWidth]})(),wide:[...document.querySelectorAll('body *')].filter(e=>e.getBoundingClientRect().right>document.documentElement.clientWidth+2&&!e.closest('.CodeMirror')).slice(0,4).map(e=>e.tagName+'.'+e.className)})"))
shot(pg,"s19-phone-yaml")
pg.goto(BASE+"/build"); pg.wait_for_timeout(1000)
print("mydesigns overflow:", pg.evaluate("()=>({sw:document.documentElement.scrollWidth,wide:[...document.querySelectorAll('body *')].filter(e=>e.getBoundingClientRect().right>document.documentElement.clientWidth+2).slice(0,6).map(e=>e.tagName+'.'+(e.className||'')+':'+Math.round(e.getBoundingClientRect().right))})"))
shot(pg,"s19-phone-mydesigns")
b.close()
