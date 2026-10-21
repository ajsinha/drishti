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

"""The pictures of BI runs for docs/guides/BI_RUNS_GUIDE.md, from the stand-in server of bi_runs_backend.py (synthetic data only):
the Runs tab in a Terminal theme, the run card on a report while its run runs, and the drawer of a finished run in the light theme.

  drishti-console/.venv/bin/python drishti-console/tests/bi_runs_shots.py     (writes docs/guides/img/bi-runs/*.png)
"""
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path[:0] = [str(HERE), str(HERE.parent)]

import conftest  # noqa: E402,F401  (the console's data folder for a run outside pytest)
from bi_runs_backend import REPORT_ID, RunsBackend, serve  # noqa: E402
from playwright.sync_api import sync_playwright  # noqa: E402

OUT = HERE.parents[1] / "docs/guides/img/bi-runs"


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    backend = RunsBackend(run_seconds=600.0)
    running = backend._new("MTM by desk and month")
    running["_t0"] -= 170                                                 # under way for a while: about 28 % built
    url, backend, stop = serve(backend)
    try:
        with sync_playwright() as p:
            b = p.chromium.launch()
            ctx = b.new_context(viewport={"width": 1440, "height": 860}, device_scale_factor=1, bypass_csp=True)
            page = ctx.new_page()
            page.goto(url + "/bi/w/me?tab=runs")
            page.wait_for_selector(f'[data-run-row="{running["id"]}"][data-run-state="running"]', timeout=15000)
            page.evaluate("document.documentElement.setAttribute('data-theme', 'terminal-green')")
            page.wait_for_timeout(1200)
            page.screenshot(path=str(OUT / "runs-tab-terminal.png"), full_page=False)

            page = ctx.new_page()
            page.goto(url + f"/bi/r/{REPORT_ID}")
            page.wait_for_selector('[data-visual="mtm-by-desk-month"] [data-run-go]', timeout=15000)
            page.click('[data-visual="mtm-by-desk-month"] [data-run-go]')
            page.wait_for_selector('[data-visual="mtm-by-desk-month"] [role=progressbar]', timeout=15000)
            newest = max(backend.runs.values(), key=lambda r: r["_t0"])
            newest["_t0"] -= 170                                              # under way for a while
            page.wait_for_timeout(2500)
            page.locator(".bi-rgrid").screenshot(path=str(OUT / "run-card-running.png"))

            done = next(r for r in backend.runs.values() if r["title"] == "Notional by counterparty")
            done["shared"]["with"] = [{"recipient": "bob", "mode": "re-masked", "recipientRun": None}]
            page = ctx.new_page()
            page.goto(url + f"/bi/w/me?tab=runs&run={done['id']}")
            page.wait_for_selector("[data-run-drawer]:not([hidden]) [data-run-drawer-state='done']", timeout=15000)
            page.evaluate("document.documentElement.setAttribute('data-theme', 'light')")
            page.wait_for_timeout(800)
            page.screenshot(path=str(OUT / "run-drawer-light.png"))
            b.close()
    finally:
        stop()
    for f in sorted(OUT.glob("*.png")):
        print(f)


if __name__ == "__main__":
    main()
