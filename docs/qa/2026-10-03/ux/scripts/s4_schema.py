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

from wb import *
import json
p, b, ctx, pg = start(); 
r = pg.request.get(BASE + "/studio/schema"); print(r.status); d = r.json(); json.dump(d, open("/tmp/claude-1000/-home-ashutosh-IdeaProjects-drishti/efa68c11-fb98-4f9a-8faa-96a952090c0e/scratchpad/uxw/schema.json","w"))
print(list(d.keys())[:20])
for k,v in d.items():
    print(k, type(v).__name__, (list(v.keys())[:30] if isinstance(v, dict) else str(v)[:100]))
b.close()
