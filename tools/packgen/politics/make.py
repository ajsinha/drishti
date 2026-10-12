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

"""The politics and society pack: real, public data for about 200 countries. Jurisdictions (government type, capital,
legislature and the seats its parties won), office holders (chief of state and head of government as the CIA World
Factbook publishes them), elections and parties (the most recent chamber results the Factbook lists), world regions, and
social and governance indicators (World Bank WDI and Worldwide Governance Indicators, UNDP Human Development Index). The
documents come from tools/realdata (the committed dataset); each carries its source, dataset, retrieval date and licence.
Opinion polls and bills are not in any open, global dataset, so the pack has no such kinds.

    python3 tools/packgen/politics/make.py            write config/packs/politics-society
    python3 tools/packgen/politics/make.py --check    fail if the pack differs from what would be written
    python3 tools/realdata/realdata.py all            refresh the data, then write both packs
"""
from __future__ import annotations

import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "common"))
sys.path.insert(0, str(HERE.parent / "banking"))
sys.path.insert(0, str(HERE.parent.parent / "realdata"))
import packbuild as PB  # noqa: E402
import taxonomy as T  # noqa: E402
import build_docs as BD  # noqa: E402
import packrun as PR  # noqa: E402
from risk_model import Kind, Panel as P  # noqa: E402

G = "Politics and society"
SRC = P("kv", "source", "Data source and licence", "$.provenance", area="right")

KINDS = [
    Kind("jurisdiction", "JUR", "JUR-", "Jurisdiction", G, "A country: government type, capital, legislature and seats by party, office holders, elections and governance indicators, from the CIA World Factbook, World Bank and UNDP.", "jurisdictionId",
         [("Country", "$.name", None, None, True), ("Capital", "$.capital", None, None, False), ("Government", "$.governmentType", None, None, False),
          ("Population (m)", "$.populationM", "price2", None, False), ("Area (km2)", "$.areaKm2", "amount0", None, False), ("Currency", "$.currency", None, None, False)],
         [P("map", "geography", "Where it is", None, opts={"highlight": "$.iso3", "link": {"kind": "jurisdiction", "id": "JUR-{iso3}"}, "height": 11}),
          P("table", "legislature", "Seats by party, lower chamber", "$.legislature", [("Party", "@.party", None, None, False), ("Seats", "@.seats", "amount0", None, True)], key="F2"),
          P("table", "indicators", "Society and governance (latest)", "$.indicators", [("Indicator", "@.indicator", None, None, False), ("Value", "@.value", "price2", None, True),
            ("Year", "@.year", None, None, False), ("Unit", "@.unit", None, None, False)], key="F3"),
          P("kv", "profile", "Profile", "$.profile", area="right"),
          P("table", "partiesListed", "Political parties (as published)", "$.partiesListed", [("Party", "@.party", None, None, False)], key="F4"), SRC],
         links={"region": ("region", "World region"), "chiefOfState": ("office-holder", "Chief of state"), "headOfGovernment": ("office-holder", "Head of government"),
                "lastElection": ("election", "Last election"), "elections": ("election", "Elections"), "parties": ("party", "Parties with seats"),
                "socialIndicators": ("social-indicator", "Indicators")}),
    Kind("party", "PARTY", "PARTY-", "Party", G, "A political party that won seats in the lower chamber at the most recent election, with the chamber's full result.", "partyId",
         [("Party", "$.name", None, None, True), ("Country", "$.countryName", None, None, False), ("Seats", "$.seats", "amount0", None, False),
          ("Seat share, %", "$.seatShare", "pc1", None, False), ("Chamber", "$.chamber", None, None, False), ("Election", "$.electionDate", None, None, False)],
         [P("hbar", "results", "Seats in the chamber", "$.results", label="party", value="seats", fmt="amount0", key="F2"), SRC],
         links={"election": ("election", "Election"), "jurisdiction": ("jurisdiction", "Country")}, badge="fmt($.seats, 'amount0') + ' seats'"),
    Kind("office-holder", "OFFC", "OFFC-", "Office holder", G, "The chief of state or head of government of a country as the CIA World Factbook publishes them, with how the office is filled.", "officeId",
         [("Country", "$.countryName", None, None, False), ("Role", "$.role", None, None, False), ("Holder", "$.holder", None, None, True), ("Since", "$.since", None, None, False),
          ("Government", "$.governmentType", None, None, False)],
         [P("kv", "mandate", "How the office is filled", "$.mandate", key="F2"), SRC],
         links={"jurisdiction": ("jurisdiction", "Country")}),
    Kind("election", "ELEC", "ELEC-", "Election", G, "The most recent election to a chamber or to the executive, with the system, term, seats by party and the date of the next one.", "electionId",
         [("Election", "$.title", None, None, False), ("Held", "$.dateText", None, None, False), ("System", "$.system", None, None, False),
          ("Largest party", "$.winner", None, None, True), ("Next election", "$.nextElection", None, None, False)],
         [P("table", "results", "Seats by party", "$.results", [("Party", "@.party", None, None, False), ("Seats", "@.seats", "amount0", None, True), ("Share, %", "@.share", "pc1", None, False)], key="F2"),
          P("kv", "summary", "Election facts (as published)", "$.summary", area="right"), SRC],
         links={"jurisdiction": ("jurisdiction", "Country")}),
    Kind("region", "REGN", "REGN-", "World region", G, "A World Bank region: its countries, population, life expectancy, women in parliament, Internet use and the government types found in it.", "regionId",
         [("Region", "$.name", None, None, True), ("Countries", "$.countries", "amount0", None, False), ("Population (m)", "$.populationM", "price2", None, False),
          ("Life expectancy", "$.lifeExpectancy", "price2", None, False), ("Women in parliament, %", "$.womenParliament", "pc1", None, False), ("Internet users, %", "$.internet", "pc1", None, False)],
         [P("hbar", "governmentTypes", "Government types (number of countries)", "$.governmentTypes", label="type", value="count", fmt="amount0", key="F2"),
          P("line", "lifeTrend", "Life expectancy at birth, years", "$.lifeTrend", x="year", y="value", fmt="price2", area="right"),
          P("line", "internetTrend", "Internet users, % of population", "$.internetTrend", x="year", y="value", fmt="pc1", key="F3"), SRC],
         links={"memberStates": ("jurisdiction", "Countries")}),
    Kind("social-indicator", "SOCI", "SOCI-", "Social indicator", G, "A social or governance statistic of one country over time, with the regional comparison: health, education, inequality, rights and rule of law.", "indicatorId",
         [("Indicator", "$.name", None, None, True), ("Latest", "$.latest", "price2", None, False), ("Unit", "$.unit", None, None, False),
          ("Year", "$.latestYear", None, None, False), ("Change (5y)", "$.change5y", "signed2", "sign", False), ("Source", "$.sourceName", None, None, False)],
         [P("line", "series", "Series", "$.series", x="year", y="value", fmt="price2", key="F2"),
          P("hbar", "byRegion", "Region comparison (latest)", "$.byRegion", label="country", value="value", fmt="price2", area="right"),
          P("kv", "meaning", "What it measures", "$.meaning", key="F3"), SRC],
         links={"jurisdiction": ("jurisdiction", "Country")}),
]

COLUMNS = {
    "jurisdiction": ["name", "iso3", "regionName", "capital", "governmentType", "populationM", "currency"],
    "party": ["name", "countryName", "seats", "seatShare", "chamber"],
    "office-holder": ["countryName", "role", "holder", "since", "governmentType"],
    "election": ["title", "dateText", "system", "winner", "nextElection"],
    "region": ["name", "countries", "populationM", "lifeExpectancy", "womenParliament"],
    "social-indicator": ["countryName", "indicator", "theme", "latest", "unit", "latestYear"]}
FORM = {
    "jurisdiction": {"name": {"type": "text", "label": "Country"}, "iso3": {"type": "text", "label": "ISO3 code"}, "regionName": {"type": "enum", "label": "World region"},
                     "capital": {"type": "text", "label": "Capital"}, "governmentType": {"type": "text", "label": "Government type"},
                     "populationM": {"type": "number", "label": "Population (m)"}, "currency": {"type": "enum", "label": "Currency"}},
    "party": {"name": {"type": "text", "label": "Party"}, "countryName": {"type": "text", "label": "Country"}, "seats": {"type": "number", "label": "Seats"},
              "seatShare": {"type": "number", "label": "Seat share (%)"}, "chamber": {"type": "text", "label": "Chamber"}},
    "office-holder": {"countryName": {"type": "text", "label": "Country"}, "role": {"type": "enum", "label": "Role"}, "holder": {"type": "text", "label": "Holder"},
                      "since": {"type": "text", "label": "Since"}, "governmentType": {"type": "text", "label": "Government type"}},
    "election": {"title": {"type": "text", "label": "Election"}, "dateText": {"type": "text", "label": "Held"}, "system": {"type": "text", "label": "System"},
                 "winner": {"type": "text", "label": "Largest party"}, "nextElection": {"type": "text", "label": "Next election"}},
    "region": {"name": {"type": "text", "label": "Region"}, "countries": {"type": "number", "label": "Countries"}, "populationM": {"type": "number", "label": "Population (m)"},
               "lifeExpectancy": {"type": "number", "label": "Life expectancy"}, "womenParliament": {"type": "number", "label": "Women in parliament (%)"}},
    "social-indicator": {"countryName": {"type": "text", "label": "Country"}, "indicator": {"type": "enum", "label": "Indicator"}, "theme": {"type": "enum", "label": "Theme"},
                         "latest": {"type": "number", "label": "Latest value"}, "unit": {"type": "enum", "label": "Unit"}, "latestYear": {"type": "text", "label": "Year"}}}


def build() -> dict[str, dict[str, dict]]:
    docs = BD.politics()
    for d in docs["office-holder"].values():              # the mandate panel: how the office is filled, as published
        d["mandate"] = {k: v for k, v in (("selection", d.get("process")), ("heldOn", d.get("electionHeld")), ("nextElection", d.get("nextElection")),
                                          ("results", d.get("results")), ("asPublished", d.get("published")), ("cabinet", d.get("cabinet"))) if v}
    for d in docs["social-indicator"].values():
        d["meaning"] = {"indicator": d["indicator"], "unit": d["unit"], "definition": d["definition"], "comparedWith": f"countries of {d['regionName']} (latest value, 2022 or later)"}
    return docs


def spec() -> PB.PackSpec:
    return PB.PackSpec(
        sample=False,
        compact_json=True,
        code="POLS",
        columns=COLUMNS,
        form_overrides=FORM,
        name="politics-society", title="Politics and society", requires=[], generator="tools/packgen/politics/make.py",
        description="Real public data on governments and societies for about 200 countries: government type, office holders, elections, parties with seats, and social and governance "
                    "indicators (CIA World Factbook, World Bank, UNDP).",
        domains={"civic": KINDS},
        examples=[("JUR JUR-IND", "India · government, legislature, governance indicators"), ("JUR JUR-USA", "United States · the same view"), ("ELEC ELEC-BRA-LOW", "Brazil · lower-chamber result"),
                  ("OFFC OFFC-JPN-HOG", "Japan · head of government"), ("PARTY PARTY-IND-01", "India · largest party in the lower chamber"), ("REGN REGN-SSF", "Sub-Saharan Africa · a World Bank region"),
                  ("SOCI SOCI-NGA-WGI-LAW", "Nigeria · rule of law")],
        overview="The data is published data, not samples: the CIA World Factbook (public domain) for governments, office holders, elections and parties, the World Bank World Development "
                 "Indicators and Worldwide Governance Indicators (CC BY 4.0), and the UNDP Human Development Index (CC BY 3.0 IGO). Texts are shown as the sources publish them; every "
                 "document names its source, dataset, retrieval date and licence in its Data source panel. Opinion polls and bills have no open global dataset, so this pack has no such "
                 "kinds. Refresh with `tools/realdata/realdata.py` (see docs/guides/REAL_DATA.md).",
        roles={"analyst": {"kinds": [k.kind for k in KINDS], "raw": True}})


if __name__ == "__main__":
    PR.run(PB, "politics", spec(), build(), T.graph_fields())
