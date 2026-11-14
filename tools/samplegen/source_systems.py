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

"""The mocked native trades of the source systems WSS, SwapOne and ICI, as committed test data (data/testdata/trades/<system>/).

    python3 tools/samplegen/source_systems.py --out DIR [--days 60] [--count N]      write the three systems' days under DIR/trades/

One folder a system, one file a business date named by the table it feeds (trades_shared.<YYYY-MM-DD>.jsonl.gz: the first day's the
snapshot, each later day's the changes), JSON Lines in the system's own native field style (src_wss.py, src_swapone.py, src_ici.py). The
three land in ONE shared table, trading.trades_shared, split by SOURCE_SYSTEM: the demo layout of a shared table (Endur, Calypso and
Murex keep a table each). tools/testdata/make_testdata.py calls ``datasets`` so the manifest lists them. No position, no scenario P&L and
no valuation is made for these trades: the VaR is unchanged."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(ROOT / "tools" / "testdata"))

from samplegen.src_ici import Ici  # noqa: E402
from samplegen.src_swapone import SwapOne  # noqa: E402
from samplegen.src_wss import Wss  # noqa: E402

SYSTEMS = (Wss, SwapOne, Ici)
SHARED_TABLE = "trades_shared"


def datasets(out: Path, dates: list[str], count: int | None = None) -> list[dict]:
    """Writes every system's days under ``out/trades/<system>/`` and returns the manifest entries (tools/testdata/testdata.yaml)."""
    import history as H
    days = H.Days(dates)
    entries = []
    for cls in SYSTEMS:
        book = cls(dates, count)
        folder = f"trades/{cls.SYSTEM.lower()}"
        hist = H.DatasetHistory(out / folder, cls.KEY, days, SHARED_TABLE).write(book.states())
        entries.append({"folder": folder, "pack": "trading", "kind": "trade", "connector": f"trade-{cls.SYSTEM.lower()}", "key": cls.KEY,
                        "table": SHARED_TABLE, "native": True, "partition-by": ["SOURCE_SYSTEM"], "mode": "overwrite-partitions", **hist})
    return entries


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--out", required=True, help="the test data folder (data/testdata); trades/<system>/ is written under it")
    p.add_argument("--days", type=int, default=60)
    p.add_argument("--count", type=int, help="trades a system (default a few hundred, by system)")
    a = p.parse_args(argv)
    import make_testdata as M
    dates = M.business_days(a.days)
    for e in datasets(Path(a.out), dates, a.count):
        n = e["days"][-1]["total"]
        print(f"  {e['folder']}: {n:,} trades on {dates[-1]}, {len(e['days'])} days")
    return 0


if __name__ == "__main__":
    sys.exit(main())
