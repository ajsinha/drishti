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

"""The visual snapshots (docs/architecture/TEST_HARNESS.md, "The visual snapshots"), in every drill's browser lane: about 30 key pages
(KEY_SNAPSHOTS) at 1366 and 1920 px, dark and light, against their golden images (tests/snapshots.py; ``--update-snapshots`` writes them),
on a server of their own over the page walk's lake (test_page_walk_browser.py's stack: the same cached lake, ending on SNAPSHOT_DATE so the
figures never move). A file apart from the walk, so each fits its re-run time limit, and its server has opened nothing before the snapshots
(no recent pages, no workspaces). Skipped when Playwright, Chromium, the built server jar or a JDK is missing, and off Linux."""
import os
import sys
from pathlib import Path

import pytest

import snapshots

from page_walk import go, new_page, open_page
from test_page_walk_browser import SNAPSHOT_DATE, THEMES, stack  # noqa: F401 - the walk's stack, started again for this file
from test_workbench_browser import browser  # noqa: F401 - the fixture

#: the visual snapshots: name -> (address, {tolerance, mask: more selectors covered, idle: wait for the network to go quiet, wait: a
#: script true once the page has drawn, scroll: capture this part of the page alone instead of the viewport});
#: a lens's Home is captured after entering the lens (its page asks to confirm: a POST), then the lens is left the same way
KEY_SNAPSHOTS: dict[str, tuple[str, dict]] = {
    "home": ("/", {}),
    "home-risk": ("/lens/risk", {}),
    "home-trading": ("/lens/trading", {}),
    "home-market": ("/lens/market", {}),
    "home-markets": ("/lens/markets", {}),
    "world": ("/lens/world", {}),
    "var-bank": ("/var?scope=BANK", {"idle": True}),
    "var-desk": ("/var?scope=DESK:DESK-RATES", {"idle": True}),
    "var-basket": ("/var?scope=BASKET:PF-ASHA-UNWIND", {"idle": True}),
    # the page scrolls itself to its "why" part when it has drawn, and its lineage shows the time it computed: the part alone
    # its ready state: data-state turns ready once every part, the waterfall painted too, is in, or failed with the server's words
    "why-it-moved": (go("VAR BANK EXPLAIN"), {"idle": True, "scroll": "[data-dx-part=\"why\"]", "wait": "(() => { const p = document.querySelector('[data-explain-page]'); const w = document.querySelector('[data-dx-part=\"why\"] [data-dx-why]');"
                                                       " return p && p.dataset.state === 'ready' && w && w.dataset.painted === 'waterfall'; })()",
                                                       "failed": "(() => { const p = document.querySelector('[data-explain-page]'); if (!p || p.dataset.state === 'loading') return null;"
                                                       " if (p.dataset.state === 'failed') return 'the page failed: ' + p.innerText.slice(0, 300);"
                                                       " return document.querySelector('[data-dx-part=\"why\"] [data-dx-why]') ? null : 'ready without a waterfall: ' + p.innerText.slice(0, 300); })()"}),
    # the cube's columns are sized as its rows arrive: a figure is cut short on one run and whole on another (2%)
    "mr-cube": ("/pivot?preset=mr", {"idle": True, "tolerance": 0.02, "wait": "!/Computing the grid/i.test(document.querySelector('#main').innerText)"}),
    "pivot": ("/pivot", {}),
    # a live board: its trend's time axis moves with the clock (1%)
    "bi-report": ("/bi/r/file:report:desk-wall", {"idle": True, "tolerance": 0.01, "wait": "/Net MTM/.test(document.querySelector('#main').innerText)"}),
    "frtb": (go("FRSA BANK"), {"idle": True, "wait": "!/loading/i.test(document.querySelector('#main').innerText)"}),
    "simm": (go("SIMM"), {"idle": True, "wait": "!/Loading/i.test(document.querySelector('#main').innerText)"}),
    "exposure": (go("EXP"), {"idle": True}),
    "form": ("/forms", {}),
    "trades": (go("TRD"), {}),
    "curves": (go("CRV"), {}),
    "fund": (go("FUND"), {}),
    "useq": (go("USEQ"), {}),
    # the memory each engine holds now and the server's folder (a temporary one per run) move: covered
    "admin-engines": ("/admin/engines", {"idle": True, "mask": ["[data-eng-meta]", "td:has(> .eng-bar)"]}),
    "admin-connectors": ("/admin/connectors", {}),
    "admin-loads": ("/admin/loads", {}),
    "admin-upload": ("/admin/loads/upload", {}),
    "admin-bindings": ("/admin/bindings", {}),
    "admin-cluster-modules": ("/admin/net?tab=modules", {}),
    "admin-modules": ("/admin/modules", {}),
    "admin-deploy": ("/admin/modules/deploy", {}),
    "admin-white-label": ("/admin/white-label", {}),
    "help": ("/help", {}),
    "elements-gallery": ("/elements?mode=docs", {}),
    "elements-playground": ("/elements?mode=playground", {}),
}


def _until(pg, expr: str, timeout_s: float = 180, failed: str | None = None) -> None:
    """Waits until ``expr`` is true on the page (evaluated by the browser's debugger, which the page's CSP allows; a page-side poll would eval);
    ``failed``: a script giving the page's own words once it has settled without drawing (its error), which ends the wait at once."""
    import time

    deadline = time.monotonic() + timeout_s
    while not pg.evaluate(expr):
        why = pg.evaluate(failed) if failed else None
        assert not why, why
        assert time.monotonic() < deadline, f"the page did not draw in {timeout_s:.0f} s: {expr[:80]}"
        pg.wait_for_timeout(100)   # wait-ok: polling a page-side condition the CSP keeps from wait_for_function


def _confirm(pg, base: str, settle: bool = True) -> None:
    """Presses the confirming page's button (entering or leaving a lens is a POST) and waits for the page it leads to."""
    with pg.expect_navigation(wait_until="load"):
        pg.locator("form[data-confirm-post] button[type=submit]").first.click()
    if settle:
        pg.evaluate("() => window.Drishti && window.Drishti.settled ? window.Drishti.settled({quiet: 50, timeout: 60000, animations: false}) : null")


def test_the_key_pages_look_as_approved(stack, browser, request, tmp_path):
    """Each key page at 1366 and 1920 px, dark and light, against its golden image; first in the file, so what the walk opens later
    (recent pages, workspaces) never shows in it. ``--update-snapshots`` writes the goldens; the drill never does."""
    if os.environ.get("DRISHTI_PAGEWALK_BASE"):
        pytest.skip("the goldens are of the test stack's fixed demo lake, not of a running console's data")
    if sys.platform != "linux":
        pytest.skip("the goldens are drawn by Chromium on Linux (the release machine); fonts render differently elsewhere")
    updating = request.config.getoption("--update-snapshots")
    if updating and os.environ.get("DRILL_TIMINGS_OUT"):
        pytest.fail("a drill never updates the visual snapshots: run --update-snapshots by hand and commit the goldens")
    base, diffs = str(stack), Path(os.environ.get("DRISHTI_SNAPSHOT_DIFFS") or tmp_path / "snapshot-diffs")
    changed, faults = [], []
    only = [n for n in os.environ.get("DRISHTI_SNAPSHOT_ONLY", "").split(",") if n]   # some pages only (refreshing one golden)
    pages = {n: v for n, v in KEY_SNAPSHOTS.items() if not only or n in only}
    for width in (1366, 1920):
        for theme in sorted(THEMES):
            pg = new_page(browser, width=width)
            try:
                pg.context.add_init_script("try { localStorage.setItem('drishti.theme', %r); } catch (e) {}" % THEMES[theme])
                pg.goto(f"{base}/asof?d={SNAPSHOT_DATE}&next=/")   # the business date picked, not the server's today
                for name, (href, opts) in pages.items():
                    r = open_page(pg, base, href)
                    if href.startswith("/lens/"):
                        _confirm(pg, base)
                    if opts.get("idle"):   # the page's last calls answered (a header's figures, a grid's rows)
                        try:
                            pg.wait_for_load_state("networkidle", timeout=10_000)
                        except Exception:  # noqa: BLE001 - a call still open: its own wait decides
                            pass
                    if opts.get("wait"):
                        _until(pg, opts["wait"], failed=opts.get("failed"))
                    faults += [f"{name}: {f}" for f in r["faults"]]
                    pg.evaluate("(t) => document.documentElement.setAttribute('data-theme', t)", THEMES[theme])
                    png = snapshots.capture(pg, opts.get("mask", []), opts.get("scroll"))
                    if href.startswith("/lens/"):
                        open_page(pg, base, "/lens/leave")
                        _confirm(pg, base, settle=False)
                    if updating:
                        snapshots.update(name, width, theme, png)
                        continue
                    row = snapshots.compare(name, width, theme, png, opts.get("tolerance", snapshots.TOLERANCE), diffs)
                    if row["changed"]:
                        changed.append(f"{name} {width} {theme}: {row['share']:.2%} changed, see {row['diff']}")
            finally:
                pg.context.close()
    assert not faults, "pages with faults:\n" + "\n".join(faults)
    assert not changed, "pages that look different from their golden (refresh with --update-snapshots when meant):\n" + "\n".join(changed)
