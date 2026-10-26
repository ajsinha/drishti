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
generator: tools/packgen/banking/make_docs.py
sources:
- name: Synthetic sample data (tools/packgen/banking)
  licence: Proprietary (part of Drishti); fictitious names and figures
  internal-only: false
  used-for: every document in samples/ and the demo lake
fields:
  counterparty:
    ECONOMY: The country of risk's economy (world-economy pack, when it is on)
    NEWS_ID: Its headlines (news-and-markets pack, when it is on)
    CREDIT_CURVE: Its credit curve (market-data pack)
    COUNTRY: Country of incorporation (ISO 3166 alpha-2)
    KYC: Know-your-customer record (status, risk rating, last review, EMIR classification, one-year PD)
    CREDIT_LIMIT: Its credit limit (USD)
    HIERARCHY: The group, agreements and netting sets above and below it, as nodes and edges for the hierarchy panel
    NETTING_SETS: Its netting sets (trades, net MTM); their exposure is computed (EXP NSET:<id>), not stored
  counterparty-group:
    MEMBERS: The counterparties in the group
  issuer:
    CREDIT_CURVE: Its credit curve (market-data pack)
    LEI: Legal Entity Identifier
    EQUITY: Its listed equity (market-data pack)
    BONDS: Bonds it has issued
  ccp:
    ACCOUNTS: The bank's clearing accounts at this CCP
  legal-entity:
    DESKS: Its desks
    BOOKS: Its books
  book:
    TOP_TRADES: Its largest trades
    ACCOUNTING_TREATMENT: Accounting classification, e.g. Fair value (FVTPL)
    REGULATORY_BOOK: Trading book or banking book
    VOLCKER: Volcker Rule activity classification, e.g. Market making
  desk:
    BOOKS: Its books
    ASSET_CLASSES: Asset classes it trades
    POSITIONS: Its positions by asset class
  trader:
    BOOKS: Books the trader is authorised on
  calendar:
    HOLIDAYS: Holidays (date, name, weekday, year, observed)
    WEEKEND: Weekend days
  clearing-account:
    TRADES: Trades cleared through the account
---
## What it is

The base of the banking packs: the parties and the organisation every other pack refers to. Counterparties (with country of risk,
UEN, KYC and credit figures), counterparty groups, issuers, central counterparties, the bank's own legal entities, books and
portfolios, desks, traders, holiday calendars and clearing accounts. Every trade, netting set and risk result names its
counterparty, book or legal entity by the ids defined here.
