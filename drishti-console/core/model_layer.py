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

"""The model layer on the console's side (docs/architecture/BI_MODEL_SPEC.md sections 1 to 3 and 9): what the designers need that is pure shape, no
access. A dataset's YAML read into the facts the pages show (grain, keys, meanings, time mode); a model kept as one JSON document and written as YAML in
the spec's order (one line per dataset, relationship and measure, so a review reads well); relationships suggested from the packs' entity references;
the right default measure for a column (aggregation, format, additivity and currency from the dataset); the implicit model of a dataset, as a document
*Make it a model* writes out; a model's description turned into the Fields pane's shape; and the ``rachana: 2`` dataset the new-dataset wizard writes.
The server checks every result (``/models/validate``) and decides every access; nothing here reads data."""
from __future__ import annotations

import re

import yaml

PACK_PREFIX = "pack."
NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
ALIAS = re.compile(r"[A-Za-z_][A-Za-z0-9_]{0,63}")
SEMVER = re.compile(r"\d+\.\d+\.\d+")
MEASURE_ROLES = ("amount", "quantity", "ratio")
AGG_WORD = {"sum": "Sum of", "avg": "Average of", "min": "Minimum of", "max": "Maximum of", "count": "Count of", "distinct": "Distinct"}
TOP_ORDER = ("rachana", "model", "version", "id", "title", "description", "owner", "tags", "endorsement", "audience", "sample", "lifecycle", "replaced-by",
             "implicit-from", "datasets", "relationships", "measures", "hierarchies", "security", "contract", "rollups", "tests", "golden", "ai", "calc-groups",
             "parameters", "calendars", "layout")
DATASET_ORDER = ("rachana", "dataset", "version", "id", "title", "description", "owner", "tags", "endorsement", "extends", "where", "source", "grain", "time",
                 "refresh", "columns", "security", "tests", "ai", "rollups", "layout")


class LayerError(ValueError):
    """A document the console cannot turn into YAML or back (the message says what to fix)."""


# ---- reading datasets ---------------------------------------------------------------------------------------------------

def load(text: str) -> dict:
    try:
        doc = yaml.safe_load(text or "") or {}
    except yaml.YAMLError as e:
        raise LayerError("the YAML does not parse: " + str(e).splitlines()[0]) from None
    if not isinstance(doc, dict):
        raise LayerError("the YAML is not a mapping of keys")
    return doc


def is_layer_dataset(text: str) -> bool:
    """A ``rachana: 2`` dataset (the model layer's), not a version-1 dataset with ``tables:``."""
    try:
        doc = yaml.safe_load(text or "")
    except yaml.YAMLError:
        return False
    return isinstance(doc, dict) and doc.get("rachana") == 2 and "dataset" in doc


def is_model(text: str) -> bool:
    try:
        doc = yaml.safe_load(text or "")
    except yaml.YAMLError:
        return False
    return isinstance(doc, dict) and "model" in doc and "tables" not in doc


def default_alias(name: str) -> str:
    """The alias a dataset takes in a model: its last name part, ``-`` turned into ``_`` (``pack.trading.trade`` is ``trade``)."""
    last = str(name).rsplit("@", 1)[0].split(".")[-1]
    alias = re.sub(r"[^A-Za-z0-9_]", "_", last) or "data"
    return alias if re.match(r"[A-Za-z_]", alias) else "d_" + alias


def _columns(raw) -> list[dict]:
    out = []
    for name, c in (raw or {}).items():
        c = c if isinstance(c, dict) else {"type": str(c)}
        unit = c.get("unit") if isinstance(c.get("unit"), dict) else {}
        out.append({"name": str(name), "type": c.get("type", "string"), "role": c.get("role") or "", "meaning": c.get("meaning") or "",
                    "references": c.get("references") or "", "currency": unit.get("currency") or "", "unitOf": unit.get("of") or "",
                    "additive": c.get("additive"), "aggregate": c.get("aggregate") or "", "format": c.get("format") or "", "path": c.get("path") or "",
                    "expr": c.get("expr") or "", "of": c.get("of") or "", "description": c.get("description") or ""})
    return out


def dataset_info(text: str, read_only: bool = False, item: str = "") -> dict:
    """A dataset's YAML as the facts a person reads before using it: grain, keys, meanings, time mode, columns, security."""
    doc = load(text)
    if doc.get("rachana") != 2 or "dataset" not in doc:
        raise LayerError("not a model-layer dataset (`rachana: 2` with `dataset:`)")
    src = doc.get("source") if isinstance(doc.get("source"), dict) else {}
    grain = doc.get("grain") if isinstance(doc.get("grain"), dict) else {}
    time = doc.get("time") if isinstance(doc.get("time"), dict) else {}
    sec = doc.get("security") if isinstance(doc.get("security"), dict) else {}
    name = str(doc.get("dataset"))
    kind = "pack" if "pack" in src or "kind" in src else "connector" if "query" in src else "python" if "python" in src else "model" if "model" in src else \
        ("extends" if doc.get("extends") else "unknown")
    return {"name": name, "item": item, "title": doc.get("title") or name, "description": doc.get("description") or "", "version": str(doc.get("version") or ""),
            "owner": doc.get("owner") or "", "endorsement": doc.get("endorsement") or "none", "generated": bool(doc.get("generated")), "readOnly": bool(read_only),
            "source": {"type": kind, "pack": src.get("pack") or "", "kind": src.get("kind") or "", "connector": src.get("connector") or "",
                       "python": src.get("python") or "", "model": src.get("model") or "", "query": src.get("query") or "", "each": src.get("each") or ""},
            "extends": doc.get("extends") or "", "grain": {"says": grain.get("says") or "", "keys": [str(k) for k in grain.get("keys") or []], "date": grain.get("date") or ""},
            "time": {"mode": time.get("mode") or "snapshot", "asOf": time.get("as-of") or "", "storage": time.get("storage") or ""}, "refresh": doc.get("refresh"),
            "columns": _columns(doc.get("columns")), "rows": sec.get("rows") or [], "masked": [str(m) for m in sec.get("masked") or []],
            "alias": default_alias(name), "role": infer_role(_columns(doc.get("columns")))}


def infer_role(columns: list[dict]) -> str:
    """Fact when the table has amounts or quantities to add up, else dimension (spec 2.2; the author may change it on the box)."""
    return "fact" if any(c.get("role") in ("amount", "quantity") for c in columns) else "dimension"


def kind_of(info: dict) -> str:
    return info.get("source", {}).get("kind") or ""


# ---- measures -----------------------------------------------------------------------------------------------------------

def default_measure(alias: str, col: dict, aggregate: str = "") -> tuple[str, dict]:
    """The right default for a measure over a column (spec 2.4): the column's own aggregation (sum for amounts and quantities, average for ratios), its
    format, its additivity (a balance is ``last`` over time; a ratio never adds) and the currency it is in."""
    role = col.get("role") or ""
    agg = aggregate or col.get("aggregate") or ("avg" if role == "ratio" else "sum" if role in ("amount", "quantity") or col.get("type") in ("decimal", "integer") else "distinct")
    field = f"{alias}.{col['name']}"
    word = AGG_WORD.get(agg, agg.capitalize() + " of")
    name = f"{word} {col['name']}"
    expr = f"{agg}({field})" if agg != "distinct" else f"distinct({field})"
    m: dict = {"expr": expr}
    if col.get("format"):
        m["format"] = col["format"]
    add = col.get("additive")
    if role == "ratio" or agg in ("avg", "distinct", "min", "max") or add is False:
        m["additive"] = False
    elif isinstance(add, dict):
        m["additive"] = dict(add)
    cur = col.get("currency") or ""
    if cur:
        m["currency"] = cur if re.fullmatch(r"[A-Z]{3}", cur) else f"{alias}.{cur}"
    return name, m


def count_measure(alias: str) -> tuple[str, dict]:
    return f"Count of {alias}", {"expr": f"count({alias})"}


# ---- relationships suggested from the packs -----------------------------------------------------------------------------

def _uses(doc: dict) -> dict:
    out = {}
    for alias, d in (doc.get("datasets") or {}).items():
        ref = d.get("dataset") if isinstance(d, dict) else d
        role = d.get("role", "fact") if isinstance(d, dict) else "fact"
        out[str(alias)] = {"dataset": str(ref or "").split("@", 1)[0], "role": role}
    return out


def _pair(a: str, b: str) -> tuple:
    return tuple(sorted((a, b)))


def suggestions(doc: dict, infos: dict) -> list[dict]:
    """Relationships the packs' entity references propose (spec 9.3): a column that ``references`` a kind, towards the model's dataset of that kind, one
    click to add. A second path between the same two datasets, or one between two facts, is proposed inactive with the reason. Dismissed ones stay away."""
    uses = _uses(doc)
    rels = [r for r in (doc.get("relationships") or []) if isinstance(r, dict)]
    dismissed = set(((doc.get("layout") or {}).get("dismissed") or []))
    active_pairs = set()
    for r in rels:
        if r.get("active", True) is not False:
            active_pairs.add(_pair(str(r.get("from", "")).split(".")[0], str(r.get("to", "")).split(".")[0]))
    existing = {(str(r.get("from")), str(r.get("to"))) for r in rels}
    out = []
    for a, ua in uses.items():
        info = infos.get(a)
        if not info:
            continue
        for col in info["columns"]:
            ref = col.get("references")
            if not ref or col.get("expr"):
                continue
            for b, ub in uses.items():
                ib = infos.get(b)
                if b == a or not ib or kind_of(ib) != ref:
                    continue
                key = "id" if not ib["grain"]["keys"] or ib["grain"]["keys"] == ["id"] or len(ib["grain"]["keys"]) != 1 else ib["grain"]["keys"][0]
                frm, to = f"{a}.{col['name']}", f"{b}.{key}"
                if (frm, to) in existing or f"{frm}->{to}" in dismissed:
                    continue
                reason, active = "", True
                if ua["role"] == "fact" and ub["role"] == "fact":
                    active, reason = False, "two facts: proposed inactive; relate both to a shared dimension instead"
                elif _pair(a, b) in active_pairs:
                    active, reason = False, f"{a} and {b} are already related: a second active path would be ambiguous"
                if active:
                    active_pairs.add(_pair(a, b))
                out.append({"from": frm, "to": to, "cardinality": "many-to-one", "active": active, "basis": "pack-reference",
                            "why": f"{a}.{col['name']} holds {ref} ids (from the pack)", "reason": reason})
    return out


# ---- the implicit model, written out ------------------------------------------------------------------------------------

def implicit_doc(info: dict, dims: list[dict], name: str = "", title: str = "") -> dict:
    """The implicit model of a dataset (spec section 3) as a model document: the dataset as a fact, the packs' dimensions one hop away with their
    relationships, and the default measures. *Make it a model* saves this as a draft model."""
    alias = info["alias"]
    datasets = {alias: {"dataset": f"{info['name']}@^{(info['version'] or '1').split('.')[0]}", "role": "fact"}}
    rels = []
    for col in info["columns"]:
        ref = col.get("references")
        dim = next((d for d in dims if kind_of(d) == ref and d["name"] != info["name"]), None) if ref else None
        if not dim:
            continue
        da = dim["alias"] if dim["alias"] not in datasets else f"{alias}_{col['name']}"
        if da in datasets:
            continue
        datasets[da] = {"dataset": f"{dim['name']}@^{(dim['version'] or '1').split('.')[0]}", "role": "dimension"}
        rels.append({"from": f"{alias}.{col['name']}", "to": f"{da}.id", "suggested": {"from": "pack-reference"}})
    measures = {}
    n, m = count_measure(alias)
    measures[n] = m
    for col in info["columns"]:
        if col.get("aggregate") or col.get("role") in MEASURE_ROLES:
            n, m = default_measure(alias, col)
            measures[n] = m
    slug = name or re.sub(r"[^a-z0-9-]+", "-", (info["name"].replace("pack.", "")).lower()).strip("-") or "model"
    return {"rachana": 2, "model": slug, "version": "1.0.0", "title": title or f"{info['title']} model", "implicit-from": info["name"],
            "datasets": datasets, "relationships": rels, "measures": measures}


def new_doc(name: str, title: str) -> dict:
    return {"rachana": 2, "model": name, "version": "1.0.0", "title": title, "datasets": {}, "relationships": [], "measures": {}}


# ---- writing YAML -------------------------------------------------------------------------------------------------------

def _flow(value) -> str:
    text = yaml.safe_dump(value, default_flow_style=True, width=10 ** 6, allow_unicode=True, sort_keys=False).strip()
    return text[:-4].rstrip() if text.endswith("\n...") else text


def _key(k) -> str:
    return _flow(str(k))


def _block(key: str, value, out: list[str]) -> None:
    """One top-level key: maps of maps one entry per line (flow), lists of maps one item per line, anything else as PyYAML writes it."""
    if isinstance(value, dict) and value and all(isinstance(v, (dict, list, str, int, float, bool)) or v is None for v in value.values()) \
            and key in ("datasets", "measures", "hierarchies", "columns", "may-see-all"):
        out.append(f"{key}:")
        for k, v in value.items():
            out.append(f"  {_key(k)}: {_flow(v)}")
    elif isinstance(value, list) and value and all(isinstance(v, dict) for v in value) and key in ("relationships", "tests", "golden", "rollups"):
        out.append(f"{key}:")
        for v in value:
            out.append(f"  - {_flow(v)}")
    elif key == "security" and isinstance(value, dict):
        out.append("security:")
        for sk, sv in value.items():
            if sk == "rows" and isinstance(sv, dict):
                out.append("  rows:")
                for alias, rules in sv.items():
                    out.append(f"    {_key(alias)}:")
                    for r in rules or []:
                        out.append(f"      - {_flow(r)}")
                if not sv:
                    out[-1] = "  rows: {}"
            elif sk == "rows" and isinstance(sv, list):
                out.append("  rows:")
                out.extend(f"    - {_flow(r)}" for r in sv)
            elif isinstance(sv, dict) and sv:
                out.append(f"  {_key(sk)}:")
                out.extend(f"    {_key(k)}: {_flow(v)}" for k, v in sv.items())
            else:
                out.append(f"  {_key(sk)}: {_flow(sv)}")
    elif isinstance(value, (dict, list)) and value:
        text = yaml.safe_dump(value, sort_keys=False, default_flow_style=None, width=200, allow_unicode=True).rstrip()
        if text.startswith(("{", "[")):                                # a small leaf: one line
            out.append(f"{_key(key)}: {text}")
        else:
            out.append(f"{_key(key)}:")
            out.extend(("  " + line) if line else line for line in text.split("\n"))
    else:
        out.append(f"{_key(key)}: {_flow(value)}")


def _clean(doc: dict) -> dict:
    """Drops what would only be noise: empty security blocks, an empty layout, empty lists the server reads as absent."""
    d = dict(doc)
    sec = d.get("security")
    if isinstance(sec, dict):
        sec = {k: v for k, v in sec.items() if v not in (None, [], {})}
        if sec:
            d["security"] = sec
        else:
            d.pop("security")
    for k in ("layout", "contract", "hierarchies", "tests", "golden", "rollups"):
        if d.get(k) in ({}, [], None):
            d.pop(k, None)
    return d


def to_yaml(doc: dict, order=TOP_ORDER) -> str:
    if not isinstance(doc, dict) or not doc:
        raise LayerError("the document is empty")
    d = _clean(doc)
    keys = [k for k in order if k in d] + [k for k in d if k not in order]
    out: list[str] = []
    for k in keys:
        _block(k, d[k], out)
    text = "\n".join(out) + "\n"
    try:
        yaml.safe_load(text)
    except yaml.YAMLError as e:                                         # never hand out YAML that does not read back
        raise LayerError("the model cannot be written as YAML: " + str(e).splitlines()[0]) from None
    return text


def dataset_yaml(doc: dict) -> str:
    return to_yaml(doc, DATASET_ORDER)


# ---- the Fields pane of a report on a model -----------------------------------------------------------------------------

def fields_shape(described: dict, ref: str) -> dict:
    """A model's description (``/models/{id}/describe``) in the shape the report designer's Fields pane reads (the dataset summary: tables with columns,
    measures, hierarchies), plus what the pane's header says about the model (implicit or not)."""
    tables = []
    for d in described.get("datasets") or []:
        alias = d.get("alias")
        cols = []
        for f in d.get("fields") or []:
            name = str(f.get("field", "")).split(".", 1)[-1]
            cols.append({"name": name, "type": f.get("type") or "string", "format": f.get("format"), "masked": bool(f.get("masked")),
                         "description": f.get("description") or "", "meaning": f.get("meaning") or "", "role": f.get("role") or "", "references": f.get("references") or ""})
        tables.append({"name": alias, "kind": d.get("role") or "fact", "dataset": d.get("dataset"), "version": d.get("version"), "mode": d.get("mode"), "columns": cols})
    measures = [{"name": m.get("name"), "expr": m.get("expr"), "format": m.get("format"), "masked": bool(m.get("masked")), "implicit": bool(m.get("implicit")),
                 "additive": m.get("additive"), "description": m.get("description") or ""} for m in described.get("measures") or []]
    model = described.get("model") if isinstance(described.get("model"), dict) else {}
    mid = str(model.get("id") or described.get("id") or "")
    implicit = mid.startswith("implicit:") or ref.startswith("dataset:")
    return {"id": mid, "title": described.get("title") or model.get("name") or ref, "description": described.get("description") or "", "tables": tables,
            "measures": measures, "hierarchies": described.get("hierarchies") or {}, "relationships": described.get("relationships") or [],
            "model": {"ref": ref, "id": mid, "name": model.get("name") or "", "version": model.get("version") or "", "implicit": implicit,
                      "dataset": ref[len("dataset:"):] if ref.startswith("dataset:") else ""}}


def report_body(slug: str, title: str, ref: str) -> str:
    """A new report bound to one model (spec 4.1): ``model:`` names it; a visual's query is a model query against it."""
    return yaml.safe_dump({"report": slug, "title": title, "model": ref, "pages": [{"id": "main", "title": "Main", "visuals": []}]}, sort_keys=False, allow_unicode=True)


def rebind_report(body: str, ref: str) -> str:
    """The report's ``model:`` line pointed at another model (Make it a model rebinds the report to the model it wrote), the rest untouched."""
    if re.search(r"(?m)^model:\s*\S.*$", body or ""):
        return re.sub(r"(?m)^model:\s*\S.*$", "model: " + ref, body, count=1)
    return "model: " + ref + "\n" + (body or "")


def slug(text: str, fallback: str = "model") -> str:
    s = re.sub(r"[^a-z0-9]+", "-", str(text).lower()).strip("-")[:47]
    return s if re.match(r"[a-z][a-z0-9-]{1,47}$", s or "") else fallback
