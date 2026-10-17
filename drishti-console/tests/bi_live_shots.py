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

"""Makes the pictures and the captured output of docs/tutorials/10-first-bi-report.md (chapters 18 and 19), BOARDS.md, REPORTS.md and DATASETS.md from the real stack (not a
test: run it by hand).

    drishti-console/.venv/bin/python drishti-console/tests/bi_live_shots.py <pictures dir> <captures.json>

Ports 18995 (server) and 17995 (console), a scratch directory, the shipped live samples over the demo source ticking every 200 ms (750 trades of which 360 tick, 66 netting sets).
Pictures are desktop (1600 px: the top bar needs it) and the phone (390 px)."""
import json
import sys
import tempfile
import time
import urllib.request
from pathlib import Path

from playwright.sync_api import sync_playwright

import bi_live_stack as S
from bi_live_stack import BOARD, CONSOLE_URL, EXPOSURE, PASSWORD, REPORT


def sign_in(browser, user="rita", **ctx):
    context = browser.new_context(**{"viewport": {"width": 1600, "height": 900}, **ctx})
    page = context.new_page()
    page.goto(CONSOLE_URL + "/login?next=/bi")
    page.fill("#lu", user)
    page.fill("#lp", PASSWORD)
    page.click("button[type=submit]")
    page.wait_for_url("**/bi")
    return page


def ready(page, path):
    page.goto(CONSOLE_URL + path)
    page.wait_for_selector("[data-ready=yes]", state="attached", timeout=30000)
    page.wait_for_function("() => !document.querySelector('.bi-vis-busy') && document.querySelectorAll('.bi-vis').length > 0", timeout=30000)
    page.wait_for_function("() => document.querySelector('.bi-live-state').getAttribute('data-live-state') === 'live'", timeout=30000)
    page.wait_for_timeout(2500)                                      # a few updates have drawn over the first picture


def stream_events(path: str, user: str, roles: list, count: int, seconds: float = 8.0) -> list:
    """The first events of a server stream, as curl -N shows them."""
    sys.path.insert(0, str(S.CONSOLE))
    from core.auth import mint_token

    req = urllib.request.Request(S.SERVER + "/api/v1" + path, headers={"Authorization": "Bearer " + mint_token(user, roles, S.SECRET, 300), "Accept": "text/event-stream"})
    out, event = [], None
    end = time.time() + seconds
    with urllib.request.urlopen(req, timeout=seconds) as r:
        for raw in r:
            line = raw.decode().rstrip("\n")
            if line.startswith("event:"):
                event = line[6:].strip()
            elif line.startswith("data:") and event:
                out.append({"event": event, "data": json.loads(line[5:])})
                if len(out) >= count or time.time() > end:
                    break
    return out


def main(out: Path, captures: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    got = {}
    with tempfile.TemporaryDirectory() as tmp:
        stack = S.LiveStack(Path(tmp)).start()
        try:
            with sync_playwright() as p:
                browser = p.chromium.launch()

                # ---- the live report ---------------------------------------------------------------------------------------------
                page = sign_in(browser, "rita")
                ready(page, f"/bi/r/{REPORT}")
                page.screenshot(path=str(out / "live-desk.png"))
                page.locator("[data-live-bar]").screenshot(path=str(out / "live-indicator.png"))
                page.click("[data-live-pause]")
                page.wait_for_timeout(400)
                page.locator("[data-live-bar]").screenshot(path=str(out / "live-paused.png"))
                page.click("[data-live-pause]")

                ready(page, f"/bi/r/{REPORT}/grid")
                page.screenshot(path=str(out / "perspective-grid.png"))
                ready(page, f"/bi/r/{REPORT}/pivot")
                page.screenshot(path=str(out / "perspective-pivot.png"))

                # the dataset page says how its live table is doing
                page.goto(f"{CONSOLE_URL}/bi/d/file:dataset:live-trading")
                page.wait_for_function("() => document.querySelector('[data-layers] tbody td') && document.querySelector('[data-layers] tbody').textContent.includes('in memory')", timeout=30000)
                page.locator("[data-layers]").screenshot(path=str(out / "dataset-live-layers.png"))

                # a connection that drops: the console is stopped, the page says so, a new one redraws everything
                ready(page, f"/bi/r/{REPORT}")
                stack.stop_console()
                page.wait_for_function("() => document.querySelector('.bi-live-state').getAttribute('data-live-state') === 'disconnected'", timeout=60000)
                page.locator("[data-live-bar]").screenshot(path=str(out / "live-disconnected.png"))
                stack.start_console()
                page.wait_for_function("() => document.querySelector('.bi-live-state').getAttribute('data-live-state') === 'live'", timeout=90000)

                # ---- the same grid for a reader without raw -----------------------------------------------------------------------
                ann = sign_in(browser, "ann")
                ready(ann, f"/bi/r/{REPORT}/grid")
                ann.screenshot(path=str(out / "perspective-grid-masked.png"))

                # ---- the board ---------------------------------------------------------------------------------------------------
                wall = sign_in(browser, "rita")
                ready(wall, f"/bi/b/{BOARD}")
                wall.screenshot(path=str(out / "board-wall.png"))
                wall.click("[data-pages] a:nth-child(2)")
                wall.wait_for_function("() => document.getElementById('biCanvas').getAttribute('data-page-id') === 'exposure' && !document.querySelector('.bi-vis-busy')", timeout=30000)
                wall.wait_for_timeout(1500)
                wall.screenshot(path=str(out / "board-exposure.png"))
                wall.click("[data-pages] a:nth-child(1)")
                wall.wait_for_timeout(1500)
                wall.click("[data-kiosk]")
                wall.wait_for_timeout(1500)
                wall.screenshot(path=str(out / "board-kiosk.png"))

                # ---- a phone ----------------------------------------------------------------------------------------------------
                phone = sign_in(browser, "rita", viewport={"width": 390, "height": 844}, is_mobile=True, has_touch=True, device_scale_factor=2)
                ready(phone, f"/bi/r/{REPORT}")
                phone.screenshot(path=str(out / "phone-live-390.png"))
                ready(phone, f"/bi/b/{BOARD}")
                phone.screenshot(path=str(out / "phone-board-390.png"))
                browser.close()

            # ---- captured output ------------------------------------------------------------------------------------------------
            events = stream_events(f"/bi/reports/{REPORT}/live", "rita", ["live-risk"], 5)
            got["stream"] = events
            s, board = S.api("GET", f"/bi/boards/{BOARD}", "rita", ["live-risk"])
            got["board"] = {"status": s, "valid": board.get("valid"), "tiles": {k: board["tiles"][k] for k in list(board["tiles"])[:3]}, "rotate": board["board"].get("rotate")}
            s, layers = S.api("GET", "/bi/datasets/file:dataset:live-trading/layers", "rita", ["live-risk"])
            got["layers"] = layers
            s, eng = S.admin("GET", "/bi/engine")
            got["engine_live"] = eng.get("live")
            q = {"page": "desk", "visual": "mtm", "state": {}}
            s, card = S.api("POST", f"/bi/reports/{REPORT}/query", "rita", ["live-risk"], q)
            got["card"] = {"rows": card["rows"], "columns": card["columns"], "meta": {k: card["meta"][k] for k in ("layer", "rows", "cached", "masked", "warnings")}, "live": {k: v for k, v in card["live"].items() if k != "tables"},
                           "table": card["live"]["tables"][0]}
            s, a = S.api("POST", f"/bi/reports/{REPORT}/query", "ann", ["live-analyst"], {"page": "grid", "visual": "all-trades", "state": {"limit": 3}})
            got["masked_rows"] = {"columns": [c["name"] for c in a["columns"]], "rows": a["rows"][:2], "masked": a["meta"]["masked"]}
            seq = card["live"]["tables"][0]["seq"]
            time.sleep(1.5)
            s, delta = S.api("POST", f"/bi/reports/{REPORT}/query", "rita", ["live-risk"], {"page": "grid", "visual": "all-trades", "state": {"since": seq}})
            got["since"] = {"since": seq, "rowCount": delta["rowCount"], "columns": [c["name"] for c in delta["columns"]], "first": delta["rows"][:2]}
            s, bad = S.api("POST", "/bi/boards/validate", "wes", ["live-writer"], {"yaml": "board: bad\npages:\n  - id: p\n    tiles:\n      - { report: live-trading-desk, visual: nope, at: { x: 0, y: 0, w: 13, h: 3 } }\n      - { report: no-such, visual: v, at: { x: 0, y: 3, w: 4, h: 3 }, alert: { warn: { op: lt, value: 0 }, notify: { email: a@b.c } } }\n"})
            got["bad_board"] = bad["problems"]
        finally:
            stack.close()
    captures.write_text(json.dumps(got, indent=2, default=str))
    print("pictures:", sorted(p.name for p in out.glob("*.png")))


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))
