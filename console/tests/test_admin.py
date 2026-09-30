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



def test_admins_see_and_purge_caches(client):
    page = client.get("/admin/caches").text
    assert "trading-stream" in page and 'data-purge="all"' in page and "diskMb" in page
    r = client.post("/admin/api/caches/trading-stream/purge")
    assert r.status_code == 200 and r.json()["purged"] == ["trading-stream"]
