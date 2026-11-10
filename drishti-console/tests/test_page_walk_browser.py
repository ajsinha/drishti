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

"""The page walk and the layout checks (docs/architecture/TEST_HARNESS.md, "The page walk"), part of every drill's browser lane:

- every page of the sitemap (each menu item, the in-context pages, Help's sections, a few pages with a demo id) and every link of the
  top bar, in the full product and in each lens, opened as an administrator on a real server over the cached VaR demo lake: none
  answers 4xx/5xx, shows a server error page, logs a console error, throws, leaves the main area empty or takes longer than the budget
  (DRISHTI_PAGEWALK_BUDGET, 5 s); every fault and every slow page is listed in one failure;
- the key pages (VaR explorer, Why it moved, MR cube, a pivot, BI, a FORM page, Admin → Engines, Connectors, Data loads, Upload, Home,
  Help) at 1366 and 1920 px, dark and light: no sideways scroll, no overlapping controls, labels beside their controls, no field group
  taller than its content, the main content using the width; a drishti-inputs Form on the page by tests/form_layout.py's measure.

DRISHTI_PAGEWALK_BASE=<console URL> walks a console that is already running instead of starting one (an owner's stack, a QA machine).
Skipped when Playwright, Chromium, the built server jar or a JDK is missing."""
import os

import pytest

from form_layout import _MEASURE
from page_walk import BUDGET_S, dedupe, go, layout_faults, menu_links, new_page, open_page, report, sitemap_addresses
from test_workbench_browser import browser  # noqa: F401 - the fixture
from var_stack import VAR_TRADES, var_data, var_lake, var_server

KEY_PAGES = ["/", "/var?scope=BANK", go("VAR BANK EXPLAIN"), "/pivot?preset=mr", "/pivot", "/bi/designer", "/forms", "/admin/engines",
             "/admin/connectors", "/admin/loads", "/admin/loads/upload", "/help"]
THEMES = {"dark": "terminal", "light": "light"}
#: in-context pages opened from a link on the page that lists them (they need its parameters)
FROM = {"/admin/lakes/table": "/admin/lakes"}


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    if os.environ.get("DRISHTI_PAGEWALK_BASE"):
        yield os.environ["DRISHTI_PAGEWALK_BASE"].rstrip("/")
        return
    lake = var_lake(tmp_path_factory, var_data(*VAR_TRADES))
    # the data inbox on (Admin -> Data loads -> Inbox and Upload are its pages), over an empty folder of its own
    yield from var_server(tmp_path_factory, lake, DRISHTI_DATALOAD_ENABLED="true", DRISHTI_DATALOAD_DIR=str(tmp_path_factory.mktemp("walk-dataload")))


def _walk(browser, base: str, hrefs: list[str]) -> list[dict]:
    """Opens each page; an in-context page that needs what its page gives it (FROM) is opened by the first such link on that page."""
    pg = new_page(browser)
    try:
        out = []
        for h in hrefs:
            if h in FROM:
                pg.goto(base + FROM[h])
                link = pg.evaluate("(p) => { const a = document.querySelector('#main a[href^=\"' + p + '?\"]'); return a ? a.getAttribute('href') : null; }", h)
                if not link:
                    continue        # nothing to open on this stack (no lake the console reads)
                h = link
            out.append(open_page(pg, base, h))
        for r in out:   # a page over the budget is opened once more: a cold cache or a busy moment is not a slow page, slow twice is
            if r["secs"] > BUDGET_S and not r["faults"]:
                again = open_page(pg, base, r["href"])
                r["secs"], r["faults"] = min(r["secs"], again["secs"]), again["faults"]
        return out
    finally:
        pg.context.close()


def _assert_clean(results: list[dict]) -> None:
    bad, slow = report(results)
    off = [r["href"] for r in results if r.get("off")]
    print(f"page walk: {len(results)} pages, {len(bad)} with faults, {len(slow)} over {BUDGET_S:.0f} s, {len(off)} of engines off: {off}")
    for line in slow:
        print("  slow", line)
    assert not bad and not slow, "\n".join(["faults:"] + bad + [f"over {BUDGET_S:.0f} s:"] + slow)


def test_every_sitemap_page_opens_clean(stack, browser):
    _assert_clean(_walk(browser, str(stack), sitemap_addresses()))


def test_every_top_bar_link_in_the_product_and_each_lens_opens_clean(stack, browser):
    """The links the top bar draws (the full product's, then each lens's) that the sitemap walk did not open already."""
    base, done = str(stack), set(sitemap_addresses())
    pg = new_page(browser)
    try:
        pg.goto(base + "/")
        links = menu_links(pg, base)
        lenses = [h for h in links if h.startswith("/lens/")]
        for lens in lenses:
            pg.goto(base + lens)
            links += menu_links(pg, base)
        pg.goto(base + "/lens/leave")
        todo = [h for h in dedupe(links) if h not in done and not h.startswith("/lens/")]
    finally:
        pg.context.close()
    results = _walk(browser, base, todo + lenses)   # each lens's own home last
    _assert_clean(results)


@pytest.mark.parametrize("width", [1366, 1920])
@pytest.mark.parametrize("theme", sorted(THEMES))
def test_the_key_pages_are_laid_out(stack, browser, width, theme):
    base, faults = str(stack), []
    pg = new_page(browser, width=width)
    try:
        pg.context.add_init_script("try { localStorage.setItem('drishti.theme', %r); } catch (e) {}" % THEMES[theme])
        for href in KEY_PAGES:
            r = open_page(pg, base, href)
            pg.evaluate("(t) => document.documentElement.setAttribute('data-theme', t)", THEMES[theme])
            found = layout_faults(pg)
            form = pg.evaluate(_MEASURE, "#main")   # a drishti-inputs Form: each label on its control, no box taller than it holds
            if form:
                found += [f"form label {p['label']!r} away from its control" for p in form["pairs"] if not (-1 <= p["gap"] <= 12 and p["dx"] <= 4)]
                found += [f"form box {s} px taller than its content" for s in form["slack"] if s > 16]
            faults += [f"{href}: {f}" for f in r["faults"] + found]
    finally:
        pg.context.close()
    assert not faults, f"{width} px, {theme}:\n" + "\n".join(faults)
