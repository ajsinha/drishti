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
qa.ROLES.update({"qa-admin":["admin"],"qa-author":["author"],"qa-author2":["author"],"qa-approver":["approver"]})
def who(u): return qa.mint(u, qa.ROLES[u])
print("studio/settings author:", qa.call("GET", qa.SRV+"/api/v1/studio/settings", who("qa-author"))[2][:200])
# get an existing sutra source to resubmit (name/version from registry)
st,h,b=qa.call("GET", qa.SRV+"/api/v1/rachana/sutras", who("qa-admin"))
print("sutras list status", st, b[:150])
sl=qa.jl(b)
# find netting-set sutra source
src=None; name=None
st,h,b=qa.call("GET", qa.SRV+"/api/v1/sutras/netting-set/1/source", who("qa-admin"))
print("source netting-set@1:", st, len(b))
if st==200:
    src=b; name="netting-set"
if src:
    src = src.replace("with exposure and collateral.", "with exposure and collateral. QA"+str(__import__('time').time())[:10])
    # propose as qa-approver (has author+approve)
    st,h,b=qa.call("POST", qa.SRV+"/api/v1/sutras?note=qa-four-eyes-test", who("qa-approver"), src, ctype="text/yaml")
    print("approver proposes:", st, b[:200])
    jj=qa.jl(b) or {}
    pid=(jj.get("proposal") or {}).get("id") or jj.get("id")
    print("proposal id:", pid)
    if pid:
        # qa-approver tries to approve their OWN -> expect four-eyes block
        st,h,b=qa.call("POST", qa.SRV+f"/api/v1/sutras/proposals/{pid}/approve", who("qa-approver"), {"comment":"self"})
        print("SELF-approve by approver:", st, (qa.jl(b) or {}).get("code"), (qa.jl(b) or {}).get("detail","")[:80])
        # admin tries to approve their own? first admin proposes
        src2=src.replace("collateral.", "collateral..")
        st2,h2,b2=qa.call("POST", qa.SRV+"/api/v1/sutras?note=admin-self", who("qa-admin"), src2, ctype="text/yaml")
        jj2=qa.jl(b2) or {}; apid=(jj2.get("proposal") or {}).get("id") or jj2.get("id")
        if apid:
            st,h,b=qa.call("POST", qa.SRV+f"/api/v1/sutras/proposals/{apid}/approve", who("qa-admin"), {"comment":"self"})
            print("SELF-approve by ADMIN:", st, (qa.jl(b) or {}).get("code"), (qa.jl(b) or {}).get("detail","")[:80])
        # author (no approve power) approves someone else's -> expect forbidden
        st,h,b=qa.call("POST", qa.SRV+f"/api/v1/sutras/proposals/{pid}/approve", who("qa-author"), {"comment":"x"})
        print("author approves (no approve power):", st, (qa.jl(b) or {}).get("code"))
        # proper: admin approves the approver's proposal (different author) -> expect 200
        st,h,b=qa.call("POST", qa.SRV+f"/api/v1/sutras/proposals/{pid}/approve", who("qa-admin"), {"comment":"ok"})
        print("admin approves approver's proposal:", st, (qa.jl(b) or {}).get("status") or (qa.jl(b) or {}).get("code"))
        # cleanup: withdraw admin's own if still pending
        if apid: qa.call("POST", qa.SRV+f"/api/v1/sutras/proposals/{apid}/withdraw", who("qa-admin"))
