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
  position:
    _: "A position Drishti derives from the trades when it loads a date, by the pack's positions: rules (docs/contracts/VAR.md section 3.7): served from the VaR engine by the var-positions connector, never stored"
    POSITION_ID: "POS-<book>~<security> for securities and listed products (fungible: the trades net), POS-<book>~<trade> for an OTC trade"
    POSITION_RULE: "securities, listed or otc: the rule that keyed it"
    DIRECTION: "LONG, SHORT or FLAT: the sign of the net quantity"
    QUANTITY: "The signed sum of the trades' quantities (a buy adds, a sell takes away)"
    TRADE_IDS: "The trades in the position (each links to the trade)"
    TRADES: "Each trade's signed quantity, direction, PV, product and counterparty"
  portfolio:
    _: "A portfolio definition: a hierarchy node, a rule over the positions' columns, or a basket of positions and trades. Portfolios may overlap; their VaR is computed when asked"
    TYPE: "HIERARCHY, RULE or BASKET"
    DEFINITION: "NODE (a hierarchy node), RULE (a condition) or MEMBERS (positions and trades)"
    LIMIT_VAR: "The portfolio's VaR limit (USD), an input"
  pnl-vector:
    _: "One trade's P&L under each of the 509 scenario days, in USD: the grain every VaR is summed from, with the trade's terms the server derives its position from"
    VECTOR_ID: "PV-<trade>-HS-509"
    SECURITY_ID: "The security or exchange contract of a securities or listed trade (the demo's are synthetic)"
    TRADE_STATUS: "The trade's status: NEW, VALIDATED and AMENDED count on the date"
    PNL: "509 values, oldest scenario first, stored as a list<double> column (out of the document)"
  scenario-set:
    _: "The scenario days of a business date: HS-509, historical simulation over 509 business days"
    SCENARIO_DATES: "The 509 scenario days, oldest first: index i of every vector is day i"
  risk-hierarchy:
    _: "A node of a hierarchy: MGMT (bank, division, desk, book) or LEGAL (bank, legal entity, desk, book), with its VaR limit"
    BOOKS: "Every book beneath the node"
    LIMIT_VAR: "The node's VaR limit (USD)"
---
## What it is

Market-risk results over the trading pack's trades: stress scenarios and their results, FRTB standardised sensitivities, P&L explain and
trade valuations (mark-to-market, P&L and sensitivities per trade), and the **VaR engine's inputs** (2.8.0, unreleased): one P&L vector
per live trade over 509 historical scenario days, the scenario set, the MGMT and LEGAL hierarchies with VaR limits, and portfolio
definitions (hierarchy, rule, basket). Value at risk is never stored: the VaR explorer (`VAR DESK DESK-RATES <GO>`,
[VAR_EXPLORER.md](../../../docs/guides/VAR_EXPLORER.md)) computes VaR, ES and contributions for any set of these trades, the portfolio
and position pages (`PORT <id>`, `POS <id>`) show the engine's figures computed as they open, and each business date's official figures
are the engine's end-of-day captures. Positions are Drishti's: derived from the trades by this pack's `positions:` rules (securities by
book and security, listed by book and contract, OTC one per trade) when a date loads ([VAR.md](../../../docs/contracts/VAR.md)).
The stored `var` (VAR-<desk>) and `var-node` kinds were retired in 2.8.0: `VAR VAR-COMM` opens the explorer at `DESK:COMM`.
