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

"""Pictures of tutorial 4, Connect your data (docs/tutorials/04-connect-your-data.md), written to docs/tutorials/img/t04/.

The connectors are the tutorial's own files (docs/tutorials/data/connectors/): desk-quotes reads a folder of JSON Lines with the shipped `file`
plugin; desk-ledger is a PostgreSQL connector aimed at a port nothing listens on, so Test connection and the retry line have something to say."""
from __future__ import annotations

from _tut import OUT, ROOT, SERVER_ENV, call, go, login  # noqa: F401
from shotlib import Ctx, register

shot = register("t04", OUT / "t04")
LEDGER = (ROOT / "docs" / "tutorials" / "data" / "connectors" / "desk-ledger.yaml").read_text()


def clean(c: Ctx, names=("desk-quotes", "desk-ledger")):
    for name in names:
        call(c, "DELETE", f"/admin/connectors/api/{name}?confirm=true")


def dialog(c: Ctx):
    d = c.page.locator("[data-edit-dialog]")
    d.locator("[data-f-name]").wait_for(timeout=30000)
    return d


@shot("01-connectors-list.jpg", also=("02-new-connector.jpg", "03-test-connection.jpg", "04-saved.jpg"))
def file_connector(c: Ctx):
    login(c, height=900)
    clean(c)
    go(c, "/admin/connectors", height=900, wait="[data-list] tr[data-conn]", settle=1500)
    c.page.fill("[data-filter]", "desk")
    c.page.wait_for_timeout(400)
    c.save("01-connectors-list.jpg", clip={"x": 0, "y": 0, "width": 1280, "height": 560})
    c.page.click("[data-new]")
    d = dialog(c)
    d.locator("[data-f-plugin]").select_option("file")
    d.locator("[data-f-name]").fill("desk-quotes")
    d.locator("[data-f-description]").fill("Desk quotes from the tutorial folder")
    d.locator("[data-setting='root']").fill("./docs/tutorials/data/desk-quotes")
    d.locator("[data-setting='lookback-days']").fill("60")
    d.locator("[data-kind-in]").fill("quote")
    d.locator("[data-kind-add]").click()
    c.page.wait_for_timeout(500)
    c.page.evaluate("() => { const d = document.querySelector('[data-edit-dialog]'); d.scrollTop = 0; }")
    c.save("02-new-connector.jpg", "[data-edit-dialog]")
    d.locator("[data-test]").click()
    d.locator("[data-test-out] p, [data-test-out] table").first.wait_for(timeout=60000)
    c.page.wait_for_timeout(600)
    c.save("03-test-connection.jpg", "[data-edit-dialog]")
    d.locator("[data-save]").click()
    c.page.wait_for_timeout(2500)
    go(c, "/admin/connectors", height=900, wait="[data-list] tr[data-conn]", settle=1200)
    c.page.fill("[data-filter]", "desk")
    c.page.wait_for_timeout(400)
    c.save("04-saved.jpg", clip={"x": 0, "y": 0, "width": 1280, "height": 620})


@shot("05-detail.jpg", also=("06-detail-phone.jpg",))
def detail(c: Ctx):
    login(c, height=1000)
    go(c, "/admin/connectors/desk-quotes", height=1000, wait="h1, h2", settle=1500)
    c.save("05-detail.jpg")
    go(c, "/admin/connectors/desk-quotes", width=390, height=844, wait="h1, h2", settle=1200)
    c.save("06-detail-phone.jpg")


@shot("07-ledger-test.jpg", also=("08-ledger-retry.jpg", "09-pool-line.jpg"))
def ledger(c: Ctx):
    login(c, height=900)
    clean(c, ("desk-ledger",))
    call(c, "PUT", "/admin/connectors/api/desk-ledger", {"text": LEDGER})
    c.page.wait_for_timeout(3000)
    go(c, "/admin/connectors?name=desk-ledger&tab=test", height=900, wait="[data-edit-dialog]", settle=800)
    d = c.page.locator("[data-edit-dialog]")
    d.locator("[data-test]").click()
    d.locator("[data-test-out] p").first.wait_for(timeout=60000)
    c.page.wait_for_timeout(600)
    c.save("07-ledger-test.jpg", "[data-edit-dialog]")
    c.page.keyboard.press("Escape")
    go(c, "/admin/connectors/desk-ledger", height=1100, wait="h1, h2", settle=1500)
    c.save("08-ledger-retry.jpg")
    c.save("09-pool-line.jpg", "[data-pool]")
