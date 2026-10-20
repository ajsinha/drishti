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
"""Every demo lake from start to finish, in one command (Windows, Linux, macOS).

1. ``drishti.py lake demo``: every dataset of the seven packs, every business day of the committed test data (data/testdata),
   moved to end on ``--as-of`` (default: the date of your newest ENDUR file when ``--endur-dir`` is given, else today's business
   date), and the connector files pointed at the lake.
2. With ``--endur-dir``: the synthetic ENDUR trades are replaced by your files, one ``YYYY-MM-DD.jsonl`` (or ``.json``, ``.gz``)
   per business date, each loaded with ``drishti.py lake build`` exactly as the Windows QA guide's step 3.2 does.
3. A summary: the tables, the dates, your files and what to start next.

Examples::

    python tools/build_demo_lakes.py
    python tools/build_demo_lakes.py --endur-dir C:\\Users\\me\\drishti-private\\endur
    python tools/build_demo_lakes.py --endur-dir ~/private/endur --as-of 2026-10-16 --dry-run

Your ENDUR files are read where they are and never copied into the project.
"""
from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DRISHTI = ROOT / "tools" / "drishti.py"
DATED = re.compile(r"^(\d{4}-\d{2}-\d{2})\.(jsonl|json)(\.gz)?$")


def endur_files(folder: Path) -> list[tuple[str, Path]]:
    """Your ENDUR files, oldest first: (business date, path) for every file named by its date."""
    out = []
    for p in sorted(folder.iterdir()):
        m = DATED.match(p.name)
        if p.is_file() and m:
            out.append((m.group(1), p))
    return out


def run(cmd: list[str], dry: bool) -> None:
    print("  $ " + " ".join(f'"{c}"' if " " in c else c for c in cmd), flush=True)
    if not dry:
        subprocess.run(cmd, check=True, cwd=ROOT)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter,
                                 epilog=__doc__.split("\n\n", 1)[1])
    ap.add_argument("--endur-dir", type=Path, help="a folder of your ENDUR files, one per business date, named YYYY-MM-DD.jsonl")
    ap.add_argument("--as-of", help="the last business date of the lakes (default: your newest ENDUR file's date, else today)")
    ap.add_argument("--lake", type=Path, default=ROOT / "data" / "delta", help="the Delta root the server reads (default data/delta)")
    ap.add_argument("--connectors-dir", type=Path, help="the connector files' folder (default: as lake demo, config/connectors)")
    ap.add_argument("--keep-synthetic-endur", action="store_true", help="add your files beside the synthetic ENDUR trades instead of replacing them")
    ap.add_argument("--dry-run", action="store_true", help="say every step, change nothing")
    a = ap.parse_args(argv)

    py = sys.executable
    files: list[tuple[str, Path]] = []
    if a.endur_dir:
        folder = a.endur_dir.expanduser().resolve()
        if not folder.is_dir():
            ap.error(f"--endur-dir {folder} is not a folder")
        files = endur_files(folder)
        if not files:
            ap.error(f"no file named YYYY-MM-DD.jsonl (or .json, .gz) in {folder}")
    as_of = a.as_of or (files[-1][0] if files else "today")
    lake = a.lake.expanduser().resolve()
    t0 = time.time()

    print(f"1/3 the demo lakes: every dataset, every business day, ending {as_of} → {lake}")
    cmd = [py, str(DRISHTI), "lake", "demo", "--as-of", as_of, "--lake", str(lake)]
    if a.connectors_dir:
        cmd += ["--connectors-dir", str(a.connectors_dir.expanduser().resolve())]
    run(cmd, a.dry_run)

    if files:
        table = lake / "trading" / "trade_endur"
        print(f"2/3 your ENDUR files ({len(files)}: {files[0][0]} … {files[-1][0]}) into {table}")
        if not a.keep_synthetic_endur:
            print(f"  the synthetic ENDUR table is removed first: {table}")
            if not a.dry_run and table.exists():
                shutil.rmtree(table)
        for day, path in files:
            run([py, str(DRISHTI), "lake", "build", "--from", str(path), "--as-of", day,
                 "--pack", str(ROOT / "config" / "packs" / "trading"), "--connector", "trade-endur", "--key", "TRADE_ID",
                 "--partition-by", "PRODUCT_TYPE", "--mode", "overwrite-dates", "--lake", str(lake)], a.dry_run)
    else:
        print("2/3 no --endur-dir: the synthetic ENDUR trades stay")

    print(f"3/3 done in {time.time() - t0:.0f} s{' (dry run: nothing changed)' if a.dry_run else ''}.")
    print("  Next: start the server (Drishti server (demo lakes), or tools\\windows\\start-server.ps1 -DemoLakes) and the console;")
    print("  if a server was running, restart it so it reads the new tables. Then try:  TRD 1000279 60  ·  TRD 1000279 60 DIFF  ·  EQ EQ-CSCA 3M  ·  CPTY CP-NORTHPLAINS 30")
    return 0


if __name__ == "__main__":
    sys.exit(main())
