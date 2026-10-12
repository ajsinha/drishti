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

"""The tests a Python item ships with (docs/guides/PYTHON_BI.md "Tests shipped with an item").

The server reads and checks the shape of ``tests:`` in a Python item (``PythonTests``); the sandbox that can run Python is the console's, so the console runs them:
Check shows each as passed, failed or could-not-run, and a publish through the console is refused while one fails (a bi-admin may force it). Each test hands the
item a frame (written in the test, or rows of a dataset as the person running the test may see them), runs the item in the same sandboxed worker a report uses,
and compares the answer with what the test expects. Nothing is kept."""
from __future__ import annotations

import io
import math
from pathlib import Path
from typing import Awaitable, Callable

from core import tmpdir
from core.pyrunner import PyError, PyRunner

Sample = Callable[[dict], Awaitable[bytes]]


def arrow_of(columns: list[str], rows: list[list]) -> bytes:
    import pyarrow as pa
    table = pa.table({c: [r[i] for r in rows] for i, c in enumerate(columns)})
    sink = io.BytesIO()
    with pa.ipc.new_stream(sink, table.schema) as w:
        w.write_table(table)
    return sink.getvalue()


def limit_arrow(blob: bytes, rows: int) -> bytes:
    """The first ``rows`` rows of an Arrow stream, as an Arrow stream."""
    import pyarrow as pa
    table = pa.ipc.open_stream(io.BytesIO(blob)).read_all().slice(0, max(1, rows))
    sink = io.BytesIO()
    with pa.ipc.new_stream(sink, table.schema) as w:
        w.write_table(table)
    return sink.getvalue()


def _lookup(node, path: str):
    """``series.0.type`` in a nested result: a key of a mapping, or an index of a list; ``KeyError`` when it is not there."""
    for part in path.split("."):
        if isinstance(node, list):
            node = node[int(part)]
        elif isinstance(node, dict):
            node = node[part]
        else:
            raise KeyError(path)
    return node


def _same(a, b, tolerance: float) -> bool:
    if isinstance(a, (int, float)) and isinstance(b, (int, float)) and not isinstance(a, bool) and not isinstance(b, bool):
        return math.isclose(float(a), float(b), rel_tol=0.0, abs_tol=tolerance)
    return a == b


def _group_key(row: list, width: int) -> str:
    return " / ".join("" if v is None else str(v) for v in row[:width])


async def _measure(runner: PyRunner, m: dict, t: dict, arrow: bytes, tolerance: float) -> tuple[str, str]:
    by = list(t.get("by") or [])
    answer = await runner.run({"manifest": m}, arrow, group_by=by, params=t.get("params") or {})
    rows = answer.get("rows") or []
    if not by:
        if len(rows) != 1:
            return "fail", f"expected one number, the item gave {len(rows)} groups"
        got = rows[0][-1]
        return ("pass", f"{got}") if got is not None and _same(got, t["expect"], tolerance) else ("fail", f"expected {t['expect']}, got {got}")
    got = {_group_key(r, len(by)): r[-1] for r in rows}
    expected = {str(k): v for k, v in (t["expect"] or {}).items()}
    problems = [f"{k}: expected {v}, got {got.get(k, 'no group')}" for k, v in expected.items() if k not in got or got[k] is None or not _same(got[k], v, tolerance)]
    problems += [f"{k}: a group the test does not expect" for k in got if k not in expected]
    return ("fail", "; ".join(problems[:5])) if problems else ("pass", f"{len(got)} groups")


async def _visual(runner: PyRunner, m: dict, t: dict, arrow: bytes) -> tuple[str, str]:
    want = t["expect"]
    answer = await runner.run({"manifest": m}, arrow, params=t.get("params") or {})
    if answer.get("kind") != want["kind"]:
        return "fail", f"expected {want['kind']}, the item returned {answer.get('kind')}"
    result = answer.get("option") if answer["kind"] == "echarts" else answer.get("spec")
    problems = []
    for path, value in (want.get("has") or {}).items():
        try:
            got = _lookup(result, str(path))
        except (KeyError, IndexError, ValueError):
            problems.append(f"{path}: not in the result")
            continue
        if not _same(got, value, float(t.get("tolerance", 1e-9))):
            problems.append(f"{path}: expected {value!r}, got {got!r}")
    return ("fail", "; ".join(problems[:5])) if problems else ("pass", answer["kind"])


async def _transform(runner: PyRunner, m: dict, t: dict) -> tuple[str, str]:
    frames = t["input"]
    names = list(m.get("inputs") or [])
    arrows = []
    for n in names:
        if n not in frames:
            return "error", f"the test gives no frame for the input '{n}'"
        arrows.append(arrow_of(frames[n]["columns"], frames[n]["rows"]))
    with tmpdir.temp_dir("drishti-pytest-") as scratch:
        dest = str(Path(scratch) / "out.parquet")
        await runner.run_transform(m, arrows, params=t.get("params") or {}, dest=dest)
        import pyarrow.parquet as pq
        table = pq.read_table(dest)
    want = t["expect"]
    problems = []
    if "columns" in want and list(table.column_names) != list(want["columns"]):
        problems.append(f"columns: expected {want['columns']}, got {list(table.column_names)}")
    if "rows" in want and table.num_rows != want["rows"]:
        problems.append(f"rows: expected {want['rows']}, got {table.num_rows}")
    return ("fail", "; ".join(problems)) if problems else ("pass", f"{table.num_rows} rows")


async def run_one(runner: PyRunner, m: dict, t: dict, sample: Sample | None) -> dict:
    out = {"name": t.get("name", ""), "status": "error", "message": ""}
    try:
        tolerance = float(t.get("tolerance", 1e-9))
        if m["interface"] == "transform":
            status, message = await _transform(runner, m, t)
        else:
            if "input" in t:
                arrow = arrow_of(t["input"]["columns"], t["input"]["rows"])
            elif sample is None:
                raise PyError("DRS-9070", 422, "this test reads a dataset, which cannot be reached from here")
            else:
                arrow = await sample(t["sample"])
            status, message = await (_measure(runner, m, t, arrow, tolerance) if m["interface"] == "measure" else _visual(runner, m, t, arrow))
        out.update(status=status, message=message)
    except PyError as e:
        out.update(status="error", message=f"{e.code} {e.detail}")
    except Exception as e:  # noqa: BLE001 - one test that cannot be built (a column of mixed types, a bad frame) must not stop the others
        out.update(status="error", message=f"{type(e).__name__}: {e}")
    return out


async def run_tests(runner: PyRunner, manifest: dict, sample: Sample | None = None) -> dict:
    """Runs every test of ``manifest`` (as the server describes it) one after another; ``{total, passed, failed, errors, ok, results}``."""
    tests = list(manifest.get("tests") or [])
    results = [await run_one(runner, manifest, t, sample) for t in tests]
    count = lambda s: sum(1 for r in results if r["status"] == s)  # noqa: E731
    return {"total": len(results), "passed": count("pass"), "failed": count("fail"), "errors": count("error"), "ok": all(r["status"] == "pass" for r in results), "results": results}
