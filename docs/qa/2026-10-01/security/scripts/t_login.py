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
admin=qa.mint("qa-admin",["admin"])
svc=qa.mint("console",["service"])
# weak password rejected on create?
for pw in ["short","nodigitsonlyletters","1234567890"]:
    st,h,b=qa.call("POST", qa.SRV+"/api/v1/admin/users", admin, {"username":"qa-weak","password":pw,"roles":["viewer"]})
    print(f"create weak pw={pw!r}:", st, (qa.jl(b) or {}).get("code") or b[:80])
    qa.call("DELETE", qa.SRV+"/api/v1/admin/users/qa-weak", admin)
# create a lock-test user with strong pw
qa.call("DELETE", qa.SRV+"/api/v1/admin/users/qa-lock", admin)
st,h,b=qa.call("POST", qa.SRV+"/api/v1/admin/users", admin, {"username":"qa-lock","password":"Str0ngPass99","roles":["viewer"]})
print("create qa-lock:", st)
# login via server /auth/login (service identity)
def login(user,pw):
    return qa.call("POST", qa.SRV+"/api/v1/auth/login", svc, {"username":user,"password":pw})
print("correct login:", login("qa-lock","Str0ngPass99")[0], "expect 200")
# brute force -> lockout
for i in range(7):
    st,h,b=login("qa-lock","wrongwrong"+str(i))
    print(f"  bad attempt {i+1}:", st, (qa.jl(b) or {}).get("code"))
# now correct pw after lockout
print("correct after lockout:", login("qa-lock","Str0ngPass99")[0], (qa.jl(login('qa-lock','Str0ngPass99')[2]) or {}).get('code'), "expect 423 locked")
# timing: unknown user vs wrong pw (constant time claim)
import statistics
def t(fn):
    xs=[]
    for _ in range(5):
        a=time.time(); fn(); xs.append(time.time()-a)
    return round(statistics.median(xs)*1000,1)
print("median ms unknown user:", t(lambda: login("nosuchuser_xyz","whatever123")))
print("median ms wrong pw (known, now locked):", t(lambda: login("qa-admin","whatever123")))
qa.call("DELETE", qa.SRV+"/api/v1/admin/users/qa-lock", admin)
