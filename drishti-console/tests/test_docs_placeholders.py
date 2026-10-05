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

"""Every ${NAME:default} placeholder of the two application.yaml files is in docs/admin/CONFIGURATION.md, with its default."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PLACEHOLDER = re.compile(r"\$\{([A-Za-z0-9_]+)(?::([^}]*))?\}")
DOC = (ROOT / "docs" / "admin" / "CONFIGURATION.md").read_text(encoding="utf-8")


def _live_placeholders(path: Path) -> list[tuple[str, str | None]]:
    found = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.lstrip().startswith("#"):
            continue
        code = re.sub(r"(^|\s)#.*$", "", line)
        found += [(m.group(1), m.group(2)) for m in PLACEHOLDER.finditer(code)]
    return found


def _section(title: str) -> str:
    start = DOC.index(title)
    end = DOC.find("\n#", start + len(title))
    return DOC[start:end]


def _check(path: Path, title: str) -> None:
    section = _section(title)
    missing = []
    for name, default in _live_placeholders(path):
        want = "none" if default is None else ("empty" if default == "" else f"`{default}`")
        if not re.search(rf"\| `{name}` \|[^\n]*\| {re.escape(want)} \|", section):
            missing.append(f"{name} default {want}")
    assert not missing, f"{title}: missing or wrong in CONFIGURATION.md: {missing}"


def test_server_placeholders_are_documented():
    _check(ROOT / "drishti-server/src/main/resources/application.yaml", "### Every placeholder in the server's")


def test_console_placeholders_are_documented():
    _check(ROOT / "drishti-console/config/application.yaml", "### Every placeholder in the console's")
