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

"""The side-by-side of Compare: an entity's key screens on two dates, field against field, the fields that differ marked.

Generic over the view model, not over any pack: the view's first label/value panel (a deal ticket, a counterparty card) and, for
each element of its first tabs panel (a trade's legs), the label/value parts of the tab. Tabs are matched by their title, fields by
their label, so a leg added on the later date or a field that appeared shows on one side only."""
from __future__ import annotations

KV = ("kv", "status")


def _fields(panel: dict) -> list[dict]:
    data = panel.get("data") or {}
    return [f for f in (data.get("fields") or []) if isinstance(f, dict)]


def _first(view: dict, kinds: tuple) -> dict | None:
    for p in view.get("panels") or []:
        if p.get("kind") in kinds and not p.get("empty"):
            return p
    return None


def _pairs(a: list[dict], b: list[dict]) -> list[dict]:
    """Rows by label, in the later side's order then the labels only the earlier has."""
    left = {f.get("label"): f.get("text", "") for f in a}
    right = {f.get("label"): f.get("text", "") for f in b}
    order = [f.get("label") for f in b] + [f.get("label") for f in a if f.get("label") not in right]
    out = []
    for label in order:
        x, y = left.get(label), right.get(label)
        out.append({"label": label, "a": "" if x is None else x, "b": "" if y is None else y,
                    "state": "added" if x is None else "removed" if y is None else ("changed" if x != y else "same")})
    return out


def sections(before: dict | None, after: dict | None) -> list[dict]:
    """[{title, rows: [{label, a, b, state}], changed}] for the ticket and each tab's terms; empty when either view is missing."""
    if not before or not after:
        return []
    out = []
    ka, kb = _first(before, KV), _first(after, KV)
    if ka or kb:
        rows = _pairs(_fields(ka or {}), _fields(kb or {}))
        out.append({"title": (kb or ka).get("title") or "Details", "rows": rows, "changed": sum(r["state"] != "same" for r in rows)})
    ta, tb = _first(before, ("tabs",)), _first(after, ("tabs",))
    if ta or tb:
        tabs_a = {t.get("title"): t for t in ((ta or {}).get("data") or {}).get("tabs") or []}
        tabs_b = {t.get("title"): t for t in ((tb or {}).get("data") or {}).get("tabs") or []}
        for title in list(tabs_b) + [t for t in tabs_a if t not in tabs_b]:
            def terms(tab):
                if not tab:
                    return []
                own = [f for f in tab.get("fields") or [] if isinstance(f, dict)]
                for part in tab.get("panels") or []:
                    if part.get("kind") in KV:
                        own += _fields(part)
                return own
            rows = _pairs(terms(tabs_a.get(title)), terms(tabs_b.get(title)))
            if rows:
                out.append({"title": title, "rows": rows, "changed": sum(r["state"] != "same" for r in rows)})
    return out
