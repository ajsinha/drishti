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

"""Studio's light check as it runs in the browser (studio-yaml.js and studio-assist.js under Node): QA 2026-10-01 GRAM-07,
a tab-indented line is said to be indented with a tab, not to need a colon."""
import json
import shutil
import subprocess
from pathlib import Path

import pytest

JS = Path(__file__).resolve().parent.parent / "web" / "static" / "js"
SCHEMA = {
    "properties": {
        "rachana": {}, "sutra": {}, "version": {},
        "match": {"type": "object", "properties": {"kind": {}, "where": {}, "priority": {}}},
        "title": {"type": "object", "properties": {"pill": {}, "id": {}, "with": {}}},
        "panels": {"type": "array", "items": {"$ref": "#/$defs/panel"}},
    },
    "$defs": {"panel": {"type": "object", "properties": {"id": {}, "kind": {}, "title": {}}, "required": ["id", "kind"], "allOf": []}},
}
HARNESS = """
global.window = global;
const fs = require('fs');
eval(fs.readFileSync(process.argv[1], 'utf8'));
eval(fs.readFileSync(process.argv[2], 'utf8'));
const schema = new window.drishtiAssist.Schema(JSON.parse(process.argv[3]));
const ps = window.drishtiAssist.check(schema, window.drishtiYaml.parse(process.argv[4]));
process.stdout.write(JSON.stringify(ps.map(p => [p.location.line, p.message])));
"""


def check(text: str) -> list:
    run = subprocess.run(["node", "-e", HARNESS, str(JS / "studio-yaml.js"), str(JS / "studio-assist.js"), json.dumps(SCHEMA), text],
                         capture_output=True, text=True, timeout=60)
    assert run.returncode == 0, run.stderr
    return json.loads(run.stdout)


@pytest.mark.skipif(shutil.which("node") is None, reason="needs Node.js to run the Studio scripts")
def test_a_tab_indented_line_is_said_to_be_indented_with_a_tab():
    text = ("rachana: 1\nsutra: qa-tab\nversion: 1\nmatch: { kind: trade }\ntitle: { id: $.tradeId }\npanels:\n"
            "\t- id: terms\n    kind: kv\n")
    problems = check(text)
    assert [7, "line 7 is indented with a tab: YAML allows only spaces"] in problems
    assert not any("needs a colon" in m for _, m in problems), problems


@pytest.mark.skipif(shutil.which("node") is None, reason="needs Node.js to run the Studio scripts")
def test_a_key_without_a_colon_still_says_so():
    problems = check("rachana: 1\nsutra qa\n")
    assert any("needs a colon" in m for _, m in problems), problems
