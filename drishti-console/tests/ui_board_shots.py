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

"""The UI Board guide's pictures (docs/guides/img/ui-board/), synthetic data only, from the stand-in server of ui_board_backend.py:
the designer in Board mode with the book-trades board, a rendered board after a selection, and a bad wire in red.

    python tests/ui_board_shots.py"""
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))

from playwright.sync_api import sync_playwright  # noqa: E402

import test_ui_board_browser as T  # noqa: E402
from ui_board_backend import serve  # noqa: E402

OUT = HERE.parent.parent / "docs" / "guides" / "img" / "ui-board"


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    url, be, stop = serve()
    try:
        with sync_playwright() as p:
            b = p.chromium.launch()
            c = b.new_context(viewport={"width": 1440, "height": 900}, bypass_csp=True)
            page = c.new_page()
            page.errors = []
            T.build_book_trades(page, url)
            page.locator('.ub-box[data-instance="trades"]').focus()
            page.locator("[data-ub-live]").check()
            page.wait_for_selector('.ub-box[data-instance="trades"] table.ub-tbl tbody tr')
            page.wait_for_timeout(300)
            page.evaluate("window.scrollTo(0, 0)")
            page.screenshot(path=str(OUT / "designer.png"))
            T.wire(page, "trades.visible", "flows.filter")
            page.wait_for_selector("svg[data-ub-wires] path.ub-wire.ub-bad", state="attached")
            page.wait_for_timeout(200)
            page.evaluate("window.scrollTo(0, 0)")
            page.screenshot(path=str(OUT / "bad-wire.png"))
            T.put_board(be, "book-trades", T.BOOK)
            page.goto(url + "/ui/b/book-trades")
            page.wait_for_selector('.ub-chip[data-instance="trades"] tr.ub-pick')
            page.locator('.ub-chip[data-instance="trades"] tr.ub-pick').nth(3).click()
            page.wait_for_selector('.ub-chip[data-instance="ticket"] .ub-kv')
            page.wait_for_timeout(300)
            page.screenshot(path=str(OUT / "rendered-board.png"))
            b.close()
    finally:
        stop()
    for f in sorted(OUT.glob("*.png")):
        print(f)


if __name__ == "__main__":
    main()
