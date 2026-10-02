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

import sys, time, re
sys.path.insert(0, __file__.rsplit("/", 1)[0])
from pw import *

OUT = []
def note(*a):
    s = " ".join(str(x) for x in a); OUT.append(s); print(s, flush=True)

def cmd(page, text):
    page.goto(BASE + "/t")
    page.fill("#cmdInput", text)
    page.press("#cmdInput", "Enter")
    page.wait_for_load_state("networkidle", timeout=15000) if False else page.wait_for_timeout(2500)

with sync_playwright() as p:
    b = p.chromium.launch()
    ctx = b.new_context(viewport={"width": 1600, "height": 1000})
    page = ctx.new_page(); attach(page, "qs")
    # 1
    cmd(page, "TRD MX-20000001")
    note("1 url", page.url)
    body = page.inner_text("body")
    for s in ["Rates · Interest rate swap (fixed/float)", "MX-20000001", "with Meridian Reinsurance Ltd", "AUD 242,000,000", "MTM (USD)", "Terms", "Legs", "Cashflows", "DV01 by bucket", "Daily P&L", "Linked entities", "How this view was built"]:
        note("  contains", repr(s), s in body)
    shot(page, "qs1_trade")
    m1 = page.inner_text("body"); time.sleep(4); m2 = page.inner_text("body")
    note("  body changed after 4s (ticks):", m1 != m2)
    keys = page.locator("footer, .fkeys, [data-fkeys]").all_inner_texts()
    note("  fkeys text:", [k[:300] for k in keys])
    # 2 F9
    page.keyboard.press("F9"); page.wait_for_timeout(1500)
    note("2 F9 url", page.url, "murex-rates" in page.inner_text("body"))
    shot(page, "qs2_f9")
    page.keyboard.press("Escape"); page.wait_for_timeout(500)
    page.keyboard.press("F7"); page.wait_for_timeout(2500)
    note("  F7 url", page.url, "NS-MERIDIAN-RE-NY" in page.url)
    page.keyboard.press("Alt+ArrowLeft"); page.wait_for_timeout(2500)
    note("  Alt+Left url", page.url)
    # 3 F8
    page.goto(BASE + "/v/trade/MX-20000001"); page.wait_for_timeout(2000)
    page.keyboard.press("F8"); page.wait_for_timeout(3000)
    t = page.inner_text("body")
    note("3 F8 url", page.url, "Impact of MX-20000001" in t, "NS-MERIDIAN-RE-NY" in t, "LIM-MERIDIAN-RE" in t)
    shot(page, "qs3_impact")
    # 4
    cmd(page, "TRD MX-200000")
    t = page.inner_text("body"); m = re.search(r"\d[\d,]* of [\d,]+ trades match[^\n]*", t)
    note("4 url", page.url, m.group(0) if m else t[:300])
    shot(page, "qs4_pick")
    cmd(page, "TRD productType=Revolver")
    t = page.inner_text("body"); m = re.search(r"\d[\d,]* of [\d,]+ trades match[^\n]*", t)
    note("5", page.url, m.group(0) if m else t[:300])
    cmd(page, "trd producttype=revolver")
    t = page.inner_text("body"); m = re.search(r"\d[\d,]* of [\d,]+ trades match[^\n]*", t)
    note("5b", page.url, m.group(0) if m else t[:300])
    cmd(page, "CPTY north")
    t = page.inner_text("body")
    note("6", page.url, "Northbridge Capital LLP" in t, "BB+" in t)
    cmd(page, "TRD where mtm > 1m order by mtm desc limit 20")
    t = page.inner_text("body"); m = re.search(r"\d[\d,]* of [\d,]+ trades match[^\n]*", t)
    note("7", page.url, m.group(0) if m else t[:300])
    shot(page, "qs7_search")
    cmd(page, "NSET NS-SUMMIT-NY")
    t = page.inner_text("body")
    i = t.find("Utilisation"); note("8", page.url, "Trades" in t, t[i:i+40].replace("\n", " ") if i >= 0 else "no Utilisation")
    j = t.find("Trades"); note("  trades text", t[j:j+30].replace("\n"," "))
    shot(page, "qs8_nset")
    # suggestions
    page.goto(BASE + "/t"); page.click("#cmdInput"); page.keyboard.type("TRD MX-2000010", delay=40); page.wait_for_timeout(1500)
    note("suggest", page.locator("[role=listbox], .suggest, .cmd-suggest").all_inner_texts()[:2])
    shot(page, "qs_suggest")
    page.keyboard.press("ArrowDown"); page.keyboard.press("Tab"); page.wait_for_timeout(300)
    note("  after tab input=", page.input_value("#cmdInput"))
    page.keyboard.press("Enter"); page.wait_for_timeout(2500); note("  ->", page.url)
    # 8 past date
    page.goto(BASE + "/asof?d=2026-09-29&next=/v/trade/MX-20000001"); page.wait_for_timeout(2500)
    t = page.inner_text("body"); note("past url", page.url, "as of 2026-09-29" in t, "Compare" in t)
    shot(page, "qs_past")
    cl = page.get_by_text("Compare", exact=True)
    note("  compare count", cl.count())
    if cl.count():
        cl.first.click(); page.wait_for_timeout(3000)
        t = page.inner_text("body"); note("  compare url", page.url, "What changed in MX-20000001" in t)
        shot(page, "qs_compare")
    page.goto(BASE + "/asof?d=live&next=/t"); page.wait_for_timeout(1000)
    note("live reset url", page.url)
    b.close()
dump_errs(__file__.rsplit("/", 1)[0] + "/errs_qs.json")
open(__file__.rsplit("/", 1)[0] + "/qs.out", "w").write("\n".join(OUT))
