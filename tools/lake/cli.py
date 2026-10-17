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

"""`drishti.py lake build|scan|status|demo|maintain`: build and look after Delta lakes from the terminal or a scheduler.

    build      stream a JSON array, JSON Lines or .gz export into <lake>/<domain>/<kind> (tools/lake/build.py)
    scan       the fields of an export with their values, and the suggested --key, --date and --partition-by (tools/lake/scan.py)
    status     who holds the lake's lock, and the last runs (from <lake>/_drishti-lake)
    demo       the demo lakes of the seven packs from the committed test data, data/testdata (tools/lake/demo.py)
    maintain   retention, compaction, checkpoints and vacuum, now or on schedules; --history (tools/lake/maintain.py; options passed through)
"""
from __future__ import annotations

import json
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent


def _mod(name: str):
    sys.path.insert(0, str(HERE))
    return __import__(name)


def cmd_build(a, extra) -> int:
    code, _ = _mod("build").run(a)
    return code


def cmd_scan(a, extra) -> int:
    return _mod("scan").run(a)


def cmd_status(a, extra) -> int:
    state = pathlib.Path(a.lake) / "_drishti-lake"
    if not state.is_dir() and (pathlib.Path(a.lake) / ".drishti-lake").is_dir():
        state = pathlib.Path(a.lake) / ".drishti-lake"                 # an earlier build's name, until the next run renames it
    lock = state / "lock"
    holder = lock.read_text(encoding="utf-8").strip() if lock.is_file() else ""
    held = False
    if lock.is_file():
        try:
            with _mod("build").LakeLock(pathlib.Path(a.lake)):
                pass
        except _mod("build").Locked:
            held = True
    log = (state / "runs.log").read_text(encoding="utf-8").splitlines() if (state / "runs.log").is_file() else []
    last = json.loads((state / "last-run.json").read_text(encoding="utf-8")) if (state / "last-run.json").is_file() else None
    tabs = _mod("laketables").tables(pathlib.Path(a.lake))
    if a.json:
        print(json.dumps({"locked": held, "holder": holder or None, "runs": log[-a.runs:], "last": last, "tables": tabs}, indent=2))
        return 0
    print(f"tables ({len(tabs)}):")
    for line in _mod("laketables").table_text(tabs).splitlines():
        print("  " + line)
    print(f"lock: {'held by ' + holder if held else 'free'}")
    print(f"runs ({len(log)} logged, last {min(a.runs, len(log))}):")
    for line in log[-a.runs:]:
        print("  " + line)
    if last:
        print(f"last run: {last.get('status')} (exit {last.get('exitCode')}), summary {state / 'last-run.json'}")
    return 0


def cmd_demo(a, extra) -> int:
    return _mod("demo").run(a)


def cmd_maintain(a, extra) -> int:
    return _mod("maintain").main(extra)


def register(groups, add, srv) -> None:
    """Adds the `lake` group to drishti.py's parser (`groups` its subparsers, `add` its parser factory)."""
    import argparse
    g = groups.add_parser("lake", help="Delta lakes: build one from a large JSON export (memory flat, safe to re-run), scan an export, status, maintain",
                          description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = g.add_subparsers(dest="cmd", metavar="COMMAND", required=True)
    b = add(sub, "build", cmd_build, "lake build: stream a JSON array / JSON Lines / .gz export into a Delta table, partitioned, with promoted columns",
            [], "examples:\n  drishti.py lake build --from exports/book.json --kind deal --key TRADE_ID --date TRADE_DATE --partition-by PRODUCT_TYPE "
                "--lake data/delta --domain trading\n  drishti.py lake build --from drop/ --pack config/packs/my-book --lake data/delta --as-of 2026-10-01 "
                "--signal http://localhost:18480\nexit: 0 ok, 2 written with rejects (or the signal failed), 1 failure, 3 another run holds the lock. "
                "Guide: docs/guides/TOOLS.md")
    _mod("build").add_arguments(b)
    s = add(sub, "scan", cmd_scan, "lake scan: an export's fields (presence, types, frequent values) and the suggested key, date and classifiers",
            [], "example:\n  drishti.py lake scan exports/book.json --limit 5000")
    _mod("scan").add_arguments(s)
    st = add(sub, "status", cmd_status, "lake status: the lake's tables (rows, files, size, partitions, business dates, last build), its lock and last runs", [],
             "example:\n  drishti.py lake status --lake data/delta")
    st.add_argument("--lake", required=True, metavar="DIR")
    st.add_argument("--runs", type=int, default=10, help="log lines shown (default 10)")
    st.add_argument("--json", action="store_true")
    dm = add(sub, "demo", cmd_demo, "lake demo: build the demo lakes of the seven packs from data/testdata (seven business days), point the "
             "connector files at them, say what to start next", [],
             "examples:\n  drishti.py lake demo\n  drishti.py lake demo --lake /tmp/demo-lake\n"
             "  drishti.py lake demo --with-real ~/Downloads/endur.json,~/Downloads/ComSwap.json      (LOCAL ONLY: never into data/testdata)\n"
             "Guide: data/testdata/README.md")
    _mod("demo").add_arguments(dm)
    add(sub, "maintain", cmd_maintain, "lake maintain: retention, compaction, checkpoint and vacuum (tools/lake/maintain.py; its options are passed through)",
        [], "examples:\n  drishti.py lake maintain --config deploy/lake-maintenance.yaml --once --dry-run\n"
            "  drishti.py lake maintain --once --tables trading/trade_endur      (the file Admin -> Lakes writes: <data>/lakes/lake-maintenance.yaml)\n"
            "  drishti.py lake maintain --history\n"
            "  drishti.py lake maintain relayout --root data/delta --domain trading\n"
            "Only tables lake build built are maintained (--force-foreign overrides, with a warning). Guide: docs/admin/LAKE_MAINTENANCE.md")
