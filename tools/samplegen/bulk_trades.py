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

    uv run --with deltalake --with pyarrow python tools/samplegen/bulk_trades.py              # 500,000 trades, 3 days
    uv run --with deltalake --with pyarrow python tools/samplegen/bulk_trades.py --trades 1000000 --days 5

It replaces the trade table of the lake the server reads (data/delta/trading/trade) and leaves every other table
as it is; a running server picks the new trades up within a minute (its search index is rebuilt every minute).
To go back to the 750 sample trades over 10 days: python3 tools/packgen/banking/make_data.py --lake data/delta
(with uv run --with deltalake --with pyarrow), which rewrites the whole lake as it was.

The trades are the trading pack's 750 sample trades (packs/trading/samples/trade, written by make_data.py), kept
as they are, and clones of them: T-1000001, T-1000002, … Each clone keeps its template's product, book, desk,
counterparty, netting set and curves (so every link still opens) and scales its amounts (notional, MTM, P&L,
DV01, cashflows) by a factor of its own between 0.2 and 5. Past business days move the market-sensitive numbers
by the same deterministic walk as the rest of the lake. Everything is deterministic: the same arguments give the
same lake.

The rest of the lake (market data, reference data, risk …) must already be there (make_data.py --lake). Netting
sets still carry the net MTM of the 750 templates only: a scale test, not a consistent book.

Rough size: about 1.1 KB per trade per day on disk (500,000 trades x 3 days is about 1.6 GB) and one to two
minutes per million trade-days on 24 cores.
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
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))

from samplegen.dates import Calendar  # noqa: E402
from samplegen.lake import business_days, walk  # noqa: E402

TEMPLATES = ROOT / "packs" / "trading" / "samples" / "trade"
AS_OF = date(2026, 9, 30)                      # the banking lake's newest business date (data_names.AS_OF)
PLACEHOLDER = "\u0000ID\u0000"
# amounts that grow with the trade's size; rates, dates, counts and discount factors stay as they are
SCALE = {"notional", "mtm", "pnl1d", "pnl", "dv01", "pv01", "cs01", "vega", "delta", "gamma", "theta", "amount", "pv", "npv",
         "accrued", "marketValue", "exposure", "premium", "principal", "quantity", "collateral", "margin"}

_templates: list[tuple[str, str]] = []         # (template id, document JSON with its id replaced by PLACEHOLDER)


def load_templates() -> list[tuple[str, str]]:
    out = []
    for f in sorted(TEMPLATES.glob("*.json")):
        doc = json.loads(f.read_text(encoding="utf-8"))
        doc.pop("_meta", None)
        tid = f.stem
        text = re.sub(re.escape(tid) + r"(?!\d)", PLACEHOLDER, json.dumps(doc, ensure_ascii=False))
        out.append((tid, text))
    if not out:
        raise SystemExit(f"no template trades in {TEMPLATES}: run tools/packgen/banking/make_data.py first")
    return out


def trade_id(i: int, templates: list) -> str:
    """The first len(templates) trades keep their ids (T-10001 …); the rest are T-1000001 onwards."""
    return templates[i][0] if i < len(templates) else f"T-{1_000_000 + i - len(templates) + 1}"


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


def build(i: int, day: date, steps: int) -> tuple[str, str]:
    """Trade number i on one business day: (id, document JSON)."""
    tid, text = _templates[i % len(_templates)]
    new_id = trade_id(i, _templates)
    doc = json.loads(text.replace(PLACEHOLDER, new_id))
    if i >= len(_templates):
        factor = random.Random(f"size/{new_id}").lognormvariate(0, 0.6)
        scaled(doc, min(5.0, max(0.2, factor)))
    if steps:                                   # the same walk, with the same seed, as samplegen/lake.py's history
        rnd = random.Random(int(hashlib.sha256(f"trade/{new_id}".encode()).hexdigest()[:12], 16))
        doc = walk(doc, new_id, steps, rnd)
    doc["businessDate"] = day.isoformat()
    return new_id, json.dumps(doc, ensure_ascii=False)


def _init(templates):
    global _templates
    _templates = templates


def _chunk(job):
    start, end, day, steps = job
    rows = [build(i, day, steps) for i in range(start, end)]
    return day, [r[0] for r in rows], [r[1] for r in rows]


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--trades", type=int, default=500_000, help="how many trades (default 500000; the first 750 are the samples)")
    ap.add_argument("--days", type=int, default=3, help="business days of history, ending at --as-of (default 3)")
    ap.add_argument("--root", default="data/delta", help="the lake whose trade table is replaced (default data/delta)")
    ap.add_argument("--as-of", default=AS_OF.isoformat(), help=f"the newest business date (default {AS_OF})")
    ap.add_argument("--workers", type=int, default=max(1, (os.cpu_count() or 2) - 1))
    ap.add_argument("--batch", type=int, default=20_000, help="trades per Parquet file (default 20000)")
    a = ap.parse_args(argv)
    if a.trades < 1 or a.days < 1:
        raise SystemExit("--trades and --days must be at least 1")

    import pyarrow as pa
    from deltalake import write_deltalake

    templates = load_templates()
    root = Path(a.root)
    days = business_days(date.fromisoformat(a.as_of), a.days, Calendar.of("USNY"))
    t0 = time.time()

    if not (root / "reference").is_dir():
        print(f"warning: {root} has no reference data; build the lake first: make_data.py --lake {root}")
    table = root / "trading" / "trade"
    if table.exists():
        shutil.rmtree(table)
    table.parent.mkdir(parents=True, exist_ok=True)
    jobs = [(s, min(s + a.batch, a.trades), d, len(days) - 1 - n) for n, d in enumerate(days) for s in range(0, a.trades, a.batch)]
    written = 0
    with multiprocessing.Pool(a.workers, initializer=_init, initargs=(templates,)) as pool:
        for day, ids, bodies in pool.imap_unordered(_chunk, jobs):
            write_deltalake(str(table), pa.table({"id": pa.array(ids, pa.string()), "doc": pa.array(bodies, pa.string()),
                                                  "business_date": pa.array([day] * len(ids), pa.date32())}),
                            mode="append", partition_by=["business_date"])
            written += len(ids)
            print(f"\r{written:,} of {a.trades * len(days):,} trade-days written ({time.time() - t0:,.0f} s)", end="", flush=True)
    size = sum(f.stat().st_size for f in table.rglob("*") if f.is_file())
    print(f"\n{table}: {a.trades:,} trades x {len(days)} business days ({days[0]} to {days[-1]}), {size / 1e9:,.2f} GB, "
          f"{time.time() - t0:,.0f} s")
    print("a running server reading this lake picks the trades up within a minute")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
