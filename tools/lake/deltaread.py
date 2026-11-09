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

"""How the Python tools read a Delta table's rows (docs/guides/DATA_LAKE_GUIDE.md section 14): always through Delta Kernel, the
standard reader and the one the server uses (``com.ash.drishti.deltalake.read.DeltaRowsCli`` in the server's exec jar), so Python reads
exactly what the server reads: deleted rows left out, columns by their physical names, checkpoints of any kind, timestamps without a
time zone. delta-rs stays for writing tables and for listing their files, and is not used to read rows.

    read(table, version=None, date=None, columns=None) -> pyarrow.Table
"""
from __future__ import annotations

import json
import os
import pathlib
import subprocess
import sys
import threading

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
for p in (str(HERE), str(ROOT / "tools")):
    if p not in sys.path:
        sys.path.append(p)

MAIN = "com.ash.drishti.deltalake.read.DeltaRowsCli"

class ReadError(RuntimeError):
    pass


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


def read(table, version: int | None = None, date: str | None = None, columns: list[str] | None = None, jar: str | None = None,
         java: str | None = None):
    """The table's rows as Delta Kernel reads them (as of ``version``; one ``business_date`` when ``date`` is given; only ``columns``
    when given), through the server's exec jar. Rows are streamed from the reader, never held twice."""
    import datetime as dt
    import decimal
    import pyarrow as pa
    import writers
    from javacmd import java_cmd
    j = writers.find_jar(jar)
    jv = writers.find_java(java)
    if not j or not jv:
        raise ReadError("Delta tables are read by Delta Kernel, the server's reader: build the server's exec jar "
                        "(./mvnw -q package -DskipTests) or name it with DRISHTI_JAR, and Java 21")
    args = [f"-Xmx{os.environ.get('DRISHTI_LOADER_HEAP', '2g')}", f"-Dloader.main={MAIN}", "-jar", j, str(table)]
    if version is not None:
        args += ["--version", str(version)]
    if date:
        args += ["--date", str(date)]
    if columns:
        args += ["--columns", ",".join(columns)]
    schema, cols = None, None
    with subprocess.Popen(java_cmd(jv, args), stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8") as p:
        err: list[str] = []
        drain = threading.Thread(target=lambda: err.extend(p.stderr), daemon=True)   # a full stderr pipe would stall the reader
        drain.start()
        for ln in p.stdout:
            if schema is None:
                if ln.startswith('{"schema"'):                      # the engine's log lines may come first
                    schema = json.loads(ln)["schema"]
                    cols = [[] for _ in schema]
                continue
            if not ln.startswith("["):
                continue                                            # a log line, never a row (a row is one JSON array)
            for c, v in zip(cols, json.loads(ln)):
                c.append(v)
        p.wait()
        drain.join(timeout=5)
    if p.returncode != 0:
        said = [ln.rstrip() for ln in err if ln.startswith("deltaread:")]
        raise ReadError((said[-1] if said else "".join(err).strip()[-600:]) or f"exit {p.returncode}")
    if schema is None:
        raise ReadError("the Java reader gave no rows: " + "".join(err).strip()[-600:])
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
