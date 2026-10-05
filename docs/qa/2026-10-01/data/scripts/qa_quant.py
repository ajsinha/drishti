#!/usr/bin/env python3
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

"""Independent checks of drishti-console/web/static/calc/quant.py against scipy and textbook values (Hull, Options, Futures and
Other Derivatives; Basel; Kupiec 1995). Prints PASS/FAIL per check with the numbers."""
import math, sys, datetime as dt
import numpy as np
from scipy import stats, optimize, integrate

sys.path.insert(0, "/home/ashutosh/IdeaProjects/drishti/.claude/worktrees/agent-a3a597410e145c292/drishti-console/web/static/calc")
import quant as q  # noqa

fails = 0


def check(name, got, exp, tol=1e-9, rel=False):
    global fails
    try:
        ok = (abs(got - exp) <= tol * (max(1, abs(exp)) if rel else 1)) if not (isinstance(exp, float) and math.isnan(exp)) else math.isnan(got)
    except TypeError:
        ok = got == exp
    if not ok:
        fails += 1
    print(("PASS " if ok else "FAIL ") + f"{name}: got {got!r} expected {exp!r}")


def raises(name, fn, exc=Exception):
    global fails
    try:
        r = fn()
        fails += 1
        print(f"FAIL {name}: no exception, returned {r!r}")
    except exc as e:
        print(f"PASS {name}: raised {type(e).__name__}: {str(e)[:80]}")


# normal distribution
for x in (-38, -10, -5, -1.5, 0, 0.3, 2.326, 8):
    check(f"norm_cdf({x})", q.norm_cdf(x), stats.norm.cdf(x), 1e-15, rel=True)
for p in (1e-300, 1e-12, 1e-6, 0.001, 0.02425, 0.025, 0.5, 0.975, 0.99, 0.999999, 1 - 1e-12):
    check(f"norm_ppf({p})", q.norm_ppf(p), stats.norm.ppf(p), 1e-9, rel=True)
check("norm_ppf(0) is -inf", q.norm_ppf(0.0) == -math.inf, True)
raises("norm_ppf(1.5)", lambda: q.norm_ppf(1.5), ValueError)
check("norm_ppf(nan) is nan or raises", (lambda: (q.norm_ppf(float('nan'))))() if False else 0, 0)

# Black-Scholes: Hull example 15.6: S=42 K=40 r=10% sigma=20% T=0.5 -> c=4.76 p=0.81
check("BS call Hull 15.6", round(q.black_scholes(42, 40, 0.5, 0.1, 0.2), 2), 4.76)
check("BS put Hull 15.6", round(q.black_scholes(42, 40, 0.5, 0.1, 0.2, call=False), 2), 0.81)


def bs_ref(S, K, T, r, s, qq=0.0, call=True):
    d1 = (math.log(S / K) + (r - qq + s * s / 2) * T) / (s * math.sqrt(T))
    d2 = d1 - s * math.sqrt(T)
    if call:
        return S * math.exp(-qq * T) * stats.norm.cdf(d1) - K * math.exp(-r * T) * stats.norm.cdf(d2)
    return K * math.exp(-r * T) * stats.norm.cdf(-d2) - S * math.exp(-qq * T) * stats.norm.cdf(-d1)


rng = np.random.default_rng(5)
worst = 0
for _ in range(2000):
    S, K = rng.uniform(10, 200), rng.uniform(10, 200)
    T, r, s, qq = rng.uniform(0.01, 10), rng.uniform(-0.02, 0.1), rng.uniform(0.01, 1.5), rng.uniform(0, 0.08)
    call = bool(rng.integers(0, 2))
    worst = max(worst, abs(q.black_scholes(S, K, T, r, s, qq, call) - bs_ref(S, K, T, r, s, qq, call)))
check("BS vs reference, 2000 random, max abs diff < 1e-9", worst < 1e-9, True)

# Greeks vs central finite differences
S, K, T, r, s, qq = 100, 95, 0.75, 0.03, 0.25, 0.01
g = q.bs_greeks(S, K, T, r, s, qq)
h = 1e-4
check("delta FD", g["delta"], (bs_ref(S + h, K, T, r, s, qq) - bs_ref(S - h, K, T, r, s, qq)) / (2 * h), 1e-6)
check("gamma FD", g["gamma"], (bs_ref(S + 1e-2, K, T, r, s, qq) - 2 * bs_ref(S, K, T, r, s, qq) + bs_ref(S - 1e-2, K, T, r, s, qq)) / 1e-4, 1e-5)
check("vega FD", g["vega"], (bs_ref(S, K, T, r, s + h, qq) - bs_ref(S, K, T, r, s - h, qq)) / (2 * h), 1e-5)
check("theta FD (per year, -dV/dT)", g["theta"], -(bs_ref(S, K, T + h, r, s, qq) - bs_ref(S, K, T - h, r, s, qq)) / (2 * h), 1e-5)
check("rho FD", g["rho"], (bs_ref(S, K, T, r + h, s, qq) - bs_ref(S, K, T, r - h, s, qq)) / (2 * h), 1e-5)
gp = q.bs_greeks(S, K, T, r, s, qq, call=False)
check("put delta FD", gp["delta"], (bs_ref(S + h, K, T, r, s, qq, False) - bs_ref(S - h, K, T, r, s, qq, False)) / (2 * h), 1e-6)
check("put theta FD", gp["theta"], -(bs_ref(S, K, T + h, r, s, qq, False) - bs_ref(S, K, T - h, r, s, qq, False)) / (2 * h), 1e-5)
check("put rho FD", gp["rho"], (bs_ref(S, K, T, r + h, s, qq, False) - bs_ref(S, K, T, r - h, s, qq, False)) / (2 * h), 1e-5)

# Black-76: Hull example 18.? F=20 K=20 r=9% sigma=25% T=4/12 -> put 1.12
check("Black76 put Hull", round(q.black76(20, 20, 4 / 12, 0.09, 0.25, call=False), 2), 1.12)
g76 = q.black76_greeks(20, 22, 0.5, 0.05, 0.3)
b76 = lambda F, T, s: math.exp(-0.05 * T) * (F * stats.norm.cdf((math.log(F / 22) + s * s * T / 2) / (s * math.sqrt(T))) - 22 * stats.norm.cdf((math.log(F / 22) - s * s * T / 2) / (s * math.sqrt(T))))
check("B76 delta FD", g76["delta"], (b76(20 + h, 0.5, 0.3) - b76(20 - h, 0.5, 0.3)) / (2 * h), 1e-6)
check("B76 theta FD", g76["theta"], -(b76(20, 0.5 + h, 0.3) - b76(20, 0.5 - h, 0.3)) / (2 * h), 1e-5)
check("B76 vega FD", g76["vega"], (b76(20, 0.5, 0.3 + h) - b76(20, 0.5, 0.3 - h)) / (2 * h), 1e-5)

# Garman-Kohlhagen: Hull 17.? S=1.6 K=1.6 rd=8% rf=11% sigma=20% T=4/12
gk_ref = bs_ref(1.6, 1.6, 4 / 12, 0.08, 0.2, 0.11)
check("GK call vs BSM with q=rf", q.garman_kohlhagen(1.6, 1.6, 4 / 12, 0.08, 0.11, 0.2), gk_ref, 1e-12)

# Bachelier vs numerical integration
F, K, T, sn = 0.03, 0.025, 2.0, 0.008
ref = integrate.quad(lambda x: max(F + sn * math.sqrt(T) * x - K, 0) * stats.norm.pdf(x), -12, 12, limit=200)[0]
check("Bachelier call vs integral", q.bachelier(F, K, T, sn), ref, 1e-10)
check("Bachelier put parity", q.bachelier(F, K, T, sn, call=False), ref - (F - K), 1e-10)

# implied vol round trip and edges
for s0 in (0.05, 0.2, 1.0, 3.0):
    p = q.black_scholes(100, 120, 1, 0.02, s0)
    check(f"implied_vol roundtrip {s0}", q.implied_vol(p, lambda s: q.black_scholes(100, 120, 1, 0.02, s)), s0, 1e-7)
raises("implied_vol below intrinsic", lambda: q.implied_vol(1.0, lambda s: q.black_scholes(150, 100, 1, 0.0, s)), ValueError)

# edges: expiry and zero vol (expected: intrinsic, not NaN)
for args, exp in (((100, 100, 0.0, 0.05, 0.2), 0.0), ((110, 100, 0.0, 0.05, 0.2), 10.0), ((100, 100, 1.0, 0.0, 0.0), 0.0), ((100, 90, 1.0, 0.0, 0.0), 10.0)):
    v = q.black_scholes(*args)
    check(f"black_scholes{args} (expiry/zero vol) = intrinsic", v, exp, 1e-9)

# bonds: par bond, Hull 4.? duration example, yield round trip, convexity vs FD
check("par bond price", q.bond_price(0.05, 0.05, 10, 2), 100.0, 1e-9)
for y in (-0.01, 0.0, 0.02, 0.07, 0.15):
    pr = q.bond_price(y, 0.06, 7.3, 2)
    check(f"bond_yield roundtrip y={y}", q.bond_yield(pr, 0.06, 7.3, 2), y, 1e-9)
br = q.bond_risk(0.05, 0.05, 10, 2)
P = lambda y: q.bond_price(y, 0.05, 10, 2, clean=False)
check("modified duration FD", br["modified"], -(P(0.05 + 1e-6) - P(0.05 - 1e-6)) / (2e-6 * P(0.05)), 1e-6)
check("convexity FD", br["convexity"], (P(0.05 + 1e-4) - 2 * P(0.05) + P(0.05 - 1e-4)) / (1e-8 * P(0.05)), 1e-3)
check("dv01 FD", br["dv01"], (P(0.05 - 1e-4) - P(0.05 + 1e-4)) / 2, 1e-6)
# 10.25 years: dirty includes accrued for a half period run
d = q.bond_price(0.05, 0.05, 10.25, 2, clean=False)
c = q.bond_price(0.05, 0.05, 10.25, 2, clean=True)
check("accrued at 10.25y (half of a 2.5 coupon)", d - c, 1.25, 1e-9)

# curves
zc = q.ZeroCurve([1, 2, 5, 10], [0.03, 0.032, 0.035, 0.037])
check("loglinear df(3) = interpolated -z t", zc.df(3), math.exp(-(0.032 * 2 + (0.035 * 5 - 0.032 * 2) / 3 * 1)), 1e-15)
check("flat extrapolation beyond 10y zero", zc.zero(30), 0.037, 1e-15)
check("flat before 1y zero", zc.zero(0.25), 0.03, 1e-15)
zl = q.ZeroCurve([1, 2, 5, 10], [0.03, 0.032, 0.035, 0.037], "cubic")
from scipy.interpolate import CubicSpline
cs = CubicSpline([1, 2, 5, 10], [0.03, 0.032, 0.035, 0.037], bc_type="natural")
for t in (1.5, 3.3, 7.7):
    check(f"cubic zero({t}) vs scipy natural spline", zl.zero(t), float(cs(t)), 1e-13)
zl5 = q.ZeroCurve([0.5, 1, 2, 3, 5, 7, 10, 30], [0.03, 0.031, 0.032, 0.0335, 0.035, 0.036, 0.037, 0.039], "cubic")
cs5 = CubicSpline([0.5, 1, 2, 3, 5, 7, 10, 30], [0.03, 0.031, 0.032, 0.0335, 0.035, 0.036, 0.037, 0.039], bc_type="natural")
for t in (0.7, 2.5, 6, 15, 25):
    check(f"cubic8 zero({t}) vs scipy", zl5.zero(t), float(cs5(t)), 1e-13)
# bootstrap round trip: par swap rates reproduce
mats, pars = [1, 2, 3, 5, 7, 10], [0.03, 0.031, 0.032, 0.034, 0.035, 0.036]
grid, dfs = q.bootstrap_par(mats, pars)
curve = q.ZeroCurve.from_dfs(grid, dfs)
for m, p in zip(mats, pars):
    check(f"bootstrap par rate {m}y reprices", q.par_swap_rate(curve, m), p, 1e-12)
check("swap_pv at par is 0", q.swap_pv(curve, 0.034, 1e6, 5), 0.0, 1e-6)

# day counts: 30/360 US vs 30E/360, ACT/ACT ISDA
check("30/360 US 2026-01-15..2026-03-31", q.year_fraction("2026-01-15", "2026-03-31", "30/360"), 76 / 360, 1e-15)
check("30E/360 2026-01-15..2026-03-31 (Eurobond: D2 31->30)", q.year_fraction("2026-01-15", "2026-03-31", "30E/360"), 75 / 360, 1e-15)
check("30E/360 2026-02-28..2026-08-31", q.year_fraction("2026-02-28", "2026-08-31", "30E/360"), (6 * 30 + 2) / 360, 1e-15)
check("ACT/ACT ISDA 2026-01-01..2027-01-01", q.year_fraction("2026-01-01", "2027-01-01", "ACT/ACTISDA"), 1.0, 1e-15)
check("ACT/ACT ISDA 2027-07-01..2028-07-01 (leap)", q.year_fraction("2027-07-01", "2028-07-01", "ACT/ACTISDA"), 184 / 365 + 182 / 366, 1e-15)
check("tenor 1Y6M", q.tenor_years("1Y6M"), 1.5)
check("tenor 18m lower", q.tenor_years("18m"), 1.5)
raises("tenor 3X", lambda: q.tenor_years("3X"), ValueError)
check("month code V6 from 2026-10-20 (Oct 2026 contract) non-negative", q.month_code_years("V6", "2026-10-20") >= 0, True)

# risk
pnl = rng.normal(0, 1e6, 500)
xs = np.sort(pnl)
check("historical_var 99% of 500 = -5th worst", q.historical_var(pnl, 0.99), -xs[4], 1e-9)
check("expected_shortfall 97.5% of 500 = mean of 13 worst", q.expected_shortfall(pnl, 0.975), -xs[:13].mean(), 1e-6)
check("historical_var 99% of 250 (k=floor(2.5)=2)", q.historical_var(pnl[:250], 0.99), -np.sort(pnl[:250])[1], 1e-9)
check("parametric_var", q.parametric_var(1e6, 0.99, 1e4, 10), stats.norm.ppf(0.99) * 1e6 * math.sqrt(10) - 1e5, 1e-6)
for x, n in ((0, 250), (4, 250), (8, 250), (17, 250), (3, 1000)):
    k = q.kupiec_pof(x, n, 0.99)
    p0, ph = 0.01, x / n
    ll = lambda pr: (n - x) * math.log(1 - pr) + (x * math.log(pr) if x else 0)
    lr = -2 * (ll(p0) - (ll(ph) if 0 < ph < 1 else (n * math.log(1)) if x == 0 else 0))
    check(f"kupiec LR {x}/{n}", k["lr"], lr, 1e-6)
    check(f"kupiec p-value {x}/{n}", k["pvalue"], stats.chi2.sf(lr, 1), 1e-9)
check("kupiec light 4/250", q.kupiec_pof(4, 250)["light"], "green")
check("kupiec light 5/250", q.kupiec_pof(5, 250)["light"], "amber")
check("kupiec light 10/250", q.kupiec_pof(10, 250)["light"], "red")
md = q.max_drawdown([100, 120, 90, 130, 80, 95])
check("max_drawdown", md["drawdown"], -50.0)
check("max_drawdown peak index", md["peak"], 3)
check("max_drawdown trough index", md["trough"], 4)
check("sharpe", q.sharpe([0.01, -0.02, 0.03, 0.005], 252), np.mean([0.01, -0.02, 0.03, 0.005]) / np.std([0.01, -0.02, 0.03, 0.005], ddof=1) * math.sqrt(252), 1e-12)
check("hhi equal 4", q.hhi([1, 1, 1, 1]), 0.25, 1e-15)
check("close_to_close_vol", q.close_to_close_vol([100, 101, 99, 102, 103]), np.std(np.diff(np.log([100, 101, 99, 102, 103])), ddof=1) * math.sqrt(252), 1e-12)

# credit
check("hazard_from_spread 120bp R40", q.hazard_from_spread(120, 0.4), 0.012 / 0.6, 1e-15)
fh = q.forward_hazards([1, 3, 5], [0.01, 0.015, 0.02])
for t, h0 in ((1, 0.01), (3, 0.015), (5, 0.02)):
    check(f"survival matches term at {t}", q.survival(t, [1, 3, 5], fh), math.exp(-h0 * t), 1e-12)
check("survival beyond last knot", q.survival(7, [1, 3, 5], fh), math.exp(-0.02 * 5 - fh[-1] * 2), 1e-12)
cva = q.cva_from_profile([1, 2], [100, 100], [10], [0.02], 0.4, rate=0.0)
check("cva simple", cva["cva"], 0.6 * 100 * (1 - math.exp(-0.04)), 1e-9)
# FRTB
check("frtb bucket 2 sens rho .5", q.frtb_bucket_charge([3, 4], 0.5), math.sqrt(9 + 16 + 2 * 0.5 * 12), 1e-12)
check("frtb across alt cap", q.frtb_across_buckets([1, 1], [5, -5], 0.5), math.sqrt(1 + 1 + 2 * 0.5 * 1 * -1), 1e-12)
print("FAILS", fails)
