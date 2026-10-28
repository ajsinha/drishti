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

"""Iceberg tables in ``lake maintain`` and Admin -> Lakes (docs/admin/LAKE_MAINTENANCE.md, "Iceberg lakes"). Delta stays the default;
a lake whose ``format: iceberg`` (or whose tables are Iceberg tables: ``<root>/<domain>/<table>/metadata/*.metadata.json``) is maintained
by Drishti's own Iceberg maintenance (IcebergMaintenance in the server's exec jar, engine native), each step one Iceberg commit:

    retention   keep-business-days: the table's newest N business dates are kept (a metadata-only delete of older days), refused past
                max-drop-share unless --force-drop
    compact     the days that drifted from the layout are rewritten sorted by id (relayout)
    vacuum      vacuum-hours: snapshots older than that are expired and the files only they used deleted (time travel reaches that far)
    orphans     remove-orphans (true) with orphan-hours (72, the least): files under the table no snapshot references, older than that

The domains' retention floor (maintconfig.RETAIN_YEARS: risk and risk-results keep 7 years) is applied as for Delta and is also given to
the Java side (``--floor-days``), which never drops a date or expires a snapshot inside it. The ownership rule is the same: only tables
``lake build --format iceberg`` built (its build record) are maintained, unless --force-foreign."""
from __future__ import annotations

import json
import pathlib
import re
import subprocess
from datetime import date, datetime, timezone

MAINT = "com.ash.drishti.plugin.iceberg.IcebergMaintenance"
ORPHAN_HOURS = 72
VERSION = re.compile(r"^v?(\d+)[.-]")


def catalog_tables(lake: dict, want: set[str] | None) -> list[str] | None:
    """A lake read through a REST catalog (iceberg-settings.catalog: rest, AWS Glue's): its tables are not folders to list, so they are
    the ones named (the run's --tables, else the lake's tables: settings); None for a path-based lake."""
    if str((lake.get("iceberg-settings") or {}).get("catalog", "")).lower() != "rest":
        return None
    names = sorted(want) if want else sorted((lake.get("tables") or {}).keys())
    return [str(lake["root"]).rstrip("/") + "/" + n for n in names]


def is_table(t: pathlib.Path) -> bool:
    """A path-based Iceberg table: a metadata folder holding a metadata file (and no Delta log)."""
    m = t / "metadata"
    return m.is_dir() and not (t / "_delta_log").is_dir() and any(m.glob("*.metadata.json"))


def tables(root: str, domains: list[str], options: dict | None = None) -> list[str]:
    """Table URIs <root>/<domain>/<table> that are Iceberg tables (local folders, or listed from object storage)."""
    if "://" in root and not root.startswith("file://"):
        import pyarrow.fs as pafs
        fs, base = pafs.FileSystem.from_uri(root)
        out = []
        for d in fs.get_file_info(pafs.FileSelector(base, recursive=False)):
            if d.type != pafs.FileType.Directory or not (domains == ["*"] or d.base_name in domains):
                continue
            for t in fs.get_file_info(pafs.FileSelector(d.path, recursive=False)):
                if t.type == pafs.FileType.Directory and fs.get_file_info(t.path + "/metadata").type == pafs.FileType.Directory:
                    out.append(root.rstrip("/") + "/" + d.base_name + "/" + t.base_name)
        return sorted(out)
    base = pathlib.Path(root.removeprefix("file://"))
    picked = [p for p in sorted(base.iterdir()) if p.is_dir() and (domains == ["*"] or p.name in domains)] if base.is_dir() else []
    return [str(t) for d in picked for t in sorted(d.iterdir()) if t.is_dir() and is_table(t)]


def current_metadata(t: pathlib.Path) -> dict | None:
    """The table's current metadata (version-hint.text, else the highest version), or None."""
    m = t / "metadata"
    hint = m / "version-hint.text"
    if hint.is_file():
        f = m / f"v{hint.read_text(encoding='utf-8').strip()}.metadata.json"
        if f.is_file():
            return json.loads(f.read_text(encoding="utf-8"))
    files = sorted(m.glob("*.metadata.json"), key=lambda f: (int(VERSION.match(f.name).group(1)) if VERSION.match(f.name) else -1, f.stat().st_mtime))
    return json.loads(files[-1].read_text(encoding="utf-8")) if files else None


def summary(t: pathlib.Path) -> dict:
    """An Iceberg table's figures for lake status and Admin -> Lakes, from its metadata alone: rows and data files from the current
    snapshot's summary, size and business dates from the data folders (until an expiry removes the files of dropped dates, those
    still count), the last commit's time."""
    out = {"table": t.parent.name + "/" + t.name, "domain": t.parent.name, "kind": t.name, "path": str(t), "format": "iceberg"}
    try:
        meta = current_metadata(t)
    except (OSError, ValueError) as e:
        out["error"] = str(e)[:200]
        return out
    if meta is None:
        out["error"] = "no metadata file"
        return out
    snap = next((s for s in meta.get("snapshots") or [] if s.get("snapshot-id") == meta.get("current-snapshot-id")), None)
    summ = (snap or {}).get("summary") or {}
    data = t / "data"
    days = sorted(p.name.split("=", 1)[1] for p in data.glob("business_date=*") if p.is_dir() and any(p.iterdir())) if data.is_dir() else []
    size = sum(f.stat().st_size for f in data.rglob("*") if f.is_file()) if data.is_dir() else 0
    rows = summ.get("total-records")
    out.update({
        "rows": int(rows) if rows is not None else None,
        "files": int(summ.get("total-data-files") or 0),
        "bytes": int(summ.get("total-files-size") or size),
        "partitionBy": ["business_date"],
        "partitions": len(days),
        "dates": len(days),
        "latestDate": days[-1] if days else None,
        "snapshots": len(meta.get("snapshots") or []),
        "tableId": meta.get("table-uuid"),
        "lastBuild": datetime.fromtimestamp(snap["timestamp-ms"] / 1000, timezone.utc).strftime("%Y-%m-%d %H:%M:%SZ") if snap else None,
    })
    return out


def split_uri(uri: str) -> tuple[str, str, str]:
    """(root, domain, table) of <root>/<domain>/<table>."""
    parts = uri.rstrip("/").split("/")
    return "/".join(parts[:-2]), parts[-2], parts[-1]


def command(uri: str, conf: dict, today: date, dry_run: bool, force_drop: bool, floor_days: int, data: pathlib.Path,
            jar: str | None = None, java: str | None = None) -> list[str]:
    """The IcebergMaintenance command for one table with these settings (a JSON line of what it did comes back)."""
    import stores
    root, domain, table = split_uri(uri)
    cmd = stores.loader_command(data, jar, java, main=MAINT) + [root, "--domain", domain, "--kinds", table, "--engine", "native",
                                                                  "--as-of", today.isoformat(), "--json"]
    if conf.get("keep-business-days"):
        cmd += ["--keep-days", str(int(conf["keep-business-days"])), "--max-drop-share", str(conf.get("max-drop-share", 0.5))]
    if conf.get("compact"):
        cmd += ["--relayout"]
    cmd += ["--expire-hours", str(int(conf["vacuum-hours"])) if conf.get("vacuum-hours") is not None else "-1"]
    if conf.get("remove-orphans", True):
        cmd += ["--remove-orphans", "--older-than-hours", str(max(ORPHAN_HOURS, int(conf.get("orphan-hours") or ORPHAN_HOURS)))]
    if floor_days:
        cmd += ["--floor-days", str(floor_days)]
    if dry_run:
        cmd += ["--dry-run"]
    if force_drop:
        cmd += ["--force-drop"]
    for key, value in (conf.get("iceberg-settings") or {}).items():   # a REST catalog (AWS Glue: catalog, uri, warehouse, catalog.rest.*)
        cmd += ["--set", f"{key}={value}"]
    opts = conf.get("storage-options") or {}
    for key, setting in (("AWS_ENDPOINT_URL", "s3.endpoint"), ("AWS_ACCESS_KEY_ID", "s3.access-key"), ("AWS_SECRET_ACCESS_KEY", "s3.secret-key"),
                         ("AWS_REGION", "s3.region")):
        if opts.get(key):
            cmd += ["--set", f"{setting}={opts[key]}"]
    return cmd


def maintain(uri: str, conf: dict, today: date, dry_run: bool, force_drop: bool = False, floor_days: int = 0,
             data: pathlib.Path | None = None, runner=None) -> dict:
    """One Iceberg table maintained (or a dry run): the result in lake maintain's form (before, after or estimate, retention, compact,
    vacuum, orphans, floor). ``runner(cmd)`` gives the JSON line (tests); by default the command runs. Raises RuntimeError on failure."""
    data = data or pathlib.Path(uri).parent.parent / "_drishti-lake"
    cmd = command(uri, conf, today, dry_run, force_drop, floor_days, data)
    if runner is not None:
        line = runner(cmd)
    else:
        p = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
        lines = [x for x in p.stdout.splitlines() if x.startswith("{")]
        if not lines:
            words = [x for x in (p.stderr + p.stdout).splitlines() if x.strip() and not x.startswith("SLF4J")]
            raise RuntimeError("Iceberg maintenance failed: " + (words[-1] if words else f"exit {p.returncode}"))
        line = lines[-1]
    r = json.loads(line)
    if r.get("error"):
        raise RuntimeError(r["error"])
    out: dict = {"table": uri, "format": "iceberg", "before": r.get("before")}
    out["estimate" if dry_run else "after"] = r.get("after")
    keep = r.get("keepDays") or {}
    if keep:
        out["retention"] = {"dates": keep.get("wouldRemove")} if dry_run else {"removed": keep.get("removed")}
    if "relayout" in r:
        out["compact"] = {"relaid-dates": r["relayout"]}
    if "expireHours" in r:
        out["vacuum"] = {"hours": r["expireHours"], "dry_run": dry_run}
    if r.get("orphans"):
        out["orphans"] = r["orphans"]
    if r.get("floor"):
        out["floor"] = r["floor"]
    return out
