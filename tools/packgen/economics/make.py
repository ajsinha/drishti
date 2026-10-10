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

"""The economics pack: economies, macro indicators with release calendars, central-bank decisions, forecasts with
scenarios, bilateral trade flows, labour markets, fiscal positions and consumer-price baskets. The economies are real;
the figures are illustrative, not official statistics (the market-data pack's feeds bring real rates).

    python3 tools/packgen/economics/make.py            write packs/economics
    python3 tools/packgen/economics/make.py --check    fail if the pack differs from what would be written
    uv run --with deltalake --with pyarrow --with pyyaml python tools/packgen/economics/make.py --lake data/delta
"""
from __future__ import annotations

import hashlib
import random
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "common"))
sys.path.insert(0, str(HERE.parent / "banking"))
import packbuild as PB  # noqa: E402
import taxonomy as T  # noqa: E402
from risk_model import Kind, Panel as P  # noqa: E402

G = "Economics"
AS_OF = date(2026, 9, 30)
# code: (name, currency, GDP USD tn, growth, inflation, unemployment, policy rate, debt/GDP, central bank, bank code)
ECONOMIES = {"US": ("United States", "USD", 30.1, 0.019, 0.028, 0.043, 0.0400, 1.22, "Federal Reserve", "FED"),
             "EA": ("Euro area", "EUR", 17.4, 0.011, 0.021, 0.063, 0.0200, 0.88, "European Central Bank", "ECB"),
             "UK": ("United Kingdom", "GBP", 3.8, 0.012, 0.034, 0.047, 0.0375, 1.01, "Bank of England", "BOE"),
             "JP": ("Japan", "JPY", 4.2, 0.007, 0.024, 0.025, 0.0075, 2.35, "Bank of Japan", "BOJ"),
             "CN": ("China", "CNY", 19.6, 0.046, 0.004, 0.051, 0.0300, 0.92, "People's Bank of China", "PBOC"),
             "IN": ("India", "INR", 4.4, 0.064, 0.041, 0.078, 0.0550, 0.82, "Reserve Bank of India", "RBI"),
             "BR": ("Brazil", "BRL", 2.3, 0.021, 0.049, 0.062, 0.1450, 0.78, "Banco Central do Brasil", "BCB")}
SECTORS = [("Services", 0.62), ("Industry", 0.22), ("Construction", 0.06), ("Agriculture", 0.04), ("Government", 0.06)]
FUNCTIONS = ["Social protection", "Health", "Education", "Defence", "Interest", "Other"]
BASKET = [("Housing and utilities", 0.24), ("Food", 0.14), ("Transport", 0.15), ("Recreation", 0.09), ("Health", 0.07), ("Clothing", 0.05),
          ("Communication", 0.04), ("Restaurants and hotels", 0.09), ("Other goods and services", 0.13)]
PAIRS = [("US", "CN"), ("US", "EA"), ("EA", "CN"), ("UK", "EA"), ("JP", "CN"), ("IN", "US"), ("BR", "CN")]

KINDS = [
    Kind("economy", "ECON", "ECON-", "Economy", G, "An economy: output, growth, prices, jobs, policy rate and debt, and output by sector.", "economyId",
         [("Economy", "$.name", None, None, False), ("GDP (USD tn)", "$.gdpUsdTn", "price2", None, False), ("Real growth", "$.growth", "pct2", "sign", True),
          ("Inflation", "$.inflation", "pct2", None, False), ("Unemployment", "$.unemployment", "pct2", None, False), ("Policy rate", "$.policyRate", "pct2", None, False),
          ("Debt / GDP", "$.debtToGdp", "pct0", None, False)],
         [P("line", "growthPath", "Real GDP growth, quarter on year", "$.growthPath", x="quarter", y="growth", fmt="pct2", key="F2"),
          P("hbar", "sectors", "Output by sector", "$.sectors", label="sector", value="share", fmt="pct0", key="F3", area="right")],
         links={"centralBank": ("central-bank-decision", "Last policy decision"), "labour": ("labour-market", "Labour market"),
                "fiscal": ("fiscal-position", "Fiscal position"), "basket": ("price-basket", "Consumer prices"), "outlook": ("economic-forecast", "Forecast")},
         badge="fmt($.growth, 'pct2') + ' growth'"),
    Kind("macro-indicator", "MACRO", "MACRO-", "Macro indicator", G, "A released statistic: history and the release calendar with consensus and surprise.", "indicatorId",
         [("Indicator", "$.name", None, None, False), ("Latest", "$.latest", "price2", None, True), ("Unit", "$.unit", None, None, False),
          ("Previous", "$.previous", "price2", None, False), ("Next release", "$.nextRelease", "date", None, False)],
         [P("line", "history", "History", "$.history", x="period", y="value", fmt="price2", key="F2"),
          P("table", "releases", "Recent releases", "$.releases", [("Released", "@.date", "date", None, False), ("Period", "@.period", None, None, False),
            ("Actual", "@.actual", "price2", None, True), ("Consensus", "@.consensus", "price2", None, False), ("Surprise", "@.surprise", "signed2", "sign", False)], key="F3")],
         links={"economy": ("economy", "Economy")}, badge="fmt($.latest, 'price2') + ' ' + $.unit"),
    Kind("central-bank-decision", "CBD", "CBD-", "Central-bank decision", G, "A monetary-policy decision: the rate, the vote, the guidance, and the path of past decisions.", "decisionId",
         [("Central bank", "$.bank", None, None, False), ("Date", "$.date", "date", None, False), ("Decision", "$.decision", None, None, True),
          ("Policy rate", "$.rate", "pct2", None, False), ("Vote", "$.vote", None, None, False)],
         [P("ladder", "path", "Recent decisions", "$.path", [("Date", "@.date", "date", None, False), ("Decision", "@.decision", None, None, False),
            ("Rate", "@.rate", "pct2", None, False)], key="F2"),
          P("kv", "statement", "Statement", "$.statement", area="right")],
         links={"economy": ("economy", "Economy")}, badge="$.decision"),
    Kind("economic-forecast", "FCST", "FCST-", "Economic forecast", G, "A forecast for the next two years with baseline, upside and downside scenarios.", "forecastId",
         [("Economy", "$.economyName", None, None, False), ("Vintage", "$.vintage", None, None, False), ("Growth 2027", "$.growth2027", "pct2", "sign", True),
          ("Inflation 2027", "$.inflation2027", "pct2", None, False), ("Recession probability", "$.recessionProbability", "pct0", None, False)],
         [P("table", "scenarios", "Scenarios", "$.scenarios", [("Scenario", "@.scenario", None, None, False), ("Weight", "@.weight", "pct0", None, False),
            ("Growth 2026", "@.growth2026", "pct2", "sign", False), ("Growth 2027", "@.growth2027", "pct2", "sign", True), ("Inflation 2027", "@.inflation2027", "pct2", None, False),
            ("Unemployment 2027", "@.unemployment2027", "pct2", None, False)], key="F2"),
          P("line", "path", "Baseline quarterly growth", "$.path", x="quarter", y="growth", fmt="pct2", area="right")],
         links={"economy": ("economy", "Economy")}),
    Kind("trade-flow", "TFLOW", "TFLOW-", "Trade flow", G, "Goods trade between two economies: exports, imports, balance and the main products.", "flowId",
         [("From", "$.reporterName", None, None, False), ("To", "$.partnerName", None, None, False), ("Exports (USD bn)", "$.exports", "amount0", None, False),
          ("Imports (USD bn)", "$.imports", "amount0", None, False), ("Balance (USD bn)", "$.balance", "signed0", "sign", True), ("Average tariff", "$.tariff", "pct2", None, False)],
         [P("hbar", "products", "Main exports (USD bn)", "$.products", label="product", value="value", fmt="amount0", key="F2"),
          P("line", "balanceHistory", "Balance (USD bn)", "$.balanceHistory", x="year", y="balance", fmt="signed0", area="right")],
         links={"economy": ("economy", "Reporter"), "partner": ("economy", "Partner")}),
    Kind("labour-market", "LABR", "LABR-", "Labour market", G, "Employment, participation, wages and vacancies, and jobs by sector.", "labourId",
         [("Economy", "$.economyName", None, None, False), ("Unemployment", "$.unemployment", "pct2", None, True), ("Participation", "$.participation", "pct2", None, False),
          ("Wage growth", "$.wageGrowth", "pct2", None, False), ("Vacancies per unemployed", "$.vacancyRatio", "price2", None, False)],
         [P("line", "unemploymentPath", "Unemployment rate", "$.unemploymentPath", x="month", y="rate", fmt="pct2", key="F2"),
          P("table", "bySector", "Employment by sector", "$.bySector", [("Sector", "@.sector", None, None, False), ("Jobs (m)", "@.jobsM", "price2", None, False),
            ("Change (1y)", "@.change", "pct2", "sign", True)], key="F3")],
         links={"economy": ("economy", "Economy")}),
    Kind("fiscal-position", "FISC", "FISC-", "Fiscal position", G, "Government revenue, spending, deficit and debt, and spending by function.", "fiscalId",
         [("Economy", "$.economyName", None, None, False), ("Revenue / GDP", "$.revenue", "pct2", None, False), ("Spending / GDP", "$.spending", "pct2", None, False),
          ("Balance / GDP", "$.balance", "pct2", "sign", True), ("Debt / GDP", "$.debt", "pct0", None, False), ("Interest / revenue", "$.interestBurden", "pct2", None, False)],
         [P("hbar", "functions", "Spending by function (% of GDP)", "$.functions", label="function", value="share", fmt="pct2", key="F2"),
          P("line", "debtPath", "Debt / GDP", "$.debtPath", x="year", y="debt", fmt="pct0", area="right")],
         links={"economy": ("economy", "Economy")}),
    Kind("price-basket", "CPIB", "CPIB-", "Consumer-price basket", G, "Headline and core inflation and each basket component's weight, inflation and contribution.", "basketId",
         [("Economy", "$.economyName", None, None, False), ("Headline", "$.headline", "pct2", None, True), ("Core", "$.core", "pct2", None, False),
          ("Services", "$.services", "pct2", None, False), ("Goods", "$.goods", "pct2", None, False)],
         [P("table", "components", "Components", "$.components", [("Component", "@.component", None, None, False), ("Weight", "@.weight", "pct0", None, False),
            ("Inflation (y/y)", "@.inflation", "pct2", "sign", False), ("Contribution (pp)", "@.contribution", "price2", "sign", True)], key="F2"),
          P("hbar", "contributions", "Contribution to headline (pp)", "$.components", label="component", value="contribution", fmt="price2", tone="sign", area="right")],
         links={"economy": ("economy", "Economy")}),
]


def rng(key: str) -> random.Random:
    return random.Random(int(hashlib.sha256(key.encode()).hexdigest()[:12], 16))


def quarters(n: int) -> list[str]:
    y, q, out = 2026, 3, []
    for _ in range(n):
        out.append(f"{y}Q{q}")
        y, q = (y, q - 1) if q > 1 else (y - 1, 4)
    return out[::-1]


def months(n: int) -> list[str]:
    y, m, out = AS_OF.year, AS_OF.month - 1, []
    for _ in range(n):
        out.append(f"{y}-{m:02d}")
        y, m = (y, m - 1) if m > 1 else (y - 1, 12)
    return out[::-1]


def build() -> dict[str, dict[str, dict]]:
    docs = {k.kind: {} for k in KINDS}
    meta = {"source": "macro-database", "generation": 1, "illustrative": True}
    for code, (name, ccy, gdp, g, infl, u, rate, debt, bank, bcode) in ECONOMIES.items():
        r = rng(code)
        docs["economy"][f"ECON-{code}"] = {
            "economyId": f"ECON-{code}", "name": name, "currency": ccy, "gdpUsdTn": gdp, "growth": g, "inflation": infl, "unemployment": u, "policyRate": rate, "debtToGdp": debt,
            "growthPath": [{"quarter": q, "growth": round(g + r.gauss(0, 0.006) + (-0.045 if q == "2020Q2" else 0), 4)} for q in quarters(16)],
            "sectors": [{"sector": s, "share": round(w * r.uniform(0.85, 1.15), 3)} for s, w in SECTORS],
            "centralBank": f"CBD-{bcode}-2026-09", "labour": f"LABR-{code}", "fiscal": f"FISC-{code}", "basket": f"CPIB-{code}", "outlook": f"FCST-{code}-2026Q3", "_meta": meta}
        # indicators
        for key, iname, unit, base, vol in [("CPI", "Consumer price inflation", "% y/y", infl * 100, 0.2), ("GDP", "Real GDP growth", "% y/y", g * 100, 0.3),
                                            ("UNEMP", "Unemployment rate", "%", u * 100, 0.08), ("PMI", "Manufacturing PMI", "index", 50.0 + r.uniform(-3, 3), 0.8)]:
            rr = rng(code + key)
            periods = quarters(12) if key == "GDP" else months(24)
            v, hist = base + rr.gauss(0, vol * 3), []
            for p in periods:
                v += (base - v) * 0.15 + rr.gauss(0, vol)
                hist.append({"period": p, "value": round(v, 2)})
            rel = []
            for h in hist[-6:]:
                cons = round(h["value"] + rr.gauss(0, vol * 0.6), 2)
                rel.append({"date": _release_date(h["period"]), "period": h["period"], "actual": h["value"], "consensus": cons, "surprise": round(h["value"] - cons, 2)})
            docs["macro-indicator"][f"MACRO-{code}-{key}"] = {
                "indicatorId": f"MACRO-{code}-{key}", "name": f"{name} · {iname}", "latest": hist[-1]["value"], "unit": unit, "previous": hist[-2]["value"],
                "nextRelease": "2026-10-15" if key != "GDP" else "2026-10-30", "history": hist, "releases": rel[::-1], "economy": f"ECON-{code}", "_meta": meta}
        # central bank
        path, rt = [], rate + 0.0125
        for k, d in enumerate(["2025-12-10", "2026-01-28", "2026-03-18", "2026-04-29", "2026-06-17", "2026-07-29", "2026-09-16"]):
            move = -0.0025 if (rt > rate and r.random() < 0.45) or k == 6 and rt > rate else 0.0
            rt = round(rt + move, 4)
            path.append({"date": d, "decision": "Cut 25 bp" if move else "Hold", "rate": rt})
        path[-1]["rate"] = rate
        dissent = r.choice([0, 0, 1, 2])
        docs["central-bank-decision"][f"CBD-{bcode}-2026-09"] = {
            "decisionId": f"CBD-{bcode}-2026-09", "bank": bank, "date": path[-1]["date"], "decision": path[-1]["decision"], "rate": rate,
            "vote": f"{9 - dissent}-{dissent}" if code != "CN" else "Committee (no published vote)", "path": path[::-1],
            "statement": {"guidance": r.choice(["Data dependent; further easing if inflation keeps falling", "Policy is well positioned; no pre-set path",
                                                 "Gradual and careful approach to further cuts"]), "balanceSheet": r.choice(["Run-off continues", "Run-off slowed", "Unchanged"]),
                          "inflationTarget": "2%" if code not in ("IN", "BR", "CN") else {"IN": "4% ± 2", "BR": "3% ± 1.5", "CN": "around 2%"}[code], "nextMeeting": "2026-10-29"},
            "economy": f"ECON-{code}", "_meta": meta}
        # forecast
        scen = [("Baseline", 0.6, 0.0, 0.0, 0.0), ("Upside: productivity boom", 0.15, 0.008, -0.003, -0.004), ("Downside: tariff escalation", 0.25, -0.016, 0.007, 0.011)]
        docs["economic-forecast"][f"FCST-{code}-2026Q3"] = {
            "forecastId": f"FCST-{code}-2026Q3", "economyName": name, "vintage": "2026 Q3", "growth2027": round(g + 0.001, 4), "inflation2027": round(infl - 0.002, 4),
            "recessionProbability": round(r.uniform(0.12, 0.35), 2),
            "scenarios": [{"scenario": s, "weight": w, "growth2026": round(g + dg / 2, 4), "growth2027": round(g + 0.001 + dg, 4), "inflation2027": round(infl - 0.002 + di, 4),
                           "unemployment2027": round(u + du, 4)} for s, w, dg, di, du in scen],
            "path": [{"quarter": q, "growth": round(g + 0.001 + r.gauss(0, 0.002), 4)} for q in ["2026Q4", "2027Q1", "2027Q2", "2027Q3", "2027Q4", "2028Q1"]],
            "economy": f"ECON-{code}", "_meta": meta}
        # labour
        rr = rng("lab" + code)
        workforce = gdp * 5.5 if code not in ("CN", "IN") else {"CN": 740, "IN": 520}[code]
        docs["labour-market"][f"LABR-{code}"] = {
            "labourId": f"LABR-{code}", "economyName": name, "unemployment": u, "participation": round(rr.uniform(0.58, 0.66), 4), "wageGrowth": round(infl + rr.uniform(0.002, 0.018), 4),
            "vacancyRatio": round(rr.uniform(0.6, 1.3), 2),
            "unemploymentPath": [{"month": m, "rate": round(u + rr.gauss(0, 0.001) + 0.0002 * (k - 12), 4)} for k, m in enumerate(months(24))],
            "bySector": [{"sector": s, "jobsM": round(workforce * w, 2), "change": round(rr.gauss(0.008, 0.012), 4)} for s, w in SECTORS], "economy": f"ECON-{code}", "_meta": meta}
        # fiscal
        rr = rng("fis" + code)
        rev = rr.uniform(0.18, 0.44)
        bal = -rr.uniform(0.01, 0.07)
        spend = rev - bal
        interest = debt * rr.uniform(0.015, 0.035) if code != "BR" else debt * 0.09
        shares = [0.34, 0.2, 0.12, 0.05, 0, 0.29]
        docs["fiscal-position"][f"FISC-{code}"] = {
            "fiscalId": f"FISC-{code}", "economyName": name, "revenue": round(rev, 4), "spending": round(spend, 4), "balance": round(bal, 4), "debt": debt,
            "interestBurden": round(interest / rev, 4),
            "functions": [{"function": f, "share": round(interest if f == "Interest" else (spend - interest) * s, 4)} for f, s in zip(FUNCTIONS, shares)],
            "debtPath": [{"year": str(y), "debt": round(debt - (2026 - y) * (-bal - 0.01) * rr.uniform(0.6, 1.2), 3)} for y in range(2016, 2027)],
            "economy": f"ECON-{code}", "_meta": meta}
        # prices
        rr = rng("cpi" + code)
        comps = []
        for c, w in BASKET:
            ci = round(infl + rr.gauss(0, 0.012) + (0.012 if c in ("Housing and utilities", "Restaurants and hotels") else 0), 4)
            comps.append({"component": c, "weight": w, "inflation": ci, "contribution": round(w * ci * 100, 2)})
        head = round(sum(x["contribution"] for x in comps) / 100, 4)
        docs["price-basket"][f"CPIB-{code}"] = {"basketId": f"CPIB-{code}", "economyName": name, "headline": head, "core": round(head + rr.uniform(-0.004, 0.006), 4),
                                                 "services": round(head + rr.uniform(0.005, 0.015), 4), "goods": round(head - rr.uniform(0.005, 0.015), 4), "components": comps,
                                                 "economy": f"ECON-{code}", "_meta": meta}
    for a, b in PAIRS:
        r = rng(a + b)
        ex = round(ECONOMIES[a][2] * ECONOMIES[b][2] ** 0.5 * r.uniform(5, 14))
        im = round(ex * r.uniform(0.5, 2.6))
        hist = [{"year": str(y), "balance": round((ex - im) * r.uniform(0.7, 1.2))} for y in range(2016, 2026)]
        docs["trade-flow"][f"TFLOW-{a}-{b}"] = {
            "flowId": f"TFLOW-{a}-{b}", "reporterName": ECONOMIES[a][0], "partnerName": ECONOMIES[b][0], "exports": ex, "imports": im, "balance": ex - im,
            "tariff": round(r.uniform(0.02, 0.22) if "US" in (a, b) and "CN" in (a, b) else r.uniform(0.01, 0.06), 4),
            "products": [{"product": p, "value": round(ex * w)} for p, w in zip(r.sample(["Machinery", "Electronics", "Vehicles", "Chemicals", "Agricultural products",
                                                                                          "Energy", "Pharmaceuticals", "Metals"], 5), [0.28, 0.2, 0.14, 0.1, 0.07])],
            "balanceHistory": hist, "economy": f"ECON-{a}", "partner": f"ECON-{b}", "_meta": meta}
    return docs


def _release_date(period: str) -> str:
    if "Q" in period:
        y, q = period.split("Q")
        m = int(q) * 3 + 1
        y = int(y) + (m > 12)
        return date(y, (m - 1) % 12 + 1, 28).isoformat()
    y, m = map(int, period.split("-"))
    y, m = (y + 1, 1) if m == 12 else (y, m + 1)
    return date(y, m, 14).isoformat()


def spec() -> PB.PackSpec:
    return PB.PackSpec(
        sample=True,
        code="ECO",
        columns={'economy': ['name', 'currency', 'gdpUsdTn', 'growth', 'inflation', 'unemployment', 'policyRate'], 'macro-indicator': ['name', 'latest', 'unit', 'previous', 'nextRelease'], 'central-bank-decision': ['bank', 'date', 'decision', 'rate', 'vote'], 'trade-flow': ['reporterName', 'partnerName', 'exports', 'imports', 'balance'], 'economic-forecast': ['economyName', 'vintage', 'growth2027', 'inflation2027', 'recessionProbability']},  # key fields shown beside each entity in pick lists
        name="economics", title="Economics", requires=[], generator="tools/packgen/economics/make.py",
        description="Economies, macro indicators and releases, central-bank decisions, forecasts, trade flows, labour markets, fiscal positions and price baskets.",
        domains={"macro": KINDS},
        examples=[("ECON ECON-US", "Economy · growth path, output by sector"), ("MACRO MACRO-EA-CPI", "Indicator · history and release surprises"),
                  ("CBD CBD-FED-2026-09", "Central-bank decision · rate path and guidance"), ("FCST FCST-UK-2026Q3", "Forecast · baseline, upside and downside"),
                  ("TFLOW TFLOW-US-CN", "Trade flow · balance and main exports"), ("CPIB CPIB-JP", "Consumer prices · contributions by component")],
        overview="An economy links to its central bank's latest decision, "
                 "labour market, fiscal position, price basket and forecast; indicators, decisions and trade flows link back to their economies. "
                 "For real rates, the market-data pack's feeds (SOFR, €STR, Treasury curve, FRED) can run alongside.",
        roles={"economist": {"kinds": [k.kind for k in KINDS], "raw": True}})


if __name__ == "__main__":
    PB.main(spec(), build(), T.graph_fields())
