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

"""GETTING_STARTED Step 13: sign-in on."""
import sys, time; sys.path.insert(0, __file__.rsplit("/", 1)[0])
from pw import *
def login(pg, u, pw):
    pg.goto(BASE + "/login"); pg.wait_for_timeout(800)
    pg.fill("input[name=user]", u); pg.fill("input[name=password]", pw)
    pg.locator("button[type=submit]").first.click(); pg.wait_for_timeout(1500)
    return pg.url, pg.evaluate("()=>{const e=document.querySelector('[role=alert], .pnl-err, .login-err'); return e? e.innerText.slice(0,200):''}")
with sync_playwright() as p:
    b = p.chromium.launch()
    ctx = b.new_context(viewport={"width": 1400, "height": 900}); pg = ctx.new_page(); attach(pg, "signin")
    pg.goto(BASE + "/t"); pg.wait_for_timeout(1500); print("/t ->", pg.url)
    shot(pg, "signin_page")
    for i in range(6):
        print("wrong %d:" % (i + 1), login(pg, "qa-lock", "wrongpassword1"))
    print("unknown user wrong:", login(pg, "nobody-here", "x"))
    print("dev admin:", login(pg, "drishti-dev-admin", "drishti-dev-admin123"))
    pg.goto(BASE + "/admin/users"); pg.wait_for_timeout(1500)
    print("users warning:", "default password" in pg.inner_text("main"))
    pg.goto(BASE + "/account"); pg.wait_for_timeout(1500)
    pw = pg.locator("input[type=password]")
    print("password fields:", pw.count(), [pw.nth(i).get_attribute("name") for i in range(pw.count())])
    if pw.count() >= 3:
        pw.nth(0).fill("drishti-dev-admin123"); pw.nth(1).fill("shortpw1"); pw.nth(2).fill("shortpw1")
        pg.get_by_role("button", name="Change password").click(); pg.wait_for_timeout(1500)
        print("short:", pg.evaluate("()=>[...document.querySelectorAll('[role=alert],[role=status],.adm-msg,[data-msg]')].map(e=>e.innerText).filter(Boolean).join(' / ').slice(0,200)"))
        pw.nth(0).fill("drishti-dev-admin123"); pw.nth(1).fill("onlyletterspassword"); pw.nth(2).fill("onlyletterspassword")
        pg.get_by_role("button", name="Change password").click(); pg.wait_for_timeout(1500)
        print("letters only:", pg.evaluate("()=>[...document.querySelectorAll('[role=alert],[role=status],.adm-msg,[data-msg]')].map(e=>e.innerText).filter(Boolean).join(' / ').slice(0,200)"))
        pw.nth(0).fill("drishti-dev-admin123"); pw.nth(1).fill("NewPassw0rd2026"); pw.nth(2).fill("NewPassw0rd2026")
        pg.get_by_role("button", name="Change password").click(); pg.wait_for_timeout(1500)
        print("good:", pg.url, pg.evaluate("()=>[...document.querySelectorAll('[role=alert],[role=status],.adm-msg,[data-msg]')].map(e=>e.innerText).filter(Boolean).join(' / ').slice(0,200)"))
    pg.goto(BASE + "/logout"); pg.wait_for_timeout(1000)
    print("old password:", login(pg, "drishti-dev-admin", "drishti-dev-admin123"))
    print("new password:", login(pg, "drishti-dev-admin", "NewPassw0rd2026"))
    # dev admin lockout: 5 wrong then right
    pg.goto(BASE + "/logout")
    for i in range(5): login(pg, "drishti-dev-admin", "bad-password-%d" % i)
    print("after 5 wrong, right password:", login(pg, "drishti-dev-admin", "NewPassw0rd2026"))
    shot(pg, "signin_locked")
    b.close()
for e in ERRS:
    if e["type"] not in ("requestfailed",): print("ERR", e)
