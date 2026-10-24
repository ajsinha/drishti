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
  trade:
    SOURCE_UPDATE_TIME: When the booking system last changed the trade
    INPUT_DATE: The day the trade was entered
    TRADE_STATUS: Life-cycle status (VALIDATED, NEW, AMENDED, MATURED, CANCELLED)
    TRADE_ACTION: The last action on the trade (empty when none)
    TRADE_VERSION: The booking system's version number of the trade
    TRANSACTION_ID: The booking system's transaction number
    PACKAGE_ID: The package the trade belongs to (null when none)
    QUANTITY: Quantity or notional in the trade's unit
    BUY_SELL_CODE: B (bought) or S (sold)
    PRODUCT_TYPE: The product type; selects the trade's Sutra (one per product type)
    PRODUCT_SUB_TYPE: The product sub-type; selects the panels shown
    PRODUCT_GROUP: Product group, e.g. FX
    PRODUCT_SUB_GROUP: Product sub-group, e.g. linear
    OPTION_TYPE: Call or Put, for options
    STRIKE_PRICE: Strike, for options
    COUNTERPARTY: The counterparty's id (core pack)
    COUNTERPARTY_UEN: The counterparty's unique entity number
    COUNTERPARTY_COUNTRY_OF_RISK: The counterparty's country of risk (ISO alpha-2)
    ORIGINAL_COUNTERPARTY: The counterparty before a novation or clearing
    ORIGINAL_COUNTERPARTY_UEN: Its unique entity number
    INTERNAL_EXTERNAL_CODE: Internal (between the bank's own books) or External
    TRANSIT: The branch transit number
    LEGAL_ENTITY: The bank's legal entity that booked it (core pack)
    PORTFOLIO: The book (portfolio) id (core pack)
    TRADER_ID: The trader's id (core pack)
    TRADER_FIRST_NAME: The trader's first name or initial
    TRADER_LAST_NAME: The trader's last name
    START_DATE: Start (effective) date
    REGISTRY_DELIVERY_DATE: Registry delivery date, for emissions and registered products
    CURRENCY: The trade's currency
    DELIVERY_VERSUS_PAYMENT: YES when settled delivery versus payment
    STRATEGY: The trading strategy (null when none)
    INTERNAL_TRADING_STRATEGY: The internal strategy code (null when none)
    NY_CURVE_FLAG: The booking system's New York curve flag (null when not set)
    VAULT_LOCATION_ALLOCATION: Vault the metal is allocated to, for precious metals
    PRECIOUS_METAL_FLAG: Y for a precious-metals trade
    TT_GROUP: The desk the trade is grouped under for transfer pricing
    TT_BOOK: The book for transfer pricing
    LEASE_PERPETUITY_FLAG: Y for a perpetual metal lease
    SETTLEMENT_DATE: Settlement date
    LEGS: The legs, each with its index and cash flows (and their resets)
    EVENTS: Cash and life-cycle events, each naming its leg by LEG_ID
---
## What it is

Trades in 125 products across rates, FX, credit, equity, commodities, inflation, fixed income, securities financing, money markets
and structured products, as three booking systems (ENDUR, CALYPSO, MUREX) write them: one common trade shape in UPPER_SNAKE names,
47 top-level attributes, `LEGS[]` with an `INDEX` and `CASH_FLOWS[]` (each flow with `RESETS[]`), and `EVENTS[]`. The pack also
derives pages of trades by counterparty, portfolio, legal entity and index (the `trade-pages` connector).
