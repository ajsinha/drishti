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

"""Numbering repair for a document that holds several numbered series (i7n, plan section 5 of DOCS_I7M_PLAN).

python3 tools/docs/renumber.py FILE [FILE...] [--prefix 'heading text=N' ...] [--write]
    A document is split into parts at its level-2 headings. The first part that uses a number keeps it; a later part whose numbered
    headings (level 3 and below) repeat a number already used gets its part number as a prefix: `### 4. The ask` under `## 4. Reactive
    connectors` becomes `### 4.4 The ask`, `#### 6.1 The request` becomes `#### 4.6.1 The request`. A part with no number of its own
    (a generated kind section) takes the prefix given by --prefix. The report lists every heading changed and the anchor rows
    (old slug -> new slug) it appends below the marker line of tools/docs/moved.yaml.
python3 tools/docs/renumber.py --links [--write]
    rewrites every Markdown link `FILE.md#old-slug` (and `#old-slug` inside the file) that an anchor row of moved.yaml renamed.
    Run it again after a merge: a link that arrived later is followed too.
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
MOVED = ROOT / "tools" / "docs" / "moved.yaml"
NUMBERED = re.compile(r"^(#{2,6}) (\d+(?:\.\d+)*)(\.?) (.*)$")
FENCE = re.compile(r"^\s*```")


def slug(text: str) -> str:
    """The GitHub heading slug (without the -1 suffix of a repeated one)."""
    t = re.sub(r"[`*_]|\\", "", text).strip().lower()
    t = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", t)
    t = re.sub(r"[^\w\- ]", "", t, flags=re.UNICODE)
    return t.replace(" ", "-")


def headings(lines: list[str]) -> list[tuple[int, int, str]]:
    """(line index, level, text after the hashes) of every heading outside a code fence."""
    out, fence = [], False
    for i, line in enumerate(lines):
        if FENCE.match(line):
            fence = not fence
        elif not fence:
            m = re.match(r"^(#{1,6}) (.*\S)\s*$", line)
            if m:
                out.append((i, len(m.group(1)), m.group(2)))
    return out


def effective_slugs(hs: list[tuple[int, int, str]]) -> list[str]:
    seen: dict[str, int] = {}
    out = []
    for _, _, text in hs:
        s = slug(text)
        n = seen.get(s, 0)
        seen[s] = n + 1
        out.append(s if n == 0 else f"{s}-{n}")
    return out


def plan(lines: list[str], prefixes: dict[str, str]) -> dict[int, str]:
    """line index -> new heading line, for the parts that repeat a number."""
    hs = headings(lines)
    parts: list[list[tuple[int, int, str]]] = []
    for h in hs:
        if h[1] == 2 or not parts:
            parts.append([])
        parts[-1].append(h)
    used: set[tuple[int, str]] = set()
    edits: dict[int, str] = {}
    for part in parts:
        head = part[0]
        mh = NUMBERED.match("## " + head[2]) if head[1] == 2 else None
        pref = prefixes.get(head[2]) or (mh.group(2) if mh else None)
        numbered = [(i, NUMBERED.match(lines[i])) for i, lvl, _ in part if lvl >= 3 and NUMBERED.match(lines[i])]
        clash = any((len(m.group(1)), m.group(2)) in used for _, m in numbered)
        if clash and pref:
            for i, m in numbered:
                num = f"{pref}.{m.group(2)}"
                edits[i] = f"{m.group(1)} {num} {m.group(4)}"
        for i, lvl, _ in part:
            m = NUMBERED.match(lines[i])
            if m:
                used.add((len(m.group(1)), (edits[i].split(" ")[1] if i in edits else m.group(2))))
    return edits


def apply(path: str, prefixes: dict[str, str], write: bool) -> list[tuple[str, str]]:
    p = ROOT / path
    lines = p.read_text(encoding="utf-8").split("\n")
    edits = plan(lines, prefixes)
    if not edits:
        return []
    hs = headings(lines)
    old = effective_slugs(hs)
    new_lines = list(lines)
    for i, t in edits.items():
        new_lines[i] = t
    new = effective_slugs(headings(new_lines))
    index = {h[0]: n for n, h in enumerate(hs)}
    rows = [(old[index[i]], new[index[i]]) for i in sorted(edits)]
    link_text: dict[str, tuple[str, str]] = {}
    for i in edits:
        om, nm = NUMBERED.match(lines[i]), NUMBERED.match(new_lines[i])
        link_text[old[index[i]]] = (om.group(2) + (om.group(3) or ""), nm.group(2))
    # links inside the file (and its contents list) follow at once
    body = "\n".join(new_lines)

    def fix(m: re.Match) -> str:
        text, target = m.group(1), m.group(2)
        if target in dict(rows):
            n = dict(rows)[target]
            o_num, n_num = link_text[target]
            if text.startswith(o_num + " ") or text.startswith(o_num + ". "):
                text = n_num + " " + text.split(" ", 1)[1]
            return f"[{text}](#{n})"
        return m.group(0)

    body = re.sub(r"\[([^\]]*)\]\(#([^)\s]+)\)", fix, body)
    if write:
        p.write_text(body, encoding="utf-8")
        marker = MOVED.read_text(encoding="utf-8")
        add = "".join(f"  - {{from: '{path}#{o}', to: '{path}#{n}', how: redirect, note: 'renumbered'}}\n" for o, n in rows)
        MOVED.write_text(marker.rstrip("\n") + "\n" + add, encoding="utf-8")
    return rows


def anchor_rows() -> dict[str, str]:
    out = {}
    for r in yaml.safe_load(MOVED.read_text(encoding="utf-8")).get("moved") or []:
        if r.get("how") == "redirect" and "#" in r["from"] and r.get("to") and "#" in r["to"]:
            out[r["from"]] = r["to"]
    return out


def tracked_markdown() -> list[str]:
    return [f for f in subprocess.run(["git", "-C", str(ROOT), "ls-files", "*.md"], check=True, capture_output=True, text=True).stdout.splitlines()
            if f != "CHANGELOG.md"]


LANE_A = ("docs/guides/USER_GUIDE.md", "docs/guides/ADMIN_GUIDE.md", "docs/guides/AI_GUIDE.md", "docs/guides/APPROVALS_GUIDE.md",
          "docs/guides/LIVE_RESULTS_GUIDE.md", "docs/guides/BANK_DATA_GUIDE.md", "docs/guides/WORLD_DATA_GUIDE.md", "docs/guides/EXPOSURE_GUIDE.md",
          "docs/guides/bi/", "docs/tutorials/", "docs/knowledge/")


def follow_links(write: bool, skip: tuple[str, ...] = ()) -> dict[str, int]:
    rows = anchor_rows()
    by_doc: dict[str, dict[str, str]] = {}
    for f, t in rows.items():
        doc, _, a = f.partition("#")
        by_doc.setdefault(doc, {})[a] = t.partition("#")[2]
    changed: dict[str, int] = {}
    for md in tracked_markdown():
        if md.startswith(skip):
            continue
        p = ROOT / md
        if not p.is_file():
            continue
        text = p.read_text(encoding="utf-8")

        def fix(m: re.Match) -> str:
            path, anchor = m.group(2), m.group(3)
            try:
                target = (p.parent / path).resolve().relative_to(ROOT).as_posix() if path else md
            except ValueError:
                return m.group(0)                                  # a Help address, not a file
            if target in by_doc and anchor in by_doc[target]:
                return f"{m.group(1)}{path}#{by_doc[target][anchor]})"
            return m.group(0)

        new = re.sub(r"(\]\()([^)#\s]*)#([^)\s]+)\)", fix, text)
        if new != text:
            changed[md] = sum(1 for a, b in zip(text.split("\n"), new.split("\n")) if a != b)
            if write:
                p.write_text(new, encoding="utf-8")
    return changed


def heading_numbers(doc: str) -> dict[str, str]:
    """new effective slug -> the heading's number, for a document."""
    lines = (ROOT / doc).read_text(encoding="utf-8").split("\n")
    hs = headings(lines)
    out = {}
    for sl, (i, _, _) in zip(effective_slugs(hs), hs):
        m = NUMBERED.match(lines[i])
        if m:
            out[sl] = m.group(2)
    return out


def follow_labels(write: bool, skip: tuple[str, ...] = ()) -> dict[str, int]:
    """`[... section 6](DOC.md#26-...)`: the words "section 6" become "section 2.6" when the anchor row renamed heading 6 to 2.6."""
    by_new: dict[str, dict[str, str]] = {}
    for f, t in anchor_rows().items():
        doc, _, a = f.partition("#")
        by_new.setdefault(t.partition("#")[0], {})[t.partition("#")[2]] = a
    nums = {d: heading_numbers(d) for d in by_new}
    changed: dict[str, int] = {}
    for md in tracked_markdown():
        if md.startswith(skip):
            continue
        p = ROOT / md
        text = p.read_text(encoding="utf-8")

        def fix(m: re.Match) -> str:
            label, path, anchor = m.group(1), m.group(2), m.group(3)
            try:
                target = (p.parent / path).resolve().relative_to(ROOT).as_posix() if path else md
            except ValueError:
                return m.group(0)
            new_num = nums.get(target, {}).get(anchor)
            if new_num is None or "." not in new_num:
                return m.group(0)
            old_num = new_num.split(".", 1)[1]
            fixed = re.sub(rf"(sections? ){re.escape(old_num)}\b(?!\.\d)", rf"\g<1>{new_num}", label)
            return f"[{fixed}]({path}#{anchor})"

        new = re.sub(r"\[([^\]]*)\]\(([^)#\s]*)#([^)\s]+)\)", fix, text)
        if new != text:
            changed[md] = sum(1 for a, b in zip(text.split("\n"), new.split("\n")) if a != b)
            if write:
                p.write_text(new, encoding="utf-8")
    return changed


def main(argv: list[str]) -> int:
    write = "--write" in argv
    if "--links" in argv:
        skip = LANE_A if "--skip-lane-a" in argv else ()
        for f, n in follow_links(write, skip).items():
            print(f"{n:4d} link line(s)  {f}")
        for f, n in follow_labels(write, skip).items():
            print(f"{n:4d} label line(s) {f}")
        return 0
    prefixes, files, it = {}, [], iter(a for a in argv if a != "--write")
    for a in it:
        if a == "--prefix":
            k, _, v = next(it).rpartition("=")
            prefixes[k] = v
        else:
            files.append(a)
    for f in files:
        rows = apply(f, prefixes, write)
        print(f"{f}: {len(rows)} heading(s) renumbered")
        for o, n in rows[:6]:
            print(f"    #{o} -> #{n}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
