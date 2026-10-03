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

"""Run one scripted sequence of design API calls against a server and print a normalised transcript; diff two servers' output."""
import json, sys, re
import qa
srv = sys.argv[1]
def j(method, path, user="qa-author", body=None, **k):
    st, b = qa.call(method, path, user, body, srv=srv, **k)
    try: o = json.loads(b)
    except Exception: o = b.decode("utf-8", "replace")
    return st, o
IDS = {}
def norm(o):
    s = json.dumps(o, sort_keys=True)
    for real, tag in IDS.items(): s = s.replace(real, tag)
    s = re.sub(r'"(token|path)": "[^"]*share=[^"]*"', r'"\1": "SHARE"', s)
    s = re.sub(r'"(created|updated|expiresAt|at|reviewedAt|createdAt)": \d+', r'"\1": 0', s)
    s = re.sub(r'\d{4}-\d\d-\d\dT[\d:.]+Z', 'TS', s)
    s = re.sub(r'"timings": \{[^}]*\}', '"timings": {}', s)
    s = re.sub(r'"(took|ms|elapsedMs|renderMs)": [\d.]+', r'"\1": 0', s)
    s = re.sub(r'"expiresIn": [\d.]+|"expiryWarning": "[^"]*"', '"EXP"', s)
    return s
out = []
def step(label, r):
    st, o = r
    if isinstance(o, dict) and isinstance(o.get("id"), str) and o["id"] not in IDS and label in ("create", "duplicate"): IDS[o["id"]] = "<ID%d>" % len(IDS)
    out.append(f"{label}: {st} {norm(o)}")
    return o
base = open("base.sutra.yaml").read()
doc = {"id": "T1", "mtm": 5, "coupon": 0.1, "qty": 3, "history": [{"date": "2026-01-0%d" % i, "v": i} for i in range(1, 6)], "legs": [{"name": "a", "amount": 1}]}
d = step("create", j("POST", "/api/v1/builder/designs", body={"name": "par", "kind": "trade", "sutra": base, "notes": "# notes\nhello é"})); i = d["id"] 
step("list", j("GET", "/api/v1/builder/designs"))
step("samples", j("POST", f"/api/v1/builder/designs/{i}/samples", body={"samples": [{"name": "s1", "document": doc}, {"name": "s2", "document": {**doc, "legs": []}}, {"name": "u é 名", "document": {"x": [1, 2]}}]}))
step("ref", j("POST", f"/api/v1/builder/designs/{i}/samples", body={"refs": {"kind": "trade", "ids": ["MX-20000001"]}}))
step("synthetic", j("POST", f"/api/v1/builder/designs/{i}/samples", body={"schema": {"type": "object", "properties": {"id": {"type": "string"}, "n": {"type": "integer"}}}, "count": 2}))
step("doc", j("GET", f"/api/v1/builder/designs/{i}/samples/document?name=s1"))
step("doc-unicode", j("GET", f"/api/v1/builder/designs/{i}/samples/document?name=u%20%C3%A9%20%E5%90%8D"))
step("doc-ref", j("GET", f"/api/v1/builder/designs/{i}/samples/document?name=trade%20MX-20000001"))
step("shape", j("POST", f"/api/v1/builder/designs/{i}/shape"))
step("preview", j("GET", f"/api/v1/builder/designs/{i}/preview?sample=s1"))
step("check", j("POST", f"/api/v1/builder/designs/{i}/check"))
d = step("get", j("GET", f"/api/v1/builder/designs/{i}")); rev = d["rev"]
o = step("ops1", j("POST", f"/api/v1/builder/designs/{i}/ops", body={"baseRev": rev, "ops": [{"op": "setOption", "panel": "terms", "option": "span", "value": 3}, {"op": "remove", "panel": "nope"}]}))
o = step("ops2", j("POST", f"/api/v1/builder/designs/{i}/ops", body={"baseRev": o["rev"], "ops": [{"op": "addPanel", "kind": "links", "id": "lk"}]}))
step("stale", j("POST", f"/api/v1/builder/designs/{i}/ops", body={"baseRev": rev, "ops": []}))
o = step("undo", j("POST", f"/api/v1/builder/designs/{i}/undo")); o = step("redo", j("POST", f"/api/v1/builder/designs/{i}/redo")); step("undo2", j("POST", f"/api/v1/builder/designs/{i}/undo"))
step("versions", j("GET", f"/api/v1/builder/designs/{i}/versions")); step("version0", j("GET", f"/api/v1/builder/designs/{i}/versions/0")); step("version9", j("GET", f"/api/v1/builder/designs/{i}/versions/9"))
step("patch", j("PATCH", f"/api/v1/builder/designs/{i}", body={"name": "renamed", "notes": "n2", "sutra": base + "\n# c\n"}))
step("autodesign", j("POST", f"/api/v1/builder/designs/{i}/autodesign"))
step("remove-sample", j("DELETE", f"/api/v1/builder/designs/{i}/samples?name=s2"))
step("other-user-get", j("GET", f"/api/v1/builder/designs/{i}", user="qa-author2"))
step("other-user-ops", j("POST", f"/api/v1/builder/designs/{i}/ops", user="qa-author2", body={"baseRev": 1, "ops": []}))
step("unknown-id", j("GET", "/api/v1/builder/designs/doesnotexist"))
step("no-auth", j("GET", f"/api/v1/builder/designs/{i}", user=None))
dup = step("duplicate", j("POST", f"/api/v1/builder/designs/{i}/duplicate")); 
step("share", j("POST", f"/api/v1/builder/designs/{i}/share"))
st, z = qa.call("GET", f"/api/v1/builder/designs/{i}/export", "qa-author", srv=srv)
import zipfile, io
zf = zipfile.ZipFile(io.BytesIO(z)); out.append("export: %s %s" % (st, sorted((n, len(zf.read(n))) for n in zf.namelist())))
open("export_%s.zip" % srv.split(":")[-1], "wb").write(z)
step("delete-dup", j("DELETE", f"/api/v1/builder/designs/{dup['id']}"))
step("delete", j("DELETE", f"/api/v1/builder/designs/{i}")); step("get-deleted", j("GET", f"/api/v1/builder/designs/{i}")); step("list-end", j("GET", "/api/v1/builder/designs"))
print("\n".join(out))
