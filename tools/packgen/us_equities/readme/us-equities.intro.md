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
generator: tools/packgen/us_equities/make.py
sources:
- name: SEC EDGAR (submissions, company facts XBRL)
  licence: Public domain (US government work)
  internal-only: false
  used-for: companies, SIC codes, filings, financial facts
- name: Wikidata
  licence: CC0
  internal-only: false
  used-for: index membership, headquarters, founding, websites
- name: GLEIF LEI records
  licence: CC0
  internal-only: false
  used-for: each company's LEI
- name: Nasdaq Trader symbol directory
  licence: Public directory, attribution
  internal-only: false
  used-for: the listing market
- name: Yahoo Finance (us-equities-prices connector, yfinance plugin)
  licence: Terms forbid redistribution; nothing committed
  internal-only: true
  used-for: price-history, read live only when the connector is on
fields:
  us-company:
    NAME: Company name
    SEC_NAME: Name as registered with the SEC
    CIK: SEC Central Index Key (10 digits)
    OTHER_TICKERS: Other tickers of the same company
    EXCHANGE: Listing exchange code
    LISTING: Listing market name
    SIC: SEC Standard Industrial Classification code
    DIVISION: SIC division letter
    SECTOR: SIC division name
    STATE_OF_INCORPORATION: State of incorporation
    FISCAL_YEAR_END: Fiscal year end (MM-DD)
    FILER_CATEGORY: SEC filer category
    HEADQUARTERS: Headquarters city (Wikidata)
    BUSINESS_ADDRESS: Business address city and state (SEC)
    FOUNDED: Founding date (Wikidata)
    WEBSITE: Official website
    PHONE: Telephone number (SEC)
    LEI: Legal Entity Identifier (GLEIF)
    LEI_LEGAL_NAME: Legal name on the LEI record
    LEI_STATUS: LEI registration status
    LEI_SOURCE: Where the LEI came from
    INDEXES: Index memberships (index, name, since)
    INDEX_TEXT: The indexes, as text for searching
    CURRENCY: The currency its figures are reported in (USD; CAD or GBP for a Canadian or UK bank)
    ASSETS: Total assets, latest fiscal year (USD)
    LIABILITIES: Total liabilities, latest fiscal year (USD)
    EQUITY: Stockholders' equity, latest fiscal year (USD)
    SHARES_OUTSTANDING: Shares outstanding
    FINANCIALS: Ten fiscal years of annual figures
    FACTS: The financial facts held for the company
    FILINGS: Recent filings
    LATEST_FILING: Date of the latest filing
    PRICE_HISTORY: Id of its price-history document (read live, internal only)
    PROVENANCE: Source, retrieval date, licence and URL
  index-membership: {INDEX: Index code (DJIA, NDX, SP500) or bank list (BANKS-US, BANKS-CA, BANKS-UK), ROWS: The members (the totals and divisions are computed on the page), PROVENANCE: Source, retrieval date, licence and URL}
  sic-sector: {DIVISION: SIC division letter, ROWS: The companies of the code (computed), BY_REVENUE: Companies by revenue (USD reporters), PROVENANCE: Source and licence, NON_USD: Companies reporting in another currency (not summed), NET_MARGIN: Net income over revenue (%), BY_DIVISION: Revenue by division (SIC ALL)}
  filing-feed: {FEED: Feed id (a form type or ALL), FORM: The form type, ROWS: The filings, BY: Filings by form, BY_TITLE: Filings by company, PROVENANCE: Source and licence}
  filing:
    ACCESSION: SEC accession number
    COMPANY_NAME: The company's name
    PRIMARY_DOCUMENT: File name of the primary document on EDGAR
    DOCUMENT_DESCRIPTION: EDGAR's description of the document
    SIZE_KB: Size of the filing (KB)
    ITEMS: Items reported (8-K)
    ITEMS_TEXT: The items, as text
    PATH: EDGAR archive path (CIK/accession without dashes)
  financial-fact: {FACT_ID: Ticker and concept, COMPANY_NAME: The company's name, CIK: SEC Central Index Key, HISTORY: Values by fiscal year}
---
## What it is

Public data on the companies of the Dow 30, the Nasdaq-100 and the S&P 500, and on the major US, Canadian and UK banks that file
with the SEC (three lists kept by Drishti: `IDX BANKS-US`, `IDX BANKS-CA`, `IDX BANKS-UK`): their SEC filings, ten fiscal years of
annual financials from their XBRL filings (a 10-K, or a foreign bank's 20-F or 40-F in the currency it reports in, `CURRENCY`), their
LEI, their SIC sector and the indexes and lists they belong to. Banks with no SEC filings (National Bank of Canada, Standard Chartered,
Nationwide) are not here; the `gleif` pack covers them. No index weights or
levels, no GICS and no prices are shipped: `price-history` is read live by the `us-equities-prices` connector, off by default and for
internal use only.

The documents are committed as standard test data, one snapshot per kind under `data/testdata/us-equities/<kind>/`;
`tools/drishti.py lake demo` builds them into the demo lake and the pack's Delta connector `us-equities-store` serves them, so the pack
works with the demo connector off. `samples/` keeps only a few reference documents.

## Data sources and licences

Every document names its source, retrieval date and licence in `PROVENANCE`. Filings are metadata and links only; the documents stay
on sec.gov.
