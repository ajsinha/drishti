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


"""source:/link panels to a kind the user may not open (qa-narrow-author) and to a masked field (qa-mauth), across all server surfaces."""
from sec import *
P = "/api/v1/builder/designs"
SUT = """rachana: 1
sutra: src-probe
version: 1
match: { kind: probe, priority: 1 }
title: { id: $.id }
panels:
  - id: linked
    kind: kv
    title: Linked trade
    source: "link($.t, 'trade')"
    columns:
      - { label: Trade, bind: $.tradeId }
      - { label: Trader, bind: $.trader }
      - { label: Notional, bind: $.notional }
  - id: linkedcp
    kind: kv
    title: Linked counterparty
    source: "link($.c, 'counterparty')"
    columns:
      - { label: Name, bind: $.name }
"""
DOC = {"id": "P1", "t": "MX-20000001", "c": "CP-MERIDIAN-RE"}
def scan(label, user, b, markers, expect):
    hit = [m for m in markers if m in b]
    log(f"source/{label}", f"as {user}", expect, f"leak={hit}", "FAIL" if hit else "PASS")
for user, markers, expect in [("qa-narrow-author", ["MX-20000001-", "TRDR-ASHAH", "242000000", "2.42E8", "Meridian Reinsurance"], "kind trade: no access; counterparty: allowed"),
                              ("qa-mauth", ["TRDR-ASHAH"], "trader masked")]:
    s, b = api(user, "POST", P, {"name": "src", "kind": "probe", "sutra": SUT}); D = jl(b)["id"]
    api(user, "POST", f"{P}/{D}/samples", {"samples": [{"name": "doc", "document": DOC}]})
    for label, m, p, body in [("preview", "GET", f"{P}/{D}/preview", None), ("check", "POST", f"{P}/{D}/check", None), ("ops-preview", "POST", f"{P}/{D}/ops", {"baseRev": 2, "ops": []}),
                              ("studio-preview", "POST", "/api/v1/studio/preview", {"yaml": SUT, "kind": "probe", "document": DOC}),
                              ("builder-check", "POST", "/api/v1/builder/check", {"yaml": SUT, "kind": "probe", "samples": [{"name": "doc", "document": DOC}]}),
                              ("builder-edit", "POST", "/api/v1/builder/edit", {"yaml": SUT, "ops": [], "kind": "probe", "document": DOC}),
                              ]:
        if label == "ops-preview":
            s0, b0 = api(user, "GET", f"{P}/{D}"); body = {"baseRev": jl(b0)["rev"], "ops": []}
        s, b = api(user, m, p, body)
        log(f"source/{label}/{user}", f"{m} {p.replace(D,'<D>')}", expect, f"{s} noaccess={'no access' in b.lower()} {b[:70] if s>=400 else ''}", "")
        scan(label, user, b, markers, expect)
    # shape / autodesign using the doc: examples contain only the pasted ids
    s, b = api(user, "POST", f"{P}/{D}/autodesign"); scan("autodesign", user, b, markers, expect)
    s, b = api(user, "POST", "/api/v1/builder/design", {"samples": [{"name": "doc", "document": DOC}], "kind": "probe"}); scan("builder-design", user, b, markers, expect)
    s, b = api(user, "POST", "/api/v1/builder/suggest", {"yaml": SUT, "samples": [{"name": "doc", "document": DOC}], "kind": "probe"}); log(f"source/suggest/{user}", "suggest", "", f"{s} {b[:80] if s>=400 else ''}"); scan("suggest", user, b, markers, expect)
    # export
    import zipfile, io, urllib.request
    req = urllib.request.Request(SRV + f"{P}/{D}/export", headers={"Authorization": "Bearer " + mint(user, ROLES[user])})
    try:
        z = zipfile.ZipFile(io.BytesIO(urllib.request.urlopen(req).read())); allt = "\n".join(z.read(n).decode("utf8", "replace") for n in z.namelist()); scan("export", user, allt, markers, expect)
    except Exception as e: log(f"source/export/{user}", "export", "", repr(e)[:100])
    s, b = api(user, "POST", f"{P}/{D}/propose", {"note": "x"}); log(f"source/propose/{user}", "propose", "", f"{s} {b[:90]}")
