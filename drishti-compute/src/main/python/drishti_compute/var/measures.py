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

"""VaR, ES and their decomposition over a vector of scenario P&Ls (gains positive), exactly as the Java engine computes them
(``VarMath``, ``VarCalc``; VAR.md section 6): k = ceil(N (1 - a) - 1e-9) clamped to [1, N]; scenarios ordered worst first, ties by the
older scenario; VaR = -(k-th worst) or interpolated; ES = -mean of the k_ES worst; component VaR = -scale x mean of the part over the
scope's scenarios ranked k-h .. k+h; incremental VaR = VaR(scope) - VaR(scope without the part). Plus the stressed window: the largest
VaR over every run of ``window`` consecutive scenario days."""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Sequence

EPS = 1e-9


def rank(n: int, alpha: float) -> int:
    """The VaR rank: the 6th worst of 509 at 99%, the 13 worst of 509 at 97.5% (for ES)."""
    return max(1, min(n, math.ceil(n * (1.0 - alpha) - EPS)))


def order(v: Sequence[float]) -> list[int]:
    """Scenario indices worst first; equal P&Ls by index, the older first (a stable sort, as Java's)."""
    return sorted(range(len(v)), key=lambda j: (v[j], j))


def var(v: Sequence[float], alpha: float, rule: str = "kth-worst", o: list[int] | None = None) -> float:
    n = len(v)
    if n == 0:
        return 0.0
    o = order(v) if o is None else o
    if rule.startswith("interp"):
        pos = n * (1.0 - alpha)
        lo = max(1, math.floor(pos))
        hi = min(n, lo + 1)
        w = min(1.0, max(0.0, pos - lo))
        return -((1 - w) * v[o[lo - 1]] + w * v[o[hi - 1]])
    return -v[o[rank(n, alpha) - 1]]


def es(v: Sequence[float], alpha: float, o: list[int] | None = None) -> float:
    n = len(v)
    if n == 0:
        return 0.0
    o = order(v) if o is None else o
    k = rank(n, alpha)
    return -math.fsum(v[o[i]] for i in range(k)) / k


@dataclass
class Measures:
    """A vector's VaR and ES, its order, the tail the components average over, and the scale that makes them add up to the VaR."""
    var: float
    es: float
    k: int
    es_count: int
    order: list[int] = field(repr=False)
    tail: list[int] = field(repr=False)
    scale: float = 1.0


def measures(v: Sequence[float], confidence: float = 0.99, es_confidence: float = 0.975, window: int = 5, scale_to_var: bool = True,
             rule: str = "kth-worst") -> Measures:
    n = len(v)
    o = order(v)
    k = rank(n, confidence) if n else 0
    h = max(0, window // 2)
    lo = max(1, k - h)
    hi = min(n, k + h)
    tail = o[lo - 1:hi] if n else []
    v_ = var(v, confidence, rule, o)
    smoothed = -math.fsum(v[j] for j in tail) / len(tail) if tail else 0.0
    sc = v_ / smoothed if scale_to_var and abs(smoothed) > EPS else 1.0
    return Measures(v_, es(v, es_confidence, o), k, rank(n, es_confidence) if n else 0, o, tail, sc)


def component(part: Sequence[float], m: Measures) -> float:
    """The part's component VaR in the scope whose measures are ``m``: the parts of a partition add up to the scope's VaR."""
    return -math.fsum(part[j] for j in m.tail) / len(m.tail) * m.scale if m.tail else 0.0


def component_es(part: Sequence[float], m: Measures) -> float:
    """The part's component ES: its mean over the scope's ES scenarios (the parts add up to the ES)."""
    return -math.fsum(part[m.order[i]] for i in range(m.es_count)) / m.es_count if m.es_count else 0.0


def plus(a: Sequence[float], b: Sequence[float], factor: float = 1.0) -> list[float]:
    if len(a) != len(b):
        raise ValueError(f"vectors of {len(a)} and {len(b)} scenarios cannot be added")
    return [x + factor * y for x, y in zip(a, b)]


def stressed(v: Sequence[float], window: int, alpha: float, rule: str = "kth-worst") -> tuple[float, int]:
    """The largest VaR over every run of ``window`` consecutive scenario days (the stressed window of the scenario set the batch
    delivered), and the index of the run's first day. A window as long as the vector is the VaR itself."""
    n = len(v)
    w = max(1, min(window, n))
    best, at = -math.inf, 0
    for start in range(0, n - w + 1):
        x = var(v[start:start + w], alpha, rule)
        if x > best:
            best, at = x, start
    return (0.0, 0) if n == 0 else (best, at)
