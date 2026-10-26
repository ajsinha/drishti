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
generator: tools/packgen/funds/make.py
sources:
- name: SEC Form N-PORT data sets
  licence: Public domain (US government work)
  internal-only: false
  used-for: holdings, derivatives and counterparties, flows, monthly returns, the fund-holder and index-funds kinds
- name: SEC Form N-CEN data sets
  licence: Public domain (US government work)
  internal-only: false
  used-for: ETF facts, adviser, custodian, auditor, administrator, transfer agent, authorised participants, share classes
- name: SEC mutual fund prospectus risk/return summary data sets
  licence: Public domain (US government work)
  internal-only: false
  used-for: expense ratio, management fee, portfolio turnover, calendar-year returns
- name: SEC ticker files (company_tickers_mf.json, company_tickers_exchange.json)
  licence: Public domain (US government work)
  internal-only: false
  used-for: share class tickers, the tickers of unit investment trusts
- name: Nasdaq Trader symbol directory
  licence: Public directory, attribution
  internal-only: false
  used-for: which tickers are ETFs and where they list
- name: GLEIF golden copy, level 2 relationships
  licence: CC0
  internal-only: false
  used-for: a fund's manager, umbrella and master fund (managed by, sub-fund of, feeder to)
fields:
  fund:
    TICKER: The fund's ticker (its ETF share class where it has one, else its oldest share class; a trust's listed ticker)
    NAME: The series name as filed
    TYPE: ETF, Mutual fund, Mutual fund with an ETF share class, or ETF (unit investment trust)
    FUND_KEY: The SEC series id, or CIK<cik> for a fund without one
    SERIES_ID: SEC series id (S + 9 digits)
    CIK: SEC Central Index Key of the registrant (10 digits)
    REGISTRANT: The registrant (the trust or company the series belongs to)
    LEI: The fund's LEI (N-PORT)
    REGISTRANT_LEI: The registrant's LEI
    TICKERS: Every share class ticker, the fund's own first (searched by the type-ahead)
    CLASSES: The share classes with their expense ratio and three-month return
    INDEX_ID: The us-equities index the fund tracks (SP500, NDX, DJIA), matched by its name
    REPORT_DATE: The date the N-PORT report is as of
    NET_ASSETS: Net assets (USD, N-PORT)
    EXPENSE_RATIO: Net expense ratio of the fund's share class (percent, prospectus)
    TURNOVER: Portfolio turnover, last fiscal year (percent, prospectus)
    ANNUAL_RETURNS: Calendar-year returns (percent, prospectus bar chart)
    FLOWS: Sales, reinvestments, redemptions and the class return of each of the three months (N-PORT)
    HOLDINGS: The largest holdings (by value) with identifiers, weight, value, asset and issuer type, and links
    TOP_HOLDINGS: The ten largest holdings
    BY_ASSET: Weight by N-PORT asset category
    BY_SECTOR: Weight by SIC division of the us-equities companies held
    DERIVATIVES: The largest derivative positions with their counterparties
    COUNTERPARTIES: The counterparties of derivatives and repos, by notional
    VS_INDEX: "The fund against its index (or the S&P 500 for reference): coverage, active share, weights, tilt"
    ALSO_HELD_BY: The other funds holding the fund's largest us-equities company
    GLEIF_RELATIONSHIPS: GLEIF's fund relationships (managed by, sub-fund of, feeder to)
    PROVENANCE: Source, licence and retrieval date
  fund-holder: {TICKER: The us-equities company's ticker, FUNDS: How many of the pack's funds hold it, HOLDERS: Each fund with the company's weight, value and shares, PCT_OF_SHARES: Shares held by the funds over shares outstanding (percent), TOP_WEIGHTS: The ten largest weights in a fund}
  index-funds: {INDEX: The us-equities index code, FUNDS: How many of the pack's funds track it, ROWS: The funds with net assets, expense ratio, members held and active share}
---
## What it is

The 200 largest US mutual funds and ETFs by net assets, with their holdings, from SEC public data: the newest Form N-PORT report of
each fund (holdings with CUSIP, ISIN and LEI; derivatives with their counterparties; flows and monthly returns), its Form N-CEN census
(ETF creation units and authorised participants, adviser, custodian, auditor) and its prospectus risk/return summary (expense ratio,
turnover, calendar-year returns). A holding links to its us-equities company, another fund of the pack or its GLEIF record; a company
lists the funds that hold it (`HELD`), an index the funds that track it (`TRACK`). No prices or NAV history.

The documents are committed as standard test data, gzipped JSON Lines under `data/testdata/funds/<kind>/` (the fund kind in parts of
about 1.2 MB); `tools/drishti.py lake demo` builds them into the demo lake and the pack's Delta connector `funds-store` serves them.
`samples/` keeps a few reference documents. The guide is `docs/guides/FUNDS_GUIDE.md`.

## Data sources and licences

Every document names its source, licence and retrieval date in `PROVENANCE`. The downloads (about 700 MB a refresh) stay in the data
folder, never in git; `tools/publicdata/funds.py` fetches them with the User-Agent setting the us-equities pack uses.
