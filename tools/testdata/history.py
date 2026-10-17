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

"""Seven business days of every dataset, as committed test data: the first day a full snapshot, each later day only what changed.

    trades        a trade joins the book on the first business day on or after its TRADE_DATE (a new booking is a change of that
                  day); each later day about 6% of the book is amended (tools/packgen/trades/amend.py: a rate, a notional, the status,
                  an added fee or settlement; TRADE_VERSION, TRADE_ACTION, TRANSACTION_ID and SOURCE_UPDATE_TIME follow), so Compare
                  across two days shows real changes
    everything    the last day is the universe as built (it reconciles: a netting set's NET_MTM is the sum of its trades'); earlier days
                  move the market-sensitive numbers back by a deterministic walk (tools/samplegen/lake.py) and carry their own date in
                  AS_OF, VALUATION_DATE and RUN_DATE; a valuation exists from its trade's booking day; a netting set lists the trades
                  booked by that day

Files: <folder>/snapshot-<first day>.jsonl.gz and <folder>/changes-<day>.jsonl.gz (one document a line, sorted by key; a day with no
change writes an empty file), split into .part-001, .part-002 ... when a file would pass the part limit (default 9 MB compressed).
Deterministic: the same universe writes the same bytes (gzip without a time stamp)."""
from __future__ import annotations

import gzip
import hashlib
import json
import random
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
sys.path.insert(0, str(ROOT / "tools" / "packgen" / "trades"))
from samplegen.lake import walk  # noqa: E402
import amend as A  # noqa: E402

PART_BYTES = 9 * 1024 * 1024
DATED = ("AS_OF", "VALUATION_DATE", "RUN_DATE")


def dumps(doc: dict) -> str:
    return json.dumps(doc, separators=(",", ":"), ensure_ascii=False)


class GzParts:
    """Writes lines into <folder>/<stem>.jsonl.gz, or .part-NNN.jsonl.gz files of at most ``limit`` compressed bytes each."""

    def __init__(self, folder: Path, stem: str, limit: int = PART_BYTES):
        self.folder, self.stem, self.limit = folder, stem, limit
        self.parts: list[Path] = []
        self.lines = 0
        self._open()

    def _open(self) -> None:
        self.folder.mkdir(parents=True, exist_ok=True)
        path = self.folder / f"{self.stem}.part-{len(self.parts) + 1:03d}.jsonl.gz"
        self.parts.append(path)
        self.raw = path.open("wb")
        self.gz = gzip.GzipFile(filename="", mode="wb", fileobj=self.raw, mtime=0, compresslevel=9)

    def write(self, line: str) -> None:
        if self.raw.tell() > self.limit - 256 * 1024 and self.lines:
            self._close_one()
            self._open()
        self.gz.write(line.encode("utf-8") + b"\n")
        self.lines += 1

    def _close_one(self) -> None:
        self.gz.close()
        self.raw.close()

    def close(self) -> list[Path]:
        self._close_one()
        if len(self.parts) == 1:                              # not split: the plain name
            plain = self.folder / f"{self.stem}.jsonl.gz"
            self.parts[0].replace(plain)
            self.parts = [plain]
        return self.parts


def _seed(*parts) -> random.Random:
    return random.Random(int(hashlib.sha256("/".join(map(str, parts)).encode()).hexdigest()[:12], 16))


class Days:
    """The business days and, per trade, the day it joins the book."""

    def __init__(self, dates: list[str]):
        self.dates = dates

    def booked(self, trade_date) -> int:
        """The index of the first day on or after TRADE_DATE (a blank or earlier date: the first day)."""
        td = str(trade_date or "")
        for i, d in enumerate(self.dates):
            if td <= d:
                return i
        return len(self.dates) - 1


class DatasetHistory:
    """One dataset's days: ``day(i)`` the documents of day i by key (JSON text), from which the snapshot and the changes are written."""

    def __init__(self, folder: Path, key: str, days: Days):
        self.folder, self.key, self.days = folder, key, days
        self.written: list[dict] = []

    def write(self, states) -> dict:
        """states: an iterable of {key: json text} per day, in order. Returns what was written (files, counts)."""
        prev: dict[str, str] = {}
        for i, state in enumerate(states):
            day = self.days.dates[i]
            if i == 0:
                out, stem = state, f"snapshot-{day}"
            else:
                out, stem = {k: v for k, v in state.items() if prev.get(k) != v}, f"changes-{day}"
            w = GzParts(self.folder, stem)
            for k in sorted(out, key=_order):
                w.write(out[k])
            files = w.close()
            self.written.append({"day": day, "kind": "snapshot" if i == 0 else "changes", "documents": len(out), "total": len(state),
                                 "files": [f.name for f in files], "bytes": sum(f.stat().st_size for f in files)})
            prev = state
        return {"days": self.written}


def _order(k: str):
    return (0, int(k)) if k.isdigit() else (1, k)


def trade_states(book: list[dict], days: Days, seed: int = 5):
    """The book of each day as {TRADE_ID: json}: bookings by TRADE_DATE, then amendments day by day."""
    joins = {}
    for t in book:
        joins.setdefault(days.booked(t.get("TRADE_DATE")), []).append(t)
    live: dict[str, dict] = {}
    for i, day in enumerate(days.dates):
        if i:
            for k, t in list(live.items()):
                live[k] = A.amend(t, day, seed)
        for t in joins.get(i, []):
            live[str(t["TRADE_ID"])] = t
        yield {k: dumps(t) for k, t in live.items()}


def doc_states(kind: str, docs: dict[str, dict], days: Days, since: dict[str, int] | None = None, members: dict | None = None):
    """The documents of each day as {id: json}: the last day as built, earlier days walked back; ``since`` (id -> first day index)
    leaves a document out before that day; ``members`` (trade id -> first day) trims a netting set's TRADE_IDS to the trades booked."""
    last = len(days.dates) - 1
    for i, day in enumerate(days.dates):
        out = {}
        for id_, doc in docs.items():
            if since and since.get(id_, 0) > i:
                continue
            steps = last - i
            body = walk(doc, id_, steps, _seed(kind, id_)) if steps else dict(doc)
            for f in DATED:
                if isinstance(body.get(f), str) and len(body[f]) == 10:
                    body[f] = day
            if members is not None and isinstance(body.get("TRADE_IDS"), list):
                body["TRADE_IDS"] = [t for t in body["TRADE_IDS"] if members.get(t, 0) <= i]
            out[id_] = dumps(body)
        yield out


def gz_lines(path: Path):
    with gzip.open(path, "rt", encoding="utf-8") as fh:
        for line in fh:
            if line.strip():
                yield line


def read_text(path: Path) -> str:
    with gzip.open(path, "rt", encoding="utf-8") as fh:
        return fh.read()

