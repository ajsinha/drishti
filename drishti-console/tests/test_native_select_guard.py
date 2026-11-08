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
"""No native <select> in the console (docs/architecture/ELEMENTS.md sections 3.1 and 8.2): a single choice is drishti-inputs' Select
(<drishti-select>, a Form's select field, the grid's select editor), with search, type-ahead and the keys of the rest of the kit. This
guard reads every console page (templates), script (static/js) and library source (static/elements/*/src, the libraries' harness pages,
the gallery), and the plugins' pages and scripts, for a raw select: the tag in markup or in a string, document.createElement('select'), or an element helper asked for one
(h(doc, 'select', ...), el('select', ...)).

ALLOWED lists the files that still draw one, each with how many and why; it only shrinks: a file that draws more fails, and so does a
file that draws fewer than its entry says (lower the number, or remove the entry at 0), so the list stays true. Comments are left out.
Not read: the vendored libraries, the generated bundles (built from the sources read here) and the published renderer versions under
static/render/<version>/ (frozen copies a host pins; the current renderer is drishti-renderer's source)."""
import re

import pytest

from conftest import CONSOLE

pytestmark = pytest.mark.static   # a quick file check: the drill's gate runs it

WEB = CONSOLE / "web"

SELECT = re.compile(r"""<select\b|createElement\(\s*['"]select['"]\s*\)|\b(?:h|el|x|make|node|tag)\(\s*(?:[\w.]+\s*,\s*)?['"]select['"]""")
COMMENT = re.compile(r"/\*.*?\*/|(?<![:\\'\"])//[^\n]*|\{#.*?#\}|<!--.*?-->", re.S)

LIB = "a library that does not require drishti-inputs (where Select lives): a Select there needs drishti-inputs as its dependency first"
SCREEN = "a screen not yet moved onto Select (the native-select sweep, 2.9.11.e)"

# file (relative to drishti-console) -> (how many, why)
ALLOWED: dict = {
    "web/static/elements/drishti-chart/src/drishti_chart-studio.js": (1, LIB),
    "web/static/elements/drishti-chart/src/drishti_map-view.js": (1, LIB),
    "web/static/elements/drishti-graph/src/drishti_graph-chrome.js": (1, LIB),
    "web/static/elements/drishti-grid/src/drishti_doc-console.js": (1, LIB),
    "web/static/elements/drishti-grid/src/drishti_grid-shell.js": (1, LIB),
    "web/static/elements/drishti-tree/src/drishti_tree-side.js": (1, LIB),
    "web/static/elements/drishti-tree/src/drishti_tree-view.js": (1, LIB),
    "web/static/elements/drishti-renderer/drishti_harness.html": (3, "the renderer's test harness page (cases, behaviours, theme), not a product screen; loads no drishti-inputs"),
    "web/static/gallery/drishti_gallery.js": (1, "the gallery's option panel for a demo's choices: next, with the gallery's own controls"),
    "plugins/banking/templates/home/trading_landing.html": (4, SCREEN),
    "plugins/banking/templates/terminal/_mr_panes.html": (1, SCREEN),
    "plugins/banking/templates/terminal/smat.html": (2, SCREEN),
    "web/static/js/build/drishti_controls-tab.js": (1, SCREEN),
    "web/static/js/build/drishti_form-tab.js": (2, SCREEN),
    "web/static/js/build/drishti_versions.js": (1, SCREEN),
    "web/static/js/drishti_bi-consume.js": (4, SCREEN),
    "web/static/js/drishti_bi-design.js": (1, SCREEN),
    "web/static/js/drishti_bi-design-model.js": (1, SCREEN),
    "web/static/js/drishti_bi-design-panes.js": (3, SCREEN),
    "web/static/js/drishti_bi-grids.js": (1, SCREEN),
    "web/static/js/drishti_bi.js": (1, SCREEN),
    "web/static/js/drishti_bi-lib.js": (1, SCREEN),
    "web/static/js/drishti_bi-runs-tab.js": (6, SCREEN),
    "web/static/js/drishti_bi-statement.js": (1, SCREEN),
    "web/static/js/drishti_colfilter.js": (1, SCREEN),
    "web/static/js/drishti_controls.js": (1, SCREEN),
    "web/static/js/drishti_data-view.js": (2, SCREEN),
    "web/static/js/drishti_designer-describe.js": (1, SCREEN),
    "web/static/js/drishti_designer-forms.js": (1, SCREEN),
    "web/static/js/drishti_designer.js": (1, SCREEN),
    "web/static/js/drishti_designer-outline.js": (2, SCREEN),
    "web/static/js/drishti_designer-settings.js": (1, SCREEN),
    "web/static/js/drishti_editor-new.js": (3, SCREEN),
    "web/static/js/drishti_editor-python.js": (3, SCREEN),
    "web/static/js/drishti_editors.js": (2, SCREEN),
    "web/static/js/drishti_history.js": (1, SCREEN),
    "web/static/js/drishti_pack-ui.js": (1, SCREEN),
    "web/static/js/drishti_record-forms.js": (1, SCREEN),
    "web/static/js/drishti_refresh.js": (1, SCREEN),
    "web/static/js/drishti_table-chart.js": (9, SCREEN),
    "web/templates/account.html": (3, SCREEN),
    "web/templates/alerts.html": (2, SCREEN),
    "web/templates/approvals.html": (1, SCREEN),
    "web/templates/bi/catalogue.html": (6, SCREEN),
    "web/templates/bi/designer.html": (4, SCREEN),
    "web/templates/bi/design.html": (2, SCREEN),
    "web/templates/bi/environments.html": (2, SCREEN),
    "web/templates/bi/item.html": (2, SCREEN),
    "web/templates/bi/layer_dataset.html": (2, SCREEN),
    "web/templates/bi/notebook.html": (5, SCREEN),
    "web/templates/bi/report.html": (4, SCREEN),
    "web/templates/bi/runs_admin.html": (1, SCREEN),
    "web/templates/bi/settings.html": (1, SCREEN),
    "web/templates/bi/transform.html": (3, SCREEN),
    "web/templates/bi/workspace.html": (9, SCREEN),
    "web/templates/build/design.html": (2, SCREEN),
    "web/templates/build/new.html": (1, SCREEN),
    "web/templates/designer/grid.html": (1, SCREEN),
    "web/templates/designer/_modes.html": (1, SCREEN),
    "web/templates/help/configuration.html": (1, SCREEN),
    "web/templates/reports.html": (3, SCREEN),
    "web/templates/subscribe.html": (2, SCREEN),
    "web/templates/terminal/_calc.html": (1, SCREEN),
    "web/templates/terminal/data.html": (1, SCREEN),
    "web/templates/terminal/_discussion.html": (2, SCREEN),
    "web/templates/terminal/form.html": (2, SCREEN),
    "web/templates/terminal/functions.html": (2, SCREEN),
    "web/templates/terminal/pivot.html": (9, SCREEN),
    "web/templates/workspace/workspace.html": (2, SCREEN),
}


def _sources():
    files = (sorted((WEB / "static" / "js").rglob("*.js")) + sorted((WEB / "templates").rglob("*.html"))
             + sorted((WEB / "static" / "elements").glob("*/src/*.js")) + sorted((WEB / "static" / "elements").glob("*/*.html"))
             + sorted((WEB / "static" / "gallery").glob("*.js"))
             + sorted(CONSOLE.glob("plugins/*/templates/**/*.html")) + sorted(CONSOLE.glob("plugins/*/static/**/*.js")))
    return {str(p.relative_to(CONSOLE)): p for p in files}


def _count(text):
    code = COMMENT.sub(lambda m: "\n" * m.group(0).count("\n"), text)
    return sum(len(SELECT.findall(line)) for line in code.splitlines())


@pytest.fixture(scope="module")
def counts():
    return {name: n for name, p in _sources().items() if (n := _count(p.read_text(encoding="utf-8")))}


def test_no_native_select_outside_the_allow_list(counts):
    extra = {name: n for name, n in counts.items() if n > ALLOWED.get(name, (0, ""))[0]}
    assert not extra, ("a native <select> (use drishti-inputs' Select: <drishti-select>, a Form's select field): "
                       + "; ".join(f"{k}: {v}" for k, v in sorted(extra.items())))


def test_the_allow_list_only_shrinks(counts):
    stale = {name: (counts.get(name, 0), n) for name, (n, _) in ALLOWED.items() if counts.get(name, 0) < n}
    assert not stale, "lower or remove these entries (now, allowed): " + "; ".join(f"{k}: {v}" for k, v in sorted(stale.items()))
    assert all(why for _, why in ALLOWED.values()), "every entry says why it cannot move yet"


def test_the_guard_sees_each_way_of_making_one():
    for code in ('<select name="a">', "document.createElement('select')", "h(doc, 'select', { cls: 'x' })", "this.el('select', {})",
                 "h('select', { name: 'day' })", "el('select', 'tbl-pg-size')"):
        assert _count(code) == 1, code
    for code in ("<drishti-select name='a'>", "// h(doc, 'select')", "/* <select> */", "{# <select> #}", "'Select all'", "data-select"):
        assert _count(code) == 0, code
