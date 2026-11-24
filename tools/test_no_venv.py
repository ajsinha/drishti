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

"""Guard (owner rule, i7g): only setup makes a virtual environment, and only on Linux and macOS (drishti-console/.venv); on Windows
Drishti never makes one (the Python you run it with). start, the console and every other tool make none, anywhere; no launcher and no
generated IDE file names a .venv. (The server's compute environments, made under the data folder for an approved lock, are a server
feature of their own and outside this rule's code scope: compute/.)"""
from __future__ import annotations

import re
import subprocess
from pathlib import Path

import pytest

pytestmark = pytest.mark.static

ROOT = Path(__file__).resolve().parents[1]
MAKES_A_VENV = re.compile(r"-m\s+venv\b|\buv\s+venv\b|virtualenv|venv\.create|EnvBuilder|[\"']-m[\"'],\s*[\"']venv[\"']|make_venv")


def tracked(*paths: str) -> list[Path]:
    out = subprocess.run(["git", "-C", str(ROOT), "ls-files", "-z", *paths], capture_output=True, text=True).stdout.split("\0")
    return [ROOT / f for f in out if f]


def code_files() -> list[Path]:
    files = tracked("tools", "drishti-console", "deploy")
    return [f for f in files if f.suffix in (".py", ".sh", ".ps1", ".cmd", ".bat") or "Dockerfile" in f.name
            if "tests" not in f.relative_to(ROOT).parts and not f.name.startswith("test_") and f.is_file()]


SETUP = "tools/launch/envsetup.py"          # the one place a venv is made (Linux, macOS)


def test_no_code_but_setup_makes_a_venv():
    found = [f"{f.relative_to(ROOT)}:{n}: {line.strip()}" for f in code_files() if f.relative_to(ROOT).as_posix() != SETUP
             for n, line in enumerate(f.read_text(encoding="utf-8", errors="replace").splitlines(), 1)
             if MAKES_A_VENV.search(line) and "def make_venv" not in line]
    assert found == [], "only setup makes a virtual environment:\n" + "\n".join(found)


def test_on_windows_setup_never_makes_a_venv(tmp_path, monkeypatch):
    import sys
    sys.path[:0] = [str(ROOT / "tools"), str(ROOT / "tools" / "launch")]
    import argparse
    import envsetup
    import procs
    monkeypatch.setattr(procs, "windows", lambda: True)
    with pytest.raises(procs.LaunchError, match="Windows uses the Python you run Drishti with; no .venv"):
        envsetup.make_venv(tmp_path, "python", run=lambda *a, **k: 1 / 0)
    for kw in (dict(part="venv"), dict(part=None, reinstall=True)):
        a = argparse.Namespace(**{"part": None, "system": False, "check": False, "reinstall": False, "skip_venv": False, "python": None,
                                  "wheels": None, "index": False, "data_dir": None, "envs_dir": str(tmp_path / "e"), "java_home": None, **kw})
        with pytest.raises(procs.LaunchError, match="no .venv"):
            envsetup.setup(a, {})
    assert envsetup.system_mode(argparse.Namespace(system=False)) is True, "plain setup on Windows is the system-Python mode"
    assert not (tmp_path / "drishti-console").exists()


def test_no_launcher_and_no_generated_ide_file_names_a_venv():
    files = [*tracked("run-configs"), *tracked("drishti-console/run-configs"), ROOT / "drishti.code-workspace"]
    named = [str(f.relative_to(ROOT)) for f in files if f.is_file() and ".venv" in f.read_text(encoding="utf-8")]
    assert files and named == []


def test_start_never_makes_or_installs():
    """setup prepares, start starts: the start and console code holds no venv making and no pip install."""
    for rel in ("tools/launch/consolestart.py", "tools/launch/serverstart.py", "tools/launch/clusterctl.py", "drishti-console/run_drishti_web.py"):
        text = (ROOT / rel).read_text(encoding="utf-8")
        assert "make_venv" not in text and "pylibs.install" not in text and '"pip", "install"' not in text, rel
