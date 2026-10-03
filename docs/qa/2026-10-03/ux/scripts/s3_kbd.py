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
p, b, ctx, pg = start(); attach(pg, "s3")
make_design(pg, "QA kbd")
def pl(): return [(x["id"], x["kind"], x["span"], x["h"]) for x in panels(pg)]
def log(t): pg.wait_for_timeout(700); print(t, "|", rev(pg), "|", say(pg)[:150], "| live:", live(pg)[:80])
def focus(): return pg.evaluate("()=>{const e=document.activeElement;return e.tagName+'.'+(e.dataset&&Object.keys(e.dataset).join(',')||'')+'|'+(e.getAttribute('aria-label')||e.innerText||'').slice(0,50)}")

# keyboard-only: reach the canvas by Tab
pg.keyboard.press("Tab")  # skip link?
print("first tab ->", focus())
n=0
while n<200:
    pg.keyboard.press("Tab"); n+=1
    if pg.evaluate("()=>!!document.activeElement.closest('[data-preview]')"): break
print("tabs to reach canvas:", n, focus())
K=pg.keyboard
def k(keys, t=None):
    K.press(keys); log(t or keys)
k("ArrowDown"); k("ArrowDown"); print(pl())
k("Alt+ArrowUp", "move up"); print(pl())
k("Alt+ArrowRight", "move to side"); print(pl())
k("Shift+ArrowLeft", "narrower"); k("Shift+ArrowRight", "wider"); k("Shift+ArrowDown","taller"); k("Shift+ArrowUp","shorter"); k("a","natural"); print(pl())
k("Enter","enter->inspector"); print("focus:", focus())
K.press("Escape"); print("after Esc in inspector focus:", focus()); pg.locator("[data-panel=temps]").focus()
# N add
k("n","N add panel"); print("menu open:", pg.locator("[role=listbox]:visible").count(), "focus:", focus())
K.type("gau"); K.press("Enter"); log("added gauge"); print(pl(), "focus:", focus())
k("b","B bind field"); print("menu open:", pg.locator("[role=listbox]:visible").count(), focus())
K.type("temps"); K.press("Enter"); log("bound"); 
k("Delete","delete"); print(pl(), "focus", focus())
K.press("Control+z"); log("ctrl+z"); K.press("Control+Shift+z"); log("ctrl+shift+z"); K.press("Control+z"); log("ctrl+z")
# Ctrl+K palette
K.press("Control+k"); pg.wait_for_timeout(400); print("palette:", pg.locator("[role=dialog]:visible, [role=combobox]:visible").count(), focus())
shot(pg,"s3-palette")
K.type("go to panel: leg"); pg.wait_for_timeout(300); print("opts:", pg.locator("[role=option]:visible").all_inner_texts()[:5]); K.press("Enter"); log("go to"); print("focus", focus())
K.press("Control+k"); K.type("switch to yaml"); K.press("Enter"); pg.wait_for_timeout(500); print("tab yaml selected:", pg.get_attribute("#wbTabYaml","aria-selected"), focus())
# F1
with pg.expect_navigation(timeout=5000) as nav:
    K.press("F1")
print("F1 ->", pg.url)
b.close()
for e in ERRS: print("ERR", e)
