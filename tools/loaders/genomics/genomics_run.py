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


"""Load your own genomics files: VCF files and gene, sample and cohort tables you drop in a folder, checked against the genomics contracts and
loaded into the genomics lake by the kinds' names (variant, sample, gene, cohort). No conversion by hand.

    python3 tools/loaders/genomics/genomics_run.py --dry-run                 read the drop folder, check, say what would load; nothing changes
    python3 tools/loaders/genomics/genomics_run.py                           check, write the dated files, load into genomicslake (java)
    python3 tools/loaders/genomics/genomics_run.py trio.vcf.gz genes.csv --date 2026-11-26      named files instead of the drop folder
    python3 tools/loaders/genomics/genomics_run.py --load inbox              hand the day to the data inbox (the server loads it)
    python3 tools/loaders/genomics/genomics_run.py --load none               write and check the files only

Windows: py -3.13 tools\\loaders\\genomics\\genomics_run.py ... (the same arguments).

What a file is, by its name (the first part of the name, then .vcf / .csv / .tsv / .jsonl, plain or .gz):
    *.vcf, *.vcf.gz                 variants and samples (VCF 4.x: the module's own rules, see genomics_kit.py)
    gene*, genes*                   the gene kind (GENE, CHROM, START, PROTEIN_LENGTH, DOMAINS ...)
    sample*, samples*               the sample kind (SAMPLE_ID, COHORT, SEX, POPULATION, VARIANTS)
    cohort*, cohorts*               the cohort kind (COHORT_ID, SAMPLES, MEMBERS, VARIANTS, GENES)
The business date is the date in the name (trio_2026-11-26.vcf.gz, genes.2026-11-26.csv, genes_20261126.csv), else --date, else today.

The drop folder is <data>/genomics/inbox (GENOMICS_INBOX_DIR, or --from-dir); done files move to done/<date>/, files that cannot be read to
rejected/ with <name>.why.txt. Everything the run wrote is under <data>/genomics/loads/<date>/ (the dated JSON Lines files, rejects, a manifest
with each file's rows and SHA-256). All the files of one date load together and REPLACE that date's rows of each kind: to add a file to a
date, drop all its files again. Exit: 0 ok, 1 failed, 2 written with rows rejected (the good rows loaded), 3 nothing to do.

The module's screens read the lake tables this run writes (genomics.variant, .sample, .gene, .cohort) through the genomics pack's connector:
there is no second folder for them.
"""
from __future__ import annotations

import argparse
import gzip
import json
import os
import re
import shutil
import sys
import time
from datetime import date, datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path[:0] = [str(HERE), str(HERE.parent / "common")]
import genomics_kit as G  # noqa: E402
import pullkit as K  # noqa: E402

DATE_IN_NAME = re.compile(r"[._-](\d{4})-?(\d{2})-?(\d{2})$")
OK, FAILED, REJECTS, NOTHING = 0, 1, 2, 3


def data_dir() -> Path:
    return K.data_dir()


def drop_folder(given: str | None = None) -> Path:
    return Path(given or os.environ.get("GENOMICS_INBOX_DIR") or data_dir() / "genomics" / "inbox")


def split_name(path: Path) -> tuple[str, str, date | None]:
    """(kind of file, stem without the date, the date in the name): kind is vcf, gene, sample or cohort; GenomicsError when the name says nothing."""
    name = Path(path).name
    low = name.lower()
    base = re.sub(r"\.(gz)$", "", low)
    ext = base.rsplit(".", 1)[-1] if "." in base else ""
    stem = base[: -(len(ext) + 1)] if ext else base
    when = None
    m = DATE_IN_NAME.search(stem)
    if m:
        try:
            when = date(int(m.group(1)), int(m.group(2)), int(m.group(3)))
            stem = stem[: m.start()]
        except ValueError:
            raise G.GenomicsError(f"{name}: '{m.group(0)[1:]}' is not a date (YYYY-MM-DD or YYYYMMDD at the end of the name)")
    if ext == "vcf":
        return "vcf", stem, when
    if ext in ("csv", "tsv", "txt", "jsonl", "json", "ndjson"):
        for kind in ("gene", "sample", "cohort"):
            if stem.startswith(kind):
                return kind, stem, when
        raise G.GenomicsError(f"{name}: a table's name starts with gene, sample or cohort (genes.csv, samples_2026-11-26.jsonl); a VCF ends in .vcf or .vcf.gz")
    raise G.GenomicsError(f"{name}: not a .vcf, .vcf.gz, .csv, .tsv or .jsonl file (plain or .gz)")


class Day:
    """The rows of one business date, by kind and key; rejects with their reasons."""

    def __init__(self, day: date):
        self.day = day
        self.rows: dict[str, dict[str, dict]] = {k: {} for k in G.KINDS}
        self.rejects: dict[str, list[dict]] = {k: [] for k in G.KINDS}
        self.sources: list[Path] = []
        self.notes: list[str] = []

    def add(self, kind: str, rows: list[dict], source: str) -> None:
        key = G.contract(kind)["key"]
        for n, r in enumerate(rows, 1):
            row, why = G.check_row(kind, r)
            if why:
                self.rejects[kind].append({"source": source, "row": n, "reason": why, "data": r})
                continue
            if row[key] in self.rows[kind]:
                self.notes.append(f"{source}: {kind} {row[key]} appears again: the later one is kept")
            self.rows[kind][row[key]] = row


def read_file(path: Path, kind: str, day: Day) -> None:
    if kind == "vcf":
        variants, samples, problems = G.read_vcf(path)
        for p in problems:
            day.rejects["variant"].append({"source": path.name, "row": 0, "reason": p, "data": None})
        day.add("variant", variants, path.name)
        day.add("sample", samples, path.name)
    else:
        day.add(kind, G.read_table(path, kind), path.name)
    day.sources.append(path)


def write_day(day: Day, out: Path) -> list[dict]:
    """The day's files under out/<date>/: <kind>.<date>.jsonl.gz (a file per kind that has rows), rejects, manifest.json. The files as
    feedload takes them."""
    folder = out / day.day.isoformat()
    if folder.exists():
        shutil.rmtree(folder)
    folder.mkdir(parents=True)
    files = []
    for kind in G.KINDS:
        rows = list(day.rows[kind].values())
        if day.rejects[kind]:
            (folder / f"{kind}.{day.day}.rejects.jsonl").write_text(
                "".join(json.dumps(r, ensure_ascii=False, default=str) + "\n" for r in day.rejects[kind]), encoding="utf-8")
        if not rows:
            continue
        path = folder / f"{kind}.{day.day}.jsonl.gz"
        with open(path, "wb") as raw, gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as gz:
            for r in rows:
                gz.write((json.dumps(r, ensure_ascii=False, sort_keys=True, default=str) + "\n").encode("utf-8"))
        files.append({"dataset": kind, "pack": G.PACK, "kind": kind, "table": kind, "key": G.contract(kind)["key"], "path": str(path),
                      "file": path.name, "rows": len(rows), "bytes": path.stat().st_size, "sha256": K.sha256(path),
                      "businessDate": day.day.isoformat()})
    (folder / "manifest.json").write_text(json.dumps({
        "loader": "genomics", "businessDate": day.day.isoformat(), "written": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "sources": [p.name for p in day.sources], "files": [{k: f[k] for k in ("kind", "file", "rows", "bytes", "sha256")} for f in files],
        "rejected": {k: len(v) for k, v in day.rejects.items() if v}}, indent=1, sort_keys=True) + "\n", encoding="utf-8")
    return files


def collect(args) -> tuple[list[Path], Path | None]:
    if args.files:
        return [Path(f) for f in args.files], None
    folder = drop_folder(args.from_dir)
    if not folder.is_dir():
        folder.mkdir(parents=True, exist_ok=True)
    return sorted(p for p in folder.iterdir() if p.is_file() and not p.name.endswith((".part", ".why.txt"))), folder


def run(args, log=print) -> int:
    t0 = time.monotonic()
    files, folder = collect(args)
    if not files:
        log(f"genomics nothing-to-do exit={NOTHING}: no files in {folder} (drop VCF, gene, sample or cohort files there)")
        return NOTHING
    days: dict[date, Day] = {}
    bad: list[tuple[Path, str]] = []
    default_day = date.fromisoformat(args.date) if args.date else date.today()
    for f in sorted(files, key=lambda p: (split_name_safe(p)[0] != "vcf", p.name)):
        try:
            kind, _stem, when = split_name(f)
            day = days.setdefault(when or default_day, Day(when or default_day))
            read_file(f, kind, day)
            log(f"  read {f.name}: {kind}, business date {day.day}")
        except (G.GenomicsError, OSError, UnicodeDecodeError, EOFError) as e:
            bad.append((f, str(e)))
            log(f"  refused {f.name}: {e}")
    code = OK
    totals = {k: 0 for k in G.KINDS}
    rejected = 0
    load_rows = 0
    route = args.load
    for d in sorted(days):
        day = days[d]
        rejected += sum(len(v) for v in day.rejects.values())
        for n in day.notes:
            log(f"  note: {n}")
        for k in G.KINDS:
            totals[k] += len(day.rows[k])
        if args.dry_run:
            log(f"  dry run {d}: " + ", ".join(f"{len(day.rows[k])} {k}" for k in G.KINDS if day.rows[k]) + f"; {sum(len(v) for v in day.rejects.values())} rejected")
            for k in G.KINDS:
                for r in day.rejects[k][:5]:
                    log(f"    reject {r['source']} row {r['row']}: {r['reason']}")
            continue
        out = Path(args.out) if args.out else data_dir() / "genomics" / "loads"
        written = write_day(day, out)
        if not written:
            log(f"  {d}: no rows to load")
            continue
        if route != "none":
            import feedload as L
            opts = {k: v for k, v in (("inbox", args.inbox), ("lake_root", args.lake_root), ("signal", args.server),
                                      ("token_file", args.token_file)) if v}
            res = L.load(route, written, args.lake, "genomics", datetime.now().strftime("%Y%m%dT%H%M%S"),
                         out / d.isoformat() / "load", opts, log)
            load_rows += res.get("rows", 0)
            if res["status"] == "failed":
                log(f"genomics failed exit={FAILED} load: {res.get('error')}")
                return FAILED
            if res["status"] == "rejects":
                code = REJECTS
    if not args.dry_run:
        for f, why in bad:
            if folder is not None:
                dest = folder / "rejected"
                dest.mkdir(exist_ok=True)
                shutil.move(str(f), str(dest / f.name))
                (dest / f"{f.name}.why.txt").write_text(why + "\n", encoding="utf-8")
        if folder is not None:
            for d, day in days.items():
                dest = folder / "done" / d.isoformat()
                dest.mkdir(parents=True, exist_ok=True)
                for src in day.sources:
                    shutil.move(str(src), str(dest / src.name))
    if bad or rejected:                    # something was refused: the good rows loaded (2), or nothing did (1)
        code = REJECTS if totals_any(totals) else FAILED
    log(f"{datetime.now().astimezone().replace(microsecond=0).isoformat()} genomics {'dry-run' if args.dry_run else ('ok' if code == OK else 'rejects' if code == REJECTS else 'failed')} "
        f"exit={code} files={len(files)} " + " ".join(f"{k}s={totals[k]}" for k in G.KINDS) + f" rejected={rejected} refused={len(bad)} "
        f"load={'none' if args.dry_run else route} loaded={load_rows:,} seconds={time.monotonic() - t0:.1f}")
    return code


def totals_any(totals: dict) -> bool:
    return any(totals.values())


def split_name_safe(p: Path):
    try:
        return split_name(p)
    except G.GenomicsError:
        return ("", "", None)


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="genomics_run.py", description="Load your own VCF, gene, sample and cohort files into the genomics lake.",
                                epilog="exit: 0 ok, 1 failed, 2 written with rows rejected, 3 nothing to do")
    p.add_argument("files", nargs="*", help="files to load (default: every file in the drop folder)")
    p.add_argument("--from-dir", help="the drop folder (default GENOMICS_INBOX_DIR, else <data>/genomics/inbox)")
    p.add_argument("--date", help="the business date of files whose name has none (YYYY-MM-DD; default today)")
    p.add_argument("--dry-run", action="store_true", help="read and check; write nothing, move nothing")
    p.add_argument("--load", choices=("java", "python", "inbox", "none"), default="java",
                   help="java (default, the parallel loader), python (lake build), inbox (the data inbox) or none (write the files only)")
    p.add_argument("--lake", default="genomicslake", help="the lake (config/lakes/<lake>.yaml; default genomicslake)")
    p.add_argument("--lake-root", help="write a scratch copy of the lake here instead of the configured one")
    p.add_argument("--inbox", help="with --load inbox: the data inbox folder (default <data>/dataload/inbox)")
    p.add_argument("--server", help="tell this server the data landed (no server is told unless you name one)")
    p.add_argument("--token-file", help="the API token file for --server (never printed)")
    p.add_argument("--out", help="where the dated files are written (default <data>/genomics/loads)")
    return p


def main(argv=None) -> int:
    a = parser().parse_args(argv)
    if a.date:
        try:
            date.fromisoformat(a.date)
        except ValueError:
            print(f"genomics: --date {a.date}: give YYYY-MM-DD", file=sys.stderr)
            return FAILED
    try:
        return run(a)
    except G.GenomicsError as e:
        print(f"genomics failed exit={FAILED}: {e}")
        return FAILED


if __name__ == "__main__":
    sys.exit(main())
