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

"""The what-if engine of the reactive connector ``var-whatif`` (docs/contracts/VAR.md section 9.7, kind ``var-whatif``): a scope's VaR
before and after hypothetical trades, and its stressed window.

Inputs: ``base``, the scope's ``var-result`` (the Java engine's answer, read as the viewer: its ``PNL`` vector, ``VAR``, ``ES``,
``CONFIDENCE``, ``ES_CONFIDENCE``, ``LIMIT``); ``sandbox`` (optional), the person's private sandbox: one item per hypothetical trade with
its ``PNL`` vector over the same scenarios (priced elsewhere: a pricer, a copy of a booked trade's vector) and an optional ``FACTOR``
(scaled linearly, 1 by default) and ``LABEL``. Taking trades out is the scope's own algebra (``DESK:RATES - TRD:X``), so it is exact.

Parameters: ``stress-window`` (scenario days of the stressed window, 250 by default; 0: none), ``component-window`` (5).

Result: one row (``SCOPE``, ``CANONICAL``, ``BUSINESS_DATE``, ``ITEMS``, ``BEFORE_VAR``, ``AFTER_VAR``, ``CHANGE_VAR``, ``BEFORE_ES``,
``AFTER_ES``, ``CHANGE_ES``, ``LIMIT``, ``LIMIT_USE_BEFORE``, ``LIMIT_USE_AFTER``, ``STRESSED_VAR_BEFORE``, ``STRESSED_VAR_AFTER``,
``STRESS_WINDOW``, ``STRESSED_FROM``, ``CONFIDENCE``, ``ES_CONFIDENCE``) and the tables ``items`` (each trade's standalone VaR, its
incremental VaR in the order listed and alone, its component VaR and ES in the scope after) and ``pnl`` (``DAY``, ``BEFORE``,
``AFTER``)."""
from __future__ import annotations

import math

from .. import arrow
from ..engine import ComputeError, Engine, Inputs, Progress, Request, Result
from . import measures as M


def _rows(inputs: Inputs, name: str) -> list[dict]:
    if name not in inputs.names():
        return []
    return inputs.table(name).to_rows()


def _number(v, default=None):
    if v is None or isinstance(v, bool):
        return default
    try:
        x = float(v)
    except (TypeError, ValueError):
        return default
    return x if math.isfinite(x) else default


class VarWhatIfEngine(Engine):
    kinds = ("var-whatif",)
    version = "1.0"

    def compute(self, req: Request, inputs: Inputs, progress: Progress) -> Result:
        base = _rows(inputs, "base")
        if not base or base[0].get("PNL") is None:
            raise ComputeError("the scope has no VaR answer to start from (its var-result has no P&L vector: "
                               "is the scope right, and may you see it?)")
        b = base[0]
        before = [float(x) for x in b["PNL"]]
        n = len(before)
        conf = _number(b.get("CONFIDENCE"), 0.99)
        es_conf = _number(b.get("ES_CONFIDENCE"), 0.975)
        window = int(req.param("component-window", 5) or 5)
        stress = int(req.param("stress-window", 250) or 0)
        items = _rows(inputs, "sandbox")
        after = list(before)
        prepared = []
        for i, it in enumerate(items):
            label = str(it.get("LABEL") or it.get("TRADE_ID") or f"item {i + 1}")
            vec = it.get("PNL")
            if vec is None:
                raise ComputeError(f"what-if item {label} has no PNL vector (one P&L per scenario day, {n} of them)")
            vec = [float(x) for x in vec]
            if len(vec) != n:
                raise ComputeError(f"what-if item {label} has {len(vec)} scenario P&Ls; the scope has {n}")
            factor = _number(it.get("FACTOR"), 1.0)
            vec = [factor * x for x in vec]
            prepared.append((label, factor, vec))
            progress.fraction(i + 1, max(1, len(items)) + 1)
        mb = M.measures(before, conf, es_conf, window)
        rows = []
        running = list(before)
        for label, factor, vec in prepared:
            prev = M.var(running, conf)
            running = M.plus(running, vec)
            rows.append({"LABEL": label, "FACTOR": factor, "STANDALONE_VAR": M.var(vec, conf), "STANDALONE_ES": M.es(vec, es_conf),
                         "INCREMENTAL_VAR": M.var(running, conf) - prev, "INCREMENTAL_ALONE": M.var(M.plus(before, vec), conf) - mb.var})
        after = running
        ma = M.measures(after, conf, es_conf, window)
        for r, (_, _, vec) in zip(rows, prepared):
            r["COMPONENT_VAR"] = M.component(vec, ma)
            r["COMPONENT_ES"] = M.component_es(vec, ma)
        limit = _number(b.get("LIMIT"))
        row = {"SCOPE": b.get("SCOPE") or b.get("CANONICAL"), "CANONICAL": b.get("CANONICAL"), "BUSINESS_DATE": b.get("BUSINESS_DATE"), "ITEMS": len(prepared),
               "BEFORE_VAR": mb.var, "AFTER_VAR": ma.var, "CHANGE_VAR": ma.var - mb.var, "BEFORE_ES": mb.es, "AFTER_ES": ma.es,
               "CHANGE_ES": ma.es - mb.es, "LIMIT": limit, "LIMIT_USE_BEFORE": mb.var / limit if limit else None,
               "LIMIT_USE_AFTER": ma.var / limit if limit else None, "CONFIDENCE": conf, "ES_CONFIDENCE": es_conf, "STRESS_WINDOW": stress}
        if stress > 0:
            sb, at = M.stressed(before, stress, conf)
            sa, _ = M.stressed(after, stress, conf)
            row.update({"STRESSED_VAR_BEFORE": sb, "STRESSED_VAR_AFTER": sa, "STRESSED_FROM": at})
        progress.fraction(1, 1)
        tables = {"pnl": arrow.Table([arrow.int64("DAY", range(1, n + 1)), arrow.float64("BEFORE", before), arrow.float64("AFTER", after)])}
        if rows:
            tables["items"] = arrow.Table([arrow.utf8("LABEL", [r["LABEL"] for r in rows])]
                                          + [arrow.float64(k, [r[k] for r in rows]) for k in ("FACTOR", "STANDALONE_VAR", "STANDALONE_ES",
                                                                                              "INCREMENTAL_VAR", "INCREMENTAL_ALONE",
                                                                                              "COMPONENT_VAR", "COMPONENT_ES")])
        return Result(rows=[row], tables=tables, meta={"engine": "var-whatif", "version": self.version})
