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

"""``drishti.quant``: the pricing and risk maths Calc's snippets share (docs/guides/PYTHON_CALC.md, section 17).

Pure Python and numpy, so it loads in a blink (no scipy): ``from drishti import quant as q``. Calc fetches this file
from the console the first time a cell names ``quant``; outside Calc it is an ordinary module (its tests run in plain
CPython). Conventions, unless a function says otherwise:

* times are in years (ACT/365F), rates and vols are decimals (0.035, not 3.5), spreads in basis points where named ``_bp``;
* zero rates are continuously compounded; discount factors ``df(t) = exp(-z t)``;
* arrays in, arrays out: every pricing function takes scalars or numpy arrays (broadcast) and returns the same shape.

Contents: tenors and day counts · root finding · the normal distribution · curves (interpolation, forwards, par swap
rates, annuities, bumps, bootstrap) · bonds (price, yield, duration, convexity, Z-spread) · options (Black-Scholes,
Black-76, Garman-Kohlhagen, Bachelier, Greeks, implied vol, FX delta strikes and smiles, SVI) · realised volatility ·
risk (VaR, ES, Kupiec, drawdown, Sharpe, HHI) · credit (hazard rates, survival, CVA) · FRTB-style aggregation.
"""
from __future__ import annotations

import math
import re
from datetime import date

import numpy as np

__all__ = [
    "tenor_years", "month_code_years", "year_fraction",
    "brent", "newton",
    "norm_cdf", "norm_pdf", "norm_ppf",
    "ZeroCurve", "df_from_zero", "zero_from_df", "forward_rate", "annuity", "par_swap_rate", "swap_pv", "bootstrap_par",
    "bond_cashflows", "bond_price", "bond_yield", "bond_risk", "z_spread",
    "black_scholes", "bs_greeks", "black76", "black76_greeks", "garman_kohlhagen", "gk_greeks", "bachelier",
    "implied_vol", "fx_strike_from_delta", "smile_from_rr_bf", "svi_total_variance",
    "close_to_close_vol", "parkinson_vol", "garman_klass_vol",
    "historical_var", "expected_shortfall", "parametric_var", "kupiec_pof", "max_drawdown", "sharpe", "hhi",
    "hazard_from_spread", "forward_hazards", "survival", "cva_from_profile",
    "frtb_bucket_charge", "frtb_across_buckets",
]

# ---- tenors and day counts ----------------------------------------------------------------------------------------------

_UNIT = {"D": 1 / 365.0, "W": 7 / 365.0, "M": 1 / 12.0, "Y": 1.0}
_SPECIAL = {"ON": 1 / 365.0, "TN": 2 / 365.0, "SN": 3 / 365.0, "SW": 7 / 365.0, "0": 0.0, "SPOT": 0.0}
_MONTHS = "FGHJKMNQUVXZ"   # futures month codes, January to December


def tenor_years(tenor) -> float:
    """A tenor as years: ``'3M'`` 0.25, ``'18M'`` 1.5, ``'10Y'`` 10, ``'1W'`` 7/365, ``'ON'`` 1/365, ``'1Y6M'`` 1.5.
    A number is returned as it is (already years)."""
    if isinstance(tenor, (int, float, np.floating, np.integer)):
        return float(tenor)
    t = str(tenor).strip().upper()
    if t in _SPECIAL:
        return _SPECIAL[t]
    parts = re.findall(r"(\d+(?:\.\d+)?)([DWMY])", t)
    if not parts or "".join(n + u for n, u in parts) != t.replace(" ", ""):
        raise ValueError(f"not a tenor: {tenor!r} (try '3M', '10Y', '1W', 'ON')")
    return float(sum(float(n) * _UNIT[u] for n, u in parts))


def month_code_years(code: str, as_of: date | str) -> float:
    """A futures contract month code (``'X6'``: November 2026, ``'Z27'``) as years from ``as_of`` to the middle of that
    month. The decade is the one that puts the contract on or after ``as_of``. In the contract month itself, once its
    middle has passed, the contract is in delivery: 0 (never negative)."""
    as_of = date.fromisoformat(str(as_of)[:10]) if not isinstance(as_of, date) else as_of
    m = re.fullmatch(r"([FGHJKMNQUVXZ])(\d{1,2})", str(code).strip().upper())
    if not m:
        raise ValueError(f"not a futures month code: {code!r} (e.g. 'X6', 'Z27')")
    month = _MONTHS.index(m.group(1)) + 1
    digits = m.group(2)
    year = 2000 + int(digits) if len(digits) == 2 else (as_of.year // 10) * 10 + int(digits)
    if len(digits) == 1 and date(year, month, 28) < as_of:
        year += 10
    years = (date(year, month, 15) - as_of).days / 365.0
    return max(years, 0.0) if (year, month) == (as_of.year, as_of.month) else years


def _days_in_year(year: int) -> int:
    return 366 if year % 4 == 0 and (year % 100 != 0 or year % 400 == 0) else 365


def _act_act_isda(d1: date, d2: date) -> float:
    """ACT/ACT ISDA (ISDA 2006 4.16(b)): the days of the period in each calendar year over that year's length (365 or
    366), summed."""
    if d2 < d1:
        return -_act_act_isda(d2, d1)
    if d1.year == d2.year:
        return (d2 - d1).days / _days_in_year(d1.year)
    first = (date(d1.year + 1, 1, 1) - d1).days / _days_in_year(d1.year)
    last = (d2 - date(d2.year, 1, 1)).days / _days_in_year(d2.year)
    return first + (d2.year - d1.year - 1) + last


def year_fraction(start, end, basis: str = "ACT/365F") -> float:
    """The year fraction between two dates (``date`` or ISO text) by ``basis`` (ISDA 2006 section 4.16):

    * ACT/365F (``ACT/365``, ``A365F``) and ACT/360 (``A360``): actual days over 365 or 360;
    * 30/360 (``BOND``, US bond basis, 4.16(f)): D1 31 becomes 30; D2 31 becomes 30 only when D1 is 30 or 31;
    * 30E/360 (``EUROBOND``, 4.16(g)): D1 and D2 31 both become 30 (2026-01-15 to 2026-03-31 is 75/360);
    * ACT/ACT ISDA (``ACT/ACT``, ``ACT/ACT ISDA``, ``AA``, 4.16(b)): the days in each calendar year over that year's
      length, 365 or 366 (2026-01-01 to 2027-01-01 is exactly 1.0)."""
    d1 = start if isinstance(start, date) else date.fromisoformat(str(start)[:10])
    d2 = end if isinstance(end, date) else date.fromisoformat(str(end)[:10])
    b = basis.upper().replace(" ", "").replace("(", "").replace(")", "")
    days = (d2 - d1).days
    if b in ("ACT/365F", "ACT/365", "A365F"):
        return days / 365.0
    if b in ("ACT/360", "A360"):
        return days / 360.0
    if b in ("30/360", "BOND"):
        dd1 = min(d1.day, 30)
        dd2 = 30 if d2.day == 31 and dd1 == 30 else d2.day
        return (360 * (d2.year - d1.year) + 30 * (d2.month - d1.month) + (dd2 - dd1)) / 360.0
    if b in ("30E/360", "EUROBOND"):
        dd1, dd2 = min(d1.day, 30), min(d2.day, 30)
        return (360 * (d2.year - d1.year) + 30 * (d2.month - d1.month) + (dd2 - dd1)) / 360.0
    if b in ("ACT/ACT", "ACT/ACTISDA", "AA"):
        return _act_act_isda(d1, d2)
    raise ValueError(f"unknown day count {basis!r}")


# ---- root finding ------------------------------------------------------------------------------------------------------

def brent(f, a: float, b: float, tol: float = 1e-12, maxiter: int = 200) -> float:
    """A root of ``f`` in ``[a, b]`` by Brent's method (bisection, secant and inverse quadratic interpolation).
    ``f(a)`` and ``f(b)`` must differ in sign; raises ValueError otherwise."""
    fa, fb = f(a), f(b)
    if fa == 0:
        return a
    if fb == 0:
        return b
    if fa * fb > 0:
        raise ValueError(f"brent: f({a:g}) and f({b:g}) have the same sign ({fa:g}, {fb:g}): widen the bracket")
    c, fc, d = a, fa, b - a
    e = d
    for _ in range(maxiter):
        if fb * fc > 0:
            c, fc, d = a, fa, b - a
            e = d
        if abs(fc) < abs(fb):
            a, b, c = b, c, b
            fa, fb, fc = fb, fc, fb
        tol1 = 2 * 2.2e-16 * abs(b) + 0.5 * tol
        xm = 0.5 * (c - b)
        if abs(xm) <= tol1 or fb == 0:
            return b
        if abs(e) >= tol1 and abs(fa) > abs(fb):
            s = fb / fa
            if a == c:
                p, q = 2 * xm * s, 1 - s
            else:
                q, r = fa / fc, fb / fc
                p = s * (2 * xm * q * (q - r) - (b - a) * (r - 1))
                q = (q - 1) * (r - 1) * (s - 1)
            if p > 0:
                q = -q
            p = abs(p)
            if 2 * p < min(3 * xm * q - abs(tol1 * q), abs(e * q)):
                e, d = d, p / q
            else:
                d, e = xm, xm
        else:
            d, e = xm, xm
        a, fa = b, fb
        b += d if abs(d) > tol1 else math.copysign(tol1, xm)
        fb = f(b)
    raise RuntimeError(f"brent: no convergence in {maxiter} iterations")


def newton(f, fprime, x0: float, tol: float = 1e-12, maxiter: int = 50) -> float:
    """A root of ``f`` by Newton's method from ``x0`` (``fprime`` its derivative). Raises RuntimeError if it stalls."""
    x = float(x0)
    for _ in range(maxiter):
        d = fprime(x)
        if d == 0:
            break
        step = f(x) / d
        x -= step
        if abs(step) < tol * max(1.0, abs(x)):
            return x
    raise RuntimeError("newton: no convergence (try brent with a bracket)")


# ---- the normal distribution -------------------------------------------------------------------------------------------

_SQRT2 = math.sqrt(2.0)
_erfc = np.vectorize(math.erfc, otypes=[float])


def norm_cdf(x):
    """The standard normal CDF, exact to machine precision (``math.erfc``), for scalars or arrays."""
    if np.ndim(x) == 0:
        return 0.5 * math.erfc(-float(x) / _SQRT2)
    return 0.5 * _erfc(-np.asarray(x, dtype=float) / _SQRT2)


def norm_pdf(x):
    """The standard normal density."""
    x = np.asarray(x, dtype=float) if np.ndim(x) else float(x)
    return np.exp(-0.5 * x * x) / math.sqrt(2 * math.pi)


def _horner(coef, x):
    """coef[0] x^(n-1) + ... + coef[n-1], by Horner's rule."""
    out = 0.0
    for c in coef:
        out = out * x + c
    return out


def norm_ppf(p):
    """The standard normal quantile (inverse CDF): Acklam's rational approximation polished by one Halley step, the
    step's residual taken on the near tail (``1 - p`` above 0.5) so it does not cancel. Measured against 60-digit values
    (mpmath) from 1e-300 to ``1 - 1e-16``: relative error below 1e-15 outside 0.4..0.6, absolute error below 1e-14
    everywhere (near 0.5 the quantile is near 0 and the input's own rounding bounds the relative error).
    ``norm_ppf(0.99)`` 2.3263."""
    a = (-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02,
         -3.066479806614716e+01, 2.506628277459239e+00)
    b = (-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01, -1.328068155288572e+01)
    c = (-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00,
         4.374664141464968e+00, 2.938163982698783e+00)
    d = (7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00)

    def one(p):
        if not 0 < p < 1:
            if p in (0, 1):
                return -math.inf if p == 0 else math.inf
            raise ValueError("norm_ppf: p must lie in (0, 1)")
        if p < 0.02425:                                            # lower tail
            q = math.sqrt(-2 * math.log(p))
            x = _horner(c, q) / (_horner(d, q) * q + 1)
        elif p > 1 - 0.02425:                                      # upper tail
            q = math.sqrt(-2 * math.log(1 - p))
            x = -_horner(c, q) / (_horner(d, q) * q + 1)
        else:                                                      # central region
            q = p - 0.5
            r = q * q
            x = _horner(a, r) * q / (_horner(b, r) * r + 1)
        if p > 0.5:                                                # one Halley step; residual CDF(x) - p, taken as
            e = (1 - p) - 0.5 * math.erfc(x / _SQRT2)              # (1-p) - SF(x) above 0.5: 1 - p is exact there
        else:                                                      # (Sterbenz) and SF(x) is accurate, so no cancellation
            e = 0.5 * math.erfc(-x / _SQRT2) - p
        u = e * math.sqrt(2 * math.pi) * math.exp(x * x / 2)
        return x - u / (1 + x * u / 2)

    return one(float(p)) if np.ndim(p) == 0 else np.vectorize(one, otypes=[float])(np.asarray(p, dtype=float))


# ---- curves ------------------------------------------------------------------------------------------------------------

def df_from_zero(z, t):
    """Discount factor from a continuously compounded zero rate: ``exp(-z t)``."""
    return np.exp(-np.asarray(z, dtype=float) * np.asarray(t, dtype=float))


def zero_from_df(df, t):
    """Continuously compounded zero rate from a discount factor: ``-ln(df) / t`` (t > 0)."""
    return -np.log(np.asarray(df, dtype=float)) / np.asarray(t, dtype=float)


def forward_rate(df1, df2, t1, t2, simple: bool = True):
    """The forward rate between ``t1`` and ``t2`` from their discount factors: simple (money-market,
    ``(df1/df2 - 1)/(t2 - t1)``) or continuously compounded (``ln(df1/df2)/(t2 - t1)``)."""
    ratio, tau = np.asarray(df1, dtype=float) / np.asarray(df2, dtype=float), np.asarray(t2, dtype=float) - np.asarray(t1, dtype=float)
    return (ratio - 1) / tau if simple else np.log(ratio) / tau


def _natural_spline(x, y):
    """Second derivatives of the natural cubic spline through (x, y), by the tridiagonal (Thomas) solve."""
    n = len(x)
    h = np.diff(x)
    m = np.zeros(n)
    if n < 3:
        return m
    a, b, c = h[:-1].copy(), 2 * (h[:-1] + h[1:]), h[1:].copy()
    r = 6 * (np.diff(y[1:]) / h[1:] - np.diff(y[:-1]) / h[:-1])
    for i in range(1, n - 2):                       # forward sweep
        w = a[i] / b[i - 1]
        b[i] -= w * c[i - 1]
        r[i] -= w * r[i - 1]
    sol = np.zeros(n - 2)
    sol[-1] = r[-1] / b[-1]
    for i in range(n - 4, -1, -1):
        sol[i] = (r[i] - c[i] * sol[i + 1]) / b[i]
    m[1:-1] = sol
    return m


def _spline_eval(x, y, m, t):
    t = np.clip(np.asarray(t, dtype=float), x[0], x[-1])
    i = np.clip(np.searchsorted(x, t) - 1, 0, len(x) - 2)
    h = x[i + 1] - x[i]
    a, b = (x[i + 1] - t) / h, (t - x[i]) / h
    return a * y[i] + b * y[i + 1] + ((a ** 3 - a) * m[i] + (b ** 3 - b) * m[i + 1]) * h * h / 6


class ZeroCurve:
    """A zero curve from pillar times (years) and continuously compounded zero rates (decimals).

    ``method``: ``'linear'`` on zero rates, ``'loglinear'`` (linear in log discount factor: piecewise-flat forwards, how
    most OIS curves are built) or ``'cubic'`` (a natural cubic spline on zero rates). Flat extrapolation of the zero
    rate beyond the last pillar, and of the first zero rate before the first.

    ``ZeroCurve([1, 2, 5, 10], [0.03, 0.032, 0.035, 0.037]).df(3)`` is the 3-year discount factor.
    """

    def __init__(self, times, zeros, method: str = "loglinear"):
        t = np.asarray(times, dtype=float)
        z = np.asarray(zeros, dtype=float)
        order = np.argsort(t)
        self.times, self.zeros, self.method = t[order], z[order], method
        if method not in ("linear", "loglinear", "cubic"):
            raise ValueError("method: 'linear', 'loglinear' or 'cubic'")
        if np.any(np.diff(self.times) <= 0):
            raise ValueError("pillar times must be distinct")
        self._m = _natural_spline(self.times, self.zeros) if method == "cubic" else None

    @classmethod
    def from_points(cls, points, tenor="tenor", rate="zeroRate", pct: bool = True, method: str = "loglinear"):
        """A curve from a document's points (``view.doc['points']``): a tenor column and a zero rate column (in percent
        when ``pct``)."""
        t = [tenor_years(p[tenor]) for p in points]
        z = [float(p[rate]) / (100.0 if pct else 1.0) for p in points]
        keep = [i for i, x in enumerate(t) if x > 0]
        return cls([t[i] for i in keep], [z[i] for i in keep], method)

    @classmethod
    def from_dfs(cls, times, dfs, method: str = "loglinear"):
        """A curve from discount factors at pillar times."""
        t = np.asarray(times, dtype=float)
        return cls(t, zero_from_df(dfs, t), method)

    def zero(self, t):
        """The continuously compounded zero rate at ``t`` years."""
        t_arr = np.asarray(t, dtype=float)
        x, z = self.times, self.zeros
        tt = np.clip(t_arr, x[0], x[-1])
        if self.method == "linear":
            out = np.interp(tt, x, z)
        elif self.method == "cubic":
            out = _spline_eval(x, z, self._m, tt)
        else:
            lndf = np.interp(tt, x, -z * x)                 # linear in ln(df)
            out = np.where(tt > 0, -lndf / np.where(tt > 0, tt, 1), z[0])
        out = np.where(t_arr < x[0], z[0], np.where(t_arr > x[-1], z[-1], out))
        return float(out) if np.ndim(t) == 0 else out

    def df(self, t):
        """The discount factor at ``t`` years."""
        return np.exp(-np.asarray(self.zero(t)) * np.asarray(t, dtype=float)) if np.ndim(t) else math.exp(-self.zero(t) * float(t))

    def forward(self, t1, t2, simple: bool = True):
        """The forward rate between ``t1`` and ``t2`` (simple by default)."""
        return forward_rate(self.df(t1), self.df(t2), t1, t2, simple)

    def shifted(self, bp=0.0, twist_bp=0.0, fly_bp=0.0, pivot: float = 5.0, wing: float = 5.0):
        """A new curve with zero rates moved (all in basis points), an illustrative scenario generator:

        * ``bp``: in parallel;
        * ``twist_bp``: a steepener (negative: a flattener) pivoting at ``pivot`` years, linear in log maturity and
          capped ``wing`` times either side of the pivot (1Y and 25Y by default), scaled so that the 2s10s spread moves
          by exactly ``twist_bp``;
        * ``fly_bp``: a butterfly, wings up and belly (the pivot) down by half of it each: the 2s5s10s butterfly
          (2 x 5Y - 2Y - 10Y) falls by about ``fly_bp``."""
        x = np.clip(np.log(np.maximum(self.times, 1e-6) / pivot) / math.log(wing), -1.0, 1.0)
        move = bp + twist_bp * x + fly_bp * (np.abs(x) - 0.5)
        return ZeroCurve(self.times, self.zeros + move / 1e4, self.method)

    def key_rate_bumped(self, i: int, bp: float = 1.0):
        """A new curve with only pillar ``i`` moved by ``bp`` (a triangular key-rate bump: the neighbours' interpolation
        spreads it to zero at the adjacent pillars)."""
        z = self.zeros.copy()
        z[i] += bp / 1e4
        return ZeroCurve(self.times, z, self.method)


def annuity(curve: ZeroCurve, maturity: float, freq: int = 1, start: float = 0.0) -> float:
    """The PV01 annuity of a fixed leg: ``sum(tau_i df(t_i))`` over coupon dates every ``1/freq`` years from ``start`` to
    ``maturity`` (a short first period if they do not divide)."""
    dates = _schedule(start, maturity, freq)
    taus = np.diff(np.concatenate([[start], dates]))
    return float(np.sum(taus * curve.df(dates)))


def _schedule(start: float, maturity: float, freq: int):
    n = max(1, math.ceil((maturity - start) * freq - 1e-6))      # a short first period when they do not divide
    dates = maturity - np.arange(n)[::-1] / freq
    return dates[dates > start + 1e-9]


def par_swap_rate(curve: ZeroCurve, maturity: float, freq: int = 1, start: float = 0.0, projection: ZeroCurve | None = None) -> float:
    """The par fixed rate of a swap from ``start`` to ``maturity``: the floating leg's PV over the annuity. Single-curve
    (``(df(start) - df(T)) / annuity``) unless a ``projection`` curve gives the forwards (then the floating leg pays the
    projection forwards discounted on ``curve``, at the same frequency: a simplification)."""
    a = annuity(curve, maturity, freq, start)
    if projection is None:
        return float((curve.df(start) - curve.df(maturity)) / a)
    dates = _schedule(start, maturity, freq)
    prev = np.concatenate([[start], dates[:-1]])
    taus = dates - prev
    fwd = projection.forward(prev, dates)
    return float(np.sum(fwd * taus * curve.df(dates)) / a)


def swap_pv(curve: ZeroCurve, fixed_rate: float, notional: float, maturity: float, freq: int = 1, receive_fixed: bool = True,
            start: float = 0.0, projection: ZeroCurve | None = None) -> float:
    """The PV of a plain fixed/float swap from today (or a forward start) on a curve: (fixed - par) x annuity x notional,
    signed for the receiver of fixed. Ignores accrued, stubs and the current fixing: a curve-risk tool, not a booking
    price."""
    par = par_swap_rate(curve, maturity, freq, start, projection)
    pv = (fixed_rate - par) * annuity(curve, maturity, freq, start) * notional
    return float(pv if receive_fixed else -pv)


def bootstrap_par(maturities, par_rates, freq: int = 1):
    """Discount factors from par swap rates (decimals) at ``maturities`` (years), the classic bootstrap: each pillar's
    discount factor makes its swap price at par, with par rates linear in between for the coupon dates the pillars
    skip. Returns ``(times, dfs)`` on the coupon grid. Single-curve and annual-style; a teaching bootstrap, not a
    production one."""
    mats, pars = np.asarray(maturities, dtype=float), np.asarray(par_rates, dtype=float)
    grid = np.arange(1, int(round(mats.max() * freq)) + 1) / freq
    grid = np.unique(np.concatenate([mats[mats < 1 / freq], grid]))
    rates = np.interp(grid, mats, pars)
    dfs = []
    for i, (t, s) in enumerate(zip(grid, rates)):
        if t <= 1 / freq + 1e-12:
            dfs.append(1 / (1 + s * t))
            continue
        prev_t = np.concatenate([[0.0], grid[:i]])
        taus = np.diff(np.concatenate([prev_t, [t]]))
        known = np.sum(taus[:-1] * np.array(dfs))
        dfs.append((1 - s * known) / (1 + s * taus[-1]))
    return grid, np.array(dfs)


# ---- bonds -------------------------------------------------------------------------------------------------------------

def bond_cashflows(coupon: float, years: float, freq: int = 2, face: float = 100.0):
    """The cashflow times (years from today) and amounts of a bullet bond with ``years`` to maturity: a coupon of
    ``coupon/freq x face`` each period, the face at maturity. Returns ``(times, amounts, accrued)``; accrued interest
    is for the part of the current period already run."""
    times = _schedule(0.0, years, freq)
    amounts = np.full(len(times), coupon / freq * face)
    amounts[-1] += face
    period = 1 / freq
    accrued = coupon / freq * face * (1 - (times[0] / period)) if len(times) else 0.0
    return times, amounts, max(0.0, accrued)


def bond_price(yld: float, coupon: float, years: float, freq: int = 2, face: float = 100.0, clean: bool = True) -> float:
    """The price of a bullet bond from its yield to maturity (compounded ``freq`` times a year, street convention):
    clean (dirty less accrued) by default."""
    times, amounts, accrued = bond_cashflows(coupon, years, freq, face)
    dirty = float(np.sum(amounts / (1 + yld / freq) ** (times * freq)))
    return dirty - accrued if clean else dirty


def bond_yield(price: float, coupon: float, years: float, freq: int = 2, face: float = 100.0, clean: bool = True) -> float:
    """The yield to maturity that reprices the bond to ``price`` (Brent between -50% and 100%)."""
    return brent(lambda y: bond_price(y, coupon, years, freq, face, clean) - price, -0.5, 1.0)


def bond_risk(yld: float, coupon: float, years: float, freq: int = 2, face: float = 100.0) -> dict:
    """Yield risk of a bullet bond: Macaulay and modified duration (years), convexity (years squared), DV01 (price
    change per 100 face for -1 bp, positive for a long) and the dirty price."""
    times, amounts, _ = bond_cashflows(coupon, years, freq, face)
    v = (1 + yld / freq) ** (-times * freq)
    pv = amounts * v
    dirty = float(pv.sum())
    mac = float(np.sum(times * pv) / dirty)
    mod = mac / (1 + yld / freq)
    conv = float(np.sum(pv * times * (times + 1 / freq)) / (dirty * (1 + yld / freq) ** 2))
    return {"dirty": dirty, "macaulay": mac, "modified": mod, "convexity": conv, "dv01": mod * dirty / 1e4}


def z_spread(dirty_price: float, times, amounts, curve: ZeroCurve) -> float:
    """The Z-spread (decimal, continuously compounded) that, added to every zero rate of ``curve``, discounts the
    cashflows to ``dirty_price``."""
    t, a = np.asarray(times, dtype=float), np.asarray(amounts, dtype=float)
    z = np.asarray(curve.zero(t), dtype=float)
    return brent(lambda s: float(np.sum(a * np.exp(-(z + s) * t))) - dirty_price, -0.5, 2.0)


# ---- options -----------------------------------------------------------------------------------------------------------

def _d12(F, K, T, sigma):
    """d1, d2 and the total standard deviation. With no deviation left (T = 0 or sigma = 0) d1 = d2 = +-inf by the sign
    of ln(F/K), and +inf at the money: N(d1) - N(d2) then prices the intrinsic value of the forward (0 at the money),
    the limit of the formula, instead of 0/0 = NaN."""
    F, K, T, sigma = (np.asarray(v, dtype=float) for v in (F, K, T, sigma))
    sd = sigma * np.sqrt(T)
    with np.errstate(divide="ignore", invalid="ignore"):
        d1 = (np.log(F / K) + 0.5 * sd * sd) / sd
        d1 = np.where(sd > 0, d1, np.where(F < K, -np.inf, np.inf))
    return d1, d1 - sd, sd


def _out(x, *inputs):
    return float(x) if all(np.ndim(i) == 0 for i in inputs) else x


def black76(F, K, T, r, sigma, call=True):
    """Black-76: an option on a forward or future ``F`` with strike ``K``, expiry ``T``, discounted at ``r``.
    ``call`` may be a bool or an array of bools."""
    d1, d2, _ = _d12(F, K, T, sigma)
    df = np.exp(-np.asarray(r, dtype=float) * np.asarray(T, dtype=float))
    c = df * (np.asarray(F) * norm_cdf(d1) - np.asarray(K) * norm_cdf(d2))
    p = df * (np.asarray(K) * norm_cdf(-d2) - np.asarray(F) * norm_cdf(-d1))
    return _out(np.where(call, c, p), F, K, T, r, sigma, call)


def black76_greeks(F, K, T, r, sigma, call=True) -> dict:
    """Black-76 Greeks: delta (to the forward, discounted), gamma, vega (per 1.00 of vol; divide by 100 for a point),
    theta (per year) and the price."""
    d1, d2, sd = _d12(F, K, T, sigma)
    T_, r_, F_ = (np.asarray(v, dtype=float) for v in (T, r, F))
    df = np.exp(-r_ * T_)
    price = black76(F, K, T, r, sigma, call)
    delta = np.where(call, df * norm_cdf(d1), -df * norm_cdf(-d1))
    gamma = df * norm_pdf(d1) / (F_ * sd)
    vega = df * F_ * norm_pdf(d1) * np.sqrt(T_)
    theta = -df * F_ * norm_pdf(d1) * np.asarray(sigma) / (2 * np.sqrt(T_)) + r_ * np.asarray(price)
    return {k: _out(v, F, K, T, r, sigma, call) for k, v in
            {"price": price, "delta": delta, "gamma": gamma, "vega": vega, "theta": theta}.items()}


def black_scholes(S, K, T, r, sigma, q=0.0, call=True):
    """Black-Scholes-Merton: a European option on a spot ``S`` paying a continuous yield ``q`` (dividends; the foreign
    rate for FX), risk-free rate ``r``."""
    F = np.asarray(S, dtype=float) * np.exp((np.asarray(r, dtype=float) - np.asarray(q, dtype=float)) * np.asarray(T, dtype=float))
    return _out(np.asarray(black76(F, K, T, r, sigma, call)), S, K, T, r, sigma, q, call)


def bs_greeks(S, K, T, r, sigma, q=0.0, call=True) -> dict:
    """Black-Scholes-Merton Greeks: price, delta (spot), gamma, vega (per 1.00 vol), theta (per year), rho (per 1.00
    rate). Divide vega and rho by 100 for one point, theta by 365 for a day."""
    S_, K_, T_, r_, s_, q_ = (np.asarray(v, dtype=float) for v in (S, K, T, r, sigma, q))
    d1 = (np.log(S_ / K_) + (r_ - q_ + 0.5 * s_ ** 2) * T_) / (s_ * np.sqrt(T_))
    d2 = d1 - s_ * np.sqrt(T_)
    dq, dr = np.exp(-q_ * T_), np.exp(-r_ * T_)
    price = black_scholes(S, K, T, r, sigma, q, call)
    delta = np.where(call, dq * norm_cdf(d1), -dq * norm_cdf(-d1))
    gamma = dq * norm_pdf(d1) / (S_ * s_ * np.sqrt(T_))
    vega = S_ * dq * norm_pdf(d1) * np.sqrt(T_)
    common = -S_ * dq * norm_pdf(d1) * s_ / (2 * np.sqrt(T_))
    theta = np.where(call, common - r_ * K_ * dr * norm_cdf(d2) + q_ * S_ * dq * norm_cdf(d1),
                     common + r_ * K_ * dr * norm_cdf(-d2) - q_ * S_ * dq * norm_cdf(-d1))
    rho = np.where(call, K_ * T_ * dr * norm_cdf(d2), -K_ * T_ * dr * norm_cdf(-d2))
    return {k: _out(v, S, K, T, r, sigma, q, call) for k, v in
            {"price": price, "delta": delta, "gamma": gamma, "vega": vega, "theta": theta, "rho": rho}.items()}


def garman_kohlhagen(S, K, T, rd, rf, sigma, call=True):
    """Garman-Kohlhagen: a European FX option (price in the quote, domestic, currency per unit of the base, foreign,
    currency): Black-Scholes with the foreign rate ``rf`` as the yield."""
    return black_scholes(S, K, T, rd, sigma, rf, call)


def gk_greeks(S, K, T, rd, rf, sigma, call=True) -> dict:
    """Garman-Kohlhagen Greeks (as ``bs_greeks``; ``rho`` is to the domestic rate)."""
    return bs_greeks(S, K, T, rd, sigma, rf, call)


def bachelier(F, K, T, sigma_n, df=1.0, call=True):
    """The Bachelier (normal) model, for rates options quoted in normal vol (``sigma_n`` in rate units: 0.0100 is 100 bp
    a year): ``df x [(F-K) N(d) + sigma_n sqrt(T) n(d)]`` for a call."""
    F_, K_, T_, s_ = (np.asarray(v, dtype=float) for v in (F, K, T, sigma_n))
    sd = s_ * np.sqrt(T_)
    with np.errstate(divide="ignore", invalid="ignore"):    # no deviation left (T = 0 or sigma = 0): intrinsic value
        d = np.where(sd > 0, (F_ - K_) / sd, np.where(F_ < K_, -np.inf, np.inf))
    c = np.asarray(df) * ((F_ - K_) * norm_cdf(d) + sd * norm_pdf(d))
    p = c - np.asarray(df) * (F_ - K_)
    return _out(np.where(call, c, p), F, K, T, sigma_n, df, call)


def implied_vol(price: float, pricer, lo: float = 1e-4, hi: float = 5.0) -> float:
    """The volatility at which ``pricer(sigma)`` equals ``price``, by Brent on ``[lo, hi]``:
    ``implied_vol(12.3, lambda s: black_scholes(100, 105, 1, 0.03, s))``. Raises ValueError when the price lies outside
    what the model can produce (below intrinsic, above the forward)."""
    return brent(lambda s: float(pricer(s)) - price, lo, hi, tol=1e-10)


def fx_strike_from_delta(S, T, rd, rf, sigma, delta, call: bool = True):
    """The strike of an FX option with a given spot delta (unadjusted for premium; ``delta`` positive, 0.25 for a 25-delta
    call or put): ``K = F exp(-+ sigma sqrt(T) N^-1(delta e^{rf T}) + sigma^2 T / 2)``."""
    F = S * math.exp((rd - rf) * T)
    x = norm_ppf(delta * math.exp(rf * T))
    sd = sigma * math.sqrt(T)
    return F * math.exp(-x * sd + 0.5 * sd * sd) if call else F * math.exp(x * sd + 0.5 * sd * sd)


def smile_from_rr_bf(atm, rr, bf) -> dict:
    """The 25-delta call and put vols from the ATM vol, the 25-delta risk reversal (call minus put) and butterfly
    (strangle over ATM): ``call = atm + bf + rr/2``, ``put = atm + bf - rr/2`` (the market's smile convention, no
    broker-strangle adjustment)."""
    return {"call25": atm + bf + rr / 2, "put25": atm + bf - rr / 2, "atm": atm}


def svi_total_variance(k, a, b, rho, m, sigma):
    """Raw SVI total implied variance at log-moneyness ``k``: ``a + b (rho (k - m) + sqrt((k - m)^2 + sigma^2))``
    (Gatheral 2004). Implied vol is ``sqrt(w / T)``."""
    k = np.asarray(k, dtype=float)
    return a + b * (rho * (k - m) + np.sqrt((k - m) ** 2 + sigma ** 2))


# ---- realised volatility -----------------------------------------------------------------------------------------------

def close_to_close_vol(close, periods: int = 252) -> float:
    """Annualised close-to-close volatility: the sample standard deviation of log returns x sqrt(periods)."""
    r = np.diff(np.log(np.asarray(close, dtype=float)))
    return float(np.std(r, ddof=1) * math.sqrt(periods))


def parkinson_vol(high, low, periods: int = 252) -> float:
    """Parkinson's high-low estimator, annualised: ``sqrt(mean(ln(H/L)^2) / (4 ln 2))``. About five times as efficient
    as close-to-close, but blind to overnight gaps and biased low with discrete prices."""
    hl = np.log(np.asarray(high, dtype=float) / np.asarray(low, dtype=float))
    return float(math.sqrt(np.mean(hl ** 2) / (4 * math.log(2)) * periods))


def garman_klass_vol(open_, high, low, close, periods: int = 252) -> float:
    """Garman-Klass OHLC estimator, annualised: ``0.5 ln(H/L)^2 - (2 ln 2 - 1) ln(C/O)^2`` averaged. Ignores drift and
    opening jumps."""
    o, h, lo, c = (np.asarray(v, dtype=float) for v in (open_, high, low, close))
    v = 0.5 * np.log(h / lo) ** 2 - (2 * math.log(2) - 1) * np.log(c / o) ** 2
    return float(math.sqrt(max(np.mean(v), 0.0) * periods))


# ---- risk --------------------------------------------------------------------------------------------------------------

def historical_var(pnl, confidence: float = 0.99) -> float:
    """Historical-simulation VaR as a positive loss: the negative of the ``1 - confidence`` quantile of the P&Ls, taken
    as the k-th worst scenario with ``k = floor(n (1 - confidence))`` (the 5th worst of 500 at 99%)."""
    x = np.sort(np.asarray(pnl, dtype=float))
    k = max(int(math.floor(len(x) * (1 - confidence) + 1e-9)), 1)
    return float(-x[k - 1])


def expected_shortfall(pnl, confidence: float = 0.975) -> float:
    """Expected shortfall as a positive loss: the mean of the worst ``ceil(n (1 - confidence))`` P&Ls."""
    x = np.sort(np.asarray(pnl, dtype=float))
    k = max(int(math.ceil(len(x) * (1 - confidence) - 1e-9)), 1)
    return float(-x[:k].mean())


def parametric_var(sigma: float, confidence: float = 0.99, mu: float = 0.0, horizon_days: float = 1.0) -> float:
    """Normal (delta-normal) VaR as a positive loss: ``z sigma sqrt(h) - mu h`` for a daily P&L standard deviation
    ``sigma`` and mean ``mu``. Assumes normal P&L: it understates fat tails."""
    return float(norm_ppf(confidence) * sigma * math.sqrt(horizon_days) - mu * horizon_days)


def kupiec_pof(exceptions: int, n: int, confidence: float = 0.99) -> dict:
    """Kupiec's proportion-of-failures test of a VaR model: the likelihood ratio of ``exceptions`` breaches in ``n``
    days against the expected rate ``1 - confidence``, its p-value (chi-squared, one degree of freedom) and the Basel
    traffic light for 250 days at 99% (green up to 4, amber 5-9, red 10 or more)."""
    p = 1 - confidence
    x = int(exceptions)
    phat = x / n if n else 0.0

    def ll(prob):
        prob = min(max(prob, 1e-15), 1 - 1e-15)
        return (n - x) * math.log(1 - prob) + x * math.log(prob)

    lr = max(-2 * (ll(p) - ll(phat)), 0.0)
    pvalue = math.erfc(math.sqrt(lr / 2))                         # chi2(1) survival
    scaled = x * 250 / n if n else 0
    light = "green" if scaled <= 4 else "amber" if scaled <= 9 else "red"
    return {"exceptions": x, "days": n, "expected": p * n, "lr": lr, "pvalue": pvalue, "light": light}


def max_drawdown(values) -> dict:
    """The largest peak-to-trough fall of a cumulative series (a price, or cumulative P&L): ``drawdown`` (a negative
    amount), ``peak`` and ``trough`` positions, and the drawdown series itself."""
    v = np.asarray(values, dtype=float)
    peak = np.maximum.accumulate(v)
    dd = v - peak
    trough = int(np.argmin(dd))
    start = int(np.argmax(v[: trough + 1])) if trough > 0 else 0
    return {"drawdown": float(dd[trough]), "peak": start, "trough": trough, "series": dd}


def sharpe(pnl, periods: int = 252) -> float:
    """Annualised Sharpe ratio of a P&L or return series (no risk-free deduction): ``mean / std x sqrt(periods)``."""
    x = np.asarray(pnl, dtype=float)
    s = x.std(ddof=1)
    return float(x.mean() / s * math.sqrt(periods)) if s > 0 else float("nan")


def hhi(amounts) -> float:
    """The Herfindahl-Hirschman index of concentration: the sum of squared shares of the absolute amounts (1: all in
    one name; 1/n: spread evenly)."""
    a = np.abs(np.asarray(amounts, dtype=float))
    s = a.sum()
    return float(np.sum((a / s) ** 2)) if s > 0 else float("nan")


# ---- credit ------------------------------------------------------------------------------------------------------------

def hazard_from_spread(spread_bp, recovery: float = 0.4):
    """The flat hazard rate implied by a CDS spread, by the credit triangle: ``lambda = s / (1 - R)`` (spread in bp).
    An approximation that ignores the premium leg's accrual and the term structure."""
    return np.asarray(spread_bp, dtype=float) / 1e4 / (1 - recovery) if np.ndim(spread_bp) else float(spread_bp) / 1e4 / (1 - recovery)


def forward_hazards(times, term_hazards):
    """Piecewise-flat (forward) hazard rates from term hazards, the flat rate to each tenor (``S(t_i) = exp(-h_i t_i)``):
    ``(h_i t_i - h_{i-1} t_{i-1}) / (t_i - t_{i-1})``, floored at zero. ``survival(t, times, forward_hazards(...))``
    then matches the term survival probabilities at every tenor."""
    t = np.asarray(times, dtype=float)
    cum = np.asarray(term_hazards, dtype=float) * t
    return np.maximum(np.diff(np.concatenate([[0.0], cum])) / np.diff(np.concatenate([[0.0], t])), 0.0)


def survival(t, times, hazards):
    """Survival probability at ``t`` under piecewise-constant hazard rates: ``hazards[i]`` applies up to ``times[i]``
    (the last one beyond)."""
    knots = np.asarray(times, dtype=float)
    lam = np.asarray(hazards, dtype=float)
    t_arr = np.atleast_1d(np.asarray(t, dtype=float))
    edges = np.concatenate([[0.0], knots])
    out = []
    for x in t_arr:
        spans = np.clip(np.minimum(edges[1:], x) - edges[:-1], 0, None)
        integral = float(np.sum(spans * lam)) + max(x - knots[-1], 0.0) * lam[-1]
        out.append(math.exp(-integral))
    return out[0] if np.ndim(t) == 0 else np.array(out)


def cva_from_profile(times, ee, hazard_times, hazards, recovery: float = 0.4, discount: ZeroCurve | None = None,
                     rate: float = 0.0) -> dict:
    """Unilateral CVA from an expected-exposure profile: ``(1 - R) sum EE(t_i) DF(t_i) [S(t_{i-1}) - S(t_i)]``, EE at the
    end of each period (a simplification; exact CVA integrates over default times and uses discounted EE). Discounting
    on ``discount`` or a flat ``rate``. Returns the CVA (positive: a cost), and per-period contributions."""
    t = np.asarray(times, dtype=float)
    e = np.maximum(np.asarray(ee, dtype=float), 0.0)
    s = survival(t, hazard_times, hazards)
    s_prev = np.concatenate([[1.0], s[:-1]])
    pd_marg = np.clip(s_prev - s, 0.0, None)
    df = discount.df(t) if discount is not None else np.exp(-rate * t)
    contrib = (1 - recovery) * e * df * pd_marg
    return {"cva": float(contrib.sum()), "contrib": contrib, "survival": s, "pd": pd_marg, "df": df}


# ---- FRTB standardised (simplified) ------------------------------------------------------------------------------------

def frtb_bucket_charge(ws, rho) -> float:
    """Within one bucket: ``K_b = sqrt(max(0, sum WS_k^2 + sum_{k != l} rho_kl WS_k WS_l))`` for weighted sensitivities
    ``ws`` and a correlation (a number for every pair, or a matrix). The FRTB sensitivities-based method's formula;
    risk weights, the three correlation scenarios and curvature are left to the caller."""
    w = np.asarray(ws, dtype=float)
    r = np.full((len(w), len(w)), float(rho)) if np.ndim(rho) == 0 else np.asarray(rho, dtype=float)
    np.fill_diagonal(r, 1.0)
    return float(math.sqrt(max(0.0, float(w @ r @ w))))


def frtb_across_buckets(kb, sb, gamma) -> float:
    """Across buckets: ``sqrt(sum K_b^2 + sum_{b != c} gamma_bc S_b S_c)``, with ``S_b`` the bucket's net sensitivity
    (``sum WS_k``), or, when the sum under the root is negative, the alternative ``S_b`` capped at +-K_b."""
    k = np.asarray(kb, dtype=float)
    s = np.asarray(sb, dtype=float)
    g = np.full((len(k), len(k)), float(gamma)) if np.ndim(gamma) == 0 else np.asarray(gamma, dtype=float)
    np.fill_diagonal(g, 0.0)
    total = float(np.sum(k ** 2) + s @ g @ s)
    if total < 0:                                   # the alternative: each S_b capped at +-K_b
        s = np.clip(s, -k, k)
        total = float(np.sum(k ** 2) + s @ g @ s)
    return float(math.sqrt(max(total, 0.0)))
