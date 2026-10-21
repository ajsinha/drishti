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

"""The thin adapter at the boundary between screen designs (Sutras) and boards, until P5's converter (UI_BOARD.md section 11) is merged.

* ``sutra_to_board``: an existing design opens in the designer's Board mode. Each panel becomes an instance of the ``panel-<kind>@1`` seed chip
  (its id and title kept), laid out in two columns as the Sutra's ``area: right`` says; the entity the Sutra matches becomes a ``board.params.id``
  of its kind. Bindings (``$.domains``, columns, ``fmt``) are not converted here: the notes say so, and the mapping sheet (or P5) maps them.
* ``board_to_sutra``: *Export Sutra*. The ``panel-*`` instances of a board written as a Sutra's panels, so pack authors keep shipping Sutras
  until 3.0; other chips are listed in the notes as not expressible.

When P5 lands, both functions are replaced by calls to its converter; the routes that use them (``/build/board-api/...``) stay."""
from __future__ import annotations

import re

import yaml

PANEL_KINDS = {"metric", "map", "kv", "status", "provenance", "markdown", "table", "ladder", "pivot", "tabs", "line", "area", "candlestick",
               "surface", "histogram", "scatter", "gauge", "waterfall", "hbar", "graph", "links", "timeline"}


def _slug(s: str) -> str:
    return re.sub(r"[^a-z0-9-]+", "-", str(s or "").lower()).strip("-")[:60] or "board"


def sutra_to_board(text: str, name: str = "", kind: str = "") -> tuple[dict, list[str]]:
    """``(board document, notes)`` for a Sutra's YAML."""
    try:
        s = yaml.safe_load(text or "") or {}
    except yaml.YAMLError as e:
        return _empty(name), [f"the design's Sutra is not YAML ({e}); an empty board is opened"]
    if not isinstance(s, dict):
        return _empty(name), ["the design has no Sutra yet; an empty board is opened"]
    kind = kind or str((s.get("match") or {}).get("kind") or "")
    board = _empty(name or str(s.get("sutra") or ""))
    if s.get("description"):
        board["description"] = str(s["description"])
    if kind:
        board["params"] = {"id": {"type": "id", "of": kind}} if "/" in kind else {"id": {"type": "id"}}
    notes, rows = [], {"main": 1, "right": 1}
    for i, p in enumerate(s.get("panels") or []):
        if not isinstance(p, dict):
            continue
        k = str(p.get("kind") or "")
        inst = _slug(p.get("id") or f"panel-{i + 1}")
        if k not in PANEL_KINDS:
            notes.append(f"panel {inst}: kind '{k}' has no seed chip; left out")
            continue
        board["chips"][inst] = {"use": f"panel-{k}@1", **({"title": str(p["title"])} if p.get("title") else {})}
        side = "right" if p.get("area") == "right" else "main"
        board["layout"]["desktop"]["areas"].append({"chip": inst, "at": [1 if side == "main" else 8, rows[side]], "span": [7 if side == "main" else 5, 4]})
        board["layout"]["phone"]["order"].append(inst)
        rows[side] += 4
        bound = [key for key in ("rows", "bind", "series", "value", "fields") if key in p]
        if bound:
            notes.append(f"panel {inst}: its bindings ({', '.join(bound)}) are not converted yet; map its pins in the mapping sheet")
    if s.get("strip"):
        notes.append("the strip (header cells) has no chip yet; it is left out")
    return board, notes


def _empty(name: str) -> dict:
    return {"rachana": 1, "board": _slug(name) if name else "", "title": name or "", "chips": {}, "traces": [],
            "layout": {"desktop": {"columns": 12, "areas": []}, "phone": {"order": [], "collapsed": []}}}


def board_to_sutra(doc: dict, kind: str = "") -> tuple[str, list[str]]:
    """``(Sutra YAML, notes)``: the board's panel-* instances as a Sutra (Export Sutra)."""
    notes, panels = [], []
    areas = {a.get("chip"): a for a in ((doc.get("layout") or {}).get("desktop") or {}).get("areas") or [] if isinstance(a, dict)}
    for inst, i in (doc.get("chips") or {}).items():
        m = re.match(r"^panel-([a-z]+)@\d+$", str((i or {}).get("use") or ""))
        if not m:
            notes.append(f"{inst} ({(i or {}).get('use')}) is not a panel chip: a Sutra cannot hold it")
            continue
        p = {"id": inst, "kind": m.group(1), "title": str(i.get("title") or inst)}
        a = areas.get(inst)
        if a and (a.get("at") or [1])[0] > 6:
            p["area"] = "right"
        panels.append(p)
    if doc.get("traces"):
        notes.append("traces have no Sutra form: the exported screen does not follow selections")
    sutra = {"rachana": 1, "sutra": _slug(doc.get("board") or "board"), "version": 1,
             "description": str(doc.get("description") or doc.get("title") or "Exported from a board"),
             "match": {"kind": kind or "CHANGE-ME", "priority": 10}, "panels": panels}
    header = "# Exported from the board " + str(doc.get("board") or "") + " (Export Sutra). Bindings are to be written: see the notes.\n"
    return header + yaml.safe_dump(sutra, sort_keys=False, allow_unicode=True, width=140), notes
