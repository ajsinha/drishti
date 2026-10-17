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

"""Pages for each value of a field ("related" pages): a counterparty, a portfolio, an index that many documents share becomes an entity of
its own, built by the server's ``derived`` connector from the documents that carry it (no extra feed), with its count, totals and the list of
its documents, and links both ways (document -> page by the field, page -> documents by its member list).

``options(kp)`` proposes the fields; ``make(kp, chosen, taken)`` returns the derived kinds (``KindPlan`` with ``derived`` set and sample
documents computed here exactly as the server will compute them, so the drafts and the tests show real-looking pages).
"""
from __future__ import annotations

import re

from .plan import ID_NAME, KindPlan, Link, _is_ordinal, blank, humanize, mnemonic_of, node_from_docs, slug

# what people open a page for; the first group is proposed switched on
GROUPABLE_ON = re.compile(r"(?i)(counterpart|cpty|client|customer|issuer|borrower|portfolio|book|desk|fund|underlying|index|curve|commodity|obligor)")
GROUPABLE = re.compile(r"(?i)(trader|entity|region|country|sector|strategy|product|asset.?class|exchange|venue|broker|location|currency|ccy)")
NOT_GROUP = re.compile(r"(?i)(original|uen|lei|first.?name|last.?name|status|date|time|flag|code$|comment|desc|note)")
MAX_PAGES = 6


def _top_values(kp: KindPlan, path: str) -> list:
    return [d.get(path) for d in kp.samples]


def _nested_values(docs: list, arr: str, sub: list) -> list:
    out = []
    for d in docs:
        v = None
        for item in d.get(arr) or []:
            x = item
            for s in sub:
                x = x.get(s) if isinstance(x, dict) else None
            if not blank(x):
                v = x
                break
        out.append(v)
    return out


def options(kp: KindPlan) -> list:
    """[{path, expr, label, on, values, why}]: the fields whose values group the documents into pages."""
    if not kp.samples or not kp.key:
        return []
    out = []
    n = len(kp.samples)
    for f in kp.fields:
        if f.hidden or f.path == kp.key or f.role in ("id", "number", "date", "datetime", "table", "object", "map", "list", "flag") or f.type not in ("string", ""):
            continue
        nested, word, parent = "[" in f.path, f.name, False
        if nested:
            m = re.fullmatch(r"([^.\[]+)\[\]\.(.+)", f.path)
            if not m or "[" in m.group(2):
                continue
            arr, sub = m.group(1), m.group(2).split(".")
            # a generic NAME / ID inside an object reads with its parent (INDEX.NAME is the index)
            parent = len(sub) > 1 and bool(re.fullmatch(r"(?i)(name|id|code|key)", sub[-1]))
            word = sub[-2] if parent else sub[-1]
        if NOT_GROUP.search(f.name) or not (GROUPABLE_ON.search(word) or GROUPABLE.search(word)):
            continue
        if nested:
            if not parent and not GROUPABLE_ON.search(word):
                continue
            vals = _nested_values(kp.samples, arr, sub)
            expr = f"first($.{arr}[?@.{m.group(2)} != '']).{m.group(2)}"
            label = humanize(word)
            on = parent and bool(GROUPABLE_ON.search(word))     # INDEX.NAME: an object naming a thing; DISCOUNT_INDEX: offered, off
        else:
            vals = _top_values(kp, f.path)
            expr = f"$.{f.path}"
            label = f.label
            on = bool(GROUPABLE_ON.search(word))
        present = [str(v) for v in vals if not blank(v)]
        distinct = len(set(present))
        if not present or distinct < 2 or distinct > max(2, int(0.7 * n)) or len(present) < 0.3 * n:
            continue                                        # a page per value only when values repeat: a group, not an id
        out.append({"path": f.path, "expr": expr, "label": label, "on": on, "values": distinct,
                    "why": f"{distinct} different values in {len(present)} of {n} sample documents"})
    out.sort(key=lambda o: (not o["on"], kp.fields.index(kp.index[o["path"]])))
    return out[:12]


def camel(text: str) -> str:
    parts = [p for p in re.split(r"[^A-Za-z0-9]+", text) if p]
    return parts[0].lower() + "".join(p.capitalize() for p in parts[1:]) if parts else "value"


def plural(word: str) -> str:
    if re.search(r"[^aeiou]y$", word):
        return word[:-1] + "ies"
    return word + ("es" if re.search(r"(s|x|ch|sh)$", word) else "s")


def members_field(kind: str) -> str:
    return camel(kind) + "Ids"


def _num(v):
    if isinstance(v, bool):
        return None
    if isinstance(v, (int, float)):
        return float(v)
    if isinstance(v, str) and re.fullmatch(r"-?\d+(\.\d+)?", v.strip()):
        return float(v)
    return None


def _clean(v):
    return int(v) if isinstance(v, float) and v.is_integer() and abs(v) < 1e15 else v


def spec_for(kp: KindPlan, opt: dict, chosen: list) -> dict:
    """The derived kind's settings: group-by, members, aggregates (count, the main sums, the other groupings' values) and one row per member."""
    tops = [f for f in kp.fields if "." not in f.path and "[" not in f.path and not f.hidden]
    nums = [f for f in tops if f.role == "number" and not ID_NAME.search(f.name) and not _is_ordinal(f) and f.fill >= 0.5][:2]
    fields = {"count": "count"}
    for f in nums:
        fields["total" + camel(humanize(f.name))[:1].upper() + camel(humanize(f.name))[1:]] = f"sum $.{f.path}"
    for o in chosen:
        if o["path"] != opt["path"] and "[" not in o["path"]:
            fields[plural(camel(o["label"]))] = f"distinct $.{o['path']}"
    rows = {kp.key: f"$.{kp.key}"}
    picks = [f for f in tops if f.path in kp.strip and f.path != opt["path"]]
    for f in picks[:7]:
        rows[f.path] = f"$.{f.path}"
    return {"from": kp.kind, "group-by": opt["expr"], "id-field": "name", "members": members_field(kp.kind), "max-members": 1000,
            "fields": fields, "rows": rows}


def derived_docs(kp: KindPlan, spec: dict, opt: dict) -> list:
    """The pages the server will build from these documents (the same document shape as the derived connector writes)."""
    groups: dict = {}
    m = re.fullmatch(r"([^.\[]+)\[\]\.(.+)", opt["path"])
    keys = _nested_values(kp.samples, m.group(1), m.group(2).split(".")) if m else [d.get(opt["path"]) for d in kp.samples]
    for d, k in zip(kp.samples, keys):
        if not blank(k):
            groups.setdefault(str(k).strip(), []).append(d)
    out = []
    for g, members in sorted(groups.items()):
        members = sorted(members, key=lambda d: str(d.get(kp.key)))
        doc = {"id": g, spec["id-field"]: g, "derivedFrom": kp.kind}
        for name, agg in spec["fields"].items():
            op, _, expr = agg.partition(" ")
            path = expr[2:] if expr.startswith("$.") else ""
            vals = [d.get(path) for d in members] if path else []
            if op == "count":
                doc[name] = len(members)
            elif op == "sum":
                doc[name] = _clean(sum(x for x in (_num(v) for v in vals) if x is not None))
            elif op == "distinct":
                doc[name] = sorted({str(v) for v in vals if not blank(v)})
        doc["memberCount"] = len(members)
        doc[spec["members"]] = [str(d.get(kp.key)) for d in members]
        doc["rows"] = [{c: d.get(e[2:]) for c, e in spec["rows"].items()} for d in members]
        out.append(doc)
    return out


def make(kp: KindPlan, chosen: list, taken: set) -> list:
    """The derived kinds for the chosen options of ``kp`` (which gets the links to them)."""
    out = []
    kp.related = []
    for opt in chosen[:MAX_PAGES]:
        word = slug(opt["label"]) or slug(opt["path"])
        kind = f"{kp.kind}-{word}"[:64]
        spec = spec_for(kp, opt, chosen)
        docs = derived_docs(kp, spec, opt)
        if len(docs) < 2:
            continue
        mn = (kp.mnemonic or mnemonic_of(kp.kind, set()))[:6] + re.sub(r"[^A-Z]", "", opt["label"].upper())[:1]
        while mn in taken:
            mn = mn[:-1] + chr(ord(mn[-1]) + 1) if mn[-1] < "Z" else mn + "2"
        taken.add(mn)
        d = KindPlan(kind=kind, orig=kind, kind_why=f"a page for each {opt['label'].lower()} of a {kp.kind}", title=f"{opt['label']} ({kp.title})",
                     description=f"Every {kp.title} with the same {opt['label'].lower()}, with their count and totals; built by the server from the {kp.kind} documents.",
                     node=node_from_docs(docs), key="id", key_why="the value the documents share", samples=docs, mnemonic=mn, template="derived")
        d.derived = {**spec, "path": opt["path"], "label": opt["label"]}
        d.links = [Link(spec["members"], kp.kind, f"the {kp.kind} documents in this group", True)]
        kp.related.append({"path": opt["path"], "kind": kind, "expr": opt["expr"], "label": opt["label"]})
        if "[" not in opt["path"]:
            kp.links.append(Link(opt["path"], kind, f"a page for each {opt['label'].lower()}"))
            if opt["path"] in kp.index:
                kp.index[opt["path"]].role = "link"
        out.append(d)
    return out


def chosen_options(kp: KindPlan, ov: dict) -> list:
    """The options switched on: the user's list (``related: [path, ...]``, [] for none), else the proposed ones."""
    if "related" in ov and isinstance(ov["related"], list):
        want = [str(p) for p in ov["related"]]
        return [o for o in kp.related_options if o["path"] in want]
    return [o for o in kp.related_options if o["on"]]
