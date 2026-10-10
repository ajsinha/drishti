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

"""Load JSON Lines files into the Delta lake the Delta connector reads (business_date partitions, the layout the
pack declares), so a running server picks them up within its refresh interval.

    uv run --with deltalake --with pyarrow --with pyyaml python tools/ingest_jsonl.py \\
        --from data/jsonl --pack config/packs/my-bank --lake /var/lib/drishti/delta

Input: --from is a folder (its *.jsonl; -r for subfolders) or one .jsonl file, repeatable; a line is a plain JSON document or the loader envelope
{"kind", "id", "doc"} (doc an object or JSON text, unwrapped). The kind is the file stem unless the envelope says one.

What is written where: <lake>/<domain>/<kind>/ with columns id, doc, business_date and the promoted columns of the layout.
With --pack the domain, the layout (settings.layout.<kind>.columns/sort-by/file-rows/row-group-rows) come from the pack's
Delta connector, and the id and date fields from its `ingest:` block ({kind: {key: FIELD, date: FIELD}}, written by
tools/packgen/pack_from_jsonl.py). Without --pack give --domain and per-kind --key / --date (FIELD or kind=FIELD,...; dotted
paths); the layout is then the bare id, doc, business_date columns plus --columns.

--store files --root DIR writes the File connector's layout instead (no Delta dependencies): <DIR>/<domain>/<date>/<kind>.jsonl,
one loader row per line ({"domain","kind","id","date","doc"}); overwrite-dates rewrites only the date files present, replace
deletes the kind's dated files first, append adds lines. Serve it with DRISHTI_FILES_ROOT=DIR (a pack declares plugin: file).

--mode overwrite-dates (default) replaces only the business dates present in the input, so a re-run is idempotent and other
dates are kept; append adds the rows (a re-run duplicates them); replace rewrites the whole table. --dry-run prints the
counts and writes nothing. --batch-rows N writes at most N rows of a date per commit.
Documents without the date (or id) and lines that are not JSON are counted and reported (file:line), never written.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys
from datetime import date

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE / "samplegen"))
import sutragen as SG  # noqa: E402  (reading, dotted paths, scalar option parsing)

MODES = ("overwrite-dates", "append", "replace")


class Report:
    def __init__(self):
        self.rows: dict[tuple[str, str], int] = {}     # (kind, date) -> rows
        self.no_date: dict[str, int] = {}
        self.no_key: dict[str, int] = {}
        self.bad: list[str] = []
        self.untracked: dict[str, int] = {}             # kinds without a date field


def read_lines(inputs, recursive: bool, kind: str | None, report: Report):
    """Yields (kind, doc) from the JSONL files; bad lines go to the report with file:line."""
    for k, doc, _ in SG.read_records(SG.input_files(inputs, recursive), report.bad.append):
        yield kind or k, doc


def pack_settings(pack: pathlib.Path) -> tuple[str, dict, dict, dict]:
    """(domain, {kind: Layout}, {kind: key}, {kind: date}) from a pack folder's pack.yaml."""
    import yaml
    import layout as L  # delta only
    m = yaml.safe_load((pack / "pack.yaml").read_text(encoding="utf-8")) or {}
    defs = m.get("connectors") if isinstance(m.get("connectors"), dict) else (m.get("connector-templates") or {})   # a pack names its connectors and suggests them as templates
    conns = [c for c in defs.values() if c.get("plugin") in ("delta", "file")]
    if not conns:
        raise SystemExit(f"ingest_jsonl: {pack}/pack.yaml declares no delta or file connector (generate the pack with --date)")
    c = conns[0]
    st = c.get("settings") or {}
    layouts = {k: lay for k in (c.get("kinds") or []) if c.get("plugin") == "delta" and (lay := L.Layout.from_settings(st, k)) is not None}
    ing = m.get("ingest") or {}
    keys = {k: v.get("key", "id") for k, v in ing.items()}
    dates = {k: v["date"] for k, v in ing.items() if v.get("date")}
    return st.get("domain") or m["pack"], layouts, keys, dates


def collect(args, keys, dates, report: Report) -> dict[str, dict[date, list]]:
    """kind -> business date -> [(id, doc json, doc)] for the kinds that have a date field."""
    out: dict[str, dict[date, list]] = {}
    for kind, doc in read_lines(args.src, args.recursive, args.kind, report):
        dfield = dates.get(kind)
        if not dfield:
            report.untracked[kind] = report.untracked.get(kind, 0) + 1
            continue
        kv = SG.get(doc, keys.get(kind, "id"))
        ds = SG.doc_date(doc, dfield)
        try:
            day = date.fromisoformat(ds) if ds else None
        except ValueError:
            day = None
        if day is None:
            report.no_date[kind] = report.no_date.get(kind, 0) + 1
            continue
        if kv is SG.MISSING or kv is None:
            report.no_key[kind] = report.no_key.get(kind, 0) + 1
            continue
        out.setdefault(kind, {}).setdefault(day, []).append((str(kv), json.dumps(doc), doc))
    return out


def write(lake: pathlib.Path, domain: str, data: dict[str, dict[date, list]], layouts: dict, mode: str, batch: int,
          report: Report, say=print) -> None:
    import layout as L
    for kind in sorted(data):
        table = str(lake / domain / kind)
        lay = layouts.get(kind) or None
        by_day = data[kind]
        first = True
        for day in sorted(by_day):
            rows = sorted(by_day[day], key=lambda r: r[0])
            for i in range(0, len(rows), batch):
                chunk = rows[i:i + batch]
                if mode == "append":
                    m = "append"
                elif mode == "replace":
                    m, first = ("overwrite" if first else "append"), False
                else:       # overwrite-dates: the first chunk of a date replaces it (the table is created if need be)
                    m = "overwrite-partition" if i == 0 and L.table_types(table) is not None else "append"
                L.write_days(table, {day: chunk}, lay, m) if m != "overwrite-partition" else L.write_partition(table, day, chunk, lay, m)
            report.rows[(kind, day.isoformat())] = len(rows)
            say(f"  {kind} {day}: {len(rows)} rows")


def write_files(root: pathlib.Path, domain: str, data: dict, mode: str, report: Report, say=print) -> None:
    """The File connector's layout: <root>/<domain>/<date>/<kind>.jsonl, a loader row per line. Each file is written beside
    itself and moved into place, so a running server never reads half a file."""
    import os
    for kind in sorted(data):
        if mode == "replace":
            for old in (root / domain).glob(f"*/{kind}.jsonl"):
                old.unlink()
        for day in sorted(data[kind]):
            rows = sorted(data[kind][day], key=lambda r: r[0])
            target = root / domain / day.isoformat() / f"{kind}.jsonl"
            target.parent.mkdir(parents=True, exist_ok=True)
            tmp = target.with_name(target.name + ".tmp")
            with open(tmp, "w" if mode != "append" else "a", encoding="utf-8") as fh:
                for id_, body, _ in rows:
                    fh.write(json.dumps({"domain": domain, "kind": kind, "id": id_, "date": day.isoformat(), "doc": body}) + "\n")
            if mode == "append" and target.exists():
                with open(target, "a", encoding="utf-8") as out:
                    out.write(tmp.read_text(encoding="utf-8"))
                tmp.unlink()
            else:
                os.replace(tmp, target)
            report.rows[(kind, day.isoformat())] = len(rows)
            say(f"  {kind} {day}: {len(rows)} rows -> {target}")


def summary(report: Report, dry: bool, say=print) -> None:
    say(f"{'kind':<20}{'business date':<16}{'rows':>9}" + ("   (dry run)" if dry else ""))
    for (k, d), n in sorted(report.rows.items()):
        say(f"{k:<20}{d:<16}{n:>9}")
    say(f"total rows: {sum(report.rows.values())}")
    for label, d in (("without a business date (skipped)", report.no_date), ("without an id (skipped)", report.no_key),
                     ("of a kind with no date field (not loaded)", report.untracked)):
        if d:
            say(f"{sum(d.values())} document(s) {label}: " + ", ".join(f"{k}={n}" for k, n in sorted(d.items())))
    for b in report.bad[:20]:
        say("bad line " + b)
    if len(report.bad) > 20:
        say(f"... and {len(report.bad) - 20} more bad lines")


def ingest(args, say=print) -> Report:
    """The whole run; returns the report (used by pack_from_jsonl and the tests)."""
    report = Report()
    layouts: dict = {}
    if not args.src:
        raise SystemExit("ingest_jsonl: give --from (a folder or .jsonl file) or --watch DIR")
    if args.mode is None:
        args.mode = "overwrite-dates"
    if args.pack:
        domain, layouts, keys, dates = pack_settings(args.pack)
    else:
        import layout as L
        if not args.domain:
            raise SystemExit("ingest_jsonl: give --pack, or --domain with --date")
        domain = args.domain
        kd, kk = SG.parse_scalar(args.key)
        dd, dk = SG.parse_scalar(args.date)
        keys = {"*": kd or "id", **kk}
        dates = {"*": dd, **dk} if dd or dk else {}
        cols = [c for c in (args.columns or "").split(",") if c]
        if cols:
            layouts = {"*": L.Layout(columns=cols)}

    class _Lookup(dict):          # "*" is the default for any kind
        def get(self, k, d=None):
            return super().get(k, super().get("*", d))
    keys, dates, layouts = _Lookup(keys), _Lookup(dates), _Lookup(layouts)
    data = collect(args, keys, dates, report)
    for kind, days in data.items():
        for day, rows in days.items():
            report.rows[(kind, day.isoformat())] = len(rows)
    if args.dry_run:
        summary(report, True, say)
        return report
    report.rows.clear()
    if args.store == "files":
        if not args.root:
            raise SystemExit("ingest_jsonl: --store files needs --root")
        write_files(args.root, domain, data, args.mode, report, say)
    else:
        if not args.lake:
            raise SystemExit("ingest_jsonl: --store delta needs --lake")
        write(args.lake, domain, data, layouts, args.mode, args.batch_rows, report, say)
    summary(report, False, say)
    return report


def build_parser(add_help: bool = True) -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter,
                                epilog=__doc__.split("\n\n", 1)[1], add_help=add_help)
    p.add_argument("--from", dest="src", type=pathlib.Path, action="append", help="a folder of *.jsonl or one .jsonl file; repeatable (or use --watch)")
    p.add_argument("-r", "--recursive", action="store_true")
    p.add_argument("--pack", type=pathlib.Path, help="pack folder: domain, layouts, key and date fields come from its pack.yaml")
    p.add_argument("--domain", help="lake domain (without --pack)")
    p.add_argument("--kind", help="kind for every document (default: file stem or envelope kind)")
    p.add_argument("--key", help="id field: FIELD or kind=FIELD,... (default id)")
    p.add_argument("--date", help="business-date field: FIELD or kind=FIELD,...")
    p.add_argument("--columns", help="promoted columns without --pack: a,b,c")
    p.add_argument("--lake", type=pathlib.Path, help="the Delta root (DRISHTI_DELTA_ROOT) for --store delta")
    p.add_argument("--store", choices=("delta", "files"), default="delta", help="delta (default) or the File connector's layout")
    p.add_argument("--root", type=pathlib.Path, help="the files root (DRISHTI_FILES_ROOT) for --store files")
    p.add_argument("--mode", choices=MODES, help="overwrite-dates (default), append or replace; a --watch run defaults to append")
    p.add_argument("--dry-run", action="store_true")
    p.add_argument("--batch-rows", type=int, default=250_000)
    w = p.add_argument_group("watch a drop folder (see tools/ingest_watch.py)")
    w.add_argument("--watch", type=pathlib.Path, metavar="DIR", help="poll DIR for new or changed *.jsonl and ingest each once (instead of --from)")
    w.add_argument("--once", action="store_true", help="with --watch: one pass, then exit (for cron); exit 1 if a file failed or had bad lines")
    w.add_argument("--interval", type=float, default=10, help="with --watch: seconds between passes (default 10)")
    w.add_argument("--stable-seconds", type=float, default=2, help="with --watch: a file is read only once its size and mtime have not changed for this long (default 2)")
    w.add_argument("--done-dir", type=pathlib.Path, help="with --watch: move each ingested file here (default: leave it, the state file marks it)")
    w.add_argument("--state", type=pathlib.Path, help="with --watch: the state file (default <lake or files root>/.drishti-ingest-state.json)")
    return p


def ingest_watch_run(args) -> int:
    import ingest_watch
    return ingest_watch.watch(args, ingest)


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    if args.watch:
        return ingest_watch_run(args)
    r = ingest(args)
    return 1 if r.bad else 0


if __name__ == "__main__":
    sys.exit(main())
