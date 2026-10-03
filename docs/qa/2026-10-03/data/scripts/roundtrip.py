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

"""Export -> import round trip: Sutra / tests / samples byte-equivalence, with hostile sample names."""
import json, zipfile, io, hashlib, sys
from qa import *
base = open("base.sutra.yaml").read()
doc = {"id": "T1", "mtm": 5, "coupon": 0.1, "qty": 3, "history": [{"date": "2026-01-01", "v": 1}], "legs": [{"name": "a", "amount": 1}]}
names = ["é.json", "名.json", "a/b", "../../etc/passwd", "CON", "a b", "A", "a", "x" * 300, ".hidden", "..", "", "sample.json", "sample", "q?*:<>|", "emoji😀", "NUL.txt", "tab\tname", "dup", "dup "]
st, d = j("POST", "/api/v1/builder/designs", body={"name": "rt", "kind": "trade", "sutra": base, "notes": "notes # with: odd\n- chars é\n"}); i = d["id"]
for n, nm in enumerate(names):
    st, o = j("POST", f"/api/v1/builder/designs/{i}/samples", body={"samples": [{"name": nm, "document": {**doc, "id": "T%d" % n, "nm": nm}}]})
    if st != 200: print("sample name", repr(nm[:20]), "->", st, str(o)[:130])
st, d = j("GET", f"/api/v1/builder/designs/{i}"); kept = [s["name"] for s in d["samples"]]; print("kept samples", len(kept))
st, z = call("GET", f"/api/v1/builder/designs/{i}/export")
zf = zipfile.ZipFile(io.BytesIO(z)); nl = zf.namelist(); print("export", st, len(nl), "entries; dup entries:", len(nl) - len(set(nl)))
bad = [n for n in nl if ".." in n or n.startswith("/") or "\\" in n]; print("suspicious entry names:", bad)
samples_in = [n for n in nl if "/samples/" in n]; tests_in = [n for n in nl if "/tests/" in n and n.endswith(".json")]; print("samples in zip", len(samples_in), "tests", len(tests_in), "(samples kept %d)" % len(kept))
# content round trip
st, imp = call("POST", "/api/v1/builder/designs/import", raw=z, ctype="application/zip")
imp = json.loads(imp); print("import", st, str(imp)[:300])
if st == 200 or st == 201:
    ds = imp.get("designs") or imp.get("created") or []
    for dd in ds:
        nid = dd["id"]; st, g = j("GET", f"/api/v1/builder/designs/{nid}")
        print(" imported", dd.get("name"), "samples", len(g["samples"]), "sutra equal:", g["sutra"].strip() == d["sutra"].strip(), "notes equal:", (g.get("notes") or "").strip() == (d.get("notes") or "").strip())
        zorig = {n.split("/", 1)[1]: zf.read(n) for n in nl if not n.endswith("/")}
        # re-export and compare bytes
        st, z2 = call("GET", f"/api/v1/builder/designs/{nid}/export"); zf2 = zipfile.ZipFile(io.BytesIO(z2)); 
        a = {n.split("/", 1)[1]: hashlib.md5(zf.read(n)).hexdigest() for n in zf.namelist() if not n.endswith("/")}
        b = {n.split("/", 1)[1]: hashlib.md5(zf2.read(n)).hexdigest() for n in zf2.namelist() if not n.endswith("/")}
        print("  re-export identical entry set:", sorted(a) == sorted(b), "differing:", [k for k in a if k in b and a[k] != b[k]][:6], "only1:", [k for k in a if k not in b][:5], "only2:", [k for k in b if k not in a][:5])
        j("DELETE", f"/api/v1/builder/designs/{nid}")
j("DELETE", f"/api/v1/builder/designs/{i}")
