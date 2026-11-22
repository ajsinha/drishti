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


"""Delta Lake maintenance, so a lake does not grow without bound. A Delta table is kept by Drishti Lake's ``lake maintain`` (LakeMaintain: retention,
relayout, compaction, checkpoint and vacuum, one set of options for every store, started through the loader's class path, through Delta Kernel;
no delta-rs). Drishti's server only reads the lake; this job does what readers cannot, on a schedule, for local lakes and lakes in object storage
(S3 and S3-compatible):

    retention   delete business dates older than the history window (keep-business-days), counted back from today in schedule.zone
                (or --as-of) in business days (Monday to Friday): today and that many business days before it are kept, never counted
                from the newest date in the table; a run that would delete more than max-drop-share (0.5) of a table's rows deletes
                nothing and reports the table failed, unless --force-drop
    compact     merge the small files each day's writes leave into files of target-file-mb; a table whose pack declares a layout keeps
                it: the dates that drifted from it (an append whose ids overlap the date's files) are rewritten sorted by id first
                (lake maintain --relayout), each file its own id range
    checkpoint  write a Delta checkpoint, so readers replay a short log
    vacuum      remove files no longer referenced, older than vacuum-hours (time travel reaches back that far)

    uv run --with pyarrow --with pyyaml python tools/lake/maintain.py --config deploy/lake-maintenance.yaml --once
    ... --daemon            run the file's schedules (lakes[].schedules: cron text or after-build); with none, every day at
                            schedule.at in schedule.zone (a container or service); a time missed while down runs once at start
    ... --dry-run           report what would happen (files and MB before -> after, dates retention drops, files vacuum removes)
    ... --tables trading/trade_endur,risk/var   only these tables (root|domain/table to name a lake)
    ... --force-foreign     also maintain tables Drishti's lake tool did not build (their publisher maintains them: a warning)
    ... --as-of 2026-09-30  count the retention back from this date instead of today
    ... --force-drop        delete even when that is more than max-drop-share of a table
    ... --signal URL        tell that server about each changed table (the data-load signal lake build sends)
    uv run ... maintain.py --history [--limit 50] [--json]     the last runs, from Admin -> Lakes and from here

--config defaults to <data>/lakes/lake-maintenance.yaml, the file Admin -> Lakes writes (docs/guides/OPERATIONS_GUIDE.md). Only
tables lake build built are maintained (its build record, tools/lake/ownership.py); each run takes the lake's lock exclusively (builds hold it shared), so it never
overlaps a build, and is kept in the history (<data>/lakes/maintenance-history.jsonl).

    uv run --with pyarrow --with pyyaml python tools/lake/maintain.py relayout --root data/delta --domain trading \\
        [--kind trade] [--dates 2026-09-28,2026-09-30 | --dates 2026-09-01..2026-09-30] [--force] [--dry-run]
                            lay the tables their pack lays out (tools/samplegen/layout.py) out again by id (lake maintain --relayout):
                            a date already in the layout is left as it is (unless --force), so running it again is harmless

Iceberg lakes (a lake with ``format: iceberg``, or tables with metadata/*.metadata.json under a local root; Delta is the default) are
maintained by the same Lake upkeep with ``--format iceberg`` (tools/lake/maintain_iceberg.py: the one retention rule, compaction,
snapshot expiry for vacuum-hours, which also removes the files no snapshot names, never under 72 hours), under the same floor, ownership rule, lock, schedules and history.

Every table of every configured domain is visited; one failing table is reported and the rest go on. A line of JSON
is printed per table and step (size and file counts before and after), for logs and monitoring."""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from datetime import date, datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from samplegen.layout import Layout, layouts_by_table  # noqa: E402

sys.path.insert(0, str(Path(__file__).resolve().parent))
import maintain_iceberg  # noqa: E402
import maintain_rows  # noqa: E402
import maintconfig  # noqa: E402
import maintschedule  # noqa: E402
import ownership  # noqa: E402

DEFAULTS = {"domains": ["*"], "keep-business-days": None, "max-drop-share": 0.5, "compact": True, "target-file-mb": 128, "checkpoint": True,
            "vacuum-hours": 168, "storage-options": {}}


def log(**event) -> None:
    print(json.dumps({"at": datetime.now().astimezone().isoformat(timespec="seconds"), **event}, default=str), flush=True)


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


LAKE_MAINTAIN = "com.ash.lake.load.LakeMaintain"


def lake_command(uri: str, conf: dict, today: date, dry_run: bool, force_drop: bool, floor_days: int, compact: bool,
                 data: Path | None = None, jar: str | None = None, java: str | None = None, relayout: bool = False, file_rows: int | None = None,
                 fmt: str = "delta") -> list[str]:
    """Drishti Lake's ``lake maintain`` for one Delta (or, with ``fmt="iceberg"``, one Iceberg) table (``<root>/<domain>/<table>``): retention (business dates, then rows), relayout
    (a laid-out table), compaction, checkpoint and vacuum, by Lake's options and in Lake's order."""
    import stores
    root, name = uri.rsplit("/", 2)[0], "/".join(uri.rstrip("/").split("/")[-2:])
    cmd = stores.loader_command(data or Path(root) / "_drishti-lake", jar, java, main=LAKE_MAINTAIN) + [
        "--lake", root, "--format", fmt, "--table", name, "--as-of", today.isoformat(), "--json"]
    if conf.get("keep-business-days"):
        cmd += ["--keep-days", str(int(conf["keep-business-days"])), "--max-drop-share", str(conf.get("max-drop-share", 0.5))]
    for key in ("ttl", "on", "max-rows"):     # row retention (maintain_rows.py): the same rule as the memory lake's
        if (conf.get("row-retention") or {}).get(key):
            cmd += [f"--{key}", str(conf["row-retention"][key])]
    if relayout:
        cmd += ["--relayout"] + (["--file-rows", str(int(file_rows))] if file_rows else [])
    if compact:
        cmd += ["--compact", "--target-file-mb", str(int(conf.get("target-file-mb", 128)))]
    if conf.get("checkpoint"):
        cmd += ["--checkpoint"]
    if conf.get("vacuum-hours") is not None:
        cmd += ["--vacuum-hours", str(int(conf["vacuum-hours"]))]
    if floor_days:
        cmd += ["--floor-days", str(floor_days)]
    if force_drop:
        cmd += ["--force-drop"]
    if dry_run:
        cmd += ["--dry-run"]
    return cmd


def maintain(uri: str, conf: dict, today: date, dry_run: bool, force_drop: bool = False, floor_days: int = 0,
             runner=None, data: Path | None = None, fmt: str = "delta") -> dict:
    """One Delta (or, with ``fmt="iceberg"``, one Iceberg) table maintained (or a dry run) by Drishti Lake's ``lake maintain`` (one set of options for every store): retention, a
    laid-out table's relayout (the dates that drifted from its id order rewritten sorted), compaction, checkpoint and vacuum. The result
    in lake maintain's form (before, after or estimate: files, mb, dates, latestDate, latestRows; retention, row-retention, compact,
    checkpoint, vacuum). ``runner(cmd)`` gives the JSON line (tests). Raises RuntimeError on failure."""
    import maintain_iceberg
    opts = conf.get("storage-options") or {}
    compact = bool(conf.get("compact"))
    lay = layout_of(uri) if compact and fmt == "delta" else None
    cmd = lake_command(uri, conf, today, dry_run, force_drop, floor_days, compact, data, relayout=lay is not None,
                       file_rows=lay.file_rows if lay is not None else None, fmt=fmt)
    old = {k: os.environ.get(k) for k in opts}
    os.environ.update({k: str(v) for k, v in opts.items()})     # an S3 lake: Lake's runtime reads the AWS_* names
    try:
        r = json.loads(runner(cmd) if runner is not None else maintain_iceberg.json_line(cmd, "lake maintain"))
    finally:
        for k, v in old.items():
            os.environ.pop(k, None) if v is None else os.environ.__setitem__(k, v)
    if r.get("error"):
        raise RuntimeError(r["error"])
    done: dict = {"table": uri, "before": r.get("before") or {}}
    keep = r.get("keepDays") or {}
    if keep:
        done["retention"] = {"cutoff": keep.get("from"), "dates": keep.get("dates") or [], "num_deleted_rows": keep.get("rows", 0)}
        if dry_run:
            done["retention"]["would_remove_files"] = len(keep.get("dates") or [])
    if "rowRetention" in r:
        days = r["rowRetention"] or []
        done["row-retention"] = {"dropped": sum(int(d.get("dropped", 0)) for d in days), "dates": days, "dry_run": dry_run}
    if "relayout" in r or "compact" in r:
        done["compact"] = {"dates": (r.get("compact") or {}).get("dates", [])}
        if "relayout" in r:
            done["compact"]["relaid-dates"] = (r.get("relayout") or {}).get("dates", [])
    if "checkpoint" in r:
        done["checkpoint"] = (r["checkpoint"] or {}).get("version")
    if "vacuum" in r:
        v = r["vacuum"] or {}
        done["vacuum"] = {"files": v.get("files", 0), "dry_run": dry_run, "hours": v.get("hours"), "first": (v.get("first") or [])[:5],
                          "mb": round((v.get("bytes") or 0) / 1_048_576, 2)}
    if dry_run:      # what the table will hold: the dates retention leaves, one file a date when the run compacts (an estimate)
        est = dict(r.get("after") or {})
        est["dates"] = max(0, int(est.get("dates") or 0) - len(keep.get("dates") or []))
        if isinstance(est.get("rows"), int) and est["rows"] >= 0:
            est["rows"] = max(0, est["rows"] - int(keep.get("rows") or 0))
        if compact:
            est["files"] = est["dates"]
        done["estimate"] = {**est, "estimate": True}
    else:
        done["after"] = r.get("after") or {}
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
        if fmt == "files":          # a files store (<root>/<domain>/<date>/<kind>.jsonl): only its rows' retention applies (maintain_rows.py)
            for name in maintain_rows.files_tables(root, list(base["domains"])):
                if want is None or name in want:
                    out["tables"].append(_files_one(root, name, lake, base, dry_run, emit, quiet, out))
            continue
        try:
            opts = base.get("storage-options") or {}
            found = [] if fmt == "iceberg" else tables(root, list(base["domains"]), opts)
            named = maintain_iceberg.catalog_tables(lake, want) if fmt == "iceberg" else None
            iceberg = set(named) if named is not None else set() if fmt == "delta" or (fmt == "auto" and "://" in root and not root.startswith("file://")) \
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
        lake = {**lake, "_lake-file": maintconfig.lake_file_settings(root, config.get("_lakes"))}   # the tables' own settings
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
    # the table's settings: the maintenance file's lake defaults, its own entry there, then its lake file's (DATA_LAKE_GUIDE.md: everything
    # specific to a table lives in the lake file), and the domain's retention floor over all of them
    own, said = maintconfig.from_lake_entry((lake.get("_lake-file") or {}).get(name) or {})
    if own:
        res["lake-file"] = sorted(k for k in own if k != "time-travel-hours")
    for n in said:
        if not quiet:
            log(event="note", table=uri, note=n)
    conf, floor = maintconfig.retention_floor(name, maintconfig.with_time_travel({**base, **((lake.get("tables") or {}).get(name) or {}), **own}),
                                              lake.get("retain-years"))
    if floor:
        res["retention-floor"] = floor
        if not quiet:
            log(event="warning", table=uri, warning="; ".join(floor))
    emit("start", res)
    try:
        if iceberg:
            res.update(maintain_iceberg.maintain(uri, conf, day, dry_run, force_drop, maintconfig.floor_days(name, lake.get("retain-years"))))
        else:
            res.update(maintain(uri, conf, day, dry_run, force_drop, maintconfig.floor_days(name, lake.get("retain-years"))))
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


def _files_one(root, name, lake, base, dry_run, emit, quiet, out) -> dict:
    """One table of a files store: its row retention applied (the table's own settings over the lake's); no other upkeep applies to day files."""
    res: dict = {"root": root, "table": f"{root.rstrip('/')}/{name}", "name": name, "format": "files"}
    conf = {**base, **((lake.get("tables") or {}).get(name) or {})}
    emit("start", res)
    try:
        rr = maintain_rows.clean(conf)
        if rr:
            res["row-retention"] = maintain_rows.apply(root, "files", name, rr, dry_run)
        else:
            res["skipped"] = "no row retention set for this table"
        if not quiet:
            log(event="maintained", dry_run=dry_run, **{k: v for k, v in res.items() if k not in ("root", "name")})
    except Exception as e:  # noqa: BLE001 - one table must not stop the others
        out["failures"] += 1
        res["error"] = f"{type(e).__name__}: {e}"
        if not quiet:
            log(event="failed", table=res["table"], error=res["error"])
    res["dryRun"] = dry_run
    emit("done", res)
    return res


def changed(res: dict) -> bool:
    """Whether a maintained table changed in a way readers should hear of (dates dropped, files rewritten)."""
    if res.get("error") or res.get("skipped") or res.get("dryRun"):
        return False
    b, a = res.get("before") or {}, res.get("after") or {}
    ret = res.get("retention") or {}
    return bool(ret.get("dates") or ret.get("removed") or (res.get("row-retention") or {}).get("dropped")) or b.get("files") != a.get("files") or b.get("mb") != a.get("mb")


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


def relayout_main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(prog="maintain.py relayout", description="lay tables out again by their pack's layout (lake maintain --relayout)")
    ap.add_argument("--root", default=(__import__("os").environ.get("DRISHTI_DATA_DIR") or str(__import__("pathlib").Path(__file__).resolve().parents[2] / "data")) + "/delta")
    ap.add_argument("--domain", required=True)
    ap.add_argument("--kind", default=None, help="one kind (default: every kind with a layout in the domain)")
    ap.add_argument("--dates", default=None, help="only these business dates: 2026-09-28,2026-09-30 or 2026-09-01..2026-09-30")
    ap.add_argument("--packs", default=None, help="the packs folder whose pack.yaml files declare the layouts (default: this repository's)")
    ap.add_argument("--force", action="store_true", help="rewrite dates already in the layout too (another codec, smaller row groups)")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args(argv)
    tables = layouts_by_table(a.domain, Path(a.packs)) if a.packs else layouts_by_table(a.domain)
    if a.kind:
        tables = {t: kl for t, kl in tables.items() if kl[0] == a.kind}
        if not tables:
            log(event="failed", domain=a.domain, kind=a.kind, error="no layout declared for this kind")
            return 1
    import maintain_iceberg
    failures = 0
    for table, (_kind, lay) in sorted(tables.items()):
        uri = str(Path(a.root) / a.domain / table)
        if not (Path(uri) / "_delta_log").is_dir() and "://" not in uri:
            continue                                          # a table the lake does not have (yet)
        try:
            import stores
            cmd = stores.loader_command(Path(a.root) / "_drishti-lake", None, None, main=LAKE_MAINTAIN) + [
                "--lake", a.root, "--format", "delta", "--table", f"{a.domain}/{table}", "--relayout", "--file-rows", str(lay.file_rows), "--json"] + (["--dates", a.dates] if a.dates else []) \
                + (["--force"] if a.force else []) + (["--dry-run"] if a.dry_run else [])
            r = json.loads(maintain_iceberg.json_line(cmd, "lake maintain"))
            log(event="relayout", dry_run=a.dry_run, table=uri, rewritten=(r.get("relayout") or {}).get("dates", []))
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
