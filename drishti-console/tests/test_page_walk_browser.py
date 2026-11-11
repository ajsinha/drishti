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
  top bar, in the full product and in each lens, opened as an administrator on a real server over the cached demo lake (WALK_DATA): none
  answers 4xx/5xx, shows a server error page, logs a console error, throws, leaves the main area empty or takes longer than the budget
  (DRISHTI_PAGEWALK_BUDGET, 10 s); every fault and every slow page is listed in one failure;
- the key pages (VaR explorer, Why it moved, MR cube, a pivot, BI, a FORM page, Admin → Engines, Connectors, Data loads, Upload, Home,
  Help) at 1366 and 1920 px, dark and light: no sideways scroll, no overlapping controls, labels beside their controls, no field group
  taller than its content, the main content using the width; a drishti-inputs Form on the page by tests/form_layout.py's measure.

The visual snapshots run on this file's stack (started again, over the same cached lake) in test_snapshots_<group>_browser.py, four files of
their own (snapshot_pages.py GROUPS) so the lane's workers capture them side by side.

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
#: the business date the page walk's lake ends on: fixed, so the visual snapshots' figures and dates (snapshot_pages.py) are the same on every run
SNAPSHOT_DATE = "2026-11-06"
#: the walk's lake: the VaR files' trades and VaR inputs, the exposure engine's committed run (its paths, netting sets, CSAs, collateral,
#: limits, counterparties), the funds and the US equities, so the Trades, EXP, FUND and USEQ pages and the Markets lens show their data
WALK_DATA = VAR_TRADES + tuple("counterparty-risk/" + k for k in ("simulation-run", "exposure-path", "netting-set", "csa", "collateral-balance",
                                                                  "credit-limit", "agreement")) + ("core/counterparty", "core/counterparty-group", "us-equities", "funds")
WALK_PACKS = "core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets,us-equities,funds"
#: in-context pages opened from a link on the page that lists them (they need its parameters)
FROM = {"/admin/lakes/table": "/admin/lakes"}


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    if os.environ.get("DRISHTI_PAGEWALK_BASE"):
        yield os.environ["DRISHTI_PAGEWALK_BASE"].rstrip("/")
        return
    lake = var_lake(tmp_path_factory, var_data(*WALK_DATA), as_of=SNAPSHOT_DATE, extra=("--exposure", "committed"))
    # the data inbox on (Admin -> Data loads -> Inbox and Upload are its pages), over an empty folder of its own; the full product's packs and
    # the capital-markets module's (off by default: config/packs/default-packs.yaml), and the Markets lens beside the four default ones
    for st in var_server(tmp_path_factory, lake, DRISHTI_DATALOAD_ENABLED="true", DRISHTI_DATALOAD_DIR=str(tmp_path_factory.mktemp("walk-dataload")),
                         DRISHTI_PACKS=WALK_PACKS, DRISHTI_LENSES="risk,world,market,trading,markets"):
        _why_ready(str(st))
        yield st


def _why_ready(base: str, seconds: float = 300) -> None:
    """Why it moved compares two business dates: the engine opens the earlier one at the first ask, which under a busy lane can outlast the
    console's call. Asked here once (the page's own question) until it answers, so the walk's pages open over both dates already open."""
    import json
    import time
    import urllib.error
    import urllib.request

    url, deadline, last = f"{base}/api/explain?kind=var&scope=BANK&part=why&date={SNAPSHOT_DATE}", time.monotonic() + seconds, ""
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=150) as r:
                if (json.load(r).get("why") or {}).get("available"):
                    return
                last = "answered without why it moved"
        except urllib.error.HTTPError as e:
            last = f"HTTP {e.code}: {e.read()[:200]!r}"
        except (urllib.error.URLError, OSError) as e:
            last = str(e)
        time.sleep(1)   # wait-ok: polling the server's answer, a deadline not a pause
    pytest.fail(f"Why it moved did not answer within {seconds:.0f} s: {last}")


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
                r["first"], r["secs"], r["faults"] = r["secs"], min(r["secs"], again["secs"]), again["faults"]
        return out
    finally:
        pg.context.close()


def _assert_clean(results: list[dict]) -> None:
    bad, slow = report(results)
    off = [r["href"] for r in results if r.get("off")]
    print(f"page walk: {len(results)} pages, {len(bad)} with faults, {len(slow)} over {BUDGET_S:.0f} s, {len(off)} of engines off: {off}")
    for line in slow:
        print("  slow", line)
    for r in results:
        if "first" in r:
            print(f"  slow on first open only {r['href']}: {r['first']:.1f}s, then {r['secs']:.1f}s")
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


def _distribution_console(server_url: str, name: str):
    """A second console over the same server, showing the distribution ``name`` (docs/architecture/DISTRIBUTIONS.md): one cluster, a
    Drishti Risk console and a Drishti Finance console, as the owner's stack runs them."""
    import threading
    import time

    import uvicorn

    from conftest import CONSOLE
    from core.app import create_app
    from core.config import Settings, load_settings
    from wb_live import free_port

    data = load_settings(CONSOLE / "config").as_dict()
    data.setdefault("backend", {})["url"] = server_url
    data["distribution"] = name
    app = create_app(Settings(data))
    port = free_port("")
    srv = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", timeout_graceful_shutdown=2))
    th = threading.Thread(target=srv.run, daemon=True)
    th.start()
    deadline = time.monotonic() + 20
    while not srv.started and time.monotonic() < deadline:
        time.sleep(0.05)   # wait-ok: the console thread starting
    assert srv.started, "the distribution's console did not start"
    return f"http://127.0.0.1:{port}", srv, th


@pytest.mark.parametrize("name,product,shown,gone", [("risk", "Drishti Risk", "/var", "MACRO"), ("finance", "Drishti Finance", None, "/var")])
def test_the_page_walk_per_distribution(stack, browser, name, product, shown, gone):
    """Each distribution's console over the one server: its top bar's every link opens clean, its brand is drawn, and a page of a module
    it leaves out is not linked (Finance has no VaR, Risk no economy)."""
    server = getattr(stack, "server", None)
    if not server:
        pytest.skip("DRISHTI_PAGEWALK_BASE walks one running console; start each distribution's console and walk it the same way")
    base, srv, th = _distribution_console(server, name)
    try:
        pg = new_page(browser)
        try:
            pg.goto(base + "/")
            assert product in pg.content()
            links = dedupe(menu_links(pg, base))
        finally:
            pg.context.close()
        assert not [h for h in links if h == gone or h.startswith(gone + "?") or (gone.isupper() and gone in h)], (name, gone)
        if shown:
            assert shown in links, (name, shown)
        _assert_clean(_walk(browser, base, [h for h in links if not h.startswith("/lens/")] + ["/admin/white-label", "/help"]))
    finally:
        srv.should_exit = True
        th.join(10)
