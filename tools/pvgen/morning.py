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

"""The morning run of a stand-in pricing system: a full business day's scenario PVs and P&L vectors, and the scenario FX, as JSONL.

    python tools/pvgen/morning.py [--date 2026-10-27] [--pv-systems ENDUR] [--pnl-systems CALYPSO,MUREX] [--all-vectors]
                                  [--out DIR] [--gzip] [--lake DIR [--server URL] [--token-file F]] [--dry-run]

Every live trade of the test data (data/testdata: Endur, Calypso and Murex) is valued by the demo's factor model
(tools/packgen/var/make_var_data.py) over the 509 scenario days: the trades of ``--pv-systems`` (default ENDUR) as scenario PVs in their
own currency with their base PV (``scenario-pv``), the others as P&L vectors in USD (``pnl-vector``), and every currency's scenario FX
(``fx-scenario``). ``--all-vectors`` sends every trade as a P&L vector, exactly as before 2.9.4. The rows are the contract a pricing
system sends: one JSON object per line, exactly the contract's fields, ``RUN_KIND: FULL``; ``--date`` moves them to another business date
(the demo lake's, today's). Files: ``<out>/scenario-pv.jsonl``, ``pnl-vector.jsonl``, ``fx-scenario.jsonl`` (``.jsonl.gz`` with
``--gzip``). ``--lake`` writes them into the lake (``drishti.py lake build``, replacing the date), ``--server`` then raises the data-load
signal. Guide: docs/guides/VAR_GUIDE.md section 15.12."""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common as C  # noqa: E402


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], epilog=__doc__.split("\n\n", 1)[1],
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--testdata", default=str(C.ROOT / "data" / "testdata"), help="the test data the trades are read from")
    p.add_argument("--date", help="the business date the rows carry (default: the test data's, 2026-09-30)")
    p.add_argument("--pv-systems", default="ENDUR", metavar="SYS,SYS", help="systems sent as scenario PVs (default ENDUR)")
    p.add_argument("--pnl-systems", default="", metavar="SYS,SYS", help="systems sent as P&L vectors even when listed above")
    p.add_argument("--all-vectors", action="store_true", help="every trade a P&L vector (as before 2.9.4)")
    p.add_argument("--out", default=str(Path(os.environ.get("DRISHTI_DATA_DIR", C.ROOT / "data")) / "pvgen"),
                   help="the folder the files go to (default <data folder>/pvgen); a subfolder morning-<date>")
    p.add_argument("--gzip", action="store_true", help="write .jsonl.gz")
    p.add_argument("--lake", help="also write them into this Delta root with lake build (the date replaced)")
    p.add_argument("--server", help="with --lake: raise the data-load signal on this server (http://host:port)")
    p.add_argument("--token-file", help="the API token for --server (default DRISHTI_TOKEN)")
    C.grain_args(p)
    p.add_argument("--dry-run", action="store_true", help="say what would be written; write nothing")
    a = p.parse_args(argv)
    pv = C.M.pv_systems(a.pv_systems, a.pnl_systems, a.all_vectors)
    m = C.Morning(Path(a.testdata), pv, a.date, C.grain_systems(a))
    folder = Path(a.out) / f"morning-{m.day}"
    grain = ", per position: " + ", ".join(m.position_systems) if m.position_systems else ""
    print(f"morning run of {m.day}: {len(m.rows['scenario-pv']):,} records as scenario PVs ({', '.join(pv) or 'none'}), "
          f"{len(m.rows['pnl-vector']):,} as P&L vectors{grain}, scenario FX for {len(m.rows['fx-scenario'])} currencies; computed at {m.at}")
    if a.dry_run:
        print(f"dry run: nothing written (would write {folder})")
        return 0
    rc = 0
    for kind in ("fx-scenario", "scenario-pv", "pnl-vector"):
        if not m.rows[kind]:
            continue
        f = C.write_jsonl(folder / f"{kind}.jsonl", m.rows[kind], a.gzip)
        print(f"  {f}  {len(m.rows[kind]):,} rows")
        if a.lake:
            rc = rc or C.lake_build(f, kind, a.lake, "overwrite-dates", a.server if kind == "pnl-vector" or not m.rows["pnl-vector"] else None,
                                    C.token_of(a.token_file), print)
    return rc


if __name__ == "__main__":
    sys.exit(main())
