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

One part only, the rest of the lakes kept as they are::

    python tools/build_demo_lakes.py --endur-dir C:\\…\\endur --files-only        # only your ENDUR files, nothing rebuilt
    python tools/build_demo_lakes.py --source-dir C:\\…\\calypso --system calypso --files-only   # another system's files
    python tools/build_demo_lakes.py --only trades/endur --as-of 2026-10-16          # only the synthetic ENDUR table
    python tools/build_demo_lakes.py --only trades/calypso,trades/murex             # several datasets (folders of data/testdata)

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


HEARTBEAT_SECONDS = int(__import__("os").environ.get("DRISHTI_HEARTBEAT_SECONDS", "30"))


def lake_progress(lake: Path) -> str:
    """The lake's size on disk and the table folder written most recently: what a heartbeat line says."""
    total, newest, newest_t = 0, None, 0.0
    if lake.is_dir():
        for f in lake.rglob("*"):
            try:
                st = f.stat()
            except OSError:
                continue
            if f.is_file():
                total += st.st_size
                if st.st_mtime > newest_t:
                    newest_t, newest = st.st_mtime, f
    where = ""
    if newest is not None:
        rel = newest.relative_to(lake).parts
        where = f", writing {'/'.join(rel[:2])}" + (f" {rel[2]}" if len(rel) > 2 and rel[2].startswith("business_date=") else "")
    return f"{total / 1e6:,.0f} MB in the lake{where}"


def run(cmd: list[str], dry: bool, step: str = "", lake: Path | None = None) -> None:
    """Runs one command; while it runs, a heartbeat every HEARTBEAT_SECONDS says it is alive and how far the lake has grown."""
    print("  $ " + " ".join(f'"{c}"' if " " in c else c for c in cmd), flush=True)
    if dry:
        return
    import threading
    t0, done = time.time(), threading.Event()

    def beat():
        while not done.wait(HEARTBEAT_SECONDS):
            extra = f"; {lake_progress(lake)}" if lake else ""
            print(f"  … {step} still running: {time.time() - t0:,.0f} s{extra}", flush=True)
    threading.Thread(target=beat, daemon=True).start()
    try:
        subprocess.run(cmd, check=True, cwd=ROOT)
    finally:
        done.set()


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter,
                                 epilog=__doc__.split("\n\n", 1)[1])
    ap.add_argument("--endur-dir", type=Path, help="a folder of your ENDUR files, one per business date, named YYYY-MM-DD.jsonl")
    ap.add_argument("--source-dir", type=Path, help="the same for the system --system names (the same as --endur-dir with --system endur)")
    ap.add_argument("--system", default="endur", choices=("endur", "calypso", "murex"),
                    help="whose files --source-dir holds: they go into trading/trade_<system> through connector trade-<system> (default endur)")
    ap.add_argument("--files-only", action="store_true",
                    help="load only your files into their system's table; every other table of the lake stays as it is (no lake demo)")
    ap.add_argument("--only", help="rebuild only these datasets of the test data (lake demo --only: folders such as trades/endur, "
                                   "trades/calypso, core/counterparty, or a pack name); the other tables stay as they are")
    ap.add_argument("--as-of", help="the last business date of the lakes (default: your newest ENDUR file's date, else today)")
    ap.add_argument("--lake", type=Path, default=ROOT / "data" / "delta", help="the Delta root the server reads (default data/delta)")
    ap.add_argument("--connectors-dir", type=Path, help="the connector files' folder (default: as lake demo, config/connectors)")
    ap.add_argument("--keep-synthetic-endur", "--keep-synthetic", dest="keep_synthetic_endur", action="store_true",
                    help="add your files beside the system's synthetic trades instead of replacing them")
    ap.add_argument("--dry-run", action="store_true", help="say every step, change nothing")
    a = ap.parse_args(argv)

    py = sys.executable
    files: list[tuple[str, Path]] = []
    if a.endur_dir and a.source_dir:
        ap.error("give --endur-dir or --source-dir, not both")
    src = a.endur_dir or a.source_dir
    system = "endur" if a.endur_dir else a.system
    if a.files_only and not src:
        ap.error("--files-only needs --endur-dir or --source-dir")
    if a.files_only and a.only:
        ap.error("--files-only loads only your files; leave out --only")
    if src:
        folder = src.expanduser().resolve()
        if not folder.is_dir():
            ap.error(f"--endur-dir {folder} is not a folder")
        files = endur_files(folder)
        if not files:
            ap.error(f"no file named YYYY-MM-DD.jsonl (or .json, .gz) in {folder}")
    as_of = a.as_of or (files[-1][0] if files else "today")
    lake = a.lake.expanduser().resolve()
    t0 = time.time()

    if a.files_only:
        print(f"1/3 the demo lakes: kept as they are (--files-only)")
    else:
        what = f"only {a.only}" if a.only else "every dataset"
        print(f"1/3 the demo lakes: {what}, every business day, ending {as_of} → {lake}")
        # --no-signal: lake demo would otherwise tell a running server of every table and date (66 tables x 60 days = 3,960 signals),
        # each making it read the data again: a running server ran out of memory (QA 2026-10-20, QA3A-01/05). A server reads the new
        # tables at its next start, or within its refresh interval.
        cmd = [py, str(DRISHTI), "lake", "demo", "--as-of", as_of, "--lake", str(lake), "--no-signal"]
        if a.only:
            cmd += ["--only", a.only]
        if a.connectors_dir:
            cmd += ["--connectors-dir", str(a.connectors_dir.expanduser().resolve())]
        run(cmd, a.dry_run, "1/3 lake demo", lake)

    if files:
        table = lake / "trading" / f"trade_{system}"
        print(f"2/3 your {system.upper()} files ({len(files)}: {files[0][0]} … {files[-1][0]}) into {table}")
        if not a.keep_synthetic_endur:
            print(f"  the synthetic {system.upper()} table is removed first: {table}")
            if not a.dry_run and table.exists():
                shutil.rmtree(table)
        for day, path in files:
            run([py, str(DRISHTI), "lake", "build", "--from", str(path), "--as-of", day,
                 "--pack", str(ROOT / "config" / "packs" / "trading"), "--connector", f"trade-{system}", "--key", "TRADE_ID",
                 "--partition-by", "PRODUCT_TYPE", "--mode", "overwrite-dates", "--lake", str(lake)], a.dry_run,
                f"2/3 {path.name} ({day})", lake / "trading" / f"trade_{system}")
    else:
        print("2/3 no --endur-dir or --source-dir: the synthetic trades stay")

    print(f"3/3 done in {time.time() - t0:.0f} s{' (dry run: nothing changed)' if a.dry_run else ''}.")
    print("  Next: start the server (Drishti server (demo lakes), or tools\\windows\\start-server.ps1 -DemoLakes) and the console;")
    print("  if a server was running, restart it so it reads the new tables. Then try:  TRD 1000279 60  ·  TRD 1000279 60 DIFF  ·  EQ EQ-CSCA 3M  ·  CPTY CP-NORTHPLAINS 30")
    return 0


if __name__ == "__main__":
    sys.exit(main())
