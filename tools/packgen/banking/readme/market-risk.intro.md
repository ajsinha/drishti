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
    _: "A position Drishti derives from the trades when it loads a date, by the pack's positions: rules (docs/guides/VAR_GUIDE.md section 15.6): served from the VaR engine by the var-positions connector, never stored"
    POSITION_ID: "POS-<book>~<security> for securities and listed products (fungible: the trades net), POS-<book>~<trade> for an OTC trade"
    POSITION_RULE: "securities, listed or otc: the rule that keyed it"
    DIRECTION: "LONG, SHORT or FLAT: the sign of the net quantity"
    QUANTITY: "The signed sum of the trades' quantities (a buy adds, a sell takes away)"
    TRADE_IDS: "The trades in the position (each links to the trade)"
    TRADES: "Each trade's signed quantity, direction, PV, product and counterparty"
  system-basket:
    _: "A system basket: one of the bank's own baskets (PORT <id>), kept in Drishti's basket store, never read from data (docs/guides/VAR_GUIDE.md section 5.7): a hierarchy node, a rule over the positions' columns, or a basket of positions and trades; served by the var-system-baskets connector. System baskets may overlap; their VaR is computed when asked"
    TYPE: "HIERARCHY, RULE or BASKET"
    DEFINITION: "NODE (a hierarchy node), RULE (a condition) or MEMBERS (positions and trades)"
    VALID_TO: "The last business date it applies to (set when it is retired); its VaR limit is a var-limit row (HIERARCHY PORTFOLIO)"
  pnl-vector:
    _: "One trade's P&L under each of the 509 scenario days, in USD: the grain every VaR is summed from, with the trade's terms the server derives its position from"
    VECTOR_ID: "PNL-<trade or position>-HS-509 (PV- also read)"
    LIMIT_LETTER: "The limit letter the trade's VaR limit is granted under: the hierarchy LL, LIMIT_LETTER > DESK, is built from it"
    SECURITY_ID: "The security or exchange contract of a securities or listed trade (the demo's are synthetic)"
    TRADE_STATUS: "The trade's status: NEW, VALIDATED and AMENDED count on the date"
    PNL: "509 values, oldest scenario first, stored as a list<double> column (out of the document)"
  scenario-set:
    _: "The scenario days of a business date: HS-509, historical simulation over 509 business days"
    SCENARIO_DATES: "The 509 scenario days, oldest first: index i of every vector is day i"
  var-limit:
    _: "A node's 1-day 99% VaR limit by hierarchy and node id, in USD: the limit letters' (LL) and the MGMT and LEGAL nodes' own"
    NODE_ID: "The node, as the engine builds it: LL~LL-0003, LL~LL-0003/DESK-RATES, MGMT~DESK-RATES"
    LIMIT_VAR: "The limit (USD); a node without a row has none"
---
## What it is

Market-risk results over the trading pack's trades: stress scenarios and their results, FRTB standardised sensitivities, P&L explain and
trade valuations (mark-to-market, P&L and sensitivities per trade), and the **VaR engine's inputs**: one P&L vector
per live trade over 509 historical scenario days, the scenario set and the VaR limits of the hierarchies' nodes and of the system
baskets (`var-limit`). The system baskets themselves (`PORT <id>`: a hierarchy node, a rule or a basket) are kept in Drishti's basket
store, not data: the demo's are this pack's seed, `seeds/system-baskets.yaml`, imported once at a demo's first start
([VAR_GUIDE.md section 5.7](../../../docs/guides/VAR_GUIDE.md#57-system-baskets)). The hierarchies themselves are configured in this pack's `hierarchies:` block (any
number): MGMT (`DIVISION > DESK > TT_BOOK`), LEGAL (`LEGAL_ENTITY > DESK > TT_BOOK`) and the limit letters LL (`LIMIT_LETTER > DESK`),
each built by the server from the trades' promoted columns ([VAR_GUIDE.md section 15.14](../../../docs/guides/VAR_GUIDE.md#1514-hierarchies-from-promoted-columns)). Value at risk is never stored: the VaR explorer (`VAR DESK DESK-RATES <GO>`,
[VAR_GUIDE.md section 3](../../../docs/guides/VAR_GUIDE.md#3-the-explorer-screen-by-screen)) computes VaR, ES and contributions for any set of these trades, the system basket
and position pages (`PORT <id>`, `POS <id>`) show the engine's figures computed as they open, and each business date's official figures
are the engine's end-of-day captures. Positions are Drishti's: derived from the trades by this pack's `positions:` rules (securities by
book and security, listed by book and contract, OTC one per trade) when a date loads ([VAR_GUIDE.md Part II](../../../docs/guides/VAR_GUIDE.md#14-the-contract-at-a-glance)).
There is no stored `var` (VAR-<desk>) or `var-node` kind: `VAR VAR-COMM` opens the explorer at `DESK:COMM`.
