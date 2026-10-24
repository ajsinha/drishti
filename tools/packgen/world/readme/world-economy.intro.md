<!--
  Project Drishti · Any data. Any domain. One grammar.

  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
  All rights reserved.

  PROPRIETARY AND CONFIDENTIAL.

  This file is the confidential and proprietary property of Ashutosh Sinha.
  Unauthorised copying, use, modification, distribution or disclosure of this
  file, via any medium, is strictly prohibited except with the express prior
  written permission of the copyright holder.

  See the LICENSE file in the root of this repository for the full terms.
-->
---
generator: tools/packgen/world/make.py
sources:
- name: World Bank World Development Indicators and Worldwide Governance Indicators
  licence: CC BY 4.0
  internal-only: false
  used-for: economies, indicators, labour markets, regions, social and governance indicators
- name: IMF World Economic Outlook
  licence: Reuse with attribution; commercial use needs the IMF's permission
  internal-only: true
  used-for: economic forecasts, and the debt and net lending of each fiscal position
- name: CIA World Factbook
  licence: Public domain
  internal-only: false
  used-for: governments, office holders, elections, parties
- name: UNDP Human Development Index
  licence: CC BY 3.0 IGO
  internal-only: false
  used-for: social indicators
fields:
  economy: {ISO3: ISO 3166 alpha-3 code, INCOME_NAME: World Bank income group, CURRENCY: Currency code, CAPITAL: Capital city, YEAR: Year of the headline figures, RESERVES_USD_BN: Total reserves including gold (USD bn), TRADE_SHARE: Trade as a share of GDP (%), POPULATION_M: Population (millions)}
  macro-indicator: {INDICATOR: Indicator name, COUNTRY_NAME: The country, DEFINITION: The source's definition of the indicator, PREVIOUS_YEAR: Year of the previous value, OF_COUNT: Countries ranked}
  labour-market: {YEAR: Year of the figures, VULNERABLE: Vulnerable employment (% of total employment)}
  fiscal-position: {YEAR: Year of the figures}
  country-group: {YEAR: Year of the figures, INFLATION: Inflation, consumer prices (% a year)}
  region-economy: {ISO3: The country's ISO alpha-3 code, YEAR: Year of the figures, POPULATION: Population, SOURCE: Where the figures come from}
  jurisdiction: {ISO3: ISO alpha-3 code, OFFICIAL_NAME: Official name, REGION_NAME: World Bank region, INDEPENDENCE: Independence (as the Factbook writes it), LEGISLATURE_NAME: The legislature's name, LEGISLATURE_STRUCTURE: Unicameral or bicameral}
  party: {RANK: Rank by seats, TOTAL_SEATS: Seats in the chamber}
  office-holder: {PUBLISHED: The office holder as the Factbook writes it, PROCESS: How the office is filled, ELECTION_HELD: When the last election was held, NEXT_ELECTION: When the next is due, RESULTS: The last result as published, CABINET: How the cabinet is formed}
  election: {COUNTRY_NAME: The country, CHAMBER: The chamber or office elected, DATE: Election date, TERM: Term of office, WOMEN: Share of seats held by women, SEATS_TEXT: Seats as published}
  region: {YEAR: Year of the figures, URBAN: Urban population (% of total)}
  social-indicator: {INDICATOR: Indicator name, COUNTRY_NAME: The country, THEME: Theme of the indicator, DEFINITION: The source's definition, REGION_NAME: World Bank region}
---
## What it is

Published data on the world's economies and governments: economies with their headline indicators, macro indicators by country,
IMF forecasts, labour markets, fiscal positions, country groups and regional economies; and jurisdictions, parties with seats,
office holders, elections, regions and social and governance indicators. A core counterparty links to its country's economy.

## Data sources and licences

The IMF figures (the forecasts, and the debt and net lending behind each fiscal position) are for **internal demonstrations only**:
raise them before any client or external use (docs/guides/REAL_DATA.md, licence note). Every document names its source, dataset,
retrieval date and licence.
