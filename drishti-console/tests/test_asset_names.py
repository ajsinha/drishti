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

"""Nothing of ours collides with another library or a host page (developer guide, *Static asset names and the one namespace*): every
static file we wrote is ``drishti_<name>``, our scripts set one global (``window.Drishti``) besides the three the published renderer and
Elements versions read, custom events are ``drishti:``, storage keys ``drishti``, custom elements ``drishti-``, and no new generic CSS class
or third-party data- attribute appears. ``tools/dev/asset_prefix.py apply`` makes a tree pass; this asks its scanner what is left."""
import re
import sys
from pathlib import Path

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "dev"))

import asset_prefix as ap  # noqa: E402
import asset_prefix_scan as sc  # noqa: E402

from core.staticzip import CompressedStatic  # noqa: E402

FIX = "run python3 tools/dev/asset_prefix.py apply (and review tools/dev/asset_prefix_map.yaml)"
CONTRACT = {"drishtiModules", "drishtiBi", "drishtiGeo"}       # read by static/render/<version> and web/embed: published


@pytest.fixture(scope="module")
def tree():
    files = sc.tracked(ROOT)
    return files, ap.load_map()


@pytest.mark.static
def test_every_static_file_we_wrote_is_named_drishti(tree):
    files, _ = tree
    left = sc.file_map(files)
    assert not left, f"static files without the drishti_ prefix: {sorted(left)[:20]}: {FIX}"
    names = [Path(p).name for p in sc.static_files(files)]
    assert names and all(n.startswith("drishti_") or n.split(".")[0] == "drishti" for n in names)


@pytest.mark.static
def test_our_scripts_put_one_name_on_window(tree):
    files, m = tree
    keep = {n for n, v in m["globals"].items() if v.get("status") == "keep"} | CONTRACT
    found = {}
    for f in files:
        if f.startswith(sc.OWN_JS) and f.endswith(".js"):
            for n in sc.global_assignments(sc.read(ROOT, f)):
                if n not in sc.BUILTINS and n != sc.NAMESPACE and n not in keep:
                    found.setdefault(n, f)
    assert not found, f"globals set outside window.Drishti: {found}: {FIX}"
    assert keep <= CONTRACT | {n for n, v in m["globals"].items() if v.get("status") == "keep"}


@pytest.mark.static
def test_scripts_that_use_the_namespace_create_it_first(tree):
    files, _ = tree
    bad = [f for f in files if f.startswith(sc.OWN_JS) and f.endswith(".js") and "window.Drishti." in sc.read(ROOT, f)
           and ap.BOOT not in sc.read(ROOT, f)]
    assert not bad, f"these use window.Drishti without the bootstrap line: {bad}: {FIX}"


@pytest.mark.static
def test_custom_events_storage_keys_and_elements_are_prefixed(tree):
    files, m = tree
    events = sc.events_map(ROOT, files)
    assert not [n for n, v in events.items() if v["status"] == "rename"], f"custom events without drishti: {sorted(events)}: {FIX}"
    keys = sc.storage_keys(ROOT, files)
    assert keys and all(v == "ok" for v in keys.values()), f"storage keys must start drishti: {[k for k, v in keys.items() if v != 'ok']}"
    els = sc.custom_elements(ROOT, files)
    assert els and all(v == "ok" for v in els.values()), f"custom elements must be drishti-<name>: {els}"


@pytest.mark.static
def test_no_new_generic_css_class_or_third_party_data_attribute(tree):
    files, m = tree
    known = set(m["classes"])
    generic = {c for c in sc.css_classes(ROOT, files) if c in sc.GENERIC_CLASSES}
    assert generic <= known, f"new generic CSS classes (name them drishti-<name>): {sorted(generic - known)}"
    datas = {d for d in sc.data_attrs(ROOT, files) if d in sc.COLLISION_DATA}
    assert datas <= set(m["data_attrs"]), f"new data- attributes other libraries use (name them data-drishti-<name>): {sorted(datas - set(m['data_attrs']))}"


@pytest.mark.static
def test_the_mapping_says_why_a_name_is_kept(tree):
    _, m = tree
    for section in ("globals", "events", "classes", "data_attrs"):
        for name, v in m[section].items():
            assert v["status"] in ("move", "rename", "keep", "manual"), (section, name)
            if v["status"] in ("keep", "manual"):
                assert v.get("why"), f"{section}.{name}: a kept name says why"


def test_an_old_name_is_served_the_prefixed_file_for_the_published_renderer(tmp_path):
    root = tmp_path / "static"
    (root / "js").mkdir(parents=True)
    (root / "js" / "drishti_tables.js").write_text("/* tables */\n")
    (root / "js" / "drishti_table.js").write_text("/* table */\n")
    (root / "img").mkdir()
    (root / "img" / "drishti_logo.png").write_bytes(b"png")
    app = FastAPI()
    app.mount("/static", CompressedStatic(directory=str(root), cache_dir=str(tmp_path / "cache")), name="static")
    c = TestClient(app)
    assert c.get("/static/js/tables.js").text == "/* tables */\n"                 # what renderer 1.0.0-1.2.0 request
    assert c.get("/static/js/drishti_tables.js").text == "/* tables */\n"
    assert c.get("/static/js/drishti-table.js").text == "/* table */\n"           # drishti-<rest> became drishti_<rest>
    assert c.get("/static/img/logo.png").status_code == 404                      # js/ and css/ only: what the renderer loads
    assert c.get("/static/js/nothing.js").status_code == 404


# -- the rewriter itself: what it changes, and that a second run changes nothing ----------------------------------------------------
def test_the_rewriter_renames_paths_and_bare_names_and_is_idempotent():
    files = {"js/bi-runs-tab.js": "js/drishti_bi-runs-tab.js", "js/about.js": "js/drishti_about.js",
             "img/guide/studio.png": "img/guide/drishti_studio.png", "js/build/about.js": "js/build/drishti_about.js"}
    tracked = [sc.STATIC + "/" + p for p in files] + ["drishti-console/web/embed/about.js"]
    text = ('<script src="/static/js/bi-runs-tab.js?v=1"></script> ![s](../../drishti-console/web/static/img/guide/studio.png)\n'
            'p = WEB / "static" / "js" / "about.js"; q = WEB / "embed" / "about.js"; r = re.search(r"/static/js/build/about\\.js")\n'
            'see `bi-runs-tab.js`, and render/1.2.0/m/about.js\n')
    refs = ap.FileRefs(files, tracked)
    once = refs.rewrite("docs/x.md", text, ap.Counter())
    assert "/static/js/drishti_bi-runs-tab.js?v=1" in once and "img/guide/drishti_studio.png" in once and "`drishti_bi-runs-tab.js`" in once
    assert '"js" / "drishti_about.js"' in once and 'WEB / "embed" / "about.js"' in once and "m/about.js" in once
    assert r"build/drishti_about\.js" in once
    assert ap.FileRefs(files, tracked).rewrite("docs/x.md", once, ap.Counter()) == once
    js = ("var me = /\\/embed\\/v1\\/js\\/about\\.js/.test(s) ? s.replace(/js\\/about\\.js.*$/, '') : '/static/';\n"
          "link('/favicon.ico'); load('js/bi-runs-tab.js');\n")
    own = ap.FileRefs(files, tracked).rewrite(sc.STATIC + "/js/x.js", js, ap.Counter())
    assert "\\/embed\\/v1\\/js\\/about\\.js" in own                         # the embed route's public name stays
    assert "/js\\/(?:drishti_)?about\\.js.*$/" in own                      # itself by either name: /static/js/drishti_x.js or /embed/v2/js/x.js
    assert "'/favicon.ico'" in own and "'js/drishti_bi-runs-tab.js'" in own  # a root address is a route, not a static file
    assert ap.FileRefs(files, tracked).rewrite(sc.STATIC + "/js/x.js", own, ap.Counter()) == own


def test_globals_move_into_the_namespace_and_the_bootstrap_is_added_once():
    js = ("/* licence */\n(function () {\n  function drishtiFetchTable() {}\n  window.drishtiFetchTable = drishtiFetchTable;\n"
          "  if (window.DrishtiBusy) { DrishtiBusy.render(); }\n})();\n")
    moves = {"drishtiFetchTable": "fetchTable", "DrishtiBusy": "Busy"}
    out = ap.rewrite_globals(sc.STATIC + "/js/x.js", js, moves, set(), ap.Counter(), [])
    assert "window.Drishti.fetchTable = drishtiFetchTable;" in out            # a local of the same name stays the local
    assert "if (window.Drishti.Busy) { Drishti.Busy.render(); }" in out
    booted = ap.add_boot(out)
    assert booted.startswith("/* licence */\n" + ap.BOOT) and ap.add_boot(booted) == booted
    assert ap.rewrite_globals(sc.STATIC + "/js/x.js", out, moves, set(), ap.Counter(), []) == out
    test = ap.rewrite_globals("drishti-console/tests/t.py", 'pg.evaluate("window.DrishtiBusy.x"); pg.evaluate("window.DrishtiBusy = 1")',
                              moves, set(), ap.Counter(), [])
    assert "window.Drishti.Busy.x" in test and "(window.Drishti ??= Object()).Busy = 1" in test         # no braces: f-strings
    src = ap.rewrite_globals("drishti-console/tests/t.py", 'assert "window.DrishtiBusy = {" in js', moves, set(), ap.Counter(), [])
    assert src == 'assert "window.Drishti.Busy = {" in js'                                            # the words of the source
    umd = "(function (root) {\n  root.drishtiCapacity = api;\n  x = root.drishtiCapacity;\n})(typeof window !== 'undefined' ? window : this);\n"
    out = ap.rewrite_globals(sc.STATIC + "/js/u.js", umd, {"drishtiCapacity": "capacity"}, set(), ap.Counter(), [])
    assert "(root.Drishti = root.Drishti || {}).capacity = api;" in out and "x = (root.Drishti || {}).capacity;" in out
    dom = "(function () {\n  function show(root) { root.__drPath = 1; }\n})();\n"                       # root is a DOM root here: never a global
    assert sc.global_assignments(dom) == [] and sc.global_assignments(umd) == ["drishtiCapacity"]
    py = ap.rewrite_globals("tools/x.py", "self.drishti = 1; page.evaluate('window.drishti.enhance()')", {"drishti": "view"}, set(), ap.Counter(), [])
    assert py == "self.drishti = 1; page.evaluate('window.Drishti.view.enhance()')"


def test_events_classes_and_data_attributes_rewrite_only_where_recognised():
    assert ap.rewrite_events("el.dispatchEvent(new CustomEvent('bi:reload')); on('bi:reload')", {"bi:reload": "drishti:bi-reload"},
                             ap.Counter()).count("'drishti:bi-reload'") == 2
    names = {"on": "drishti-on"}
    code = ap.rewrite_classes_code('x.classList.toggle(\'on\', a); h = `<b class="tab ${a ? \'on\' : \'\'}">`; q(".tab.on"); s = \'turn on\'',
                                   names, ap.Counter())
    assert "toggle('drishti-on'" in code and "'drishti-on' : ''" in code and "s = 'turn on'" in code
    assert ap.rewrite_classes_css(".tab.on { width: 1.5em } /* .on */", names, ap.Counter()) == ".tab.drishti-on { width: 1.5em } /* .on */"
    assert ap.rewrite_data('<i data-theme="x"> el.dataset.theme', {"theme": "drishti-theme"}, ap.Counter()) == \
        '<i data-drishti-theme="x"> el.dataset.drishtiTheme'


def test_docs_fragments_are_placed_once():
    text = "# Log\n\n## 2.9.11.b — x\n\n- old\n"
    where = {"at": "end-of:## 2.9.11.c", "else-before": "## 2.9.11.b", "heading": "## 2.9.11.c — y"}
    once, note = ap.place(text, where, "- new\n")
    assert note is None and once.index("## 2.9.11.c") < once.index("- new") < once.index("## 2.9.11.b")
    twice, _ = ap.place(once, where, "- newer\n")
    assert re.search(r"- new\n- newer\n\n## 2.9.11.b", twice)
    assert ap.heading_renames("## 9. Charts (`charts.js`)\ntext\n", "## 9. Charts (`drishti_charts.js`)\ntext\n") == \
        {"9-charts-chartsjs": "9-charts-drishti_chartsjs"}                                         # links to the heading follow it
