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

"""A large trading book for scale tests: N trades over D business days, written as JSON Lines, then loaded into the Delta Lake the server reads.

    uv run --with deltalake --with pyarrow --with pyyaml python tools/samplegen/bulk_trades.py      # 500,000 trades, 3 days
    uv run --with deltalake --with pyarrow --with pyyaml python tools/samplegen/bulk_trades.py --trades 1000000 --days 5
    python3 tools/samplegen/bulk_trades.py --trades 10000 --no-lake                                    # the JSONL only

Generating and loading are separate steps (docs/guides/DATA_GUIDE.md). The generator writes one file per business day,
<out>/<system>/trades-<date>.jsonl.gz (--out, default <data>/build/bulk): one trade document a line, in id order. The loader is
`drishti.py lake build` (tools/lake/build.py), run here on each file unless --no-lake: it replaces one source system's trade table
of the lake (--root, default data/delta: trading/trade_murex, or with --system CALYPSO trade_calypso), partitioned by business date
and PRODUCT_TYPE with the columns the trading pack promotes, as tools/packgen/trades/make_trades.py --lake does, and leaves every
other table as it is; a running server picks the new trades up within a minute. For a book of all three systems at a laptop's size,
with the day's amendments, use make_trades.py instead. To go back to the sample trades over 10 days:
python3 tools/packgen/banking/make_data.py --lake data/delta (with uv run --with deltalake --with pyarrow --with pyyaml).

--jsonl FILE|- writes loader rows instead ({"domain", "kind", "id", "date", "doc", "columns"}: the document and the fields the trading
pack promotes, by path), for tools/load-aerospike.sh and the other stores' loaders.

The trades are the system's sample trades (config/packs/trading/samples/trade.jsonl, written by make_data.py, in the common
schema of tools/packgen/trades/schema.py), kept as they are, and clones of them. A clone continues the system's numbering
from a higher range than the samples (MX-30000000 ..., CLY-4000000 ..., tools/packgen/banking/booking.py), so ids never
collide, keeps its template's product, portfolio, counterparty and legal entity (so every link still opens) and scales its
amounts (NOTIONAL, QUANTITY, AMOUNT at every level) by a factor of its own between 0.2 and 5. Everything is deterministic:
the same arguments give the same files and the same lake.

Memory stays low: all trade ids are listed and sorted first; the workers build the documents in slices of 5,000 and the main
process appends each slice to the day's file as it comes, in order; lake build then reads each file in batches (memory flat).
The rest of the lake (market data, reference data, risk ...) must already be there (make_data.py --lake): a scale test,
not a consistent book.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import multiprocessing
import os
import random
import re
import shutil
import sys
import time
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
sys.path.insert(0, str(ROOT / "tools" / "packgen" / "banking"))
sys.path.insert(0, str(ROOT / "tools" / "packgen" / "common"))

import booking as BK  # noqa: E402
import packsamples  # noqa: E402  (the pack's samples/trade.jsonl)
from samplegen.dates import Calendar  # noqa: E402
from samplegen.lake import business_days, walk  # noqa: E402
from samplegen.layout import Layout, infer_types, layouts_for_domain, promote  # noqa: E402

TEMPLATES = ROOT / "config" / "packs" / "trading" / "samples"            # trade.jsonl
AS_OF = date(2026, 9, 30)                      # the banking lake's newest business date (data_names.AS_OF)
PLACEHOLDER = "\u0000ID\u0000"
# amounts that grow with the trade's size; rates, dates, counts and discount factors stay as they are
SCALE = {"NOTIONAL", "QUANTITY", "AMOUNT"}

_templates: list[tuple[str, str, str]] = []    # (template id, document JSON with its id replaced by PLACEHOLDER, its system)
_layout: Layout = Layout()                     # the trade table's layout (trading pack.yaml), in each worker
_types: dict[str, str] = {}                    # its columns' types, fixed for the whole run


# Workers are spawned, never forked: the main process runs pyarrow and deltalake, whose thread pools hold locks that a
# forked child inherits with no thread left to release them (a fork after threads can hang the workers for good; Python
# warns "os.fork() was called ... multi-threaded"). Spawned workers start clean and get what they need from _init.
POOL_CONTEXT = multiprocessing.get_context("spawn")


def load_templates(system: str = "MUREX") -> list[tuple[str, str, str]]:
    """The sample trades booked in ``system`` (MUREX or CALYPSO: their ids carry a prefix a clone can renumber)."""
    out = []
    for tid, doc in sorted(packsamples.read(TEMPLATES).get("trade", {}).items()):
        doc.pop("_meta", None)
        if doc.get("SOURCE_SYSTEM") != system:
            continue
        text = re.sub(re.escape(tid) + r"(?!\d)", PLACEHOLDER, json.dumps(doc, ensure_ascii=False))
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
    doc = json.loads(text.replace(PLACEHOLDER, new_id))
    if system is not None:
        doc["TRANSACTION_ID"] = f"{native}001"
        factor = random.Random(f"size/{new_id}").lognormvariate(0, 0.6)
        scaled(doc, min(5.0, max(0.2, factor)))
    if steps:                                   # the same walk, with the same seed, as samplegen/lake.py's history
        rnd = random.Random(int(hashlib.sha256(f"trade/{new_id}".encode()).hexdigest()[:12], 16))
        doc = walk(doc, new_id, steps, rnd)
    return new_id, doc                          # the business date is the row's partition, never a field of the trade


def build(i: int, day: date, steps: int) -> tuple[str, str]:
    """Trade number i on one business day: (id, document JSON)."""
    new_id, doc = document(i, day, steps)
    return new_id, json.dumps(doc, ensure_ascii=False)


def _init(templates, lay, types):
    global _templates, _layout, _types
    _templates, _layout, _types = templates, lay, types


def _docs(job):
    """The trade documents of a slice, one JSON line each, in the order given (sorted by id)."""
    indices, day, steps = job
    return [json.dumps(document(i, day, steps)[1], ensure_ascii=False, separators=(",", ":")) for i in indices]


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
    ap.add_argument("--root", default=os.environ.get("DRISHTI_DATA_DIR", "data") + "/delta", help="the lake whose trade table is replaced (default data/delta)")
    ap.add_argument("--as-of", default=AS_OF.isoformat(), help=f"the newest business date (default {AS_OF})")
    ap.add_argument("--workers", type=int, default=max(1, (os.cpu_count() or 2) - 1))
    ap.add_argument("--system", default="MUREX", choices=("MUREX", "CALYPSO"), help="the source system whose trades are cloned and whose table "
                                                                                        "(trading/trade_<system>) is replaced (default MUREX)")
    ap.add_argument("--jsonl", default=None, help="write loader rows to this file (- for standard output) instead, for tools/load-aerospike.sh "
                                                  "and the other stores' loaders: the documents with the fields the trading pack promotes")
    ap.add_argument("--out", default=os.environ.get("DRISHTI_DATA_DIR", "data") + "/build/bulk",
                    help="where the JSONL files go: <out>/<system>/trades-<date>.jsonl.gz (default <data>/build/bulk)")
    ap.add_argument("--no-lake", action="store_true", help="write the JSONL files only; load them later with drishti.py lake build")
    ap.add_argument("--engine", default="auto", choices=("auto", "drishti", "deltalake"), help="the lake writer lake build uses (default auto)")
    a = ap.parse_args(argv)
    if a.trades < 1 or a.days < 1:
        raise SystemExit("--trades and --days must be at least 1")

    lay = layouts_for_domain("trading").get("trade") or Layout()
    templates = load_templates(a.system)
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

    folder = Path(a.out) / a.system.lower()
    folder.mkdir(parents=True, exist_ok=True)
    order = [i for f in plan(a.trades, templates, a.trades) for i in f]          # every trade number in id order
    files, written = [], 0
    with POOL_CONTEXT.Pool(a.workers, initializer=_init, initargs=(templates, lay, types)) as pool:
        for n, d in enumerate(days):
            f = folder / f"trades-{d.isoformat()}.jsonl.gz"
            jobs = [(order[s:s + 5_000], d, len(days) - 1 - n) for s in range(0, a.trades, 5_000)]
            with gzip.open(f, "wt", encoding="utf-8", compresslevel=1) as out:
                for lines in pool.imap(_docs, jobs):                              # in order: the file stays sorted by id
                    out.write("\n".join(lines) + "\n")
                    written += len(lines)
                    print(f"\r{written:,} of {a.trades * len(days):,} trade-days written ({time.time() - t0:,.0f} s)", end="", flush=True)
            files.append(f)
    print(f"\n{folder}: {a.trades:,} trades x {len(days)} business days ({days[0]} to {days[-1]}) as JSON Lines, {time.time() - t0:,.0f} s")
    if a.no_lake:
        return 0
    return load(files, days, root, a.system, a.engine)


def load(files: list[Path], days: list[date], root: Path, system: str, engine: str = "auto") -> int:
    """The loading step: each day's file through lake build into trading/trade_<system> (the table its connector reads), written
    afresh, partitioned by business date and PRODUCT_TYPE. Returns the worst exit code."""
    sys.path.insert(0, str(ROOT / "tools" / "lake"))
    import build as LB

    if not (root / "reference").is_dir():
        print(f"warning: {root} has no reference data; build the lake first: make_data.py --lake {root}")
    table = root / "trading" / f"trade_{system.lower()}"
    if table.exists():
        shutil.rmtree(table)
    worst, t0 = 0, time.time()
    for f, d in zip(files, days):
        args = LB.build_parser().parse_args(["--from", str(f), "--pack", str(ROOT / "config" / "packs" / "trading"), "--kind", "trade",
                                             "--connector", f"trade-{system.lower()}", "--key", "TRADE_ID", "--as-of", d.isoformat(),
                                             "--partition-by", "PRODUCT_TYPE", "--lake", str(root), "--engine", engine, "-q"])
        code, summary = LB.run(args, say=lambda *x, **k: None)
        print(f"  {system} {d}: {summary.get('status')} {summary.get('rows', summary.get('documents', ''))} -> {summary.get('table')}"
              + (f" ({summary.get('error')})" if summary.get("error") else ""))
        worst = max(worst, code)
    print(f"{table}: loaded in {time.time() - t0:,.0f} s; a running server reading this lake picks the trades up within a minute")
    return worst


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
