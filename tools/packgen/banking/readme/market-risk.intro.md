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
  var:
    MEAN_PNL: Mean scenario P&L (USD)
    PNL_SERIES: Scenario P&L values
    CONTRIBUTIONS: VaR contribution by book
    DESK: The desk's id (core pack)
    METHOD: Method, e.g. historical simulation, 1-day, 99%, 500 scenarios
  stress-scenario: {SHOCKS: The shocks applied, by risk factor, HORIZON: Horizon of the scenario}
  stress-result: {BY_BOOK: Stress P&L by book, DESK: The desk's id}
  frtb-sensitivity: {SENSITIVITIES: Sensitivities by risk factor, BY_CLASS: Capital by risk class, APPROACH: The FRTB approach, e.g. standardised (SBM)}
  pnl-explain: {ATTRIBUTION: P&L by risk factor, EXPLAIN_STEPS: The explain steps in order, BOOK: The book's id}
  trade-valuation:
    TRADE_ID: The trade valued (trading pack)
    PORTFOLIO: The book's id
    DESK: The desk's id
    COUNTERPARTY: The counterparty's id
    NETTING_SET: The netting set's id
    TRADER_ID: The trader's id
    BUY_SELL_CODE: B or S
    MTM_CURRENCY: Currency of the mark-to-market
    TRADE_DATE: The trade's trade date
---
## What it is

Market-risk results over the trading pack's trades: value at risk and expected shortfall by desk, stress scenarios and their results,
FRTB standardised sensitivities, P&L explain and trade valuations (mark-to-market, P&L and sensitivities per trade).
