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

"""Analyse (Rupaka phase 9, docs/guides/ANALYSE.md): the masked extract, the SQL cell and the notebook body.

An **extract** is the rows of a dataset (or one report visual) that *the person asking* may see: the server masks columns and filters rows for the reader
and the console only ever holds what it was given, cut to ``bi.explore.max_rows``. Nothing here widens access: an extract is rebuilt for the reader on
every call and is never kept between calls.

The SQL cell runs over an extract in an in-memory SQLite database (one table, ``extract``) that is read-only by construction: an authorizer refuses
everything except SELECT, a deadline interrupts a long query, and the result is cut to ``bi.explore.sql_max_rows``. (DuckDB-Wasm in the browser is the
planned engine, docs/guides/ANALYSE.md section 12; it is not vendored, so SQL runs here.)"""
from __future__ import annotations

import io
import re
import sqlite3
import time
from dataclasses import dataclass, field

import yaml

NOTEBOOK_KEYS = {"rachana", "notebook", "title", "description", "dataset", "fields", "cells", "version"}
CELL_TYPES = ("slice", "sql", "python")
_SAFE = re.compile(r"[^A-Za-z0-9_]")


class ExploreError(Exception):
    """A refusal of Analyse with the DRS code to answer with (the Python items' codes: 9070 the request is invalid, 9072 a limit, 9073 refused, 9074 not available)."""

    def __init__(self, code: str, status: int, detail: str):
        super().__init__(detail)
        self.code, self.status, self.detail = code, status, detail


@dataclass
class Limits:
    """Analyse's limits, all from the console's ``bi.explore`` settings."""
    max_rows: int = 10_000
    max_fields: int = 60
    sql_max_rows: int = 5_000
    sql_max_seconds: float = 5.0
    sql_max_chars: int = 20_000
    cell_timeout: int = 20
    cell_memory: int = 512
    cell_max_chars: int = 65_536
    max_cells: int = 60
    quant_engine: str = "stand-in"

    @classmethod
    def from_settings(cls, settings) -> "Limits":
        def g(key, default, lo, hi):
            try:
                return max(lo, min(hi, type(default)(settings.get("bi.explore." + key, default))))
            except (TypeError, ValueError):
                return default
        return cls(max_rows=g("max_rows", 10_000, 10, 100_000), max_fields=g("max_fields", 60, 1, 500), sql_max_rows=g("sql_max_rows", 5_000, 1, 100_000),
                   sql_max_seconds=g("sql_max_seconds", 5.0, 0.5, 60.0), sql_max_chars=g("sql_max_chars", 20_000, 100, 200_000),
                   cell_timeout=g("cell_timeout_seconds", 20, 1, 60), cell_memory=g("cell_memory_mb", 512, 64, 4096),
                   cell_max_chars=g("cell_max_chars", 65_536, 100, 500_000), max_cells=g("max_cells", 60, 1, 500),
                   quant_engine=str(settings.get("bi.explore.quant_engine", "stand-in")))


@dataclass
class Extract:
    """The rows one reader may see, named for SQL and Python (``desk``, not ``trade.desk``), cut to the row limit."""
    columns: list[str]
    types: list[str]
    rows: list[list]
    masked: list[str] = field(default_factory=list)
    total: int = 0                       # rows the server sent (before the console's cut)
    truncated: bool = False
    arrow: bytes | None = None           # the same rows as an Arrow IPC stream, for the Python runner

    def as_json(self) -> dict:
        return {"columns": [{"name": c, "type": t, "masked": c in self.masked} for c, t in zip(self.columns, self.types)], "rows": self.rows,
                "rowCount": len(self.rows), "total": self.total, "truncated": self.truncated, "masked": self.masked}


def short_names(columns: list[str]) -> list[str]:
    """``trade.desk`` as ``desk``; a name two columns share keeps its table (``trade_desk``). Characters SQL cannot use become ``_``."""
    last = [c.split(".", 1)[-1] for c in columns]
    out = []
    for c, s in zip(columns, last):
        name = _SAFE.sub("_", c.replace(".", "_") if last.count(s) > 1 else s) or "col"
        out.append(("c" + name) if name[0].isdigit() else name)
    return out


def from_arrow(content: bytes, limit: int, masked: list[str] | None = None) -> Extract:
    """An extract from the server's Arrow stream: the row ``id`` is dropped, names are shortened, and rows past ``limit`` are cut."""
    import pyarrow as pa
    table = pa.ipc.open_stream(io.BytesIO(content)).read_all()
    keep = [i for i, n in enumerate(table.column_names) if n.split(".")[-1] != "id"]
    table = table.select(keep)
    original = list(table.column_names)
    names = short_names(original)
    table = table.rename_columns(names)
    total = table.num_rows
    if total > limit:
        table = table.slice(0, limit)
    sink = io.BytesIO()
    with pa.ipc.new_stream(sink, table.schema) as w:
        w.write_table(table)
    cols = table.to_pydict()
    rows = [list(r) for r in zip(*[cols[n] for n in names])] if names else []
    short = dict(zip(original, names))
    return Extract(names, [_type(str(f.type)) for f in table.schema], rows, [short.get(m, m.split(".")[-1]) for m in (masked or [])], total, total > limit, sink.getvalue())


def from_json(answer: dict, limit: int) -> Extract:
    """An extract from the server's JSON answer (used when pyarrow is not installed on the console)."""
    cols = [c for c in answer.get("columns") or [] if c["name"].split(".")[-1] != "id"]
    keep = [i for i, c in enumerate(answer.get("columns") or []) if c in cols]
    names = short_names([c["name"] for c in cols])
    rows = [[r[i] for i in keep] for r in (answer.get("rows") or [])]
    masked = [n for n, c in zip(names, cols) if c.get("masked")]
    return Extract(names, [c.get("type", "string") for c in cols], rows[:limit], masked, len(rows), len(rows) > limit)


def _type(arrow_type: str) -> str:
    return "integer" if arrow_type.startswith(("int", "uint")) else "decimal" if arrow_type.startswith(("float", "double", "decimal")) else "string"


def query_body(fields: list[str], limit: int, filters=None, dates=None) -> dict:
    """The dataset query for an extract: the fields, then the table's ``id`` so the rows are the table's own, not grouped."""
    table = fields[0].split(".", 1)[0] if "." in fields[0] else None
    body: dict = {"fields": list(fields) + ([table + ".id"] if table and (table + ".id") not in fields else []), "limit": limit}
    if filters:
        body["filters"] = filters
    if dates:
        body["dates"] = dates
    return body


def check_fields(fields, limits: Limits) -> list[str]:
    if not isinstance(fields, list) or not fields or not all(isinstance(f, str) and re.fullmatch(r"[A-Za-z0-9_.\- ]{1,128}", f) for f in fields):
        raise ExploreError("DRS-9070", 422, "send fields: a list of the dataset's columns, such as [\"trade.desk\", \"trade.mtm\"]")
    if len(fields) > limits.max_fields:
        raise ExploreError("DRS-9072", 413, f"{len(fields)} fields asked; the limit is {limits.max_fields} (bi.explore.max_fields)")
    return fields


# ---- SQL ---------------------------------------------------------------------------------------------------------------------
_ALLOWED = {sqlite3.SQLITE_SELECT, sqlite3.SQLITE_READ, sqlite3.SQLITE_FUNCTION, sqlite3.SQLITE_RECURSIVE}
_DENIED_FUNCTIONS = {"load_extension", "readfile", "writefile", "edit", "fts3_tokenizer"}


def _authorizer(action, arg1, arg2, db, source):
    if action == sqlite3.SQLITE_FUNCTION and str(arg2).lower() in _DENIED_FUNCTIONS:
        return sqlite3.SQLITE_DENY
    return sqlite3.SQLITE_OK if action in _ALLOWED else sqlite3.SQLITE_DENY


def run_sql(extract: Extract, sql: str, limits: Limits) -> dict:
    """Runs one SELECT over ``extract`` (the table ``extract``). Answers ``{columns, rows, rowCount, truncated, ms}``; refusals are :class:`ExploreError`."""
    sql = (sql or "").strip().rstrip(";").strip()
    if not sql:
        raise ExploreError("DRS-9070", 422, "write a SELECT over the table extract")
    if len(sql) > limits.sql_max_chars:
        raise ExploreError("DRS-9072", 413, f"the SQL is {len(sql):,} characters; the limit is {limits.sql_max_chars:,} (bi.explore.sql_max_chars)")
    t0 = time.monotonic()
    db = sqlite3.connect(":memory:", check_same_thread=False)
    try:
        kinds = {"integer": "INTEGER", "decimal": "REAL"}
        db.execute("CREATE TABLE extract (" + ", ".join(f'"{c}" {kinds.get(t, "TEXT")}' for c, t in zip(extract.columns, extract.types)) + ")")
        if extract.rows:
            db.executemany("INSERT INTO extract VALUES (" + ",".join("?" * len(extract.columns)) + ")", extract.rows)
        for name, value in (("SQLITE_LIMIT_SQL_LENGTH", limits.sql_max_chars), ("SQLITE_LIMIT_LENGTH", 1_000_000), ("SQLITE_LIMIT_COMPOUND_SELECT", 20),
                            ("SQLITE_LIMIT_ATTACHED", 0), ("SQLITE_LIMIT_FUNCTION_ARG", 16)):
            if hasattr(sqlite3, name):
                db.setlimit(getattr(sqlite3, name), value)
        db.set_authorizer(_authorizer)
        deadline = time.monotonic() + limits.sql_max_seconds
        db.set_progress_handler(lambda: 1 if time.monotonic() > deadline else 0, 2000)
        try:
            cur = db.execute(sql)
            rows = cur.fetchmany(limits.sql_max_rows + 1)
        except (sqlite3.Warning, sqlite3.Error) as e:
            text = str(e)
            if "interrupted" in text:
                raise ExploreError("DRS-9072", 504, f"the query ran longer than {limits.sql_max_seconds:g} s and was stopped (bi.explore.sql_max_seconds)") from None
            if "not authorized" in text:
                raise ExploreError("DRS-9073", 403, "only a single SELECT over the table extract is allowed") from None
            raise ExploreError("DRS-9070", 422, "only one statement is allowed" if "one statement" in text else text) from None
        if cur.description is None:
            raise ExploreError("DRS-9070", 422, "the statement returned no rows: use a SELECT")
        cut = len(rows) > limits.sql_max_rows
        rows = [[(v.hex() if isinstance(v, bytes) else v) for v in r] for r in rows[:limits.sql_max_rows]]
        return {"columns": [d[0] for d in cur.description], "rows": rows, "rowCount": len(rows), "truncated": cut, "ms": round((time.monotonic() - t0) * 1000)}
    finally:
        db.close()


# ---- notebooks -----------------------------------------------------------------------------------------------------------------
def parse_notebook(text: str, limits: Limits) -> dict:
    """The body of a ``notebook`` item: YAML ``{rachana: 1, notebook, dataset, fields, cells: [{type, source | slice}]}``. Raises :class:`ExploreError`."""
    try:
        doc = yaml.safe_load(text or "") or {}
    except yaml.YAMLError as e:
        raise ExploreError("DRS-9070", 422, f"the notebook is not YAML: {e}") from None
    if not isinstance(doc, dict):
        raise ExploreError("DRS-9070", 422, "a notebook is a YAML mapping")
    extra = sorted(set(doc) - NOTEBOOK_KEYS)
    if extra:
        raise ExploreError("DRS-9070", 422, f"unknown notebook key(s): {', '.join(extra)}")
    cells = doc.get("cells") or []
    if not isinstance(cells, list) or len(cells) > limits.max_cells:
        raise ExploreError("DRS-9072", 413, f"a notebook holds a list of up to {limits.max_cells} cells (bi.explore.max_cells)")
    for i, c in enumerate(cells):
        if not isinstance(c, dict) or c.get("type") not in CELL_TYPES:
            raise ExploreError("DRS-9070", 422, f"cell {i + 1}: type is one of {', '.join(CELL_TYPES)}")
        if c["type"] == "slice":
            if not isinstance(c.get("slice"), dict):
                raise ExploreError("DRS-9070", 422, f"cell {i + 1}: a slice cell holds slice: {{rows, columns, values, filters}}")
        elif not isinstance(c.get("source"), str) or len(c["source"]) > limits.cell_max_chars:
            raise ExploreError("DRS-9072", 422, f"cell {i + 1}: source is text up to {limits.cell_max_chars:,} characters")
    if doc.get("fields") is not None:
        check_fields(doc["fields"], limits)
    return doc


def promote_python(cell_source: str, name: str, interface: str, columns: list[str], packages: list[str]) -> str:
    """A Python cell as the YAML of a Python Measure or Visual draft (PYTHON_BI.md): the cell's ``df`` becomes the function's ``table``.

    A measure's function must return one number; a visual's an ECharts option, SVG or PNG. The author checks and edits the draft before publishing;
    nothing is published by promoting."""
    if interface not in ("measure", "visual"):
        raise ExploreError("DRS-9070", 422, "interface is measure or visual")
    entry = "compute" if interface == "measure" else "render"
    body = "\n".join(("    " + ln) if ln.strip() else "" for ln in cell_source.splitlines()) or "    pass"
    ret = "    return result" if "result" in cell_source else "    return None"
    source = f"def {entry}(table, params):\n    df = table\n{body}\n{ret}\n"
    doc: dict = {"rachana": 1, "python": re.sub(r"[^A-Za-z0-9_-]", "-", name).strip("-")[:64] or "from-analyse", "title": name, "interface": interface,
                 "inputs": columns, "packages": packages, "target": "server", "source": source}
    if interface == "visual":
        doc["output"] = "echarts"
    return yaml.safe_dump(doc, sort_keys=False, default_flow_style=None, width=200)
