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

"""The Python engine that writes a staged table into Delta Lake: the second half of ``lake build`` (the parallel Java loader, ``javaload``, is the other
engine; it is Drishti Lake's loader, the one way a host writes rows with Java).

``deltalake``  the Python deltalake package (delta-rs) with pyarrow: one commit per business date and batch of rows (every partition
               of the date together; one per partition with ``overwrite-partitions``), a checkpoint every ten commits; the first
               batch of a replaced date or partition replaces it (``predicate`` overwrite), the next ones append.

It writes the same table the loader does: ``id`` STRING, ``doc`` STRING (the whole document, nested arrays intact), ``business_date`` DATE, the
partition columns (STRING, Hive folders ``business_date=2026-10-01/desk=RATES/``) and the promoted columns (DOUBLE when every value
is a number, else STRING; a vector column, ``layout.<kind>.vectors``, as ``list<double>``), partitioned by business_date then the
partition columns."""
from __future__ import annotations

import json
import os
import pathlib
import shutil
import sys

from stage import Part, list_texts, read_rows, row_line

ROOT = pathlib.Path(__file__).resolve().parents[2]


class WriteError(RuntimeError):
    pass


def predicate_for(day: str, names: list[str], values: tuple | None) -> str:
    """The SQL predicate of one business date (values None) or of one partition."""
    parts = [f"business_date = '{day}'"]
    for n, v in zip(names, values or ()):
        parts.append(f"{n} IS NULL" if v is None or v == "" else f"{n} = '" + v.replace("'", "''") + "'")
    return " AND ".join(parts)


# ------------------------------------------------------------------------------------------------ choosing an engine

def find_jar(explicit: str | None = None) -> str | None:
    """--jar (a one-off run), else the newest drishti-server-*-exec.jar in <home>/lib, the one place Drishti's own jars are kept
    (tools/javacmd.py ``server_jar``; ``python tools/drishti.py build jar`` puts it there); None when there is none."""
    tools = str(ROOT / "tools")
    if tools not in sys.path:
        sys.path.append(tools)
    import javacmd
    try:
        return str(javacmd.server_jar(explicit))
    except javacmd.JarError:
        return None


def find_java(explicit: str | None = None) -> str | None:
    """The java the lake tools run, in the one order of every Drishti tool (tools/javacmd.py ``find_java``: --java, --java-home, drishti.java.home, DRISHTI_JAVA_HOME,
    JAVA_HOME, the PATH), Java 21 or newer (an older one is refused in words); None when there is none."""
    tools = str(ROOT / "tools")
    if tools not in sys.path:
        sys.path.append(tools)
    import javacmd
    return javacmd.find_java(explicit, required=False)


def deltalake_available() -> bool:
    try:
        import deltalake  # noqa: F401
        import pyarrow  # noqa: F401
        return True
    except ImportError:
        return False


def choose(engine: str, jar: str | None = None, java: str | None = None) -> tuple[str, str]:
    """(engine, why): the Python deltalake package, when it is installed (the parallel Java loader is chosen before this, by ``javaload``)."""
    if deltalake_available():
        return "deltalake", "the Python deltalake package" + ("; the parallel lake loader was not found" if engine == "auto" else "")
    raise WriteError("no engine: build the server (its exec jar carries Drishti Lake's loader) or install deltalake and pyarrow "
                     "(pip install deltalake pyarrow)")


# ------------------------------------------------------------------------------------------------ the table

def retention_properties(uri: str) -> dict[str, str]:
    """The Delta properties a new table of a kept domain is created with (the risk lake: 7 years, docs/guides/VAR_GUIDE.md section 23.1
    question 10): its log and its removed files are kept that long, so its time travel reaches back that far (a vacuum or a log
    clean-up never takes them sooner). Empty for any other domain: the table keeps Delta's defaults."""
    import maintconfig
    domain = uri.rstrip("/").split("/")[-2] if "/" in uri.rstrip("/") else ""
    years = maintconfig.RETAIN_YEARS.get(domain)
    if not years:
        return {}
    days = maintconfig.retain_days(years)
    return {"delta.logRetentionDuration": f"interval {days} days", "delta.deletedFileRetentionDuration": f"interval {days} days"}


class Table:
    """What one write needs: where, the columns and their types, the partitioning, and how to replace."""

    def __init__(self, uri: str, partition_names: list[str], promoted: dict[str, str], mode: str, batch_rows: int,
                 stats_columns: list[str], sort: bool = True):
        self.uri, self.partition_names, self.promoted, self.mode = uri, partition_names, promoted, mode
        self.batch_rows, self.stats_columns, self.sort = batch_rows, stats_columns, sort

    @property
    def partition_by(self) -> list[str]:
        return ["business_date", *self.partition_names]


def existing_layout(uri: str) -> tuple[list[str], dict[str, str]] | None:
    """(partition columns, {column: double|string}) of an existing table, read from its last metadata action in the log (no
    dependency); None when there is no table."""
    log = pathlib.Path(uri) / "_delta_log"
    if not log.is_dir():
        return None
    meta = None
    for f in sorted(log.glob("*.json")):
        with open(f, encoding="utf-8") as fh:
            for line in fh:
                if '"metaData"' in line:
                    meta = json.loads(line)["metaData"]
    if meta is None:
        if not any(log.glob("*.checkpoint*.parquet")):
            return None
        if not deltalake_available():
            raise WriteError(f"{uri}: the table's log starts at a checkpoint; install deltalake to read it (pip install deltalake)")
        from deltalake import DeltaTable
        dt = DeltaTable(uri)
        meta = {"partitionColumns": dt.metadata().partition_columns, "schemaString": dt.schema().to_json()}
    schema = json.loads(meta["schemaString"])
    types = {f["name"]: ("double" if f["type"] in ("double", "float") else ("list" if f["type"].get("elementType") == "string" else "vector")
                         if isinstance(f["type"], dict) and f["type"].get("type") == "array" else "string") for f in schema["fields"]}
    return list(meta.get("partitionColumns") or []), types


def check_layout(t: Table) -> None:
    """A run adds to a table only in the table's own partitioning; a promoted column keeps the type the table has."""
    have = existing_layout(t.uri)
    if have is None:
        return
    parts, types = have
    if parts != t.partition_by:
        want = ",".join(p for p in parts if p != "business_date") or "''"
        raise WriteError(f"{t.uri} is partitioned by {', '.join(parts) or 'nothing'}; this run asks for {', '.join(t.partition_by)} "
                         f"(give --partition-by {want} or write a new table)")
    for name in t.promoted:
        if name in types:
            t.promoted[name] = types[name]


def _cell(v, kind: str):
    if v is None:
        return None
    if kind == "list":                               # a list attribute: array<string> (DATA_LAKE_GUIDE.md section 2.4)
        return list_texts(v)
    if kind == "vector":                             # a P&L vector: floats, a missing element stays missing (null, never zero)
        return [None if x is None else float(x) for x in v] if isinstance(v, list) else None
    if kind == "double":
        if isinstance(v, str):                       # an id column an older table keeps as a number: the id's digits
            try:
                return float(v)
            except ValueError:
                return None
        return float(v) if isinstance(v, (int, float)) and not isinstance(v, bool) else None
    if isinstance(v, str):
        return v
    return json.dumps(v, ensure_ascii=False, separators=(",", ":"))


def _chunks(t: Table, part: Part):
    """The staged rows of a part in batches; a part that fits one batch is sorted by id (each file then holds a contiguous range)."""
    for rows in read_rows(part.file, t.batch_rows):
        if t.sort:
            rows.sort(key=lambda r: r[0])
        yield rows


# ------------------------------------------------------------------------------------------------ deltalake (Python)

CHECKPOINT_EVERY = 10   # commits between checkpoints while writing: a commit then replays at most this many log files, not all of them


def write_deltalake(t: Table, by_day: dict, progress=None) -> dict:
    """One commit per business date and batch of rows, every partition of the date in it (``overwrite-partitions`` keeps one
    commit per partition, as it replaces partitions one by one); a checkpoint every CHECKPOINT_EVERY commits, so the log a commit
    replays stays short however many dates a run writes (sixty dates of a table partitioned by product type were 300 commits, each
    replaying the whole log: minutes on Linux, far longer on Windows)."""
    import pyarrow as pa
    from deltalake import DeltaTable, WriterProperties, write_deltalake as _write

    check_layout(t)
    exists = DeltaTable.is_deltatable(t.uri)
    commits = 0
    done = 0
    total = sum(len(parts) for parts in by_day.values())
    per_part = t.mode == "overwrite-partitions"

    def arrow(day, part, rows):
        cols = {"id": pa.array([r[0] for r in rows], pa.string()), "doc": pa.array([r[1] for r in rows], pa.string()),
                "business_date": pa.array([day] * len(rows), pa.date32())}
        for n, v in zip(t.partition_names, part.values):
            cols[n] = pa.array([v if v != "" else None] * len(rows), pa.string())     # an empty value is null, as Spark writes it
        for name, kind in t.promoted.items():
            cols[name] = pa.array([_cell(r[2].get(name), kind) for r in rows],
                                  pa.float64() if kind == "double" else pa.list_(pa.float64()) if kind == "vector"
                                  else pa.list_(pa.string()) if kind == "list" else pa.string())
        return pa.table(cols)

    def commit(table, replace):
        nonlocal exists, commits
        kw: dict = {"partition_by": t.partition_by, "writer_properties": WriterProperties(compression="SNAPPY")}
        if not exists:
            kw["configuration"] = {"delta.dataSkippingStatsColumns": ",".join(t.stats_columns), **retention_properties(t.uri)}
        if replace:
            kw.update(mode="overwrite", predicate=replace, schema_mode="merge")
        else:
            kw.update(mode="append", schema_mode="merge" if exists else None)
            if kw["schema_mode"] is None:
                del kw["schema_mode"]
        _write(t.uri, table, **kw)
        exists = True
        commits += 1
        if commits % CHECKPOINT_EVERY == 0:
            try:
                DeltaTable(t.uri).create_checkpoint()
            except Exception:                                 # noqa: BLE001 - an optimisation: the table reads without it
                pass

    for day, parts in by_day.items():
        first_of_day = True
        pending, pending_rows = [], 0

        def flush():
            nonlocal pending, pending_rows, first_of_day
            if not pending:
                return
            replace = predicate_for(day.isoformat(), [], None) if (exists and t.mode == "overwrite-dates" and first_of_day) else None
            commit(pa.concat_tables(pending) if len(pending) > 1 else pending[0], replace)
            pending, pending_rows, first_of_day = [], 0, False

        for part in parts:
            first_of_part = True
            for rows in _chunks(t, part):
                table = arrow(day, part, rows)
                if per_part:
                    replace = predicate_for(day.isoformat(), t.partition_names, part.values) if (exists and first_of_part) else None
                    commit(table, replace)
                    first_of_part = False
                else:
                    pending.append(table)
                    pending_rows += len(rows)
                    if pending_rows >= t.batch_rows:
                        flush()
            done += 1
            if progress:
                progress(done, total)
        flush()
    version = DeltaTable(t.uri).version() if exists else None
    return {"commits": commits, "version": version}
