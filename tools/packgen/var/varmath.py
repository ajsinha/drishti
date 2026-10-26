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

"""The VaR measures the demo data stores beside its vectors (a position's and a portfolio's VaR, ES and component VaR), by exactly the
rules the server's engine uses (com.ash.drishti.compute.var.VarMath; VAR_GUIDE.md section 18), so a stored figure and the engine's
answer are the same number:

- VaR(a) is minus the k-th worst value, k = ceil(N (1 - a)): the 6th worst of 509 at 99%.
- ES(a) is minus the mean of the k worst values, k = ceil(N (1 - a)): the 13 worst of 509 at 97.5%.
- Component VaR of a part: minus its mean P&L over the scope's scenarios ranked k - h .. k + h (h = window // 2, a window of 5 by
  default), scaled so the components add up to the VaR exactly.
- The worst ranks break ties by scenario index (the older scenario first), so the order is the same everywhere.
"""
from __future__ import annotations

import math

EPS = 1e-9


def rank_k(n: int, alpha: float) -> int:
    return max(1, min(n, math.ceil(n * (1.0 - alpha) - EPS)))


def order(v: list[float]) -> list[int]:
    """Scenario indices from the worst P&L to the best (ties: the lower index first)."""
    return sorted(range(len(v)), key=lambda j: (v[j], j))


def var(v: list[float], alpha: float = 0.99) -> float:
    return -v[order(v)[rank_k(len(v), alpha) - 1]]


def es(v: list[float], alpha: float = 0.975) -> float:
    o = order(v)
    k = rank_k(len(v), alpha)
    return -sum(v[j] for j in o[:k]) / k


def tail(v: list[float], alpha: float = 0.99, window: int = 5) -> list[int]:
    """The scenarios the component VaR averages over: ranks k - h .. k + h of the scope's vector."""
    o = order(v)
    k = rank_k(len(v), alpha)
    h = window // 2
    lo, hi = max(1, k - h), min(len(v), k + h)
    return o[lo - 1:hi]


def components(total: list[float], parts: dict[str, list[float]], alpha: float = 0.99, window: int = 5) -> dict[str, float]:
    """Each part's component VaR in the total (the parts add up to the total), scaled to add up to the total's VaR exactly."""
    t = tail(total, alpha, window)
    smooth = -sum(total[j] for j in t) / len(t)
    v = var(total, alpha)
    scale = v / smooth if abs(smooth) > EPS else 1.0
    return {k: -sum(p[j] for j in t) / len(t) * scale for k, p in parts.items()}


def add(vectors) -> list[float]:
    out = None
    for v in vectors:
        if out is None:
            out = list(v)
        else:
            for j, x in enumerate(v):
                out[j] += x
    return out or []
