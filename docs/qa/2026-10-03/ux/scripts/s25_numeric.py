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

"""For every text option of every kind: type 5 in the inspector; report the options the server refuses (number typed as text)."""
from wb import *
import json
SC = json.load(open("/tmp/claude-1000/-home-ashutosh-IdeaProjects-drishti/efa68c11-fb98-4f9a-8faa-96a952090c0e/scratchpad/uxw/schema.json"))
kinds = {a["if"]["properties"]["kind"]["const"]: a["then"].get("x-rachana-options", []) for a in SC["$defs"]["panel"]["allOf"]}
p, b, ctx, pg = start(); attach(pg, "s25")
make_design(pg, "QA numeric")
bad = {}
for kind, opts in kinds.items():
    tab(pg,"inspector"); btn = pg.locator(f"[data-palette] li[data-kind={kind}] button"); btn.scroll_into_view_if_needed(); btn.click(); pg.wait_for_timeout(700)
    for o in opts:
        lab = pg.locator(f"[data-inspector] .wb-field:has(> label:text-is('{o}')) input[type=text], [data-inspector] .wb-field:has(> label:text-is('{o}')) input:not([type])")
        if lab.count() == 0: continue
        f = lab.first
        if not f.is_visible() or f.input_value(): continue
        f.fill("5"); f.press("Tab"); pg.wait_for_timeout(500)
        m = say(pg)
        if "DRS-5023" in m and "'5'" in m: bad.setdefault(kind, []).append(o); print(f"REFUSED {kind}.{o}: {m[:140]}")
        f.fill(""); f.press("Tab"); pg.wait_for_timeout(300)
print("SUMMARY", json.dumps(bad))
b.close()
