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

"""scratch-ttl 20s, named-ttl 60s, warn-after 30s, sweep 5s (server :18964). Measure deletion time & touch semantics."""
import time, json
from qa import *
S = "http://127.0.0.1:18964"; D = "/api/v1/builder/designs"
def J(m, p, **k): return j(m, p, srv=S, **k)
t0 = time.time()
st, sc = J("POST", D, body={}); st, nm = J("POST", D, body={"name": "named"}); st, sc2 = J("POST", D, body={}); st, sc3 = J("POST", D, body={})
print("scratch expiresAt-created ms:", sc["expiresAt"] - sc["created"], "named:", nm["expiresAt"] - nm["created"], "warn:", nm["expiryWarning"])
# touch sc2 by reading every 8 s; touch sc3 by a patch at 15 s
ids = {"scratch": sc["id"], "named": nm["id"], "read-touched": sc2["id"], "patch-touched": sc3["id"]}
gone = {}
patched = False
while time.time() - t0 < 100 and len(gone) < 4:
    el = time.time() - t0
    if int(el) % 8 == 0: J("GET", f"{D}/{sc2['id']}")
    if el > 15 and not patched: print("patch at", round(el, 1), J("PATCH", f"{D}/{sc3['id']}", body={"notes": "x"})[0]); patched = True
    for k, i in ids.items():
        if k in gone: continue
        st, o = J("GET", f"{D}/{i}") if k != "read-touched" else (J("GET", f"{D}/{i}") if True else None)
        if st == 404: gone[k] = round(el, 1)
        elif k == "named" and o.get("expiryWarning") and "warn" not in gone: gone["warn"] = round(el, 1)
    time.sleep(1.0)
print("gone at (s since create):", gone)
st, l = J("GET", D); print("remaining", [d["name"] or d["id"] for d in l["designs"]])
