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

"""The liquidity-risk pack: LCR, NSFR, maturity ladders, HQLA holdings, funding sources, liquidity stress and intraday
liquidity, per legal entity, generated from the banking packs' data so entities, counterparties and bonds line up.

    python3 tools/packgen/liquidity/make.py            write config/packs/liquidity-risk
    python3 tools/packgen/liquidity/make.py --check    fail if the pack differs from what would be written
    uv run --with deltalake --with pyarrow --with pyyaml python tools/packgen/liquidity/make.py --lake data/delta
"""
from __future__ import annotations

import math
import random
import sys
from datetime import date, timedelta
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "common"))
sys.path.insert(0, str(HERE.parent / "banking"))
import packbuild as PB  # noqa: E402
import make_data as BANK  # noqa: E402
import taxonomy as T  # noqa: E402
from risk_model import Kind, Panel as P  # noqa: E402

G = "Liquidity"
AS_OF = date(2026, 9, 30)
LE_CCY = {"LE-NY": ["USD"], "LE-LDN": ["GBP", "USD", "EUR"], "LE-FRA": ["EUR", "USD"], "LE-TKY": ["JPY", "USD"]}
BUCKETS = ["O/N", "2D-1W", "1W-1M", "1M-3M", "3M-6M", "6M-1Y", "1Y-2Y", ">2Y"]

KINDS = [
    Kind("lcr", "LCR", "LCR-", "Liquidity coverage ratio", G, "Basel III LCR: high-quality liquid assets against 30-day stressed net outflows.", "lcrId",
         [("Entity", "$.legalEntityName", None, None, False), ("LCR", "$.lcr", "pct0", None, True), ("HQLA", "$.hqla", "compact", None, False),
          ("Net outflows (30d)", "$.netOutflows", "compact", None, False), ("Minimum", "$.minimum", "pct0", None, False),
          ("Buffer over minimum", "$.buffer", "compact", "sign", False), ("As of", "$.asOf", "date", None, False)],
         [P("hbar", "hqla", "HQLA after haircuts by level", "$.hqlaByLevel", label="level", value="value", fmt="compact", key="F2", code="HQLA"),
          P("table", "outflows", "Stressed outflows and inflows (30 days)", "$.flows", [("Category", "@.category", None, None, False),
            ("Balance", "@.balance", "compact", None, False), ("Rate", "@.rate", "pct0", None, False), ("Flow", "@.flow", "signed0", "sign", True)], key="F3"),
          P("line", "trend", "LCR, last 30 business days", "$.trend", x="date", y="lcr", fmt="pct0", area="right")],
         links={"legalEntity": ("legal-entity", "Legal entity")}, badge="fmt($.lcr, 'pct0') + ' LCR'", live={"lcr": 0.004}),
    Kind("nsfr", "NSFR", "NSFR-", "Net stable funding ratio", G, "Basel III NSFR: available against required stable funding over one year.", "nsfrId",
         [("Entity", "$.legalEntityName", None, None, False), ("NSFR", "$.nsfr", "pct0", None, True), ("ASF", "$.asfTotal", "compact", None, False),
          ("RSF", "$.rsfTotal", "compact", None, False), ("Minimum", "$.minimum", "pct0", None, False)],
         [P("table", "asf", "Available stable funding", "$.asf", [("Source", "@.category", None, None, False), ("Amount", "@.amount", "compact", None, False),
            ("Factor", "@.factor", "pct0", None, False), ("Weighted", "@.weighted", "compact", None, True)], key="F2"),
          P("table", "rsf", "Required stable funding", "$.rsf", [("Asset", "@.category", None, None, False), ("Amount", "@.amount", "compact", None, False),
            ("Factor", "@.factor", "pct0", None, False), ("Weighted", "@.weighted", "compact", None, True)], key="F3")],
         links={"legalEntity": ("legal-entity", "Legal entity")}, badge="fmt($.nsfr, 'pct0') + ' NSFR'"),
    Kind("maturity-ladder", "MLAD", "MLAD-", "Maturity ladder", G, "Contractual inflows and outflows by time bucket for one entity and currency, with the cumulative gap.", "ladderId",
         [("Entity", "$.legalEntityName", None, None, False), ("Currency", "$.currency", None, None, False),
          ("Cumulative gap (1M)", "$.gap1m", "signed0", "sign", True), ("Survival (days)", "$.survivalDays", "amount0", None, False)],
         [P("hbar", "net", "Net flow by bucket", "$.buckets", label="bucket", value="net", fmt="signed0", tone="sign", key="F2", code="GAP"),
          P("table", "buckets", "Buckets", "$.buckets", [("Bucket", "@.bucket", None, None, False), ("Inflows", "@.inflows", "compact", None, False),
            ("Outflows", "@.outflows", "compact", None, False), ("Net", "@.net", "signed0", "sign", False), ("Cumulative", "@.cumulative", "signed0", "sign", False)], key="F3")],
         links={"legalEntity": ("legal-entity", "Legal entity")}),
    Kind("hqla-holding", "HQLA", "HQLA-", "HQLA holding", G, "A security held in the liquidity buffer: its HQLA level, haircut and liquidity value.", "holdingId",
         [("Security", "$.security", None, None, False), ("Level", "$.level", None, None, False), ("Market value", "$.marketValue", "amount0", None, False),
          ("Haircut", "$.haircut", "pct0", None, False), ("Liquidity value", "$.liquidityValue", "amount0", None, True), ("Encumbered", "$.encumbered", None, None, False)],
         [P("kv", "details", "Holding", "$.details", key="F2")],
         links={"bond": ("bond", "Bond"), "legalEntity": ("legal-entity", "Legal entity")}),
    Kind("funding-source", "FUND", "FUND-", "Funding source", G, "A source of funding: deposits, repo or issuance, with its LCR run-off assumption.", "fundingId",
         [("Provider", "$.counterpartyName", None, None, False), ("Type", "$.type", None, None, False), ("Amount", "$.amount", "amount0", None, True),
          ("Currency", "$.currency", None, None, False), ("Maturity", "$.maturity", "date", None, False), ("Run-off", "$.runOffRate", "pct0", None, False)],
         [P("kv", "terms", "Terms", "$.terms", key="F2")],
         links={"counterparty": ("counterparty", "Counterparty"), "legalEntity": ("legal-entity", "Legal entity")}),
    Kind("liquidity-stress", "LST", "LST-", "Liquidity stress result", G, "How long an entity survives a liquidity stress, day by day.", "resultId",
         [("Scenario", "$.scenarioName", None, None, False), ("Entity", "$.legalEntityName", None, None, False),
          ("Survival (days)", "$.survivalDays", "amount0", None, True), ("Lowest position", "$.minimumPosition", "signed0", "sign", False),
          ("Appetite (days)", "$.appetiteDays", "amount0", None, False)],
         [P("line", "path", "Cumulative liquidity position (90 days)", "$.path", x="day", y="position", fmt="compact", key="F2", code="PATH"),
          P("hbar", "drivers", "Outflow drivers", "$.drivers", label="driver", value="outflow", fmt="compact", area="right")],
         links={"legalEntity": ("legal-entity", "Legal entity")}, badge="$.survivalDays + ' days'"),
    Kind("intraday-liquidity", "IDL", "IDL-", "Intraday liquidity", G, "Intraday use of liquidity in a payment system: usage through the day and the largest payments.", "reportId",
         [("Entity", "$.legalEntityName", None, None, False), ("Currency", "$.currency", None, None, False), ("Peak usage", "$.peakUsage", "compact", None, True),
          ("Available", "$.available", "compact", None, False), ("Usage", "$.usagePct", "pct0", None, False)],
         [P("line", "usage", "Liquidity used through the day", "$.usage", x="time", y="used", fmt="compact", key="F2"),
          P("table", "payments", "Largest payments", "$.largePayments", [("Time", "@.time", None, None, False), ("Counterparty", "@.counterparty", None, None, False),
            ("Amount", "@.amount", "signed0", "sign", False)], key="F3")],
         links={"legalEntity": ("legal-entity", "Legal entity")}, live={"peakUsage": 250000}),
]


def rng(key: str) -> random.Random:
    import hashlib
    return random.Random(int(hashlib.sha256(key.encode()).hexdigest()[:12], 16))


def business_days(n: int) -> list[date]:
    out, d = [], AS_OF
    while len(out) < n:
        if d.weekday() < 5:
            out.append(d)
        d -= timedelta(days=1)
    return sorted(out)


def build(bank: dict) -> dict[str, dict[str, dict]]:
    docs = {k.kind: {} for k in KINDS}
    les = bank["legal-entity"]
    govt = [b for b in bank["bond"].values() if b["terms"]["seniority"] == "Sovereign"]
    corp = [b for b in bank["bond"].values() if b["terms"]["seniority"] != "Sovereign"]
    cps = list(bank["counterparty"].values())
    for le, led in les.items():
        r = rng(le)
        name, scale = led["name"], {"LE-NY": 1.0, "LE-LDN": 0.8, "LE-FRA": 0.45, "LE-TKY": 0.3}[le]
        # HQLA holdings: sovereigns (level 1), covered and corporates (2A / 2B)
        holdings = []
        for i, b in enumerate(r.sample(govt, 5) + r.sample(corp, 4)):
            level = "Level 1" if b in govt else r.choice(["Level 2A", "Level 2B"])
            hc = {"Level 1": 0.0, "Level 2A": 0.15, "Level 2B": 0.5}[level]
            mv = round(r.uniform(0.3, 2.5) * 1e9 * scale * (1.6 if level == "Level 1" else 0.4), -5)
            enc = r.random() < 0.15
            hid = f"HQLA-{le[3:]}-{i + 1:02d}"
            h = {"holdingId": hid, "security": f"{b['issuerName']} {b['coupon'] * 100:.3f}% {b['maturity'][:4]}", "level": level, "marketValue": mv,
                 "haircut": hc, "liquidityValue": 0 if enc else round(mv * (1 - hc)), "encumbered": "Yes (pledged)" if enc else "No",
                 "bond": b["isin"], "legalEntity": le,
                 "details": {"isin": b["isinCode"], "currency": b["terms"]["currency"], "price": b["price"], "custodian": r.choice(["Fed", "Euroclear", "Clearstream", "BoE"]),
                             "operationalRequirements": "Met", "centralBankEligible": level != "Level 2B"},
                 "_meta": {"source": "treasury-alm", "generation": 1}}
            docs["hqla-holding"][hid] = h
            holdings.append(h)
        by_level = {}
        for h in holdings:
            by_level[h["level"]] = by_level.get(h["level"], 0) + h["liquidityValue"]
        cash = round(r.uniform(4, 9) * 1e9 * scale, -6)
        by_level["Level 1 (central bank reserves)"] = cash
        hqla = sum(by_level.values())
        flows = []
        outflow = 0
        for cat, bal, rate in [("Retail deposits, stable", 22e9, 0.05), ("Retail deposits, less stable", 9e9, 0.10), ("Operational deposits", 14e9, 0.25),
                               ("Non-operational wholesale", 11e9, 0.40), ("Secured funding (non-level-1 collateral)", 7e9, 0.25),
                               ("Derivative outflows", 1.8e9, 1.0), ("Committed credit facilities", 6e9, 0.10)]:
            b = round(bal * scale * r.uniform(0.8, 1.2), -6)
            f = -round(b * rate)
            outflow += -f
            flows.append({"category": cat, "balance": b, "rate": rate, "flow": f})
        inflow = 0
        for cat, bal, rate in [("Performing loans maturing", 5e9, 0.5), ("Reverse repo (non-level-1)", 4e9, 0.5), ("Derivative inflows", 1.5e9, 1.0)]:
            b = round(bal * scale * r.uniform(0.8, 1.2), -6)
            f = round(b * rate)
            inflow += f
            flows.append({"category": cat, "balance": b, "rate": rate, "flow": f})
        net = outflow - min(inflow, 0.75 * outflow)
        lcr = hqla / net
        trend = [{"date": d.isoformat(), "lcr": round(lcr * (1 + 0.03 * math.sin(i / 3) - 0.002 * (29 - i)), 4)} for i, d in enumerate(business_days(30))]
        trend[-1]["lcr"] = round(lcr, 4)
        docs["lcr"][f"LCR-{le[3:]}"] = {"lcrId": f"LCR-{le[3:]}", "legalEntityName": name, "lcr": round(lcr, 4), "hqla": round(hqla), "netOutflows": round(net),
                                        "minimum": 1.0, "buffer": round(hqla - net), "asOf": AS_OF.isoformat(),
                                        "hqlaByLevel": [{"level": k, "value": v} for k, v in sorted(by_level.items())], "flows": flows, "trend": trend,
                                        "legalEntity": le, "_meta": {"source": "treasury-alm", "generation": 1, "live": True, "walk": {"lcr": 0.004}}}
        asf = [{"category": c, "amount": round(a * scale), "factor": f, "weighted": round(a * scale * f)} for c, a, f in
               [("Regulatory capital", 18e9, 1.0), ("Retail deposits, stable", 22e9, 0.95), ("Retail deposits, less stable", 9e9, 0.9),
                ("Wholesale funding > 1 year", 16e9, 1.0), ("Operational deposits", 14e9, 0.5), ("Wholesale funding < 1 year", 11e9, 0.5)]]
        rsf = [{"category": c, "amount": round(a * scale), "factor": f, "weighted": round(a * scale * f)} for c, a, f in
               [("Level 1 assets", 12e9, 0.0), ("Level 2A assets", 3e9, 0.15), ("Loans to non-financials > 1 year", 30e9, 0.65),
                ("Loans to financials < 6 months", 8e9, 0.10), ("Derivative assets net", 2e9, 1.0), ("Other assets", 6e9, 1.0)]]
        asf_t, rsf_t = sum(x["weighted"] for x in asf), sum(x["weighted"] for x in rsf)
        docs["nsfr"][f"NSFR-{le[3:]}"] = {"nsfrId": f"NSFR-{le[3:]}", "legalEntityName": name, "nsfr": round(asf_t / rsf_t, 4), "asfTotal": asf_t,
                                          "rsfTotal": rsf_t, "minimum": 1.0, "asf": asf, "rsf": rsf, "legalEntity": le,
                                          "_meta": {"source": "treasury-alm", "generation": 1}}
        for ccy in LE_CCY[le]:
            rr = rng(le + ccy)
            cum, buckets, survival = 0, [], None
            for i, bk in enumerate(BUCKETS):
                inflow_b = round(rr.uniform(0.4, 2.0) * 1e9 * scale * (1 + i * 0.3), -5)
                outflow_b = round(rr.uniform(0.6, 2.4) * 1e9 * scale * (1 + i * 0.25), -5)
                cum += inflow_b - outflow_b
                buckets.append({"bucket": bk, "inflows": inflow_b, "outflows": outflow_b, "net": inflow_b - outflow_b, "cumulative": cum})
            cum_hqla = hqla / len(LE_CCY[le])
            days = [1, 7, 30, 90, 180, 365, 730, 1095]
            for d_, b in zip(days, buckets):
                if survival is None and cum_hqla + b["cumulative"] < 0:
                    survival = d_
            lid = f"MLAD-{le[3:]}-{ccy}"
            docs["maturity-ladder"][lid] = {"ladderId": lid, "legalEntityName": name, "currency": ccy, "gap1m": buckets[2]["cumulative"],
                                            "survivalDays": survival or 365, "buckets": buckets, "legalEntity": le,
                                            "_meta": {"source": "treasury-alm", "generation": 1}}
            if ccy in ("USD", "EUR"):
                peak = round(rr.uniform(0.8, 3.5) * 1e9 * scale, -6)
                avail = round(peak * rr.uniform(1.8, 3.2), -6)
                usage, used = [], 0.0
                for h in range(7, 19):
                    used = max(0, used + rr.gauss(peak / 8, peak / 6))
                    usage.append({"time": f"{h:02d}:00", "used": round(min(used, peak))})
                pays = [{"time": f"{rr.randint(7, 18):02d}:{rr.randint(0, 59):02d}", "counterparty": rr.choice(cps)["name"],
                         "amount": round(rr.choice([-1, 1]) * rr.uniform(50, 900) * 1e6, -5)} for _ in range(8)]
                iid = f"IDL-{le[3:]}-{ccy}"
                docs["intraday-liquidity"][iid] = {"reportId": iid, "legalEntityName": name, "currency": ccy, "peakUsage": peak, "available": avail,
                                                   "usagePct": round(peak / avail, 4), "usage": usage, "largePayments": sorted(pays, key=lambda p: p["time"]),
                                                   "paymentSystem": "Fedwire" if ccy == "USD" else "TARGET2", "legalEntity": le,
                                                   "_meta": {"source": "payments-hub", "generation": 1, "live": True, "walk": {"peakUsage": 250000}}}
        for scn, sname, sev in [("IDIO", "Idiosyncratic (rating downgrade by 3 notches)", 1.0), ("MKT", "Market-wide (funding markets close)", 0.8),
                                ("COMB", "Combined idiosyncratic and market-wide", 1.4)]:
            rs = rng(le + scn)
            pos, path, survival, low = hqla, [], None, hqla
            drivers = {"Wholesale run-off": 0, "Retail run-off": 0, "Collateral calls (downgrade)": 0, "Committed facility draws": 0}
            for day in range(0, 91):
                out = hqla * sev * 0.028 * math.exp(-day / 25) * rs.uniform(0.8, 1.2)
                for k2, w in zip(drivers, (0.45, 0.2, 0.2, 0.15)):
                    drivers[k2] += out * w
                pos -= out
                low = min(low, pos)
                path.append({"day": day, "position": round(pos)})
                if survival is None and pos < 0:
                    survival = day
            rid = f"LST-{scn}-{le[3:]}"
            docs["liquidity-stress"][rid] = {"resultId": rid, "scenarioName": sname, "legalEntityName": name, "survivalDays": survival or 90,
                                             "minimumPosition": round(low), "appetiteDays": 60, "path": path,
                                             "drivers": [{"driver": k2, "outflow": round(v)} for k2, v in drivers.items()], "legalEntity": le,
                                             "_meta": {"source": "treasury-alm", "generation": 1}}
    for i, cp in enumerate(c for c in cps if c["type"] not in ("Clearing broker",)):
        r = rng("fund" + cp["counterpartyId"])
        for n in range(2):
            le = r.choice(list(les))
            fid = f"FUND-{cp['counterpartyId'][3:]}-{n + 1}"
            typ = r.choice(["Wholesale time deposit", "Repo funding", "Commercial paper", "Operational deposit"])
            amt = round(r.uniform(25, 900) * 1e6, -5)
            mat = AS_OF + timedelta(days=r.randint(1, 400))
            docs["funding-source"][fid] = {"fundingId": fid, "counterpartyName": cp["name"], "type": typ, "amount": amt, "currency": r.choice(LE_CCY[le]),
                                           "maturity": mat.isoformat(), "runOffRate": {"Wholesale time deposit": 0.4, "Repo funding": 0.25, "Commercial paper": 1.0,
                                                                                       "Operational deposit": 0.25}[typ],
                                           "terms": {"rate": round(r.uniform(0.02, 0.055), 4), "startDate": (AS_OF - timedelta(days=r.randint(5, 300))).isoformat(),
                                                     "collateral": "Government bonds" if typ == "Repo funding" else "Unsecured",
                                                     "withdrawable": typ == "Operational deposit"},
                                           "counterparty": cp["counterpartyId"], "legalEntity": le, "_meta": {"source": "treasury-alm", "generation": 1}}
    return docs


def spec(bank: dict) -> PB.PackSpec:
    return PB.PackSpec(
        code="LIQ",
        columns={'lcr': ['legalEntityName', 'lcr', 'hqla', 'netOutflows', 'buffer'], 'nsfr': ['legalEntityName', 'nsfr', 'asfTotal', 'rsfTotal'], 'funding-source': ['counterpartyName', 'type', 'amount', 'currency', 'maturity', 'runOffRate'], 'hqla-holding': ['security', 'level', 'marketValue', 'haircut', 'liquidityValue'], 'liquidity-stress': ['scenarioName', 'legalEntityName', 'survivalDays', 'minimumPosition'], 'maturity-ladder': ['legalEntityName', 'currency', 'gap1m', 'survivalDays']},  # key fields shown beside each entity in pick lists
        name="liquidity-risk", title="Liquidity risk", requires=["trading"], generator="tools/packgen/liquidity/make.py",
        description="LCR and NSFR by legal entity, maturity ladders, HQLA holdings, funding sources, liquidity stress survival and intraday liquidity.",
        domains={"liquidity": KINDS},
        examples=[("LCR LCR-NY", "Liquidity coverage ratio · HQLA by level, outflows, 30-day trend"), ("NSFR NSFR-LDN", "Net stable funding ratio"),
                  ("MLAD MLAD-LDN-GBP", "Maturity ladder · net gap by bucket"), ("LST LST-COMB-NY", "Liquidity stress · survival horizon"),
                  ("IDL IDL-NY-USD", "Intraday liquidity · usage through the day")],
        overview="Liquidity figures are computed per legal entity from the same entities, counterparties and bonds as the banking packs: HQLA holdings "
                 "are the sovereign and corporate bonds of the market-data pack, funding sources are the banking-core counterparties.",
        external_ids={"legal-entity": set(bank["legal-entity"]), "counterparty": set(bank["counterparty"]), "bond": set(bank["bond"])},
        roles={"treasury": {"kinds": [k.kind for k in KINDS] + ["legal-entity", "counterparty", "bond"], "raw": True}})


if __name__ == "__main__":
    bank = BANK.build()
    PB.main(spec(bank), build(bank), T.graph_fields())
