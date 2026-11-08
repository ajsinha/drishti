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

"""How the Python tools read a Delta table's rows (docs/guides/DATA_LAKE_GUIDE.md section 14): every reader feature honoured, never a
half-read.

delta-rs's pyarrow reader (``DeltaTable.to_pyarrow_dataset``) reads a table only partly when the table uses column mapping with the
legacy protocol (reader version 2, as Databricks writes it: the data files' columns are ``col-<uuid>``, so every column reads as null),
and refuses deletion vectors, column mapping and v2 checkpoints under reader version 3. Such a table is read by Delta Kernel, the
server's own reader (``com.ash.drishti.deltalake.read.DeltaRowsCli`` in the server's exec jar): deleted rows left out, columns by
their physical names, checkpoints of any kind. Every other table is read by delta-rs, which reads it completely.

    read(table, version=None, date=None, columns=None) -> pyarrow.Table
"""
from __future__ import annotations

import json
import os
import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
for p in (str(HERE), str(ROOT / "tools")):
    if p not in sys.path:
        sys.path.append(p)

MAIN = "com.ash.drishti.deltalake.read.DeltaRowsCli"
# what delta-rs's pyarrow reader reads completely (deltalake.table.SUPPORTED_READER_FEATURES, 1.6.6)
PYARROW_READER_FEATURES = {"timestampNtz", "variantType", "variantType-preview"}


class ReadError(RuntimeError):
    pass


def needs_kernel(table: str, version: int | None = None) -> str | None:
    """Why delta-rs's pyarrow reader cannot read the table completely (then Kernel reads it), or None."""
    from deltalake import DeltaTable
    dt = DeltaTable(str(table), version=version)
    p = dt.protocol()
    mode = (dt.metadata().configuration or {}).get("delta.columnMapping.mode", "none")
    if mode and mode != "none":
        return f"column mapping ({mode})"
    missing = set(p.reader_features or []) - PYARROW_READER_FEATURES
    if p.min_reader_version >= 3 and missing:
        return "reader features " + ", ".join(sorted(missing))
    if p.min_reader_version > 3:
        return f"reader version {p.min_reader_version}"
    return None


def read(table, version: int | None = None, date: str | None = None, columns: list[str] | None = None, jar: str | None = None,
         java: str | None = None):
    """The table's rows (as of ``version``; one ``business_date`` when ``date`` is given; only ``columns`` when given)."""
    why = needs_kernel(str(table), version)
    if why is None:
        from deltalake import DeltaTable
        dt = DeltaTable(str(table), version=version)
        parts = [("business_date", "=", date)] if date and "business_date" in dt.metadata().partition_columns else None
        ds = dt.to_pyarrow_dataset(partitions=parts)
        return ds.to_table(columns=columns)
    return read_with_kernel(table, version, date, columns, jar, java)


_TYPES = None


def _arrow_type(name: str):
    import pyarrow as pa
    if name.startswith("array<") and name.endswith(">"):
        return pa.list_(_arrow_type(name[6:-1]))
    if name.startswith("decimal("):
        p, s = name[8:-1].split(",")
        return pa.decimal128(int(p), int(s))
    return {"string": pa.string(), "double": pa.float64(), "float": pa.float32(), "long": pa.int64(), "integer": pa.int32(),
            "short": pa.int16(), "byte": pa.int8(), "boolean": pa.bool_(), "date": pa.date32(), "timestamp": pa.timestamp("us", tz="UTC"),
            "timestamp_ntz": pa.timestamp("us")}[name]


def read_with_kernel(table, version=None, date=None, columns=None, jar=None, java=None):
    """The rows as Delta Kernel reads them, through the server's exec jar."""
    import datetime as dt
    import decimal
    import pyarrow as pa
    import writers
    from javacmd import java_cmd
    j = writers.find_jar(jar)
    jv = writers.find_java(java)
    if not j or not jv:
        raise ReadError("this table needs Delta Kernel (the server's reader) to be read completely: build the server's exec jar "
                        "(./mvnw -q package -DskipTests) or name it with DRISHTI_JAR, and Java 21")
    args = [f"-Xmx{os.environ.get('DRISHTI_LOADER_HEAP', '2g')}", f"-Dloader.main={MAIN}", "-jar", j, str(table)]
    if version is not None:
        args += ["--version", str(version)]
    if date:
        args += ["--date", str(date)]
    if columns:
        args += ["--columns", ",".join(columns)]
    r = subprocess.run(java_cmd(jv, args), capture_output=True, text=True, encoding="utf-8")
    if r.returncode != 0:
        said = [ln for ln in r.stderr.splitlines() if ln.startswith("deltaread:")]
        raise ReadError((said[-1] if said else r.stderr.strip()[-600:]) or f"exit {r.returncode}")
    lines = r.stdout.splitlines()
    head = next((i for i, ln in enumerate(lines) if ln.startswith('{"schema"')), None)   # the engine's log lines may come first
    if head is None:
        raise ReadError("the Java reader gave no rows: " + r.stderr.strip()[-600:])
    schema = json.loads(lines[head])["schema"]
    cols = [[] for _ in schema]
    for ln in lines[head + 1:]:
        if not ln.startswith("["):
            continue                                                # a log line, never a row (a row is one JSON array)
        row = json.loads(ln)
        for i, v in enumerate(row):
            cols[i].append(v)
    arrays = []
    for f, values in zip(schema, cols):
        t = _arrow_type(f["type"])
        if f["type"] == "date":
            values = [None if v is None else dt.date.fromisoformat(v) for v in values]
        elif f["type"].startswith("decimal("):
            values = [None if v is None else decimal.Decimal(v) for v in values]
        elif f["type"] in ("double", "float"):
            values = [float(v) if isinstance(v, str) else v for v in values]
        arrays.append(pa.array(values, t))
    return pa.table(arrays, names=[f["name"] for f in schema]) if schema else pa.table({})
