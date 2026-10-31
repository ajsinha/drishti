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

"""A screen laid out with ``layout:`` (RACHANA_REFERENCE.md 13), the console side: the server's view model carries the Sutra's layout and each
panel's place; this groups the view's panels into areas (a picture of the screen in named areas, a split, tabs, a sticky area) or into
the flow grid's collapsible sections, sorted by ``order``, with the reader's own arrangement applied (the split's position and what is
folded always; each panel's area, width and order only where the Sutra says ``user-can-arrange: true``). The arrangement is kept per
reader and Sutra on the server (``/me/arrange``); ``static/js/screen-grid.js`` draws the picture, the divider, tabs and folds."""
from __future__ import annotations

from typing import Any

GRID = 12


def _num(v, lo: int, hi: int):
    return v if isinstance(v, int) and not isinstance(v, bool) and lo <= v <= hi else None


def arrange(layout: dict | None, panels: list[dict], mine: dict | None = None) -> dict | None:
    """The screen's grid for the template, or None when the view has no ``layout:`` (its panels flow in main and right, as before)."""
    if not isinstance(layout, dict):
        return None
    mine = mine if isinstance(mine, dict) else {}
    place = layout.get("place") if isinstance(layout.get("place"), dict) else {}
    pictured = [str(r) for r in layout.get("areas") or [] if isinstance(r, str)]
    names = list(dict.fromkeys(c for r in pictured for c in r.split() if c != "."))
    may = bool(layout.get("arrange"))
    own = {str(e.get("id")): e for e in (mine.get("panels") or []) if isinstance(e, dict) and e.get("id")} if may else {}
    rows = []
    for i, p in enumerate(panels):
        pid = str(p.get("id") or "")
        at = place.get(pid) if isinstance(place.get(pid), dict) else {}
        e = own.get(pid) or {}
        area = at.get("area") or (names[0] if names else None)
        if names and e.get("area") in names:
            area = e["area"]
        order = _num(e.get("order"), 0, 999)
        if order is None:
            order = _num(at.get("order"), 0, 999)
        span = _num(e.get("span"), 1, GRID) if e else None
        q = dict(p)
        if span:
            q["span"] = None if span == GRID else span
        q["grid"] = {"area": area, "section": at.get("section"), "collapsible": bool(at.get("collapsible")),
                     "collapsed": bool((mine.get("collapsed") or {}).get(pid)) if at.get("collapsible") else False}
        rows.append((i if order is None else order, i, q))
    rows.sort(key=lambda r: (r[0], r[1]))
    ordered = [r[2] for r in rows]
    folded = mine.get("collapsed") if isinstance(mine.get("collapsed"), dict) else {}
    out: dict[str, Any] = {"arrange": may, "sticky": layout.get("sticky"), "tabs": list(layout.get("tabs") or []),
                           "columns": _columns(layout, mine, names, pictured), "own": bool(mine.get("panels") or mine.get("columns") or folded),
                           "split": None, "picture": [], "tablet": [], "tabletColumns": []}
    split = layout.get("split") if isinstance(layout.get("split"), dict) else None
    if split and len(split.get("between") or []) == 2:
        out["split"] = {"left": split["between"][0], "right": split["between"][1], "resizable": split.get("resizable", True) is not False,
                        "remember": split.get("remember") or "user"}
    phone = [str(x) for x in layout.get("phone") or []]
    if pictured:
        out["mode"] = "areas"
        out["picture"] = pictured
        out["tablet"] = [str(r) for r in layout.get("tablet") or []]
        out["tabletColumns"] = list(layout.get("tabletColumns") or [])
        out["areas"] = [{"name": n, "panels": [q for q in ordered if q["grid"]["area"] == n], "tabs": n in out["tabs"],
                         "sticky": n == layout.get("sticky")} for n in names]
    else:
        out["mode"] = "flow"
        sections = [s for s in layout.get("sections") or [] if isinstance(s, dict) and s.get("id")]
        known = {s["id"] for s in sections}
        out["top"] = [q for q in ordered if q["grid"]["section"] not in known]
        out["sections"] = [{"id": s["id"], "title": s.get("title") or s["id"],
                            "collapsed": bool(folded.get(s["id"], s.get("collapsed", False))),
                            "panels": [q for q in ordered if q["grid"]["section"] == s["id"]]} for s in sections]
    out["phone"] = _phone(phone, ordered, names)
    out["mine"] = {k: mine[k] for k in ("columns", "collapsed", "panels") if k in mine}           # what screen-grid.js saves on top of
    out["fold"] = {q["id"]: q["grid"]["collapsed"] for q in ordered if q["grid"]["collapsible"]}   # panels the reader may fold
    return out


def _columns(layout: dict, mine: dict, names: list, pictured: list) -> list:
    """The area columns' widths in grid units: the reader's (a split they dragged, when the Sutra remembers it), else the Sutra's, else
    equal."""
    width = len(pictured[0].split()) if pictured else 0
    if not width:
        return []
    split = layout.get("split") if isinstance(layout.get("split"), dict) else {}
    theirs = mine.get("columns")
    if split and split.get("remember", "user") == "user" and isinstance(theirs, list) and len(theirs) == width \
            and all(isinstance(c, (int, float)) and not isinstance(c, bool) and c > 0 for c in theirs):
        return [round(float(c), 2) for c in theirs]
    cols = layout.get("columns")
    if isinstance(cols, list) and len(cols) == width:
        return cols
    return [round(GRID / width, 2)] * width


def _phone(phone: list, ordered: list, names: list) -> dict:
    """Each panel's place in the phone's single column: the ``phone:`` list (areas or panel ids) first, the rest as the picture reads."""
    rank: dict[str, int] = {}
    n = 0
    for x in phone:
        for q in ordered:
            if q.get("id") not in rank and (q.get("id") == x or (x in names and q["grid"]["area"] == x)):
                rank[q["id"]] = n
                n += 1
    by_area = sorted(ordered, key=lambda q: names.index(q["grid"]["area"]) if q["grid"]["area"] in names else 0)
    for q in by_area:
        if q.get("id") not in rank:
            rank[q["id"]] = n
            n += 1
    return rank


def area_names(layout: dict | None) -> list:
    """The area names of a view's layout, in reading order (Customize offers them as moves)."""
    if not isinstance(layout, dict):
        return []
    return list(dict.fromkeys(c for r in layout.get("areas") or [] if isinstance(r, str) for c in r.split() if c != "."))
