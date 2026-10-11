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

"""Makes the pictures and the captured output of the wave 3 BI chapter (docs/guides/BI_TUTORIAL.md chapter 23, REPORTS.md, DATASETS.md) from the real stack (not a test: run it by hand).

    drishti-console/.venv/bin/python drishti-console/tests/bi_visuals_shots.py <pictures dir> <captures.json>

Ports 18954 (server) and 17954 (console) and the stack and the demo report of tests/test_bi_new_visuals_browser.py: a map, a funnel, a histogram, a box plot and a bubble
over two days of 60 trades, a matrix and a table with true totals, a dataset with a daily refresh."""
import json
import os
import sys
import tempfile
from pathlib import Path

os.environ.setdefault("DRISHTI_BI_REPORT_SERVER_PORT", "18954")
os.environ.setdefault("DRISHTI_BI_REPORT_CONSOLE_PORT", "17954")

from playwright.sync_api import sync_playwright  # noqa: E402

import bi_report_stack  # noqa: E402
from bi_report_helpers import open_report, settled, sign_in  # noqa: E402
from bi_report_stack import CONSOLE_URL, api  # noqa: E402
from test_bi_new_visuals_browser import DATASET, DATASET_YAML, REPORT, REPORT_YAML, Stack  # noqa: E402


def main(out: Path, captures: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    got = {}
    with tempfile.TemporaryDirectory() as tmp:
        stack = Stack(Path(tmp), delta_root="").start()
        try:
            with sync_playwright() as p:
                browser = p.chromium.launch()
                page = sign_in(browser, "rita")
                open_report(page, REPORT)
                settled(page)
                page.wait_for_function("() => ![...document.querySelectorAll('.bi-vis-empty')].some(e => /Loading the map/.test(e.textContent))", timeout=20000)
                page.wait_for_timeout(800)
                for name, sel in (("report-map", '[data-visual="world"]'), ("report-points", '[data-visual="points"]'), ("report-funnel", '[data-visual="steps"]'),
                                  ("report-histogram", '[data-visual="spread"]'), ("report-box", '[data-visual="boxes"]'), ("report-bubble", '[data-visual="bubbles"]'),
                                  ("report-matrix", '[data-visual="matrix"]'), ("report-table", '[data-visual="table"]'), ("report-cards", '[data-visual="ytd"]')):
                    page.locator(sel).scroll_into_view_if_needed()
                    page.locator(sel).screenshot(path=str(out / f"{name}.png"))
                page.evaluate("window.scrollTo(0, document.body.scrollHeight)")
                page.wait_for_timeout(500)
                page.evaluate("window.scrollTo(0, 0)")
                page.screenshot(path=str(out / "report-all.png"), full_page=True)
                page.context.close()

                page_wait = sign_in(browser, "wes")
                page_wait.wait_for_timeout(3500)                                      # the scheduler's first look (tick 2 s) plans the next run
                page_wait.context.close()
                ds = sign_in(browser, "wes")
                ds.goto(CONSOLE_URL + "/bi/d/file:dataset:" + DATASET)
                ds.wait_for_selector("[data-refresh] tbody tr", timeout=30000)
                ds.locator("[data-refresh-sec]").screenshot(path=str(out / "dataset-refresh.png"))
                ds.context.close()

                dz = sign_in(browser, "wes")
                dz.goto(CONSOLE_URL + "/bi/designer")
                dz.wait_for_selector("[data-new-dataset] option[value='file:dataset:" + DATASET + "']", state="attached")
                dz.select_option("[data-new-dataset]", "file:dataset:" + DATASET)
                dz.fill("[data-new-title]", "New visuals draft")
                dz.click("[data-new] button[type=submit]")
                dz.wait_for_selector("[data-design-root][data-ready=yes]", timeout=30000)
                dz.click('.bi-vtype[data-type="map"]')
                dz.wait_for_selector('[data-well="location"]')
                dz.wait_for_timeout(500)
                dz.locator("[data-fields-tree]").screenshot(path=str(out / "designer-fields.png"))
                dz.locator("[data-types]").screenshot(path=str(out / "designer-types.png"))
                dz.locator("[data-build]").screenshot(path=str(out / "designer-build-map.png"))
                dz.context.close()

                ph = sign_in(browser, "rita", viewport={"width": 390, "height": 844}, has_touch=True, is_mobile=True)
                open_report(ph, REPORT)
                settled(ph)
                ph.wait_for_timeout(800)
                ph.locator('[data-visual="world"]').scroll_into_view_if_needed()
                ph.locator('[data-visual="world"]').screenshot(path=str(out / "phone-map.png"))
                ph.locator('[data-visual="matrix"]').scroll_into_view_if_needed()
                ph.locator('[data-visual="matrix"]').screenshot(path=str(out / "phone-matrix.png"))
                ph.context.close()
                browser.close()

            body = {"page": "main", "state": {"dates": {"from": "2026-10-07", "to": "2026-10-08"}}}
            for vis in ("matrix", "table", "spread", "boxes", "ytd"):
                s, r = api("POST", f"/bi/reports/{REPORT}/query", "rita", ["risk"], dict(body, visual=vis))
                got[vis] = {"status": s, "columns": [c["name"] for c in r.get("columns", [])], "rows": r.get("rows", [])[:8], "totals": r.get("totals"), "meta": {k: r.get("meta", {}).get(k) for k in ("layer", "dates", "rows")}}
            s, r = api("GET", f"/bi/datasets/file:dataset:{DATASET}/refresh", "wes", ["bi-writer"])
            got["refresh"] = r
            s, r = api("POST", f"/bi/datasets/file:dataset:{DATASET}/refresh", "wes", ["bi-writer"], {})
            got["refresh-now"] = {"status": s, "body": r}
            import time

            time.sleep(4)
            s, r = api("GET", f"/bi/datasets/file:dataset:{DATASET}/refresh", "wes", ["bi-writer"])
            got["refresh-after"] = r
        finally:
            stack.close()
    captures.write_text(json.dumps(got, indent=1, default=str), encoding="utf-8")


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))
