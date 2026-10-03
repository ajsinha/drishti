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

import sys; sys.path.insert(0, __file__.rsplit("/", 1)[0])
from pw import *
with sync_playwright() as p:
    b = p.chromium.launch(); ctx, pg = new_ctx(b, "admin"); print(pg.url)
    for name, role in (("author", "author"), ("appr", "approver")):
        u, pw = USERS[name]
        r = pg.request.post(BASE + "/admin/api/users", data={"username": u, "password": pw, "roles": [role], "displayName": u})
        print(u, r.status, r.text()[:200])
    b.close()
