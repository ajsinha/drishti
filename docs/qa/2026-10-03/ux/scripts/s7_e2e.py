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
p = sync_playwright().start(); b = p.chromium.launch()
ca, pa = new_ctx(b, "author"); attach(pa, "e2e-author")
cr, pr = new_ctx(b, "appr"); attach(pr, "e2e-appr")
make_design(pa, "QA e2e")
did = pa.url.rsplit("/", 1)[-1]
print("status chip:", pa.inner_text("[data-status-chip]"), "| save btn:", pa.inner_text("[data-save]").strip(), "| Ctrl+S hint title:", pa.get_attribute("[data-save]", "title"))
# Reviews link before anything
print("Reviews link:", pa.locator("a:has-text('Reviews')").first.get_attribute("href"))
pa.fill("[data-note]", "Added the shipments view; checked on 6 samples")
pa.keyboard.press("Control+s"); pa.wait_for_timeout(2500)
print("after Ctrl+S say:", say(pa)); print("status chip:", pa.inner_text("[data-status-chip]")); shot(pa, "s7-submitted")
# author on the review page
m = re.search(r"P-\d+", say(pa)) if (re:=__import__("re")) else None; pid = m.group(0) if m else None; print("proposal:", pid)
pa.goto(BASE + f"/build/reviews/{pid}"); pa.wait_for_timeout(1000); print("author sees actions:", pa.locator(".review-actions").inner_text().replace("\n"," | ")); shot(pa, "s7-review-author", full=True)
print("evidence:", pa.locator(".pnl").nth(1).inner_text()[:400].replace("\n"," | ") if pa.locator(".pnl").count()>1 else "none")
# approver
pr.goto(BASE + "/build/reviews"); pr.wait_for_timeout(1000); print("reviews list:", pr.inner_text("main, .adm")[:300].replace("\n"," | "))
pr.goto(BASE + f"/build/reviews/{pid}"); pr.wait_for_timeout(1000); shot(pr, "s7-review-appr", full=True)
print("approver actions:", pr.locator(".review-actions").inner_text().replace("\n"," | "))
pr.fill("#okC", "ok"); pr.click("text=Approve and publish"); pr.wait_for_timeout(2000); print("after approve:", pr.url, pr.inner_text(".review")[:200].replace("\n"," | "))
pa.goto(BASE + "/build"); pa.wait_for_timeout(1000); print("My designs:", pa.inner_text("main, .adm")[:400].replace("\n"," | ")); shot(pa, "s7-mydesigns")
pa.goto(BASE + f"/build/d/{did}"); pa.wait_for_timeout(2000); print("workbench status chip:", pa.inner_text("[data-status-chip]"))
b.close()
for e in ERRS: print("ERR", e)
