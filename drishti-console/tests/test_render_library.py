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

"""The client-side renderer's distribution and rules, without a browser (docs/architecture/CLIENT_RENDERER.md sections 5, 9 and 13):
a version's folder is immutable (its integrity record matches every file), the sheet is generated and current, the sources never write
markup or evaluate code, nothing comes from a CDN, every file carries the licence and stays under the size cap, every kind of the grammar
is drawn, and the console's routes the library needs (the playground and the raw stream) answer."""
import json
import re

from core import render_dist as rd

SRC = rd.FOLDER
FORBIDDEN = re.compile(r"\.innerHTML\b|\.outerHTML\b|insertAdjacentHTML|document\.write|\beval\s*\(|new\s+Function\b|setTimeout\(\s*['\"]|createContextualFragment")
EXTERNAL = re.compile(r"""(?:from|import)\s*\(?\s*['"]https?://|(?:src|href)\s*=\s*['"]\s*(?:https?:)?//|url\(\s*['"]?https?://""", re.I)


def js_files():
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
    for glyph in ("inbox", "lock", "question-circle", "hand-index", "arrow-left", "chevron-down"):
        assert f".bi-{glyph}::before" in text, glyph


def test_the_sources_never_write_markup_or_evaluate_code():
    offenders = {p.name: FORBIDDEN.findall(code(p)) for p in js_files()}
    assert {k: v for k, v in offenders.items() if v} == {}


def test_nothing_comes_from_outside_the_console():
    for p in list(SRC.rglob("*.js")) + list(SRC.rglob("*.css")) + list(SRC.rglob("*.html")):
        assert not EXTERNAL.search(code(p)), p


def test_every_file_has_the_licence_and_stays_under_the_cap():
    for p in SRC.rglob("*"):
        if p.is_file() and p.suffix in (".js", ".css", ".ts", ".html"):
            text = p.read_text(encoding="utf-8")
            assert "PROPRIETARY AND CONFIDENTIAL" in text[:1200], p
            assert p.name == rd.SHEET or len(text.splitlines()) <= 1500, p          # the generated sheet is not a source


def test_every_kind_of_the_grammar_is_drawn():
    kinds = re.search(r"KINDS = Object\.freeze\(\[(.*?)\]\)", (SRC / "m" / "kinds.js").read_text(encoding="utf-8"), re.S).group(1)
    drawn = set(re.findall(r"'([a-z]+)'", kinds))
    macro = set(re.findall(r"p\.kind (?:==|in) \[?'([a-z]+)'", (rd.WEB / "templates/_macros/panels.html").read_text(encoding="utf-8")))
    macro |= set(re.findall(r"'([a-z]+)'", re.search(r"p\.kind in \(([^)]*)\)", (rd.WEB / "templates/_macros/panels.html").read_text(encoding="utf-8")).group(1)))
    assert len(drawn) == 22 and macro <= drawn, sorted(macro - drawn)


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
