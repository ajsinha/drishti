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
the Runs tab in a Terminal theme, the run card on a report while its run runs, the drawer of a finished run in the light theme, its sharing and schedule
in the Terminal theme, and the rollup suggestions of Admin → BI → Runs.

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

            # sharing and a schedule: each recipient's mode as the server decided it, the next firing, the schedule form's presets
            done["shared"]["with"] = [{"recipient": "bob", "mode": "re-masked", "recipientRun": None}, {"recipient": "dave", "mode": "same", "recipientRun": None},
                                      {"recipient": "carol", "mode": "recomputed", "recipientRun": "r-java-2-01JAC4Q8Z6T3M4R9B0X1Y00042"}]
            backend.schedules["sch-1"] = {"id": "sch-1", "runId": done["id"], "title": done["title"], "source": done["source"], "schedule": "business-days 07:30", "data": "latest",
                                          "enabled": True, "lastRun": None, "nextAt": "2026-10-22T07:30:00Z", "createdAt": "2026-10-21T09:00:00Z", "owner": backend.user}
            done["schedule"] = {k: v for k, v in backend.schedules["sch-1"].items() if k != "owner"}
            page = ctx.new_page()
            page.set_viewport_size({"width": 1440, "height": 1000})
            page.goto(url + f"/bi/w/me?tab=runs&run={done['id']}")
            page.wait_for_selector("[data-run-drawer]:not([hidden]) [data-run-share='carol']", timeout=15000)
            page.evaluate("document.documentElement.setAttribute('data-theme', 'terminal')")
            page.click("[data-run-sched-edit]")
            page.select_option("[data-run-sched-form] select[name=kind]", "weekly")
            page.select_option("[data-run-sched-form] select[name=day]", "mon")
            page.fill("[data-run-sched-form] input[name=time]", "08:00")
            page.evaluate("document.querySelector('[data-run-drawer]').scrollTop = 10000")
            page.wait_for_timeout(800)
            page.locator("[data-run-drawer]").screenshot(path=str(OUT / "run-share-schedule.png"))

            # Admin → BI → Runs: the repeated heavy questions with Accept and Dismiss, one rollup's YAML open
            page = ctx.new_page()
            page.set_viewport_size({"width": 1440, "height": 900})
            page.goto(url + "/admin/bi/runs#suggestions")
            page.wait_for_selector('[data-sugg="sg-1"] [data-sugg-accept]', timeout=15000)
            page.click('[data-sugg="sg-1"] summary')
            page.evaluate("document.documentElement.setAttribute('data-theme', 'light')")
            page.wait_for_timeout(800)
            page.set_viewport_size({"width": 1440, "height": 640})
            page.evaluate("window.scrollTo(0, document.getElementById('suggestions').getBoundingClientRect().top + window.scrollY - 90)")
            page.wait_for_timeout(300)
            page.screenshot(path=str(OUT / "rollup-suggestions.png"))
            b.close()
    finally:
        stop()
    for f in sorted(OUT.glob("*.png")):
        print(f)


if __name__ == "__main__":
    main()
