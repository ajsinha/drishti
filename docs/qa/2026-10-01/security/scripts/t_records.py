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
admin=qa.mint("qa-admin",["admin"])
# custom role def
st,h,b=qa.call("GET", qa.SRV+"/api/v1/admin/role-definitions/qa-masked", admin)
print("qa-masked role:", st, b)
# find a trade via search as admin
st,h,b=qa.call("GET", qa.SRV+"/api/v1/search?q="+qa.urllib.parse.quote("trade"), admin)
print("search trade:", st, b[:500])
