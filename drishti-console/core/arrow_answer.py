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

"""Arrow to the browser and to Python (DATA_GUIDE.md › Data formats, section 6; docs/architecture/DATA_ARCHITECTURE.md decided 5).

A browser that loaded Arrow JS asks with ``Accept: application/vnd.apache.arrow.stream``; the server answers with an Arrow IPC stream
written by the official Arrow Java library (``ArrowAnswers``): the answer's big table is the record batch, the rest of the answer rides in
the schema's metadata (``drishti.answer``, JSON), and ``drishti.table`` says where the table goes back. The console passes the server's
bytes through as they came. It never writes Arrow from JSON: when the server answers JSON (a question asked as a run, a table Arrow is
not written with), the browser gets that JSON, which it reads as well. Python reads Drishti's data through Arrow Flight
(clients/python/drishti_flight.py); ``decode`` stays for the tests and for a script that holds a stream.
"""
from __future__ import annotations

import json

MEDIA = "application/vnd.apache.arrow.stream"
ANSWER_KEY = b"drishti.answer"
TABLE_KEY = b"drishti.table"


def wanted(accept: str | None) -> bool:
    """True when the ``Accept`` header names the Arrow stream."""
    return MEDIA in (accept or "").lower()


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


def pass_on(content: bytes, headers, shape: str = "", at: str = ""):
    """The server's answer to a call that asked for Arrow: its Arrow bytes as they came, else its JSON as it came (the console writes no
    Arrow of its own). ``shape`` and ``at`` are kept for the callers' sake: the server decides the table."""
    from fastapi.responses import JSONResponse, Response

    kind = (headers.get("content-type") or "").lower() if headers is not None else ""
    if MEDIA in kind:
        return _arrow_response(content)
    if "json" not in kind:
        return Response(content, media_type=kind or "application/octet-stream", headers={"Cache-Control": "no-store"})
    return JSONResponse(json.loads(content) if content else None, headers={"Cache-Control": "no-store", "Vary": "Accept"})
