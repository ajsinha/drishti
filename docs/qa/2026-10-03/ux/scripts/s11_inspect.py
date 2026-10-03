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
p, b, ctx, pg = start(); attach(pg, "s11")
make_design(pg, "QA inspect")
def sel(i): 
    pg.click(f"[data-panel={i}] .pnl-h"); pg.wait_for_timeout(500)
# expression autocomplete in table rows
sel("legs"); tab(pg,"inspector")
f = pg.locator("[data-inspector] .wb-field:has(label:text-is('rows *')) input").first
f.fill(""); f.type("$.", delay=60); pg.wait_for_timeout(700)
lb = pg.locator("[role=listbox]:visible"); print("after '$.' listbox:", lb.count(), lb.first.inner_text().replace("\n"," | ")[:200] if lb.count() else "")
f.type("le", delay=60); pg.wait_for_timeout(400); print("filtered:", lb.first.inner_text().replace("\n"," | ")[:100] if lb.count() else "none"); shot(pg,"s11-expr")
print("aria:", f.get_attribute("role"), f.get_attribute("aria-expanded"), f.get_attribute("aria-autocomplete"), f.get_attribute("aria-controls"), f.get_attribute("aria-activedescendant"))
pg.keyboard.press("ArrowDown"); pg.keyboard.press("Enter"); pg.wait_for_timeout(1200); print("value now:", f.input_value(), "|", say(pg)[:100])
f.type("[", delay=50); f.type("@.", delay=60); pg.wait_for_timeout(500); print("@. list:", lb.first.inner_text().replace("\n"," | ")[:120] if lb.count() else "none"); pg.keyboard.press("Escape"); print("Esc closes:", lb.count()==0)
f.fill(""); f.type("fm", delay=60); pg.wait_for_timeout(400); print("fn list:", lb.first.inner_text().replace("\n"," | ")[:120] if lb.count() else "none"); pg.keyboard.press("Escape")
f.fill("$.legs"); f.press("Tab"); pg.wait_for_timeout(1000)
# options types: every option type incl. select (fmt), switch, number
sel("details"); 
for lab, val in (("title","Shipment facts"),("span","6"),("height","8"),("code","SHP")):
    c = pg.locator(f"[data-inspector] label:text-is('{lab}')").first
    inp = pg.locator(f"[data-inspector] .wb-field:has(label:text-is('{lab}')) input").first
    inp.fill(val); inp.press("Tab"); pg.wait_for_timeout(900); print(lab, "->", say(pg)[:90])
s = pg.locator("[data-inspector] .wb-field:has(label:text-is('area')) select").first; s.select_option("right"); pg.wait_for_timeout(900); print("area ->", say(pg)[:90])
s = pg.locator("[data-inspector] .wb-field:has(label:text-is('key')) select").first; s.select_option("F5"); pg.wait_for_timeout(900); print("key ->", say(pg)[:90])
inp = pg.locator("[data-inspector] .wb-field:has(label:text-is('span')) input").first; inp.fill("99"); inp.press("Tab"); pg.wait_for_timeout(900); print("span 99 ->", say(pg)[:160], "| input:", inp.input_value())
inp.fill("0"); inp.press("Tab"); pg.wait_for_timeout(900); print("span 0 ->", say(pg)[:160])
inp.fill("-3"); inp.press("Tab"); pg.wait_for_timeout(900); print("span -3 ->", say(pg)[:160])
inp.fill("3.5"); inp.press("Tab"); pg.wait_for_timeout(900); print("span 3.5 ->", say(pg)[:160])
# list editor: kv fields
pg.locator("[data-inspector] button:text-is('Add field')").first.click(); pg.wait_for_timeout(300)
print("list item controls:", pg.evaluate("()=>[...document.querySelectorAll('[data-inspector] .wb-item label')].map(l=>l.innerText.trim())"))
shot(pg,"s11-list")
# title / strip / nothing selected
pg.click("[data-preview] .vtitle"); pg.wait_for_timeout(600); print("title inspector:", pg.inner_text("[data-inspector]")[:150].replace("\n"," | "))
pg.click("[data-preview] dl.strip"); pg.wait_for_timeout(600); print("strip inspector:", pg.inner_text("[data-inspector]")[:200].replace("\n"," | "))
b.close()
for e in ERRS:
    if e["type"] in("pageerror",): print("ERR", e)
