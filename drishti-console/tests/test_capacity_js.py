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

"""Admin -> Capacity's evaluator as it runs in the browser (static/js/capacity.js under Node): every named figure of the planner
(drishti-server/src/main/resources/capacity/planner.yaml) at the three shapes of the shared test vectors
(drishti-server/src/test/resources/capacity/test-vectors.json, written by tools/sizing_planner.py) agrees with the Python evaluator the
workbook is checked with, text exactly (the YAML block and the command line too), numbers to a millionth; and today against the plan
says what breaks first from a server's measured figures."""
import json
import shutil
import subprocess
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
JS = ROOT / "drishti-console" / "web" / "static" / "js" / "capacity.js"
PLANNER = ROOT / "drishti-server" / "src" / "main" / "resources" / "capacity" / "planner.yaml"
VECTORS = ROOT / "drishti-server" / "src" / "test" / "resources" / "capacity" / "test-vectors.json"

AGREE = r"""
global.window = global;
const fs = require('fs');
eval(fs.readFileSync(process.argv[1], 'utf8'));
const plan = JSON.parse(fs.readFileSync(0, 'utf8'));
const vec = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const C = window.drishtiCapacity, bad = [];
let n = 0;
for (const [shape, s] of Object.entries(vec.shapes)) {
  const ev = C.evaluate(plan, Object.assign({ServerJar: s.results.ServerJar}, s.inputs));
  for (const [name, want] of Object.entries(s.results)) {
    let got;
    try { got = ev.value(name); } catch (e) { bad.push([shape, name, 'error: ' + e.message]); continue; }
    n++;
    const same = typeof want === 'number' ? typeof got === 'number' && Math.abs(got - want) <= 1e-6 * Math.max(1, Math.abs(want)) : got === want;
    if (!same) { bad.push([shape, name, got, want]); }
  }
}
process.stdout.write(JSON.stringify({n: n, bad: bad.slice(0, 10)}));
"""

HEADROOM = r"""
global.window = global;
const fs = require('fs');
eval(fs.readFileSync(process.argv[1], 'utf8'));
const input = JSON.parse(fs.readFileSync(0, 'utf8'));
const C = window.drishtiCapacity, ev = C.evaluate(input.plan, input.inputs);
const rows = C.headroom(input.plan, ev, input.live);
const g = C.growth(input.plan, input.inputs, [1, 2]).map(r => [r.factor, r.trades, r.ev.value('XmxGB'), r.ev.value('GcChoice')]);
process.stdout.write(JSON.stringify({rows: rows.map(r => [r.id, r.state, Math.round(r.need), r.has == null ? null : Math.round(r.has)]), growth: g}));
"""

pytestmark = pytest.mark.skipif(shutil.which("node") is None, reason="needs Node.js to run capacity.js")


def node(script: str, stdin: dict, *args) -> dict:
    run = subprocess.run(["node", "-e", script, str(JS), *map(str, args)], input=json.dumps(stdin), capture_output=True, text=True, timeout=120)
    assert run.returncode == 0, run.stderr
    return json.loads(run.stdout)


def plan() -> dict:
    return yaml.safe_load(PLANNER.read_text(encoding="utf-8"))


def test_the_browser_evaluator_agrees_with_the_workbooks_on_every_figure():
    out = node(AGREE, plan(), VECTORS)
    assert out["bad"] == [], out["bad"]
    assert out["n"] > 800, out["n"]


def test_today_against_the_plan_says_what_is_short():
    live = {"server": {"heapMaxMb": 8192, "heapAfterGcMb": 4310, "rssMb": 15250, "ramMb": 32768, "cpus": 24},
            "disk": {"freeBytes": 209 << 30, "totalBytes": 500 << 30, "guardBytes": 50 << 30},
            "load": {"requestsPerSecondPeakToday": 96}}
    out = node(HEADROOM, {"plan": plan(), "inputs": {}, "live": live})
    rows = {r[0]: r for r in out["rows"]}
    assert rows["heap"][1] == "short"                      # the plan needs -Xmx10g's worth (9.4 GB), the server has 8 GB
    assert rows["ram"][1] in ("room", "tight")             # 24 GB planned on a 32 GB machine
    assert rows["disk"][1] in ("room", "tight")
    assert rows["cores"][1] == "room"
    assert out["growth"][0][:3] == [1, 300000, 10] and out["growth"][1][1] == 600000
    nothing = node(HEADROOM, {"plan": plan(), "inputs": {}, "live": None})
    assert all(r[1] == "none" for r in nothing["rows"])
