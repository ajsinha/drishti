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

"""Shared Playwright helpers for the 2026-10-03 UX pass (scratch console :17963)."""
import json, os, sys, time
from playwright.sync_api import sync_playwright
BASE = "http://localhost:17963"
SHOTS = os.path.join(os.path.dirname(__file__), "..", "shots")
ERRS = []
USERS = {"admin": ("qaadmin", "QaAdmin12345"), "author": ("qaauthor", "QaAuthor12345"), "appr": ("qaappr", "QaAppr1234567")}

def attach(page, tag):
    page.on("console", lambda m: ERRS.append({"tag": tag, "url": page.url, "type": "console." + m.type, "text": m.text[:300]}) if m.type in ("error", "warning") else None)
    page.on("pageerror", lambda e: ERRS.append({"tag": tag, "url": page.url, "type": "pageerror", "text": str(e)[:300]}))
    page.on("response", lambda r: ERRS.append({"tag": tag, "url": page.url, "type": "http%d" % r.status, "text": r.url}) if r.status >= 400 else None)

def shot(page, name, full=False):
    p = os.path.join(SHOTS, name + ".png"); page.screenshot(path=p, full_page=full); return p

def login(pg, who):
    u, pw = USERS[who]
    pg.goto(BASE + "/login"); pg.fill("input[name=user]", u); pg.fill("input[name=password]", pw)
    pg.locator("button[type=submit]").first.click(); pg.wait_for_timeout(1500)

def new_ctx(b, who, w=1440, h=900):
    ctx = b.new_context(viewport={"width": w, "height": h}); pg = ctx.new_page(); login(pg, who); return ctx, pg
