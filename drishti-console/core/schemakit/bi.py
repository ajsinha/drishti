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

"""The BI dataset a generated pack ships for a kind whose documents hold arrays (``config/bi/datasets/<kind>-tables.dataset.yaml``): one
table for the documents and one for EVERY array at every depth (legs, each leg's cash flows, each flow's resets, events), each row of a
nested table carrying the keys of the levels above it (``$.TRADE_ID``, ``^.LEG_ID``, ``^^.FLOW_ID``), every table joined to its document,
and measures that count and add up each level. A deploy imports it into the pack's BI workspace (BI_GOVERNANCE.md, BI content in packs)."""
from __future__ import annotations

import re

import yaml

from .plan import ID_NAME, KindPlan, Plan, humanize, singular

TOTALISH = re.compile(r"(?i)(notional|quantity|qty|amount|volume|pv|mtm|value|balance|exposure|principal|premium)")


def _ident(name: str) -> str:
    s = re.sub(r"[^A-Za-z0-9_]", "_", name)
    return s if re.match(r"[A-Za-z_]", s) else "c_" + s


def _type(f) -> str:
    if f.role in ("date", "datetime"):
        return "date"
    if f.role == "number" and f.type in ("number", "integer", "string"):
        return "decimal"
    return "string"


def _cols(kp: KindPlan, prefix: str, n: int = 30) -> dict:
    """The scalar fields of one level (objects inside flattened: INDEX.NAME is INDEX_NAME), blank-everywhere fields left out."""
    out: dict = {}
    for f in kp.fields:
        if not f.path.startswith(prefix) or f.hidden or f.fill <= 0 or f.role in ("table", "object", "map", "list"):
            continue
        rest = f.path[len(prefix):]
        if "[" in rest or rest.count(".") > 1:
            continue
        name = _ident(rest.replace(".", "_"))
        if name in ("id", "businessDate", "index") or name in out:
            continue
        spec: dict = {"type": _type(f)}
        if rest != name:
            spec["path"] = rest
        if spec["type"] == "decimal":
            spec["format"] = "amount0" if TOTALISH.search(f.name) else "price2"
        out[name] = spec
        if len(out) >= n:
            break
    return out


def _arrays(kp: KindPlan, prefix: str) -> list:
    return [f for f in kp.fields if f.role == "table" and not f.hidden and f.fill > 0 and f.path.startswith(prefix)
            and "[" not in f.path[len(prefix):] and "." not in f.path[len(prefix):]]


def _key_of(kp: KindPlan, prefix: str):
    return next((f for f in kp.fields if f.path.startswith(prefix) and "[" not in f.path[len(prefix):] and "." not in f.path[len(prefix):]
                 and f.role == "id" and f.fill >= 0.9), None)


def _table_name(f) -> str:
    n = f.name.lower() if f.name.isupper() else re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", f.name).lower()
    return _ident(n)


def dataset(kp: KindPlan, plan: Plan, connector: str) -> str | None:
    """The dataset YAML for ``kp``, or None when its documents hold no array of records."""
    if not _arrays(kp, "") or not kp.key:
        return None
    pack = plan.pack["name"]
    base = _ident(kp.kind.replace("-", "_"))
    tables: dict = {base: {"source": {"connector": connector, "kind": kp.kind}, "mode": "cached", "columns": _cols(kp, "")}}
    rels, measures = [], {f"{humanize(kp.kind).capitalize()} documents": {"expr": f"count({base})", "description": f"How many {kp.kind} documents."}}
    for f in kp.fields:
        if "." not in f.path and "[" not in f.path and f.role == "number" and TOTALISH.search(f.name) and not ID_NAME.search(f.name) and not f.hidden:
            measures[f"{f.label} (total)"] = {"expr": f"sum({base}.{_ident(f.name)})", "format": "amount0"}
            break

    def walk(arr, chain: list, ancestors: list):
        """``chain``: the array names from the document down to ``arr``; ``ancestors``: (prefix, key field) of each level above."""
        prefix = arr.path + "[]."
        name = _table_name(arr) if len(chain) == 1 else _ident("_".join(_table_name(a) for a in chain[-2:]))
        while name in tables:
            name += "_x"
        cols: dict = {}
        if kp.key:                                                       # the document's key on every row
            cols[_ident(kp.key)] = {"type": "string", "path": f"$.{kp.key}"}
        for depth, (pfx, key) in enumerate(ancestors[1:], 1):            # each level's key: ^ one up, ^^ two up ...
            if key is not None:
                up = "^" * (len(ancestors) - depth)
                cols.setdefault(_ident(key.name), {"type": "string", "path": f"{up}.{key.name}"})
        for c, spec in _cols(kp, prefix).items():
            cols.setdefault(c, spec)
        tables[name] = {"source": {"connector": connector, "kind": kp.kind, "each": "[].".join(a.name for a in chain)}, "mode": "cached", "columns": cols}
        rels.append({"from": f"{name}.id", "to": f"{base}.id", "cardinality": "many-to-one"})
        label = humanize(arr.name.lower() if arr.name.isupper() else arr.name)
        measures[label] = {"expr": f"count({name})", "description": f"How many {label.lower()} (rows of {name})."}
        for c, spec in cols.items():
            if spec["type"] == "decimal" and TOTALISH.search(c) and not ID_NAME.search(c):
                measures[f"{humanize(singular(arr.name.lower()))} {c.lower().replace('_', ' ')}"] = {"expr": f"sum({name}.{c})", "format": "amount0"}
                break
        for sub in _arrays(kp, prefix):
            walk(sub, chain + [sub], ancestors + [(prefix, _key_of(kp, prefix))])

    for arr in _arrays(kp, ""):
        walk(arr, [arr], [("", _key_of(kp, ""))])
    doc = {"rachana": 1, "expressions": 2, "dataset": f"{pack}-{kp.kind}-tables", "version": 1,
           "title": f"{kp.title}: every level as a table",
           "description": f"The {kp.kind} documents and every array inside them as tables of their own (one row per element, at every depth), each row "
                          f"keeping the keys of the levels above it, joined to its document. Generated by Build -> New pack.",
           "tags": [pack, kp.kind, "generated"], "tables": tables, "relationships": rels, "measures": measures}
    return "# Generated by drishti schemakit (docs/guides/SCHEMA_TO_PACK.md, BI tables); now yours to edit.\n" + \
        yaml.safe_dump(doc, sort_keys=False, width=140, allow_unicode=True)
