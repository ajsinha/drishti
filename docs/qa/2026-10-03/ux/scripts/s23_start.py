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
W = "/tmp/claude-1000/-home-ashutosh-IdeaProjects-drishti/efa68c11-fb98-4f9a-8faa-96a952090c0e/scratchpad/uxw/"
schema = {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","required":["id"],"properties":{"id":{"type":"string"},"amount":{"type":"number","minimum":1,"maximum":900},"ccy":{"enum":["USD","EUR"]},"when":{"type":"string","format":"date"},"items":{"type":"array","items":{"type":"object","properties":{"sku":{"type":"string"},"qty":{"type":"integer"}}}}}}
json.dump(schema, open(W+"junk/schema.json","w"))
p, b, ctx, pg = start(); attach(pg, "s23")
def status(): return pg.evaluate("()=>[...document.querySelectorAll('[role=status],[role=alert],.bs-status')].map(e=>e.innerText.trim()).filter(Boolean).join(' || ')")[:300]
def go(label):
    pg.click("[data-create]"); 
    try: pg.wait_for_url("**/build/d/**", timeout=8000); pg.wait_for_timeout(2000); print(f"[{label}] OK ->", pg.inner_text(".wb-title"), "| samples:", pg.locator("[data-samples] li").count(), "| result:", result(pg), "| problems:", pg.inner_text("[role=tab][data-tab=problems]").replace("\n"," "), "| panels:", len(panels(pg)))
    except Exception: print(f"[{label}] stayed on New:", status())
# schema
pg.goto(BASE+"/build/new"); pg.set_input_files("input[data-schema-file]", W+"junk/schema.json"); pg.fill("[data-kind]","order"); pg.fill("[data-name]","QA schema"); go("schema")
# stored entity (nonexistent kind)
pg.goto(BASE+"/build/new"); pg.fill("[data-store-kind]","trade"); pg.fill("[data-store-ids]","NOPE-1"); pg.fill("[data-kind]","trade"); go("stored entity missing")
pg.goto(BASE+"/build/new"); pg.fill("[data-store-kind]","bogus kind"); go("stored bad kind")
# existing sutra from registry
pg.goto(BASE+"/build/new"); pg.select_option("[data-registry]", "var@1"); pg.check("input[name=start][value=existing]"); pg.fill("[data-kind]","var"); go("existing var@1 (no data)")
# empty start with file
pg.goto(BASE+"/build/new"); pg.set_input_files("input[data-files]", [W+"junk/ok.json"]); pg.check("input[name=start][value=empty]"); pg.fill("[data-kind]","thing"); go("empty start")
# yaml upload
pg.goto(BASE+"/build/new"); pg.set_input_files("input[data-yaml-file]", "/home/ashutosh/IdeaProjects/drishti/docs/guides/examples/pnl-explain.sutra.yaml"); pg.set_input_files("input[data-files]", ["/home/ashutosh/IdeaProjects/drishti/docs/guides/examples/pnl-explain.json"]); pg.check("input[name=start][value=existing]"); go("yaml upload + json")
# File menu on workbench
pg.click("[data-file-menu]"); pg.wait_for_timeout(400); print("FILE menu:", pg.locator("[role=dialog] [role=option]").all_inner_texts()); pg.keyboard.press("Escape")
# File>Open yaml with bad YAML
open(W+"junk/badsutra.yaml","w").write("rachana: 1\nsutra: x\n  bad: [")
pg.set_input_files("[data-file-input]", W+"junk/badsutra.yaml"); pg.wait_for_timeout(1500); print("open bad yaml ->", say(pg)[:200])
pg.set_input_files("[data-file-input]", [W+"junk/ok.json"]); pg.wait_for_timeout(1500); print("open json ->", say(pg)[:200])
pg.set_input_files("[data-file-input]", [W+"junk/note.txt"]); pg.wait_for_timeout(1500); print("open txt ->", say(pg)[:200])
b.close()
