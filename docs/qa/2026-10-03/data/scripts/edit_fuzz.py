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

"""Random operation sequences through POST /builder/edit, one operation at a time, against a python model of the parsed YAML.
Checks: result model equality, comments never lost (except those on a removed panel), invalid ops located & text unchanged."""
import json, random, sys, re, yaml, copy
from qa import *
R = random.Random(int(sys.argv[1]) if len(sys.argv) > 1 else 1)
N = int(sys.argv[2]) if len(sys.argv) > 2 else 40
base = open("base.sutra.yaml").read()
REQ = {"kv": {}, "links": {}, "provenance": {}, "status": {}, "markdown": {"text": "hello # not a comment"}, "gauge": {"value": "$.mtm", "max": "100"},
       "table": {"rows": "$.legs"}, "hbar": {"rows": "$.legs", "label": "name", "value": "amount"}, "line": {"rows": "$.history", "x": "date", "y": "v"}}
def comments(y): return [l.strip() for l in y.splitlines() if l.strip().startswith("#")]
def panels(m): return m.get("panels") or []
def find(m, pid): return next((p for p in panels(m) if p.get("id") == pid), None)
tot = {"seq": 0, "ops": 0, "applied": 0, "problems": 0, "model_mismatch": 0, "comment_loss": 0, "unexpected": 0}
VALUES = [5, "x y", "a: b", "# hash", "- dash", "yes", "no", "null", "~", "1e3", "0x1f", "'q'", '"dq"', "$.mtm + 1", "line1\nline2", "", " lead", "trail ", "é名", True, False, None, 3.5, [1, 2], {"a": 1}, "@.name", "{x}", "[y]", "%p", "!tag", "&anc", "*ali", "a #b", "key: v # c"]
for s in range(N):
    y = base; m = yaml.safe_load(y); tot["seq"] += 1
    keep = [c for c in comments(base) if "keep me" in c]
    for step in range(R.randint(2, 10)):
        ids = [p["id"] for p in panels(m)]
        pick = R.random(); op = None; model = None
        if pick < .3 and ids:
            pid = R.choice(ids); opt = R.choice(["title", "span", "height", "area", "note", "rows", "fmt"]); v = R.choice(VALUES)
            op = {"op": "setOption", "panel": pid, "option": opt, "value": v}
        elif pick < .45:
            k = R.choice(list(REQ)); pid = "p%d" % R.randint(0, 99999); op = {"op": "addPanel", "kind": k, "id": pid, "options": dict(REQ[k])}
            if ids and R.random() < .5: op["at"] = {R.choice(["before", "after"]): R.choice(ids)}
        elif pick < .6 and ids:
            op = {"op": "remove", "panel": R.choice(ids)}
        elif pick < .75 and ids:
            op = {"op": "move", "panel": R.choice(ids)}
            c = R.random()
            if c < .4: t = R.choice(ids); op[R.choice(["before", "after"])] = t
            if c > .3: op["span"] = R.randint(1, 12)
            if R.random() < .5: op["height"] = R.randint(1, 20)
            if R.random() < .3: op["area"] = R.choice(["main", "right"])
        elif pick < .82:
            op = {"op": "setTitle", "title": {"pill": R.choice(VALUES[:12]) if True else "", "id": "$.id"}}
        elif pick < .9:
            op = {"op": "setStrip", "items": [{"label": "L%d" % i, "bind": "$.mtm", "fmt": "signed0"} for i in range(R.randint(0, 3))]}
        elif pick < .95:
            op = {"op": "setKeys", "keys": {"F7": "link($.book, 'book')"}}
        else:
            op = {"op": "setMatch", "match": {"kind": "trade", "priority": R.randint(1, 5)}}
        st, o = j("POST", "/api/v1/builder/edit", body={"yaml": y, "ops": [op]})
        tot["ops"] += 1
        if st != 200: print("HTTP", st, json.dumps(op)[:150], str(o)[:200]); tot["unexpected"] += 1; continue
        if o["applied"] == 1:
            tot["applied"] += 1
            try: nm = yaml.safe_load(o["yaml"])
            except Exception as e: print("RESULT NOT YAML", json.dumps(op), repr(e)[:150]); tot["model_mismatch"] += 1; print(o["yaml"][-400:]); break
            # model
            exp = copy.deepcopy(m); kind = op["op"]
            if kind == "setOption":
                p = find(exp, op["panel"]); 
                if op["value"] is None: p.pop(op["option"], None)
                else: p[op["option"]] = op["value"]
            elif kind == "addPanel":
                p = {"id": op["id"], "kind": op["kind"], **op["options"]}; pl = exp.setdefault("panels", [])
                at = op.get("at", {})
                tg = find(exp, at.get("before") or at.get("after")) if at else None
                if tg and tg.get("area") not in (None, "main"): p["area"] = tg["area"]
                if "before" in at: pl.insert([x["id"] for x in pl].index(at["before"]), p)
                elif "after" in at: pl.insert([x["id"] for x in pl].index(at["after"]) + 1, p)
                else: pl.append(p)
            elif kind == "remove": exp["panels"] = [p for p in panels(exp) if p["id"] != op["panel"]]
            elif kind == "move":
                pl = exp["panels"]; p = next(x for x in pl if x["id"] == op["panel"])
                if "before" in op or "after" in op:
                    pl.remove(p); t = op.get("before") or op.get("after"); idx = [x["id"] for x in pl].index(t)
                    pl.insert(idx if "before" in op else idx + 1, p)
                for kk in ("span", "height", "area"):
                    if kk in op: p[kk] = op[kk]
            elif kind == "setTitle": exp["title"] = op["title"]
            elif kind == "setStrip": exp["strip"] = op["items"]
            elif kind == "setKeys": exp["keys"] = op["keys"]
            elif kind == "setMatch": exp["match"] = op["match"]
            # normalise: model compare ignoring panel order if move without before/after
            def canon(mm):
                mm = copy.deepcopy(mm)
                for p in mm.get("panels") or []:
                    if p.get("area") == "main": p.pop("area")
                    if p.get("span") == 12: p.pop("span")
                    if p.get("area") is None: p["area"] = "main"
                mm["panels"] = sorted(mm.get("panels") or [], key=lambda p: (p["area"], 0)) if False else mm.get("panels") or []
                cols = {}
                for p in mm["panels"]: cols.setdefault(json.dumps(p["area"]), []).append(p)
                mm["panels"] = cols
                return mm
            def norm(x): return json.dumps(x, sort_keys=True, default=str)
            a, b = canon(exp), canon(nm)
            if kind == "move" and "before" not in op and "after" not in op:
                for mm in (a, b):
                    for ar in mm["panels"]: mm["panels"][ar] = sorted(mm["panels"][ar], key=lambda p: p["id"])
            if kind == "setStrip" and not op["items"]: a.pop("strip", None); b.pop("strip", None); a.pop("strip", None)
            if norm(a) != norm(b):
                tot["model_mismatch"] += 1; print("MISMATCH", json.dumps(op)[:200]); 
                for kk in set(a) | set(b):
                    if norm(a.get(kk)) != norm(b.get(kk)): print("   ", kk, "expected", norm(a.get(kk))[:300], "\n       got   ", norm(b.get(kk))[:300])
                json.dump({"yaml": y, "op": op}, open("fail_edit_%s_%d_%d.json" % (sys.argv[1] if len(sys.argv)>1 else 1, s, step), "w"))
                break
            # comments
            lost = [c for c in keep if c not in comments(o["yaml"])]
            if kind == "remove": keep = [c for c in keep if c in comments(o["yaml"])]; lost = []
            if kind == "setTitle" or kind == "setStrip":  lost = [c for c in lost]
            if lost:
                tot["comment_loss"] += 1; print("COMMENT LOST", json.dumps(op)[:160], lost); json.dump({"yaml": y, "op": op}, open("fail_cmt_%s_%d_%d.json" % (sys.argv[1] if len(sys.argv)>1 else 1, s, step), "w")); keep = [c for c in keep if c not in lost]
            y, m = o["yaml"], nm
        else:
            tot["problems"] += 1
            if o["yaml"] != y: print("TEXT CHANGED ON PROBLEM", json.dumps(op)[:150]); tot["unexpected"] += 1
            pr = o["problems"][0]
            if not (pr.get("code") and pr.get("message")): print("UNLOCATED PROBLEM", pr); tot["unexpected"] += 1
print(tot)
