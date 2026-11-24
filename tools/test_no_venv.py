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

"""Guard (owner rule, i7g): on every system Drishti never MAKES a virtual environment and never NAMES one. The one Python is
DRISHTI_PYTHON, else drishti.python of the settings file, else the Python running the tool; a user who wants a virtual environment makes
it and runs Drishti with it, or names it in drishti.python. Outside the documents, no tracked file holds the dot-venv folder name, except
as an IGNORE pattern (a user's own environment in the tree is never packed or scanned) whose line, or the line before it, says so, and
the two ignore files. No code runs ``-m venv``, ``uv venv``, virtualenv or venv.create, the server's compute environments included."""
from __future__ import annotations

import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NAME = "." + "venv"                                    # spelled so: this guard holds no such literal itself
MAKES = re.compile(r"-m\s+" + "venv" + r"\b|\buv\s+" + "venv" + r"\b|(?<![-\w])virtual" + "env\\b|venv" + r"\.create|EnvBuilder|"
                   r"[\"']-m[\"'],\s*[\"']" + "venv" + r"[\"']|make_" + "venv")
IGNORE_FILES = {".gitignore", ".dockerignore"}
TEXT = (".py", ".sh", ".ps1", ".cmd", ".bat", ".java", ".yaml", ".yml", ".ini", ".xml", ".json", ".toml", ".cfg", ".txt", ".js",
        ".code-workspace", ".properties")


def tracked() -> list[Path]:
    out = subprocess.run(["git", "-C", str(ROOT), "ls-files", "-z"], capture_output=True, text=True).stdout.split("\0")
    return [ROOT / f for f in out if f]


def files() -> list[Path]:
    return [f for f in tracked() if f.is_file() and (f.suffix in TEXT or "Dockerfile" in f.name or f.name in IGNORE_FILES)
            and "vendor" not in f.parts and "testdata" not in f.parts]


def test_no_file_names_a_virtual_environment_but_as_an_ignore_pattern():
    found = []
    for f in files():
        if f.name in IGNORE_FILES:
            continue
        lines = f.read_text(encoding="utf-8", errors="replace").splitlines()
        for n, line in enumerate(lines):
            if NAME in line and "IGNORE pattern" not in line and (n == 0 or "IGNORE pattern" not in lines[n - 1]):
                found.append(f"{f.relative_to(ROOT)}:{n + 1}: {line.strip()[:120]}")
    assert found == [], "Drishti names no virtual environment (an ignore pattern says so on its line):\n" + "\n".join(found)


def test_no_code_makes_a_virtual_environment():
    found = [f"{f.relative_to(ROOT)}:{n}: {line.strip()[:120]}" for f in files() if f.name != Path(__file__).name
             for n, line in enumerate(f.read_text(encoding="utf-8", errors="replace").splitlines(), 1) if MAKES.search(line)]
    assert found == [], "Drishti makes no virtual environment:\n" + "\n".join(found)


def test_setup_and_start_hold_no_environment_machinery():
    import sys
    sys.path[:0] = [str(ROOT / "tools"), str(ROOT / "tools" / "launch")]
    import envsetup
    import procs
    assert not hasattr(envsetup, "make_" + "venv") and not hasattr(procs, "venv_python") and not hasattr(procs, "old_venv")
    for rel in ("tools/launch/consolestart.py", "tools/launch/serverstart.py", "tools/launch/clusterctl.py", "drishti-console/run_drishti_web.py"):
        text = (ROOT / rel).read_text(encoding="utf-8")
        assert "pylibs.install" not in text and '"pip", "install"' not in text, rel


def test_no_launcher_names_an_interpreter():
    launchers = [f for f in tracked() if f.suffix == ".xml" and f.parent.name == "runConfigurations"] + [ROOT / "drishti.code-workspace"]
    assert launchers and [str(f.relative_to(ROOT)) for f in launchers if NAME in f.read_text(encoding="utf-8")] == []
    for f in launchers:
        text = f.read_text(encoding="utf-8")
        if "PythonConfigurationType" in text:
            assert 'name="SDK_HOME" value=""' in text, f"{f.name}: the project interpreter the user chooses, no SDK path"
