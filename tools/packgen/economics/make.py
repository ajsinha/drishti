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

"""The economics pack: real, public macro data for every economy the World Bank and the IMF cover. Economies, headline
indicators per country (history, rank, definition), IMF World Economic Outlook forecasts, labour markets, fiscal
positions and the World Bank regions and income groups. The documents come from tools/realdata (the committed dataset);
nothing is invented, and each document carries its source, dataset, retrieval date and licence.

    python3 tools/packgen/economics/make.py            write config/packs/economics
    python3 tools/packgen/economics/make.py --check    fail if the pack differs from what would be written
    python3 tools/realdata/realdata.py all             refresh the data, then write both packs
"""
from __future__ import annotations

import dataclasses
import os
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

G = "Economics"
SRC = P("kv", "source", "Data source and licence", "$.provenance", area="right")

KINDS = [
    Kind("economy", "ECON", "ECON-", "Economy", G, "An economy: output, growth, prices, jobs, debt and external balance, and output by sector, from World Bank, IMF and CIA data.", "economyId",
         [("Economy", "$.name", None, None, False), ("Region", "$.regionName", None, None, False), ("GDP (USD bn)", "$.gdpUsdBn", "amount0", None, False),
          ("GDP per capita (USD)", "$.gdpPerCapita", "amount0", None, False), ("Real growth", "$.growth", "pc2", "sign", True),
          ("Inflation", "$.inflation", "pc2", None, False), ("Unemployment", "$.unemployment", "pc2", None, False), ("Debt / GDP", "$.debtToGdp", "pc1", None, False),
          ("Current account / GDP", "$.currentAccount", "pc2", "sign", False)],
         [P("map", "geography", "Where it is", None, opts={"highlight": "$.iso3", "link": {"kind": "economy", "id": "ECON-{iso3}"},
                                                         "shade": {"kind": "economy", "field": "$.gdpPerCapita", "by": "$.iso3", "fmt": "amount0", "label": "GDP per capita (USD)"}, "height": 11}),
          P("line", "growthPath", "Real GDP growth, % a year", "$.growthPath", x="year", y="growth", fmt="pc2", key="F2"),
          P("hbar", "sectors", "Output by sector, % of GDP", "$.sectors", label="sector", value="share", fmt="pc1", key="F3", area="right"),
          P("line", "gdpPath", "GDP, USD bn", "$.gdpPath", x="year", y="gdp", fmt="amount0", key="F4"), SRC],
         links={"regionGroup": ("country-group", "Region"), "incomeGroup": ("country-group", "Income group"), "labour": ("labour-market", "Labour market"),
                "fiscal": ("fiscal-position", "Fiscal position"), "outlook": ("economic-forecast", "IMF forecast"), "indicators": ("macro-indicator", "Indicators")},
         badge="fmt($.growth, 'pc2') + ' growth'"),
    Kind("macro-indicator", "MACRO", "MACRO-", "Macro indicator", G, "One headline statistic of one economy: the series, the latest value, the change and the rank among economies.", "indicatorId",
         [("Indicator", "$.name", None, None, False), ("Latest", "$.latest", "price2", None, True), ("Unit", "$.unit", None, None, False),
          ("Year", "$.latestYear", None, None, False), ("Previous", "$.previous", "price2", None, False), ("Rank", "$.rank", "amount0", None, False)],
         [P("line", "history", "History", "$.history", x="year", y="value", fmt="price2", key="F2"),
          P("kv", "facts", "About this series", "$.facts", area="right"), SRC],
         links={"economy": ("economy", "Economy")}, badge="fmt($.latest, 'price2') + ' ' + $.unit"),
    Kind("economic-forecast", "FCST", "FCST-", "Economic forecast", G, "The IMF World Economic Outlook: growth, inflation, unemployment, external balance and debt, outturn and projections to 2031.", "forecastId",
         [("Economy", "$.economyName", None, None, False), ("Vintage", "$.vintage", None, None, False), ("Growth 2027", "$.growth2027", "pc2", "sign", True),
          ("Inflation 2027", "$.inflation2027", "pc2", None, False), ("Debt / GDP 2030", "$.debt2030", "pc1", None, False)],
         [P("table", "projections", "IMF series by year", "$.projections", [("Year", "@.year", None, None, False), ("Basis", "@.basis", None, None, False),
            ("Growth, %", "@.growth", "pc2", "sign", False), ("Inflation, %", "@.inflation", "pc2", None, False), ("Unemployment, %", "@.unemployment", "pc2", None, False),
            ("Current account, % GDP", "@.currentAccount", "pc2", "sign", False), ("Debt, % GDP", "@.debt", "pc1", None, False),
            ("Net lending, % GDP", "@.netLending", "pc2", "sign", False), ("GDP, USD bn", "@.gdp", "amount0", None, False)], key="F2"),
          P("line", "path", "Real GDP growth, % a year (to 2031)", "$.path", x="year", y="growth", fmt="pc2", area="right"), SRC],
         links={"economy": ("economy", "Economy")}),
    Kind("labour-market", "LABR", "LABR-", "Labour market", G, "Unemployment, participation, youth and female labour, and employment and output by sector.", "labourId",
         [("Economy", "$.economyName", None, None, False), ("Unemployment", "$.unemployment", "pc2", None, True), ("Participation", "$.participation", "pc1", None, False),
          ("Youth unemployment", "$.youthUnemployment", "pc1", None, False), ("Employment ratio", "$.employmentRatio", "pc1", None, False),
          ("Female participation", "$.femaleParticipation", "pc1", None, False)],
         [P("line", "unemploymentPath", "Unemployment rate, % of labour force", "$.unemploymentPath", x="year", y="rate", fmt="pc2", key="F2"),
          P("table", "bySector", "Employment and output by sector", "$.bySector", [("Sector", "@.sector", None, None, False), ("Share of jobs, %", "@.employmentShare", "pc1", None, True),
            ("Share of GDP, %", "@.valueAddedShare", "pc1", None, False)], key="F3"), SRC],
         links={"economy": ("economy", "Economy")}),
    Kind("fiscal-position", "FISC", "FISC-", "Fiscal position", G, "Government debt and net lending (IMF) with revenue, expense and military spending (World Bank), as shares of GDP.", "fiscalId",
         [("Economy", "$.economyName", None, None, False), ("Debt / GDP", "$.debt", "pc1", None, False), ("Net lending / GDP", "$.balance", "pc2", "sign", True),
          ("Revenue / GDP", "$.revenue", "pc1", None, False), ("Expense / GDP", "$.spending", "pc1", None, False), ("Military / GDP", "$.military", "pc2", None, False)],
         [P("hbar", "ratios", "Central government, % of GDP", "$.ratios", label="item", value="value", fmt="pc1", key="F2"),
          P("line", "debtPath", "General government gross debt, % of GDP", "$.debtPath", x="year", y="debt", fmt="pc1", area="right"),
          P("line", "balancePath", "Net lending / borrowing, % of GDP", "$.balancePath", x="year", y="balance", fmt="pc2", key="F3"), SRC],
         links={"economy": ("economy", "Economy")}),
    Kind("country-group", "ECG", "ECG-", "Country group", G, "A World Bank region or income group (or the world): output, population, growth and its largest economies.", "groupId",
         [("Group", "$.name", None, None, False), ("Type", "$.groupType", None, None, False), ("Economies", "$.economies", "amount0", None, False),
          ("GDP (USD bn)", "$.gdpUsdBn", "amount0", None, False), ("GDP per capita (USD)", "$.gdpPerCapita", "amount0", None, False),
          ("Population (m)", "$.populationM", "price2", None, False), ("Real growth", "$.growth", "pc2", "sign", True)],
         [P("line", "growthPath", "Real GDP growth, % a year", "$.growthPath", x="year", y="growth", fmt="pc2", key="F2"),
          P("table", "topEconomies", "Largest economies", "$.topEconomies", [("Economy", "@.economy", None, None, False), ("GDP (USD bn)", "@.gdpUsdBn", "amount0", None, True),
            ("Growth, %", "@.growth", "pc2", "sign", False)], key="F3"),
          P("line", "gdpPath", "GDP, USD bn", "$.gdpPath", x="year", y="gdp", fmt="amount0", area="right"), SRC],
         links={"memberEconomies": ("economy", "Economies")}),
]

COLUMNS = {
    "economy": ["name", "iso3", "regionName", "incomeName", "gdpUsdBn", "gdpPerCapita", "growth", "inflation", "unemployment", "debtToGdp"],
    "macro-indicator": ["countryName", "indicator", "latest", "unit", "latestYear", "rank"],
    "economic-forecast": ["economyName", "vintage", "growth2027", "inflation2027", "debt2030"],
    "labour-market": ["economyName", "unemployment", "participation", "youthUnemployment", "employmentRatio"],
    "fiscal-position": ["economyName", "debt", "balance", "revenue", "spending", "military"],
    "country-group": ["name", "groupType", "economies", "gdpUsdBn", "populationM", "growth"]}
FORM = {
    "economy": {"name": {"type": "text", "label": "Name"}, "iso3": {"type": "text", "label": "ISO3 code"}, "regionName": {"type": "enum", "label": "Region"},
                "incomeName": {"type": "enum", "label": "Income group"}, "gdpUsdBn": {"type": "number", "label": "GDP (USD bn)"},
                "gdpPerCapita": {"type": "number", "label": "GDP per capita (USD)"}, "growth": {"type": "number", "label": "Real growth (%)"},
                "inflation": {"type": "number", "label": "Inflation (%)"}, "unemployment": {"type": "number", "label": "Unemployment (%)"},
                "debtToGdp": {"type": "number", "label": "Debt / GDP (%)"}},
    "macro-indicator": {"countryName": {"type": "text", "label": "Economy"}, "indicator": {"type": "enum", "label": "Indicator"}, "latest": {"type": "number", "label": "Latest value"},
                        "unit": {"type": "enum", "label": "Unit"}, "latestYear": {"type": "text", "label": "Year"}, "rank": {"type": "number", "label": "Rank"}},
    "economic-forecast": {"economyName": {"type": "text", "label": "Economy"}, "vintage": {"type": "enum", "label": "Vintage"}, "growth2027": {"type": "number", "label": "Growth 2027 (%)"},
                          "inflation2027": {"type": "number", "label": "Inflation 2027 (%)"}, "debt2030": {"type": "number", "label": "Debt / GDP 2030 (%)"}},
    "labour-market": {"economyName": {"type": "text", "label": "Economy"}, "unemployment": {"type": "number", "label": "Unemployment (%)"},
                      "participation": {"type": "number", "label": "Participation (%)"}, "youthUnemployment": {"type": "number", "label": "Youth unemployment (%)"},
                      "employmentRatio": {"type": "number", "label": "Employment ratio (%)"}},
    "fiscal-position": {"economyName": {"type": "text", "label": "Economy"}, "debt": {"type": "number", "label": "Debt / GDP (%)"}, "balance": {"type": "number", "label": "Net lending / GDP (%)"},
                        "revenue": {"type": "number", "label": "Revenue / GDP (%)"}, "spending": {"type": "number", "label": "Expense / GDP (%)"},
                        "military": {"type": "number", "label": "Military / GDP (%)"}},
    "country-group": {"name": {"type": "text", "label": "Name"}, "groupType": {"type": "enum", "label": "Type"}, "economies": {"type": "number", "label": "Economies"},
                      "gdpUsdBn": {"type": "number", "label": "GDP (USD bn)"}, "populationM": {"type": "number", "label": "Population (m)"}, "growth": {"type": "number", "label": "Real growth (%)"}}}


def _without_imf() -> None:
    """`realdata.py --no-imf`: the pack without the IMF forecast kind, the IMF debt and net lending, and every mention of the IMF."""
    global KINDS, COLUMNS, FORM
    kinds = []
    for k in KINDS:
        if k.kind == "economic-forecast":
            continue
        if k.kind == "economy":
            k = dataclasses.replace(
                k, desc=k.desc.replace("World Bank, IMF and CIA", "World Bank and CIA"), strip=[c for c in k.strip if c[1] != "$.debtToGdp"],
                links={f: v for f, v in k.links.items() if f != "outlook"})
        elif k.kind == "fiscal-position":
            k = dataclasses.replace(
                k, desc="Revenue, expense and military spending (World Bank), as shares of GDP.",
                strip=[("Economy", "$.economyName", None, None, False), ("Revenue / GDP", "$.revenue", "pc1", None, True),
                       ("Expense / GDP", "$.spending", "pc1", None, False), ("Military / GDP", "$.military", "pc2", None, False)],
                panels=[q for q in k.panels if q.id not in ("debtPath", "balancePath")])
        kinds.append(k)
    KINDS = kinds
    COLUMNS = {k: [f for f in v if f != "debtToGdp"] for k, v in COLUMNS.items() if k != "economic-forecast"}
    COLUMNS["fiscal-position"] = ["economyName", "revenue", "spending", "military"]
    FORM = {k: {f: x for f, x in v.items() if f != "debtToGdp"} for k, v in FORM.items() if k != "economic-forecast"}
    FORM["fiscal-position"] = {f: x for f, x in FORM["fiscal-position"].items() if f not in ("debt", "balance")}


NO_IMF = os.environ.get(BD.ENV_NO_IMF) == "1"
if NO_IMF:
    _without_imf()


def build() -> dict[str, dict[str, dict]]:
    return BD.economics()


def spec() -> PB.PackSpec:
    return PB.PackSpec(
        sample=False,
        compact_json=True,
        code="ECO",
        columns=COLUMNS,
        form_overrides=FORM,
        name="economics", title="Economics", requires=[], generator="tools/packgen/economics/make.py",
        description=("Real public economic data for about 200 economies: output, growth, prices, jobs and external balance (World Bank, CIA World Factbook), "
                     "and World Bank regions and income groups." if NO_IMF else
                     "Real public economic data for about 200 economies: output, growth, prices, jobs, debt and external balance (World Bank, IMF, CIA World Factbook), IMF forecasts, "
                     "and World Bank regions and income groups."),
        domains={"macro": KINDS},
        examples=[e for e in [("ECON ECON-IND", "India · growth path, output by sector"), ("ECON ECON-USA", "United States · the same view"), ("MACRO MACRO-BRA-GDP-GROWTH", "Brazil · one indicator with history and rank"),
                  ("FCST FCST-JPN-WEO", "Japan · IMF outlook to 2031"), ("LABR LABR-NGA", "Nigeria · labour market"), ("FISC FISC-USA", "United States · debt and balance"),
                  ("ECG ECG-SAS", "South Asia · a World Bank region")] if not (NO_IMF and e[0].startswith("FCST"))],
        overview=("The figures are published data, not samples: World Bank World Development Indicators (CC BY 4.0) and the CIA World Factbook (public domain). This build has no IMF data. "
                  "Every document names its source, dataset, retrieval date and licence in its Data source panel. An economy links to its region and income "
                  "group, labour market, fiscal position and its headline indicators; the groups link back to their economies. Refresh with `tools/realdata/realdata.py` "
                  "(see docs/guides/REAL_DATA.md)." if NO_IMF else
                  "The figures are published data, not samples: World Bank World Development Indicators (CC BY 4.0), the IMF World Economic Outlook (reuse with attribution), and the CIA "
                  "World Factbook (public domain). Every document names its source, dataset, retrieval date and licence in its Data source panel. An economy links to its region and income "
                  "group, labour market, fiscal position, IMF forecast and its headline indicators; the groups link back to their economies. Refresh with `tools/realdata/realdata.py` "
                  "(see docs/guides/REAL_DATA.md)." " The IMF figures (the forecasts, and the debt and net lending behind each fiscal position) are for **internal demonstrations only**: the IMF requires its permission for commercial use, so raise it before any client or external use (docs/guides/REAL_DATA.md, licence note)."),
        roles={"economist": {"kinds": [k.kind for k in KINDS], "raw": True}})


if __name__ == "__main__":
    PR.run(PB, "economics", spec(), build(), T.graph_fields())
