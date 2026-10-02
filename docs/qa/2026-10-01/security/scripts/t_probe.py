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
print("about:", qa.call("GET", qa.SRV+"/public/about")[0])
tok=qa.mint("qa-admin",["admin"])
st,h,b=qa.call("GET", qa.SRV+"/api/v1/admin/status", tok)
print("admin/status:", st, b[:200])
st,h,b=qa.call("GET", qa.SRV+"/api/v1/admin/users", tok)
print("users:", st, b[:500])
st,h,b=qa.call("GET", qa.SRV+"/api/v1/admin/role-definitions", tok)
print("roles:", st, b[:800])
