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

"""The what-if engine of the reactive connector ``exposure-whatif`` (docs/guides/EXPOSURE_GUIDE.md sections 7 and 19.5, kind
``exposure-whatif``): a scope's exposure before and after candidate trades, by the exact ``paths`` method (EXPOSURE_GUIDE.md section 18.7). Each
candidate's MtM on every path of the date's run at every grid date is added to its netting set's value V, the set's collateral and
exposure are computed again (the CSA's terms, today's holdings, the look-back points, the asked collateral model), and the scope's
exposure becomes ``E_scope - E_set + E'_set`` path by path; a candidate without a netting set is a netting set of its own,
uncollateralised. Incremental = with - without, for every figure and every primary date. Deterministic: no random numbers.

Inputs, read as the viewer from the Java engine (``exposure-result`` with ``detail``, ExposureWhatIfInputs in the server):

- ``base`` (one row): ``E_SCOPE``, ``N_SCOPE`` (``G_primary x P``), ``TIMES``, ``PRIMARY``, ``LOOKBACK_FOR``, ``TENORS``, ``DATES``,
  ``RULE``, ``CE_SCOPE``, ``LIFE_YEARS``, ``WHATIF_SET_IDS``, ``PATHS``, ``RUN_ID``, ``CONFIDENCE``, the scope's ids;
- ``sets`` (a row per netting set the candidates may join): ``NETTING_SET``, ``V`` (``G x P``), ``E_SET``, ``N_SET``, the CSA's terms,
  ``HELD_TODAY``, ``IM_HELD``, ``V0``, ``CE_SET``;
- ``sandbox``: the person's candidates: ``TRADE_ID``, ``LABEL``, ``NETTING_SET`` (empty: its own), ``MTM`` (``G x P``), ``MTM0``
  (today's MtM; else the paths' mean at the first grid date), ``LIFE_YEARS``, ``RUN_ID``.

Result: one row (``SCOPE``, ``CANONICAL``, ``BUSINESS_DATE``, ``RUN_ID``, ``CONFIDENCE``, ``COLLATERAL_MODEL``, ``METHOD``, ``ITEMS``, and
``BEFORE_``, ``AFTER_`` and ``CHANGE_`` of ``EPE``, ``EEPE``, ``PEAK_PFE``, ``PEAK_EE``, ``CE``; ``BEFORE_PEAK_PFE_TENOR``,
``AFTER_PEAK_PFE_TENOR``, ``FLAGS``) and the tables ``profile`` (``TENOR``, ``DATE``, ``T``, ``EE_BASE``, ``EE``, ``ENE_BASE``, ``ENE``,
``PFE_BASE``, ``PFE``, ``INCREMENTAL_EE``, ``INCREMENTAL_PFE``) and ``items`` (each candidate's peak PFE alone and its incremental peak
PFE added alone)."""
from __future__ import annotations

import math

from .. import arrow
from ..engine import ComputeError, Engine, Inputs, Progress, Request, Result
from . import measures as M


def _rows(inputs: Inputs, name: str) -> list[dict]:
    if name not in inputs.names():
        return []
    return inputs.table(name).to_rows()


def _num(v, default=0.0) -> float:
    if v is None or isinstance(v, bool):
        return default
    try:
        x = float(v)
    except (TypeError, ValueError):
        return default
    return x if math.isfinite(x) else default


def _csa(r: dict) -> M.Csa:
    return M.Csa(present=bool(r.get("CSA_PRESENT")), margined=bool(r.get("MARGINED")), th_them=_num(r.get("TH_THEM")),
                 th_us=_num(r.get("TH_US")), mta_them=_num(r.get("MTA_THEM")), mta_us=_num(r.get("MTA_US")), ia=_num(r.get("IA")),
                 rounding=_num(r.get("ROUNDING")), held_today=_num(r.get("HELD_TODAY")), im_held=_num(r.get("IM_HELD")))


class ExposureWhatIfEngine(Engine):
    kinds = ("exposure-whatif",)
    version = "1.0"

    def compute(self, req: Request, inputs: Inputs, progress: Progress) -> Result:
        base = _rows(inputs, "base")
        if not base or base[0].get("E_SCOPE") is None:
            raise ComputeError("the scope has no exposure answer to start from (its exposure-result has no exposure matrix: is the scope "
                               "right, does the date's run cover it, and may you see it?)")
        b = base[0]
        p = int(_num(b.get("PATHS")))
        times = [float(x) for x in b["TIMES"]]
        gp = len(times)
        grid = M.Grid(primary=[x > 0.5 for x in b["PRIMARY"]], lookback_for=[int(x) for x in b["LOOKBACK_FOR"]])
        g = grid.size
        if p < 1 or len(b["E_SCOPE"]) != gp * p or len(grid.primaries) != gp:
            raise ComputeError(f"the scope's exposure matrix is not {gp} primary dates x {p} paths")
        q = _num(req.param("q", b.get("CONFIDENCE")), 0.95)
        model = str(req.param("collateral", "lagged") or "lagged").lower()
        rule = str(b.get("RULE") or "kth")
        run = str(b.get("RUN_ID") or "")
        tenors = str(b.get("TENORS") or "").split(",")
        dates = str(b.get("DATES") or "").split(",")
        sets = {str(r.get("NETTING_SET")): r for r in _rows(inputs, "sets")}
        items = _rows(inputs, "sandbox")
        e0 = [float(x) for x in b["E_SCOPE"]]
        n0 = [float(x) for x in b["N_SCOPE"]]
        ce0 = _num(b.get("CE_SCOPE"))
        life0 = _num(b.get("LIFE_YEARS"))

        prepared = []
        for i, it in enumerate(items):
            label = str(it.get("LABEL") or it.get("TRADE_ID") or f"candidate {i + 1}")
            vec = it.get("MTM")
            if vec is None:
                raise ComputeError(f"the candidate {label} has no paths (MTM: {g} grid dates x {p} paths of the run {run})")
            vec = [float(x) for x in vec]
            if len(vec) != g * p:
                raise ComputeError(f"the candidate {label}'s paths are not of the run {run}: {len(vec)} values, the run has {g} grid dates x "
                                   f"{p} paths = {g * p}")
            if it.get("RUN_ID") and run and str(it.get("RUN_ID")) != run:
                raise ComputeError(f"the candidate {label}'s paths are of the run {it.get('RUN_ID')}, not {run}")
            ns = str(it.get("NETTING_SET") or "").strip()
            if ns and ns not in sets:
                raise ComputeError(f"the candidate {label} joins the netting set {ns}, which the scope {b.get('CANONICAL')} does not cover whole "
                                   f"(the sets it may join: {b.get('WHATIF_SET_IDS') or 'none'}); leave NETTING_SET empty for a netting set of its own")
            mtm0 = it.get("MTM0")
            mtm0 = _num(mtm0) if mtm0 is not None else math.fsum(vec[:p]) / p
            prepared.append({"id": str(it.get("TRADE_ID") or label), "label": label, "set": ns, "m": vec, "mtm0": mtm0,
                             "life": _num(it.get("LIFE_YEARS"), 1.0)})
            progress.fraction(i + 1, max(1, len(items)) + 1)

        def apply(cands: list[dict]) -> tuple[list[float], list[float], float, float]:
            """The scope's E, N, CE and life with the candidates in."""
            e, n, ce, life = list(e0), list(n0), ce0, life0
            by_set: dict[str, list[dict]] = {}
            for c in cands:
                by_set.setdefault(c["set"], []).append(c)
                life = max(life, c["life"])
            for ns, cs in by_set.items():
                if not ns:                                   # each a netting set of its own, uncollateralised
                    for c in cs:
                        ea, na = M.positive(c["m"], grid, p)
                        e = [x + y for x, y in zip(e, ea)]
                        n = [x + y for x, y in zip(n, na)]
                        ce += max(c["mtm0"], 0.0)
                    continue
                s = sets[ns]
                csa = _csa(s)
                v = [float(x) for x in s["V"]]
                for c in cs:
                    v = [x + y for x, y in zip(v, c["m"])]
                e1, n1 = M.exposure(v, grid, p, csa, model)
                es, nset = s["E_SET"], s["N_SET"]
                e = [x - float(a) + y for x, a, y in zip(e, es, e1)]
                n = [x - float(a) + y for x, a, y in zip(n, nset, n1)]
                v0 = _num(s.get("V0")) + math.fsum(c["mtm0"] for c in cs)
                ce += M.current_exposure(v0, csa, model) - _num(s.get("CE_SET"))
            return e, n, ce, life

        before = M.statistics(e0, n0, times, p, min(1.0, life0), q, ce0, rule)
        e1, n1, ce1, life1 = apply(prepared)
        after = M.statistics(e1, n1, times, p, min(1.0, life1), q, ce1, rule)

        def tenor(i: int):
            return tenors[i] if 0 <= i < len(tenors) else None

        row = {"SCOPE": b.get("SCOPE") or b.get("CANONICAL"), "CANONICAL": b.get("CANONICAL"), "BUSINESS_DATE": b.get("BUSINESS_DATE"),
               "RUN_ID": run, "CONFIDENCE": q, "COLLATERAL_MODEL": model, "METHOD": "paths", "ITEMS": len(prepared),
               "ENGINE": f"exposure-whatif {self.version} (Python)", "FLAGS": ["exact"]}
        for name, x, y in (("EPE", before.epe, after.epe), ("EEPE", before.eepe, after.eepe), ("PEAK_PFE", before.peak_pfe, after.peak_pfe),
                           ("PEAK_EE", before.peak_ee, after.peak_ee), ("CE", before.ce, after.ce)):
            row["BEFORE_" + name], row["AFTER_" + name], row["CHANGE_" + name] = x, y, y - x
        row["BEFORE_PEAK_PFE_TENOR"], row["AFTER_PEAK_PFE_TENOR"] = tenor(before.peak_at), tenor(after.peak_at)
        tables = {"profile": arrow.Table([arrow.utf8("TENOR", [tenor(i) for i in range(gp)]),
                                          arrow.utf8("DATE", [dates[i] if i < len(dates) else None for i in range(gp)]),
                                          arrow.float64("T", times), arrow.float64("EE_BASE", before.ee), arrow.float64("EE", after.ee),
                                          arrow.float64("ENE_BASE", before.ene), arrow.float64("ENE", after.ene),
                                          arrow.float64("PFE_BASE", before.pfe), arrow.float64("PFE", after.pfe),
                                          arrow.float64("INCREMENTAL_EE", [y - x for x, y in zip(before.ee, after.ee)]),
                                          arrow.float64("INCREMENTAL_PFE", [y - x for x, y in zip(before.pfe, after.pfe)])])}
        if prepared:
            alone, inc = [], []
            for c in prepared:
                alone.append(M.peak_alone(c["m"], grid, p, q, rule))
                ea, na, cea, la = apply([c])
                inc.append(M.statistics(ea, na, times, p, min(1.0, la), q, cea, rule).peak_pfe - before.peak_pfe)
            tables["items"] = arrow.Table([arrow.utf8("TRADE_ID", [c["id"] for c in prepared]), arrow.utf8("LABEL", [c["label"] for c in prepared]),
                                           arrow.utf8("NETTING_SET", [c["set"] for c in prepared]), arrow.float64("PEAK_PFE_ALONE", alone),
                                           arrow.float64("INCREMENTAL_PEAK_PFE", inc)])
        progress.fraction(1, 1)
        return Result(rows=[row], tables=tables, meta={"engine": "exposure-whatif", "version": self.version})
