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

"""The pictures of "Search with a form (FORM <pack>)" (docs/guides/USER_GUIDE.md) and of the workbench's Form tab (docs/guides/RACHANA_REFERENCE.md),
written to docs/guides/img/form/: the form for the trading pack's trades, its results with the command it made, the same on a phone (390 px,
touch), a regular expression the search refused, and the Form tab beside a design. The real console over a real server:

    drishti-console/.venv/bin/python tools/docs/screenshots.py --guide form
"""
from __future__ import annotations

from shotlib import Ctx, register, showcase

shot = register("form")
RESULTS = "/form/trading?kind=trade&run=1&v.CURRENCY=EUR&v.CURRENCY=USD&op.QUANTITY=%3E%3D&v.QUANTITY=100m&limit=100"
# the trades' fields are lists, amounts, dates and ids (production-shaped, 2.3.0): a text field with *matches regex* is a counterparty's name
REGEX = "/form/core?kind=counterparty&run=1&op.NAME=regex&v.NAME=%28a%29%5C1"


def open_form(c: Ctx, path: str, width: int = 1440, height: int = 1500, touch: bool = False):
    c.page.set_viewport_size({"width": width, "height": height})
    c.page.goto(c.base + path)
    c.page.locator("[data-qform]").wait_for(timeout=30000)
    c.page.wait_for_timeout(800)


@shot("01-form.jpg")
def form(c: Ctx):
    open_form(c, "/form/trading?kind=trade", height=1150)
    c.page.fill('[name="v.TRADE_ID"]', "MX-2*")
    c.page.locator('[name="v.SOURCE_SYSTEM"][value="MUREX"]').check()
    cur = c.page.locator("details.ms-box", has=c.page.locator('[name="v.CURRENCY"]'))
    cur.locator("summary").click()                       # a long list of values is one dropdown: search, Select all, Clear, a box per value
    cur.locator('[name="v.CURRENCY"][value="EUR"]').check()
    cur.locator('[name="v.CURRENCY"][value="USD"]').check()
    cur.locator("summary").click()
    c.page.locator('[name="op.QUANTITY"]').select_option(">=")
    c.page.fill('[name="v.QUANTITY"]', "100m")
    c.page.locator('[name="op.MATURITY_DATE"]').select_option("relative")
    c.page.fill('[name="v.MATURITY_DATE"]', "next 10 business days")
    c.page.mouse.move(1430, 1100)
    c.page.wait_for_timeout(300)
    c.save("01-form.jpg", clip={"x": 0, "y": 56, "width": 1440, "height": 1060})


@shot("02-results.jpg")
def results(c: Ctx):
    open_form(c, RESULTS, height=1700)
    c.page.locator("[data-qf-command]").wait_for(timeout=30000)
    c.page.wait_for_timeout(600)
    top = c.page.locator(".qf-command").bounding_box()
    c.save("02-results.jpg", clip={"x": 0, "y": max(0, top["y"] - 24), "width": 1440, "height": 640})


@shot("06-form-folded.jpg")
def folded(c: Ctx):
    open_form(c, RESULTS, height=1700)
    c.page.locator("[data-qf-command]").wait_for(timeout=30000)
    c.page.wait_for_timeout(600)
    c.save("06-form-folded.jpg", clip={"x": 0, "y": 56, "width": 1440, "height": 560})


@shot("03-phone.jpg")
def phone(c: Ctx):
    open_form(c, "/form/trading?kind=trade", width=390, height=900)
    c.page.locator('[name="v.QUANTITY"]').scroll_into_view_if_needed()
    c.save("03-phone.jpg", clip={"x": 0, "y": 0, "width": 390, "height": 900})


@shot("04-regex-refused.jpg")
def refused(c: Ctx):
    open_form(c, REGEX, height=1500)
    c.page.locator("[data-qf-error]").wait_for(timeout=30000)
    group = c.page.locator('.qf-group:has([name="v.NAME"])').first.bounding_box()
    err = c.page.locator("[data-qf-error]").bounding_box()
    y = max(0, group["y"] - 44)                       # with the note above the fields
    c.save("04-regex-refused.jpg", clip={"x": 0, "y": y, "width": 1440, "height": min(err["y"] + err["height"] + 16 - y, 900)})


@shot("05-designer-form-tab.jpg")
def designer_tab(c: Ctx):
    sample, sutra = showcase()
    c.page.set_viewport_size({"width": 1440, "height": 1000})
    id_ = c.design("Form tab", files={"trade.json": sample}, sutra=sutra, kind="trade")
    c.open(id_)
    c.page.locator("#wbTabForm").click()
    c.page.locator("[data-form-palette] button").first.wait_for(timeout=30000)
    c.page.locator("[data-form-starter]").click()
    c.page.locator("[data-form-list] li").first.wait_for(timeout=30000)
    c.page.wait_for_timeout(1200)
    c.save("05-designer-form-tab.jpg", locator=".wb-right")
