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

"""Parity: the client-side renderer draws what the console's macros draw (docs/guides/EMBED_GUIDE.md section 14).

Each ViewModel is drawn two ways, the console's server HTML and the library in a blank page, and compared: the accessible tree, the
visible text line by line, every cell, every link and its target, every tone, every chart's series and points (from its ECharts option,
not pixels), at 390 and 1280 px, with no sideways scroll, no CSP violation and no request off the console's origin. The ViewModels: the
captured views and the edge cases of VIEWMODEL.md (render_cases.py) in every theme, and every Sutra of every shipped pack bound on its
own sample by a real server (skipped when the server jar or a JDK is missing). The intentional differences are normalised by name in
``INTENTIONAL`` below; anything else fails.

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

# What differs on purpose, each with its reason (EMBED_GUIDE.md section 14.3). Applied to both sides before comparing.
INTENTIONAL = {
    "unknown-kind": "a kind the client does not know says so (VIEWMODEL.md section 8); the macros draw an empty body",
    "header-role": "a panel header in a tabbed grid area: Chromium exposes it as a banner when the console's script turns the section into a "
                   "tabpanel after parsing; the renderer sets the role before the panel is in the page, and HTML-AAM says generic",
    "resize-handles": "the console's resize handles are its chrome, bound by its own component (drishti-core's resize): a side column's "
                      "splitter, a table's column handles and the screen grid's split (drag, arrows, Home or a double-click: as designed); "
                      "the library, for embeds and <drishti-report>, draws the grid's split with its own handle (1.2.0, unchanged)",
}
UNKNOWN = re.compile(r"This panel kind is not supported by this client \([^)]*\)")
HANDLE = re.compile(r'''^\s*- '?separator "Resize the ''')                # a splitter or column handle: the console's only
SPLIT = re.compile(r'''^(\s*- )'?(separator "Divider between [^":]+)(?::[^"]*)?"'?$''')   # the grid's split, in both: its name, not its keys


def _aria(text: str) -> list[str]:
    out, drop = [], None
    for line in text.splitlines():
        ind = len(line) - len(line.lstrip())
        if drop is not None and ind <= drop:
            drop = None
        if line.strip() == "- banner:":                                    # header-role
            drop = ind
            continue
        if UNKNOWN.search(line):                                            # unknown-kind
            continue
        if HANDLE.match(line):                                              # resize-handles
            continue
        line = SPLIT.sub(r'\1\2"', line)
        out.append(line[2:] if drop is not None else line)
    return out


def normalised(r: dict) -> dict:
    return {"aria": _aria(r["aria"]), "lines": [x for x in r["lines"] if not UNKNOWN.search(x)], "cells": r["cells"], "links": r["links"],
            "tones": r["tones"], "charts": [json.dumps(c, sort_keys=True) for c in r["charts"]]}


async def draw(browsers, site, url, width, library):
    ph = Phases("parity-library" if library else "parity-console")
    async with browsers.page(width) as page:
        ph.mark("page")
        await page.goto(url)
        ph.mark("load")
        if library:
            await page.wait_for_function("() => window.__rendered === true", timeout=20000)
            ph.mark("rendered")
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
    na, nb = normalised(a), normalised(b)
    diffs = {k: (na[k], nb[k]) for k in na if na[k] != nb[k]}
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
    import difflib
    out = []
    for k, (x, y) in diffs.items():
        out.append(f"--- {k}")
        out += list(difflib.unified_diff([str(i) for i in x], [str(i) for i in y], "console", "library", lineterm="", n=1))[:40]
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
            failures.append(f"{name} at {width}px ({kw}) differs:\n" + explain(diffs))
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
    """shadow: false (how the console itself will draw its pages, phase 2): the host's sheets style it."""
    render_site.pages["/render-test/library-light.html"] = ('<!DOCTYPE html><html lang="en"><head><meta charset="utf-8"><title>light</title>'
                                                            + "".join(f'<link rel="stylesheet" href="/static/{s}">' for s in H.CONSOLE_SHEETS)
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
    if vm is None:
        pytest.skip(f"not bound by the server's preview: {[s for s in pack_views['skipped'] if s.startswith(sutra)]}")
    assert_same(browsers, render_site, sutra, vm)
