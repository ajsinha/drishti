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
    b = p.chromium.launch(); pg = b.new_page(viewport={'width': 1600, 'height': 900}); attach(pg, 'calc0')
    pg.goto(BASE + '/v/trade/MX-20000002'); pg.wait_for_timeout(2000)
    pg.keyboard.press('Alt+c'); pg.wait_for_timeout(2500)
    shot(pg, 'calc_no_runtime')
    d = pg.locator('[data-calc], .calc, #calc').first
    print(pg.evaluate("()=>{const e=document.querySelector('[data-calc]')||document.querySelector('.calc-drawer');return e?e.innerText.slice(0,400):'no drawer'}"))
    print(ERRS)
    b.close()
