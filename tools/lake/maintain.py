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
    ... --daemon            run the file's schedules (lakes[].schedules: cron text or after-build); with none, every day at
                            schedule.at in schedule.zone (a container or service); a time missed while down runs once at start
    ... --dry-run           report what would happen (files and MB before -> after, dates retention drops, files vacuum removes)
    ... --tables trading/trade_endur,risk/var   only these tables (root|domain/table to name a lake)
    ... --force-foreign     also maintain tables Drishti's lake tool did not build (their publisher maintains them: a warning)
    ... --as-of 2026-09-30  count the retention back from this date instead of today
    ... --force-drop        delete even when that is more than max-drop-share of a table
    ... --signal URL        tell that server about each changed table (the data-load signal lake build sends)
    uv run ... maintain.py --history [--limit 50] [--json]     the last runs, from Admin -> Lakes and from here

--config defaults to <data>/lakes/lake-maintenance.yaml, the file Admin -> Lakes writes (docs/admin/LAKE_MAINTENANCE.md). Only
tables lake build built are maintained (its build record, tools/lake/ownership.py); each run takes the lake's lock exclusively (builds hold it shared), so it never
overlaps a build, and is kept in the history (<data>/lakes/maintenance-history.jsonl).

    uv run --with deltalake --with pyarrow --with pyyaml python tools/lake/maintain.py relayout --root data/delta --domain trading \\
        [--kind trade] [--dates 2026-09-28,2026-09-30 | --dates 2026-09-01..2026-09-30] [--force] [--dry-run]
                            rewrite tables into the layout their pack declares (tools/samplegen/layout.py), one business
                            date at a time; a date already in the layout is left as it is, so running it again is harmless

Iceberg lakes (a lake with ``format: iceberg``, or tables with metadata/*.metadata.json under a local root; Delta is the default) are
maintained by Drishti's Iceberg maintenance instead (tools/lake/maintain_iceberg.py: retention, relayout, snapshot expiry for vacuum-hours,
and orphan files older than orphan-hours, 72 at least), under the same floor, ownership rule, lock, schedules and history.

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
from samplegen.layout import Layout, column_name, keep_stats_small, layouts_by_table, promote, write_partition  # noqa: E402
from samplegen.layout import stats_columns as stats_columns_of  # noqa: E402

sys.path.insert(0, str(Path(__file__).resolve().parent))
import maintain_iceberg  # noqa: E402
import maintconfig  # noqa: E402
import maintschedule  # noqa: E402
import ownership  # noqa: E402

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
    return stats_of(add_actions(dt))


def stats_of(actions: list[dict]) -> dict:
    """Files and MB, the business dates, and the latest date with its rows (for the data-load signal)."""
    dates = sorted({str(a.get("partition.business_date")) for a in actions if a.get("partition.business_date") is not None})
    latest = dates[-1] if dates else None
    rows = [a.get("num_records") for a in actions if latest and str(a.get("partition.business_date")) == latest]
    return {"files": len(actions), "mb": round(sum(a["size_bytes"] for a in actions) / 1_048_576, 2), "dates": len(dates),
            "latestDate": latest, "latestRows": sum(r for r in rows if r is not None) if rows else None}


def estimate(actions: list[dict], conf: dict, cutoff: date | None, lay: Layout | None) -> dict:
    """What a run would leave, for a dry run: the dates retention drops are gone and each remaining date's small files are merged into
    files of target-file-mb (a laid-out table: files of the layout's rows). An estimate: compression changes the sizes a little."""
    keep = [a for a in actions if cutoff is None or str(a.get("partition.business_date", "9999")) >= cutoff.isoformat()]
    if not conf.get("compact"):
        return {**stats_of(keep), "estimate": True}
    target = int(conf.get("target-file-mb", 128)) * 1_048_576
    groups: dict[tuple, list[dict]] = {}
    for a in keep:
        groups.setdefault(tuple(sorted((k, str(v)) for k, v in a.items() if k.startswith("partition."))), []).append(a)
    files = 0
    for g in groups.values():
        if lay is not None and all(a.get("num_records") is not None for a in g):
            files += max(1, -(-sum(a["num_records"] for a in g) // max(1, lay.file_rows)))
        else:
            small = [a for a in g if a["size_bytes"] < target]
            files += (len(g) - len(small)) + (max(1, -(-sum(a["size_bytes"] for a in small) // target)) if small else 0)
    out = stats_of(keep)
    out.update(files=files, estimate=True)
    return out


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
    actions = add_actions(dt)
    before = stats_of(actions)
    done: dict = {"table": uri, "before": before}
    keep = conf.get("keep-business-days")
    cutoff = business_cutoff(today, int(keep)) if keep else None
    lay = layout_of(uri)
    if cutoff:
        dropping, total, unit = retention_share(actions, cutoff)
        share = float(conf.get("max-drop-share", 0.5))
        if total and dropping > share * total and not force_drop:
            raise RuntimeError(f"retention would delete {dropping:,} of {total:,} {unit} (more than max-drop-share {share}), the business dates "
                               f"before {cutoff}, counted back from {today}; nothing was deleted: check the dates, or run with --force-drop")
        old = [a for a in actions if str(a.get("partition.business_date", "9999")) < cutoff.isoformat()]
        dropped = sorted({str(a.get("partition.business_date")) for a in old})
        if dry_run:
            done["retention"] = {"cutoff": cutoff, "would_remove_files": len(old), "dates": dropped}
        else:
            done["retention"] = {"cutoff": cutoff, "dates": dropped, **{k: v for k, v in dt.delete(f"business_date < '{cutoff.isoformat()}'").items()
                                                                        if k in ("num_deleted_rows", "num_removed_files")}}
    if dry_run:
        done["estimate"] = estimate(actions, conf, cutoff, lay)
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
        done["vacuum"] = {"files": len(removed), "dry_run": dry_run, "hours": int(hours), "first": sorted(removed)[:5]}
        if dry_run and "://" not in uri:
            paths = [Path(f.removeprefix("file://")) if Path(f.removeprefix("file://")).is_absolute() else Path(uri) / f for f in removed]
            done["vacuum"]["mb"] = round(sum(p.stat().st_size for p in paths if p.is_file()) / 1_048_576, 2)
    done["after"] = stats(DeltaTable(uri, storage_options=opts or None))
    return done


def layout_of(uri: str) -> Layout | None:
    """The layout a pack declares for the table at <root>/<domain>/<table>, if any: found by the table's folder (trade_endur
    is the trade layout of the connector whose table it is), never by taking the folder for a kind."""
    parts = uri.rstrip("/").split("/")
    if len(parts) < 2:
        return None
    try:
        found = layouts_by_table(parts[-2]).get(parts[-1])
        return found[1] if found else None
    except Exception:  # noqa: BLE001 - no packs folder (a lake maintained elsewhere): plain compaction
        return None


def run(config: dict, dry_run: bool, today: date | None = None, force_drop: bool = False, **kw) -> int:
    """One pass over every configured lake; returns the number of tables that failed (see :func:`run_detailed`)."""
    return run_detailed(config, dry_run, today, force_drop, **kw)["failures"]


def table_name(uri: str) -> str:
    """<domain>/<table> of a table URI."""
    parts = uri.rstrip("/").split("/")
    return "/".join(parts[-2:])


def wanted_tables(spec, root: str) -> set[str] | None:
    """The tables of ``root`` a run is limited to: None for all; ``spec`` is {root: [domain/table]} (Admin -> Lakes) or a list of
    "domain/table" and "root|domain/table" names (--tables)."""
    if spec is None:
        return None
    if isinstance(spec, dict):
        for r, names in spec.items():
            if maintconfig.same_root(str(r), root):
                return set(names)
        return set()
    out = set()
    for name in spec:
        r, _, t = name.rpartition("|")
        if not r or maintconfig.same_root(r, root):
            out.add(t)
    return out


def run_detailed(config: dict, dry_run: bool, today: date | None = None, force_drop: bool = False, tables_spec=None,
                 force_foreign: bool = False, cancel=None, on_event=None, quiet: bool = False) -> dict:
    """One pass: every table of every configured lake (or only ``tables_spec``), each with its own settings (lakes[].tables over the
    lake's), under the lake's lock (exclusive; lake build holds it shared, with its own table's lock exclusive), only tables Drishti's lake tool built
    unless ``force_foreign``. ``cancel()`` true stops before the next table; ``on_event(event, data)`` hears each table start and end.
    Returns {"tables": [result], "failures", "cancelled"}; a result has table, root, name, before/after or error, skipped."""
    out: dict = {"tables": [], "failures": 0, "cancelled": False}
    emit = on_event or (lambda *_: None)
    zone = ZoneInfo(config.get("schedule", {}).get("zone", "America/New_York"))
    day = today or datetime.now(zone).date()
    for lake in config.get("lakes", []):
        root = str(lake["root"])
        base = {**DEFAULTS, **{k: v for k, v in lake.items() if k not in ("tables", "schedules")}}
        want = wanted_tables(tables_spec, root)
        if want is not None and not want:
            continue
        fmt = str(lake.get("format") or "auto").lower()
        try:
            opts = base.get("storage-options") or {}
            found = [] if fmt == "iceberg" else tables(root, list(base["domains"]), opts)
            iceberg = set() if fmt == "delta" or (fmt == "auto" and "://" in root and not root.startswith("file://")) \
                else set(maintain_iceberg.tables(root, list(base["domains"]), opts))
            found = sorted(set(found) | iceberg)
        except Exception as e:  # noqa: BLE001 - one lake must not stop the others
            out["failures"] += 1
            out["tables"].append({"root": root, "table": root, "name": "*", "error": f"{type(e).__name__}: {e}"})
            continue
        found = [u for u in found if want is None or table_name(u) in want]
        for name in sorted((want or set()) - {table_name(u) for u in found}):
            out["failures"] += 1
            out["tables"].append({"root": root, "table": f"{root.rstrip('/')}/{name}", "name": name, "error": "no such table under this lake"})
        owned = ownership.built(root)
        lock = None
        if not dry_run and "://" not in root and Path(root).is_dir():
            import build
            lock = build.LakeLock(Path(root))
            try:
                lock.__enter__()
            except build.Locked as e:
                for uri in found:
                    out["failures"] += 1
                    out["tables"].append({"root": root, "table": uri, "name": table_name(uri), "error": f"{e}: try again when that run ends"})
                continue
        try:
            for uri in found:
                if cancel and cancel():
                    out["cancelled"] = True
                    break
                out["tables"].append(_one(uri, root, lake, base, owned, want, day, dry_run, force_drop, force_foreign, emit, quiet, out,
                                          uri in iceberg))
        finally:
            if lock is not None:
                lock.__exit__(None, None, None)
        if out["cancelled"]:
            break
    return out


def _one(uri, root, lake, base, owned, want, day, dry_run, force_drop, force_foreign, emit, quiet, out, iceberg: bool = False) -> dict:
    name = table_name(uri)
    res: dict = {"root": root, "table": uri, "name": name, "owner": owned.get(name), "format": "iceberg" if iceberg else "delta"}
    if name not in owned and not force_foreign:
        res["skipped"] = ownership.FOREIGN
        if want is not None:                       # named on purpose: a refusal, not a quiet skip
            res["error"] = "refused: " + ownership.FOREIGN + "; --force-foreign maintains it anyway"
            out["failures"] += 1
        if not quiet:
            log(event="refused" if want is not None else "skipped", table=uri, reason=ownership.FOREIGN)
        emit("skipped", res)
        return res
    if name not in owned:
        res["forced"] = True
        log(event="warning", table=uri, warning="maintaining a table Drishti did not build (--force-foreign): its publisher may rewrite "
                                                 "or vacuum it too, and a run here may break what it expects")
    conf, floor = maintconfig.retention_floor(name, {**base, **((lake.get("tables") or {}).get(name) or {})}, lake.get("retain-years"))
    if floor:
        res["retention-floor"] = floor
        if not quiet:
            log(event="warning", table=uri, warning="; ".join(floor))
    emit("start", res)
    try:
        if iceberg:
            res.update(maintain_iceberg.maintain(uri, conf, day, dry_run, force_drop, maintconfig.floor_days(name, lake.get("retain-years"))))
        else:
            res.update(maintain(uri, conf, day, dry_run, force_drop))
        if not quiet:
            log(event="maintained", dry_run=dry_run, **{k: v for k, v in res.items() if k not in ("root", "name", "owner")})
    except Exception as e:  # noqa: BLE001 - one table must not stop the others
        out["failures"] += 1
        res["error"] = f"{type(e).__name__}: {e}"
        if not quiet:
            log(event="failed", table=uri, error=res["error"])
    res["dryRun"] = dry_run
    emit("done", res)
    return res


def changed(res: dict) -> bool:
    """Whether a maintained table changed in a way readers should hear of (dates dropped, files rewritten)."""
    if res.get("error") or res.get("skipped") or res.get("dryRun"):
        return False
    b, a = res.get("before") or {}, res.get("after") or {}
    ret = res.get("retention") or {}
    return bool(ret.get("dates") or ret.get("removed")) or b.get("files") != a.get("files") or b.get("mb") != a.get("mb")


def signal_bodies(results: list[dict], run_id: str) -> list[tuple[str, str, dict]]:
    """(pack, body) of the data-load signal for each changed table built for a pack: its latest business date, as lake build sends."""
    out = []
    for r in results:
        own = r.get("owner") or {}
        after = r.get("after") or {}
        if changed(r) and own.get("pack") and after.get("latestDate"):
            out.append((own["pack"], r["name"], {"kind": own.get("kind") or r["name"].split("/")[-1], "businessDate": after["latestDate"], "status": "ready",
                                      "rows": after.get("latestRows") or 0, "rejected": 0, "batchId": run_id, "source": "lake maintain"}))
    return out


def outcome(report: dict) -> str:
    if report.get("cancelled"):
        return "cancelled"
    done = [t for t in report["tables"] if not t.get("skipped") or t.get("error")]
    bad = [t for t in done if t.get("error")]
    return "failed" if bad and len(bad) == len(done) else "partial" if bad else "ok"


def history_entry(report: dict, dry_run: bool, by: str, schedule: str | None = None, note: str | None = None, started: str | None = None) -> dict:
    keep = ("name", "root", "format", "before", "after", "estimate", "retention", "compact", "checkpoint", "vacuum", "orphans", "floor", "error",
            "skipped", "forced", "signal")
    return {"at": started or maintconfig.now_iso(), "finished": maintconfig.now_iso(), "by": by, "schedule": schedule, "dryRun": dry_run,
            "outcome": outcome(report), "note": note, "tables": [{k: v for k, v in t.items() if k in keep} for t in report["tables"]]}


def in_layout(actions: list[dict], lay: Layout, columns: set[str]) -> bool:
    """Whether a business date's files already follow the layout: every promoted column present with statistics, no
    file over file-rows and, sorted by id, contiguous disjoint id ranges with only the last file short."""
    if not actions or any(column_name(p) not in columns for p in lay.columns):
        return False
    partitioned = {k.removeprefix("partition.") for a in actions for k in a if k.startswith("partition.")}   # no statistics: one value a folder
    if any(a.get(f"null_count.{column_name(p)}") is None for a in actions for p in lay.columns if column_name(p) not in partitioned):
        return False
    if any(a["num_records"] > lay.file_rows for a in actions):
        return False
    if lay.sort_by != "id":
        return True
    # a table partitioned past the date (a trade table by PRODUCT_TYPE) is checked in each of its partitions: the layout's
    # files of file-rows are cut across them, so there the files are in id order but need not be full
    groups: dict[tuple, list[dict]] = {}
    for a in actions:
        groups.setdefault(tuple(sorted((k, str(v)) for k, v in a.items() if k.startswith("partition.") and k != "partition.business_date")), []).append(a)
    full = len(groups) == 1 and not next(iter(groups))
    for group in groups.values():
        files = sorted(group, key=lambda a: str(a.get("min.id")))
        if not all(a.get("min.id") is not None and a.get("max.id") is not None for a in files):
            return False
        if not all(files[k]["max.id"] < files[k + 1]["min.id"] and (not full or files[k]["num_records"] == lay.file_rows) for k in range(len(files) - 1)):
            return False
    return True


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
    ap.add_argument("--root", default=__import__("os").environ.get("DRISHTI_DATA_DIR", "data") + "/delta")
    ap.add_argument("--domain", required=True)
    ap.add_argument("--kind", default=None, help="one kind (default: every kind with a layout in the domain)")
    ap.add_argument("--dates", default=None, help="only these business dates: 2026-09-28,2026-09-30 or 2026-09-01..2026-09-30")
    ap.add_argument("--packs", default=None, help="the packs folder whose pack.yaml files declare the layouts (default: this repository's)")
    ap.add_argument("--force", action="store_true", help="rewrite dates already in the layout too")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args(argv)
    tables = layouts_by_table(a.domain, Path(a.packs)) if a.packs else layouts_by_table(a.domain)
    if a.kind:
        tables = {t: kl for t, kl in tables.items() if kl[0] == a.kind}
        if not tables:
            log(event="failed", domain=a.domain, kind=a.kind, error="no layout declared for this kind")
            return 1
    failures = 0
    for table, (_kind, lay) in sorted(tables.items()):
        uri = str(Path(a.root) / a.domain / table)
        if not (Path(uri) / "_delta_log").is_dir() and "://" not in uri:
            continue                                          # a table the lake does not have (yet)
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


def post_signals(url: str, bodies: list[tuple[str, str, dict]], token: str | None, user: str | None) -> list[dict]:
    """POST /api/v1/packs/{pack}/loads for each changed table (lake build's signal, same headers)."""
    import urllib.error
    import urllib.request
    out = []
    for pack, table, body in bodies:
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        elif user:
            headers["X-Drishti-User"] = user
        req = urllib.request.Request(url.rstrip("/") + f"/api/v1/packs/{urllib.request.quote(pack, safe='')}/loads",
                                     data=json.dumps(body).encode(), headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=30) as r:     # noqa: S310 - the operator's own --signal URL
                out.append({"pack": pack, "table": table, "kind": body["kind"], "date": body["businessDate"], "ok": True, "status": r.status})
        except urllib.error.HTTPError as e:
            out.append({"pack": pack, "table": table, "kind": body["kind"], "ok": False, "status": e.code, "error": e.read().decode("utf-8", "replace")[:300]})
        except (urllib.error.URLError, OSError) as e:
            out.append({"pack": pack, "table": table, "kind": body["kind"], "ok": False, "error": str(getattr(e, "reason", e))})
    return out


def once(config: dict, a, tables_spec=None, by: str | None = None, schedule: str | None = None, note: str | None = None) -> int:
    """One run from the command line or the daemon: run, signal, keep it in the history; returns the failures."""
    started = maintconfig.now_iso()
    as_of = date.fromisoformat(a.as_of) if a.as_of else None
    report = run_detailed(config, a.dry_run, as_of, a.force_drop, tables_spec=tables_spec, force_foreign=a.force_foreign)
    if a.signal and not a.dry_run:
        token = Path(a.token_file).read_text(encoding="utf-8").strip() if a.token_file else __import__("os").environ.get("DRISHTI_TOKEN")
        sent = post_signals(a.signal, signal_bodies(report["tables"], "maintain-" + started), token, a.user or __import__("os").environ.get("DRISHTI_USER"))
        for s in sent:
            log(event="signal", **s)
        for t in report["tables"]:
            t["signal"] = [s for s in sent if s.get("table") == t["name"]] or None
    entry = history_entry(report, a.dry_run, by or a.by, schedule, note, started)
    try:
        maintconfig.record(maintconfig.history_path(config), entry)
    except OSError as e:
        log(event="warning", warning=f"the history could not be written: {e}")
    log(event="run", outcome=entry["outcome"], tables=len(report["tables"]), failures=report["failures"])
    return report["failures"]


def daemon(config_file: Path, a) -> int:
    """The file's schedules, ticking every 30 seconds (the file is read again each tick, so Admin -> Lakes changes apply), or, with
    no schedules in it, every table once a day at schedule.at."""
    sched = None
    while True:
        config = maintconfig.load(config_file)
        zone = ZoneInfo(config.get("schedule", {}).get("zone", "America/New_York"))
        all_schedules = maintconfig.schedules(config)
        if not all_schedules:
            wake = next_run(datetime.now(zone), str(config.get("schedule", {}).get("at", "02:30")))
            log(event="sleeping", until=wake)
            time.sleep(max(1.0, (wake - datetime.now(zone)).total_seconds()))
            once(config, a, by="daemon", schedule="daily " + str(config.get("schedule", {}).get("at", "02:30")))
            continue
        sched = sched or maintschedule.Scheduler(maintconfig.state_path(config))
        now = datetime.now(zone)
        built = {str(lake.get("root")): {k: v.get("lastBuild") for k, v in ownership.built(str(lake.get("root"))).items()}
                 for lake in config.get("lakes") or []}
        for s, why in sched.due(all_schedules, now, built):
            once(config, a, tables_spec={s["root"]: list(s.get("tables") or [])}, by=s.get("by") or "daemon", schedule=s.get("name"),
                 note=why if why.startswith("catch-up") else None)
            sched.ran(s, now, built)
        time.sleep(30)


def main(argv=None) -> int:
    argv = sys.argv[1:] if argv is None else list(argv)
    if argv[:1] == ["relayout"]:
        return relayout_main(argv[1:])
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--config", default=None, help="the lake-maintenance YAML (default <data>/lakes/lake-maintenance.yaml, the file Admin -> Lakes writes)")
    mode = ap.add_mutually_exclusive_group(required=True)
    mode.add_argument("--once", action="store_true")
    mode.add_argument("--daemon", action="store_true")
    mode.add_argument("--history", action="store_true", help="print the last runs (Admin -> Lakes and command line), newest first")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--tables", default=None, help="only these tables: domain/table[,domain/table] (root|domain/table names a lake)")
    ap.add_argument("--force-foreign", action="store_true",
                    help="also maintain tables Drishti's lake tool did not build; their publisher maintains them, so this can break it")
    ap.add_argument("--as-of", default=None, help="count the retention back from this date (default: today in schedule.zone)")
    ap.add_argument("--force-drop", action="store_true", help="delete even when that is more than max-drop-share of a table")
    ap.add_argument("--signal", metavar="URL", help="tell this server about each changed table (POST /api/v1/packs/{pack}/loads)")
    ap.add_argument("--token-file", help="the API token for --signal (default: DRISHTI_TOKEN); never printed")
    ap.add_argument("--user", help="the user name to send to a server with sign-in off (default: DRISHTI_USER)")
    ap.add_argument("--by", default=__import__("os").environ.get("USER") or "cli", help="who the history says ran it (default: $USER)")
    ap.add_argument("--limit", type=int, default=50, help="--history: how many runs (default 50)")
    ap.add_argument("--json", action="store_true", help="--history: as JSON")
    a = ap.parse_args(argv)
    config_file = Path(a.config) if a.config else maintconfig.default_path()
    config = maintconfig.load(config_file)
    if a.history:
        rows = maintconfig.history(maintconfig.history_path(config), a.limit)
        print(json.dumps(rows, indent=2, default=str) if a.json else maintconfig.history_text(rows))
        return 0
    if not config.get("lakes"):
        print(f"{config_file}: no lakes: to maintain (write the file, or set tables up in Admin -> Lakes)", file=sys.stderr)
        return 1
    if a.force_foreign:
        print("WARNING: --force-foreign maintains tables another system published; compaction and vacuum can break what that "
              "publisher expects. Prefer letting the publisher maintain them.", file=sys.stderr)
    if a.once:
        spec = [t.strip() for t in a.tables.split(",") if t.strip()] if a.tables else None
        return 1 if once(config, a, spec) else 0
    return daemon(config_file, a)


if __name__ == "__main__":
    sys.exit(main())
