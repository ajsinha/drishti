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

"""The Notebook (docs/guides/USER_GUIDE.md › The Notebook): the masked extract, the SQL cell and the notebook body.

An **extract** is the rows of a model (or one report visual) that *the person asking* may see: the server masks columns and filters rows for the reader
and the console only ever holds what it was given, cut to ``bi.notebook.max_rows``. Nothing here widens access: an extract is rebuilt for the reader on
every call and is never kept between calls. A SQL cell is Drishti SQL run by the server over the model; the console runs no SQL and no code of the
reader's (a Python cell runs on the server's compute pool)."""
from __future__ import annotations

import io
import re
from dataclasses import dataclass, field

import yaml

NOTEBOOK_KEYS = {"rachana", "notebook", "title", "description", "dataset", "fields", "extracts", "cells", "version", "sample", "tags", "endorsement", "owner", "audience"}
CELL_TYPES = ("sql", "python")
CUT_CELLS = ("slice",)                                 # a cell of a cut kind in an older notebook: dropped with a note, never an error
_SAFE = re.compile(r"[^A-Za-z0-9_]")


class NotebookError(Exception):
    """A refusal of the Notebook with the DRS code to answer with (the Python items' codes: 9070 the request is invalid, 9072 a limit, 9073 refused, 9074 not available)."""

    def __init__(self, code: str, status: int, detail: str):
        super().__init__(detail)
        self.code, self.status, self.detail = code, status, detail


@dataclass
class Limits:
    """The Notebook's limits, all from the console's ``bi.notebook`` settings."""
    max_rows: int = 10_000
    max_fields: int = 60
    sql_max_rows: int = 5_000
    sql_max_chars: int = 20_000
    cell_timeout: int = 20
    cell_memory: int = 512
    cell_max_chars: int = 65_536
    max_cells: int = 60
    max_extracts: int = 6

    @classmethod
    def from_settings(cls, settings) -> "Limits":
        def g(key, default, lo, hi):
            try:
                return max(lo, min(hi, type(default)(settings.get("bi.notebook." + key, default))))
            except (TypeError, ValueError):
                return default
        return cls(max_rows=g("max_rows", 10_000, 10, 100_000), max_fields=g("max_fields", 60, 1, 500), sql_max_rows=g("sql_max_rows", 5_000, 1, 100_000),
                   sql_max_chars=g("sql_max_chars", 20_000, 100, 200_000),
                   cell_timeout=g("cell_timeout_seconds", 20, 1, 60), cell_memory=g("cell_memory_mb", 512, 64, 4096),
                   cell_max_chars=g("cell_max_chars", 65_536, 100, 500_000), max_cells=g("max_cells", 60, 1, 500), max_extracts=g("max_extracts", 6, 1, 20))


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
    (ARCHITECTURE.md §28 › The model layer: the specification of wave W1, section 8); a catalogue id (``bi-...``) is sent as it is (a version 2 dataset's implicit model)."""
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
        raise NotebookError("DRS-9070", 422, "send fields: a list of the dataset's columns, such as [\"trade.desk\", \"trade.mtm\"]")
    if len(fields) > limits.max_fields:
        raise NotebookError("DRS-9072", 413, f"{len(fields)} fields asked; the limit is {limits.max_fields} (bi.notebook.max_fields)")
    return fields


# ---- Drishti SQL on the server (ARCHITECTURE.md §28 3.4: the default engine of a SQL cell) -----------------------------------------
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
        raise NotebookError("DRS-9070", 422, "write one SELECT over the model's tables (its aliases), with MEASURE('name') for a measure")
    if len(sql) > limits.sql_max_chars:
        raise NotebookError("DRS-9072", 413, f"the SQL is {len(sql):,} characters; the limit is {limits.sql_max_chars:,} (bi.notebook.sql_max_chars)")
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
    """The body of a ``notebook`` item: YAML ``{rachana: 1, notebook, dataset, fields, cells: [{type: sql | python, source}]}``. Raises :class:`NotebookError`.
    A cell of a cut kind (a ``slice`` of an older notebook: slicing is the Pivot's) is left out and named in ``notes``, so the notebook still opens."""
    try:
        doc = yaml.safe_load(text or "") or {}
    except yaml.YAMLError as e:
        raise NotebookError("DRS-9070", 422, f"the notebook is not YAML: {e}") from None
    if not isinstance(doc, dict):
        raise NotebookError("DRS-9070", 422, "a notebook is a YAML mapping")
    extra = sorted(set(doc) - NOTEBOOK_KEYS)
    if extra:
        raise NotebookError("DRS-9070", 422, f"unknown notebook key(s): {', '.join(extra)}")
    cells = doc.get("cells") or []
    if not isinstance(cells, list) or len(cells) > limits.max_cells:
        raise NotebookError("DRS-9072", 413, f"a notebook holds a list of up to {limits.max_cells} cells (bi.notebook.max_cells)")
    kept, notes = [], []
    for i, c in enumerate(cells):
        if isinstance(c, dict) and c.get("type") in CUT_CELLS:
            notes.append(f"cell {i + 1} was a slice: slicing is done in the Pivot now, so it is left out")
            continue
        if not isinstance(c, dict) or c.get("type") not in CELL_TYPES:
            raise NotebookError("DRS-9070", 422, f"cell {i + 1}: type is one of {', '.join(CELL_TYPES)}")
        if not isinstance(c.get("source"), str) or len(c["source"]) > limits.cell_max_chars:
            raise NotebookError("DRS-9072", 422, f"cell {i + 1}: source is text up to {limits.cell_max_chars:,} characters")
        kept.append(c)
    if notes:
        doc["cells"] = kept
        doc["notes"] = notes
    if doc.get("fields") is not None:
        check_fields(doc["fields"], limits)
    if doc.get("extracts") is not None:
        check_extracts(doc["extracts"], limits)
    return doc


def check_extracts(specs, limits: Limits) -> list[dict]:
    """The extra extracts of a notebook: ``[{name, dataset, fields}]``, each a masked extract of a dataset that a cell reads as ``extract("<name>")``."""
    if not isinstance(specs, list) or len(specs) > limits.max_extracts:
        raise NotebookError("DRS-9072", 413, f"a notebook holds a list of up to {limits.max_extracts} extracts (bi.notebook.max_extracts)")
    seen = set()
    for i, s in enumerate(specs):
        if not isinstance(s, dict) or not isinstance(s.get("dataset"), str) or not s["dataset"].strip():
            raise NotebookError("DRS-9070", 422, f"extract {i + 1}: send {{name, dataset, fields}}")
        name = s.get("name")
        if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.\- ]{0,63}", name):
            raise NotebookError("DRS-9070", 422, f"extract {i + 1}: name is 1 to 64 letters, digits, space, hyphen, dot or underscore")
        if name in seen or _SAFE.sub("_", name) == "extract":
            raise NotebookError("DRS-9070", 422, f"extract {i + 1}: the name '{name}' is already taken (and 'extract' is the first extract's)")
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
    """A Python cell that joins several extracts as the YAML of a Python Transform draft (REPORTS_GUIDE.md › Python in BI: measures, visuals and data prep written in Python, section 13): each ``extract("x")`` becomes the function's
    parameter for that extract, ``result`` is returned and must be a pandas DataFrame. The inputs are the parameters' names, in order: the inputs of the Python
    dataset that runs it (2.2: each a model query); nothing is published."""
    if not extracts:
        raise NotebookError("DRS-9070", 422, "a Transform reads datasets: open the extracts the cell uses first")
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
            raise NotebookError("DRS-9070", 422, f"the cell reads extract(\"{key}\"), which is not one of the notebook's extracts ({', '.join(by_name)})")
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
    """A Python cell as the YAML of a Python Measure or Visual draft (REPORTS_GUIDE.md): the cell's ``df`` becomes the function's ``table``.

    A measure's function must return one number; a visual's an ECharts option, SVG or PNG. The author checks and edits the draft before publishing;
    nothing is published by promoting."""
    if interface not in ("measure", "visual"):
        raise NotebookError("DRS-9070", 422, "interface is measure or visual")
    entry = "compute" if interface == "measure" else "render"
    body = "\n".join(("    " + ln) if ln.strip() else "" for ln in cell_source.splitlines()) or "    pass"
    ret = "    return result" if "result" in cell_source else "    return None"
    source = f"def {entry}(table, params):\n    df = table\n{body}\n{ret}\n"
    doc: dict = {"rachana": 1, "python": re.sub(r"[^A-Za-z0-9_-]", "-", name).strip("-")[:64] or "from-analyse", "title": name, "interface": interface,
                 "inputs": columns, "packages": packages, "target": "server", "source": source}
    if interface == "visual":
        doc["output"] = "echarts"
    return yaml.safe_dump(doc, sort_keys=False, default_flow_style=None, width=200)
