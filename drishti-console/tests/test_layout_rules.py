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

"""Layout mode's rules (QA 2026-10-01 UX-14): a minimum width, the last visible panel stays, and the announced height is
the kept one. The rules are plain JavaScript (layout-rules.js), run here in Node."""
import json
import shutil
import subprocess
from pathlib import Path

import pytest

JS = Path(__file__).resolve().parents[1] / "web" / "static" / "js"
NODE = shutil.which("node")
pytestmark = pytest.mark.skipif(NODE is None, reason="needs node")


def run(expr: str):
    code = f"const R=require({json.dumps(str(JS / 'layout-rules.js'))});console.log(JSON.stringify({expr}))"
    return json.loads(subprocess.run([NODE, "-e", code], capture_output=True, text=True, check=True, timeout=30).stdout)


def test_a_panel_cannot_be_made_narrower_than_the_minimum():
    assert run("[R.clampSpan(1,3),R.clampSpan(0,3),R.clampSpan(5,3),R.clampSpan(40,3),R.clampSpan(1)]") == [3, 3, 5, 12, 3]


def test_the_last_visible_panel_cannot_be_hidden():
    assert run("R.hideRefused([false,true,true],0)") is True
    assert run("R.hideRefused([false,false,true],0)") is False
    assert run("R.hideRefused([false,true,true],1)") is False          # showing is always allowed


def test_the_announcement_says_the_kept_height():
    assert run("R.sizeText('Legs',6,24)") == "Legs: 6 of 12 columns wide, 24 rows tall"
    assert run("R.sizeText('Legs',12,0)").endswith("as tall as its content")
    assert run("R.sizeText('Legs',12,1)").endswith("1 row tall")


def test_a_measured_height_is_not_short_by_a_row():
    assert run("[R.rowsFromPixels(960,40),R.rowsFromPixels(959.4,40),R.rowsFromPixels(961,40),R.rowsFromPixels(5000,40)]") == [24, 24, 24, 24]


def test_layout_mode_uses_the_rules_and_the_page_loads_them():
    js = (JS / "layout.js").read_text()
    assert "toggleHidden" in js and "R.clampSpan" in js and "R.sizeText" in js


def test_the_view_carries_the_minimum_width_and_loads_the_rules(client):
    page = client.get("/v/trade/IRS-48213").text
    assert 'data-min-span="3"' in page and "layout-rules.js" in page
