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
qa.ROLES.update({"qa-viewer":["viewer"]})
v=qa.mint("qa-viewer",["viewer"])
for text in ["trade counterparty ","trade trader ","trade where ","trade ","netting-set "]:
    st,h,b=qa.call("GET", qa.SRV+"/api/v1/phrase?text="+qa.urllib.parse.quote(text), v)
    d=qa.jl(b) or {}
    # dump any field suggestions carrying values, look for masked field names
    s=json.dumps(d)
    masked=[f for f in ("trader","counterparty","counterpartyId","mtm","patientName") if f in s]
    print(f"text={text!r} st={st} masked_names_in_response={masked}")
    if masked:
        print("   ", s[:800])
