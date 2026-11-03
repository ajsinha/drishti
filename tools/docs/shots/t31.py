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

"""Pictures of tutorial 31, news held in memory only (docs/tutorials/31-news-in-memory-only.md), written to docs/tutorials/img/t31/. Run it
on its own: it starts its own scratch server with the in-memory lake on, the example connector news-store (root: mem://news) in a scratch
connectors folder and the example store and tables (config/examples/in-memory-lake.yaml) in the scratch folder's application.local.yaml:

    drishti-console/.venv/bin/python tools/docs/screenshots.py --guide t31

The pictures, in the tutorial's order: Admin → Connectors on news-store, Admin → Lakes with the store and its four tables, a news page, a
quote and a company read from memory, the Data view, and Admin → Health."""
from __future__ import annotations

import shutil
import time
import urllib.error
import urllib.request

from _tut import OUT
from shotlib import CONSOLE_PORT, ROOT, Ctx, register
from shotlib import start_servers as _start

shot = register("t31", OUT / "t31")
SERVER_ENV = {"DRISHTI_SECURITY_ENABLED": "false"}   # marks the guide as one that runs on its own (--guide t31): it starts its own stack
WIDE, HIGH = 1600, 1000


def start_servers(work) -> list:
    """The example connector and store in the scratch folder (the sources' relative paths resolve there: data/testdata and config are
    links to the repository's), then the scratch server with the in-memory lake on; waits for the warm-up to fill the news table."""
    connectors = work / "connectors"
    connectors.mkdir(parents=True, exist_ok=True)
    shutil.copy(ROOT / "config" / "connectors.examples" / "memory" / "news-store.yaml", connectors / "news-store.yaml")
    shutil.copy(ROOT / "config" / "examples" / "in-memory-lake.yaml", work / "application.local.yaml")
    lakes = work / "lakes"                              # the demo lake and the store news (a lake: config/lakes.examples/memory/news.yaml)
    lakes.mkdir(parents=True, exist_ok=True)
    shutil.copy(ROOT / "config" / "lakes" / "demo.yaml", lakes / "demo.yaml")
    shutil.copy(ROOT / "config" / "lakes.examples" / "memory" / "news.yaml", lakes / "news.yaml")
    (work / "data").mkdir(parents=True, exist_ok=True)
    if not (work / "data" / "testdata").exists():
        (work / "data" / "testdata").symlink_to(ROOT / "data" / "testdata")
    procs = _start(work, {**SERVER_ENV, "DRISHTI_CONNECTORS_DIR": str(connectors), "DRISHTI_LAKES_DIR": str(lakes), "DRISHTI_MEMORY_LAKE_ENABLED": "true",
                          "DRISHTI_DEMO_ENABLED": "false", "DRISHTI_OFFLINE": "true"})
    deadline = time.monotonic() + 240
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{CONSOLE_PORT}/admin/lakes", timeout=60) as resp:
                if "mem://news" in resp.read().decode("utf-8", "replace"):
                    break
        except (urllib.error.URLError, OSError):
            pass
        time.sleep(2)
    time.sleep(5)                                       # the warm-up's four tables
    return procs


def _open(c: Ctx, path: str, wait_ms: int = 2500) -> None:
    c.page.set_viewport_size({"width": WIDE, "height": HIGH})
    c.page.goto(c.base + path)
    c.page.wait_for_timeout(wait_ms)


@shot("01-connector.jpg")
def connector(c: Ctx):
    _open(c, "/admin/connectors?q=news-store")
    c.save("01-connector.jpg")


@shot("02-lakes.jpg")
def lakes(c: Ctx):
    _open(c, "/admin/lakes")
    c.until("document.querySelector('#lkMemory') !== null", 60)
    c.page.locator("#lkMemory").scroll_into_view_if_needed()
    c.page.evaluate("document.querySelector('#lkMemory').scrollIntoView({block: 'start'})")
    c.page.wait_for_timeout(600)
    c.save("02-lakes.jpg")


@shot("03-news.jpg")
def news(c: Ctx):
    _open(c, "/v/news/NEWS-Apple", 4000)
    c.save("03-news.jpg")


@shot("04-quote.jpg")
def quote(c: Ctx):
    _open(c, "/v/quote/QUOTE-AAPL", 4000)
    c.save("04-quote.jpg")


@shot("05-data.jpg")
def data(c: Ctx):
    _open(c, "/data?find=news", 4000)
    c.save("05-data.jpg")


@shot("06-health.jpg")
def health(c: Ctx):
    _open(c, "/admin/health", 4000)
    c.save("06-health.jpg", locator="[data-health-busy]")          # the busy protection card with the store's gauge
