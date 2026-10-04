#!/usr/bin/env python3
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

"""Regenerates the screenshots of the Sutra developer guide (docs/guides/SUTRA_DEVELOPER_GUIDE.md) into docs/guides/img/sutras/.

It reuses the shared screenshot driver (tools/docs/screenshots.py): the same scratch server :18997 and console :17997, never the usual
:18480 / :17480.

    console/.venv/bin/python tools/docs/shots/sutras.py            # all of them
    console/.venv/bin/python tools/docs/shots/sutras.py --only s04 # the ones whose file name contains "s04"
    console/.venv/bin/python tools/docs/shots/sutras.py --list"""
from __future__ import annotations

import base64
import json
from pathlib import Path
import sys
import tempfile

HERE = Path(__file__).resolve()
sys.path.insert(0, str(HERE.parents[1]))
import screenshots as S  # noqa: E402  the shared driver: Ctx, servers, main()

S.OUT = S.ROOT / "docs" / "guides" / "img" / "sutras"
S.SHOTS.clear()
shot = S.shot

SHORT = """rachana: 1
sutra: bond-short
version: 1
description: "A short bond layout, the strip leading with what is checked first."
match: { kind: trade, priority: 20 }
title: { pill: "Bond · ${$.productName}", id: $.tradeId, with: "link($.counterparty.id, 'counterparty', $.counterparty.name)" }
strip:
  - { label: MTM (USD), bind: $.mtm, fmt: signed0, tone: sign, emphasis: true }
  - { label: 1-day P&L, bind: $.pnl1d, fmt: signed0, tone: sign }
  - { label: DV01 (USD), bind: $.risk.dv01, fmt: signed0, tone: sign }
panels:
  - id: terms
    kind: kv
    title: Terms
    columns:
      - { label: Coupon, bind: $.terms.coupon, fmt: pct4 }
      - { label: Clean price, bind: $.terms.cleanPrice, fmt: price2 }
      - { label: Yield, bind: $.terms.yield, fmt: pct4 }
"""


def showcase():
    return (S.EXAMPLES / "all-panels-showcase.json").read_text(), (S.EXAMPLES / "all-panels-showcase.sutra.yaml").read_text()


@shot("s01-yaml-problems.jpg")
def yaml_problems(c):
    c.example("all-panels-showcase")
    c.page.get_by_role("tab", name="YAML").click()
    c.page.wait_for_timeout(500)
    c.page.evaluate("document.querySelector('.CodeMirror').CodeMirror.replaceRange('  - id: oops\\n    kind: chart\\n', {line: 32, ch: 0})")
    c.page.get_by_role("tab", name="Problems").click()
    c.page.locator(".wb-problem-b", has_text="chart").first.wait_for(timeout=20000)
    c.save("s01-yaml-problems.jpg")


@shot("s02-preview.jpg")
def preview(c):
    json_text, sutra = showcase()
    doc = json.loads(json_text)
    doc["tradeId"] = "TRY-IT-ON-THE-SPOT"
    doc["productName"] = "A file that is not in the design"
    tmp = Path(tempfile.mkdtemp()) / "try-this.json"
    tmp.write_text(json.dumps(doc))
    c.open(c.design("Screenshots: sutra preview", files={"full.json": json_text}, sutra=sutra))
    c.page.locator("[data-preview-file]").set_input_files(str(tmp))
    c.page.get_by_text("TRY-IT-ON-THE-SPOT").first.wait_for(timeout=20000)
    c.page.wait_for_timeout(600)
    c.save("s02-preview.jpg")


@shot("s03-match-summary.jpg")
def match_summary(c):
    json_text, sutra = showcase()
    c.open(c.design("Screenshots: sutra summary", files={"full.json": json_text}, sutra=sutra))
    c.page.get_by_role("tab", name="Summary").click()
    c.page.wait_for_timeout(1200)
    c.save("s03-match-summary.jpg")


@shot("s04-tests-tab.jpg")
def tests_tab(c):
    json_text, sutra = showcase()
    doc = json.loads(json_text)
    thin = dict(doc)
    for k in ("legs", "schedule", "pnlHistory"):
        thin.pop(k, None)
    thin["tradeId"] = "DEMO-BOND-THIN"
    other = dict(doc)
    other["tradeId"] = "DEMO-BOND-2"
    c.open(c.design("Screenshots: sutra tests", files={"full.json": json_text, "thin.json": json.dumps(thin), "second.json": json.dumps(other)}, sutra=sutra))
    c.page.get_by_role("tab", name="Tests").click()
    c.page.locator(".wb-matrix").wait_for(timeout=30000)
    c.page.wait_for_timeout(600)
    c.save("s04-tests-tab.jpg")


@shot("s05-inference-vs-sutra.jpg")
def inference_vs_sutra(c):
    """The auto-design draft (what inference makes of the document) beside a short hand-written Sutra on the same document."""
    json_text, _ = showcase()
    id_ = c.design("Screenshots: inference", files={"full.json": json_text})      # an empty Sutra has no panel yet, so c.open() would wait for one
    c.made.append(id_)
    c.page.goto(f"{c.base}/build/d/{id_}")
    c.page.wait_for_timeout(3000)
    c.page.on("dialog", lambda d: d.accept())
    rev = c.rev()
    c.page.locator("[data-autodesign]").click()
    confirm = c.page.get_by_role("button", name="Auto-design", exact=True)             # the "Replace the Sutra?" dialog's own button, if it asks
    if confirm.count() > 1:
        confirm.last.click()
    c.settle(rev)
    c.page.wait_for_timeout(1000)
    left = c.page.locator("[data-preview]").first.screenshot()
    c.open(c.design("Screenshots: short sutra", files={"full.json": json_text}, sutra=SHORT))
    right = c.page.locator("[data-preview]").first.screenshot()
    html = ("<div style='position:fixed;inset:0;background:#7a7f8a;padding:8px;display:flex;gap:10px;align-items:flex-start;box-sizing:border-box'>"
            + "".join(f"<img style='width:50%;max-width:50%;height:auto;display:block' src='data:image/png;base64,{base64.b64encode(b).decode()}'>"
                      for b in (left, right)) + "</div>")
    page = c.page                                    # the page is only navigated away from afterwards
    page.goto("about:blank")                         # the console's CSP would drop the inline styles
    page.set_viewport_size({"width": 1440, "height": 700})
    page.set_content(html)
    page.wait_for_timeout(1500)
    out = S.OUT / "s05-inference-vs-sutra.jpg"
    page.screenshot(path=str(out), type="jpeg", quality=84)
    page.set_viewport_size({"width": 1440, "height": 900})
    print("  wrote", out.relative_to(S.ROOT))


if __name__ == "__main__":
    sys.exit(S.main())
