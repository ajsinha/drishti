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

import qa, time
qa.ROLES.update({"qa-viewer":["viewer"]})
v=qa.mint("qa-viewer",["viewer"])
def preview(yaml, label, timeout=15):
    t=time.time()
    st,h,b=qa.call("POST", qa.SRV+"/api/v1/studio/preview", v, {"kind":"trade","id":"BBG-60000001","yaml":yaml}, timeout=timeout)
    print(f"{label}: st={st} {round(time.time()-t,2)}s body={b[:120]}")
# 1 billion laughs
lol='a0: &a0 "x"\n'
prev='a0'
lines=[]
for i in range(1,12):
    lines.append(f'a{i}: &a{i} [*a{i-1},*a{i-1},*a{i-1},*a{i-1},*a{i-1},*a{i-1},*a{i-1},*a{i-1},*a{i-1},*a{i-1}]')
bomb="a0: &a0 \"lol\"\n"+"\n".join(lines)+"\nboom: *a11\n"
preview(bomb, "billion-laughs(10^11 aliases)")
# 2 deep nesting (flow) -> recursion
deep="x: "+("["*5000)+("]"*5000)
preview(deep, "deep-nest-5000")
# 3 huge scalar
preview("sutra: "+("A"*5_000_000), "huge-5MB-scalar")
# 4 valid baseline
preview("rachana: 1\nsutra: x\nversion: 1\nmatch: {kind: trade}\ntitle: {pill: X, id: $.id}\n", "valid-baseline")
