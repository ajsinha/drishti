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

"""Market-data kinds for the risk pack taxonomy."""
from risk_model import Kind, Panel as P

G = "Market data"
POINTS = [("Tenor", "@.tenor", None, None, False), ("Date", "@.date", "date", None, False)]

KINDS = [
    Kind("ir-curve", "CRV", "CRV-", "Interest rate curve", G, "Discounting (OIS), projection (IBOR/term RFR), government or basis curve for a currency.", "curveId",
         [("Currency", "$.currency", None, None, False), ("Type", "$.curveType", None, None, False), ("Index", "$.index", None, None, False), ("10Y", "$.tenY", "pct4", None, True), ("2s10s", "$.slope2s10s", "bp1", "sign", False), ("Points", "size($.points)", None, None, False), ("As of", "$.asOf", "date", None, False)],
         [P("line", "curve", "Zero rates (%)", "$.points", x="tenor", y="zeroRate", fmt="price2", key="F2", code="CRV"),
          P("table", "points", "Pillars", "$.points", [("Tenor", "@.tenor", None, None, False), ("Instrument", "@.instrument", None, None, False), ("Quote", "@.quote", "pct4", None, False), ("Zero", "@.zeroRate", "price2", None, False), ("DF", "@.df", "df4", None, False)], key="F3"),
          P("line", "forwards", "1Y forward rates (%)", "$.forwards", x="tenor", y="rate", fmt="price2", area="right")],
         links={"calendar": ("calendar", "Calendar"), "fixingIndex": ("rate-fixing", "Fixing index")}, badge="fmt($.tenY, 'pct2') + ' 10Y'", live={"tenY": 0.0004}),
    Kind("repo-curve", "REPO", "REPO-", "Repo curve", G, "General-collateral or specials repo rates by term.", "curveId",
         [("Currency", "$.currency", None, None, False), ("Collateral", "$.collateral", None, None, False), ("O/N", "$.overnight", "pct4", None, True), ("As of", "$.asOf", "date", None, False)],
         [P("line", "curve", "Repo rates (%)", "$.points", x="tenor", y="rate", fmt="price2", key="F2"), P("table", "points", "Terms", "$.points", [("Term", "@.tenor", None, None, False), ("Rate (%)", "@.rate", "price2", None, False)])],
         badge="fmt($.overnight, 'pct2') + ' O/N'"),
    Kind("fx-spot", "FX", "FX-", "FX spot rate", G, "Spot rate for a currency pair with bid/ask.", "pair",
         [("Pair", "$.pair", None, None, False), ("Mid", "$.mid", "rate5", None, True), ("Bid", "$.bid", "rate5", None, False), ("Ask", "$.ask", "rate5", None, False), ("1D change", "$.change1d", "pct2", "sign", False), ("Value date", "$.spotDate", "date", None, False)],
         [P("line", "history", "Last 30 days", "$.history", x="date", y="mid", fmt="rate5", key="F2"), P("kv", "pair", "Conventions", "$.conventions", area="right")],
         links={"fxForwardCurve": ("fx-forward-curve", "Forward curve"), "fxVolSurface": ("fx-vol-surface", "Vol surface")}, badge="fmt($.mid, 'rate5')", live={"mid": 0.0006}),
    Kind("fx-forward-curve", "FXF", "FXF-", "FX forward curve", G, "Forward points by tenor for a pair.", "curveId",
         [("Pair", "$.pair", None, None, False), ("Spot", "$.spot", "rate5", None, False), ("3M points", "$.points3m", "pips1", "sign", True), ("1Y points", "$.points1y", "pips1", "sign", False)],
         [P("line", "points", "Forward points (pips)", "$.points", x="tenor", y="pips", fmt="pips1", key="F2"), P("table", "outright", "Outrights", "$.points", [("Tenor", "@.tenor", None, None, False), ("Points", "@.pips", "pips1", "sign", False), ("Outright", "@.outright", "rate5", None, False)])],
         links={"fxSpot": ("fx-spot", "Spot")}),
    Kind("fx-vol-surface", "FXV", "FXV-", "FX volatility surface", G, "Implied vols by expiry and delta (ATM, 25D RR/BF).", "surfaceId",
         [("Pair", "$.pair", None, None, False), ("1M ATM", "$.atm1m", "price2", None, True), ("1Y ATM", "$.atm1y", "price2", None, False), ("1Y 25D RR", "$.rr1y", "price2", "sign", False)],
         [P("surface", "smile", "Smile surface (vol %)", "$.grid", [("10D P", "@.p10", "price2", None, False), ("25D P", "@.p25", "price2", None, False), ("ATM", "@.atm", "price2", None, False), ("25D C", "@.c25", "price2", None, False), ("10D C", "@.c10", "price2", None, False)], y="expiry", key="F4", code="SURF"), P("line", "atm", "ATM vol term structure", "$.grid", x="expiry", y="atm", fmt="price2", key="F2"), P("table", "grid", "Surface (vol %)", "$.grid", [("Expiry", "@.expiry", None, None, False), ("10D P", "@.p10", "price2", None, False), ("25D P", "@.p25", "price2", None, False), ("ATM", "@.atm", "price2", None, False), ("25D C", "@.c25", "price2", None, False), ("10D C", "@.c10", "price2", None, False)], key="F3")],
         links={"fxSpot": ("fx-spot", "Spot")}, badge="fmt($.atm1y, 'price2') + ' vol'", live={"atm1m": 0.05}),
    Kind("ir-vol-cube", "IRV", "IRV-", "Swaption volatility cube", G, "Normal (bp) swaption vols by expiry × tenor.", "cubeId",
         [("Currency", "$.currency", None, None, False), ("Model", "$.model", None, None, False), ("1Y×10Y", "$.v1y10y", "price2", None, True), ("5Y×5Y", "$.v5y5y", "price2", None, False)],
         [P("surface", "surface", "Normal vols by expiry × tenor (bp)", "$.grid", [("1Y", "@.t1Y", "price2", None, False), ("2Y", "@.t2Y", "price2", None, False), ("5Y", "@.t5Y", "price2", None, False), ("10Y", "@.t10Y", "price2", None, False), ("30Y", "@.t30Y", "price2", None, False)], y="expiry", key="F4", code="SURF"), P("table", "grid", "ATM normal vols (bp)", "$.grid", [("Expiry", "@.expiry", None, None, False), ("1Y", "@.t1Y", "price2", None, False), ("2Y", "@.t2Y", "price2", None, False), ("5Y", "@.t5Y", "price2", None, False), ("10Y", "@.t10Y", "price2", None, False), ("30Y", "@.t30Y", "price2", None, False)], key="F2"),
          P("line", "row10y", "10Y tenor by expiry", "$.grid", x="expiry", y="t10Y", fmt="price2", area="right")], badge="fmt($.v1y10y, 'price2') + ' bp'"),
    Kind("cap-vol-surface", "CPV", "CPV-", "Cap/floor volatility surface", G, "Cap vols by maturity and strike.", "surfaceId",
         [("Currency", "$.currency", None, None, False), ("5Y ATM", "$.atm5y", "price2", None, True)],
         [P("surface", "surface", "Cap vols by maturity × strike (bp)", "$.grid", [("ATM", "@.atm", "price2", None, False), ("2%", "@.k2", "price2", None, False), ("3%", "@.k3", "price2", None, False), ("4%", "@.k4", "price2", None, False), ("5%", "@.k5", "price2", None, False)], y="maturity", key="F4", code="SURF"), P("table", "grid", "Cap vols (bp)", "$.grid", [("Maturity", "@.maturity", None, None, False), ("ATM", "@.atm", "price2", None, False), ("2%", "@.k2", "price2", None, False), ("3%", "@.k3", "price2", None, False), ("4%", "@.k4", "price2", None, False), ("5%", "@.k5", "price2", None, False)], key="F2"),
          P("line", "atm", "ATM by maturity", "$.grid", x="maturity", y="atm", fmt="price2", area="right")]),
    Kind("equity", "EQ", "EQ-", "Equity", G, "A listed stock: price, dividends, sector and identifiers.", "ticker",
         [("Name", "$.name", None, None, False), ("Price", "$.price", "price2", None, True), ("1D", "$.change1d", "pct2", "sign", False), ("Market cap", "$.marketCap", "compact", None, False), ("Sector", "$.sector", None, None, False), ("Exchange", "$.exchange", None, None, False), ("Currency", "$.currency", None, None, False), ("Beta", "$.beta", "price2", None, False)],
         [P("line", "history", "Price, last 30 days", "$.history", x="date", y="close", fmt="price2", key="F2"), P("kv", "ids", "Identifiers", "$.identifiers", area="right"),
          P("candlestick", "ohlc", "Daily bars (last 60 days)", "$.ohlc", x="date", fmt="price2", key="F3", code="OHLC", opts={"volume": "volume"})],
         links={"issuer": ("issuer", "Issuer"), "equityVolSurface": ("equity-vol-surface", "Vol surface"), "dividendCurve": ("dividend-curve", "Dividends"), "equityIndex": ("equity-index", "Index")},
         badge="fmt($.price, 'price2')", live={"price": 0.4}),
    Kind("equity-index", "EQX", "EQX-", "Equity index", G, "Index level with its largest constituents.", "indexId",
         [("Name", "$.name", None, None, False), ("Level", "$.level", "price2", None, True), ("1D", "$.change1d", "pct2", "sign", False), ("Constituents", "$.constituentCount", None, None, False), ("Currency", "$.currency", None, None, False)],
         [P("table", "constituents", "Top constituents", "$.constituents", [("Equity", "@.equity", None, None, False), ("Weight", "@.weight", "pct2", None, False)], key="F2"),
          P("line", "history", "Level, last 30 days", "$.history", x="date", y="close", fmt="price2", area="right")],
         links={"equityVolSurface": ("equity-vol-surface", "Vol surface"), "dividendCurve": ("dividend-curve", "Dividends")}, badge="fmt($.level, 'price2')", live={"level": 3.0}),
    Kind("dividend-curve", "DIV", "DIV-", "Dividend curve", G, "Expected dividends by year (implied from dividend futures and forwards).", "curveId",
         [("Underlier", "$.underlier", None, None, False), ("Yield", "$.dividendYield", "pct2", None, True)],
         [P("hbar", "divs", "Expected dividends", "$.points", label="year", value="amount", fmt="price2", key="F2")], links={"underlyingEquity": ("equity", "Underlier"), "underlyingIndex": ("equity-index", "Underlier")}),
    Kind("equity-vol-surface", "EQV", "EQV-", "Equity volatility surface", G, "Implied vols by expiry and moneyness.", "surfaceId",
         [("Underlier", "$.underlier", None, None, False), ("1M ATM", "$.atm1m", "price2", None, True), ("1Y ATM", "$.atm1y", "price2", None, False), ("1Y skew (90–110)", "$.skew1y", "price2", "sign", False)],
         [P("surface", "surface", "Implied vol by expiry × moneyness (%)", "$.grid", [("80%", "@.m80", "price2", None, False), ("90%", "@.m90", "price2", None, False), ("100%", "@.m100", "price2", None, False), ("110%", "@.m110", "price2", None, False), ("120%", "@.m120", "price2", None, False)], y="expiry", key="F4", code="SURF"), P("table", "grid", "Vol (%) by moneyness", "$.grid", [("Expiry", "@.expiry", None, None, False), ("80%", "@.m80", "price2", None, False), ("90%", "@.m90", "price2", None, False), ("100%", "@.m100", "price2", None, False), ("110%", "@.m110", "price2", None, False), ("120%", "@.m120", "price2", None, False)], key="F2"),
          P("line", "atm", "ATM term structure", "$.grid", x="expiry", y="m100", fmt="price2", area="right")], badge="fmt($.atm1m, 'price2') + ' vol'", live={"atm1m": 0.08}),
    Kind("credit-curve", "CDS", "CDS-", "Credit curve", G, "CDS par spreads by tenor, recovery and implied default probabilities.", "curveId",
         [("Reference entity", "$.issuerName", None, None, False), ("5Y spread", "$.spread5y", "bp1", None, True), ("Recovery", "$.recovery", "pct0", None, False), ("Seniority", "$.seniority", None, None, False), ("Doc clause", "$.docClause", None, None, False), ("1Y PD", "$.pd1y", "pct2", None, False)],
         [P("line", "spreads", "Par spreads (bp)", "$.points", x="tenor", y="spread", fmt="price2", key="F2"),
          P("table", "points", "Term structure", "$.points", [("Tenor", "@.tenor", None, None, False), ("Spread (bp)", "@.spread", "price2", None, False), ("Hazard", "@.hazard", "pct2", None, False), ("Survival", "@.survival", "df4", None, False)], key="F3")],
         links={"issuer": ("issuer", "Reference entity")}, badge="fmt($.spread5y, 'amount0') + ' bp 5Y'", live={"spread5y": 1.5}),
    Kind("inflation-index", "INF", "INF-", "Inflation index", G, "Published CPI fixings (US CPI-U NSA, HICPxT, UK RPI…).", "indexId",
         [("Name", "$.name", None, None, False), ("Latest", "$.latest", "price2", None, True), ("YoY", "$.yoy", "pct2", "sign", False), ("Publication lag", "$.lag", None, None, False)],
         [P("ladder", "fixings", "Monthly fixings", "$.fixings", [("Month", "@.month", None, None, False), ("Index", "@.value", "price2", None, False), ("YoY", "@.yoy", "pct2", "sign", False)], key="F2")],
         links={"inflationCurve": ("inflation-curve", "Inflation curve")}),
    Kind("inflation-curve", "INFC", "INFC-", "Inflation curve", G, "Zero-coupon breakevens by tenor, with monthly seasonality.", "curveId",
         [("Index", "$.index", None, None, False), ("5Y breakeven", "$.be5y", "pct2", None, True), ("10Y breakeven", "$.be10y", "pct2", None, False)],
         [P("line", "curve", "ZC breakevens (%)", "$.points", x="tenor", y="breakeven", fmt="price2", key="F2"), P("hbar", "seasonality", "Seasonality", "$.seasonality", label="month", value="factor", fmt="price2", area="right")],
         links={"inflationIndex": ("inflation-index", "Index")}, badge="fmt($.be10y, 'pct2') + ' 10Y'"),
    Kind("commodity", "CMD", "CMD-", "Commodity", G, "A traded commodity with its benchmark, unit and venue.", "commodityId",
         [("Name", "$.name", None, None, False), ("Front month", "$.front", "price2", None, True), ("Unit", "$.unit", None, None, False), ("Exchange", "$.exchange", None, None, False), ("Sector", "$.sector", None, None, False)],
         [P("kv", "spec", "Contract specification", "$.spec", key="F2"),
          P("candlestick", "ohlc", "Front month (daily bars, last 60 days)", "$.ohlc", x="date", fmt="price2", key="F3", code="OHLC", opts={"volume": "volume"})],
         links={"commodityCurve": ("commodity-curve", "Forward curve"), "commodityVolSurface": ("commodity-vol-surface", "Vol surface")}, badge="fmt($.front, 'price2')", live={"front": 0.25}),
    Kind("commodity-curve", "CMDC", "CMDC-", "Commodity forward curve", G, "Futures settlement prices by delivery month.", "curveId",
         [("Commodity", "$.commodity", None, None, False), ("Front", "$.front", "price2", None, True), ("Shape", "$.shape", None, None, False), ("M1–M12", "$.roll", "price2", "sign", False)],
         [P("line", "curve", "Forward curve", "$.points", x="month", y="price", fmt="price2", key="F2"), P("table", "points", "Settlements", "$.points", [("Month", "@.month", None, None, False), ("Price", "@.price", "price2", None, False), ("OI", "@.openInterest", "amount0", None, False)])],
         links={"commodityRef": ("commodity", "Commodity")}, badge="$.shape", live={"front": 0.25}),
    Kind("commodity-vol-surface", "CMDV", "CMDV-", "Commodity volatility surface", G, "Implied vols by delivery month and moneyness.", "surfaceId",
         [("Commodity", "$.commodity", None, None, False), ("Front ATM", "$.atmFront", "price2", None, True)],
         [P("surface", "surface", "Implied vol by contract × moneyness (%)", "$.grid", [("90%", "@.m90", "price2", None, False), ("100%", "@.m100", "price2", None, False), ("110%", "@.m110", "price2", None, False)], y="month", key="F4", code="SURF"), P("table", "grid", "Vol (%)", "$.grid", [("Month", "@.month", None, None, False), ("90%", "@.m90", "price2", None, False), ("ATM", "@.m100", "price2", None, False), ("110%", "@.m110", "price2", None, False)], key="F2"),
          P("line", "atm", "ATM by month", "$.grid", x="month", y="m100", fmt="price2", area="right")]),
    Kind("rate-fixing", "FIX", "FIX-", "Rate index fixings", G, "Published overnight and term rate fixings (SOFR, €STR, SONIA…).", "indexId",
         [("Index", "$.name", None, None, False), ("Latest", "$.latest", "pct4", None, True), ("Administrator", "$.administrator", None, None, False), ("Tenor", "$.tenor", None, None, False)],
         [P("ladder", "fixings", "Recent fixings", "$.fixings", [("Date", "@.date", "date", None, False), ("Rate", "@.rate", "pct4", None, False), ("Volume (bn)", "@.volumeBn", "amount0", None, False)], key="F2"),
          P("line", "chart", "Last 30 fixings", "$.fixings", x="date", y="ratePct", fmt="price2", area="right")], badge="fmt($.latest, 'pct2')"),
    Kind("bond", "BND", "BND-", "Bond (security master)", G, "A listed bond: terms, price, yield, spreads and analytics.", "isin",
         [("Issuer", "$.issuerName", None, None, False), ("Coupon", "$.coupon", "pct4", None, False), ("Maturity", "$.maturity", "date", None, False), ("Price", "$.price", "price2", None, True), ("Yield", "$.yield", "pct4", None, False), ("Z-spread", "$.zSpread", "bp1", None, False), ("Duration", "$.modDuration", "price2", None, False), ("Rating", "$.rating", None, None, False)],
         [P("kv", "terms", "Terms", "$.terms", key="F2"), P("table", "coupons", "Coupons", "$.coupons", [("Date", "@.date", "date", None, False), ("Coupon", "@.amount", "amount2", None, False)], key="F3"),
          P("line", "history", "Price, last 30 days", "$.history", x="date", y="price", fmt="price2", area="right")],
         links={"issuer": ("issuer", "Issuer"), "benchmarkCurve": ("ir-curve", "Benchmark curve")}, badge="fmt($.price, 'price2')", live={"price": 0.05}),
    Kind("correlation-matrix", "CORR", "CORR-", "Correlation matrix", G, "Pairwise correlations used by basket, quanto and spread products.", "matrixId",
         [("Name", "$.name", None, None, False), ("Size", "size($.factors)", None, None, False), ("Average", "$.average", "price2", None, True)],
         [P("table", "pairs", "Pairs", "$.pairs", [("Factor 1", "@.a", None, None, False), ("Factor 2", "@.b", None, None, False), ("ρ", "@.rho", "price2", "sign", False)], key="F2")]),
    Kind("calendar", "CAL", "CAL-", "Holiday calendar", G, "Business-day calendar (USNY, TARGET, GBLO, JPTO…).", "calendarId",
         [("Name", "$.name", None, None, False), ("Holidays (2026)", "size($.holidays)", None, None, True)],
         [P("table", "holidays", "Holidays", "$.holidays", [("Date", "@.date", "date", None, False), ("Holiday", "@.name", None, None, False)], key="F2")]),
]
