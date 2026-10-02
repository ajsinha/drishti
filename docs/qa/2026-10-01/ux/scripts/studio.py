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

"""Studio in the browser: invalid Sutras, huge Sutras, problems by line, submit for review, review/approve."""
import sys, time; sys.path.insert(0, __file__.rsplit("/", 1)[0])
from pw import *

SET = "(c) => { const cm = document.querySelector('.CodeMirror'); if (cm && cm.CodeMirror) { cm.CodeMirror.setValue(c); return 'cm'; } const t=document.querySelector('[data-src]'); t.value=c; t.dispatchEvent(new Event('input')); return 'ta'; }"
PROB = "() => { const p = document.querySelector('[data-problems]'); const o = document.querySelector('[data-out]'); const s = document.querySelector('[data-status]'); return [(p?p.innerText:''), (s?s.innerText:''), (o?o.innerText.slice(0,200):'')].map(x=>x.replace(/\\n/g,' | ').slice(0,300)); }"
HEAD = "rachana: 1\nsutra: qa-studio\nversion: 1\nmatch: { kind: trade, where: \"$.tradeId == 'MX-20000005'\", priority: 50 }\ntitle: { pill: QA Studio, id: $.tradeId }\n"
CASES = [
    ("valid", HEAD + "panels:\n  - { id: refs, kind: links, title: Linked }\n"),
    ("yaml error", HEAD + "panels:\n  - { id: refs, kind: links\n"),
    ("unknown kind", HEAD + "panels:\n  - { id: a, kind: chart }\n"),
    ("multi problems", "rachana: 2\nsutra: BAD_NAME\nversion: x\nmatch: {}\npanels: 5\n"),
    ("deep parens 1000", HEAD + "panels:\n  - { id: a, kind: kv, columns: [{label: x, bind: '" + "(" * 1000 + "1" + ")" * 1000 + "'}] }\n"),
    ("empty", ""),
    ("2000 panels", HEAD + "panels:\n" + "".join("  - { id: p%d, kind: markdown, text: 'panel %d' }\n" % (i, i) for i in range(2000))),
    ("tabs indent", HEAD.replace("title:", "\ttitle:")),
]
with sync_playwright() as p:
    b = p.chromium.launch()
    pg = b.new_page(viewport={"width": 1600, "height": 1000}); attach(pg, "studio")
    pg.goto(BASE + "/studio?kind=trade&id=MX-20000005"); pg.wait_for_timeout(2500)
    print("initial:", pg.evaluate(PROB))
    for name, y in CASES:
        pg.evaluate(SET, y); t0 = time.time()
        pg.click("[data-preview]"); pg.wait_for_timeout(3000)
        print("%-18s %.1fs %s" % (name, time.time() - t0, pg.evaluate(PROB)))
        shot(pg, "studio_" + name.replace(" ", "_"))
    # submit valid for review
    pg.evaluate(SET, CASES[0][1]); pg.wait_for_timeout(500)
    if pg.locator("[data-note]").count(): pg.fill("[data-note]", "QA proposal <b>bold</b>")
    pg.once("dialog", lambda d: (print("dialog:", d.message[:200]), d.accept()))
    pg.click("[data-save]"); pg.wait_for_timeout(3000)
    print("after submit:", pg.evaluate(PROB))
    shot(pg, "studio_submitted")
    pg.goto(BASE + "/studio/reviews"); pg.wait_for_timeout(2000)
    print("reviews:", pg.inner_text("main")[:400].replace("\n", " | "))
    shot(pg, "studio_reviews")
    link = pg.locator("main a[href^='/studio/reviews/']").first
    if link.count():
        link.click(); pg.wait_for_timeout(2500)
        print("review page:", pg.url, pg.inner_text("main")[:500].replace("\n", " | "))
        shot(pg, "studio_review_page", full=True)
        ap = pg.get_by_role("button", name="Approve")
        if ap.count():
            pg.once("dialog", lambda d: (print("dialog:", d.message[:200]), d.accept()))
            ap.first.click(); pg.wait_for_timeout(3000)
            print("after approve:", pg.inner_text("main")[:300].replace("\n", " | "))
    pg.goto(BASE + "/v/trade/MX-20000005"); pg.wait_for_timeout(3000)
    print("MX-20000005 now:", pg.inner_text("main")[:120].replace("\n", " | "))
    b.close()
for e in ERRS: print("ERR", e)
