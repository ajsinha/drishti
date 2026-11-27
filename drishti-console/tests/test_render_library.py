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

"""The client-side renderer's distribution and rules, without a browser (docs/architecture/ARCHITECTURE.md sections 30.6, 30.7 and 30.9):
a version's folder is immutable (its integrity record matches every file), the sheet is generated and current, the sources never write
markup or evaluate code, nothing comes from a CDN, every file carries the licence and stays under the size cap, every kind of the grammar
is drawn, and the console's routes the library needs (the playground and the raw stream) answer."""
import json
import re

from core import render_dist as rd
from core import elements_pin  # the vendored Drishti Elements
from code_cap import CODE_LINE_CAP  # noqa: E402  (tools/code_cap.py: the one line cap of a code file)

SRC = rd.FOLDER
FORBIDDEN = re.compile(r"\.innerHTML\b|\.outerHTML\b|insertAdjacentHTML|document\.write|\beval\s*\(|new\s+Function\b|setTimeout\(\s*['\"]|createContextualFragment")
EXTERNAL = re.compile(r"""(?:from|import)\s*\(?\s*['"]https?://|(?:src|href)\s*=\s*['"]\s*(?:https?:)?//|url\(\s*['"]?https?://""", re.I)


def js_files():
    """The current version's files: its entries and its host part (m/)."""
    return sorted(SRC.rglob("*.js"))


def code(path) -> str:
    """A source without its comments (they may name what the code never does)."""
    text = re.sub(r"/\*.*?\*/", "", path.read_text(encoding="utf-8"), flags=re.S)
    return re.sub(r"(?m)^\s*//.*$", "", text)


def test_a_version_is_one_folder_and_its_record_matches_every_file():
    assert rd.VERSION in rd.versions()
    have = json.loads((SRC / rd.RECORD).read_text(encoding="utf-8"))
    assert have == rd.record(), "a file of static/render/%s changed: a released version is immutable; make a new version folder and run tools/render_sheet.py" % rd.VERSION
    assert {"drishti-render.js", "drishti-render.css", "drishti-render.d.ts"} <= set(have["files"])
    assert all(v["integrity"].startswith("sha384-") for v in have["files"].values())


def test_the_sheet_is_generated_and_current():
    text, facts = rd.build_sheet()
    assert (SRC / rd.SHEET).read_text(encoding="utf-8") == text, "stale: python3 tools/render_sheet.py"
    assert "@font-face" not in text and ":root" not in text and "@container drishti" in text
    assert "[hidden] { display: none !important; }" in text
    for glyph in ("arrow-left", "info-circle", "shield-lock", "box-arrow-in-right"):
        assert f".bi-{glyph}::before" in text, glyph


def test_the_sources_never_write_markup_or_evaluate_code():
    offenders = {p.name: FORBIDDEN.findall(code(p)) for p in js_files()}
    assert {k: v for k, v in offenders.items() if v} == {}


def test_nothing_comes_from_outside_the_console():
    for p in js_files() + list(SRC.rglob("*.css")) + list(SRC.rglob("*.html")):
        assert not EXTERNAL.search(code(p)), p


def test_every_file_has_the_licence_and_stays_under_the_cap():
    for p in list(SRC.rglob("*")) + js_files():
        if p.is_file() and p.suffix in (".js", ".css", ".ts", ".html"):
            text = p.read_text(encoding="utf-8")
            assert "PROPRIETARY AND CONFIDENTIAL" in text[:1200], p
            assert p.name == rd.SHEET or len(text.splitlines()) <= CODE_LINE_CAP, p          # the generated sheet is not a source


def test_every_kind_of_the_grammar_is_drawn_by_the_painter():
    """Every panel kind the console's macros draw has a painter in Drishti Elements' drishti-views (the one way to draw a ViewModel in the
    browser); facets, the old renderer's kind that no server sends, is gone (I7B_DUPLICATES DD-11)."""
    views = elements_pin.ROOT / "elements" / "drishti-views" / "src"
    text = "\n".join(p.read_text(encoding="utf-8") for p in views.glob("*.js"))
    drawn = set(re.findall(r"defineKind\('([a-z-]+)'", text))
    drawn |= set(re.findall(r"'([a-z]+)'", re.search(r"CHART_KINDS = Object\.freeze\(\[(.*?)\]\)", text, re.S).group(1)))
    macro = set(re.findall(r"p\.kind (?:==|in) \[?'([a-z]+)'", (rd.WEB / "templates/_macros/panels.html").read_text(encoding="utf-8")))
    macro |= set(re.findall(r"'([a-z]+)'", re.search(r"p\.kind in \(([^)]*)\)", (rd.WEB / "templates/_macros/panels.html").read_text(encoding="utf-8")).group(1)))
    assert macro - {"facets"} <= drawn, sorted(macro - drawn)


def test_the_host_part_draws_no_panel_and_keeps_no_grid_chart_format_or_live_code_of_its_own():
    """No dual machinery (owner 11-16): every panel is drawn by the painter (drishti-views paint()); the host part only fetches, signs in,
    navigates and draws its own bar, sign-in card and About sheet. Streams are drishti-events' (raw mode)."""
    src = {p.name: code(p) for p in rd.LIBRARY.glob("*.js")}
    engine = re.compile(r"echarts\.init|\.setOption\(|getInstanceByDom|new EventSource|\.toFixed\(|Intl\.NumberFormat|createElement\('table'\)|h\('table'|h\('td'")
    assert {k: engine.findall(v) for k, v in src.items() if engine.search(v)} == {}
    joined = "\n".join(src.values())
    for lib in ("drishti-views", "drishti-core", "drishti-events"):
        assert f"/vendor/drishti-elements/{elements_pin.VERSION}/elements/{lib}/src/drishti_index.js" in joined, lib   # the vendored copy
    assert "paint as paintView" in src["drishti_screen.js"]
    assert not (rd.WEB / "static" / "elements" / "drishti-renderer").exists(), "the old renderer library is gone"


def test_the_playground_asks_for_sign_in_first():
    from core.app import protected
    assert protected("/render/demo")            # signed out: the console's login, then back here (its data calls need a session)


def test_the_playground_route_opens_the_current_version(client):
    r = client.get("/render/demo?kind=trade&id=IRS-48213", follow_redirects=False)
    assert r.status_code == 302 and r.headers["location"] == f"/static/render/{rd.VERSION}/demo.html?kind=trade&id=IRS-48213"
    page = client.get(f"/static/render/{rd.VERSION}/demo.html")
    assert page.status_code == 200 and 'type="module" src="demo.js"' in page.text
    assert "script-src 'self'" in page.headers["content-security-policy"]
    js = client.get(f"/static/render/{rd.VERSION}/drishti-render.js")
    assert js.status_code == 200 and "javascript" in js.headers["content-type"]


def test_the_raw_stream_relays_the_server_s_own_events(client, backend):
    vm = {"ref": {"kind": "trade", "id": "T-1"}, "panels": [], "strip": [], "provenance": {"generation": 3}}
    frame = {"seq": 1, "generation": 4, "patches": [{"op": "panel", "panel": {"id": "p", "kind": "kv", "data": {"fields": []}}}]}

    async def fake_stream(kind, id_, ident=None, **kw):
        yield "view", json.dumps(vm)
        yield "frame", json.dumps(frame)

    backend.stream = fake_stream
    with client.stream("GET", "/api/stream/trade/T-1?raw=1") as r:
        body = "".join(r.iter_text())
    events = dict(re.findall(r"event: (\w+)\ndata: (.*)\n\n", body))
    assert json.loads(events["view"]) == vm and json.loads(events["frame"]) == frame       # the panel stays a panel, no html
    with client.stream("GET", "/api/stream/trade/T-1") as r:
        cooked = "".join(r.iter_text())
    assert '"html"' in cooked and '"generation": 3' in cooked                              # the console's own pages: unchanged
