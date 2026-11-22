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
import sys
import time
from collections import defaultdict
from pathlib import Path

_seconds: dict[str, float] = defaultdict(float)
_phases: dict[str, dict] = {}   # the journal: a test's phases until its teardown reports
_CONFIG = None
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


def pytest_configure(config) -> None:
    global _CONFIG
    _CONFIG = config
    if str(Path(__file__).parent) not in sys.path:
        sys.path.insert(0, str(Path(__file__).parent))


def pytest_collection_modifyitems(config, items) -> None:
    """The drill's resume (DRILL_RESUME_FILE, written by drill_journal.py snapshot before the lane starts, so every worker sees the same
    list): a test that passed at this commit with the same file key is not run again."""
    path = os.environ.get("DRILL_RESUME_FILE")
    if not path or not Path(path).is_file():
        return
    import drill_journal
    done = {r["id"]: r["key"] for r in drill_journal.read(path)}
    root, keys, keep, gone = Path(str(config.rootpath)), {}, [], []
    for item in items:
        f = item.nodeid.split("::", 1)[0]
        if f not in keys:
            keys[f] = drill_journal.py_key(root, item.nodeid)
        (gone if done.get(item.nodeid) == keys[f] else keep).append(item)
    if gone:
        config.hook.pytest_deselected(items=gone)
        items[:] = keep
        wid = getattr(config, "workerinput", {}).get("workerid", "gw0")
        if wid == "gw0" and os.environ.get("DRILL_RESUMED"):   # one worker counts (every worker collects the same tests)
            drill_journal.append(os.environ["DRILL_RESUMED"], {"lane": os.environ.get("DRILL_LANE", ""), "count": len(gone)})


def _journal(report) -> None:
    """One line per finished test, at its teardown (a test killed mid-way leaves none): failed when any phase failed."""
    path = os.environ.get("DRILL_JOURNAL")
    if not path:
        return
    st = _phases.setdefault(report.nodeid, {"failed": False, "called": False, "secs": 0.0})
    st["secs"] += float(getattr(report, "duration", 0.0) or 0.0)
    st["failed"] |= report.outcome == "failed"
    st["called"] |= report.when == "call" and report.outcome == "passed"
    if report.when != "teardown":
        return
    st = _phases.pop(report.nodeid)
    import drill_journal
    outcome = "failed" if st["failed"] else "passed" if st["called"] else "skipped"
    drill_journal.append(path, {"lane": os.environ.get("DRILL_LANE", ""), "id": report.nodeid, "outcome": outcome,
                                "seconds": round(st["secs"], 2), "commit": os.environ.get("DRILL_COMMIT", ""),
                                "key": drill_journal.py_key(Path(str(_CONFIG.rootpath)), report.nodeid), "module": ""})


def pytest_runtest_logreport(report) -> None:
    """Adds one phase's duration to its file; the controller (or the one process) journals the finished test."""
    _seconds[report.nodeid.split("::", 1)[0]] += float(getattr(report, "duration", 0.0) or 0.0)
    if _CONFIG is not None and not hasattr(_CONFIG, "workerinput"):
        _journal(report)


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
