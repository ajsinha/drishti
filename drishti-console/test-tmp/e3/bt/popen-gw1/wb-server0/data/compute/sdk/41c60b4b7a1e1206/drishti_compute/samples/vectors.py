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

"""The sample engine: echo and sum vectors (docs/architecture/PYTHON_COMPUTE.md section 8). It needs nothing beyond the standard library
(numpy is used when the environment has it), so it runs on the Windows QA machine and in a bare interpreter. It is what the load test and
Admin → Compute's "Test the pool" run.

Kinds:

- ``echo``: the parameters back as one row, and every input table back unchanged (a round trip through the transport).
- ``sum``: per numeric column of the input ``values`` (or ``params.input``): the sum and the row count, with progress every tenth.
- ``sum-vectors``: the element-wise sum of a vector column (``params.column``, default ``PNL``) over every row: how a scope's P&L path
  is the sum of its positions' paths. The total path is the table ``total``; its sum, rows and width are the row.
"""
from __future__ import annotations

import array
import math

from .. import arrow
from ..engine import ComputeError, Engine, Inputs, Progress, Request, Result


class VectorEngine(Engine):
    kinds = ("echo", "sum", "sum-vectors")
    version = "0.1.0"

    def compute(self, req: Request, inputs: Inputs, progress: Progress) -> Result:
        if req.kind == "echo":
            return Result(rows=[{"kind": "echo", "params": req.params, "user": req.user, "inputs": inputs.names()}],
                          tables={n: inputs.table(n) for n in inputs.names()}, meta={"engine": "vectors", "version": self.version})
        name = req.param("input", "values")
        t = inputs.table(name)
        if req.kind == "sum":
            return self._sum(t, progress)
        return self._sum_vectors(t, req.param("column", "PNL"), progress)

    def _sum(self, t: arrow.Table, progress: Progress) -> Result:
        rows = []
        numeric = [c for c in t.columns if c.type in (arrow.INT64, arrow.FLOAT64)]
        if not numeric:
            raise ComputeError("the input has no numeric column to sum")
        for i, c in enumerate(numeric, 1):
            if c.nulls:
                values = [c.data[r] for r in range(c.rows) if r not in c.nulls]
            else:
                values = c.data
            total = math.fsum(values) if c.type == arrow.FLOAT64 else sum(values)
            rows.append({"column": c.name, "sum": total, "count": c.rows - (len(c.nulls) if c.nulls else 0)})
            progress.fraction(i, len(numeric))
        return Result(rows=rows, meta={"rows": t.rows})

    def _sum_vectors(self, t: arrow.Table, column: str, progress: Progress) -> Result:
        try:
            c = t.column(column)
        except KeyError:
            raise ComputeError(f"the input has no column {column}") from None
        if c.type != arrow.FLOAT64_VECTOR:
            raise ComputeError(f"column {column} is {c.type}, not a vector column")
        w = c.width
        total = _numpy_sum(c)
        if total is None:
            total = array.array("d", bytes(8 * w))
            step = max(1, c.rows // 10)
            for r in range(c.rows):
                if c.nulls and r in c.nulls:
                    continue
                v = c.data[r * w:(r + 1) * w]
                for k in range(w):
                    total[k] += v[k]
                if (r + 1) % step == 0:
                    progress.fraction(r + 1, c.rows)
        out = arrow.Table([arrow.vectors(column, w, total)])
        return Result(rows=[{"rows": c.rows, "width": w, "sum": math.fsum(total)}], tables={"total": out}, meta={"rows": c.rows})


def _numpy_sum(c: arrow.Column):
    try:
        import numpy as np  # noqa: WPS433 (optional)
    except ImportError:
        return None
    if c.nulls:
        return None
    m = np.frombuffer(c.data, dtype="<f8").reshape(c.rows, c.width) if c.rows else np.zeros((0, c.width))
    return array.array("d", m.sum(axis=0).tobytes())
