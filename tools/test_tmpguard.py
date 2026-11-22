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

"""The test suites leave nothing in the machine's temp folder (tools/tmpguard.py): a session that creates temp folders the careless way
(``mkdtemp`` with no cleanup, a folder made by a worker) ends with its session folder removed and nothing of its own beside it."""
import os
import subprocess
import sys
from pathlib import Path

TOOLS = Path(__file__).resolve().parent

CONFTEST = '''
import tmpguard
from tmpguard import pytest_sessionfinish  # noqa
tmpguard.install()
'''

CASE = '''
import os, tempfile


def test_leaves_folders_behind():
    d = tempfile.mkdtemp(prefix="careless-")
    open(os.path.join(d, "f"), "w").write("x")
    tempfile.NamedTemporaryFile(delete=False).close()
'''


def test_a_session_removes_every_temp_path_it_made(tmp_path):
    base = tmp_path / "machine-tmp"
    base.mkdir()
    case = tmp_path / "test_case.py"
    case.write_text(CASE, encoding="utf-8")
    (tmp_path / "conftest.py").write_text(CONFTEST, encoding="utf-8")
    env = dict(os.environ, TMPDIR=str(base), PYTHONPATH=str(TOOLS), DRISHTI_TMP_STRICT="1")
    for k in ("DRISHTI_PYTEST_TMP", "PYTEST_XDIST_WORKER"):
        env.pop(k, None)
    r = subprocess.run([sys.executable, "-m", "pytest", "-p", "no:cacheprovider", "-q", str(case)], env=env, cwd=str(tmp_path),
                       capture_output=True, text=True, timeout=120)
    assert r.returncode == 0, r.stdout + r.stderr
    assert list(base.iterdir()) == [], "left in the temp folder: " + ", ".join(sorted(p.name for p in base.iterdir()))
