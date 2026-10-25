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

"""The sample engine of the sample reactive connector (docs/architecture/REACTIVE_CONNECTORS.md): ``sum-vectors`` over P&L paths, in
the same shape as Drishti's Java twin (``com.ash.drishti.compute.reactive.samples.VectorSumEngine``), so the connector can switch engine
type without its screen changing. Standard library only (numpy used when present): it runs on the Windows QA machine.

Parameters: ``input`` (default ``values``), ``column`` (default ``PNL``), ``delay-ms`` (a pause per tenth of the rows, to show progress
on a screen). Result: one row (``ROWS``, ``WIDTH``, ``SUM``, ``WORST``, ``BEST``) and the table ``total``: one row per scenario day
(``DAY``, ``PNL``). With the connector's ``engine.split`` the rows are cut over every worker of the pool; ``engine.reduce`` adds the
chunks' answers up (``ROWS``, ``SUM`` and the ``PNL`` of each day).
"""
from __future__ import annotations

import array
import math
import time

from .. import arrow
from ..engine import ComputeError, Engine, Inputs, Progress, Request, Result


class PathSumEngine(Engine):
    kinds = ("sum-vectors",)
    version = "1.0"

    def compute(self, req: Request, inputs: Inputs, progress: Progress) -> Result:
        name = req.param("input", "values")
        column = req.param("column", "PNL")
        delay = float(req.param("delay-ms", 0) or 0) / 1000.0
        t = inputs.table(name)
        try:
            c = t.column(column)
        except KeyError:
            raise ComputeError(f"no record of {name} has a {column} path to add up") from None
        if c.type != arrow.FLOAT64_VECTOR:
            raise ComputeError(f"column {column} is {c.type}, not a path (a list of numbers of one length)")
        w = c.width
        total = array.array("d", bytes(8 * w))
        used = 0
        step = max(1, c.rows // 10)
        for r in range(c.rows):
            if not (c.nulls and r in c.nulls):
                v = c.data[r * w:(r + 1) * w]
                for k in range(w):
                    total[k] += v[k]
                used += 1
            if (r + 1) % step == 0:
                progress.partial({"done": r + 1, "of": c.rows, "estimate": {"SUM": math.fsum(total)}})
                if delay:
                    time.sleep(delay)
        if used == 0:
            raise ComputeError(f"no record of {name} has a {column} path to add up")
        out = arrow.Table([arrow.int64("DAY", range(1, w + 1)), arrow.float64("PNL", list(total))])
        row = {"ROWS": used, "WIDTH": w, "SUM": math.fsum(total), "WORST": min(total), "BEST": max(total)}
        return Result(rows=[row], tables={"total": out}, meta={"engine": "path-sum", "version": self.version})
