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

"""The keys that move and size a panel (build/grid-keys.js), shared by layout mode and the workbench canvas, run in Node: layout mode's
arrows move the panel; the workbench's arrows select and Alt+arrows move; Shift+arrows size in both."""
import json
from pathlib import Path
import shutil
import subprocess

import pytest

JS = Path(__file__).resolve().parents[1] / "web" / "static" / "js" / "build" / "grid-keys.js"
NODE = shutil.which("node")
pytestmark = pytest.mark.skipif(NODE is None, reason="needs node")


def keys(scheme, *events):
    code = f"const G=require({json.dumps(str(JS))});const ev={json.dumps(events)};console.log(JSON.stringify(ev.map(e=>G.interpret(e,{json.dumps(scheme)}))))"
    return json.loads(subprocess.run([NODE, "-e", code], capture_output=True, text=True, check=True, timeout=30).stdout)


def k(key, **mods):
    return {"key": key, **mods}


def test_layout_mode_arrows_move_and_shift_arrows_size():
    out = keys("layout", k("ArrowUp"), k("ArrowDown"), k("ArrowLeft"), k("ArrowRight"), k("ArrowLeft", shiftKey=True), k("ArrowRight", shiftKey=True),
               k("ArrowUp", shiftKey=True), k("ArrowDown", shiftKey=True), k("h"), k("Delete"), k("a"), k("-"), k("+"))
    assert out == [{"type": "move", "step": -1}, {"type": "move", "step": 1}, {"type": "column", "area": "main"}, {"type": "column", "area": "right"},
                   {"type": "span", "delta": -1}, {"type": "span", "delta": 1}, {"type": "height", "delta": -1}, {"type": "height", "delta": 1},
                   {"type": "hide"}, {"type": "hide"}, {"type": "natural"}, {"type": "span", "delta": -1}, {"type": "span", "delta": 1}]


def test_the_workbench_arrows_select_and_alt_arrows_move():
    out = keys("workbench", k("ArrowUp"), k("ArrowDown"), k("ArrowLeft"), k("ArrowRight"), k("Home"), k("End"),
               k("ArrowUp", altKey=True), k("ArrowDown", altKey=True), k("ArrowLeft", altKey=True), k("ArrowRight", altKey=True))
    assert out == [{"type": "select", "dir": "prev"}, {"type": "select", "dir": "next"}, {"type": "select", "dir": "main"}, {"type": "select", "dir": "side"},
                   {"type": "select", "dir": "first"}, {"type": "select", "dir": "last"},
                   {"type": "move", "step": -1}, {"type": "move", "step": 1}, {"type": "column", "area": "main"}, {"type": "column", "area": "right"}]


def test_the_workbench_deletes_where_layout_mode_hides():
    assert keys("workbench", k("Delete"), k("h")) == [{"type": "remove"}, None]
    assert keys("layout", k("Delete"), k("h")) == [{"type": "hide"}, {"type": "hide"}]


def test_shift_arrows_size_in_the_workbench_too_and_control_keys_are_left_alone():
    out = keys("workbench", k("ArrowRight", shiftKey=True), k("ArrowDown", shiftKey=True), k("z", ctrlKey=True), k("ArrowLeft", ctrlKey=True), k("x"))
    assert out == [{"type": "span", "delta": 1}, {"type": "height", "delta": 1}, None, None, None]
