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
qa.ROLES.update({"qa-viewer":["viewer"],"qa-admin":["admin"]})
nid="NS-ALDERSHOT-FRA"
# raw full, viewer vs admin
for u in ("qa-viewer","qa-admin"):
    st,h,b=qa.as_user(u,"GET","/api/v1/entities/netting-set/"+nid+"/raw")
    d=(qa.jl(b) or {}).get("data",{})
    mt0=(d.get("memberTrades") or [{}])[0]
    print(u,"raw: top mtm=",d.get("mtm")," memberTrade[0].mtm=",mt0.get("mtm"),"keys=",list(mt0.keys())[:12])
# search values for mtm as viewer (search applies redact)
st,h,b=qa.as_user("qa-viewer","GET","/api/v1/search?q="+qa.urllib.parse.quote("netting-set")+"&limit=2")
rows=(qa.jl(b) or {}).get("rows",[])
print("search viewer netting-set row0 values:", rows[0]["values"] if rows else None)
# search trade mtm as viewer
st,h,b=qa.as_user("qa-viewer","GET","/api/v1/search?q="+qa.urllib.parse.quote("trade")+"&limit=2")
rows=(qa.jl(b) or {}).get("rows",[])
print("search viewer trade row0 values:", rows[0]["values"] if rows else None)
