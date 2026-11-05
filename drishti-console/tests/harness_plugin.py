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

"""How the console's tests are handed to pytest-xdist workers (docs/architecture/TEST_HARNESS.md, "Distribution by test").

The browser lane runs with ``--dist loadgroup``. Every test is a work unit of its own when it is marked ``spread`` (a small,
independent test that needs only the per-worker fixtures: the worker's browser, its stand-in console, the shared pack views); every
other test is grouped with the rest of its file (``xdist_group`` = the file), so a file that starts its own server or keeps state
between its tests still runs on one worker, in order, as under ``--dist loadfile``. The spread tests are queued after the file groups:
the groups are long and the planner orders them longest first, the spread tests are seconds each and fill the workers at the end.

Under any other ``--dist`` (the other lane, the gate) the marks change nothing. Imported by conftest.py, which re-exports the hooks."""
from __future__ import annotations

from pathlib import Path

import pytest

MARKERS = (
    "spread: a small independent test; under --dist loadgroup it is a work unit of its own (TEST_HARNESS.md)",
    "browser: drives a browser (informational)",
    "xdist_group(name): run with the other tests of that group on one worker",
)


def configure(config) -> None:
    for m in MARKERS:
        config.addinivalue_line("markers", m)


def group_and_order(config, items: list) -> None:
    """Under --dist loadgroup: the file group on every test that is not spread, and the spread tests last (stable otherwise)."""
    if not (getattr(config.option, "loadgroup", False) or getattr(config.option, "dist", "") == "loadgroup"):
        return
    for item in items:
        if item.get_closest_marker("spread") or item.get_closest_marker("xdist_group"):
            continue
        item.add_marker(pytest.mark.xdist_group(name=Path(str(item.fspath)).name))
    items.sort(key=lambda it: 1 if it.get_closest_marker("spread") else 0)


@pytest.hookimpl(tryfirst=True)
def pytest_collection_modifyitems(config, items):
    group_and_order(config, items)


def pytest_configure(config):
    configure(config)
