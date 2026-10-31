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
"""A board tile with a hysteresis keeps its colour while the value hovers at a threshold (docs/guides/BI_GUIDE.md): the browser's rule, run in Node, is the same
rule TileLevel.java applies for the notification (the Java side is tested in TileLevelHysteresisTest)."""
import json
import shutil
import subprocess
from pathlib import Path

import pytest

STATIC = Path(__file__).resolve().parent.parent / "web" / "static"
node = shutil.which("node")
needs_node = pytest.mark.skipif(node is None, reason="needs Node to run the shared board code")

ALERT = {"on": "MTM", "warn": {"op": "lt", "value": 100}, "crit": {"op": "lt", "value": 50}, "hysteresis": 10}


def level(alert, value, previous=""):
    res = {"columns": [{"name": "MTM", "type": "decimal"}], "rows": [[value]]}
    script = ("global.window = {}; require(%s); console.log(JSON.stringify(window.drishtiBi.live.level(%s, %s, %s)))"
              % (json.dumps(str(STATIC / "js/bi-live.js")), json.dumps(alert), json.dumps(res), json.dumps(previous)))
    out = subprocess.run([node, "-e", script], capture_output=True, text=True, timeout=30)
    assert out.returncode == 0, out.stderr
    return json.loads(out.stdout)


@needs_node
def test_without_a_hysteresis_the_level_follows_the_value_exactly():
    plain = {k: v for k, v in ALERT.items() if k != "hysteresis"}
    assert [level(plain, v, p) for v, p in ((120, ""), (99, ""), (49, "warn"), (55, "crit"), (101, "warn"))] == ["", "warn", "crit", "warn", ""]


@needs_node
def test_a_tile_keeps_its_level_until_the_value_is_back_inside_by_the_hysteresis():
    assert level(ALERT, 49) == "crit"                    # crosses: red
    assert level(ALERT, 55, "crit") == "crit"            # back above 50 but not by 10: still red
    assert level(ALERT, 60, "crit") == "warn"            # 10 back inside the red threshold: red clears, amber still holds
    assert level(ALERT, 99, "warn") == "warn"
    assert level(ALERT, 105, "warn") == "warn"           # above 100 but within 10: still amber
    assert level(ALERT, 110, "warn") == ""               # 10 back: clear
    assert level(ALERT, 105, "") == ""                   # a tile that was normal does not turn amber by hovering


@needs_node
def test_a_tile_never_stays_lower_than_the_value_says():
    assert level(ALERT, 30, "") == "crit" and level(ALERT, 30, "warn") == "crit"
    assert level(ALERT, 120, "crit") == ""
