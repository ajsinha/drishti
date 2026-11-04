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

"""lake undo: remove one load's rows by its load id (docs/architecture/DATA_LOAD_RULES.md section 6.3).

Every row a load wrote carries its ``_load_id`` (``<run>.<file>``: a ``lake build``'s or the parallel loader's summary lists them under
``loads``; a row's ``_load_id`` column says it too). Undoing it removes exactly those rows, one commit per table and date, in every table
of the lake that has them (an archive's tables together). A load that replaced a date is undone by restoring the date as it was before it,
the rows later loads appended kept. Delta tables are undone by the parallel loader's ``LakeUndo``, Iceberg tables by the Iceberg
maintenance (``IcebergMaintenance --undo``), both in the server's exec jar.

The undo first says what it would do (a dry run), then asks for the load id again to confirm; ``--yes`` skips the question (scripts), and
without a terminal it is refused unless given.

    drishti.py lake undo 20261104T093000.123-4242.1 --lake data/delta
    drishti.py lake undo 20261104T093000.123-4242.1 --lake data/delta --dry-run --json"""
from __future__ import annotations

import argparse
import json
import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

UNDO = "com.ash.drishti.lakeload.LakeUndo"
MAINT = "com.ash.drishti.plugin.iceberg.IcebergMaintenance"


def plan(lake: str, load_id: str, dry_run: bool, jar=None, java=None, runner=None) -> list[dict]:
    """What undoing ``load_id`` in the lake does (``dry_run``) or did: a line per table and date ({table, date, removed, restored,
    replaced, format}). ``runner(cmd)`` gives a command's output (tests); by default it runs."""
    import stores
    data = pathlib.Path(lake) / "_drishti-lake"
    run = runner or (lambda cmd: subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace"))
    out: list[dict] = []
    cmd = stores.loader_command(data, jar, java, main=UNDO) + ["--lake", lake, "--load-id", load_id, "--json"] + (["--dry-run"] if dry_run else [])
    p = run(cmd)
    text = p.stdout.strip()
    if p.returncode != 0 or not text.startswith("["):
        raise RuntimeError("lake undo (Delta): " + (text.splitlines()[-1] if text else f"exit {p.returncode}"))
    out += [{**d, "format": "delta"} for d in json.loads(text)]
    import maintain_iceberg
    for uri in maintain_iceberg.tables(lake, ["*"]):
        root, domain, table = maintain_iceberg.split_uri(uri)
        cmd = stores.loader_command(data, jar, java, main=MAINT) + [root, "--domain", domain, "--kinds", table, "--engine", "native",
                                                                      "--undo", load_id, "--expire-hours", "-1", "--json"]
        if dry_run:
            cmd.append("--dry-run")
        p = run(cmd)
        lines = [x for x in p.stdout.splitlines() if x.startswith("{")]
        if not lines:
            raise RuntimeError(f"lake undo (Iceberg {domain}/{table}): exit {p.returncode}")
        r = json.loads(lines[-1])
        if r.get("error"):
            raise RuntimeError(r["error"])
        out += [{"table": f"{domain}/{table}", **u, "format": "iceberg"} for u in r.get("undo") or []]
    return out


def words(lines: list[dict], load_id: str, dry_run: bool) -> list[str]:
    if not lines:
        return [f"no row of load {load_id} in the lake"]
    return [f"{'would undo' if dry_run else 'undone'} {x['table']} {x['date']}: {x['removed']} rows of load {load_id} removed"
            + (f", {x['restored']} rows of the date before it restored" if x.get("replaced") else "") for x in lines]


def run(a, say=print, ask=input) -> int:
    try:
        before = plan(a.lake, a.load_id, True, a.jar, a.java)
    except (RuntimeError, SystemExit) as e:
        say(f"lake undo: {e}")
        return 1
    if a.dry_run or not before:
        say(json.dumps(before, indent=2) if a.json else "\n".join(words(before, a.load_id, True)))
        return 0
    if not a.yes:
        say("\n".join(words(before, a.load_id, True)))
        if not sys.stdin.isatty():
            say("lake undo: give --yes to undo without a terminal (the plan above is what it would do)")
            return 1
        if ask("type the load id to undo it: ").strip() != a.load_id:
            say("lake undo: not confirmed; nothing changed")
            return 1
    try:
        done = plan(a.lake, a.load_id, False, a.jar, a.java)
    except (RuntimeError, SystemExit) as e:
        say(f"lake undo: {e}")
        return 1
    say(json.dumps(done, indent=2) if a.json else "\n".join(words(done, a.load_id, False)))
    return 0


def add_arguments(p: argparse.ArgumentParser) -> argparse.ArgumentParser:
    p.add_argument("load_id", metavar="LOAD_ID", help="the load's id (a summary's loads, or a row's _load_id)")
    p.add_argument("--lake", required=True, metavar="DIR", help="the lake root (its Delta and Iceberg tables)")
    p.add_argument("--dry-run", action="store_true", help="say what would be undone, change nothing")
    p.add_argument("--yes", action="store_true", help="undo without asking for the load id again (scripts)")
    p.add_argument("--json", action="store_true")
    p.add_argument("--jar", help="the drishti-server exec jar (default: DRISHTI_JAR, then drishti-server/target)")
    p.add_argument("--java", help="the java binary")
    return p
