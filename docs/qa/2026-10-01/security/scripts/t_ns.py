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

import qa, json
qa.ROLES.update({"qa-viewer":["viewer"],"qa-admin":["admin"],"qa-masked":["qa-masked"]})
admin=qa.mint("qa-admin",["admin"])
st,h,b=qa.call("GET", qa.SRV+"/api/v1/search?q="+qa.urllib.parse.quote("netting-set"), admin)
d=qa.jl(b) or {}
rows=d.get("rows",[])
print("netting-set count:", len(rows))
if rows:
    nid=rows[0]["ref"]["id"]
    print("id:", nid)
    st,h,b=qa.as_user("qa-viewer","GET","/api/v1/entities/netting-set/"+nid+"/raw")
    rd=qa.jl(b) or {}
    mts=(rd.get("data",{}) or {}).get("memberTrades",[])
    print("RAW viewer st",st,"first memberTrade mtm:", (mts[0].get("mtm") if mts else "NONE"))
    st,h,b=qa.as_user("qa-viewer","GET","/api/v1/views/netting-set/"+nid+"/panels/trades/records")
    print("RECORDS viewer st",st)
    print("  has_bullets:", "•••" in b, " 'mtm' present:", '"mtm"' in b)
    print("  BODY:", b[:700])
