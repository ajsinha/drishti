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

"""The plan: from schemas (and optionally real sample documents) decide, per kind, its name, key, links, labels, match column, date column,
strip, Sutras and mnemonic, and say what was uncertain. Every rule here is documented in docs/guides/SCHEMA_TO_PACK.md.

``make_plan(schemas, samples, overrides, settings)``:
  schemas    [(file name, text or dict)]            JSON Schema files (JSON or YAML)
  samples    {kind: [document, ...]}                real documents (from JSONL); a kind with samples and no schema is inferred from them
  overrides  {kind: {key, match, date, links, mnemonic, title, description, kind}}   what the user decided (UI cards, CLI flags)
"""
from __future__ import annotations

import copy
import re
from dataclasses import dataclass, field

from .resolve import Node, Resolver, SchemaDoc, SchemaError, parse_text

KIND_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
ISO_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
ISO_DATETIME = re.compile(r"^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}")
ID_NAME = re.compile(r"(^_?id$)|(Id$)|(_id$)|(^uuid$)|(^key$)|(Key$)|(_key$)")
LINK_NAME = re.compile(r"^(.+?)(Ids?|_ids?|Keys?|_keys?|Refs?|_refs?)$")
DATE_PREFERRED = ("businessdate", "business_date", "asof", "asofdate", "as_of", "as_of_date", "reportingdate", "date", "valuationdate", "tradedate")
STATUS_NAMES = ("status", "state", "stage", "phase", "lifecycle")
DEFAULTS = {"strip_max": 6, "enum_max": 40, "samples": 5, "match_max_values": 24, "match_cross_max": 40, "distinct_enum_max": 12}


# ----------------------------------------------------------------------------------------------- small helpers

def norm(s: str) -> str:
    return re.sub(r"[^a-z0-9]", "", str(s).lower())


def singular(s: str) -> str:
    if s.lower().endswith("ies") and len(s) > 4:
        return s[:-3] + "y"
    return s[:-1] if s.endswith("s") and len(s) > 3 and not s.endswith("ss") else s


def words(name: str) -> list[str]:
    s = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", name)
    s = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1 \2", s)
    return [w for w in re.split(r"[\s_\-.\[\]]+", s) if w]


def humanize(name: str) -> str:
    """tradeDate -> Trade date; counterparty_id -> Counterparty ID; dv01 -> DV01; mtm -> MTM-like short words stay as typed in capitals."""
    ws = words(name)
    out = []
    for i, w in enumerate(ws):
        if w.lower() == "id":
            out.append("ID")
        elif w.isupper() or re.fullmatch(r"[a-z]{1,3}\d+", w) or (len(w) <= 4 and not re.search(r"[aeiouy]", w.lower())):
            out.append(w.upper())
        else:
            out.append(w.lower() if i else w.capitalize())
    return " ".join(out) or name


def slug(text: str) -> str:
    s = re.sub(r"([a-z0-9])([A-Z])", r"\1-\2", str(text))
    s = re.sub(r"[^A-Za-z0-9]+", "-", s).strip("-").lower()
    return s[:48].strip("-")


def literal(v) -> str:
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (int, float)):
        return repr(v)
    return "'" + str(v).replace("\\", "\\\\").replace("'", "\\'") + "'"


def kind_from_names(title: str, ident: str, stem: str, explicit: str = "") -> tuple[str, str]:
    """(kind, why): x-drishti-kind, else a short title, else the $id's last segment, else the file name."""
    if explicit and KIND_RE.fullmatch(explicit):
        return explicit, "x-drishti-kind"
    t = slug(title) if title and len(title.split()) <= 3 else ""
    if t and KIND_RE.fullmatch(t):
        return t, "the schema title"
    last = re.sub(r"(\.schema)?\.(json|ya?ml)$", "", ident.rstrip("/").split("/")[-1], flags=re.I) if ident else ""
    if last and last.lower() not in ("schema", "root") and KIND_RE.fullmatch(slug(last) or "!"):
        return slug(last), "the schema $id"
    s = slug(stem) or "document"
    return s, "the file name"


# ----------------------------------------------------------------------------------------------- the plan's data

@dataclass(eq=False)
class FieldInfo:
    path: str                    # dotted; arrays of objects as legs[].pv
    name: str
    label: str
    description: str = ""
    type: str = ""
    format: str = ""
    required: bool = False
    fmt: str = ""                # a Sutra format name
    unit: str = ""
    enum: list = field(default_factory=list)
    role: str = ""               # status | dimension | date | datetime | id | link | text | number | flag | table | map | object
    hidden: bool = False
    importance: int = 0
    firm: bool = False           # fmt comes from the schema (x-drishti-format, a date format, a 0..1 range), not from a name guess
    values: dict = field(default_factory=dict)   # x-drishti-values: what each enum value means


@dataclass
class Link:
    field: str
    to: str
    why: str
    many: bool = False


@dataclass
class SutraPlan:
    name: str
    label: str
    where: str = ""              # the match.where expression, "" for the catch-all
    values: dict = field(default_factory=dict)   # match field -> value
    variant: Node | None = None
    priority: int = 10
    fallback: bool = False


@dataclass
class KindPlan:
    kind: str
    orig: str = ""            # the name before the user renamed it: overrides are keyed by it
    kind_why: str = ""
    title: str = ""
    description: str = ""
    source: str = ""             # the schema file, "" when inferred from samples only
    node: Node | None = None
    key: str | None = None
    key_why: str = ""
    key_candidates: list = field(default_factory=list)
    key_ambiguous: bool = False
    links: list = field(default_factory=list)
    match: list = field(default_factory=list)
    match_why: str = ""
    match_options: list = field(default_factory=list)   # [{field, values, sutras}]
    date: str | None = None
    date_why: str = ""
    mnemonic: str = ""
    fields: list = field(default_factory=list)
    index: dict = field(default_factory=dict)
    strip: list = field(default_factory=list)           # field paths
    controls: list = field(default_factory=list)        # dropdown suggestions (hook for the controls feature)
    sutras: list = field(default_factory=list)
    samples: list = field(default_factory=list)         # real documents
    warnings: list = field(default_factory=list)
    connector: str = ""
    template: str = "samples"

    @property
    def real(self) -> bool:
        return bool(self.samples)

    def label_of(self, path: str) -> str:
        f = self.index.get(path)
        return f.label if f else humanize(path.split(".")[-1].replace("[]", ""))


@dataclass
class Plan:
    kinds: list = field(default_factory=list)
    pack: dict = field(default_factory=dict)
    warnings: list = field(default_factory=list)
    settings: dict = field(default_factory=dict)

    def kind(self, name: str) -> KindPlan | None:
        return next((k for k in self.kinds if k.kind == name), None)

    @property
    def sutra_count(self) -> int:
        return sum(len(k.sutras) for k in self.kinds)

    def edges(self) -> list:
        return [{"from": k.kind, "to": l.to, "field": l.field, "why": l.why} for k in self.kinds for l in k.links]

    def to_dict(self) -> dict:
        return {"pack": self.pack, "warnings": self.warnings, "sutraCount": self.sutra_count, "edges": self.edges(),
                "kinds": [kind_dict(k) for k in self.kinds]}


def kind_dict(k: KindPlan) -> dict:
    return {"kind": k.kind, "origKind": k.orig or k.kind, "kindWhy": k.kind_why, "title": k.title, "description": k.description, "source": k.source,
            "key": k.key, "keyWhy": k.key_why, "keyCandidates": k.key_candidates, "keyAmbiguous": k.key_ambiguous,
            "links": [vars(l) for l in k.links], "match": k.match, "matchWhy": k.match_why, "matchOptions": k.match_options,
            "date": k.date, "dateWhy": k.date_why, "mnemonic": k.mnemonic, "strip": k.strip, "controls": k.controls,
            "connector": k.connector, "template": k.template, "samples": len(k.samples), "synthetic": not k.real,
            "sutras": [{"name": s.name, "label": s.label, "where": s.where, "fallback": s.fallback} for s in k.sutras],
            "fields": [{"path": f.path, "label": f.label, "description": f.description, "type": f.type, "format": f.format, "required": f.required,
                        "fmt": f.fmt, "unit": f.unit, "enum": f.enum, "role": f.role} for f in k.fields],
            "warnings": k.warnings}


# ----------------------------------------------------------------------------------------------- nodes from sample documents

def node_from_docs(docs: list[dict], depth: int = 0) -> Node:
    """A schema-like Node inferred from documents: types, enums of repeated short strings, dates, and required = in 95% of the documents."""
    n = Node(type="object")
    seen: dict[str, list] = {}
    for d in docs:
        for k, v in d.items():
            seen.setdefault(k, []).append(v)
    total = len(docs)
    for k, vals in seen.items():
        n.props[k] = _node_of_values(vals, depth)
        if len(vals) >= 0.95 * total and all(v is not None for v in vals):
            n.required.add(k)
    return n


def _node_of_values(vals: list, depth: int) -> Node:
    vals = [v for v in vals if v is not None]
    n = Node()
    if not vals:
        return n
    if all(isinstance(v, bool) for v in vals):
        n.type = "boolean"
    elif all(isinstance(v, int) and not isinstance(v, bool) for v in vals):
        n.type = "integer"
    elif all(isinstance(v, (int, float)) and not isinstance(v, bool) for v in vals):
        n.type = "number"
    elif all(isinstance(v, str) for v in vals):
        n.type = "string"
        if all(ISO_DATE.match(v) for v in vals):
            n.format = "date"
        elif all(ISO_DATETIME.match(v) for v in vals):
            n.format = "date-time"
        else:
            distinct = list(dict.fromkeys(vals))
            if len(vals) >= 6 and len(distinct) <= min(DEFAULTS["distinct_enum_max"], len(vals) // 2) and max(len(v) for v in distinct) <= 32:
                n.enum = distinct
    elif all(isinstance(v, dict) for v in vals) and depth < 6:
        n = node_from_docs(vals, depth + 1)
    elif all(isinstance(v, list) for v in vals):
        n.type = "array"
        items = [i for v in vals for i in v][:200]
        n.items = node_from_docs(items, depth + 1) if items and all(isinstance(i, dict) for i in items) else _node_of_values(items, depth + 1)
    else:
        n.type = "string"
    n.examples = list(dict.fromkeys(v for v in vals if isinstance(v, (str, int, float)) and not isinstance(v, bool)))[:3]
    return n


# ----------------------------------------------------------------------------------------------- fields

def _fmt_for(f: FieldInfo, n: Node) -> str:
    named = n.ann.get("format")
    if isinstance(named, str) and named:
        return {"money": "amount2", "percent": "pct2", "currency": "amount2", "integer": "amount0"}.get(named.lower(), named)
    if n.format == "date":
        return "date"
    if n.format == "date-time":
        return "date"
    nm = norm(f.name)
    if n.type == "integer":
        return "amount0" if not re.search(r"year|version|count$|^n[A-Z]|rank|level", f.name) else ""
    if n.type == "number":
        if f.unit in ("%", "percent") or (n.minimum is not None and n.maximum is not None and 0 <= n.minimum and n.maximum <= 1):
            return "pct2"
        if re.search(r"(pct|percent|ratio|rate|yield)$", nm) and (n.maximum is None or n.maximum <= 1):
            return "pct2"
        if re.search(r"(mtm|pnl|pl|delta|change|diff)", nm):
            return "signed0"
        return "amount2" if re.search(r"(price|rate|spread|amount|fee|cost|notional)", nm) else "amount0"
    return ""


def _role_of(f: FieldInfo, n: Node) -> str:
    r = n.ann.get("role")
    if isinstance(r, str) and r:
        return r
    if n.type == "array":
        return "table" if n.items is not None and (n.items.props or n.items.type == "object") else "list"
    if n.type == "object":
        return "map" if (not n.props and n.additional is not None) else "object"
    if n.format == "date":
        return "date"
    if n.format == "date-time":
        return "datetime"
    if n.enum and n.type in ("string", "integer", ""):
        return "status" if norm(f.name) in STATUS_NAMES or norm(f.name).endswith(STATUS_NAMES) else "dimension"
    if n.type == "boolean":
        return "flag"
    if n.type in ("integer", "number"):
        return "number"
    if n.type == "string":
        return "text"
    return ""


def _walk(kp: KindPlan, n: Node, prefix: str = "", in_array: bool = False, depth: int = 0) -> None:
    for name, c in n.props.items():
        path = f"{prefix}{name}"
        f = FieldInfo(path=path, name=name, label=str(c.ann.get("label") or c.title or humanize(name)), description=c.description.strip(),
                      type=c.type, format=c.format, required=name in n.required and not in_array, unit=str(c.ann.get("unit") or ""),
                      enum=list(c.enum)[:DEFAULTS["enum_max"]], hidden=bool(c.ann.get("hidden")))
        f.role = _role_of(f, c)
        f.fmt = _fmt_for(f, c) if c.scalar else ""
        f.firm = bool(f.fmt) and (bool(c.ann.get("format")) or c.format in ("date", "date-time") or f.fmt == "pct2" and f.unit in ("%", "percent")
                                  or (c.minimum is not None and c.maximum is not None and 0 <= c.minimum and c.maximum <= 1))
        if isinstance(c.ann.get("values"), dict):
            f.values = {str(k): str(v) for k, v in c.ann["values"].items()}
        if f.unit and f.fmt in ("amount0", "amount2") and f.unit not in f.label:
            pass
        f.importance = (4 if f.required else 0) + (3 if f.description else 0) + (2 if f.role == "number" else 0) + int(c.ann.get("importance") or 0)
        kp.fields.append(f)
        kp.index[path] = f
        if depth < 6:
            if c.type == "object" and c.props:
                _walk(kp, c, path + ".", in_array, depth + 1)
            elif c.type == "array" and c.items is not None and c.items.props:
                _walk(kp, c.items, path + "[].", True, depth + 1)
                f.role = "table"
            elif c.type == "object" and c.additional is not None and c.additional.scalar and c.additional.type in ("number", "integer"):
                f.role = "map"


# ----------------------------------------------------------------------------------------------- the rules

def _scalar_top(n: Node):
    return [(k, v) for k, v in n.props.items() if v.type in ("string", "integer") and not v.enum]


def pick_key(kp: KindPlan, others: dict, ov_key: str | None) -> None:
    n = kp.node
    docs = kp.samples

    def unique(f: str) -> bool:
        vals = [d.get(f) for d in docs]
        return bool(docs) and all(isinstance(v, (str, int)) and not isinstance(v, bool) and v != "" for v in vals) and len(set(vals)) == len(vals)

    if ov_key:
        kp.key, kp.key_why = ov_key, "chosen by you"
        if ov_key not in n.props and not any(ov_key in d for d in docs):
            kp.warnings.append(f"the key '{ov_key}' is not a field of {kp.kind}")
        return
    tagged = [k for k, v in n.props.items() if v.ann.get("role") == "key" or v.ann.get("key") is True]
    if tagged:
        kp.key, kp.key_why = tagged[0], "x-drishti-role: key"
        if len(tagged) > 1:
            kp.warnings.append(f"more than one field is marked x-drishti-role: key ({', '.join(tagged)}); using {tagged[0]}")
        return
    cands = [k for k, v in _scalar_top(n) if ID_NAME.search(k) or v.format == "uuid"]
    req = [k for k in cands if k in n.required]
    pool = req or cands
    if docs:
        uniq = [k for k in pool if unique(k)]
        if uniq:
            pool = uniq
        elif pool:
            kp.warnings.append(f"no id-like field is unique in all {len(docs)} sample documents: {', '.join(pool)}")
    kp.key_candidates = pool or [k for k, v in n.props.items() if v.scalar and k in n.required]
    kn = norm(kp.kind)
    for want, why in (("id", "named id"), (kn + "id", "named <kind>Id"), (singular(kn) + "id", "named <kind>Id"), (kn + "key", "named <kind>Key")):
        hit = next((k for k in pool if norm(k) == want), None)
        if hit:
            kp.key, kp.key_why = hit, f"{why}" + (", required" if hit in n.required else "")
            return
    plain = [k for k in pool if not _is_link_name(k, others, kp.kind)]
    if len(plain) == 1:
        kp.key, kp.key_why = plain[0], "the one required id-like field that is not a reference to another kind"
        return
    choice = plain or pool
    if choice:
        kp.key, kp.key_why = choice[0], "the first id-like field"
        kp.key_ambiguous = len(choice) > 1
        if kp.key_ambiguous:
            kp.warnings.append(f"ambiguous key: {', '.join(choice)} all look like ids; using {choice[0]} (choose another with x-drishti-role: key or --key)")
        return
    kp.key_ambiguous = True
    kp.warnings.append(f"no field of {kp.kind} looks like an id: choose the key (x-drishti-role: key on a property, or --key)")


def _is_link_name(name: str, others: dict, own: str) -> bool:
    m = LINK_NAME.match(name)
    if not m:
        return False
    stem = norm(singular(m.group(1)))
    return any(stem in (norm(k), norm(singular(k)), norm(o.orig), norm(singular(o.orig))) for k, o in others.items() if k != own)


def find_links(kp: KindPlan, kinds: dict, ov_links: dict) -> None:
    n = kp.node
    keys = {k.kind: {str(d.get(k.key)) for d in k.samples if k.key and d.get(k.key) is not None} for k in kinds.values()}
    done = set()
    for path, f in kp.index.items():
        c = _node_at(n, path)
        if c is None or path == kp.key:
            continue
        explicit = ov_links.get(path, c.ann.get("link"))
        if path in ov_links and not ov_links[path]:
            continue                                  # the user removed it
        target, why = None, ""
        many = c.type == "array"
        elem = c.items if many and c.items is not None else c
        if isinstance(explicit, str) and explicit:
            target, why = explicit, "x-drishti-link" if path not in ov_links else "chosen by you"
        elif "." not in path and "[" not in path and elem.type == "string" and not elem.enum:
            m = LINK_NAME.match(f.name)
            if m:
                stem = norm(singular(m.group(1)))
                hit = next((k for k, o in kinds.items() if k != kp.kind and stem in (norm(k), norm(singular(k)), norm(o.orig), norm(singular(o.orig)))), None)
                if hit:
                    target, why = hit, f"named {m.group(1)}{m.group(2)}, and {hit} is a kind in this batch"
            if not target:
                hit = next((k for k, o in kinds.items() if k != kp.kind and norm(f.name) in (norm(k), norm(singular(k)), norm(o.orig), norm(singular(o.orig)))), None)
                if hit and kinds[hit].key:
                    target, why = hit, f"named like the kind {hit}"
            if not target and kp.samples:
                vals = {str(x) for d in kp.samples for x in (d.get(path) if isinstance(d.get(path), list) else [d.get(path)]) if x is not None}
                if len(vals) >= 3:
                    for k, ks in keys.items():
                        if k != kp.kind and ks and len(vals & ks) >= 0.9 * len(vals):
                            target, why = k, f"its values are keys of {k}"
                            break
        if target and path not in done:
            if target not in kinds and target != kp.kind:
                kp.warnings.append(f"{path} links to '{target}', which is not a kind in this batch (the link still works if another pack defines it)")
            kp.links.append(Link(path, target, why, many))
            done.add(path)
            f.role = "link"


def _node_at(n: Node, path: str) -> Node | None:
    cur = n
    for part in path.split("."):
        arr = part.endswith("[]")
        part = part[:-2] if arr else part
        cur = cur.props.get(part) if cur is not None else None
        if cur is None:
            return None
        if arr:
            cur = cur.items
    return cur


def pick_date(kp: KindPlan, ov: dict) -> None:
    n = kp.node
    if "date" in ov:
        kp.date, kp.date_why = ov["date"] or None, "chosen by you" if ov["date"] else "none"
        return
    tagged = [k for k, v in n.props.items() if v.ann.get("role") in ("date", "business-date", "businessDate") or v.ann.get("date") is True]
    cand = [k for k, v in n.props.items() if v.format in ("date", "date-time") or (v.type == "string" and kp.index.get(k) and kp.index[k].role in ("date", "datetime"))]
    if tagged:
        kp.date, kp.date_why = tagged[0], "x-drishti-role: date"
        return
    for want in DATE_PREFERRED:
        hit = next((k for k in cand if norm(k) == want), None)
        if hit:
            kp.date, kp.date_why = hit, "a date field named like a business date"
            return
    req = [k for k in cand if k in n.required]
    if len(req) == 1 or (req and kp.real):
        kp.date, kp.date_why = req[0], "the required date field"
        return
    kp.date_why = "no date field is named like a business date: no dated store is written" if not cand else \
        f"several date fields ({', '.join(cand[:4])}) and none named like a business date: choose one to get a dated store"


def match_options(kp: KindPlan) -> list:
    out = []
    n = kp.node
    for name, c in n.props.items():
        if c.type in ("string", "integer") and (c.enum or kp.real):
            vals = list(c.enum)
            if not vals and kp.real:
                seen = list(dict.fromkeys(d.get(name) for d in kp.samples if isinstance(d.get(name), (str, int)) and not isinstance(d.get(name), bool)))
                vals = seen if 1 < len(seen) <= DEFAULTS["match_max_values"] else []
            if 1 < len(vals) <= DEFAULTS["match_max_values"] and name != kp.key and name != kp.date:
                out.append({"field": name, "values": vals, "sutras": len(vals) + 1})
    return out


def pick_match(kp: KindPlan, ov: dict) -> None:
    n = kp.node
    kp.match_options = match_options(kp)
    if "match" in ov:
        m = ov["match"]
        kp.match = [x for x in ([m] if isinstance(m, str) else m or []) if x]
        kp.match_why = "chosen by you" if kp.match else "none"
        return
    if n.variants and n.discriminator:
        kp.match, kp.match_why = [n.discriminator], f"oneOf with discriminator '{n.discriminator}': one Sutra per variant"
        return
    tagged = [k for k, v in n.props.items() if v.ann.get("match") is True or v.ann.get("role") == "match"]
    if tagged:
        kp.match, kp.match_why = tagged[:2], "x-drishti-match"
        return
    kp.match_why = "one Sutra for the kind" + (f" (it can be split by {', '.join(o['field'] for o in kp.match_options[:3])})" if kp.match_options else "")


def build_sutras(kp: KindPlan, name_prefix: str = "") -> None:
    n = kp.node
    kp.sutras = []
    fields = [f for f in kp.match]
    if not fields:
        kp.sutras = [SutraPlan(f"{name_prefix}{kp.kind}-default", kp.title, priority=1, fallback=True)]
        return
    value_sets = []
    for f in fields:
        c = n.props.get(f)
        vals = list(c.enum) if c is not None and c.enum else []
        if not vals and kp.real:
            vals = list(dict.fromkeys(d.get(f) for d in kp.samples if isinstance(d.get(f), (str, int, float, bool))))
        if not vals:
            kp.warnings.append(f"the match field '{f}' has no known values (no enum in the schema, no samples): only the catch-all Sutra is written")
        value_sets.append(vals)
    combos = [()]
    for vals in value_sets:
        combos = [c + (v,) for c in combos for v in vals][:DEFAULTS["match_cross_max"]]
    if all(value_sets):
        used: set = set()
        for combo in combos:
            where = " && ".join(f"$.{f} == {literal(v)}" for f, v in zip(fields, combo))
            sl = slug("-".join(str(v) for v in combo)) or "x"
            nm, i = f"{name_prefix}{kp.kind}-{sl}", 2
            while nm in used:
                nm, i = f"{nm}-{i}", i + 1
            used.add(nm)
            variant = None
            if n.variants and len(fields) == 1 and fields[0] == n.discriminator:
                variant = next((b for _, b in n.variants if _variant_value(b, fields[0]) == combo[0]), None)
            label = ", ".join(f"{humanize(f)} {v}" for f, v in zip(fields, combo))
            kp.sutras.append(SutraPlan(nm, label, where, dict(zip(fields, combo)), variant, priority=10))
    kp.sutras.append(SutraPlan(f"{name_prefix}{kp.kind}-default", kp.title, priority=1, fallback=True))


def _variant_value(b: Node, disc: str):
    p = b.props.get(disc)
    if p is None:
        return None
    return p.const if p.has_const else (p.enum[0] if len(p.enum) == 1 else None)


def pick_strip(kp: KindPlan, max_n: int) -> None:
    """The status first, then numbers: required and described first (see SCHEMA_TO_PACK.md, 'The strip')."""
    top = [f for f in kp.fields if "." not in f.path and "[" not in f.path and not f.hidden and f.path != kp.key]
    forced = [f for f in kp.fields if not f.hidden and _node_at(kp.node, f.path) is not None and _node_at(kp.node, f.path).ann.get("strip") is True]
    banned = {f.path for f in kp.fields if _node_at(kp.node, f.path) is not None and _node_at(kp.node, f.path).ann.get("strip") is False}
    status = [f for f in top if f.role == "status" and f.path not in banned][:1]
    links = {l.field for l in kp.links}
    nums = [f for f in kp.fields if f.role == "number" and "[" not in f.path and not f.hidden and f.path not in links and f.path not in banned
            and not _is_ordinal(f)]
    nums = sorted(nums, key=lambda f: (-f.importance, f.path.count("."), kp.fields.index(f)))
    dates = [f for f in top if f.role in ("date",) and f.required and f.path != kp.date][:1]
    chosen = list(dict.fromkeys(forced + status + nums + dates))
    kp.strip = [f.path for f in chosen[:max_n]]


def _is_ordinal(f: FieldInfo) -> bool:
    """A year, a version or a rank is a label, not an amount."""
    return bool(re.search(r"(^|[a-z])(year|version|rank|index|order|seq)$", f.name, re.I))


def pick_controls(kp: KindPlan) -> None:
    """Hook for the controls feature (RUPAKA 8.1, being designed): enum fields are suggested as dropdowns, nothing is written to a Sutra yet."""
    kp.controls = []
    for f in kp.fields:
        if f.enum and "[" not in f.path:
            c = _node_at(kp.node, f.path)
            kind = str(c.ann.get("control") or "dropdown") if c is not None else "dropdown"
            kp.controls.append({"field": f.path, "control": kind, "label": f.label, "options": f.enum})


def mnemonic_of(kind: str, taken: set) -> str:
    ws = [w for w in re.split(r"[-_.\s]+", kind) if w]
    m = ("".join(w[0] for w in ws) if len(ws) > 1 else kind[:3]).upper()
    m = re.sub(r"[^A-Z0-9]", "", m) or "K"
    base, i = m, 2
    while m in taken:
        m, i = f"{base}{i}", i + 1
    taken.add(m)
    return m


# ----------------------------------------------------------------------------------------------- the entry point

def load_docs(schemas: list) -> tuple[list[SchemaDoc], list[str]]:
    docs, bad = [], []
    for name, body in schemas:
        try:
            data = body if isinstance(body, dict) else parse_text(body, name)
            docs.append(SchemaDoc(name, data))
        except SchemaError as e:
            bad.append(str(e))
    return docs, bad


def make_plan(schemas: list, samples: dict | None = None, overrides: dict | None = None, settings: dict | None = None,
              existing_mnemonics: set | None = None, pack_name: str = "") -> Plan:
    cfg = {**DEFAULTS, **(settings or {})}
    samples = {k: v for k, v in (samples or {}).items() if v}
    overrides = overrides or {}
    plan = Plan(settings=cfg)
    docs, bad = load_docs(schemas)
    plan.warnings += bad
    res = Resolver(docs)
    kinds: dict[str, KindPlan] = {}
    for d in docs:
        top = res.node(d.data, d)
        if not (top.props or top.variants or top.type == "object"):
            plan.warnings.append(f"{d.name}: no object schema found at the top (properties, allOf or oneOf); skipped")
            continue
        ann = top.ann
        kind, why = kind_from_names(top.title, d.ident, d.stem, str(ann.get("kind") or ""))
        base, i = kind, 2
        while kind in kinds:
            kind, i = f"{base}-{i}", i + 1
        if kind != base:
            plan.warnings.append(f"{d.name}: the kind '{base}' is already taken by {kinds[base].source}; this one is '{kind}' (rename it on its card)")
        kp = KindPlan(kind=kind, kind_why=why, title=top.title or humanize(kind), description=top.description.strip(), source=d.name, node=top)
        kinds[kind] = kp
    for k, docs_k in samples.items():                       # real samples: attach to a kind of the batch, or make a kind of their own
        hit = _kind_for(k, kinds)
        if hit:
            kinds[hit].samples = docs_k
        else:
            kinds[k] = KindPlan(kind=k, kind_why="the file name", title=humanize(k), node=node_from_docs(docs_k), samples=docs_k)
    plan.warnings += res.warnings
    for kp in kinds.values():
        kp.orig = kp.kind
    for k, ov in list(overrides.items()):                   # renames first
        if k in kinds and ov.get("kind") and ov["kind"] != k and KIND_RE.fullmatch(ov["kind"]):
            kp = kinds.pop(k)
            kp.kind, kp.kind_why = ov["kind"], "chosen by you"
            kinds[kp.kind] = kp
            overrides[kp.kind] = ov
    for kp in kinds.values():
        ov = overrides.get(kp.kind, {})
        _walk(kp, kp.node)
        if ov.get("title"):
            kp.title = ov["title"]
        if ov.get("description") is not None:
            kp.description = ov["description"]
    for kp in kinds.values():
        pick_key(kp, kinds, overrides.get(kp.kind, {}).get("key"))
    for kp in kinds.values():
        ov = overrides.get(kp.kind, {})
        find_links(kp, kinds, ov.get("links") or {})
        pick_date(kp, ov)
        pick_match(kp, ov)
    taken = set(existing_mnemonics or ())
    for kp in kinds.values():
        ov = overrides.get(kp.kind, {})
        want = str(ov.get("mnemonic") or "").upper()
        if want and re.fullmatch(r"[A-Z][A-Z0-9]{1,7}", want) and want not in taken:
            taken.add(want)
            kp.mnemonic = want
        else:
            if want:
                kp.warnings.append(f"the mnemonic {want} is taken or not valid; using a free one")
            kp.mnemonic = mnemonic_of(kp.kind, taken)
        kp.template = ov.get("template") or ("delta" if kp.real and kp.date else "samples")
        kp.connector = str(ov.get("connector") or "")
        pick_strip(kp, int(cfg["strip_max"]))
        pick_controls(kp)
        build_sutras(kp, "")
        undescribed = [f.path for f in kp.fields if not f.description and "[" not in f.path and f.role not in ("object", "table")]
        if undescribed and kp.source:
            kp.warnings.append(f"{len(undescribed)} field(s) have no description (About-this-page text will say TODO): {', '.join(undescribed[:5])}"
                               + (" ..." if len(undescribed) > 5 else ""))
        if kp.real and kp.source:
            extra = sorted({k for d in kp.samples[:200] for k in d} - set(kp.node.props))
            if extra:
                kp.warnings.append(f"fields in the samples that the schema does not describe: {', '.join(extra[:8])}")
    plan.kinds = list(kinds.values())
    synthetic = [k.kind for k in plan.kinds if not k.real]
    if synthetic:
        plan.warnings.append(f"no real documents for {', '.join(synthetic)}: their previews and tests use synthetic samples generated from the schema, marked synthetic")
    plan.pack = suggest_pack(plan.kinds, pack_name)
    _tidy_warnings(plan)
    return plan


def _kind_for(name: str, kinds: dict) -> str | None:
    n = norm(name)
    for k in kinds:
        if norm(k) == n or norm(singular(k)) == norm(singular(name)):
            return k
    for k in kinds:
        if len(n) > 3 and (norm(k) in n or n in norm(k)):
            return k
    return None


def match_kind(file_stem: str, kinds: list[str]) -> str | None:
    """Which kind a JSONL file's rows belong to, by its name (trades.jsonl -> trade); None when no kind fits."""
    return _kind_for(file_stem, {k: None for k in kinds})


def suggest_pack(kinds: list, name: str = "") -> dict:
    nm = slug(name) or (slug(kinds[0].kind) + "-pack" if len(kinds) == 1 else "my-pack")
    ws = [w for w in nm.split("-") if w]
    code = ("".join(w[0] for w in ws) if len(ws) > 1 else nm[:4]).upper()
    titles = ", ".join(k.title for k in kinds[:4]) + (" ..." if len(kinds) > 4 else "")
    return {"name": nm, "code": code, "title": humanize(nm.replace("-", " ")) if nm else "", "version": "1.0.0",
            "description": f"Generated from schemas: {titles}." if kinds else "",
            "connector": connector_name(nm)}


def connector_name(pack: str) -> str:
    """The suggested logical name of the pack's connector; a pack refers to its sources by this name only."""
    return f"{pack}-store"[:60]


def _tidy_warnings(plan: Plan) -> None:
    for k in plan.kinds:
        for w in k.warnings:
            plan.warnings.append(f"{k.kind}: {w}")
    plan.warnings = list(dict.fromkeys(plan.warnings))


def clone_plan(plan: Plan) -> Plan:
    return copy.deepcopy(plan)
