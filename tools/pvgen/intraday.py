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

"""Intraday updates of a stand-in pricing system: N small batch files of amended, new and cancelled trades, as JSONL.

    python tools/pvgen/intraday.py [--date 2026-10-27] [--batches 20] [--trades-per-batch 15] [--seed 7]
                                   [--mix amend=70,new=15,cancel=15] [--pv-systems ENDUR] [--pnl-systems ...] [--all-vectors]
                                   [--out DIR] [--gzip] [--lake DIR [--server URL] [--token-file F] [--every 30s]] [--dry-run]

After the morning run (the demo's factor model, as tools/pvgen/morning.py writes it), each batch re-sends ``--trades-per-batch`` trades:
amended (quantity and every PV moved by a factor of 0.8 to 1.2), new (a copy of a live trade in its book, a new id) or cancelled
(``TRADE_STATUS: CANCELLED``, the vector null), in the ``--mix`` proportions; each row marked ``RUN_KIND: INTRADAY``, the batch's own
``BATCH_ID`` (``I-<date>-001``) and a ``COMPUTED_AT`` a minute after the batch before (the first five minutes after the morning run).
A trade's update comes as scenario PVs when its system is in ``--pv-systems`` (default ENDUR), else as a P&L vector, the engine taking
either. ``--seed`` makes the same batches every run. Files: ``<out>/intraday-<date>/I-<date>-<NNN>.<kind>.jsonl`` (one kind a
file; ``.jsonl.gz`` with ``--gzip``). ``--lake`` appends each batch to the lake (``drishti.py lake build --mode append``), ``--server``
raises its data-load signal (the VaR engine applies it without a reload), ``--every 30s`` drips them. Guide:
docs/guides/VAR_GUIDE.md section 15.12."""
from __future__ import annotations

import argparse
import os
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common as C  # noqa: E402


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], epilog=__doc__.split("\n\n", 1)[1],
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--testdata", default=str(C.ROOT / "data" / "testdata"), help="the test data the morning run is made from")
    p.add_argument("--date", help="the business date (default: the test data's, 2026-09-30)")
    p.add_argument("--batches", type=int, default=20, help="how many batches (default 20)")
    p.add_argument("--trades-per-batch", type=int, default=15, help="trades a batch (default 15)")
    p.add_argument("--seed", type=int, default=7, help="the same batches for the same seed (default 7)")
    p.add_argument("--mix", default="amend=70,new=15,cancel=15", help="the shares of amended, new and cancelled trades")
    p.add_argument("--pv-systems", default="ENDUR", metavar="SYS,SYS", help="systems whose updates come as scenario PVs (default ENDUR)")
    p.add_argument("--pnl-systems", default="", metavar="SYS,SYS", help="systems whose updates come as P&L vectors even when listed above")
    p.add_argument("--all-vectors", action="store_true", help="every update a P&L vector")
    p.add_argument("--morning-pv-systems", default="ENDUR", metavar="SYS,SYS", help="the morning run's scenario-PV systems (default ENDUR)")
    p.add_argument("--systems", default="", metavar="SYS,SYS", help="update only these systems' trades (default: every system)")
    p.add_argument("--plan", default="", metavar="KIND,KIND", help="each batch's updates in this order (amend,new,cancel) instead of the mix")
    p.add_argument("--start", metavar="ISO", help="the first batch's COMPUTED_AT (default five minutes after the morning run)")
    p.add_argument("--out", default=str(Path(os.environ.get("DRISHTI_DATA_DIR", C.ROOT / "data")) / "pvgen"),
                   help="the folder the files go to (default <data folder>/pvgen); a subfolder intraday-<date>")
    p.add_argument("--gzip", action="store_true", help="write .jsonl.gz")
    p.add_argument("--lake", help="append each batch to this Delta root with lake build --mode append")
    p.add_argument("--server", help="with --lake: raise each batch's data-load signal on this server (http://host:port)")
    p.add_argument("--token-file", help="the API token for --server (default DRISHTI_TOKEN)")
    p.add_argument("--every", default="0", help="with --lake: wait this long between batches (30s, 2m; default 0)")
    p.add_argument("--first", type=int, default=1, help="the first batch's number (default 1): a second run on the same date goes on "
                   "from the last one (--first 21), so that each batch keeps a BATCH_ID of its own")
    C.grain_args(p)
    p.add_argument("--dry-run", action="store_true", help="say what would be written; write nothing")
    a = p.parse_args(argv)
    morning = C.Morning(Path(a.testdata), C.systems(a.morning_pv_systems), a.date, C.grain_systems(a))
    ups = C.Updates(morning, a.seed, C.mix_of(a.mix), C.M.pv_systems(a.pv_systems, a.pnl_systems, a.all_vectors), a.start,
                    only=C.systems(a.systems), first=a.first)
    plan = [x.strip() for x in a.plan.split(",") if x.strip()] or None
    folder = Path(a.out) / f"intraday-{morning.day}"
    wait = C.seconds(a.every)
    rc = 0
    for n in range(a.batches):
        batch_id, rows = ups.batch(a.trades_per_batch, plan)
        by_kind: dict[str, list[dict]] = {}
        for kind, row in rows:
            by_kind.setdefault(kind, []).append(row)
        cancels = sum(1 for _, r in rows if r.get("TRADE_STATUS") == "CANCELLED")
        news = sum(1 for _, r in rows if r.get("TRADE_STATUS") == "NEW")
        print(f"{batch_id}: {len(rows)} trades ({len(rows) - cancels - news} amended, {news} new, {cancels} cancelled), "
              + ", ".join(f"{len(v)} {k}" for k, v in by_kind.items()))
        if a.dry_run:
            continue
        for kind, krows in by_kind.items():
            f = C.write_jsonl(folder / f"{batch_id}.{kind}.jsonl", krows, a.gzip)
            if a.lake:
                rc = rc or C.lake_build(f, kind, a.lake, "append", a.server, C.token_of(a.token_file), print)
        if a.lake and wait and n < a.batches - 1:
            time.sleep(wait)
    if a.dry_run:
        print("dry run: nothing written")
    else:
        print(f"wrote {a.batches} batches to {folder}")
    return rc


if __name__ == "__main__":
    sys.exit(main())
