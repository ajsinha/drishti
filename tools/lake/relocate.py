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

"""lake relocate: move a lake built in the older layout (its tables directly in <data>/delta/<domain>/<table>) into its own folder,
<data>/delta/<lake>/, once (docs/guides/OPERATIONS_GUIDE.md, "The lakes' folders").

The server does this for the demo lake at start (drishti.lakes.relocate-on-start); this runs the same Java (``LakeRelocate``, in the server's
exec jar) by hand. Only the lake's known folders move, by a rename on the same file system; every table is opened and measured before and
after (version, data files, rows); any difference or error moves everything back; nothing is ever deleted. It refuses, saying why, when the
target exists, when anything at the old place belongs to no lake, across file systems, for an Iceberg table, or while a build or load holds
the lake. Stop the server first: its connectors hold the tables open.

    drishti.py lake relocate demo --dry-run
    drishti.py lake relocate demo"""
from __future__ import annotations

import argparse
import json
import os
import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))

MAIN = "com.ash.drishti.lakeload.LakeRelocate"


def plan(lake: str, dry_run: bool, jar=None, java=None, lakes_dir=None, runner=None) -> tuple[int, dict]:
    """(exit code, the outcome {state, message, lake, from, to, folders, tables}). ``runner(cmd, env)`` gives (code, output) (tests)."""
    import stores
    data = pathlib.Path(os.environ.get("DRISHTI_DATA_DIR") or (ROOT / "data"))
    cmd = stores.loader_command(data, jar, java, main=MAIN) + ["--lake", lake] + (["--dry-run"] if dry_run else [])
    env = dict(os.environ)
    env["DRISHTI_LAKES_DIR"] = str(pathlib.Path(lakes_dir or os.environ.get("DRISHTI_LAKES_DIR") or (ROOT / "config" / "lakes")).resolve())

    def default(c, e):
        p = subprocess.run(c, capture_output=True, text=True, encoding="utf-8", errors="replace", env=e, cwd=str(ROOT))
        return p.returncode, p.stdout
    code, out = (runner or default)(cmd, env)
    text = out[out.find("{"):] if "{" in out else ""
    try:
        return code, json.loads(text)
    except ValueError:
        return 1, {"state": "failed", "message": (out.strip().splitlines() or [f"exit {code}"])[-1]}


def words(o: dict) -> str:
    state = o.get("state", "")
    tables = o.get("tables") or {}
    head = {"moved": "moved", "would-move": "would move", "nothing": "nothing to move", "done-before": "done before",
            "refused": "REFUSED", "rolled-back": "ROLLED BACK", "failed": "FAILED"}.get(state, state)
    lines = [f"lake relocate {o.get('lake', '')}: {head}: {o.get('message', '')}"]
    if tables and state in ("moved", "would-move"):
        lines += [f"  {t}: version {m.get('version')}, {m.get('files')} files, {m.get('rows')} rows" for t, m in sorted(tables.items())]
    return "\n".join(lines)


def run(a, say=print) -> int:
    try:
        code, o = plan(a.lake_name, a.dry_run, a.jar, a.java, a.lakes_dir)
    except SystemExit as e:
        say(f"lake relocate: {e}")
        return 1
    say(json.dumps(o, indent=2) if a.json else words(o))
    return code


def add_arguments(p: argparse.ArgumentParser) -> argparse.ArgumentParser:
    p.add_argument("lake_name", metavar="LAKE", help="the lake to move into <data>/delta/<lake> (demo)")
    p.add_argument("--dry-run", action="store_true", help="say what would move and measure the tables; move nothing")
    p.add_argument("--lakes-dir", help="the lake files' folder (default DRISHTI_LAKES_DIR, else config/lakes)")
    p.add_argument("--json", action="store_true")
    p.add_argument("--jar", help="the drishti-server exec jar (default: DRISHTI_JAR, then drishti-server/target)")
    p.add_argument("--java", help="the java binary")
    return p
