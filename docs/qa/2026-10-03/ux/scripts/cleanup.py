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

"""Delete every design of the QA author (keeps the quota free). usage: cleanup.py [keep-name-substring]"""
import sys; from wb import *
keep = sys.argv[1] if len(sys.argv) > 1 else "QA e2e"
p, b, ctx, pg = start()
ids = pg.evaluate("()=>[...document.querySelectorAll('a[href^=\"/build/d/\"]')].map(a=>a.getAttribute('href').split('/').pop().split('?')[0])") if pg.goto(BASE+"/build") else []
rows = pg.evaluate("()=>[...document.querySelectorAll('table tr')].map(r=>[r.innerText.slice(0,30), (r.querySelector('a[href^=\"/build/d/\"]')||{}).href||''])")
n=0
for t, h in rows:
    if h and keep not in t:
        r = pg.request.delete(h.replace("/build/d/", "/build/designs/")); n += r.status in (200,204)
print("deleted", n, "of", len(rows)); b.close()
