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

"""What the inspector reads of a Sutra (build/yamlmodel.js, on Studio's forgiving reader), run in Node: every panel of every example,
including panels written as a flow mapping over several lines, and the options of a panel as values."""
import json
from pathlib import Path
import shutil
import subprocess

import pytest

ROOT = Path(__file__).resolve().parents[2]
JS = ROOT / "console" / "web" / "static" / "js"
EXAMPLES = ROOT / "docs" / "guides" / "examples"
NODE = shutil.which("node")
pytestmark = pytest.mark.skipif(NODE is None, reason="needs node")


def model(text: str) -> dict:
    code = ("global.window=global;const fs=require('fs');eval(fs.readFileSync(process.argv[1],'utf8'));eval(fs.readFileSync(process.argv[2],'utf8'));"
            "console.log(JSON.stringify(window.DrishtiWB.model(process.argv[3])))")
    out = subprocess.run([NODE, "-e", code, str(JS / "studio-yaml.js"), str(JS / "build" / "yamlmodel.js"), text], capture_output=True, text=True, check=True, timeout=30)
    return json.loads(out.stdout)


def test_every_panel_of_every_example_is_read_including_multi_line_flow_maps():
    for sutra in sorted(EXAMPLES.glob("*.sutra.yaml")):
        text = sutra.read_text()
        want = text.count("id: ") - text.count("  id: ") * 0           # every panel has one id: line or flow key; titles use `id:` once
        m = model(text)
        ids = [p["id"] for p in m["panels"]]
        assert len(ids) == len(set(ids)) and ids, sutra.name
        assert m["title"].get("id"), sutra.name
        assert want >= len(ids)
    showcase = model((EXAMPLES / "all-panels-showcase.sutra.yaml").read_text())
    assert len(showcase["panels"]) == 21 and {p["kind"] for p in showcase["panels"]} >= {"waterfall", "kv", "pivot", "timeline"}


def test_a_panel_written_over_several_lines_keeps_its_options_and_the_lines_after_it_keep_their_numbers():
    text = "panels:\n  - { id: a, kind: waterfall,\n      rows: $.x, label: step }\n  - id: b\n    kind: kv\n"
    m = model(text)
    assert [p["id"] for p in m["panels"]] == ["a", "b"]
    assert m["panels"][0]["values"]["rows"] == "$.x" and m["panels"][1]["line"] == 3                     # 0-based: the fourth line


def test_braces_in_text_blocks_and_quotes_do_not_join_lines():
    text = "description: >\n  a { b\n  c\npanels:\n  - id: a\n    kind: kv\n    title: \"{ not a map\"\n  - id: b\n    kind: kv\n"
    assert [p["id"] for p in model(text)["panels"]] == ["a", "b"]
