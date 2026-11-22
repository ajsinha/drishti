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
"""No tracked text file holds a git conflict marker (a merge left two in CLI_REFERENCE and RACHANA_REFERENCE once).

A conflict is a line starting ``<<<<<<< ``, later a line starting ``>>>>>>> ``, with a line that is exactly ``=======`` between them.
Pure Python over ``git ls-files``; binary files and the data folders are skipped."""
from __future__ import annotations

import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SKIP_DIRS = ("data/", "node_modules/", "/vendor/")
MAX_BYTES = 5_000_000
OPEN, MID, CLOSE = re.compile(r"^<{7}( |$)"), re.compile(r"^={7}$"), re.compile(r"^>{7}( |$)")


def markers(text: str) -> list[int]:
    """Line numbers (1-based) where a conflict block opens: an open marker followed by a lone ======= and a close marker."""
    found, state, start = [], 0, 0
    for n, line in enumerate(text.splitlines(), 1):
        if state == 0 and OPEN.match(line):
            state, start = 1, n
        elif state == 1 and MID.match(line):
            state = 2
        elif state == 2 and CLOSE.match(line):
            found.append(start)
            state = 0
    return found


def test_the_detector_finds_a_conflict_and_ignores_a_heading_rule():
    assert markers("a\n<<<<<<< HEAD\nx\n=======\ny\n>>>>>>> other\nb\n") == [2]
    assert markers("Title\n=======\ntext\n") == []                    # a setext heading underline alone is no conflict
    assert markers("<<<<<<< HEAD\nonly an opener\n") == []


def test_no_tracked_text_file_has_a_conflict_marker():
    files = subprocess.run(["git", "ls-files", "-z"], cwd=ROOT, capture_output=True, check=True).stdout.decode("utf-8", "replace").split("\0")
    bad = []
    for rel in filter(None, files):
        if rel.startswith(SKIP_DIRS[0]) or any(s in "/" + rel for s in SKIP_DIRS[1:]):
            continue
        p = ROOT / rel
        if not p.is_file() or p.is_symlink() or p.stat().st_size > MAX_BYTES:
            continue
        raw = p.read_bytes()
        if b"\0" in raw[:4096]:
            continue
        bad += [f"{rel}:{n}" for n in markers(raw.decode("utf-8", "replace"))]
    assert not bad, "git conflict markers left in tracked files:\n" + "\n".join(bad)
