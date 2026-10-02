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

"""Keyboard-only navigation and ARIA of the command line, top bar, help."""
import sys, time; sys.path.insert(0, __file__.rsplit("/", 1)[0])
from pw import *
FOC = """() => { const e = document.activeElement; if (!e) return 'none'; const s = getComputedStyle(e); const r = e.getBoundingClientRect();
  const ring = (s.outlineStyle !== 'none' && parseFloat(s.outlineWidth) > 0) || s.boxShadow !== 'none';
  return (e.tagName + (e.id ? '#' + e.id : '') + ' "' + (e.getAttribute('aria-label') || e.innerText || e.value || '').trim().slice(0, 30) + '"' + (ring ? '' : ' [NO FOCUS RING]') + (r.width === 0 ? ' [INVISIBLE]' : '')).replace(/\\n/g, ' '); }"""
with sync_playwright() as p:
    b = p.chromium.launch()
    pg = b.new_page(viewport={"width": 1600, "height": 1000}); attach(pg, "kbd")
    pg.goto(BASE + "/v/trade/MX-20000002"); pg.wait_for_timeout(2000)
    seq = []
    for i in range(45):
        pg.keyboard.press("Tab"); seq.append(pg.evaluate(FOC))
    print("TAB ORDER (first 45):"); [print("  %2d %s" % (i, s)) for i, s in enumerate(seq)]
    print("no ring count:", sum("NO FOCUS RING" in s for s in seq))
    # skip link?
    pg.goto(BASE + "/v/trade/MX-20000002"); pg.wait_for_timeout(1500); pg.keyboard.press("Tab")
    print("first tab stop:", pg.evaluate(FOC))
    # / focuses the command line; ARIA of combobox
    pg.keyboard.press("Escape"); pg.locator("body").click(position={"x": 5, "y": 500}); pg.keyboard.press("/")
    print("after '/':", pg.evaluate(FOC))
    print("cmd aria:", pg.evaluate("()=>{const i=document.getElementById('cmdInput'); return ['role','aria-autocomplete','aria-controls','aria-expanded','aria-activedescendant','aria-label'].map(a=>a+'='+i.getAttribute(a)).join(' ')}"))
    pg.keyboard.type("XYZ Q", delay=50); pg.wait_for_timeout(1200)
    print("XYZ Q dropdown:", pg.evaluate("()=>{const l=document.querySelector('[role=listbox]'); return l? (l.hidden?'hidden':l.innerText.slice(0,100)) : 'no listbox'}"))
    shot(pg, "kbd_xyzq")
    pg.fill("#cmdInput", ""); pg.keyboard.type("TRD MX-2000010", delay=40); pg.wait_for_timeout(1200)
    pg.keyboard.press("ArrowDown"); pg.keyboard.press("ArrowDown")
    print("after 2x down aria:", pg.evaluate("()=>{const i=document.getElementById('cmdInput'); const a=i.getAttribute('aria-activedescendant'); const o=a&&document.getElementById(a); return 'expanded='+i.getAttribute('aria-expanded')+' active='+a+' -> '+(o?o.getAttribute('role')+':'+o.innerText.slice(0,40).replace(/\\n/g,' '):'none')}"))
    pg.keyboard.press("Escape"); pg.wait_for_timeout(300)
    print("after Esc expanded:", pg.evaluate("()=>document.getElementById('cmdInput').getAttribute('aria-expanded')"))
    # F1 contextual help
    pg.goto(BASE + "/v/trade/MX-20000002"); pg.wait_for_timeout(1500); pg.keyboard.press("F1"); pg.wait_for_timeout(1500)
    print("F1 ->", pg.url)
    # F-keys on the view with focus in the command line
    pg.goto(BASE + "/v/trade/MX-20000002"); pg.wait_for_timeout(1500); pg.click("#cmdInput"); pg.keyboard.press("F9"); pg.wait_for_timeout(1000)
    print("F9 with focus in cmd: raw open=", pg.evaluate("()=>!!document.querySelector('.raw:not([hidden]):not(.calc-drawer)')"))
    pg.keyboard.press("Escape")
    # Alt+1..4 etc; theme menu via keyboard
    pg.goto(BASE + "/help/sutra-guide"); pg.wait_for_timeout(1500)
    un = pg.locator("a[data-unavailable]")
    print("help unavailable links on sutra-guide:", un.count())
    for slug in ["connectors", "plugins", "configuration", "operations", "packs", "using-the-terminal"]:
        pg.goto(BASE + "/help/" + slug); pg.wait_for_timeout(800)
        u = pg.locator("a[data-unavailable]"); n = u.count()
        ex = [u.nth(i).inner_text()[:30] for i in range(min(n, 3))]
        print("help/%s unavailable links: %d %s" % (slug, n, ex))
        if n:
            u.first.scroll_into_view_if_needed(); u.first.click(); pg.wait_for_timeout(500)
            print("   clicking first -> url", pg.url, "title attr:", u.first.get_attribute("title"))
    shot(pg, "help_unavailable_link")
    b.close()
for e in ERRS: print("ERR", e)
