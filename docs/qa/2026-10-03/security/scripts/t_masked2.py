# Project Drishti · Any data. Any domain. One grammar.
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
# All rights reserved.
#
# PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository for the full terms.

"""Masked field `trader` shown by a strip figure and by a source:/link panel, across preview/check/propose evidence/export/reviews/diff, as qa-mauth."""
import io, zipfile, urllib.request
from sec import *
P = "/api/v1/builder/designs"; U = "qa-mauth"; TR = "TRDR-ASHAH"
s, b = api(U, "POST", P, {"name": "m3", "kind": "trade", "base": "irs-fixfloat@1"}); D = jl(b)["id"]
lines = jl(b)["sutra"].split("\n"); i = next(k for k, l in enumerate(lines) if l.startswith("strip:"))
lines[i + 1] = "  - { label: TraderX, bind: $.trader }"
print(api(U, "PATCH", f"{P}/{D}", {"sutra": "\n".join(lines)})[0])
print(api(U, "POST", f"{P}/{D}/samples", {"refs": {"kind": "trade", "ids": ["MX-20000001"]}})[0])
def chk(label, m, p, body=None):
    s, b = api(U, m, p, body); l = TR in b
    log(f"masked2/{label}", f"{m} {p.replace(D, '<D>')}", "trader shown as bullets, never the value", f"{s} leak={l} bullets={'•••' in b}", "FAIL" if l else "PASS"); return s, b
chk("preview", "GET", f"{P}/{D}/preview")
chk("check", "POST", f"{P}/{D}/check")
chk("shape", "POST", f"{P}/{D}/shape")
s, b = chk("propose", "POST", f"{P}/{D}/propose", {"note": "qa"}); print(b[:200])
pid = (jl(b) or {}).get("proposal", {}).get("id")
if pid:
    for u in ["qa-admin", "qa-approver"]:
        s, b = api(u, "GET", f"/api/v1/sutras/proposals/{pid}"); log(f"masked2/proposal as {u}", f"GET proposals/{pid}", "no unmasked trader", f"{s} leak={TR in b} len={len(b)}", "FAIL" if TR in b else "PASS")
    s, b = api("qa-admin", "GET", "/api/v1/sutras/proposals"); log("masked2/proposals list", "GET", "no trader", f"leak={TR in b}", "FAIL" if TR in b else "PASS")
    open("proposal_id.txt", "w").write(pid)
req = urllib.request.Request(SRV + f"{P}/{D}/export", headers={"Authorization": "Bearer " + mint(U, ROLES[U])})
try:
    z = zipfile.ZipFile(io.BytesIO(urllib.request.urlopen(req).read())); allt = "\n".join(z.read(n).decode("utf8", "replace") for n in z.namelist())
    log("masked2/export", "GET export", "no trader; names", f"files={z.namelist()} leak={TR in allt}", "FAIL" if TR in allt else "PASS")
except Exception as e:
    log("masked2/export", "GET export", "zip", repr(e)[:100])
open("masked_design.txt", "w").write(D)
