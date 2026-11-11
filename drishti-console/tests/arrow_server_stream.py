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

"""The stream the server sends for an answer asked as Arrow (``ArrowAnswers`` in drishti-server, the official Arrow Java library), written
here with pyarrow for the console's tests only: the console itself never writes Arrow from JSON (HOW_IT_FITS.md decided 5), it passes the
server's bytes through. The answer's big table is the record batch, the rest of the answer rides in the schema's metadata
(``drishti.answer``), and ``drishti.table`` says where the table goes back; repeated text as a dictionary, as the server writes it."""
from __future__ import annotations

import json

ANSWER_KEY = b"drishti.answer"
TABLE_KEY = b"drishti.table"




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


def server_stream(answer: dict, shape: str, at: str) -> bytes | None:
    """The answer as the server streams it, or None when the server would send JSON (a table Arrow is not written with)."""
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
