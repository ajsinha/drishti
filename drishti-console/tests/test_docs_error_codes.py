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

"""The error-code lists in the docs match the code (DOC-09, DOC-11, DOC-12).

API_GUIDE's table lists every code of drishti-common's ErrorCode and nothing the code lacks; TROUBLESHOOTING's table
covers every code; RACHANA_REFERENCE's problem table lists every DRS-20xx code the Rachana parser can report.
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CODE = re.compile(r"DRS-\d{4}")
CONSOLE_ONLY = {"DRS-5003"}  # raised by the console, not by the server (documented as such)


def _read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def _section(text: str, heading: str) -> str:
    start = text.index(heading)
    end = text.find("\n## ", start + len(heading))
    return text[start:end if end > 0 else len(text)]


def _expand(cell: str) -> set[str]:
    """DRS-6001–DRS-6010 in a table cell means every code from the first to the last."""
    codes = set(CODE.findall(cell))
    for a, b in re.findall(r"DRS-(\d{4})`?\s*[–-]\s*`?DRS-(\d{4})", cell):
        codes |= {f"DRS-{n}" for n in range(int(a), int(b) + 1)}
    return codes


def _server_codes() -> set[str]:
    return set(CODE.findall(_read("drishti-common/src/main/java/com/ash/drishti/common/ErrorCode.java")))


def _parser_codes() -> set[str]:
    found: set[str] = set()
    for path in (ROOT / "drishti-rachana/src/main").rglob("*.java"):
        found |= set(CODE.findall(path.read_text(encoding="utf-8")))
    return {c for c in found if c.startswith("DRS-20") and c not in ("DRS-2101", "DRS-2102")}


def _first_column_codes(section: str) -> set[str]:
    codes: set[str] = set()
    for line in section.splitlines():
        if line.startswith("|"):
            codes |= _expand(line.split("|")[1])
    return codes


def test_api_guide_lists_every_error_code_and_no_other():
    documented = _first_column_codes(_section(_read("docs/guides/API_GUIDE.md"), "## Error code table"))
    assert _server_codes() - documented == set(), "ErrorCode codes missing from API_GUIDE's table"
    assert documented - _server_codes() == set(), "API_GUIDE's table lists codes ErrorCode does not have"


def test_troubleshooting_covers_every_error_code():
    covered = _first_column_codes(_section(_read("docs/guides/TROUBLESHOOTING.md"), "## Error codes"))
    missing = (_server_codes() | CONSOLE_ONLY | _parser_codes()) - covered
    assert not missing, f"TROUBLESHOOTING's error-code table lacks {sorted(missing)}"


def test_rachana_reference_lists_every_problem_code_the_parser_reports():
    documented = _first_column_codes(_section(_read("docs/guides/RACHANA_REFERENCE.md"), "## Problem codes"))
    assert _parser_codes() - documented == set(), "parser problem codes missing from RACHANA_REFERENCE"
    grammar = sorted(c for c in _parser_codes() if c not in ("DRS-2001", "DRS-2004", "DRS-2009", "DRS-2032", "DRS-2033")
                     and not "DRS-2040" <= c <= "DRS-2049")      # the pack about-file family has its own rows
    assert f"`{grammar[0]}` to `{grammar[-1]}`" in _read("docs/guides/RACHANA_REFERENCE.md"), \
        "the grammar problem-code range in RACHANA_REFERENCE is out of date"
