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

"""The YAML round trip of the designers (docs/architecture/BI_DESIGN.md section 8.2): a designer edits a document (the model or the dataset as an
object); writing it back must keep what the person wrote around it: comments, the order of keys, blank lines, the licence header. This writes the
document as a **minimal patch of the original text**, on the concrete layout PyYAML reports (each top-level key's lines, and each entry's lines in a
one-entry-per-line map such as ``measures:``):

* a top-level key whose value did not change keeps its lines exactly, with the comments directly above it;
* a changed map of entries (``datasets``, ``measures``, ``fields``, ...) keeps every unchanged entry's line and its comments, rewrites the changed ones in
  place, drops the removed ones and appends the new ones;
* any other changed key is rewritten in place (its comments kept); a new key goes after the key that precedes it in the spec's order; a removed one goes.

The result is checked: it must read back as exactly the document, else the plain writer's text is used (never a wrong file). The server validates either."""
from __future__ import annotations

from typing import Callable

import yaml
from yaml.nodes import MappingNode

Writer = Callable[[str, object, list], None]


def _is_comment(line: str) -> bool:
    return line.lstrip().startswith("#")


def _segments(lines: list[str], node: MappingNode, top: int, bottom: int) -> tuple[int, list[tuple[str, int, int, int]]]:
    """For each key of a block mapping: (key, first line with its comments, key line, last line). Returns the first line of the first segment too."""
    keys = [(str(k.value), k.start_mark.line) for k, _ in node.value]
    out = []
    for i, (key, line) in enumerate(keys):
        start = line
        floor = keys[i - 1][1] + 1 if i else top
        while start - 1 >= floor and _is_comment(lines[start - 1]):                  # the comments directly above belong to the key
            start -= 1
        out.append([key, start, line, 0])
    for i, seg in enumerate(out):
        end = out[i + 1][1] - 1 if i + 1 < len(out) else bottom
        while end > seg[2] and (not lines[end].strip() or _is_comment(lines[end])) and i + 1 >= len(out):
            end -= 1                                                                    # the last key does not own the trailing blank lines and comments
        seg[3] = end
    return (out[0][1] if out else top), [tuple(s) for s in out]


def merge(original: str, doc: dict, order: tuple, write: Writer, entry_keys: tuple, plain: Callable[[dict], str]) -> str:
    """``doc`` written over ``original`` as a minimal patch (see the module docstring); ``write(key, value, out)`` is the plain writer of one top-level
    key, ``plain(doc)`` the whole-document writer used when the original cannot be patched."""
    try:
        root = yaml.compose(original or "")
        old = yaml.safe_load(original or "")
    except yaml.YAMLError:
        return plain(doc)
    if not isinstance(root, MappingNode) or root.flow_style or not isinstance(old, dict):
        return plain(doc)
    lines = (original or "").split("\n")
    if lines and lines[-1] == "":
        lines = lines[:-1]
    first, segs = _segments(lines, root, 0, len(lines) - 1)
    nodes = {str(k.value): v for k, v in root.value}
    out: list[str] = lines[:first]
    written = set()
    after_last = lines[segs[-1][3] + 1:] if segs else []

    def fresh(key) -> list[str]:
        buf: list[str] = []
        write(key, doc[key], buf)
        return buf

    def emit_new_before(key):
        """New keys whose place in the spec's order is before ``key``."""
        if key not in order:
            return
        for k in order[:order.index(key)]:
            if k in doc and k not in old and k not in written:
                out.extend(fresh(k))
                written.add(k)

    for key, start, kline, end in segs:
        emit_new_before(key)
        if key not in doc:
            continue                                                                    # removed: its lines and comments go
        written.add(key)
        if old.get(key) == doc[key]:
            out.extend(lines[start:end + 1])
            continue
        comments = lines[start:kline]
        node = nodes.get(key)
        if key in entry_keys and isinstance(node, MappingNode) and not node.flow_style and isinstance(old.get(key), dict) and isinstance(doc[key], dict) and doc[key]:
            out.extend(lines[start:kline + 1])
            if node.value:
                _, entries = _segments(lines, node, kline + 1, end)
                indent = lines[entries[0][2]][:len(lines[entries[0][2]]) - len(lines[entries[0][2]].lstrip())] if entries else "  "
                plain_lines: list[str] = []
                write(key, doc[key], plain_lines)
                by_name = {}
                for ln in plain_lines[1:]:
                    k2 = yaml.safe_load(ln.strip() + "\n")
                    if isinstance(k2, dict) and len(k2) == 1:
                        by_name[str(next(iter(k2)))] = ln.strip()
                seen = set()
                for name, es, ekl, ee in entries:
                    if name not in doc[key]:
                        continue
                    seen.add(name)
                    if old[key].get(name) == doc[key][name]:
                        out.extend(lines[es:ee + 1])
                    else:
                        out.extend(lines[es:ekl])
                        out.append(indent + by_name.get(name, ""))
                for name in doc[key]:
                    if name not in seen:
                        out.append(indent + by_name.get(name, ""))
                continue
        out.extend(comments)
        out.extend(fresh(key))
    for k in list(order) + [k for k in doc if k not in order]:
        if k in doc and k not in written:
            out.extend(fresh(k))
            written.add(k)
    out.extend(after_last)
    text = "\n".join(out).rstrip("\n") + "\n"
    try:
        if yaml.safe_load(text) == doc:
            return text
    except yaml.YAMLError:
        pass
    return plain(doc)
