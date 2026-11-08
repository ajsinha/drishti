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
"""The standing rule (the product owner, 2026-11-07; docs/architecture/ELEMENTS.md section 3.1): no bespoke UI. Every designer, editor and
authoring tool is assembled from Drishti Elements; a need the libraries do not meet is met by extending a library, never by code in the
screen. This guard holds it for what designers and editors are made of. No console script (static/js) or page (templates) implements:

* its own drag and drop or pointer-dragged layout (a canvas, a sortable list, a resizable pane by the pointer): drishti-designer's
  LayoutCanvas, DragHub and trackPointer, or drishti-core's resize handles;
* its own code or YAML editor (CodeMirror made by hand, a mode of its own, a text area for code): drishti-inputs' CodeEditor, the kit's
  YamlPane, or a <textarea data-di-code="yaml"> that drishti-inputs makes a code editor;
* its own tree view for an outline: drishti-designer's Outline (or drishti-inputs' TreePicker);
* its own undo history: drishti-designer's CommandStack or SnapshotHistory.

The first two hold for every script and page (no screen needs a drag or a code editor of its own); the tree and the undo rules hold for
the authoring screens (AUTHORING: designers, editors, Build, Studio, forms, notebooks, packs, transforms), since a reading screen's tree
of data or a zoom's way back is not a designer's.

ALLOWED lists the files still being moved, each with the rules it still breaks; it only shrinks: an entry whose file is gone, or no
longer breaks the rule, fails the check, so the list stays true. Comments are left out."""
import re

import pytest

from conftest import CONSOLE

pytestmark = pytest.mark.static   # a quick file check: the drill's gate runs it

WEB = CONSOLE / "web"

RULES = {
    "drag": (re.compile(r"\bsetPointerCapture\b|addEventListener\(\s*['\"](?:dragstart|pointermove)['\"]|\.draggable\s*=|['\"]?draggable['\"]?\s*:\s*['\"]?true"
                        r"|\bdraggable=|dataTransfer\.setData"),
             "drags or lays out by the pointer itself: use drishti-designer (LayoutCanvas, DragHub, trackPointer) or drishti-core's resize handles"),
    "code-editor": (re.compile(r"""\bfromTextArea\b|\bCodeMirror\s*\(\s*[\w.]+\s*,|CodeMirror\.defineMode\b|<textarea(?![^>]*data-di-code=)[^>]*(?:data-[\w-]*(?:yaml|sutra|code|python|formula)\b|name="(?:body|yamlbody)")"""),
                    "makes its own code or YAML editor: use drishti-inputs' CodeEditor or drishti-designer's YamlPane"),
    "tree": (re.compile(r"""role['"]?\s*[:=,]\s*['"]tree['"]|setAttribute\(\s*['"]role['"]\s*,\s*['"]tree['"]"""),
             "draws its own tree view: use drishti-designer's Outline or drishti-inputs' TreePicker"),
    "undo": (re.compile(r"\b(?:undo|redo)(?:Stack)?\s*=\s*\[\s*\]|\bconst\s+undo\s*=\s*\[|\bvar\s+undo\s*=\s*\["),
             "keeps its own undo history: use drishti-designer's CommandStack or SnapshotHistory"),
}

AUTHORING = re.compile(r"design|editor|build|studio|outline|form|notebook|pack-new|transform|calc|alert|connector|lens|edition|workflow|approval")
AUTHORING_ONLY = {"tree", "undo"}
# file (relative to drishti-console/web) -> the rules it still breaks (each the designer or editor still being moved onto the kit)
ALLOWED = {
    "templates/build/design.html": {"tree"},                      # the workbench's data-shape tree (drishti_build-shape-tree.js)
}

COMMENT = re.compile(r"/\*.*?\*/|(?<![:\\'\"])//[^\n]*|\{#.*?#\}|<!--.*?-->", re.S)


def _sources():
    files = sorted((WEB / "static" / "js").rglob("*.js")) + sorted((WEB / "templates").rglob("*.html"))
    return {str(p.relative_to(WEB)): p for p in files}


def _broken(text, name=None):
    code = COMMENT.sub(lambda m: "\n" * m.group(0).count("\n"), text)
    out = {}
    for rule, (pattern, _) in RULES.items():
        if rule in AUTHORING_ONLY and name is not None and not AUTHORING.search(name):
            continue
        for line in code.splitlines():
            if pattern.search(line):
                out[rule] = line.strip()[:140]
                break
    return out


@pytest.fixture(scope="module")
def findings():
    return {name: _broken(p.read_text(encoding="utf-8", errors="replace"), name) for name, p in _sources().items()}


@pytest.mark.parametrize("rule", sorted(RULES))
def test_no_designer_or_editor_keeps_bespoke_ui(findings, rule):
    bad = [f"{name}: {RULES[rule][1]}\n      {found[rule]}" for name, found in findings.items() if rule in found and rule not in ALLOWED.get(name, set())]
    assert not bad, "\n".join(bad)


def test_the_allow_list_only_shrinks():
    """Every entry is a file still being moved onto the kit, and it still breaks the rule (else it comes off the list)."""
    sources = _sources()
    stale = []
    for name, rules in ALLOWED.items():
        if name not in sources:
            stale.append(f"{name}: gone (take it off the list)")
            continue
        found = _broken(sources[name].read_text(encoding="utf-8", errors="replace"), name)
        stale += [f"{name}: keeps the rule {r} now (take it off the list)" for r in sorted(rules) if r not in found]
    assert not stale, "\n".join(stale)


def test_the_allow_list_is_capped():
    """The list never grows past what it was when the rule was made (2026-11-07): new files cannot be added to it."""
    assert len(ALLOWED) <= 1 and sum(len(r) for r in ALLOWED.values()) <= 1


def test_the_guard_finds_what_it_looks_for():
    assert set(_broken("el.addEventListener('dragstart', go);")) == {"drag"}
    assert set(_broken("h.setPointerCapture(e.pointerId);")) == {"drag"}
    assert set(_broken("var ed = CodeMirror.fromTextArea(ta, {});")) == {"code-editor"}
    assert set(_broken('<textarea data-yaml rows="9"></textarea>')) == {"code-editor"}
    assert _broken('<textarea data-yaml data-di-code="yaml" rows="9"></textarea>') == {}            # drishti-inputs makes it a code editor
    assert set(_broken("ul.setAttribute('role', 'tree');")) == {"tree"}
    assert set(_broken("const undo = [], redo = [];")) == {"undo"}
    assert _broken("/* el.addEventListener('dragstart', go) */\nconst editor = new I.CodeEditor(host, {});") == {}
    assert _broken("if (e.target.closest('.CodeMirror')) { return; }") == {}


# ---- hand-made inspector and form panes ---------------------------------------------------------------------------------------------
# A label and an input (or select) drawn in a loop over a schema's properties or a list of fields: an inspector or form pane made by hand.
# drishti-inputs' Form draws those from a schema (custom widgets, hooks, data-path), drishti-designer's Inspector wraps it for a selection,
# a drishti-grid with editing takes a matrix. Console scripts only (a page's server-drawn form is the server's).
FORM_LOOP = re.compile(r"(?:\bproperties|\bfields|\bprops|\bschema\w*|\bparams|\bsettings|\boptions)\b[\w.\[\]'\"()]{0,60}\.(?:forEach|map)\(|"
                       r"for\s*\(\s*(?:var|let|const)\s+\w+\s+(?:in|of)\s+[\w.]*(?:properties|fields|props|schema\w*|params|settings|options)\b")
FORM_LABEL = re.compile(r"""\(\s*['"]label['"]|<label\b""")
FORM_INPUT = re.compile(r"""\(\s*['"](?:input|select)['"]|<(?:input|select)\b""")
FORM_WINDOW = 25      # lines after the loop's head that the label and the input are looked for in
# file (relative to drishti-console/web) -> still draws a form pane by hand; empty since every pane is on Form or drishti-grid editing
FORMS_ALLOWED: set = set()


def _hand_form(text):
    """The first loop over a schema's properties or fields that draws a label and an input itself, or None."""
    lines = COMMENT.sub(lambda m: "\n" * m.group(0).count("\n"), text).splitlines()
    for i, line in enumerate(lines):
        m = FORM_LOOP.search(line)
        if m:
            body, depth = [], 0                  # the loop's body: from its head until its braces close (at most FORM_WINDOW lines)
            for j, part in enumerate(lines[i:i + FORM_WINDOW]):
                chunk = part[m.start():] if j == 0 else part
                body.append(chunk)
                depth += chunk.count("{") - chunk.count("}")
                if depth <= 0 and "{" in "".join(body):
                    break
            window = "\n".join(body)
            if FORM_LABEL.search(window) and FORM_INPUT.search(window):
                return line.strip()[:140]
    return None


@pytest.fixture(scope="module")
def hand_forms():
    return {str(p.relative_to(WEB)): _hand_form(p.read_text(encoding="utf-8", errors="replace")) for p in sorted((WEB / "static" / "js").rglob("*.js"))}


def test_no_console_script_draws_an_inspector_or_form_pane_by_hand(hand_forms):
    bad = [f"{name}: draws a label and an input in a loop over a schema's properties or fields: use drishti-inputs' Form (custom widgets, hooks, "
           f"data-path), drishti-designer's Inspector, or a drishti-grid with editing\n      {line}"
           for name, line in hand_forms.items() if line and name not in FORMS_ALLOWED]
    assert not bad, "\n".join(bad)


def test_the_form_allow_list_only_shrinks_and_is_capped(hand_forms):
    stale = [f"{name}: gone or on Form now (take it off the list)" for name in sorted(FORMS_ALLOWED) if not hand_forms.get(name)]
    assert not stale, "\n".join(stale)
    assert len(FORMS_ALLOWED) == 0                # 3 when the rule was made (2026-11-07); 0 now (a lake's fields on grid editing, a transform's parameters on Form)


def test_the_form_guard_finds_what_it_looks_for():
    assert _hand_form("Object.keys(schema.properties).forEach(function (k) {\n  var l = el('label', null, k);\n  var i = el('input');\n});")
    assert _hand_form("fields.forEach((f) => { box.append(h('label', {text: f.label}), h('select', {})); });")
    assert _hand_form("for (const k in s.properties) { html += '<label>' + k + '</label><input name=' + k + '>'; }")
    assert _hand_form("rows.forEach(function (r) { tr.appendChild(el('td', null, r.name)); });") is None                         # not a form
    assert _hand_form("var f = new I.Form(host, {schema: s});\nfields.forEach(function (f) { names.push(f.label); });") is None    # Form draws it
    assert _hand_form("/* fields.forEach(f => h('label') + h('input')) */") is None


def test_the_kit_is_where_the_designers_live():
    """The libraries the rule points to exist and every designer page loads the kit."""
    kit = WEB / "static" / "elements" / "drishti-designer" / "src"
    for name in ("drishti_layout-canvas.js", "drishti_palette.js", "drishti_inspector.js", "drishti_outline.js", "drishti_yaml-pane.js", "drishti_shell.js"):
        assert (kit / name).is_file(), name
    for page in ("build/design.html", "build/tables.html", "bi/design.html"):
        assert '{% include "_designer_kit.html" %}' in (WEB / "templates" / page).read_text(encoding="utf-8"), page
