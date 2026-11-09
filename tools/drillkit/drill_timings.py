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

"""A pytest plugin of the release drill (tools/drill.sh): the seconds each test file took, written when the session ends.

Loaded with ``-p drill_timings`` (tools/drillkit on PYTHONPATH); DRILL_TIMINGS_OUT names the JSON file it writes,
``{"files": {"drishti-console/tests/test_x.py": 12.3, ...}}``. A file's seconds are the sum of its tests' setup, call and teardown,
so a module fixture that starts a server counts once, in the file that starts it. Under pytest-xdist only the controller writes:
the workers' reports reach it with their durations. tools/drillkit/drill_plan.py merges the file into data/drill/durations.json and
orders the next drill's lanes longest first.
"""
from __future__ import annotations

import json
import os
import time
from collections import defaultdict
from pathlib import Path

_seconds: dict[str, float] = defaultdict(float)
_PAUSE = 300.0        # seconds a test waits at most for memory: never fails it, and a drill that is really out of memory still shows


def _available_gb() -> float:
    try:
        for line in Path("/proc/meminfo").read_text().splitlines():
            if line.startswith("MemAvailable:"):
                return int(line.split()[1]) / 1048576
    except OSError:
        pass
    return 1e9


def pytest_runtest_setup(item) -> None:
    """The memory floor (DRILL_MIN_FREE_GB): a test does not start while less is free, up to five minutes, so the workers ease off when the
    Java lane peaks instead of Chromium being killed."""
    floor = float(os.environ.get("DRILL_MIN_FREE_GB") or 0)
    began = time.monotonic()
    while floor and _available_gb() < floor and time.monotonic() - began < _PAUSE:
        time.sleep(2)


def pytest_runtest_logreport(report) -> None:
    """Adds one phase's duration to its file."""
    _seconds[report.nodeid.split("::", 1)[0]] += float(getattr(report, "duration", 0.0) or 0.0)


def pytest_sessionfinish(session, exitstatus) -> None:
    """Writes the per-file seconds (the controller only, when DRILL_TIMINGS_OUT is set)."""
    out = os.environ.get("DRILL_TIMINGS_OUT")
    if not out or hasattr(session.config, "workerinput") or not _seconds:
        return
    root = Path(str(session.config.rootpath))
    files = {}
    for nodeid, secs in _seconds.items():
        p = Path(nodeid)
        files[str(p if not p.is_absolute() else p.relative_to(root))] = round(secs, 1)
    Path(out).parent.mkdir(parents=True, exist_ok=True)
    Path(out).write_text(json.dumps({"files": dict(sorted(files.items()))}, indent=1) + "\n", encoding="utf-8")
