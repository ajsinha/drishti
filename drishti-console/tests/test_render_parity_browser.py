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

"""Parity: the client-side renderer shows what the console's macros show (docs/guides/EMBED_GUIDE.md section 14).

Each ViewModel is drawn two ways, the console's server HTML and the library (Drishti Elements' painter behind render()) in a blank page,
and compared for content, not markup (the painter's DOM and look are its own): every text of the console's cells, fields and strip is in
the library's view, every entity link of the console's page is a link of the library's, and every chart has the same series and points
(from its ECharts option, not pixels); at 390 and 1280 px, with no sideways scroll, no CSP violation, no script error and no request off
the console's origin. The ViewModels: the captured views and the edge cases of VIEWMODEL.md (render_cases.py) in every theme, and every
Sutra of every shipped pack bound on its own sample by a real server (skipped when the server jar or a JDK is missing). The intentional
differences are normalised by name in ``INTENTIONAL`` below; anything else fails.

Every test is small and independent (one ViewModel, its pages drawn at once on the worker's browser) and marked ``spread``: the
browser lane hands them to its workers one by one (docs/architecture/TEST_HARNESS.md). The pages wait on the page's settled signal."""
import asyncio
import json
import re

import pytest

import pack_views as P
import render_cases as cases
import render_harness as H
from browser_harness import Phases, settled

pytest.importorskip("playwright.async_api", reason="needs Playwright (pip install playwright)")
pytestmark = [pytest.mark.browser, pytest.mark.spread]
WIDTHS = (390, 1280)

# What differs on purpose, each with its reason (EMBED_GUIDE.md section 14.3). Applied before comparing.
INTENTIONAL = {
    "unknown-kind": "a kind neither side draws: the console draws an empty body, the painter a titled panel that says so",
    "console-chrome": "texts of the console's own chrome around a panel (its help '?', the download menu, the filter row, Chart any table): "
                      "not the ViewModel's content",
    "empty-cell": "an empty cell: the console prints nothing, the painter a dash",
    "pivot-corner": "a pivot's corner names its row fields in their own case on the painter ('book › family'), capitalised on the console",
    "inactive-words": "an inactive cell: the console adds the words 'not active' for a screen reader, the painter says it with the tone",
    "drill-count": "a drill-down row's count of children ('2 ▸'): the console's affordance; the painter opens the row by a click",
    "pages-and-closed-rows": "a long table's further pages and a tree's or pivot's closed rows: the console's page holds them all, the "
                             "painter one click away ('Show more', a row's twisty); the library's page is read after opening them",
    "chart-marks": "a chart's marks: compared as each series' type, name and values; colours come from each page's theme, and the points' "
                   "own payload (links, texts) is what each side's click and tooltip read",
}
CHROME = {"?", "—", "–", "-"}
DRILL = re.compile(r"\s*\d+ ▸$")
INACTIVE = re.compile(r" not active$")


def _texts(r: dict) -> set[str]:
    out = set()
    for c in r["cells"]:
        c = DRILL.sub("", c)
        if c in CHROME:
            continue
        out.add(INACTIVE.sub("", c))
    return out


def _value(d):
    if isinstance(d, dict):
        return d.get("value")
    return d


def _chart(c) -> list:
    """A chart as compared: its panel and, per series, the type, the name and the values that are there."""
    panel, series = c
    if series and series[0]["type"] == "map":         # a map: its values, and how many places are marked (each side projects them its own way)
        return [panel, [[s["type"], len(s["data"] or [])] if s["type"] == "scatter" else [s["type"], [v for v in (_value(d) for d in (s["data"] or [])) if v is not None]] for s in series]]
    return [panel, [[s["type"], s["name"], [v for v in (_value(d) for d in (s["data"] or [])) if v is not None]] for s in series]]


def missing(console: dict, library: dict) -> dict:
    """What the console's page shows that the library's does not: {part: [items]} (empty when the library shows everything)."""
    text = library["text"].lower()
    charts = [_chart(c) for c in library["charts"]]
    out = {"cells": sorted(c for c in _texts(console) if c.lower() not in text),
           "links": sorted(set(console["links"]) - set(library["links"])),
           "charts": [c for c in (_chart(c) for c in console["charts"]) if c not in charts]}
    return {k: v for k, v in out.items() if v}


async def draw(browsers, site, url, width, library):
    ph = Phases("parity-library" if library else "parity-console")
    async with browsers.page(width) as page:
        ph.mark("page")
        await page.goto(url)
        ph.mark("load")
        if library:
            await page.wait_for_function("() => window.__rendered === true", timeout=20000)
            ph.mark("rendered")
        if library and await H.open_all_async(page):  # pages and closed rows: the painter's content a click away, opened before the read
            ph.mark("opened")
        await settled(page, animations=False)          # the read takes each chart's option, never its pixels
        ph.mark("settled")
        out = await H.read_async(page)
        ph.mark("read")
        out["errors"] = list(page.errors)
        out["seen"] = list(page.reqs)
    ph.mark("release")
    ph.done()
    return out


async def compare(browsers, site, name, vm, width, theme="", design=True, light=False):
    """Draws the ViewModel both ways at once; returns ({part: (console, library)} for every part that differs, the problems)."""
    key = re.sub(r"[^A-Za-z0-9_.-]", "_", name)
    site.add_vm(key, vm)
    site.add_console(f"{key}-{theme}-{int(design)}", H.console_html(vm, theme, design))
    o = json.dumps({"shadow": False}) if light else "{}"
    page = "library-light.html" if light else "library.html"
    a, b = await asyncio.gather(
        draw(browsers, site, f"{H.ORIGIN}/render-test/console/{key}-{theme}-{int(design)}.html", width, False),
        draw(browsers, site, f"{H.ORIGIN}/render-test/{page}?case={key}&design={int(design)}&theme={theme or 'inherit'}&o={o}", width, True))
    off = [u for u in a["seen"] + b["seen"] if not u.startswith(H.ORIGIN + "/")]
    diffs = missing(a, b)
    problems = []
    if b["overflow"] > 1:                                                     # the library never scrolls sideways
        problems.append(f"sideways scroll: library {b['overflow']} px (console {a['overflow']} px)")
    if a["overflow"] > 1:                                                     # a finding about the console's page, not a parity failure
        print(f"the console's page scrolls sideways (the library does not): {name} at {width}px by {a['overflow']}px")
    if a["csp"] or b["csp"]:
        problems.append(f"CSP violations: console {a['csp']}, library {b['csp']}")
    if b["errors"]:
        problems.append(f"library errors: {b['errors']}")
    if off:
        problems.append(f"requests off the console's origin: {off}")
    return diffs, problems


def explain(diffs) -> str:
    out = []
    for k, items in diffs.items():
        out.append(f"--- {k}: on the console's page, not in the library's")
        out += [f"  {str(i)[:300]}" for i in items[:40]]
    return "\n".join(out)


def assert_same(browsers, site, name, vm, widths=WIDTHS, **kw):
    """The ViewModel drawn both ways at every width given, all pages at once; every width must be the same."""
    async def all_widths():
        return await asyncio.gather(*(compare(browsers, site, name, vm, w, **kw) for w in widths))
    failures = []
    for width, (diffs, problems) in zip(widths, browsers.run(all_widths())):
        if problems:
            failures.append(f"{name} at {width}px: " + "; ".join(problems))
        if diffs:
            failures.append(f"{name} at {width}px ({kw}) misses content:\n" + explain(diffs))
    assert not failures, "\n\n".join(failures)


# ---- the captured views and the edge cases -----------------------------------------------------------------------------------
EDGES = cases.edge_cases()
FIXTURES = {k: v for k, v in cases.fixture_views().items() if k != cases.SHOWCASE[:-5]}


@pytest.mark.parametrize("theme", H.THEMES)
def test_the_showcase_is_the_same_in_every_theme(browsers, render_site, theme):
    assert_same(browsers, render_site, "showcase", EDGES["showcase"], theme=theme)


@pytest.mark.parametrize("theme", ["", "crimson-dark"])
@pytest.mark.parametrize("name", [k for k in EDGES if k != "showcase"])
def test_every_edge_case_is_the_same(browsers, render_site, name, theme):
    assert_same(browsers, render_site, name, EDGES[name], theme=theme)


@pytest.mark.parametrize("name", sorted(FIXTURES))
def test_every_captured_view_is_the_same(browsers, render_site, name):
    assert_same(browsers, render_site, name, FIXTURES[name])


def test_without_the_designer_the_sutra_is_named_without_a_link(browsers, render_site):
    assert_same(browsers, render_site, "showcase", EDGES["showcase"], design=False)


def test_light_dom_with_the_console_s_own_sheets_is_the_same(browsers, render_site):
    """shadow: false: drawn into the host's own element, styled by the page's sheets."""
    render_site.pages["/render-test/library-light.html"] = ('<!DOCTYPE html><html lang="en"><head><meta charset="utf-8"><title>light</title>'
                                                            + "".join(f'<link rel="stylesheet" href="{H._pin.static_url(s)}">' for s in H.CONSOLE_SHEETS)
                                                            + '</head><body class="terminal"><div id="host"></div><script src="/render-test/csp.js"></script>'
                                                            '<script type="module" src="/render-test/harness.js"></script></body></html>')
    assert_same(browsers, render_site, "showcase", EDGES["showcase"], light=True)


# ---- every Sutra of every shipped pack, bound on its own sample by a real server ------------------------------------------------
def test_the_pack_sutras_are_bound_on_their_samples(pack_views):
    views = pack_views["views"]
    print(f"\npack views bound: {len(views)}; not drawn: {len(pack_views['skipped'])}: " + "; ".join(pack_views["skipped"]))
    assert len(views) >= 100, f"too few pack views bound: {len(views)}; skipped {pack_views['skipped'][:10]}"


@pytest.mark.parametrize("sutra", P.drawable(), ids=P.test_id)
def test_every_pack_sutra_on_its_sample_is_the_same(browsers, render_site, pack_views, sutra):
    vm = pack_views["views"].get(sutra)
    assert vm is not None, f"not bound by the server's preview: {[s for s in pack_views['skipped'] if s.startswith(sutra)]}"   # a refusal is a failure, never a skip
    assert_same(browsers, render_site, sutra, vm)
