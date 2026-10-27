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

"""lake tables: what a lake holds, table by table: rows, files, size, partitions, business dates (how many, the latest) and when it was last
built (its last commit). Read from each table's Delta log alone (no data file is opened): with deltalake when it is installed (a log that
starts at a checkpoint needs it), else by replaying the log's JSON commits. Used by ``drishti.py lake status`` and the console's Lakes screen.
An Iceberg table under the same root (``metadata/*.metadata.json``: ``lake build --format iceberg``) is listed too, its figures from its
metadata (tools/lake/maintain_iceberg.py); every table says its ``format``, delta (the default) or iceberg.

    python3 tools/drishti.py lake status --lake data/delta"""
from __future__ import annotations

import datetime as _dt
import json
import pathlib


def _replay(t: pathlib.Path) -> dict | None:
    """The live files of a table from its JSON commits: {path: (rows, size, partition values)}, and the last commit's time (ms)."""
    log = t / "_delta_log"
    files: dict[str, tuple[int | None, int, dict]] = {}
    last = None
    commits = sorted(log.glob("*.json"))
    if not commits:
        return None
    if any(log.glob("*.checkpoint*.parquet")) and not commits[0].name.startswith("00000000000000000000"):
        return None                                                     # starts at a checkpoint: only deltalake reads it
    for f in commits:
        with open(f, encoding="utf-8") as fh:
            for line in fh:
                a = json.loads(line)
                if "add" in a:
                    x = a["add"]
                    stats = json.loads(x["stats"]) if x.get("stats") else {}
                    files[x["path"]] = (stats.get("numRecords"), int(x.get("size") or 0), x.get("partitionValues") or {})
                elif "remove" in a:
                    files.pop(a["remove"]["path"], None)
                elif "commitInfo" in a and a["commitInfo"].get("timestamp"):
                    last = a["commitInfo"]["timestamp"]
        if last is None:
            last = int(f.stat().st_mtime * 1000)
    return {"files": files, "last": last}


def _with_deltalake(t: pathlib.Path) -> dict | None:
    try:
        from deltalake import DeltaTable
    except ImportError:
        return None
    dt = DeltaTable(str(t))
    got = dt.get_add_actions(flatten=True)
    try:
        acts = got.to_pylist()
    except AttributeError:                                              # deltalake 1.x answers an arro3 table: read it through pyarrow
        import pyarrow
        acts = pyarrow.table(got).to_pylist()
    files = {}
    for a in acts:
        parts = {k[len("partition."):]: v for k, v in a.items() if k.startswith("partition.")}
        files[a["path"]] = (a.get("num_records"), int(a.get("size_bytes") or 0), parts)
    hist = dt.history(1)
    last = hist[0].get("timestamp") if hist else None
    return {"files": files, "last": last}


def summary(t: pathlib.Path) -> dict:
    """One table's figures; an unreadable table says why in ``error``."""
    if not (t / "_delta_log").is_dir() and _iceberg().is_table(t):
        return _iceberg().summary(t)
    out = {"table": t.parent.name + "/" + t.name, "domain": t.parent.name, "kind": t.name, "path": str(t), "format": "delta"}
    try:
        got = _replay(t) or _with_deltalake(t)                         # the JSON log first (no dependency), deltalake for a checkpoint
    except Exception as e:  # noqa: BLE001 - one broken table never hides the others
        out["error"] = str(e)[:200]
        return out
    if got is None:
        out["error"] = "the log starts at a checkpoint: install deltalake to read it"
        return out
    files = got["files"].values()
    rows = [r for r, _, _ in files]
    dates = sorted({str(p.get("business_date")) for _, _, p in files if p.get("business_date")})
    keys = sorted({k for _, _, p in files for k in p})
    out.update({
        "rows": sum(r for r in rows if r is not None) if rows and all(r is not None for r in rows) else (sum(r for r in rows if r is not None) or None),
        "files": len(rows),
        "bytes": sum(s for _, s, _ in files),
        "partitionBy": keys,
        "partitions": len({tuple(sorted(p.items())) for _, _, p in files}),
        "dates": len(dates),
        "latestDate": dates[-1] if dates else None,
        "lastBuild": _dt.datetime.fromtimestamp(got["last"] / 1000, _dt.timezone.utc).strftime("%Y-%m-%d %H:%M:%SZ") if got["last"] else None,
    })
    return out


def _iceberg():
    import sys
    here = str(pathlib.Path(__file__).resolve().parent)
    if here not in sys.path:
        sys.path.insert(0, here)
    import maintain_iceberg
    return maintain_iceberg


def tables(lake: pathlib.Path) -> list[dict]:
    """Every table under a lake root (<root>/<domain>/<kind> with a _delta_log, or an Iceberg table's metadata), sorted by domain and kind."""
    lake = pathlib.Path(lake)
    if not lake.is_dir():
        return []
    found = {log.parent for log in lake.glob("*/*/_delta_log")} | {m.parent for m in lake.glob("*/*/metadata") if _iceberg().is_table(m.parent)}
    return [summary(t) for t in sorted(found)]


def _log_meta(t: pathlib.Path, history: int) -> dict:
    """From the log alone: the newest metadata (schema, partition columns, properties, created), the protocol, and the newest
    commits (version, time, operation, its parameters and metrics), newest first. Uses deltalake when the log starts at a checkpoint."""
    log = t / "_delta_log"
    commits = sorted(log.glob("*.json"))
    meta, protocol, hist = None, None, []
    if commits and commits[0].name.startswith("00000000000000000000"):
        for f in commits:
            with open(f, encoding="utf-8") as fh:
                for line in fh:
                    a = json.loads(line)
                    if "metaData" in a:
                        meta = a["metaData"]
                    elif "protocol" in a:
                        protocol = a["protocol"]
        for f in reversed(commits[-history:]):
            with open(f, encoding="utf-8") as fh:
                info = next((json.loads(x)["commitInfo"] for x in fh if '"commitInfo"' in x), {})
            hist.append({"version": int(f.stem), "timestamp": info.get("timestamp") or int(f.stat().st_mtime * 1000),
                         "operation": info.get("operation"), "parameters": info.get("operationParameters") or {},
                         "metrics": info.get("operationMetrics") or {}, "engine": info.get("engineInfo") or info.get("clientVersion")})
    else:
        from deltalake import DeltaTable
        dt = DeltaTable(str(t))
        m = dt.metadata()
        meta = {"id": m.id, "name": m.name, "description": m.description, "partitionColumns": list(m.partition_columns),
                "configuration": dict(m.configuration or {}), "createdTime": m.created_time, "schemaString": dt.schema().to_json()}
        pr = dt.protocol()
        protocol = {"minReaderVersion": pr.min_reader_version, "minWriterVersion": pr.min_writer_version}
        for h in dt.history(history):
            hist.append({"version": h.get("version"), "timestamp": h.get("timestamp"), "operation": h.get("operation"),
                         "parameters": h.get("operationParameters") or {}, "metrics": h.get("operationMetrics") or {},
                         "engine": h.get("engineInfo") or h.get("clientVersion")})
    return {"meta": meta or {}, "protocol": protocol or {}, "history": hist, "version": int(commits[-1].stem) if commits else None}


def _when(ms) -> str | None:
    return _dt.datetime.fromtimestamp(ms / 1000, _dt.timezone.utc).strftime("%Y-%m-%d %H:%M:%SZ") if ms else None


def detail(t: pathlib.Path, history: int = 30) -> dict:
    """One table in full, from its log (no data file is opened): its figures, each business date (rows, files, size, partitions),
    its columns and types, partition columns, properties, protocol, and its newest commits."""
    t = pathlib.Path(t)
    out = summary(t)
    if out.get("error") or out.get("format") == "iceberg":
        return out                                               # an Iceberg table: its figures (the Delta log's detail has no counterpart)
    got = _replay(t) or _with_deltalake(t)
    by_date: dict[str, dict] = {}
    for rows, size, parts in got["files"].values():
        d = str(parts.get("business_date") or "(no date)")
        x = by_date.setdefault(d, {"date": d, "rows": 0, "files": 0, "bytes": 0, "partitions": {}})
        x["rows"] += rows or 0
        x["files"] += 1
        x["bytes"] += size
        key = ", ".join(f"{k}={v}" for k, v in sorted(parts.items()) if k != "business_date")
        if key:
            x["partitions"][key] = x["partitions"].get(key, 0) + (rows or 0)
    dates = [dict(v, size=human_bytes(v["bytes"]), partitions=sorted(v["partitions"].items(), key=lambda kv: -kv[1]))
             for _, v in sorted(by_date.items(), reverse=True)]
    lm = _log_meta(t, history)
    meta = lm["meta"]
    try:
        fields = json.loads(meta.get("schemaString") or "{}").get("fields", [])
    except ValueError:
        fields = []
    columns = [{"name": f.get("name"), "type": f["type"] if isinstance(f.get("type"), str) else (f.get("type") or {}).get("type", "struct"),
                "nullable": f.get("nullable", True), "partition": f.get("name") in (meta.get("partitionColumns") or [])} for f in fields]
    for h in lm["history"]:
        h["when"] = _when(h["timestamp"])
    out.update(size=human_bytes(out.get("bytes")), byDate=dates, columns=columns, partitionColumns=meta.get("partitionColumns") or [],
               properties=meta.get("configuration") or {}, tableId=meta.get("id"), created=_when(meta.get("createdTime")),
               protocol=lm["protocol"], version=lm["version"], history=lm["history"],
               firstDate=next((d["date"] for d in reversed(dates) if d["date"] != "(no date)"), None))
    return out


def human_bytes(n: int | None) -> str:
    if not n:
        return "0 B"
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:,.0f} {unit}" if unit == "B" else f"{n:,.1f} {unit}"
        n /= 1024
    return str(n)


def table_text(rows: list[dict]) -> str:
    """The tables as aligned text for a terminal."""
    if not rows:
        return "no tables (no <domain>/<kind>/_delta_log under the lake)"
    head = ("table", "rows", "files", "size", "partitions", "dates", "latest date", "last build")
    body = [(r["table"], "?" if r.get("rows") is None else f"{r['rows']:,}", str(r.get("files", "")), human_bytes(r.get("bytes")),
             str(r.get("partitions", "")) + (" (" + ",".join(k for k in r.get("partitionBy", []) if k != "business_date") + ")" if [k for k in r.get("partitionBy", []) if k != "business_date"] else ""),
             str(r.get("dates", "")), r.get("latestDate") or "–", r.get("lastBuild") or "–") if not r.get("error") else (r["table"], "error: " + r["error"], "", "", "", "", "", "")
            for r in rows]
    w = [max(len(x[i]) for x in [head] + body) for i in range(len(head))]
    return "\n".join("  ".join(c.ljust(w[i]) for i, c in enumerate(line)).rstrip() for line in [head] + body)
