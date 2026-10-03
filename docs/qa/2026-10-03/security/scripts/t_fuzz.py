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


"""Error-shape fuzz of the design endpoints: wrong types, nulls, huge numbers, odd ids. Flags any 5xx, stack trace, absolute path or class name in a body."""
from sec import *
import itertools
P = "/api/v1/builder/designs"; U = "qa-author"
s, b = api(U, "POST", P, {"name": "fz", "kind": "fz", "sutra": "rachana: 1\nsutra: fz-probe\nversion: 1\nmatch: { kind: fz }\ntitle: { id: $.id }\npanels: []\n"}); D = jl(b)["id"]
api(U, "POST", f"{P}/{D}/samples", {"samples": [{"name": "s", "document": {"id": "1", "a": [1, 2]}}]})
bodies = [None, [], "str", 123, {"name": 5}, {"name": None}, {"kind": ["x"]}, {"sutra": 5}, {"sutra": None}, {"tests": "x"}, {"tests": [1, "a", None]}, {"baseRev": "1"}, {"baseRev": 1.5}, {"baseRev": 2**70}, {"baseRev": -1},
          {"baseRev": 1, "ops": "x"}, {"baseRev": 1, "ops": [None]}, {"baseRev": 1, "ops": [{"op": "nope"}]}, {"baseRev": 1, "ops": [{"op": "setTitle"}]}, {"samples": "x"}, {"samples": [None]}, {"samples": [{"name": 5, "document": 1}]},
          {"samples": [{"name": "a"}]}, {"refs": "x"}, {"refs": {"kind": 5}}, {"refs": {"kind": "trade", "ids": "x"}}, {"refs": {"kind": "trade", "ids": [None, 5, {}]}}, {"refs": {"kind": "trade", "count": "x"}},
          {"schema": "x"}, {"schema": {"type": 5}}, {"schema": {"properties": []}, "count": "3"}, {"file": 5}, {"file": None}, {"file": ["a.yaml"]}, {"note": 5}, {"note": "x" * 100000}, {"name": "x" * 100000}, {"notes": "x" * 3000000}, {"name": "\u0000"}, {"name": "\ud800"}]
bad = 0; n = 0
for m, p in [("POST", f"{P}"), ("PATCH", f"{P}/{D}"), ("POST", f"{P}/{D}/samples"), ("POST", f"{P}/{D}/ops"), ("POST", f"{P}/{D}/undo"), ("POST", f"{P}/{D}/redo"), ("POST", f"{P}/{D}/propose"), ("POST", f"{P}/{D}/duplicate"),
             ("POST", f"{P}/{D}/bind"), ("POST", f"{P}/import")]:
    for body in bodies:
        s, h, b = call(m, SRV + p, mint(U, ROLES[U]), json.dumps(body) if body is not None else None, raw=(json.dumps(body).encode() if body is not None else None)); n += 1
        flag = s >= 500 or s == 0 or any(x in b for x in ["at com.", "at java.", "Exception", "/home/", "/tmp/", "org.springframework", "com.ash."])
        if flag:
            bad += 1; log("fuzz", f"{m} {p.replace(D,'<D>')} body={json.dumps(body)[:60]}", "4xx problem+json, no internals", f"{s} {b[:100]}", "FAIL")
ids = ["..", "%2e%2e", "a" * 5000, "0", "-1", "null", "%00", "ä", "%ff", "1;2", "1,2", "{D}"]
for i in ids:
    for m, suf in [("GET", ""), ("GET", "/preview"), ("GET", "/versions/-1"), ("GET", "/versions/99999999999"), ("GET", "/versions/x"), ("GET", "/samples/document"), ("DELETE", "/samples"), ("GET", "/samples/document?name=")]:
        p = f"{P}/{i.replace('{D}', D)}{suf}"
        s, h, b = call(m, SRV + p, mint(U, ROLES[U])); n += 1
        flag = s >= 500 or s == 0 or any(x in b for x in ["at com.", "at java.", "/home/", "/tmp/", "org.springframework", "com.ash."])
        if flag: bad += 1; log("fuzz id", f"{m} {p[:90]}", "4xx", f"{s} {b[:100]}", "FAIL")
s, b = api(U, "GET", f"{P}/{D}/versions/99999999999"); log("fuzz version int overflow", "GET versions/99999999999", "4xx", f"{s} {b[:100]}")
log("fuzz summary", f"{n} requests", "no 5xx / internals", f"{bad} flagged", "PASS" if bad == 0 else "FAIL")
