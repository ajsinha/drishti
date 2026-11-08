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

"""Pictures of a hierarchy whose level column the data does not promote (docs/guides/VAR_GUIDE.md section 15.14), written to
docs/guides/img/varskip/: a VaR lake whose P&L-vector and scenario-PV tables do not promote LIMIT_LETTER (the market-risk pack copied into
the scratch folder with the column taken out of its layouts), read by a server whose pack configures the limit letters (LL). The date
loads, MGMT and LEGAL answer, and every row sits under LL's (Blank) letter: the tree shows *(Blank)* muted, in italics, with its ?, and
Admin -> Health says *Hierarchy warning* with the fix. Run it on its own:

    drishti-console/.venv/bin/python tools/docs/screenshots.py --guide varskip"""
from __future__ import annotations

import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request

from _var import open_var, painted
from shotlib import DEMO_ROOT_ENV
from shotlib import CONSOLE_PORT, ROOT, Ctx, register
from shotlib import start_servers as _start
if str(ROOT / "drishti-console") not in sys.path:
    sys.path.insert(0, str(ROOT / "drishti-console"))
from core.pack_paths import pack_dirs  # noqa: E402  (the one rule for where a pack's folder is)

shot = register("varskip")
SERVER_ENV = {"DRISHTI_SECURITY_ENABLED": "false"}   # marks the guide as one that runs on its own (--guide varskip)
KINDS = ",".join([f"market-risk/{k}" for k in ("pnl-vector", "scenario-pv", "fx-scenario", "scenario-set", "var-limit")] + ["core/desk", "core/book", "core/legal-entity"])


def start_servers(work) -> list:
    packs = work / "packs"
    packs.mkdir()
    for name, p in pack_dirs().items():
        if name != "market-risk":
            (packs / name).symlink_to(p)
    shutil.copytree(ROOT / "config" / "packs" / "banking" / "market-risk", packs / "market-risk")
    manifest = packs / "market-risk" / "pack.yaml"
    manifest.write_text("".join(line for line in manifest.read_text(encoding="utf-8").splitlines(keepends=True)
                                if line.strip() != "- LIMIT_LETTER"), encoding="utf-8")
    lake = work / "delta"
    r = subprocess.run([sys.executable, str(ROOT / "tools" / "drishti.py"), "lake", "demo", "--lake", str(lake), "--data-dir", str(work / "data"),
                        "--packs", str(packs), "--connectors-dir", str(work / "connectors"), "--as-of", "data", "--only", KINDS, "--no-signal", "-q",
                        "--engine", "auto"], capture_output=True, text=True, cwd=ROOT, timeout=900)
    if r.returncode != 0 or not (lake / "risk" / "pnl-vector").is_dir():
        sys.exit("could not build the lake without LIMIT_LETTER: " + (r.stderr or r.stdout)[-600:])
    procs = _start(work, {DEMO_ROOT_ENV: str(lake), "DRISHTI_CONNECTORS_DIR": str(work / "connectors"), "DRISHTI_DATA_DIR": str(work / "data"),
                          "DRISHTI_DEMO_ENABLED": "false", "DRISHTI_VAR_SYSTEM_BASKETS_SEED": "true", "DRISHTI_OFFLINE": "true", "DRISHTI_SECURITY_ENABLED": "false"})
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


@shot("01-hierarchy-blank.jpg")
def blank(c: Ctx):
    """The limit letters' tree: every row under the (Blank) letter, muted and in italics, its ? saying how many trades and the fix; the
    provenance's DRS-5211 warning on the right."""
    open_var(c, "/var?scope=BANK&hierarchy=LL")
    painted(c, "BANK")
    c.until("!!document.querySelector('[data-vx-tree] .dg-blank')", 60)
    c.page.wait_for_timeout(500)
    c.save("01-hierarchy-blank.jpg")


@shot("02-health-hierarchy-warning.jpg")
def health(c: Ctx):
    """Admin -> Health: Hierarchy warning (var LL LIMIT_LETTER pnl-vector) with the fix."""
    c.page.set_viewport_size({"width": 1400, "height": 900})
    c.page.goto(c.base + "/admin/health")
    c.until("document.body.innerText.includes('Hierarchy warning')", 90)
    top = c.page.evaluate("""(() => { const b = Array.from(document.querySelectorAll('p.t-warn b')).find(x => x.textContent.includes('Hierarchy warning'));
        return b.getBoundingClientRect().top + window.scrollY; })()""")
    c.page.set_viewport_size({"width": 1400, "height": int(top) + 200})     # the clip lies inside the window
    c.page.wait_for_timeout(800)
    c.save("02-health-hierarchy-warning.jpg", clip={"x": 80, "y": top - 16, "width": 1240, "height": 110})   # the warnings, not the chart above or the scratch paths below
