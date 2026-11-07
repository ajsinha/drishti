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


OPS = {"equals": "equals", "contains": "contains", "starts": "starts with", "regex": "matches regex", "=": "=", ">": ">", "<": "<", ">=": "≥", "<=": "≤",
       "between": "between", "on": "on", "relative": "relative", "before": "before", "after": "after", "in": "is one of", "is": "is"}
BIG = 300                  # an enum longer than this is found by typing (the console asks /form/<pack>/values), not one checkbox each
SMALL = 3                  # an enum this short stays a row of checkboxes; a longer one is a multi-select


def form_schema(view: dict, presets: list[str], pack: str = "", kind: str = "") -> dict:
    """The form as drishti-inputs draws it (its Form, live inside the page's GET form): a fieldset per group and per field, each control
    named as the address names it (``op.<path>``, ``v.<path>``, ``v2.<path>``, ``cs.<path>``), so the form submits exactly the address
    it always did. Returns ``{"schema", "value"}``; the value is what the address (or a field's default) holds."""
    groups: dict = {}
    value: dict = {}
    keys: dict[str, str] = {}
    any_indexed = any(f.get("indexed") for g in view["groups"] for f in g["fields"])
    for gi, g in enumerate(view["groups"]):
        for fi, f in enumerate(g["fields"]):
            keys[f["path"]] = f"g{gi}.f{fi}"
    for gi, g in enumerate(view["groups"]):
        gkey, props, gval = f"g{gi}", {}, {}
        for fi, f in enumerate(g["fields"]):
            fkey, p, t = f"f{fi}", f["path"], f["type"]
            ops = f.get("operators") or ["equals"]
            sub: dict = {}
            val: dict = {}
            if len(ops) > 1:
                sub["op"] = {"type": "string", "title": "Compare", "enum": ops, "enumLabels": [OPS.get(o, o) for o in ops], "widget": "select", "allowEmpty": False,
                             "name": "op." + p, "hook": {"data-qf-op": "", "aria-label": f["label"] + ": how to compare"}}
            else:
                sub["op"] = {"type": "string", "title": "Compare", "widget": "qf-constant", "noLabel": True, "name": "op." + p}
            val["op"] = f.get("op") or ops[0]
            held = [h for h in f.get("held") or [] if h != ""]
            between = {"field": "op", "equals": "between"}
            if t == "text":
                sub["v"] = {"type": "string", "title": "Value", "name": "v." + p, "hook": {"data-qf-v": "", "spellcheck": "false"}}
                sub["cs"] = {"type": "boolean", "title": "Match case", "name": "cs." + p, "nameValue": "1"}
                val["v"], val["cs"] = (held[0] if held else None), bool(f.get("cs"))
            elif t in ("number", "amount"):
                ph = "250k, 1.5m, 2bn" if t == "amount" else None
                sub["v"] = {"type": "string", "title": "Value", "name": "v." + p, "placeholder": ph, "hook": {"data-qf-v": "", "inputmode": "decimal"}}
                sub["v2"] = {"type": "string", "title": "and", "name": "v2." + p, "placeholder": ph, "when": between, "hook": {"data-qf-v2": "", "inputmode": "decimal"}}
                val["v"], val["v2"] = (held[0] if held else None), (f.get("held2") or None)
            elif t == "date":
                rel = {"field": "op", "equals": "relative"}
                sub["v"] = {"type": "date", "title": "Date", "name": "v." + p, "when": {"not": rel}, "hook": {"data-qf-v": ""}}
                sub["vrel"] = {"type": "string", "title": "Range", "name": "v." + p, "widget": "combobox", "when": rel, "placeholder": "last 5 business days",
                               "options": {"source": presets, "allowFree": True, "minChars": 0}, "hook": {"data-qf-vrel": ""}}
                sub["v2"] = {"type": "date", "title": "and", "name": "v2." + p, "when": between, "hook": {"data-qf-v2": ""}}
                relative = (f.get("op") == "relative")
                val["v"] = None if relative else (held[0] if held else None)
                val["vrel"] = (held[0] if held else None) if relative else None
                val["v2"] = f.get("held2") or None
            elif t == "enum" and not f.get("depends") and len(f.get("values") or []) > BIG:
                sub["v"] = {"type": "array", "title": "Values", "items": {"type": "string"}, "widget": "qf-big", "name": "v." + p,
                            "options": {"url": f"/form/{pack}/values?kind={kind}&path={p}", "count": len(f["values"])}}
                val["v"] = held
            elif t == "enum":
                values = [str(v) for v in f.get("values") or []]
                sub["v"] = {"type": "array", "title": "Values", "items": {"type": "string", "enum": values}, "name": "v." + p,
                            "widget": "multiselect" if len(values) > SMALL else "checkboxes", "optionHook": "qf-value"}
                if f.get("depends") and f["depends"] in keys:
                    sub["v"]["dependsOn"] = "/" + keys[f["depends"]] + ".v"
                    sub["v"]["parents"] = {str(k): [str(x) for x in v] for k, v in (f.get("parents") or {}).items()}
                val["v"] = [h for h in held if h in values]
            elif t == "id":
                sub["v"] = {"type": "string", "title": "Ids", "name": "v." + p, "format": "textarea", "options": {"rows": 2},
                            "placeholder": "one or many: paste a list (spaces, commas or lines); MX-2* is any id starting MX-2", "hook": {"data-qf-v": "", "spellcheck": "false"}}
                val["v"] = "\n".join(held) or None
            elif t == "bool":
                sub["v"] = {"type": "string", "title": "Value", "enum": ["any", "true", "false"], "enumLabels": ["Any", "Yes", "No"], "widget": "radio", "name": "v." + p}
                val["v"] = held[0] if held else "any"
            else:
                continue
            slow = "slower: not indexed (the documents are read to answer this)" if any_indexed and not f.get("indexed") else ""
            props[fkey] = {"type": "object", "title": f["label"], "description": " · ".join(x for x in (f.get("help"), slow) if x) or None, "properties": sub,
                           "order": [k for k in ("op", "v", "vrel", "v2", "cs") if k in sub], "required": ["v"] if f.get("required") else [],
                           "hook": {"data-field": p, "data-type": t, "class": "qf-field qf-t-" + t}}
            gval[fkey] = {k: v for k, v in val.items() if v is not None}
        groups[gkey] = {"type": "object", "title": g["name"] or "Criteria", "properties": props, "hook": {"class": "qf-group"}}
        value[gkey] = gval
    return {"schema": {"type": "object", "properties": groups}, "value": value}


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
