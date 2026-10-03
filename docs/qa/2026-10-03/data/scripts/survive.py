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

import sys, json, hashlib
from qa import *
srv = sys.argv[2]; phase = sys.argv[1]
def J(m, p, **k): return j(m, p, srv=srv, **k)
base = open("base.sutra.yaml").read()
D = "/api/v1/builder/designs"
if phase == "make":
    st, d = J("POST", D, body={"name": "surv", "kind": "trade", "sutra": base, "notes": "n é"}); i = d["id"]
    J("POST", f"{D}/{i}/samples", body={"samples": [{"name": "s1", "document": {"id": "T1", "mtm": 1}}, {"name": "é", "document": {"a": [1, 2]}}]})
    J("POST", f"{D}/{i}/samples", body={"refs": {"kind": "trade", "ids": ["MX-20000001"]}})
    st, d = J("GET", f"{D}/{i}"); r = d["rev"]
    for n in range(4):
        st, o = J("POST", f"{D}/{i}/ops", body={"baseRev": r, "ops": [{"op": "setOption", "panel": "legs", "option": "title", "value": "t%d" % n}]}); r = o["rev"]
    J("POST", f"{D}/{i}/undo"); st, sh = J("POST", f"{D}/{i}/share")
    st, d = J("GET", f"{D}/{i}"); json.dump({"id": i, "d": d, "token": sh["token"]}, open("surv_%s.json" % srv[-5:], "w"))
    print("made", i, "rev", d["rev"], "opsAt", d["opsAt"], "ops", len(d["ops"]))
else:
    s = json.load(open("surv_%s.json" % srv[-5:])); i = s["id"]
    st, d = J("GET", f"{D}/{i}"); print("get", st)
    for k in ("name", "kind", "notes", "sutra", "rev", "opsAt", "status", "shared", "bytes", "scratch"):
        if d.get(k) != s["d"].get(k): print("  DIFF", k, repr(d.get(k))[:80], repr(s["d"].get(k))[:80])
    print("  ops equal:", d["ops"] == s["d"]["ops"], "samples equal:", d["samples"] == s["d"]["samples"], "expiresAt same:", d["expiresAt"] == s["d"]["expiresAt"], d["expiresAt"] - s["d"]["expiresAt"])
    st, o = J("POST", f"{D}/{i}/redo"); print("  redo", st, o.get("rev") if isinstance(o, dict) else o)
    st, o = J("GET", f"{D}/shared/{i}?token={s['token']}"); print("  share token still valid:", st)
    st, o = J("GET", f"{D}/{i}/samples/document?name=%C3%A9"); print("  unicode sample:", st, o)
    st, o = J("GET", f"{D}/{i}/preview?sample=s1"); print("  preview", st)
    st, o = J("DELETE", f"{D}/{i}"); print("  delete", st)
