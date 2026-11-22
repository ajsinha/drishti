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
    "serial: changes server-wide state on the lane's server (TEST_HARNESS.md, \"Lane servers\"): the drill runs it after the parallel run, -n 0, and it restores what it changed",
)


def configure(config) -> None:
    for m in MARKERS:
        config.addinivalue_line("markers", m)


def _set_nodeid(item, nodeid: str) -> None:
    """Sets a test's node id on any pytest: the structured ``_id`` (pytest 9.2) or ``_nodeid`` (before)."""
    if hasattr(item, "_id"):
        from _pytest.nodeid import coerce_node_id
        item._id = coerce_node_id(nodeid)
    else:
        item._nodeid = nodeid


def group_and_order(config, items: list) -> None:
    """Under --dist loadgroup: the file group on every test that is not spread, and the spread tests last (stable otherwise)."""
    if not (getattr(config.option, "loadgroup", False) or getattr(config.option, "dist", "") == "loadgroup"):
        return
    for item in items:
        if item.get_closest_marker("spread"):
            continue
        if not item.get_closest_marker("xdist_group"):
            item.add_marker(pytest.mark.xdist_group(name=Path(str(item.fspath)).name))
        # the group as xdist's scheduler reads it: the node id's "@group" suffix. pytest-xdist 3.8 writes it to item._nodeid, which
        # pytest 9.2 no longer reads (the id is item._id), so --dist loadgroup grouped nothing: a file's tests went to every worker
        # and each started the file's module fixtures (its servers) again (found 2026-11-22). Written here the way pytest reads it.
        if "@" not in item.nodeid:
            names = sorted({str(m.args[0] if m.args else m.kwargs.get("name", "default")) for m in item.iter_markers("xdist_group")})
            _set_nodeid(item, f"{item.nodeid}@{'_'.join(names)}")
    items.sort(key=lambda it: 1 if it.get_closest_marker("spread") else 0)


@pytest.hookimpl(tryfirst=True)
def pytest_collection_modifyitems(config, items):
    group_and_order(config, items)


def pytest_configure(config):
    configure(config)
