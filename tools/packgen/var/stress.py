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

"""The demo's **stress scenario set** for stressed VaR (docs/guides/VAR_GUIDE.md section 18.8): today's trades revalued under the moves
of a 12-month stress window, as a bank's pricing system would send them, beside the normal set of the same business date.

- **The window.** The business days of 2008-07-01 to 2009-06-30 (the New York calendar): 250 scenario days, the set ``HS-STRESS-2008``
  (``SET_TYPE: STRESS``). The moves are **synthetic** (the set says so in ``SYNTHETIC`` and its description): a 2008-like year of the same
  forty factors as the normal set (factors.py), wider and fat-tailed: every shock Student-t with four degrees of freedom and GARCH
  clustering, the volatility 2.2 times the normal set's, the global factor's weight raised by a quarter (correlations rise in a crisis),
  an acute episode from mid-September to the end of November 2008 at 1.5 times that, and crash days that move everything down at once
  (2008-09-15, 2008-09-29, 2008-10-09, 2008-10-15, 2008-12-01, 2009-01-20).
- **The rows.** The same contract and feed as the normal set: each trade's P&amp;L vector (``pnl-vector``, ``PNL-<trade>-HS-STRESS-2008``)
  or, for the scenario-PV systems, its scenario PVs in its own currency (``scenario-pv``, ``SPV-<trade>-HS-STRESS-2008``) with the stress
  set's scenario FX (``fx-scenario``, ``FXS-<ccy>-HS-STRESS-2008``); the trade's terms, run and batch are the normal row's. Each trade
  keeps its exposure (the same factor loadings and size as in the normal set); only the market moves differ.
- **Kept apart.** The rows are written to their own files (``stress-cob…``, beside the normal set's under the same date), so a reader of the normal set
  can tell them apart by file as the engine does by ``SCENARIO_SET_ID``.

Deterministic: the same trades give the same files, byte for byte (Python's own Random, seeded by text)."""
from __future__ import annotations

import math
import random
from datetime import date, timedelta

import factors as F
import scenario_fx as SFX
import varmath as VM

SET = "HS-STRESS-2008"
#: the stress set's files start with this, in the normal set's folders
FILE_PREFIX = "stress-"
START, END = "2008-07-01", "2009-06-30"
#: the stress window's crash days: everything down at once
CRASHES = ("2008-09-15", "2008-09-29", "2008-10-09", "2008-10-15", "2008-12-01", "2009-01-20")
#: the acute episode: about three times the stress year's own volatility
ACUTE = ("2008-09-15", "2008-11-28")
#: the stress year's volatility against the normal set's, the acute episode's against the stress year's, a crash day's extra move (sigmas)
BASE_SCALE, ACUTE_SCALE, CRASH_SIGMAS = 2.2, 1.5, 1.0
#: the Student-t degrees of freedom of every shock
DOF = 4
DESCRIPTION = ("Stressed VaR: today's trades under a synthetic 2008-like stress year (2008-07-01 to 2009-06-30, 250 business days); "
               "synthetic moves for the demo, not market history")


def stress_days() -> list[str]:
    """The business days of the stress window, oldest first (the New York calendar: 250)."""
    from samplegen.dates import Calendar
    cal = Calendar.of("USNY")
    d, end, out = date.fromisoformat(START), date.fromisoformat(END), []
    while d <= end:
        if cal.is_business_day(d):
            out.append(d.isoformat())
        d += timedelta(days=1)
    return out


def _shock(r: random.Random) -> float:
    """A Student-t draw with DOF degrees of freedom, scaled to unit variance."""
    return F.student_t(r, DOF)


class StressMarket:
    """The stress year's factor moves: ``moves[f][j]`` for stress day j (0 the oldest), and one more day (unused, so the normal set's
    unit paths can be reused: factors.unit_path reads ``n + 1`` days)."""

    def __init__(self, seed: str = "drishti-var-stress-2008"):
        self.days_iso = stress_days()
        self.n = len(self.days_iso)
        days = self.n + 1
        at = {d: j for j, d in enumerate(self.days_iso)}
        lo, hi = ACUTE
        acute = {j for d, j in at.items() if lo <= d <= hi}
        crashes = {at[d] for d in CRASHES if d in at}
        scale = [BASE_SCALE * (ACUTE_SCALE if j in acute else 1.0) for j in range(days)]
        r = F.seeded(seed, "market")
        global_ = self._garch(r, days, scale, crashes, skew=True)
        group = {g: self._garch(F.seeded(seed, "group", g), days, scale, crashes, skew=g in ("CR", "EQ", "CO")) for g in F.GROUPS}
        self.moves: dict[str, list[float]] = {}
        for f, (g, wg, wh) in F.FACTORS.items():
            fr = F.seeded(seed, "factor", f)
            wg = math.copysign(min(0.9, abs(wg) * 1.25), wg)        # correlations rise in a crisis
            own = math.sqrt(max(0.05, 1.0 - wg * wg - wh * wh))
            self.moves[f] = [wg * global_[j] + wh * group[g][j] + own * _shock(fr) * scale[j] for j in range(days)]

    @staticmethod
    def _garch(r: random.Random, days: int, scale: list[float], crashes: set[int], skew: bool) -> list[float]:
        """As factors.Market's, with the stress year's shocks and crash days."""
        omega, alpha, beta = 0.05, 0.12, 0.85
        var, prev, out = 1.0, 0.0, []
        for j in range(days):
            var = omega + alpha * prev * prev + beta * var
            x = _shock(r) * math.sqrt(var) * scale[j]
            if j in crashes:
                x = -abs(x) - CRASH_SIGMAS * scale[j] if skew else x * 2.5
            out.append(x)
            prev = x / scale[j]
        return out


class Stress:
    """The stress set's rows for a demo batch (make_var_data.Demo): its trades' stress vectors and the rows that carry them."""

    def __init__(self, demo):
        self.demo = demo
        self.market = StressMarket()
        self.n = self.market.n
        self.days = self.market.days_iso
        b = demo.batch
        self.fx = SFX.Fx(self.market, {c: demo.fx.today(c) for c in SFX.CURRENCIES})
        self.trade_vectors: dict[str, list[int]] = {}
        self.engine_vectors: dict[str, list[float]] = {}
        self._vectors(b)

    def _vectors(self, b) -> None:
        """Each trade under the stress days: its position's exposure (size and loadings as in the normal set), the stress market's moves."""
        import positions as P
        m = self.market
        for pid, ts in b.groups.items():
            t0 = ts[0]
            book = P.book_of(t0)
            grp = P.group_of(t0)
            r = F.seeded("instrument", book, pid)                     # the same draws as the normal set: the same size and gamma
            risk = F.BASE_RISK.get(grp.lower(), 150_000) * r.lognormvariate(0.0, 0.55)
            gross = sum(abs(P.signed_qty(t)) for t in ts) or 1.0
            per_unit = risk / gross
            unit = F.unit_path(m, F.loadings(grp, t0.get("PRODUCT_TYPE"), t0.get("PRODUCT_SUB_TYPE"), t0.get("CURRENCY")),
                               F.is_option(t0.get("PRODUCT_TYPE")), r)
            for t in ts:
                tid = str(t["TRADE_ID"])
                q = P.signed_qty(t)
                tr = F.seeded("trade-stress", tid)
                own = 0.06 * abs(q) * per_unit
                self.trade_vectors[tid] = [round(q * per_unit * unit[j] + own * _shock(tr)) for j in range(m.n)]

    def rows(self) -> dict[str, list[dict]]:
        """The stress set's documents of the date, by kind: its scenario set, P&amp;L vectors, scenario PVs and scenario FX. Each record is
        the stress set's version of the normal set's, at the same grain: a trade's own, or a position's, summed from its trades' stress rows."""
        demo, b = self.demo, self.demo.batch
        pnl, spv = [], []

        def trade(row: dict) -> dict:
            """A trade's stress row from its normal-set row."""
            tid = row["TRADE_ID"]
            if "SCENARIO_PV" not in row:
                return {**row, "VECTOR_ID": f"PNL-{tid}-{SET}", "SCENARIO_SET_ID": SET, "PNL": self.trade_vectors[tid]}
            ccy = row["CURRENCY"]
            base, r0 = row["BASE_PV"], self.fx.today(ccy)                 # the trade's base PV is the normal set's: one valuation today
            pvs = [round(base + x / r0, 2) for x in self.trade_vectors[tid]]
            self.engine_vectors[tid] = SFX.engine_pnl(self.fx, ccy, base, pvs)
            return {**row, "SCENARIO_PV_ID": f"SPV-{tid}-{SET}", "SCENARIO_SET_ID": SET, "SCENARIO_PV": pvs}

        for row in b.vector_rows + b.pv_rows:
            if row.get("GRAIN") == "TRADE":
                (spv if "SCENARIO_PV" in row else pnl).append(trade(row))
        for pid, sys, members in b.supplied_parts:
            kind, row = b.supply(pid, sys, [trade(m) for m in members], SET)
            (spv if kind == "scenario-pv" else pnl).append(row)
        key = lambda r: (r["LEGAL_ENTITY"], r["DESK"], r["TT_BOOK"], str(r.get("TRADE_ID") or r.get("POSITION_ID")))   # noqa: E731
        return {"scenario-set": [self.scenario_set()], "pnl-vector": sorted(pnl, key=key), "scenario-pv": sorted(spv, key=key),
                "fx-scenario": [{**x, "SOURCE": "demo-stress-model"} for x in self.fx.rows(demo.cob, SET, f"B-{demo.cob}-01")]}

    def scenario_set(self) -> dict:
        return {"SCENARIO_SET_ID": SET, "SET_TYPE": "STRESS", "SYNTHETIC": True, "BUSINESS_DATE": self.demo.cob, "SCENARIO_DATES": self.days,
                "SCENARIO_COUNT": self.n, "WINDOW_START": self.days[0], "WINDOW_END": self.days[-1], "HORIZON_DAYS": 1,
                "REVALUATION": "full", "WEIGHTS": None, "SOURCE_BATCH": f"B-{self.demo.cob}-01", "DESCRIPTION": DESCRIPTION}

    def bank_figures(self) -> str:
        """The bank's stressed VaR 99% and ES 97.5% in USD as the engine computes them from the files (rows() first)."""
        v = VM.add((self.engine_vectors.get(t) or [float(x) for x in vec]) for t, vec in self.trade_vectors.items())
        return f"stressed VaR {VM.var(v):,.0f} ES {VM.es(v):,.0f} ({self.n} stress days {self.days[0]} .. {self.days[-1]})"
