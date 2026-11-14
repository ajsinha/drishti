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

"""One streaming pass over every record of every supplied file (the Pack designer's analysis, `drishti.py pack new`).

Nothing is sampled: every record is read once, one at a time, and folded into running counts, so a very large ``.jsonl.gz`` works in
the memory of its counts. Per kind it gathers:

- every discriminator combination (``--match`` fields) with its count, so a combination seen once still gets its Sutra;
- every field seen anywhere (dotted paths, ``[]`` for the elements of an array of objects) with the JSON types it held and how often;
- the id's uniqueness over the whole input: a 64-bit hash of every id in a set (about 70 bytes an id), duplicates counted and the
  first ones named;
- the business dates present (``--date``) and the rows without one.

Only what Sutras and previews *show* is a chosen few: up to ``samples`` documents per combination, spread over the dates seen.
``head()`` reads the first rows of the first files to infer what the command line did not give (key, date, match); the full pass then
verifies it.
"""
from __future__ import annotations

import hashlib
import sys
import time
from collections import Counter
from dataclasses import dataclass, field

sys.path.insert(0, str(__import__("pathlib").Path(__file__).resolve().parents[1]))
import sutragen as SG  # noqa: E402
sys.path.insert(0, str(__import__("pathlib").Path(__file__).resolve().parents[1] / "lake"))
import dataprofile as DP  # noqa: E402  (the field walk and the key, date and match candidates of `data profile`)

DUPES_NAMED = 20
DISTINCT = 1000                                         # distinct values per field `data profile` tracks (inference over the head only)
TYPE_NAMES = {bool: "boolean", int: "integer", float: "number", str: "string", dict: "object", list: "array", type(None): "null"}
ISO = DP.ISO_DATE


class Stat:
    """One field over every row: rows that have it, its JSON types, ISO dates and timestamps among its strings, a few examples."""
    __slots__ = ("docs", "types", "iso", "strings", "stamps", "examples", "last")

    def __init__(self):
        self.docs = self.iso = self.strings = self.stamps = 0
        self.types: dict[str, int] = {}
        self.examples: list = []
        self.last = -1


def fold(obj: dict, fields: dict, row: int, prefix: str = "", depth: int = 0) -> None:
    """Folds one document into the field statistics (dotted paths, ``[]`` for the elements of an array of objects), every value of it."""
    for k, v in obj.items():
        p = prefix + k
        s = fields.get(p)
        if s is None:
            s = fields[p] = Stat()
        if s.last != row:
            s.last = row
            s.docs += 1
        t = type(v)
        tn = TYPE_NAMES.get(t, "string")
        s.types[tn] = s.types.get(tn, 0) + 1
        if t is str:
            s.strings += 1
            if len(v) >= 10 and v[4] == "-" and ISO.match(v):
                s.iso += 1
                if len(v) > 10:
                    s.stamps += 1
        elif t is dict:
            if depth < 8:
                fold(v, fields, row, p + ".", depth + 1)
            continue
        elif t is list:
            if depth < 8:
                for x in v:
                    if type(x) is dict:
                        fold(x, fields, row, p + "[].", depth + 1)
            continue
        if len(s.examples) < 3 and v is not None and v != "" and v not in s.examples:
            s.examples.append(v)


@dataclass
class KindProfile:
    kind: str
    match: list[str]
    key: str | None
    date: str | None
    samples: int = 5
    rows: int = 0
    fields: dict = field(default_factory=dict)           # path -> Stat
    combos: Counter = field(default_factory=Counter)     # tuple of match values -> rows
    shown: dict = field(default_factory=dict)            # combo -> {date: [docs]}: the few documents shown
    ids: set = field(default_factory=set)
    dup_rows: int = 0
    dup_named: list = field(default_factory=list)
    no_key: int = 0
    dates: Counter = field(default_factory=Counter)
    no_date: int = 0
    head: list = field(default_factory=list)             # the first documents: pick-list columns and the search form

    def add(self, doc: dict, file_date: str | None = None) -> None:
        """One row; ``file_date`` is its file's business date by the load rules (its name), the row's date when there is no --date."""
        fold(doc, self.fields, self.rows)
        self.rows += 1
        combo = tuple(SG.group_value(doc, f) for f in self.match)
        self.combos[combo] += 1
        d = SG.doc_date(doc, self.date) if self.date else file_date
        if d is None:
            self.no_date += 1
        else:
            self.dates[d] += 1
        if self.key:
            v = SG.get(doc, self.key)
            if v is SG.MISSING or v is None:
                self.no_key += 1
            else:
                h = int.from_bytes(hashlib.blake2b(f"{d or ''}\x00{v}".encode("utf-8"), digest_size=8).digest(), "little")
                if h in self.ids:                           # one entity per id per business date (the stores' rule)
                    self.dup_rows += 1
                    if len(self.dup_named) < DUPES_NAMED and str(v) not in self.dup_named:
                        self.dup_named.append(str(v) + (f" on {d}" if d else ""))
                else:
                    self.ids.add(h)
        by_date = self.shown.setdefault(combo, {})
        bucket = by_date.get(d or "")
        if bucket is None:
            if len(by_date) < self.samples * 2:            # a few dates per combination ...
                by_date[d or ""] = [doc]
        elif len(bucket) < self.samples:                    # ... and a few documents per date
            bucket.append(doc)
        if len(self.head) < 200:
            self.head.append(doc)

    def chosen(self, combo: tuple) -> list[dict]:
        """The documents shown for one combination: round-robin over its dates, the most recent first."""
        by_date = self.shown.get(combo, {})
        days = sorted(by_date, reverse=True)
        out, depth = [], 0
        while len(out) < self.samples and any(depth < len(by_date[x]) for x in days):
            for x in days:
                if depth < len(by_date[x]) and len(out) < self.samples:
                    out.append(by_date[x][depth])
            depth += 1
        return out

    def field_types(self) -> dict[str, dict[str, int]]:
        return {p: dict(sorted(f.types.items(), key=lambda x: -x[1])) for p, f in self.fields.items()}

    def unique(self) -> bool:
        return bool(self.key) and self.dup_rows == 0 and self.no_key == 0


@dataclass
class Profile:
    kinds: dict = field(default_factory=dict)            # kind -> KindProfile
    files: int = 0
    rows: int = 0
    bad: int = 0
    undated: list = field(default_factory=list)         # files whose name carries no date (the load rules)
    seconds: float = 0.0
    bad_named: list = field(default_factory=list)


def file_date(f) -> str | None:
    """A file's business date by the load rules (tools/lake/filedate.py, the loaders' rule): the date in its name
    (trade.2026-11-03.jsonl.gz), else none (such a file loads under --as-of, its .ready file's date or the business today)."""
    import filedate
    d = filedate.of(f.name)
    return d.isoformat() if d else None


@dataclass
class Config:
    """What the pass needs per kind, as plain data (it goes to the worker processes): --kind, and (match, key, date) per kind with a default."""
    kind: str | None
    per_kind: dict
    default: tuple
    prefix: str = ""                                    # --kind-prefix: before every kind

    def of(self, kind: str) -> tuple:
        return self.per_kind.get(kind, self.default)


def _scan_file(args) -> Profile:
    f, conf, samples = args
    prof = Profile(files=1)

    def bad(text):
        prof.bad += 1
        if len(prof.bad_named) < 10:
            prof.bad_named.append(text)
    fd = file_date(f)
    if fd is None:
        prof.undated.append(f.name)
    for k, doc, _ in SG.read_records([f], bad):
        kind = conf.prefix + (conf.kind or k)
        kp = prof.kinds.get(kind)
        if kp is None:
            match, key, date = conf.of(kind)
            kp = prof.kinds[kind] = KindProfile(kind, list(match), key, date, samples)
        kp.add(doc, fd)
        prof.rows += 1
    return prof


def merge(into: Profile, p: Profile) -> None:
    """Folds one file's profile into the whole (counts add; ids already seen in another file are duplicates)."""
    into.rows += p.rows
    into.undated += p.undated
    into.bad += p.bad
    into.bad_named += p.bad_named[:max(0, 10 - len(into.bad_named))]
    for kind, b in p.kinds.items():
        a = into.kinds.get(kind)
        if a is None:
            into.kinds[kind] = b
            continue
        a.rows += b.rows
        for path, sb in b.fields.items():
            sa = a.fields.get(path)
            if sa is None:
                a.fields[path] = sb
                continue
            sa.docs += sb.docs
            sa.iso += sb.iso
            sa.strings += sb.strings
            sa.stamps += sb.stamps
            for t, n in sb.types.items():
                sa.types[t] = sa.types.get(t, 0) + n
            sa.examples += [x for x in sb.examples if x not in sa.examples][:max(0, 3 - len(sa.examples))]
        a.combos.update(b.combos)
        a.dates.update(b.dates)
        a.no_date += b.no_date
        a.no_key += b.no_key
        both = len(a.ids & b.ids) if len(a.ids) > len(b.ids) else len(b.ids & a.ids)
        a.dup_rows += b.dup_rows + both
        a.dup_named += b.dup_named[:max(0, DUPES_NAMED - len(a.dup_named))]
        a.ids |= b.ids
        for combo, by_date in b.shown.items():
            mine = a.shown.setdefault(combo, {})
            for d, docs in by_date.items():
                if d in mine:
                    mine[d] += docs[:max(0, a.samples - len(mine[d]))]
                elif len(mine) < a.samples * 2:
                    mine[d] = docs
        a.head += b.head[:max(0, 200 - len(a.head))]


def scan(files, conf: Config, samples: int = 5, workers: int = 1, progress=None) -> Profile:
    """The one pass: every record of every file (``SG.read_records``: .jsonl, .jsonl.gz, .ndjson, .json arrays, loader envelopes), a
    file per task over ``workers`` processes; the files' profiles are merged in file order, so the result does not depend on them."""
    prof = Profile(files=len(files))
    t0 = time.monotonic()
    tasks = [(f, conf, samples) for f in files]
    if workers > 1 and len(files) > 1:
        import concurrent.futures as cf
        import multiprocessing as mp
        with cf.ProcessPoolExecutor(max_workers=min(workers, len(files)), mp_context=mp.get_context("spawn")) as ex:
            results = ex.map(_scan_file, tasks, chunksize=1)
            for i, p in enumerate(results, 1):
                merge(prof, p)
                if progress and i % 50 == 0:
                    progress(prof.rows, time.monotonic() - t0)
    else:
        for i, t in enumerate(tasks, 1):
            merge(prof, _scan_file(t))
            if progress and i % 50 == 0:
                progress(prof.rows, time.monotonic() - t0)
    prof.seconds = time.monotonic() - t0
    return prof


def head(files, limit: int = 2000) -> dict[str, list[dict]]:
    """The first ``limit`` documents per kind (for inference only; the full pass checks what it infers)."""
    out: dict[str, list[dict]] = {}
    for f in files:                                     # a file whose kind (by its name) is full already is not opened: every kind gets its head
        if len(out.get(SG.file_kind(f), ())) >= limit:
            continue
        for k, doc, _ in SG.read_records([f], lambda t: None):
            lst = out.setdefault(k, [])
            if len(lst) < limit:
                lst.append(doc)
            elif all(len(v) >= limit for v in out.values()):
                break
    return out


def drop_absent(kp: KindProfile) -> list[str]:
    """Match fields no row of the kind has (``--match`` given for every kind): dropped, the combinations folded; returns them."""
    gone = [f for f in kp.match if f not in kp.fields]
    if not gone:
        return []
    keep = [i for i, f in enumerate(kp.match) if f not in gone]
    combos, shown = Counter(), {}
    for c, n in kp.combos.items():
        combos[tuple(c[i] for i in keep)] += n
    for c, by_date in kp.shown.items():
        mine = shown.setdefault(tuple(c[i] for i in keep), {})
        for d, docs in by_date.items():
            mine[d] = (mine.get(d, []) + docs)[:kp.samples]
    kp.match, kp.combos, kp.shown = [kp.match[i] for i in keep], combos, shown
    return gone


def candidates(kind: str, docs: list[dict]) -> dict:
    """`data profile`'s key, date and match candidates over the head of a kind."""
    fields: dict = {}
    for d in docs:
        DP.walk(d, "", set(), fields, DISTINCT)
    return DP.analyse(kind, len(docs), fields, DISTINCT)
