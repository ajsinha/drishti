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
p, b, ctx, pg = start(); attach(pg, "s17")
make_design(pg, "QA dialogs")
AE = "()=>{const e=document.activeElement;return (e.getAttribute('aria-label')||e.innerText||e.tagName).trim().slice(0,28)+' ['+e.tagName+(e.closest('[role=dialog]')?' in-dialog':' OUTSIDE')+']'}"
for sel,label,via in (("[data-file-menu]","File","click"),("[data-ship-menu]","Ship","key"),("[data-palette-open]","Commands","click"),("[data-add-menu]","Add panel","key"),("[data-bind-menu]","Bind field","key")):
    pg.focus(sel)
    if via=="key": pg.keyboard.press("Enter")
    else: pg.click(sel)
    pg.wait_for_timeout(500)
    st = pg.evaluate(AE); dlg = pg.evaluate("()=>{const d=document.querySelector('[role=dialog]');return d?{modal:d.getAttribute('aria-modal'),vis:!d.hidden,name:d.getAttribute('aria-label')}:null}")
    seq=[]
    for i in range(8):
        pg.keyboard.press("Tab"); seq.append(pg.evaluate(AE))
    still = pg.evaluate("()=>{const d=document.querySelector('[role=dialog]');return !!d&&!d.hidden&&d.offsetParent!==null}")
    print(f"[{label}] open focus={st} dialog={dlg}\n    tab seq={seq}\n    dialog still open after 8 tabs: {still}")
    pg.keyboard.press("Escape"); pg.wait_for_timeout(300); print("    after Esc focus:", pg.evaluate(AE))
    # Shift+Tab from first
    pg.reload(); pg.wait_for_timeout(2200)
# outside click closes?
pg.click("[data-palette-open]"); pg.wait_for_timeout(300); pg.mouse.click(700,500); pg.wait_for_timeout(300); print("palette open after outside click:", pg.evaluate("()=>{const d=document.querySelector('[role=dialog]');return !!d&&!d.hidden&&d.offsetParent!==null}"))
# background still reachable by Tab while palette open (no inert)?
print("inert/aria-hidden on main while open:", pg.evaluate("()=>[document.querySelector('main')&&document.querySelector('main').inert, document.querySelector('.wb')&&document.querySelector('.wb').getAttribute('aria-hidden')]"))
b.close()
