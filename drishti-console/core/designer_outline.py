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

"""The Sutra designer's Outline mode (SCREEN_DESIGNER.md section 31): the screen as a tree in plain words (its sections, the panels in
each, the panels a click opens under a table) and the steps the tree's drags and settings make on the SAME Sutra YAML Customize and
Describe edit: move a panel (to another place or section), nest it under a table (it opens on a click, :func:`core.designer.click`
with the panel moved under the table) or un-nest it, add, rename or remove a section (``layout.sections``), and set a panel's or a
child's settings (width, area, folding, where a child opens and when it shows, refresh where the Sutra has one)."""
from __future__ import annotations

import re

from core import designer
from core.designer import DesignerError

KIND_WORDS = {
    "table": "A table", "kv": "Labels and values", "ladder": "A ladder of dated rows", "line": "A line chart", "area": "An area chart",
    "hbar": "Bars, one a row", "tabs": "Tabs", "metric": "One headline number", "markdown": "A note", "timeline": "Dated events",
    "links": "Linked records", "provenance": "Where the data came from", "map": "A map", "status": "A status line", "pivot": "A pivot",
    "gauge": "A gauge", "waterfall": "A waterfall chart", "histogram": "A histogram", "scatter": "A scatter chart",
    "candlestick": "A candlestick chart", "graph": "A network of linked records", "surface": "A surface",
}
SHOW_LABELS = {"on-select": "when a row is picked", "always": "always", "on-expand": "when the row is opened"}
SETTINGS = ("span", "area", "collapsible", "refresh", "place", "show")


def _rows_words(rows) -> str:
    found = re.findall(r"\.([A-Za-z_]\w*)", str(rows or "")) if "$" in str(rows or "") else []      # the last field read: what the rows are
    return f" of {found[-1].replace('_', ' ').lower()}" if found else ""


def describe(e: dict, kit: dict | None = None) -> str:
    """A panel in plain words: its own ``description:``, else the kit fragment's summary, else what its kind shows (and from where)."""
    if not isinstance(e, dict):
        return ""
    if e.get("description"):
        return str(e["description"])
    if e.get("use"):
        ref = str(e["use"])
        doc = (kit or {}).get(ref) or (kit or {}).get(ref.split("@")[0]) or {}
        return doc.get("summary") or f"A shared part ({ref.split('@')[0].split('/')[-1].replace('-', ' ')})"
    words = KIND_WORDS.get(str(e.get("kind") or ""), "A panel") + _rows_words(e.get("rows"))
    cols = e.get("columns") if isinstance(e.get("columns"), list) else []
    if cols:
        words += f", {len(cols)} column{'s' if len(cols) != 1 else ''}"
    return words


def _sections(doc: dict) -> list:
    lay = doc.get("layout") if isinstance(doc.get("layout"), dict) else {}
    return [s for s in lay.get("sections") or [] if isinstance(s, dict) and s.get("id")]


def tree(doc: dict, kit: dict | None = None) -> dict:
    """The outline: sections in order (the first, unnamed, holds the panels in no section), each with its panels and their children."""
    secs = _sections(doc)
    known = {s["id"] for s in secs}
    groups = [{"id": "", "title": "The page", "panels": []}] + [{"id": s["id"], "title": s.get("title") or s["id"],
                                                                  "collapsed": bool(s.get("collapsed")), "panels": []} for s in secs]
    lay = doc.get("layout") if isinstance(doc.get("layout"), dict) else {}
    for i, e in enumerate(doc["panels"]):
        if not isinstance(e, dict):
            continue
        item = {"index": i, "id": str(e.get("id") or (e.get("with") or {}).get("id") or ""), "title": designer.entry_title(e),
                "kind": e.get("kind") or "use", "description": describe(e, kit), "used": bool(e.get("use")),
                "table": e.get("kind") == "table" and not e.get("use"),
                "settings": {k: e[k] for k in ("span", "area", "collapsible", "refresh") if k in e}, "children": []}
        for g, group in enumerate(e.get("children") or []):
            if not isinstance(group, dict):
                continue
            for c in group.get("panels") or []:
                if not isinstance(c, dict):
                    continue
                item["children"].append({"group": g, "id": str(c.get("id") or (c.get("with") or {}).get("id") or ""),
                                         "title": designer.entry_title(c), "description": describe(c, kit),
                                         "opens": f"{designer.PLACE_LABELS.get(group.get('place'), group.get('place') or 'below')}, "
                                                  f"{SHOW_LABELS.get(group.get('show') or 'on-select', group.get('show'))}",
                                         "settings": {"place": group.get("place") or "detail-below", "show": group.get("show") or "on-select"}})
        sec = e.get("section") if e.get("section") in known else ""
        next(g for g in groups if g["id"] == sec)["panels"].append(item)
    return {"sections": groups, "areas": [c for c in dict.fromkeys(x for r in lay.get("areas") or [] for x in str(r).split()) if c != "."],
            "places": designer.PLACE_LABELS, "shows": SHOW_LABELS, "canSection": not lay.get("areas")}


def _index(doc: dict, i) -> int:
    try:
        i = int(i)
    except (TypeError, ValueError):
        raise DesignerError("which panel?") from None
    if not 0 <= i < len(doc["panels"]):
        raise DesignerError("that panel is no longer on the page: reload it")
    return i


def move(doc: dict, i, to, section=None) -> dict:
    """A panel moved to position ``to`` of the page, into ``section`` (``""``: no section; None: keep its section)."""
    i = _index(doc, i)
    e = doc["panels"].pop(i)
    to = max(0, min(int(to), len(doc["panels"])))
    if section is not None:
        if section and section not in {s["id"] for s in _sections(doc)}:
            raise DesignerError("that section is gone: reload the page")
        if section:
            e["section"] = section
        else:
            e.pop("section", None)
    doc["panels"].insert(to, e)
    return doc


def nest(doc: dict, keep: dict, i, under: str, place: str) -> dict:
    """The panel at ``i`` opens under the table ``under`` on a click on a row, ``place`` saying where: the "when I click" sentence with an
    existing panel (:func:`core.designer.click`). Returns the document."""
    parent = designer.find_panel(doc, under)
    if parent is None or parent.get("kind") != "table":
        raise DesignerError("a panel can only open under a table written on this page: open the table up in Customize first")
    i = _index(doc, i)
    if doc["panels"][i] is parent:
        raise DesignerError("a table cannot open itself")
    for k in ("section", "order", "area"):                 # a child opens where the click says, not in a section or area of the page
        doc["panels"][i].pop(k, None)
    fields = [designer.bound_field(c.get("bind")) for c in parent.get("columns") or [] if isinstance(c, dict)]
    designer.click(doc, under, place, [f for f in fields if f], designer.plain_title(parent.get("title")), {"how": "panel", "index": i}, keep)
    return doc


def unnest(doc: dict, keep: dict, parent: str) -> dict:
    """What opens under the table stops opening there; a panel that was moved under it goes back to the page."""
    if designer.find_panel(doc, parent) is None:
        raise DesignerError("that table is no longer on the page: reload it")
    return designer.unclick(doc, parent, keep)


def _slug(title: str, taken: set) -> str:
    base = re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")[:28] or "section"
    if not base[0].isalpha():
        base = "s-" + base
    sid, n = base, 2
    while sid in taken:
        sid, n = f"{base}-{n}", n + 1
    return sid


def add_section(doc: dict, title: str) -> str:
    """A new, empty section at the end (``layout.sections``); its id."""
    lay = doc.setdefault("layout", {})
    if not isinstance(lay, dict):
        raise DesignerError("this screen's layout cannot be read")
    if lay.get("areas"):
        raise DesignerError("this screen places panels by area: sections arrange screens without areas")
    title = re.sub(r"\s+", " ", title or "").strip()[:60] or "New section"
    secs = lay.setdefault("sections", [])
    sid = _slug(title, {s.get("id") for s in secs if isinstance(s, dict)})
    secs.append({"id": sid, "title": title})
    return sid


def rename_section(doc: dict, sid: str, title: str) -> dict:
    for s in _sections(doc):
        if s["id"] == sid:
            s["title"] = re.sub(r"\s+", " ", title or "").strip()[:60] or s.get("title") or sid
            return doc
    raise DesignerError("that section is gone: reload the page")


def remove_section(doc: dict, sid: str) -> dict:
    """The section goes; its panels stay on the page, in no section."""
    lay = doc.get("layout") or {}
    lay["sections"] = [s for s in _sections(doc) if s["id"] != sid]
    for e in doc["panels"]:
        if isinstance(e, dict) and e.get("section") == sid:
            e.pop("section", None)
    if not lay["sections"]:
        lay.pop("sections")
    if isinstance(doc.get("layout"), dict) and not doc["layout"]:
        doc.pop("layout")
    return doc


def setting(doc: dict, i, key: str, value, group=None) -> dict:
    """One setting: a panel's ``span`` (1 to 12, 12 is the whole width), ``area`` (one of the screen's areas), ``collapsible``,
    ``refresh`` (only where the Sutra has one: seconds), or a child group's ``place`` and ``show``."""
    if key not in SETTINGS:
        raise DesignerError("that setting cannot be changed here")
    e = doc["panels"][_index(doc, i)]
    if key in ("place", "show"):
        try:
            g = (e.get("children") or [])[int(group or 0)]
        except (TypeError, ValueError, IndexError):
            raise DesignerError("that click is gone: reload the page") from None
        allowed = designer.PLACES if key == "place" else tuple(SHOW_LABELS)
        if value not in allowed:
            raise DesignerError(f"{key} is one of {', '.join(allowed)}")
        g[key] = value
        return doc
    if key == "span":
        v = int(value)
        if not 1 <= v <= 12:
            raise DesignerError("a width is 1 to 12 columns")
        if v == 12:
            e.pop("span", None)
        else:
            e["span"] = v
    elif key == "area":
        areas = [c for c in dict.fromkeys(x for r in (doc.get("layout") or {}).get("areas") or [] for x in str(r).split()) if c != "."]
        if value not in areas + ["main", "right"]:
            raise DesignerError("pick one of the screen's areas")
        e["area"] = value
    elif key == "collapsible":
        if value:
            e["collapsible"] = True
        else:
            e.pop("collapsible", None)
    elif key == "refresh":
        if "refresh" not in e:
            raise DesignerError("this panel does not refresh on its own")
        v = int(value)
        if not 1 <= v <= 86400:
            raise DesignerError("refresh is 1 to 86400 seconds")
        e["refresh"] = v
    return doc
