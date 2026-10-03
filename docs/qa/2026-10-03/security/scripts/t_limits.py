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


"""Limits: samples count/size/depth/total on /builder/shape, /design, designs/samples; designs per user; user quota; error codes (DRS-5003/5005)."""
from sec import *
import time
P = "/api/v1/builder/designs"; U = "qa-viewer"
def deep(n): return json.loads('{"a":' * n + "1" + "}" * n) if n < 900 else None
def t(label, m, path, body=None, raw=None, ctype="application/json", expect="", user=U):
    t0 = time.time(); tok = mint(user, ROLES[user]); s, h, b = call(m, SRV + path, tok, body, raw=raw, ctype=ctype, timeout=120)
    log(f"limits {label}", f"{m} {path} ({len(raw) if raw else len(json.dumps(body)) if body is not None else 0} B)", expect, f"{s} {time.time()-t0:.1f}s {b[:130]}", "")
    return s, b
s, b = api(U, "POST", P, {"name": "lim", "kind": "k"}); D = jl(b)["id"]
t("51 samples", "POST", f"{P}/{D}/samples", {"samples": [{"name": f"s{i}", "document": {"a": i}} for i in range(51)]}, expect="400/413 DRS-5003/5005")
t("50 samples", "POST", f"{P}/{D}/samples", {"samples": [{"name": f"s{i}", "document": {"a": i}} for i in range(50)]}, expect="200")
t("51st via second call", "POST", f"{P}/{D}/samples", {"samples": [{"name": "extra", "document": {"a": 1}}]}, expect="refused DRS-5005")
s, b = api(U, "POST", P, {"name": "lim2", "kind": "k"}); D2 = jl(b)["id"]
t("6MB document", "POST", f"{P}/{D2}/samples", {"samples": [{"name": "big", "document": {"x": "A" * (6 * 1024 * 1024)}}]}, expect="413 DRS-5003 (max-file-mb 5)")
t("depth 64", "POST", f"{P}/{D2}/samples", {"samples": [{"name": "d64", "document": deep(55)}]}, expect="200")
t("depth 70 sample", "POST", f"{P}/{D2}/samples", raw=('{"samples":[{"name":"d70","document":' + '{"a":' * 70 + "1" + "}" * 70 + "}]}").encode(), expect="refused depth")
t("depth 100000 body", "POST", f"{P}/{D2}/samples", raw=('{"samples":[{"name":"d","document":' + "[" * 100000 + "]" * 100000 + "}]}").encode(), expect="400/413 no 500/stack overflow")
t("26MB body", "POST", f"{P}/{D2}/samples", raw=b'{"samples":[{"name":"x","document":{"x":"' + b"A" * (26 * 1024 * 1024) + b'"}}]}', expect="413")
t("body is array", "POST", f"{P}/{D2}/samples", raw=b"[1,2,3]", expect="400")
t("body is text/plain JSON", "POST", f"{P}/{D2}/samples", raw=b'{"samples":[{"name":"t","document":{"a":1}}]}', ctype="text/plain", expect="415 (consumes json)")
t("body form-encoded", "POST", f"{P}/{D2}/samples", raw=b"samples=1", ctype="application/x-www-form-urlencoded", expect="415")
t("NaN/duplicate keys", "POST", f"{P}/{D2}/samples", raw=b'{"samples":[{"name":"n","document":{"a":NaN,"a":1}}]}', expect="400 or accepted harmlessly")
t("sample name 10KB", "POST", f"{P}/{D2}/samples", {"samples": [{"name": "n" * 10000, "document": {"a": 1}}]}, expect="refused or fine")
t("sample name with ../ and nul", "POST", f"{P}/{D2}/samples", {"samples": [{"name": "../../../etc/passwd\u0000", "document": {"a": 1}}]}, expect="200 (names hashed on disk)")
t("refs count 10^9", "POST", f"{P}/{D2}/samples", {"refs": {"kind": "trade", "count": 1000000000}}, expect="clamped")
t("refs kind with path", "POST", f"{P}/{D2}/samples", {"refs": {"kind": "../x", "ids": ["a"]}}, expect="400")
t("schema count 10^9", "POST", f"{P}/{D2}/samples", {"schema": {"type": "object", "properties": {"a": {"type": "string"}}}, "count": 1000000000}, expect="clamped to 50")
t("schema recursive $ref", "POST", f"{P}/{D2}/samples", {"schema": {"$ref": "#/$defs/n", "$defs": {"n": {"type": "object", "properties": {"c": {"$ref": "#/$defs/n"}}, "required": ["c"]}}}, "count": 3}, expect="bounded, no stack overflow")
t("schema huge minItems", "POST", f"{P}/{D2}/samples", {"schema": {"type": "array", "minItems": 2000000000, "items": {"type": "string"}}, "count": 3}, expect="bounded (no OOM)")
t("schema pattern ReDoS-ish", "POST", f"{P}/{D2}/samples", {"schema": {"type": "string", "pattern": "^(a+)+$", "minLength": 5000}, "count": 3}, expect="bounded")
t("schema huge string len", "POST", f"{P}/{D2}/samples", {"schema": {"type": "object", "properties": {"s": {"type": "string", "minLength": 2000000000}}, "required": ["s"]}, "count": 3}, expect="bounded (no OOM)")
print("alive:", api(U, "GET", P)[0])
# /builder/shape limits (authors?) 
t("builder/shape 51 docs", "POST", "/api/v1/builder/shape", {"samples": [{"name": f"s{i}", "document": {"a": i}} for i in range(51)]}, expect="DRS-5003", user="qa-author")
t("builder/shape depth 70", "POST", "/api/v1/builder/shape", raw=('{"samples":[{"name":"d","document":' + '{"a":' * 70 + "1" + "}" * 70 + "}]}").encode(), expect="DRS-5003", user="qa-author")
# designs per user
made = []
for i in range(55):
    s, b = api("qa-masked", "POST", P, {"name": f"d{i}", "kind": "k"}); made.append((s, jl(b).get("id") if s == 201 else b[:100]))
log("limits designs per user", "55 x POST designs as qa-masked", "stops at 50 with DRS-5005", f"201 x{sum(1 for m in made if m[0]==201)}; first refusal: {next((m for m in made if m[0]!=201), None)}", "")
for s, i in made:
    if s == 201: api("qa-masked", "DELETE", f"{P}/{i}")
