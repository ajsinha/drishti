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

import sys, os; sys.path.insert(0, os.path.dirname(__file__))
from pw import *
SHIP = "/tmp/claude-1000/-home-ashutosh-IdeaProjects-drishti/efa68c11-fb98-4f9a-8faa-96a952090c0e/scratchpad/uxw/shipments"

def make_design(pg, name="QA tmp", kind="shipment", start="auto"):
    pg.goto(BASE + "/build/new"); pg.wait_for_timeout(600)
    pg.set_input_files("input[data-folder]", SHIP); pg.wait_for_timeout(1200)
    pg.fill("[data-name]", name); pg.fill("[data-kind]", kind)
    pg.check(f"input[name=start][value={start}]")
    pg.click("[data-create]"); pg.wait_for_url("**/build/d/**", timeout=60000)
    pg.wait_for_selector("[data-preview] .pnl, [data-preview] .vtitle", timeout=30000); pg.wait_for_timeout(1200)
    return pg.url

def example(pg, name):
    pg.goto(BASE + "/build/new#examples"); pg.wait_for_timeout(600)
    pg.click(f"[data-example={name}]"); pg.wait_for_url("**/build/d/**", timeout=60000)
    pg.wait_for_timeout(2500)

def panels(pg):
    return pg.evaluate("""()=>[...document.querySelectorAll('[data-preview] .pnl[data-panel]')].map(p=>{const r=p.getBoundingClientRect();
      return {id:p.dataset.panel,kind:p.dataset.kind,span:p.dataset.span||'',h:p.dataset.height||'',x:r.x,y:r.y,w:r.width,hh:r.height,col:p.parentNode.className}})""")

def say(pg): return pg.inner_text("[data-say]")
def live(pg): return pg.inner_text("[data-live]")
def rev(pg): return pg.inner_text("footer [data-rev]").strip()
def result(pg): return pg.inner_text("[data-result]").strip()
def tab(pg, name): pg.click(f"[role=tab][data-tab={name}]"); pg.wait_for_timeout(300)
def drag(pg, a, b, steps=12, hold=300):
    pg.mouse.move(*a); pg.mouse.down(); pg.mouse.move(a[0]+6, a[1]+6, steps=2)
    pg.mouse.move(*b, steps=steps); pg.wait_for_timeout(hold); pg.mouse.up(); pg.wait_for_timeout(900)
def center(box): return (box["x"] + box["width"]/2, box["y"] + box["height"]/2)
def start(headless=True, w=1440, h=900, who="author"):
    p = sync_playwright().start(); b = p.chromium.launch(headless=headless); ctx, pg = new_ctx(b, who, w, h); return p, b, ctx, pg
