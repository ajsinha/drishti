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

"""The exposure measures by exactly the rules of the server's exposure engine (com.ash.drishti.compute.ccr.ExposureMath;
docs/contracts/EXP.md sections 5 and 6), in plain Python over lists, so the test data's reference figures and the engine's answers are
the same numbers (the parity test in drishti-compute reads them):

- a netting set's value on a path is the sum of its trades' MtM there (V);
- collateral on a margined set is what the CSA calls for at the look-back point, a margin period of risk earlier on the same path:
  ``target(v) = max(v - TH_them, 0) - max(-v - TH_us, 0) + IA``, rounded as a change from today's collateral (deliveries up, returns
  down), the held side shortened by the counterparty's MTA; a primary date whose look-back falls on or before the business date keeps
  the collateral held today (nothing called later settles within the margin period of risk); an unmargined set holds its IA only;
- exposure ``E = max(V - C - IM, 0)``, negative exposure ``N = min(V - C, 0)``; across netting sets both add per path;
- EE and ENE are means over the paths, PFE_q the ceil(P q)-th smallest exposure (``kth``) or interpolated, EEE the running maximum of
  EE from the current exposure, EPE and EEPE the time averages of EE and EEE over the first year (T = min(1 year, the longest remaining
  maturity); the interval that crosses T counts up to T), the peaks over the primary dates;
- a part's EE contribution is its mean of ``x_j`` over the paths, where the parts' ``x_j`` add up to E on every path (a netting set: its
  E; a trade: ``(MTM_i - w_i (C + IM)) 1{E > 0}``, ``w_i = MTM_i / V``); its PFE contribution its mean over the paths ranked around the
  quantile at the peak date (the window), scaled so the parts add up to the peak PFE.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field

EPS = 1e-9


def rank(p: int, q: float) -> int:
    """The 1-based rank of PFE_q among P exposures: ceil(P q - 1e-9), within 1..P."""
    return max(1, min(p, math.ceil(p * q - EPS)))


def quantile(values: list[float], q: float, rule: str = "kth") -> float:
    s = sorted(values)
    p = len(s)
    if p == 0:
        return 0.0
    if rule == "interpolated":
        pos = p * q
        lo = max(1, min(p, math.floor(pos)))
        hi = min(p, lo + 1)
        w = min(1.0, max(0.0, pos - lo))
        return (1 - w) * s[lo - 1] + w * s[hi - 1]
    return s[rank(p, q) - 1]


def order(values: list[float]) -> list[int]:
    """Path indices from the smallest exposure to the largest (ties: the lower index first)."""
    return sorted(range(len(values)), key=lambda j: (values[j], j))


def window(values: list[float], q: float, size: int) -> list[int]:
    """The paths ranked around the quantile: ranks k - h .. k + h of the exposures (h = size // 2)."""
    o = order(values)
    k = rank(len(values), q)
    h = size // 2
    lo, hi = max(1, k - h), min(len(values), k + h)
    return o[lo - 1:hi]


@dataclass
class Csa:
    """A netting set's collateral terms in USD (EXP.md section 6.3); ``margined`` False: the set holds its IA only."""
    th_them: float = 0.0
    th_us: float = 0.0
    mta_them: float = 0.0
    mta_us: float = 0.0
    ia: float = 0.0
    rounding: float = 0.0
    mpor_days: int = 10
    margined: bool = True
    held_today: float = 0.0
    im_held: float = 0.0


def target(v: float, c: Csa) -> float:
    return max(v - c.th_them, 0.0) - max(-v - c.th_us, 0.0) + c.ia


def called(v: float, c: Csa) -> float:
    """The collateral the CSA calls for at value v: rounded as a change from today's collateral, the held side shortened by MTA_them."""
    x = target(v, c)
    if c.rounding > 0:
        delta = x - c.held_today
        delta = math.ceil(delta / c.rounding) * c.rounding if delta > 0 else -math.floor(-delta / c.rounding) * c.rounding
        x = c.held_today + delta
    if x > 0:
        x = max(x - c.mta_them, 0.0)
    return x


@dataclass
class Grid:
    """The run's grid: every date's time in years from the business date, its role, and for each primary its look-back point (-1:
    none after the business date, today's collateral applies)."""
    times: list[float]
    primary: list[bool]
    lookback_of: list[int]
    lookback_for: dict[int, int] = field(default_factory=dict)

    def __post_init__(self):
        if not self.lookback_for:
            for i, of in enumerate(self.lookback_of):
                if not self.primary[i] and of >= 0:
                    self.lookback_for[of] = i

    @property
    def primaries(self) -> list[int]:
        return [i for i, p in enumerate(self.primary) if p]


def collateral(v_rows: list[list[float]], k: int, grid: Grid, c: Csa | None, model: str = "lagged") -> list[float]:
    """C on every path at primary k: lagged (called at the look-back point; today's collateral held when it has none), current
    (today's collateral held), or none."""
    p = len(v_rows[k])
    if c is None or model == "none":
        return [0.0] * p
    if not c.margined:
        return [c.ia] * p
    if model == "current":
        return [c.held_today] * p
    lb = grid.lookback_for.get(k, -1)
    if lb < 0:
        return [c.held_today] * p
    return [called(v, c) for v in v_rows[lb]]


def exposure(v_rows: list[list[float]], grid: Grid, c: Csa | None, model: str = "lagged") -> tuple[dict, dict, dict]:
    """E, N and C at each primary date of one netting set."""
    e, n, cs = {}, {}, {}
    im = 0.0 if c is None or model == "none" else c.im_held
    for k in grid.primaries:
        col = collateral(v_rows, k, grid, c, model)
        e[k] = [max(v - x - im, 0.0) for v, x in zip(v_rows[k], col)]
        n[k] = [min(v - x, 0.0) for v, x in zip(v_rows[k], col)]
        cs[k] = col
    return e, n, cs


def current_exposure(v0: float, c: Csa | None, model: str = "lagged") -> float:
    if c is None or model == "none":
        return max(v0, 0.0)
    held = c.ia if not c.margined else c.held_today
    return max(v0 - held - c.im_held, 0.0)


def mean(xs: list[float]) -> float:
    return math.fsum(xs) / len(xs) if xs else 0.0


def statistics(e: dict[int, list[float]], n: dict[int, list[float]], v_rows: list[list[float]], grid: Grid, horizon: float, qs: list[float],
               ce: float = 0.0, rule: str = "kth") -> dict:
    """The profile and the figures of a scope from its summed E and N matrices (EXP.md section 6.4); ``horizon`` is T in years."""
    profile = []
    eee = ce
    for k in grid.primaries:
        ee = mean(e[k])
        eee = max(eee, ee)
        row = {"k": k, "t": grid.times[k], "ee": ee, "ene": mean(n[k]), "eee": eee,
               "pfe": {q: quantile(e[k], q, rule) for q in qs},
               "ee_uncoll": mean([max(v, 0.0) for v in v_rows[k]]) if v_rows is not None else None}
        profile.append(row)
    epe = eepe = 0.0
    prev = 0.0
    if horizon > 0:
        for r in profile:
            if prev >= horizon - EPS:
                break
            dt = min(r["t"], horizon) - prev
            epe += r["ee"] * dt
            eepe += r["eee"] * dt
            prev = r["t"]
        epe /= horizon
        eepe /= horizon
    peaks = {}
    for q in qs:
        best = max(profile, key=lambda r: (r["pfe"][q], -r["t"])) if profile else None
        peaks[q] = (best["pfe"][q], best["k"]) if best else (0.0, -1)
    return {"profile": profile, "epe": epe, "eepe": eepe, "peak_ee": max((r["ee"] for r in profile), default=0.0), "peak_pfe": peaks,
            "ce": ce}


def add_rows(acc: dict[int, list[float]] | None, rows: dict[int, list[float]]) -> dict[int, list[float]]:
    """Per path sums of matrices (exposures across netting sets)."""
    if acc is None:
        return {k: list(v) for k, v in rows.items()}
    for k, v in rows.items():
        a = acc.setdefault(k, [0.0] * len(v))
        for j, x in enumerate(v):
            a[j] += x
    return acc


def trade_parts(mtm: dict[str, list[list[float]]], v_rows: list[list[float]], e: dict[int, list[float]], cs: dict[int, list[float]],
                im: float, k: int) -> dict[str, list[float]]:
    """Each trade's share x_i of the set's exposure on every path at primary k (they add up to E exactly)."""
    out = {}
    v = v_rows[k]
    for t, rows in mtm.items():
        xs = []
        for j, m in enumerate(rows[k]):
            if e[k][j] <= 0 or v[j] == 0:
                xs.append(0.0)
            else:
                xs.append(m - m / v[j] * (cs[k][j] + im))
        out[t] = xs
    return out


def pfe_contributions(total: list[float], parts: dict[str, list[float]], q: float, size: int, peak: float) -> dict[str, float]:
    """Each part's mean over the window paths of the total, scaled so the parts add up to the peak PFE."""
    w = window(total, q, size)
    raw = {key: math.fsum(x[j] for j in w) / len(w) for key, x in parts.items()}
    s = math.fsum(raw.values())
    scale = peak / s if abs(s) > EPS else 1.0
    return {key: r * scale for key, r in raw.items()}


def worked_example() -> dict:
    """EXP.md section 15: one netting set of two trades, 4 paths, 3 primary dates, thresholds 1, look-back values given."""
    a = [[3, -2, 3, 0], [5, -1, 4, -2], [2, -3, 6, -1]]
    b = [[-1, 1, -2, 1], [-1, 2, 0, 0], [1, 1, -1, -2]]
    look = [[1, -1, 0, 1], [2, 0, 3, -1], [3, -1, 4, -3]]
    v = [[x + y for x, y in zip(ra, rb)] for ra, rb in zip(a, b)]
    rows = look + v                                    # grid: three look-back dates, then the three primaries
    grid = Grid(times=[0.25, 0.5, 1.0, 0.25, 0.5, 1.0], primary=[False] * 3 + [True] * 3, lookback_of=[3, 4, 5, -1, -1, -1])
    c = Csa(th_them=1, th_us=1)
    e, n, cs = exposure(rows, grid, c)
    stats = statistics(e, n, rows, grid, 1.0, [0.75, 0.95])
    parts = trade_parts({"A": [[0] * 4] * 3 + a, "B": [[0] * 4] * 3 + b}, rows, {k: [max(x, 0) for x in rows[k]] for k in grid.primaries},
                        {k: [0.0] * 4 for k in grid.primaries}, 0.0, 4)
    return {"stats": stats, "contrib_uncoll_t2": {t: mean(x) for t, x in parts.items()}}
