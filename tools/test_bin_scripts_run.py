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
"""The scripts of bin/ run (tools/test_bin_scripts.py checks their files): --help exits 0 and a wrong option 2 (bash; PowerShell when
pwsh is installed), every .sh parses, a loopback address is never advertised, and a stale pid file is reported and removed. Nothing here
starts a server or a console.

    python3 -m pytest -q tools/test_bin_scripts_run.py
"""
from __future__ import annotations

import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
BIN = ROOT / "bin"
SCRIPTS = ("start-server", "stop-server", "start-console", "stop-console", "status", "start-all", "stop-all")
SH = sorted(BIN.glob("*.sh")) + [BIN / "lib" / "common.sh"]


@pytest.mark.skipif(shutil.which("bash") is None, reason="no bash")
@pytest.mark.parametrize("name", SCRIPTS)
def test_help_and_a_wrong_option(name):
    script = BIN / f"{name}.sh"
    r = subprocess.run(["bash", str(script), "--help"], capture_output=True, text=True, timeout=30)
    assert r.returncode == 0 and r.stdout.startswith("usage: bin/"), r.stderr
    r = subprocess.run(["bash", str(script), "--no-such-option"], capture_output=True, text=True, timeout=30)
    assert r.returncode == 2 and "unknown option --no-such-option" in r.stderr


@pytest.mark.skipif(shutil.which("bash") is None, reason="no bash")
def test_the_shell_scripts_parse():
    for p in SH:
        r = subprocess.run(["bash", "-n", str(p)], capture_output=True, text=True, timeout=30)
        assert r.returncode == 0, f"{p.name}: {r.stderr}"


@pytest.mark.skipif(shutil.which("pwsh") is None, reason="no PowerShell on this machine")
@pytest.mark.parametrize("name", SCRIPTS)
def test_ps1_help(name):
    r = subprocess.run(["pwsh", "-NoProfile", "-File", str(BIN / f"{name}.ps1"), "--help"], capture_output=True, text=True, timeout=60)
    assert r.returncode == 0 and "usage: bin\\" in r.stdout, r.stderr


def test_a_loopback_address_is_never_advertised(tmp_path):
    r = subprocess.run(["bash", str(BIN / "start-server.sh"), "--node", "n1", "--advertise", "127.0.0.1", "--data-dir", str(tmp_path),
                        "--port", "1"], capture_output=True, text=True, timeout=60)
    assert r.returncode == 2 and "loopback" in r.stderr, r.stderr


def test_a_stale_pid_file_is_removed(tmp_path):
    (tmp_path / "run").mkdir()
    pid = tmp_path / "run" / "server-ghost.pid"
    pid.write_text("999999\nport=1\n", encoding="utf-8")
    r = subprocess.run(["bash", str(BIN / "status.sh"), "--data-dir", str(tmp_path)], capture_output=True, text=True, timeout=30)
    assert r.returncode == 1 and "stale pid" in r.stdout
    r = subprocess.run(["bash", str(BIN / "stop-server.sh"), "--all-nodes", "--data-dir", str(tmp_path)], capture_output=True, text=True, timeout=30)
    assert r.returncode == 0 and "stale" in r.stdout and not pid.exists()
