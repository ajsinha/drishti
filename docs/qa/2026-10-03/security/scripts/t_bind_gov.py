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


"""File binding + governance: an author with binding on rewrites a live, approved Sutra file (and adds a new one) without any review."""
from sec import *
import os, time
P = "/api/v1/builder/designs"; W = open(".workdir").read().strip()
live = "rachana: 1\nsutra: live-victim\nversion: 1\ndescription: approved and live\nmatch: { kind: victimkind, priority: 1 }\ntitle: { id: $.id }\npanels: []\n"
open(W + "/sutras/live-victim.v1.sutra.yaml", "w").write(live)
time.sleep(3)
s, b = api("qa-viewer", "GET", "/api/v1/sutras/live-victim/1/source"); log("setup live Sutra", "file live-victim.v1.sutra.yaml dropped in dir", "visible", f"{s}")
s, b = api("qa-author", "POST", P, {"name": "rewrite", "kind": "victimkind", "sutra": live}); D = jl(b)["id"]
s, b = api("qa-author", "POST", f"{P}/{D}/bind", {"file": "live-victim.v1.sutra.yaml"}); log("bind live file", "bind", "200", s)
tampered = live.replace("approved and live", "TAMPERED BY BINDING")
api("qa-author", "PATCH", f"{P}/{D}", {"sutra": tampered})
s, b = api("qa-author", "POST", f"{P}/{D}/save-file"); log("save-file over live v1", "POST save-file (review on, four-eyes on)", "refused / proposal", f"{s} {b[:60]}")
time.sleep(3)
s, b = api("qa-viewer", "GET", "/api/v1/sutras/live-victim/1/source"); log("live v1 source after", "GET source", "unchanged unless approved", "TAMPERED" if "TAMPERED" in b else "unchanged", "FAIL (live Sutra changed without review)" if "TAMPERED" in b else "PASS")
# brand new Sutra via a new file name
newt = "rachana: 1\nsutra: sneaky-new\nversion: 1\ndescription: never reviewed\nmatch: { kind: victimkind, priority: 99 }\ntitle: { id: $.id }\npanels: []\n"
s, b = api("qa-author", "POST", P, {"name": "sneaky", "kind": "victimkind", "sutra": newt}); D2 = jl(b)["id"]
api("qa-author", "POST", f"{P}/{D2}/bind", {"file": "sneaky-new.v1.sutra.yaml"}); s, b = api("qa-author", "POST", f"{P}/{D2}/save-file"); time.sleep(3)
s, b = api("qa-viewer", "GET", "/api/v1/sutras"); has = "sneaky-new" in b
log("new Sutra via save-file", "bind sneaky-new.v1.sutra.yaml + save-file", "not live without approval", f"{s} live={has}", "FAIL (live without review)" if has else "PASS")
# does a non-approver non-admin author with binding on also bypass four-eyes (author2 alone)? yes same path.
# and the claim by non-author: viewer cannot
s, b = api("qa-viewer", "POST", P, {"name": "v", "kind": "victimkind", "sutra": newt}); Dv = jl(b)["id"]
s, b = api("qa-viewer", "POST", f"{P}/{Dv}/bind", {"file": "viewer-new.v1.sutra.yaml"}); log("viewer bind", "bind", "403", s, "PASS" if s == 403 else "FAIL")
