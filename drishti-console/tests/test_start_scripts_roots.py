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

"""The start scripts' root handling, by reading them (a quick file check: no PowerShell, no process): the install folder is the one the
scripts sit in, the server gets it and its modules folder as absolute settings, and the working folder is used only for a relative path
the person typed. The scripts run from another folder in the drill's anywhere lane (test_release_from_other_folder.py)."""
from __future__ import annotations

import re
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]


@pytest.mark.static
def test_ps1_roots():
    """The Windows scripts' root handling, by reading them (no PowerShell here): the install folder is the one the scripts sit in, the server
    gets it and its modules folder as absolute settings, and the working folder is used only for a relative path the person typed."""
    common = (ROOT / "bin" / "lib" / "common.ps1").read_text(encoding="utf-8")
    start = (ROOT / "bin" / "start-server.ps1").read_text(encoding="utf-8")
    assert re.search(r"\$script:DrishtiRoot = \(Resolve-Path \(Join-Path \$PSScriptRoot '\.\.'\)\)\.Path", common), "DrishtiRoot is not from the script's own folder"
    assert "$env:DRISHTI_HOME = $script:DrishtiRoot" in start, "start-server.ps1 does not pass DRISHTI_HOME"
    assert "$env:DRISHTI_MODULES_HOME = Join-Path $env:DRISHTI_HOME 'modules'" in start, "start-server.ps1 does not pass DRISHTI_MODULES_HOME"
    uses = [ln.strip() for p in ("bin/lib/common.ps1", "bin/start-server.ps1", "bin/start-console.ps1")
            for ln in (ROOT / p).read_text(encoding="utf-8").splitlines() if "Get-Location" in ln]
    assert all("IsPathRooted" in u for u in uses), f"the working folder anchors something but a relative path the person typed: {uses}"
    sh = (ROOT / "bin" / "start-server.sh").read_text(encoding="utf-8")
    assert 'export DRISHTI_HOME="${DRISHTI_HOME:-$DRISHTI_ROOT}"' in sh and "DRISHTI_MODULES_HOME" in sh
