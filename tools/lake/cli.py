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

"""`drishti.py lake build|publish|scan|status|demo|maintain`: build and look after lakes from the terminal or a scheduler (Delta Lake,
the default; Apache Iceberg with `build --format iceberg`, and `maintain` for a lake whose `format: iceberg`).

    build      stream a JSON array, JSON Lines or .gz export into <lake>/<domain>/<kind> (tools/lake/build.py; --format delta|iceberg)
    scan       the fields of an export with their values, and the suggested --key and --partition-by (tools/lake/scan.py)
    publish    commit a table lake build wrote (a scratch lake) into a Delta table in S3, one commit (tools/lake/publish.py)
    status     whether the lake's lock is held (lake maintain, or a load), and the last runs (from <lake>/_drishti-lake)
    demo       the demo lakes of the seven packs from the committed test data, data/testdata (tools/lake/demo.py)
    maintain   retention, compaction, checkpoints and vacuum, now or on schedules; --history (tools/lake/maintain.py; options passed through)
    memory     the in-memory lake over REST: status, jobs, refill (tools/lake/memory_cli.py; docs/guides/DATA_LAKE_GUIDE.md)
    show       every lake of config/lakes/ and its tables, as the server reads them (tools/lake/lakeconfig.py; docs/guides/DATA_LAKE_GUIDE.md)
    check      connectors name known lakes; each lake file matches its tables on disk (format, partitions, promoted columns)
    migrate-config  lake files from connector files that still describe their lake; the connectors rewritten to lake: <name>
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


def cmd_publish(a, extra) -> int:
    code, _ = _mod("publish").run(a)
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


def cmd_undo(a, extra) -> int:
    return _mod("undo").run(a)


def cmd_relocate(a, extra) -> int:
    return _mod("relocate").run(a)


def cmd_maintain(a, extra) -> int:
    return _mod("maintain").main(extra)


def register(groups, add, srv, ctx: dict | None = None) -> None:
    """Adds the `lake` group to drishti.py's parser (`groups` its subparsers, `add` its parser factory)."""
    import argparse
    g = groups.add_parser("lake", help="Delta lakes: build one from a large JSON export (memory flat, safe to re-run), scan an export, status, maintain",
                          description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = g.add_subparsers(dest="cmd", metavar="COMMAND", required=True)
    b = add(sub, "build", cmd_build, "lake build: stream a JSON array / JSON Lines / .gz export into a Delta table, partitioned, with promoted columns",
            [], "examples:\n  drishti.py lake build --from exports/deal.2026-10-01.json --kind deal --key TRADE_ID --partition-by PRODUCT_TYPE --mode overwrite-dates "
                "--lake data/delta/demo --domain trading\n  drishti.py lake build --from drop/ --pack config/packs/my-book --lake data/delta/demo --as-of 2026-10-01 "
                "--signal http://localhost:18480\nexit: 0 ok, 2 written with rejects (or the signal failed), 1 failure, 3 another run holds the table's (or the lake's) lock. "
                "Guide: docs/guides/TOOLS.md")
    _mod("build").add_arguments(b)
    pb = add(sub, "publish", cmd_publish, "lake publish: commit the files of a table lake build wrote into a Delta table in S3 (one commit "
             "replacing the dates; S3 conditional writes)", [],
             "example:\n  drishti.py lake publish --from /opt/drishti/data/lake-build/run-1/trading/trade_endur "
             "--to s3://acme-drishti-prod-lake/lake/trading/trade_endur --dates 2026-10-20 --storage-option AWS_REGION=eu-west-2\n"
             "exit: 0 ok, 1 failure (nothing committed), 2 committed but the signal failed, 3 nothing to publish. Guide: docs/architecture/CLUSTER_DEPLOYMENT.md")
    _mod("publish").add_arguments(pb)
    s = add(sub, "scan", cmd_scan, "lake scan: an export's fields (presence, types, frequent values) and the suggested key, date and classifiers",
            [], "example:\n  drishti.py lake scan exports/book.json --limit 5000")
    _mod("scan").add_arguments(s)
    st = add(sub, "status", cmd_status, "lake status: the lake's tables (rows, files, size, partitions, business dates, last build), its lock and last runs", [],
             "example:\n  drishti.py lake status --lake data/delta/demo")
    st.add_argument("--lake", required=True, metavar="DIR")
    st.add_argument("--runs", type=int, default=10, help="log lines shown (default 10)")
    st.add_argument("--json", action="store_true")
    dm = add(sub, "demo", cmd_demo, "lake demo: build the demo lakes of the seven packs from data/testdata (sixty business days), point the "
             "connector files at them, say what to start next", [],
             "examples:\n  drishti.py lake demo\n  drishti.py lake demo --lake /tmp/demo-lake\n"
             "  drishti.py lake demo --with-real ~/Downloads/endur.json,~/Downloads/ComSwap.json      (LOCAL ONLY: never into data/testdata)\n"
             "Guide: data/testdata/README.md")
    _mod("demo").add_arguments(dm)
    u = add(sub, "undo", cmd_undo, "lake undo: remove one load's rows by its load id, in every table of the lake (a replace restored); asks "
            "to confirm", [], "examples:\n  drishti.py lake undo 20261104T093000.123-4242.1 --lake data/delta/demo --dry-run\n"
            "  drishti.py lake undo 20261104T093000.123-4242.1 --lake data/delta/demo --yes\nGuide: docs/guides/DATA_LAKE_GUIDE.md section 5.5")
    _mod("undo").add_arguments(u)
    rl = add(sub, "relocate", cmd_relocate, "lake relocate: move a lake built in the older layout (<data>/delta/<domain>) into its own folder "
             "<data>/delta/<lake> once: verified, moved back on any error, never a delete", [],
             "examples:\n  drishti.py lake relocate demo --dry-run\n  drishti.py lake relocate demo\n"
             "The server does it for the demo lake at start (drishti.lakes.relocate-on-start). Guide: docs/admin/OPERATIONS.md")
    _mod("relocate").add_arguments(rl)
    add(sub, "maintain", cmd_maintain, "lake maintain: retention, compaction, checkpoint and vacuum (tools/lake/maintain.py; its options are passed through)",
        [], "examples:\n  drishti.py lake maintain --config deploy/lake-maintenance.yaml --once --dry-run\n"
            "  drishti.py lake maintain --once --tables trading/trade_endur      (the file Admin -> Lakes writes: <data>/lakes/lake-maintenance.yaml)\n"
            "  drishti.py lake maintain --history\n"
            "  drishti.py lake maintain relayout --root data/delta/demo --domain trading\n"
            "Only tables lake build built are maintained (--force-foreign overrides, with a warning). Guide: docs/admin/OPERATIONS.md")
    _mod("lakeconfig").register(sub, add)                               # lake show|check|migrate-config (2.9.11.c, DATA_LAKE_GUIDE.md)
    if ctx:
        _mod("memory_cli").register(sub, add, srv, ctx)                # lake memory status|jobs|refill (2.9.11, docs/architecture/DATA_ARCHITECTURE.md)
