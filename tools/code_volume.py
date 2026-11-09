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

"""Code volume per release, read from git and never typed by hand: lines of Java main and test, console Python and console JS, for every
tag from v2.9.11.d on and the current commit. Run in each release's preparation; the table lives in docs/admin/BENCHMARK.md.

    python3 tools/code_volume.py            # print the table
    python3 tools/code_volume.py --write    # and replace it in BENCHMARK.md
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOC = ROOT / "docs" / "admin" / "BENCHMARK.md"
FIRST_TAG = "v2.9.11.d"
COLUMNS = ("java main", "java test", "console python", "console js")
BEGIN = "<!-- code-volume:begin (tools/code_volume.py --write) -->"
END = "<!-- code-volume:end -->"
NOT_CONSOLE_JS = re.compile(r"/static/vendor/|/static/render/1\.2\.0/|/web/embed/|\.min\.js$")


def category(path: str) -> str | None:
    """The column a repository-relative file counts in, or None."""
    if path.endswith(".java") and "/src/main/" in path:
        return "java main"
    if path.endswith(".java") and "/src/test/" in path:
        return "java test"
    if path.startswith("drishti-console/") and "/tests/" not in path:
        if path.endswith(".py"):
            return "console python"
        if path.endswith((".js", ".mjs")) and not NOT_CONSOLE_JS.search(path):
            return "console js"
    return None


def git(*args: str, data: bytes | None = None) -> bytes:
    """Runs git at the repository root; its standard output."""
    return subprocess.run(["git", *args], cwd=ROOT, input=data, check=True, capture_output=True).stdout


def volume(ref: str) -> dict[str, int]:
    """Lines per column at a tag or commit, from git's objects."""
    objects: list[tuple[str, str]] = []
    for line in git("ls-tree", "-r", ref).decode("utf-8", "surrogateescape").splitlines():
        meta, path = line.split("\t", 1)
        col = category(path)
        if col is not None:
            objects.append((meta.split()[2], col))
    out = git("cat-file", "--batch", data="".join(f"{sha}\n" for sha, _ in objects).encode())
    totals = dict.fromkeys(COLUMNS, 0)
    pos = 0
    for _, col in objects:
        eol = out.index(b"\n", pos)
        size = int(out[pos:eol].split()[2])
        body = out[eol + 1:eol + 1 + size]
        totals[col] += body.count(b"\n") + (1 if body and not body.endswith(b"\n") else 0)
        pos = eol + 1 + size + 1
    return totals


def releases() -> list[str]:
    """The tags from FIRST_TAG on, oldest first."""
    tags = git("tag", "--sort=creatordate").decode().split()
    return tags[tags.index(FIRST_TAG):]


def table() -> str:
    """The Markdown table: one row per release and one for the current commit."""
    rows = ["| Release | Date | " + " | ".join(c.capitalize() for c in COLUMNS) + " |", "|---|---|" + "---:|" * len(COLUMNS)]
    for ref, name in [*((t, t) for t in releases()), ("HEAD", "current")]:
        date = git("log", "-1", "--format=%cs", ref).decode().strip()
        v = volume(ref)
        rows.append(f"| {name} | {date} | " + " | ".join(f"{v[c]:,}" for c in COLUMNS) + " |")
    return "\n".join(rows)


def replaced(doc: str, tbl: str) -> str:
    """The document with the table between the markers replaced."""
    head, _, rest = doc.partition(BEGIN)
    _, _, tail = rest.partition(END)
    if not rest or END not in rest:
        raise ValueError("BENCHMARK.md has no code-volume markers")
    return f"{head}{BEGIN}\n{tbl}\n{END}{tail}"


def main() -> int:
    """Command line: see the module's docstring."""
    ap = argparse.ArgumentParser(description="code volume per release, from git")
    ap.add_argument("--write", action="store_true", help="replace the table in docs/admin/BENCHMARK.md")
    a = ap.parse_args()
    tbl = table()
    print(tbl)
    if a.write:
        DOC.write_text(replaced(DOC.read_text(encoding="utf-8"), tbl), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
