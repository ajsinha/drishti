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

"""drishti.quant, Calc's shared pricing and risk maths (console/web/static/calc/quant.py), against textbook values.

It needs numpy (scipy, when present, is a second opinion), which the console itself does not: without numpy these
tests are skipped. Run them with numpy and scipy installed: ``python -m pytest console/tests/test_quant.py``."""
from __future__ import annotations

import importlib.util
import json
import math
from pathlib import Path

import pytest

np = pytest.importorskip("numpy")

ROOT = Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location("quant", ROOT / "console/web/static/calc/quant.py")
q = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(q)

try:
    from scipy import stats as _stats
    from scipy.interpolate import CubicSpline as _CubicSpline
except ImportError:      # pragma: no cover - scipy is optional
    _stats = _CubicSpline = None


def close(a, b, tol=1e-6):
    return abs(a - b) <= tol * max(1.0, abs(b))


# ---- tenors, roots, the normal distribution ------------------------------------------------------------------------------

def test_tenors_read_as_years():
    assert q.tenor_years("3M") == 0.25 and q.tenor_years("18M") == 1.5 and q.tenor_years("10Y") == 10
    assert close(q.tenor_years("1W"), 7 / 365) and close(q.tenor_years("ON"), 1 / 365) and q.tenor_years("1Y6M") == 1.5
    assert q.tenor_years("0") == 0 and q.tenor_years(2.5) == 2.5
    with pytest.raises(ValueError):
        q.tenor_years("3Q")


def test_futures_month_codes_count_from_the_business_date():
    assert close(q.month_code_years("X6", "2026-09-30"), 46 / 365)     # 15 Nov 2026
    assert q.month_code_years("Z7", "2026-09-30") > 1.0
    assert close(q.month_code_years("F27", "2026-09-30"), (107) / 365)


def test_day_counts():
    assert close(q.year_fraction("2026-01-01", "2027-01-01", "ACT/360"), 365 / 360)
    assert q.year_fraction("2026-01-31", "2026-07-31", "30/360") == 0.5
    assert close(q.year_fraction("2026-01-01", "2026-07-02"), 182 / 365)


def test_brent_and_newton_find_the_classic_root():
    f = lambda x: x ** 3 - 2 * x - 5                      # noqa: E731 - Wallis's example
    assert close(q.brent(f, 2, 3), 2.0945514815423265, 1e-12)
    assert close(q.newton(f, lambda x: 3 * x * x - 2, 2), 2.0945514815423265, 1e-12)
    with pytest.raises(ValueError):
        q.brent(f, 3, 4)


def test_the_normal_distribution():
    assert close(q.norm_cdf(1.959963984540054), 0.975, 1e-12)
    assert close(q.norm_ppf(0.99), 2.3263478740408408, 1e-12)
    assert close(q.norm_ppf(1e-6), -4.753424308822899, 1e-10)
    xs = np.linspace(-6, 6, 25)
    assert np.allclose(q.norm_ppf(q.norm_cdf(xs)), xs, atol=1e-9)
    if _stats is not None:
        assert np.allclose(q.norm_cdf(xs), _stats.norm.cdf(xs), atol=1e-15)
        assert np.allclose(q.norm_pdf(xs), _stats.norm.pdf(xs), atol=1e-15)


# ---- options ------------------------------------------------------------------------------------------------------------

def test_black_scholes_textbook_prices():
    assert close(q.black_scholes(100, 100, 1, 0.05, 0.2), 10.450583572185565, 1e-10)
    assert close(q.black_scholes(100, 100, 1, 0.05, 0.2, call=False), 5.573526022256971, 1e-10)
    assert round(q.black_scholes(42, 40, 0.5, 0.10, 0.2), 2) == 4.76                 # Hull, Example 15.6
    assert round(q.black_scholes(42, 40, 0.5, 0.10, 0.2, call=False), 2) == 0.81


def test_put_call_parity_with_a_dividend_yield():
    S, K, T, r, s, y = 95.0, 103.0, 1.7, 0.031, 0.27, 0.012
    c, p = q.black_scholes(S, K, T, r, s, y, True), q.black_scholes(S, K, T, r, s, y, False)
    assert close(c - p, S * math.exp(-y * T) - K * math.exp(-r * T), 1e-12)


def test_greeks_match_finite_differences():
    S, K, T, r, s, y = 100.0, 110.0, 0.75, 0.04, 0.3, 0.01
    g = q.bs_greeks(S, K, T, r, s, y)
    h = 1e-4
    price = lambda **kw: q.black_scholes(**{**dict(S=S, K=K, T=T, r=r, sigma=s, q=y), **kw})   # noqa: E731
    assert close(g["delta"], (price(S=S + h) - price(S=S - h)) / (2 * h), 1e-7)
    assert close(g["gamma"], (price(S=S + h) - 2 * price() + price(S=S - h)) / h ** 2, 1e-4)
    assert close(g["vega"], (price(sigma=s + h) - price(sigma=s - h)) / (2 * h), 1e-7)
    assert close(g["rho"], (price(r=r + h) - price(r=r - h)) / (2 * h), 1e-7)
    assert close(g["theta"], -(price(T=T + h) - price(T=T - h)) / (2 * h), 1e-6)
    gp = q.bs_greeks(S, K, T, r, s, y, call=False)
    assert close(g["delta"] - gp["delta"], math.exp(-y * T), 1e-12) and close(g["gamma"], gp["gamma"], 1e-12)


def test_black76_hull_example_and_its_greeks():
    assert round(q.black76(20, 20, 4 / 12, 0.09, 0.25, call=False), 2) == 1.12        # Hull, Example 18.6
    g = q.black76_greeks(60.0, 55.0, 1.25, 0.03, 0.35)
    h = 1e-4
    assert close(g["delta"], (q.black76(60 + h, 55, 1.25, 0.03, 0.35) - q.black76(60 - h, 55, 1.25, 0.03, 0.35)) / (2 * h), 1e-7)
    assert close(g["vega"], (q.black76(60, 55, 1.25, 0.03, 0.35 + h) - q.black76(60, 55, 1.25, 0.03, 0.35 - h)) / (2 * h), 1e-7)
    assert close(g["theta"], -(q.black76(60, 55, 1.25 + h, 0.03, 0.35) - q.black76(60, 55, 1.25 - h, 0.03, 0.35)) / (2 * h), 1e-6)


def test_garman_kohlhagen_is_black_scholes_with_the_foreign_rate_and_obeys_parity():
    S, K, T, rd, rf, s = 1.174, 1.20, 0.5, 0.0366, 0.0216, 0.072
    c, p = q.garman_kohlhagen(S, K, T, rd, rf, s), q.garman_kohlhagen(S, K, T, rd, rf, s, call=False)
    assert close(c - p, S * math.exp(-rf * T) - K * math.exp(-rd * T), 1e-12)
    assert round(q.garman_kohlhagen(1.6, 1.6, 4 / 12, 0.08, 0.11, 0.141), 4) == 0.0430   # Hull: the four-month GBP call


def test_bachelier_at_the_money_and_parity():
    F, T, sn, df = 0.03, 2.0, 0.0090, 0.95
    assert close(q.bachelier(F, F, T, sn, df), df * sn * math.sqrt(T) / math.sqrt(2 * math.pi), 1e-12)
    assert close(q.bachelier(F, 0.025, T, sn, df) - q.bachelier(F, 0.025, T, sn, df, call=False), df * (F - 0.025), 1e-12)


def test_implied_vol_round_trips():
    for s in (0.05, 0.2, 0.8):
        price = q.black_scholes(100, 90, 2, 0.03, s, call=False)
        assert close(q.implied_vol(price, lambda v: q.black_scholes(100, 90, 2, 0.03, v, call=False)), s, 1e-8)
    with pytest.raises(ValueError):           # below intrinsic: no vol fits
        q.implied_vol(0.0, lambda v: q.black_scholes(100, 50, 1, 0.0, v))


def test_a_25_delta_strike_has_25_delta():
    S, T, rd, rf, s = 150.0, 0.5, 0.01, 0.04, 0.11
    kc = q.fx_strike_from_delta(S, T, rd, rf, s, 0.25, call=True)
    kp = q.fx_strike_from_delta(S, T, rd, rf, s, 0.25, call=False)
    assert close(q.gk_greeks(S, kc, T, rd, rf, s)["delta"], 0.25, 1e-10)
    assert close(q.gk_greeks(S, kp, T, rd, rf, s, call=False)["delta"], -0.25, 1e-10)
    assert kp < S < kc
    smile = q.smile_from_rr_bf(0.10, -0.01, 0.003)
    assert close(smile["call25"], 0.098) and close(smile["put25"], 0.108)


def test_svi_at_its_centre():
    assert close(float(q.svi_total_variance(0.1, 0.02, 0.4, -0.3, 0.1, 0.2)), 0.02 + 0.4 * 0.2, 1e-12)


def test_vectorised_pricing_keeps_shapes():
    K = np.array([80.0, 100.0, 120.0])
    out = q.black_scholes(100, K, 1, 0.02, 0.25, call=np.array([False, True, True]))
    assert out.shape == (3,) and close(out[1], q.black_scholes(100, 100, 1, 0.02, 0.25))


# ---- curves -------------------------------------------------------------------------------------------------------------

def test_curves_reproduce_their_pillars_and_interpolate():
    t, z = [0.5, 1, 2, 5, 10, 30], [0.030, 0.031, 0.033, 0.036, 0.038, 0.039]
    for m in ("linear", "loglinear", "cubic"):
        c = q.ZeroCurve(t, z, m)
        assert np.allclose(c.zero(np.array(t)), z, atol=1e-14), m
        assert close(c.df(5), math.exp(-0.036 * 5), 1e-12)
        assert c.zero(50) == 0.039 and c.zero(0.1) == 0.030               # flat outside the pillars
    ll = q.ZeroCurve(t, z, "loglinear")
    f = ll.forward(2, 5, simple=False)                                     # log-linear: flat forwards between pillars
    assert close(f, (0.036 * 5 - 0.033 * 2) / 3, 1e-12) and close(ll.forward(3, 4, simple=False), f, 1e-12)
    if _CubicSpline is not None:
        grid = np.linspace(0.5, 30, 97)
        assert np.allclose(q.ZeroCurve(t, z, "cubic").zero(grid), _CubicSpline(t, z, bc_type="natural")(grid), atol=1e-13)


def test_a_curve_from_a_documents_points():
    pts = [{"tenor": "1M", "zeroRate": 3.66, "df": 0.997}, {"tenor": "1Y", "zeroRate": 3.54}, {"tenor": "10Y", "zeroRate": 3.80}]
    c = q.ZeroCurve.from_points(pts)
    assert close(c.zero(1), 0.0354) and len(c.times) == 3


def test_par_rate_annuity_and_swap_pv_on_a_flat_curve():
    c = q.ZeroCurve([1, 30], [0.04, 0.04])
    dfs = np.exp(-0.04 * np.arange(1, 6))
    assert close(q.annuity(c, 5, 1), dfs.sum(), 1e-12)
    par = q.par_swap_rate(c, 5, 1)
    assert close(par, (1 - dfs[-1]) / dfs.sum(), 1e-12) and close(par, math.exp(0.04) - 1, 1e-12)
    assert abs(q.swap_pv(c, par, 1e8, 5)) < 1e-6
    assert close(q.swap_pv(c, par + 0.0001, 1e8, 5), 1e4 * dfs.sum(), 1e-9)     # 1 bp off par: notional x annuity x 1bp
    assert close(q.par_swap_rate(c, 5, 1, projection=c), par, 1e-12)            # one curve: the same par rate


def test_bootstrap_recovers_a_flat_curve():
    times, dfs = q.bootstrap_par([1, 2, 3, 5, 7, 10], [0.05] * 6)
    assert np.allclose(dfs, 1.05 ** -times, atol=1e-12)


def test_scenario_and_key_rate_bumps():
    c = q.ZeroCurve([1, 2, 5, 10, 30], [0.03, 0.031, 0.034, 0.036, 0.037])
    assert np.allclose(c.shifted(10).zeros - c.zeros, 0.001)
    tw = c.shifted(twist_bp=50).zeros - c.zeros
    assert tw[0] < 0 < tw[-1]
    flat = q.ZeroCurve([1, 2, 5, 10, 25], [0.03] * 5, "linear")
    moved = flat.shifted(twist_bp=40)
    assert close((moved.zero(10) - moved.zero(2)) * 1e4, 40.0, 1e-9)        # the 2s10s spread moves by the twist
    bent = flat.shifted(fly_bp=20)
    assert close((2 * bent.zero(5) - bent.zero(2) - bent.zero(10)) * 1e4, -20.0, 0.01)
    k = c.key_rate_bumped(2, 1).zeros - c.zeros
    assert close(k[2], 1e-4) and np.count_nonzero(k) == 1


# ---- bonds --------------------------------------------------------------------------------------------------------------

def test_a_par_bond_and_the_textbook_duration():
    assert close(q.bond_price(0.06, 0.06, 10, 2), 100.0, 1e-10)
    r = q.bond_risk(0.08, 0.08, 5, 1)
    assert round(r["macaulay"], 4) == 4.3121 and round(r["modified"], 4) == 3.9927
    assert close(r["dirty"], 100.0, 1e-10)
    h = 1e-5
    num_conv = (q.bond_price(0.08 + h, 0.08, 5, 1, clean=False) + q.bond_price(0.08 - h, 0.08, 5, 1, clean=False) - 200) / (100 * h * h)
    assert close(r["convexity"], num_conv, 1e-4)
    assert close(r["dv01"], (q.bond_price(0.0799, 0.08, 5, 1) - q.bond_price(0.0801, 0.08, 5, 1)) / 2, 1e-4)


def test_yield_round_trips_and_accrued_is_counted():
    p = q.bond_price(0.045, 0.05, 7.3, 2)
    assert close(q.bond_yield(p, 0.05, 7.3, 2), 0.045, 1e-10)
    times, amounts, accrued = q.bond_cashflows(0.05, 7.3, 2)
    assert close(times[0], 0.3) and close(accrued, 2.5 * 0.4, 1e-9)
    times, _, accrued = q.bond_cashflows(0.04, 4.45, 1)                      # a coupon 0.45 years away is not dropped
    assert len(times) == 5 and close(times[0], 0.45) and close(accrued, 4 * 0.55, 1e-9)


def test_z_spread_recovers_the_spread_used_to_price():
    c = q.ZeroCurve([1, 3, 5, 10], [0.03, 0.032, 0.034, 0.036])
    times, amounts, _ = q.bond_cashflows(0.05, 6, 2)
    price = float(np.sum(amounts * np.exp(-(c.zero(times) + 0.0123) * times)))
    assert close(q.z_spread(price, times, amounts, c), 0.0123, 1e-9)


# ---- realised volatility ------------------------------------------------------------------------------------------------

def test_vol_estimators_on_a_simulated_path():
    rng = np.random.default_rng(7)
    sigma, n, steps = 0.25, 1500, 400
    paths = np.exp(np.cumsum(rng.normal(-0.5 * sigma ** 2 / (252 * steps), sigma / math.sqrt(252 * steps), (n, steps)), axis=1))
    level = np.concatenate([[1.0], np.cumprod(paths[:, -1])[:-1]])
    o, c = level, level * paths[:, -1]
    hi, lo = level * np.maximum(paths.max(axis=1), 1.0), level * np.minimum(paths.min(axis=1), 1.0)
    assert abs(q.close_to_close_vol(np.concatenate([[1.0], c])) - sigma) < 0.015
    assert abs(q.garman_klass_vol(o, hi, lo, c) - sigma) < 0.02
    assert abs(q.parkinson_vol(hi, lo) - sigma) < 0.025    # range estimators are biased low on a discrete path
    assert close(q.parkinson_vol([2.0], [1.0], 1), math.log(2) / math.sqrt(4 * math.log(2)), 1e-12)


# ---- risk ---------------------------------------------------------------------------------------------------------------

def test_var_and_es_reproduce_the_engine_on_the_sample():
    doc = json.loads((ROOT / "packs/market-risk/samples/var/VAR-RATES.json").read_text())
    assert round(q.historical_var(doc["scenarioPnl"], 0.99)) == doc["var99"]
    pnl = np.arange(1, 101) - 50.0
    assert q.historical_var(pnl, 0.95) == 45 and q.expected_shortfall(pnl, 0.95) == 47.0
    assert close(q.parametric_var(1e6, 0.99), 2326347.874, 1e-9)
    assert close(q.parametric_var(1e6, 0.99, horizon_days=10), 2326347.874 * math.sqrt(10), 1e-9)


def test_kupiec_against_the_chi_squared_distribution():
    k = q.kupiec_pof(0, 250, 0.99)
    assert close(k["lr"], -2 * 250 * math.log(0.99), 1e-12) and k["light"] == "green"
    k = q.kupiec_pof(8, 250, 0.99)
    p, x, n = 0.01, 8, 250
    lr = -2 * ((n - x) * math.log(1 - p) + x * math.log(p) - (n - x) * math.log(1 - x / n) - x * math.log(x / n))
    assert close(k["lr"], lr, 1e-12) and k["light"] == "amber"
    if _stats is not None:
        assert close(k["pvalue"], _stats.chi2.sf(lr, 1), 1e-10)
    assert q.kupiec_pof(12, 250)["light"] == "red"


def test_drawdown_sharpe_and_concentration():
    dd = q.max_drawdown([100, 120, 90, 95, 130, 70, 80])
    assert dd["drawdown"] == -60 and dd["peak"] == 4 and dd["trough"] == 5
    assert close(q.sharpe([1, -1, 1, -1, 2], 1), 0.4 / np.std([1, -1, 1, -1, 2], ddof=1))
    assert q.hhi([5, -5]) == 0.5 and q.hhi([1, 0, 0]) == 1.0


# ---- credit and FRTB ----------------------------------------------------------------------------------------------------

def test_hazard_survival_and_cva():
    lam = q.hazard_from_spread(120, 0.4)
    assert close(lam, 0.02)
    assert close(q.survival(3.0, [1, 5], [lam, lam]), math.exp(-0.06), 1e-12)
    assert close(q.survival(3.0, [1, 5], [0.01, 0.03]), math.exp(-(0.01 + 0.06)), 1e-12)
    tenors, term = np.array([0.5, 1, 3, 5]), np.array([0.011, 0.012, 0.017, 0.022])
    fwd = q.forward_hazards(tenors, term)
    assert np.allclose(q.survival(tenors, tenors, fwd), np.exp(-term * tenors), atol=1e-14)
    times = np.array([1.0, 2.0, 3.0])
    out = q.cva_from_profile(times, [1e6] * 3, [10], [lam], 0.4, rate=0.03)
    s = np.exp(-lam * np.array([0, 1, 2, 3]))
    want = 0.6 * 1e6 * np.sum(np.exp(-0.03 * times) * (s[:-1] - s[1:]))
    assert close(out["cva"], want, 1e-12)


def test_frtb_aggregation_formulas():
    assert close(q.frtb_bucket_charge([3, 4], 0.0), 5.0)
    assert close(q.frtb_bucket_charge([3, 4], 0.5), math.sqrt(9 + 16 + 2 * 0.5 * 12))
    assert close(q.frtb_bucket_charge([3, -4], 1.0), 1.0)
    assert close(q.frtb_across_buckets([5, 5], [5, -5], 0.5), math.sqrt(50 - 2 * 0.5 * 25))
    assert q.frtb_across_buckets([1, 1], [1, -1], 1.0) == 0.0
