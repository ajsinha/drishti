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

"""Proves that nothing was lost in a merge (i7k, plan section 5): every heading and every fenced block of a merged source document is
in a kept document, or in the lane's log as "dropped as a duplicate of <place>".

python3 tools/docs/merge_audit.py [--lane A|B|C] [--source REF] [--log FILE] [--show N]

The sources are read from `REF` (default: the tag v2.9.11.i_7j) with `git show`; the targets are the working tree. Rows come from
tools/docs/moved.yaml with how merge, rename or move; a deletion is outside the audit (the plan lists them). A heading is found when a
heading of the same words exists in the row's new home, else in any other tracked Markdown document (reported as "elsewhere"). A fenced
block is found when its whitespace-normalised text is a substring of any kept document. The log has one line per dropped item:
`<old path> | <heading or first line of the block> | <the place that already says it>`.
Exit 0 when every item is found or logged, else 1 (a report of the first --show items follows).
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import moved  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_REF = "v2.9.11.i_7j"
HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*#*\s*$")


def squash(s: str) -> str:
    return re.sub(r"\s+", " ", s).strip()


def head_key(h: str) -> str:
    """The heading's words; a leading section number is left out (i7m renumbers the appended parts of a document, plan section 5)."""
    h = re.sub(r"^\d+(\.\d+)*\.?\s+", "", h)
    return re.sub(r"[^a-z0-9]+", " ", re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", h).lower()).strip()


def items(text: str) -> tuple[list[str], list[str]]:
    """(headings outside fences, fenced blocks) of a document."""
    heads, blocks, cur, fence = [], [], [], False
    for line in text.splitlines():
        if line.lstrip().startswith("```"):
            if fence:
                blocks.append("\n".join(cur))
                cur = []
            fence = not fence
        elif fence:
            cur.append(line)
        else:
            m = HEADING.match(line)
            if m and m.group(2):
                heads.append(m.group(2))
    return heads, [b for b in blocks if squash(b)]


def parse_log(text: str) -> set[tuple[str, str]]:
    out = set()
    for line in text.splitlines():
        parts = [p.strip() for p in line.split("|")]
        if len(parts) >= 3 and parts[0] and not parts[0].startswith("#"):
            out.add((parts[0], head_key(parts[1]) if not parts[1].startswith("```") else squash(parts[1]).strip("`")))
    return out


def audit(sources: dict[str, str], homes: dict[str, str], docs: dict[str, str], log: set[tuple[str, str]] = frozenset()) -> list[str]:
    """Problems: sources = old path -> old text; homes = old path -> new home path; docs = every kept document path -> text."""
    dh = {p: {head_key(h) for h in items(t)[0]} for p, t in docs.items()}
    flat = "\n".join(squash(t) for t in docs.values())
    bad = []
    for old, text in sources.items():
        home = homes.get(old, "")
        heads, blocks = items(text)
        for h in heads:
            k = head_key(h)
            if not k or (old, k) in log:
                continue
            if k in dh.get(home, ()):
                continue
            where = "elsewhere" if any(k in v for v in dh.values()) else "missing"
            if where == "missing":
                bad.append(f"{old}: heading '{h}' not found (merge target {home or '?'})")
        for b in blocks:
            sb = squash(b)
            first = squash(b.splitlines()[0]) if b.strip() else ""
            if (old, sb) in log or (old, first) in log or (old, head_key(first)) in log:
                continue
            if sb not in flat:
                bad.append(f"{old}: fenced block '{first[:70]}' not found (merge target {home or '?'})")
    return bad


#: code folders moved after the reference tag (the loaders under tools/loaders/): a source document's commands name the new place, as the merged text does
CODE_MOVES = (("tools/mrloader/", "tools/loaders/mrloader/"), ("tools\\mrloader\\", "tools\\loaders\\mrloader\\"))


def git_show(ref: str, path: str) -> str | None:
    r = subprocess.run(["git", "-C", str(ROOT), "show", f"{ref}:{path}"], capture_output=True, text=True)
    if r.returncode != 0:
        return None
    out = r.stdout
    for old, new in CODE_MOVES:
        out = out.replace(old, new)
    return out


def main(argv: list[str]) -> int:
    arg = lambda k, d: argv[argv.index(k) + 1] if k in argv else d
    ref, lane, show = arg("--source", DEFAULT_REF), arg("--lane", ""), int(arg("--show", "10"))
    import corpus  # noqa: E402
    lanes = {p: l for p, _c, l, _t, _x in [(r[0],) + r[1:] for r in corpus.build()[0]]}
    rows = [r for r in moved.load() if r.get("how") in ("merge", "rename", "move") and (not lane or lanes.get(r["from"]) == lane)]
    sources = {r["from"]: t for r in rows if (t := git_show(ref, r["from"])) is not None}
    homes = {r["from"]: r.get("to") or "" for r in rows}
    docs = {}
    for p in corpus.tracked():
        f = ROOT / p
        if f.exists() and not any(p == r["from"] for r in rows if not (ROOT / r["from"]).exists()):
            docs[p] = f.read_text(encoding="utf-8")
    # a source that still exists in the tree (not yet merged) is not its own proof
    for old in sources:
        docs.pop(old, None)
    log: set = set()
    for i, a in enumerate(argv):                                   # --log may be given once per merge log (lane A, lane B)
        if a == "--log" and i + 1 < len(argv) and Path(argv[i + 1]).exists():
            log |= parse_log(Path(argv[i + 1]).read_text(encoding="utf-8"))
    bad = audit(sources, homes, docs, log)
    for b in bad[:show]:
        print("  ", b)
    print(f"[{'FAIL' if bad else 'OK'}] {len(sources)} merged sources audited, {len(bad)} items not found")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
