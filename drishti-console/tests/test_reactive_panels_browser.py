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

"""Computed panels in a real browser (REACTIVE_CONNECTORS.md 6.2 and 12.4), a real server behind the console with the compute-demo
sample pack and compute workers on (the sample pool, the console's own Python):

* a book's screen opens at once with its computed panels drawn computing (never blank), each with its request id;
* the progress bar moves from the live channel, and the result fills the panel in place when it lands (no reload);
* the Python engine (fanned out over the pool's workers) and its Java twin give the same number;
* a parameter the connector does not allow is refused in words; panels that read no computed kind are unaffected;
* the screenshots for the tutorial (docs/tutorials/img/t23: computing, computed-panels, connector, computed-kind, refused, pools).

Skipped when Playwright, Chromium, the built server jar or a JDK is missing (wb_live.py)."""
import sys
from pathlib import Path

import pytest

from test_workbench_browser import browser, wait  # noqa: F401 - fixtures and helpers
from wb_live import _stack
from docshots import docs_img

IMG = docs_img("tutorials/img/t23")                 # docs/tutorials/img/t23 only when DRISHTI_SHOTS is set (docshots.py)
ENV = {"DRISHTI_PACKS": "core,compute-demo", "DRISHTI_COMPUTE_ENABLED": "true", "DRISHTI_PYTHON": sys.executable, "DRISHTI_LAKE_ENABLED": "false",
       "DRISHTI_PACKS_SAMPLES": "visible"}


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    yield from (str(s) for s in _stack(tmp_path_factory, ENV))


def open_book(browser, base, book, slow):
    pg = browser.new_page(viewport={"width": 1500, "height": 950})
    pg.add_init_script("""document.addEventListener('DOMContentLoaded', () => {
      window.__doc = 1; window.__seen = {}; window.__pct = 0;
      new MutationObserver(() => {
        document.querySelectorAll('section.pnl[data-compute]').forEach(s => { (window.__seen[s.dataset.panel] = window.__seen[s.dataset.panel] || []).push(s.dataset.compute); });
        document.querySelectorAll('[data-compute-bar]').forEach(b => { window.__pct = Math.max(window.__pct, +b.getAttribute('data-w') || 0); });
      }).observe(document.documentElement, { childList: true, subtree: true, attributes: true });
    })""")
    pg.goto(f"{base}/v/path-book/{book}?ctl.slow={slow}")
    return pg


def state(pg, panel):
    return pg.locator(f"#p-{panel}").get_attribute("data-compute")


def test_a_computed_panel_is_drawn_computing_then_filled_in_place_with_progress(stack, browser):
    pg = open_book(browser, stack, "BOOK-RATES", 100)
    assert state(pg, "py-sum") in ("computing", "ready")                  # drawn at once, never blank
    assert pg.locator("#p-py-sum").get_attribute("data-compute-req").startswith("req-")
    if state(pg, "py-sum") == "computing":
        assert pg.locator("#p-py-sum [data-compute-strip]").inner_text().startswith("Computing")
    wait(pg, "document.querySelector('#p-py-sum').dataset.compute === 'ready' && document.querySelector('#p-java-sum').dataset.compute === 'ready'",
         seconds=60)
    assert pg.evaluate("window.__doc") == 1                                # filled in place: the same document
    py = pg.locator("#p-py-sum .metric-v").inner_text()
    java = pg.locator("#p-java-sum .metric-v").inner_text()
    assert py == java and py.strip()                                       # Python over the pool's workers == the Java twin
    # the book's path, 250 days (the macros keep a chart's data in data-chart; the client-side renderer, ui.render: client, on the element)
    assert pg.evaluate("(() => { const el = document.querySelector('#p-py-path .chart'); return (el.__drishti ? el.__drishti.data : JSON.parse(el.dataset.chart)).x.length; })()") == 250
    assert pg.locator("#p-stamp").inner_text().count("java:vector-sum") == 1
    IMG.mkdir(parents=True, exist_ok=True)
    pg.screenshot(path=str(IMG / "computed-panels.jpg"), type="jpeg", quality=84, clip={"x": 0, "y": 0, "width": 1500, "height": 820})
    pg.close()


def test_progress_moves_on_a_slow_computation(stack, browser):
    pg = open_book(browser, stack, "BOOK-CREDIT", 100)
    wait(pg, "window.__pct > 0 || document.querySelector('#p-py-sum').dataset.compute === 'ready'", seconds=60)
    if pg.locator("#p-py-sum").get_attribute("data-compute") == "computing":   # mid-way: the bar and its percentage, for the tutorial
        IMG.mkdir(parents=True, exist_ok=True)
        pg.screenshot(path=str(IMG / "computing.jpg"), type="jpeg", quality=84, clip={"x": 0, "y": 0, "width": 1500, "height": 560})
    wait(pg, "document.querySelector('#p-java-sum').dataset.compute === 'ready'", seconds=60)
    seen = pg.evaluate("window.__seen['java-sum'] || []")
    if "computing" in seen:                                                # drawn computing: the bar moved before the result landed
        assert pg.evaluate("window.__pct") > 0
    pg.close()


def test_a_parameter_out_of_bounds_is_refused_in_words_and_other_panels_are_unaffected(stack, browser):
    pg = browser.new_page(viewport={"width": 1500, "height": 950})
    pg.goto(f"{stack}/v/path-sum-java/BOOK-FX~delay-ms=900")             # the computed kind itself, a parameter past its bound
    assert "delay-ms for path-sum-java is 900: it must be at least 0 and at most 500" in pg.content()
    pg.goto(f"{stack}/v/path-book/BOOK-FX?ctl.slow=0")
    wait(pg, "document.querySelector('#p-java-sum').dataset.compute === 'ready'", seconds=60)
    assert pg.locator("#p-book").count() == 1 and pg.locator("#p-book[data-compute]").count() == 0
    pg.close()


def test_the_connector_the_computed_kind_and_the_pools_for_the_tutorial(stack, browser):
    pg = browser.new_page(viewport={"width": 1500, "height": 950})
    IMG.mkdir(parents=True, exist_ok=True)
    pg.goto(f"{stack}/admin/connectors/path-sum-python")
    wait(pg, "document.body.innerText.includes('reactive')", seconds=30)
    pg.screenshot(path=str(IMG / "connector.jpg"), type="jpeg", quality=84, clip={"x": 0, "y": 0, "width": 1500, "height": 760})
    pg.goto(f"{stack}/v/path-sum-java/BOOK-FX")
    wait(pg, "document.body.innerText.includes('java:vector-sum')", seconds=60)
    pg.screenshot(path=str(IMG / "computed-kind.jpg"), type="jpeg", quality=84, clip={"x": 0, "y": 0, "width": 1500, "height": 760})
    pg.goto(f"{stack}/v/path-sum-java/BOOK-FX~delay-ms=900")
    assert "at most 500" in pg.content()
    pg.screenshot(path=str(IMG / "refused.jpg"), type="jpeg", quality=84, clip={"x": 0, "y": 0, "width": 1500, "height": 560})
    pg.goto(f"{stack}/admin/compute")
    wait(pg, "document.body.innerText.includes('sample')", seconds=30)
    pg.screenshot(path=str(IMG / "pools.jpg"), type="jpeg", quality=84, clip={"x": 0, "y": 0, "width": 1500, "height": 760})
    pg.close()

