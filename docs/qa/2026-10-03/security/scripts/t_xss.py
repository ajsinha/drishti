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


"""Stored XSS probes: hostile text in design name, notes, sample names, Sutra description/title, proposal note, review comment, share page, versions/diff; look for the payload unescaped in console HTML."""
from t_console import *
S = json.load(open("console_sessions.json")); P = "/api/v1/builder/designs"
X1 = '<img src=x onerror=alert(101)>'; X2 = '"><script>alert(102)</script>'; X3 = "<svg/onload=alert(103)>"; X4 = "';alert(104);//"; X5 = "</script><script>alert(105)</script>"
SUT = ("rachana: 1\nsutra: xss-probe\nversion: 1\ndescription: \"" + X1.replace('"', "'") + "\"\nmatch: { kind: xsskind, priority: 1 }\n"
       "title: { id: $.id, pill: \"" + X3 + "\" }\nstrip:\n  - { label: \"" + X1 + "\", bind: $.id }\npanels:\n  - { id: p, kind: kv, title: \"" + X5.replace('"', "'") + "\", columns: [ { label: \"" + X3 + "\", bind: $.name } ] }\n")
s, b = api("qa-author", "POST", P, {"name": X1, "kind": "xsskind", "sutra": SUT, "notes": X2 + X5}); D = jl(b)["id"]
s, b = api("qa-author", "POST", f"{P}/{D}/samples", {"samples": [{"name": X3, "document": {"id": X1, "name": X5}}]}); print("samples", s)
s, b = api("qa-author", "POST", f"{P}/{D}/propose", {"note": X1 + X5}); print("propose", s, b[:120]); pid = (jl(b) or {}).get("proposal", {}).get("id")
pages = [("author", f"/build/d/{D}"), ("author", "/build"), ("author", f"/build/designs/{D}"), ("author", f"/build/designs/{D}/versions"), ("author", f"/build/designs/{D}/diff"), ("author", f"/build/designs/{D}/preview"),
         ("author", f"/build/designs/{D}/shape"), ("approver", "/build/reviews"), ("approver", "/build/reviews?status=all"), ("approver", f"/build/reviews/{pid}")]
s, b = api("qa-author", "POST", f"{P}/{D}/share"); tok = jl(b)["token"]; pages.append(("qa-viewer".replace("qa-", "") if False else "viewer", f"/build/d/{D}?share={tok}"))
S["author"] = S["qa-author"]; S["approver"] = S["qa-approver"]; S["viewer"] = S["qa-viewer"]
raw = ["<img src=x onerror=alert(101)>", '"><script>alert(102)</script>', "<svg/onload=alert(103)>", "</script><script>alert(105)</script>"]
for who, path in pages:
    st, h, b = creq(S[who], "GET", path)
    # JSON endpoints legitimately hold the raw text (JSON, not HTML); check content type
    ct = h.get("content-type", "")
    found = [r for r in raw if r in b]
    html = "text/html" in ct
    log(f"xss page {path.split('?')[0].replace(D,'<D>')[:40]}", f"GET as {who} ({ct[:20]})", "payload escaped in HTML", f"{st} rawPayloadsInBody={len(found)}", "FAIL" if (html and found) else "PASS")
# review comment
st, h, b = creq(S["qa-admin"], "POST", f"/build/reviews/{pid}/reject", "comment=" + urllib.parse.quote(X1 + X2), headers={"Origin": CON}, ctype="application/x-www-form-urlencoded")
st, h, b = creq(S["qa-admin"], "GET", f"/build/reviews/{pid}"); log("xss review comment", "reviews page after reject comment", "escaped", f"{st} raw={[r for r in raw if r in b]}", "FAIL" if any(r in b for r in raw) else "PASS")
# JS context: the workbench inlines init JSON; check script-injection through </script> in JSON
st, h, b = creq(S["qa-author"], "GET", f"/build/d/{D}"); i = b.find("</script><script>alert(105)"); log("xss inline init JSON", "GET workbench", "no raw </script> from data", f"raw_close_script_from_data={i>=0}", "FAIL" if i >= 0 else "PASS")
