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

"""lake scan: what a large export holds, read as a stream: every field (dotted paths, up to depth 3; arrays are counted, not
entered), how often it is present, its types, its most frequent values, and suggestions for ``lake build``: the id (--key), the
business date (--date) and classifiers (--partition-by: text fields with few distinct values).

    python3 tools/drishti.py lake scan exports/book.json --limit 5000

Memory stays flat: at most ``--values`` distinct values are counted per field (a field with more is marked ``many``)."""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
from collections import Counter

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import jsonstream  # noqa: E402
import stage  # noqa: E402

MAX_DEPTH = 3
MAX_FIELDS = 2000
_ID = re.compile(r"(^|[._-])(id|key|ref|code|number|no)$", re.I)
_DATE = re.compile(r"date|_dt$|day|as_?of", re.I)


class Field:
    __slots__ = ("path", "present", "types", "values", "many", "dates")

    def __init__(self, path: str):
        self.path, self.present, self.types, self.values, self.many, self.dates = path, 0, Counter(), Counter(), False, 0


def _walk(doc: dict, prefix: str, depth: int, out: dict, cap: int) -> None:
    for k, v in doc.items():
        path = prefix + k
        f = out.get(path)
        if f is None:
            if len(out) >= MAX_FIELDS:
                continue
            f = out[path] = Field(path)
        f.present += 1
        t = "null" if v is None else "boolean" if isinstance(v, bool) else "number" if isinstance(v, (int, float)) else \
            "text" if isinstance(v, str) else "array" if isinstance(v, list) else "object"
        f.types[t] += 1
        if t in ("text", "number", "boolean"):
            if t == "text" or (t == "number" and isinstance(v, int) and 19000101 <= v <= 21001231):
                if stage.parse_day(v) is not None:
                    f.dates += 1
            if not f.many:
                key = stage.scalar_text(v)
                if key in f.values or len(f.values) < cap:
                    f.values[key] += 1
                else:
                    f.many = True
        elif t == "object" and depth < MAX_DEPTH:
            _walk(v, path + ".", depth + 1, out, cap)


def scan(inputs, limit: int | None = None, cap: int = 1000, recursive: bool = False, on_progress=None) -> dict:
    """The fields of up to ``limit`` documents, with suggestions."""
    fields: dict[str, Field] = {}
    n = bad = 0
    for doc, _where in jsonstream.documents(inputs, recursive):
        if isinstance(doc, jsonstream.Bad):
            bad += 1
            continue
        n += 1
        _walk(doc, "", 1, fields, cap)
        if on_progress and n % 2000 == 0:
            on_progress(n)
        if limit and n >= limit:
            break
    rows = []
    for f in fields.values():
        distinct = None if f.many else len(f.values)
        rows.append({"path": f.path, "present": f.present, "types": dict(f.types.most_common()), "distinct": distinct,
                     "many": f.many, "dateLike": f.dates, "top": [[v, c] for v, c in f.values.most_common(8)]})
    return {"documents": n, "rejected": bad, "limited": bool(limit and n >= limit), "fields": rows, "suggest": suggest(rows, n)}


def suggest(rows: list[dict], n: int) -> dict:
    """The likeliest id, business date and classifiers. Ids: present in every document, every value different, named like an id
    first. Dates: mostly date-like values, few distinct ones, named like a date first. Classifiers: text in most documents with 2
    to 40 values."""
    if not n:
        return {"key": [], "date": [], "partitionBy": []}
    keys = [r for r in rows if r["present"] == n and r["types"].keys() <= {"text", "number"}
            and (r["many"] or r["distinct"] == n)]
    keys.sort(key=lambda r: (not _ID.search(r["path"]), r["path"].count("."), len(r["path"])))
    dates = [r for r in rows if r["present"] >= n * 0.9 and r["dateLike"] >= r["present"] * 0.9]
    dates.sort(key=lambda r: (not _DATE.search(r["path"]), r["many"], r["distinct"] or 0, r["path"].count(".")))
    classes = [r for r in rows if r["present"] >= n * 0.5 and set(r["types"]) <= {"text", "null"} and not r["many"]
               and 2 <= (r["distinct"] or 0) <= 40 and r not in dates]
    classes.sort(key=lambda r: (r["distinct"], r["path"].count(".")))
    return {"key": [r["path"] for r in keys[:5]], "date": [r["path"] for r in dates[:5]], "partitionBy": [r["path"] for r in classes[:8]]}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="lake scan", description=__doc__.split("\n\n")[0], epilog=__doc__.split("\n\n", 1)[1],
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    add_arguments(ap)
    return run(ap.parse_args(argv))


def add_arguments(ap):
    ap.add_argument("inputs", nargs="+", metavar="PATH", help="files or folders (.json arrays, .jsonl, .gz)")
    ap.add_argument("-r", "--recursive", action="store_true")
    ap.add_argument("--limit", type=int, help="read at most this many documents (default: all)")
    ap.add_argument("--values", type=int, default=1000, help="distinct values counted per field (default 1,000)")
    ap.add_argument("--json", action="store_true", help="print the whole result as JSON")
    return ap


def run(a) -> int:
    r = scan(a.inputs, a.limit, a.values, a.recursive)
    if a.json:
        print(json.dumps(r, indent=2))
        return 0
    print(f"documents: {r['documents']}" + (" (limit reached)" if r["limited"] else "") + (f", rejected: {r['rejected']}" if r["rejected"] else ""))
    print(f"{'field':<34}{'present':>8}{'distinct':>10}  types / most frequent values")
    for f in r["fields"]:
        types = ",".join(f["types"])
        top = ", ".join(f"{v}({c})" for v, c in f["top"][:4]) if not f["many"] and f["distinct"] and f["distinct"] <= 40 else ""
        print(f"{f['path'][:33]:<34}{f['present']:>8}{('many' if f['many'] else str(f['distinct'])):>10}  {types}  {top}"[:160])
    s = r["suggest"]
    print(f"suggested --key {s['key'][0] if s['key'] else '?'}   --date {s['date'][0] if s['date'] else '? (or --as-of)'}   "
          f"--partition-by: {', '.join(s['partitionBy'][:3]) or '(none)'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
