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

"""The climate-risk pack: counterparty climate profiles, PCAF financed emissions per book, NGFS scenarios, climate
stress results, physical-risk assets and the EU-taxonomy green asset ratio, generated from the banking packs' data.

    python3 tools/packgen/climate/make.py            write packs/climate-risk
    python3 tools/packgen/climate/make.py --check    fail if the pack differs from what would be written
    uv run --with deltalake --with pyarrow python tools/packgen/climate/make.py --lake data/delta
"""
from __future__ import annotations

import hashlib
import random
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "common"))
sys.path.insert(0, str(HERE.parent / "banking"))
import packbuild as PB  # noqa: E402
import make_data as BANK  # noqa: E402
import taxonomy as T  # noqa: E402
from risk_model import Kind, Panel as P  # noqa: E402

G = "Climate"
# sector: (scope 1+2 intensity tCO2e per USD m revenue, transition risk 0-100, physical risk 0-100)
SECTOR = {"Utilities": (1650, 82, 55), "Airlines": (1320, 88, 40), "Shipping": (980, 80, 52), "Agriculture": (710, 61, 78),
          "Materials": (890, 74, 45), "Autos": (140, 66, 38), "Technology": (22, 18, 30), "Telecoms": (48, 22, 33), "Healthcare": (35, 15, 28),
          "Banking": (8, 30, 20), "Insurance": (6, 35, 60), "Asset management": (4, 20, 15), "Pensions": (3, 18, 12), "Government": (60, 40, 50),
          "Financial services": (6, 25, 18)}
SCENARIOS = {"NGFS-NETZERO2050": ("Net Zero 2050", "Orderly", 1.4, [(2025, 85), (2030, 250), (2035, 420), (2040, 560), (2050, 760)]),
             "NGFS-DELAYED": ("Delayed transition", "Disorderly", 1.7, [(2025, 20), (2030, 30), (2035, 390), (2040, 610), (2050, 880)]),
             "NGFS-CURRENTPOLICIES": ("Current policies", "Hot house world", 2.9, [(2025, 20), (2030, 22), (2035, 25), (2040, 28), (2050, 35)])}

KINDS = [
    Kind("climate-profile", "CLIM", "CLIM-", "Climate profile", G, "A counterparty's emissions, targets and transition and physical risk scores.", "profileId",
         [("Counterparty", "$.counterpartyName", None, None, False), ("Sector", "$.sector", None, None, False),
          ("Scope 1+2 (tCO2e)", "$.scope12", "amount0", None, False), ("Intensity", "$.intensity", "amount0", None, False),
          ("Transition risk", "$.transitionScore", "amount0", None, True), ("Physical risk", "$.physicalScore", "amount0", None, False),
          ("Net-zero target", "$.netZeroTarget", None, None, False)],
         [P("hbar", "scopes", "Emissions by scope (tCO2e)", "$.scopes", label="scope", value="tco2e", fmt="amount0", key="F2", code="EMIS"),
          P("line", "trend", "Scope 1+2 emissions by year", "$.history", x="year", y="tco2e", fmt="compact", area="right"),
          P("kv", "targets", "Targets and disclosure", "$.targets", key="F3")],
         links={"counterparty": ("counterparty", "Counterparty")}, badge="'transition ' + $.transitionScore"),
    Kind("financed-emissions", "FE", "FE-", "Financed emissions", G, "PCAF financed emissions of a book: each exposure's share of its counterparty's emissions.", "resultId",
         [("Book", "$.book", None, None, False), ("Financed (tCO2e)", "$.financed", "amount0", None, True), ("Exposure", "$.exposure", "compact", None, False),
          ("Intensity (t per USD m)", "$.intensity", "amount0", None, False), ("PCAF data quality", "$.dataQuality", "price2", None, False)],
         [P("table", "byCounterparty", "By counterparty", "$.byCounterparty", [("Counterparty", "@.counterparty", None, None, False),
            ("Exposure", "@.exposure", "compact", None, False), ("Attribution", "@.attribution", "pct2", None, False), ("Financed (tCO2e)", "@.financed", "amount0", None, True),
            ("Score", "@.dataQuality", None, None, False)], key="F2"),
          P("hbar", "bySector", "By sector (tCO2e)", "$.bySector", label="sector", value="financed", fmt="amount0", area="right")],
         links={"book": ("book", "Book")}),
    Kind("climate-scenario", "NGFS", "NGFS-", "Climate scenario", G, "An NGFS climate scenario: carbon price path, warming and sector shocks.", "scenarioId",
         [("Scenario", "$.name", None, None, False), ("Category", "$.category", None, None, False), ("Warming by 2100", "$.warming", "price2", None, True),
          ("Carbon price 2030", "$.carbon2030", "amount0", None, False), ("Carbon price 2050", "$.carbon2050", "amount0", None, False)],
         [P("line", "carbon", "Carbon price (USD per tCO2e)", "$.carbonPath", x="year", y="price", fmt="amount0", key="F2"),
          P("table", "shocks", "Sector shocks (equity value, 2030)", "$.shocks", [("Sector", "@.sector", None, None, False), ("Shock", "@.shock", "pct0", "sign", False)], key="F3")]),
    Kind("climate-stress", "CST", "CST-", "Climate stress result", G, "Expected credit loss on a desk's portfolio under a climate scenario, by sector and horizon.", "resultId",
         [("Scenario", "$.scenarioName", None, None, False), ("Desk", "$.deskName", None, None, False), ("Loss (2030)", "$.loss2030", "signed0", "sign", True),
          ("Loss (2050)", "$.loss2050", "signed0", "sign", False), ("Share of capital", "$.capitalShare", "pct2", None, False)],
         [P("hbar", "bySector", "Loss by sector (2030)", "$.bySector", label="sector", value="loss", fmt="signed0", tone="sign", key="F2"),
          P("line", "horizon", "Loss by horizon", "$.byHorizon", x="year", y="loss", fmt="compact", area="right")],
         links={"scenario": ("climate-scenario", "Scenario"), "desk": ("desk", "Desk")}),
    Kind("physical-asset", "PHY", "PHY-", "Physical-risk asset", G, "A counterparty's physical asset and its exposure to floods, heat, storms and wildfire.", "assetId",
         [("Asset", "$.name", None, None, False), ("Owner", "$.counterpartyName", None, None, False), ("Country", "$.country", None, None, False),
          ("Value", "$.value", "compact", None, False), ("Worst hazard", "$.worstHazard", None, None, True), ("Insured", "$.insured", "pct0", None, False)],
         [P("hbar", "hazards", "Hazard scores (0-100, 2050, RCP 4.5)", "$.hazards", label="hazard", value="score", fmt="amount0", key="F2"),
          P("kv", "location", "Location", "$.location", area="right")],
         links={"counterparty": ("counterparty", "Counterparty")}),
    Kind("taxonomy-alignment", "GAR", "GAR-", "Green asset ratio", G, "EU taxonomy alignment of a legal entity's balance sheet: eligible and aligned assets by objective.", "reportId",
         [("Entity", "$.legalEntityName", None, None, False), ("Green asset ratio", "$.gar", "pct2", None, True), ("Eligible", "$.eligible", "pct0", None, False),
          ("Covered assets", "$.covered", "compact", None, False)],
         [P("hbar", "objectives", "Aligned assets by objective", "$.objectives", label="objective", value="aligned", fmt="compact", key="F2")],
         links={"legalEntity": ("legal-entity", "Legal entity")}),
]


def rng(key: str) -> random.Random:
    return random.Random(int(hashlib.sha256(key.encode()).hexdigest()[:12], 16))


def build(bank: dict) -> dict[str, dict[str, dict]]:
    docs = {k.kind: {} for k in KINDS}
    for cp in bank["counterparty"].values():
        r = rng("clim" + cp["counterpartyId"])
        inten, tr, ph = SECTOR.get(cp["sector"], (100, 40, 40))
        revenue = r.uniform(400, 25000)                                   # USD m
        s12 = round(inten * revenue * r.uniform(0.7, 1.3))
        s3 = round(s12 * r.uniform(2, 9))
        pid = f"CLIM-{cp['counterpartyId'][3:]}"
        docs["climate-profile"][pid] = {
            "profileId": pid, "counterpartyName": cp["name"], "sector": cp["sector"], "scope12": s12, "intensity": round(s12 / revenue),
            "transitionScore": min(100, max(0, round(tr + r.gauss(0, 8)))), "physicalScore": min(100, max(0, round(ph + r.gauss(0, 8)))),
            "netZeroTarget": r.choice(["2040", "2050", "None", "2050 (SBTi validated)"]),
            "scopes": [{"scope": "Scope 1", "tco2e": round(s12 * 0.7)}, {"scope": "Scope 2", "tco2e": round(s12 * 0.3)}, {"scope": "Scope 3", "tco2e": s3}],
            "history": [{"year": str(y), "tco2e": round(s12 * (1 + 0.04 * (2025 - y)) * r.uniform(0.95, 1.05))} for y in range(2019, 2026)],
            "targets": {"reduction2030": f"{r.choice([25, 30, 42, 50])}% vs 2019", "disclosure": r.choice(["CSRD", "ISSB S2", "CDP only", "None"]),
                        "revenueUsdM": round(revenue), "reportingYear": 2025},
            "counterparty": cp["counterpartyId"], "_meta": {"source": "climate-data", "generation": 1}}
    for b in bank["book"].values():
        r = rng("fe" + b["bookId"])
        trades = [t for t in bank["trade"].values() if t["book"] == b["bookId"]]
        by_cp = {}
        for t in trades:
            by_cp.setdefault(t["counterparty"]["id"], 0.0)
            by_cp[t["counterparty"]["id"]] += abs(t["notional"]) * 0.05
        rows, by_sector = [], {}
        for cpid, exp in sorted(by_cp.items(), key=lambda x: -x[1])[:12]:
            prof = docs["climate-profile"][f"CLIM-{cpid[3:]}"]
            evic = prof["targets"]["revenueUsdM"] * 1e6 * r.uniform(1.5, 4)      # enterprise value incl. cash
            attr = min(1.0, exp / evic)
            fin = round(attr * prof["scope12"])
            dq = r.choice([2, 3, 4])
            rows.append({"counterparty": bank["counterparty"][cpid]["name"], "exposure": round(exp), "attribution": round(attr, 6), "financed": fin, "dataQuality": dq})
            sector = bank["counterparty"][cpid]["sector"]
            by_sector[sector] = by_sector.get(sector, 0) + fin
        total_exp = sum(x["exposure"] for x in rows) or 1
        fin_total = sum(x["financed"] for x in rows)
        rid = f"FE-{b['bookId'][5:]}"
        docs["financed-emissions"][rid] = {
            "resultId": rid, "book": b["bookId"], "financed": fin_total, "exposure": total_exp, "intensity": round(fin_total / (total_exp / 1e6)),
            "dataQuality": round(sum(x["dataQuality"] * x["exposure"] for x in rows) / total_exp, 2) if rows else 5.0,
            "byCounterparty": rows, "bySector": [{"sector": s, "financed": v} for s, v in sorted(by_sector.items(), key=lambda x: -x[1])],
            "methodology": "PCAF Global GHG Standard, attribution = exposure / EVIC", "_meta": {"source": "climate-data", "generation": 1}}
    for sid, (name, cat, warming, path) in SCENARIOS.items():
        r = rng(sid)
        docs["climate-scenario"][sid] = {
            "scenarioId": sid, "name": name, "category": cat, "warming": warming, "carbon2030": dict(path)[2030], "carbon2050": dict(path)[2050],
            "carbonPath": [{"year": str(y), "price": p} for y, p in path],
            "shocks": [{"sector": s, "shock": round(-SECTOR[s][1] / 100 * dict(path)[2030] / 1000 * r.uniform(0.6, 1.2), 4)} for s in
                       ["Utilities", "Airlines", "Shipping", "Materials", "Autos", "Agriculture", "Technology", "Banking"]],
            "source": "NGFS Phase V (illustrative calibration)", "_meta": {"source": "climate-data", "generation": 1}}
        for desk in bank["desk"].values():
            rs = rng(sid + desk["deskId"])
            base = abs(desk["mtm"]) * 0.02 + 5e6
            sev = {"Orderly": 0.6, "Disorderly": 1.3, "Hot house world": 0.9}[cat]
            by_sector = [{"sector": s, "loss": -round(base * sev * SECTOR[s][1] / 100 * rs.uniform(0.2, 0.8))} for s in ["Utilities", "Airlines", "Shipping", "Materials", "Autos"]]
            l30 = sum(x["loss"] for x in by_sector)
            l50 = round(l30 * (1.6 if cat == "Hot house world" else 1.2))
            cid = f"CST-{sid[5:]}-{desk['deskId'][5:]}"
            docs["climate-stress"][cid] = {
                "resultId": cid, "scenarioName": name, "deskName": desk["name"], "loss2030": l30, "loss2050": l50, "capitalShare": round(-l30 / 4e9, 5),
                "bySector": by_sector, "byHorizon": [{"year": "2030", "loss": -l30}, {"year": "2040", "loss": -round((l30 + l50) / 2)}, {"year": "2050", "loss": -l50}],
                "scenario": sid, "desk": desk["deskId"], "_meta": {"source": "climate-stress", "generation": 1}}
    places = [("Rotterdam port terminal", "NL", 51.95, 4.14), ("Houston refinery", "US", 29.72, -95.2), ("Mumbai logistics hub", "IN", 19.0, 72.85),
              ("Queensland farm estate", "AU", -27.5, 153.0), ("Piraeus shipyard", "GR", 37.94, 23.64), ("Phoenix data centre", "US", 33.45, -112.07),
              ("São Paulo processing plant", "BR", -23.55, -46.63), ("Osaka assembly plant", "JP", 34.69, 135.5)]
    for i, (pname, country, lat, lon) in enumerate(places):
        cp = list(bank["counterparty"].values())[i % len(bank["counterparty"])]
        r = rng(pname)
        hazards = [{"hazard": h, "score": r.randint(10, 95)} for h in ["River flood", "Coastal flood", "Extreme heat", "Tropical storm", "Wildfire", "Drought"]]
        worst = max(hazards, key=lambda h: h["score"])
        aid = f"PHY-{i + 1:03d}"
        docs["physical-asset"][aid] = {
            "assetId": aid, "name": pname, "counterpartyName": cp["name"], "country": country, "value": round(r.uniform(80, 2500) * 1e6, -5),
            "worstHazard": f"{worst['hazard']} ({worst['score']})", "insured": round(r.uniform(0.3, 0.95), 2), "hazards": hazards,
            "location": {"latitude": lat, "longitude": lon, "elevationM": r.randint(1, 900), "distanceToCoastKm": round(r.uniform(0.2, 400), 1)},
            "counterparty": cp["counterpartyId"], "_meta": {"source": "climate-data", "generation": 1}}
    for le, led in bank["legal-entity"].items():
        r = rng("gar" + le)
        covered = round(r.uniform(20, 120) * 1e9, -6)
        objs = [{"objective": o, "aligned": round(covered * r.uniform(0.004, 0.04))} for o in
                ["Climate change mitigation", "Climate change adaptation", "Water and marine", "Circular economy", "Pollution prevention", "Biodiversity"]]
        gid = f"GAR-{le[3:]}"
        docs["taxonomy-alignment"][gid] = {"reportId": gid, "legalEntityName": led["name"], "gar": round(sum(o["aligned"] for o in objs) / covered, 4),
                                           "eligible": round(r.uniform(0.25, 0.55), 2), "covered": covered, "objectives": objs, "legalEntity": le,
                                           "_meta": {"source": "climate-data", "generation": 1}}
    return docs


def spec(bank: dict) -> PB.PackSpec:
    return PB.PackSpec(
        name="climate-risk", title="Climate risk", requires=["trading"], generator="tools/packgen/climate/make.py",
        description="Counterparty climate profiles, PCAF financed emissions, NGFS scenarios, climate stress, physical-risk assets and the green asset ratio.",
        domains={"climate": KINDS},
        examples=[("CLIM CLIM-SOLARIS", "Climate profile · emissions by scope, transition and physical risk"), ("FE FE-RATES-1", "Financed emissions of a book"),
                  ("NGFS NGFS-DELAYED", "NGFS scenario · carbon price path, sector shocks"), ("CST CST-DELAYED-COMM", "Climate stress on the commodities desk"),
                  ("PHY PHY-002", "Physical risk · hazards at a refinery"), ("GAR GAR-FRA", "EU taxonomy green asset ratio")],
        overview="Climate figures reuse the banking packs' counterparties, books, desks and legal entities: a counterparty's climate profile follows "
                 "its sector, financed emissions attribute its emissions to the books that trade with it, and climate stress runs on each desk.",
        external_ids={"counterparty": set(bank["counterparty"]), "book": set(bank["book"]), "desk": set(bank["desk"]), "legal-entity": set(bank["legal-entity"])},
        roles={"climate-analyst": {"kinds": [k.kind for k in KINDS] + ["counterparty", "book", "desk", "legal-entity"], "raw": True}})


if __name__ == "__main__":
    bank = BANK.build()
    PB.main(spec(bank), build(bank), T.graph_fields())
