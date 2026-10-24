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

"""Forms in the Sutra designer (SUTRA_NEXT.md 10, SCREEN_DESIGNER section 31): Customize's "Let people edit this table" writes ``form:`` on a
table (a form beside it, or its cells; which columns; New in a drawer), and Describe's ``edit trades beside|in the cells`` and ``new trade
in a drawer`` write the same on the trades of a book. A field's type is guessed from its column (a number format or a name like NOTIONAL
is a number, a date format or *_DATE a date); the first column names the record and is read-only. Saving keeps drafts only (2.8.0 writes)."""
from __future__ import annotations

import re

from core.designer import DesignerError, bound_field, find_panel

MODES = ("beside", "cells", "off")
NUMBER_FMTS = re.compile(r"^(amount|compact|num|int|pct|bp|price|rate|money|qty)", re.I)
NUMBER_NAMES = re.compile(r"(NOTIONAL|AMOUNT|LIMIT|USED|PRICE|RATE|QUANTITY|QTY|VALUE|EXPOSURE|SPREAD|STRIKE)", re.I)


def _id(field: str) -> str:
    s = re.sub(r"[^a-z0-9]+", "_", field.lower()).strip("_") or "field"
    return s if s[0].isalpha() else "f_" + s


def _type(field: str, fmt: str | None) -> str:
    if (fmt or "") == "date" or field.upper().endswith("_DATE") or field.upper() == "DATE":
        return "date"
    if NUMBER_FMTS.match(fmt or "") or NUMBER_NAMES.search(field):
        return "number"
    return "text"


def fields_of(columns: list, chosen: list | None) -> list:
    """The form's fields from a table's columns: the first names the record (read-only); the chosen ones may be edited."""
    out = []
    for n, c in enumerate(c for c in columns if isinstance(c, dict)):
        field = bound_field(c.get("bind"))
        if not field:
            continue
        f = {"id": _id(field), "label": str(c.get("label") or field), "bind": field, "type": _type(field, c.get("fmt"))}
        if n == 0:
            f.update({"readonly": True, "reason": "it names the record", "new": False})
        elif chosen is not None and field not in chosen:
            f.update({"readonly": True, "reason": "not editable on this page"})
        if f["type"] == "number" and not f.get("readonly"):
            f["fmt"] = "amount0"
        out.append(f)
    return out


def edit(doc: dict, pid: str, mode: str, chosen: list | None = None, new: bool = False) -> dict:
    """``form:`` on table ``pid``: edited beside (``beside``) or in its cells (``cells``), the ``chosen`` columns editable (all when None),
    New in a drawer when ``new``; ``off`` takes it away."""
    if mode not in MODES:
        raise DesignerError("edit beside, in the cells, or off")
    p = find_panel(doc, pid)
    if p is None or p.get("kind") != "table" or not isinstance(p.get("columns"), list):
        raise DesignerError("only a table's records can be edited here: pick a table (unpack a used one first)")
    if mode == "off" and not new:
        p.pop("form", None)
        return doc
    fields = fields_of(p["columns"], [str(x) for x in chosen] if chosen is not None else None)
    if len(fields) < 2:
        raise DesignerError("the table needs a column naming the record and one to edit")
    if mode != "off" and not any(not f.get("readonly") for f in fields):
        raise DesignerError("choose at least one column people may edit")
    form: dict = {"title": "Edit " + str(p.get("title") or pid).lower(), "key": fields[0]["id"]}
    if mode != "off":
        form["edit"] = mode
    if new:
        form.update({"new": "drawer", "new-label": "New"})
    form["fields"] = fields
    p["form"] = form
    return doc


def form_of(entry: dict) -> dict:
    """What Customize shows for a table: how it is edited, which fields, whether New opens a drawer."""
    f = entry.get("form") if isinstance(entry.get("form"), dict) else {}
    return {"mode": f.get("edit") or "off", "new": f.get("new") == "drawer",
            "fields": [x.get("bind") or x.get("id") for x in f.get("fields") or [] if isinstance(x, dict) and not x.get("readonly")]}


def trade_form(mode: str, new: bool) -> dict:
    """The trades of a book, edited beside (or in the cells) and started in a drawer: Describe's ``edit trades beside`` and ``new trade in
    a drawer``. The counterparty is chosen from the credit limits, and the drawer shows the limit after the trade."""
    form: dict = {"title": "Edit trade", "key": "trade_id"}
    if mode in ("beside", "cells"):
        form["edit"] = mode
    if new:
        form.update({"new": "drawer", "new-label": "New trade",
                     "steps": [{"id": "who", "label": "Who"}, {"id": "what", "label": "What"}, {"id": "check", "label": "Check"}]})
    form["fields"] = [
        {"id": "trade_id", "label": "Trade", "bind": "TRADE_ID", "readonly": True, "reason": "set by the booking system", "new": False},
        {"id": "counterparty", "label": "Counterparty", "bind": "COUNTERPARTY", "type": "lookup", "required": True, "readonly": True,
         "step": "who" if new else None,
         "options": {"source": {"pack": "counterparty-risk", "kind": "credit-limit", "where": "nonblank(@.COUNTERPARTY)"},
                     "value": "COUNTERPARTY", "label": "COUNTERPARTY_NAME", "carry": ["LIMIT", "USED"]}},
        {"id": "notional", "label": "Notional", "bind": "NOTIONAL", "type": "number", "min": 0, "required": True, "fmt": "amount0",
         "step": "what" if new else None},
        {"id": "maturity_date", "label": "Maturity", "bind": "MATURITY_DATE", "type": "date", "min": "today", "step": "what" if new else None},
        {"id": "comment", "label": "Comment", "bind": "COMMENT", "type": "textarea", "max-length": 500, "step": "check" if new else None},
    ]
    for f in form["fields"]:
        if f.get("step") is None:
            f.pop("step", None)
    form["rules"] = [{"rule": "notional <= 30000000", "severity": "warn", "fields": ["notional"],
                      "message": "Above the desk limit of 30,000,000: a second approver is needed."}]
    if new:
        form["hints"] = [{"label": "PFE limit after this trade", "value": "counterparty.USED + notional", "of": "counterparty.LIMIT", "step": "what"}]
    return form
