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

"""GETTING_STARTED Step 12 literally: alert + monitor; hostile values; notes; workspaces; reports."""
import sys, time; sys.path.insert(0, __file__.rsplit("/", 1)[0])
from pw import *
from urllib.parse import quote

with sync_playwright() as p:
    b = p.chromium.launch()
    pg = b.new_page(viewport={"width": 1600, "height": 1000}); attach(pg, "alerts")
    pg.goto(BASE + "/v/netting-set/NS-SUMMIT-NY"); pg.wait_for_timeout(2000)
    pg.get_by_text("Alert", exact=True).first.click(); pg.wait_for_timeout(2000)
    print("alert page:", pg.url, "kind=", pg.input_value("[name=kind]"), "id=", pg.input_value("[name=id]"))
    pg.fill("[name=name]", "Summit utilisation"); pg.fill("[name=when]", "$.utilisation > 0.5")
    pg.select_option("[name=severity]", "warn") if pg.locator("select[name=severity]").count() else pg.fill("[name=severity]", "warn")
    pg.fill("[name=message]", "${$.nettingSetId}: ${fmt($.utilisation, 'pct0')} of limit")
    pg.get_by_role("button", name="Save rule").click(); pg.wait_for_timeout(4000)
    t = pg.inner_text("body")
    print("fires:", "NS-SUMMIT-NY: 58% of limit" in t, "| bell:", pg.evaluate("()=>{const b=document.querySelector('[data-alerts-count], .bell-count, .tool-count');return b?b.innerText:''}"))
    shot(pg, "alert_saved")
    # hostile rules
    for name, when, msg in [("bad expr", "$.utilisation >", "x"), ("bad template", "true", "${$.x"), ("", "true", "no name"),
                             ("x" * 300, "true", "long name"), ("<img src=x onerror=alert(1)>", "true", "<b>html</b> ${'<script>'}"),
                             ("always", "true", "always firing ${$.nettingSetId}")]:
        pg.goto(BASE + "/alerts?kind=netting-set&id=NS-SUMMIT-NY"); pg.wait_for_timeout(1200)
        pg.fill("[name=name]", name); pg.fill("[name=when]", when); pg.fill("[name=message]", msg)
        pg.get_by_role("button", name="Save rule").click(); pg.wait_for_timeout(2000)
        m = pg.evaluate("()=>{const e=document.querySelector('[data-msg], .adm-msg, [role=alert]');return e?e.innerText.slice(0,200):''}")
        print("rule %-30s -> %s" % (name[:30], m.replace("\n", " ")))
    shot(pg, "alerts_after_hostile", full=True)
    # monitor from search
    pg.goto(BASE + "/s?q=" + quote("NSET where utilisation > 0.7 order by utilisation desc")); pg.wait_for_timeout(2500)
    pg.fill("#srchName", "High utilisation"); pg.get_by_role("button", name="Watch as a monitor").click(); pg.wait_for_timeout(3000)
    print("monitor url:", pg.url, "rows:", pg.locator("[data-row]").count())
    add = pg.locator("[data-add] input, input[data-add]").first
    if add.count():
        add.fill("NSET NS-SUMMIT-NY"); add.press("Enter"); pg.wait_for_timeout(2500)
        print("after add rows:", pg.locator("[data-row]").count())
        add.fill("NSET NOPE-1"); add.press("Enter"); pg.wait_for_timeout(2500)
        print("after bad add:", pg.evaluate("()=>{const e=document.querySelector('[data-msg]');return e?e.innerText:''}"), pg.locator("[data-row]").count())
    shot(pg, "monitor")
    # monitor with hostile name via search form
    pg.goto(BASE + "/s?q=" + quote("TRD where mtm > 1m")); pg.wait_for_timeout(2500)
    pg.fill("#srchName", "../../etc/passwd"); pg.get_by_role("button", name="Watch as a monitor").click(); pg.wait_for_timeout(3000)
    print("traversal monitor ->", pg.url, pg.inner_text("main")[:150].replace("\n", " | "))
    pg.goto(BASE + "/m"); pg.wait_for_timeout(1500); print("monitors list:", pg.inner_text("main")[:400].replace("\n", " | "))
    # notes
    pg.goto(BASE + "/v/trade/MX-20000002"); pg.wait_for_timeout(2000)
    pg.get_by_text("Notes", exact=True).first.click(); pg.wait_for_timeout(1500)
    ta = pg.locator("textarea").last
    if ta.count():
        ta.fill("<script>alert(1)</script> **note** " + "z" * 20000)
        pg.keyboard.press("Control+Enter"); pg.wait_for_timeout(1500)
        btn = pg.get_by_role("button", name="Add note")
        if btn.count(): btn.first.click(); pg.wait_for_timeout(1500)
        print("notes:", pg.evaluate("()=>{const e=document.querySelector('[data-notes], .notes');return e?e.innerText.slice(0,300):'?'}").replace("\n", " | "))
    shot(pg, "notes")
    b.close()
for e in ERRS: print("ERR", e)
