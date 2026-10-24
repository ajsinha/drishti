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
  agreement: {ELECTIONS: The agreement's elections, COUNTERPARTY: The counterparty's id}
  csa: {ELIGIBLE: Eligible collateral with haircuts, COUNTERPARTY: The counterparty's id}
  netting-set:
    PROFILE: Exposure profile by time bucket
    TRADES: Trades in the netting set
    BY_ASSET: Exposure by asset class
    COUNTERPARTY: The counterparty's id
    LEGAL_ENTITY: The bank's legal entity
    NETTING_ENFORCEABLE: Whether close-out netting is enforceable
  credit-limit: {BUCKETS: Limit and usage by tenor bucket, COUNTERPARTY: The counterparty's id, APPROVED_BY: Who approved the limit, REVIEW_DUE: When the limit is next reviewed}
  exposure-profile: {BUCKETS: Exposure (EE, PFE) by time bucket, MODEL: Simulation model, CONFIDENCE: PFE confidence level}
  cva: {COMPONENTS: CVA components, SENSITIVITIES: CVA sensitivities, CREDIT_CURVE: The counterparty's credit curve}
  sa-ccr: {ADD_ONS: Add-ons by asset class, HEDGING_SETS: Hedging sets, ALPHA: The SA-CCR alpha (1.4)}
  collateral-balance: {POSITIONS: Collateral positions held and posted}
  margin-call: {EVENTS: The call's events, CURRENCY: The call's currency}
  simm: {RISK_CLASSES: Initial margin by risk class, COUNTERPARTY: The counterparty's id}
---
## What it is

Counterparty credit risk over the trading pack's trades: master agreements and CSAs, netting sets, credit limits, exposure profiles,
CVA, SA-CCR, collateral balances, margin calls and SIMM initial margin.
