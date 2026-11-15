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

"""The row rule: what one document becomes, as one function (docs/architecture/POLARS_LOADER_DESIGN.md, contract rules C11 to C18).

``row(plan, doc, day_of)`` is the only place that decides, for one document, whether it is rejected and why, what its id is, which
constants, flattened fields and vectors it gains, what its text is, its partition values and its promoted columns. The Python path
(``stage.Stager``) calls it per document; the Polars engine will call the same function, so the two cannot drift. It reads no file and
writes none; the business date of the file stays the caller's (``day_of``: rule C9 is a rule of the file, not of the row).

The order of the checks is part of the rule, because a document with two faults is rejected for the first: the id, then the date, then
the vectors."""
from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from datetime import date

MISSING = object()
_YMD = re.compile(r"^(\d{4})(\d{2})(\d{2})$")
_STEP = re.compile(r"([^.\[\]]+)|\[(\d+)\]")
_ID_FIELD = re.compile(r"(?i)(^|_)(id|ids|ref|refs|key)$")


def get(doc, path: str):
    """The value at a dotted path (``index.group``), MISSING when absent."""
    cur = doc
    for part in path.split("."):
        if isinstance(cur, dict) and part in cur:
            cur = cur[part]
        else:
            return MISSING
    return cur


def get_indexed(doc, path: str):
    """The value at a path that may index lists (``LEGS[0].NOTIONAL``, ``LEGS[1].INDEX.NAME``), MISSING when absent."""
    cur = doc
    for name, index in _STEP.findall(path):
        if name:
            if isinstance(cur, dict) and name in cur:
                cur = cur[name]
            else:
                return MISSING
        else:
            i = int(index)
            if isinstance(cur, list) and i < len(cur):
                cur = cur[i]
            else:
                return MISSING
    return cur


def column_name(path: str) -> str:
    """The physical column of a promoted path or partition: counterparty.id -> counterparty__id (as tools/samplegen/layout.py)."""
    return path.replace(".", "__")


def parse_day(v) -> date | None:
    """A business date from a field: an ISO date or date-time text (2026-10-01, 2026-10-01T17:00:00Z), yyyymmdd as text or a number."""
    if v is None or v is MISSING or isinstance(v, bool):
        return None
    if isinstance(v, int):
        v = str(v)
    if not isinstance(v, str):
        return None
    s = v.strip()
    m = _YMD.match(s)
    try:
        if m:
            return date(int(m[1]), int(m[2]), int(m[3]))
        return date.fromisoformat(s[:10]) if len(s) >= 10 else None
    except ValueError:
        return None


def scalar_text(v) -> str | None:
    """A partition value as text (Hive folders hold text): None for missing or null, JSON for an object or array (rule C15)."""
    if v is MISSING or v is None:
        return None
    if isinstance(v, str):
        return v
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (int, float)):
        return str(v)
    return json.dumps(v, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


def is_id_field(path: str) -> bool:
    """A field named like an id or key (ID, TRADE_ID, TRADE_IDS, DEAL_REF, counterparty.id): its column is text, whatever the values."""
    return bool(_ID_FIELD.search(path.replace("__", ".").rsplit(".", 1)[-1]))


def is_simple_list(v) -> bool:
    """A list of simple values (texts, numbers, booleans, nulls): a promoted field whose every value is one is written as an
    array<string> column (DATA_LAKE_GUIDE.md section 2.4); a list holding an object or a list stays in the document."""
    return isinstance(v, list) and not any(isinstance(x, (list, dict)) for x in v)


def list_texts(v) -> list[str] | None:
    """A list attribute's values as texts (ListValues.java): a number in its plain text (5, never 5.0), a null left out; a single value
    is a list of one; JSON text of a list is read as the list."""
    if v is None:
        return None
    if isinstance(v, str) and v.startswith("["):
        try:
            parsed = json.loads(v)
            if is_simple_list(parsed):
                v = parsed
        except ValueError:
            pass
    items = v if isinstance(v, list) else [v]
    out = []
    for x in items:
        if x is None:
            continue
        if isinstance(x, bool):
            out.append("true" if x else "false")
        elif isinstance(x, float) and x.is_integer() and abs(x) < 1e18:
            out.append(str(int(x)))
        elif isinstance(x, (list, dict)):
            out.append(json.dumps(x, ensure_ascii=False, separators=(",", ":")))
        else:
            out.append(str(x))
    return out


def id_text(v) -> str | None:
    """An id as its exact text: 1000008 (a number in the file) -> "1000008", never "1000008.0"; text unchanged (leading zeros kept)."""
    if isinstance(v, float) and v.is_integer() and abs(v) < 1e18:
        return str(int(v))
    return scalar_text(v)


def is_number(v) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def document_text(doc) -> str:
    """The document written again (rule C18): Python's compact JSON, key order kept, a float as ``repr``, non-ASCII as it is."""
    return json.dumps(doc, ensure_ascii=False, separators=(",", ":"))


@dataclass(frozen=True)
class RowPlan:
    """What the row rule needs of a table: the lake file's and the connector's settings, as the front resolves them."""
    key: str
    partition_by: tuple = ()
    promote: tuple = ()                    # promoted paths, flattened names included, partition columns excluded
    flatten: dict = field(default_factory=dict)
    vectors: tuple = ()                    # vector paths, partition and promoted columns excluded
    set: dict = field(default_factory=dict)


@dataclass
class Reject:
    reason: str


@dataclass
class Row:
    """One accepted document: its id, its business date, the document as stored (text too), its partition values and its promoted and
    vector columns, and what it showed of the promoted columns' types (the caller folds that into the table's)."""
    ident: str
    doc: object
    text: str
    day: date
    own: date | None                                  # the row's own date field, when it has one (counted, never used: rule C10)
    values: tuple                                     # the partition values, as text
    promoted: dict                                    # column -> value, vector columns included
    not_numeric: tuple = ()                           # promoted columns this row shows are not all numbers
    lists: tuple = ()                                 # (promoted column, the value is a list of simple values)


def row(plan: RowPlan, doc, day_of) -> Row | Reject:
    """The one document's outcome. ``day_of(doc)`` is the caller's rule for the business date (rule C9): it returns ``(day, own)`` or a
    text, the reason the document is rejected for lack of a date. ``doc`` is a decoded JSON object (the caller rejects what is not)."""
    if plan.set and isinstance(doc, dict):               # the connector's set: constants are stored in a row that lacks the field
        lacking = {n: v for n, v in plan.set.items() if n not in doc}
        if lacking:
            doc = {**doc, **lacking}
    kv = get(doc, plan.key)
    if kv is MISSING or kv is None or kv == "":
        return Reject(f"no id (field '{plan.key}' is missing or empty)")
    if isinstance(kv, (dict, list)):
        return Reject(f"the id field '{plan.key}' is not a single value")
    ident = id_text(kv)
    if is_number(kv) and isinstance(doc, dict) and plan.key in doc:       # the document carries its id as text too
        doc = {**doc, plan.key: ident}
    d = day_of(doc)
    if isinstance(d, str):
        return Reject(d)
    day, own = d
    if plan.flatten and isinstance(doc, dict):
        added = {n: v for n, path in plan.flatten.items() if n not in doc and (v := get_indexed(doc, path)) is not MISSING}
        if added:
            doc = {**doc, **added}
    vecs = {}
    for p in plan.vectors:
        v = get(doc, p) if isinstance(doc, dict) else MISSING
        if v is MISSING or v is None:
            continue
        if not isinstance(v, list) or not all(is_number(x) or x is None for x in v):
            return Reject(f"the vector field '{p}' is not a list of numbers")
        vecs[column_name(p)] = v
        doc = {**doc, p: {"$vector": column_name(p)}} if "." not in p else doc
    values = tuple(scalar_text(get(doc, p)) for p in plan.partition_by)
    promoted, not_numeric, lists = {}, [], []
    for p in plan.promote:
        v = get(doc, p)
        v = None if v is MISSING else v
        name = column_name(p)
        if v is not None and not is_number(v):
            not_numeric.append(name)
        if v is not None:                                           # a list attribute: every value a list of simple values (section 2.4)
            lists.append((name, is_simple_list(v)))
        if is_id_field(p) or p == plan.key:                         # an id column is text even when every id is a number
            not_numeric.append(name)
            v = id_text(v) if is_number(v) else v
        promoted[name] = v
    promoted.update(vecs)
    return Row(ident, doc, document_text(doc), day, own, values, promoted, tuple(not_numeric), tuple(lists))
