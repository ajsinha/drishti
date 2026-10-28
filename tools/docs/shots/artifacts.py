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

"""The pictures of Admin -> Artifacts (docs/guides/OPERATIONALISING.md and tutorial 17), written to docs/guides/img/artifacts/.

The setup is its own: the scratch site of drishti-console/tests/artifact_stack.py (a pack the site always loads, three installed from archives of which one
extends another, a connector the pack reads, one nothing uses, and three BI files), on the scratch ports of DRISHTI_SHOTS_SERVER_PORT / DRISHTI_SHOTS_CONSOLE_PORT.

    DRISHTI_SHOTS_SERVER_PORT=18936 DRISHTI_SHOTS_CONSOLE_PORT=17936 \\
        drishti-console/.venv/bin/python tools/docs/screenshots.py --guide artifacts
"""
from __future__ import annotations

import os
import sys
import tempfile
from pathlib import Path

from shotlib import CONSOLE_PORT, ROOT, SERVER_PORT, Ctx, listening, register

OUT = ROOT / "docs" / "guides" / "img" / "artifacts"
shot = register("artifacts", OUT)


def start_servers(work: Path) -> list:
    for port in (SERVER_PORT, CONSOLE_PORT):
        if listening(port):
            sys.exit(f":{port} already answers: stop it first")
    os.environ["DRISHTI_ARTIFACTS_SERVER_PORT"] = str(SERVER_PORT)
    os.environ["DRISHTI_ARTIFACTS_CONSOLE_PORT"] = str(CONSOLE_PORT)
    sys.path.insert(0, str(ROOT / "drishti-console" / "tests"))
    import artifact_stack

    artifact_stack.SERVER_PORT, artifact_stack.CONSOLE_PORT = SERVER_PORT, CONSOLE_PORT
    artifact_stack.SERVER = f"http://127.0.0.1:{SERVER_PORT}"
    artifact_stack.CONSOLE_URL = f"http://127.0.0.1:{CONSOLE_PORT}"
    stack = artifact_stack.Stack(work).start()
    return list(stack.procs.values())


def wide(c: Ctx, w=1280, h=900):
    c.page.set_viewport_size({"width": w, "height": h})


def listing(c: Ctx):
    c.page.goto(c.base + "/admin/artifacts")
    c.page.wait_for_selector("tr[data-artifact]")
    c.page.wait_for_timeout(1200)


@shot("01-list.jpg")
def installed(c: Ctx):
    wide(c, 1280, 1000)
    listing(c)
    c.save("01-list.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 860})


@shot("02-refused.jpg")
def refused(c: Ctx):
    wide(c, 1280, 1000)
    listing(c)
    c.page.locator("tr[data-name='lab-base']").get_by_role("button", name="Uninstall lab-base").click()
    c.page.locator("[data-group='reports']").wait_for()
    c.page.wait_for_timeout(500)
    c.save("02-refused.jpg", "[data-uninstall-dialog]")
    c.page.locator("[data-art-keep]").click()


@shot("03-disabled.jpg", also=("03b-disabled-list.jpg",))
def disabled(c: Ctx):
    wide(c, 1280, 1000)
    listing(c)
    c.page.locator("tr[data-name='lab-base']").get_by_role("button", name="Uninstall lab-base").click()
    dlg = c.page.locator("[data-uninstall-dialog]")
    dlg.locator("[data-group='reports']").wait_for()
    dlg.locator("[data-art-disable]").click()                         # refused: lab-child builds on lab-base
    dlg.locator("[data-art-cascade]").wait_for(state="visible")
    dlg.locator("[data-art-cascade]").check()
    c.page.wait_for_timeout(400)
    c.save("03-disabled.jpg", "[data-uninstall-dialog]")
    dlg.locator("[data-art-go]").click()
    c.page.wait_for_selector("tr[data-name='lab-base'][data-state='off']")
    c.page.wait_for_timeout(800)
    c.save("03b-disabled-list.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 760})
    c.page.locator("tr[data-name='lab-child']").get_by_role("button", name="Enable lab-child").click()     # and back on: the other pictures need them
    c.page.wait_for_selector("tr[data-name='lab-child'][data-state='on']")


@shot("04-uninstall-unused.jpg", also=("05-uninstalled.jpg", "06-bi-upload.jpg"))
def unused(c: Ctx):
    wide(c, 1280, 1000)
    listing(c)
    c.page.locator("tr[data-name='scratch-conn']").get_by_role("button", name="Uninstall scratch-conn").click()
    c.page.locator("[data-art-text]:has-text('Nothing depends')").wait_for()
    c.page.wait_for_timeout(400)
    c.save("04-uninstall-unused.jpg", "[data-uninstall-dialog]")
    c.page.locator("[data-art-go]").click()
    c.page.wait_for_selector("[data-msg]:has-text('Uninstalled connector scratch-conn')")
    c.page.click("#artTabUninstalled")
    c.page.wait_for_timeout(600)
    c.save("05-uninstalled.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 640})
    c.page.get_by_role("button", name="Restore scratch-conn").click()                       # and back: nothing was lost
    c.page.wait_for_selector("[data-msg]:has-text('Restored scratch-conn')")
    c.page.click("#artTabInstalled")
    scratch = tempfile.TemporaryDirectory(prefix="drishti-art-")
    sample = Path(scratch.name) / "lab-more.dataset.yaml"
    sample.write_text("dataset: lab-more\ntitle: Lab more\ntables:\n  item:\n    source: { connector: lab-files, kind: lab-item }\n    columns:\n      itemId: { type: string, key: true }\n",
                      encoding="utf-8")
    try:
        c.page.set_input_files("[data-bi-file]", str(sample))
        c.page.locator("[data-bi-result]:has-text('Valid')").wait_for()
        c.page.wait_for_timeout(400)
        c.save("06-bi-upload.jpg", "[data-bi-install]")
    finally:
        scratch.cleanup()


@shot("07-phone.jpg", also=("08-phone-dialog.jpg",))
def phone(c: Ctx):
    c.page.set_viewport_size({"width": 390, "height": 844})
    listing(c)
    c.save("07-phone.jpg")
    c.page.locator("tr[data-name='lab-solo']").get_by_role("button", name="Uninstall lab-solo").click()
    c.page.locator("[data-art-text]:has-text('Nothing depends')").wait_for()
    c.page.wait_for_timeout(400)
    c.save("08-phone-dialog.jpg")
    c.page.locator("[data-art-keep]").click()
