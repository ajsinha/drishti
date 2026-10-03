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
J = "/tmp/claude-1000/-home-ashutosh-IdeaProjects-drishti/efa68c11-fb98-4f9a-8faa-96a952090c0e/scratchpad/uxw/junk/"
p, b, ctx, pg = start(); attach(pg, "s9")
def banner(): return pg.evaluate("()=>[...document.querySelectorAll('[role=alert],[role=status],.bs-status,.asof-banner')].map(e=>e.innerText.trim()).filter(Boolean).join(' || ')")[:400]
def R(t): print(f"## {t}\n   {banner()}")
pg.goto(BASE+"/build/d/doesnotexist"); pg.wait_for_timeout(800); R("design not found")
pg.goto(BASE+"/build/d/doesnotexist?share=abc"); pg.wait_for_timeout(800); R("bad share")
pg.goto(BASE+"/build/reviews/P-999999"); pg.wait_for_timeout(800); R("review not found")
pg.goto(BASE+"/build/new"); pg.wait_for_timeout(500)
pg.click("[data-create]"); pg.wait_for_timeout(800); R("create with nothing")
pg.set_input_files("input[data-files]", [J+"bad.json"]); pg.wait_for_timeout(1000); R("bad json file"); 
pg.set_input_files("input[data-files]", [J+"bad.json", J+"ok.json"]); pg.wait_for_timeout(1000); R("bad+ok")
pg.fill("[data-kind]", "Bad Kind!"); pg.click("[data-create]"); pg.wait_for_timeout(1500); R("bad kind name"); print("   url", pg.url)
pg.set_input_files("input[data-files]", [J+"arr.json"]); pg.wait_for_timeout(1000); R("array doc")
pg.set_input_files("input[data-files]", [J+"note.txt"]); pg.wait_for_timeout(1000); R("txt file")
pg.set_input_files("input[data-schema-file]", J+"bad.json"); pg.wait_for_timeout(1000); R("bad schema")
pg.set_input_files("input[data-import-zip]", J+"empty.zip"); pg.wait_for_timeout(1500); R("bad zip")
pg.set_input_files("input[data-import-zip]", J+"nosutra.zip"); pg.wait_for_timeout(1500); R("zip without sutra")
# existing-start without sutra
pg.reload(); pg.set_input_files("input[data-files]", [J+"ok.json"]); pg.check("input[name=start][value=existing]"); pg.fill("[data-kind]","thing"); pg.click("[data-create]"); pg.wait_for_timeout(1500); R("existing with none chosen"); print("   url", pg.url)
# workbench: add bad file, preview bad file, yaml errors
make_design(pg, "QA err")
pg.set_input_files("input[data-add-files]", [J+"bad.json"]); pg.wait_for_timeout(1500); R("workbench add bad file")
pg.set_input_files("input[data-preview-file]", [J+"bad.json"]); pg.wait_for_timeout(1500); R("preview bad file")
pg.set_input_files("input[data-preview-file]", [J+"note.txt"]); pg.wait_for_timeout(1500); R("preview txt")
tab(pg,"yaml")
for label, txt in (("yaml syntax", "title: [unclosed"), ("unknown top key", "rachana: 1\nsutra: x\nversion: 1\nmatch: {kind: shipment}\nbogus: 1\npanels: []\n"), ("unknown kind", "rachana: 1\nsutra: x\nversion: 1\nmatch: {kind: shipment}\npanels:\n  - {id: a, kind: chart}\n"), ("dup id", "rachana: 1\nsutra: x\nversion: 1\nmatch: {kind: shipment}\npanels:\n  - {id: a, kind: markdown, text: hi}\n  - {id: a, kind: markdown, text: hi}\n"), ("bad expr", "rachana: 1\nsutra: x\nversion: 1\nmatch: {kind: shipment}\npanels:\n  - {id: a, kind: table, rows: '$.legs[?'}\n")):
    pg.evaluate("(t)=>{const cm=document.querySelector('.CodeMirror');if(cm&&cm.CodeMirror){cm.CodeMirror.setValue(t)}else{const a=document.querySelector('[data-yaml-src]');a.value=t;a.dispatchEvent(new Event('input',{bubbles:true}))}}", txt)
    pg.wait_for_timeout(2500); print(f"## {label}\n   say: {say(pg)[:300]}"); tab(pg,"problems"); print("   problems:", pg.inner_text("[data-problems]").replace("\n"," | ")[:420]); tab(pg,"yaml")
b.close()
for e in ERRS: 
    if e["type"].startswith("pageerror"): print("ERR", e)
