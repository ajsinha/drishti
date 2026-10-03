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
p, b, ctx, pg = start(); attach(pg, "s20")
make_design(pg, "QA guide")
def P(t, ok, extra=""): print(("PASS " if ok else "FAIL ")+t, extra)
ids = lambda: [x["id"] for x in panels(pg)]
# --- section 3 shape tree: Enter shows reason; S suggests; B binds; buttons present
row = pg.locator("[data-tree] li").nth(3); row.focus(); pg.keyboard.press("Enter"); pg.wait_for_timeout(400)
det = pg.evaluate("()=>document.querySelector('[data-tree] li:nth-child(4)').innerText.slice(0,300)"); P("S3 Enter on a field shows reason/examples/files", "reason" in det.lower() or "example" in det.lower() or len(det)>60, det.replace("\n"," | ")[:150])
btns = pg.evaluate("()=>[...document.querySelectorAll('[data-tree] button')].map(b=>b.innerText).slice(0,4)"); P("S3 field has 'Suggest panels…' and 'Bind to selected panel' buttons", any("Suggest" in x for x in btns) and any("Bind" in x for x in btns), str(btns))
pg.keyboard.press("s"); pg.wait_for_timeout(600); P("S3 key S opens suggestions", pg.locator("[role=listbox]:visible").count()>0, say(pg)[:80]); pg.keyboard.press("Escape")
# filter
pg.fill("#wbFilter","cost"); pg.wait_for_timeout(300); P("S3 filter by path", "cost" in pg.inner_text("[data-counts]") or pg.locator("[data-tree] li:visible").count()<16, pg.inner_text("[data-counts]")); pg.fill("#wbFilter","")
pg.fill("#wbFilter","measure"); pg.wait_for_timeout(300); P("S3 filter by role", pg.locator("[data-tree] li:visible").count()<16, str(pg.locator("[data-tree] li:visible").count())); pg.fill("#wbFilter","")
# conflicts / rare listed above
P("S3 rare fields listed above the tree", "Rare fields" in pg.inner_text("[data-left]"))
# --- section 6: bind to markdown panel
pg.locator("[data-palette] li[data-kind=markdown] button").click(); pg.wait_for_timeout(800)
tr = pg.locator("[data-tree] li", has_text="carrier").first; tr.scroll_into_view_if_needed(); tb = tr.bounding_box(); md = [x for x in panels(pg) if x["kind"]=="markdown"][0]
drag(pg, (tb["x"]+30, tb["y"]+10), (md["x"]+md["w"]/2, md["y"]+md["hh"]/2)); P("S6 markdown refuses bind with DRS-5022", "DRS-5022" in say(pg) or "bind no data" in say(pg), say(pg)[:140])
# strip drop
tr = pg.locator("[data-tree] li", has_text="weightKg").first; tr.scroll_into_view_if_needed(); tb = tr.bounding_box(); sb = pg.locator("[data-preview] dl.strip").bounding_box()
drag(pg, (tb["x"]+30, tb["y"]+10), (sb["x"]+sb["width"]/2, sb["y"]+sb["height"]/2)); P("S6 drop on strip adds a figure", "figure" in say(pg).lower() or "strip" in say(pg).lower(), say(pg)[:120])
# --- section 7 autodesign
pg.click("[data-autodesign]"); pg.wait_for_timeout(2500); P("S7 auto-design asks before overwriting work", pg.locator("[role=dialog]:visible,[role=alertdialog]:visible").count()>0 or "sure" in say(pg).lower() or "undo" in say(pg).lower(), say(pg)[:150])
dr = pg.evaluate("()=>{const d=document.querySelector('[data-draft]');return d&&!d.hidden?d.innerText.slice(0,300):''}"); print("   draft panel:", dr.replace("\n"," | ")[:250])
# --- section 14 phone / theme buttons
pg.click("[data-width=phone]"); pg.wait_for_timeout(400); w = pg.evaluate("()=>document.querySelector('.wb-frame-wrap, [data-preview]').getBoundingClientRect().width"); P("S14 Phone narrows the canvas", w<460, str(round(w))); pg.click("[data-width=desktop]")
opts = pg.evaluate("()=>[...document.querySelectorAll('[data-theme-pick] option')].map(o=>o.value+':'+o.innerText)"); P("S14 Theme previews in each of the console themes", len(opts)>=8, str(opts))
pg.select_option("[data-theme-pick]", "crimson"); pg.wait_for_timeout(600); print("   frame theme attr:", pg.evaluate("()=>{const f=document.querySelector('[data-preview]');return [f.getAttribute('data-theme'), document.documentElement.getAttribute('data-theme'), getComputedStyle(f).backgroundColor]}"))
# --- section 9 summary
tab(pg,"summary"); P("S9 Summary tab shows panels", "Legs" in pg.inner_text("[data-summary]") or "legs" in pg.inner_text("[data-summary]"), pg.inner_text("[data-summary]")[:120].replace("\n"," | "))
# --- section 15 keys: Home/End, Esc, +/-, A
tab(pg,"design"); pg.locator("[data-panel=legs]").click(); pg.keyboard.press("End"); pg.wait_for_timeout(300); s1 = pg.evaluate("()=>document.activeElement.dataset.panel||document.activeElement.dataset.wbRegion"); pg.keyboard.press("Home"); s2 = pg.evaluate("()=>document.activeElement.dataset.panel||document.activeElement.dataset.wbRegion")
P("S15 Home/End first/last", s1 and s2 and s1!=s2, f"End->{s1} Home->{s2}")
pg.keyboard.press("ArrowLeft"); pg.keyboard.press("ArrowRight"); print("   left/right ->", pg.evaluate("()=>document.activeElement.dataset.panel||document.activeElement.dataset.wbRegion"))
pg.locator("[data-panel=legs]").focus(); n0 = [x for x in panels(pg) if x["id"]=="legs"][0]["span"]; pg.keyboard.press("-"); pg.wait_for_timeout(700); n1=[x for x in panels(pg) if x["id"]=="legs"][0]["span"]; pg.keyboard.press("+"); pg.wait_for_timeout(700); n2=[x for x in panels(pg) if x["id"]=="legs"][0]["span"]
P("S15 '-' and '+' resize", n1!=n0 and n2==n0, f"{n0}->{n1}->{n2}")
pg.keyboard.press("Escape"); pg.wait_for_timeout(300); P("S15 Esc deselects", pg.locator(".wb-sel").count()==0, "")
pg.locator("[data-panel=legs]").focus(); pg.keyboard.press("Backspace"); pg.wait_for_timeout(700); P("S15 Backspace removes the panel", "legs" not in ids(), say(pg)[:80]); pg.keyboard.press("Control+z"); pg.wait_for_timeout(700)
# Ctrl+Enter in YAML
tab(pg,"yaml"); pg.click(".CodeMirror"); pg.keyboard.press("Control+Enter"); pg.wait_for_timeout(1200); print("   Ctrl+Enter say:", say(pg)[:100])
# Ctrl+S never opens browser dialog; fine.  F1 from inside yaml editor
b.close()
