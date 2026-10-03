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
import json
SC = json.load(open("/tmp/claude-1000/-home-ashutosh-IdeaProjects-drishti/efa68c11-fb98-4f9a-8faa-96a952090c0e/scratchpad/uxw/schema.json"))
P = SC["$defs"]["panel"]; common = set(P["properties"]); 
opts = {}; req = {}
for a in P["allOf"]:
    k = a["if"]["properties"]["kind"]["const"]; opts[k] = a["then"].get("x-rachana-options", []); req[k] = a["then"].get("required", [])
JS = """()=>{const root=document.querySelector('[data-inspector]');const out=[];
 root.querySelectorAll('input,select,textarea,button').forEach(e=>{
  let name=e.getAttribute('aria-label')||''; if(!name&&e.id){const l=root.querySelector('label[for="'+e.id+'"]');if(l)name=l.innerText}
  if(!name&&e.closest('label'))name=e.closest('label').innerText; const lb=e.getAttribute('aria-labelledby'); if(!name&&lb){const l=document.getElementById(lb);if(l)name=l.innerText}
  if(!name&&e.tagName=='BUTTON')name=e.innerText;
  out.push({tag:e.tagName.toLowerCase()+(e.type?':'+e.type:''),name:name.trim().slice(0,40),value:(e.value||'').slice(0,40)});});
 return {controls:out,text:root.innerText.slice(0,2000)}}"""
p, b, ctx, pg = start(); attach(pg, "s5")
make_design(pg, "QA kinds")
res = {}
for kind in [k[0] for k in json.load(open("/tmp/claude-1000/-home-ashutosh-IdeaProjects-drishti/efa68c11-fb98-4f9a-8faa-96a952090c0e/scratchpad/uxw/kinds.json"))] if False else list(opts):
    tab(pg, "inspector")
    li = pg.locator(f"[data-palette] li[data-kind={kind}] button"); li.scroll_into_view_if_needed(); li.click(); pg.wait_for_timeout(900)
    r = pg.evaluate(JS); names = [c["name"] for c in r["controls"]]
    unl = [c for c in r["controls"] if not c["name"]]
    # which schema options are reachable: label text present in inspector text
    txt = r["text"]
    missing = [o for o in opts[kind] if o not in txt]
    prob = pg.inner_text("[role=tab][data-tab=problems]")
    res[kind] = {"unlabeled": unl, "missing": missing, "required": req[kind], "say": say(pg)[:120], "problems": prob, "check": result(pg), "n": len(r["controls"])}
    print(kind, "| controls", len(r["controls"]), "| unlabeled", len(unl), unl[:3], "| options not shown:", missing, "|", prob.replace("\n"," "), "|", result(pg))
    if kind in ("table","pivot","line","tabs","waterfall"): shot(pg, f"s5-{kind}")
json.dump(res, open(os.path.join(os.path.dirname(__file__), "s5_kinds.json"), "w"), indent=1)
b.close()
for e in ERRS[:20]: print("ERR", e)
