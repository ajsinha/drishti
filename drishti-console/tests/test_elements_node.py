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

"""Drishti's own Elements libraries in Node (it starts Node, so it runs in the console's lane, not the gate's quick checks: test_elements.py
has those): their unit tests (tests/elements/*.test.mjs, node --test: fast, no browser), against the vendored Drishti Elements. Drishti
Elements' own unit tests are in its project."""
import shutil
import subprocess

import pytest

from conftest import CONSOLE

NODE = shutil.which("node")


@pytest.mark.skipif(NODE is None, reason="needs Node.js")
def test_the_libraries_unit_tests_pass_in_node():
    files = sorted(str(p) for p in (CONSOLE / "tests" / "elements").glob("*.test.mjs"))
    assert files
    run = subprocess.run([NODE, "--test", *files], capture_output=True, text=True, timeout=300)
    assert run.returncode == 0, run.stdout[-4000:] + run.stderr[-2000:]
