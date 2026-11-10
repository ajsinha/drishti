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

"""The one keymap (2.9.11.e, web/static/js/drishti_keys.js), checked on the source: every key a page answers is registered with the
keymap, written in its canonical form, and no two registrations claim the same key in the same scope and area; and no script listens for
keys on the whole page (document, window, body) on its own, except the few on ALLOWED below, each with its reason. A widget's keys bound
on its own element (a table, a drop-down, a dialog's field) are the widget's and are not page-level. The browser side (the ? overlay,
Alt+K): test_keymap_browser.py."""
from __future__ import annotations

import re
from pathlib import Path

import pytest

pytestmark = pytest.mark.static   # a quick file check: the drill's gate runs it (TEST_HARNESS.md, "Quick file checks")

WEB = Path(__file__).resolve().parent.parent / "web"
JS = WEB / "static" / "js"
KEYS_JS = JS / "drishti_keys.js"

# Page-level key listeners that stay outside the keymap: file (relative to web/static/js) -> (how many, why).
ALLOWED = {
    # a host page without the console's keymap (the client-side renderer's 'console' behaviours): the drill binds the document itself
    "drishti_drill.js": (1, "fallback for a host page that has no keymap; on a console page the drill registers drill.keys"),
    # BI's tab lists in <drishti-report> on another site (no keymap there): the same fallback
    "drishti_bi-lib.js": (1, "fallback for a host page that has no keymap; on a console page BI's tabs register bi.tabs"),
}

# Keys bound on a widget's own element and only described to the keymap (so the ? overlay lists them): they must stay described.
WIDGET_DESCRIBED = {
    "drishti_tables.js": "table.walk",          # a table's row walk, chart, clear filters
    "drishti_zoom.js": "panel.zoom",            # a panel's zoom
    "drishti_about.js": "about.toggle",         # About this page (F1, ?)
}

PAGE_LEVEL = re.compile(
    r"""\b(?:document|window|document\.body|document\.documentElement)\s*\.\s*"""
    r"""(?:addEventListener\(\s*['"]key(?:down|up|press)['"]|onkey(?:down|up|press)\s*=)""")
CALL = re.compile(r"""\.(register|describe)\(\s*\{""")
MODS = ("Ctrl", "Alt", "Shift", "Meta")
SCOPES = ("dialog", "panel", "screen", "global")


def _scripts():
    """The console's scripts and the sources of the libraries that register keys on a page (drishti-insight: Explain and the search box)."""
    insight = WEB / "static" / "elements" / "drishti-insight" / "src"
    return sorted([p for p in JS.rglob("*.js") if "vendor" not in p.parts] + list(insight.glob("drishti_*.js")))


def _rel(p):
    """A script's name as the lists above write it: relative to static/js, a library's source by its own name."""
    return p.relative_to(JS).as_posix() if JS in p.parents else p.name


def _templates():
    return sorted(p for p in (WEB / "templates").rglob("*.html"))


def _strip_comments(src: str) -> str:
    out, i, n, quote = [], 0, len(src), None
    while i < n:
        c = src[i]
        if quote:
            out.append(c)
            if c == "\\" and i + 1 < n:
                out.append(src[i + 1])
                i += 2
                continue
            if c == quote:
                quote = None
        elif c in "'\"`":
            quote = c
            out.append(c)
        elif src.startswith("//", i) and (i == 0 or src[i - 1] not in ":\\"):
            j = src.find("\n", i)
            i = n if j < 0 else j
            continue
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        else:
            out.append(c)
        i += 1
    return "".join(out)


def _object_at(src: str, start: int) -> str:
    depth, quote, i = 0, None, start
    while i < len(src):
        c = src[i]
        if quote:
            if c == "\\":
                i += 2
                continue
            if c == quote:
                quote = None
        elif c in "'\"`":
            quote = c
        elif c in "{[(":
            depth += 1
        elif c in "}])":
            depth -= 1
            if depth == 0:
                return src[start:i + 1]
        i += 1
    return src[start:]


def canonical(chord: str) -> str:
    """drishti_keys.js canonical(): modifiers Ctrl, Alt, Shift, Meta, then the key; a letter in capitals; a printed sign never names Shift."""
    parts = chord.split("+")
    key = parts.pop()
    if key == "" and parts and parts[-1] == "":
        parts.pop()
        key = "+"
    mods = [m for m in MODS if m in parts]
    if len(key) == 1:
        key = key.upper()
        if not re.match(r"[A-Z0-9]", key):
            mods = [m for m in mods if m != "Shift"]
    return "+".join(mods + [key])


def _field(obj: str, name: str):
    m = re.search(r"\b" + name + r"""\s*:\s*(['"])(.*?)\1(\s*\+)?""", obj)
    return m.group(2) if m and not m.group(3) else None    # a computed value ('view.fkey.' + key): None


def _strings(listed: str):
    return [k[1] for k in re.findall(r"""(['"])((?:(?!\1).)*)\1""", listed)]


def _keys(obj: str):
    m = re.search(r"\bkeys\s*:\s*\[", obj)
    if not m:
        return None                                        # computed (a variable): the browser test sees it
    return _strings(_object_at(obj, m.end() - 1))


def registrations():
    """Every keymap registration written as a literal: (file, id, keys, scope, area, listed-only)."""
    found = []
    for p in _scripts():
        src = _strip_comments(p.read_text(encoding="utf-8"))
        for m in CALL.finditer(src):
            obj = _object_at(src, m.end() - 1)
            if not re.search(r"\bkeys\s*:", obj) or not re.search(r"\bid\s*:", obj):
                continue                                   # another register() (an editor's, a renderer's)
            rid, scope = _field(obj, "id"), _field(obj, "scope")
            area = "*" if scope == "global" else (_field(obj, "area") or "")
            found.append((_rel(p), rid, _keys(obj), scope, area, m.group(1) == "describe"))
        if p.name == "drishti_history-page.js":            # its helper hx(id, keys, ...) registers screen keys in area 'history'
            for hm in re.finditer(r"""\bhx\(\s*'([^']+)'\s*,\s*\[""", src):
                found.append((p.name, "history." + hm.group(1), _strings(_object_at(src, hm.end() - 1)), "screen", "history", False))
    return found


def test_registrations_are_found():
    regs = registrations()
    ids = {r[1] for r in regs}
    assert len(regs) >= 50, len(regs)
    for rid in ("keys.overlay", "find.open", "command.focus", "help.context", "history.next", "explain.open", "bi.go", "pivot.chart"):
        assert rid in ids, rid


def test_every_registration_names_a_scope():
    bad = [(f, rid, scope) for f, rid, _keys, scope, _area, _d in registrations() if scope not in SCOPES]
    assert bad == [], "a registration's scope is dialog, panel, screen or global: " + repr(bad)


def test_every_non_global_registration_names_its_area():
    bad = [(f, rid) for f, rid, _keys, scope, area, _d in registrations() if scope != "global" and not area]
    assert bad == [], "a dialog, panel or screen registration names its area: " + repr(bad)


def test_keys_are_written_canonically():
    bad = [(f, rid, k, canonical(k)) for f, rid, keys, *_ in registrations() for k in (keys or []) if canonical(k) != k]
    assert bad == [], "write each chord in its canonical form (Ctrl, Alt, Shift, Meta, then the key): " + repr(bad)


def test_no_two_registrations_claim_one_key_in_one_scope():
    seen, clashes = {}, []
    for f, rid, keys, scope, area, _d in registrations():
        if rid is None or keys is None:
            continue
        for k in keys:
            other = seen.setdefault((scope, area, k), (f, rid))
            if other[1] != rid:
                clashes.append(f"{k} in {scope} {area}: {other[1]} ({other[0]}) and {rid} ({f})")
    assert clashes == [], "conflicting keys:\n" + "\n".join(clashes)


def test_ids_are_unique_across_files():
    owner, dup = {}, []
    for f, rid, *_ in registrations():
        if rid is None:
            continue
        if owner.setdefault(rid, f) != f:
            dup.append(f"{rid}: {owner[rid]} and {f}")
    assert dup == [], "one id, one file: " + repr(dup)


def test_no_stray_page_level_key_handlers():
    counts = {}
    for p in _scripts():
        if p == KEYS_JS:
            continue
        n = len(PAGE_LEVEL.findall(_strip_comments(p.read_text(encoding="utf-8"))))
        if n:
            counts[_rel(p)] = n
    stray = {f: n for f, n in counts.items() if f not in ALLOWED}
    assert stray == {}, ("register page keys with Drishti.keys.register (drishti_keys.js), not on document/window: " + repr(stray))
    grown = {f: (n, ALLOWED[f][0]) for f, n in counts.items() if f in ALLOWED and n > ALLOWED[f][0]}
    assert grown == {}, "an allowed file gained page-level key handlers (found, allowed): " + repr(grown)


def test_allow_list_has_no_stale_entries():
    stale = [f for f in ALLOWED if not (JS / f).exists()
             or not PAGE_LEVEL.search(_strip_comments((JS / f).read_text(encoding="utf-8")))]
    assert stale == [], "these files no longer listen on the page: take them off ALLOWED: " + repr(stale)


def test_templates_bind_no_page_keys():
    bad = [p.relative_to(WEB).as_posix() for p in _templates() if PAGE_LEVEL.search(p.read_text(encoding="utf-8"))]
    assert bad == [], bad


def test_widget_keys_are_described():
    regs = {(f, rid) for f, rid, *_ in registrations()}
    missing = [(f, rid) for f, rid in WIDGET_DESCRIBED.items() if (f, rid) not in regs]
    assert missing == [], "a widget's own keys are described to the keymap (the ? overlay lists them): " + repr(missing)


def test_keymap_and_search_load_on_every_console_page():
    base = (WEB / "templates" / "base.html").read_text(encoding="utf-8")
    keys_at, search_at = base.find("drishti_keys.js"), base.find("drishti-insight/drishti_insight.js")
    assert 0 <= keys_at < search_at, "base.html loads drishti_keys.js, then drishti-insight (the search box and Explain)"
    assert "drishti_keys.css" in base


@pytest.mark.parametrize("chord,expected", [
    ("Shift+?", "?"), ("Alt+Shift+?", "Alt+?"), ("Shift+Ctrl+a", "Ctrl+Shift+A"), ("Ctrl++", "Ctrl++"), ("Meta+Shift+z", "Shift+Meta+Z"),
])
def test_canonical_mirrors_the_script(chord, expected):
    assert canonical(chord) == expected
