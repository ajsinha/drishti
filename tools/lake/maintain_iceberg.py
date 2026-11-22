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

"""Iceberg tables in ``lake maintain`` and Admin -> Lakes (docs/guides/OPERATIONS_GUIDE.md, "Iceberg lakes"). Delta stays the default;
a lake whose ``format: iceberg`` (or whose tables are Iceberg tables: ``<root>/<domain>/<table>/metadata/*.metadata.json``) is maintained
by Drishti Lake's ``lake maintain --format iceberg`` (the one upkeep of every store), each step one Iceberg commit:

    retention   keep-business-days: today and the N business days before it are kept, counted on the calendar as for Delta (a
                metadata-only delete of older days), refused past max-drop-share unless --force-drop
    compact     the days that drifted from the layout are rewritten sorted by id
    vacuum      vacuum-hours: snapshots older than that are expired and the files only they used deleted (time travel reaches that far);
                a file no snapshot names goes only when it is older than 72 hours too

The domains' retention floor (maintconfig.RETAIN_YEARS: risk and risk-results keep 7 years) is applied as for Delta and is also given to
Lake (``--floor-days``), which never drops a date or expires a snapshot inside it. The ownership rule is the same: only tables
``lake build --format iceberg`` built (its build record) are maintained, unless --force-foreign."""
from __future__ import annotations

import json
import pathlib
import re
import subprocess
from datetime import date, datetime, timezone

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
    """The table's current metadata, found as drishti-iceberg's PathTableOps and the Apache library find it: version-hint.text's vN, else the
    highest version (vN, or a catalog's NNNNN-uuid); gzip-compressed metadata files (vN.gz.metadata.json, *.metadata.json.gz) included."""
    m = t / "metadata"
    hint = m / "version-hint.text"
    if hint.is_file():
        n = hint.read_text(encoding="utf-8").strip()
        for name in (f"v{n}.metadata.json", f"v{n}.gz.metadata.json", f"v{n}.metadata.json.gz"):
            if (m / name).is_file():
                return read_metadata(m / name)
    files = sorted([*m.glob("*.metadata.json"), *m.glob("*.metadata.json.gz")],
                   key=lambda f: (int(VERSION.match(f.name).group(1)) if VERSION.match(f.name) else -1, f.stat().st_mtime))
    return read_metadata(files[-1]) if files else None


def read_metadata(f: pathlib.Path) -> dict:
    """A metadata file's JSON, gunzipped when it is gzip-compressed (write.metadata.compression-codec: gzip)."""
    import gzip
    data = f.read_bytes()
    return json.loads(gzip.decompress(data) if data[:2] == b"\x1f\x8b" else data)


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


def json_line(cmd: list[str], what: str) -> str:
    """Runs a Java maintenance command and returns the last line of JSON it printed; RuntimeError (naming ``what`` and the last word of its
    output) when it printed none."""
    p = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    lines = [x for x in p.stdout.splitlines() if x.startswith("{")]
    if not lines:
        words = [x for x in (p.stderr + p.stdout).splitlines() if x.strip() and not x.startswith("SLF4J")]
        raise RuntimeError(f"{what} failed: " + (words[-1] if words else f"exit {p.returncode}"))
    return lines[-1]


def maintain(uri: str, conf: dict, today: date, dry_run: bool, force_drop: bool = False, floor_days: int = 0,
             data: pathlib.Path | None = None, runner=None) -> dict:
    """One Iceberg table maintained (or a dry run) by Drishti Lake's ``lake maintain`` with ``--format iceberg``: the one rule of every store
    (retention keeps today and the N business days before it, counted on the calendar), the same options and the same answer as for Delta
    (maintain.maintain). ``runner(cmd)`` gives the JSON line (tests). Raises RuntimeError on failure."""
    import maintain
    if str((conf.get("iceberg-settings") or {}).get("catalog", "")).lower() == "rest":
        raise RuntimeError("a lake read through a REST catalog is not maintained by lake maintain (it takes a path-based table): "
                           "maintain it in its catalog's own tools")
    done = maintain.maintain(uri, conf, today, dry_run, force_drop, floor_days, runner=runner, data=data, fmt="iceberg")
    done["format"] = "iceberg"
    return done
