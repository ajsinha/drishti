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
p, b, ctx, pg = start(); attach(pg, "s10")
make_design(pg, "QA yaml")
tab(pg,"yaml")
HEAD = "rachana: 1\nsutra: shipment-auto\nversion: 1\nmatch: {kind: shipment}\n"
def put(txt):
    pg.click(".CodeMirror"); pg.keyboard.press("Control+a"); pg.keyboard.insert_text(txt); pg.wait_for_timeout(3000)
def report(label):
    tab(pg,"problems"); pr = pg.inner_text("[data-problems]").replace("\n"," | ")[:350]; tab(pg,"yaml")
    print(f"## {label}\n   say: {say(pg)[:260]}\n   problems: {pr}\n   rev: {rev(pg)} preview ok: {pg.locator('[data-preview] .pnl').count()} panels; result {result(pg)}")
put(HEAD + "panels:\n  - {id: a, kind: markdown, text: hi}\n  - {id: a, kind: markdown, text: hi}\n"); report("dup id")
put(HEAD + "panels:\n  - {id: a, kind: table, rows: '$.legs[?'}\n"); report("bad expr")
put(HEAD + "panels:\n  - {id: a, kind: table}\n"); report("table w/o rows")
put(HEAD + "panels:\n  - {id: a, kind: gauge, value: '$.nothere', bogus: 1}\n"); report("unknown option")
put("title: [unclosed"); report("syntax")
put(HEAD + "panels:\n  - {id: a, kind: markdown, text: round trip}\n"); report("good: round trip")
tab(pg,"design"); print("canvas panels:", [(x["id"],x["kind"]) for x in panels(pg)])
# split view + round trip canvas->yaml
pg.click("[data-split]"); pg.wait_for_timeout(500); shot(pg,"s10-split")
pg.set_viewport_size({"width":1440,"height":900})
b.close()
for e in ERRS:
    if e["type"] in("pageerror",): print("ERR", e)
