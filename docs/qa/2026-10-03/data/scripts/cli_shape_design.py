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

import os, json, shutil, glob, sys
from cli_run import *
from qa import j
W = S + "/w3"; shutil.rmtree(W, ignore_errors=True); os.makedirs(W + "/in")
E = "/home/ashutosh/IdeaProjects/drishti/docs/guides/examples/"
sets = {"pnl": [E + "pnl-explain.json"], "multi": [E + "exposure-profile.json", E + "market-charts.json"], "showcase": [E + "all-panels-showcase.json"], "tree": [E + "tree-table.json"]}
# a 5-sample set of similar docs
for n in range(5):
    json.dump({"id": "T-%d" % n, "book": "B%d" % (n % 2), "mtm": 1000 * n + 7, "pnl": [{"date": "2026-01-0%d" % (k + 1), "v": n * k} for k in range(6)]}, open(f"{W}/in/t{n}.json", "w"))
sets["five"] = sorted(glob.glob(W + "/in/t*.json"))
for name, files in sets.items():
    out = f"{W}/out_{name}"
    rc1, o1 = sutra("shape", *files, "--out", out); rc2, o2 = sutra("design", *files, "--kind", "deal", "--out", out + "_d")
    samples = [{"name": os.path.basename(f), "document": json.load(open(f))} for f in files]
    st, sh = j("POST", "/api/v1/builder/shape", body={"samples": samples})
    cs = json.load(open(out + "/shape.json")) if os.path.exists(out + "/shape.json") else None
    same_schema = cs is not None and (cs.get("schema", cs) == sh["schema"])
    same_roles = cs is not None and cs.get("roles") == sh["roles"]
    st, ds = j("POST", "/api/v1/builder/design", body={"kind": "deal", "samples": samples})
    cy = open(out + "_d/deal.sutra.yaml").read() if os.path.exists(out + "_d/deal.sutra.yaml") else None
    print(f"{name:9} shape rc={rc1} files={os.listdir(out) if os.path.exists(out) else None} schema==api:{same_schema} roles==api:{same_roles} | design rc={rc2} yaml==api:{cy == ds['yaml'] if cy else None}")
    if cy and cy != ds["yaml"]:
        import difflib; print("".join(list(difflib.unified_diff(cy.splitlines(1), ds["yaml"].splitlines(1)))[:12]))
    if cs and not same_schema: print("   cli keys", list(cs)[:6], "api keys", list(sh)[:6])
# preview
rc, o = sutra("preview", E + "pnl-explain.sutra.yaml", "--out", W + "/prev"); print("preview rc", rc, os.listdir(W + "/prev") if os.path.exists(W + "/prev") else None, o[-150:])
# stdout shape without --out
rc, o = sutra("shape", E + "pnl-explain.json"); print("shape stdout rc", rc, len(o), o[:80].replace("\n", " "))
# usage errors
for a in (["shape"], ["design"], ["shape", W + "/nope.json"], ["design", W + "/in", "--kind"], ["design", W + "/in", "--kind", "bad kind!"], ["shape", W + "/in/t0.json", "--samples"]):
    rc, o = sutra(*a); print("exit", rc, a[:3], "|", (o.splitlines() or [""])[-1][:110])
# jsonl, empty folder, mixed
open(W + "/in/x.jsonl", "w").write(json.dumps({"id": 1, "v": 2}) + "\n" + json.dumps({"id": 2, "v": 3}) + "\n")
os.makedirs(W + "/empty", exist_ok=True)
rc, o = sutra("shape", W + "/in/x.jsonl"); print("shape jsonl rc", rc, o[:100].replace("\n", " "))
rc, o = sutra("shape", W + "/empty"); print("shape empty folder rc", rc, o[:120].replace("\n", " "))
rc, o = sutra("design", W + "/empty", "--out", W + "/o"); print("design empty folder rc", rc, o[-120:].replace("\n", " "))
