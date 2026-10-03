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

"""Random sample sets -> /builder/shape. Checks (independent): every sample validates; `required` equals the keys present in
every instance of that record node (brute force, walking schema+data together); optional keys carry presence ~ share."""
import json, random, sys, string
from qa import *
from jsonschema import Draft202012Validator
import os
BIG = os.environ.get("BIG")=="1"
R = random.Random(int(sys.argv[1]) if len(sys.argv) > 1 else 1)
N = int(sys.argv[2]) if len(sys.argv) > 2 else 200
KEYS = ["id","name","book","ccy","mtm","pnl","qty","price","date","ts","status","tags","rows","legs","children","meta","a.b","x y","名","","note","amount","limit","curve","email"]
def scalar():
    c = R.random()
    if c < .2: return R.randint(-10**6, 10**6)
    if c < .3: return round(R.uniform(-1e5, 1e5), R.randint(0, 4))
    if c < .4: return R.choice(["USD","EUR","GBP"])
    if c < .5: return "2026-%02d-%02d" % (R.randint(1,12), R.randint(1,28))
    if c < .55: return "2026-%02d-%02dT10:00:00Z" % (R.randint(1,12), R.randint(1,28))
    if c < .6: return None
    if c < .65: return R.choice([True, False])
    if c < .7: return "a%d@x.com" % R.randint(1,9)
    if c < .75: return "".join(R.choice(string.ascii_letters + " é名") for _ in range(R.randint(0, 40)))
    if c < .8 and BIG: return R.randint(10**15, 10**18)
    return "".join(R.choice(string.ascii_lowercase) for _ in range(R.randint(1, 6)))
def gen(depth, template_rng):
    c = R.random()
    if depth > 3 or c < .35: return scalar()
    if c < .65:
        keys = R.sample(KEYS, R.randint(0, 6))
        return {k: gen(depth + 1, template_rng) for k in keys}
    if c < .85:
        return [gen(depth + 1, template_rng) for _ in range(R.randint(0, 5))]
    return {("K%d" % R.randint(0, 99999)): gen(depth + 1, template_rng) for _ in range(R.randint(2, 8))}
def mutate(d, p):
    if isinstance(d, dict):
        out = {}
        for k, v in d.items():
            if R.random() < p: continue
            out[k] = mutate(v, p) if R.random() > p else scalar()
        if R.random() < p: out[R.choice(KEYS)] = scalar()
        return out
    if isinstance(d, list): return [mutate(x, p) for x in d if R.random() > p / 2]
    return d if R.random() > p else scalar()
def walk(schema, root, vals, path, issues):
    """vals: list of instances at this node. check required vs brute force for record nodes."""
    s = schema
    if "$ref" in s:
        ref = s["$ref"]; tgt = root
        for part in ref.lstrip("#/").split("/"):
            if part: tgt = tgt[part.replace("~1","/").replace("~0","~")]
        s = tgt
    for alt in s.get("oneOf", []) + s.get("anyOf", []):
        walk(alt, root, vals, path, issues)
    if s.get("type") == "object" and "properties" in s:
        objs = [v for v in vals if isinstance(v, dict)]
        if not objs: return
        req = set(s.get("required", []))
        inter = set(objs[0].keys())
        for o in objs[1:]: inter &= set(o.keys())
        # keys with null-only? a key present is present. brute-force truth:
        if "x-drishti" in s and s["x-drishti"].get("map"): return
        if req != inter and not any(isinstance(v, dict) and False for v in vals):
            # if conflicting oneOf the object branch only sees dict instances: already filtered
            issues.append(("required-mismatch", path, sorted(req ^ inter)[:5]))
        for k, ks in s["properties"].items():
            sub = [o[k] for o in objs if k in o]
            walk(ks, root, sub, path + "." + k, issues)
            pres = ks.get("x-drishti", {}).get("presence")
            if pres is not None and k in req: issues.append(("presence-on-required", path + "." + k, pres))
            if pres is None and k not in req: issues.append(("optional-without-presence", path + "." + k, None))
            if pres is not None and abs(pres - len(sub) / len(objs)) > 0.011: issues.append(("presence-wrong", path + "." + k, (pres, len(sub), len(objs))))
    elif s.get("type") == "array" and "items" in s:
        items = [e for v in vals if isinstance(v, list) for e in v]
        walk(s["items"], root, items, path + "[]", issues)
tot = {"cases": 0, "invalid": 0, "issues": 0, "http": 0}
for it in range(N):
    base = gen(0, None)
    if not isinstance(base, dict): base = {"v": base}
    docs = [mutate(base, R.choice([0, .05, .2, .5])) for _ in range(R.randint(1, 8))]
    if R.random() < .3: docs.append(gen(0, None))
    st, b = call("POST", "/api/v1/builder/shape", body={"samples": [{"name": "s%d" % i, "document": d} for i, d in enumerate(docs)]})
    tot["cases"] += 1
    if st != 200: tot["http"] += 1; print("HTTP", it, st, b[:150]); json.dump(docs, open("fail_http_%d.json" % it, "w")); continue
    out = json.loads(b); v = Draft202012Validator(out["schema"])
    bad = [i for i, d in enumerate(docs) if not v.is_valid(d)]
    issues = []
    walk(out["schema"], out["schema"], docs, "$", issues)
    if bad:
        tot["invalid"] += 1; e = next(iter(v.iter_errors(docs[bad[0]])))
        print("INVALID", it, bad, e.message[:140], list(e.absolute_path)[:6]); json.dump(docs, open("fail_inv_%d_%s.json" % (it, sys.argv[1] if len(sys.argv)>1 else 1), "w"))
    if issues:
        tot["issues"] += 1; print("ISSUE", it, issues[:3]); json.dump(docs, open("fail_iss_%d_%s.json" % (it, sys.argv[1] if len(sys.argv)>1 else 1), "w"))
print(tot)
