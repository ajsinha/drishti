#!/usr/bin/env python3
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
"""A pytest plugin of the release drill (tools/drill.sh): every failure and error written as it happens.

Loaded with ``-p drill_failures`` (tools/drillkit on PYTHONPATH); DRILL_FAILURES_OUT names the file it appends to, one line per failing
phase: ``<nodeid>  [<setup|call|teardown>]  <one-line reason>``. Under pytest-xdist the workers relay every report to the controller,
which is the only process that writes (a worker knows itself by ``config.workerinput``), so a line is never written twice and the file
grows while the lane runs: a drill can read which tests fail without waiting for the end summary that ``-q`` prints last. The terminal
gets a running count (``drill: N failing so far``) with each failure, which shows in the lane's log as it grows.
"""
from __future__ import annotations

import os
from pathlib import Path

_state = {"worker": False, "count": 0, "terminal": None}


def pytest_configure(config) -> None:
    """Notes whether this process is an xdist worker, and the terminal reporter for the running count."""
    _state["worker"] = hasattr(config, "workerinput")
    _state["terminal"] = config.pluginmanager.getplugin("terminalreporter")


def reason_of(report) -> str:
    """One line saying why the report failed (the crash message's first line, else the report's own text), at most 240 characters."""
    crash = getattr(getattr(report, "longrepr", None), "reprcrash", None)
    text = getattr(crash, "message", None) or str(getattr(report, "longrepr", "") or "")
    line = next((x.strip() for x in text.splitlines() if x.strip()), "")
    return line[:240]


def line_of(report) -> str:
    """The file's line for a failed report."""
    return f"{report.nodeid}  [{report.when}]  {reason_of(report)}"


def pytest_runtest_logreport(report) -> None:
    """Appends a failing phase to DRILL_FAILURES_OUT and prints the running count (the controller or a plain session only)."""
    out = os.environ.get("DRILL_FAILURES_OUT")
    if not out or _state["worker"] or not report.failed:
        return
    _state["count"] += 1
    p = Path(out)
    p.parent.mkdir(parents=True, exist_ok=True)
    with open(p, "a", encoding="utf-8") as f:
        f.write(line_of(report) + "\n")
    tr = _state["terminal"]
    if tr is not None:
        tr.write_line(f"drill: {_state['count']} failing so far: {report.nodeid}")
