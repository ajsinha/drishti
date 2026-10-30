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

"""Parity: the client-side renderer draws what the console's macros draw (docs/architecture/CLIENT_RENDERER.md section 14).

Each ViewModel is drawn two ways, the console's server HTML and the library in a blank page, and compared: the accessible tree, the
visible text line by line, every cell, every link and its target, every tone, every chart's series and points (from its ECharts option,
not pixels), at 390 and 1280 px, with no sideways scroll, no CSP violation and no request off the console's origin. The ViewModels: the
captured views and the edge cases of VIEWMODEL.md (render_cases.py) in every theme, and every Sutra of every shipped pack bound on its
own sample by a real server (skipped when the server jar or a JDK is missing). The intentional differences are normalised by name in
``INTENTIONAL`` below; anything else fails."""
import json
import re
from pathlib import Path

import pytest

import render_cases as cases
import render_harness as H
from wb_live import live_console  # noqa: F401 - the fixture

sync_api = pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
ROOT = Path(__file__).resolve().parents[2]
WIDTHS = (390, 1280)

# What differs on purpose, each with its reason (CLIENT_RENDERER.md section 14.3). Applied to both sides before comparing.
INTENTIONAL = {
    "unknown-kind": "a kind the client does not know says so (VIEWMODEL.md section 8); the macros draw an empty body",
    "header-role": "a panel header in a tabbed grid area: Chromium exposes it as a banner when the console's script turns the section into a "
                   "tabpanel after parsing; the renderer sets the role before the panel is in the page, and HTML-AAM says generic",
}
CONSOLE_OVERFLOW: set = set()         # (view, width, px) where the console's own page scrolls sideways (CLIENT_RENDERER.md 14.3)
UNKNOWN = re.compile(r"This panel kind is not supported by this client \([^)]*\)")


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
        out.append(line[2:] if drop is not None else line)
    return out


def normalised(r: dict) -> dict:
    return {"aria": _aria(r["aria"]), "lines": [x for x in r["lines"] if not UNKNOWN.search(x)], "cells": r["cells"], "links": r["links"],
            "tones": r["tones"], "charts": [json.dumps(c, sort_keys=True) for c in r["charts"]]}


@pytest.fixture(scope="module")
def browser():
    with sync_api.sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001 - no browser installed: skip
            pytest.skip(f"needs Playwright's Chromium: {str(e).splitlines()[0]}")
        yield b
        b.close()


@pytest.fixture(scope="module")
def site():
    return H.Site()


def draw(browser, site, url, width, library):
    page = browser.new_page(viewport={"width": width, "height": 900})
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.route("**/*", site.handle)
    try:
        page.goto(url)
        if library:
            page.wait_for_function("() => window.__rendered === true", timeout=20000)
        H.settle(page)
        out = H.read(page)
        out["errors"] = errors
        return out
    finally:
        page.close()


def compare(browser, site, name, vm, width, theme="", design=True, light=False):
    """Draws the ViewModel both ways; returns {part: (console, library)} for every part that differs, and both reads."""
    key = re.sub(r"[^A-Za-z0-9_.-]", "_", name)
    site.add_vm(key, vm)
    site.add_console(f"{key}-{theme}-{int(design)}", H.console_html(vm, theme, design))
    before = len(site.requests)
    a = draw(browser, site, f"{H.ORIGIN}/render-test/console/{key}-{theme}-{int(design)}.html", width, False)
    o = json.dumps({"shadow": False}) if light else "{}"
    page = "library-light.html" if light else "library.html"
    b = draw(browser, site, f"{H.ORIGIN}/render-test/{page}?case={key}&design={int(design)}&theme={theme or 'inherit'}&o={o}", width, True)
    off = [u for u in site.requests[before:] if not u.startswith(H.ORIGIN + "/")]
    na, nb = normalised(a), normalised(b)
    diffs = {k: (na[k], nb[k]) for k in na if na[k] != nb[k]}
    problems = []
    if b["overflow"] > 1:                                                     # the library never scrolls sideways
        problems.append(f"sideways scroll: library {b['overflow']} px (console {a['overflow']} px)")
    if a["overflow"] > 1:                                                     # a finding about the console's page, not a parity failure
        CONSOLE_OVERFLOW.add((name, width, a["overflow"]))
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


def assert_same(browser, site, name, vm, width, **kw):
    diffs, problems = compare(browser, site, name, vm, width, **kw)
    assert not problems, f"{name} at {width}px: " + "; ".join(problems)
    assert not diffs, f"{name} at {width}px ({kw}) differs:\n" + explain(diffs)


# ---- the captured views and the edge cases -----------------------------------------------------------------------------------
EDGES = cases.edge_cases()
FIXTURES = {k: v for k, v in cases.fixture_views().items() if k != cases.SHOWCASE[:-5]}


@pytest.mark.parametrize("width", WIDTHS)
@pytest.mark.parametrize("theme", H.THEMES)
def test_the_showcase_is_the_same_in_every_theme(browser, site, theme, width):
    assert_same(browser, site, "showcase", EDGES["showcase"], width, theme=theme)


@pytest.mark.parametrize("width", WIDTHS)
@pytest.mark.parametrize("theme", ["", "crimson-dark"])
@pytest.mark.parametrize("name", [k for k in EDGES if k != "showcase"])
def test_every_edge_case_is_the_same(browser, site, name, theme, width):
    assert_same(browser, site, name, EDGES[name], width, theme=theme)


@pytest.mark.parametrize("width", WIDTHS)
@pytest.mark.parametrize("name", sorted(FIXTURES))
def test_every_captured_view_is_the_same(browser, site, name, width):
    assert_same(browser, site, name, FIXTURES[name], width)


@pytest.mark.parametrize("width", WIDTHS)
def test_without_the_designer_the_sutra_is_named_without_a_link(browser, site, width):
    assert_same(browser, site, "showcase", EDGES["showcase"], width, design=False)


def test_light_dom_with_the_console_s_own_sheets_is_the_same(browser, site):
    """shadow: false (how the console itself will draw its pages, phase 2): the host's sheets style it."""
    site.pages["/render-test/library-light.html"] = ('<!DOCTYPE html><html lang="en"><head><meta charset="utf-8"><title>light</title>'
                                                     + "".join(f'<link rel="stylesheet" href="/static/{s}">' for s in H.CONSOLE_SHEETS)
                                                     + '</head><body class="terminal"><div id="host"></div><script src="/render-test/csp.js"></script>'
                                                     '<script type="module" src="/render-test/harness.js"></script></body></html>')
    for width in WIDTHS:
        assert_same(browser, site, "showcase", EDGES["showcase"], width, light=True)


# ---- every Sutra of every shipped pack, bound on its own sample by a real server ------------------------------------------------
def pack_sutras() -> list[tuple[str, Path]]:
    out = []
    for f in sorted((ROOT / "config" / "packs").glob("*/sutras/**/*.sutra.yaml")):
        out.append((f.relative_to(ROOT / "config" / "packs").parts[0], f))
    for f in sorted((ROOT / "docs" / "guides" / "examples").glob("*.sutra.yaml")):
        out.append(("examples", f))
    return out


def _sample(pack: str, sutra: Path, kind: str, text: str) -> tuple[str, dict] | None:
    if pack == "examples":
        doc = sutra.with_name(sutra.name.replace(".sutra.yaml", ".json"))
        return ("SAMPLE", json.loads(doc.read_text(encoding="utf-8"))) if doc.exists() else None
    samples = ROOT / "config" / "packs" / pack / "samples" / f"{kind}.jsonl"
    if not samples.exists():
        return None
    m = re.search(r'"/v/[^/]+/([^"]+)"', text)
    want = m.group(1) if m else None
    first = None
    with samples.open(encoding="utf-8") as fh:
        for line in fh:
            if not line.strip():
                continue
            first = first or line
            if want and f'"{want}"' in line:
                return want, json.loads(line)
    return (want or "SAMPLE", json.loads(first)) if first else None


@pytest.fixture(scope="module")
def pack_views(live_console):
    """{sutra file: ViewModel} for every pack Sutra with a sample document: POST /api/v1/studio/preview on a real server."""
    import urllib.request
    import yaml
    got, skipped = {}, []
    for pack, f in pack_sutras():
        text = f.read_text(encoding="utf-8")
        y = yaml.safe_load(text) or {}
        kind = ((y.get("match") or {}).get("kind")) if isinstance(y.get("match"), dict) else None
        kind = kind or "trade"
        sample = _sample(pack, f, kind, text)
        if not sample:
            skipped.append(str(f.relative_to(ROOT)))
            continue
        body = json.dumps({"yaml": text, "kind": kind, "id": sample[0], "document": sample[1]}).encode()
        req = urllib.request.Request(live_console.server + "/api/v1/studio/preview", data=body, headers={"Content-Type": "application/json"}, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                got[str(f.relative_to(ROOT))] = json.loads(r.read())
        except Exception as e:  # noqa: BLE001 - recorded: a Sutra the preview refuses is reported, not drawn
            skipped.append(f"{f.relative_to(ROOT)}: {e}")
    return {"views": got, "skipped": skipped}


def test_every_pack_sutra_on_its_sample_is_the_same(browser, site, pack_views):
    views = pack_views["views"]
    assert len(views) >= 100, f"too few pack views bound: {len(views)}; skipped {pack_views['skipped'][:10]}"
    failures, kinds = [], set()
    for name, vm in views.items():
        kinds.update(p["kind"] for p in vm.get("panels") or [])
        for width in WIDTHS:
            diffs, problems = compare(browser, site, name, vm, width)
            if diffs or problems:
                failures.append(f"{name} at {width}px: {'; '.join(problems)}\n{explain(diffs)}")
    print(f"\npack parity: {len(views)} views x {len(WIDTHS)} widths, kinds {sorted(kinds)}; not drawn: {len(pack_views['skipped'])}")
    print("not drawn: " + "; ".join(pack_views["skipped"]))
    print("the console's page scrolls sideways (the library does not): " + "; ".join(f"{n} at {w}px by {px}px" for n, w, px in sorted(CONSOLE_OVERFLOW)))
    assert not failures, f"{len(failures)} of {len(views) * len(WIDTHS)} differ:\n" + "\n\n".join(failures[:8])
