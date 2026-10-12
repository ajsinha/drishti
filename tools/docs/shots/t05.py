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

"""Pictures of tutorial 5, Build a pack from your data (docs/tutorials/05-build-a-pack.md), written to docs/tutorials/img/t05/.

Build -> New pack takes the tutorial's schema and its folder of quotes (docs/tutorials/data), makes the pack `desk-quotes`, hands it to
Admin -> Packs, which checks it and deploys it."""
from __future__ import annotations

from _tut import OUT, ROOT, SERVER_ENV, call, elem_shot, go, login, page_shot  # noqa: F401
from shotlib import Ctx, register

shot = register("t05", OUT / "t05")
DATA = ROOT / "docs" / "tutorials" / "data"


def sources(c: Ctx, width: int = 1280, height: int = 900):
    login(c, width=width, height=height)
    c.page.goto(c.base + "/build/pack/new")
    c.page.set_input_files("[data-files]", [str(DATA / "schemas" / "quote.schema.json")])
    c.page.locator("[data-total]:has-text('1 schema')").wait_for(timeout=30000)
    c.page.set_input_files("[data-folder]", str(DATA / "desk-quotes"))
    c.page.locator("[data-total]:has-text('10 data files')").wait_for(timeout=30000)


def kinds(c: Ctx, width: int = 1280):
    sources(c, width)
    c.page.click("[data-next]")
    c.page.locator(".pk-card").first.wait_for(timeout=30000)
    c.page.wait_for_timeout(800)


def pack(c: Ctx, width: int = 1280):
    kinds(c, width)
    p = c.page
    p.click("[data-next]")
    p.fill("#pkp-name", "desk-quotes")
    p.fill("#pkp-title", "Desk quotes")
    p.fill("#pkp-description", "The desk's end-of-day quotes, generated from their schema and a folder of documents.")
    p.locator("select[aria-label='Source of quote']").select_option("file")
    p.fill("input[aria-label='Connector name for quote']", "desk-quotes")
    p.locator("input[aria-label='Connector name for quote']").dispatch_event("change")
    for sel in ("#pkp-name", "#pkp-title", "#pkp-description"):
        p.locator(sel).dispatch_event("change")
    c.until("!document.querySelector('[data-next]').disabled")


@shot("01-sources.jpg")
def s1(c: Ctx):
    sources(c)
    elem_shot(c, "01-sources.jpg", "[data-step='1']")


@shot("02-kinds.jpg")
def s2(c: Ctx):
    kinds(c)
    elem_shot(c, "02-kinds.jpg", "[data-step='2']")


@shot("03-pack.jpg")
def s3(c: Ctx):
    pack(c)
    elem_shot(c, "03-pack.jpg", "[data-step='3']")


@shot("04-preview.jpg", also=("05-sutra.jpg",))
def s4(c: Ctx):
    pack(c)
    c.page.click("[data-next]")
    c.page.locator(".pk-item").first.wait_for(timeout=90000)
    c.page.locator(".pk-item").first.click()
    c.page.locator(".pk-pv [data-panel], .pk-pv .pnl").first.wait_for(timeout=30000)
    c.page.wait_for_timeout(1500)
    elem_shot(c, "04-preview.jpg", "[data-step='4']")
    c.page.click("#pkt-yaml")
    c.page.wait_for_timeout(500)
    elem_shot(c, "05-sutra.jpg", ".pk-detail")


@shot("06-output.jpg", also=("07-deploy-checks.jpg",))
def s5(c: Ctx):
    pack(c)
    c.page.click("[data-next]")
    c.page.locator(".pk-item").first.wait_for(timeout=90000)
    c.page.click("[data-next]")
    c.page.locator("[data-deploy-link]").wait_for(timeout=60000)
    elem_shot(c, "06-output.jpg", "[data-step='5']")
    c.page.click("[data-deploy-link]")
    c.page.set_viewport_size({"width": 1280, "height": 1500})
    c.page.wait_for_url("**/admin/packs**")
    c.page.locator("[data-result]:not([hidden])").wait_for(timeout=60000)
    c.page.evaluate("window.scrollTo(0, 0)")
    c.page.wait_for_timeout(400)
    box = c.page.locator("[data-deploy]").bounding_box()
    page_shot(c, "07-deploy-checks.jpg", {"x": 0, "y": box["y"] - 36, "width": 1280, "height": 800}, pad=0)


def deploy_through_the_wizard(c: Ctx, version: str):
    """The wizard to its last step, a version, then the hand-off to Admin -> Packs, where Deploy is confirmed."""
    pack(c)
    if version != "1.0.0":
        c.page.fill("#pkp-version", version)
        c.page.locator("#pkp-version").dispatch_event("change")
        c.until("!document.querySelector('[data-next]').disabled")
    c.page.click("[data-next]")
    c.page.locator(".pk-item").first.wait_for(timeout=90000)
    c.page.click("[data-next]")
    c.page.locator("[data-deploy-link]").wait_for(timeout=60000)
    c.page.click("[data-deploy-link]")
    c.page.wait_for_url("**/admin/packs**")
    c.page.locator("[data-deploy-go]").wait_for(timeout=60000)
    c.page.once("dialog", lambda d: d.accept())
    c.page.locator("[data-deploy-go]").click()
    c.page.wait_for_timeout(10000)                     # the deploy takes effect at once, then the page reloads itself


@shot("08-deployed.jpg", also=("09-quote.jpg",))
def deployed(c: Ctx):
    deploy_through_the_wizard(c, "1.0.0")
    go(c, "/admin/packs#history", height=1700, wait="[data-history]", settle=1000)
    elem_shot(c, "08-deployed.jpg", "[data-history]", pad=50, extra=40)
    go(c, "/v/quote/Q-EURUSD", height=800, wait="[data-panel]", settle=1800)
    c.save("09-quote.jpg")


@shot("10-versions-kept.jpg", also=("11-rolled-back.jpg",))
def kept(c: Ctx):
    deploy_through_the_wizard(c, "1.1.0")
    go(c, "/admin/packs#history", height=1700, wait="[data-history]", settle=1000)
    c.page.evaluate("window.scrollTo(0, 0)")
    elem_shot(c, "10-versions-kept.jpg", "[data-kept]", pad=60, extra=200)
    c.page.once("dialog", lambda d: d.accept())
    c.page.locator("[data-kept] tr[data-version='1.0.0'] [data-rollback]").first.click()
    c.page.wait_for_timeout(4000)
    go(c, "/admin/packs#history", height=1700, wait="[data-history]", settle=1000)
    elem_shot(c, "11-rolled-back.jpg", "[data-history]", pad=60, extra=60)
