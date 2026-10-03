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


"""Console upload limits: a body sent chunked (no Content-Length) skips the length pre-check; is it refused, and how much does the console buffer?"""
import http.client, json, os, sys, time
from t_console import *
S = json.load(open("console_sessions.json")); fx = json.load(open("fixtures.json"))
pid = int(open(os.path.join(W, "console.pid")).read())
def rss(): return int(open(f"/proc/{pid}/status").read().split("VmRSS:")[1].split()[0]) // 1024
def chunked(path, ctype, total_mb, ck):
    c = http.client.HTTPConnection("127.0.0.1", 17961, timeout=120)
    c.putrequest("POST", path); c.putheader("Cookie", ck); c.putheader("Origin", CON); c.putheader("Content-Type", ctype); c.putheader("Transfer-Encoding", "chunked"); c.endheaders()
    blk = b"A" * (1 << 20); peak = rss()
    try:
        for i in range(total_mb):
            c.send(f"{len(blk):x}\r\n".encode() + blk + b"\r\n")
            if i % 10 == 0: peak = max(peak, rss())
        c.send(b"0\r\n\r\n"); peak = max(peak, rss())
        r = c.getresponse(); body = r.read(300).decode("utf8", "replace"); return r.status, body, peak
    except Exception as e:
        return 0, repr(e)[:100], peak
before = rss()
# own design for files route
st, h, b = creq(S["qa-author2"], "POST", "/build/designs", json.dumps({"name": "upl"}), headers={"Origin": CON}, ctype="application/json"); D = jl(b)["id"]
for label, path, ct in [("files (json)", f"/build/designs/{D}/files", "application/json"), ("import (zip)", "/build/import", "application/zip"), ("preview-file", f"/build/designs/{D}/preview-file", "application/json")]:
    st, body, peak = chunked(path, ct, 60, S["qa-author2"])
    log(f"console upload chunked 60MB {label}", f"POST {path.replace(D,'<D>')} chunked", "413 early, bounded buffering", f"{st} {body[:90]} console RSS {before}MB -> peak {peak}MB, after {rss()}MB", "")
# declared length over the limit
try:
    st, h, b = creq(S["qa-author2"], "POST", f"/build/designs/{D}/files", b"x" * (60 * 1024 * 1024), headers={"Origin": CON}, ctype="application/json"); log("console upload 60MB with Content-Length", "POST files", "413", f"{st} {b[:90]}", "PASS" if st == 413 else "CHECK")
except Exception as e:
    log("console upload 60MB with Content-Length", "POST files", "413", f"connection reset by the console ({e!r:.60})", "PASS (refused, no JSON problem)")
creq(S["qa-author2"], "DELETE", f"/build/designs/{D}", headers={"Origin": CON})
