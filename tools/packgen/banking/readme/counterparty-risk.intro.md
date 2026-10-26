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
    TRADES: Trades in the netting set
    BY_ASSET: Exposure by asset class
    COUNTERPARTY: The counterparty's id
    LEGAL_ENTITY: The bank's legal entity
    NETTING_ENFORCEABLE: Whether close-out netting is enforceable
  credit-limit: {BUCKETS: Limit and usage by tenor bucket, COUNTERPARTY: The counterparty's id, APPROVED_BY: Who approved the limit, REVIEW_DUE: When the limit is next reviewed}
  simulation-run:
    _: "An upstream exposure simulation of a business date (docs/contracts/EXP.md section 3.2): the paths, the grid of future dates, the model; an input of the exposure engine"
    RUN_ID: "XR-<date>-<n>"
    GRID_DATES: "The grid's dates, strictly increasing after the business date"
    GRID_ROLE: "PRIMARY (exposure is reported) or LOOKBACK (the value a margin period of risk before, for collateral) per grid date"
    LOOKBACK_OF: "For a look-back date the index of the primary date it serves, -1 for a primary date"
    PRECISION: "float32 or float64: how the paths' values are stored"
  exposure-path:
    _: "One trade's mark to market on every simulated path at one grid date (docs/contracts/EXP.md section 3.3): the grain every exposure is netted, collateralised and counted from"
    PATH_ROW_ID: "<run>~<trade>~<grid index>"
    MTM: "P values, path order of the run, stored as a list<double> column (out of the document)"
    GRID_INDEX: "Index into the run's GRID_DATES"
    NETTING_SET: "The trade's netting set (denormalised; the netting set's membership wins)"
  cva: {COMPONENTS: CVA components, SENSITIVITIES: CVA sensitivities, CREDIT_CURVE: The counterparty's credit curve}
  sa-ccr: {ADD_ONS: Add-ons by asset class, HEDGING_SETS: Hedging sets, ALPHA: The SA-CCR alpha (1.4)}
  collateral-balance: {POSITIONS: Collateral positions held and posted}
  margin-call: {EVENTS: The call's events, CURRENCY: The call's currency}
  simm: {RISK_CLASSES: Initial margin by risk class, COUNTERPARTY: The counterparty's id}
---
## What it is

Counterparty credit risk over the trading pack's trades: master agreements and CSAs, netting sets, credit limits, CVA, SA-CCR,
collateral balances, margin calls and SIMM initial margin, and the **exposure engine's inputs** (2.9.0): an upstream simulation run
(`simulation-run`) and every trade's mark to market on every path of its grid (`exposure-path`). Exposure is never stored: `EXP
NSET:NS-ALDERSHOT-FRA <GO>` (the computed kind `exposure-result`, [EXP.md](../../../docs/contracts/EXP.md)) nets the paths per
netting set, applies each CSA's collateral path by path with the margin period of risk, and reads EE, ENE, EEE, PFE 95% and 99%, EPE
and EEPE for any scope (`NSET:`, `CPTY:`, `GROUP:`, `LE:`, `DESK:`, `TRD:`); the netting-set and counterparty pages show the engine's
figures computed as they open. The stored `exposure-profile` kind (`EXP-<cp>-<le>`) was retired in 2.9.0: `EXP EXP-ALDERSHOT-FRA`
opens `EXP NSET:NS-ALDERSHOT-FRA`.
