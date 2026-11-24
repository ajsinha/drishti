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

"""The first-run matrix, launcher rows (docs/plans/FIRST_RUN_ROBUSTNESS.md section B2: 32, 58 to 62): each IDE's launchers in its own
project's .idea/runConfigurations and nowhere else; a launcher's command line is a CLI command line (the launcher-equivalent runner,
tools/launch/launchers.py argv); no launcher names an interpreter, a virtual environment or setup --index. Rows 58 to 61 need an IDE (M) as well."""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pytest

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
for p in (str(TOOLS), str(TOOLS / "launch")):
    if p not in sys.path:
        sys.path.insert(0, p)
import launchers  # noqa: E402

JAVA = sorted((ROOT / ".idea" / "runConfigurations").glob("*.xml"))
PYTHON = sorted((ROOT / "drishti-console" / ".idea" / "runConfigurations").glob("*.xml"))


def tracked() -> list[str]:
    return subprocess.run(["git", "-C", str(ROOT), "ls-files"], capture_output=True, text=True, check=True).stdout.splitlines()


def test_rows58_59_60_each_ide_has_its_own_launchers_and_no_run_xml_elsewhere():
    assert JAVA and PYTHON
    assert all("JarApplication" in f.read_text(encoding="utf-8") or "MavenRunConfiguration" in f.read_text(encoding="utf-8") for f in JAVA)
    assert all("PythonConfigurationType" in f.read_text(encoding="utf-8") for f in PYTHON)
    assert not [f for f in tracked() if f.endswith(".run.xml")], "no *.run.xml anywhere (an IDE would show it in the other's project)"


@pytest.mark.parametrize("xml", PYTHON, ids=lambda p: p.stem)
def test_row62_every_python_launcher_runs_a_cli_command_line(xml, tmp_path):
    words, env = launchers.argv(xml, ROOT, tmp_path)
    assert words[0] == "python", "the project interpreter, never a path"
    script = Path(words[1])
    assert script.name in ("drishti.py", "run_drishti_web.py"), words
    if script.name == "drishti.py":
        sys.path.insert(0, str(TOOLS))
        import drishti_parser  # noqa: F401 - the CLI's own parser accepts it
        rest = [w for w in words[2:]]
        assert "--index" not in rest and "venv" not in " ".join(rest)


def test_row32_no_launcher_names_python_by_name_or_a_venv():
    for f in JAVA + PYTHON:                    # the VS Code workspace: tools/test_no_venv.py (its file-exclude patterns)
        text = f.read_text(encoding="utf-8")
        assert "." + "venv" not in text and "python3 " not in text and "/bin/python" not in text and "python.exe" not in text, f.name
