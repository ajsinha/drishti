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

"""Row retention in the lake maintenance (docs/guides/DATA_LAKE_GUIDE.md, "Row retention"): a table's ``retention: {ttl, on, max-rows}``
(a lake file's table entry, or ``row-retention:`` in the maintenance file's lake or table settings) drops the rows past their age, by the one
rule every store shares (the Java ``RowRetention``: the memory lake's compaction applies it too). It runs as a step of ``lake maintain``, the one
upkeep schedule: a Delta table by the lake loader's ``LakeRetention`` (each business date holding dropped rows rewritten, one commit a date, the
old files vacuumed after by the table's own vacuum step), an Iceberg table by ``IcebergMaintenance`` (its ``--ttl``, ``--on`` and ``--max-rows``,
see :mod:`maintain_iceberg`, the old snapshots expired by its own step), a files store (``format: files``: ``<root>/<domain>/<date>/<kind>.jsonl``)
by ``LakeRetention`` too, the affected day files rewritten. Absent: nothing changes.
"""
from __future__ import annotations

import json
import pathlib

RETENTION = "com.ash.drishti.lakeload.LakeRetention"


def clean(conf) -> dict | None:
    """The row retention of a table's settings as {ttl, on, max-rows} (only what is given), or None when it has no limit."""
    rr = (conf or {}).get("row-retention") or {}
    out = {k: str(rr[k]) for k in ("ttl", "on", "max-rows") if rr.get(k) not in (None, "")}
    return out or None


def command(root: str, fmt: str, name: str, rr: dict, dry_run: bool, data: pathlib.Path, jar: str | None = None, java: str | None = None) -> list[str]:
    """The LakeRetention command for one table (``name`` is <domain>/<table>)."""
    import stores
    cmd = stores.loader_command(data, jar, java, main=RETENTION) + ["--lake", root, "--format", fmt, "--table", name, "--json"]
    for key in ("ttl", "on", "max-rows"):
        if rr.get(key):
            cmd += [f"--{key}", rr[key]]
    if dry_run:
        cmd += ["--dry-run"]
    return cmd


def apply(root: str, fmt: str, name: str, rr: dict, dry_run: bool, data: pathlib.Path | None = None, runner=None) -> dict:
    """One table's rows dropped (or counted, on a dry run): {"dropped": n, "dates": [{"date", "rows", "dropped"}]}. ``runner(cmd)`` gives the
    JSON line (tests); by default the command runs. Raises RuntimeError on failure."""
    data = data or pathlib.Path(root) / "_drishti-lake"
    cmd = command(root, fmt, name, rr, dry_run, data)
    import maintain_iceberg
    r = json.loads(runner(cmd) if runner is not None else maintain_iceberg.json_line(cmd, "row retention"))
    return {"ttl": rr.get("ttl"), "on": rr.get("on"), "max-rows": rr.get("max-rows"), "dropped": r.get("dropped", 0), "dates": r.get("dates", []),
            "dry_run": dry_run}


def files_tables(root: str, domains: list[str]) -> list[str]:
    """``<domain>/<kind>`` of the files store at ``root`` (``<root>/<domain>/<date>/<kind>.jsonl``), sorted."""
    base = pathlib.Path(root)
    out: set[str] = set()
    if base.is_dir():
        for d in sorted(p for p in base.iterdir() if p.is_dir() and (domains == ["*"] or p.name in domains)):
            for day in d.iterdir():
                if day.is_dir():
                    out.update(f"{d.name}/{f.name[:-len('.jsonl')]}" for f in day.glob("*.jsonl"))
    return sorted(out)
