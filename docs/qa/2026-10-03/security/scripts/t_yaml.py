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


"""Hostile YAML in the Sutra of a design: billion laughs, deep nesting, !!java tags through PATCH, ops, preview, check, autodesign, propose, /studio/preview, /builder/edit."""
from sec import *
import time, os
P = "/api/v1/builder/designs"; U = "qa-author"
H = "rachana: 1\nsutra: hostile\nversion: 1\ndescription: x\nmatch: { kind: hk, priority: 1 }\ntitle: { id: $.id }\npanels: []\n"
laugh = H + "a0: &a0 [x,x,x,x,x,x,x,x,x]\n" + "".join(f"a{i}: &a{i} [" + ",".join([f"*a{i-1}"] * 9) + "]\n" for i in range(1, 12))
deepflow = H + "x: " + "[" * 20000 + "]" * 20000 + "\n"
deepblock = H + "".join(" " * i + "k:\n" for i in range(0, 4000))
javatag = H + "x: !!java.lang.ProcessBuilder [[\"touch\", \"/tmp/pwned_javatag2\"]]\n"
javatag2 = H + "x: !!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[!!java.net.URL [\"http://127.0.0.1:9/x\"]]]]\n"
bigscalar = H + "x: " + "A" * (20 * 1024 * 1024) + "\n"
alias_text = H + "x: &x " + "B" * 1000 + "\n" + "".join(f"y{i}: *x\n" for i in range(5000))
def alive():
    t = time.time(); s, b = api(U, "GET", P); return f"{s} {time.time()-t:.1f}s"
for label, y in [("billion laughs", laugh), ("deep flow 20000", deepflow), ("deep block 4000", deepblock), ("!!java ProcessBuilder", javatag), ("!!java URLClassLoader", javatag2), ("20MB scalar", bigscalar), ("5000 aliases of a string", alias_text)]:
    s, b = api(U, "POST", P, {"name": "h", "kind": "hk"}); D = jl(b)["id"]
    api(U, "POST", f"{P}/{D}/samples", {"samples": [{"name": "s", "document": {"id": "1"}}]})
    t = time.time(); s, b = api(U, "PATCH", f"{P}/{D}", {"sutra": y}); r = [f"PATCH {s} {time.time()-t:.1f}s"]
    for m, path, body in [("GET", f"{P}/{D}/preview", None), ("POST", f"{P}/{D}/check", None), ("POST", f"{P}/{D}/propose", {}), ("POST", f"{P}/{D}/autodesign", None), ("GET", f"{P}/{D}", None)]:
        t = time.time(); s, b = api(U, m, path, body); r.append(f"{path.split('/')[-1]} {s} {time.time()-t:.1f}s" + (" [stacktrace/path?]" if ("Exception" in b or "at com." in b or "/home/" in b or "/tmp/" in b) else ""))
    s, b = api(U, "POST", "/api/v1/studio/preview", {"yaml": y, "kind": "hk", "document": {"id": "1"}}); r.append(f"studio/preview {s} {b[:60] if s>=500 else ''}")
    s, b = api(U, "POST", "/api/v1/sutras", y, ctype="text/yaml"); r.append(f"POST /sutras {s}")
    log(f"hostile yaml: {label}", "PATCH/preview/check/propose/autodesign/studio-preview/save", "4xx/422, no 500, no hang, no exec", "; ".join(r) + " | alive " + alive(), "")
    api(U, "DELETE", f"{P}/{D}")
log("java tag canary", "/tmp/pwned_javatag2", "absent", os.path.exists("/tmp/pwned_javatag2"), "PASS" if not os.path.exists("/tmp/pwned_javatag2") else "FAIL")
