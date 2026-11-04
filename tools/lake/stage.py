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
KEEP_DUPLICATE_EXAMPLES = 10         # duplicates named (kept and dropped file:line); all are counted
LATEST_BY = "COMPUTED_AT"
LOAD_ID, SOURCE_FILE = "_load_id", "_source_file"   # the columns every row carries (DATA_LOAD_RULES.md section 4)            # the timestamp field that decides which duplicate is kept (TOOLS.md section 4.13)
OPEN_FILES = 64                      # staged files kept open at once
DUPLICATE_WATCH = 5_000_000          # ids remembered (8 bytes each) to find duplicates; past this, duplicates are neither found nor dropped
_YMD = re.compile(r"^(\d{4})(\d{2})(\d{2})$")
_ISO = re.compile(r"^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:[.,](\d+))?)?)?\s*(Z|z|[+-]\d{2}(?::?\d{2})?)?$")


def instant_micros(v) -> int | None:
    """A ``latest-by`` timestamp as microseconds since the epoch (UTC): ISO-8601 text (a date, a date-time with seconds, a fraction to
    microseconds and an offset; no offset is UTC). None for anything else. The Java loader's Latest.micros is the same rule."""
    if not isinstance(v, str):
        return None
    m = _ISO.match(v.strip())
    if not m:
        return None
    try:
        day = date(int(m[1]), int(m[2]), int(m[3])).toordinal() - date(1970, 1, 1).toordinal()
    except ValueError:
        return None
    h, mi, s = int(m[4] or 0), int(m[5] or 0), int(m[6] or 0)
    if h > 23 or mi > 59 or s > 59:
        return None
    frac = int(((m[7] or "") + "000000")[:6])
    off = 0
    z = m[8]
    if z and z not in ("Z", "z"):
        d = z[1:].replace(":", "")
        oh, om = int(d[:2]), int(d[2:] or 0)
        if oh > 23 or om > 59:
            return None
        off = (-1 if z[0] == "-" else 1) * (oh * 3600 + om * 60)
    return (day * 86400 + h * 3600 + mi * 60 + s - off) * 1_000_000 + frac


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
    duplicates: Counter = field(default_factory=Counter)             # business date -> copies dropped (an id seen more than once)
    duplicate_examples: list = field(default_factory=list)            # [{date, kept, dropped}] (file:line), in input order
    numeric: dict = field(default_factory=dict)                       # promoted column -> True while every value is a number
    files: list = field(default_factory=list)                         # [{file, bytes}]
    doc_bytes: int = 0                                                # characters of the accepted documents (compact JSON)
    sample: tuple | None = None                                       # (id, date) of the first accepted document, to open one
    file_states: list = field(default_factory=list)                   # [FileState]: each file read, its date and what its rows said

    def reject(self, where: str, reason: str) -> None:
        self.rejects[reason] += 1
        if len(self.reject_samples) < KEEP_REJECTS:
            self.reject_samples.append({"where": where, "reason": reason})

    @property
    def rejected(self) -> int:
        return sum(self.rejects.values())


@dataclass
class FileState:
    """One file of the load (DATA_GUIDE.md section 2.8): its label, its business date (its tar folder's, the CLI's, its name's or the
    business today), where that came from, its documents and the rows whose own date field said another date."""
    label: str
    outside: date | None
    source: str
    documents: int = 0
    accepted: int = 0
    other: int = 0
    error: str | None = None
    load_id: str | None = None


class Stager:
    """Writes accepted documents into staged files by (business date, partition values)."""

    def __init__(self, folder: pathlib.Path | None, key: str, date_field: str | None, as_of: date | None,
                 partition_by: list[str], promote: list[str], flatten: dict | None = None, vectors: list[str] | None = None,
                 latest_by: str | None = LATEST_BY):
        self.folder, self.key, self.date_field, self.as_of = folder, key, date_field, as_of
        self.latest_by = (latest_by or "").strip() or None
        self._twice: dict[date, set] = {}                                      # per date: the id hashes seen more than once
        # flattened fields (the pack's layout.<kind>.flatten: NOTIONAL: LEGS[0].NOTIONAL): a plain field of the document and a
        # column, so searches, totals and BI read it without the document; a field the document already has is never replaced
        self.flatten = dict(flatten or {})
        promote = list(promote) + [n for n in self.flatten if n not in promote]
        self.partition_by = list(partition_by)
        self.promote = [p for p in promote if p not in self.partition_by]       # a partition column is a real column already
        self.vectors = [v for v in (vectors or []) if v not in self.partition_by and v not in self.promote]
        self.parts: dict[tuple, Part] = {}
        self.stats = Stats(numeric={column_name(p): True for p in self.promote})
        self.stats.numeric.update({LOAD_ID: False, SOURCE_FILE: False})          # every row: the load that wrote it and its file
        self.run = ""
        self._load_ids: dict[str, str] = {}
        self._open: OrderedDict[tuple, object] = OrderedDict()
        self._seen: dict[date, set] = {}
        self.cur: FileState | None = None                                       # the file being read (file())
        self.date_from = "auto"
        self.today: date | None = None
        if folder:
            folder.mkdir(parents=True, exist_ok=True)

    def file(self, label: str, name_date: date | None, folder_date: date | None = None, given: date | None = None, top: str | None = None) -> None:
        """The next documents come from the file ``label``: its tar folder's date, else filedate.decide (--as-of, else ``given``, its
        .ready file's business-date; its name's date; the business today; ``date_from`` name: a name without a date refuses the file)."""
        import filedate
        if folder_date is not None:
            s = FileState(label, folder_date, "folder")
        else:
            d = filedate.decide(self.as_of or given, name_date, self.date_from, self.today, label)
            s = FileState(label, d.date, "ready" if given and not self.as_of else d.source, error=d.error)
        key = top or label                              # one load id per data file: an archive's members share their archive's
        s.load_id = self._load_ids.setdefault(key, f"{self.run}.{len(self._load_ids) + 1}")
        self.cur = s
        self.stats.file_states.append(s)

    def loads(self) -> dict:
        """Each load id of the run and its data file (the summary's ``loads``; ``lake undo`` removes a load's rows by its id)."""
        return {i: pathlib.Path(k).name for k, i in self._load_ids.items()}

    def refused_files(self) -> list[str]:
        """Why files are refused (date-from name without a date in the name); [] none. Known before a row is read."""
        return [s.error for s in self.stats.file_states if s.error]

    def date_report(self, given_by: str = "the command line") -> dict:
        """The summary's keys (the Java loader's LoadReport gives the same): ``fileDates`` (each file: its date, where it came from, in
        words, the rows whose own date field said another date), ``otherDateRows``, ``dateNotes`` (those rows in words) and
        ``emptyFiles`` (files with no document: ignored, nothing written for them)."""
        import filedate
        files, notes = [], []
        for s in self.stats.file_states:
            if s.outside is None:
                continue
            d = filedate.Decided(s.outside, s.source)
            files.append({"file": s.label, "date": s.outside.isoformat(), "source": s.source, "dateText": filedate.words(d, given_by),
                          "rows": s.accepted, "otherDateRows": s.other})
            if s.other:
                notes.append(filedate.other_date_note(s.other, s.label, self.date_field, s.outside))
        return {"fileDates": files, "otherDateRows": sum(s.other for s in self.stats.file_states), "dateNotes": notes,
                "emptyFiles": [s.label for s in self.stats.file_states if s.documents == 0]}

    # ----------------------------------------------------------------------------------------------- one document

    def add(self, doc, where: str) -> None:
        st = self.stats
        st.read += 1
        if self.cur is not None:
            self.cur.documents += 1
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
        own = None
        cur = self.cur
        if cur is not None:                                  # the file's date (filedate.decide): the row's own only counted
            if cur.outside is None:
                st.reject(where, cur.error or "no business date")
                return
            day = cur.outside
            own = parse_day(get(doc, self.date_field)) if self.date_field else None
        elif self.as_of:
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
        if cur is not None:
            promoted[LOAD_ID], promoted[SOURCE_FILE] = cur.load_id, cur.label
        h = int.from_bytes(hashlib.blake2b(ident.encode("utf-8"), digest_size=8).digest(), "big")
        seen = self._seen.setdefault(day, set())
        if h in seen:
            st.duplicates[day.isoformat()] += 1                           # one copy will be dropped (resolve)
            self._twice.setdefault(day, set()).add(h)
        elif st.accepted < DUPLICATE_WATCH:
            seen.add(h)
        st.accepted += 1
        if cur is not None:
            cur.accepted += 1
            if own is not None and own != day:
                cur.other += 1
        if st.sample is None:
            st.sample = (ident, day.isoformat())
        part = self.parts.get((day, values))
        if part is None:
            part = self.parts[(day, values)] = Part(day, values, self.folder / f"{len(self.parts) + 1}.rows" if self.folder else pathlib.Path(""))
        part.rows += 1
        text = json.dumps(doc, ensure_ascii=False, separators=(",", ":"))
        st.doc_bytes += len(text)
        if self.folder:
            self._handle(part).write(row_line(ident, text, promoted, st.read, where))

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

    # ----------------------------------------------------------------------------------------------- duplicates

    def resolve(self) -> None:
        """The duplicate-record rule (TOOLS.md section 4.13; the Java loader's Latest and LoadReport do the same): of an id's copies on one
        business date, the one with the latest ``latest-by`` timestamp is kept, else (none, or a tie) the last one read in input order
        (files sorted by path, then line order); a copy with a timestamp beats one without. The others leave their staged files and the
        row counts, are counted in ``duplicates`` and named in ``duplicate_examples``. Only the dates with duplicates are read again.
        Without staged files (a dry run without --lake) the counts stand and nothing is named."""
        self.close()
        if not self.folder or not self._twice:
            return
        st = self.stats
        examples = []
        for day, twice in self._twice.items():
            parts = [p for p in self.parts.values() if p.day == day]
            copies: dict[str, list] = {}
            for part in parts:
                with open(part.file, encoding="utf-8") as fh:
                    for line in fh:
                        r = json.loads(line)
                        ident = r["id"]
                        if int.from_bytes(hashlib.blake2b(ident.encode("utf-8"), digest_size=8).digest(), "big") not in twice:
                            continue
                        ts = None
                        if self.latest_by:
                            v = get(json.loads(r["doc"]), self.latest_by)
                            ts = instant_micros(v if v is not MISSING else None)
                        copies.setdefault(ident, []).append((ts, r["n"], r["w"], part))
            drop: dict[int, Part] = {}
            for cs in copies.values():
                if len(cs) < 2:
                    continue
                best = max(cs, key=lambda c: (c[0] is not None, c[0] or 0, c[1]))
                for c in cs:
                    if c is not best:
                        drop[c[1]] = c[3]
                        examples.append((day, c[1], best[2], c[2]))
            iso = day.isoformat()
            if drop:
                st.duplicates[iso] = len(drop)
            else:
                st.duplicates.pop(iso, None)
            for part in {id(p): p for p in drop.values()}.values():
                kept = part.file.with_suffix(".kept")
                with open(part.file, encoding="utf-8") as src, open(kept, "w", encoding="utf-8") as out:
                    for line in src:
                        if json.loads(line)["n"] not in drop:
                            out.write(line)
                kept.replace(part.file)
                part.rows -= sum(1 for p in drop.values() if p is part)
        for k in [k for k, p in self.parts.items() if p.rows <= 0]:
            self.parts[k].file.unlink(missing_ok=True)
            del self.parts[k]
        examples.sort(key=lambda e: (e[0], e[1]))
        st.duplicate_examples = [{"date": d.isoformat(), "kept": k, "dropped": w} for d, _, k, w in examples[:KEEP_DUPLICATE_EXAMPLES]]

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


def row_line(ident, text: str, promoted: dict, n: int | None = None, where: str | None = None) -> str:
    """One staged row: one JSON object a line, {"id", "doc" (the document as JSON text), "columns" (the promoted values)} (2.9.4,
    docs/guides/DATA_GUIDE.md); the Java writer (LakeWriter) reads the same form. While staging, also "n" (the document's place in the
    input) and "w" (file:line), which the duplicate rule reads (Stager.resolve) and the writers ignore."""
    row = {"id": ident, "doc": text, "columns": promoted}
    if n is not None:
        row["n"], row["w"] = n, where
    return json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n"


def read_rows(file: pathlib.Path, batch: int):
    """The staged rows of a file, ``batch`` at a time: lists of (id, document JSON, promoted). A staged file of the earlier form, one
    array [id, doc, promoted] a line, is read too."""
    chunk = []
    with open(file, encoding="utf-8") as fh:
        for line in fh:
            r = json.loads(line)
            chunk.append((r["id"], r["doc"], r.get("columns") or {}) if isinstance(r, dict) else tuple(r))
            if len(chunk) >= batch:
                yield chunk
                chunk = []
    if chunk:
        yield chunk
