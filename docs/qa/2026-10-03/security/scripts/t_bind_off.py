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


"""File binding with drishti.builder.file-binding=false: every route must refuse (also for admin / author / path variants)."""
from sec import *
P = "/api/v1/builder/designs"
s, b = api("qa-author", "POST", P, {"name": "bindoff", "kind": "x", "sutra": "rachana: 1\nsutra: bo\nversion: 1\nmatch: { kind: x }\ntitle: { id: $.id }\npanels: []\n"}); D = jl(b)["id"]
s, b = api("qa-author", "GET", f"{P}/binding"); log("binding off: status", "GET binding", "enabled=false", f"{s} {b[:100]}", "PASS" if jl(b).get("enabled") is False else "FAIL")
for u in ["qa-author", "qa-admin", "qa-viewer"]:
    for m, path, body in [("POST", f"{P}/{D}/bind", {"file": "bo.yaml"}), ("POST", f"{P}/{D}/save-file", None), ("GET", f"{P}/{D}/sync", None), ("POST", f"{P}/{D}/bind", {"file": "../../etc/x.yaml"})]:
        s, b = api(u, m, path, body); log(f"binding off {u}", f"{m} {path.replace(D,'<D>')} {body}", "403 (sync: 200 no change)", f"{s} {b[:90]}", "PASS" if s == 403 or (m == "GET" and s == 200 and '"changed":false' in b) else "FAIL")
# can a PATCH smuggle boundFile?
s, b = api("qa-author", "PATCH", f"{P}/{D}", {"boundFile": "evil.yaml", "shareHash": "x", "owner": "qa-author2", "status": "live(v9)", "rev": 99}); d = jl(b)
log("mass assignment via PATCH", "PATCH {boundFile,shareHash,owner,status,rev}", "ignored", f"{s} bound={d.get('boundFile')} status={d.get('status')} rev={d.get('rev')}", "PASS" if not d.get('boundFile') and d.get('status') == 'draft' else "FAIL")
s, b = api("qa-author", "POST", P, {"name": "mass", "owner": "qa-author2", "id": "000000000001", "status": "live(v9)", "boundFile": "x.yaml", "scratch": False, "shareHash": "aa"}); d = jl(b)
log("mass assignment via create", "POST {owner,id,status,boundFile,shareHash}", "ignored", f"{s} id={d.get('id')} status={d.get('status')} bound={d.get('boundFile')} shared={d.get('shared')}", "PASS" if d.get('id') != '000000000001' and d.get('status') == 'draft' and not d.get('boundFile') else "FAIL")
s, b = api("qa-author2", "GET", P); log("mass assignment owner", "author2 list after create-with-owner", "no foreign design", "ok" if "mass" not in b else "LEAK", "PASS" if "mass" not in b else "FAIL")
