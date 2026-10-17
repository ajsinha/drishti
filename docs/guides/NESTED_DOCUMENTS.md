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
# Nested documents

Real documents are trees. A trade from a booking system holds legs that each hold their own list of cash flows, each
flow its own list of resets (the fixings of a floating rate or price), and a list of events, cash and lifecycle, each
naming the leg it belongs to. Rachana reads any depth with the same short paths.

Every example below runs against the trading pack's interest rate swap **`MX-20000001`**, booked in Murex. It is in the
common schema every source system writes (Endur, Calypso and Murex alike: 47 upper-snake attributes, `LEGS[]` with
`INDEX` and `CASH_FLOWS[]` -> `RESETS[]`, and `EVENTS[]`; [PACKS.md](PACKS.md#trading)). To follow along:

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
  "SOURCE_SYSTEM": "MUREX", "TRADE_ID": "MX-20000001", "TRANSACTION_ID": "20000001002", "TRADE_VERSION": "2",
  "PRODUCT_TYPE": "IRS_FIXFLOAT", "PRODUCT_SUB_TYPE": "Interest rate swap (fixed/float)",
  "COUNTERPARTY": "CP-MERIDIAN-RE", "COUNTERPARTY_UEN": "UEN0100407", "COUNTERPARTY_COUNTRY_OF_RISK": "BM",
  "LEGAL_ENTITY": "LE-NY", "TRANSIT": "00107", "PORTFOLIO": "BOOK-RATES-3", "TRADER_ID": "TRDR-ASHAH",
  "TRADER_FIRST_NAME": "A.", "TRADER_LAST_NAME": "Shah", "SOURCE_UPDATE_TIME": "2026-09-30T18:30:00Z",
  "LEGS": [
    { "LEG_ID": 1, "LEG_TYPE": "FIXED", "LEG_ECONOMIC_TYPE": "Receive", "NOTIONAL": 242000000.0, "INDEX": { "NAME": "", … },
      "CASH_FLOWS": [
        { "FLOW_ID": 20000001001, "RATE": 0.040829, "START_DATE": "2025-07-28", "PAYMENT_DATE": "2026-06-29", "RESETS": [] },
        { "FLOW_ID": 20000001002, "PAYMENT_DATE": "2027-06-29", … }, …
      ] },
    { "LEG_ID": 2, "LEG_TYPE": "FLOAT", "LEG_ECONOMIC_TYPE": "Pay", "INDEX": { "NAME": "AONIA", "GROUP": "Rates", "SUB_GROUP": "Monthly" },
      "CASH_FLOWS": [
        { "FLOW_ID": 20000001008, "RATE": 0.035377, "PAYMENT_DATE": "2025-09-29",
          "RESETS": [ { "RESET_ID": "R-1", "RESET_DATE": "2025-07-28", "RATE": 0.035376 }, { "RESET_ID": "R-2", … } ] },
        … 28 periods … ] }
  ],
  "EVENTS": [
    { "LEG_ID": "", "EVENT_TYPE": "BOOKED", "EVENT_DATE": "2025-07-25", "AMOUNT": "" }, …,
    { "LEG_ID": "", "EVENT_TYPE": "MATURITY", "EVENT_DATE": "2032-06-25", "AMOUNT": "" },
    { "LEG_ID": 1, "EVENT_TYPE": "CASH_SETTLEMENT", "PAYMENT_TYPE": "Interest", "EVENT_DATE": "2026-06-29", "AMOUNT": 8987301.85 }, …
  ]
}
```

Missing values are written the way extracts write them: mostly an empty text `""` (the fixed leg's `INDEX.NAME`, a deal event's
`LEG_ID` and `AMOUNT`), sometimes `null`, and an Endur deal may leave half its fields blank. Section 3 shows how to read past them.

## 2. Paths go as deep as the data

A path starts at `$` (the document), walks into objects with `.NAME` and into arrays with `[n]` (counting from 0).

| Path | Reads, on MX-20000001 |
|---|---|
| `$.COUNTERPARTY` | *CP-MERIDIAN-RE* (a reference: `link($.COUNTERPARTY, 'counterparty')` opens it) |
| `$.LEGS[1].INDEX.NAME` | *AONIA*: array, object, field |
| `$.LEGS[0].LEG_ECONOMIC_TYPE` | *Receive* (the first leg) |
| `$.LEGS[-1].LEG_TYPE` | *FLOAT* (negative numbers count from the end) |
| `$.LEGS[0].CASH_FLOWS[1].PAYMENT_DATE` | *2027-06-29*: array, object, array, field |
| `$.LEGS[1].CASH_FLOWS[0].RESETS[0].RATE` | *0.035376*: four levels down, the first fixing of the floating leg |
| `size($.LEGS[1].CASH_FLOWS)` | *28* (how many periods the floating leg has) |
| `size(flatten($.LEGS[*].CASH_FLOWS))` | *35* (every period of every leg, as one list) |

Try one: add this to the strip in the YAML tab and press **Ctrl+Enter**.

```yaml
  - { label: Trader, bind: "$.TRADER_FIRST_NAME + ' ' + $.TRADER_LAST_NAME + ' (' + $.TRADER_ID + ')'" }
```

You should see *Trader: A. Shah (TRDR-ASHAH)*.

!!! note "Missing branches are empty, not errors"
    A path that runs into a branch the document does not have (`$.LEGS[5]` here; `$.EVENTS` on a deal whose extract
    left the key out) is simply empty. The cell shows a dash, and a panel with nothing to show says
    *No data available*. Nothing fails, and the rest of the view renders.

## 3. Pick elements by content with filters

Positions change from document to document; content does not. The filter `[?condition]` (taught in
[the guide](/help/sutra-developer-guide#filters)) is what makes a path into a tree robust. Where a position would break:

| Expression | Reads |
|---|---|
| `$.LEGS[?@.LEG_TYPE == 'FLOAT'][0].INDEX.NAME` | *AONIA*: the floating leg, wherever it sits |
| `$.LEGS[?@.LEG_ECONOMIC_TYPE == 'Pay'][0].LEG_ID` | *2* (the first leg we pay) |
| `size($.EVENTS[?!nonblank(@.LEG_ID)])` | *7* (the deal's own events: no leg) |
| `size(flatten($.LEGS[1].CASH_FLOWS[*].RESETS)[?!nonblank(@.RATE)])` | *75* (fixings of the floating leg not yet known) |

`nonblank(x, …)` gives the first argument that has a value, skipping `""` and spaces as well as null: it is how a Sutra reads an
extract that spells "nothing" as empty text. `coalesce` keeps an empty text, so prefer `nonblank` for fields like these.

## 4. Tables over nested arrays

`rows` can point at an array anywhere in the tree. Inside the rows, `@` is the current row:

```yaml
- id: flows
  kind: ladder
  title: Fixed leg cash flows
  rows: $.LEGS[0].CASH_FLOWS          # an array inside an array element
  highlight: "@.PAYMENT_DATE > '2026-09-30' && #index > 0 && $.LEGS[0].CASH_FLOWS[#index - 1].PAYMENT_DATE <= '2026-09-30'"   # the next payment
  columns:
    - { label: Start, bind: "@.START_DATE", fmt: date }
    - { label: Payment, bind: "@.PAYMENT_DATE", fmt: date }
    - { label: Rate, bind: "@.RATE", fmt: pct4 }
    - { label: Notional, bind: "@.NOTIONAL", fmt: amount0 }
```

Paste it into the `panels:` list and preview. You should see seven rows from *2026-06-29* to *2032-06-29*, the
*2027-06-29* row highlighted (the first payment after the samples' date).

To list only what is still to come, filter the rows (`today()` is the business date the view is built for):

```yaml
  rows: "$.LEGS[0].CASH_FLOWS[?days(today(), @.PAYMENT_DATE) >= 0]"
```

## 5. One tab per element, each with its own nested rows

A `tabs` panel repeats a layout for every element of an array. Inside the body, `@` is that element, including
everything nested inside it:

```yaml
- id: legs
  kind: tabs
  title: Legs
  each: $.LEGS
  layout: columns
  tabTitle: "'Leg ' + @.LEG_ID + ' · ' + @.LEG_ECONOMIC_TYPE + ' ' + @.LEG_TYPE"
  body:
    kind: kv
    columns:
      - { label: Index, bind: "nonblank(@.INDEX.NAME, 'Fixed')" }        # the fixed leg's index is empty text
      - { label: Periods, bind: "size(@.CASH_FLOWS)" }
      - { label: Next payment, bind: "@.CASH_FLOWS[?@.PAYMENT_DATE > '2026-09-30'][0].PAYMENT_DATE", fmt: date }
      - { label: Fixings, bind: "size(flatten(@.CASH_FLOWS[*].RESETS))" }
```

You should see two columns. *Leg 1 · Receive FIXED*: Index *Fixed*, Periods *7*, Next payment *2027-06-29*, Fixings *0*.
*Leg 2 · Pay FLOAT*: Index *AONIA*, Periods *28*, Next payment *2026-12-31*, Fixings *90*.

A tab can hold whole panels as well, with `panels:`; each is read with `@` the tab's element. Here each leg shows its notional
profile (a step line), its fixings (a line) and its own cash flows, each flow opening to its resets, in a column for every leg
whatever their number (this is how the trading pack's deal screens show legs side by side):

```yaml
- id: legs
  kind: tabs
  title: Legs
  each: $.LEGS
  layout: columns
  tabTitle: "'Leg ' + @.LEG_ID + ' · ' + @.LEG_TYPE"
  panels:
    - id: leg-notional
      kind: line
      title: Notional profile
      rows: "@.CASH_FLOWS"
      x: START_DATE
      y: NOTIONAL
      step: true
    - id: leg-fixings
      kind: line
      title: Fixings
      rows: "flatten(@.CASH_FLOWS[*].RESETS)[?nonblank(@.RATE)]"
      x: START_DATE
      y: RATE
    - id: leg-flows
      kind: table
      rows: "@.CASH_FLOWS"
      children: "@.RESETS"          # a flow that has resets opens to them (omit this for a plain table)
      columns:
        - { label: Flow / reset, bind: "nonblank(@.FLOW_ID, @.RESET_ID)" }
        - { label: Payment, bind: "nonblank(@.PAYMENT_DATE, @.RESET_DATE)" }
        - { label: Rate, bind: "@.RATE", fmt: pct4 }
```

You should see two columns, *Leg 1 · FIXED* and *Leg 2 · FLOAT*: the first with a flat notional line, no fixings (*No data
available*: a fixed leg has none) and 7 flows; the second with its notional line, the AONIA fixings known so far, and 28 flows, each
opening (▸) to its two resets. `children` makes a table a tree: a row whose `@.RESETS` is a list gets a ▸ that opens them. A panel
inside a tab is a `kv`, `status`, `table`, `ladder`, `hbar`, `markdown`, `metric`, `line`, `area` or `timeline`, needs an `id`, and
cannot have `span`, `height` or a Pivot tab. [Panels inside a tab](/help/rachana-reference#panels-inside-a-tab) lists the rules.

## 6. A whole Sutra over a nested trade

This Sutra is an operations view of any trade: who booked it where, its version, its parties, its events and what is paid
when. In the workbench, paste the YAML below into the YAML tab of the design you started above and preview it. `description` and
`notes` at the top and bottom are plain text for the people who maintain the layout; they do not change the view.

```yaml
rachana: 1
sutra: trade-operations
version: 1
description: Operations view of any trade, from any source system, read from its legs, flows, resets and events.
match: { kind: trade, priority: 1 }
title: { pill: "Trade · operations", id: $.TRADE_ID, with: "link($.COUNTERPARTY, 'counterparty')" }
strip:
  - { label: Product, bind: $.PRODUCT_SUB_TYPE }
  - { label: Source, bind: $.SOURCE_SYSTEM }
  - { label: Transaction, bind: $.TRANSACTION_ID }
  - { label: Version, bind: "nonblank($.TRADE_VERSION, '–')" }
  - { label: Status, bind: $.TRADE_STATUS, tone: status }
  - { label: Legal entity, bind: "link($.LEGAL_ENTITY, 'legal-entity')" }
  - { label: Transit, bind: $.TRANSIT }
  - { label: Updated, bind: "nonblank($.SOURCE_UPDATE_TIME, '–')", emphasis: true }
panels:
  - id: parties
    kind: kv
    title: Parties and booking
    key: F2
    columns:
      - { label: Counterparty, bind: "link($.COUNTERPARTY, 'counterparty')" }
      - { label: UEN, bind: "nonblank($.COUNTERPARTY_UEN, '–')" }
      - { label: Country of risk, bind: "nonblank($.COUNTERPARTY_COUNTRY_OF_RISK, '–')" }
      - { label: Portfolio, bind: "link($.PORTFOLIO, 'book')" }
      - { label: Trader, bind: "link($.TRADER_ID, 'trader', $.TRADER_FIRST_NAME + ' ' + $.TRADER_LAST_NAME)" }
      - { label: Internal or external, bind: $.INTERNAL_EXTERNAL_CODE }
  - id: history
    kind: ladder
    title: Lifecycle
    key: F3
    rows: "$.EVENTS[?!nonblank(@.LEG_ID)]"
    highlight: "#index == size($.EVENTS[?!nonblank(@.LEG_ID)]) - 1"
    columns:
      - { label: Event, bind: "@.EVENT_TYPE" }
      - { label: Date, bind: "@.EVENT_DATE", fmt: date }
  - id: cash
    kind: table
    title: Cash events
    rows: "$.EVENTS[?nonblank(@.AMOUNT)]"
    columns:
      - { label: Leg, bind: "@.LEG_ID" }
      - { label: Date, bind: "@.EVENT_DATE", fmt: date }
      - { label: Type, bind: "@.PAYMENT_TYPE" }
      - { label: Amount, bind: "@.AMOUNT", fmt: signed2, tone: sign, total: true }
  - id: by-year
    kind: hbar
    title: Cash by year (paid − / received +)
    area: right
    rows: "group($.EVENTS[?nonblank(@.AMOUNT)], 'EVENT_DATE', 'AMOUNT', 'year')"
    fmt: signed0
    tone: sign
  - { id: built, kind: provenance, title: How this view was built }
keys: { F7: "link($.COUNTERPARTY, 'counterparty-trades')", F9: raw }
notes: |
  Priority 1, so any product's own Sutra wins; this one is for operations staff who open a trade by hand.
  Every path reaches into a nested branch; a trade without one of them shows a dash, not an error.
```

You should see:

- the strip: *Product Interest rate swap (fixed/float)*, *Source MUREX*, *Transaction 20000001002*, *Version 2*, *Status AMENDED*,
  *Legal entity LE-NY* (a link), *Transit 00107*, and *Updated 2026-09-30T18:30:00Z* highlighted;
- **Parties and booking** (F2), with *Counterparty*, *Portfolio* and *Trader* as links you can click;
- **Lifecycle** (F3): *BOOKED*, *CONFIRMED*, *CLEARED*, *AMENDED*, *CASH_SETTLED*, *NEXT_PAYMENT*, *MATURITY*, the last highlighted;
- **Cash events**: the 36 settlements of both legs with their total, and on the right **Cash by year**, received in green and
  paid in red.

Why `priority: 1`: the product Sutras (such as `irs-fixfloat`) match with priority 10, so they stay the default
view of a trade. Saved as it is, this operations Sutra lays out only trades that no product Sutra matches. To make
it the default where operations staff work, give it a higher priority in that installation.

## 7. When inference meets a tree

With no Sutra at all, inference also walks the tree. Open `trade` / `MX-20000001` with *auto-design* as the start and
you get, among others:

| Part of the document | Inferred panel |
|---|---|
| `LEGS` (two similar objects) | `tabs` *Legs* |
| `LEGS[0].CASH_FLOWS` (an array inside the first leg) | a `table` of the first leg's cash flows |
| `EVENTS` (rows with a date) | a `table` or `ladder` of the events |
| the deal's own fields | `kv` panels |

Upper-snake names read as words in the labels the inference writes (`MATURITY_DATE` is *Maturity date*). Keep what is useful,
delete the rest, and add labels and keys. See [Inference](/help/inference) for the rules.

!!! tip "Finding the path"
    Press **F9** on any view to see its raw JSON. In the workbench, the **shape** in the left pane lists the document the Sutra
    reads. A path is exactly the keys you see there, joined with dots, with `[n]` for list positions.
