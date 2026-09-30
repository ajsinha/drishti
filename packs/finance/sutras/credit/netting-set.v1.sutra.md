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
# Netting set (`netting-set` v1)

Netting set with exposure profile, member trades, CSA and collateral (mockup drishti-netting-set.png).

**Applies to:** entities of kind `netting-set`, priority 10.

**Strip:** Trades, Net MTM (USD), Collateral posted, EE peak, PFE 95 peak, Limit, Utilisation, As of.

```sutra
sutra: netting-set
version: 1
description: Netting set with exposure profile, member trades, CSA and collateral (mockup drishti-netting-set.png).
match: { kind: netting-set, priority: 10 }
title: { pill: Netting set, id: $.nettingSetId, with: "link($.counterparty.id, 'counterparty', $.counterparty.name)" }
strip:
  - { label: Trades, bind: $.trades }
  - { label: Net MTM (USD), bind: $.netMtm, fmt: signed0, tone: sign }
  - { label: Collateral posted, bind: $.collateralPosted, fmt: amount0 }
  - { label: EE peak, bind: $.eePeak, fmt: compact }
  - { label: PFE 95 peak, bind: $.pfe95Peak, fmt: compact, emphasis: true }
  - { label: Limit, bind: $.limit, fmt: compact }
  - { label: Utilisation, bind: $.utilisation, fmt: pct0 }
  - { label: As of, bind: $.asOf }
panels:
  - id: exposure
    kind: area
    title: Exposure profile (USD)
    code: EXP
    key: F2
    rows: $.exposure
    x: tenor
    series:
      - { label: Expected exposure, value: ee, tone: link }
      - { label: PFE 95, value: pfe95, tone: accent }
    limit: $.limit
    limitLabel: Credit limit
  - id: trades
    kind: table
    title: Member trades
    code: TRD
    key: F3
    rows: $.memberTrades
    limit: 4
    moreLabel: "(size($.memberTrades) - 4) + ' more trades'"
    totalLabel: Net
    columns:
      - { label: Trade, bind: "@.trade", link: true }
      - { label: Product, bind: "@.product" }
      - { label: Notional, bind: "@.currency + ' ' + fmt(@.notional, 'amount0')" }
      - { label: Maturity, bind: "@.maturity", fmt: date }
      - { label: MTM (USD), bind: "@.mtm", fmt: signed0, tone: sign, total: true }
  - { id: csa, kind: kv, title: CSA terms, code: CSA, key: F4, area: right, rows: $.csaTerms }
  - { id: collateral, kind: kv, title: Collateral, code: COLL, area: right, rows: $.collateral }
  - { id: refs, kind: links, title: Linked entities, code: REFS, area: right }
keys: { F7: "link($.counterparty.id, 'counterparty')", F9: raw }
```

## Panels

| Panel | Code | Key | Shows | Area |
|---|---|---|---|---|
| Exposure profile (USD) | EXP | F2 | area chart | main |
| Member trades | TRD | F3 | table | main |
| CSA terms | CSA | F4 | field list | right |
| Collateral | COLL |  | field list | right |
| Linked entities | REFS |  | linked entities | right |

**Function keys:** F7 → `link($.counterparty.id, 'counterparty')`, F9 → `raw`.
