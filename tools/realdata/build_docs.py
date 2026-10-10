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

"""Builds the documents of the economics and politics-society packs from the committed dataset (normalise.py's output).
Both pack generators call economics() and politics(); nothing here is invented: every figure is a published value, every
text is as published (HTML removed), and every document carries its provenance (source, dataset, retrieval date, licence).
Percentages stay in percent as published (6.43 means 6.43%); the packs' pc1 / pc2 formats add the % sign.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import normalise as N  # noqa: E402

RECENT = 2022          # an indicator counts in a ranking only when its latest year is this recent
GENERIC_PARTY = re.compile(r"^(other|others|independents?|vacant|vacancy|unaffiliated|non-?partisan|undistributed)\b", re.I)


class Data:
    """The dataset with the lookups the builders share."""

    def __init__(self, ds: dict | None = None, cfg: dict | None = None):
        self.ds = ds or N.load()
        self.cfg = cfg or N.load_config()
        self.meta, self.C, self.G = self.ds["meta"], self.ds["countries"], self.ds["groups"]
        self.S, self.FC, self.FB = self.ds["series"], self.ds["forecast"], self.ds["factbook"]
        self.ind = {i["key"]: i for i in self.cfg["indicators"]}
        self.src = self.cfg["sources"]

    def ser(self, key: str, code: str) -> dict:
        return self.S.get(key, {}).get(code, {})

    def last(self, key: str, code: str, max_year: int | None = None):
        s = {y: v for y, v in self.ser(key, code).items() if max_year is None or int(y) <= max_year}
        if not s:
            return None, None
        y = max(s)
        return int(y), s[y]

    def val(self, key: str, code: str):
        return self.last(key, code)[1]

    def path(self, key: str, code: str, field: str, first: int | None = None) -> list[dict]:
        return [{"year": y, field: v} for y, v in sorted(self.ser(key, code).items()) if first is None or int(y) >= first]

    def prev(self, key: str, code: str, back: int):
        """(year, value) about `back` years before the latest observation (the nearest earlier year with data)."""
        y, _ = self.last(key, code)
        if y is None:
            return None, None
        s = self.ser(key, code)
        for yy in range(y - back, y - back - 3, -1):
            if str(yy) in s:
                return yy, s[str(yy)]
        return None, None

    def rank(self, key: str, code: str):
        """(rank, of) of a country's latest value among countries with a recent latest value, highest first."""
        y, v = self.last(key, code)
        if y is None or y < RECENT:
            return None, None
        vals = [x for c in self.C if (yy := self.last(key, c)[0]) and yy >= RECENT and (x := self.val(key, c)) is not None]
        return 1 + sum(1 for x in vals if x > v), len(vals)

    def prov(self, *srcs: str) -> dict:
        """The provenance block of a document built from the given sources (wdi, wgi, imf, undp, factbook)."""
        rows = [self.src[s] for s in srcs]
        ret = self.meta.get("retrieved", {})
        when = max((ret.get(s, "") for s in srcs), default="")
        vint = self.meta.get("vintage", {})
        return {"source": "; ".join(r["name"] for r in rows),
                "dataset": "; ".join(f"{self.src[s]['dataset']} ({vint.get(s, '') or 'current'})" for s in srcs),
                "retrievedAt": when, "licence": "; ".join(dict.fromkeys(r["licence"] for r in rows)),
                "attribution": " ".join(dict.fromkeys(r["attribution"] for r in rows)),
                "url": rows[0]["url"]}

    def meta_of(self, prov: dict) -> dict:
        return {"source": prov["source"], "dataset": prov["dataset"], "retrievedAt": prov["retrievedAt"], "licence": prov["licence"], "generation": 1}


def _code(key: str) -> str:
    return key.upper().replace("_", "-")


# =================================================================== economics
def economics(d: Data | None = None) -> dict[str, dict[str, dict]]:
    d = d or Data()
    docs: dict[str, dict] = {k: {} for k in ("economy", "macro-indicator", "economic-forecast", "labour-market", "fiscal-position", "country-group")}
    p_wb, p_all, p_imf = d.prov("wdi"), d.prov("wdi", "imf", "factbook"), d.prov("imf")
    p_wbimf = d.prov("wdi", "imf")
    econ_inds = [i for i in d.cfg["indicators"] if i["group"] == "economy"]
    countries = sorted(c for c in d.C if d.ser("gdp", c) or d.ser("gdp_growth", c))
    macro_ids: dict[str, list[str]] = {}
    for iso in countries:
        c = d.C[iso]
        name = c["name"]
        # ---- macro indicators (one per country and headline indicator)
        for i in econ_inds:
            s = d.ser(i["key"], iso)
            if not s:
                continue
            ly, lv = d.last(i["key"], iso)
            py, pv = d.prev(i["key"], iso, 1)
            rk, of = d.rank(i["key"], iso)
            mid = f"MACRO-{iso}-{_code(i['key'])}"
            macro_ids.setdefault(iso, []).append(mid)
            docs["macro-indicator"][mid] = {
                "indicatorId": mid, "name": f"{name} · {i['name']}", "indicator": i["name"], "countryName": name, "unit": i["unit"], "definition": i["def"],
                "latest": lv, "latestYear": str(ly), "previous": pv, "previousYear": str(py) if py else None, "rank": rk, "ofCount": of,
                "history": [{"year": y, "value": v} for y, v in sorted(s.items())],
                "facts": {"indicator": i["name"], "unit": i["unit"], "latestYear": str(ly), "rank": f"{rk} of {of}" if rk else "not ranked (older than {})".format(RECENT),
                          "worldBankCode": i["code"]},
                "economy": f"ECON-{iso}", "provenance": p_wb, "_meta": d.meta_of(p_wb)}
        # ---- economy
        gy, gdp = d.last("gdp", iso)
        sectors = [{"sector": lab, "share": v} for lab, k in (("Services", "services"), ("Industry", "industry"), ("Agriculture", "agriculture")) if (v := d.val(k, iso)) is not None]
        fb = d.FB.get(iso, {})
        docs["economy"][f"ECON-{iso}"] = {
            "economyId": f"ECON-{iso}", "name": name, "iso3": iso, "regionName": c["regionName"], "incomeName": c["incomeName"], "currency": fb.get("currency") or None,
            "capital": c["capital"] or fb.get("capital"), "year": str(gy) if gy else None, "gdpUsdBn": gdp, "gdpPerCapita": d.val("gdp_pc", iso),
            "growth": d.val("gdp_growth", iso), "inflation": d.val("inflation", iso), "unemployment": d.val("unemployment", iso),
            "debtToGdp": d.val("debt", iso), "currentAccount": d.val("current_account", iso), "reservesUsdBn": d.val("reserves", iso),
            "tradeShare": d.val("trade", iso), "populationM": d.val("population", iso),
            "growthPath": d.path("gdp_growth", iso, "growth"), "gdpPath": d.path("gdp", iso, "gdp"), "sectors": sectors,
            "regionGroup": f"ECG-{c['region']}", "incomeGroup": f"ECG-{c['income']}",
            "labour": f"LABR-{iso}" if d.ser("unemployment", iso) or d.ser("lfp", iso) else None,
            "fiscal": f"FISC-{iso}" if d.ser("debt", iso) or d.ser("net_lending", iso) else None,
            "outlook": f"FCST-{iso}-WEO" if d.FC.get("f_gdp_growth", {}).get(iso) else None,
            "indicators": macro_ids.get(iso, []), "provenance": p_all, "_meta": d.meta_of(p_all)}
        # ---- labour
        if d.ser("unemployment", iso) or d.ser("lfp", iso):
            ly, _ = d.last("unemployment", iso)
            docs["labour-market"][f"LABR-{iso}"] = {
                "labourId": f"LABR-{iso}", "economyName": name, "year": str(ly) if ly else None, "unemployment": d.val("unemployment", iso),
                "participation": d.val("lfp", iso), "youthUnemployment": d.val("youth_unemployment", iso), "employmentRatio": d.val("employment_ratio", iso),
                "femaleParticipation": d.val("female_lfp", iso), "vulnerable": d.val("vulnerable", iso),
                "unemploymentPath": d.path("unemployment", iso, "rate"),
                "bySector": [{"sector": lab, "employmentShare": d.val(ek, iso), "valueAddedShare": d.val(vk, iso)}
                             for lab, ek, vk in (("Agriculture", "emp_agriculture", "agriculture"), ("Industry", "emp_industry", "industry"), ("Services", "emp_services", "services"))
                             if d.val(ek, iso) is not None],
                "economy": f"ECON-{iso}", "provenance": p_wb, "_meta": d.meta_of(p_wb)}
        # ---- fiscal
        if d.ser("debt", iso) or d.ser("net_lending", iso):
            ly, _ = d.last("debt", iso, 2025)
            lend = d.last("net_lending", iso, 2025)[1]
            items = [{"item": lab, "value": v} for lab, v in (("Tax revenue", d.val("tax_revenue", iso)), ("Revenue (excl. grants)", d.val("revenue", iso)),
                                                              ("Expense", d.val("expense", iso)), ("Military expenditure", d.val("military", iso))) if v is not None]
            docs["fiscal-position"][f"FISC-{iso}"] = {
                "fiscalId": f"FISC-{iso}", "economyName": name, "year": str(ly) if ly else None, "debt": d.last("debt", iso, 2025)[1], "balance": lend,
                "revenue": d.val("revenue", iso), "spending": d.val("expense", iso), "military": d.val("military", iso),
                "ratios": items, "debtPath": [{"year": y, "debt": v} for y, v in sorted(d.ser("debt", iso).items())],
                "balancePath": [{"year": y, "balance": v} for y, v in sorted(d.ser("net_lending", iso).items())],
                "economy": f"ECON-{iso}", "provenance": p_wbimf, "_meta": d.meta_of(p_wbimf)}
        # ---- forecast (IMF WEO)
        fg = d.FC.get("f_gdp_growth", {}).get(iso)
        if fg:
            vint = "IMF World Economic Outlook, " + d.meta.get("vintage", {}).get("imf", "")
            yr0 = int(re.search(r"(\d{4})", vint).group(1)) if re.search(r"(\d{4})", vint) else 2026
            years = sorted({y for k in d.FC for y in d.FC[k].get(iso, {})})
            rows = [{"year": y, "growth": d.FC["f_gdp_growth"].get(iso, {}).get(y), "inflation": d.FC["f_inflation"].get(iso, {}).get(y),
                     "unemployment": d.FC["f_unemployment"].get(iso, {}).get(y), "currentAccount": d.FC["f_current_account"].get(iso, {}).get(y),
                     "debt": d.FC["f_debt"].get(iso, {}).get(y), "netLending": d.FC["f_net_lending"].get(iso, {}).get(y), "gdp": d.FC["f_gdp"].get(iso, {}).get(y),
                     "basis": "Projection" if int(y) >= yr0 else "Outturn or estimate"} for y in years]
            docs["economic-forecast"][f"FCST-{iso}-WEO"] = {
                "forecastId": f"FCST-{iso}-WEO", "economyName": name, "vintage": vint, "growth2027": fg.get("2027"), "inflation2027": d.FC["f_inflation"].get(iso, {}).get("2027"),
                "debt2030": d.FC["f_debt"].get(iso, {}).get("2030"), "projections": rows,
                "path": [{"year": y, "growth": v} for y, v in sorted(fg.items())], "economy": f"ECON-{iso}", "provenance": p_imf, "_meta": d.meta_of(p_imf)}
    # ---- country groups (World Bank regions, income groups, the world)
    for gid, g in d.G.items():
        members = sorted((i for i, c in d.C.items() if (c["region"] == gid or c["income"] == gid or gid == "WLD") and f"ECON-{i}" in docs["economy"]),
                         key=lambda i: -(d.val("gdp", i) or 0))
        gy, gdp = d.last("gdp", gid)
        docs["country-group"][f"ECG-{gid}"] = {
            "groupId": f"ECG-{gid}", "name": g["name"], "groupType": {"region": "World Bank region", "income": "World Bank income group", "world": "World"}[g["type"]],
            "economies": len(members), "year": str(gy) if gy else None, "gdpUsdBn": gdp, "gdpPerCapita": d.val("gdp_pc", gid), "populationM": d.val("population", gid),
            "growth": d.val("gdp_growth", gid), "inflation": d.val("inflation", gid), "growthPath": d.path("gdp_growth", gid, "growth"), "gdpPath": d.path("gdp", gid, "gdp"),
            "topEconomies": [{"economy": d.C[i]["name"], "gdpUsdBn": d.val("gdp", i), "growth": d.val("gdp_growth", i)} for i in members[:10]],
            "memberEconomies": [f"ECON-{i}" for i in members], "provenance": p_wb, "_meta": d.meta_of(p_wb)}
    return docs


# =================================================================== politics and society
def _since(text: str) -> tuple[str, str]:
    m = re.search(r"\(since ([^)]+)\)", text)
    return (text[:m.start()].strip() + text[m.end():].strip(), m.group(1)) if m else (text, "")


def politics(d: Data | None = None) -> dict[str, dict[str, dict]]:
    d = d or Data()
    docs: dict[str, dict] = {k: {} for k in ("jurisdiction", "party", "office-holder", "election", "region", "social-indicator")}
    p_fb, p_wb = d.prov("factbook"), d.prov("wdi", "wgi", "undp")
    p_jur = d.prov("factbook", "wdi", "wgi", "undp")
    soc_inds = [i for i in d.cfg["indicators"] if i["group"] in ("social", "governance")]
    jur_set = sorted(c for c in d.C if c in d.FB)
    soc_ids: dict[str, list[str]] = {}
    for iso in jur_set:
        c, fb = d.C[iso], d.FB[iso]
        name = c["name"]
        # ---- social and governance indicators
        peers_all: dict[str, dict] = {}
        for i in soc_inds:
            s = d.ser(i["key"], iso)
            if not s:
                continue
            ly, lv = d.last(i["key"], iso)
            by, bv = d.prev(i["key"], iso, 5)
            sid = f"SOCI-{iso}-{_code(i['key'])}"
            soc_ids.setdefault(iso, []).append(sid)
            peers = sorted(((d.C[o]["name"], d.val(i["key"], o)) for o in d.C if d.C[o]["region"] == c["region"] and o != iso and (d.last(i["key"], o)[0] or 0) >= RECENT
                            and d.val(i["key"], o) is not None), key=lambda x: -x[1])
            ranked = sorted(peers + [(name, lv)], key=lambda x: -x[1])
            top = ranked[:10]
            if (name, lv) not in top:
                top = ranked[:9] + [(name, lv)]
            src = d.src[i["kind"]]
            docs["social-indicator"][sid] = {
                "indicatorId": sid, "name": f"{name} · {i['name']}", "indicator": i["name"], "countryName": name, "theme": "Governance" if i["group"] == "governance" else "Society",
                "latest": lv, "latestYear": str(ly), "unit": i["unit"], "change5y": round(lv - bv, 4) if bv is not None else None,
                "sourceName": src["name"], "definition": i["def"], "series": [{"year": y, "value": v} for y, v in sorted(s.items())],
                "byRegion": [{"country": n, "value": v} for n, v in top], "regionName": c["regionName"],
                "jurisdiction": f"JUR-{iso}", "provenance": d.prov(i["kind"]), "_meta": d.meta_of(d.prov(i["kind"]))}
        # ---- office holders
        holders = []
        for role, field, suffix in (("Chief of state", "chiefOfState", "COS"), ("Head of government", "headOfGovernment", "HOG")):
            text = fb.get(field)
            if not text:
                continue
            holder, since = _since(text)
            oid = f"OFFC-{iso}-{suffix}"
            holders.append(oid)
            docs["office-holder"][oid] = {
                "officeId": oid, "countryName": name, "role": role, "holder": holder[:300], "since": since or None, "published": text[:400],
                "process": fb.get("execProcess", "")[:500] or None, "electionHeld": fb.get("execLastElection") or None, "nextElection": fb.get("execNextElection") or None,
                "results": fb.get("execResults") or None, "governmentType": fb.get("governmentType"), "cabinet": fb.get("cabinet", "")[:300] or None,
                "jurisdiction": f"JUR-{iso}", "provenance": p_fb, "_meta": d.meta_of(p_fb)}
        # ---- elections and parties
        elec_ids, party_ids, low_eid = [], [], None
        for tag, label, ch in (("LOW", "lower chamber", fb.get("lowerChamber")), ("UPP", "upper chamber", fb.get("upperChamber"))):
            if not ch or not (ch.get("lastElection") or ch.get("parties")):
                continue
            eid = f"ELEC-{iso}-{tag}"
            elec_ids.append(eid)
            low_eid = low_eid or eid
            res = ch.get("parties", [])
            tot = sum(r["seats"] for r in res) or 1
            results = [{"party": r["party"], "seats": r["seats"], "share": round(100 * r["seats"] / tot, 1)} for r in res]
            win = max(res, key=lambda r: r["seats"])["party"] if res else None
            docs["election"][eid] = {
                "electionId": eid, "title": f"{name} · {ch.get('name') or label.capitalize()}", "countryName": name, "chamber": ch.get("name") or label,
                "date": ch.get("lastElectionIso") or None, "dateText": ch.get("lastElection") or None, "system": ch.get("system") or None, "term": ch.get("term") or None,
                "winner": win, "nextElection": ch.get("nextElection") or None, "women": ch.get("women") or None, "seatsText": ch.get("seatsText") or None, "results": results,
                "summary": {k: v for k, v in (("chamber", ch.get("name")), ("seats", ch.get("seatsText")), ("electoralSystem", ch.get("system")), ("term", ch.get("term")),
                                              ("heldOn", ch.get("lastElection")), ("nextElection", ch.get("nextElection")),
                                              ("women", ch.get("women"))) if v},
                "jurisdiction": f"JUR-{iso}", "provenance": p_fb, "_meta": d.meta_of(p_fb)}
            if tag == "LOW":
                named = [r for r in results if not GENERIC_PARTY.match(r["party"])]
                for n, r in enumerate(sorted(named, key=lambda x: -x["seats"]), 1):
                    pid = f"PARTY-{iso}-{n:02d}"
                    party_ids.append(pid)
                    docs["party"][pid] = {
                        "partyId": pid, "name": r["party"], "countryName": name, "seats": r["seats"], "seatShare": r["share"], "chamber": ch.get("name") or label, "rank": n,
                        "totalSeats": tot, "electionDate": ch.get("lastElection") or None, "results": results, "election": eid, "jurisdiction": f"JUR-{iso}",
                        "provenance": p_fb, "_meta": d.meta_of(p_fb)}
        if fb.get("execLastElection") or fb.get("execProcess"):
            eid = f"ELEC-{iso}-EXE"
            elec_ids.append(eid)
            docs["election"][eid] = {
                "electionId": eid, "title": f"{name} · executive (head of state)", "countryName": name, "chamber": "Executive", "date": N.parse_date(fb.get("execLastElection", "")) or None,
                "dateText": fb.get("execLastElection") or None, "system": fb.get("execProcess", "")[:240] or None, "term": None, "winner": _since(fb.get("chiefOfState", ""))[0][:120] or None,
                "nextElection": fb.get("execNextElection") or None, "women": None, "seatsText": None, "results": [],
                "summary": {k: v for k, v in (("selection", fb.get("execProcess", "")[:500]), ("heldOn", fb.get("execLastElection")),
                                              ("nextElection", fb.get("execNextElection")), ("results", fb.get("execResults"))) if v},
                "jurisdiction": f"JUR-{iso}", "provenance": p_fb, "_meta": d.meta_of(p_fb)}
        # ---- jurisdiction
        low = fb.get("lowerChamber") or {}
        ind_rows = []
        for key in ("hdi", "life_expectancy", "gini", "urban", "internet", "women_parliament", "military", "wgi_voice", "wgi_stability", "wgi_effectiveness", "wgi_regulatory", "wgi_law", "wgi_corruption"):
            y, v = d.last(key, iso)
            if v is not None:
                i = d.ind[key]
                ind_rows.append({"indicator": i["name"], "value": v, "year": str(y), "unit": i["unit"]})
        docs["jurisdiction"][f"JUR-{iso}"] = {
            "jurisdictionId": f"JUR-{iso}", "name": name, "iso3": iso, "officialName": fb.get("longName") or name, "capital": fb.get("capital") or c["capital"] or None,
            "regionName": c["regionName"], "populationM": d.val("population", iso), "areaKm2": fb.get("areaKm2"), "governmentType": fb.get("governmentType"),
            "currency": fb.get("currency"), "independence": fb.get("independence"), "legislatureName": fb.get("legislatureName") or low.get("name"),
            "legislatureStructure": fb.get("legislatureStructure"), "legislature": (low.get("parties") or []), "indicators": ind_rows,
            "partiesListed": [{"party": p} for p in fb.get("parties", [])],
            "profile": {k: v for k, v in (("officialName", fb.get("longName")), ("languages", fb.get("languages")), ("religions", fb.get("religions")), ("legalSystem", fb.get("legalSystem")),
                                          ("suffrage", fb.get("suffrage")), ("independence", fb.get("independence")), ("nationalHoliday", fb.get("nationalHoliday")),
                                          ("location", fb.get("location")), ("currencyText", fb.get("currencyText"))) if v},
            "region": f"REGN-{c['region']}", "chiefOfState": f"OFFC-{iso}-COS" if f"OFFC-{iso}-COS" in holders else None,
            "headOfGovernment": f"OFFC-{iso}-HOG" if f"OFFC-{iso}-HOG" in holders else None, "lastElection": low_eid, "elections": elec_ids, "parties": party_ids,
            "socialIndicators": soc_ids.get(iso, []), "provenance": p_jur, "_meta": d.meta_of(p_jur)}
    # ---- regions
    for rid in sorted(N.REGION_IDS):
        if rid not in d.G:
            continue
        members = sorted(i for i in jur_set if d.C[i]["region"] == rid)
        types: dict[str, int] = {}
        for i in members:
            t = d.FB[i].get("governmentType")
            if t:
                types[t] = types.get(t, 0) + 1
        ly, life = d.last("life_expectancy", rid)
        docs["region"][f"REGN-{rid}"] = {
            "regionId": f"REGN-{rid}", "name": d.G[rid]["name"], "countries": len(members), "populationM": d.val("population", rid), "lifeExpectancy": life, "year": str(ly) if ly else None,
            "internet": d.val("internet", rid), "womenParliament": d.val("women_parliament", rid), "urban": d.val("urban", rid),
            "lifeTrend": d.path("life_expectancy", rid, "value"), "internetTrend": d.path("internet", rid, "value"),
            "governmentTypes": [{"type": t, "count": n} for t, n in sorted(types.items(), key=lambda x: (-x[1], x[0]))[:12]],
            "memberStates": [f"JUR-{i}" for i in members], "provenance": d.prov("wdi", "factbook"), "_meta": d.meta_of(d.prov("wdi", "factbook"))}
    return docs
