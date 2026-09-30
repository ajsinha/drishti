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

    retention   delete business dates older than the history window (keep-business-days)
    compact     merge the small files each day's writes leave into files of target-file-mb
    checkpoint  write a Delta checkpoint, so readers replay a short log
    vacuum      remove files no longer referenced, older than vacuum-hours (time travel reaches back that far)

    uv run --with deltalake --with pyarrow --with pyyaml python tools/lake/maintain.py --config deploy/lake-maintenance.yaml --once
    ... --daemon            run every day at schedule.at in schedule.zone (a container or service)
    ... --dry-run           report what would happen, change nothing

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

DEFAULTS = {"domains": ["*"], "keep-business-days": None, "compact": True, "target-file-mb": 128, "checkpoint": True,
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


def maintain(uri: str, conf: dict, today: date, dry_run: bool) -> dict:
    from deltalake import DeltaTable

    opts = conf.get("storage-options") or {}
    dt = DeltaTable(uri, storage_options=opts or None)
    before = stats(dt)
    done: dict = {"table": uri, "before": before}
    keep = conf.get("keep-business-days")
    if keep:
        cutoff = business_cutoff(today, int(keep))
        if dry_run:
            old = [a for a in add_actions(dt) if str(a.get("partition.business_date", "9999")) < cutoff.isoformat()]
            done["retention"] = {"cutoff": cutoff, "would_remove_files": len(old)}
        else:
            done["retention"] = {"cutoff": cutoff, **{k: v for k, v in dt.delete(f"business_date < '{cutoff.isoformat()}'").items()
                                                      if k in ("num_deleted_rows", "num_removed_files")}}
    if conf.get("compact") and not dry_run:
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


def run(config: dict, dry_run: bool, today: date | None = None) -> int:
    """One pass over every configured lake; returns the number of tables that failed."""
    failures = 0
    for lake in config.get("lakes", []):
        conf = {**DEFAULTS, **lake}
        zone = ZoneInfo(config.get("schedule", {}).get("zone", "America/New_York"))
        day = today or datetime.now(zone).date()
        for uri in tables(str(lake["root"]), list(conf["domains"]), conf.get("storage-options") or {}):
            try:
                log(event="maintained", dry_run=dry_run, **maintain(uri, conf, day, dry_run))
            except Exception as e:  # noqa: BLE001 - one table must not stop the others
                failures += 1
                log(event="failed", table=uri, error=f"{type(e).__name__}: {e}")
    return failures


def next_run(now: datetime, at: str) -> datetime:
    hh, mm = (int(x) for x in at.split(":"))
    target = now.replace(hour=hh, minute=mm, second=0, microsecond=0)
    return target if target > now else target + timedelta(days=1)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--config", required=True)
    mode = ap.add_mutually_exclusive_group(required=True)
    mode.add_argument("--once", action="store_true")
    mode.add_argument("--daemon", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args(argv)
    config = yaml.safe_load(Path(a.config).read_text(encoding="utf-8")) or {}
    if a.once:
        return 1 if run(config, a.dry_run) else 0
    schedule = config.get("schedule", {})
    zone = ZoneInfo(schedule.get("zone", "America/New_York"))
    while True:
        wake = next_run(datetime.now(zone), str(schedule.get("at", "02:30")))
        log(event="sleeping", until=wake)
        time.sleep(max(1.0, (wake - datetime.now(zone)).total_seconds()))
        run(config, a.dry_run)


if __name__ == "__main__":
    sys.exit(main())
