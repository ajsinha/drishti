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
everything except SELECT, a deadline interrupts a long query, and the result is cut to ``bi.explore.sql_max_rows``. (DuckDB-Wasm in the browser was the
planned engine, docs/guides/ANALYSE.md section 12; it is not vendored, so SQL runs here.)"""
from __future__ import annotations

import io
import re
import sqlite3
import time
from dataclasses import dataclass, field

import yaml

NOTEBOOK_KEYS = {"rachana", "notebook", "title", "description", "dataset", "fields", "extracts", "cells", "version", "sample", "tags", "endorsement", "owner", "audience"}
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
    max_extracts: int = 6
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
                   cell_max_chars=g("cell_max_chars", 65_536, 100, 500_000), max_cells=g("max_cells", 60, 1, 500), max_extracts=g("max_extracts", 6, 1, 20),
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


def model_of(source: str) -> str:
    """The model an extract's source names: ``mq:<model>@<major>`` is that model, ``dataset:<name>`` the dataset's implicit model, and a bare name
    (a notebook saved before 2.2, or a shipped file's id ``file:dataset:<name>``) the model the converter made of that 2.1 dataset, ``<name>@1``
    (BI_MODEL_SPEC.md section 8); a catalogue id (``bi-...``) is sent as it is (a version 2 dataset's implicit model)."""
    s = str(source or "").strip()
    if s.startswith("mq:"):
        return s[3:]
    if s.startswith("file:dataset:"):
        s = s[len("file:dataset:"):]
    if s.startswith(("dataset:", "bi-")) or "@" in s or not s:
        return s
    return s + "@1"


def mq_rows(model: str, fields: list[str], limit: int, filters=None, dates=None) -> dict:
    """The model query of an extract: the fields' rows (shape rows, no measures), the filters as ``where``, and the old ``dates``
    (``{from, to}`` or one date) as the query's ``time``."""
    mq: dict = {"mq": 1, "model": model, "select": {"fields": list(fields), "measures": []}, "shape": "rows", "limit": limit}
    if filters:
        mq["where"] = filters
    if isinstance(dates, dict) and dates.get("from"):
        mq["time"] = {"window": {"from": str(dates["from"]), "to": str(dates.get("to") or dates["from"])}}
    elif isinstance(dates, str) and dates:
        mq["time"] = {"as-of": dates}
    return mq


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


def table_name(name: str) -> str:
    """The SQL table an extra extract is read as: its name with anything but letters, digits and underscore replaced (``extract`` itself is the primary extract)."""
    t = _SAFE.sub("_", name) or "x"
    return ("x" + t) if t[0].isdigit() else t


def _load(db: sqlite3.Connection, table: str, extract: Extract) -> None:
    kinds = {"integer": "INTEGER", "decimal": "REAL"}
    db.execute(f'CREATE TABLE "{table}" (' + ", ".join(f'"{c}" {kinds.get(t, "TEXT")}' for c, t in zip(extract.columns, extract.types)) + ")")
    if extract.rows:
        db.executemany(f'INSERT INTO "{table}" VALUES (' + ",".join("?" * len(extract.columns)) + ")", extract.rows)


def run_sql(extract: Extract, sql: str, limits: Limits, extras: dict[str, Extract] | None = None) -> dict:
    """Runs one SELECT over ``extract`` (the table ``extract``) and, when the notebook has more extracts, over those too (a table each, named as the extract is).
    Answers ``{columns, rows, rowCount, truncated, ms}``; refusals are :class:`ExploreError`."""
    sql = (sql or "").strip().rstrip(";").strip()
    if not sql:
        raise ExploreError("DRS-9070", 422, "write a SELECT over the table extract")
    if len(sql) > limits.sql_max_chars:
        raise ExploreError("DRS-9072", 413, f"the SQL is {len(sql):,} characters; the limit is {limits.sql_max_chars:,} (bi.explore.sql_max_chars)")
    t0 = time.monotonic()
    db = sqlite3.connect(":memory:", check_same_thread=False)
    try:
        _load(db, "extract", extract)
        for name, other in (extras or {}).items():
            if table_name(name) != "extract":
                _load(db, table_name(name), other)
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


# ---- Drishti SQL on the server (BI_DESIGN.md 3.4: the default engine of a SQL cell) -----------------------------------------
def model_ref(dataset: str) -> str | None:
    """The model a notebook's extract reads, for Drishti SQL (``model_of``); None when the source names nothing."""
    d = str(dataset or "").strip()
    if d in ("mq:", "dataset:"):
        return None
    return model_of(d) or None


def dsql_request(sql: str, model: str, limits: Limits) -> dict:
    """The body of ``POST /api/v1/bi/sql`` for one cell: the statement, the model and one row more than the cell may show (to say it was cut)."""
    sql = (sql or "").strip().rstrip(";").strip()
    if not sql:
        raise ExploreError("DRS-9070", 422, "write one SELECT over the model's tables (its aliases), with MEASURE('name') for a measure")
    if len(sql) > limits.sql_max_chars:
        raise ExploreError("DRS-9072", 413, f"the SQL is {len(sql):,} characters; the limit is {limits.sql_max_chars:,} (bi.explore.sql_max_chars)")
    return {"sql": sql, "model": model, "limit": limits.sql_max_rows + 1}


def dsql_answer(answer: dict, limits: Limits, ms: int) -> dict:
    """The server's answer in the cell's shape: ``{columns, rows, rowCount, truncated, ms, engine, plan, masked, query}`` (column names only, as a cell shows)."""
    cols = [c.get("name") if isinstance(c, dict) else str(c) for c in answer.get("columns") or []]
    rows = list(answer.get("rows") or [])
    meta = answer.get("meta") if isinstance(answer.get("meta"), dict) else {}
    cut = len(rows) > limits.sql_max_rows
    rows = rows[:limits.sql_max_rows]
    return {"columns": cols, "rows": rows, "rowCount": len(rows), "truncated": cut, "ms": int(meta.get("millis") or ms), "engine": "server",
            "plan": meta.get("plan") or meta.get("planHash") or "", "masked": list(meta.get("masked") or []), "query": meta.get("query")}


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
    if doc.get("extracts") is not None:
        check_extracts(doc["extracts"], limits)
    return doc


def check_extracts(specs, limits: Limits) -> list[dict]:
    """The extra extracts of a notebook: ``[{name, dataset, fields}]``, each a masked extract of a dataset that a cell reads as ``extract("<name>")``."""
    if not isinstance(specs, list) or len(specs) > limits.max_extracts:
        raise ExploreError("DRS-9072", 413, f"a notebook holds a list of up to {limits.max_extracts} extracts (bi.explore.max_extracts)")
    seen = set()
    for i, s in enumerate(specs):
        if not isinstance(s, dict) or not isinstance(s.get("dataset"), str) or not s["dataset"].strip():
            raise ExploreError("DRS-9070", 422, f"extract {i + 1}: send {{name, dataset, fields}}")
        name = s.get("name")
        if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.\- ]{0,63}", name):
            raise ExploreError("DRS-9070", 422, f"extract {i + 1}: name is 1 to 64 letters, digits, space, hyphen, dot or underscore")
        if name in seen or table_name(name) == "extract":
            raise ExploreError("DRS-9070", 422, f"extract {i + 1}: the name '{name}' is already taken (and 'extract' is the first extract's)")
        seen.add(name)
        check_fields(s.get("fields"), limits)
    return specs


def transform_name(name: str, used: list[str]) -> str:
    """A Python parameter name for an extract: letters, digits and underscore, not starting with a digit, not already used."""
    t = _SAFE.sub("_", name).strip("_") or "frame"
    t = ("x" + t) if t[0].isdigit() else t
    n, out = 2, t
    while out in used:
        out, n = f"{t}_{n}", n + 1
    return out


def promote_transform(cell_source: str, name: str, extracts: list[dict], packages: list[str]) -> str:
    """A Python cell that joins several extracts as the YAML of a Python Transform draft (PYTHON_BI.md section 13): each ``extract("x")`` becomes the function's
    parameter for that extract, ``result`` is returned and must be a pandas DataFrame. The inputs are the parameters' names, in order: the inputs of the Python
    dataset that runs it (2.2: each a model query); nothing is published."""
    if not extracts:
        raise ExploreError("DRS-9070", 422, "a Transform reads datasets: open the extracts the cell uses first")
    params: list[str] = []
    by_name: dict[str, str] = {}
    for e in extracts:
        p = transform_name(e["name"], params)
        params.append(p)
        by_name[e["name"]] = p
        by_name.setdefault(e["dataset"], p)

    def swap(m):
        key = m.group(2)
        if key not in by_name:
            raise ExploreError("DRS-9070", 422, f"the cell reads extract(\"{key}\"), which is not one of the notebook's extracts ({', '.join(by_name)})")
        return by_name[key]
    body_src = re.sub(r"extract\(\s*([\"'])(.+?)\1\s*\)", swap, cell_source)
    lines = body_src.splitlines()
    if re.search(r"\bdf\b", body_src) and not re.search(r"^\s*df\s*=", body_src, re.M):
        lines.insert(0, f"df = {params[0]}")
    body = "\n".join(("    " + ln) if ln.strip() else "" for ln in lines) or "    pass"
    source = f"def run({', '.join(params)}):\n{body}\n    return result\n"
    doc: dict = {"rachana": 1, "python": re.sub(r"[^A-Za-z0-9_-]", "-", name).strip("-")[:64] or "from-analyse", "title": name, "interface": "transform",
                 "inputs": params, "packages": packages, "target": "server", "source": source}
    return yaml.safe_dump(doc, sort_keys=False, default_flow_style=None, width=200)


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


PIVOT_AGGS = ("sum", "avg", "count", "min", "max")
PIVOT_MAX_KEYS = 2_000


def pivot(extract: Extract, spec: dict) -> dict:
    """The Slice of Analyse, worked out on the console (wave B: the browser draws, it adds nothing up; BI_DESIGN.md P3). ``spec`` is
    ``{rows: [names], columns: [names], values: [{field, agg}], filters: [{field, value}]}`` over the extract's short names. Answers
    ``{header, body, used, total, distinct}``: the header and rows of the pivot table as the page shows it (row keys, then one cell per column key and
    value), the rows used after the filters, and up to 200 distinct values of each filter field for its picker."""
    idx = {c: i for i, c in enumerate(extract.columns)}
    rows_f = [str(x) for x in spec.get("rows") or [] if str(x) in idx]
    cols_f = [str(x) for x in spec.get("columns") or [] if str(x) in idx]
    vals = [{"field": str(v.get("field") or ""), "agg": str(v.get("agg") or "sum")} for v in spec.get("values") or [] if isinstance(v, dict)]
    vals = [v for v in vals if (not v["field"] or v["field"] in idx) and v["agg"] in PIVOT_AGGS] or [{"field": "", "agg": "count"}]
    filters = [(str(f.get("field")), str(f.get("value") or "")) for f in spec.get("filters") or [] if isinstance(f, dict) and str(f.get("field")) in idx]
    text = lambda v: "" if v is None else str(v)  # noqa: E731
    distinct = {}
    for name, _ in filters:
        seen = sorted({text(r[idx[name]]) for r in extract.rows})
        distinct[name] = seen[:200]
    kept = [r for r in extract.rows if all(not want or text(r[idx[f]]) == want for f, want in filters)]
    key = lambda r, fs: tuple("(blank)" if r[idx[f]] is None else str(r[idx[f]]) for f in fs)  # noqa: E731
    cells: dict = {}
    rk: dict = {}
    ck: dict = {}
    for r in kept:
        a, b = key(r, rows_f), key(r, cols_f)
        rk[a] = a
        ck[b] = b
        if len(rk) > PIVOT_MAX_KEYS or len(ck) > PIVOT_MAX_KEYS:
            raise ExploreError("DRS-9072", 413, f"the slice has more than {PIVOT_MAX_KEYS} row or column keys: put fewer fields in Rows or Columns, or filter")
        acc = cells.setdefault((a, b), [{"n": 0, "sum": 0.0, "min": None, "max": None} for _ in vals])
        for v, c in zip(vals, acc):
            x = r[idx[v["field"]]] if v["field"] else 1
            if x is None:
                continue
            c["n"] += 1
            try:
                num = float(x)
            except (TypeError, ValueError):
                continue
            c["sum"] += num
            c["min"] = num if c["min"] is None else min(c["min"], num)
            c["max"] = num if c["max"] is None else max(c["max"], num)

    def natural(k):
        return [(0, float(p), "") if re.fullmatch(r"-?\d+(\.\d+)?", p) else (1, 0.0, p.lower()) for p in k]

    def out(c, v):
        a = v["agg"]
        if not c or (a != "count" and c["min"] is None):              # nothing to add up (no rows, or every value masked or blank)
            return 0 if c and a == "count" else ""
        val = c["n"] if a == "count" else c["sum"] if a == "sum" else (c["sum"] / c["n"] if c["n"] else "") if a == "avg" else c["min"] if a == "min" else c["max"]
        return round(val, 6) if isinstance(val, float) else val

    row_keys = sorted(rk, key=natural) or [()]
    col_keys = sorted(ck, key=natural) if cols_f else [()]
    many = len(vals) > 1
    header = list(rows_f) if rows_f else ["(all rows)"]
    for c in col_keys:
        for v in vals:
            what = ((v["agg"] + " " + v["field"]) if v["field"] else "rows") if (many or not c) else ""
            header.append(" · ".join(x for x in (" / ".join(c), what) if x) or "rows")
    body = []
    for a in row_keys:
        row = list(a) if rows_f else ["(all)"]
        for b in col_keys:
            row.extend(out(c, v) for c, v in zip(cells.get((a, b)) or [None] * len(vals), vals))
        body.append(row)
    return {"header": header, "body": body, "used": len(kept), "total": len(extract.rows), "distinct": distinct, "engine": "console"}
