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

"""The politics and society pack: jurisdictions, parties, candidates, elections with seat allocation, opinion polls,
bills and their stages, regions and social indicators. The polities are fictional, so the pack states nothing about
real politics; the structures (D'Hondt and first-past-the-post seats, poll margins of error, legislative stages) are
the real ones.

    python3 tools/packgen/politics/make.py            write packs/politics-society
    python3 tools/packgen/politics/make.py --check    fail if the pack differs from what would be written
    uv run --with deltalake --with pyarrow --with pyyaml python tools/packgen/politics/make.py --lake data/delta
"""
from __future__ import annotations

import hashlib
import math
import random
import sys
from datetime import date, timedelta
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "common"))
sys.path.insert(0, str(HERE.parent / "banking"))
import packbuild as PB  # noqa: E402
import taxonomy as T  # noqa: E402
from risk_model import Kind, Panel as P  # noqa: E402

G = "Politics and society"
AS_OF = date(2026, 9, 30)
# id: (name, system, seats, population m, capital, regions)
JURISDICTIONS = {"JUR-VAL": ("Republic of Valdoria", "Proportional (D'Hondt)", 240, 38.2, "Aurel", ["North Coast", "Central Plains", "Capital District", "Southern Hills", "Eastern Lakes"]),
                 "JUR-KES": ("Kingdom of Kestria", "First past the post", 180, 12.6, "Brennmoor", ["Highlands", "Riverlands", "Metropolitan", "Isles"])}
PARTIES = {"JUR-VAL": [("PARTY-VAL-PPA", "People's Progressive Alliance", "Centre-left", 0.31), ("PARTY-VAL-CUN", "Civic Union", "Centre-right", 0.28),
                       ("PARTY-VAL-GRN", "Green Future", "Green", 0.12), ("PARTY-VAL-NRV", "National Revival", "Right", 0.14), ("PARTY-VAL-LIB", "Liberal Forum", "Liberal", 0.09),
                       ("PARTY-VAL-OTH", "Others", "—", 0.06)],
           "JUR-KES": [("PARTY-KES-LAB", "Kestrian Labour", "Centre-left", 0.36), ("PARTY-KES-CON", "Constitutional Party", "Centre-right", 0.38),
                       ("PARTY-KES-ISL", "Isles First", "Regionalist", 0.07), ("PARTY-KES-DEM", "Democratic Reform", "Liberal", 0.13), ("PARTY-KES-OTH", "Others", "—", 0.06)]}
FIRST = ["Elena", "Marcus", "Ines", "Tobias", "Amara", "Felix", "Sana", "Victor", "Lucia", "Oskar", "Nadia", "Hugo"]
LAST = ["Varga", "Holm", "Castell", "Ribeiro", "Adeyemi", "Lindqvist", "Moreau", "Petrov", "Achebe", "Duarte", "Sorensen", "Kalnins"]

KINDS = [
    Kind("jurisdiction", "JUR", "JUR-", "Jurisdiction", G, "A country: electoral system, legislature, government and headline social indicators.", "jurisdictionId",
         [("Name", "$.name", None, None, True), ("Capital", "$.capital", None, None, False), ("Population (m)", "$.populationM", "price2", None, False),
          ("Electoral system", "$.system", None, None, False), ("Seats", "$.seats", "amount0", None, False), ("Government", "$.government", None, None, False)],
         [P("hbar", "legislature", "Seats in the legislature", "$.legislature", label="party", value="seats", fmt="amount0", key="F2"),
          P("kv", "indicators", "Indicators", "$.indicators", key="F3", area="right")],
         links={"lastElection": ("election", "Last election"), "latestPoll": ("poll", "Latest poll")}),
    Kind("party", "PARTY", "PARTY-", "Party", G, "A political party: position, leader, seats, and its polling trend.", "partyId",
         [("Party", "$.name", None, None, True), ("Position", "$.position", None, None, False), ("Leader", "$.leaderName", None, None, False),
          ("Seats", "$.seats", "amount0", None, False), ("Polling", "$.polling", "pct0", None, False), ("Members", "$.members", "amount0", None, False)],
         [P("line", "trend", "Polling average", "$.trend", x="month", y="share", fmt="pct0", key="F2"),
          P("kv", "platform", "Platform", "$.platform", area="right")],
         links={"jurisdiction": ("jurisdiction", "Jurisdiction"), "leader": ("candidate", "Leader")}, badge="fmt($.polling, 'pct0')"),
    Kind("candidate", "CAND", "CAND-", "Candidate", G, "A candidate or office holder: party, constituency, results and legislative record.", "candidateId",
         [("Name", "$.name", None, None, True), ("Party", "$.partyName", None, None, False), ("Constituency", "$.constituency", None, None, False),
          ("Role", "$.role", None, None, False), ("Vote share", "$.voteShare", "pct0", None, False), ("Since", "$.since", "date", None, False)],
         [P("table", "record", "Votes on bills", "$.record", [("Bill", "@.bill", None, None, False), ("Vote", "@.vote", None, None, False), ("Date", "@.date", "date", None, False)], key="F2"),
          P("kv", "profile", "Profile", "$.profile", area="right")],
         links={"party": ("party", "Party")}),
    Kind("election", "ELEC", "ELEC-", "Election", G, "A general election: turnout, votes and seats by party, and the regional result.", "electionId",
         [("Election", "$.title", None, None, False), ("Date", "$.date", "date", None, False), ("Turnout", "$.turnout", "pct0", None, True),
          ("System", "$.system", None, None, False), ("Largest party", "$.winner", None, None, False), ("Majority", "$.majority", None, None, False)],
         [P("table", "results", "Results", "$.results", [("Party", "@.party", None, None, False), ("Votes", "@.votes", "amount0", None, False),
            ("Share", "@.share", "pct2", None, True), ("Seats", "@.seats", "amount0", None, True), ("Change", "@.change", "signed0", "sign", False)], key="F2"),
          P("hbar", "turnoutByRegion", "Turnout by region", "$.turnoutByRegion", label="region", value="turnout", fmt="pct0", area="right")],
         links={"jurisdiction": ("jurisdiction", "Jurisdiction")}),
    Kind("poll", "POLL", "POLL-", "Opinion poll", G, "A voting-intention poll: fieldwork, sample, method and shares with margins of error.", "pollId",
         [("Pollster", "$.pollster", None, None, False), ("Fieldwork", "$.fieldwork", None, None, False), ("Sample", "$.sample", "amount0", None, False),
          ("Method", "$.method", None, None, False), ("Lead", "$.lead", None, None, True), ("Margin of error", "$.moe", "pct2", None, False)],
         [P("table", "shares", "Voting intention", "$.shares", [("Party", "@.party", None, None, False), ("Share", "@.share", "pct0", None, True),
            ("Low", "@.low", "pct0", None, False), ("High", "@.high", "pct0", None, False), ("Change", "@.change", "signed2", "sign", False)], key="F2")],
         links={"jurisdiction": ("jurisdiction", "Jurisdiction")}, badge="$.lead"),
    Kind("bill", "BILL", "BILL-", "Bill", G, "A bill in the legislature: sponsor, stage, votes at each reading, and its fiscal cost.", "billId",
         [("Bill", "$.title", None, None, False), ("Sponsor", "$.sponsorName", None, None, False), ("Stage", "$.stage", None, "status", True),
          ("Introduced", "$.introduced", "date", None, False), ("Cost (5y, m)", "$.costM", "amount0", None, False)],
         [P("ladder", "stages", "Stages", "$.stages", [("Date", "@.date", "date", None, False), ("Stage", "@.stage", None, None, False),
            ("For", "@.for", "amount0", None, False), ("Against", "@.against", "amount0", None, False), ("Outcome", "@.outcome", None, "status", False)], key="F2"),
          P("kv", "summary", "Summary", "$.summary", area="right")],
         links={"sponsor": ("candidate", "Sponsor"), "jurisdiction": ("jurisdiction", "Jurisdiction")}, badge="$.stage"),
    Kind("region", "REGN", "REGN-", "Region", G, "A region: population, income, unemployment, education, health and how it voted.", "regionId",
         [("Region", "$.name", None, None, True), ("Population (m)", "$.populationM", "price2", None, False), ("Median income", "$.medianIncome", "amount0", None, False),
          ("Unemployment", "$.unemployment", "pct2", None, False), ("Life expectancy", "$.lifeExpectancy", "price2", None, False)],
         [P("hbar", "vote", "Vote share at the last election", "$.vote", label="party", value="share", fmt="pct0", key="F2"),
          P("line", "unemploymentTrend", "Unemployment rate", "$.unemploymentTrend", x="year", y="rate", fmt="pct2", area="right")],
         links={"jurisdiction": ("jurisdiction", "Jurisdiction")}),
    Kind("social-indicator", "SOCI", "SOCI-", "Social indicator", G, "A social statistic by region over time: poverty, trust, housing, education.", "indicatorId",
         [("Indicator", "$.name", None, None, True), ("Latest", "$.latest", "price2", None, False), ("Unit", "$.unit", None, None, False),
          ("Change (5y)", "$.change5y", "signed2", "sign", False), ("Source", "$.sourceName", None, None, False)],
         [P("line", "series", "National series", "$.series", x="year", y="value", fmt="price2", key="F2"),
          P("hbar", "byRegion", "By region (latest)", "$.byRegion", label="region", value="value", fmt="price2", area="right")],
         links={"jurisdiction": ("jurisdiction", "Jurisdiction")}),
]


def rng(key: str) -> random.Random:
    return random.Random(int(hashlib.sha256(key.encode()).hexdigest()[:12], 16))


def dhondt(votes: dict[str, int], seats: int) -> dict[str, int]:
    out = {p: 0 for p in votes}
    for _ in range(seats):
        best = max(votes, key=lambda p: votes[p] / (out[p] + 1))
        out[best] += 1
    return out


def fptp(shares: dict[str, float], seats: int, r: random.Random) -> dict[str, int]:
    """Winner takes each seat: constituency shares vary around the national share (a cube-law-like outcome)."""
    out = {p: 0 for p in shares}
    for _ in range(seats):
        local = {p: s * r.lognormvariate(0, 0.35) for p, s in shares.items() if p != "Others"}
        out[max(local, key=local.get)] += 1
    return out


def build() -> dict[str, dict[str, dict]]:
    docs = {k.kind: {} for k in KINDS}
    meta = {"source": "elections-office", "generation": 1}
    for jid, (jname, system, seats, pop, capital, regions) in JURISDICTIONS.items():
        r = rng(jid)
        parties = PARTIES[jid]
        electorate = round(pop * 1e6 * 0.76)
        turnout = round(r.uniform(0.61, 0.74), 4)
        cast = round(electorate * turnout)
        shares = {name: s for _, name, _, s in parties}
        votes = {name: round(cast * s) for name, s in shares.items()}
        won = dhondt({p: v for p, v in votes.items() if p != "Others" and v / cast >= 0.05}, seats) if system.startswith("Prop") else fptp(shares, seats, r)
        won = {p: won.get(p, 0) for p in shares}
        winner = max(won, key=won.get)
        eid = f"ELEC-{jid[4:]}-2025"
        docs["election"][eid] = {
            "electionId": eid, "title": f"{jname} general election 2025", "date": date(2025, 5 if jid == "JUR-VAL" else 10, 18).isoformat(), "turnout": turnout,
            "system": system + (" · 5% threshold" if system.startswith("Prop") else ""), "winner": winner,
            "majority": "Majority" if won[winner] * 2 > seats else f"Hung ({won[winner]} of {seats}, {seats // 2 + 1} needed)",
            "results": [{"party": p, "votes": votes[p], "share": round(votes[p] / cast, 4), "seats": won[p], "change": r.randint(-14, 14) if p != "Others" else 0} for p in shares],
            "turnoutByRegion": [{"region": g, "turnout": round(turnout + r.gauss(0, 0.04), 3)} for g in regions], "jurisdiction": jid, "_meta": meta}
        # polls since the election
        poll_ids = []
        state = dict(shares)
        day = date(2026, 1, 12)
        k = 0
        while day <= AS_OF:
            k += 1
            state = {p: max(0.01, s + r.gauss(0, 0.008)) for p, s in state.items()}
            tot = sum(state.values())
            state = {p: s / tot for p, s in state.items()}
            n = r.choice([1000, 1500, 2000, 2400])
            moe = 1.96 * math.sqrt(0.25 / n)
            ranked = sorted(state.items(), key=lambda x: -x[1])
            pid = f"POLL-{jid[4:]}-{k:03d}"
            docs["poll"][pid] = {
                "pollId": pid, "pollster": r.choice(["Meridian Research", "Civica Polling", "Northstar Insight"]),
                "fieldwork": f"{(day - timedelta(days=3)).isoformat()} to {day.isoformat()}", "sample": n, "method": r.choice(["Online panel", "Telephone (mixed mode)"]),
                "lead": f"{ranked[0][0]} +{(ranked[0][1] - ranked[1][1]) * 100:.0f}", "moe": round(moe, 4),
                "shares": [{"party": p, "share": round(s, 3), "low": round(max(0, s - 1.96 * math.sqrt(s * (1 - s) / n)), 3),
                            "high": round(s + 1.96 * math.sqrt(s * (1 - s) / n), 3), "change": round(s - shares[p], 4)} for p, s in ranked],
                "jurisdiction": jid, "_meta": {"source": "poll-aggregator", "generation": 1}}
            poll_ids.append((day, pid, dict(state)))
            day += timedelta(days=14)
        # candidates: two per main party (the leader first)
        cands = {}
        for i, (party_id, pname, pos, share) in enumerate(parties):
            if pname == "Others":
                continue
            for j in range(2):
                rr = rng(party_id + str(j))
                cid = f"CAND-{jid[4:]}-{i * 2 + j + 1:03d}"
                cands.setdefault(party_id, []).append(cid)
                docs["candidate"][cid] = {"candidateId": cid, "name": f"{FIRST[(i * 2 + j + len(jid)) % 12]} {LAST[(i * 3 + j * 5 + len(jid)) % 12]}", "partyName": pname,
                                          "constituency": f"{regions[(i + j) % len(regions)]} {rr.choice(['East', 'West', 'Central'])}",
                                          "role": "Party leader" if j == 0 else rr.choice(["Member of parliament", "Shadow minister", "Committee chair"]),
                                          "voteShare": round(min(0.7, share * rr.uniform(1.1, 1.8)), 3), "since": date(rr.randint(2009, 2025), rr.randint(1, 12), 1).isoformat(),
                                          "record": [], "profile": {"born": rr.randint(1958, 1990), "profession": rr.choice(["Lawyer", "Teacher", "Economist", "Doctor", "Engineer"]),
                                                                    "committees": rr.choice(["Finance", "Health", "Environment", "Justice"])},
                                          "party": party_id, "_meta": meta}
        latest = poll_ids[-1][2]
        for i, (party_id, pname, pos, share) in enumerate(parties):
            if pname == "Others":
                continue
            rr = rng(party_id)
            docs["party"][party_id] = {"partyId": party_id, "name": pname, "position": pos, "leaderName": docs["candidate"][cands[party_id][0]]["name"],
                                       "seats": won[pname], "polling": round(latest[pname], 3), "members": rr.randint(8, 160) * 1000,
                                       "trend": [{"month": d.strftime("%Y-%m-%d"), "share": round(s[pname], 3)} for d, _, s in poll_ids],
                                       "platform": {"economy": rr.choice(["Higher public investment", "Lower taxes, balanced budget", "Green industrial strategy", "Deregulation"]),
                                                    "founded": rr.randint(1921, 2014), "europeanGroup": rr.choice(["S&D", "EPP", "Greens/EFA", "Renew", "ECR"])},
                                       "jurisdiction": jid, "leader": cands[party_id][0], "_meta": meta}
        gov = winner if won[winner] * 2 > seats else f"{winner} coalition"
        docs["jurisdiction"][jid] = {"jurisdictionId": jid, "name": jname, "capital": capital, "populationM": pop, "system": system, "seats": seats, "government": gov,
                                     "legislature": [{"party": p, "seats": s} for p, s in sorted(won.items(), key=lambda x: -x[1]) if s],
                                     "indicators": {"giniCoefficient": round(r.uniform(0.26, 0.38), 3), "trustInGovernment": f"{r.randint(28, 61)}%",
                                                    "medianAge": round(r.uniform(38, 46), 1), "urbanPopulation": f"{r.randint(62, 88)}%", "fictional": True},
                                     "lastElection": eid, "latestPoll": poll_ids[-1][1], "_meta": meta}
        # bills
        topics = [("Clean Energy Transition Act", 4200), ("Affordable Housing Act", 2600), ("Digital Services Tax Act", -1800), ("Public Health Workforce Act", 1500),
                  ("Criminal Justice Reform Act", 300)]
        stages = ["First reading", "Second reading", "Committee", "Third reading", "Royal assent" if jid == "JUR-KES" else "Promulgated"]
        for b, (title, cost) in enumerate(topics):
            rr = rng(jid + title)
            sponsor_party = parties[b % 3][0]
            sponsor = cands[sponsor_party][b % 2]
            reached = rr.randint(1, len(stages))
            d0 = AS_OF - timedelta(days=rr.randint(120, 400))
            st, d = [], d0
            for s in stages[:reached]:
                f = rr.randint(seats // 2 + 1, seats - 20) if s not in ("First reading",) else None
                st.append({"date": d.isoformat(), "stage": s, "for": f, "against": (seats - f - rr.randint(0, 10)) if f else None, "outcome": "Passed"})
                d += timedelta(days=rr.randint(14, 70))
            bid = f"BILL-{jid[4:]}-{2026}-{b + 11:03d}"
            docs["bill"][bid] = {"billId": bid, "title": title, "sponsorName": docs["candidate"][sponsor]["name"], "stage": stages[reached - 1], "introduced": d0.isoformat(),
                                 "costM": cost, "stages": st, "summary": {"policyArea": title.split(" Act")[0], "type": "Government bill" if parties[b % 3][1] == winner else "Private member's bill",
                                                                        "impactAssessment": "Published"}, "sponsor": sponsor, "jurisdiction": jid, "_meta": meta}
            for c in docs["candidate"]:
                if c.startswith(f"CAND-{jid[4:]}") and len(docs["candidate"][c]["record"]) < 4 and len(st) > 1:
                    docs["candidate"][c]["record"].append({"bill": title, "vote": rng(c + title).choice(["For", "For", "Against", "Abstain"]), "date": st[1]["date"]})
        # regions and indicators
        for g in regions:
            rr = rng(jid + g)
            rid = f"REGN-{jid[4:]}-{g.upper().replace(' ', '')[:6]}"
            rshares = {p: s * rr.uniform(0.6, 1.4) for p, s in shares.items()}
            tot = sum(rshares.values())
            docs["region"][rid] = {"regionId": rid, "name": g, "populationM": round(pop / len(regions) * rr.uniform(0.5, 1.6), 2), "medianIncome": round(rr.uniform(24, 52) * 1000, -2),
                                   "unemployment": round(rr.uniform(0.035, 0.11), 4), "lifeExpectancy": round(rr.uniform(77, 84), 1),
                                   "vote": [{"party": p, "share": round(s / tot, 3)} for p, s in sorted(rshares.items(), key=lambda x: -x[1])],
                                   "unemploymentTrend": [{"year": str(y), "rate": round(rr.uniform(0.035, 0.11), 4)} for y in range(2016, 2026)], "jurisdiction": jid, "_meta": meta}
        for name, unit, lo, hi in [("Relative poverty rate", "% of households", 9, 22), ("Trust in parliament", "% who trust", 20, 60),
                                   ("Housing cost overburden", "% of households", 6, 19), ("Tertiary education attainment", "% of 25-34 year-olds", 32, 58)]:
            rr = rng(jid + name)
            sid = f"SOCI-{jid[4:]}-{''.join(w[0] for w in name.split()).upper()}"
            v = rr.uniform(lo, hi)
            series = []
            for y in range(2012, 2026):
                series.append({"year": str(y), "value": round(v, 2)})
                v = min(hi * 1.1, max(lo * 0.8, v + rr.gauss(0, 0.6)))
            docs["social-indicator"][sid] = {"indicatorId": sid, "name": name, "latest": series[-1]["value"], "unit": unit, "change5y": round(series[-1]["value"] - series[-6]["value"], 2),
                                             "sourceName": "National statistics office (fictional)", "series": series,
                                             "byRegion": [{"region": g, "value": round(series[-1]["value"] * rr.uniform(0.75, 1.25), 2)} for g in regions],
                                             "jurisdiction": jid, "_meta": {"source": "statistics-office", "generation": 1}}
    return docs


def spec() -> PB.PackSpec:
    return PB.PackSpec(
        name="politics-society", title="Politics and society", requires=[], generator="tools/packgen/politics/make.py",
        description="Jurisdictions, parties, candidates, elections, opinion polls, bills, regions and social indicators (fictional polities).",
        domains={"civic": KINDS},
        examples=[("JUR JUR-VAL", "Jurisdiction · seats in the legislature"), ("ELEC ELEC-KES-2025", "Election · first-past-the-post result"),
                  ("POLL POLL-VAL-019", "Opinion poll · shares with margins of error"), ("PARTY PARTY-VAL-GRN", "Party · polling trend"),
                  ("BILL BILL-VAL-2026-011", "Bill · stages and votes"), ("SOCI SOCI-KES-TIP", "Social indicator · trust in parliament")],
        overview="Valdoria and Kestria are fictional, so nothing here describes real parties or people. The mechanics are real: Valdoria's seats are "
                 "allocated by D'Hondt with a 5% threshold, Kestria's by first past the post; poll margins of error are 95% intervals for the sample size.",
        roles={"analyst": {"kinds": [k.kind for k in KINDS], "raw": True}})


if __name__ == "__main__":
    PB.main(spec(), build(), T.graph_fields())
