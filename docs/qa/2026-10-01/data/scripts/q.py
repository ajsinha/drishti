#!/usr/bin/env python3
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

"""q.py <port> <search text> [asOf]  -> prints matched/scanned/partial and first ids/values compactly
   q.py <port> raw <kind> <id> [asOf] -> prints provenance and selected fields of a raw read"""
import json, sys, urllib.parse, urllib.request


def get(url):
    try:
        with urllib.request.urlopen(url, timeout=120) as r:
            return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        try:
            return e.code, json.loads(body)
        except ValueError:
            return e.code, body[:300]


port = sys.argv[1]
B = f"http://localhost:{port}/api/v1"
if sys.argv[2] == "raw":
    kind, id_ = sys.argv[3], sys.argv[4]
    asof = sys.argv[5] if len(sys.argv) > 5 else None
    url = f"{B}/entities/{kind}/{urllib.parse.quote(id_, safe='')}/raw" + (f"?asOf={asof}" if asof else "")
    st, d = get(url)
    if isinstance(d, dict) and "data" in d:
        data = d["data"]
        print(st, d.get("provenance", {}).get("businessDate"), d.get("provenance", {}).get("source"),
              {k: data.get(k) for k in ("tradeId", "mtm", "book")} if isinstance(data, dict) else str(data)[:100], "len", len(json.dumps(data)))
    else:
        print(st, str(d)[:400])
    sys.exit()
q = sys.argv[2]
asof = sys.argv[3] if len(sys.argv) > 3 else None
st, d = get(f"{B}/search?q={urllib.parse.quote(q)}" + (f"&asOf={asof}" if asof else ""))
if st != 200:
    print(st, str(d)[:400])
    sys.exit()
rows = d.get("rows", [])
print(st, "matched", d.get("matched"), "scanned", d.get("scanned"), "partial", d.get("partial"), "ms", d.get("elapsedMs"))
for r in rows[:int(sys.argv[4]) if len(sys.argv) > 4 else 6]:
    print("   ", r["ref"]["id"][:60], {k: v for k, v in r["values"].items() if k in ("$.mtm", "$.book", "$.notional")})
