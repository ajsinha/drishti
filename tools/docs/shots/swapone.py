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
"""Pictures of tutorial 23, add a source system (docs/guides/BUILD_GUIDE.md), written to docs/tutorials/img/swapone/, in the light
theme. The pictures follow the tutorial's steps, which this module runs itself, in order, on the scratch stack of tutorial 16's pictures
(t45's server and console, started here: its own copy of config/, no demo lake, sign-in off, the data inbox on) with the shipped
sample module source-swapone left out (DRISHTI_MODULES_SHIPPED=false), so the reader's own module is the only one. Nothing is written to the
repository's config/ or data/:

    DRISHTI_SHOTS_WORK=<an empty folder> DRISHTI_SHOTS_SERVER_PORT=<port> DRISHTI_SHOTS_CONSOLE_PORT=<port> \\
        drishti-console/.venv/bin/python tools/docs/screenshots.py --guide swapone

The data is the mocked SwapOne file of data/testdata/trades/swapone/ (tools/samplegen/src_swapone.py), made by docs/tutorials/data/swapone/make_day.py as swapone-2026-07-08.jsonl.gz.
Run the pictures all together and once: each step moves files on."""
from __future__ import annotations

import shutil
import time

import sys

import t45
from _tut import OUT, command, light
from shotlib import ROOT, Ctx, register
from t45 import at, cli, inbox, page, save, scrub, work  # noqa: F401

sys.path.insert(0, str(ROOT / "docs" / "tutorials" / "data" / "swapone"))
from make_day import make  # noqa: E402

SERVER_ENV = {**t45.SERVER_ENV, "DRISHTI_MODULES_SHIPPED": "false"}


def start_servers(w):
    """A scratch stack with no demo lake (the tutorial's own lake, swaplake, is the only one): a copy of config/, the shipped sample modules left
    out, sign-in off, the data inbox on; t45's server and console."""
    shutil.rmtree(w / "config", ignore_errors=True)
    shutil.copytree(ROOT / "config", w / "config", ignore=shutil.ignore_patterns("connectors"))
    (w / "config" / "dataload.yaml").write_text("dataload:\n  rules: []\n  unmatched: fail\n")
    for d in [p for p in (w / "data").glob("*")] + [w / "sutras", w / "modules"]:
        shutil.rmtree(d, ignore_errors=True) if d.is_dir() else d.unlink(missing_ok=True)
    (w / "sutras").mkdir(exist_ok=True)
    keep = t45.SERVER_ENV
    t45.SERVER_ENV = SERVER_ENV
    try:
        t45.PROCS.append(t45._server())
        try:
            t45._console(w)
            t45._up()
        except BaseException:
            for proc in t45.PROCS:
                t45._stop(proc)
            raise
        return t45.PROCS
    finally:
        t45.SERVER_ENV = keep


shot = register("swapone", OUT / "swapone")
DAY = "2026-07-08"
LAKE = """lake: swaplake                   # the file's name; its folder is data/delta/swaplake
format: delta
defaults:
  load-mode: replace
tables:
  trading.trades_shared:
    kind: trade
    partition-by: [SOURCE_SYSTEM]
    promote: [SOURCE_SYSTEM]
    sort-by: [id]
"""
ROLE = """drishti:
  security:
    roles:
      swapone-desk:
        kinds: [trade]
        rows:
          trade: "SOURCE_SYSTEM = 'SWAPONE'"
"""
STATE: dict = {}


def before(c: Ctx, _name: str) -> None:
    light(c)
    c.page.request.fetch(c.base + "/api/settings", method="PATCH", data='{"theme": "light"}', headers={"Content-Type": "application/json"})


def designer(c: Ctx) -> None:
    """The designer with the file read and the second mode chosen (what steps 1 to 3 do in the browser)."""
    page(c, "/build/pack/designer", 2500)
    c.page.evaluate("sessionStorage.clear()")
    page(c, "/build/pack/designer", 1500)
    f = make(work() / "data" / "swapone-drop", DAY)[0]
    c.page.set_input_files("[data-pane=data] .di-file-drop input[type=file]:not([webkitdirectory])", [str(f)])
    c.page.locator("[data-mode-box]:not([hidden])").wait_for(timeout=240_000)
    c.page.wait_for_timeout(1500)


def pick(c: Ctx, host: str, label: str) -> None:
    c.page.click(f"{host} .di-select-control")
    c.page.locator(".di-option", has_text=label).first.click()


@shot("01-analysed.jpg")
def analysed(c: Ctx):
    """Step 1: the whole file read; the person says what it is."""
    designer(c)
    save(c, "01-analysed.jpg")
    pick(c, "[data-sel-mode]", "A source for an existing kind")
    c.page.locator("[data-map-grid]").get_by_text("TRADE_ID", exact=True).first.wait_for(timeout=240_000)
    c.page.wait_for_timeout(1500)


@shot("02-map.jpg")
def mapped(c: Ctx):
    """Step 2: the proposed map, a wrong guess set to none (its evidence beside the grid)."""
    import re
    c.page.locator("[data-map-grid] .dg-row", has_text=re.compile(r"^COUNTERPARTY_UEN")).first.click()
    c.page.wait_for_timeout(800)
    c.page.locator("[data-map-grid] .dg-row", has_text=re.compile(r"^COUNTERPARTY_UEN")).locator(".dg-cell[data-c='2']").click()
    c.page.keyboard.press("Enter")                                   # the guess (a name for an id) is wrong: none
    c.page.locator(".di-option", has_text="(none)").first.click()
    c.page.wait_for_timeout(800)
    save(c, "02-map.jpg")


@shot("03-layout.jpg")
def layout(c: Ctx):
    """Step 2: the shared table and the connector YAML it makes."""
    c.page.click("[data-go=layout]")
    pick(c, "[data-sel-layout]", "A shared table")
    c.page.fill("[data-s=table]", "trading.trades_shared")
    c.page.wait_for_timeout(500)
    save(c, "03-layout.jpg")


@shot("04-made.jpg")
def made(c: Ctx):
    """Step 3: the module made, with its checks."""
    c.page.click("[data-go=finalize]")
    c.page.click("[data-finalize]")
    c.page.locator("[data-download]:not([hidden])").wait_for(timeout=480_000)
    c.page.wait_for_timeout(1000)
    save(c, "04-made.jpg")
    c.page.click("[data-go=deploy]")
    c.page.click("[data-install]")
    c.page.locator("[data-open]:not([hidden])").wait_for(timeout=240_000)
    time.sleep(8)                                                    # auto-deploy loads the module within seconds


@shot("05-module.jpg")
def module(c: Ctx):
    """Step 3: Admin, Modules, the module that is running."""
    page(c, "/admin/modules?module=source-swapone", 3000)
    save(c, "05-module.jpg")


@shot("06-inbox.jpg")
def inbound(c: Ctx):
    """Step 4 and 5: the file and its .ready dropped; the inbox loads it."""
    (work() / "config" / "lakes" / "swaplake.yaml").write_text(LAKE)
    target, _n, digest = make(inbox(), DAY)
    (inbox() / (target.name + ".ready")).write_text(digest + "\n")
    t45.idle()
    page(c, "/admin/loads", 3000)
    save(c, "06-inbox.jpg")


@shot("07-trd.jpg")
def trd(c: Ctx):
    """Step 6: TRD lists the SwapOne trades."""
    page(c, "/", 2500)
    command(c, "TRD")
    c.page.wait_for_timeout(5000)
    save(c, "07-trd.jpg")


@shot("08-trade.jpg")
def trade(c: Ctx):
    """Step 6: a SwapOne trade, with its own fields section."""
    page(c, "/", 2500)
    command(c, "TRD S1-700001")
    c.page.wait_for_timeout(5000)
    save(c, "08-trade.jpg")


@shot("09-blotter.jpg")
def blotter(c: Ctx):
    """Step 6: the blotter, filtered by source system."""
    page(c, "/live/live_trading_trade", 4000)
    save(c, "09-blotter.jpg")


@shot("10-rowrule.jpg")
def rowrule(c: Ctx):
    """Step 7: the role with its row rule."""
    page(c, "/admin/roles", 2500)
    save(c, "10-rowrule.jpg")


@shot("11-off.jpg")
def off(c: Ctx):
    """Step 8: switched off in Admin."""
    page(c, "/admin/modules?module=source-swapone", 3000)
    save(c, "11-off.jpg")
