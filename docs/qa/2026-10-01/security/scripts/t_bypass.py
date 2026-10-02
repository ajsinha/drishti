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

import qa
# No token at all. Baseline: /api/v1/packs should be 401.
print("baseline anon /api/v1/packs:", qa.call("GET", qa.SRV+"/api/v1/packs")[0], "(expect 401)")
# Bypass vectors (raw URI does not literally start with /api/v1/ but Spring routes it)
vectors = [
  "/api/%761/packs",        # %76 = 'v' -> decodes to /api/v1/packs
  "/api/v1%2Fpacks",        # encoded slash
  "/api/v1/../v1/packs",    # dot-segment
  "/api/./v1/packs",
  "/api/v1;x/packs",        # matrix param on segment
  "/./api/v1/packs",
]
for v in vectors:
    st,h,b=qa.call("GET", qa.SRV+v)
    leak = st==200
    print(f"anon {v}: {st} {'<<< BYPASS (data w/o token)' if leak else ''} {b[:80] if leak else ''}")
# Also try an identity/admin endpoint via bypass
for v in ["/api/%761/admin/status", "/api/v1;x/admin/status"]:
    st,h,b=qa.call("GET", qa.SRV+v)
    print(f"anon admin via {v}: {st} {b[:80]}")
