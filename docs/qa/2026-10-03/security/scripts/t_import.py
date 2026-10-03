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


"""Pack import (POST /builder/designs/import): zip-slip names, absolute paths, bombs, duplicates, hostile YAML/JSON inside."""
from sec import *
import io, zipfile, os, time
P = "/api/v1/builder/designs"; W = open(".workdir").read().strip()
GOOD = "rachana: 1\nsutra: imp{n}\nversion: 1\ndescription: x\nmatch: {{ kind: impkind, priority: 1 }}\ntitle: {{ id: $.id }}\npanels: []\n"
def zipof(entries, method=zipfile.ZIP_DEFLATED):
    bio = io.BytesIO()
    with zipfile.ZipFile(bio, "w", method) as z:
        for name, data in entries:
            z.writestr(name, data)
    return bio.getvalue()
def imp(u, data, label, expect, ctype="application/zip"):
    t0 = time.time(); s, h, b = call("POST", SRV + P + "/import", mint(u, ROLES[u]), raw=data, ctype=ctype, timeout=120)
    el = time.time() - t0
    log(f"import {label}", f"POST import ({len(data)} B) as {u}", expect, f"{s} {el:.1f}s {b[:110]}", "")
    return s, b
U = "qa-viewer"   # any signed-in user may import (designs are open)
s, b = imp(U, zipof([("p/sutras/a.v1.sutra.yaml", GOOD.format(n=1))]), "baseline", "201")
# zip-slip & absolute names: nothing is written to disk, but check no file appears and entries skipped
canary = [W + "/pwned.txt", "/tmp/pwned_zipslip.txt"]
s, b = imp(U, zipof([("../../../../../../tmp/pwned_zipslip.txt", "x"), ("/tmp/pwned_zipslip.txt", "x"), ("..\\..\\pwned.txt", "x"), ("p/sutras/../../../pwned.txt", "x"), ("p/sutras/b.v1.sutra.yaml", GOOD.format(n=2)), ("../evil.v1.sutra.yaml", GOOD.format(n=3)), ("/abs.v1.sutra.yaml", GOOD.format(n=4))]), "slip names", "201, only b imported")
d = jl(b) or {}; log("import slip result", "designs made", "only b", [x.get('name') for x in d.get('designs', [])] if d else b[:100], "PASS" if [x.get('name') for x in d.get('designs', [])] == ['imp2'] else "CHECK")
log("import slip files", "canary files", "absent", [os.path.exists(c) for c in canary], "PASS" if not any(os.path.exists(c) for c in canary) else "FAIL")
# bomb: 30 MB of zeros in a tiny zip (limit 25 MB unpacked)
big = zipof([("p/sutras/a.v1.sutra.yaml", GOOD.format(n=5)), ("p/samples/impkind/big.json", b"[" + b"0," * (15 * 1024 * 1024) + b"0]")])
s, b = imp(U, big, "zip bomb 30MB unpacked", "413")
# 4GB-claim bomb: highly compressible 200 MB
bomb = zipof([("p/zeros.md", b"\0" * (200 * 1024 * 1024))]); print("bomb zip size", len(bomb))
s, b = imp(U, bomb, "200MB zeros", "413 fast, no OOM")
# many entries
many = zipof([(f"p/f{i}.md", "x") for i in range(20000)]); s, b = imp(U, many, "20000 entries", "413 over entry cap")
# duplicates
s, b = imp(U, zipof([("a.v1.sutra.yaml", GOOD.format(n=6)), ("a.v1.sutra.yaml", GOOD.format(n=7))]), "duplicate entry names", "201 (two designs?) or refuse")
# not a zip, empty, truncated
s, b = imp(U, b"not a zip", "garbage", "400"); s, b = imp(U, b"", "empty", "400"); s, b = imp(U, zipof([("a.v1.sutra.yaml", GOOD.format(n=8))])[:60], "truncated", "400")
s, b = imp(U, zipof([("a.v1.sutra.yaml", GOOD.format(n=8))]), "wrong content type json", "415", ctype="application/json")
# encrypted / zip64 / unsupported method
s, b = imp(U, zipof([("a.v1.sutra.yaml", GOOD.format(n=9))], zipfile.ZIP_STORED), "stored", "201")
# hostile YAML inside
laugh = "rachana: 1\nsutra: laugh\nversion: 1\nmatch: { kind: impkind }\ntitle: { id: $.id }\npanels: []\n" + "a0: &a0 [x,x,x,x,x,x,x,x,x]\n" + "".join(f"a{i}: &a{i} [*a{i-1},*a{i-1},*a{i-1},*a{i-1},*a{i-1},*a{i-1},*a{i-1},*a{i-1},*a{i-1}]\n" for i in range(1, 10))
deep = "rachana: 1\nsutra: deep\nversion: 1\nmatch: { kind: impkind }\ntitle: { id: $.id }\npanels: []\nx: " + "[" * 5000 + "]" * 5000 + "\n"
deepmap = "rachana: 1\nsutra: deepm\nversion: 1\nmatch: { kind: impkind }\ntitle: { id: $.id }\npanels: []\n" + "".join(" " * i + "k:\n" for i in range(0, 3000))
javatag = "rachana: 1\nsutra: jt\nversion: 1\nmatch: { kind: impkind }\ntitle: { id: $.id }\npanels: []\nx: !!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[!!java.net.URL [\"http://127.0.0.1:9/x\"]]]]\n"
javatag2 = "rachana: 1\nsutra: jt2\nversion: 1\nmatch: { kind: impkind }\ntitle: { id: $.id }\npanels: []\nx: !!java.lang.ProcessBuilder [[\"touch\", \"/tmp/pwned_javatag\"]]\n"
for label, y in [("billion laughs", laugh), ("deep flow nesting 5000", deep), ("deep block map 3000", deepmap), ("!!java tag URLClassLoader", javatag), ("!!java ProcessBuilder", javatag2)]:
    s, b = imp(U, zipof([("a.v1.sutra.yaml", y)]), "yaml " + label, "201 imported-as-invalid or 4xx, no hang/500/exec")
log("import java tag canary", "/tmp/pwned_javatag", "absent", os.path.exists("/tmp/pwned_javatag"), "PASS" if not os.path.exists("/tmp/pwned_javatag") else "FAIL")
# hostile JSON sample inside (depth 100000, huge number, many keys)
s, b = imp(U, zipof([("p/sutras/a.v1.sutra.yaml", GOOD.format(n=11)), ("p/tests/imp11/deep.json", "[" * 100000 + "]" * 100000), ("p/tests/imp11/ok.json", '{"id":"A"}'), ("p/tests/imp11/depth70.json", '{"a":' * 70 + "1" + "}" * 70)]), "json samples deep", "skipped, not 500")
print(b[:600])
