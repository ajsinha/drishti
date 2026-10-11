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

"""Makes the pictures and the captured output of the <drishti-report> chapters (docs/guides/DISTRIBUTION.md section 5, REPORTS.md section 14, BI_GUIDE.md, BI_TUTORIAL.md
chapter 22, ELEMENTS.md) from the real stack (not a test: run it by hand).

    drishti-console/.venv/bin/python drishti-console/tests/embed_report_shots.py <pictures dir> <captures.json>

It starts what tests/test_embed_report_browser.py starts (the live stack with embedding on: server 18953, console 17953, the sample host application 17952), takes the pictures
and stops it all. Pictures: the host's page with the report in a card (desktop, 1200 px), after a click on a bar, one visual in a card, the same page at 390 px with the
report's phone layout, and the designer's Phone tab. Everything shown is real: the host is tools/elements-demo, the data is the live demo source."""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

from playwright.sync_api import sync_playwright

import test_embed_report_browser as T

S = T.S


def main(out: Path, captures: Path) -> None:
    for port in (S.SERVER_PORT, S.CONSOLE_PORT, T.HOST_PORT):
        assert not S.listening(port), f"port {port} is in use: stop whatever listens there"
    os.environ.update(DRISHTI_EMBED_ENABLED="true", DRISHTI_PUBLIC_URL=S.SERVER, DRISHTI_CONSOLE_URL=S.CONSOLE_URL)
    with tempfile.TemporaryDirectory() as tmp:
        stack = S.LiveStack(Path(tmp)).start()
        host = None
        try:
            secret = T.register_host()
            host = subprocess.Popen([sys.executable, str(T.ROOT / "tools/elements-demo/server.py"), f"--port={T.HOST_PORT}", f"--console={S.CONSOLE_URL}", f"--server={S.SERVER}",
                                     f"--secret={secret}", f"--app={T.APP}"], stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
            T._wait(T.HOST + "/api/me")
            shoot(out, captures)
        finally:
            if host:
                host.terminate()
                host.wait(10)
            stack.close()


def shoot(out: Path, captures: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    got: dict = {}
    with sync_playwright() as p:
        browser = p.chromium.launch()
        page = T.host_page(browser, viewport={"width": 1200, "height": 1000})
        T.ready(page)
        page.wait_for_function("() => window.__events.filter(e => e.name === 'tick').length >= 3", timeout=30000)
        page.wait_for_timeout(1200)
        page.locator(".stack > .card").first.screenshot(path=str(out / "host-report.png"))
        page.locator("#one").scroll_into_view_if_needed()
        page.locator(".card.narrow").screenshot(path=str(out / "host-one-visual.png"))
        got["events"] = [li for li in page.locator("#log li").all_inner_texts()][:8]
        got["state"] = page.evaluate("document.getElementById('rep').state")
        got["filters_default"] = page.evaluate("document.getElementById('rep').filters")
        got["tabs"] = page.locator("#rep [data-page]").all_inner_texts()
        got["trader_cells"] = page.locator('#rep [data-visual="movers"] tbody tr td:nth-child(3)').all_inner_texts()[:4]
        # a click on a bar: the host is told
        page.locator('#rep [data-visual="by-desk"] .bi-chart').scroll_into_view_if_needed()
        where = page.evaluate(T.MARK, ["#rep", "by-desk"])
        page.mouse.click(where["x"], where["y"])
        page.wait_for_function("() => window.__events.some(e => e.name === 'filter-changed' && e.detail.selections.length)", timeout=20000)
        page.wait_for_timeout(1500)
        got["filter_changed"] = [e for e in T.events(page, "filter-changed") if e["detail"]["selections"]][-1]["detail"]
        page.locator(".stack > .card").first.screenshot(path=str(out / "host-report-selected.png"))
        page.context.close()

        phone = T.host_page(browser, viewport={"width": 390, "height": 844}, is_mobile=True, has_touch=True)
        T.ready(phone)
        phone.wait_for_timeout(1500)
        phone.screenshot(path=str(out / "host-phone-390.png"))
        got["phone_order"] = phone.evaluate("[...document.querySelector('#rep').shadowRoot.querySelectorAll('.bi-vis')].map(v => v.dataset.visual)")
        phone.context.close()

        body = (T.ROOT / "config/bi/reports/live-trading-desk.report.yaml").read_text()
        body = (body.split("layouts:")[0] + "pages:" + body.split("\npages:", 1)[1]).replace("report: live-trading-desk", "report: phone-shots")
        status, made = S.api("POST", "/bi/workspaces/me/items", "wes", ["live-writer"], {"kind": "report", "title": "Live trading desk (phone)", "description": "", "body": body})
        assert status in (200, 201), made
        d = T.console_page(browser, "wes", f"/bi/design/{made['id']}", viewport={"width": 1600, "height": 1000})
        d.wait_for_selector("[data-design-root][data-ready=yes]", state="attached", timeout=30000)
        d.click('[data-tab="phone"]')
        for vis in ("rolling", "losses", "latest"):
            d.check(f'[data-phone-hide="{vis}"]')
        d.click('[data-phone-up="mtm"]')
        d.click('[data-phone-up="mtm"]')
        d.fill('[data-phone-rows="by-desk"]', "5")
        d.press('[data-phone-rows="by-desk"]', "Tab")
        d.wait_for_timeout(500)
        d.locator(".bi-phone-edit").screenshot(path=str(out / "designer-phone-tab.png"))
        d.click('[data-tab="yaml"]')
        d.wait_for_function("() => /layouts:/.test(document.querySelector('.CodeMirror').CodeMirror.getValue())", timeout=10000)
        got["yaml"] = "\n".join(d.evaluate("document.querySelector('.CodeMirror').CodeMirror.getValue()").splitlines()[:30])
        d.context.close()
        browser.close()
    got["integrity_head"] = json.loads(urllib.request.urlopen(f"{S.CONSOLE_URL}/embed/v1/elements/1.1.0/integrity.json").read())["files"]["drishti-elements.js"]
    captures.write_text(json.dumps(got, indent=2, ensure_ascii=False), encoding="utf-8")
    print("wrote", len(list(out.glob("*.png"))), "pictures and", captures)


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))
