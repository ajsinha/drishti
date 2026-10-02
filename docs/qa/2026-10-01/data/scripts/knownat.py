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

"""knownat.py <port>: raw read and search of MX-30000006 on 2026-09-30 as known at several instants (Delta time travel)."""
import json, sys, urllib.request, urllib.parse

port = sys.argv[1]
B = f"http://localhost:{port}/api/v1"
cases = [("2026-10-02T00:40:00Z", "nothing yet"), ("2026-10-02T00:40:11.700Z", "after v1 (09-30 not yet written?)"),
         ("2026-10-02T00:40:12Z", "after v2: original -342639050"), ("2026-10-02T00:59:15.600Z", "v3: -342639049"),
         ("2026-10-02T00:59:51.000Z", "v4: -342639049"), ("2026-10-02T00:59:52.500Z", "v5: -342639050"),
         ("2026-10-02T00:59:54.000Z", "v6: -342639049"), ("2026-10-02T01:00:33Z", "v33 latest: -342639050"), (None, "latest")]
for ka, what in cases:
    qs = "asOf=2026-09-30" + (f"&knownAt={urllib.parse.quote(ka)}" if ka else "")
    try:
        d = json.load(urllib.request.urlopen(f"{B}/entities/trade/MX-30000006/raw?{qs}", timeout=60))
        raw = (d["data"].get("mtm"), d["provenance"].get("businessDate"), d["provenance"].get("generation"))
    except urllib.error.HTTPError as e:
        raw = (e.code, json.loads(e.read()).get("code"))
    try:
        s = json.load(urllib.request.urlopen(f"{B}/search?q={urllib.parse.quote('TRD where mtm < -342000000')}&{qs}", timeout=60))
        srch = (s.get("matched"), s.get("scanned"), s.get("partial"), [r["values"].get("$.mtm") for r in s.get("rows", [])][:2])
    except urllib.error.HTTPError as e:
        srch = (e.code, e.read()[:120])
    print(f"knownAt={ka} [{what}] raw={raw} search={srch}")
