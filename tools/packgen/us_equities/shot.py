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

"""The picture of the us-equities pack's guide (config/packs/capitalmarkets/us-equities/guides/us-equities.md), written to its guides/images/.

The pack is off by default, so this script starts its own scratch server and console with it on (18742/18743 unless
DRISHTI_SHOTS_SERVER_PORT / DRISHTI_SHOTS_CONSOLE_PORT say otherwise), takes the picture and stops them (build the server first):

    drishti-console/.venv/bin/python tools/packgen/us_equities/shot.py [--work DIR]
"""
from __future__ import annotations

import argparse
import os
import tempfile
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "docs"))
from shotlib import Ctx, start_pack_servers  # noqa: E402

ROOT = Path(__file__).resolve().parents[3]
PACKS = "core,world-economy,news-and-markets,us-equities"
SERVER_PORT = int(os.environ.get("DRISHTI_SHOTS_SERVER_PORT", 18742))
CONSOLE_PORT = int(os.environ.get("DRISHTI_SHOTS_CONSOLE_PORT", 18743))
OUT = ROOT / "config" / "packs" / "capitalmarkets" / "us-equities" / "guides" / "images"


def start_servers(work: Path) -> list:
    return start_pack_servers(work, PACKS, SERVER_PORT, CONSOLE_PORT)


def company(c: Ctx):
    """Apple's page with its latest filing opened beside the filings table."""
    c.page.set_viewport_size({"width": 1600, "height": 1300})
    c.page.goto(c.base + "/v/us-company/AAPL")
    c.page.locator("[data-panel]").first.wait_for(timeout=60000)
    c.page.wait_for_timeout(2500)
    rows = c.page.locator('[data-panel="filings"] tbody tr')
    if rows.count():
        rows.first.click()
        c.page.wait_for_timeout(2500)
    OUT.mkdir(parents=True, exist_ok=True)
    c.page.screenshot(path=str(OUT / "us-equities-company.jpg"), type="jpeg", quality=84)
    print("wrote", (OUT / "us-equities-company.jpg").relative_to(ROOT))
    c.page.set_viewport_size({"width": 1440, "height": 900})



def main() -> int:
    ap = argparse.ArgumentParser(description="The us-equities guide's picture")
    ap.add_argument("--work", type=Path, help="the scratch server's folder (default: a new temporary one)")
    a = ap.parse_args()
    work = a.work or Path(tempfile.mkdtemp(prefix="drishti-useq-shot-"))
    work.mkdir(parents=True, exist_ok=True)
    from playwright.sync_api import sync_playwright
    procs = start_servers(work)
    try:
        with sync_playwright() as p:
            browser = p.chromium.launch()
            company(Ctx(browser.new_page(viewport={"width": 1440, "height": 900}), f"http://127.0.0.1:{CONSOLE_PORT}"))
            browser.close()
    finally:
        for proc in procs:
            proc.terminate()
        for proc in procs:
            proc.wait(timeout=60)
    return 0


if __name__ == "__main__":
    sys.exit(main())
