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
# Tutorial 4 · Nested documents

Real documents are trees. A trade from a booking system holds a counterparty object, legs that each hold
their own cashflows, an execution block, a lifecycle with a list of events, a regulatory block, and so on,
several levels deep. Rachana reads any depth with the same short paths, and every example below runs against
the trading pack's samples (`TRD T-10001`, an interest rate swap).

## 1. Paths go as deep as the data

A path walks objects with `.` and arrays with `[n]`:

| Path | Reads |
|---|---|
| `$.counterparty.name` | a field of a nested object |
| `$.execution.venue` | the trading venue, one level down |
| `$.regulatory.uti` | the unique transaction identifier |
| `$.legs[0].cashflows[2].amount` | the third cashflow of the first leg: array, object, array, field |
| `$.legs[-1].label` | the last leg (negative indexes count from the end) |
| `$.lifecycle.events[-1].by` | who changed the trade last |
| `size($.legs[0].cashflows)` | how many periods the first leg has |

A path that runs into a missing branch (`$.clearing.ccp` on a bilateral trade) is simply empty. The cell shows
a dash, and a panel with nothing to show says *No data available*. Nothing fails.

## 2. Tables over nested arrays

`rows` can point at an array anywhere in the tree. Inside the rows, `@` is the current row, and it nests too:

```yaml
- id: flows
  kind: ladder
  title: Fixed leg cashflows
  rows: $.legs[0].cashflows          # an array inside an array element
  highlight: "@.status == 'Projected' && #index == 0"
  columns:
    - { label: Pay date, bind: "@.payDate", fmt: date }
    - { label: Rate, bind: "@.rate", fmt: pct4 }
    - { label: Amount, bind: "@.amount", fmt: signed0, tone: sign, total: true }
    - { label: PV, bind: "@.pv", fmt: signed0, tone: sign, total: true }
```

Filters pick elements by content: `$.legs[?@.type == 'FLOAT']` is the floating leg wherever it sits, and
`$.legs[?@.payer][0].label` is the label of the first leg we pay.

## 3. One tab per element, each with its own nested rows

A `tabs` panel repeats a layout for every element of an array. The body reads each element with `@`, including
what is nested inside it:

```yaml
- id: legs
  kind: tabs
  title: Legs
  each: $.legs
  tabTitle: "'Leg ' + @.leg + ' · ' + @.label"
  body:
    kind: kv
    columns:
      - { label: Index, bind: "coalesce(@.index, 'Fixed')" }
      - { label: Periods, bind: "size(@.cashflows)" }
      - { label: Next payment, bind: "@.cashflows[0].payDate", fmt: date }
      - { label: Leg PV, bind: "@.pv", fmt: signed0, tone: sign }
```

## 4. A whole Sutra over a nested trade

This Markdown Sutra is an operations view of any trade: who, where, what the regulator sees, where cash settles,
and every version of the trade. Paste it into Studio, set the entity to `trade` / `T-10001`, and preview.

```sutra
sutra: trade-operations
version: 1
description: Operations view of any trade, read from its nested execution, lifecycle, regulatory and settlement blocks.
match: { kind: trade, priority: 1 }
title: { pill: "Trade · operations", id: $.tradeId, with: "link($.counterparty.id, 'counterparty', $.counterparty.name)" }
strip:
  - { label: Product, bind: $.productName }
  - { label: Venue, bind: "$.execution.venue + ' (' + $.execution.venueMic + ')'" }
  - { label: Executed, bind: $.execution.executionTimestamp }
  - { label: UTI, bind: $.regulatory.uti }
  - { label: Version, bind: $.lifecycle.version }
  - { label: Confirmation, bind: $.confirmation.status, tone: status }
  - { label: Clearing, bind: $.clearing.status }
  - { label: MTM (USD), bind: $.mtm, fmt: signed0, tone: sign, emphasis: true }
panels:
  - id: parties
    kind: kv
    title: Parties and booking
    key: F2
    columns:
      - { label: Counterparty, bind: $.counterparty.name }
      - { label: Our entity, bind: $.legalEntity }
      - { label: Book, bind: "link($.book, 'book')" }
      - { label: Trader, bind: "link($.trader, 'trader')" }
      - { label: Source system, bind: $.lifecycle.sourceSystem }
      - { label: Order, bind: $.execution.orderId }
  - id: history
    kind: ladder
    title: Lifecycle
    key: F3
    rows: $.lifecycle.events
    highlight: "#index == size($.lifecycle.events) - 1"
    columns:
      - { label: Version, bind: "@.version" }
      - { label: Event, bind: "@.event" }
      - { label: Why, bind: "@.reason" }
      - { label: When, bind: "@.at" }
      - { label: By, bind: "@.by" }
  - id: regulatory
    kind: kv
    title: Regulatory reporting
    columns:
      - { label: UTI, bind: $.regulatory.uti }
      - { label: UPI, bind: $.regulatory.upi }
      - { label: First regime, bind: "first($.regulatory.reportingRegimes)" }
      - { label: Status, bind: $.regulatory.reportingStatus, tone: status }
      - { label: SA-CCR class, bind: $.regulatory.saCcrAssetClass }
  - id: settlement
    kind: kv
    title: Settlement instructions
    area: right
    columns:
      - { label: Method, bind: $.settlementInstructions.method }
      - { label: Our agent, bind: $.settlementInstructions.ourAgent }
      - { label: Our BIC, bind: $.settlementInstructions.ourAgentBic }
      - { label: Their BIC, bind: $.settlementInstructions.theirAgentBic }
      - { label: SSI, bind: $.settlementInstructions.ssiId }
  - { id: built, kind: provenance, title: How this view was built }
keys: { F7: "link($.nettingSet, 'netting-set')", F9: raw }
```

Why `priority: 1`: the product Sutras match with priority 10, so they stay the default view of a trade. This
one is the operations view: save it and give it a higher priority where operations staff work, or open it from
Studio.

## 5. When inference meets a tree

With no Sutra at all, inference also walks the tree. It makes a panel for each nested object (`Execution`,
`Regulatory`), a table for each array of objects (`Lifecycle events`, `Legs 0 cashflows`), and tabs for arrays
of similar objects (`Legs`). Start from inference in Studio to see what it makes of a document, then keep what
is useful.

!!! tip "Finding the path"
    Press **F9** on any view to see the raw JSON. In Studio, the *Sample JSON* tab shows the document the
    Sutra reads. Paths are exactly the keys you see there, joined with dots.
