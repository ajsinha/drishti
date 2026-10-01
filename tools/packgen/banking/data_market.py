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

"""Market-data documents for the market-data pack: curves, surfaces, prices, fixings and bonds, with realistic
shapes (upward or inverted curves, volatility smiles, backwardation) and 30 business days of history."""
from __future__ import annotations

import hashlib
import math
import random
import sys
from datetime import date, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from samplegen import ids  # noqa: E402
from samplegen.curves import Curve  # noqa: E402
from samplegen.dates import Calendar, add_tenor, iso, tenor_years  # noqa: E402

import data_names as N  # noqa: E402

CURVE_T = ["1M", "3M", "6M", "1Y", "2Y", "3Y", "5Y", "7Y", "10Y", "15Y", "20Y", "30Y"]
VOL_EXPIRIES = ["1M", "3M", "6M", "1Y", "2Y", "5Y"]
USNY = Calendar.of("USNY")


def rng(key: str) -> random.Random:
    return random.Random(int(hashlib.sha256(key.encode()).hexdigest()[:12], 16))


def business_days(n: int, cal: Calendar = USNY) -> list[date]:
    out, d = [], N.AS_OF
    while len(out) < n:
        if cal.is_business_day(d):
            out.append(d)
        d -= timedelta(days=1)
    return sorted(out)


def history(key: str, last: float, vol: float, n: int = 30, dp: int = 4, field: str = "close") -> list[dict]:
    """A random walk that ends at `last` (the walk is generated backwards)."""
    r, v, out = rng(key), last, []
    for d in reversed(business_days(n)):
        out.append({"date": iso(d), field: round(v, dp)})
        v *= 1 + r.gauss(0, vol)
    return list(reversed(out))


def ohlc(key: str, last: float, vol: float, volume: float, n: int = 60) -> list[dict]:
    """n business days of daily bars that close on the same walk as history(key, ...) (so the last 30 closes are the
    line chart's), with an open near the previous close, a high and a low around both, and a volume."""
    closes = history(key, last, vol, n=n, dp=6)
    r, out, prev = rng(key + "ohlc"), [], None
    for row in closes:
        c = row["close"]
        o = c * (1 + r.gauss(0, vol * 0.6)) if prev is None else prev * (1 + r.gauss(0, vol * 0.25))
        hi, lo = max(o, c) * (1 + abs(r.gauss(0, vol * 0.5))), min(o, c) * (1 - abs(r.gauss(0, vol * 0.5)))
        out.append({"date": row["date"], "open": round(o, 2), "high": round(hi, 2), "low": round(lo, 2), "close": round(c, 2),
                    "volume": int(volume * r.uniform(0.5, 1.8))})
        prev = c
    return out


# ---- ids a trade's market-data selectors resolve to ------------------------------------------------------------
RFR_FIX = {c: f"FIX-{v[3]}" for c, v in N.CCY.items()}


def md_id(kind: str, selector: str) -> str:
    kind_part, _, val = selector.partition(":")
    if kind == "ir-curve":
        return f"CRV-{val}-{ {'ois': 'OIS', 'proj': 'PROJ', 'govt': 'GOVT', 'basis': 'BASIS'}[kind_part] }"
    if kind == "ir-vol-cube":
        return f"IRV-{val}"
    if kind == "cap-vol-surface":
        return f"CPV-{val}"
    if kind == "rate-fixing":
        return RFR_FIX[val]
    prefix = {"repo-curve": "REPO-", "fx-spot": "FX-", "fx-forward-curve": "FXF-", "fx-vol-surface": "FXV-", "credit-curve": "CDS-",
              "equity": "EQ-", "equity-vol-surface": "EQV-", "dividend-curve": "DIV-", "equity-index": "EQX-", "inflation-curve": "INFC-",
              "inflation-index": "INF-", "commodity-curve": "CMDC-", "commodity-vol-surface": "CMDV-", "bond": "BND-",
              "correlation-matrix": ""}[kind]
    return prefix + selector


def zero_curve(ccy: str, kind: str) -> list[float]:
    """Zero rates (%) by CURVE_T: a Nelson-Siegel-like shape through the currency's 1Y and 10Y levels."""
    one, ten = N.CCY[ccy][0], N.CCY[ccy][1]
    shift = {"OIS": 0.0, "PROJ": 0.06, "GOVT": -0.12, "BASIS": 0.02}[kind]
    out = []
    for t in CURVE_T:
        y = tenor_years(t)
        hump = 0.18 * math.exp(-((y - 2.5) ** 2) / 4) * (1 if ten < one else -1)
        base = one + (ten - one) * (1 - math.exp(-y / 4)) / (1 - math.exp(-2.5)) - (one - ten) * 0.02 * (y < 1)
        out.append(round(base + hump + shift, 4))
    return out


def ir_curve(ccy: str, kind: str) -> dict:
    cid = f"CRV-{ccy}-{kind}"
    zs = zero_curve(ccy, kind)
    c = Curve(cid, ccy, N.AS_OF, CURVE_T, [z / 100 for z in zs])
    pillars = c.pillars()
    for p, z in zip(pillars, zs):
        p["zeroRate"] = z
        p["instrument"] = {"OIS": f"{N.CCY[ccy][3]} OIS", "PROJ": "Term RFR swap", "GOVT": f"{N.CCY[ccy][4]} benchmark",
                           "BASIS": "Basis swap"}[kind] if tenor_years(p["tenor"]) >= 1 else "Deposit / future"
    fwds = [{"tenor": t, "rate": round(c.forward(add_tenor(N.AS_OF, t), add_tenor(add_tenor(N.AS_OF, t), "1Y")) * 100, 4)}
            for t in ["1Y", "2Y", "3Y", "5Y", "7Y", "10Y", "15Y", "20Y"]]
    return {"curveId": cid, "currency": ccy, "curveType": {"OIS": "Discount (OIS)", "PROJ": "Projection", "GOVT": "Government",
                                                           "BASIS": "Cross-currency basis"}[kind],
            "index": N.CCY[ccy][3], "tenY": zs[CURVE_T.index("10Y")] / 100, "slope2s10s": round((zs[8] - zs[4]) * 100, 1),
            "asOf": iso(N.AS_OF), "points": pillars, "forwards": fwds, "interpolation": "Log-linear on discount factors",
            "dayCount": "ACT/365F", "calendar": "CAL-" + N.CCY[ccy][2], "fixingIndex": RFR_FIX[ccy],
            "builtAt": iso(N.AS_OF) + "T18:02:41Z", "sources": ["Broker composite", "Exchange settlements"]}


def repo_curve(ccy: str) -> dict:
    on = N.CCY[ccy][0] / 100 - 0.0008
    pts = [{"tenor": t, "rate": round((on + 0.0004 * tenor_years(t) ** 0.5) * 100, 4)} for t in ["ON", "1W", "1M", "3M", "6M", "1Y"]]
    return {"curveId": f"REPO-{ccy}", "currency": ccy, "collateral": "General collateral (government)", "overnight": on, "asOf": iso(N.AS_OF),
            "points": pts}


def fx_spot(pair: str) -> dict:
    mid, vol = N.FX[pair], N.FX_VOL[pair] / 100 / math.sqrt(252)
    spread = mid * 0.00004
    hist = history("fx" + pair, mid, vol, dp=5, field="mid")
    return {"pair": f"FX-{pair}", "pairName": pair[:3] + "/" + pair[3:], "mid": mid, "bid": round(mid - spread, 5), "ask": round(mid + spread, 5),
            "change1d": round(hist[-1]["mid"] / hist[-2]["mid"] - 1, 5), "spotDate": iso(USNY.add_business_days(N.AS_OF, 2)),
            "history": hist, "conventions": {"base": pair[:3], "quote": pair[3:], "pipFactor": 100 if "JPY" in pair else 10000,
                                             "spotLag": "T+2", "calendar": "GBLO+USNY", "ndf": pair in ("USDINR", "USDBRL", "USDKRW")},
            "fxForwardCurve": f"FXF-{pair}", "fxVolSurface": f"FXV-{pair}"}


def fx_forward_curve(pair: str) -> dict:
    base, quote = pair[:3], pair[3:]
    rb = N.CCY.get(base, (4.0, 4.0))[0] / 100 if base in N.CCY else 0.04
    rq = N.CCY.get(quote, (6.5, 6.5))[0] / 100 if quote in N.CCY else {"CNH": 0.017, "INR": 0.058, "BRL": 0.148, "KRW": 0.026}[quote]
    spot, pip = N.FX[pair], (100 if "JPY" in pair else 10000)
    pts = []
    for t in ["ON", "1W", "1M", "2M", "3M", "6M", "9M", "1Y", "2Y"]:
        y = tenor_years(t)
        fwd = spot * math.exp((rq - rb) * y)
        pts.append({"tenor": t, "pips": round((fwd - spot) * pip, 1), "outright": round(fwd, 5)})
    p = {x["tenor"]: x["pips"] for x in pts}
    return {"curveId": f"FXF-{pair}", "pair": pair, "spot": spot, "points3m": p["3M"], "points1y": p["1Y"], "points": pts, "fxSpot": f"FX-{pair}"}


def fx_vol_surface(pair: str) -> dict:
    atm = N.FX_VOL[pair]
    grid = []
    for e in VOL_EXPIRIES:
        y = tenor_years(e)
        a = round(atm * (0.92 + 0.08 * min(y, 2)), 2)
        rr = round(-0.35 * atm / 10 * (1 + y / 3), 2)
        bf = round(0.25 + 0.05 * y, 2)
        grid.append({"expiry": e, "p10": round(a + 2.2 * bf - 0.9 * rr, 2), "p25": round(a + bf - rr / 2, 2), "atm": a,
                     "c25": round(a + bf + rr / 2, 2), "c10": round(a + 2.2 * bf + 0.9 * rr, 2), "rr25": rr, "bf25": bf})
    g = {x["expiry"]: x for x in grid}
    return {"surfaceId": f"FXV-{pair}", "pair": pair, "atm1m": g["1M"]["atm"], "atm1y": g["1Y"]["atm"], "rr1y": g["1Y"]["rr25"],
            "grid": grid, "model": "Vanna-volga smile, delta-quoted", "fxSpot": f"FX-{pair}"}


def ir_vol_cube(ccy: str) -> dict:
    lvl = 55 + N.CCY[ccy][1] * 18
    grid = [{"expiry": e, **{f"t{t}": round(lvl * (1.12 - 0.05 * math.log1p(tenor_years(e))) * (1.04 - 0.03 * math.log1p(tenor_years(t))), 2)
                             for t in ["1Y", "2Y", "5Y", "10Y", "30Y"]}} for e in ["1M", "3M", "6M", "1Y", "2Y", "5Y", "10Y"]]
    g = {x["expiry"]: x for x in grid}
    return {"cubeId": f"IRV-{ccy}", "currency": ccy, "model": "Normal (Bachelier), SABR smile", "v1y10y": g["1Y"]["t10Y"],
            "v5y5y": g["5Y"]["t5Y"], "grid": grid}


def cap_vol_surface(ccy: str) -> dict:
    lvl = 60 + N.CCY[ccy][1] * 15
    grid = [{"maturity": m, "atm": round(lvl * (1.1 - 0.04 * tenor_years(m) ** 0.5), 2),
             **{f"k{k}": round(lvl * (1.1 - 0.04 * tenor_years(m) ** 0.5) * (1 + 0.035 * abs(k - N.CCY[ccy][1])), 2) for k in (2, 3, 4, 5)}}
            for m in ["1Y", "2Y", "3Y", "5Y", "7Y", "10Y"]]
    return {"surfaceId": f"CPV-{ccy}", "currency": ccy, "atm5y": grid[3]["atm"], "grid": grid, "model": "Normal, stripped caplets"}


def equity(t: str) -> dict:
    name, iss, exch, ccy, px, sector, beta, idx = N.EQUITIES[t]
    hist = history("eq" + t, px, 0.017, dp=2)
    shares = rng("sh" + t).randint(300, 4000) * 1e6
    out = {"ticker": f"EQ-{t}", "name": name, "price": px, "change1d": round(hist[-1]["close"] / hist[-2]["close"] - 1, 5),
           "marketCap": round(px * shares * (N.FX.get(ccy + "USD", 1 / N.FX.get("USD" + ccy, 1)) if ccy != "USD" else 1)),
           "sector": sector, "exchange": exch, "currency": ccy, "beta": beta, "history": hist, "ohlc": ohlc("eq" + t, px, 0.017, shares * 0.004),
           "identifiers": {"isin": ids.isin({"USD": "US", "EUR": "FR", "GBP": "GB", "JPY": "JP", "CHF": "CH", "CAD": "CA"}[ccy], t + "0001"),
                           "bloomberg": f"{t} {exch[:2].upper()} Equity", "ric": f"{t}.{exch[:1]}", "figi": "BBG" + hashlib.sha1(t.encode()).hexdigest()[:9].upper()},
           "issuer": f"ISS-{iss}", "equityVolSurface": f"EQV-{t}", "dividendCurve": f"DIV-{t}"}
    if idx:
        out["equityIndex"] = f"EQX-{idx}"
    return out


def equity_index(i: str) -> dict:
    name, ccy, lvl, n = N.INDICES[i]
    hist = history("ix" + i, lvl, 0.009, dp=2)
    members = [t for t, v in N.EQUITIES.items() if v[7] == i]
    weights = [round(0.02 + 0.015 * k, 4) for k in range(len(members))]
    return {"indexId": f"EQX-{i}", "name": name, "level": lvl, "change1d": round(hist[-1]["close"] / hist[-2]["close"] - 1, 5),
            "constituentCount": n, "currency": ccy, "constituents": [{"equity": f"EQ-{t}", "weight": w} for t, w in zip(members, weights)],
            "history": hist, "equityVolSurface": f"EQV-{i}", "dividendCurve": f"DIV-{i}"}


def dividend_curve(u: str) -> dict:
    is_idx = u in N.INDICES
    px = N.INDICES[u][2] if is_idx else N.EQUITIES[u][4]
    y = 0.018 if is_idx else rng("dy" + u).uniform(0.0, 0.045)
    pts = [{"year": str(2026 + k), "amount": round(px * y * (1 + 0.04 * k), 2)} for k in range(6)]
    out = {"curveId": f"DIV-{u}", "underlier": ("EQX-" if is_idx else "EQ-") + u, "dividendYield": round(y, 4), "points": pts}
    out["underlyingIndex" if is_idx else "underlyingEquity"] = out["underlier"]
    return out


def equity_vol_surface(u: str) -> dict:
    atm = 16.5 if u in N.INDICES else 18 + N.EQUITIES[u][6] * 12
    grid = [{"expiry": e, **{f"m{m}": round(atm * (1 + 0.25 * max(0, (100 - m) / 100) * 3 - 0.08 * max(0, (m - 100) / 100) * 3)
                                            * (1.06 - 0.04 * math.log1p(tenor_years(e))), 2) for m in (80, 90, 100, 110, 120)}}
            for e in VOL_EXPIRIES]
    g = {x["expiry"]: x for x in grid}
    return {"surfaceId": f"EQV-{u}", "underlier": ("EQX-" if u in N.INDICES else "EQ-") + u, "atm1m": g["1M"]["m100"], "atm1y": g["1Y"]["m100"],
            "skew1y": round(g["1Y"]["m90"] - g["1Y"]["m110"], 2), "grid": grid, "model": "SVI, moneyness-quoted"}


def credit_curve(i: str) -> dict:
    name, sector, country, rating, s5, sov = N.ISSUERS[i]
    rec = 0.40 if not sov else 0.25
    pts = []
    for t in ["6M", "1Y", "2Y", "3Y", "5Y", "7Y", "10Y"]:
        y = tenor_years(t)
        s = round(s5 * (0.45 + 0.55 * min(y, 5) / 5 + 0.03 * max(0, y - 5)), 1)
        h = s / 1e4 / (1 - rec)
        pts.append({"tenor": t, "spread": s, "hazard": round(h, 6), "survival": round(math.exp(-h * y), 6)})
    return {"curveId": f"CDS-{i}", "issuerName": name, "spread5y": s5, "recovery": rec, "seniority": "Senior unsecured",
            "docClause": "CR14" if country in ("US", "CA") else "MM14", "pd1y": round(1 - pts[1]["survival"], 6), "points": pts,
            "issuer": f"ISS-{i}", "currency": "USD" if country in ("US", "CA", "BR") else "EUR"}


def inflation_index(k: str) -> dict:
    name, ccy, yoy, lag = N.INFLATION[k]
    base = {"USCPI": 322.5, "HICPX": 128.4, "UKRPI": 401.2, "JPCPI": 110.3}[k]
    fixings, v = [], base
    for m in range(12):
        month = date(2026, 8, 1) - timedelta(days=30 * m)
        fixings.append({"month": month.strftime("%Y-%m"), "value": round(v, 3), "yoy": round(yoy / 100 + 0.001 * math.sin(m), 4)})
        v /= 1 + yoy / 100 / 12
    return {"indexId": f"INF-{k}", "name": name, "latest": fixings[0]["value"], "yoy": fixings[0]["yoy"], "lag": lag, "currency": ccy,
            "fixings": fixings, "inflationCurve": f"INFC-{k}"}


def inflation_curve(k: str) -> dict:
    yoy = N.INFLATION[k][2] / 100
    pts = [{"tenor": t, "breakeven": round((yoy * 0.9 + 0.0015 * math.log1p(tenor_years(t))) * 100, 4)} for t in ["1Y", "2Y", "5Y", "10Y", "20Y", "30Y"]]
    seas = [{"month": m, "factor": round(0.0015 * math.sin((i + 1) * math.pi / 6), 5)} for i, m in
            enumerate(["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"])]
    p = {x["tenor"]: x["breakeven"] for x in pts}
    return {"curveId": f"INFC-{k}", "index": f"INF-{k}", "be5y": p["5Y"] / 100, "be10y": p["10Y"] / 100, "points": pts,
            "seasonality": seas, "inflationIndex": f"INF-{k}"}


def commodity(c: str) -> dict:
    name, unit, exch, sector, px, vol = N.COMMODITIES[c]
    return {"commodityId": f"CMD-{c}", "name": name, "front": px, "unit": unit, "exchange": exch, "sector": sector,
            "ohlc": ohlc("cmd" + c, px, vol / 100 / 16, 250000),
            "spec": {"contract": c, "unit": unit, "exchange": exch, "settlement": "Physical" if sector != "Metals" else "Physical (vault)",
                     "listing": "Monthly", "tickSize": 0.01, "tradingHours": "Sun-Fri 18:00-17:00 ET"},
            "commodityCurve": f"CMDC-{c}", "commodityVolSurface": f"CMDV-{c}"}


MONTHS = "FGHJKMNQUVXZ"


def commodity_curve(c: str) -> dict:
    name, unit, exch, sector, px, vol = N.COMMODITIES[c]
    slope = {"Energy": -0.004, "Metals": 0.003, "Agriculture": 0.002}[sector]
    pts = []
    for k in range(12):
        m = (10 + k) % 12
        yr = 6 + (10 + k) // 12
        pts.append({"month": f"{MONTHS[m]}{yr}", "price": round(px * (1 + slope * k + 0.01 * math.sin(k / 2) * (sector == "Agriculture")), 3),
                    "openInterest": max(1000, int(200000 * math.exp(-k / 3)))})
    return {"curveId": f"CMDC-{c}", "commodity": name, "front": pts[0]["price"], "shape": "backwardation" if slope < 0 else "contango",
            "roll": round((pts[1]["price"] / pts[0]["price"] - 1) * 100, 2), "points": pts, "commodityRef": f"CMD-{c}", "unit": unit}


def commodity_vol_surface(c: str) -> dict:
    vol = N.COMMODITIES[c][5]
    grid = [{"month": f"{MONTHS[(10 + k) % 12]}{6 + (10 + k) // 12}", "m90": round(vol * (1.08 - 0.02 * k) + 1.5, 2),
             "m100": round(vol * (1.08 - 0.02 * k), 2), "m110": round(vol * (1.08 - 0.02 * k) + 0.8, 2)} for k in range(8)]
    return {"surfaceId": f"CMDV-{c}", "commodity": N.COMMODITIES[c][0], "atmFront": grid[0]["m100"], "grid": grid}


def rate_fixing(ccy: str) -> dict:
    rfr = N.CCY[ccy][3]
    lvl = N.CCY[ccy][0] + 0.2
    fx = []
    for d in reversed(business_days(20, Calendar.of(N.CCY[ccy][2]))):
        r = round(lvl + 0.01 * math.sin(d.toordinal() / 5), 4)
        fx.append({"date": iso(d), "rate": round(r / 100, 6), "ratePct": r, "volumeBn": round(rng(rfr + iso(d)).uniform(50, 2400), 1)})
    return {"indexId": f"FIX-{rfr}", "name": {"SOFR": "Secured Overnight Financing Rate", "ESTR": "Euro short-term rate", "SONIA": "Sterling Overnight Index Average",
                                              "TONA": "Tokyo Overnight Average Rate", "SARON": "Swiss Average Rate Overnight",
                                              "AONIA": "AUD Overnight Index Average", "CORRA": "Canadian Overnight Repo Rate Average"}[rfr],
            "latest": fx[0]["rate"], "administrator": {"SOFR": "FRBNY", "ESTR": "ECB", "SONIA": "Bank of England", "TONA": "Bank of Japan",
                                                       "SARON": "SIX", "AONIA": "RBA", "CORRA": "Bank of Canada"}[rfr],
            "tenor": "Overnight", "currency": ccy, "fixings": fx}


def bonds() -> dict[str, dict]:
    out = {}
    for i, (name, sector, country, rating, s5, sov) in N.ISSUERS.items():
        ccy = {"US": "USD", "DE": "EUR", "GB": "GBP", "JP": "JPY", "CH": "CHF", "CA": "CAD", "GR": "EUR", "BR": "USD", "FR": "EUR"}[country]
        for k, (yrs, cpn) in enumerate([(5, 0.0375), (10, 0.0425)]):
            r = rng(i + str(k))
            code = ids.isin(country if country != "GR" else "XS", f"{i[:4]}{k}{r.randint(1000, 9999)}")
            mat = date(2026 + yrs, r.randint(1, 12), 15)
            y = N.CCY.get(ccy, (4, 4))[0] / 100 + (s5 / 1e4) + 0.002 * k
            coupon = round(max(0.0025, cpn + (s5 / 1e4) * 0.6 - (0.02 if ccy in ("JPY", "CHF") else 0)), 4)
            dur = yrs * 0.87
            price = round(100 + (coupon - y) * dur * 100, 3)
            bid = f"BND-{code}"
            out[bid] = {"isin": bid, "isinCode": code, "issuerName": name, "coupon": coupon, "maturity": iso(mat), "price": price,
                        "yield": round(y, 5), "zSpread": round(s5 * 0.95, 1), "modDuration": round(dur, 2), "rating": rating,
                        "terms": {"currency": ccy, "couponFrequency": "Semi-annual" if ccy == "USD" else "Annual", "dayCount": "ACT/ACT",
                                  "issueDate": iso(date(2026 - (10 - yrs), mat.month, 15)), "amountIssued": r.randint(5, 60) * 100_000_000,
                                  "seniority": "Sovereign" if sov else "Senior unsecured", "callable": False, "cusip": ids.cusip(code[2:10]) if country == "US" else None},
                        "coupons": [{"date": iso(date(2026 + y2, mat.month, 15)), "amount": round(coupon * 100 / (2 if ccy == "USD" else 1), 4)} for y2 in range(1, min(yrs, 6) + 1)],
                        "history": history("bnd" + code, price, 0.002, dp=3, field="price"), "issuer": f"ISS-{i}",
                        "benchmarkCurve": f"CRV-{ccy}-GOVT"}
    return out


def correlation_matrix(cid: str) -> dict:
    name, factors = N.CORRELATIONS[cid]
    pairs = []
    for a in range(len(factors)):
        for b in range(a + 1, len(factors)):
            pairs.append({"a": factors[a], "b": factors[b], "rho": round(rng(cid + str(a) + str(b)).uniform(0.15, 0.78), 3)})
    return {"matrixId": cid, "name": name, "factors": factors, "average": round(sum(p["rho"] for p in pairs) / len(pairs), 3), "pairs": pairs,
            "window": "2 years, daily log returns", "estimator": "Ledoit-Wolf shrinkage"}


def build() -> dict[str, dict[str, dict]]:
    """Every market-data document, by kind and id."""
    docs: dict[str, dict[str, dict]] = {}

    def put(kind, doc, idf):
        docs.setdefault(kind, {})[doc[idf]] = doc

    for ccy in N.CCY:
        for k in ("OIS", "PROJ", "GOVT", "BASIS"):
            put("ir-curve", ir_curve(ccy, k), "curveId")
        put("repo-curve", repo_curve(ccy), "curveId")
        put("ir-vol-cube", ir_vol_cube(ccy), "cubeId")
        put("cap-vol-surface", cap_vol_surface(ccy), "surfaceId")
        put("rate-fixing", rate_fixing(ccy), "indexId")
    for p in N.FX:
        put("fx-spot", fx_spot(p), "pair")
        put("fx-forward-curve", fx_forward_curve(p), "curveId")
        put("fx-vol-surface", fx_vol_surface(p), "surfaceId")
    for t in N.EQUITIES:
        put("equity", equity(t), "ticker")
        put("dividend-curve", dividend_curve(t), "curveId")
        put("equity-vol-surface", equity_vol_surface(t), "surfaceId")
    for i in N.INDICES:
        put("equity-index", equity_index(i), "indexId")
        put("dividend-curve", dividend_curve(i), "curveId")
        put("equity-vol-surface", equity_vol_surface(i), "surfaceId")
    for i in N.ISSUERS:
        put("credit-curve", credit_curve(i), "curveId")
    for k in N.INFLATION:
        put("inflation-index", inflation_index(k), "indexId")
        put("inflation-curve", inflation_curve(k), "curveId")
    for c in N.COMMODITIES:
        put("commodity", commodity(c), "commodityId")
        put("commodity-curve", commodity_curve(c), "curveId")
        put("commodity-vol-surface", commodity_vol_surface(c), "surfaceId")
    for b in bonds().values():
        put("bond", b, "isin")
    for cid in N.CORRELATIONS:
        put("correlation-matrix", correlation_matrix(cid), "matrixId")
    return docs


if __name__ == "__main__":
    d = build()
    print({k: len(v) for k, v in d.items()}, sum(len(v) for v in d.values()))
