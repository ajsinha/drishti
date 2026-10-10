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

"""A large trading book for scale tests: N trades over D business days, as a Delta Lake the server reads.

    uv run --with deltalake --with pyarrow --with pyyaml python tools/samplegen/bulk_trades.py      # 500,000 trades, 3 days
    uv run --with deltalake --with pyarrow --with pyyaml python tools/samplegen/bulk_trades.py --trades 1000000 --days 5

It replaces the trade table of the lake the server reads (data/delta/trading/trade) and leaves every other table
as it is; a running server picks the new trades up within a minute (its search index is rebuilt every minute).
To go back to the 750 sample trades over 10 days: python3 tools/packgen/banking/make_data.py --lake data/delta
(with uv run --with deltalake --with pyarrow --with pyyaml), which rewrites the whole lake as it was.

The table follows the layout the trading pack declares for trades (config/packs/trading/pack.yaml, settings.layout.trade;
see samplegen/layout.py): the promoted columns beside id and doc, each business date sorted by id and cut into
files of file-rows trades (--file-rows overrides it), row groups of row-group-rows. All trade ids are listed and
sorted first; the workers build each file's documents in slices, and the main process writes one file at a time
(one append each), so memory holds about two files' rows whatever the size of the book.

The trades are the trading pack's 750 sample trades (config/packs/trading/samples/trade, written by make_data.py), kept
as they are, and clones of them. Every trade is booked in the system its asset class lives in and carries that
system's number (tools/packgen/banking/booking.py): Murex MX-… (rates, inflation), Calypso CLY-… (credit), Endur
END-… (commodities), Imagine IMG-… (equity, structured), Bloomberg TOMS BBG-… (fixed income, securities financing),
Wall Street Systems WSS-… (FX, money markets). A clone stays in its template's system and continues that system's
numbering from a higher range than the samples (MX-30000000 …, CLY-4000000 …), so ids never collide; sourceSystem
and sourceTradeId say where each trade lives. Each clone keeps its template's product, book, desk,
counterparty, netting set and curves (so every link still opens) and scales its amounts (notional, MTM, P&L,
DV01, cashflows, the P&L explain steps and the lifecycle timeline's amounts) by a factor of its own between 0.2 and 5;
the timeline's dates and descriptions carry no amount, so they stay true for every clone. Past business days move the market-sensitive numbers
by the same deterministic walk as the rest of the lake. Everything is deterministic: the same arguments give the
same lake.

The rest of the lake (market data, reference data, risk …) must already be there (make_data.py --lake). Netting
sets still carry the net MTM of the 750 templates only: a scale test, not a consistent book.

Rough size: about 1.7 KB per trade per day on disk (1,000,000 trades x 3 days is about 5.1 GB: four files of
250,000 trades a day, 25 row groups each) and about 30 seconds per million trade-days on 24 cores; the main
process peaks at about 9.5 GB with 250,000-trade files, whatever the number of trades.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import multiprocessing
import os
import random
import re
import shutil
import sys
import time
from collections import deque
from dataclasses import replace
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
sys.path.insert(0, str(ROOT / "tools" / "packgen" / "banking"))

import booking as BK  # noqa: E402
from samplegen.dates import Calendar  # noqa: E402
from samplegen.lake import business_days, walk  # noqa: E402
from samplegen.layout import Layout, arrow_table, infer_types, layouts_for_domain, promote, write_file  # noqa: E402

TEMPLATES = ROOT / "config" / "packs" / "trading" / "samples" / "trade"
AS_OF = date(2026, 9, 30)                      # the banking lake's newest business date (data_names.AS_OF)
PLACEHOLDER = "\u0000ID\u0000"
# amounts that grow with the trade's size; rates, dates, counts and discount factors stay as they are
SCALE = {"notional", "mtm", "pnl1d", "pnl", "dv01", "pv01", "cs01", "vega", "delta", "gamma", "theta", "amount", "pv", "npv",
         "accrued", "marketValue", "exposure", "premium", "principal", "quantity", "collateral", "margin"}

_templates: list[tuple[str, str, str]] = []    # (template id, document JSON with its id replaced by PLACEHOLDER, its system)
_layout: Layout = Layout()                     # the trade table's layout (trading pack.yaml), in each worker
_types: dict[str, str] = {}                    # its columns' types, fixed for the whole run


# Workers are spawned, never forked: the main process runs pyarrow and deltalake, whose thread pools hold locks that a
# forked child inherits with no thread left to release them (a fork after threads can hang the workers for good; Python
# warns "os.fork() was called ... multi-threaded"). Spawned workers start clean and get what they need from _init.
POOL_CONTEXT = multiprocessing.get_context("spawn")


def load_templates() -> list[tuple[str, str, str]]:
    out = []
    for f in sorted(TEMPLATES.glob("*.json")):
        doc = json.loads(f.read_text(encoding="utf-8"))
        doc.pop("_meta", None)
        tid = f.stem
        system = doc.get("sourceSystem") or "Murex"
        native = str(doc.get("sourceTradeId") or "")
        text = json.dumps(doc, ensure_ascii=False)
        if native:                                  # the system's own number is the id's number: both change in a clone
            text = text.replace(f'"sourceTradeId": "{native}"', f'"sourceTradeId": "{PLACEHOLDER}N"')
        text = re.sub(re.escape(tid) + r"(?!\d)", PLACEHOLDER, text)
        out.append((tid, text, system))
    if not out:
        raise SystemExit(f"no template trades in {TEMPLATES}: run tools/packgen/banking/make_data.py first")
    return out


def trade_id(i: int, templates: list) -> tuple[str, str | None, str | None]:
    """(id, source system, the system's own number): the first len(templates) trades are the samples as they are;
    clone i is booked in its template's system and numbered in that system's clone range, in order."""
    if i < len(templates):
        return templates[i][0], None, None
    rank, count = _ranks(templates)
    t = i % len(templates)
    system = templates[t][2]
    ordinal = (i // len(templates) - 1) * count[system] + rank[t]
    new_id, native = BK.clone_id(system, ordinal)
    return new_id, system, native


_RANKS: dict[int, tuple[list[int], dict[str, int]]] = {}


def _ranks(templates: list) -> tuple[list[int], dict[str, int]]:
    """Each template's place among its system's templates, and how many each system has (cached per template list)."""
    key = id(templates)
    if key not in _RANKS:
        rank, count = [], {}
        for _, _, system in templates:
            rank.append(count.get(system, 0))
            count[system] = count.get(system, 0) + 1
        _RANKS[key] = (rank, count)
    return _RANKS[key]


def scaled(doc, factor: float):
    if isinstance(doc, dict):
        for k, v in doc.items():
            if k in SCALE and isinstance(v, (int, float)) and not isinstance(v, bool):
                nv = v * factor
                doc[k] = round(nv) if isinstance(v, int) else round(nv, 2)
            else:
                scaled(v, factor)
    elif isinstance(doc, list):
        for x in doc:
            scaled(x, factor)
    return doc


def document(i: int, day: date, steps: int) -> tuple[str, dict]:
    """Trade number i on one business day: (id, document)."""
    tid, text, _ = _templates[i % len(_templates)]
    new_id, system, native = trade_id(i, _templates)
    doc = json.loads(text.replace(PLACEHOLDER + "N", native or tid.split("-", 1)[1]).replace(PLACEHOLDER, new_id))
    if system is not None:
        doc["sourceSystem"] = system
        doc["sourceTradeId"] = native
        factor = random.Random(f"size/{new_id}").lognormvariate(0, 0.6)
        scaled(doc, min(5.0, max(0.2, factor)))
    if steps:                                   # the same walk, with the same seed, as samplegen/lake.py's history
        rnd = random.Random(int(hashlib.sha256(f"trade/{new_id}".encode()).hexdigest()[:12], 16))
        doc = walk(doc, new_id, steps, rnd)
    doc["businessDate"] = day.isoformat()
    return new_id, doc


def build(i: int, day: date, steps: int) -> tuple[str, str]:
    """Trade number i on one business day: (id, document JSON)."""
    new_id, doc = document(i, day, steps)
    return new_id, json.dumps(doc, ensure_ascii=False)


def _init(templates, lay, types):
    global _templates, _layout, _types
    _templates, _layout, _types = templates, lay, types


def _piece(job):
    """Rows of one file's slice, in the order given (sorted by id), as Arrow: id, doc, business_date, promoted columns."""
    indices, day, steps = job
    ids, texts, cols = [], [], {n: [] for n in _layout.names}
    for i in indices:
        new_id, doc = document(i, day, steps)
        ids.append(new_id)
        texts.append(json.dumps(doc, ensure_ascii=False))
        for n, v in promote(doc, _layout).items():
            cols[n].append(v)
    return arrow_table(day, ids, texts, cols, _types)


def _lines(job):
    """Rows of a slice as JSON lines for tools/load-aerospike.sh: the document and its promoted values by path."""
    indices, day, steps = job
    names = dict(zip(_layout.names, _layout.columns))
    out = []
    for i in indices:
        new_id, doc = document(i, day, steps)
        columns = {names[n]: v for n, v in promote(doc, _layout).items()}
        out.append(json.dumps({"domain": "trading", "kind": "trade", "id": new_id, "date": day.isoformat(),
                               "doc": json.dumps(doc, ensure_ascii=False), "columns": columns}, ensure_ascii=False))
    return out


def plan(trades: int, templates: list, file_rows: int) -> list[list[int]]:
    """Trade numbers in id order, cut into files of file_rows: each file holds its own contiguous id range."""
    ids = [trade_id(i, templates)[0] for i in range(trades)]
    order = sorted(range(trades), key=ids.__getitem__)
    return [order[s:s + file_rows] for s in range(0, trades, file_rows)]


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--trades", type=int, default=500_000, help="how many trades (default 500000; the first 750 are the samples)")
    ap.add_argument("--days", type=int, default=3, help="business days of history, ending at --as-of (default 3)")
    ap.add_argument("--root", default=__import__("os").environ.get("DRISHTI_DATA_DIR", "data") + "/delta", help="the lake whose trade table is replaced (default data/delta)")
    ap.add_argument("--as-of", default=AS_OF.isoformat(), help=f"the newest business date (default {AS_OF})")
    ap.add_argument("--workers", type=int, default=max(1, (os.cpu_count() or 2) - 1))
    ap.add_argument("--file-rows", type=int, default=None, help="trades per Parquet file (default: the trading pack's layout, file-rows)")
    ap.add_argument("--jsonl", default=None, help="write JSON lines to this file (- for standard output) instead of the lake, for "
                                                  "tools/load-aerospike.sh: the documents with the fields the trading pack promotes")
    a = ap.parse_args(argv)
    if a.trades < 1 or a.days < 1:
        raise SystemExit("--trades and --days must be at least 1")

    import pyarrow as pa

    lay = layouts_for_domain("trading").get("trade") or Layout()
    if a.file_rows:
        lay = replace(lay, file_rows=a.file_rows)
    templates = load_templates()
    _init(templates, lay, {})
    sample = [document(i, AS_OF, 0)[1] for i in range(min(a.trades, len(templates) + 6))]
    types = infer_types({n: [promote(d, lay)[n] for d in sample] for n in lay.names})
    root = Path(a.root)
    days = business_days(date.fromisoformat(a.as_of), a.days, Calendar.of("USNY"))
    t0 = time.time()
    if a.jsonl:                                       # a stream for Aerospike (or anything that reads JSON lines)
        out = sys.stdout if a.jsonl == "-" else open(a.jsonl, "w", encoding="utf-8")
        jobs = [(list(range(s, min(s + 5_000, a.trades))), d, len(days) - 1 - n) for n, d in enumerate(days) for s in range(0, a.trades, 5_000)]
        written = 0
        with POOL_CONTEXT.Pool(a.workers, initializer=_init, initargs=(templates, lay, types)) as pool:
            for lines in pool.imap_unordered(_lines, jobs):
                out.write("\n".join(lines) + "\n")
                written += len(lines)
                print(f"\r{written:,} of {a.trades * len(days):,} trade-days written ({time.time() - t0:,.0f} s)", end="", flush=True, file=sys.stderr)
        if out is not sys.stdout:
            out.close()
        print(f"\n{written:,} trade-days as JSON lines in {time.time() - t0:,.0f} s", file=sys.stderr)
        return 0

    if not (root / "reference").is_dir():
        print(f"warning: {root} has no reference data; build the lake first: make_data.py --lake {root}")
    table = root / "trading" / "trade"
    if table.exists():
        shutil.rmtree(table)
    table.parent.mkdir(parents=True, exist_ok=True)
    files = plan(a.trades, templates, lay.file_rows)
    piece = max(1, min(5_000, -(-lay.file_rows // (2 * a.workers))))
    # one job per slice of a file; the main process writes each file once its slices are in, while the workers
    # build the next one (about two files in memory)
    jobs = [[(chunk[s:s + piece], d, len(days) - 1 - n) for s in range(0, len(chunk), piece)] for n, d in enumerate(days) for chunk in files]
    written, window = 0, -(-lay.file_rows // piece)
    with POOL_CONTEXT.Pool(a.workers, initializer=_init, initargs=(templates, lay, types)) as pool:
        pending: deque = deque()
        queue = deque((k, len(slices), job) for slices in jobs for k, job in enumerate(slices))
        current: list = []
        while queue or pending:
            while queue and len(pending) < window:
                k, count, job = queue.popleft()
                pending.append((k, count, pool.apply_async(_piece, (job,))))
            k, count, result = pending.popleft()
            current.append(result.get())
            if k == count - 1:
                rows = pa.concat_tables(current).combine_chunks()
                current = []
                write_file(str(table), rows, lay, "append")
                written += rows.num_rows
                del rows
                print(f"\r{written:,} of {a.trades * len(days):,} trade-days written ({time.time() - t0:,.0f} s)", end="", flush=True)
    size = sum(f.stat().st_size for f in table.rglob("*") if f.is_file())
    print(f"\n{table}: {a.trades:,} trades x {len(days)} business days ({days[0]} to {days[-1]}), {len(files)} files a day, "
          f"{size / 1e9:,.2f} GB, {time.time() - t0:,.0f} s")
    print("a running server reading this lake picks the trades up within a minute")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
