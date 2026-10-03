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

"""Pivot by-list / tree table over a masked field (trader): what does a role without raw see? Group labels must not leak values or split groups."""
import json
from qa import *
rows = [{"trader": t, "book": b, "ccy": c, "v": v, "kids": [{"trader": t, "v": v / 2}]} for t, b, c, v in [("alice", "b1", "USD", 10), ("alice", "b2", "EUR", 20), ("bob", "b1", "USD", 30), ("carol", "b2", "USD", 40), ("bob", "b1", "EUR", 50)]]
y = """rachana: 1
sutra: pmask
version: 1
match: { kind: pm }
title: { pill: "PM", id: "'x'" }
panels:
  - id: pv
    kind: pivot
    title: p
    rows: $.rows
    by: [trader, book]
    across: ccy
    value: v
    expand: all
  - id: pv2
    kind: pivot
    title: p2
    rows: $.rows
    by: book
    across: trader
    value: v
  - id: tt
    kind: table
    title: t
    rows: $.rows
    children: "@.kids"
    columns:
      - { label: Trader, bind: "@.trader" }
      - { label: V, bind: "@.v", total: true }
"""
out = {}
for u in ["qa-admin", "qa-viewer", "qa-author"]:
    st, d = j("POST", "/api/v1/builder/designs", user=u, body={"name": "pm", "kind": "pm", "sutra": y}); i = d["id"]
    j("POST", f"/api/v1/builder/designs/{i}/samples", user=u, body={"samples": [{"name": "s", "document": {"rows": rows}}]})
    st, p = j("GET", f"/api/v1/builder/designs/{i}/preview?sample=s", user=u)
    out[u] = p
    j("DELETE", f"/api/v1/builder/designs/{i}", user=u)
    if st != 200: print(u, st, str(p)[:200]); continue
    print("==", u)
    for pn in p["panels"]:
        dd = pn.get("data") or {}
        if pn["kind"] == "pivot": print(pn["id"], "cols", dd.get("columns"), "rows", [(r["label"], r.get("values"), r["total"]["text"] if r.get("total") else None) for r in dd.get("rows", [])][:8])
        else: print(pn["id"], "rows", [(r if not isinstance(r, dict) else r.get("cells", r)) for r in dd.get("rows", [])][:3])
raw = json.dumps(out["qa-viewer"])
print("leak check viewer: names present:", [n for n in ("alice", "bob", "carol") if n in raw])
