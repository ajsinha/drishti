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

"""The demo's factor dictionary (kind ``risk-factor``, docs/guides/FRTB_SA_GUIDE.md section 3): every risk factor the
demo sensitivities use, named by the factor naming standard, with its FRTB-SA and ISDA SIMM mapping and the names three source systems
give it.

The naming standard (decided): one text name per factor, slash-separated:

    IR/CURVE/<ccy>/<curve>/<tenor>          a point of an interest-rate curve (GIRR delta)
    IR/CURVE/<ccy>                          the currency's curves together (GIRR curvature, MAR21.8)
    IR/BASIS/<ccy>                          the cross-currency basis against USD (GIRR delta)
    INF/<index>                             the inflation factor of the index's currency (GIRR delta, no tenor in FRTB)
    INF/<index>/<tenor>                     a point of an inflation curve (GIRR inflation)
    CR/SPREAD/<issuer>/<BOND|CDS>/<tenor>   a point of an issuer's spread curve (CSR delta); CR/SPREAD/<issuer>: the issuer (curvature)
    FX/SPOT/<ccy>                           the currency against the reporting currency (FX delta and curvature)
    EQ/SPOT/<underlying>, EQ/REPO/<name>    an equity price or repo rate (equity delta and curvature)
    CM/PRICE/<underlying>/<location>/<tenor>  a commodity forward (commodity delta)
    CM/PRICE/<underlying>                   the commodity, every location and tenor (commodity curvature, MAR21.13)
    VOL/<class>/<underlying>/<expiry>[/<underlying tenor>]   an implied volatility (vega)

Everything here is synthetic but shaped on the regulatory grids: the buckets, tenors and liquidity horizons follow Basel MAR21 and MAR33,
the SIMM buckets and labels follow the ISDA SIMM methodology's structure. The parameters themselves (risk weights, correlations) are not
here: they are configuration (docs/architecture/capital-parameters/). Deterministic: no randomness at all.
"""
from __future__ import annotations

import functools

#: FRTB GIRR delta vertices (MAR21.8), in years
GIRR_TENORS = {"3M": 0.25, "6M": 0.5, "1Y": 1.0, "2Y": 2.0, "3Y": 3.0, "5Y": 5.0, "10Y": 10.0, "15Y": 15.0, "20Y": 20.0, "30Y": 30.0}
#: FRTB CSR delta vertices (MAR21.8)
CSR_TENORS = {"6M": 0.5, "1Y": 1.0, "3Y": 3.0, "5Y": 5.0, "10Y": 10.0}
#: commodity forward points the demo uses (a subset of MAR21.8's commodity vertices)
CM_TENORS = {"3M": 0.25, "6M": 0.5, "1Y": 1.0, "2Y": 2.0, "3Y": 3.0, "5Y": 5.0}
#: inflation curve points (the bank's grid; FRTB GIRR inflation is one factor per currency, SIMM one per currency)
INF_TENORS = {"2Y": 2.0, "5Y": 5.0, "10Y": 10.0, "30Y": 30.0}
#: vega option-maturity vertices (MAR21.8)
VEGA_EXPIRIES = {"6M": 0.5, "1Y": 1.0, "3Y": 3.0, "5Y": 5.0, "10Y": 10.0}
#: CSR tenor -> the SIMM credit tenor label it is reported under (SIMM credit tenors 1y, 2y, 3y, 5y, 10y)
SIMM_CREDIT_TENOR = {"6M": "1y", "1Y": "1y", "3Y": "3y", "5Y": "5y", "10Y": "10y"}

#: currency -> (overnight curve, SIMM IR bucket: 1 regular, 2 low and 3 high volatility; FRTB liquidity horizon of its curve)
CURVES = {"USD": ("SOFR", "1", 10), "EUR": ("ESTR", "1", 10), "GBP": ("SONIA", "1", 10), "JPY": ("TONA", "2", 10),
          "AUD": ("AONIA", "1", 10), "CAD": ("CORRA", "1", 10), "CHF": ("SARON", "1", 10), "BRL": ("CDI", "3", 20),
          "MXN": ("TIIE", "3", 20), "CNH": ("CNHHIBOR", "3", 20)}
EM_CCYS = ("BRL", "MXN", "CNH")
#: the currencies MAR21.44 lets a bank divide the GIRR risk weight by the square root of two for, and MAR21.88's specified FX pairs
SPECIFIED = ("USD", "EUR", "GBP", "AUD", "JPY", "CAD", "CHF")
INFLATION = {"USD": "USCPI", "EUR": "HICPXT", "GBP": "UKRPI"}
FX_CCYS = ("EUR", "GBP", "JPY", "AUD", "CAD", "CHF", "CNH", "BRL", "MXN")
#: SIMM FX volatility group (1 regular, 2 high)
FX_SIMM_GROUP = {"BRL": "2"}

#: credit sectors: code -> (FRTB CSR bucket IG, HY; SIMM credit-qualifying bucket IG, HY; sector name)
SECTORS = {"SOV": (1, 9, 1, 7, "Sovereigns including central banks"),
           "FIN": (3, 11, 2, 8, "Financials including government-backed financials"),
           "BAS": (4, 12, 3, 9, "Basic materials, energy, industrials, agriculture, manufacturing, mining"),
           "CON": (5, 13, 4, 10, "Consumer goods and services, transportation and storage, administrative and support services"),
           "TEC": (6, 14, 5, 11, "Technology, telecommunications"),
           "HLT": (7, 15, 6, 12, "Health care, utilities, professional and technical activities")}
ISSUERS_PER = 3
#: credit indices: name -> (FRTB CSR bucket, credit quality)
CREDIT_INDICES = {"CDX.NA.IG": (17, "IG"), "ITRAXX.EUROPE": (17, "IG"), "CDX.NA.HY": (18, "HY")}

#: equity underlyings: name -> (region factor of the VaR model, FRTB bucket, SIMM bucket, market cap, economy, sector, is an index)
EQUITIES = {
    "SPX": ("EQ.US", 12, 11, "LARGE", "ADVANCED", "Index", True),
    "SX5E": ("EQ.EU", 12, 11, "LARGE", "ADVANCED", "Index", True),
    "NKY": ("EQ.JP", 12, 11, "LARGE", "ADVANCED", "Index", True),
    "MXEF": ("EQ.EM", 13, 11, "LARGE", "EMERGING", "Index", True),
    "EQ-US-LC-01": ("EQ.US", 5, 5, "LARGE", "ADVANCED", "Consumer goods and services", False),
    "EQ-US-LC-02": ("EQ.US", 8, 8, "LARGE", "ADVANCED", "Financials and technology", False),
    "EQ-US-SC-01": ("EQ.US", 10, 10, "SMALL", "ADVANCED", "Small cap", False),
    "EQ-EU-LC-01": ("EQ.EU", 6, 6, "LARGE", "ADVANCED", "Telecommunications and industrials", False),
    "EQ-EU-LC-02": ("EQ.EU", 7, 7, "LARGE", "ADVANCED", "Basic materials and energy", False),
    "EQ-JP-LC-01": ("EQ.JP", 8, 8, "LARGE", "ADVANCED", "Financials and technology", False),
    "EQ-EM-LC-01": ("EQ.EM", 1, 1, "LARGE", "EMERGING", "Consumer goods and services", False),
    "EQ-EM-LC-02": ("EQ.EM", 4, 4, "LARGE", "EMERGING", "Financials and technology", False),
}
REGION_INDEX = {"EQ.US": "SPX", "EQ.EU": "SX5E", "EQ.JP": "NKY", "EQ.EM": "MXEF"}

#: commodities: underlying -> (location, VaR sector factor, FRTB bucket, SIMM bucket, FRTB liquidity horizon)
COMMODITIES = {
    "WTI": ("CUSHING", "CO.ENERGY", 2, 2, 20), "BRENT": ("ICE", "CO.ENERGY", 2, 2, 20), "NG": ("HENRY_HUB", "CO.ENERGY", 6, 6, 20),
    "POWER": ("PJM_WEST", "CO.POWER", 3, 8, 20), "EUA": ("ICE_ENDEX", "CO.POWER", 3, 16, 20), "GOLD": ("LBMA", "CO.METALS", 7, 12, 60),
    "COPPER": ("LME", "CO.METALS", 5, 11, 60), "CORN": ("CBOT", "CO.AGRI", 8, 13, 60), "SUGAR": ("ICE_US", "CO.AGRI", 10, 14, 60),
    "FREIGHT": ("BALTIC", "CO.ENERGY", 4, 10, 60),
}
SECTOR_COMMODITIES = {"CO.ENERGY": ("WTI", "BRENT", "NG"), "CO.POWER": ("POWER", "EUA"), "CO.METALS": ("GOLD", "COPPER"),
                      "CO.AGRI": ("CORN", "SUGAR")}

SIMM_CLASS = {"GIRR": "InterestRate", "CSR_NS": "CreditQ", "EQ": "Equity", "COMM": "Commodity", "FX": "FX"}
SYSTEMS = ("MUREX", "CALYPSO", "ENDUR")


def issuers() -> list[tuple[str, str, str]]:
    """(issuer id, sector code, credit quality) of every synthetic issuer: ISSUERS_PER per sector and quality."""
    return [(f"ISS-{s}-{q}-{n:02d}", s, q) for s in SECTORS for q in ("IG", "HY") for n in range(1, ISSUERS_PER + 1)]


def source_names(name: str) -> list[str]:
    """The names three source systems give a factor (what the bank's mapping turns into the standard name): Murex-like
    ``MX:IR:CURVE-USD-SOFR-10Y``, Calypso-like ``IR.CURVE.USD.SOFR.10Y``, Endur-like ``10Y_SOFR_USD_CURVE_IR``. Synthetic, one each;
    unique across the dictionary because the standard name is."""
    parts = name.split("/")
    return [f"MX:{parts[0]}:{'-'.join(parts[1:])}", ".".join(parts), "_".join(reversed(parts))]


def eligibility(name: str, rclass: str, qualifier: str, tenor: str | None, quality: str | None) -> tuple[bool, str | None]:
    """(MODELLABLE, PROXY_OF): the demo's risk factor eligibility (MAR31): the volatilities of emerging-market equity names and
    currencies and the long end of high-yield spreads have too few real prices, and an emerging-market name's volatility is proxied by
    its index's. Synthetic, deterministic."""
    if name.startswith("VOL/EQ/EQ-EM-"):
        return False, f"VOL/EQ/MXEF/{tenor}"
    if name.startswith("VOL/FX/") and qualifier in EM_CCYS:
        return False, None
    if rclass == "CSR_NS" and quality == "HY" and tenor == "10Y":
        return False, None
    return True, None


def _row(name: str, ftype: str, rclass: str, qualifier: str, curve: str | None, underlying: str | None, tenor: str | None,
         bucket: str, simm_bucket: str, label1: str | None, label2: str | None, lh: int, ref: str | None, **extra) -> dict:
    row = {"RISK_FACTOR": name, "FACTOR_TYPE": ftype, "RISK_CLASS": rclass, "QUALIFIER": qualifier, "CURVE": curve,
           "UNDERLYING": underlying, "TENOR": tenor, "UNDERLYING_TENOR": extra.pop("underlying_tenor", None),
           "FRTB_BUCKET": bucket, "SIMM_RISK_CLASS": SIMM_CLASS[rclass], "SIMM_BUCKET": simm_bucket, "SIMM_LABEL1": label1,
           "SIMM_LABEL2": label2}
    row.update({k.upper(): v for k, v in extra.items()})
    modellable, proxy = eligibility(name, rclass, qualifier, tenor, extra.get("credit_quality"))
    row.update({"LIQUIDITY_HORIZON": lh, "MODELLABLE": modellable})
    if proxy:
        row["PROXY_OF"] = proxy
    row.update({"MARKET_DATA_REF": ref, "SOURCE_NAMES": source_names(name)})
    if name.startswith("CM/PRICE/FREIGHT"):            # the freight index contract is replaced at the year end: the rows end with it
        row["VALID_TO"] = "2026-12-31"
    return row


def factors() -> list[dict]:
    """Every factor of the demo dictionary, in name order (without the dates, which the generator adds)."""
    out: list[dict] = []
    for ccy, (curve, simm, lh) in CURVES.items():
        # the GIRR curvature factor is the currency (MAR21.8): every curve of it shifted together; the cross-currency basis a factor of
        # its own (MAR21.8(1)(d))
        out.append(_row(f"IR/CURVE/{ccy}", "CURVE", "GIRR", ccy, None, None, None, ccy, simm, None, None, lh, f"CRV-{ccy}"))
        if ccy != "USD":
            out.append(_row(f"IR/BASIS/{ccy}", "BASIS", "GIRR", ccy, None, None, None, ccy, simm, None, "XCcy", lh, f"XCCY-{ccy}USD"))
        for t in GIRR_TENORS:
            out.append(_row(f"IR/CURVE/{ccy}/{curve}/{t}", "CURVE", "GIRR", ccy, curve, None, t, ccy, simm, t.lower(), "OIS", lh,
                            f"CRV-{ccy}-{curve}#{t}"))
        for e in VEGA_EXPIRIES:
            out.append(_row(f"VOL/IR/{ccy}/{curve}/{e}/10Y", "VOL", "GIRR", ccy, curve, curve, e, ccy, simm, e.lower(), None, 60,
                            f"VOL-{ccy}-{curve}-SWPN#{e}x10Y", underlying_tenor="10Y"))
    for ccy, index in INFLATION.items():
        # the inflation factor without a tenor (one per currency in FRTB, MAR21.8(1)(c)); the tenor points for SIMM and the ladders
        out.append(_row(f"INF/{index}", "INFLATION", "GIRR", ccy, index, index, None, ccy, CURVES[ccy][1], "Inf", None, 20, f"INF-{index}"))
        for t in INF_TENORS:
            out.append(_row(f"INF/{index}/{t}", "INFLATION", "GIRR", ccy, index, index, t, ccy, CURVES[ccy][1], "Inf", None, 20,
                            f"INF-{index}#{t}"))
    for iss, sec, q in issuers():
        b_ig, b_hy, s_ig, s_hy, sector = SECTORS[sec]
        bucket, simm = (b_ig, s_ig) if q == "IG" else (b_hy, s_hy)
        lh = (20 if q == "IG" else 40) if sec == "SOV" else (40 if q == "IG" else 60)
        out.append(_row(f"CR/SPREAD/{iss}", "SPREAD", "CSR_NS", iss, None, None, None, str(bucket), str(simm), None, None, lh,
                        f"CDS-{iss}", sector=sector, credit_quality=q, seniority="SENIOR"))
        for kind in ("BOND", "CDS"):
            for t in CSR_TENORS:
                out.append(_row(f"CR/SPREAD/{iss}/{kind}/{t}", "SPREAD", "CSR_NS", iss, kind, None, t, str(bucket), str(simm),
                                SIMM_CREDIT_TENOR[t], kind, lh, f"{kind}-{iss}#{t}", sector=sector, credit_quality=q, seniority="SENIOR"))
    for idx, (bucket, q) in CREDIT_INDICES.items():
        out.append(_row(f"CR/SPREAD/{idx}", "SPREAD", "CSR_NS", idx, None, idx, None, str(bucket), "Residual", None, None, 40,
                        f"CDX-{idx}", sector="Index", credit_quality=q))
        for t in CSR_TENORS:
            out.append(_row(f"CR/SPREAD/{idx}/CDS/{t}", "SPREAD", "CSR_NS", idx, "CDS", idx, t, str(bucket), "Residual",
                            SIMM_CREDIT_TENOR[t], "CDS", 40, f"CDX-{idx}#{t}", sector="Index", credit_quality=q))
        for e in VEGA_EXPIRIES:
            out.append(_row(f"VOL/CR/{idx}/{e}", "VOL", "CSR_NS", idx, None, idx, e, str(bucket), "Residual", e.lower(), None, 120,
                            f"VOL-{idx}#{e}", sector="Index", credit_quality=q))
    for ccy in FX_CCYS:
        lh = 10 if ccy in SPECIFIED else 20
        out.append(_row(f"FX/SPOT/{ccy}", "SPOT", "FX", ccy, None, f"{ccy}USD", None, ccy, FX_SIMM_GROUP.get(ccy, "1"), None, None, lh,
                        f"FX-{ccy}USD"))
        for e in VEGA_EXPIRIES:
            out.append(_row(f"VOL/FX/{ccy}/{e}", "VOL", "FX", ccy, None, f"{ccy}USD", e, ccy, FX_SIMM_GROUP.get(ccy, "1"), e.lower(), None,
                            40, f"VOL-{ccy}USD#{e}"))
    for und, (_, bucket, simm, cap, eco, sector, index) in EQUITIES.items():
        lh = 10 if cap == "LARGE" else 20
        extra = {"sector": sector, "market_cap": cap, "economy": eco}
        out.append(_row(f"EQ/SPOT/{und}", "SPOT", "EQ", und, None, und, None, str(bucket), str(simm), None, None, lh, f"EQ-{und}", **extra))
        if not index:
            out.append(_row(f"EQ/REPO/{und}", "REPO", "EQ", und, None, und, None, str(bucket), str(simm), None, None, lh, f"REPO-{und}", **extra))
        for e in VEGA_EXPIRIES:
            out.append(_row(f"VOL/EQ/{und}/{e}", "VOL", "EQ", und, None, und, e, str(bucket), str(simm), e.lower(), None,
                            20 if cap == "LARGE" else 60, f"VOL-{und}#{e}", **extra))
    for und, (loc, _, bucket, simm, lh) in COMMODITIES.items():
        # the commodity curvature factor is the commodity (MAR21.13): every location and tenor shifted together
        out.append(_row(f"CM/PRICE/{und}", "PRICE", "COMM", und, None, und, None, str(bucket), str(simm), None, None, lh, f"CMD-{und}"))
        for t in CM_TENORS:
            out.append(_row(f"CM/PRICE/{und}/{loc}/{t}", "PRICE", "COMM", und, loc, und, t, str(bucket), str(simm), None, None, lh,
                            f"CMD-{und}-{loc}#{t}"))
        for e in VEGA_EXPIRIES:
            out.append(_row(f"VOL/CM/{und}/{e}", "VOL", "COMM", und, loc, und, e, str(bucket), str(simm), e.lower(), None, 120,
                            f"VOL-{und}#{e}"))
    return sorted(out, key=lambda r: r["RISK_FACTOR"])


@functools.lru_cache(maxsize=1)
def by_name() -> dict[str, dict]:
    return {r["RISK_FACTOR"]: r for r in factors()}


def nearest_two(tenors: dict[str, float], years: float) -> list[tuple[str, float]]:
    """A cash flow at ``years`` split onto the two vertices around it, linearly (MAR21.8's interpolation): [(vertex, share)]."""
    names = list(tenors)
    if years <= tenors[names[0]]:
        return [(names[0], 1.0)]
    if years >= tenors[names[-1]]:
        return [(names[-1], 1.0)]
    for a, b in zip(names, names[1:]):
        ya, yb = tenors[a], tenors[b]
        if years == ya:
            return [(a, 1.0)]
        if ya < years <= yb:
            w = (years - ya) / (yb - ya)
            return [(b, 1.0)] if w >= 1.0 else [(a, 1.0 - w), (b, w)]
    return [(names[-1], 1.0)]
