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

"""Trades of the three source systems in the common schema, over several business days, as JSON Lines files and (with --lake) as the
trading pack's Delta tables, one per system (trade_endur, trade_calypso, trade_murex), each read by its own connector.

    python3 tools/packgen/trades/make_trades.py --trades 3000 --days 7 --out data/build/trades
    uv run --with deltalake --with pyarrow --with pyyaml python tools/packgen/trades/make_trades.py --trades 3000 --days 7 \\
        --out data/build/trades --lake data/delta

--trades is per system (ENDUR, CALYPSO, MUREX; --systems picks some). Each system writes <out>/<system>/trades-<date>.jsonl.gz, one file
per business day (the last is --as-of, default the samples' date), each the whole book as booked at the end of that day: about 6% of
the trades are amended each day (amend.py), so dates differ. ENDUR deals come from endur.py (five product types); CALYPSO and MUREX are
the trading pack's sample trades (every product of the taxonomy) and clones of them with numbers of their own and amounts scaled by a
factor between 0.2 and 5. With --lake, each file goes through `drishti.py lake build` (tools/lake/build.py) into the table the system's
connector names, partitioned by business date and PRODUCT_TYPE. Deterministic. Keep it at 10,000 trades or fewer on a laptop.
"""
from __future__ import annotations

import argparse
import copy
import datetime as dt
import gzip
import json
import random
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import refdata as R  # noqa: E402  (puts tools/packgen and banking on the path)
import amend as A  # noqa: E402
import schema as S  # noqa: E402
from endur import Endur  # noqa: E402

ROOT = HERE.parents[2]
CONNECTOR = {"ENDUR": "trade-endur", "CALYPSO": "trade-calypso", "MUREX": "trade-murex"}
CLONE_FROM = {"CALYPSO": ("CLY", 4_000_000), "MUREX": ("MX", 30_000_000)}
AMOUNTS = ("NOTIONAL", "QUANTITY", "AMOUNT")


def business_days(last: dt.date, n: int) -> list[str]:
    out, d = [], last
    while len(out) < n:
        if d.weekday() < 5:
            out.append(d.isoformat())
        d -= dt.timedelta(days=1)
    return out[::-1]


def templates(system: str, as_of: str) -> list[dict]:
    """The pack's sample trades of a system, in the common schema (convert.py over data_trades.py's products)."""
    import convert
    import data_trades
    rich = data_trades.build()
    return [convert.convert(d, as_of) for d in rich.values() if convert.system_of(d) == system]


def _scale(v, f: float):
    if isinstance(v, dict):
        return {k: (round(x * f, 2) if k in AMOUNTS and isinstance(x, (int, float)) and not isinstance(x, bool) else _scale(x, f)) for k, x in v.items()}
    if isinstance(v, list):
        return [_scale(x, f) for x in v]
    return v


def clone(t: dict, i: int, system: str) -> dict:
    """The i-th clone of a template: its own TRADE_ID (above the samples' numbers), flow and event numbers, amounts scaled."""
    prefix, first = CLONE_FROM[system]
    native = first + i
    rng = random.Random(native)
    d = _scale(copy.deepcopy(t), round(rng.uniform(0.2, 5.0), 3))
    d["TRADE_ID"] = f"{prefix}-{native}"
    d["TRANSACTION_ID"] = f"{native}001"
    base = native * 1000
    n = 0
    for leg in d["LEGS"]:
        for f in leg["CASH_FLOWS"]:
            n += 1
            f["FLOW_ID"] = base + n
    for k, e in enumerate(d["EVENTS"], start=1):
        e["EVENT_ID"] = base + k
    return S.text_ids(d)


def book(system: str, count: int, as_of: str) -> list[dict]:
    if system == "ENDUR":
        return list(Endur(as_of=dt.date.fromisoformat(as_of)).deals(count))
    temps = templates(system, as_of)
    out = temps[:count]
    i = 0
    while len(out) < count:
        out.append(clone(temps[i % len(temps)], i, system))
        i += 1
    return out


def write(system: str, trades: list[dict], days: list[str], out: Path) -> list[Path]:
    folder = out / system.lower()
    folder.mkdir(parents=True, exist_ok=True)
    files, current = [], trades
    for i, day in enumerate(days):
        if i:
            current = [A.amend(t, day) for t in current]
        f = folder / f"trades-{day}.jsonl.gz"
        with gzip.open(f, "wt", encoding="utf-8") as fh:
            for t in current:
                problems = S.problems(t)
                if problems:
                    raise SystemExit(f"{system} trade does not fit the common schema: {problems[:3]}")
                fh.write(json.dumps(t, separators=(",", ":")) + "\n")
        files.append(f)
    return files


def to_lake(system: str, files: list[Path], days: list[str], lake: Path, engine: str) -> int:
    sys.path.insert(0, str(ROOT / "tools" / "lake"))
    sys.path.insert(0, str(ROOT / "tools"))
    import build as LB
    worst = 0
    for f, day in zip(files, days):
        args = LB.build_parser().parse_args(["--from", str(f), "--pack", str(ROOT / "config" / "packs" / "trading"), "--kind", "trade",
                                             "--connector", CONNECTOR[system], "--key", "TRADE_ID", "--as-of", day, "--partition-by", "PRODUCT_TYPE",
                                             "--lake", str(lake), "--engine", engine, "-q"])
        code, summary = LB.run(args, say=lambda *a, **k: None)
        print(f"  {system} {day}: {summary.get('status')} {summary.get('rows', summary.get('documents', ''))} -> {summary.get('table')}"
              + (f" ({summary.get('error')})" if summary.get("error") else ""))
        worst = max(worst, code)
    return worst


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__.split("\n\n", 1)[1])
    p.add_argument("--trades", type=int, default=1000, help="trades per system (default 1,000)")
    p.add_argument("--days", type=int, default=7, help="business days, the last being --as-of (default 7)")
    p.add_argument("--as-of", default=R.AS_OF.isoformat(), help=f"the last business day (default {R.AS_OF})")
    p.add_argument("--systems", default="ENDUR,CALYPSO,MUREX", help="which systems (default all three)")
    p.add_argument("--out", default=str(ROOT / "data" / "build" / "trades"), help="where the JSON Lines files go (default data/build/trades)")
    p.add_argument("--lake", help="also build the Delta tables under this lake root (the connectors' root, data/delta)")
    p.add_argument("--engine", default="auto", choices=("auto", "drishti", "deltalake"), help="the lake writer (default auto)")
    a = p.parse_args(argv)
    systems = [s.strip().upper() for s in a.systems.split(",") if s.strip()]
    if any(s not in S.SYSTEMS for s in systems):
        p.error(f"--systems is some of {', '.join(S.SYSTEMS)}")
    if a.trades * len(systems) > 30_000:
        p.error("at most 30,000 trades in all on a laptop (10,000 is the local test size)")
    days = business_days(dt.date.fromisoformat(a.as_of), a.days)
    code = 0
    for system in systems:
        trades = book(system, a.trades, days[0])
        files = write(system, trades, days, Path(a.out))
        print(f"{system}: {len(trades)} trades x {len(days)} days -> {files[0].parent}")
        if a.lake:
            code = max(code, to_lake(system, files, days, Path(a.lake), a.engine))
    return code


if __name__ == "__main__":
    sys.exit(main())
