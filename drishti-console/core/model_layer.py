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

"""The model layer on the console's side (docs/architecture/ARCHITECTURE.md §28 › The model layer: the specification of wave W1, sections 1 to 3 and 9): what the designers need that is pure shape, no
access. A dataset's YAML read into the facts the pages show (grain, keys, meanings, time mode); a model kept as one JSON document and written as YAML in
the spec's order (one line per dataset, relationship and measure, so a review reads well); relationships suggested from the packs' entity references;
the right default measure for a column (aggregation, format, additivity and currency from the dataset); the implicit model of a dataset, as a document
*Make it a model* writes out; a model's description turned into the Fields pane's shape; and the ``rachana: 2`` dataset the new-dataset wizard writes.
The server checks every result (``/models/validate``) and decides every access; nothing here reads data."""
from __future__ import annotations

import re

import yaml

from core import yaml_roundtrip

PACK_PREFIX = "pack."
NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
ALIAS = re.compile(r"[A-Za-z_][A-Za-z0-9_]{0,63}")
SEMVER = re.compile(r"\d+\.\d+\.\d+")
MEASURE_ROLES = ("amount", "quantity", "ratio")
AGG_WORD = {"sum": "Sum of", "avg": "Average of", "min": "Minimum of", "max": "Maximum of", "count": "Count of", "distinct": "Distinct"}
TOP_ORDER = ("rachana", "expressions", "model", "version", "id", "title", "description", "owner", "tags", "endorsement", "audience", "sample", "lifecycle", "replaced-by",
             "implicit-from", "tables", "datasets", "relationships", "measures", "fields", "hierarchies", "perspectives", "security", "contract", "rollups", "tests", "golden", "ai", "calc-groups",
             "parameters", "calendars", "fx", "layout")
DATASET_ORDER = ("rachana", "expressions", "dataset", "version", "id", "title", "description", "owner", "tags", "endorsement", "extends", "source", "columns", "where", "grain",
                 "derive", "group", "time", "refresh", "security", "tests", "ai", "rollups", "layout")          # ARCHITECTURE.md §28 3.1: the derivation keys in the order they apply


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
    kind = "pack" if "pack" in src or "kind" in src else "sql" if "sql" in src else "connector" if "query" in src and "model" not in src else "python" if "python" in src \
        else "model" if "model" in src else ("extends" if doc.get("extends") else "unknown")
    columns = _derived(doc, _columns(doc.get("columns") if isinstance(doc.get("columns"), dict) else {}))
    return {"name": name, "item": item, "title": doc.get("title") or name, "description": doc.get("description") or "", "version": str(doc.get("version") or ""),
            "owner": doc.get("owner") or "", "endorsement": doc.get("endorsement") or "none", "generated": bool(doc.get("generated")), "readOnly": bool(read_only),
            "source": {"type": kind, "pack": src.get("pack") or "", "kind": src.get("kind") or "", "connector": src.get("connector") or "",
                       "python": src.get("python") or "", "model": src.get("model") or "", "query": src.get("query") or "", "each": src.get("each") or "",
                       "sql": src.get("sql") or ""},
            "extends": doc.get("extends") or "", "grain": _grain(grain), "where": str(doc.get("where") or ""),
            "subset": [str(c) for c in doc["columns"]] if isinstance(doc.get("columns"), list) else [],
            "derive": doc.get("derive") if isinstance(doc.get("derive"), dict) else {}, "group": doc.get("group") if isinstance(doc.get("group"), dict) else {},
            "time": {"mode": time.get("mode") or "snapshot", "asOf": time.get("as-of") or "", "storage": time.get("storage") or ""}, "refresh": doc.get("refresh"),
            "columns": columns, "rows": sec.get("rows") or [], "masked": [str(m) for m in sec.get("masked") or []],
            "alias": default_alias(name), "role": infer_role(columns)}


def _grain(grain: dict) -> dict:
    """The grain as the pages read it: the keys (``keys``, or ``one-row-per`` of an explicit de-duplication), what one row is, and which duplicate wins."""
    per = grain.get("one-row-per")
    keys = grain.get("keys") or ([per] if isinstance(per, str) else per) or []
    return {"says": grain.get("says") or "", "keys": [str(k) for k in keys], "date": grain.get("date") or "", "keep": str(grain.get("keep") or "")}


def _derived(doc: dict, columns: list[dict]) -> list[dict]:
    """The columns with the dataset's worked-out ones (``derive``) after them; a grouped dataset (``group``) has its ``by`` columns and the grouped ones."""
    out = list(columns)
    for name, d in (doc.get("derive") or {}).items() if isinstance(doc.get("derive"), dict) else []:
        d = d if isinstance(d, dict) else {"expr": str(d)}
        typ = d.get("type") or "decimal"
        out.append({"name": str(name), "type": typ, "role": d.get("role") or ("amount" if typ in ("decimal", "integer") and d.get("meaning") in ("amount", "notional", "mtm") else "dimension" if typ == "string" else ""),
                    "meaning": d.get("meaning") or "", "references": "", "currency": ((d.get("unit") or {}).get("currency") if isinstance(d.get("unit"), dict) else "") or "", "unitOf": "",
                    "additive": None, "aggregate": "", "format": d.get("format") or "", "path": "", "expr": d.get("expr") or "", "of": "", "description": d.get("description") or "",
                    "python": d.get("python") or "", "derived": True})
    group = doc.get("group") if isinstance(doc.get("group"), dict) else None
    if group and group.get("by"):
        by = [str(b) for b in group.get("by") or []]
        aggs = group.get("columns") if isinstance(group.get("columns"), dict) else {}
        known = {c["name"]: c for c in out}
        grouped = [dict(known.get(b) or {"name": b, "type": "string"}, role="dimension") for b in by]
        for name, agg in aggs.items():
            base = known.get(str(name)) or {"name": str(name), "type": "integer" if agg in ("count", "distinct") else "decimal"}
            grouped.append(dict(base, name=str(name), role="amount" if agg in ("sum", "count") else base.get("role") or "", aggregate="sum" if agg in ("sum", "count") else "avg" if agg == "avg" else agg))
        out = [dict({"references": "", "currency": "", "unitOf": "", "additive": None, "aggregate": "", "format": "", "path": "", "expr": "", "of": "", "description": "", "meaning": ""}, **c) for c in grouped]
    return out


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


# ---- the implicit model, written out ------------------------------------------------------------------------------------

def implicit_doc(info: dict, dims: list[dict], name: str = "", title: str = "") -> dict:
    """The implicit model of a dataset (spec section 3) as a model document: the dataset as a fact, the packs' dimensions one hop away with their
    relationships, and the default measures. *Make it a model* saves this as a draft model."""
    alias = info["alias"]
    datasets = {alias: {"table": info["name"], "role": "fact"}}                  # the table layer's model (rachana: 3): its tables by alias
    rels = []
    for col in info["columns"]:
        ref = col.get("references")
        dim = next((d for d in dims if kind_of(d) == ref and d["name"] != info["name"]), None) if ref else None
        if not dim:
            continue
        da = dim["alias"] if dim["alias"] not in datasets else f"{alias}_{col['name']}"
        if da in datasets:
            continue
        datasets[da] = {"table": dim["name"], "role": "dimension"}
        rels.append({"from": f"{alias}.{col['name']}", "to": f"{da}.id", "suggested": {"from": "pack-reference"}})
    measures = {}
    n, m = count_measure(alias)
    measures[n] = m
    for col in info["columns"]:
        if col.get("aggregate") or col.get("role") in MEASURE_ROLES:
            n, m = default_measure(alias, col)
            measures[n] = m
    slug = name or re.sub(r"[^a-z0-9-]+", "-", (info["name"].replace("pack.", "")).lower()).strip("-") or "model"
    return {"rachana": 3, "model": slug, "version": "1.0.0", "title": title or f"{info['title']} model", "implicit-from": info["name"],
            "tables": datasets, "relationships": rels, "measures": measures}


# ---- writing YAML -------------------------------------------------------------------------------------------------------

def _flow(value) -> str:
    text = yaml.safe_dump(value, default_flow_style=True, width=10 ** 6, allow_unicode=True, sort_keys=False).strip()
    return text[:-4].rstrip() if text.endswith("\n...") else text


def _key(k) -> str:
    return _flow(str(k))


def _block(key: str, value, out: list[str]) -> None:
    """One top-level key: maps of maps one entry per line (flow), lists of maps one item per line, anything else as PyYAML writes it."""
    if isinstance(value, dict) and value and all(isinstance(v, (dict, list, str, int, float, bool)) or v is None for v in value.values()) \
            and key in ("datasets", "measures", "hierarchies", "columns", "may-see-all", "derive", "fields", "perspectives", "parameters"):
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


ENTRY_KEYS = ("datasets", "measures", "hierarchies", "columns", "derive", "fields", "perspectives", "parameters")


def to_yaml_keeping(original: str, doc: dict, order=TOP_ORDER) -> str:
    """``doc`` written over the YAML it was read from (``original``), keeping its comments, key order and untouched lines (ARCHITECTURE.md §28 8.2);
    without an original it is :func:`to_yaml`."""
    if not isinstance(doc, dict) or not doc:
        raise LayerError("the document is empty")
    if not (original or "").strip():
        return to_yaml(doc, order)
    return yaml_roundtrip.merge(original, _clean(doc), order, _block, ENTRY_KEYS, lambda d: to_yaml(d, order))


# ---- the Fields pane of a report on a model -----------------------------------------------------------------------------

FIELD_PROPS = ("display", "description", "format", "folder", "hidden", "sort-by", "category", "summarise")


def fields_shape(described: dict, ref: str, model_doc: dict | None = None) -> dict:
    """A model's description (``/models/{id}/describe``) in the shape every Fields pane reads (the dataset summary: tables with columns, measures,
    hierarchies, perspectives), plus what the pane's header says about the model (implicit or not). Each field and measure carries the properties the
    model gives it (ARCHITECTURE.md §28 8.1: display name, description, format, folder, hidden, sort-by, data category, default summarisation): a consumer
    inherits them and never re-declares them. ``model_doc`` (the model's YAML, read when the server's description does not carry them yet) fills the gaps;
    hiding there is presentation only: what a reader may not see the server leaves out of ``describe`` (``hidden-for``)."""
    props = (model_doc or {}).get("fields") if isinstance((model_doc or {}).get("fields"), dict) else {}
    mdefs = (model_doc or {}).get("measures") if isinstance((model_doc or {}).get("measures"), dict) else {}
    tables = []
    for d in described.get("datasets") or []:
        alias = d.get("alias")
        cols = []
        for f in d.get("fields") or []:
            full = str(f.get("field", ""))
            name = full.split(".", 1)[-1]
            p = props.get(full) if isinstance(props.get(full), dict) else {}
            col = {"name": name, "type": f.get("type") or "string", "format": f.get("format") or p.get("format"), "masked": bool(f.get("masked")),
                   "description": f.get("description") or p.get("description") or "", "meaning": f.get("meaning") or "", "role": f.get("role") or "",
                   "references": f.get("references") or ""}
            for k in ("display", "folder", "hidden", "sort-by", "category", "summarise"):
                v = f.get(k, f.get({"sort-by": "sortBy", "summarise": "summarize"}.get(k, k), p.get(k)))
                if v not in (None, "", False):
                    col[k] = v
            cols.append(col)
        tables.append({"name": alias, "kind": d.get("role") or "fact", "entity": d.get("entity") or "", "dataset": d.get("dataset"), "version": d.get("version"),
                       "mode": d.get("mode"), "columns": cols})
    measures = []
    for m in described.get("measures") or []:
        p = mdefs.get(m.get("name")) if isinstance(mdefs.get(m.get("name")), dict) else {}
        out = {"name": m.get("name"), "expr": m.get("expr"), "format": m.get("format") or p.get("format"), "masked": bool(m.get("masked")), "implicit": bool(m.get("implicit")),
               "local": bool(m.get("local")), "additive": m.get("additive"), "description": m.get("description") or p.get("description") or ""}
        for k in ("folder", "hidden", "display"):
            v = m.get(k, p.get(k))
            if v not in (None, "", False):
                out[k] = v
        measures.append(out)
    model = described.get("model") if isinstance(described.get("model"), dict) else {}
    mid = str(model.get("id") or described.get("id") or "")
    implicit = mid.startswith("implicit:") or ref.startswith("dataset:")
    persp = described.get("perspectives") if isinstance(described.get("perspectives"), dict) else (model_doc or {}).get("perspectives") or {}
    if isinstance(described.get("perspectives"), list):                    # the server lists them by name; their fields come with ?perspective=<name>
        persp = {str(p.get("name")): {"title": p.get("title") or p.get("name"), "description": p.get("description") or "", "fields": None, "measures": None}
                 for p in described["perspectives"] if isinstance(p, dict) and p.get("name")}
    return {"id": mid, "title": described.get("title") or model.get("name") or ref, "description": described.get("description") or "", "tables": tables,
            "measures": measures, "hierarchies": described.get("hierarchies") or {}, "relationships": described.get("relationships") or [],
            "perspectives": {str(k): {"title": (v or {}).get("title") or str(k), "description": (v or {}).get("description") or "",
                                      "fields": None if (v or {}).get("fields", []) is None else list((v or {}).get("fields") or []),
                                      "measures": None if (v or {}).get("measures", []) is None else list((v or {}).get("measures") or [])}
                             for k, v in persp.items() if isinstance(v, dict) or v is None},
            # wave B: what a reader may set on the model (parameter slicers, Show as), as the server describes it
            "parameters": [p for p in described.get("parameters") or [] if isinstance(p, dict) and p.get("name")],
            "calcGroups": [g for g in described.get("calcGroups") or [] if isinstance(g, dict) and g.get("name")],
            "model": {"ref": ref, "id": mid, "name": model.get("name") or "", "version": model.get("version") or "", "implicit": implicit,
                      "dataset": ref[len("dataset:"):] if ref.startswith("dataset:") else ""}}


def needs_model_doc(described: dict) -> bool:
    """Whether the server's description lacks the model's field properties and perspectives (an older server): the console then reads the model's YAML."""
    if "perspectives" in described:
        return False
    return not any(any(k in f for k in ("display", "folder", "hidden", "sortBy", "sort-by", "category")) for d in described.get("datasets") or [] for f in d.get("fields") or [])


def report_body(slug: str, title: str, ref: str) -> str:
    """A new report bound to one model (spec 4.1): ``model:`` names it; a visual's query is a model query against it."""
    return yaml.safe_dump({"report": slug, "title": title, "model": ref, "pages": [{"id": "main", "title": "Main", "visuals": []}]}, sort_keys=False, allow_unicode=True)


# ---- the contract: what changed since the published version, and the version it needs ---------------------------------

_LEVEL = {"major": 3, "minor": 2, "patch": 1, "none": 0}


def report_measures(body: str) -> dict:
    """A report's own measures (``measures:``, spec 4.2) as ``{name: {expr, format?, description?}}``; a bare string is the expression."""
    raw = load(body).get("measures") or {}
    if not isinstance(raw, dict):
        raise LayerError("`measures:` maps a measure's name to its expression")
    out = {}
    for name, m in raw.items():
        m = {"expr": m} if isinstance(m, str) else m if isinstance(m, dict) else {}
        if m.get("expr"):
            out[str(name)] = {k: m[k] for k in ("expr", "format", "description") if m.get(k)}
    return out


def implicit_source(body: str) -> str | None:
    """The dataset a report's implicit model is made from (``model: dataset:<name>``, quoted or not, as the designer or a hand saves it), else None."""
    try:
        doc = yaml.safe_load(body or "")
    except yaml.YAMLError:
        doc = None
    ref = doc.get("model") if isinstance(doc, dict) else None
    if not isinstance(ref, str):
        m = re.search(r"""(?m)^model:\s*["']?(dataset:[^\s"']+)""", body or "")
        ref = m.group(1) if m else None
    ref = (ref or "").strip()
    return ref[len("dataset:"):] if ref.startswith("dataset:") and len(ref) > len("dataset:") else None


def rebind_report(body: str, ref: str) -> str:
    """The report's ``model:`` line pointed at another model (Make it a model rebinds the report to the model it wrote), the rest untouched."""
    if re.search(r"(?m)^model:\s*\S.*$", body or ""):
        return re.sub(r"(?m)^model:\s*\S.*$", "model: " + ref, body, count=1)
    return "model: " + ref + "\n" + (body or "")


def slug(text: str, fallback: str = "model") -> str:
    s = re.sub(r"[^a-z0-9]+", "-", str(text).lower()).strip("-")[:47]
    return s if re.match(r"[a-z][a-z0-9-]{1,47}$", s or "") else fallback
