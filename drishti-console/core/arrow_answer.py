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

"""Arrow to the browser (2.9.7, "Arrow where it pays", docs/guides/DATA_FORMATS.md section 6).

A browser that loaded Arrow JS asks with ``Accept: application/vnd.apache.arrow.stream``; the console answers the same answer as an Arrow
IPC stream: the answer's big table is the record batch, the rest of the answer rides in the schema's metadata (``drishti.answer``, JSON),
and ``drishti.table`` says where the table goes back (``{"shape": "rows"|"records"|"arrays", "at" | "keys", "absent"}``). The server's
``ArrowAnswers`` writes the same stream; the console passes the server's bytes through when it has them, and writes them here (pyarrow)
when the answer came as JSON (a question asked as a run, an older server). Cells are text (repeated text as a dictionary), 64-bit
integers, doubles or booleans. Anything else (an object, a list, a column mixing text and numbers, NaN, a key that is both null and left
out) goes as JSON: ``encode`` answers None."""
from __future__ import annotations

import json

MEDIA = "application/vnd.apache.arrow.stream"
ANSWER_KEY = b"drishti.answer"
TABLE_KEY = b"drishti.table"


def wanted(accept: str | None) -> bool:
    """True when the ``Accept`` header names the Arrow stream."""
    return MEDIA in (accept or "").lower()


def _column(values: list):
    """One column as pyarrow builds it: text, 64-bit integers or doubles, with nulls; None when the values are anything else."""
    import pyarrow as pa

    text = integral = real = boolean = False
    for v in values:
        if v is None:
            continue
        if isinstance(v, bool):
            boolean = True
        elif isinstance(v, str):
            text = True
        elif isinstance(v, int):
            if not -(1 << 63) <= v < (1 << 63):
                return None
            integral = True
        elif isinstance(v, float):
            if v != v or v in (float("inf"), float("-inf")):
                return None
            real = True
        else:
            return None
    if boolean:                                   # true and false, one bit a cell; with anything else: JSON
        return None if text or integral or real else pa.array(values, type=pa.bool_())
    if text and (integral or real):
        return None
    if text or not (integral or real):
        return pa.array(values, type=pa.string())
    if real:
        return pa.array([None if v is None else float(v) for v in values], type=pa.float64())
    return pa.array(values, type=pa.int64())


def repeats(array) -> bool:
    """True when a text column repeats its values enough to go as a dictionary (as the server's ArrowAnswers.repeats): at most half as many
    distinct values as cells, over at least 16 cells."""
    n = len(array)
    return n >= 16 and len(array.drop_null().unique()) <= n // 2


def encode(answer: dict, shape: str, at: str) -> bytes | None:
    """The answer as an Arrow stream (see the module's note), or None when its table cannot be written as Arrow or pyarrow is missing."""
    try:
        import pyarrow as pa
    except ImportError:
        return None
    if not isinstance(answer, dict):
        return None
    rest = dict(answer)
    table: dict = {"shape": shape}
    names, arrays = [], []
    if shape == "rows":
        rows, declared = rest.pop(at, None), answer.get("columns")
        if not isinstance(rows, list) or not isinstance(declared, list):
            return None
        if any(not isinstance(r, list) or len(r) != len(declared) for r in rows):
            return None
        for c, d in enumerate(declared):
            col = _column([r[c] for r in rows])
            if col is None:
                return None
            names.append(str(d.get("name") if isinstance(d, dict) else d))
            arrays.append(col)
        table["at"] = at
        n = len(rows)
    elif shape == "records":
        rows = rest.pop(at, None)
        if not isinstance(rows, list) or any(not isinstance(r, dict) for r in rows):
            return None
        keys: dict = {}
        for r in rows:
            for k in r:
                keys.setdefault(k, None)
        absent = []
        for k in keys:
            missing = any(k not in r for r in rows)
            if missing and any(k in r and r[k] is None for r in rows):
                return None                       # null and left out mean different things here: JSON keeps both
            if missing:
                absent.append(k)
            col = _column([r.get(k) for r in rows])
            if col is None:
                return None
            names.append(str(k))
            arrays.append(col)
        table.update(at=at, absent=absent)
        n = len(rows)
    elif shape == "arrays":
        keys_ = at.split(",")
        n = -1
        for k in keys_:
            values = rest.pop(k, None)
            if not isinstance(values, list) or (n >= 0 and len(values) != n):
                return None
            n = len(values)
            col = _column(values)
            if col is None:
                return None
            names.append(k)
            arrays.append(col)
        table["keys"] = keys_
    else:
        raise ValueError(f"shape is rows, records or arrays: {shape}")
    try:
        meta = {ANSWER_KEY: json.dumps(rest, separators=(",", ":"), ensure_ascii=False, allow_nan=False).encode(),
                TABLE_KEY: json.dumps(table, separators=(",", ":")).encode()}
    except ValueError:
        return None
    arrays = [a.dictionary_encode() if pa.types.is_string(a.type) and repeats(a) else a for a in arrays]
    schema = pa.schema([pa.field(nm, a.type) for nm, a in zip(names, arrays)], metadata=meta)
    batch = pa.record_batch(arrays, schema=schema) if arrays else None
    sink = pa.BufferOutputStream()
    with pa.ipc.new_stream(sink, schema) as w:
        if batch is not None and n > 0:
            w.write_batch(batch)
    return sink.getvalue().to_pybytes()


def decode(content: bytes) -> dict:
    """The answer rebuilt from a stream (what the browser's ``drishtiTable`` does): for the tests, and a Python client."""
    import pyarrow as pa

    t = pa.ipc.open_stream(content).read_all()
    meta = t.schema.metadata or {}
    answer = json.loads(meta[ANSWER_KEY])
    spec = json.loads(meta[TABLE_KEY])
    cols = {nm: t.column(nm).to_pylist() for nm in t.column_names}
    if spec["shape"] == "rows":
        answer[spec["at"]] = [list(r) for r in zip(*cols.values())] if cols else []
        if cols and not t.num_rows:
            answer[spec["at"]] = []
    elif spec["shape"] == "records":
        absent = set(spec.get("absent") or [])
        answer[spec["at"]] = [{k: v[i] for k, v in cols.items() if not (v[i] is None and k in absent)} for i in range(t.num_rows)]
    else:
        answer.update(cols)
    return answer


def _arrow_response(content: bytes, headers: dict | None = None):
    from fastapi.responses import Response

    return Response(content, media_type=MEDIA, headers={"Cache-Control": "no-store", "Vary": "Accept", **(headers or {})})


def pass_on(content: bytes, headers, shape: str, at: str):
    """The server's answer to a call that asked for Arrow: its Arrow bytes as they came, or (an older server, or a table the server
    sends as JSON) the JSON written as Arrow here, or the JSON itself when that cannot be written either."""
    from fastapi.responses import JSONResponse, Response

    kind = (headers.get("content-type") or "").lower() if headers is not None else ""
    if MEDIA in kind:
        return _arrow_response(content)
    if "json" not in kind:
        return Response(content, media_type=kind or "application/octet-stream", headers={"Cache-Control": "no-store"})
    answer = json.loads(content) if content else None
    written = encode(answer, shape, at) if isinstance(answer, dict) else None
    if written is not None:
        return _arrow_response(written)
    return JSONResponse(answer, headers={"Cache-Control": "no-store", "Vary": "Accept"})


def respond_bi(answer):
    """A visual's answer given in time by a run (``{columns, rows, ...}``, or ``{run, answer}`` when it was recorded as a run) as Arrow;
    the run it ran as goes in ``X-Drishti-Ran-As`` so the answer itself is the same as the JSON one. None: send the JSON."""
    if not isinstance(answer, dict):
        return None
    inline = isinstance(answer.get("answer"), dict) and answer.get("run") and "columns" not in answer
    inner = answer["answer"] if inline else answer
    if "columns" not in inner or "rows" not in inner:
        return None
    written = encode(inner, "rows", "rows")
    if written is None:
        return None
    return _arrow_response(written, {"X-Drishti-Ran-As": json.dumps(answer["run"], separators=(",", ":"))} if inline else None)
