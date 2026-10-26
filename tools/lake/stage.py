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

"""Reading a stream of documents into staged partition files: the first half of ``lake build``.

Every accepted document becomes one row ``[id, document JSON, {promoted column: value}]`` (a JSON array per line) in the staged
file of its business date and partition values (``<staging>/<n>.rows``). Nothing is held in memory but the counts, a bounded set of
open files and, per business date, a set of 8-byte id hashes (to count duplicates): memory stays flat however large the input.

A document is rejected, with the reason, when it has no id (``--key``), no business date (``--date``, unless ``--as-of``), a date
that is not a date, or when it is not a JSON object; a reject is counted per reason and the first ones are kept with where they were
(``file:line`` or ``file[n]``).

A **vector** field (the pack's ``layout.<kind>.vectors``: a P&L vector of 509 numbers, VAR_GUIDE.md section 29.3) becomes a column of
its own (``list<double>``) and leaves the document, which keeps the marker ``{"$vector": "PNL"}`` in its place, so the numbers are not
stored twice; the Delta connector puts the vector back into a document it serves. A vector that is not a list of numbers (or null) is
a reject."""
from __future__ import annotations

import hashlib
import json
import pathlib
import re
from collections import Counter, OrderedDict
from dataclasses import dataclass, field
from datetime import date

import jsonstream

MISSING = object()
KEEP_REJECTS = 50                    # rejects kept with where and why (all are counted)
OPEN_FILES = 64                      # staged files kept open at once
DUPLICATE_WATCH = 5_000_000          # ids remembered (8 bytes each) to count duplicates; past this, duplicates are not counted
_YMD = re.compile(r"^(\d{4})(\d{2})(\d{2})$")


def get(doc, path: str):
    """The value at a dotted path (``index.group``), MISSING when absent."""
    cur = doc
    for part in path.split("."):
        if isinstance(cur, dict) and part in cur:
            cur = cur[part]
        else:
            return MISSING
    return cur


_STEP = re.compile(r"([^.\[\]]+)|\[(\d+)\]")


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
    """A partition value as text (Hive folders hold text): None for missing or null, JSON for an object or array."""
    if v is MISSING or v is None:
        return None
    if isinstance(v, str):
        return v
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (int, float)):
        return str(v)
    return json.dumps(v, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


_ID_FIELD = re.compile(r"(?i)(^|_)(id|ids|ref|refs|key)$")


def is_id_field(path: str) -> bool:
    """A field named like an id or key (ID, TRADE_ID, TRADE_IDS, DEAL_REF, counterparty.id): its column is text, whatever the values."""
    return bool(_ID_FIELD.search(path.replace("__", ".").rsplit(".", 1)[-1]))


def id_text(v) -> str | None:
    """An id as its exact text: 1000008 (a number in the file) -> "1000008", never "1000008.0"; text unchanged (leading zeros kept)."""
    if isinstance(v, float) and v.is_integer() and abs(v) < 1e18:
        return str(int(v))
    return scalar_text(v)


def _number(v) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool)


@dataclass
class Part:
    """One staged partition: a business date and the partition values, its file and row count."""
    day: date
    values: tuple
    file: pathlib.Path
    rows: int = 0


@dataclass
class Stats:
    read: int = 0                                                     # documents read (accepted and rejected)
    accepted: int = 0
    rejects: Counter = field(default_factory=Counter)                # reason -> count
    reject_samples: list = field(default_factory=list)                # [{where, reason}]
    duplicates: Counter = field(default_factory=Counter)             # business date -> ids seen more than once
    numeric: dict = field(default_factory=dict)                       # promoted column -> True while every value is a number
    files: list = field(default_factory=list)                         # [{file, bytes}]
    doc_bytes: int = 0                                                # characters of the accepted documents (compact JSON)
    sample: tuple | None = None                                       # (id, date) of the first accepted document, to open one

    def reject(self, where: str, reason: str) -> None:
        self.rejects[reason] += 1
        if len(self.reject_samples) < KEEP_REJECTS:
            self.reject_samples.append({"where": where, "reason": reason})

    @property
    def rejected(self) -> int:
        return sum(self.rejects.values())


class Stager:
    """Writes accepted documents into staged files by (business date, partition values)."""

    def __init__(self, folder: pathlib.Path | None, key: str, date_field: str | None, as_of: date | None,
                 partition_by: list[str], promote: list[str], flatten: dict | None = None, vectors: list[str] | None = None):
        self.folder, self.key, self.date_field, self.as_of = folder, key, date_field, as_of
        # flattened fields (the pack's layout.<kind>.flatten: NOTIONAL: LEGS[0].NOTIONAL): a plain field of the document and a
        # column, so searches, totals and BI read it without the document; a field the document already has is never replaced
        self.flatten = dict(flatten or {})
        promote = list(promote) + [n for n in self.flatten if n not in promote]
        self.partition_by = list(partition_by)
        self.promote = [p for p in promote if p not in self.partition_by]       # a partition column is a real column already
        self.vectors = [v for v in (vectors or []) if v not in self.partition_by and v not in self.promote]
        self.parts: dict[tuple, Part] = {}
        self.stats = Stats(numeric={column_name(p): True for p in self.promote})
        self._open: OrderedDict[tuple, object] = OrderedDict()
        self._seen: dict[date, set] = {}
        if folder:
            folder.mkdir(parents=True, exist_ok=True)

    # ----------------------------------------------------------------------------------------------- one document

    def add(self, doc, where: str) -> None:
        st = self.stats
        st.read += 1
        if isinstance(doc, jsonstream.Bad):
            st.reject(doc.where, doc.reason)
            return
        kv = get(doc, self.key)
        if kv is MISSING or kv is None or kv == "":
            st.reject(where, f"no id (field '{self.key}' is missing or empty)")
            return
        if isinstance(kv, (dict, list)):
            st.reject(where, f"the id field '{self.key}' is not a single value")
            return
        ident = id_text(kv)
        if _number(kv) and isinstance(doc, dict) and self.key in doc:       # the document carries its id as text too
            doc = {**doc, self.key: ident}
        if self.as_of:
            day = self.as_of
        else:
            raw = get(doc, self.date_field)
            day = parse_day(raw)
            if day is None:
                st.reject(where, f"no business date (field '{self.date_field}' is " + ("missing or empty)" if raw in (MISSING, None, "") else "not a date)"))
                return
        if self.flatten and isinstance(doc, dict):
            added = {n: v for n, path in self.flatten.items() if n not in doc and (v := get_indexed(doc, path)) is not MISSING}
            if added:
                doc = {**doc, **added}
        vecs = {}
        for p in self.vectors:
            v = get(doc, p) if isinstance(doc, dict) else MISSING
            if v is MISSING or v is None:
                continue
            if not isinstance(v, list) or not all(_number(x) or x is None for x in v):
                st.reject(where, f"the vector field '{p}' is not a list of numbers")
                return
            vecs[column_name(p)] = v
            doc = {**doc, p: {"$vector": column_name(p)}} if "." not in p else doc
        values = tuple(scalar_text(get(doc, p)) for p in self.partition_by)
        promoted = {}
        for p in self.promote:
            v = get(doc, p)
            v = None if v is MISSING else v
            name = column_name(p)
            if v is not None and not _number(v):
                st.numeric[name] = False
            if is_id_field(p) or p == self.key:                         # an id column is text even when every id is a number
                st.numeric[name] = False
                v = id_text(v) if _number(v) else v
            promoted[name] = v
        promoted.update(vecs)
        h = int.from_bytes(hashlib.blake2b(ident.encode("utf-8"), digest_size=8).digest(), "big")
        seen = self._seen.setdefault(day, set())
        if h in seen:
            st.duplicates[day.isoformat()] += 1
        elif st.accepted < DUPLICATE_WATCH:
            seen.add(h)
        st.accepted += 1
        if st.sample is None:
            st.sample = (ident, day.isoformat())
        part = self.parts.get((day, values))
        if part is None:
            part = self.parts[(day, values)] = Part(day, values, self.folder / f"{len(self.parts) + 1}.rows" if self.folder else pathlib.Path(""))
        part.rows += 1
        text = json.dumps(doc, ensure_ascii=False, separators=(",", ":"))
        st.doc_bytes += len(text)
        if self.folder:
            self._handle(part).write(json.dumps([ident, text, promoted], ensure_ascii=False, separators=(",", ":")) + "\n")

    def _handle(self, part: Part):
        k = (part.day, part.values)
        fh = self._open.get(k)
        if fh is not None:
            self._open.move_to_end(k)
            return fh
        if len(self._open) >= OPEN_FILES:
            _, old = self._open.popitem(last=False)
            old.close()
        fh = self._open[k] = open(part.file, "a", encoding="utf-8")
        return fh

    def close(self) -> None:
        for fh in self._open.values():
            fh.close()
        self._open.clear()

    # ----------------------------------------------------------------------------------------------- results

    def types(self) -> dict[str, str]:
        """The promoted columns' types from what was read: double when every value present is a number, else string."""
        out = {name: ("double" if ok else "string") for name, ok in self.stats.numeric.items()}
        out.update({column_name(v): "vector" for v in self.vectors})
        return out

    def by_day(self) -> dict[date, list[Part]]:
        out: dict[date, list[Part]] = {}
        for p in self.parts.values():
            out.setdefault(p.day, []).append(p)
        return {d: sorted(out[d], key=lambda p: tuple("" if v is None else v for v in p.values)) for d in sorted(out)}


def read_rows(file: pathlib.Path, batch: int):
    """The staged rows of a file, ``batch`` at a time: lists of (id, document JSON, promoted)."""
    chunk = []
    with open(file, encoding="utf-8") as fh:
        for line in fh:
            chunk.append(json.loads(line))
            if len(chunk) >= batch:
                yield chunk
                chunk = []
    if chunk:
        yield chunk
