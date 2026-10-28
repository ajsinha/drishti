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

"""Pictures of tutorial 29, the risk lake and the official results on Iceberg (docs/tutorials/29-var-on-iceberg.md), written to
docs/tutorials/img/t29/. Its own stack, built as the tutorial says: the VaR test data (and MUREX's trades, so the lake holds a Delta
table beside the Iceberg ones) with `build_demo_lakes.py --as-of data --store risk=iceberg`, the official results to Iceberg under the
Iceberg root (application.local.yaml), then the scratch server and console, and var-results reset to the pack's default. Run it on its own:

    drishti-console/.venv/bin/python tools/docs/screenshots.py --guide t29

The scratch folder's path is shown as `data` in the pictures (the reader's own default data folder): the pages print the lake roots."""
from __future__ import annotations

import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

from _tut import OUT
from _var import open_var, painted, tab
from shotlib import CONSOLE_PORT, ROOT, SERVER_PORT, Ctx, register
from shotlib import start_servers as _start

shot = register("t29", OUT / "t29")
SERVER_ENV = {"DRISHTI_SECURITY_ENABLED": "false"}   # marks the guide as one that runs on its own (--guide t29)
VAR_KINDS = ",".join(f"market-risk/{k}" for k in ("pnl-vector", "scenario-pv", "fx-scenario", "scenario-set", "risk-hierarchy", "portfolio", "var-limit"))
WORK: list = []


def start_servers(work) -> list:
    """The tutorial's steps 1 to 3 on a scratch folder, then the stack."""
    WORK[:] = [str(work)]
    lake, ice, conns = work / "delta", work / "iceberg", work / "connectors"
    r = subprocess.run([sys.executable, str(ROOT / "tools" / "build_demo_lakes.py"), "--as-of", "data", "--lake", str(lake), "--connectors-dir", str(conns),
                        "--only", VAR_KINDS + ",trades/murex", "--store", "risk=iceberg"], capture_output=True, text=True, cwd=ROOT, timeout=1800)
    if r.returncode != 0 or not (ice / "risk" / "pnl-vector" / "metadata").is_dir():
        sys.exit("could not build the Iceberg risk lake: " + (r.stderr or r.stdout)[-800:])
    (work / "application.local.yaml").write_text(f"drishti:\n  var:\n    official:\n      format: iceberg\n      root: {ice}\n", encoding="utf-8")
    procs = _start(work, {"DRISHTI_DELTA_ROOT": str(lake), "DRISHTI_ICEBERG_ROOT": str(ice), "DRISHTI_CONNECTORS_DIR": str(conns),
                          "DRISHTI_DATA_DIR": str(work / "data"), "DRISHTI_LAKE_ROOTS": f"{lake},{ice}", "DRISHTI_DEMO_ENABLED": "false",
                          "DRISHTI_OFFLINE": "true", "DRISHTI_SECURITY_ENABLED": "false"})
    deadline = time.monotonic() + 300
    while True:
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{CONSOLE_PORT}/api/var/scope?scope=BANK", timeout=60) as resp:
                if resp.status == 200:
                    break
        except (urllib.error.URLError, OSError):
            pass
        if time.monotonic() > deadline:
            sys.exit("the VaR engine did not answer within 300 s; see " + str(work))
        time.sleep(1)
    for _ in range(100):                                 # the start's official capture published to Iceberg (step 3), then the reader reset (step 6)
        try:
            with urllib.request.urlopen(urllib.request.Request(f"http://127.0.0.1:{SERVER_PORT}/api/v1/var/official",
                                                                headers={"X-Drishti-User": "rita"}), timeout=60) as resp:
                if json.loads(resp.read()).get("published") is True:
                    break
        except (urllib.error.URLError, OSError, ValueError):
            pass
        time.sleep(3)
    req = urllib.request.Request(f"http://127.0.0.1:{SERVER_PORT}/api/v1/admin/connectors/var-results/reset", data=b"{}", method="POST",
                                 headers={"Content-Type": "application/json", "X-Drishti-User": "drishti-dev-admin"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        json.loads(resp.read() or b"{}")
    return procs


def tidy(c: Ctx) -> None:
    """The scratch folder's path printed as the reader's default data folder."""
    c.page.evaluate("""(w) => { const t = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT); let n;
        while ((n = t.nextNode())) { if (n.nodeValue.includes(w)) n.nodeValue = n.nodeValue.split(w + '/data').join('data').split(w).join('data'); } }""", WORK[0])


def page(c: Ctx, path: str, ready: str, width: int = 1400, height: int = 900) -> None:
    c.page.set_viewport_size({"width": width, "height": height})
    c.page.goto(c.base + path)
    c.until(ready, 90)
    c.page.wait_for_timeout(800)
    tidy(c)


@shot("01-bank-on-iceberg.jpg")
def bank(c: Ctx):
    """Step 4: VAR BANK read from the Iceberg risk lake: the same 112,041,646, the provenance naming risk-store."""
    open_var(c, "/var?scope=BANK")
    painted(c, "BANK")
    c.save("01-bank-on-iceberg.jpg")


@shot("02-connectors.jpg")
def connectors(c: Ctx):
    """Step 3: Admin -> Connectors: risk-store and exposure-store on iceberg, the trades on delta, var-results on iceberg."""
    page(c, "/admin/connectors", "document.body.innerText.includes('risk-store')", height=1000)
    c.page.fill("[data-filter]", "market-risk")
    c.page.wait_for_timeout(800)
    c.page.evaluate("window.scrollTo(0, document.querySelector('[data-filter]').getBoundingClientRect().top + window.scrollY - 70)")
    c.page.wait_for_timeout(400)
    tidy(c)
    c.save("02-connectors.jpg")


@shot("03-lakes.jpg")
def lakes(c: Ctx):
    """Step 5: Admin -> Lakes: the Format column, MUREX's trades in Delta beside the risk tables and the official results in Iceberg."""
    page(c, "/admin/lakes", "document.querySelectorAll('[data-lake-table]').length > 5", height=1100)
    c.page.evaluate("window.scrollTo(0, document.querySelector('[data-maint]').getBoundingClientRect().top + window.scrollY - 70)")
    c.page.wait_for_timeout(400)
    c.save("03-lakes.jpg")


@shot("04-var-results.jpg")
def var_results(c: Ctx):
    """Step 6: the var-results connector after Reset to pack default: plugin iceberg over drishti.var.official.root."""
    page(c, "/admin/connectors/var-results", "document.body.innerText.includes('iceberg')")
    c.save("04-var-results.jpg")


@shot("05-letters-on-iceberg.jpg")
def letters(c: Ctx):
    """Step 4: the limit letters' tree from Iceberg, LL-0011 chosen."""
    open_var(c, "/var?scope=LL:LL-0011&hierarchy=LL")
    painted(c, "LL:LL-0011")
    tab(c, "contrib")
    c.until("document.querySelectorAll('.vx-tree .vx-row[data-scope^=\"NODE:LL~LL-\"]').length > 3", 60)
    c.save("05-letters-on-iceberg.jpg")
