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
p, b, ctx, pg = start(); attach(pg, "s2")
make_design(pg, "QA mouse")
def pl(): return [(x["id"], x["kind"], x["span"], x["h"]) for x in panels(pg)]
def log(t): print(t, "|", rev(pg), "|", say(pg)[:160])
print("panels", pl())
# A palette drop at rim of 2nd panel
li = pg.locator("[data-palette] li[data-kind=gauge]"); li.scroll_into_view_if_needed(); bx = li.bounding_box()
ps = panels(pg); t = ps[1]
drag(pg, center(bx), (t["x"]+t["w"]/2, t["y"]+6)); log("A palette drop"); print(pl()); shot(pg, "s2-A")
# D mouse delete via inspector
pg.get_by_role("button", name="Remove panel").click(); pg.wait_for_timeout(700); log("D remove panel button"); print(pl())
# B field drops. tree rows
rows = pg.locator("[data-tree] li"); print("tree rows", rows.count())
def fld(path):
    r = pg.locator("[data-tree] li", has_text=path).first; r.scroll_into_view_if_needed(); return r
ps = panels(pg)
# B1: weightKg onto middle of 'details' kv (bind)
f = fld("weightKg").locator("> .bs-line, > div, > span").first if False else fld("weightKg"); fb = f.bounding_box()
d = ps[0]; drag(pg, (fb["x"]+30, fb["y"]+10), (d["x"]+d["w"]/2, d["y"]+d["hh"]/2)); log("B1 bind weightKg->details")
# B2: temps onto rim between panels -> suggestions
ps = panels(pg); t = ps[1]
f = fld("carrier"); fb = f.bounding_box()
drag(pg, (fb["x"]+30, fb["y"]+10), (t["x"]+t["w"]/2, t["y"]+5), hold=500); 
menu = pg.locator("[role=listbox]:visible"); print("B2 menu:", menu.count(), menu.first.inner_text()[:300].replace("\n"," | ") if menu.count() else "none"); shot(pg, "s2-B2")
log("B2")
pg.keyboard.press("Escape")
# C move: drag heading of panel 'route' to left column top
ps = panels(pg); print([ (x["id"], round(x["x"]), round(x["y"])) for x in ps])
r = [x for x in ps if x["id"]=="route"][0]; first = ps[0]
hd = pg.locator("[data-panel=route] .pnl-h").bounding_box()
drag(pg, (hd["x"]+40, hd["y"]+10), (first["x"]+first["w"]/2, first["y"]+4)); log("C move route above first"); print(pl())
# E resize right edge of 'details'
ps = panels(pg); d = [x for x in ps if x["id"]=="details"][0]
drag(pg, (d["x"]+d["w"]-4, d["y"]+d["hh"]/2), (d["x"]+d["w"]*0.5, d["y"]+d["hh"]/2)); log("E resize width"); print(pl())
ps = panels(pg); d = [x for x in ps if x["id"]=="details"][0]
drag(pg, (d["x"]+d["w"]/2, d["y"]+d["hh"]-4), (d["x"]+d["w"]/2, d["y"]+d["hh"]+80)); log("E resize height"); print(pl())
pg.mouse.dblclick(d["x"]+d["w"]/2, panels(pg)[[x["id"] for x in panels(pg)].index("details")]["y"]+panels(pg)[[x["id"] for x in panels(pg)].index("details")]["hh"]-4); pg.wait_for_timeout(800); log("E dblclick bottom"); print(pl())
# undo/redo via buttons
pg.click("[data-undo]"); pg.wait_for_timeout(700); log("undo"); pg.click("[data-redo]"); pg.wait_for_timeout(700); log("redo")
shot(pg, "s2-end")
b.close()
for e in ERRS: print("ERR", e)
