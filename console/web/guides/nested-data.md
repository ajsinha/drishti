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

Real documents are trees. A trade from a booking system holds a counterparty object, legs that each hold their
own list of cashflows, an execution block, a lifecycle with a list of events, a regulatory block, and so on,
several levels deep. Rachana reads any depth with the same short paths.

Every example below runs against the trading pack's interest rate swap **`MX-20000001`**. To follow along:

1. Open **Build → New screen**, bring the stored entity `trade` `MX-20000001`, and start from *an empty Sutra*.
2. Open the **YAML** tab to write, and the **Design** tab to see the result; the left pane's *shape* lists every path of
   the document while you write them.

The path, filter and function basics (`$`, `@`, `[?...]`, `size`, `first`) are taught in
[Rachana-EL step by step](/help/sutra-developer-guide#3-rachana-el-step-by-step); this tutorial is about how they behave
**deep in a tree**.

## 1. The shape of the document

This is the part of `MX-20000001` that the examples read (abridged; `…` marks what is left out):

```json
{
  "tradeId": "MX-20000001",
  "productName": "Interest rate swap (fixed/float)",
  "counterparty": { "id": "CP-MERIDIAN-RE", "name": "Meridian Reinsurance Ltd" },
  "legs": [
    { "leg": 1, "label": "Receive fixed 4.0829%", "type": "FIXED", "payer": false, "rate": 0.040829,
      "cashflows": [
        { "n": 1, "payDate": "2026-06-29", "rate": 0.040829, "amount": 8987301.85, "pv": 0.0, "status": "Settled" },
        { "n": 2, "payDate": "2027-06-29", "status": "Fixed", … },
        { "n": 3, "payDate": "2028-06-28", "status": "Projected", … }, …
      ], "pv": 52387404.92 },
    { "leg": 2, "label": "Pay AONIA compounded", "type": "FLOAT", "payer": true, "index": "AONIA",
      "cashflows": [ … 28 periods … ] }
  ],
  "execution": { "venue": "Voice", "venueMic": "XOFF", "executionTimestamp": "2025-07-25T10:15:36.962Z", "orderId": "ORD-C00C6EF736" },
  "lifecycle": { "version": 2, "sourceSystem": "Murex",
                 "events": [ { "version": 1, "event": "New", "reason": "Trade captured", "at": "2025-07-25T09:45:00Z", "by": "mo.ops" },
                             { "version": 2, "event": "Amendment", "reason": "Notional corrected", "at": "2025-10-02T09:45:00Z", "by": "mo.ops" } ] },
  "regulatory": { "uti": "549300O1N13GO2II2096C00C6EF736D1853579DC", "upi": "QZMB1EE7CS4O",
                  "reportingRegimes": ["CFTC Part 43/45"], "reportingStatus": "Accepted", "saCcrAssetClass": "Interest rate" },
  "clearing": { "status": "Cleared", "ccp": "LCH SwapClear", … },
  "settlementInstructions": { "method": "Gross", "ourAgentBic": "CHASUS33XXX", "theirAgentBic": "MERIGB33XXX", "ssiId": "SSI-AUD-9DC4EA", … }
}
```

## 2. Paths go as deep as the data

A path starts at `$` (the document), walks into objects with `.name` and into arrays with `[n]` (counting from 0).

| Path | Reads, on MX-20000001 |
|---|---|
| `$.counterparty.name` | *Meridian Reinsurance Ltd* (a field of a nested object) |
| `$.execution.venue` | *Voice* |
| `$.regulatory.uti` | *549300O1N13GO2II2096C00C6EF736D1853579DC* |
| `$.legs[0].label` | *Receive fixed 4.0829%* (the first leg) |
| `$.legs[-1].label` | *Pay AONIA compounded* (negative numbers count from the end) |
| `$.legs[0].cashflows[1].payDate` | *2027-06-29*: array, object, array, field |
| `$.lifecycle.events[-1].reason` | *Notional corrected* (the latest change) |
| `size($.legs[1].cashflows)` | *28* (how many periods the floating leg has) |
| `first($.regulatory.reportingRegimes)` | *CFTC Part 43/45* |

Try one: add this to the strip in the YAML tab and press **Ctrl+Enter**.

```yaml
  - { label: Last change, bind: "$.lifecycle.events[-1].reason + ' by ' + $.lifecycle.events[-1].by" }
```

You should see *Last change: Notional corrected by mo.ops*.

!!! note "Missing branches are empty, not errors"
    A path that runs into a branch the document does not have (`$.clearing.novatedAt` here is null; a bilateral
    trade has no `clearing` at all) is simply empty. The cell shows a dash, and a panel with nothing to show says
    *No data available*. Nothing fails, and the rest of the view renders.

## 3. Pick elements by content with filters

Positions change from document to document; content does not. The filter `[?condition]` (taught in
[the guide](/help/sutra-developer-guide#filters)) is what makes a path into a tree robust. Where a position would break:

| Expression | Reads |
|---|---|
| `$.legs[?@.type == 'FLOAT'][0].index` | *AONIA*: the floating leg, wherever it sits |
| `$.legs[?@.payer][0].label` | *Pay AONIA compounded* (the first leg we pay) |
| `size($.legs[0].cashflows[?@.status == 'Projected'])` | *5* (fixed-leg periods not yet fixed) |

## 4. Tables over nested arrays

`rows` can point at an array anywhere in the tree. Inside the rows, `@` is the current row:

```yaml
- id: flows
  kind: ladder
  title: Fixed leg cashflows
  rows: $.legs[0].cashflows          # an array inside an array element
  highlight: "@.status == 'Fixed'"   # the period whose rate is fixed but not yet paid
  columns:
    - { label: Pay date, bind: "@.payDate", fmt: date }
    - { label: Rate, bind: "@.rate", fmt: pct4 }
    - { label: Amount, bind: "@.amount", fmt: signed0, tone: sign, total: true }
    - { label: PV, bind: "@.pv", fmt: signed0, tone: sign, total: true }
    - { label: Status, bind: "@.status", tone: status }
```

Paste it into the `panels:` list and preview. You should see seven rows from *2026-06-29* to *2032-06-29*, the
*2027-06-29* row highlighted, and a total row under *Amount* and *PV*.

To list only what is still to come, filter the rows:

```yaml
  rows: "$.legs[0].cashflows[?@.status != 'Settled']"
```

## 5. One tab per element, each with its own nested rows

A `tabs` panel repeats a layout for every element of an array. Inside the body, `@` is that element, including
everything nested inside it:

```yaml
- id: legs
  kind: tabs
  title: Legs
  each: $.legs
  layout: columns
  tabTitle: "'Leg ' + @.leg + ' · ' + @.label"
  body:
    kind: kv
    columns:
      - { label: Index, bind: "coalesce(@.index, 'Fixed')" }        # the fixed leg has no index
      - { label: Periods, bind: "size(@.cashflows)" }
      - { label: Next payment, bind: "@.cashflows[?@.status != 'Settled'][0].payDate", fmt: date }
      - { label: Leg PV, bind: "@.pv", fmt: signed0, tone: sign }
```

You should see two columns. *Leg 1 · Receive fixed 4.0829%*: Index *Fixed*, Periods *7*, Next payment
*2027-06-29*. *Leg 2 · Pay AONIA compounded*: Index *AONIA*, Periods *28*, Next payment *2026-12-30*.

## 6. A whole Sutra over a nested trade

This Sutra is an operations view of any trade: who, where, what the regulator sees, where cash settles,
and every version of the trade. In the workbench, paste the YAML below into the YAML tab of the design you started above and preview it. `description` and `notes` at the top and
bottom are plain text for the people who maintain the layout; they do not change the view.

```yaml
rachana: 1
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
notes: |
  Priority 1, so any product's own Sutra wins; this one is for operations staff who open a trade by hand.
  Every path reaches into a nested block; a trade without one of them shows a dash, not an error.
```

You should see:

- the strip: *Product Interest rate swap (fixed/float)*, *Venue Voice (XOFF)*, *Version 2*, *Confirmation
  Confirmed*, *Clearing Cleared*, and MTM highlighted;
- **Parties and booking** (F2), with *Book* and *Trader* as links you can click;
- **Lifecycle** (F3): *New · Trade captured* and *Amendment · Notional corrected*, the second highlighted;
- **Regulatory reporting** and, on the right, **Settlement instructions** (*Gross*, *CHASUS33XXX*, *MERIGB33XXX*).

Why `priority: 1`: the product Sutras (such as `irs-fixfloat`) match with priority 10, so they stay the default
view of a trade. Saved as it is, this operations Sutra lays out only trades that no product Sutra matches. To make
it the default where operations staff work, give it a higher priority in that installation.

## 7. When inference meets a tree

With no Sutra at all, inference also walks the tree. Open `trade` / `MX-20000001` with *auto-design* as the start and
you get, among others:

| Part of the document | Inferred panel |
|---|---|
| `legs` (two similar objects) | `tabs` *Legs* |
| `legs[0].cashflows` (an array inside the first leg) | `table` *Cashflows · Legs 1* |
| `schedule`, `pnlHistory` (rows led by a date) | `ladder` *Schedule*, `ladder` *Pnl history* |
| `execution`, `clearing`, `terms`, `lifecycle` (objects) | `kv` panels |
| `sensitivities` (rows keyed by tenor) | `line` *Sensitivities* |

Keep what is useful, delete the rest, and add labels and keys. See [Inference](/help/inference) for the rules.

!!! tip "Finding the path"
    Press **F9** on any view to see its raw JSON. In the workbench, the **shape** in the left pane lists the document the Sutra
    reads. A path is exactly the keys you see there, joined with dots, with `[n]` for list positions.
