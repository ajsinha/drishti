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

"""Layout mode (Alt+L): keyboard, drag, extremes, save/reset."""
import sys, time; sys.path.insert(0, __file__.rsplit("/", 1)[0])
from pw import *

ORDER = "() => [...document.querySelectorAll('.pnl[data-panel]')].map(p => p.getAttribute('data-panel') + (p.closest('.vright') ? 'R' : '') + (p.hasAttribute('hidden') || p.classList.contains('lay-hidden') || p.classList.contains('is-hidden') ? 'H' : '') + ':' + ([...p.classList].find(c => c.startsWith('c-span-')) || '')).join(' ')"
BAR = "() => { const b = document.querySelector('[data-layout-bar]'); return b ? (b.hidden ? 'bar hidden' : b.innerText.replace(/\\n/g,' ').slice(0,200)) : 'no bar'; }"
with sync_playwright() as p:
    b = p.chromium.launch()
    pg = b.new_page(viewport={"width": 1600, "height": 1000}); attach(pg, "layout")
    pg.goto(BASE + "/v/trade/MX-20000001"); pg.wait_for_timeout(2500)
    print("before:", pg.evaluate(ORDER))
    pg.keyboard.press("Alt+l"); pg.wait_for_timeout(800)
    print("bar:", pg.evaluate(BAR))
    shot(pg, "layout_on")
    # keyboard: focus first panel
    first = pg.locator(".pnl[data-panel]").first
    first.focus(); print("focused:", pg.evaluate("() => document.activeElement.getAttribute('data-panel')"), "tabindex:", first.get_attribute("tabindex"))
    for k in ["ArrowDown", "ArrowDown", "Shift+ArrowLeft"] + ["-"] * 15 + ["Shift+ArrowDown"] * 30 + ["ArrowRight"]:
        pg.keyboard.press(k)
    pg.wait_for_timeout(500)
    print("after keys:", pg.evaluate(ORDER)[:400])
    print("live region:", pg.evaluate("() => (document.querySelector('[data-layout-live]')||{}).innerText"))
    shot(pg, "layout_keys")
    # hide every panel
    for el in pg.locator(".pnl[data-panel]").all():
        el.focus(); pg.keyboard.press("h")
    pg.wait_for_timeout(400)
    shot(pg, "layout_all_hidden")
    print("all hidden:", pg.evaluate(ORDER)[:300])
    pg.click("[data-layout-save]"); pg.wait_for_timeout(1500)
    print("after save bar:", pg.evaluate(BAR))
    pg.reload(); pg.wait_for_timeout(2500)
    shot(pg, "layout_all_hidden_reloaded")
    print("reloaded:", pg.evaluate(ORDER)[:300], "| main text:", pg.inner_text("main")[:200].replace("\n", " | "))
    # reset
    pg.keyboard.press("Alt+l"); pg.wait_for_timeout(600)
    pg.once("dialog", lambda d: (print("dialog:", d.message), d.accept()))
    pg.click("[data-layout-reset]"); pg.wait_for_timeout(2000)
    pg.reload(); pg.wait_for_timeout(2500)
    print("after reset:", pg.evaluate(ORDER)[:300])
    # mouse drag: drag panel 2 heading to the right column
    pg.keyboard.press("Alt+l"); pg.wait_for_timeout(600)
    src = pg.locator(".pnl[data-panel] .pnl-h, .pnl[data-panel] header").nth(1)
    dst = pg.locator(".vright .pnl[data-panel]").first
    sb, db = src.bounding_box(), dst.bounding_box()
    pg.mouse.move(sb["x"] + 30, sb["y"] + 10); pg.mouse.down()
    for i in range(1, 21):
        pg.mouse.move(sb["x"] + 30 + (db["x"] + 40 - sb["x"] - 30) * i / 20, sb["y"] + 10 + (db["y"] + 10 - sb["y"] - 10) * i / 20); pg.wait_for_timeout(30)
    shot(pg, "layout_dragging")
    pg.mouse.up(); pg.wait_for_timeout(500)
    print("after drag:", pg.evaluate(ORDER)[:400])
    pg.keyboard.press("Escape"); pg.wait_for_timeout(500)
    print("after Esc:", pg.evaluate(ORDER)[:300], "| bar:", pg.evaluate(BAR))
    # Alt+L on a page without sutra (inferred) and on 404 page
    for u in ["/v/trade/NOPE-404", "/v/agreement/AGR-NORTHBRIDGE-ISDA", "/s?q=TRD"]:
        pg.goto(BASE + u); pg.wait_for_timeout(1500); pg.keyboard.press("Alt+l"); pg.wait_for_timeout(500)
        print(u, "->", pg.evaluate(BAR))
    b.close()
for e in ERRS: print("ERR", e)
