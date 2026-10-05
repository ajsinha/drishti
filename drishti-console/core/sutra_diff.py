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


"""The difference between two versions of a Sutra as a reviewer wants it: panels that moved, said in words ("moved:
Legs from position 1 to 3 (main → side)"), and a line diff of the real edits only. A plain line diff shows a moved
panel block as a delete here and an add there, which hides the one changed line in it; this module reads the
``panels:`` list of both texts, matches the blocks by panel id, puts the old blocks in the new order, and only then
diffs. The full line diff stays available. Text it cannot read as a panel list (a flow list, duplicate or missing ids)
gets no moves and the full line diff as its edits, so nothing is ever hidden."""
from __future__ import annotations

import difflib
import re
from dataclasses import dataclass

_PANELS = re.compile(r"^panels:\s*(#.*)?$")
_ITEM = re.compile(r"^(\s*)-(\s|$)")
_FLOW_VALUE = r"""\s*:\s*("[^"]*"|'[^']*'|[^,}]+)"""
COLUMN = {"main": "main", "right": "side"}


def line_diff(old: str, new: str) -> list[tuple[str, str]]:
    """A unified diff of two texts, as (kind, line) with kind add, del, hunk or ctx (no file headers)."""
    out = []
    for line in difflib.unified_diff(old.splitlines(), new.splitlines(), "live", "proposed", n=3, lineterm=""):
        if line.startswith(("---", "+++")):
            continue
        kind = "hunk" if line.startswith("@@") else "add" if line.startswith("+") else "del" if line.startswith("-") else "ctx"
        out.append((kind, line))
    return out


@dataclass(frozen=True)
class _Block:
    id: str
    title: str
    area: str
    lines: tuple[str, ...]


@dataclass(frozen=True)
class _Parsed:
    head: tuple[str, ...]
    blocks: tuple[_Block, ...]
    tail: tuple[str, ...]


def _indent(line: str) -> int:
    return len(line) - len(line.lstrip(" "))


def _unquote(v: str) -> str:
    v = v.split(" #", 1)[0].strip()
    return v[1:-1] if len(v) >= 2 and v[0] == v[-1] and v[0] in "\"'" else v


def _key(block: list[str], item: int, key: str) -> str | None:
    """A direct key of the panel: on the item line (``- id: x`` or a flow mapping) or one level in."""
    first = block[item]
    flow = re.search(r"[{,]\s*" + key + _FLOW_VALUE, first)
    if first.lstrip().startswith("- {") and flow:
        return _unquote(flow.group(1))
    own = re.match(r"^\s*-\s+" + key + r":\s*(.*)$", first)
    if own:
        return _unquote(own.group(1))
    depth = _indent(first) + 2
    for line in block[item + 1:]:
        if line.strip() and not line.lstrip().startswith("#") and _indent(line) == depth:
            m = re.match(r"^\s*" + key + r":\s*(.*)$", line)
            if m:
                return _unquote(m.group(1))
    return None


def _parse(text: str) -> _Parsed | None:
    """The text split into what comes before the panel list, its blocks (each with the comments above it), and the rest."""
    lines = text.splitlines()
    start = next((i for i, line in enumerate(lines) if _PANELS.match(line)), None)
    if start is None:
        return None
    first = next((i for i in range(start + 1, len(lines)) if lines[i].strip() and not lines[i].lstrip().startswith("#")), None)
    if first is None or not _ITEM.match(lines[first]):
        return None                                   # a flow list, or no panels: not something to reorder
    col = _indent(lines[first])
    end = len(lines)
    for i in range(first, len(lines)):
        s = lines[i]
        if s.strip() and not s.lstrip().startswith("#") and (_indent(s) < col or (_indent(s) == col and not _ITEM.match(s))):
            end = i
            break
    while end > first and (not lines[end - 1].strip() or lines[end - 1].lstrip().startswith("#")) and not _ITEM.match(lines[end - 1]):
        end -= 1                                      # blank lines and comments before the next key are not a panel's
    starts = [i for i in range(first, end) if _ITEM.match(lines[i]) and _indent(lines[i]) == col]
    cuts = []
    for s in starts:                                  # the comments right above an item travel with it
        c = s
        while c - 1 > start and lines[c - 1].lstrip().startswith("#") and _indent(lines[c - 1]) == col:
            c -= 1
        cuts.append(c)
    blocks, seen = [], set()
    for n, (cut, item) in enumerate(zip(cuts, starts)):
        stop = cuts[n + 1] if n + 1 < len(cuts) else end
        block = lines[cut:stop]
        pid = _key(block, item - cut, "id")
        if not pid or pid in seen:
            return None
        seen.add(pid)
        area = (_key(block, item - cut, "area") or "main").lower()
        blocks.append(_Block(pid, _key(block, item - cut, "title") or pid, area if area in COLUMN else "main", tuple(block)))
    return _Parsed(tuple(lines[:cuts[0]]), tuple(blocks), tuple(lines[end:]))


def _lcs(a: list[str], b: list[str]) -> set[str]:
    """The ids that keep their relative order (a longest common subsequence)."""
    m = [[0] * (len(b) + 1) for _ in range(len(a) + 1)]
    for i in range(len(a) - 1, -1, -1):
        for j in range(len(b) - 1, -1, -1):
            m[i][j] = m[i + 1][j + 1] + 1 if a[i] == b[j] else max(m[i + 1][j], m[i][j + 1])
    out, i, j = set(), 0, 0
    while i < len(a) and j < len(b):
        if a[i] == b[j]:
            out.add(a[i])
            i, j = i + 1, j + 1
        elif m[i + 1][j] > m[i][j + 1]:           # on a tie the panel that came up is the one that moved (dragged up)
            i += 1
        else:
            j += 1
    return out


def _positions(blocks) -> dict[str, tuple[str, int]]:
    """Each panel's column and its 1-based position in that column."""
    count: dict[str, int] = {}
    out = {}
    for b in blocks:
        count[b.area] = count.get(b.area, 0) + 1
        out[b.id] = (b.area, count[b.area])
    return out


def _moves(old: _Parsed, new: _Parsed) -> list[dict]:
    was, now = _positions(old.blocks), _positions(new.blocks)
    titles = {b.id: b.title for b in new.blocks}
    kept: set[str] = set()
    for area in COLUMN:                               # within a column, the panels out of order are the ones that moved
        a = [b.id for b in old.blocks if b.area == area and now.get(b.id, ("", 0))[0] == area]
        b = [b.id for b in new.blocks if b.area == area and was.get(b.id, ("", 0))[0] == area]
        kept |= _lcs(a, b)
    out = []
    for b in new.blocks:
        if b.id not in was or b.id in kept:
            continue
        (fa, fp), (ta, tp) = was[b.id], now[b.id]
        column = f" ({COLUMN[fa]} → {COLUMN[ta]})" if fa != ta else ""
        out.append({"id": b.id, "title": titles[b.id], "from": fp, "to": tp, "fromArea": COLUMN[fa], "toArea": COLUMN[ta],
                    "text": f"moved: {titles[b.id]} from position {fp} to {tp}{column}"})
    return out


def _in_new_order(old: _Parsed, new: _Parsed) -> str:
    """The old text with its panel blocks in the new order (removed blocks stay after the block they followed)."""
    by_id = {b.id: b for b in old.blocks}
    order = [by_id[b.id] for b in new.blocks if b.id in by_id]
    for n, b in enumerate(old.blocks):
        if b.id in {x.id for x in new.blocks}:
            continue
        after = next((old.blocks[k].id for k in range(n - 1, -1, -1) if old.blocks[k] in order), None)
        at = 0 if after is None else next(i for i, x in enumerate(order) if x.id == after) + 1
        order.insert(at, b)
    lines = list(old.head) + [line for b in order for line in b.lines] + list(old.tail)
    return "\n".join(lines)


def review(old: str, new: str) -> dict:
    """What changed between two versions: ``moves`` (in words), ``edits`` (the line diff without the moves) and ``full``
    (the plain line diff). Without a readable panel list on both sides there are no moves and the edits are the full diff."""
    full = line_diff(old, new)
    a, b = _parse(old), _parse(new)
    if not a or not b:
        return {"moves": [], "edits": full, "full": full}
    moves = _moves(a, b)
    edits = line_diff(_in_new_order(a, b), new) if moves else full
    return {"moves": moves, "edits": edits, "full": full}
