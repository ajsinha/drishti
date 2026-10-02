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


"""Delta Lake maintenance, so a lake does not grow without bound. Drishti's server only reads the lake; this job
does what readers cannot, on a schedule, for local lakes and lakes in object storage (S3 and S3-compatible):

    retention   delete business dates older than the history window (keep-business-days), counted back from today in
                schedule.zone (or --as-of), never from the newest date in the table; a run that would delete more than
                max-drop-share (0.5) of a table's rows deletes nothing and reports the table failed, unless --force-drop
    compact     merge the small files each day's writes leave into files of target-file-mb; a table whose pack declares
                a layout is instead rewritten, sorted, for the dates that drifted from it (relayout), and keeps
                statistics only on id, the date and its promoted columns
    checkpoint  write a Delta checkpoint, so readers replay a short log
    vacuum      remove files no longer referenced, older than vacuum-hours (time travel reaches back that far)

    uv run --with deltalake --with pyarrow --with pyyaml python tools/lake/maintain.py --config deploy/lake-maintenance.yaml --once
    ... --daemon            run every day at schedule.at in schedule.zone (a container or service)
    ... --dry-run           report what would happen, change nothing
    ... --as-of 2026-09-30  count the retention back from this date instead of today
    ... --force-drop        delete even when that is more than max-drop-share of a table

    uv run --with deltalake --with pyarrow --with pyyaml python tools/lake/maintain.py relayout --root data/delta --domain trading \\
        [--kind trade] [--dates 2026-09-28,2026-09-30 | --dates 2026-09-01..2026-09-30] [--force] [--dry-run]
                            rewrite tables into the layout their pack declares (tools/samplegen/layout.py), one business
                            date at a time; a date already in the layout is left as it is, so running it again is harmless

Every table of every configured domain is visited; one failing table is reported and the rest go on. A line of JSON
is printed per table and step (size and file counts before and after), for logs and monitoring."""
from __future__ import annotations

import argparse
import json
import sys
import time
from datetime import date, datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from samplegen.layout import Layout, column_name, keep_stats_small, layouts_for_domain, promote, write_partition  # noqa: E402
from samplegen.layout import stats_columns as stats_columns_of  # noqa: E402

DEFAULTS = {"domains": ["*"], "keep-business-days": None, "max-drop-share": 0.5, "compact": True, "target-file-mb": 128, "checkpoint": True,
            "vacuum-hours": 168, "storage-options": {}}


def log(**event) -> None:
    print(json.dumps({"at": datetime.now().astimezone().isoformat(timespec="seconds"), **event}, default=str), flush=True)


def business_cutoff(today: date, days: int) -> date:
    """The date `days` business days (Monday to Friday) before `today`."""
    d, n = today, 0
    while n < days:
        d -= timedelta(days=1)
        if d.weekday() < 5:
            n += 1
    return d


def tables(root: str, domains: list[str], options: dict) -> list[str]:
    """Table URIs <root>/<domain>/<kind> that have a _delta_log (local folders, or listed from object storage)."""
    if "://" in root and not root.startswith("file://"):
        import pyarrow.fs as pafs
        fs, base = pafs.FileSystem.from_uri(root)
        if options:
            fs = pafs.S3FileSystem(endpoint_override=options.get("AWS_ENDPOINT_URL"), access_key=options.get("AWS_ACCESS_KEY_ID"),
                                   secret_key=options.get("AWS_SECRET_ACCESS_KEY"), region=options.get("AWS_REGION", "us-east-1"),
                                   scheme="https" if str(options.get("AWS_ENDPOINT_URL", "https")).startswith("https") else "http")
        out = []
        for d in fs.get_file_info(pafs.FileSelector(base, recursive=False)):
            if d.type != pafs.FileType.Directory or not (domains == ["*"] or d.base_name in domains):
                continue
            for t in fs.get_file_info(pafs.FileSelector(d.path, recursive=False)):
                if t.type == pafs.FileType.Directory and fs.get_file_info(t.path + "/_delta_log").type == pafs.FileType.Directory:
                    out.append(root.rstrip("/") + "/" + d.base_name + "/" + t.base_name)
        return sorted(out)
    base = Path(root)
    picked = [p for p in sorted(base.iterdir()) if p.is_dir() and (domains == ["*"] or p.name in domains)] if base.is_dir() else []
    return [str(t) for d in picked for t in sorted(d.iterdir()) if (t / "_delta_log").is_dir()]


def add_actions(dt) -> list[dict]:
    """The table's live files, as dicts (deltalake 1.x returns an Arrow C-stream table; pyarrow reads it)."""
    import pyarrow as pa
    return pa.table(dt.get_add_actions(flatten=True)).to_pylist()


def stats(dt) -> dict:
    actions = add_actions(dt)
    return {"files": len(actions), "mb": round(sum(a["size_bytes"] for a in actions) / 1_048_576, 2)}


def retention_share(actions: list[dict], cutoff: date) -> tuple[int, int, str]:
    """What deleting the dates before `cutoff` would take: (rows before it, rows in all, "rows"), or files when the
    log does not count the rows."""
    old = [a for a in actions if str(a.get("partition.business_date", "9999")) < cutoff.isoformat()]
    if all(a.get("num_records") is not None for a in actions):
        return sum(a["num_records"] for a in old), sum(a["num_records"] for a in actions), "rows"
    return len(old), len(actions), "files"


def maintain(uri: str, conf: dict, today: date, dry_run: bool, force_drop: bool = False) -> dict:
    from deltalake import DeltaTable

    opts = conf.get("storage-options") or {}
    dt = DeltaTable(uri, storage_options=opts or None)
    before = stats(dt)
    done: dict = {"table": uri, "before": before}
    keep = conf.get("keep-business-days")
    if keep:
        cutoff = business_cutoff(today, int(keep))
        dropping, total, unit = retention_share(add_actions(dt), cutoff)
        share = float(conf.get("max-drop-share", 0.5))
        if total and dropping > share * total and not force_drop:
            raise RuntimeError(f"retention would delete {dropping:,} of {total:,} {unit} (more than max-drop-share {share}), the business dates "
                               f"before {cutoff}, counted back from {today}; nothing was deleted: check the dates, or run with --force-drop")
        if dry_run:
            old = [a for a in add_actions(dt) if str(a.get("partition.business_date", "9999")) < cutoff.isoformat()]
            done["retention"] = {"cutoff": cutoff, "would_remove_files": len(old)}
        else:
            done["retention"] = {"cutoff": cutoff, **{k: v for k, v in dt.delete(f"business_date < '{cutoff.isoformat()}'").items()
                                                      if k in ("num_deleted_rows", "num_removed_files")}}
    lay = layout_of(uri)
    if conf.get("compact") and lay is not None:
        # a laid-out table is compacted by rewriting only the dates that drifted from its layout (small intraday
        # appends, overlapping id ranges), sorted again: a plain compaction would merge files out of id order
        done["stats-columns-set"] = False if dry_run else keep_stats_small(uri, lay, opts)
        r = relayout(uri, lay, dry_run=dry_run)
        done["compact"] = {"relaid-dates": r["rewritten"], "files": r["files"], "rows": r["rows"]}
        dt = DeltaTable(uri, storage_options=opts or None)
    elif conf.get("compact") and not dry_run:
        r = dt.optimize.compact(target_size=int(conf.get("target-file-mb", 128)) * 1_048_576)
        done["compact"] = {k: r.get(k) for k in ("numFilesAdded", "numFilesRemoved")}
    if conf.get("checkpoint") and not dry_run:
        dt.create_checkpoint()
        dt.cleanup_metadata()
        done["checkpoint"] = dt.version()
    hours = conf.get("vacuum-hours")
    if hours is not None:
        removed = dt.vacuum(retention_hours=int(hours), dry_run=dry_run, enforce_retention_duration=False)
        done["vacuum"] = {"files": len(removed), "dry_run": dry_run}
    done["after"] = stats(DeltaTable(uri, storage_options=opts or None))
    return done


def layout_of(uri: str) -> Layout | None:
    """The layout a pack declares for the table at <root>/<domain>/<kind>, if any."""
    parts = uri.rstrip("/").split("/")
    if len(parts) < 2:
        return None
    try:
        return layouts_for_domain(parts[-2]).get(parts[-1])
    except Exception:  # noqa: BLE001 - no packs folder (a lake maintained elsewhere): plain compaction
        return None


def run(config: dict, dry_run: bool, today: date | None = None, force_drop: bool = False) -> int:
    """One pass over every configured lake; returns the number of tables that failed."""
    failures = 0
    for lake in config.get("lakes", []):
        conf = {**DEFAULTS, **lake}
        zone = ZoneInfo(config.get("schedule", {}).get("zone", "America/New_York"))
        day = today or datetime.now(zone).date()
        for uri in tables(str(lake["root"]), list(conf["domains"]), conf.get("storage-options") or {}):
            try:
                log(event="maintained", dry_run=dry_run, **maintain(uri, conf, day, dry_run, force_drop))
            except Exception as e:  # noqa: BLE001 - one table must not stop the others
                failures += 1
                log(event="failed", table=uri, error=f"{type(e).__name__}: {e}")
    return failures


def in_layout(actions: list[dict], lay: Layout, columns: set[str]) -> bool:
    """Whether a business date's files already follow the layout: every promoted column present with statistics, no
    file over file-rows and, sorted by id, contiguous disjoint id ranges with only the last file short."""
    if not actions or any(column_name(p) not in columns for p in lay.columns):
        return False
    if any(a.get(f"null_count.{column_name(p)}") is None for a in actions for p in lay.columns):
        return False
    if any(a["num_records"] > lay.file_rows for a in actions):
        return False
    if lay.sort_by != "id":
        return True
    files = sorted(actions, key=lambda a: str(a.get("min.id")))
    return all(a.get("min.id") is not None and a.get("max.id") is not None for a in files) and \
        all(files[k]["max.id"] < files[k + 1]["min.id"] and files[k]["num_records"] == lay.file_rows for k in range(len(files) - 1))


def wanted_dates(spec: str | None) -> tuple[str, str] | set[str] | None:
    if not spec:
        return None
    if ".." in spec:
        lo, hi = spec.split("..", 1)
        return lo.strip(), hi.strip()
    return {d.strip() for d in spec.split(",") if d.strip()}


def relayout(uri: str, lay: Layout, dates: str | None = None, force: bool = False, dry_run: bool = False) -> dict:
    """Rewrites a table into `lay` one business date at a time: the date's documents are read from a pinned
    version, promoted, sorted and written back, file by file (the first write replaces the date, the rest append),
    so memory holds one file's rows. Dates already in the layout are skipped unless `force`."""
    import pyarrow as pa
    import pyarrow.compute as pc
    from deltalake import DeltaTable

    dt = DeltaTable(uri)
    version = dt.version()
    columns = {f.name for f in pa.schema(dt.schema())}
    by_date: dict[str, list[dict]] = {}
    for a in add_actions(dt):
        by_date.setdefault(str(a.get("partition.business_date")), []).append(a)
    want = wanted_dates(dates)
    done: dict = {"table": uri, "version": version, "rewritten": [], "skipped": [], "files": 0, "rows": 0}
    for iso in sorted(by_date):
        if isinstance(want, set) and iso not in want or isinstance(want, tuple) and not want[0] <= iso <= want[1]:
            continue
        if not force and in_layout(by_date[iso], lay, columns):
            done["skipped"].append(iso)
            continue
        done["rewritten"].append(iso)
        if dry_run:
            continue
        day = date.fromisoformat(iso)
        snapshot = DeltaTable(uri, version=version).to_pyarrow_dataset(file_pruning_predicate=[("business_date", "=", iso)])
        ids = sorted(set(snapshot.to_table(columns=["id"]).column("id").to_pylist()))
        step = lay.file_rows if lay.sort_by == "id" else max(1, len(ids))
        for k in range(0, max(1, len(ids)), step):
            part = ids[k:k + step]
            where = (pc.field("id") >= part[0]) & (pc.field("id") <= part[-1]) if part and lay.sort_by == "id" else None
            t = snapshot.to_table(columns=["id", "doc"], filter=where)
            rows = [(i, d, promote(json.loads(d), lay)) for i, d in zip(t.column("id").to_pylist(), t.column("doc").to_pylist())]
            done["files"] += write_partition(uri, day, rows, lay, "overwrite-partition" if k == 0 else "append", promoted=True)
            done["rows"] += len(rows)
    return done


def relayout_main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(prog="maintain.py relayout", description="rewrite tables into their pack's layout")
    ap.add_argument("--root", default="data/delta")
    ap.add_argument("--domain", required=True)
    ap.add_argument("--kind", default=None, help="one kind (default: every kind with a layout in the domain)")
    ap.add_argument("--dates", default=None, help="only these business dates: 2026-09-28,2026-09-30 or 2026-09-01..2026-09-30")
    ap.add_argument("--packs", default=None, help="the packs folder whose pack.yaml files declare the layouts (default: this repository's)")
    ap.add_argument("--force", action="store_true", help="rewrite dates already in the layout too")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args(argv)
    layouts = layouts_for_domain(a.domain, Path(a.packs)) if a.packs else layouts_for_domain(a.domain)
    if a.kind:
        if a.kind not in layouts:
            log(event="failed", domain=a.domain, kind=a.kind, error="no layout declared for this kind")
            return 1
        layouts = {a.kind: layouts[a.kind]}
    failures = 0
    for kind, lay in sorted(layouts.items()):
        uri = str(Path(a.root) / a.domain / kind)
        try:
            log(event="relayout", dry_run=a.dry_run, **relayout(uri, lay, a.dates, a.force, a.dry_run))
        except Exception as e:  # noqa: BLE001 - one table must not stop the others
            failures += 1
            log(event="failed", table=uri, error=f"{type(e).__name__}: {e}")
    return 1 if failures else 0


def next_run(now: datetime, at: str) -> datetime:
    hh, mm = (int(x) for x in at.split(":"))
    target = now.replace(hour=hh, minute=mm, second=0, microsecond=0)
    return target if target > now else target + timedelta(days=1)


def main(argv=None) -> int:
    argv = sys.argv[1:] if argv is None else list(argv)
    if argv[:1] == ["relayout"]:
        return relayout_main(argv[1:])
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--config", required=True)
    mode = ap.add_mutually_exclusive_group(required=True)
    mode.add_argument("--once", action="store_true")
    mode.add_argument("--daemon", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--as-of", default=None, help="count the retention back from this date (default: today in schedule.zone)")
    ap.add_argument("--force-drop", action="store_true", help="delete even when that is more than max-drop-share of a table")
    a = ap.parse_args(argv)
    config = yaml.safe_load(Path(a.config).read_text(encoding="utf-8")) or {}
    as_of = date.fromisoformat(a.as_of) if a.as_of else None
    if a.once:
        return 1 if run(config, a.dry_run, as_of, a.force_drop) else 0
    schedule = config.get("schedule", {})
    zone = ZoneInfo(schedule.get("zone", "America/New_York"))
    while True:
        wake = next_run(datetime.now(zone), str(schedule.get("at", "02:30")))
        log(event="sleeping", until=wake)
        time.sleep(max(1.0, (wake - datetime.now(zone)).total_seconds()))
        run(config, a.dry_run, as_of, a.force_drop)


if __name__ == "__main__":
    sys.exit(main())
