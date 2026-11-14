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

"""Shared pieces of the capital and margin pictures (the FRTB-SA tutorial, FRTB-SA, module t30; the SIMM tutorial, SIMM, module t32): the VaR pictures'
scratch lake (see _var.py) with the position sensitivities, the factor dictionary and the counterparty-risk pack's netting sets and CSAs
added, and the toy SIMM parameter file of the tests installed as a version (round illustrative numbers, never ISDA's)."""
from __future__ import annotations

import os
import shutil
import time
import urllib.error
import urllib.request

import _var
from shotlib import DEMO_ROOT_ENV
from shotlib import CONSOLE_PORT, ROOT, Ctx
from shotlib import start_servers as _start

MORE_DATA = ["market-risk/position-sensitivity", "market-risk/risk-factor", "counterparty-risk/netting-set", "counterparty-risk/csa",
             "counterparty-risk/counterparty"]
SERVER_ENV = {"DRISHTI_SECURITY_ENABLED": "false"}   # marks the guide as one that runs on its own (--guide t30): it starts its own stack


def start_servers(work) -> list:
    """The toy SIMM version in the scratch folder's simm/, then the VaR scratch stack over the larger lake."""
    simm = work / "simm"
    simm.mkdir(parents=True, exist_ok=True)
    shutil.copy(ROOT / "modules" / "banking" / "banking-compute" / "src" / "test" / "resources" / "simm" / "simm-toy.yaml", simm / "simm-toy.yaml")
    os.environ["DRISHTI_SIMM_PARAMETERS"] = str(simm)          # read by the scratch server started below (it inherits the environment)
    lake = work / "delta"
    if (lake / "risk" / "position-sensitivity").is_dir():       # a kept scratch folder (DRISHTI_SHOTS_WORK) already holds the lake: start on it
        _var.LAKE[:] = [lake]
        procs = _start(work, {DEMO_ROOT_ENV: str(lake), "DRISHTI_DEMO_ENABLED": "false", "DRISHTI_OFFLINE": "true",
                              "DRISHTI_SECURITY_ENABLED": "false"})
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{CONSOLE_PORT}/api/var/scope?scope=BANK", timeout=60) as resp:
                    if resp.status == 200:
                        break
            except (urllib.error.URLError, OSError):
                pass
            time.sleep(1)
        return procs
    _var.VAR_DATA = ",".join([_var.VAR_DATA] + MORE_DATA)       # only when this guide runs on its own (--guide t30 or t32)
    return _var.start_servers(work)


def settled(c: Ctx, meta: str, seconds: int = 120) -> None:
    """Waits until the page's meta line is filled (not loading) or its notice speaks, then for the drawing to finish."""
    c.until("(() => { const m = document.querySelector('%s'); const n = document.querySelector('.pg-notice');"
            " return (m && m.textContent.trim() && m.textContent.indexOf('loading') < 0) || (n && !n.hidden); })()" % meta, seconds)
    c.page.wait_for_timeout(900)
