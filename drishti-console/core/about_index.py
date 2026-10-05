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

"""The index the About this page partial hands the browser (docs/architecture/CONTEXT_HELP.md, layer 2): the glossary entries and
the text each panel's popover shows, taken from the server's explain answer and nothing else. The browser draws every word of it
with textContent. A field hidden for the caller keeps its definition and never carries the meaning of a value (the server
already left those out); nothing here looks a value up."""
from __future__ import annotations

TERM_KEYS = ("key", "label", "labels", "shownIn", "term", "means", "unit", "sign", "note", "formula", "values", "masked")

WHY = {"missing": "is missing in this document", "null": "is null in this document", "empty list": "is an empty list",
       "masked": "is hidden for your role", "no values": "has no values", "no data": "has no data"}


def build(ex: dict | None) -> dict:
    """``{"terms": [...], "panels": {id: {"about": text, "notes": [text, ...]}}}`` for one explain answer."""
    ex = ex or {}
    terms = [{k: t[k] for k in TERM_KEYS if t.get(k) not in (None, "", [], {})} for t in ex.get("glossary") or []]
    panels: dict[str, dict] = {}

    def entry(pid: str) -> dict:
        return panels.setdefault(pid, {"notes": []})

    for p in (ex.get("about") or {}).get("panels") or []:
        if p.get("id") and p.get("description"):
            entry(p["id"])["about"] = p["description"]
    layout = ex.get("layout") or {}
    for n in layout.get("noData") or []:
        where = f" ({n['path']} {WHY.get(n.get('why'), n.get('why') or 'has nothing to show')})" if n.get("path") else ""
        entry(n["id"])["notes"].append("Shows no data" + where + ".")
    for e in layout.get("errors") or []:
        entry(e["id"])["notes"].append("Could not be drawn: " + str(e.get("message") or "an error") + ".")
    for i in layout.get("inferredPanels") or []:
        entry(i["id"])["notes"].append("Added by inference" + (f": {i['reason']}" if i.get("reason") else "") + ".")
    return {"terms": terms, "panels": panels}
