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

"""Run each command line argument through /go and print url + the first lines of main text."""
import sys, re
sys.path.insert(0, __file__.rsplit("/", 1)[0])
from pw import *
from urllib.parse import quote

wait = 2500
with sync_playwright() as p:
    b = p.chromium.launch()
    page = b.new_page(viewport={"width": 1600, "height": 1000}); attach(page, "cmds")
    for i, c in enumerate(sys.argv[1:]):
        page.goto(BASE + "/go?q=" + quote(c)); page.wait_for_timeout(wait)
        t = page.inner_text("main") if page.locator("main").count() else page.inner_text("body")
        m = re.search(r"[\d,]+ of [\d,]+ [^\n]*match[^\n]*", t)
        print("%-55s -> %s | %s" % (c[:55], page.url.replace(BASE, ""), (m.group(0) if m else t[:250].replace("\n", " | "))))
        shot(page, "cmd_%02d" % i)
    b.close()
for e in ERRS:
    print("ERR", e)
