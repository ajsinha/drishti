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

"""source: on scatter, status, markdown, tabs(+ nested body), table children, pivot by-list: sourced render == own render, with and without field masks."""
import yaml, json
from qa import *
PANELS = [
 {"id": "sc", "kind": "scatter", "rows": "$.books", "x": "mtm", "y": "trades", "label": "id"},
 {"id": "st", "kind": "status", "fields": [{"label": "Head", "bind": "$.head", "tone": "status"}, {"label": "VaR", "bind": "$.var99"}]},
 {"id": "tb", "kind": "tabs", "each": "$.books", "tabTitle": "@.id", "body": {"kind": "kv", "columns": [{"label": "Trades", "bind": "@.trades"}, {"label": "MTM", "bind": "@.mtm", "fmt": "signed0"}]}},
 {"id": "pv", "kind": "pivot", "rows": "$.positions", "by": ["book", "family"], "across": "currency", "value": "mtm", "fmt": "compact", "expand": "all"},
 {"id": "pv1", "kind": "pivot", "rows": "$.positions", "by": "book", "across": "currency", "value": "mtm", "agg": "max"},
 {"id": "tt", "kind": "table", "rows": "$.books", "columns": [{"label": "Book", "bind": "@.id"}, {"label": "MTM", "bind": "@.mtm", "total": True}]},
 {"id": "hb", "kind": "hbar", "rows": "$.books", "label": "id", "value": "mtm"},
 {"id": "ga", "kind": "gauge", "value": "$.var99", "max": "20000000"},
 {"id": "ka", "kind": "kv", "columns": [{"label": "Name", "bind": "$.name"}, {"label": "MTM", "bind": "$.mtm", "fmt": "amount0"}]},
]
def build(source, kind="desk"):
    ps = [dict(p) for p in PANELS]
    if source:
        for p in ps: p["source"] = "link($.ref, 'desk')"
        return yaml.safe_dump({"rachana": 1, "sutra": "srcmore", "version": 1, "match": {"kind": "wrapx"}, "title": {"pill": "W", "id": "$.ref"}, "panels": ps}, sort_keys=False)
    return yaml.safe_dump({"rachana": 1, "sutra": "srcmore0", "version": 1, "match": {"kind": "desk"}, "title": {"pill": "D", "id": "$.deskId"}, "panels": ps}, sort_keys=False)
def run(user, label):
    st, d1 = j("POST", "/api/v1/builder/designs", user=user, body={"name": "s0", "kind": "desk", "sutra": build(False)}); a = d1["id"]
    j("POST", f"/api/v1/builder/designs/{a}/samples", user=user, body={"refs": {"kind": "desk", "ids": ["DESK-RATES"]}})
    st, d2 = j("POST", "/api/v1/builder/designs", user=user, body={"name": "s1", "kind": "wrapx", "sutra": build(True)}); b = d2["id"]
    j("POST", f"/api/v1/builder/designs/{b}/samples", user=user, body={"samples": [{"name": "w", "document": {"ref": "DESK-RATES"}}]})
    st1, p1 = j("GET", f"/api/v1/builder/designs/{a}/preview", user=user); st2, p2 = j("GET", f"/api/v1/builder/designs/{b}/preview?sample=w", user=user)
    print(label, st1, st2)
    if st1 == 200 and st2 == 200:
        t = {p["id"]: p for p in p1["panels"]}
        for p in p2["panels"]:
            d1_, d2_ = dict(t[p["id"]].get("data") or {}), dict(p.get("data") or {}); d2_.pop("source", None)
            print("  ", p["id"], p["kind"], "equal" if d1_ == d2_ and bool(t[p["id"]].get("error")) == bool(p.get("error")) else "DIFF", ("error=" + str(p.get("error"))[:80]) if p.get("error") else "", "empty" if p.get("empty") else "")
    else: print(str(p1)[:200], str(p2)[:200])
    for x in (a, b): j("DELETE", "/api/v1/builder/designs/" + x, user=user)
run("qa-author", "unmasked")
