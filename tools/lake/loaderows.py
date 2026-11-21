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

"""The one writer of loader rows (``{domain, kind, id, date, doc, columns}`` a line) into a lake table: Drishti Lake's loader, started
as ``DrishtiLakeCli load`` with ``source.format: loader-rows`` (one table a load, each business date replaced). ``lake build --format
iceberg``, ``ingest_jsonl --store iceberg`` and ``stores.copy`` (a Delta table moved to Iceberg) all come here: there is no second
Iceberg writer.

The rows are written to one file a date (``<table>.<date>.jsonl``) in the build's working folder, then loaded. The loader takes a vector
from the document (where its marker stands), so a vector among a row's columns (a list with a float in it, or a name in ``vectors``) is
moved into the document; the other columns are promoted as they are named.
"""
from __future__ import annotations

import contextlib
import json
import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
for p in (str(HERE), str(ROOT / "tools")):
    if p not in sys.path:
        sys.path.append(p)
import javaload  # noqa: E402
from javacmd import java_cmd  # noqa: E402


class LoadError(RuntimeError):
    """The load did not finish, in the loader's own words."""


def _is_vector(v) -> bool:
    return isinstance(v, list) and any(isinstance(x, float) for x in v)


def load(rows, root, domain: str, table: str, workdir: pathlib.Path, fmt: str = "iceberg", vectors=(), settings=None, jar: str | None = None,
         java: str | None = None, say=None) -> dict:
    """Loads the loader-rows lines (each a JSON text) into ``<root>/<domain>/<table>``; {rows, dates, summary}. ``settings`` are the
    target's ``--iceberg-set KEY=VALUE`` words. Raises LoadError with the loader's last line when it fails (a date that did not commit is
    unchanged)."""
    base = javaload.command(jar, java)
    if not base:
        raise LoadError("the Iceberg writer is Drishti Lake's loader: it needs the server's exec jar (python tools/drishti.py build jar; "
                        "--jar) or LAKE_CLASSPATH, and Java 21")
    feed = workdir / "rows"
    feed.mkdir(parents=True, exist_ok=True)
    handles: dict[str, object] = {}
    promote: list[str] = []
    seen: set[str] = set()
    vecs = set(vectors)
    n = 0
    try:
        for line in rows:
            row = json.loads(line)
            cols = dict(row.get("columns") or {})
            doc = row.get("doc")
            moved = [k for k, v in cols.items() if k in vecs or _is_vector(v)]
            if moved:
                d = json.loads(doc) if isinstance(doc, str) else dict(doc or {})
                for k in moved:
                    vecs.add(k)
                    if cols[k] is not None:
                        d[k] = cols.pop(k)
                    else:
                        cols.pop(k)
                row["doc"] = json.dumps(d, ensure_ascii=False, separators=(",", ":"))
            for k in cols:
                if k not in seen:
                    seen.add(k)
                    promote.append(k)
            row["columns"] = cols
            day = str(row["date"])[:10]
            fh = handles.get(day)
            if fh is None:
                fh = handles[day] = open(feed / f"{table}.{day}.jsonl", "w", encoding="utf-8", newline="\n")
            fh.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")
            n += 1
    finally:
        for fh in handles.values():
            fh.close()
    if not n:
        return {"rows": 0, "dates": [], "summary": {}}
    cfg = {"source": {"files": [str(f) for f in sorted(feed.glob("*.jsonl"))], "format": "loader-rows", "promote": promote,
                      "vectors": sorted(vecs)},
           "target": {"format": fmt, "path": str(root).rstrip("/") + "/" + domain + "/" + table, "domain": domain, "kind": table,
                      "mode": "replace-date"},
           "memory": {"spill-dir": str(workdir / "spill")}, "rejects": {"file": str(workdir / "rejects.jsonl")}}
    ice: dict[str, str] = {}
    env = None
    for kv in settings or []:
        if "=" not in kv:
            raise LoadError(f"--iceberg-set {kv}: give KEY=VALUE")
        k, v = kv.split("=", 1)
        ice[k.strip()] = v
    if ice:
        cfg["target"]["iceberg"] = ice
    cfg_file = workdir / "loader.json"
    cfg_file.write_text(json.dumps(cfg, indent=1), encoding="utf-8")
    summary_file = workdir / "loader-summary.json"
    java_bin, args = base
    cmd = java_cmd(java_bin, [*args, "--config", str(cfg_file), "--summary", str(summary_file), "--no-lock", "--no-run-log", "-q"],
                   tmp=workdir / "tmp", env=env)
    proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")
    said = [s for s in proc.stdout.splitlines() if s.strip() and not s.startswith("SLF4J")]
    if not summary_file.is_file():
        raise LoadError("the load did not finish: " + (said[-1] if said else f"exit {proc.returncode}"))
    s = json.loads(summary_file.read_text(encoding="utf-8"))
    if proc.returncode in (1, 3) or s.get("status") == "failed":
        raise LoadError(s.get("error") or (said[-1] if said else f"the load failed (exit {proc.returncode})"))
    if say:
        say(f"    {domain}/{table}: {n:,} rows loaded")
    with contextlib.suppress(OSError):
        for f in feed.glob("*.jsonl"):
            f.unlink()
        feed.rmdir()
    return {"rows": n, "dates": sorted(handles), "summary": s}
