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

"""Loads dated loader rows (JSON Lines) into the Delta Lake the server's Delta connectors read: the loading half of the sample
history that tools/samplegen/lake.py, tools/packgen/banking/make_data.py --lake and the pack generators' --lake write as JSONL.

    uv run --with deltalake --with pyarrow --with pyyaml python tools/samplegen/load_rows.py \\
        --rows data/build/lake-rows/banking-history.jsonl --restated data/build/lake-rows/banking-restated.jsonl --root data/delta

A row (one JSON object a line, UTF-8; .jsonl or .jsonl.gz, or an archive of them: .zip, .tar.gz, .tgz, .tar; docs/guides/DATA_GUIDE.md ›
Data formats: JSON Lines is the standard, section 2, and section 7 for archives):

    {"domain": "trading", "kind": "trade", "table": "trade_endur", "id": "1000015", "date": "2026-09-30", "doc": "<the document as JSON text>"}

`table` is the table's folder under the domain (default: the kind). Every table named in --rows is written afresh: its rows, every
business date, in the layout its pack declares (samplegen/layout.py: promoted columns, sorted, files of file-rows). Each --restated
file is a second commit on top: its rows replace the rows of the same id on their date, so time travel ("as known at") shows the
correction. A restated row's table must be in --rows of the same run. Build-time tooling only: the console never reads the lake.

An archive's JSON Lines members are read in name order. Its folders, one level deep, are datasets: each folder is a table, and every
row in it must name that table (a row naming another is refused). Every file and archive is read and checked before anything is
written; then each table is written, and a line per table says its rows, its files and its Delta version.
"""
from __future__ import annotations

import argparse
import gzip
import json
import pathlib
import sys
from datetime import date

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent / "lake"))
import archives  # noqa: E402  (tools/lake/archives.py: the archive rule every loader shares)
from samplegen.layout import Layout, layouts_for_domain, write_days, write_partition  # noqa: E402


def read(path: pathlib.Path):
    """The rows of one JSONL file (plain or gzip), in order; of an archive, every member's rows (archives.py). Each row carries the
    file it came from under ``"_source"`` (load takes it off)."""
    path = pathlib.Path(path)
    if archives.is_archive(path.name):
        try:
            plan = archives.plan(path)
            for note in plan.notes:
                print(f"{path.name}: {note}")
            for ds, m, n, line in archives.read_lines(plan):
                if not line.strip():
                    continue
                try:
                    r = json.loads(line)
                except ValueError as e:
                    raise SystemExit(f"{m.label}:{n}: not a JSON object ({e})")
                if plan.folders and (r.get("table") or r.get("kind")) != ds:
                    raise SystemExit(f"{m.label}:{n}: a row of table {r.get('table') or r.get('kind')!r} in folder {ds}/ (each folder is "
                                     "a table, and its rows name it); nothing was written")
                r["_source"] = m.label
                yield r
        except archives.ArchiveError as e:
            raise SystemExit(str(e)) from None
        return
    opener = gzip.open if str(path).endswith(".gz") else open
    with opener(path, "rt", encoding="utf-8") as f:
        for n, line in enumerate(f, 1):
            if line.strip():
                try:
                    r = json.loads(line)
                except ValueError as e:
                    raise SystemExit(f"{path}:{n}: not a JSON object ({e})")
                r["_source"] = path.name
                yield r


def _layout(domain: str, table: str, kind: str, layouts) -> Layout | None:
    if isinstance(layouts, dict):
        return layouts.get(table) or layouts.get(kind)
    found = layouts(domain)
    return found.get(table) or found.get(kind)


def load(rows: list[pathlib.Path], root: pathlib.Path, restated: list[pathlib.Path] = (), layouts=None, say=print) -> int:
    """Writes every table of `rows` under root/<domain>/<table>, then applies `restated`. layouts: {table or kind: Layout} for
    every table, or None for each domain's packs (layouts_for_domain). Returns the number of history rows written."""
    cache: dict[str, dict] = {}

    def lays(domain):
        if domain not in cache:
            cache[domain] = layouts_for_domain(domain)
        return cache[domain]

    tables: dict[tuple[str, str], dict] = {}
    for p in rows:
        for r in read(pathlib.Path(p)):
            key = (r["domain"], r.get("table") or r["kind"])
            t = tables.setdefault(key, {"kind": r["kind"], "days": {}, "files": set()})
            t["files"].add(r.pop("_source", str(p)))
            t["days"].setdefault(date.fromisoformat(r["date"]), []).append((str(r["id"]), r["doc"]))
    fixes: dict[tuple[str, str], dict] = {}
    for p in restated:
        for r in read(pathlib.Path(p)):
            key = (r["domain"], r.get("table") or r["kind"])
            if key not in tables:
                raise SystemExit(f"{p}: restates {key[0]}/{key[1]}, which no --rows file of this run holds")
            fixes.setdefault(key, {}).setdefault(date.fromisoformat(r["date"]), []).append((str(r["id"]), r["doc"]))
    total = 0
    for (domain, table), t in tables.items():
        lay = _layout(domain, table, t["kind"], layouts if layouts is not None else lays)
        by_day = {d: [(i, body, json.loads(body) if lay else None) for i, body in rs] for d, rs in t["days"].items()}
        path = root / domain / table
        write_days(str(path), by_day, lay, "overwrite")
        for d, rs in sorted(fixes.get((domain, table), {}).items()):
            ids = {i for i, _ in rs}
            keep = [r for r in by_day.get(d, []) if r[0] not in ids] + [(i, body, json.loads(body) if lay else None) for i, body in rs]
            write_partition(str(path), d, keep, lay, "overwrite-partition")
        entities = len({r[0] for rs in by_day.values() for r in rs})
        rows = sum(len(r) for r in by_day.values())
        total += rows
        files = len(t["files"])
        say(f"{path}: {entities} entities x {len(by_day)} business days" + (f", {len(lay.columns)} promoted columns" if lay else "")
            + f"; {rows} rows from {files} file{'s' if files != 1 else ''}, version {_version(path)}")
    return total


def _version(path: pathlib.Path):
    """The table's Delta version after the writes (for the report line); '?' when it cannot be read."""
    try:
        from deltalake import DeltaTable
        return DeltaTable(str(path)).version()
    except Exception:  # noqa: BLE001  (a report line only)
        return "?"


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter,
                                 epilog=__doc__.split("\n\n", 1)[1])
    ap.add_argument("--rows", action="append", required=True, metavar="FILE", help="a JSONL file of dated rows, or an archive of them (repeatable)")
    ap.add_argument("--restated", action="append", default=[], metavar="FILE", help="a JSONL file of corrections, a second commit (repeatable)")
    ap.add_argument("--root", required=True, help="the Delta root (the connectors' root, data/delta)")
    a = ap.parse_args(argv)
    total = load([pathlib.Path(p) for p in a.rows], pathlib.Path(a.root), [pathlib.Path(p) for p in a.restated])
    print(f"{total} rows under {a.root}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
