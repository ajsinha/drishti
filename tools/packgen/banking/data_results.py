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

"""Reference data (banking-core) and risk results (counterparty-risk, market-risk) computed from the trades, so they
reconcile: a netting set's net MTM is the sum of its trades', its exposure profile follows their maturities, CVA
uses that exposure and the counterparty's credit curve, limits use the PFE, and VaR aggregates books into desks."""
from __future__ import annotations

import math
import sys
from collections import defaultdict
from datetime import date, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from samplegen import ids  # noqa: E402
from samplegen.dates import Calendar, iso, tenor_years  # noqa: E402

import data_market as M  # noqa: E402
import data_names as N  # noqa: E402
import data_trades as TR  # noqa: E402

TENORS = ["0", "1M", "3M", "6M", "1Y", "2Y", "3Y", "5Y", "7Y", "10Y"]
SF = {"Rates": 0.005, "Inflation": 0.005, "Money market": 0.005, "Fixed income": 0.005, "Securities financing": 0.005, "FX": 0.04,
      "Credit": 0.05, "Equity": 0.32, "Structured": 0.32, "Commodity": 0.18}
SACCR_CLASS = {"Rates": "Interest rate", "Inflation": "Interest rate", "Money market": "Interest rate", "Fixed income": "Interest rate",
               "Securities financing": "Interest rate", "FX": "Foreign exchange", "Credit": "Credit", "Equity": "Equity", "Structured": "Equity",
               "Commodity": "Commodity"}


def years(d: str) -> float:
    return max((date.fromisoformat(d) - N.AS_OF).days / 365, 0.0)


BUCKETS = [(1, "0-1Y"), (2, "1-2Y"), (5, "2-5Y"), (10, "5-10Y")]


def maturity_bucket(d: str) -> str:
    """A trade's remaining maturity as a desk buckets it (natural order: 0-1Y, 1-2Y, 2-5Y, 5-10Y, 10Y+)."""
    y = years(d)
    return next((label for top, label in BUCKETS if y < top), "10Y+")


def trade_row(t: dict) -> dict:
    """A trade as a row of another entity's trade list: the columns shown, and what its Pivot tab groups by."""
    return {"tradeId": t["tradeId"], "product": t["productName"], "assetClass": t["assetClass"], "currency": t["currency"], "book": t["book"],
            "notional": t["notional"], "maturity": t["maturityDate"], "maturityBucket": maturity_bucket(t["maturityDate"]), "mtm": t["mtm"]}


def rating_pd(rating: str) -> float:
    return {"AAA": 0.0001, "AA+": 0.0002, "AA": 0.0003, "AA-": 0.0004, "A+": 0.0005, "A": 0.0006, "A-": 0.0008, "BBB+": 0.0012, "BBB": 0.0018,
            "BBB-": 0.0026, "BB+": 0.0042, "BB": 0.0068, "BB-": 0.011, "B+": 0.018, "B": 0.03}.get(rating, 0.005)


# ---- counterparty credit risk ----------------------------------------------------------------------------------
def netting_sets(trades: dict) -> dict[str, list[dict]]:
    by = defaultdict(list)
    for t in trades.values():
        by[t["nettingSet"]].append(t)
    return by


def profile(members: list[dict], collateral: float) -> list[dict]:
    net = sum(t["mtm"] for t in members)
    out = []
    for tn in TENORS:
        y = 0 if tn == "0" else tenor_years(tn)
        alive = [t for t in members if years(t["maturityDate"]) > y]
        vol = sum(abs(t["notional"]) * SF[t["assetClass"]] * 0.35 for t in alive)
        ee = max(net - collateral, 0) * math.exp(-y / 4) + vol * math.sqrt(max(y, 1 / 52)) * 0.4
        ene = -max(collateral - net, 0) * math.exp(-y / 4) - vol * math.sqrt(max(y, 1 / 52)) * 0.3
        out.append({"tenor": tn, "ee": round(ee), "ene": round(ene), "pfe95": round(ee * 2.2 + vol * 0.05), "pfe99": round(ee * 2.9 + vol * 0.07)})
    return out


def credit_results(trades: dict, docs: dict) -> None:
    for ns, members in sorted(netting_sets(trades).items()):
        cp = ns.split("-", 1)[1].rsplit("-", 1)[0]
        name, typ, sector, country, rating, _ = N.COUNTERPARTIES[cp]
        r = M.rng(ns)
        net = sum(t["mtm"] for t in members)
        coll = round(net * r.uniform(0.6, 0.95)) if net > 0 else round(net * r.uniform(0.5, 0.9))
        prof = profile(members, coll)
        ee_peak, pfe_peak = max(p["ee"] for p in prof), max(p["pfe95"] for p in prof)
        limit = max(5e6, round(pfe_peak * r.uniform(1.2, 2.2), -6))
        pd = rating_pd(rating)
        # CVA = -LGD x sum over periods of EE x PD x period length (flat hazard from the one-year PD)
        cva = -round(0.6 * sum(p["ee"] * pd * (tenor_years(p["tenor"]) - (0 if q["tenor"] == "0" else tenor_years(q["tenor"])))
                               for q, p in zip(prof[:-1], prof[1:])))
        by_asset = defaultdict(float)
        for t in members:
            by_asset[t["assetClass"]] += t["mtm"]
        tag = ns[3:]
        docs["netting-set"][ns] = {
            "nettingSetId": ns, "tradeCount": len(members), "netMtm": net, "collateral": coll, "eePeak": ee_peak, "pfePeak": pfe_peak,
            "limit": limit, "utilisation": round(pfe_peak / limit, 4), "cva": cva, "profile": [{"tenor": p["tenor"], "ee": p["ee"], "pfe": p["pfe95"]} for p in prof],
            "trades": [trade_row(t) for t in sorted(members, key=lambda t: -abs(t["mtm"]))][:40],
            "byAsset": [{"asset": a, "mtm": round(v)} for a, v in sorted(by_asset.items(), key=lambda x: -abs(x[1]))],
            "counterparty": f"CP-{cp}", "agreement": f"AGR-{cp}-ISDA", "csa": f"CSA-{cp}", "creditLimit": f"LIM-{cp}",
            "exposureProfile": f"EXP-{tag}", "cvaResult": f"CVA-{tag}", "saccr": f"SACCR-{tag}", "simm": f"SIMM-{cp}",
            "collateralBalance": f"COLL-{tag}", "legalEntity": {"NY": "LE-NY", "LDN": "LE-LDN", "FRA": "LE-FRA", "TKY": "LE-TKY"}[ns.rsplit("-", 1)[1]],
            "nettingEnforceable": True, "_meta": {"source": "xva-engine", "generation": 1, "live": True, "walk": {"netMtm": max(1000, abs(net) // 500)}}}
        docs["exposure-profile"][f"EXP-{tag}"] = {
            "profileId": f"EXP-{tag}", "epe": round(sum(p["ee"] for p in prof) / len(prof)), "eepe": round(max(p["ee"] for p in prof[:5])),
            "pfePeak": pfe_peak, "paths": 5000, "runDate": iso(N.AS_OF), "buckets": prof, "nettingSet": ns,
            "model": "Hull-White 1F rates, GBM FX and equity, 5,000 paths", "confidence": 0.95, "_meta": {"source": "xva-engine", "generation": 1}}
        dva = round(-cva * 0.35 * rating_pd("A") / pd) if pd else 0
        docs["cva"][f"CVA-{tag}"] = {
            "resultId": f"CVA-{tag}", "cva": cva, "dva": dva, "fva": round(-abs(net) * 0.0012), "kva": round(-pfe_peak * 0.004), "cs01": round(cva * 0.018),
            "components": [{"name": "CVA", "amount": cva}, {"name": "DVA", "amount": dva}, {"name": "FVA", "amount": round(-abs(net) * 0.0012)},
                           {"name": "KVA", "amount": round(-pfe_peak * 0.004)}],
            "sensitivities": [{"factor": f, "bump": b, "delta": round(cva * k)} for f, b, k in
                              [("Counterparty spread", "+1 bp", 0.018), ("USD rates", "+1 bp", -0.004), ("EUR rates", "+1 bp", -0.002),
                               ("FX vol", "+1 vol", 0.03), ("Recovery", "+1%", -0.017)]],
            "nettingSet": ns, "creditCurve": f"CDS-{cp}" if cp in N.ISSUERS else None, "_meta": {"source": "xva-engine", "generation": 1}}
        add = defaultdict(float)
        hs = defaultdict(float)
        for t in members:
            eff = t["notional"] * min(1.0, max(years(t["maturityDate"]), 0.1)) * (1 if "Pay" in t["direction"] or t["direction"] in ("Buy", "Long") else -1)
            hs[(SACCR_CLASS[t["assetClass"]], t["currency"])] += eff
        for (ac, h), e in hs.items():
            add[ac] += abs(e) * SF_BY_CLASS[ac]
        rc = max(net - coll, 0)
        addon = sum(add.values())
        mult = round(min(1.0, 0.05 + 0.95 * math.exp((net - coll) / (2 * 0.95 * max(addon, 1)))), 4)
        ead = round(1.4 * (rc + mult * addon))
        docs["sa-ccr"][f"SACCR-{tag}"] = {
            "calcId": f"SACCR-{tag}", "replacementCost": round(rc), "addOn": round(addon), "multiplier": mult, "ead": ead,
            "rwa": round(ead * {"A": 0.5, "A+": 0.5, "A-": 0.5, "AA": 0.2, "AA-": 0.2, "AAA": 0.2}.get(rating, 1.0)),
            "addOns": [{"assetClass": a, "addOn": round(v)} for a, v in add.items()],
            "hedgingSets": [{"assetClass": a, "hedgingSet": h, "effectiveNotional": round(e), "sf": SF_BY_CLASS[a]} for (a, h), e in hs.items()],
            "nettingSet": ns, "alpha": 1.4, "_meta": {"source": "capital-engine", "generation": 1}}
        held, posted = (abs(coll), 0) if coll > 0 else (0, abs(coll))
        positions = []
        for b in r.sample(list(TR.all_bonds().values()), 3):
            mv = round((held or posted) / 3)
            hc = r.choice([0.005, 0.01, 0.02])
            positions.append({"asset": b["issuerName"] + " " + b["maturity"][:4], "direction": "Held" if held else "Posted", "marketValue": mv,
                              "haircut": hc, "value": round(mv * (1 - hc))})
        docs["collateral-balance"][f"COLL-{tag}"] = {
            "balanceId": f"COLL-{tag}", "held": held, "posted": posted, "net": held - posted, "asOf": iso(N.AS_OF), "positions": positions,
            "csa": f"CSA-{cp}", "nettingSet": ns, "_meta": {"source": "collateral-system", "generation": 1}}
        for k in range(2):
            cid = f"MC-{tag}-{k + 1}"
            d = Calendar.of("USNY").add_business_days(N.AS_OF, -2 * k)
            amt = round(abs(net) * r.uniform(0.02, 0.08), -3)
            status = "Agreed" if k == 0 else "Settled"
            docs["margin-call"][cid] = {
                "callId": cid, "callDate": iso(d), "marginType": "Variation", "direction": "We call" if net > 0 else "They call", "amount": amt,
                "status": status, "disputeAmount": 0 if r.random() > 0.2 else round(amt * 0.1, -3),
                "events": [{"at": iso(d) + " 09:05", "event": "Call issued", "by": "collateral-system"},
                           {"at": iso(d) + " 11:40", "event": "Agreed", "by": "counterparty ops"}]
                          + ([{"at": iso(d + timedelta(days=1)) + " 10:15", "event": "Settled", "by": "payments"}] if status == "Settled" else []),
                "nettingSet": ns, "csa": f"CSA-{cp}", "currency": "USD", "_meta": {"source": "collateral-system", "generation": 1}}
    by_cp = defaultdict(list)
    for ns, d in docs["netting-set"].items():
        by_cp[d["counterparty"][3:]].append(d)
    for cp, sets in by_cp.items():
        name, typ, sector, country, rating, _ = N.COUNTERPARTIES[cp]
        used = sum(s["pfePeak"] for s in sets)
        limit = max(10e6, round(used * M.rng("lim" + cp).uniform(1.2, 2.0), -6))
        buckets = [{"bucket": b, "limit": round(limit * f), "used": round(used * u), "utilisation": round(used * u / (limit * f), 4)}
                   for b, f, u in [("0-1Y", 1.0, 0.85), ("1-3Y", 1.0, 1.0), ("3-5Y", 0.8, 0.7), ("5-10Y", 0.6, 0.35), ("10Y+", 0.4, 0.1)]]
        docs["credit-limit"][f"LIM-{cp}"] = {
            "limitId": f"LIM-{cp}", "counterpartyName": name, "measure": "PFE 95 peak", "limit": limit, "used": used,
            "utilisation": round(used / limit, 4), "status": "Within limit" if used < 0.8 * limit else "Early warning" if used < limit else "Breach",
            "buckets": buckets, "counterparty": f"CP-{cp}", "approvedBy": "Credit Risk Committee", "reviewDue": iso(N.AS_OF + timedelta(days=170)),
            "_meta": {"source": "limits-system", "generation": 1}}
        im = round(sum(abs(t["mtm"]) for s in sets for t in s["trades"]) * 0.08 + 1e6)
        classes = [("Interest rate", 0.45), ("Credit qualifying", 0.12), ("Equity", 0.18), ("Commodity", 0.08), ("FX", 0.17)]
        docs["simm"][f"SIMM-{cp}"] = {
            "calcId": f"SIMM-{cp}", "total": im, "posted": round(im * 0.97), "received": round(im * 1.02), "version": "ISDA SIMM 2.7",
            "riskClasses": [{"riskClass": c, "im": round(im * w)} for c, w in classes], "nettingSet": sets[0]["nettingSetId"],
            "counterparty": f"CP-{cp}", "_meta": {"source": "margin-engine", "generation": 1}}


SF_BY_CLASS = {"Interest rate": 0.005, "Foreign exchange": 0.04, "Credit": 0.05, "Equity": 0.32, "Commodity": 0.18}


# ---- market risk -----------------------------------------------------------------------------------------------
SCENARIOS = {"SCN-GFC2008": ("Global financial crisis 2008", "Historical", "Severe", {"Equities": -0.42, "Credit spreads (IG)": 2.40, "USD rates": -1.60, "Oil": -0.58, "USD vs G10": 0.12}),
             "SCN-COVID2020": ("COVID-19 March 2020", "Historical", "Severe", {"Equities": -0.34, "Credit spreads (IG)": 1.35, "USD rates": -1.20, "Oil": -0.65, "Equity vol": 45.0}),
             "SCN-RATES-UP200": ("Parallel rates +200 bp", "Hypothetical", "Moderate", {"USD rates": 2.0, "EUR rates": 2.0, "GBP rates": 2.0, "JPY rates": 1.0}),
             "SCN-RATES-TWIST": ("Bear steepener 2s10s +75 bp", "Hypothetical", "Mild", {"2Y rates": 0.25, "10Y rates": 1.00}),
             "SCN-USD-SPIKE": ("USD +15% vs all", "Hypothetical", "Moderate", {"USD vs G10": 0.15, "USD vs EM": 0.20}),
             "SCN-OIL-SHOCK": ("Oil supply shock", "Hypothetical", "Moderate", {"Oil": 0.60, "Natural gas": 0.45, "Equities": -0.08}),
             "SCN-EM-CRISIS": ("Emerging-market crisis", "Hypothetical", "Severe", {"USD vs EM": 0.30, "EM credit spreads": 4.0, "Equities": -0.15}),
             "SCN-INFL-SURGE": ("Inflation surprise", "Hypothetical", "Moderate", {"Breakevens": 0.80, "USD rates": 1.20, "Equities": -0.10})}


def book_var(members: list[dict]) -> float:
    return 2.33 * math.sqrt(sum((abs(t["mtm"]) * 0.004 + sum(abs(v) for v in t["risk"].values()) * 0.6) ** 2 for t in members))


def market_results(trades: dict, docs: dict) -> None:
    books = defaultdict(list)
    for t in trades.values():
        books[t["book"]].append(t)
    desk_books = defaultdict(list)
    for b in books:
        desk_books["DESK-" + b.split("-")[1]].append(b)
    days = M.business_days(60)
    for desk, bl in desk_books.items():
        r = M.rng("var" + desk)
        contrib = {b: book_var(books[b]) for b in sorted(bl)}
        var99 = round(math.sqrt(sum(v * v for v in contrib.values())) * 0.85)
        limit = round(var99 * r.uniform(1.25, 1.8), -4)
        pnl = [{"date": iso(d), "pnl": round(r.gauss(0, var99 / 2.33))} for d in days]
        exceptions = sum(1 for p in pnl if p["pnl"] < -var99)
        scenarios = scenario_pnl(desk, var99)
        docs["var"][f"VAR-{desk[5:]}"] = {
            "resultId": f"VAR-{desk[5:]}", "var99": var99, "es975": round(var99 * 1.13), "svar": round(var99 * 1.9), "limit": limit, "exceptions": exceptions,
            "scenarioPnl": scenarios, "meanPnl": round(sum(scenarios) / len(scenarios)),
            "pnlSeries": pnl, "contributions": [{"book": b, "var": round(v * 0.85)} for b, v in contrib.items()], "desk": desk,
            "method": "Historical simulation, 1-day, 99%, 500 scenarios", "_meta": {"source": "var-engine", "generation": 1}}
        for scn, (name, typ, sev, shocks) in SCENARIOS.items():
            by_book = [{"book": b, "pnl": round(-contrib[b] * {"Severe": 4.2, "Moderate": 2.1, "Mild": 0.9}[sev] * r.uniform(0.4, 1.3))} for b in sorted(bl)]
            sid = f"STR-{scn[4:]}-{desk[5:]}"
            docs["stress-result"][sid] = {
                "resultId": sid, "scenarioName": name, "pnl": sum(x["pnl"] for x in by_book), "limit": -round(limit * 4, -5), "byBook": by_book,
                "scenario": scn, "desk": desk, "_meta": {"source": "stress-engine", "generation": 1}}
        for b in sorted(bl):
            ts = books[b]
            rb = M.rng("frtb" + b)
            delta = round(book_var(ts) * rb.uniform(1.1, 1.6))
            vega, curv = round(delta * rb.uniform(0.1, 0.4)), round(delta * rb.uniform(0.05, 0.2))
            classes = sorted({TR_CLASS.get(t["assetClass"], "GIRR") for t in ts})
            docs["frtb-sensitivity"][f"FRTB-{b[5:]}"] = {
                "resultId": f"FRTB-{b[5:]}", "book": b, "deltaCharge": delta, "vegaCharge": vega, "curvatureCharge": curv, "total": delta + vega + curv,
                "sensitivities": [{"riskClass": c, "bucket": bk, "measure": m, "value": round(rb.gauss(0, delta / 20))}
                                  for c in classes for bk in ("1", "2", "3") for m in ("Delta", "Vega")],
                "byClass": [{"riskClass": c, "charge": round((delta + vega + curv) / len(classes))} for c in classes],
                "approach": "FRTB standardised (SBM)", "_meta": {"source": "frtb-engine", "generation": 1}}
            actual = round(sum(t["pnl1d"] for t in ts))
            explained = round(actual * rb.uniform(0.85, 1.05))
            docs["pnl-explain"][f"PNL-{b[5:]}"] = {
                "resultId": f"PNL-{b[5:]}", "date": iso(N.AS_OF), "actual": actual, "explained": explained, "unexplained": actual - explained,
                "attribution": [{"factor": f, "pnl": round(explained * w)} for f, w in
                                [("Rates delta", 0.42), ("FX delta", 0.12), ("Credit", 0.08), ("Vega", 0.1), ("Carry and theta", 0.2), ("New trades", 0.08)]],
                "explainSteps": explain_steps(explained, actual),
                "book": b, "_meta": {"source": "pnl-explain", "generation": 1}}
    for scn, (name, typ, sev, shocks) in SCENARIOS.items():
        docs["stress-scenario"][scn] = {"scenarioId": scn, "name": name, "type": typ, "severity": sev,
                                         "shocks": [{"factor": f, "shock": s} for f, s in shocks.items()], "horizon": "10 days",
                                         "_meta": {"source": "stress-engine", "generation": 1}}


def scenario_pnl(desk: str, var99: float, n: int = 500) -> list[int]:
    """The desk's P&L under each of the n historical scenarios the VaR is read from: fat-tailed (a scale mixture of
    normals), scaled so the fifth worst of 500 (the 1% quantile) is minus the VaR."""
    r = M.rng("scenarios" + desk)
    raw = [r.gauss(0, 1) * (1.6 if r.random() < 0.08 else 1.0) for _ in range(n)]
    q = sorted(raw)[max(0, n // 100 - 1)]
    k = var99 / -q if q < 0 else var99 / 2.33
    return [round(x * k) for x in raw]


# the risk factors a book's explained P&L comes from, and their usual shares (theta costs)
EXPLAIN_WEIGHTS = [("Carry", 0.14), ("Roll-down", 0.06), ("Rates delta", 0.42), ("FX delta", 0.12), ("Credit", 0.08), ("Vol (vega)", 0.10),
                   ("Theta", -0.04), ("New trades", 0.12)]


def explain_steps(explained: int, actual: int) -> list[dict]:
    """The waterfall from zero to the actual P&L: each factor's share of the explained P&L (the last one takes the
    rounding), then the unexplained rest."""
    steps = [{"step": f, "pnl": round(explained * w)} for f, w in EXPLAIN_WEIGHTS]
    steps[-1]["pnl"] += explained - sum(x["pnl"] for x in steps)
    return steps + [{"step": "Unexplained", "pnl": actual - explained}]


TR_CLASS = {"Rates": "GIRR", "Inflation": "GIRR", "Money market": "GIRR", "Fixed income": "CSR non-securitisation", "Securities financing": "GIRR",
            "FX": "FX", "Credit": "CSR non-securitisation", "Equity": "Equity", "Structured": "Equity", "Commodity": "Commodity"}


# ---- reference data --------------------------------------------------------------------------------------------
def reference(trades: dict, docs: dict) -> None:
    books = defaultdict(list)
    for t in trades.values():
        books[t["book"]].append(t)
    for le, (name, country, reg) in N.LEGAL_ENTITIES.items():
        desks = [d for d, v in N.DESKS.items() if v[1] == le]
        docs["legal-entity"][le] = {"entityId": le, "name": name, "lei": ids.lei(name), "jurisdiction": country, "regulator": reg,
                                    "desks": [{"id": d, "name": N.DESKS[d][0]} for d in desks],
                                    "books": [{"book": b, "desk": N.DESKS[d][0], "var": round(book_var(books[b]) * 0.85), "pnl": round(sum(t["pnl1d"] for t in books[b])),
                                               "mtm": round(sum(t["mtm"] for t in books[b])), "trades": len(books[b])} for d in desks for b in TR.books_of(d) if books[b]],
                                    "_meta": {"source": "entity-master", "generation": 1}}
    for desk, (name, le, classes, head) in N.DESKS.items():
        bl = TR.books_of(desk)
        mtm = sum(t["mtm"] for b in bl for t in books[b])
        docs["desk"][desk] = {"deskId": desk, "name": name, "head": head, "books": [{"id": b, "trades": len(books[b]), "mtm": round(sum(t["mtm"] for t in books[b]))} for b in bl],
                              "var99": docs["var"].get(f"VAR-{desk[5:]}", {}).get("var99"), "mtm": round(mtm), "legalEntity": le,
                              "varResult": f"VAR-{desk[5:]}", "assetClasses": classes,
                              "positions": [{"tradeId": t["tradeId"], "book": b, "currency": t["currency"], "family": t["family"], "mtm": t["mtm"]}
                                            for b in bl for t in sorted(books[b], key=lambda t: t["tradeId"])],
                              "_meta": {"source": "org-master", "generation": 1}}
        for b in bl:
            ts = books[b]
            docs["book"][b] = {"bookId": b, "name": f"{name} · book {b[-1]}", "deskName": name, "tradeCount": len(ts),
                               "mtm": round(sum(t["mtm"] for t in ts)), "dv01": round(sum(t["risk"].get("dv01", 0) for t in ts)),
                               "topTrades": [trade_row(t) for t in sorted(ts, key=lambda t: -abs(t["mtm"]))[:10]], "desk": desk,
                               "accountingTreatment": "Fair value (FVTPL)", "regulatoryBook": "Trading book", "volcker": "Market making",
                               "_meta": {"source": "org-master", "generation": 1, "live": True, "walk": {"mtm": 5000}}}
        for tr in TR.traders_of(desk):
            tid = TR.trader_id(tr)
            docs["trader"][tid] = {"traderId": tid, "name": tr, "deskName": name, "tradeCount": sum(1 for t in trades.values() if t["trader"] == tid),
                                   "books": [{"id": b} for b in bl], "desk": desk, "_meta": {"source": "org-master", "generation": 1}}
    for cp, (name, typ, sector, country, rating, grp) in N.COUNTERPARTIES.items():
        sets = [d for d in docs["netting-set"].values() if d["counterparty"] == f"CP-{cp}"]
        out = {"counterpartyId": f"CP-{cp}", "name": name, "lei": ids.lei(name), "rating": rating, "sector": sector, "country": country, "type": typ,
               "netMtm": sum(s["netMtm"] for s in sets), "pfePeak": max([s["pfePeak"] for s in sets] or [0]),
               "nettingSets": [{"id": s["nettingSetId"], "agreement": s["agreement"], "trades": s["tradeCount"], "netMtm": s["netMtm"]} for s in sets],
               "kyc": {"status": "Approved", "riskRating": "Standard" if rating[0] == "A" else "Enhanced", "lastReview": "2026-02-11",
                       "emirClassification": "FC" if typ in ("Bank", "Hedge fund", "Insurer", "Asset manager", "Pension fund") else "NFC-",
                       "pd1y": rating_pd(rating)},
               "group": f"GRP-{grp}", "creditLimit": f"LIM-{cp}", "hierarchy": hierarchy(cp, grp, sets),
               "_meta": {"source": "counterparty-master", "generation": 1}}
        if cp in N.ISSUERS:
            out["creditCurve"] = f"CDS-{cp}"
        docs["counterparty"][f"CP-{cp}"] = out
        docs["agreement"][f"AGR-{cp}-ISDA"] = {
            "agreementId": f"AGR-{cp}-ISDA", "type": "ISDA 2002 Master", "counterpartyName": name, "governingLaw": "New York" if country == "US" else "English",
            "signed": iso(date(2015 + len(cp) % 9, 1 + len(name) % 12, 10)), "closeOutNetting": "Enforceable",
            "elections": {"automaticEarlyTermination": False, "crossDefault": "3% of shareholders' equity", "setOff": True, "terminationCurrency": "USD",
                          "additionalTerminationEvents": "Rating downgrade below BBB-" if rating[0] in "AB" else "None",
                          "protocols": "ISDA 2020 IBOR Fallbacks; 2018 US Resolution Stay"},
            "counterparty": f"CP-{cp}", "csa": f"CSA-{cp}", "_meta": {"source": "legal-docs", "generation": 1}}
        th = 0 if typ in ("Bank", "Hedge fund", "Clearing broker") else 10e6
        docs["csa"][f"CSA-{cp}"] = {
            "csaId": f"CSA-{cp}", "marginType": "VM + IM" if typ in ("Bank", "Hedge fund") else "VM", "thresholdUs": th, "thresholdThem": th,
            "mta": 500000, "independentAmount": 0, "frequency": "Daily", "mpor": "10 business days",
            "eligible": [{"asset": "USD, EUR, GBP cash", "haircut": 0.0}, {"asset": "G7 government bonds < 1Y", "haircut": 0.005},
                         {"asset": "G7 government bonds 1-5Y", "haircut": 0.02}, {"asset": "G7 government bonds 5-10Y", "haircut": 0.04}],
            "agreement": f"AGR-{cp}-ISDA", "counterparty": f"CP-{cp}", "_meta": {"source": "legal-docs", "generation": 1}}
    groups = defaultdict(list)
    for cp, v in N.COUNTERPARTIES.items():
        groups[v[5]].append(cp)
    for g, members in groups.items():
        lim = sum(docs["credit-limit"].get(f"LIM-{m}", {}).get("limit", 0) for m in members)
        used = sum(docs["credit-limit"].get(f"LIM-{m}", {}).get("used", 0) for m in members)
        docs["counterparty-group"][f"GRP-{g}"] = {"groupId": f"GRP-{g}", "name": N.GROUPS.get(g, g), "limit": lim, "utilisation": round(used / lim, 4) if lim else 0,
                                                   "members": [{"id": f"CP-{m}", "name": N.COUNTERPARTIES[m][0], "rating": N.COUNTERPARTIES[m][4]} for m in members],
                                                   "_meta": {"source": "counterparty-master", "generation": 1}}
    for i, (name, sector, country, rating, s5, sov) in N.ISSUERS.items():
        bl = [b for b in TR.all_bonds().values() if b["issuer"] == f"ISS-{i}"]
        out = {"issuerId": f"ISS-{i}", "name": name, "rating": rating, "sector": sector, "country": country,
               "bonds": [{"isin": b["isinCode"], "coupon": b["coupon"], "maturity": b["maturity"]} for b in bl], "creditCurve": f"CDS-{i}",
               "lei": ids.lei(name), "_meta": {"source": "counterparty-master", "generation": 1}}
        eq = [t for t, v in N.EQUITIES.items() if v[1] == i]
        if eq:
            out["equity"] = f"EQ-{eq[0]}"
        docs["issuer"][f"ISS-{i}"] = out
    for ccp, (name, model, fund) in N.CCPS.items():
        accts = [f"CLR-{ccp[4:]}-1"]
        docs["ccp"][ccp] = {"ccpId": ccp, "name": name, "defaultFund": fund, "imPosted": round(fund * 0.06), "marginModel": model,
                            "accounts": [{"id": a, "im": round(fund * 0.06)} for a in accts], "_meta": {"source": "clearing-system", "generation": 1}}
        cleared = [t for t in trades.values() if t.get("clearingAccount") == accts[0]]
        docs["clearing-account"][accts[0]] = {"accountId": accts[0], "ccpName": name, "im": round(fund * 0.06), "vmToday": round(sum(t["pnl1d"] for t in cleared)),
                                              "excess": round(fund * 0.012), "trades": [trade_row(t) for t in cleared[:30]], "ccp": ccp,
                                              "_meta": {"source": "clearing-system", "generation": 1}}
    for cal, name in N.CALENDARS.items():
        c = Calendar.of(cal[4:])
        hol = []
        for y in (2026, 2027):
            d = date(y, 1, 1)
            while d.year == y:
                if d.weekday() < 5 and not c.is_business_day(d):
                    hol.append({"date": iso(d), "name": "Public holiday"})
                d += timedelta(days=1)
        docs["calendar"][cal] = {"calendarId": cal, "name": name, "holidays": hol, "weekend": ["Saturday", "Sunday"], "_meta": {"source": "reference-master", "generation": 1}}


def hierarchy(cp: str, grp: str, sets: list[dict]) -> dict:
    """The counterparty in its legal-entity hierarchy: the group (ultimate parent) over its members, and under this
    counterparty its ISDA, the CSA that secures it and the netting sets it governs. Every node is an entity id."""
    members = sorted(m for m, v in N.COUNTERPARTIES.items() if v[5] == grp)
    nodes = [{"id": f"GRP-{grp}", "label": N.GROUPS.get(grp, grp), "type": "Group (ultimate parent)"}]
    nodes += [{"id": f"CP-{m}", "label": N.COUNTERPARTIES[m][0], "type": "Counterparty"} for m in members]
    edges = [{"from": f"GRP-{grp}", "to": f"CP-{m}", "label": "parent of"} for m in members]
    nodes += [{"id": f"AGR-{cp}-ISDA", "label": "ISDA 2002 Master", "type": "Agreement"}, {"id": f"CSA-{cp}", "label": "Credit support annex", "type": "CSA"}]
    edges += [{"from": f"CP-{cp}", "to": f"AGR-{cp}-ISDA", "label": "signed"}, {"from": f"AGR-{cp}-ISDA", "to": f"CSA-{cp}", "label": "secured by"}]
    for s in sorted(sets, key=lambda x: x["nettingSetId"]):
        nodes.append({"id": s["nettingSetId"], "label": f"{s['nettingSetId']} · {s['tradeCount']} trade{'' if s['tradeCount'] == 1 else 's'}", "type": "Netting set"})
        edges.append({"from": f"AGR-{cp}-ISDA", "to": s["nettingSetId"], "label": "nets"})
    return {"nodes": nodes, "edges": edges}


def build(trades: dict) -> dict[str, dict[str, dict]]:
    from collections import defaultdict as dd
    docs: dict[str, dict[str, dict]] = dd(dict)
    credit_results(trades, docs)
    market_results(trades, docs)
    reference(trades, docs)
    return dict(docs)
