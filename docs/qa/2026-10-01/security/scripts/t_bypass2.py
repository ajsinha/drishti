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
for v in [
  "/api/%761/sources",
  "/api/%761/business-date",
  "/api/%761/sutras",
  "/api/%761/rachana/schema",
  "/api/%761/views/trade/BBG-60000001",   # needs principal -> should fail closed
  "/api/%761/search?q=trade",             # needs principal -> fail closed
  "/api/%761/entities/trade/BBG-60000001/raw",
]:
    st,h,b=qa.call("GET", qa.SRV+v)
    print(f"anon {v}: {st} {b[:90]}")
