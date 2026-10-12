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

"""static/js/bi-phone.js (Rupaka phase 7, per-report phone layouts): the one file the viewer, <drishti-report> and the designer's Phone tab share to decide what a phone
shows and in which order and height. Run in Node (skipped when Node is not installed), with the browser's `window` faked: the file touches nothing else."""
import json
import shutil
import subprocess
from pathlib import Path

import pytest

JS = Path(__file__).resolve().parent.parent / "web" / "static" / "js" / "bi-phone.js"
node = shutil.which("node")
pytestmark = pytest.mark.skipif(node is None, reason="needs Node to run the shared phone-layout code")

PAGE = {"id": "desk", "visuals": [
    {"id": "a", "at": {"x": 6, "y": 0, "w": 3, "h": 2}}, {"id": "b", "at": {"x": 0, "y": 0, "w": 3, "h": 2}}, {"id": "c", "at": {"x": 0, "y": 2, "w": 6, "h": 5}},
    {"id": "d", "at": {"x": 6, "y": 2, "w": 6, "h": 5}}, {"id": "e", "at": {"x": 0, "y": 7, "w": 12, "h": 4}}]}


def run(script: str):
    prelude = "global.window = { drishtiBi: { ROW_PX: 56, GAP_PX: 10 } }; require(%s); const P = window.drishtiBi.phone;" % json.dumps(str(JS))
    out = subprocess.run([node, "-e", prelude + script], capture_output=True, text=True, timeout=30)
    assert out.returncode == 0, out.stderr
    return json.loads(out.stdout)


def test_without_a_layout_a_page_is_the_grids_reading_order_with_nothing_hidden():
    got = run("console.log(JSON.stringify(P.arrange(%s, null).map(x => [x.spec.id, x.rows])))" % json.dumps(PAGE))
    assert got == [["b", None], ["a", None], ["c", None], ["d", None], ["e", None]]        # top to bottom, then left to right


def test_order_comes_first_the_rest_follow_in_reading_order_hidden_ones_are_left_out_and_span_sets_rows():
    lay = {"order": ["d", "b"], "hide": ["c"], "span": {"d": 5, "e": 3}}
    got = run("console.log(JSON.stringify(P.arrange(%s, %s).map(x => [x.spec.id, x.rows])))" % (json.dumps(PAGE), json.dumps(lay)))
    assert got == [["d", 5], ["b", None], ["a", None], ["e", 3]]


def test_an_unknown_or_repeated_id_in_order_is_ignored():
    got = run("console.log(JSON.stringify(P.arrange(%s, { order: ['zz', 'e', 'e'] }).map(x => x.spec.id)))" % json.dumps(PAGE))
    assert got == ["e", "b", "a", "c", "d"]


def test_a_layout_applies_only_to_a_phone_width_and_only_when_it_says_something():
    report = {"layouts": {"phone": {"desk": {"order": ["a"]}, "empty": {}, "blank": {"order": [], "hide": [], "span": {}}}}}
    got = run("const r = %s; console.log(JSON.stringify([P.layoutOf(r, 'desk'), P.layoutOf(r, 'empty'), P.layoutOf(r, 'blank'), P.layoutOf(r, 'nope'), P.layoutOf({}, 'desk'), P.layoutOf(null, 'x'), "
              "P.narrow(390), P.narrow(640), P.narrow(641), P.narrow(0)]))" % json.dumps(report))
    assert got == [{"order": ["a"]}, None, None, None, None, None, True, True, False, False]


def test_a_row_height_is_whole_rows_and_their_gaps_and_tidy_drops_what_says_nothing():
    got = run("console.log(JSON.stringify([P.height(1), P.height(5), P.tidy({ order: [], hide: [], span: { a: 0 } }), P.tidy({ order: ['a'], hide: [], span: { a: 3, b: 0 } }), P.tidy(null)]))")
    assert got == [56, 5 * 56 + 4 * 10, None, {"order": ["a"], "span": {"a": 3}}, None]


def test_a_width_is_a_phone_a_tablet_or_a_desktop_and_each_has_its_own_layout():
    report = {"layouts": {"phone": {"desk": {"order": ["a"]}}, "tablet": {"desk": {"wide": ["e"]}, "only": {"span": {"a": 3}}}}}
    got = run("const r = %s; console.log(JSON.stringify([P.device(390), P.device(640), P.device(641), P.device(1024), P.device(1025), P.device(0), "
              "P.layoutOf(r, 'desk', 'phone'), P.layoutOf(r, 'desk', 'tablet'), P.layoutOf(r, 'desk'), P.layoutOf(r, 'only', 'phone'), P.layoutOf(r, 'only', 'tablet')]))" % json.dumps(report))
    assert got == ["phone", "phone", "tablet", "tablet", None, None, {"order": ["a"]}, {"wide": ["e"]}, {"order": ["a"]}, None, {"span": {"a": 3}}]


def test_a_tablet_visual_takes_half_the_width_unless_it_is_wide_and_its_height_is_its_rows_or_its_own():
    lay = {"wide": ["e"], "span": {"c": 3}}
    got = run("const lay = %s; const v = (id, h) => ({ id, at: { h } }); console.log(JSON.stringify([P.tabletColumn(v('c', 5), lay), P.tabletColumn(v('e', 4), lay), P.tabletColumn(v('c', 5), null), "
              "P.tabletRow(v('c', 5), 3), P.tabletRow(v('d', 5), null), P.tidy({ order: [], hide: [], span: {}, wide: ['e'] }), P.tidy({ wide: [] })]))" % json.dumps(lay))
    assert got == ["span 6", "1 / -1", "span 6", "span 3", "span 5", {"wide": ["e"]}, None]
