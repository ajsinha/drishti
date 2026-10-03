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
p, b, ctx, pg = start(); attach(pg, "s8")
pg.goto(BASE+"/build/new"); opts = pg.evaluate("()=>[...document.querySelectorAll('[data-registry] option')].map(o=>o.value).filter(Boolean)"); print("registry sutras", len(opts), opts[:3])
ids = pg.evaluate("()=>1")
did = make_design(pg, "QA redir").rsplit("/",1)[-1]
samp = pg.evaluate("()=>document.querySelector('[data-sample]').dataset.sample")
sutra = [o for o in opts if o.startswith("shipment-auto")] or opts[:1]
urls = ["/studio", "/studio/", "/studio?example=tree-table", "/studio?example=nope", f"/studio?sutra={sutra[0]}", "/studio?sutra=nope@9", "/studio?kind=trade&id=T-1", "/studio?kind=shipment&id=SHP-1",
        f"/studio?design={did}&sample={samp}", f"/studio?design={did}&build=1", "/studio?example=tree-table&build=1", "/studio?design=zzzz", "/studio/reviews", "/studio/reviews/P-000001", "/studio/reviews/P-999999", "/studio?build=1", "/studio?example=all-panels-showcase&sample=x"]
for u in urls:
    pg.goto(BASE+u); pg.wait_for_timeout(1800)
    tabsel = pg.evaluate("()=>{const t=document.querySelector('[role=tab][aria-selected=true]');return t?t.innerText:''}")
    print(f"{u:60s} -> {pg.url.replace(BASE,''):32s} tab={tabsel!r} h1={pg.evaluate('()=>(document.querySelector(\"h1\")||{}).innerText')!r} say={pg.evaluate('()=>(document.querySelector(\"[data-say]\")||{}).innerText||\"\"')[:60]!r}")
# JSON endpoints unchanged
for path, body in (("/studio/preview", {"yaml":"x"}), ("/studio/summary", {"yaml":"x"}), ("/studio/schema", None), ("/studio/save", {"yaml":"x"}), ("/studio/validate", {"yaml":"x"})):
    r = pg.request.post(BASE+path, data=body) if body else pg.request.get(BASE+path)
    print(path, r.status, r.text()[:100].replace("\n"," "))
b.close()
for e in ERRS: print("ERR", e)
