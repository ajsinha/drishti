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
admin=qa.mint("qa-admin",["admin"])
# token for qa-trader as the console would mint it (roles from session)
trader=qa.mint("qa-trader",["trader"])
# baseline: trader can hit /auth/me
print("before disable /auth/me:", qa.call("GET", qa.SRV+"/api/v1/auth/me", trader)[0])
# disable qa-trader
st,h,b=qa.call("POST", qa.SRV+"/api/v1/admin/users/qa-trader/enabled", admin, {"enabled":False})
print("disable qa-trader:", st, b[:120])
# server re-check? call again with the SAME (pre-existing) token
st,h,b=qa.call("GET", qa.SRV+"/api/v1/auth/me", trader)
print("after disable, same token /auth/me:", st, "(401/403 would mean enforced; 200 = NOT enforced)")
# also a data read
st,h,b=qa.call("GET", qa.SRV+"/api/v1/search?q="+qa.urllib.parse.quote("trade")+"&limit=1", trader)
print("after disable, search:", st)
# re-enable to restore state
qa.call("POST", qa.SRV+"/api/v1/admin/users/qa-trader/enabled", admin, {"enabled":True})
print("re-enabled")
# role-change not enforced: remove 'trader' role then show old token still admin? no, show a stale token keeps old rights
