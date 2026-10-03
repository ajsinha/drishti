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
p, b, ctx, pg = start(); attach(pg, "s14")
pg.goto(BASE+"/build/new?example=tree-table"); pg.wait_for_timeout(2500); print("/build/new?example= ->", pg.url.replace(BASE,""), "| h1", pg.inner_text("h1")[:40])
for slug in ("screen-designer","examples","sutra-cli","screen-builder"):
    pg.goto(BASE+"/help/"+slug); pg.wait_for_timeout(1200)
    if pg.inner_text("body").find("not found")>=0 and slug!="x": print(slug, "status text:", pg.inner_text("h1")[:60])
    hrefs = pg.evaluate("()=>[...document.querySelectorAll('main a, article a, .help a, .doc a')].map(a=>a.getAttribute('href')).filter(Boolean)")
    imgs = pg.evaluate("()=>[...document.querySelectorAll('main img, article img, .doc img')].map(i=>[i.getAttribute('src'),i.naturalWidth,i.alt.length])")
    dead=[]; anchors=[]
    ids = set(pg.evaluate("()=>[...document.querySelectorAll('[id]')].map(e=>e.id)"))
    for h in sorted(set(hrefs)):
        if h.startswith("#"):
            if h[1:] not in ids: anchors.append(h)
        elif h.startswith("http"): pass
        else:
            u = h if h.startswith("/") else "/help/"+h
            r = pg.request.get(BASE+u.split("#")[0]); 
            if r.status>=400: dead.append((h,r.status))
    badimg=[i for i in imgs if i[1]==0]
    print(f"/help/{slug}: links={len(set(hrefs))} dead={dead} dead-anchors={anchors[:10]} imgs={len(imgs)} broken-imgs={badimg[:5]} noalt={sum(1 for i in imgs if i[2]==0)}")
b.close()
