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

"""The two engines that write a staged table into Delta Lake: the second half of ``lake build``.

``drishti``    Drishti's own Java writer, in the server's exec jar (``java -jar drishti-server-<v>-exec.jar lake write --manifest M``):
               Delta Kernel and Parquet, no Python dependency; a whole table (every business date of the run) is ONE commit, so a
               reader sees all of it or none of it; old files of a replaced date are removed in the same commit.
``deltalake``  the Python deltalake package (delta-rs) with pyarrow: one commit per business date and batch of rows (every partition
               of the date together; one per partition with ``overwrite-partitions``), a checkpoint every ten commits; the first
               batch of a replaced date or partition replaces it (``predicate`` overwrite), the next ones append.

Both write the same table: ``id`` STRING, ``doc`` STRING (the whole document, nested arrays intact), ``business_date`` DATE, the
partition columns (STRING, Hive folders ``business_date=2026-10-01/desk=RATES/``) and the promoted columns (DOUBLE when every value
is a number, else STRING), partitioned by business_date then the partition columns."""
from __future__ import annotations

import json
import os
import pathlib
import shutil
import subprocess
import sys
import zipfile

from stage import Part, read_rows

LAKE_CLASS = "com/ash/drishti/deltalake/write/LakeWriter.class"
ROOT = pathlib.Path(__file__).resolve().parents[2]
if str(ROOT / "tools") not in sys.path:
    sys.path.append(str(ROOT / "tools"))
from javacmd import java_cmd  # noqa: E402


class WriteError(RuntimeError):
    pass


def predicate_for(day: str, names: list[str], values: tuple | None) -> str:
    """The SQL predicate of one business date (values None) or of one partition."""
    parts = [f"business_date = '{day}'"]
    for n, v in zip(names, values or ()):
        parts.append(f"{n} IS NULL" if v is None else f"{n} = '" + v.replace("'", "''") + "'")
    return " AND ".join(parts)


# ------------------------------------------------------------------------------------------------ choosing an engine

def jar_has_writer(jar: str) -> bool:
    try:
        with zipfile.ZipFile(jar) as z:
            names = set(z.namelist())
            if LAKE_CLASS in names:
                return True
            # a Spring Boot exec jar keeps the modules as nested jars
            nested = [n for n in names if n.startswith("BOOT-INF/lib/drishti-deltalake-") and n.endswith(".jar")]
            for n in nested:
                with z.open(n) as inner, zipfile.ZipFile(inner) as zi:
                    if LAKE_CLASS in zi.namelist():
                        return True
    except (OSError, zipfile.BadZipFile):
        return False
    return False


def find_jar(explicit: str | None = None) -> str | None:
    """--jar, then DRISHTI_JAR, then the newest drishti-server/target/drishti-server-*-exec.jar of this checkout."""
    for j in (explicit, os.environ.get("DRISHTI_JAR")):
        if j:
            return j if os.path.isfile(j) else None
    found = sorted((ROOT / "drishti-server" / "target").glob("drishti-server-*-exec.jar"), key=lambda p: p.stat().st_mtime)
    return str(found[-1]) if found else None


def find_java(explicit: str | None = None) -> str | None:
    if explicit:
        return explicit
    home = os.environ.get("JAVA_HOME")
    for cand in ([os.path.join(home, "bin", "java")] if home else []) + ["/usr/lib/jvm/java-21-openjdk-amd64/bin/java"]:
        if os.path.isfile(cand):
            return cand
    return shutil.which("java")


def deltalake_available() -> bool:
    try:
        import deltalake  # noqa: F401
        import pyarrow  # noqa: F401
        return True
    except ImportError:
        return False


def java_command(jar: str | None, java: str | None) -> tuple[str, list[str]] | None:
    """How to start Drishti's writer, as (java, arguments): the server's exec jar (``java -jar J lake ...``), or for development a class
    path in DRISHTI_LAKE_CLASSPATH (``java -cp CP com.ash.drishti.deltalake.write.LakeCli lake ...``). javacmd.java_cmd starts it."""
    java_bin = find_java(java)
    if not java_bin:
        return None
    heap = "-Xmx" + os.environ.get("DRISHTI_LAKE_HEAP", "1g")
    cp = os.environ.get("DRISHTI_LAKE_CLASSPATH")
    if cp and not jar:
        return java_bin, [heap, "-cp", cp, "com.ash.drishti.deltalake.write.LakeCli"]
    j = find_jar(jar)
    if j and jar_has_writer(j):
        return java_bin, [heap, "-jar", j]
    return None


def choose(engine: str, jar: str | None = None, java: str | None = None) -> tuple[str, str]:
    """(engine, why): auto is Drishti's writer when a jar with it is found, else deltalake when it is installed."""
    if engine in ("auto", "drishti"):
        cmd = java_command(jar, java)
        if cmd:
            args = cmd[1]
            return "drishti", "Drishti's Java writer in " + (os.path.basename(args[-1]) if args[-2] == "-jar" else "DRISHTI_LAKE_CLASSPATH")
        if engine == "drishti":
            raise WriteError("--engine drishti needs the server's exec jar with the lake writer (--jar, DRISHTI_JAR or a built "
                             "drishti-server/target/drishti-server-*-exec.jar) and Java 21")
    if deltalake_available():
        return "deltalake", "the Python deltalake package" + ("; no server jar with the lake writer was found" if engine == "auto" else "")
    raise WriteError("no engine: build the server (its exec jar carries Drishti's writer) or install deltalake and pyarrow "
                     "(uv run --with deltalake --with pyarrow ...)")


# ------------------------------------------------------------------------------------------------ the table

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
            raise WriteError(f"{uri}: the table's log starts at a checkpoint; install deltalake to read it (uv run --with deltalake ...)")
        from deltalake import DeltaTable
        dt = DeltaTable(uri)
        meta = {"partitionColumns": dt.metadata().partition_columns, "schemaString": dt.schema().to_json()}
    schema = json.loads(meta["schemaString"])
    types = {f["name"]: ("double" if f["type"] in ("double", "float") else "string") for f in schema["fields"]}
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
            cols[n] = pa.array([v] * len(rows), pa.string())
        for name, kind in t.promoted.items():
            cols[name] = pa.array([_cell(r[2].get(name), kind) for r in rows], pa.float64() if kind == "double" else pa.string())
        return pa.table(cols)

    def commit(table, replace):
        nonlocal exists, commits
        kw: dict = {"partition_by": t.partition_by, "writer_properties": WriterProperties(compression="SNAPPY")}
        if not exists:
            kw["configuration"] = {"delta.dataSkippingStatsColumns": ",".join(t.stats_columns)}
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


# ------------------------------------------------------------------------------------------------ Drishti's Java writer

def write_drishti(t: Table, by_day: dict, workdir: pathlib.Path, jar: str | None = None, java: str | None = None, progress=None) -> dict:
    check_layout(t)
    base = java_command(jar, java)
    if not base:
        raise WriteError("Drishti's writer needs the server's exec jar and Java 21")
    if t.sort:                                          # the Java writer reads each part's file as it is: sort the small ones here
        for parts in by_day.values():
            for part in parts:
                if part.rows <= t.batch_rows:
                    rows = next(_chunks(t, part), [])
                    with open(part.file, "w", encoding="utf-8") as fh:
                        for r in rows:
                            fh.write(json.dumps(r, ensure_ascii=False, separators=(",", ":")) + "\n")
    manifest = {
        "table": str(pathlib.Path(t.uri).resolve()),
        "partitionColumns": t.partition_by,
        "columns": [{"name": "id", "type": "string"}, {"name": "doc", "type": "string"}, {"name": "business_date", "type": "date"},
                    *[{"name": n, "type": "string"} for n in t.partition_names],
                    *[{"name": n, "type": k} for n, k in t.promoted.items()]],
        "promoted": list(t.promoted),
        "mode": t.mode,
        "batchRows": t.batch_rows,
        "statsColumns": t.stats_columns,
        "parts": [{"file": str(p.file.resolve()), "rows": p.rows,
                   "values": {"business_date": d.isoformat(), **{n: v for n, v in zip(t.partition_names, p.values)}}}
                  for d, parts in by_day.items() for p in parts],
    }
    mf = workdir / "manifest.json"
    mf.write_text(json.dumps(manifest), encoding="utf-8")
    # the writer's temporary files go beside the staged rows, in the lake, never the system temp folder: drishti.tmp-dir for Drishti's own,
    # -Djava.io.tmpdir= (javacmd) for the native libraries it unpacks, which only the command line moves
    java_bin, args = base
    cmd = java_cmd(java_bin, [f"-Ddrishti.tmp-dir={workdir / 'tmp'}", *args, "lake", "write", "--manifest", str(mf)], tmp=workdir / "tmp")
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    result: dict = {}
    total = len(manifest["parts"])
    for line in proc.stdout:
        line = line.strip()
        if not line.startswith("{"):
            continue
        msg = json.loads(line)
        if "partsDone" in msg and progress:
            progress(msg["partsDone"], total)
        if msg.get("done"):
            result = msg
    err = proc.stderr.read()
    if proc.wait() != 0:
        raise WriteError("Drishti's writer failed: " + (err.strip().splitlines()[-1] if err.strip() else f"exit {proc.returncode}"))
    return {"commits": result.get("commits", 1), "version": result.get("version"), "files": result.get("files")}
