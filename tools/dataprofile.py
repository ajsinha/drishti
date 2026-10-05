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

"""`drishti.py data profile INPUTS`: what is in your JSON Lines, and the `pack make` command that fits it.

One streaming pass (a document at a time) per input; memory is bounded by --max-distinct per field (default 10,000 values tracked: past it the
distinct count is reported as "at least" and the field cannot be a key or a match column).

Per kind (the file stem, or --kind): document count and every field (dotted path; `[]` marks array elements, e.g. `legs[].rate`) with its types,
coverage, distinct values and examples. Then the candidates:
    KEY    present in every document and different in each (exact up to --max-distinct documents, "probable" beyond)
    DATE   every value an ISO date (YYYY-MM-DD, optionally with a time), present in at least 95% of the documents
    MATCH  low-cardinality text/integer/boolean fields that would split the kind into worthwhile screens, scored 0..1:
           score = coverage x (1 - distinct/documents) x min(1, average group size / 5) x min(1, distinct/8) x (0.5 + 0.5 x evenness),
           x1.15 when the name ends in type/class/category/kind/family/product/segment/region/status, x0.9 per nesting level
           (evenness = entropy of the value counts / log(distinct); the fields explain themselves in the output)
"""
from __future__ import annotations

import math
import pathlib
import re
import sys

ISO_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}(?:$|[T ])")
DATE_PRESENCE = 0.95
MAX_GROUPS = 200
NAME_HINT = re.compile(r"(type|class|category|kind|family|product|segment|region|status)$", re.I)
EXAMPLES = 3


class ProfileError(Exception):
    def __init__(self, message: str, code: int = 2):
        super().__init__(message)
        self.code = code


def type_of(v) -> str:
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "boolean"
    if isinstance(v, int):
        return "integer"
    if isinstance(v, float):
        return "number"
    if isinstance(v, str):
        return "string"
    return "array" if isinstance(v, list) else "object"


class Field:
    __slots__ = ("docs", "types", "values", "capped", "dup", "examples", "iso", "strings", "lo", "hi", "total_len", "max_len")

    def __init__(self):
        self.docs = 0
        self.types: dict[str, int] = {}
        self.values: dict = {}
        self.capped = False
        self.dup = False
        self.examples: list = []
        self.iso = self.strings = 0
        self.lo = self.hi = None
        self.total_len = self.max_len = 0

    def see(self, v, limit: int, first_in_doc: bool) -> None:
        t = type_of(v)
        self.types[t] = self.types.get(t, 0) + 1
        if first_in_doc:
            self.docs += 1
        if t in ("object", "array"):
            if t == "array":
                self.total_len += len(v)
                self.max_len = max(self.max_len, len(v))
            return
        if t == "string":
            self.strings += 1
            if ISO_DATE.match(v):
                self.iso += 1
        if t in ("integer", "number"):
            self.lo = v if self.lo is None or v < self.lo else self.lo
            self.hi = v if self.hi is None or v > self.hi else self.hi
        if v is None:
            return
        if v in self.values:
            self.values[v] += 1
            self.dup = True
        elif len(self.values) < limit:
            self.values[v] = 1
        else:
            self.capped = True
        if len(self.examples) < EXAMPLES and v not in self.examples and v != "":
            self.examples.append(v)


def walk(v, path: str, doc_seen: set, fields: dict, limit: int) -> None:
    f = fields.get(path)
    if path:
        if f is None:
            f = fields[path] = Field()
        first = path not in doc_seen
        doc_seen.add(path)
        f.see(v, limit, first)
    if isinstance(v, dict):
        for k, x in v.items():
            walk(x, f"{path}.{k}" if path else k, doc_seen, fields, limit)
    elif isinstance(v, list):
        for x in v:
            if isinstance(x, dict):
                for k, y in x.items():
                    walk(y, f"{path}[].{k}", doc_seen, fields, limit)
            elif isinstance(x, (dict, list)):
                continue
            else:
                walk(x, path + "[]", doc_seen, fields, limit)


def short(v, n: int = 32):
    s = v if isinstance(v, str) else str(v)
    return s if len(s) <= n else s[:n - 1] + "…"


def analyse(kind: str, count: int, fields: dict, limit: int) -> dict:
    rows, keys, dates, matches = [], [], [], []
    for path, f in fields.items():
        dist = len(f.values)
        row = {"field": path, "types": dict(sorted(f.types.items(), key=lambda x: -x[1])), "documents": f.docs, "coverage": round(f.docs / count, 4),
               "distinct": dist, "distinctExact": not f.capped, "examples": [short(x) for x in f.examples]}
        if f.lo is not None:
            row["min"], row["max"] = f.lo, f.hi
        if f.max_len:
            row["maxArrayLength"] = f.max_len
        rows.append(row)
        scalar = set(f.types) <= {"string", "integer", "number", "boolean"} and "[]" not in path
        if scalar and f.docs == count and sum(f.types.values()) == count and not f.dup and dist == count:
            keys.append({"field": path, "confidence": "exact" if not f.capped else "probable", "why": f"present in all {count} documents, {dist} different values"})
        elif scalar and f.docs == count and not f.dup and f.capped:
            keys.append({"field": path, "confidence": "probable", "why": f"present in all {count} documents; no duplicate among the first {limit} values"})
        if f.strings and f.docs >= DATE_PRESENCE * count and f.iso == f.strings and f.types.get("string", 0) == sum(f.types.values()) and "[]" not in path:
            nd = len({v[:10] for v in f.values})
            dates.append({"field": path, "coverage": round(f.docs / count, 4), "distinctDates": nd, "range": [min(f.values)[:10], max(f.values)[:10]]})
        if (scalar and not f.capped and "number" not in f.types and f.iso == 0 and "[]" not in path
                and 2 <= dist <= MAX_GROUPS and f.docs / count >= DATE_PRESENCE):
            n = sum(f.values.values())
            cov = f.docs / count
            avg = n / dist
            ent = -sum(c / n * math.log(c / n) for c in f.values.values())
            even = ent / math.log(dist)
            groups = min(1.0, dist / 8)
            hint = bool(NAME_HINT.search(path.rsplit(".", 1)[-1]))
            depth = path.count(".")
            score = min(1.0, cov * (1 - dist / count) * min(1.0, avg / 5) * groups * (0.5 + 0.5 * even) * (1.15 if hint else 1.0) * (0.9 ** depth))
            top = max(f.values, key=f.values.get)
            matches.append({"field": path, "score": round(score, 3), "distinct": dist,
                            "explain": f"coverage {cov:.0%}, {dist} values over {n} documents (average {avg:.1f} per value, evenness {even:.2f}; "
                                       f"largest group {short(top)} = {f.values[top]})" + ("; name suggests a category" if hint else "") + ("; nested field" if depth else "")})
    matches.sort(key=lambda m: -m["score"])
    keys.sort(key=lambda k: (k["confidence"] != "exact", not re.search(r"id$|key$", k["field"], re.I)))
    return {"kind": kind, "documents": count, "fields": rows, "keyCandidates": keys, "dateCandidates": dates, "matchCandidates": matches[:8]}


def suggest(kind: str, prof: dict, inputs: list[str]) -> str:
    cmd = ["python3 tools/drishti.py pack make"] + inputs + [f"--kind {kind}"]
    if prof["matchCandidates"]:
        cmd.append(f"--match {prof['matchCandidates'][0]['field']}")
    else:
        cmd.append("--match FIELD  # no field looks like a good split: choose one")
    if prof["keyCandidates"]:
        cmd.append(f"--key {prof['keyCandidates'][0]['field']}")
    if prof["dateCandidates"]:
        cmd.append(f"--date {prof['dateCandidates'][0]['field']}")
    cmd.append(f"--name {re.sub(r'[^a-z0-9-]', '-', kind.lower()).strip('-') or 'my'}-pack")
    return " ".join(cmd)


def profile(inputs, recursive: bool, kind: str | None, limit: int, SG):
    files = SG.input_files(inputs, recursive)
    stats: dict[str, list] = {}
    bad = []
    for k, doc, _ in SG.read_records(files, bad.append):
        entry = stats.setdefault(kind or k, [0, {}])
        entry[0] += 1
        walk(doc, "", set(), entry[1], limit)
    return stats, bad, files


def run(a, cli) -> int:
    SG = cli.sutragen()
    try:
        stats, bad, files = profile(a.inputs, a.recursive, a.kind, a.max_distinct, SG)
    except SystemExit as e:
        raise ProfileError(str(e.code)) from None
    if not stats:
        raise ProfileError("no JSON documents found in " + ", ".join(map(str, a.inputs)))
    ins = [str(i) for i in a.inputs]
    out = []
    for kind in sorted(stats):
        p = analyse(kind, stats[kind][0], stats[kind][1], a.max_distinct)
        p["suggestedCommand"] = suggest(kind, p, ins)
        out.append(p)
    if a.json:
        cli.say_json({"inputs": ins, "files": len(files), "badLines": len(bad), "kinds": out})
        return 0
    print(f"data profile: {len(files)} file(s), {sum(p['documents'] for p in out)} document(s), {len(out)} kind(s)" + (f", {len(bad)} unreadable line(s) skipped" if bad else ""))
    for p in out:
        print(f"\n== {p['kind']}: {p['documents']} document(s), {len(p['fields'])} field(s)")
        w = min(46, max(len(r["field"]) for r in p["fields"]))
        print(f"  {'field':<{w}}  {'type':<14} {'cover':>6} {'distinct':>9}  examples")
        for r in p["fields"]:
            types = "/".join(r["types"])[:14]
            dist = f"{r['distinct']}" if r["distinctExact"] else f">={r['distinct']}"
            print(f"  {r['field']:<{w}}  {types:<14} {r['coverage']:>6.0%} {dist:>9}  " + ", ".join(r["examples"]))
        print("\n  key candidates (present and unique everywhere):")
        for k in p["keyCandidates"][:5] or [{"field": "(none)", "why": "pass --key if documents have an id the profile could not prove unique", "confidence": ""}]:
            print(f"    {k['field']:<28} {k['confidence']:<9} {k['why']}")
        print("  date candidates (ISO dates in at least 95% of the documents):")
        for d in p["dateCandidates"][:5] or [{"field": "(none)", "range": ["", ""], "distinctDates": 0, "coverage": 0}]:
            print(f"    {d['field']:<28} " + (f"{d['distinctDates']} date(s) {d['range'][0]} .. {d['range'][1]}, coverage {d['coverage']:.0%}" if d["distinctDates"] else "no dated store without one"))
        print("  match candidates (split the kind into screens; score 0..1):")
        for m in p["matchCandidates"][:6] or [{"field": "(none)", "score": 0, "explain": "no low-cardinality field covers 95% of the documents"}]:
            print(f"    {m['field']:<28} {m['score']:.3f}  {m['explain']}")
        print(f"  suggested:\n    {p['suggestedCommand']}")
    return 0
