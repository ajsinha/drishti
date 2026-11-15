"""
Project Drishti · Any data. Any domain. One grammar.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
All rights reserved.

PROPRIETARY AND CONFIDENTIAL.

This file is the confidential and proprietary property of Ashutosh Sinha.
Unauthorised copying, use, modification, distribution or disclosure of this
file, via any medium, is strictly prohibited except with the express prior
written permission of the copyright holder.

See the LICENSE file in the root of this repository for the full terms.
"""
import re
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]

pytestmark = pytest.mark.static

# No path of the product may depend on the folder a script or process is started in. A script under bin/, tools/ or deploy/ that
# names a repository folder by a relative path (config/..., tools/...) must first anchor itself on its own location
# (BASH_SOURCE or dirname "$0", $PSScriptRoot, %~dp0), or work on absolute container paths (/opt/drishti/...).
SCRIPT_SUFFIXES = (".sh", ".ps1", ".cmd", ".bat")
SCRIPT_DIRS = ("bin", "tools", "deploy")
REPO_FOLDERS = ("config", "data", "modules", "tools", "bin", "docs", "kernel", "lake", "plugins", "compute", "testing", "drishti-console")

# a repository folder written as a relative path: after a space, quote, '=', '(' or ':' (not after '/', '$VAR' or '}'), with / or \ after it
REL_PATH = re.compile(r"""(?:^|[\s"'=(:`])(?:\.[/\\])?(?:%s)[/\\]""" % "|".join(REPO_FOLDERS))
ANCHOR = re.compile(r"""BASH_SOURCE|dirname\s+"?\$\{?0|PSScriptRoot|%~dp0|MyInvocation\.MyCommand\.(?:Path|Definition)""")
ABSOLUTE_ROOT = re.compile(r"""^\s*[A-Za-z_]+=["']?/opt/""", re.M)   # D=/opt/drishti: a container's fixed absolute root

# Not anchored on purpose, each with its reason (path from the repository root). Empty today: every script anchors itself.
ALLOWED: dict[str, str] = {}


def _is_comment(line: str, suffix: str) -> bool:
    s = line.lstrip()
    if suffix in (".cmd", ".bat"):
        return s.upper().startswith("REM ") or s.startswith("::")
    return s.startswith("#")


def needs_anchor(text: str, suffix: str) -> bool:
    """True when the script names a repository folder by a relative path in a line that is not a comment."""
    if suffix == ".ps1":
        text = re.sub(r"<#.*?#>", "", text, flags=re.S)   # the comment-based help block is documentation, not a use
    return any(REL_PATH.search(line) for line in text.splitlines() if not _is_comment(line, suffix))


def is_anchored(text: str) -> bool:
    return bool(ANCHOR.search(text) or ABSOLUTE_ROOT.search(text))


def violates(text: str, suffix: str) -> bool:
    return needs_anchor(text, suffix) and not is_anchored(text)


def _tracked(*names: str) -> list[Path]:
    # a walk of the folders (a static test starts no process); build output and environments are not scripts of the product
    files = [p for d in SCRIPT_DIRS for p in (ROOT / d).rglob("*") if p.is_file()]
    skip = ("node_modules", ".venv", "/testdata/", "/fixtures/", "/target/", "/__pycache__/", "/cache/")
    return [p for p in files if p.is_file() and not any(s in p.as_posix() for s in skip) and any(p.name.endswith(x) for x in names)]


def test_every_script_that_names_a_repository_folder_anchors_itself():
    offenders = []
    for f in _tracked(*SCRIPT_SUFFIXES):
        rel = f.relative_to(ROOT).as_posix()
        if rel in ALLOWED:
            continue
        if violates(f.read_text(encoding="utf-8", errors="replace"), f.suffix):
            offenders.append(rel)
    assert not offenders, "scripts that use a repository path without anchoring on their own folder " \
        "(BASH_SOURCE / dirname \"$0\", $PSScriptRoot, %~dp0):\n" + "\n".join(offenders)


def test_allowed_entries_exist():
    for rel in ALLOWED:
        assert (ROOT / rel).is_file(), f"ALLOWED names a file that is gone: {rel}"


def test_server_images_set_the_launch_root():
    images = _tracked("server.Dockerfile")
    assert images, "no server Dockerfile found"
    for f in images:
        assert re.search(r"^\s*(?:ENV\s+)?DRISHTI_HOME=/opt/", f.read_text(encoding="utf-8"), re.M), \
            f"{f.relative_to(ROOT)} does not set DRISHTI_HOME to an absolute container path"


def test_systemd_unit_sets_the_launch_roots():
    units = list((ROOT / "deploy" / "systemd").glob("*.service"))
    assert units, "no systemd unit found"
    for f in units:
        text = f.read_text(encoding="utf-8")
        assert re.search(r"^Environment=DRISHTI_HOME=/", text, re.M), f"{f.name} does not set DRISHTI_HOME"
        assert re.search(r"^Environment=DRISHTI_DATA_DIR=/", text, re.M), f"{f.name} does not set DRISHTI_DATA_DIR"


@pytest.mark.parametrize("text,suffix,expected", [
    ('cd "$(dirname "$0")/.."\nls config/packs\n', ".sh", False),                                 # anchored by dirname $0
    ('ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"\nls tools/x.py\n', ".sh", False),   # anchored by BASH_SOURCE
    ('ls config/packs\n', ".sh", True),                                                           # relative, no anchor
    ('# see config/packs in the docs\necho hi\n', ".sh", False),                                  # a comment is not a use
    ('D=/opt/drishti\nls "$D/config/net"\n', ".sh", False),                                       # a fixed absolute root; "$D/config" is not relative
    ('python "${ROOT}/tools/x.py"\n', ".sh", False),                                              # after a variable: not relative
    ('Set-Location $repo\n& .\\tools\\x.ps1\n', ".ps1", True),                                    # PowerShell relative
    ('$repo = Join-Path $PSScriptRoot "..\\.."\nGet-Content config\\a.yaml\n', ".ps1", False),     # anchored by PSScriptRoot
    ('<#\n.SYNOPSIS\n  see tools/x.sh\n#>\nparam()\n', ".ps1", False),                                  # a help block is documentation
    ('REM tools\\x.cmd\ncall tools\\y.cmd\n', ".cmd", True),                                      # cmd relative without %~dp0
    ('cd /d "%~dp0.."\ncall tools\\y.cmd\n', ".cmd", False),                                      # anchored by %~dp0
])
def test_detection_self_check(text, suffix, expected):
    assert violates(text, suffix) is expected
