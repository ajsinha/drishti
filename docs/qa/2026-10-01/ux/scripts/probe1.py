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

import sys; sys.path.insert(0, __file__.rsplit("/", 1)[0])
from pw import *
with sync_playwright() as p:
    b = p.chromium.launch(); pg = b.new_page(viewport={'width': 1600, 'height': 900}); attach(pg, 'x')
    pg.goto(BASE + '/help/sutra-guide'); pg.wait_for_timeout(1500)
    print(pg.evaluate("""()=>[...document.images].filter(i=>i.getBoundingClientRect().right>1600).map(i=>[i.src.slice(-50), i.naturalWidth, Math.round(i.getBoundingClientRect().width), getComputedStyle(i).maxWidth, i.parentElement.tagName, i.parentElement.className])"""))
    pg.evaluate("""()=>{const i=[...document.images].find(i=>i.getBoundingClientRect().right>1600); i.scrollIntoView();}"""); pg.wait_for_timeout(300)
    shot(pg, 'help_sutra_guide_img_overflow')
    pg.goto(BASE + '/v/counterparty/CP-NORTHBRIDGE'); pg.wait_for_timeout(2000)
    print(pg.evaluate("""()=>[...document.querySelectorAll('a.lnk')].filter(a=>!a.innerText.trim()).slice(0,3).map(a=>[a.outerHTML.slice(0,250), a.closest('[data-kind]')?.getAttribute('data-kind')])"""))
    pg.goto(BASE + '/studio'); pg.wait_for_timeout(2500)
    print([e for e in ERRS if 'studio' in e['url']])
    b.close()
