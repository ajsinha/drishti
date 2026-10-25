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
  position:
    _: "A holding of one instrument in one book, netting the trades that build or reduce it (VAR_DATA_MODEL.md section 18). Data: tools/packgen/var/make_var_data.py."
    POSITION_ID: "POS-<book>~<instrument>: one position per instrument per book on a business date"
    INSTRUMENT_ID: "The instrument: product, currency, underlying and maturity quarter"
    DIRECTION: "LONG, SHORT or FLAT: the sign of the net quantity"
    QUANTITY: "The signed sum of the trades' quantities (a buy adds, a sell takes away)"
    TRADE_IDS: "The trades in the position (each links to the trade)"
    TRADE_QUANTITIES: "Each trade's signed quantity, its own VaR and its component of the position's VaR"
    COMPONENT_VAR: "The position's share of its book's VaR (Euler; the shares add up to the book's VaR)"
    PNL: "The position's P&L under the 509 scenario days: the sum of its trades' vectors (a list<double> column in the lake)"
  portfolio:
    _: "A set of positions: a hierarchy node, a rule over the positions' columns, or a basket of positions and trades. Portfolios may overlap."
    TYPE: "HIERARCHY, RULE or BASKET"
    DEFINITION: "NODE (a hierarchy node), RULE (a condition) or MEMBERS (positions and trades)"
    POSITIONS: "The positions on the date, each with its VaR and component VaR"
    PNL: "The portfolio's P&L under the scenario days, each trade counted once"
  pnl-vector:
    _: "One trade's P&L under each of the 509 scenario days, in USD: the grain every VaR is summed from"
    VECTOR_ID: "PV-<trade>-HS-509"
    POSITION_ID: "The position the trade belongs to"
    PNL: "509 values, oldest scenario first, stored as a list<double> column (out of the document)"
  scenario-set:
    _: "The scenario days of a business date: HS-509, historical simulation over 509 business days"
    SCENARIO_DATES: "The 509 scenario days, oldest first: index i of every vector is day i"
  risk-hierarchy:
    _: "A node of a hierarchy: MGMT (bank, division, desk, book) or LEGAL (bank, legal entity, desk, book), with its VaR limit"
    BOOKS: "Every book beneath the node"
    LIMIT_VAR: "The node's VaR limit (USD)"
  var-node:
    _: "A node's official VaR, ES and hypothetical P&L on one business date (250 days kept: the backtest)"
    HYPO_PNL: "The node's P&L of the day with its positions unchanged"
    EXCEPTION: "True when the day's loss exceeded the previous day's VaR"
---
## What it is

Market-risk results over the trading pack's trades: value at risk and expected shortfall by desk, stress scenarios and their results,
FRTB standardised sensitivities, P&L explain and trade valuations (mark-to-market, P&L and sensitivities per trade). Since 2.8.0
(unreleased) also the **VaR engine's data**: positions (holdings netting their trades), portfolios (hierarchy, rule, basket), one P&L
vector per trade over 509 historical scenario days, the MGMT and LEGAL hierarchies with VaR limits and every node's 250-day history.
The VaR explorer (`VAR DESK DESK-RATES <GO>`, [VAR_EXPLORER.md](../../../docs/guides/VAR_EXPLORER.md)) computes VaR, ES and
contributions for any set of these trades; `PORT <id>` and `POS <id>` open a portfolio's and a position's pages.
