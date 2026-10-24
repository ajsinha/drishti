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

"""The settings of a part added from the UI kit, in plain words (SCREEN_DESIGNER.md section 31, *Add to this page*). Each parameter of the
fragment's ``doc:`` becomes one question (*Which number? [NOTIONAL]*, *Which list?*, *Label*, *Format*), its choices built from the record's
real fields: a list parameter offers the record's lists of rows, a field parameter the fields of the chosen rows, a number the record's
numbers. A parameter whose value names nothing in the record is *unset*: the panel would be empty, so Customize says what to choose.

Also the parts a person added in this draft (kept in the Design's notes, ``keep["added"]``): only those can be **removed** (a pack's panel is
hidden instead), and the last removal is kept so **Undo** puts it back where it was. A step carries a ``nonce`` (one per click): the same
nonce twice is done once, so a double click or a retried request adds one panel."""
from __future__ import annotations

import re

from core.designer import DesignerError, _index

FORMATS = (("amount0", "1,235"), ("amount2", "1,234.56"), ("signed0", "+1,235"), ("signed2", "+1,234.56"), ("compact", "1.2M"),
           ("pct0", "12%"), ("pct2", "12.34%"), ("price2", "price, 2 decimals"), ("date", "2026-10-24"), ("dmy", "24 Oct 2026"),
           ("text", "as it is"))
AREAS = (("", "the main column"), ("right", "the right column"))
NONCES_KEPT = 50

_QUESTIONS = {"value": "Which number?", "rows": "Which list?", "title": "Title", "label": "Label", "fmt": "Format", "caption": "Caption",
              "text": "Text", "area": "Where it sits", "highlight": "Which country?", "date": "Which date?", "amount": "Which amount?",
              "name": "Which name?", "first": "Which number first?", "second": "Which number second?"}


def _resolve(record, expr):
    """The value ``$.A.B`` names in the record (``$`` the record itself); None when it names nothing or is not a plain path."""
    s = str(expr or "").strip()
    if s == "$":
        return record
    m = re.fullmatch(r"\$((?:\.[A-Za-z0-9_\-]+)+)", s)
    if not m or not isinstance(record, dict):
        return None
    cur = record
    for k in m.group(1).split(".")[1:]:
        if not isinstance(cur, dict) or k not in cur:
            return None
        cur = cur[k]
    return cur


def _number(v) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def _lists(record) -> list:
    return [(k, v) for k, v in (record or {}).items() if isinstance(v, list) and v and isinstance(v[0], dict)] if isinstance(record, dict) else []


def _scalars(record, numbers_first: bool) -> list:
    if not isinstance(record, dict):
        return []
    out = [k for k, v in record.items() if v is not None and not isinstance(v, (dict, list))]
    return sorted(out, key=lambda k: (not _number(record[k]), out.index(k))) if numbers_first else out


def _question(name: str, meaning: str) -> str:
    if name in _QUESTIONS:
        return _QUESTIONS[name]
    base = name.split("-")[-1]
    if base in ("label", "title") and name != base:
        return name.replace("-", " ").capitalize()
    if base == "fmt":
        return "Format of " + name.rsplit("-", 1)[0].replace("-", " ")
    return meaning or name.replace("-", " ").capitalize()


def form(fragment: dict, entry: dict, record) -> dict:
    """The questions for one used part: ``{ref, title, fields: [{name, question, meaning, input, value, choices, unset, advanced}], unset}``.
    ``input`` is ``choice`` (a list of ``[value, words]``) or ``text``; ``unset`` names the questions to answer before the part shows data."""
    params = dict(fragment.get("params") or {})
    docp = (fragment.get("doc") or {}).get("params") or {}
    have = {**{k: v for k, v in params.items()}, **(entry.get("with") or {})}
    rows_at = _resolve(record, have.get("rows")) if "rows" in docp else None
    row = rows_at[0] if isinstance(rows_at, list) and rows_at and isinstance(rows_at[0], dict) else (record if isinstance(record, dict) else {})
    fields, unset = [], []
    for name, d in docp.items():
        if not isinstance(d, dict):
            continue
        t, meaning = str(d.get("type") or "text"), str(d.get("meaning") or "")
        value = have.get(name, d.get("default", ""))
        value = "" if value is None else value
        f = {"name": name, "question": _question(name, meaning), "meaning": meaning, "input": "text", "value": str(value), "choices": [],
             "unset": False, "advanced": t in ("id", "key")}
        if t == "id":
            continue                                                          # made unique by the designer, not a question
        if t == "expression" and (name == "rows" or meaning.lower().startswith(("the list", "the object"))):
            f["input"] = "choice"
            if str(params.get(name, "")).strip() == "$":
                f["choices"].append(["$", "the whole record"])
            f["choices"] += [[f"$.{k}", f"{k.replace('_', ' ').lower()} ({len(v)} rows)"] for k, v in _lists(record)]
            f["unset"] = _resolve(record, value) is None
        elif t == "expression" and str(params.get(name, "")).startswith("$.") and not name.startswith("shade"):
            f["input"] = "choice"
            numeric = name in ("value", "amount") or "number" in meaning.lower()
            f["choices"] = [[f"$.{k}", k] for k in _scalars(record, numeric) if not numeric or _number(record[k])] or \
                           [[f"$.{k}", k] for k in _scalars(record, True)]
            f["unset"] = _resolve(record, value) is None
        elif t == "field":
            f["input"] = "choice"
            names = [k for k, v in row.items() if not isinstance(v, (dict, list))] if isinstance(row, dict) else []
            f["choices"] = [["", "(none)"]] + [[k, k] for k in names]
            f["unset"] = bool(value) and bool(names) and str(value) not in names
        elif t == "format":
            f["input"] = "choice"
            f["choices"] = [[k, f"{k} · {w}"] for k, w in FORMATS]
        elif t == "area":
            f["input"] = "choice"
            f["choices"] = [list(a) for a in AREAS]
            f["advanced"] = True
        elif t == "boolean":
            f["input"] = "choice"
            f["choices"] = [["true", "yes"], ["false", "no"]]
        if f["input"] == "choice" and f["value"] not in [c[0] for c in f["choices"]]:
            f["choices"].insert(0, [f["value"], f["value"] + " (not in this record)" if f["unset"] else f["value"]])
        if f["unset"]:
            unset.append(f["question"])
        fields.append(f)
    return {"ref": entry.get("use", ""), "title": str((entry.get("with") or {}).get("title") or params.get("title") or fragment.get("name") or ""),
            "fields": fields, "unset": unset}


def guess_value(fragment: dict, record) -> dict:
    """A figure's number when its default names nothing in the record: the record's first number (NOTIONAL before an id)."""
    params, docp = fragment.get("params") or {}, (fragment.get("doc") or {}).get("params") or {}
    out = {}
    for name, d in docp.items():
        if isinstance(d, dict) and d.get("type") == "expression" and name in ("value", "amount") and str(params.get(name, "")).startswith("$."):
            if _resolve(record, params[name]) is None:
                nums = [k for k in _scalars(record, True) if _number(record[k])]
                if nums:
                    out[name] = f"$.{nums[0]}"
    return out


def set_with(doc: dict, i, values: dict, fragment: dict) -> dict:
    """The part's ``with:`` answers: only the fragment's own parameters, as text or numbers; an empty answer goes back to the default."""
    i = _index(doc, i)
    e = doc["panels"][i]
    if not e.get("use"):
        raise DesignerError("only a part from the UI kit has settings: use Columns for a table")
    docp = (fragment.get("doc") or {}).get("params") or {}
    w = dict(e.get("with") or {})
    for k, v in (values or {}).items():
        if k not in docp or (docp.get(k) or {}).get("type") == "id" or not isinstance(v, (str, int, float)) or isinstance(v, bool):
            continue
        v = str(v).strip()[:500]
        if v == "" and (docp.get(k) or {}).get("type") not in ("text", "field", "key", "area"):
            w.pop(k, None)
        else:
            w[k] = v
    e["with"] = w
    return doc


def added_id(entry: dict) -> str:
    return str((entry.get("with") or {}).get("id") or entry.get("id") or "")


def mark_added(keep: dict, entry: dict) -> None:
    keep.setdefault("added", []).append(added_id(entry))


def is_added(keep: dict, entry: dict) -> bool:
    return bool(added_id(entry)) and added_id(entry) in (keep.get("added") or [])


def remove(doc: dict, keep: dict, i) -> dict:
    """Removes a part this person added (Hide is for the pack's panels); the removal is kept for Undo."""
    i = _index(doc, i)
    e = doc["panels"][i]
    if not is_added(keep, e):
        raise DesignerError("only a panel you added can be removed: hide the pack's panels instead")
    if len(doc["panels"]) == 1:
        raise DesignerError("a page keeps at least one panel")
    doc["panels"].pop(i)
    keep["removed"] = {"entry": e, "at": i}
    return doc


def undo_remove(doc: dict, keep: dict) -> dict:
    got = keep.pop("removed", None)
    if not isinstance(got, dict) or not isinstance(got.get("entry"), dict):
        raise DesignerError("there is nothing to put back")
    doc["panels"].insert(min(int(got.get("at") or 0), len(doc["panels"])), got["entry"])
    return doc


def seen(keep: dict, nonce) -> bool:
    """True when this step (its nonce) was done already; else remembers it (the last few)."""
    n = str(nonce or "")[:64]
    if not n:
        return False
    done = keep.setdefault("nonces", [])
    if n in done:
        return True
    done.append(n)
    del done[:-NONCES_KEPT]
    return False
