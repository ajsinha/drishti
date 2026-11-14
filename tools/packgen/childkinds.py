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

"""Child kinds: an array of objects in a document (``LEGS``, ``CASHFLOWS``) made a kind of its own, one row per element
(docs/guides/BUILD_GUIDE.md section 8.1.4, docs/guides/DATA_LAKE_GUIDE.md "Child kinds").

A child row is the element's own fields with two more in front: its **id** (``LEG_KEY``: the parent's id, ``:``, and the element's natural
key when every element has a unique one such as ``LEG_ID``, else its index in the array) and the **link** to its parent (``TRADE_ID``: the
parent's id). The parent keeps the whole array: its screen still shows it as a sub-table.

One implementation, used by everything that reads or loads the documents: ``pack new``'s full pass (tools/packgen/packprofile.py) profiles
the child rows as it reads each parent, and the loader (tools/ingest_jsonl.py, ``data ingest`` and ``pack new --load``) writes them beside
the parent, from the same ``explode``. A pack says its child kinds in ``pack.yaml``'s ``ingest:`` block::

    ingest:
      trade: { key: TRADE_ID, date: BUSINESS_DATE }
      trade-leg: { key: LEG_KEY, child-of: { kind: trade, field: LEGS, link: TRADE_ID, natural: LEG_ID } }

Which arrays become child kinds: the ones ``--child`` names (``LEGS,CASHFLOWS`` or ``trade=LEGS;quote=TICKS``; ``none`` for none), else
every top-level array of objects with at least ``--child-min-avg`` elements on average (2 by default; 0 for none) in the first rows.
"""
from __future__ import annotations

import re
from dataclasses import dataclass

MIN_AVG = 2.0
NONE = ("none", "-")
ID_LIKE = re.compile(r"(?i)(^|_)(id|ref|key|no|num|number|seq|code)$")
SEP = ":"


@dataclass(frozen=True)
class Child:
    """One child kind: the parent's array ``field`` as the kind ``kind``, its id field ``key`` and its link field ``link``."""
    parent: str
    field: str
    kind: str
    key: str
    link: str
    parent_key: str
    natural: str | None = None

    def ingest(self) -> dict:
        """The child's entry in pack.yaml's ``ingest:`` block."""
        of = {"kind": self.parent, "field": self.field, "link": self.link, **({"natural": self.natural} if self.natural else {})}
        return {"key": self.key, "child-of": of}

    def words(self) -> str:
        how = f"{self.parent_key}{SEP}{self.natural}" if self.natural else f"{self.parent_key}{SEP}index"
        return f"{self.field} -> {self.kind}: one row per element, id {self.key} ({how}), link {self.link} -> {self.parent}"


def singular(word: str) -> str:
    """LEGS -> LEG, CASHFLOWS -> CASHFLOW, ENTRIES -> ENTRY, ADDRESSES -> ADDRESS; a word that is not plural stays as it is."""
    w = word
    up = w.upper()
    if up.endswith("IES") and len(w) > 4:
        return w[:-3] + ("Y" if w[-1].isupper() else "y")
    if re.search(r"(?i)(ss|x|ch|sh)es$", w):
        return w[:-2]
    if up.endswith("S") and not up.endswith("SS") and len(w) > 2:
        return w[:-1]
    return w


def plural(word: str) -> str:
    """trade -> trades, entry -> entries, address -> addresses (the BI tables' and measures' words)."""
    if re.search(r"[^aeiou]y$", word):
        return word[:-1] + "ies"
    if re.search(r"(s|x|ch|sh)$", word):
        return word + "es"
    return word + "s"


def kind_of(parent: str, field: str) -> str:
    """trade + LEGS -> trade-leg; trade + CASH_FLOWS -> trade-cash-flow."""
    return f"{parent}-{re.sub(r'[^a-z0-9]+', '-', singular(field).lower()).strip('-')}"


def link_of(parent: str, parent_key: str) -> str:
    """The link field's name: the parent's id field (TRADE_ID); a bare ``id`` becomes TRADE_ID after the parent kind."""
    last = parent_key.split(".")[-1]
    return last if last.lower() != "id" else re.sub(r"[^A-Z0-9]+", "_", parent.upper()).strip("_") + "_ID"


def key_of(field: str) -> str:
    """The child's id field: LEGS -> LEG_KEY."""
    return re.sub(r"[^A-Z0-9]+", "_", singular(field).upper()).strip("_") + "_KEY"


def arrays(docs: list[dict]) -> dict[str, dict]:
    """Every top-level array of objects in the documents: how many documents have it, its elements, their average, its natural key."""
    out: dict[str, dict] = {}
    for d in docs:
        for k, v in d.items():
            if isinstance(v, list) and any(isinstance(x, dict) for x in v):
                a = out.setdefault(k, {"field": k, "docs": 0, "elements": 0})
                a["docs"] += 1
                a["elements"] += sum(1 for x in v if isinstance(x, dict))
    for k, a in out.items():
        a["avg"] = round(a["elements"] / a["docs"], 2) if a["docs"] else 0.0
        a["natural"] = natural_key(docs, k)
    return dict(sorted(out.items()))


def natural_key(docs: list[dict], field: str) -> str | None:
    """An element field named like an id (LEG_ID, CASHFLOW_REF), a plain value in every element and unique within each document: the first
    such, the one named after the array first (LEGS: LEG_ID)."""
    seen: dict[str, bool] = {}
    for d in docs:
        els = [x for x in (d.get(field) or []) if isinstance(x, dict)] if isinstance(d.get(field), list) else []
        if not els:
            continue
        names = set().union(*(e.keys() for e in els))
        for n in [n for n in names if ID_LIKE.search(n)]:
            if seen.get(n) is False:
                continue
            vals = [e.get(n) for e in els]
            seen[n] = (all(isinstance(v, (str, int)) and not isinstance(v, bool) and v != "" for v in vals) and len(set(vals)) == len(vals)
                       and seen.get(n, True))
        for n in [n for n in seen if n not in names]:
            seen[n] = False
    good = [n for n, ok in seen.items() if ok]
    first = singular(field).upper() + "_"
    good.sort(key=lambda n: (not n.upper().startswith(first), n))
    return good[0] if good else None


def parse(spec: str | None) -> tuple[list[str] | None, dict[str, list[str]]]:
    """--child: None when not given (automatic), [] for ``none``, else the fields (``LEGS,CASHFLOWS`` or ``trade=LEGS;quote=TICKS``)."""
    if spec is None:
        return None, {}
    if str(spec).strip().lower() in NONE:
        return [], {}
    default: list[str] | None = None
    kinds: dict[str, list[str]] = {}
    for part in [p.strip() for p in str(spec).split(";") if p.strip()]:
        if "=" in part:
            k, v = part.split("=", 1)
            kinds[k.strip()] = [] if v.strip().lower() in NONE else [x.strip() for x in v.split(",") if x.strip()]
        else:
            default = [x.strip() for x in part.split(",") if x.strip()]
    return default, kinds


def plan(parent: str, docs: list[dict], parent_key: str, chosen: list[str] | None, min_avg: float = MIN_AVG,
         taken: set[str] | frozenset = frozenset()) -> tuple[list[Child], list[str]]:
    """The child kinds of one kind from its first rows: the ``chosen`` fields, or (None) every array of objects with ``min_avg`` elements on
    average (0: none). Returns them and the reasons in words."""
    cands = arrays(docs)
    why: list[str] = []
    if chosen is None:
        fields = [f for f, a in cands.items() if min_avg > 0 and a["avg"] >= min_avg]
        for f in fields:
            why.append(f"child kind from {f}: an array of objects, {cands[f]['avg']:g} elements on average (--child-min-avg {min_avg:g})")
    else:
        fields = []
        for f in chosen:
            if f in cands:
                fields.append(f)
                why.append(f"child kind from {f} (--child)")
            else:
                why.append(f"--child {f}: not an array of objects in the first rows of {parent}; left out")
    out = []
    for f in fields:
        kind = kind_of(parent, f)
        if kind in taken or kind == parent:
            why.append(f"child kind {kind} from {f}: the name is taken; left out")
            continue
        out.append(Child(parent, f, kind, key_of(f), link_of(parent, parent_key), parent_key, cands[f]["natural"]))
    return out, why


def _value(doc, path: str):
    cur = doc
    for part in path.split("."):
        if not isinstance(cur, dict) or part not in cur:
            return None
        cur = cur[part]
    return cur


def explode(doc: dict, ch: Child) -> list[dict]:
    """The child rows of one parent document (none when it has no id or no such array): its id and link first, then the element's fields."""
    pid = _value(doc, ch.parent_key)
    arr = doc.get(ch.field)
    if pid is None or isinstance(pid, (dict, list)) or not isinstance(arr, list):
        return []
    out = []
    for i, el in enumerate(arr):
        if not isinstance(el, dict):
            continue
        nat = el.get(ch.natural) if ch.natural else None
        tail = nat if isinstance(nat, (str, int)) and not isinstance(nat, bool) and nat != "" else i
        row = {ch.key: f"{pid}{SEP}{tail}", ch.link: pid}
        row.update((k, v) for k, v in el.items() if k not in (ch.key, ch.link))
        out.append(row)
    return out


def of_ingest(ingest: dict | None, keys: dict | None = None) -> dict[str, list[Child]]:
    """parent kind -> its child kinds, from pack.yaml's ``ingest:`` block (the parent's key from the same block)."""
    out: dict[str, list[Child]] = {}
    for kind, v in (ingest or {}).items():
        of = v.get("child-of") if isinstance(v, dict) else None
        if not isinstance(of, dict):
            continue
        parent = str(of.get("kind"))
        pkey = ((ingest.get(parent) or {}).get("key") if isinstance(ingest.get(parent), dict) else None) or (keys or {}).get(parent) or "id"
        out.setdefault(parent, []).append(Child(parent, str(of.get("field")), kind, str(v.get("key")), str(of.get("link") or link_of(parent, pkey)),
                                                str(pkey), of.get("natural")))
    return out


def check_ingest(kind: str, v: dict, kinds: set) -> list[str]:
    """Problems of one child entry of the ``ingest:`` block (``drishti.py pack check``)."""
    of = v.get("child-of")
    if not isinstance(of, dict):
        return [f"pack.yaml ingest.{kind}.child-of: must be a map {{kind, field, link}}"]
    probs = []
    if of.get("kind") not in kinds:
        probs.append(f"pack.yaml ingest.{kind}.child-of.kind: '{of.get('kind')}' is not one of the pack's kinds")
    for k in ("field", "link"):
        if not isinstance(of.get(k), str) or not of.get(k):
            probs.append(f"pack.yaml ingest.{kind}.child-of.{k}: needed (the parent's array field, the field carrying the parent's id)")
    return probs


# ---- BI: the parent and child kinds as tables, related in one model ------------------------------------------------------------------

def _part(s: str) -> str:
    """A table name's part: lower case letters, digits and '_' (the layer's TableName)."""
    n = re.sub(r"_+", "_", re.sub(r"[^a-z0-9_]", "_", s.lower())).strip("_")
    return n if n and n[0].isalpha() else "t_" + n


def column_type(stat) -> str | None:
    """The layer's type of a promoted field from its profile (packprofile.Stat); None for a list or a record (not a column of the table)."""
    types = {t: n for t, n in stat.types.items() if t != "null"}
    if not types or set(types) & {"array", "object"}:
        return None
    if set(types) <= {"integer"}:
        return "integer"
    if set(types) <= {"integer", "number"}:
        return "decimal"
    if set(types) == {"boolean"}:
        return "boolean"
    if set(types) == {"string"} and stat.iso and stat.iso == stat.strings:
        return "timestamp" if stat.stamps else "date"
    return "string"


def bi_files(pack: str, version: str, kinds: dict, keys: dict, promoted: dict, children: dict, header: str = "") -> dict[str, str]:
    """The tables and the model that relate each parent kind to its child kinds (``config/tables/<domain>/``; BI_GUIDE.md): one table
    per kind over its id and promoted columns, and a model with a many-to-one relationship from each child's link to its parent's id, both
    ways (a filter on the child reaches the parent as a semi-join: "trades having any leg where ..."), counts and sums. ``kinds`` is kind ->
    packprofile.KindProfile, ``promoted`` kind -> its promoted columns, ``children`` parent -> [Child]. Empty when there is no child kind."""
    import json as _json
    if not any(children.values()):
        return {}
    domain = _part(pack)

    def words(k: str) -> str:
        return k.replace("-", " ").replace("_", " ")
    out: dict[str, str] = {}
    tables, rels, measures = [], [], []
    for parent in [p for p in sorted(children) if children[p]]:
        for k in [parent] + [c.kind for c in children[parent]]:
            if k not in kinds:
                continue
            kp, key = kinds[k], keys[k]
            alias, table = _part(k), f"{domain}.{_part(plural(k))}"
            cols = [f"  {key}: {{ path: {_json.dumps('$.' + key)}, type: string, key: true }}"]
            sums = []
            for c in promoted.get(k, []):
                st = kp.fields.get(c)
                t = column_type(st) if st is not None and "." not in c else None
                if t is None or c == key:
                    continue
                cols.append(f"  {c}: {{ path: {_json.dumps('$.' + c)}, type: {t}" + (", format: amount0" if t == "decimal" else "") + " }")
                if t in ("integer", "decimal") and not ID_LIKE.search(c):
                    sums.append(c)
            ch = next((c for c in children.get(parent, []) if c.kind == k), None)
            about = (f"One row per element of a {parent}'s {ch.field} (the child kind {k}); {ch.link} is the {parent}'s id." if ch else
                     f"One row per {k} and business date.")
            out[f"{domain}/{_part(plural(k))}.table.yaml"] = header + "\n".join([
                "rachana: 3", f"table: {table}", f"version: {version}", f"title: {_json.dumps(f'{pack}: {words(k)}')}",
                f"description: {_json.dumps(about)}", "tags: [generated, pack-new]", f"from: {{ kind: {k} }}", f"keys: [{key}]", "columns:", *cols]) + "\n"
            tables.append(f"  {alias}: {{ table: {table}, role: {'fact' if ch else 'dimension'} }}")
            if ch:
                rels.append(f"  - {{ from: {alias}.{ch.link}, to: {_part(parent)}.{keys[parent]}, cardinality: many-to-one, direction: both, "
                            f"name: {alias}_{_part(parent)} }}")
            label = words(k).capitalize()
            measures.append(f"  {_json.dumps(plural(label))}: {{ expr: count({alias}), description: {_json.dumps(about)} }}")
            for c in sums:
                measures.append(f"  {_json.dumps(f'{label} {c}')}: {{ expr: sum({alias}.{c}), format: amount0 }}")
    desc = (f"The kinds of {pack} with their child kinds, related from each child row to its parent (both ways: a filter on a child keeps "
            f"the parents having any such child). Generated by drishti.py pack new.")
    out[f"{domain}/{pack}.model.yaml"] = header + "\n".join([
        "rachana: 3", f"model: {pack}", f"version: {version}", f"title: {_json.dumps(pack + ': parents and child kinds')}", f"description: {_json.dumps(desc)}",
        "tags: [generated, pack-new]", "tables:", *tables, "relationships:", *rels, "measures:", *measures]) + "\n"
    return out
