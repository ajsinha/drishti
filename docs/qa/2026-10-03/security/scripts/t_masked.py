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


"""Masked fields (trader) and ref samples across every design surface, as qa-mauth (author, trade kind, no raw)."""
from sec import *
import zipfile, io
P = "/api/v1/builder/designs"; U = "qa-mauth"
s, b = api("qa-admin", "GET", "/api/v1/entities/trade/MX-20000001/raw"); raw = jl(b)["data"]; TR = raw["trader"]; CPN = raw["counterparty"]["name"]
print("unmasked trader:", TR, "cp name:", CPN)
def leak(b, label):
    hit = [k for k in (TR, ) if k and k in b]
    return hit
s, b = api(U, "POST", P, {"name": "masked", "kind": "trade", "base": "irs-fixfloat@1"}); D = jl(b)["id"]
sutra = jl(b)["sutra"].replace("strip:\n", "strip:\n  - { label: TraderX, bind: $.trader }\n", 1)
s, b = api(U, "PATCH", f"{P}/{D}", {"sutra": sutra}); print("patch", s)
s, b = api(U, "POST", f"{P}/{D}/samples", {"refs": {"kind": "trade", "ids": ["MX-20000001"]}, "samples": [{"name": "doc", "document": {"tradeId": "T1", "trader": "MY-OWN-TRADER", "currency": "USD", "notional": 5}}]}); print("samples", s, b[:100])
tests = [("preview ref", "GET", f"{P}/{D}/preview?sample=" + "trade%20MX-20000001"), ("preview default", "GET", f"{P}/{D}/preview"), ("shape", "POST", f"{P}/{D}/shape", None),
 ("check", "POST", f"{P}/{D}/check", None), ("autodesign", "POST", f"{P}/{D}/autodesign", None), ("read", "GET", f"{P}/{D}", None), ("versions", "GET", f"{P}/{D}/versions", None),
 ("doc of ref", "GET", f"{P}/{D}/samples/document?name=trade%20MX-20000001", None), ("ops", "POST", f"{P}/{D}/ops", {"baseRev": 2, "ops": []}), ("undo", "POST", f"{P}/{D}/undo", {}),
 ("propose", "POST", f"{P}/{D}/propose", {"note": "qa"})]
for t in tests:
    s, b = api(U, t[2] and t[1], t[2] if len(t) > 3 else t[2], t[3] if len(t) > 3 else None) if False else (None, None)
for label, m, p, body in [(t[0], t[1], t[2], t[3] if len(t) > 3 else None) for t in tests]:
    s, b = api(U, m, p, body); h = leak(b, label)
    log(f"masked/{label}", f"{m} {p.replace(D,'<D>')}", "no unmasked trader", f"{s} leak={h} {b[:60]}", "FAIL" if h else "PASS")
# export zip
s, h, raw_zip = call("GET", SRV + f"{P}/{D}/export", mint(U, ROLES[U]))
log("masked/export", "GET export", "zip", s)
if s == 200:
    # binary-safe: re-read bytes
    import urllib.request
    req = urllib.request.Request(SRV + f"{P}/{D}/export", headers={"Authorization": "Bearer " + mint(U, ROLES[U])})
    z = zipfile.ZipFile(io.BytesIO(urllib.request.urlopen(req).read()))
    names = z.namelist(); allt = "\n".join(z.read(n).decode("utf8", "replace") for n in names)
    log("masked/export contents", ", ".join(names)[:200], "no unmasked trader; refs not exported or masked", f"leak={leak(allt,'')} files={len(names)}", "FAIL" if leak(allt, '') else "PASS")
    open("export_masked.txt", "w").write(allt[:20000])
# proposals evidence
s, b = api("qa-admin", "GET", "/api/v1/sutras/proposals"); print("proposals list", s, b[:300])
print("design", D)
open("masked_design.txt", "w").write(D)
