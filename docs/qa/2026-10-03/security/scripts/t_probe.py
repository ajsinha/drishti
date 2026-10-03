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


from sec import *
import sys
for u in ["qa-author","qa-narrow","qa-masked","qa-viewer","qa-narrow-author"]:
    st,b=api(u,"GET","/api/v1/entities/trade/MX-20000001/raw"); print(u,"trade raw",st,b[:100].replace("\n"," "))
    st,b=api(u,"GET","/api/v1/entities/counterparty/CP-MERIDIAN-RE/raw"); print(u,"cp raw",st,b[:100].replace("\n"," "))
st,b=api("qa-author","GET","/api/v1/me"); print(st,b[:300])
