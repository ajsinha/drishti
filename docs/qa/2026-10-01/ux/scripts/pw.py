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

"""Shared Playwright helpers for the QA-UX run."""
import json, os, sys, time
from playwright.sync_api import sync_playwright

BASE = "http://localhost:17985"
SHOTS = os.path.join(os.path.dirname(__file__), "shots")
ERRS = []


def attach(page, tag):
    def con(msg):
        if msg.type in ("error", "warning"):
            ERRS.append({"tag": tag, "url": page.url, "type": "console." + msg.type, "text": msg.text[:400]})
    page.on("console", con)
    page.on("pageerror", lambda e: ERRS.append({"tag": tag, "url": page.url, "type": "pageerror", "text": str(e)[:400]}))
    page.on("requestfailed", lambda r: ERRS.append({"tag": tag, "url": page.url, "type": "requestfailed", "text": r.url + " " + str(r.failure)}) if "stream" not in r.url else None)
    page.on("response", lambda r: ERRS.append({"tag": tag, "url": page.url, "type": "http%d" % r.status, "text": r.url}) if r.status >= 400 else None)


def shot(page, name, full=False):
    p = os.path.join(SHOTS, name + ".png")
    page.screenshot(path=p, full_page=full)
    return p


def overflow(page):
    return page.evaluate("() => ({sw: document.documentElement.scrollWidth, cw: document.documentElement.clientWidth})")


def dump_errs(path):
    with open(path, "w") as f:
        json.dump(ERRS, f, indent=1)
