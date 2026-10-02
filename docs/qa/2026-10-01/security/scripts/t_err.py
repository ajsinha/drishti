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
v=qa.mint("qa-viewer",["viewer"])
admin=qa.mint("qa-admin",["admin"])
# public about (unauth)
print("GET /public/about:", qa.call("GET", qa.SRV+"/public/about")[2][:300])
# malformed JSON to a POST
st,h,b=qa.call("POST", qa.SRV+"/api/v1/command", v, "{not json", ctype="application/json")
print("malformed json:", st, b[:200])
# bad entity -> 404/500?
st,h,b=qa.call("GET", qa.SRV+"/api/v1/views/trade/..%2F..%2Fetc%2Fpasswd", v)
print("path traversal id:", st, b[:160])
# search with broken EL condition
st,h,b=qa.call("GET", qa.SRV+"/api/v1/search?q="+qa.urllib.parse.quote("trade where ((("), v)
print("broken EL:", st, b[:160])
# type mismatch to trigger 500
st,h,b=qa.call("POST", qa.SRV+"/api/v1/admin/users", admin, {"username":123,"roles":"notalist"})
print("bad types create user:", st, b[:200])
# does any error echo the token secret or a stack trace?
for label,resp in []:
    pass
