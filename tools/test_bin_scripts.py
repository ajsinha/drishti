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
"""The start and stop scripts of bin/ (bin/README.md; docs/DEPLOYMENT_AND_QA_GUIDE.md section 6), as files: every script has its .sh
(Linux, macOS), .ps1 and .cmd (Windows) with the licence header; nothing stops a process by name; the .sh files keep to bash 3.2 and the
BSD userland (macOS), the .ps1 files to Windows PowerShell 5.1; the old tools/windows starters are gone (one way to start Drishti). That
they run (--help, a wrong option, a stale pid file): tools/test_bin_scripts_run.py.

    python3 -m pytest -q tools/test_bin_scripts.py
"""
from __future__ import annotations

import re
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
BIN = ROOT / "bin"
SCRIPTS = ("start-server", "stop-server", "start-console", "stop-console", "status", "start-all", "stop-all", "start-cluster", "stop-cluster", "drishti-node")
HEADER = ("# Project Drishti · Any data. Any domain. One grammar.", "Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.",
          "PROPRIETARY AND CONFIDENTIAL.", "See the LICENSE file in the root of this repository for the full terms.")
SH = sorted(BIN.glob("*.sh")) + [BIN / "lib" / "common.sh"]
PS1 = sorted(BIN.glob("*.ps1")) + [BIN / "lib" / "common.ps1"]
# bash 4+ only, or GNU only: Apple ships bash 3.2 and the BSD tools (each pattern on a line that is not a comment)
NOT_ON_MACOS = {
    r"\breadlink\s+-f\b": "readlink -f (GNU): cd \"$(dirname \"$0\")\" && pwd -P",
    r"\bsed\s+-i\b": "sed -i (GNU and BSD differ): no in-place edits",
    r"\bmapfile\b|\breadarray\b": "mapfile/readarray (bash 4)",
    r"\bdeclare\s+-A\b|\blocal\s+-A\b": "associative arrays (bash 4)",
    r"\$\{[A-Za-z_][A-Za-z0-9_]*(,,|\^\^)": "${x,,} / ${x^^} (bash 4)",
    r"&>>": "&>> (bash 4)",
    r"\bwait\s+-n\b": "wait -n (bash 4.3)",
    r"\bdate\s+[^|;]*--": "GNU date flags",
    r"\bgrep\s+-P\b": "grep -P (GNU)",
}
NAME_KILLS = re.compile(r"\b(pkill|killall|taskkill\s+/IM|Stop-Process\s+-Name|Get-Process\s+-Name)\b", re.I)


def code_lines(path: Path) -> list[tuple[int, str]]:
    return [(n, line) for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1) if not line.lstrip().startswith(("#", "rem "))]


@pytest.mark.static
def test_every_script_has_its_three_forms():
    for name in SCRIPTS:
        for ext in (".sh", ".ps1", ".cmd"):
            assert (BIN / f"{name}{ext}").is_file(), f"bin/{name}{ext} is missing"
    names = {p.stem for p in BIN.iterdir() if p.suffix in (".sh", ".ps1", ".cmd")}
    assert names == set(SCRIPTS), f"bin/ has scripts without all three forms, or unknown ones: {sorted(names ^ set(SCRIPTS))}"
    for p in BIN.glob("*.cmd"):
        text = p.read_text(encoding="utf-8")
        assert "-ExecutionPolicy Bypass -File \"%~dp0" + p.stem + ".ps1\" %*" in text, p.name


@pytest.mark.static
def test_the_header_and_the_shebang():
    for p in SH + PS1:
        text = p.read_text(encoding="utf-8")
        for line in HEADER:
            assert line in text, f"{p.relative_to(ROOT)}: the licence header"
    for p in BIN.glob("*.sh"):
        assert p.read_text(encoding="utf-8").startswith("#!/bin/sh\n" if p.stem == "drishti-node" else "#!/usr/bin/env bash\n"), p.name
        # drishti-node.sh is POSIX sh (server images): no pipefail there, and it reads exit codes itself, so set -u only
        assert ("set -u\n" if p.stem == "drishti-node" else "set -euo pipefail") in p.read_text(encoding="utf-8"), p.name
        assert p.stat().st_mode & 0o111, f"{p.name} is not executable (git update-index --chmod=+x)"
    for p in BIN.glob("*.cmd"):
        assert "Copyright (c) 2026 Ashutosh Sinha" in p.read_text(encoding="utf-8"), p.name


@pytest.mark.static
def test_nothing_stops_a_process_by_name():
    for p in SH + PS1 + sorted(BIN.glob("*.cmd")) + [BIN / "lib" / "cluster.py"]:
        for n, line in code_lines(p):
            assert not NAME_KILLS.search(line), f"{p.relative_to(ROOT)}:{n}: stops by name: {line.strip()}"


@pytest.mark.static
def test_the_shell_scripts_run_on_macos():
    for p in SH:
        for n, line in code_lines(p):
            for pattern, why in NOT_ON_MACOS.items():
                assert not re.search(pattern, line), f"{p.relative_to(ROOT)}:{n}: {why}: {line.strip()}"
        text = p.read_text(encoding="utf-8")
        if "hostname -I" in text:                       # Linux only: always behind a check that falls back
            assert "ip -4 route get" in text and "ifconfig" in text, p.name


@pytest.mark.static
def test_the_ps1_scripts_keep_to_windows_powershell_5():
    for p in PS1:
        for n, line in code_lines(p):
            assert " ?? " not in line and "?." not in line.replace("'?'", "").replace("-?", ""), f"{p.name}:{n}: PowerShell 7 only"
            assert not re.search(r"\)\s*&&\s*|\s&&\s", line), f"{p.name}:{n}: && is PowerShell 7 only"
            assert "MALLOC_ARENA_MAX" not in line, f"{p.name}:{n}: glibc only"


@pytest.mark.static
def test_the_old_windows_starters_are_gone():
    for old in ("tools/windows/start-server.ps1", "tools/windows/start-console.ps1"):
        assert not (ROOT / old).exists(), f"{old}: bin/ is the one way to start Drishti"
