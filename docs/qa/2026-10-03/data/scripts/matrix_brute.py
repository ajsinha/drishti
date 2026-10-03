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

"""Checker matrix vs brute force: the same Sutra previewed on each sample one at a time (Design preview) gives the truth
(panel.empty / panel.error); the matrix from /builder/check and /designs/{id}/check must equal it, cell by cell."""
import json, random, sys, copy
from qa import *
E = "/home/ashutosh/IdeaProjects/drishti/docs/guides/examples/"
R = random.Random(int(sys.argv[1]) if len(sys.argv) > 1 else 1)
name = sys.argv[2] if len(sys.argv) > 2 else "all-panels-showcase"
N = int(sys.argv[3]) if len(sys.argv) > 3 else 12
sutra = open(E + name + ".sutra.yaml").read(); doc = json.load(open(E + name + ".json"))
import yaml
kind = yaml.safe_load(sutra)["match"]["kind"]
def paths(x, p=()):
    if isinstance(x, dict):
        for k, v in x.items(): yield p + (k,); yield from paths(v, p + (k,))
    elif isinstance(x, list):
        for i, v in enumerate(x): yield p + (i,); yield from paths(v, p + (i,))
def mutate(d):
    d = copy.deepcopy(d); ps = list(paths(d))
    for _ in range(R.randint(0, 6)):
        if not ps: break
        p = R.choice(ps); cur = d
        try:
            for k in p[:-1]: cur = cur[k]
            c = R.random()
            if c < .35 and isinstance(cur, dict): cur.pop(p[-1], None)
            elif c < .5: cur[p[-1]] = None
            elif c < .65: cur[p[-1]] = "garbage"
            elif c < .75: cur[p[-1]] = []
            elif c < .85: cur[p[-1]] = {}
            elif c < .95: cur[p[-1]] = -1e300
            else: cur[p[-1]] = [[]]
        except Exception: pass
    return d
samples = [{"name": "orig", "document": doc}] + [{"name": "m%d" % i, "document": mutate(doc)} for i in range(N)] + [{"name": "empty", "document": {}}, {"name": "null-root-fields", "document": {k: None for k in doc}}]
st, d = j("POST", "/api/v1/builder/designs", body={"name": "mb", "kind": kind, "sutra": sutra}); i = d["id"]
for s in samples: j("POST", f"/api/v1/builder/designs/{i}/samples", body={"samples": [s]})
truth = {}
for s in samples:
    st, p = j("GET", f"/api/v1/builder/designs/{i}/preview?sample={s['name']}")
    if st != 200: truth[s["name"]] = ("preview-%s" % st, str(p)[:100]); continue
    truth[s["name"]] = {x["id"]: ("error" if x.get("error") else "empty" if x.get("empty") else "ok") for x in p["panels"]}
st, c1 = j("POST", "/api/v1/builder/check", body={"yaml": sutra, "kind": kind, "samples": samples})
st2, c2 = j("POST", f"/api/v1/builder/designs/{i}/check")
bad = 0
for tag, c in (("stateless", c1), ("design", c2)):
    if "panels" not in c: print(tag, "NO MATRIX", str(c)[:200]); bad += 1; continue
    names = [x["name"] for x in c["samples"]]
    for pn in c["panels"]:
        for sn, cell in zip(names, pn["cells"]):
            t = truth[sn]
            exp = t.get(pn["id"], "absent") if isinstance(t, dict) else "?"
            if exp != cell["status"] and not (exp == "absent" and cell["status"] == "empty"):
                bad += 1; print(tag, "MISMATCH sample", sn, "panel", pn["id"], "truth", exp, "matrix", cell)
    # counts
    tot = {"ok": 0, "empty": 0, "error": 0, "noAccess": 0}
    for pn in c["panels"]:
        for cell in pn["cells"]: tot[cell["status"]] += 1
    if tot != c["counts"]: bad += 1; print(tag, "COUNTS", tot, c["counts"])
    for pn in c["panels"]:
        t = {"ok": 0, "empty": 0, "error": 0, "noAccess": 0}
        for cell in pn["cells"]: t[cell["status"]] += 1
        if t != pn["counts"]: bad += 1; print(tag, "PANEL COUNTS", pn["id"], t, pn["counts"])
    if c.get("ok") != (c["counts"]["error"] == 0 and c["counts"]["noAccess"] == 0): print(tag, "ok flag", c.get("ok"), c["counts"])
print(name, "samples", len(samples), "panels", len(c1.get("panels", [])), "mismatches", bad, "counts", c1.get("counts"))
j("DELETE", f"/api/v1/builder/designs/{i}")
