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

"""Query by form, the console's side (``FORM <pack> <GO>``): the criteria of a submitted form from the address, and the form
described for the page. The address is the form's state: ``op.<path>``, ``v.<path>`` (repeated for several), ``v2.<path>`` and
``cs.<path>`` for each field, ``kind``, ``order``, ``desc`` and ``limit``; ``run=1`` means it was submitted. The server compiles the
criteria to the ordinary search grammar and runs the ordinary search: nothing here builds a query."""
from __future__ import annotations

from typing import Iterable
from urllib.parse import urlencode

RESERVED = "FORM"          # a reserved command word, like RUPAKA: no pack may define a mnemonic with this name
LIMITS = (25, 50, 100, 250, 500, 1000)
PRESETS = ["today", "yesterday", "last 5 business days", "last 10 business days", "next 5 business days", "last 7 days", "last 30 days",
           "this week", "this month", "month to date", "last month", "year to date"]


def criteria_from(params: Iterable[tuple[str, str]], fields: list[dict]) -> list[dict]:
    """One criterion for every field the person filled in. A field is a criterion when it has a value; a boolean needs yes or no."""
    multi: dict[str, list[str]] = {}
    for k, v in params:
        multi.setdefault(k, []).append(v)
    out = []
    for f in fields:
        p = f["path"]
        values = [v for v in multi.get("v." + p, []) if v.strip()]
        second = [v for v in multi.get("v2." + p, []) if v.strip()]
        if not values and f["type"] != "bool":
            continue
        if f["type"] == "bool" and (not values or values[0] == "any"):
            continue
        op = (multi.get("op." + p) or [f["operators"][0]])[0]
        c = {"path": p, "op": op, "values": values + (second[:1] if op == "between" else [])}
        if f["type"] == "text" and multi.get("cs." + p, [""])[0] in ("1", "on", "true"):
            c["case"] = True
        out.append(c)
    return out


def address(pack: str, kind: str, criteria: list[dict], order: str = "", desc: bool = False, limit: int = 0) -> str:
    """The form's address for these criteria (what Save and Share keep; the browser's Back button returns to it)."""
    q: list[tuple[str, str]] = [("kind", kind)] if kind else []
    for c in criteria:
        p = c["path"]
        q.append(("op." + p, c["op"]))
        vals = c["values"]
        if c["op"] == "between" and len(vals) == 2:
            q += [("v." + p, vals[0]), ("v2." + p, vals[1])]
        else:
            q += [("v." + p, v) for v in vals]
        if c.get("case"):
            q.append(("cs." + p, "1"))
    if order:
        q.append(("order", order))
        if desc:
            q.append(("desc", "1"))
    if limit:
        q.append(("limit", str(limit)))
    q.append(("run", "1"))
    return f"/form/{pack}?" + urlencode(q)


def prepare(form: dict, params: Iterable[tuple[str, str]]) -> dict:
    """The form for the page: visible fields grouped (in order, by group), each with the values it currently holds (from the address, else
    its default). Hidden fields stay out of the page; criteria on them in the address still apply."""
    multi: dict[str, list[str]] = {}
    for k, v in params:
        multi.setdefault(k, []).append(v)
    submitted = "run" in multi
    groups: list[dict] = []
    for f in form["fields"]:
        if f.get("hidden"):
            continue
        p = f["path"]
        held = multi.get("v." + p, [])
        if not submitted and not held and f.get("default"):
            held = [f["default"]]
        g = dict(f, values=f.get("values") or [], op=(multi.get("op." + p) or [f["operators"][0]])[0], held=held, held2=(multi.get("v2." + p) or [""])[0],
                 cs=multi.get("cs." + p, [""])[0] in ("1", "on", "true"), id="qf-" + p.replace(".", "-").replace("[", "-").replace("]", ""))
        name = f.get("group") or ""
        group = next((x for x in groups if x["name"] == name), None)
        if group is None:
            group = {"name": name, "fields": []}
            groups.append(group)
        group["fields"].append(g)
    return {"groups": groups, "submitted": submitted}


_SYMBOL = {"equals": "=", "contains": "contains", "starts": "starts", "regex": "matches", "between": "between", "relative": "", "in": "in", "is": "="}


def summary(view: dict, shown: int = 3) -> str:
    """One line of the criteria the form holds ("desk = RATES · notional > 10m · +3 more"), for when the form is folded away."""
    parts: list[str] = []
    for g in view["groups"]:
        for f in g["fields"]:
            held = [h for h in f["held"] if h and h != "any"]
            if not held:
                continue
            op = _SYMBOL.get(f["op"], f["op"])
            value = ",".join(held) if len(held) < 4 else f"{len(held)} values"
            if f["op"] == "between":
                value = f"{held[0]} and {f['held2']}"
            parts.append(" ".join(x for x in (f["label"], op, value) if x))
    if len(parts) > shown:
        return " \u00b7 ".join(parts[:shown] + [f"+{len(parts) - shown} more"])
    return " \u00b7 ".join(parts)
