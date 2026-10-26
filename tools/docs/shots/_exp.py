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

"""Shared pieces of the exposure pictures (the guide docs/guides/EXPOSURE_GUIDE.md, module exp; tutorial 26, module t26): a scratch
server and console over the demo lake's exposure run (`drishti.py lake demo --exposure demo`, the default: the run XR-2026-09-30-01
generated for 73 netting sets of 22 counterparties, 200 paths, 24 grid dates; the committed eight sets inside it with the same figures)
with the netting sets, CSAs, collateral balances, credit limits, counterparties, groups and legal entities beside them, built into a
scratch lake (`drishti.py lake demo --only` those kinds, the business dates as the data has them), the demo connector off, offline. The engine is
waited for (the bank's exposure answers) before the first picture."""
from __future__ import annotations

import subprocess
import sys
import time
import urllib.error
import urllib.request

from shotlib import CONSOLE_PORT, ROOT, SERVER_PORT, Ctx
from shotlib import start_servers as _start

EXP_DATA = ",".join(["counterparty-risk/" + k for k in ("simulation-run", "exposure-path", "netting-set", "csa", "collateral-balance", "credit-limit")]
                    + ["core/" + k for k in ("counterparty", "counterparty-group", "legal-entity")])
WIDE, HIGH = 1600, 1000


def build_lake(work, as_of: str = "data"):
    """The scratch lake of the exposure kinds under ``work``; returns its root."""
    lake = work / "delta"
    r = subprocess.run([sys.executable, str(ROOT / "tools" / "drishti.py"), "lake", "demo", "--lake", str(lake), "--data-dir", str(work / "data"),
                        "--connectors-dir", str(work / "connectors"), "--as-of", as_of, "--only", EXP_DATA, "--no-signal", "-q",
                        "--engine", "auto"], capture_output=True, text=True, cwd=ROOT, timeout=900)
    if r.returncode != 0 or not (lake / "risk" / "exposure-path").is_dir():
        raise RuntimeError("could not build the exposure lake: " + (r.stderr or r.stdout)[-600:])
    return lake


def start_servers(work) -> list:
    """Builds the exposure lake under the scratch folder, then the scratch server and console reading it."""
    try:
        lake = build_lake(work)
    except RuntimeError as e:
        sys.exit(str(e))
    procs = _start(work, {"DRISHTI_DELTA_ROOT": str(lake), "DRISHTI_DEMO_ENABLED": "false", "DRISHTI_OFFLINE": "true",
                          "DRISHTI_SECURITY_ENABLED": "false", "DRISHTI_COMPUTE_ENABLED": "true", "DRISHTI_PYTHON": sys.executable})
    deadline = time.monotonic() + 300
    while True:
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{CONSOLE_PORT}/api/exposure/scope?scope=BANK", timeout=60) as resp:
                if resp.status == 200:
                    break
        except (urllib.error.URLError, OSError):
            pass
        if time.monotonic() > deadline:
            sys.exit("the exposure engine did not answer within 300 s; see " + str(work))
        time.sleep(1)
    return procs


def server() -> str:
    return f"http://127.0.0.1:{SERVER_PORT}"


def open_exp(c: Ctx, path: str, width: int = WIDE, height: int = HIGH) -> None:
    c.page.set_viewport_size({"width": width, "height": height})
    c.page.goto(c.base + path)


def painted(c: Ctx, scope: str, seconds: int = 120) -> None:
    c.until("document.querySelector('[data-exposure]') && document.querySelector('[data-exposure]').dataset.painted === %r" % scope, seconds)
    c.until("document.querySelector('[data-exposure]').dataset.tabPainted !== undefined", seconds)
    c.page.wait_for_timeout(800)                                       # the chart and the fan finish drawing


def tab(c: Ctx, name: str) -> None:
    c.page.locator(f'.xx-tab[data-tab="{name}"]').click()
    c.until("document.querySelector('[data-exposure]').dataset.tabPainted === %r" % name, 90)
    c.page.wait_for_timeout(500)
