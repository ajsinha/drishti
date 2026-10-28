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

"""``lake build --format iceberg``: the staged rows of a build written into an Apache Iceberg table by Drishti's own Iceberg loader
(IcebergLoader in the server's exec jar, ``engine: native``: no Hadoop, so it works on Windows). Delta stays the default format; Iceberg
is chosen per build (``--format iceberg``) or by a pack's Iceberg connector.

The table is ``<lake>/<domain>/<table>``, in the layout the Iceberg connector reads fastest (docs/guides/ICEBERG_CONNECTOR.md): rows
``(id, doc, business_date)``, the promoted columns beside them, a vector field (``--vector PNL``) as an optional ``list<double>`` column
whose missing elements stay null, each business date sorted by id. Each business date of the input replaces that date (the loader commits
one overwrite of the day), so re-running a day is safe; ``overwrite-partitions`` and ``append`` are Delta's and are refused here. Partition
columns (``--partition-by``) are kept as ordinary columns: an Iceberg table of this layout is partitioned by business date alone.

The staged files are streamed to the loader's standard input (one JSON line a row: domain, kind, id, date, doc, columns), so memory stays
flat; the loader sorts each day itself (spilling to the build's staging folder)."""
from __future__ import annotations

import json
import pathlib
import subprocess
import threading

import stage
import stores
import writers


def lines(domain: str, table: str, by_day: dict, partition_names: list[str]):
    """The loader's lines for every staged row: its promoted values, and the partition values as columns."""
    for day, parts in by_day.items():
        iso = day.isoformat()
        for part in parts:
            fixed = {n: v for n, v in zip(partition_names, part.values) if v is not None}
            for chunk in stage.read_rows(part.file, 2000):
                for ident, doc, promoted in chunk:
                    cols = {**fixed, **{k: v for k, v in promoted.items() if v is not None}}
                    yield json.dumps({"domain": domain, "kind": table, "id": ident, "date": iso, "doc": doc, "columns": cols},
                                     ensure_ascii=False, separators=(",", ":")) + "\n"


def write(lake, domain: str, table: str, by_day: dict, partition_names: list[str], workdir: pathlib.Path,
          jar: str | None = None, java: str | None = None, progress=None, settings: list[str] | None = None) -> dict:
    """Streams the staged rows into ``<lake>/<domain>/<table>``; {format, table, rows, dates, engine}. Raises writers.WriteError with the
    loader's own last line when it fails (nothing of a day that did not commit is visible)."""
    spill = workdir / "spill"
    spill.mkdir(parents=True, exist_ok=True)
    try:
        cmd = stores.loader_command(workdir, jar, java) + ["-", str(lake), "--engine", "native", "--spill-dir", str(spill), "--buffer-mb", "512"]
        for kv in settings or []:
            if "=" not in kv:
                raise writers.WriteError(f"--iceberg-set {kv}: give KEY=VALUE")
            cmd += ["--set", kv]
    except SystemExit as e:
        raise writers.WriteError(str(e)) from None
    said: list[str] = []
    total = sum(len(parts) for parts in by_day.values())
    n = 0
    with subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8",
                          errors="replace") as proc:
        reader = threading.Thread(target=lambda: said.extend(proc.stdout), daemon=True)
        reader.start()
        try:
            for line in lines(domain, table, by_day, partition_names):
                proc.stdin.write(line)
                n += 1
            if progress:
                progress(total, total)
            proc.stdin.close()
        except BrokenPipeError:
            pass
        proc.wait()
        reader.join(timeout=10)
    words = [s.rstrip() for s in said if s.strip() and not s.startswith("SLF4J")]
    if proc.returncode:
        raise writers.WriteError("the Iceberg load did not finish: " + (words[-1] if words else f"exit {proc.returncode}"))
    return {"format": "iceberg", "engine": "native", "table": str(lake).rstrip("/") + "/" + domain + "/" + table, "rows": n,
            "dates": sorted(d.isoformat() for d in by_day), "loader": [w for w in words if w.startswith("iceberg:")][-3:]}
