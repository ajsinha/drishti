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
# Tutorial 2 · Write your first Sutra

A Sutra is one layout written in **Rachana**, Drishti's screen grammar. You will lay out interest rate
swaps with a header strip, a cashflow table and a curve: about a dozen lines, and no code.

## 1. Name it and say what it matches

```yaml
# sutras/rates/my-swap.v1.yaml
sutra: my-swap
version: 1
match: { kind: trade, where: "$.productType == 'IRS'", priority: 20 }
title: { pill: "Trade · My swap", id: $.tradeId, with: "link($.counterparty.id, 'counterparty', $.counterparty.name)" }
```

`where` is a Rachana-EL expression over the document. A higher `priority` beats the built-in
`irs-vanilla` Sutra (priority 10).

## 2. Add the key figures

```yaml
# The header strip (up to eight)
strip:
  - { label: Notional (USD), bind: $.notional, fmt: amount0 }
  - { label: MTM (USD), bind: $.mtm, fmt: signed0, tone: sign, emphasis: true }
  - { label: Maturity, bind: $.maturityDate, fmt: date }
```

`fmt` names a format: `signed0` prints `−412,580` and `+22,310`. `tone: sign` colours by sign.

## 3. Add panels

```yaml
# A table whose columns inference fills in, and a curve from a linked entity
panels:
  - { id: flows, kind: table, title: Cashflows, key: F2, rows: $.legs[0].cashflows, infer: true }
  - { id: curve, kind: line, title: Discount curve, area: right, source: "link($.discountCurve, 'curve')",
      rows: points, x: tenor, y: rate, mark: $.maturityTenor }
  - { id: refs, kind: links, title: Linked entities, area: right }
```

`infer: true` lets inference choose the table's columns. The view's label then says
`Sutra my-swap v1 + inference`.

## 4. Save and open

Save the file under `sutras/`. The server reloads it within a quarter of a second, and
`TRD IRS-48213 <GO>` now uses your Sutra. If the file has a mistake, the previous version stays live,
and `/api/v1/sutras/problems` lists each problem with its line and column.

!!! warning "Test in Studio first"
    [Sutra Studio](sutra-studio.md) previews an unsaved Sutra against any entity. It is the quickest way to
    see a mistake.

Every key and option is in the [Rachana reference](../../../docs/RACHANA_REFERENCE.md).
