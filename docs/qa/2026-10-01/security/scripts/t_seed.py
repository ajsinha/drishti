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
svc=qa.mint("console",["service"])
# the documented default seed admin creds
st,h,b=qa.call("POST", qa.SRV+"/api/v1/auth/login", svc, {"username":"drishti-dev-admin","password":"drishti-dev-admin123"})
print("seed admin login (security ON):", st, (qa.jl(b) or {}).get("roles"), "expect 200 => known-cred admin is live")
