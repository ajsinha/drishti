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

"""The exposure measures over flat lists, exactly as ``com.ash.drishti.compute.ccr.ExposureMath`` (and tools/packgen/ccr/ccrmath.py)
compute them (docs/guides/EXPOSURE_GUIDE.md sections 2 and 18). A matrix is one row of P path values per date, row-major:
``m[i * P + p]``. A netting set's value matrix ``V`` covers every grid date (``G x P``); exposure matrices cover the primary dates only
(``G_primary x P``, row i the grid's i-th primary point).

- collateral (``lagged``): what the CSA calls at the look-back point on the same path, rounded as a change from today's collateral
  (deliveries up, returns down), the held side shortened by the counterparty's MTA; a primary point without a look-back point keeps
  today's collateral; ``current`` holds today's collateral on every path; ``none`` holds nothing (and no initial margin); an unmargined
  set holds its independent amount;
- ``E = max(V - C - IM, 0)``, ``N = min(V - C, 0)``, both additive per path across netting sets;
- EE and ENE the means over paths, PFE_q the ceil(P q - 1e-9)-th smallest (``kth``) or interpolated, EEE the running maximum of EE
  from the current exposure, EPE and EEPE their time averages over [0, T] (the interval crossing T counted up to T), the peaks over the
  primary dates (the earliest on a tie)."""
from __future__ import annotations

import math
from dataclasses import dataclass, field

EPS = 1e-9


def rank(p: int, q: float) -> int:
    """The 1-based rank of PFE_q among P exposures: ceil(P q - 1e-9), within 1..P."""
    return max(1, min(p, math.ceil(p * q - EPS)))


def quantile_sorted(s: list[float], q: float, rule: str = "kth") -> float:
    p = len(s)
    if p == 0:
        return 0.0
    if (rule or "kth").startswith("interp"):
        pos = p * q
        lo = max(1, min(p, math.floor(pos)))
        hi = min(p, lo + 1)
        w = min(1.0, max(0.0, pos - lo))
        return (1 - w) * s[lo - 1] + w * s[hi - 1]
    return s[rank(p, q) - 1]


@dataclass
class Csa:
    """A netting set's collateral terms in USD (EXPOSURE_GUIDE.md section 18.3, CsaTerms in Java)."""
    present: bool = False
    margined: bool = False
    th_them: float = 0.0
    th_us: float = 0.0
    mta_them: float = 0.0
    mta_us: float = 0.0
    ia: float = 0.0
    rounding: float = 0.0
    held_today: float = 0.0
    im_held: float = 0.0

    def target(self, v: float) -> float:
        return max(v - self.th_them, 0.0) - max(-v - self.th_us, 0.0) + self.ia

    def called(self, v: float) -> float:
        x = self.target(v)
        if self.rounding > 0:
            delta = x - self.held_today
            delta = math.ceil(delta / self.rounding) * self.rounding if delta > 0 else -math.floor(-delta / self.rounding) * self.rounding
            x = self.held_today + delta
        if x > 0:
            x = max(x - self.mta_them, 0.0)
        return x


NONE = Csa()


@dataclass
class Grid:
    """The run's grid: per grid date whether it is primary and, for a primary date, its look-back point (-1: none)."""
    primary: list[bool]
    lookback_for: list[int]
    primaries: list[int] = field(default_factory=list)

    def __post_init__(self):
        self.primaries = [k for k, x in enumerate(self.primary) if x]

    @property
    def size(self) -> int:
        return len(self.primary)


def _collateral_at(v: list[float], lb: int, j: int, p: int, c: Csa, model: str) -> float:
    if c is None or not c.present or model == "none":
        return 0.0
    if not c.margined:
        return c.ia
    if model == "current" or lb < 0:
        return c.held_today
    return c.called(v[lb * p + j])


def exposure(v: list[float], grid: Grid, p: int, c: Csa, model: str = "lagged") -> tuple[list[float], list[float]]:
    """One netting set's E and N at the primary dates from its value matrix (every grid date)."""
    gp = len(grid.primaries)
    e = [0.0] * (gp * p)
    n = [0.0] * (gp * p)
    im = 0.0 if c is None or model == "none" else c.im_held
    for i, k in enumerate(grid.primaries):
        lb = grid.lookback_for[k]
        for j in range(p):
            x = _collateral_at(v, lb, j, p, c, model)
            val = v[k * p + j]
            e[i * p + j] = max(val - x - im, 0.0)
            n[i * p + j] = min(val - x, 0.0)
    return e, n


def current_exposure(v0: float, c: Csa, model: str = "lagged") -> float:
    if c is None or not c.present or model == "none":
        return max(v0, 0.0)
    held = c.held_today if c.margined else c.ia
    return max(v0 - held - c.im_held, 0.0)


def positive(m: list[float], grid: Grid, p: int) -> tuple[list[float], list[float]]:
    """A trade or set alone, uncollateralised: ``max(M, 0)`` and ``min(M, 0)`` at the primary dates."""
    e, n = [], []
    for k in grid.primaries:
        row = m[k * p:(k + 1) * p]
        e.extend(max(x, 0.0) for x in row)
        n.extend(min(x, 0.0) for x in row)
    return e, n


@dataclass
class Stats:
    ee: list[float]
    ene: list[float]
    eee: list[float]
    pfe: list[float]
    epe: float
    eepe: float
    peak_ee: float
    peak_pfe: float
    peak_at: int
    ce: float
    horizon: float


def _mean(m: list[float], i: int, p: int) -> float:
    return math.fsum(m[i * p:(i + 1) * p]) / p if p else 0.0


def statistics(e: list[float], n: list[float], times: list[float], p: int, horizon: float, q: float, ce: float = 0.0,
               rule: str = "kth") -> Stats:
    """The profile, time averages and peaks of a scope from its summed E and N (``G_primary x P``)."""
    gp = len(times)
    ee, ene, eee, pfe = [], [], [], []
    run = ce
    for i in range(gp):
        x = _mean(e, i, p)
        ee.append(x)
        ene.append(_mean(n, i, p))
        run = max(run, x)
        eee.append(run)
        pfe.append(quantile_sorted(sorted(e[i * p:(i + 1) * p]), q, rule))
    epe = eepe = 0.0
    if horizon > 0:
        prev = 0.0
        for i in range(gp):
            if prev >= horizon - EPS:
                break
            dt = min(times[i], horizon) - prev
            epe += ee[i] * dt
            eepe += eee[i] * dt
            prev = times[i]
        epe /= horizon
        eepe /= horizon
    peak_at = -1
    peak = 0.0
    for i in range(gp):
        if peak_at < 0 or pfe[i] > peak:
            peak, peak_at = pfe[i], i
    return Stats(ee, ene, eee, pfe, epe, eepe, max(ee, default=0.0), peak, peak_at, ce, horizon)


def peak_alone(m: list[float], grid: Grid, p: int, q: float, rule: str = "kth") -> float:
    """A trade's peak PFE as if it had no netting set (uncollateralised)."""
    best = 0.0
    for k in grid.primaries:
        best = max(best, quantile_sorted(sorted(max(x, 0.0) for x in m[k * p:(k + 1) * p]), q, rule))
    return best
