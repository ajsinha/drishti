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

"""The Sutra designer's edits (SCREEN_DESIGNER.md section 31): every mode changes the SAME Sutra YAML, a user's own Design, never the pack's Sutra.
Customize's steps are plain functions over the Sutra as a document: move a panel, hide or show it, choose a table's columns, "when I click
a row in A show B in C" (``emits`` and ``children``), add a kit fragment with parameters guessed from the record. Hidden panels are kept in
the Design's notes, so Show brings them back as they were. A customised page is first named :data:`PAGE_PREFIX` + kind;
which Design is "my version of this page" is a per-user flag kept by the server (``/me/pages``), so a rename keeps it."""
from __future__ import annotations

import json
import re

import yaml

from core import designer_links

PAGE_PREFIX = "My page · "
NOTES_MARK = "sutra-designer:"
PLACES = ("expand-row", "detail-right", "detail-below", "tabs")
PLACE_LABELS = {"expand-row": "under the clicked row", "detail-right": "the right column", "detail-below": "below the table", "tabs": "tabs"}
HEADER = "# My page, saved by the Sutra designer (Customize). Only I see it until I share it; Reset goes back to the pack's page.\n"


class DesignerError(ValueError):
    """A step that cannot be done, in plain words."""


def page_name(kind: str) -> str:
    return PAGE_PREFIX + kind


def load(text: str) -> dict:
    doc = yaml.safe_load(text or "") or {}
    if not isinstance(doc, dict) or not isinstance(doc.get("panels"), list):
        raise DesignerError("this page's Sutra has no panels to customise")
    return doc


def dump(doc: dict) -> str:
    return HEADER + yaml.safe_dump(doc, sort_keys=False, allow_unicode=True, width=140)


def notes_of(notes: str) -> dict:
    """The designer's keepings in a Design's notes: ``{"hidden": [{"entry": ..., "title": ...}]}``."""
    for line in (notes or "").splitlines():
        if line.startswith(NOTES_MARK):
            try:
                got = json.loads(line[len(NOTES_MARK):])
                return got if isinstance(got, dict) else {}
            except ValueError:
                return {}
    return {}


def notes_with(notes: str, keep: dict) -> str:
    rest = [x for x in (notes or "").splitlines() if not x.startswith(NOTES_MARK)]
    return "\n".join([*rest, NOTES_MARK + json.dumps(keep, separators=(",", ":"))]).strip()


def rename(doc: dict, kind: str) -> dict:
    """A user's page keeps its own name, so it never stands for the pack's Sutra."""
    base = str(doc.get("sutra") or kind)
    if not base.startswith("my-"):
        doc["sutra"] = ("my-" + base)[:64]
    doc["version"] = 1
    m = doc.get("match") if isinstance(doc.get("match"), dict) else {"kind": kind}
    doc["match"] = {"kind": m.get("kind") or kind}            # drawn by name for this user only: no where, no priority
    return doc


def plain_title(t) -> str:
    """A title as words: what a template (``${...}``) computes is left out ("Trades (${n} of ${m})" reads "Trades")."""
    t = str(t or "")
    cut = t.split("${")[0].rstrip(" ([:·-—,")
    return cut or t


def entry_title(e: dict, brought: list | None = None) -> str:
    if not isinstance(e, dict):
        return "Panel"
    if e.get("title"):
        return plain_title(e["title"])
    w = e.get("with") if isinstance(e.get("with"), dict) else {}
    if w.get("title"):
        return plain_title(w["title"])
    for b in brought or []:
        if b.get("title"):
            return plain_title(b["title"])
    return str(e.get("use") or e.get("id") or "Panel").split("@")[0].split("/")[-1].replace("-", " ").capitalize()


def _index(doc: dict, i) -> int:
    try:
        i = int(i)
    except (TypeError, ValueError):
        raise DesignerError("which panel?") from None
    if not 0 <= i < len(doc["panels"]):
        raise DesignerError("that panel is no longer on the page: reload it")
    return i


def move(doc: dict, i, to) -> dict:
    i, to = _index(doc, i), max(0, min(int(to), len(doc["panels"]) - 1))
    doc["panels"].insert(to, doc["panels"].pop(i))
    return doc


def hide(doc: dict, keep: dict, i, title: str = "") -> tuple[dict, dict]:
    i = _index(doc, i)
    if len(doc["panels"]) == 1:
        raise DesignerError("a page keeps at least one panel")
    e = doc["panels"].pop(i)
    keep.setdefault("hidden", []).append({"entry": e, "title": (title or "").strip()[:120] or entry_title(e), "at": i})
    return doc, keep


def show(doc: dict, keep: dict, h) -> tuple[dict, dict]:
    hidden = keep.get("hidden") or []
    try:
        got = hidden.pop(int(h))
    except (TypeError, ValueError, IndexError):
        raise DesignerError("that hidden panel is gone: reload the page") from None
    doc["panels"].insert(min(int(got.get("at") or 0), len(doc["panels"])), got["entry"])
    return doc, keep


REFRESH_CHOICES = (0, 10, 15, 30, 60, 300, 900)


def refresh(doc: dict, i, seconds) -> dict:
    """``refresh: N`` on one part of the page (a panel or a use: every panel it brings), or off (0) (SUTRA_NEXT.md 9)."""
    i = _index(doc, i)
    try:
        n = int(seconds)
    except (TypeError, ValueError):
        raise DesignerError("how often?") from None
    if not 0 <= n <= 86400:
        raise DesignerError("refresh is 0 (off) to 86400 seconds")
    if n:
        doc["panels"][i]["refresh"] = n
    else:
        doc["panels"][i].pop("refresh", None)
    return doc


def refresh_of(entry: dict) -> int:
    """The seconds an entry refreshes at (its own ``refresh:``), 0 when it does not."""
    r = entry.get("refresh")
    r = r.get("every") if isinstance(r, dict) else r
    return r if isinstance(r, int) and not isinstance(r, bool) else 0


def unpack(doc: dict, i, brought: list) -> dict:
    """A used fragment written out in place (its panels, parameters filled), so one of its tables can take other columns."""
    i = _index(doc, i)
    panels = [b["panel"] for b in brought or [] if isinstance(b.get("panel"), dict)]
    if not panels:
        raise DesignerError("this part of the page cannot be opened up")
    doc["panels"][i:i + 1] = panels
    return doc


def _label(field: str) -> str:
    return field.replace("_", " ").strip().capitalize()


def bound_field(bind) -> str:
    m = re.fullmatch(r"\s*@\.([A-Za-z_][\w]*)\s*", str(bind or ""))
    return m.group(1) if m else ""


def find_panel(doc: dict, pid: str) -> dict | None:
    for e in doc["panels"]:
        if isinstance(e, dict) and e.get("id") == pid:
            return e
    return None


def columns(doc: dict, pid: str, fields: list) -> dict:
    """The table's columns as chosen, in that order: a column it had keeps its label and format, a new one binds the row's field."""
    p = find_panel(doc, pid)
    if p is None or not isinstance(p.get("columns"), list):
        raise DesignerError("that table cannot take other columns")
    fields = [f for f in fields if isinstance(f, str) and re.fullmatch(r"[A-Za-z_][\w]*", f)]
    if not fields:
        raise DesignerError("a table keeps at least one column")
    had = {bound_field(c.get("bind")): c for c in p["columns"] if isinstance(c, dict) and bound_field(c.get("bind"))}
    others = [c for c in p["columns"] if not (isinstance(c, dict) and bound_field(c.get("bind")))]   # computed columns stay, at the end
    p["columns"] = [had.get(f) or {"label": _label(f), "bind": f"@.{f}"} for f in fields] + others
    return doc


def rows_fields(record, rows: str) -> list:
    """The fields of the first row a table's ``rows:`` reads from the record (``$.A``, ``$.A[*].B``, ``$.A[0].B``): the Columns choices."""
    first = designer_links.first_row(record, rows)
    return [k for k, v in (first or {}).items() if not isinstance(v, (dict, list))]


def click(doc: dict, pid: str, place: str, fields: list, title: str, show: dict | None = None, keep: dict | None = None) -> dict:
    """"When I click a row in <pid> show <show> in <place>": the table emits ``open`` with the row's fields, and its child shows the row's
    details (a kv), a linked record (``source`` reading ``@parent.open.<field>``, see :mod:`core.designer_links`) or an existing panel of
    the page moved under it. A new sentence for the same table replaces the old one (a panel moved under it goes back to the page)."""
    p = find_panel(doc, pid)
    if p is None or p.get("kind") != "table":
        raise DesignerError("only a table written on this page can be clicked: open it up first")
    if place not in PLACES:
        raise DesignerError("show it under the row, in the right column, below the table or as tabs")
    fields = [f for f in fields if re.fullmatch(r"[A-Za-z_]\w*", f or "")][:8]
    show = show if isinstance(show, dict) else {"how": "row"}
    keep = {} if keep is None else keep
    how = show.get("how") or "row"
    moving = None
    if how == "panel":
        moving = doc["panels"][_index(doc, show.get("index"))]
        if moving is p:
            raise DesignerError("a table cannot show itself: pick another panel")
    linked = None
    if how not in ("row", "panel"):
        try:
            linked = designer_links.child(dict(show), pid)
        except designer_links.LinkError as e:
            raise DesignerError(str(e)) from None
    unclick(doc, pid, keep)
    p = find_panel(doc, pid)
    waits = f"Pick a row of {title or 'the table'} to see it here"
    if how == "row":
        group = {"panels": [{"id": f"{pid}-row", "kind": "kv", "title": f"{title or 'Row'}: ${{@parent.open.{fields[0]}}}" if fields else title or "Row",
                             "columns": [{"label": _label(f), "bind": f"@parent.open.{f}"} for f in fields]}]}
    elif how == "panel":
        i = next(n for n, e in enumerate(doc["panels"]) if e is moving)
        doc["panels"].pop(i)
        keep.setdefault("moved", {}).setdefault(pid, []).append({"entry": moving, "at": i})
        group = {"panels": [moving]}
    else:
        group, need = linked
        fields = list(dict.fromkeys(([fields[0]] if fields else []) + [need] + fields[1:]))[:8]
    if not fields:
        raise DesignerError("this table's rows have no fields to show")
    p["emits"] = [{"name": "open", "on": "row-click",
                   "payload": {f: {"bind": f"@.{f}", "type": "id" if i == 0 else "any"} for i, f in enumerate(fields)}}]
    p["children"] = [{"place": place, "waits": waits, **group}]
    return doc


def unclick(doc: dict, pid: str, keep: dict | None = None) -> dict:
    """Stops a click sentence; a panel that was moved under the table goes back to where it was on the page."""
    p = find_panel(doc, pid)
    if p is not None:
        p.pop("emits", None)
        p.pop("children", None)
    for m in reversed(((keep or {}).get("moved") or {}).pop(pid, [])):
        doc["panels"].insert(min(int(m.get("at") or 0), len(doc["panels"])), m["entry"])
    return doc


def _ids(doc: dict) -> set:
    return {str(e.get("id") or (e.get("with") or {}).get("id") or "") for e in doc["panels"] if isinstance(e, dict)}


def add(doc: dict, ref: str, with_: dict, at=None) -> dict:
    """A kit fragment inserted as ``use:`` with its ``with:`` parameters; its panel id made unique on the page."""
    if not re.fullmatch(r"[a-z0-9-]+(?:/[a-z0-9-]+)?@\d+", ref or ""):
        raise DesignerError("pick something from the list")
    w = {k: v for k, v in (with_ or {}).items() if isinstance(k, str) and isinstance(v, (str, int, float))}
    base = str(w.get("id") or ref.split("@")[0].split("/")[-1])
    taken, n, pid = _ids(doc), 2, base
    while pid in taken:
        pid, n = f"{base}-{n}", n + 1
    w["id"] = pid
    e = {"use": ref, "with": w} if w else {"use": ref}
    at = len(doc["panels"]) if at is None else max(0, min(int(at), len(doc["panels"])))
    doc["panels"].insert(at, e)
    return doc


def guess_with(fragment: dict, record) -> dict:
    """Sensible ``with:`` for a fragment from the record's fields: a ``rows`` parameter takes the record's first list whose rows hold the
    fields the defaults name (else its first list of objects), each field parameter the row field that matches its default or its
    meaning, a ``value`` the first number; the rest keep their defaults."""
    params = dict(fragment.get("params") or {})
    meanings = {k: (v or {}).get("type") for k, v in ((fragment.get("doc") or {}).get("params") or {}).items() if isinstance(v, dict)}
    out: dict = {}
    if not isinstance(record, dict):
        return out
    lists = [(k, v) for k, v in record.items() if isinstance(v, list) and v and isinstance(v[0], dict)]
    fields_default = [str(params[k]) for k, t in meanings.items() if t == "field" and params.get(k)]
    chosen = None
    if "rows" in params and lists:
        chosen = max(lists, key=lambda kv: sum(1 for f in fields_default if f in kv[1][0]))
        if meanings.get("rows") == "expression" and str(params.get("rows", "")).strip() in ("$", ""):
            chosen = None                                                    # a whole-record fragment (field-list): no list
        else:
            out["rows"] = f"$.{chosen[0]}"
    row = chosen[1][0] if chosen else record
    names = list(row.keys())
    for k, t in meanings.items():
        if t != "field":
            continue
        d = str(params.get(k) or "")
        if d in row:
            continue
        hint = k.upper().replace("-", "_")
        pick = next((n for n in names if hint in n or n in hint), None)
        if pick is None and k in ("value", "amount", "y"):
            pick = next((n for n in names if isinstance(row.get(n), (int, float)) and not isinstance(row.get(n), bool)), None)
        if pick is None and k in ("label", "name", "x", "a", "key"):
            pick = next((n for n in names if isinstance(row.get(n), str)), None)
        if pick:
            out[k] = pick
    return out


def fits(fragment: dict, record) -> int:
    """How well a fragment fits a record (0 = not at all): a list of rows for a ``rows`` fragment whose fields it finds, a figure for a
    figure, any record for a whole-record one."""
    if not isinstance(record, dict):
        return 0
    params = fragment.get("params") or {}
    lists = [v for v in record.values() if isinstance(v, list) and v and isinstance(v[0], dict)]
    if str(params.get("rows", "")).strip() == "$":
        return 2
    if "rows" in params:
        if not lists:
            return 0
        fields = [str(v) for k, v in params.items() if isinstance(v, str) and re.fullmatch(r"[A-Z][A-Z0-9_]+", v)]
        return 1 + max(sum(1 for f in fields if f in rows[0]) for rows in lists)
    return 1


def search(kit: list, q: str, record) -> list:
    """The kit by docstring (title, summary, tags), best first; with no words, what fits the record. Each with ``with`` guessed."""
    words = [w for w in re.split(r"\W+", (q or "").lower()) if w]
    out = []
    for f in kit:
        if f.get("ref", "").startswith("row-picker") or f.get("name") in ("source-footer",):
            continue
        doc = f.get("doc") or {}
        hay = " ".join([f.get("name", ""), doc.get("summary", ""), doc.get("description", ""), " ".join(doc.get("tags") or [])]).lower()
        score = sum(3 if w in f.get("name", "") else 1 for w in words if w in hay) if words else 0
        fit = fits(f, record)
        if words and not score:
            continue
        out.append({"ref": f["ref"], "title": (f.get("params") or {}).get("title") or f.get("name", "").replace("-", " ").capitalize(),
                    "summary": doc.get("summary") or f.get("summary") or "", "tags": doc.get("tags") or [], "fit": fit,
                    "with": {**({"id": (f.get("params") or {})["id"]} if (f.get("params") or {}).get("id") else {}), **guess_with(f, record)},
                    "score": score})
    out.sort(key=lambda x: (-x["score"], -(x["fit"] > 0), -x["fit"], x["title"]))
    return out[:12]
