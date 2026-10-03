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
p, b, ctx, pg = start(); attach(pg, "s6")
make_design(pg, "QA options")
def add(kind):
    tab(pg,"inspector"); li = pg.locator(f"[data-palette] li[data-kind={kind}] button"); li.scroll_into_view_if_needed(); li.click(); pg.wait_for_timeout(900)
def field(name):  # the input inside the label/fieldset for an option
    return pg.locator(f"[data-inspector] .wb-field:has(label:text-is('{name}')) input, [data-inspector] .wb-field:has(label:text-is('{name}')) select").first
def yaml(): 
    tab(pg,"yaml"); t = pg.evaluate("()=>document.querySelector('[data-yaml-src]').value"); return t
def probs(): 
    tab(pg,"problems"); return pg.inner_text("[data-problems]").replace("\n"," | ")[:300]
def type_in(name, val, kind=None):
    f = field(name); print(f"  [{name}] count={pg.locator(f'[data-inspector] label:text-is(\"{name}\")').count()}")
    f.fill(val); f.press("Tab"); pg.wait_for_timeout(1200)
    print("  say:", say(pg)[:200], "| probs:", probs())
add("table"); type_in("limit","5"); type_in("expand","all"); type_in("search","true")
add("histogram"); type_in("bins","12")
add("pivot"); print("pivot labels:", pg.evaluate("()=>[...document.querySelectorAll('[data-inspector] label, [data-inspector] legend')].map(e=>e.innerText.trim()).join(',')"))
type_in("by","port, etaDays")
y = yaml(); import re; print("YAML:", re.findall(r".*(?:limit|expand|search|bins|by:).*", y))
b.close()
for e in ERRS[:10]: print("ERR", e)
