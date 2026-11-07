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
  YamlPane;
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
    "code-editor": (re.compile(r"""\bfromTextArea\b|\bCodeMirror\s*\(\s*[\w.]+\s*,|CodeMirror\.defineMode\b|<textarea[^>]*(?:data-[\w-]*(?:yaml|sutra|code|python|formula)\b|name="(?:body|yamlbody)")"""),
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
    "static/js/build/drishti_controls-tab.js": {"drag"},          # the workbench's controls tab: its cards re-ordered by drag
    "static/js/build/drishti_form-tab.js": {"drag"},              # the workbench's search-form tab: the same
    "static/js/drishti_bi-design-panes.js": {"drag"},             # the BI designer's Fields and Visualizations panes (drags onto the canvas)
    "static/js/drishti_bi-design-phone.js": {"drag"},             # the BI designer's Phone and Tablet order (LayoutModel breakpoints)
    "static/js/drishti_controls.js": {"drag"},                    # a Sutra's controls (a range slider's thumbs)
    "static/js/drishti_designer-grid.js": {"drag"},               # the Sutra designer's Grid mode
    "static/js/drishti_designer-outline.js": {"drag"},            # the Sutra designer's Outline mode
    "static/js/drishti_designer.js": {"drag"},                    # the Sutra designer's Customize mode
    "static/js/drishti_layout.js": {"drag"},                      # a reader's layout mode on a screen
    "static/js/drishti_screen-grid.js": {"drag"},                 # a reader's arranging of a laid-out screen
    "static/js/drishti_workspace.js": {"drag"},                   # a workspace's panes and dividers
    "templates/admin/_connector_dialogs.html": {"code-editor"},   # Admin › Connectors: the connector's YAML
    "templates/admin/net.html": {"code-editor"},                  # Admin › Drishti*net: the cluster's YAML
    "templates/bi/transform.html": {"code-editor"},               # BI › Transform: the Python step
    "templates/bi/design_theme.html": {"code-editor"},            # BI › the theme designer's YAML tab
    "templates/bi/item.html": {"code-editor"},                    # BI › an item's body (YAML)
    "templates/bi/workspace.html": {"code-editor"},               # BI › a workspace's new dataset (YAML, for experts)
    "templates/build/design.html": {"tree"},                      # the workbench's data-shape tree (drishti_build-shape-tree.js)
    "templates/designer/grid.html": {"code-editor"},              # the Sutra designer's formula bar (Grid mode)
    "templates/terminal/_calc.html": {"code-editor"},             # the calculator's Python
    "templates/terminal/pivot.html": {"code-editor"},             # the Pivot's computed fields (code)
    "templates/designer/outline.html": {"tree"},                  # the Sutra designer's Outline mode
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
    assert len(ALLOWED) <= 22 and sum(len(r) for r in ALLOWED.values()) <= 22


def test_the_guard_finds_what_it_looks_for():
    assert set(_broken("el.addEventListener('dragstart', go);")) == {"drag"}
    assert set(_broken("h.setPointerCapture(e.pointerId);")) == {"drag"}
    assert set(_broken("var ed = CodeMirror.fromTextArea(ta, {});")) == {"code-editor"}
    assert set(_broken('<textarea data-yaml rows="9"></textarea>')) == {"code-editor"}
    assert set(_broken("ul.setAttribute('role', 'tree');")) == {"tree"}
    assert set(_broken("const undo = [], redo = [];")) == {"undo"}
    assert _broken("/* el.addEventListener('dragstart', go) */\nconst editor = new I.CodeEditor(host, {});") == {}
    assert _broken("if (e.target.closest('.CodeMirror')) { return; }") == {}


def test_the_kit_is_where_the_designers_live():
    """The libraries the rule points to exist and every designer page loads the kit."""
    kit = WEB / "static" / "elements" / "drishti-designer" / "src"
    for name in ("drishti_layout-canvas.js", "drishti_palette.js", "drishti_inspector.js", "drishti_outline.js", "drishti_yaml-pane.js", "drishti_shell.js"):
        assert (kit / name).is_file(), name
    for page in ("build/design.html", "build/tables.html", "bi/design.html"):
        assert '{% include "_designer_kit.html" %}' in (WEB / "templates" / page).read_text(encoding="utf-8"), page
