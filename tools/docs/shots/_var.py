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

"""Shared pieces of the VaR pictures (the guide docs/guides/VAR_GUIDE.md, module var; tutorial 25, module t25): a scratch server and
console over the market-risk pack's committed VaR test data (9,572 trades of 2026-09-30, 88 hierarchy nodes, 28 portfolios), built into
a scratch lake exactly as drishti-console/tests/test_var_explorer_browser.py builds it (`drishti.py lake demo --only` the four VaR
kinds), the demo connector off, offline. The engine is waited for (the bank's VaR answers) before the first picture."""
from __future__ import annotations

import subprocess
import sys
import time
import urllib.error
import urllib.request

from shotlib import CONSOLE_PORT, ROOT, SERVER_PORT, Ctx
from shotlib import start_servers as _start

VAR_DATA = ",".join(f"market-risk/{k}" for k in ("pnl-vector", "scenario-set", "risk-hierarchy", "portfolio"))
WIDE, HIGH = 1600, 1000


def start_servers(work) -> list:
    """Builds the VaR lake under the scratch folder, then the scratch server and console reading it."""
    lake = work / "delta"
    r = subprocess.run([sys.executable, str(ROOT / "tools" / "drishti.py"), "lake", "demo", "--lake", str(lake), "--data-dir", str(work / "data"),
                        "--connectors-dir", str(work / "connectors"), "--as-of", "data", "--only", VAR_DATA, "--no-signal", "-q",
                        "--engine", "auto"], capture_output=True, text=True, cwd=ROOT, timeout=900)
    if r.returncode != 0 or not (lake / "risk" / "pnl-vector").is_dir():
        sys.exit("could not build the VaR lake: " + (r.stderr or r.stdout)[-600:])
    procs = _start(work, {"DRISHTI_DELTA_ROOT": str(lake), "DRISHTI_DEMO_ENABLED": "false", "DRISHTI_OFFLINE": "true",
                          "DRISHTI_SECURITY_ENABLED": "false"})
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


def server() -> str:
    return f"http://127.0.0.1:{SERVER_PORT}"


def open_var(c: Ctx, path: str, width: int = WIDE, height: int = HIGH) -> None:
    c.page.set_viewport_size({"width": width, "height": height})
    c.page.goto(c.base + path)


def painted(c: Ctx, scope: str, seconds: int = 90) -> None:
    c.until("document.querySelector('[data-var]') && document.querySelector('[data-var]').dataset.painted === %r" % scope, seconds)
    c.page.wait_for_timeout(700)                                       # the charts finish drawing


def tab(c: Ctx, name: str) -> None:
    c.page.locator(f'.vx-tab[data-tab="{name}"]').click()
    c.until("document.querySelector('[data-var]').dataset.tabPainted === %r" % name, 60)
    c.page.wait_for_timeout(600)
