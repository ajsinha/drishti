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

"""Sixty business days of every dataset, as committed test data: the first day a full snapshot, each later day only what changed.

    trades        a trade joins the book on the first business day on or after its TRADE_DATE (a new booking is a change of that
                  day); each later day about 6% of the book is amended (tools/packgen/trades/amend.py: a rate, a notional, the status,
                  an added fee or settlement; TRADE_VERSION, TRADE_ACTION, TRANSACTION_ID and SOURCE_UPDATE_TIME follow), so Compare
                  across two days shows real changes
    everything    the last day is the universe as built (it reconciles: a netting set's NET_MTM is the sum of its trades'); earlier days
                  move the market-sensitive numbers back by a deterministic walk (tools/samplegen/lake.py) and carry their own date in
                  AS_OF, VALUATION_DATE and RUN_DATE; a valuation exists from its trade's booking day and values the trade's version
                  of the day (valuation_versions); a netting set lists its trades booked by that day (the universe holds only the
                  trades live on the last day: universe.py); SPOT_DATE and an IR curve's pillar maturities move with the day
    market data   equities, indices, FX, bonds, fixings, commodities and the IR, repo, commodity and FX forward curves are built per day
                  by marketdays.py instead of the walk: the day's price is the close of the document's own series on that day, and the
                  fields quoted next to it move with it (BID/ASK around MID, a bond's YIELD, Z_SPREAD and MOD_DURATION against its PRICE,
                  a curve's points together), QA2-201

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
# Dates a market document holds at a fixed number of business days from its business date (an FX spot's value date, T+2; an IR curve's
# pillar maturities): moved with the day, by as many USNY business days as the day is from the last one, so the 2026-09-29 spot does
# not settle on the 2026-09-30 spot's date. tools/lake/demo.py moves the same fields when it moves the dates to today.
TENOR_DATES = ("SPOT_DATE",)
TENOR_POINTS = {"POINTS": "MATURITY"}
# The price a document's CHANGE_1D is the day's move of (equity PRICE, index LEVEL, FX MID): worked out from the walked price of the day
# and of the day before, so it moves with the price instead of staying the last day's figure on every day (QA1-10).
PRICE_OF_CHANGE = ("PRICE", "LEVEL", "MID", "SPOT", "LAST_PRICE")


def day_change(body: dict, before: dict) -> None:
    """Sets body's CHANGE_1D (a fraction, five places) from its price and the previous day's; left as it is without both prices."""
    if "CHANGE_1D" not in body:
        return
    f = next((k for k in PRICE_OF_CHANGE if isinstance(body.get(k), (int, float)) and isinstance(before.get(k), (int, float)) and before.get(k)), None)
    if f:
        body["CHANGE_1D"] = round(body[f] / before[f] - 1, 5)


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
    for live in books(book, days, seed):
        yield {k: dumps(t) for k, t in live.items()}


def timelines(book: list[dict], days: Days, seed: int = 5) -> dict[str, list[tuple[int, dict]]]:
    """Each trade's versions as trade_states writes them: TRADE_ID -> [(the day index it took effect, the trade)], oldest first. The
    last is the trade on the last day (its latest version, status and terms), which the netting sets and valuations follow."""
    out: dict[str, list[tuple[int, dict]]] = {}
    for i, live in enumerate(books(book, days, seed)):
        for k, t in live.items():
            seen = out.setdefault(k, [])
            if not seen or seen[-1][1] is not t:
                seen.append((i, t))
    return out


def books(book: list[dict], days: Days, seed: int = 5):
    """The book of each day as {TRADE_ID: trade} (the documents themselves: an unchanged trade is the same object as the day before)."""
    joins = {}
    for t in book:
        joins.setdefault(days.booked(t.get("TRADE_DATE")), []).append(t)
    live: dict[str, dict] = {}
    for i, day in enumerate(days.dates):
        if i:
            for k, t in list(live.items()):
                live[k] = A.amend(t, day, seed)
        for t in joins.get(i, []):
            stamp = str(t.get("SOURCE_UPDATE_TIME") or "")
            if stamp[:10] > day:                              # a feed stamps a trade when it sends it: never after the day it joins
                t["SOURCE_UPDATE_TIME"] = f"{day}{stamp[10:]}"
            live[str(t["TRADE_ID"])] = t
        yield live


def doc_states(kind: str, docs: dict[str, dict], days: Days, since: dict[str, int] | None = None, members: dict | None = None,
               market=None, versions: dict | None = None):
    """The documents of each day as {id: json}: the last day as built, earlier days walked back; ``since`` (id -> first day index)
    leaves a document out before that day; ``members`` (trade id -> first day) trims a netting set's TRADE_IDS and TRADES rows to the
    trades booked. ``market`` (marketdays.MarketDays) builds the day's market documents of the kinds it handles, their quoted fields
    moving together (QA2-201), instead of the walk. ``versions`` (id -> [(first day index, trade status, notional factor)], valuation_versions)
    gives a trade valuation the status and notional of its trade's version of the day."""
    last = len(days.dates) - 1
    prev: dict[str, dict] = {}
    coherent = market is not None and market.handles(kind)

    def body_of(id_: str, doc: dict, i: int) -> dict:
        if coherent:
            return market.day(kind, id_, i)
        return walk(doc, id_, last - i, _seed(kind, id_)) if last - i else dict(doc)

    for i, day in enumerate(days.dates):
        out = {}
        for id_, doc in docs.items():
            if since and since.get(id_, 0) > i:
                continue
            body = body_of(id_, doc, i)
            if "CHANGE_1D" in body:
                before = prev.get(id_) or body_of(id_, doc, i - 1)
                prev[id_] = dict(body)
                day_change(body, before)
            for f in DATED:
                if isinstance(body.get(f), str) and len(body[f]) == 10:
                    body[f] = day
            if last - i:
                move_tenor_dates(body, i - last)
            if members is not None and isinstance(body.get("TRADE_IDS"), list):
                body["TRADE_IDS"] = [t for t in body["TRADE_IDS"] if members.get(t, 0) <= i]
                if isinstance(body.get("TRADES"), list):
                    body["TRADES"] = [r for r in body["TRADES"] if members.get(str(r.get("TRADE_ID")), 0) <= i]
            if versions and id_ in versions:
                as_of_day(body, versions[id_], i)
            out[id_] = dumps(body)
        yield out


_CAL: list = []


def move_tenor_dates(doc: dict, n: int, cal=None) -> None:
    """Moves a document's TENOR_DATES and TENOR_POINTS dates by ``n`` business days (``cal``, default USNY)."""
    from datetime import date
    if cal is None:
        if not _CAL:
            from samplegen.dates import Calendar
            _CAL.append(Calendar.of("USNY"))
        cal = _CAL[0]

    def one(v):
        if not (isinstance(v, str) and len(v) == 10 and v[4] == "-"):
            return v
        try:
            return cal.add_business_days(date.fromisoformat(v), n).isoformat()
        except ValueError:
            return v
    for f in TENOR_DATES:
        if f in doc:
            doc[f] = one(doc[f])
    for lst, f in TENOR_POINTS.items():
        points = doc.get(lst)
        if isinstance(points, list):
            for pt in points:
                if isinstance(pt, dict) and f in pt:
                    pt[f] = one(pt[f])


#: a trade valuation's figures that scale with its trade's notional
SCALES_WITH_NOTIONAL = ("NOTIONAL", "MTM", "PNL_1D", "DV01")


def leg_notional(trade: dict) -> float:
    """The notional of a trade's first leg (the one a valuation and a trade's title show), 0 when it has none."""
    try:
        return abs(float(((trade.get("LEGS") or [{}])[0]).get("NOTIONAL") or 0.0))
    except (TypeError, ValueError):
        return 0.0


def valuation_versions(timeline: dict[str, list[tuple[int, dict]]]) -> dict[str, list[tuple[int, str, float]]]:
    """VALUATION_ID -> [(first day index, the trade's status, its notional over the last version's)] for every trade with more than
    one version: the valuation of a day values the trade's version of that day (QA: a valuation never of an older version)."""
    out = {}
    for tid, seen in timeline.items():
        if len(seen) < 2:
            continue
        last = leg_notional(seen[-1][1])
        out[f"TVAL-{tid}"] = [(i, str(t.get("TRADE_STATUS") or ""), (leg_notional(t) / last) if last else 1.0) for i, t in seen]
    return out


def as_of_day(body: dict, seen: list[tuple[int, str, float]], i: int) -> None:
    """Sets a valuation's TRADE_STATUS and notional-led figures to its trade's version on day i (``seen`` from valuation_versions)."""
    _, status, factor = next((v for v in reversed(seen) if v[0] <= i), seen[0])
    body["TRADE_STATUS"] = status
    if factor != 1.0:
        for f in SCALES_WITH_NOTIONAL:
            if isinstance(body.get(f), (int, float)) and not isinstance(body[f], bool):
                body[f] = round(body[f] * factor, 2) if isinstance(body[f], float) else round(body[f] * factor)


def gz_lines(path: Path):
    with gzip.open(path, "rt", encoding="utf-8") as fh:
        for line in fh:
            if line.strip():
                yield line


def read_text(path: Path) -> str:
    with gzip.open(path, "rt", encoding="utf-8") as fh:
        return fh.read()

