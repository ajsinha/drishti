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
import zipfile, re, subprocess, shutil
W = "/tmp/claude-1000/-home-ashutosh-IdeaProjects-drishti/efa68c11-fb98-4f9a-8faa-96a952090c0e/scratchpad/uxw/"
p = sync_playwright().start(); b = p.chromium.launch()
# viewer user
ca, pa = new_ctx(b, "admin"); 
r = pa.request.post(BASE + "/admin/api/users", data={"username": "qaviewer", "password": "QaViewer12345", "roles": ["viewer"], "displayName": "qaviewer"}); print("viewer user", r.status)
USERS["viewer"] = ("qaviewer", "QaViewer12345")
cv, pv = new_ctx(b, "viewer"); attach(pv, "viewer")
make_design(pv, "QA viewer"); 
print("VIEWER save btn:", pv.inner_text("[data-save]").strip(), "| aria-disabled:", pv.get_attribute("[data-save]","aria-disabled"), "| title:", (pv.get_attribute("[data-save]","title") or "")[:100], "| note box:", pv.locator("[data-note]").count())
pv.keyboard.press("Control+s"); pv.wait_for_timeout(800); print("  Ctrl+S ->", say(pv)[:140])
pv.click("[data-save]", force=True); pv.wait_for_timeout(600); print("  click ->", say(pv)[:140])
pv.click("[data-ship-menu]"); pv.wait_for_timeout(400); print("  ship menu (viewer):", pv.locator("[role=dialog] [role=option]").all_inner_texts()); pv.keyboard.press("Escape")
ca.close(); 
# author: ship
cau, pa = new_ctx(b, "author"); attach(pa, "author")
make_design(pa, "QA ship"); did = pa.url.rsplit("/",1)[-1]
pa.click("[data-ship-menu]"); pa.wait_for_timeout(400); opts = pa.locator("[role=dialog] [role=option]").all_inner_texts(); print("AUTHOR ship menu:", opts); shot(pa,"s21-ship-menu")
# export
with pa.expect_download() as dl:
    pa.get_by_role("option", name=re.compile("Export")).click()
d = dl.value; zp = W+"frag.zip"; d.save_as(zp); print("export:", d.suggested_filename, os.path.getsize(zp), "bytes")
z = zipfile.ZipFile(zp); names = z.namelist(); print("  zip entries:", len(names), names[:12])
shutil.rmtree(W+"frag", ignore_errors=True); z.extractall(W+"frag")
print("  README head:", z.read([n for n in names if n.endswith("README.md")][0]).decode()[:600].replace("\n"," | "))
print("  pack.yaml:", z.read([n for n in names if n.endswith("pack.yaml")][0]).decode()[:400].replace("\n"," | "))
# share
pa.click("[data-ship-menu]"); pa.wait_for_timeout(300); pa.get_by_role("option", name=re.compile("read-only link")).click(); pa.wait_for_timeout(1500)
url = pa.input_value("[data-share-url]"); print("share url:", url, "| box visible:", pa.is_visible("[data-share-box]")); print("  say:", say(pa)[:120]); shot(pa,"s21-share-box")
cr, pr = new_ctx(b, "appr"); attach(pr, "recipient")
pr.goto(url if url.startswith("http") else BASE+url); pr.wait_for_timeout(2000); print("SHARED page:", pr.inner_text("main, .adm, body")[:500].replace("\n"," | ")); shot(pr,"s21-shared", full=True)
pr.set_input_files("input[type=file]", W+"shipments/shp-1.json"); pr.wait_for_timeout(1500); print("  preview own file ->", pr.evaluate("()=>[...document.querySelectorAll('[role=status],.bs-status')].map(e=>e.innerText).join(' || ')")[:200], "| panels:", pr.locator(".pnl[data-panel]").count())
# copy button / revoke
pa.click("[data-share-revoke]"); pa.wait_for_timeout(1200); print("revoke say:", say(pa)[:100])
pr.goto(url if url.startswith("http") else BASE+url); pr.wait_for_timeout(1500); print("after revoke recipient:", pr.inner_text("main, .adm, body")[:200].replace("\n"," | "))
# import round trip
pa.goto(BASE+"/build/new#import"); pa.wait_for_timeout(500); pa.set_input_files("input[data-import-zip]", zp); pa.wait_for_timeout(3000); print("IMPORT:", pa.evaluate("()=>document.querySelector('[data-import-status]').innerText"), "|", pa.evaluate("()=>[...document.querySelectorAll('[data-import-list] li')].map(l=>l.innerText.replace(/\\n/g,' ')).join(' ; ')")[:300]); shot(pa,"s21-import")
b.close(); p.stop()
