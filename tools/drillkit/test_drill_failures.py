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
"""The drill's failures plugin (tools/drillkit/drill_failures.py): a failing test is written to the lane's file as it happens."""
from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]

SAMPLE = '''
import pytest

def test_ok():
    assert True

def test_bad():
    assert 1 == 2, "one is not two"

@pytest.fixture
def broken():
    raise RuntimeError("fixture exploded")

def test_error(broken):
    pass
'''


class FailuresPlugin(unittest.TestCase):
    def run_pytest(self, tmp: Path, *extra: str) -> tuple[subprocess.CompletedProcess, Path]:
        (tmp / "test_sample.py").write_text(SAMPLE, encoding="utf-8")
        out = tmp / "lane-failures.txt"
        env = dict(os.environ, PYTHONPATH=str(HERE), DRILL_FAILURES_OUT=str(out))
        r = subprocess.run([sys.executable, "-m", "pytest", "-q", "-p", "no:cacheprovider", "-p", "drill_failures", *extra, str(tmp / "test_sample.py")],
                           cwd=tmp, env=env, capture_output=True, text=True, check=False)
        return r, out

    def test_each_failure_and_error_is_written_with_its_reason_and_counted(self):
        with tempfile.TemporaryDirectory() as d:
            r, out = self.run_pytest(Path(d))
            lines = out.read_text(encoding="utf-8").splitlines()
            self.assertEqual(2, len(lines), lines)
            self.assertTrue(any("::test_bad  [call]  " in x and "one is not two" in x for x in lines), lines)
            self.assertTrue(any("::test_error  [setup]  " in x and "fixture exploded" in x for x in lines), lines)
            self.assertIn("drill: 2 failing so far", r.stdout)

    def test_a_workers_reports_are_written_once_by_the_controller(self):
        try:
            import xdist  # noqa: F401
        except ImportError:
            self.skipTest("pytest-xdist is not installed")
        with tempfile.TemporaryDirectory() as d:
            _, out = self.run_pytest(Path(d), "-n", "2")
            self.assertEqual(2, len(out.read_text(encoding="utf-8").splitlines()))

    def test_without_the_variable_nothing_is_written(self):
        with tempfile.TemporaryDirectory() as d:
            tmp = Path(d)
            (tmp / "test_sample.py").write_text(SAMPLE, encoding="utf-8")
            env = {k: v for k, v in os.environ.items() if k != "DRILL_FAILURES_OUT"}
            env["PYTHONPATH"] = str(HERE)
            subprocess.run([sys.executable, "-m", "pytest", "-q", "-p", "no:cacheprovider", "-p", "drill_failures", str(tmp / "test_sample.py")],
                           cwd=tmp, env=env, capture_output=True, text=True, check=False)
            self.assertEqual([], sorted(p.name for p in tmp.glob("*failures*")))

    def test_every_pytest_lane_loads_the_plugin(self):
        text = (ROOT / "tools" / "drill.sh").read_text(encoding="utf-8")
        self.assertGreaterEqual(text.count("-p drill_failures"), 3)       # the console lanes, the tools' files and the anywhere lane
        for lane in ("tools-a", "tools-b", "anywhere"):
            self.assertIn(f"{lane}-failures.txt", text)
        self.assertIn('DRILL_FAILURES_OUT="$LOGS/$name-failures.txt"', text)   # browser and other


if __name__ == "__main__":
    unittest.main()
