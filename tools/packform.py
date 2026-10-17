#!/usr/bin/env python3
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

"""The `form:` section of pack.yaml (query by form, `FORM <pack>`): what it may hold, how a pack check reads it, and how the pack tools
write one from key fields or sample documents. The rules mirror the server's (drishti-engine, search/form: FormSpec, FormField, FieldType).

    form:
      trade:                          # a kind of the pack; a list of fields, or {calendar: USNY, fields: [...]}
        - {path: productType, label: Product, type: enum, group: What}
        - {path: mtm, type: amount, operators: [">", "<", between], default: "", order: 2, help: "...", required: false, hidden: false}
"""
from __future__ import annotations

import re

TYPES = {
    "text": ["equals", "contains", "starts", "regex"],
    "number": ["=", "between", ">", "<", ">=", "<="],
    "amount": ["=", "between", ">", "<", ">=", "<="],
    "date": ["on", "between", "relative", "before", "after"],
    "enum": ["in"],
    "id": ["in"],
    "bool": ["is"],
}
KEYS = {"path", "label", "type", "operators", "default", "order", "group", "help", "required", "hidden", "values", "depends"}
PATH = re.compile(r"[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*|\[\d+\])*")
ENUMS = {"status", "type", "kind", "sector", "country", "rating", "currency", "ccy", "direction", "side", "book", "desk", "stage", "severity", "phase",
         "category", "segment", "level", "purpose", "product", "decision", "tier", "region", "state", "class", "grade", "instrument", "tissue",
         "consequence", "significance", "criticality", "position", "role", "bank", "scenario", "pollster", "productType", "assetClass", "eventType"}
AMOUNTS = re.compile(r"(?i).*(amount|notional|mtm|pnl|price|value|exposure|balance|cost|fee|nav|limit|loss|deposits|lending|spend|income|hqla|"
                     r"outflows|asf|rsf|financed|arrears|yield|capital|dv01|gap).*")
NUMBERS = re.compile(r"(?i).*(rate|score|ratio|share|count|length|growth|inflation|unemployment|probability|lcr|nsfr|ltv|days|years|seats|polling|turnout|"
                     r"majority|intensity|warming|carbon|purity|enrolled|customers|sample|lead|moe|haircut|q30|utilisation|variantcount|recordsaffected).*")


def humanize(path: str) -> str:
    last = path.rsplit(".", 1)[-1]
    if last.upper() == last:
        last = last.lower()                       # TRADE_DATE reads as "Trade date", as tradeDate does
    words = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", last).replace("_", " ").lower().strip()
    owner = path.rsplit(".", 1)[0].replace(".", " ") + " " if "." in path else ""
    text = (owner + words).strip()
    return text[:1].upper() + text[1:] if text else path


def type_of(path: str, sample: list | None = None) -> str:
    """A field's type from sample values when there are some, else from its name."""
    last = path.rsplit(".", 1)[-1]
    seen = [v for v in (sample or []) if v not in (None, "")]
    if seen:
        if all(isinstance(v, bool) for v in seen):
            return "bool"
        if all(isinstance(v, (int, float)) and not isinstance(v, bool) for v in seen):
            return "amount" if AMOUNTS.match(last) else "number"
        if all(isinstance(v, str) and re.fullmatch(r"\d{4}-\d{2}-\d{2}([T ].*)?", v) for v in seen):
            return "date"
        if re.fullmatch(r"(?i)(id|.*Id|.*_id|.*code|.*ref|.*number)", last):
            return "id"
        distinct = {str(v) for v in seen}
        return "enum" if len(distinct) <= 12 and len(seen) >= 2 * len(distinct) else "text"
    low = last.lower()
    if low.endswith(("date", "time", "due")) or re.search(r"[a-z]On$", last) or (low.endswith("at") and len(low) > 4) or low in ("occurred", "collected", "introduced", "due", "maturity", "fieldwork", "vintage"):
        return "date"
    if re.fullmatch(r"(is|has)[A-Z].*", last):
        return "bool"
    if re.fullmatch(r"(?i)(id|.*Id|.*_id|.*code|accession)", last):
        return "id"
    if last in ENUMS or low in ENUMS:
        return "enum"
    if AMOUNTS.match(last):
        return "amount"
    if NUMBERS.match(last):
        return "number"
    return "text"


def field(path: str, ftype: str | None = None, label: str | None = None, **more) -> dict:
    """One declared field with only what differs from the defaults written out."""
    out = {"path": path, "label": label or humanize(path), "type": ftype or type_of(path)}
    out.update({k: v for k, v in more.items() if v not in (None, "", False, [])})
    return out


def form_from_columns(columns: list[str], id_field: str | None = None, extra: dict[str, list[dict]] | None = None) -> list[dict]:
    """A kind's form from its key fields (the columns of its pick lists): the id first, then each column by its name's type."""
    paths = ([id_field] if id_field and id_field not in columns else []) + list(columns)
    return [field(p, "id" if p == id_field else None) for p in paths]


def form_from_documents(docs: list[dict], limit: int = 12, id_field: str | None = None) -> list[dict]:
    """A kind's form from sample documents (pack make, New pack): top-level scalar fields, typed by their values, the id first."""
    flat: dict[str, list] = {}
    for d in docs:
        for k, v in d.items():
            if isinstance(v, (str, int, float, bool)) or v is None:
                flat.setdefault(k, []).append(v)
    names = ([id_field] if id_field in flat else []) + [k for k in flat if k != id_field]
    return [field(n, "id" if n == id_field else type_of(n, flat[n])) for n in names[:limit]]


def validate(manifest: dict) -> list[str]:
    """What is wrong with the `form:` section of a pack.yaml (as a dict); empty when it is fine or absent."""
    form = manifest.get("form")
    if form is None:
        return []
    if not isinstance(form, dict):
        return ["pack.yaml form: must be a mapping of kind to its fields"]
    kinds = set(manifest.get("kinds") or [])
    problems: list[str] = []
    for kind, spec in form.items():
        where = f"pack.yaml form.{kind}"
        if kind not in kinds:
            problems.append(f"{where}: '{kind}' is not one of the pack's kinds")
        fields = spec
        if isinstance(spec, dict):
            fields = spec.get("fields")
            problems += [f"{where}: unknown key '{k}' (use fields and calendar)" for k in spec if k not in ("fields", "calendar")]
        if not isinstance(fields, list) or not fields:
            problems.append(f"{where} needs a non-empty list of fields")
            continue
        seen: set[str] = set()
        for i, f in enumerate(fields):
            at = f"{where}.fields[{i}]"
            if not isinstance(f, dict):
                problems.append(f"{at} must be a mapping with at least a path")
                continue
            path = str(f.get("path", "")).strip()
            path = path[2:] if path.startswith("$.") else path
            if not PATH.fullmatch(path):
                problems.append(f"{at}: path '{path}' is not a field path such as mtm or counterparty.name")
                continue
            at = f"{at} ({path})"
            problems += [f"{at}: unknown key '{k}' (known: {', '.join(sorted(KEYS))})" for k in f if k not in KEYS]
            ftype = str(f.get("type", "text")).strip().lower()
            if ftype not in TYPES:
                problems.append(f"{at}: type '{f.get('type')}' is not one of {', '.join(TYPES)}")
                continue
            ops = f.get("operators")
            if ops is not None:
                if not isinstance(ops, list) or not ops:
                    problems.append(f"{at}: operators must be a non-empty list")
                else:
                    problems += [f"{at}: operator '{o}' does not suit type {ftype} (allowed: {', '.join(TYPES[ftype])})" for o in ops if str(o) not in TYPES[ftype]]
            if "order" in f and not (isinstance(f["order"], int) and not isinstance(f["order"], bool)):
                problems.append(f"{at}: order must be a whole number")
            if "values" in f and ftype != "enum":
                problems.append(f"{at}: values belongs to type enum")
            for flag in ("required", "hidden"):
                if flag in f and not isinstance(f[flag], bool):
                    problems.append(f"{at}: {flag} must be true or false")
            if f.get("required") and f.get("hidden") and not f.get("default"):
                problems.append(f"{at}: a required field that is hidden needs a default")
            if path in seen:
                problems.append(f"{where}.fields: {path} appears twice")
            seen.add(path)
    return problems
