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

"""Every ${NAME:default} placeholder of the two application.yaml files is in docs/admin/CONFIGURATION.md's generated reference
(tools/settings_catalogue.py): a row whose Env var column names the variable, with its default."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PLACEHOLDER = re.compile(r"\$\{([A-Za-z0-9_]+)(?::((?:[^{}]|\$\{[^}]*\})*))?\}")   # one level of nesting: ${X:${drishti.data.dir}/y}
DATA_DIR = re.compile(r"\$\{drishti\.data\.dir(?::[^}]*)?\}")                        # documented as its default, ./data (CONFIGURATION.md, "The data folder")
DOC = (ROOT / "docs" / "admin" / "CONFIGURATION.md").read_text(encoding="utf-8")


def _live_placeholders(path: Path) -> list[tuple[str, str | None]]:
    found = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.lstrip().startswith("#"):
            continue
        code = re.sub(r"(^|\s)#.*$", "", line)
        for m in PLACEHOLDER.finditer(code):
            if not m.group(1).isupper():
                continue                                       # ${drishti.logging.file:}: a setting, not a variable
            found.append((m.group(1), None if m.group(2) is None else DATA_DIR.sub("./data", m.group(2))))
    return found


def _section(title: str) -> str:
    start = DOC.index(title)
    end = DOC.find("\n### ", start + len(title))
    return DOC[start:end]


def _check(path: Path, title: str) -> None:
    section = _section(title)
    missing = []
    for name, default in _live_placeholders(path):
        if "${" in (default or ""):
            want = None                                        # a nested default (${A:${B:x}}): the row names both variables
        else:
            want = "none" if default is None else ("empty" if default == "" else f"`{default}`")
        rows = [r for r in section.splitlines() if r.startswith("| `") and f"`{name}`" in r.split(" | ")[3]]
        if not rows or (want and not any(f" | {want} | " in r for r in rows)):
            missing.append(f"{name} default {want}")
    assert not missing, f"{title}: missing or wrong in CONFIGURATION.md: {missing}"


def test_server_placeholders_are_documented():
    _check(ROOT / "drishti-server/src/main/resources/application.yaml", "### The server: `application.yaml`")


def test_console_placeholders_are_documented():
    _check(ROOT / "drishti-console/config/application.yaml", "### The console: `application.yaml`")
