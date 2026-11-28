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

"""Loads loader rows (JSON Lines) into the Delta Lake the server's Delta connectors read: the loading half of the sample history that
tools/samplegen/lake.py, tools/packgen/banking/make_data.py --lake and the pack generators' --lake write.

    python tools/samplegen/load_rows.py \\
        --rows data/build/lake-rows/banking-history.tar --restated data/build/lake-rows/banking-restated.tar --root data/delta/demo

A row (one JSON object a line, UTF-8):

    {"domain": "trading", "kind": "trade", "table": "trade_endur", "id": "1000015", "date": "2026-09-30", "doc": "<the document as JSON text>"}

`table` is the table's folder under the domain (default: the kind). The row is kept as it is: its document is stored unchanged.

**Its business date follows the load rules** (docs/guides/DATA_LAKE_GUIDE.md), never the row's ``date``:

* a **tar** (.tar, .tgz, .tar.gz, .tar.zip) holds ``<table>/<YYYY-MM-DD>/<file>.jsonl[.gz]``: each file's rows go under its folder's
  date, and every row must name its folder's table (a row naming another is refused); --as-of is refused for a tar;
* a **plain file** (.jsonl, .jsonl.gz; or a .zip of one table's files) is one table and one business date: --as-of, else its .ready
  file's business-date, else the date in its name (``trade_endur.2026-09-30.jsonl``), else the business today. Rows of several tables
  (or domains) in one plain file are refused: send a tar.

A row whose own ``date`` differs from the date it is stored under is counted and said (as lake build says it), never moved.

``--format iceberg`` writes the same tables into Apache Iceberg through Drishti Lake's one loader (``tools/lake/loaderows.py``; the server's
exec jar or LAKE_CLASSPATH): one load a table, each business date replaced, the corrections applied before it. Every table named in --rows
is written afresh: its rows, every business date, in the layout its pack declares (samplegen/layout.py:
promoted columns, sorted, files of file-rows). Each --restated input is a second commit on top: its rows replace the rows of the same
id on their date, so time travel ("as known at") shows the correction. A restated row's table must be in --rows of the same run.
Every input is read and checked before anything is written; then each table is written, and a line per table says its rows, its files
and its Delta version. Build-time tooling only: the console never reads the lake.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys
from datetime import date

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent / "lake"))
import lakeconfig  # noqa: E402  (the one join of lake/domain/table: locate)
import feedroute  # noqa: E402  (a file's .ready file)
import lakewheel  # noqa: F401  (the drishti-lake wheel on the path: tools/lake/lakewheel.py)
from drishti_lake import archives  # noqa: E402  (Drishti Lake's archive rule, the one every loader shares)
from drishti_lake import filedate  # noqa: E402  (the load rules: a name's table and date, a tar's layout)
from samplegen.layout import Layout, layouts_for_domain, promote, write_days, write_partition  # noqa: E402

SEND_A_TAR = "a file is one table and one business date (docs/guides/DATA_LAKE_GUIDE.md): send a tar of <table>/<YYYY-MM-DD>/ folders"


def read(path: pathlib.Path, as_of: date | None = None, report: dict | None = None):
    """(row, business date) of every row of one input (module doc): a tar's under each folder's date, a plain file's (or a zip's) under
    the date the rules decide. Each row carries its file under ``"_source"`` (load takes it off). ``report``: {"dates": {label: words},
    "other": {label: (rows, date)}} filled in."""
    path = pathlib.Path(path)
    report = report if report is not None else {"dates": {}, "other": {}}

    def rows_of(lines, label):
        for n, line in lines:
            if not line.strip():
                continue
            try:
                r = json.loads(line)
            except ValueError as e:
                raise SystemExit(f"{label}:{n}: not a JSON object ({e})")
            r["_source"] = label
            yield r

    def counted(r, day, label):
        if str(r.get("date") or "")[:10] not in ("", day.isoformat()):
            n, _ = report["other"].get(label, (0, day))
            report["other"][label] = (n + 1, day)

    if filedate.is_tar(path.name):
        if as_of is not None:
            raise SystemExit(f"{path.name}: a tar archive names its own tables and dates (its <table>/<YYYY-MM-DD>/ folders): --as-of is refused for it")
        try:
            plan = archives.plan(path, layout="feed")
            for note in plan.notes:
                print(f"{path.name}: {note}")
            with archives.Archive(plan) as arc:
                for table, members in sorted(plan.datasets.items()):
                    for m in members:
                        report["dates"].setdefault(m.label, filedate.words(filedate.Decided(m.date, "folder")))
                        for r in rows_of(enumerate(arc.lines(m), 1), m.label):
                            if str(r.get("table") or r.get("kind") or "").lower() != table:
                                raise SystemExit(f"{m.label}: a row of table {r.get('table') or r.get('kind')!r} in folder {table}/ (each "
                                                 "folder is a table, and its rows name it); nothing was written")
                            counted(r, m.date, m.label)
                            yield r, m.date
        except archives.ArchiveError as e:
            raise SystemExit(str(e)) from None
        return
    ready = None if as_of else feedroute.read_marker(path).get("business-date")
    d = filedate.decide(as_of or ready, filedate.of(path.name), "auto", None, path.name)
    d = filedate.Decided(d.date, "ready") if ready is not None else d
    report["dates"][path.name] = filedate.words(d)
    if archives.is_archive(path.name):                  # a zip: one table's files of one date
        try:
            plan = archives.plan(path, layout="feed")
            lines = ((n, line) for _ds, _m, n, line in archives.read_lines(plan))
        except archives.ArchiveError as e:
            raise SystemExit(str(e)) from None
    else:
        import gzip
        fh = gzip.open(path, "rt", encoding="utf-8") if str(path).endswith(".gz") else open(path, encoding="utf-8")
        lines = enumerate(fh, 1)
    seen = set()
    for r in rows_of(lines, path.name):
        seen.add((r.get("domain"), r.get("table") or r.get("kind")))
        if len(seen) > 1:
            raise SystemExit(f"{path.name}: rows of {', '.join(sorted(f'{a}/{b}' for a, b in seen))}: {SEND_A_TAR}; nothing was written")
        counted(r, d.date, path.name)
        yield r, d.date


def _layout(domain: str, table: str, kind: str, layouts) -> Layout | None:
    if isinstance(layouts, dict):
        return layouts.get(table) or layouts.get(kind)
    found = layouts(domain)
    return found.get(table) or found.get(kind)


def _write_iceberg(path: pathlib.Path, domain: str, table: str, kind: str, by_day: dict, lay: Layout | None, work: pathlib.Path, settings,
                   jar: str | None, java: str | None, say) -> None:
    """One table into Iceberg through the one loader (``loaderows``: Drishti Lake's ``DrishtiLakeCli load``): each business day replaced, the
    layout's promoted columns beside the document."""
    import loaderows

    def lines():
        for d, rs in sorted(by_day.items()):
            for i, body, doc in rs:
                cols = promote(doc, lay) if lay and doc is not None else {}
                yield json.dumps({"domain": domain, "kind": kind, "id": i, "date": d.isoformat(), "doc": body, "columns": cols},
                                 ensure_ascii=False, separators=(",", ":"))
    try:
        loaderows.load(lines(), path.parent.parent, domain, table, work / table, "iceberg", settings=settings, jar=jar, java=java, say=say)
    except loaderows.LoadError as e:
        raise SystemExit(f"{domain}/{table}: the Iceberg load did not finish: {e}") from None


def load(rows: list[pathlib.Path], root: pathlib.Path, restated: list[pathlib.Path] = (), layouts=None, say=print,
         as_of: date | None = None, fmt: str = "delta", work: pathlib.Path | None = None, iceberg_set=(), jar: str | None = None,
         java: str | None = None) -> int:
    """Writes every table of `rows` under root/<domain>/<table>, then applies `restated`. layouts: {table or kind: Layout} for
    every table, or None for each domain's packs (layouts_for_domain). ``as_of``: the business date of every plain file (refused for a
    tar). ``fmt`` ``iceberg``: the tables go into Iceberg through the one loader (``work``: its working folder, removed afterwards;
    ``iceberg_set``: its ``KEY=VALUE`` settings; ``jar``, ``java``). Returns the number of history rows written."""
    if fmt == "iceberg" and work is None:
        raise SystemExit("--format iceberg needs --work DIR: the loader's working folder")
    cache: dict[str, dict] = {}

    def lays(domain):
        if domain not in cache:
            cache[domain] = layouts_for_domain(domain)
        return cache[domain]

    report: dict = {"dates": {}, "other": {}}
    tables: dict[tuple[str, str], dict] = {}
    for p in rows:
        for r, day in read(pathlib.Path(p), as_of, report):
            key = (r["domain"], r.get("table") or r["kind"])
            t = tables.setdefault(key, {"kind": r["kind"], "days": {}, "files": set()})
            t["files"].add(r.pop("_source", str(p)))
            t["days"].setdefault(day, []).append((str(r["id"]), r["doc"]))
    fixes: dict[tuple[str, str], dict] = {}
    for p in restated:
        for r, day in read(pathlib.Path(p), as_of, report):
            key = (r["domain"], r.get("table") or r["kind"])
            if key not in tables:
                raise SystemExit(f"{p}: restates {key[0]}/{key[1]}, which no --rows file of this run holds")
            fixes.setdefault(key, {}).setdefault(day, []).append((str(r["id"]), r["doc"]))
    for label, words in sorted(report["dates"].items()):
        if not label.count("!/"):                           # a tar's members: their folders' dates, said per folder below only when rows differ
            say(f"{label}: business date {words}")
    for label, (n, day) in sorted(report["other"].items()):
        say(filedate.other_date_note(n, label, "date", day))
    total = 0
    for (domain, table), t in tables.items():
        lay = _layout(domain, table, t["kind"], layouts if layouts is not None else lays)
        by_day = {d: [(i, body, json.loads(body) if lay else None) for i, body in rs] for d, rs in t["days"].items()}
        path = lakeconfig.locate_path(root, domain, table)
        if fmt == "iceberg":                            # the corrections first, then one load: each date is replaced once
            for d, rs in sorted(fixes.get((domain, table), {}).items()):
                ids = {i for i, _ in rs}
                by_day[d] = [r for r in by_day.get(d, []) if r[0] not in ids] + [(i, body, json.loads(body) if lay else None) for i, body in rs]
            _write_iceberg(path, domain, table, t["kind"], by_day, lay, work, iceberg_set, jar, java, say)
        else:
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
            + f"; {rows} rows from {files} file{'s' if files != 1 else ''}" + ("" if fmt == "iceberg" else f", version {_version(path)}"))
    if work is not None:
        import shutil
        shutil.rmtree(work, ignore_errors=True)
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
    ap.add_argument("--rows", action="append", required=True, metavar="FILE",
                    help="a tar of <table>/<YYYY-MM-DD>/ folders of rows, or a file of one table's rows of one date (repeatable)")
    ap.add_argument("--restated", action="append", default=[], metavar="FILE", help="corrections in the same forms, a second commit (repeatable)")
    ap.add_argument("--root", required=True, help="the lake's root (the demo lake's root, data/delta/demo; data/iceberg with --format iceberg)")
    ap.add_argument("--format", choices=("delta", "iceberg"), default="delta",
                    help="the table format; iceberg goes through Drishti Lake's one loader (tools/lake/loaderows.py)")
    ap.add_argument("--iceberg-set", action="append", default=[], metavar="KEY=VALUE", help="--format iceberg: a setting of the loader (catalog=rest, uri=..., "
                                                                                              "warehouse=..., s3.region=...); repeatable")
    ap.add_argument("--work", metavar="DIR", help="--format iceberg: the loader's working folder (removed afterwards)")
    ap.add_argument("--jar", help="--format iceberg: the server's exec jar (default: the one in <home>/lib)")
    ap.add_argument("--java", help="--format iceberg: the Java to run the loader")
    ap.add_argument("--as-of", metavar="YYYY-MM-DD", help="the business date of every plain file (over its .ready file's and its name's); "
                                                          "refused for a tar, whose folders name their dates")
    a = ap.parse_args(argv)
    total = load([pathlib.Path(p) for p in a.rows], pathlib.Path(a.root), [pathlib.Path(p) for p in a.restated],
                 as_of=date.fromisoformat(a.as_of) if a.as_of else None, fmt=a.format, work=pathlib.Path(a.work) if a.work else None,
                 iceberg_set=a.iceberg_set, jar=a.jar, java=a.java)
    print(f"{total} rows under {a.root}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
