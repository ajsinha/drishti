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

"""Pictures of a hierarchy skipped because the data lacks its level column (docs/guides/VAR_GUIDE.md section 15.14), written to
docs/guides/img/varskip/: a VaR lake built as before 2.9.5, its P&L-vector and scenario-PV tables without LIMIT_LETTER (the market-risk pack
copied into the scratch folder with the column taken out of its layouts), read by a server whose pack configures the limit letters (LL).
The date loads, MGMT and LEGAL answer, LL is skipped: the explorer's select lists it *(not available)*, disabled, and Admin -> Health says
*Hierarchy skipped*. Run it on its own:

    drishti-console/.venv/bin/python tools/docs/screenshots.py --guide varskip"""
from __future__ import annotations

import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request

from _var import open_var, painted
from shotlib import CONSOLE_PORT, ROOT, Ctx, register
from shotlib import start_servers as _start

shot = register("varskip")
SERVER_ENV = {"DRISHTI_SECURITY_ENABLED": "false"}   # marks the guide as one that runs on its own (--guide varskip)
KINDS = ",".join(f"market-risk/{k}" for k in ("pnl-vector", "scenario-pv", "fx-scenario", "scenario-set", "risk-hierarchy", "portfolio", "var-limit"))


def start_servers(work) -> list:
    packs = work / "packs"
    packs.mkdir()
    for p in (ROOT / "config" / "packs").iterdir():
        if p.name != "market-risk":
            (packs / p.name).symlink_to(p)
    shutil.copytree(ROOT / "config" / "packs" / "market-risk", packs / "market-risk")
    manifest = packs / "market-risk" / "pack.yaml"
    manifest.write_text("".join(line for line in manifest.read_text(encoding="utf-8").splitlines(keepends=True)
                                if line.strip() != "- LIMIT_LETTER"), encoding="utf-8")
    lake = work / "delta"
    r = subprocess.run([sys.executable, str(ROOT / "tools" / "drishti.py"), "lake", "demo", "--lake", str(lake), "--data-dir", str(work / "data"),
                        "--packs", str(packs), "--connectors-dir", str(work / "connectors"), "--as-of", "data", "--only", KINDS, "--no-signal", "-q",
                        "--engine", "auto"], capture_output=True, text=True, cwd=ROOT, timeout=900)
    if r.returncode != 0 or not (lake / "risk" / "pnl-vector").is_dir():
        sys.exit("could not build the lake without LIMIT_LETTER: " + (r.stderr or r.stdout)[-600:])
    procs = _start(work, {"DRISHTI_DELTA_ROOT": str(lake), "DRISHTI_CONNECTORS_DIR": str(work / "connectors"), "DRISHTI_DATA_DIR": str(work / "data"),
                          "DRISHTI_DEMO_ENABLED": "false", "DRISHTI_OFFLINE": "true", "DRISHTI_SECURITY_ENABLED": "false"})
    deadline = time.monotonic() + 240
    while True:
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{CONSOLE_PORT}/api/var/scope?scope=BANK", timeout=60) as resp:
                if resp.status == 200:
                    break
        except (urllib.error.URLError, OSError):
            pass
        if time.monotonic() > deadline:
            sys.exit("the VaR engine did not answer within 240 s; see " + str(work))
        time.sleep(1)
    return procs


@shot("01-hierarchy-not-available.jpg")
def not_available(c: Ctx):
    """The hierarchy select opened as a list (a native select's popup is not drawn in a picture): Management, Legal entities and
    Limit letters (not available), greyed; the provenance's DRS-5211 warning on the right."""
    open_var(c, "/var?scope=BANK")
    painted(c, "BANK")
    c.until("Array.from(document.querySelectorAll('[data-vx-hierarchy] option')).some(o => o.disabled)", 60)
    c.page.evaluate("(() => { const s = document.querySelector('[data-vx-hierarchy]'); s.size = s.options.length; s.style.height = 'auto'; })()")
    c.page.wait_for_timeout(500)
    c.save("01-hierarchy-not-available.jpg")


@shot("02-health-hierarchy-skipped.jpg")
def health(c: Ctx):
    """Admin -> Health: Hierarchy skipped (var LL) with the reason and the fix."""
    c.page.set_viewport_size({"width": 1400, "height": 900})
    c.page.goto(c.base + "/admin/health")
    c.until("document.body.innerText.includes('Hierarchy skipped')", 90)
    top = c.page.evaluate("""(() => { const b = Array.from(document.querySelectorAll('p.t-warn b')).find(x => x.textContent.includes('Hierarchy skipped'));
        return b.getBoundingClientRect().top + window.scrollY; })()""")
    c.page.set_viewport_size({"width": 1400, "height": int(top) + 200})     # the clip lies inside the window
    c.page.wait_for_timeout(800)
    c.save("02-health-hierarchy-skipped.jpg", clip={"x": 80, "y": top - 200, "width": 1240, "height": 270})   # the paragraph, not the scratch paths below
