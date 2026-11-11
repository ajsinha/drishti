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
"""The visual snapshots' pages and their capture (docs/architecture/TEST_HARNESS.md, "The visual snapshots"): about 30 key pages
(KEY_SNAPSHOTS) at 1366 and 1920 px, dark and light, against their golden images (tests/snapshots.py; ``--update-snapshots`` writes
them), on a server over the page walk's lake (test_page_walk_browser.py's stack: the same cached lake, ending on SNAPSHOT_DATE so the
figures never move).

The pages are in four groups (GROUPS), one test file each (test_snapshots_<group>_browser.py), so the lane's workers capture them side
by side; each file's server has opened nothing before its snapshots (no recent pages, no workspaces). A page is captured once it says it
has drawn: the page's settled signal (``Drishti.settled``: every chart drawn and its animation done, every grid drawn, no request of
the page's own unanswered, the page quiet) and the page's own ready state where it has one (``wait``); a second capture must then
agree. Only when the page never settles, or the two captures differ, are captures a second apart compared until two agree (the
fallback, counted in the report)."""
from __future__ import annotations

import os
import sys
import time
from pathlib import Path

import pytest

import snapshots
from page_walk import go, new_page, open_page

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
    # the VaR page marks itself painted (data-painted) once its headline and its tab have drawn
    "var-bank": ("/var?scope=BANK", {"idle": True, "wait": "!!document.querySelector('[data-var][data-painted]')"}),
    "var-desk": ("/var?scope=DESK:DESK-RATES", {"idle": True, "wait": "!!document.querySelector('[data-var][data-painted]')"}),
    "var-basket": ("/var?scope=BASKET:PF-ASHA-UNWIND", {"idle": True, "wait": "!!document.querySelector('[data-var][data-painted]')"}),
    # Why it moved as a page (layout A: the compare line, four figures, the waterfall wide with the step's contributors beside it);
    # its ready state: data-state turns ready once the waterfall and the side are drawn, or failed with the server's words
    "why-it-moved": (go("VAR BANK EXPLAIN"), {"idle": True, "wait": "(() => { const p = document.querySelector('[data-explain-page]');"
                                                       " return !!(p && p.dataset.state === 'ready' && p.querySelector('[data-dx-why-waterfall] [data-dx-waterfall] svg, [data-dx-why-waterfall] [data-dx-waterfall] canvas')); })()",
                                                       "failed": "(() => { const p = document.querySelector('[data-explain-page]'); if (!p || p.dataset.state === 'loading') return null;"
                                                       " if (p.dataset.state === 'failed') return 'the page failed: ' + p.innerText.slice(0, 300);"
                                                       " return p.querySelector('[data-dx-why-waterfall]') ? null : 'ready without the waterfall: ' + p.innerText.slice(0, 300); })()"}),
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
    # Admin -> Engines is left out: its native-maths notice depends on whether the server's BLAS probe answered within the page's budget,
    # so the table moves between runs; the page is covered by test_engines_browser and test_admin_engines_budget
    "admin-connectors": ("/admin/connectors", {"idle": True, "tolerance": 0.02}),   # live connector states settle at their own pace
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

#: the four groups, each about a quarter of the captures' time: every key page in exactly one (test_snapshot_groups.py checks it)
GROUPS: dict[str, tuple[str, ...]] = {
    "home": ("home", "home-risk", "home-trading", "home-market", "home-markets", "world", "help", "elements-gallery", "elements-playground"),
    "var": ("var-bank", "var-desk", "var-basket", "why-it-moved", "mr-cube", "frtb", "simm", "exposure"),
    "pages": ("pivot", "bi-report", "form", "trades", "curves", "fund", "useq"),
    "admin": ("admin-connectors", "admin-loads", "admin-upload", "admin-bindings", "admin-cluster-modules", "admin-modules", "admin-deploy",
              "admin-white-label"),
}

#: the drawn signal's options: charts' animations waited for, and the page quiet this long (ms) after its last change
DRAWN = {"quiet": 300, "timeout": 60000, "animations": True}


def _until(pg, expr: str, timeout_s: float = 180, failed: str | None = None) -> None:
    """Waits until ``expr`` is true on the page (evaluated by the browser's debugger, which the page's CSP allows; a page-side poll would eval);
    ``failed``: a script giving the page's own words once it has settled without drawing (its error), which ends the wait at once."""
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


def drawn(pg) -> bool:
    """The page's drawn signal (drishti_settled.js): True once it has settled, False when it has none or never settles in time."""
    return bool(pg.evaluate("(o) => window.Drishti && window.Drishti.settled ? window.Drishti.settled(o).then(() => true, () => false) : false", DRAWN))


def still(pg, opts: dict, stats: dict) -> bytes:
    """One capture of a page that has drawn: after its drawn signal, a capture and a second one that must agree; the fallback (captures a
    second apart until two agree) only when the page never settles or the two differ. ``stats`` counts which way each page went."""
    masks, part = opts.get("mask", []), opts.get("scroll")
    # the first capture stills the page (timings quieted, focus and hover dropped, transitions off): its own change, so it is not kept;
    # measured: two captures straight after the drawn signal differed by a few bytes on every page until the page had been stilled once
    snapshots.capture(pg, masks, part)
    signalled = drawn(pg)
    png = snapshots.capture(pg, masks, part)
    if signalled and drawn(pg) and snapshots.capture(pg, masks, part) == png:
        stats["signal"] = stats.get("signal", 0) + 1
        return png
    stats["fallback"] = stats.get("fallback", 0) + 1
    for _ in range(8):   # still drawing (a grid filling, a chart easing in) and saying nothing: two captures a second apart must agree
        pg.wait_for_timeout(1000)   # wait-ok: the fallback's interval between two captures that must agree (the page gave no drawn signal)
        again = snapshots.capture(pg, masks, part)
        if again == png:
            break
        png = again
    return png


def run_group(group: str, stack, browser, request, tmp_path) -> None:
    """Each page of ``group`` at 1366 and 1920 px, dark and light, against its golden image; first in its file, so what a later test opens
    (recent pages, workspaces) never shows in it. ``--update-snapshots`` writes the goldens; the drill never does."""
    from test_page_walk_browser import SNAPSHOT_DATE, THEMES

    if os.environ.get("DRISHTI_PAGEWALK_BASE"):
        pytest.skip("the goldens are of the test stack's fixed demo lake, not of a running console's data")
    if sys.platform != "linux":
        pytest.skip("the goldens are drawn by Chromium on Linux (the release machine); fonts render differently elsewhere")
    updating = request.config.getoption("--update-snapshots")
    if updating and os.environ.get("DRILL_TIMINGS_OUT"):
        pytest.fail("a drill never updates the visual snapshots: run --update-snapshots by hand and commit the goldens")
    base, diffs = str(stack), Path(os.environ.get("DRISHTI_SNAPSHOT_DIFFS") or tmp_path / "snapshot-diffs")
    changed, faults, stats = [], [], {}
    only = [n for n in os.environ.get("DRISHTI_SNAPSHOT_ONLY", "").split(",") if n]   # some pages only (refreshing one golden)
    pages = {n: KEY_SNAPSHOTS[n] for n in GROUPS[group] if not only or n in only}
    if not pages:
        pytest.skip(f"DRISHTI_SNAPSHOT_ONLY names no page of the {group} group")
    for width in (1366, 1920):
        for theme in sorted(THEMES):
            pg = new_page(browser, width=width)
            lost: list[str] = []   # requests that failed, named when a page does not draw
            pg.on("requestfailed", lambda q, lost=lost: lost.append(f"{q.url} ({q.failure})"))
            try:
                pg.context.add_init_script("try { localStorage.setItem('drishti.theme', %r); } catch (e) {}" % THEMES[theme])
                pg.goto(f"{base}/asof?d={SNAPSHOT_DATE}&next=/")   # the business date picked, not the server's today
                for name, (href, opts) in pages.items():
                    began = time.monotonic()
                    r = open_page(pg, base, href)
                    if href.startswith("/lens/"):
                        _confirm(pg, base)
                    if opts.get("idle"):   # the page's last calls answered (a header's figures, a grid's rows), images too
                        try:
                            pg.wait_for_load_state("networkidle", timeout=10_000)
                        except Exception:  # noqa: BLE001 - a call still open: its own wait decides
                            pass
                    if opts.get("wait"):
                        try:
                            _until(pg, opts["wait"], failed=opts.get("failed"))
                        except AssertionError as e:
                            raise AssertionError(f"{name} at {width} {theme}: {e}; requests that failed: {lost[-6:]}") from None
                    faults += [f"{name}: {f}" for f in r["faults"]]
                    pg.evaluate("(t) => document.documentElement.setAttribute('data-theme', t)", THEMES[theme])
                    png = still(pg, opts, stats)
                    stats.setdefault("secs", {}).setdefault(name, []).append(round(time.monotonic() - began, 1))
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
    print(f"snapshots {group}: {stats.get('signal', 0)} captured on the drawn signal, {stats.get('fallback', 0)} by the fallback; seconds per page:"
          f" {stats.get('secs', {})}")
    assert not faults, "pages with faults:\n" + "\n".join(faults)
    assert not changed, "pages that look different from their golden (refresh with --update-snapshots when meant):\n" + "\n".join(changed)
