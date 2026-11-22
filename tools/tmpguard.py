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

"""A test session's private temp folder (the standing rule: a test run leaves nothing in the machine's temp folder).

Imported first by the conftests of the Python and browser suites. It makes ONE folder for the whole session (the controller makes it, the
xdist workers inherit it through ``DRISHTI_PYTEST_TMP``) and points ``tempfile`` and the ``TMPDIR`` / ``TEMP`` / ``TMP`` variables of this process
and of every child it starts (servers, consoles, browsers) at it, so ``mkdtemp``, ``TemporaryDirectory``, ``pytest``'s ``tmp_path`` and the browsers'
profile folders all land inside it. The controller removes the folder when the session ends (a worker's leftovers included).

``DRISHTI_TMP_STRICT=1`` also makes the session fail when it ended with a path of its own left in the machine's temp folder (a test that wrote to
``/tmp`` by name): the new ``drishti-*`` / ``tmp*`` entries of that folder this user owns, which another run on the machine can also add; the
strict check is for a quiet machine (the drill, the gate).
"""
import os
import shutil
import tempfile
import time

ENV = "DRISHTI_PYTEST_TMP"
_STARTED = time.time()
_BASE = tempfile.gettempdir()
_OWNER = False


def install() -> str:
    """Make (controller) or join (worker, child) the session's temp folder; returns it."""
    global _OWNER
    folder = os.environ.get(ENV)
    if not folder or not os.path.isdir(folder):
        folder = tempfile.mkdtemp(prefix="drishti-pytest-", dir=_BASE)
        os.environ[ENV] = folder
        _OWNER = True
    mine = os.path.join(folder, os.environ.get("PYTEST_XDIST_WORKER") or "main")
    os.makedirs(mine, exist_ok=True)
    for k in ("TMPDIR", "TEMP", "TMP"):
        os.environ[k] = mine
    tempfile.tempdir = mine
    return folder


def _leaks() -> list:
    out = []
    uid = os.getuid() if hasattr(os, "getuid") else None
    try:
        names = os.listdir(_BASE)
    except OSError:
        return out
    folder = os.environ.get(ENV, "")
    for n in names:
        p = os.path.join(_BASE, n)
        if p == folder or not (n.startswith("drishti-") or n.startswith("tmp")):
            continue
        try:
            st = os.lstat(p)
        except OSError:
            continue
        if (uid is None or st.st_uid == uid) and st.st_mtime >= _STARTED:
            out.append(n)
    return sorted(out)


def pytest_sessionfinish(session, exitstatus):
    """The controller's end: remove the session folder; with DRISHTI_TMP_STRICT=1, fail the session when a path of its own stays in /tmp."""
    if os.environ.get("PYTEST_XDIST_WORKER") or not _OWNER:
        return
    shutil.rmtree(os.environ.get(ENV, ""), ignore_errors=True)
    if os.environ.get("DRISHTI_TMP_STRICT") == "1":
        left = _leaks()
        if left:
            print("\nthe test session left these in " + _BASE + ": " + ", ".join(left))
            session.exitstatus = 1
