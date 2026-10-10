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

"""The pictures of Build -> New pack (docs/guides/SCHEMA_TO_PACK.md), written to docs/guides/img/schemapack/: the five steps with the example
schemas and JSON Lines of docs/guides/examples/schemas, the warning for a kind another pack already defines, one kind card, the About text, the
hand-off to Admin -> Packs -> Deploy archive (checked, not confirmed) and the page at phone width. The real console over a real scratch server:

    DRISHTI_SHOTS_SERVER_PORT=18993 DRISHTI_SHOTS_CONSOLE_PORT=17993 \\
        python tools/docs/screenshots.py --guide schemapack --base http://127.0.0.1:17993
"""
from __future__ import annotations

from shotlib import ROOT, Ctx, register

shot = register("schemapack")
SCHEMAS = ROOT / "docs" / "guides" / "examples" / "schemas"
FILES = [str(SCHEMAS / n) for n in ("trade.schema.json", "counterparty.schema.json", "book.schema.json", "instrument.schema.json")]
RENAMES = {"trade": "deal", "counterparty": "obligor", "book": "ledger", "instrument": "tradable"}     # the scratch server's packs already define the originals


def sources(c: Ctx, width: int = 1200, height: int = 900):
    p = c.page
    p.set_viewport_size({"width": width, "height": height})
    p.goto(c.base + "/build/pack/new")
    p.set_input_files("[data-files]", FILES)
    p.locator("[data-total]:has-text('4 schemas')").wait_for(timeout=30000)
    p.set_input_files("[data-folder]", str(SCHEMAS / "data"))
    p.locator("[data-total]:has-text('2 data files')").wait_for(timeout=30000)


def kinds(c: Ctx, rename: bool = True, width: int = 1200):
    sources(c, width)
    p = c.page
    p.click("[data-next]")
    p.locator(".pk-card").first.wait_for(timeout=30000)
    if rename:
        for old, new in RENAMES.items():
            p.fill(f"input[data-focus=name-{old}]", new)
            p.locator(f"input[data-focus=name-{old}]").dispatch_event("change")
            p.locator(f"input[data-focus=name-{old}][value={new}]").wait_for(timeout=30000)
            c.until("!document.querySelector('[data-next]').disabled")


def pack(c: Ctx, width: int = 1200):
    kinds(c, True, width)
    p = c.page
    p.click("[data-next]")
    p.fill("#pkp-name", "desk-pack")
    p.fill("#pkp-title", "Desk pack")
    p.fill("#pkp-description", "Deals, parties, ledgers and securities, generated from their schemas.")
    p.locator("#pkp-description").dispatch_event("change")
    p.locator("#pkp-name").dispatch_event("change")
    p.locator("#pkp-title").dispatch_event("change")
    c.until("!document.querySelector('[data-next]').disabled")


def preview(c: Ctx, width: int = 1200):
    pack(c, width)
    p = c.page
    p.click("[data-next]")
    p.locator(".pk-item").first.wait_for(timeout=90000)
    p.locator(".pk-item[data-sutra='deal-irs']").click()
    p.locator(".pk-pv [data-panel], .pk-pv .pnl").first.wait_for(timeout=30000)
    p.wait_for_timeout(1500)


@shot("01-sources.jpg")
def s1(c: Ctx):
    sources(c)
    c.save("01-sources.jpg", locator="[data-step='1']")


@shot("02-conflict.jpg")
def s2(c: Ctx):
    kinds(c, rename=False)
    c.save("02-conflict.jpg", locator=".pk-card >> nth=1")


@shot("03-kinds.jpg")
def s3(c: Ctx):
    kinds(c)
    c.save("03-kinds.jpg", locator="[data-step='2']")


@shot("04-card.jpg")
def s4(c: Ctx):
    kinds(c)
    c.save("04-card.jpg", locator=".pk-card:has(h3:has-text('Trade'))")


@shot("05-pack.jpg")
def s5(c: Ctx):
    pack(c)
    c.save("05-pack.jpg", locator="[data-step='3']")


@shot("06-preview.jpg")
def s6(c: Ctx):
    preview(c)
    c.save("06-preview.jpg", locator="[data-step='4']")


@shot("07-about.jpg")
def s7(c: Ctx):
    preview(c)
    c.page.click("#pkt-about")
    c.save("07-about.jpg", locator=".pk-detail")


@shot("08-sutra.jpg")
def s8(c: Ctx):
    preview(c)
    c.page.click("#pkt-yaml")
    c.save("08-sutra.jpg", locator=".pk-detail")


@shot("09-output.jpg")
def s9(c: Ctx):
    preview(c)
    c.page.click("[data-next]")
    c.page.locator("[data-download]").wait_for(timeout=60000)
    c.save("09-output.jpg", locator="[data-step='5']")


@shot("10-deploy.jpg")
def s10(c: Ctx):
    preview(c)
    c.page.click("[data-next]")
    c.page.locator("[data-deploy-link]").wait_for(timeout=60000)
    c.page.click("[data-deploy-link]")
    c.page.wait_for_url("**/admin/packs**")
    c.page.locator("[data-result]:not([hidden])").wait_for(timeout=60000)
    box = c.page.locator("[data-deploy]").bounding_box()
    c.save("10-deploy.jpg", clip={"x": 0, "y": max(box["y"] - 60, 0), "width": 1200, "height": 820})


@shot("11-phone.jpg")
def s11(c: Ctx):
    kinds(c, True, 390)
    c.page.set_viewport_size({"width": 390, "height": 844})
    c.page.wait_for_timeout(500)
    c.save("11-phone.jpg", clip={"x": 0, "y": 0, "width": 390, "height": 844})
