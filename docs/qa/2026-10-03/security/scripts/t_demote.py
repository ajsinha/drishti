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


"""Demotion: qa-author's design D holds refs to trade and counterparty. The same user with a narrower token (kind counterparty only; and no-kind viewer-less role) must not read trade through D."""
from sec import *
import zipfile, io, urllib.request
fx = json.load(open("fixtures.json")); D = fx["D"]; P = "/api/v1/builder/designs"
ROLES["qa-author-demoted"] = ["qa-narrow-author"]
def as_demoted(m, p, body=None):
    return call(m, SRV + p, mint("qa-author", ["qa-narrow-author"]), body)[0::2]
M = ["TRDR-ASHAH", "1875863", "242000000", "2.42E8", "MX-20000001-"]
for label, m, p, body in [("read design", "GET", f"{P}/{D}", None), ("preview ref trade", "GET", f"{P}/{D}/preview?sample=trade%20MX-20000001", None),
                          ("preview default", "GET", f"{P}/{D}/preview", None), ("preview ref counterparty", "GET", f"{P}/{D}/preview?sample=counterparty%20CP-MERIDIAN-RE", None),
                          ("check", "POST", f"{P}/{D}/check", None), ("shape", "POST", f"{P}/{D}/shape", None), ("autodesign", "POST", f"{P}/{D}/autodesign", None),
                          ("versions", "GET", f"{P}/{D}/versions", None), ("sample document of ref", "GET", f"{P}/{D}/samples/document?name=trade%20MX-20000001", None),
                          ("propose", "POST", f"{P}/{D}/propose", {}), ("add ref trade", "POST", f"{P}/{D}/samples", {"refs": {"kind": "trade", "ids": ["MX-20000002"]}}),
                          ("add ref trade by count", "POST", f"{P}/{D}/samples", {"refs": {"kind": "trade", "count": 3}})]:
    s, b = as_demoted(m, p, body)
    hit = [x for x in M if x in b]
    log(f"demoted/{label}", f"{m} {p.replace(D,'<D>')}", "no trade data; 403/noAccess/skipped", f"{s} leak={hit} {('noaccess' if 'no access' in b.lower() or 'noAccess' in b else '')} {b[:60] if s>=400 else ''}", "FAIL" if hit and 'read design' != label else "PASS" if s != 200 or 'no access' in b.lower() or 'noAccess' in b or 'skipped' in b else "CHECK")
req = urllib.request.Request(SRV + f"{P}/{D}/export", headers={"Authorization": "Bearer " + mint("qa-author", ["qa-narrow-author"])})
try:
    z = zipfile.ZipFile(io.BytesIO(urllib.request.urlopen(req).read())); allt = "\n".join(z.read(n).decode("utf8", "replace") for n in z.namelist())
    log("demoted/export", "GET export", "no trade data", f"files={z.namelist()} leak={[x for x in M if x in allt]}", "FAIL" if any(x in allt for x in M) else "PASS")
except Exception as e: log("demoted/export", "GET export", "", repr(e)[:150])
