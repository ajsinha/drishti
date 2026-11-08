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

"""The java and python load routes into a temporary lake (its own lake file under DRISHTI_LAKES_DIR, its own root): the recorded test
feed pulled, loaded, and loaded again for the same date (replace by date: the same rows, never twice). They need the server's exec jar
(DRISHTI_JAR or drishti-server/target) and Java 21; without them they are skipped."""
from __future__ import annotations

import json
import os
import sys
from datetime import date
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
sys.path[:0] = [str(HERE.parent), str(HERE.parent / "sources"), str(HERE.parents[1] / "lake")]
import feedbase as FB  # noqa: E402
import feedrunner as R  # noqa: E402
from test_feeds import FX, Recorded  # noqa: E402


def _jar() -> str | None:
    import javaload
    import writers
    j = writers.find_jar(None)
    return j if j and javaload.jar_has_loader(j) and writers.find_java(None) else None


pytestmark = pytest.mark.skipif(_jar() is None, reason="needs the server's exec jar with the parallel loader and Java 21")


@pytest.fixture
def lake(tmp_path, monkeypatch):
    monkeypatch.setenv("DRISHTI_DATA_DIR", str(tmp_path / "data"))
    lakes = tmp_path / "lakes"
    lakes.mkdir()
    root = tmp_path / "lake"
    (lakes / "feedtestlake.yaml").write_text(f"lake: feedtestlake\nformat: delta\nroot: {root}\ndefaults: {{load-mode: replace}}\ntables: {{}}\n")
    monkeypatch.setenv("DRISHTI_LAKES_DIR", str(lakes))
    return root


def rows_in(root: Path) -> int:
    """The rows of the lake's lei-mapping table, from its Delta log's add actions (numRecords)."""
    log = next(root.rglob("lei-mapping/_delta_log"))
    live = {}
    for f in sorted(log.glob("*.json")):
        for line in f.read_text().splitlines():
            a = json.loads(line)
            if "add" in a:
                live[a["add"]["path"]] = json.loads(a["add"].get("stats") or "{}").get("numRecords", 0)
            if "remove" in a:
                live.pop(a["remove"]["path"], None)
    return sum(live.values())


@pytest.mark.parametrize("route", ["java", "python"])
def test_a_route_loads_the_feed_into_the_lake_and_a_rerun_replaces_the_date(lake, route):
    spec = FB.load_spec("sample-mapping", FX)
    spec.datasets.pop("country")                     # one table: the pack's lei-mapping
    o = R.RunOptions(business_date=date(2026, 11, 5), since=date(2026, 11, 4), load=route)
    logs = []
    rc, entry = R.run_feed(spec, o, log=logs.append, opener=Recorded(), sleep=lambda s: None)
    assert rc == 0, "\n".join(logs)
    assert entry["load"]["route"] == route and entry["load"]["status"] == "ok" and entry["load"]["rows"] == 3 + 4
    assert rows_in(lake) == 7
    rc, entry = R.run_feed(spec, o, log=logs.append, opener=Recorded(), sleep=lambda s: None)
    assert rc == 0, "\n".join(logs)
    assert rows_in(lake) == 7                         # replaced by date, not appended
