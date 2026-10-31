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

"""Publish in the model designer stays Published when a read of the item answers late (2.9.7 fix).

After Save the designer reads the item again (refreshItem). A Publish clicked at once was answered first, set the state to Published, and then the read
asked before it, answered after it, put the draft back: the page said "Saved draft" although the server had published (test_bi_wave_b_browser.py and
test_bi_languages_browser.py saw it whenever the read took longer than the publish). The item's answers now go through drishtiBi.itemAnswers: a read
answered after a later write's answer is dropped. Here the race is played in Node with the real bi-lib.js, and the designer is checked to route every
write and the refresh through it, so a new write path cannot bring the race back."""
import json
import re
import shutil
import subprocess
from pathlib import Path

import pytest

JS = Path(__file__).resolve().parent.parent / "web" / "static" / "js"
node = shutil.which("node")
needs_node = pytest.mark.skipif(node is None, reason="needs Node to run bi-lib.js")


def play(script: str):
    prelude = ("global.window = {location: {search: ''}, setTimeout, clearTimeout, addEventListener() {}}; global.document = {addEventListener() {}, querySelector() { return null; }, querySelectorAll() { return []; }}; require(%s); const B = window.drishtiBi;\n"
               "const later = (v, ms) => new Promise((r) => setTimeout(() => r(v), ms));\n" % json.dumps(str(JS / "bi-lib.js")))
    out = subprocess.run([node, "-e", prelude + script], capture_output=True, text=True, timeout=30)
    assert out.returncode == 0, out.stderr
    return json.loads(out.stdout)


@needs_node
def test_a_read_asked_before_publish_and_answered_after_it_does_not_put_the_draft_back():
    shown = play("""
      const a = B.itemAnswers(); let item = {status: 'draft'};
      const refresh = a.read(later({status: 'draft'}, 60)).then((fresh) => { if (fresh) { item = fresh; } });   // asked after Save, answered late
      const publish = later({status: 'published'}, 10).then((it) => { a.wrote(); item = it; });                    // clicked at once, answered first
      Promise.all([refresh, publish]).then(() => console.log(JSON.stringify(item.status)));
    """)
    assert shown == "published"


@needs_node
def test_a_read_answered_with_no_write_since_is_applied():
    shown = play("""
      const a = B.itemAnswers(); let item = {status: 'draft'};
      later(null, 5).then(() => a.wrote());                                                                       // a write answered before the read was asked
      later(null, 20).then(() => a.read(later({status: 'published'}, 10))).then((fresh) => { if (fresh) { item = fresh; } console.log(JSON.stringify(item.status)); });
    """)
    assert shown == "published"


def test_the_model_designer_routes_every_item_write_and_the_refresh_through_the_answers():
    src = (JS / "bi-model-designer.js").read_text(encoding="utf-8")
    assert "const answers = B.itemAnswers();" in src
    writes = [line for line in src.splitlines() if re.search(r"\bM\.item = (?!fresh\b)", line)]
    assert len(writes) >= 4, writes                                       # save, autosave, publish, move (the page's first read sets it once at open)
    for line in writes:
        assert "answers.wrote()" in line or "M.item = it;" == line.strip(), line
    refresh = src[src.index("function refreshItem()"):src.index("M.refreshItem = refreshItem;")]
    assert "answers.read(B.api('GET'" in refresh and "if (!fresh || M.dirty)" in refresh, refresh
