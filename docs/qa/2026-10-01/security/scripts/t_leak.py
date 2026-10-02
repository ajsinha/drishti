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
ID="trade/BBG-60000001"
# raw as viewer (should mask mtm/trader/counterparty)
st,h,b=qa.as_user("qa-viewer","GET","/api/v1/entities/"+ID+"/raw")
d=qa.jl(b) or {}
raw=d.get("data",{})
print("RAW viewer status",st,"mtm=",raw.get("mtm"),"trader=",raw.get("trader"),"cpty=",raw.get("counterparty"),"cptyId=",raw.get("counterpartyId"))
# view as viewer: list panels + records per panel
st,h,b=qa.as_user("qa-viewer","GET","/api/v1/views/"+ID)
v=qa.jl(b) or {}
panels=[(p.get("id"),p.get("code"),p.get("kind")) for p in v.get("panels",[])]
print("VIEW panels:",panels)
# records per panel as viewer -- check for unmasked masked fields
import re
for pid,code,knd in panels:
    st,h,b=qa.as_user("qa-viewer","GET","/api/v1/views/"+ID+"/panels/"+str(pid)+"/records")
    if st==200:
        hit=[f for f in ("mtm","trader","counterparty","counterpartyId","patientName") if f in b]
        masked = "•••" in b
        print(f"  records panel={pid} st={st} masked_field_names_present={hit} has_bullets={masked} len={len(b)}")
        if hit and not masked:
            print("    SAMPLE:", b[:400])
    else:
        print(f"  records panel={pid} st={st} body={b[:120]}")
