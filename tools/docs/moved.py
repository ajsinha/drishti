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

"""The one rename table of the documentation consolidation (i7k): `tools/docs/moved.yaml`, old path -> new home.

python3 tools/docs/moved.py --lookup docs/architecture/ACCESS_MODEL.md   where it went (or "deleted")
python3 tools/docs/moved.py --hint <old-path>                            one line for a link checker's message
python3 tools/docs/moved.py --apply [--write]                            the sweep of non-Markdown files (code comments, tests, YAML):
                                                                         counts per file; --write rewrites them
python3 tools/docs/moved.py --verify [--base REF]                        every changed line of a non-Markdown file since REF is a comment
                                                                         line or sits in a test file (the plan's "comment lines only")

The sweep replaces the full repository path of a document that was merged, renamed or moved by its new home, and a bare file name
`NAME.md` only when it is an upper-case name that is unique among the moved documents. A deleted document has no new home and is
never rewritten (a code comment that names one is the lane's to fix by hand: --apply lists them).
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
MOVED = ROOT / "tools" / "docs" / "moved.yaml"
COMMENT = re.compile(r"^\s*(#|//|\*|/\*|<!--|-->|--|;|rem\b)", re.I)
SKIP = {"tools/docs/moved.yaml", "tools/docs/moved.py", "tools/docs/corpus.py", "config/docs-corpus.yaml", "config/docs-owners.yaml",
        "tools/docs/dup_check.py", "tools/docs/merge_audit.py", "tools/docs/merge_log_B.txt", "tools/test_docs_consolidation.py"}
TEXT_SUFFIXES = {".java", ".py", ".yaml", ".yml", ".xml", ".js", ".html", ".css", ".sh", ".ps1", ".properties", ".json", ".txt", ".toml"}


def load(path: Path = MOVED) -> list[dict]:
    return (yaml.safe_load(path.read_text(encoding="utf-8")) or {}).get("moved") or []


def table(rows: list[dict] | None = None) -> dict[str, str]:
    """old path -> new home ('' for a deletion)."""
    return {r["from"]: (r.get("to") or "") for r in (load() if rows is None else rows)}


def hint(old: str, rows: list[dict] | None = None) -> str:
    t = table(rows)
    if old not in t:
        return ""
    return f"{old} was merged into {t[old]}" if t[old] else f"{old} was deleted"


def rewrites(rows: list[dict] | None = None) -> dict[str, str]:
    """what a sweep may replace: full path -> new path, plus the unique upper-case bare names."""
    out: dict[str, str] = {}
    names: dict[str, list[str]] = {}
    for old, new in table(rows).items():
        if not new or not new.endswith(".md"):
            continue
        out[old] = new
        base = old.rsplit("/", 1)[-1]
        if re.fullmatch(r"[A-Z][A-Z0-9_]{5,}\.md", base):
            names.setdefault(base, []).append(old)
    for base, olds in names.items():
        if len(olds) == 1:
            out.setdefault(base, out[olds[0]].rsplit("/", 1)[-1])
    return out


def sweep(files: dict[str, str], rows: list[dict] | None = None) -> tuple[dict[str, str], list[str]]:
    """(rewritten texts of the files that change, report lines for names of deleted documents)."""
    rw = rewrites(rows)
    gone = [o for o, n in table(rows).items() if not n]
    pat = re.compile("|".join(re.escape(k) for k in sorted(rw, key=len, reverse=True))) if rw else None
    changed, left = {}, []
    for name, text in files.items():
        new = pat.sub(lambda m: rw[m.group(0)], text) if pat else text
        if new != text:
            changed[name] = new
        for g in gone:
            if g in text:
                left.append(f"{name}: names the deleted {g}")
    return changed, left


def non_markdown() -> list[str]:
    out = subprocess.run(["git", "-C", str(ROOT), "ls-files"], check=True, capture_output=True, text=True).stdout.splitlines()
    return [f for f in out if Path(f).suffix in TEXT_SUFFIXES and f not in SKIP
            and not f.startswith("drishti-console/web/static/vendor/")]


def verify_diff(diff: str) -> list[str]:
    """Problems in a unified diff of non-Markdown files: a changed line that is neither a comment nor in a test file."""
    bad, cur = [], ""
    for line in diff.splitlines():
        if line.startswith("+++ "):
            cur = line[6:] if line.startswith("+++ b/") else line[4:]
        elif line[:1] in "+-" and not line.startswith(("+++", "---")):
            test = bool(re.search(r"(^|/)(test_[^/]*|[^/]*Test\.java|tests?/.*)$", cur))
            if not test and not COMMENT.match(line[1:]):
                bad.append(f"{cur}: {line[:110]}")
    return bad


def main(argv: list[str]) -> int:
    if "--lookup" in argv or "--hint" in argv:
        p = argv[-1]
        t = table()
        if p not in t:
            print(f"{p}: not in moved.yaml")
            return 1
        print(hint(p))
        return 0
    if "--apply" in argv:
        files = {}
        for f in non_markdown():
            try:
                files[f] = (ROOT / f).read_text(encoding="utf-8")
            except (OSError, UnicodeDecodeError):
                pass
        changed, left = sweep(files)
        for f in sorted(changed):
            print(f"{'rewrite' if '--write' in argv else 'would rewrite'} {f}")
            if "--write" in argv:
                (ROOT / f).write_text(changed[f], encoding="utf-8", newline="")
        print(*left, sep="\n")
        print(f"{len(changed)} files, {len(left)} names of deleted documents left for the lane")
        return 0
    if "--verify" in argv:
        base = argv[argv.index("--base") + 1] if "--base" in argv else "HEAD"
        diff = subprocess.run(["git", "-C", str(ROOT), "diff", "-U0", base, "--", *[f for f in non_markdown()]],
                              check=True, capture_output=True, text=True).stdout
        bad = verify_diff(diff)
        print(*bad, sep="\n")
        print(f"[{'FAIL' if bad else 'OK'}] {len(bad)} changed non-comment lines outside tests")
        return 1 if bad else 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
