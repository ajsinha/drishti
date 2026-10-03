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
p, b, ctx, pg = start(); attach(pg, "s12")
make_design(pg, "QA versions")
# make a few changes by keyboard add
for k in ("gauge","markdown"):
    pg.locator(f"[data-palette] li[data-kind={k}] button").scroll_into_view_if_needed(); pg.locator(f"[data-palette] li[data-kind={k}] button").click(); pg.wait_for_timeout(900)
pg.locator("[data-panel=temps]").click(); pg.keyboard.press("Alt+ArrowUp"); pg.wait_for_timeout(800)
tab(pg,"versions"); t = pg.inner_text("[data-versions]"); print("VERSIONS:", t[:700].replace("\n"," | ")); shot(pg,"s12-versions")
ctl = pg.evaluate("()=>[...document.querySelectorAll('[data-versions] select,[data-versions] button,[data-versions] input')].map(e=>e.tagName+':'+(e.getAttribute('aria-label')||e.innerText||e.id).slice(0,40))"); print("controls:", ctl)
sel = pg.locator("[data-versions] select").first
opts = sel.locator("option").all_inner_texts(); print("options:", opts)
sel.select_option(index=1); pg.wait_for_timeout(1200); print("DIFF:", pg.inner_text("[data-versions]")[:500].replace("\n"," | ")); shot(pg,"s12-diff")
n0 = len(panels(pg)); 
pg.get_by_role("button", name="Restore").first.click(); pg.wait_for_timeout(1500); print("restore ->", say(pg)[:150], "| panels before/after", n0, len(panels(pg)), rev(pg))
pg.click("[data-undo]"); pg.wait_for_timeout(800); print("undo restore:", len(panels(pg)), say(pg)[:60])
# command palette items
pg.keyboard.press("Control+k"); pg.wait_for_timeout(500)
print("palette options:", pg.locator("[role=option]:visible").all_inner_texts()[:40])
pg.keyboard.press("Escape"); pg.wait_for_timeout(300); print("focus after esc:", pg.evaluate("()=>document.activeElement.tagName+'.'+(document.activeElement.className||'')"))
# sample switcher + tests matrix
tab(pg,"tests"); pg.wait_for_timeout(500); cells = pg.locator("[data-tests] td"); print("cells", cells.count()); 
cells.nth(8).click(); pg.wait_for_timeout(800); print("after cell click sample:", pg.inner_text("[data-sample-pos]"), "| selected:", pg.evaluate("()=>{const e=document.querySelector('.wb-sel');return e?e.dataset.panel:null}"))
pg.click("[data-next]"); pg.wait_for_timeout(700); print("next:", pg.inner_text("[data-sample-pos]")); pg.click("[data-prev]"); pg.click("[data-prev]"); pg.wait_for_timeout(700); print("prev x2:", pg.inner_text("[data-sample-pos]"))
# click a sample name
pg.locator("[data-samples] button").nth(2).click(); pg.wait_for_timeout(700); print("sample click:", pg.inner_text("[data-sample-pos]"))
# preview with a good file
good = "/home/ashutosh/IdeaProjects/drishti/docs/guides/examples/all-panels-showcase.json"
pg.set_input_files("input[data-preview-file]", good); pg.wait_for_timeout(1800); print("file chip hidden:", pg.get_attribute("[data-file-chip]","hidden"), "| result:", result(pg), "| say:", say(pg)[:100]); shot(pg,"s12-previewfile")
pg.click("[data-file-clear]"); pg.wait_for_timeout(800); print("back:", pg.inner_text("[data-sample-pos]"))
b.close()
for e in ERRS:
    if e["type"] in("pageerror",): print("ERR", e)
