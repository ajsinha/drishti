#!/usr/bin/env python3
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

"""Converts Markdown Sutras (*.sutra.md, read before Drishti 1.11) into YAML Sutras (*.sutra.yaml).

The ```sutra block becomes the file, unchanged (comments and layout kept), with `rachana: 1` added at the top.
The prose around it becomes `notes:` (plain text), except what only restates the layout: the title line, the
"Applies to", "Strip" and "Function keys" lines, and the "Header fields", "Panels" and "Links" tables, which
tools derive from the YAML. A file's licence comment becomes a `#` comment.

Usage: python3 tools/rachana/md_to_yaml.py <file-or-folder>... [--delete]   (--delete removes each converted .md)
"""
import pathlib
import re
import sys

DERIVED = {"header fields", "panels", "links"}
DERIVED_LINES = re.compile(r"^\*\*(applies to|strip|function keys|group|asset class):\*\*", re.I)
OPEN = re.compile(r"^(```|~~~)\s*sutra\s*$")


def convert(md: str) -> str:
    lines = md.splitlines()
    header, i = [], 0
    if lines and lines[0].strip() == "<!--":                       # the licence comment
        i = 1
        while i < len(lines) and lines[i].strip() != "-->":
            header.append(lines[i].strip())
            i += 1
        i += 1
    block, prose, fence, section = [], [], None, None
    for line in lines[i:]:
        t = line.strip()
        if fence is None and OPEN.match(t):
            fence = OPEN.match(t).group(1)
            continue
        if fence is not None:
            if t == fence:
                fence = "done"
            elif fence != "done":
                block.append(line)
            continue
        if t.startswith("<!--") and t.endswith("-->"):
            continue
        if t.startswith("# "):                                        # the title line restates name and version
            continue
        if t.startswith("## "):
            section = t[3:].strip().lower()
            if section in DERIVED:
                continue
        if section in DERIVED or DERIVED_LINES.match(t):
            continue
        prose.append(line.rstrip())
    if not block:
        raise ValueError("no ```sutra block")
    described = next((re.sub(r'^description:\s*', "", b).strip().strip('"\'') for b in block if re.match(r"^description:", b)), "")
    prose = [p for p in prose if p.strip().strip('"\'') != described]   # the description says it already
    text = "\n".join(prose).strip()
    text = re.sub(r"\n{3,}", "\n\n", text)
    out = ["".join(f"# {h}".rstrip()) for h in header] + ([""] if header else [])
    out += ["rachana: 1"] + [b.rstrip() for b in block]
    while out and not out[-1].strip():
        out.pop()
    if text and not any(re.match(r"^notes\s*:", b) for b in block):
        out += ["notes: |"] + [("  " + l) if l.strip() else "" for l in text.splitlines()]
    return "\n".join(out) + "\n"


def main(args):
    delete = "--delete" in args
    paths = [pathlib.Path(a) for a in args if not a.startswith("--")]
    files = [f for p in paths for f in ([p] if p.is_file() else sorted(p.rglob("*.sutra.md")))]
    for f in files:
        target = f.with_name(f.name[: -len(".sutra.md")] + ".sutra.yaml")
        target.write_text(convert(f.read_text(encoding="utf-8")), encoding="utf-8")
        if delete:
            f.unlink()
        print(f"{f} -> {target}")
    if not files:
        print("no *.sutra.md files found")


if __name__ == "__main__":
    main(sys.argv[1:])
