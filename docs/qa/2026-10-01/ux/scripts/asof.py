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

import re, urllib.request, http.cookiejar, urllib.parse
B = "http://localhost:17985"
for d in ["garbage", "9999-99-99", "2030-01-01", "1900-01-01", "2026-09-27", "2026-09-26", "2026-09-29'<script>"]:
    cj = http.cookiejar.CookieJar(); op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
    op.open(B + "/asof?d=" + urllib.parse.quote(d) + "&next=/t").read()
    ck = [c.value for c in cj if c.name == "drishti_asof"]
    out = []
    for path in ["/v/trade/MX-20000002", "/t", "/s?q=TRD%20MX-2000001"]:
        try:
            r = op.open(B + path); st, t = r.status, r.read().decode()
        except urllib.error.HTTPError as e:
            st, t = e.code, e.read().decode()
        txt = re.sub(r"<[^>]+>", " ", t); txt = re.sub(r"\s+", " ", txt)
        m = re.findall(r"DRS-\d{4}[^|]{0,140}|not a dated[^.]{0,60}|as of 20\d\d-\d\d-\d\d", txt)
        out.append("%s %s %s" % (path, st, m[:2]))
    print("d=%r cookie=%r\n   %s" % (d, ck, "\n   ".join(out)))
