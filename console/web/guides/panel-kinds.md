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
# The twenty panel kinds

Every box on a Drishti screen is a **panel**, and every panel is one of twenty kinds. A Sutra names the kind
and tells it where the data is; when there is no Sutra, inference picks a kind for you. This page shows each
kind with a real entity you can open, the Sutra lines that produce it, and the options it accepts.
[Panels in depth](panels) goes further: the data each kind needs, how the server computes it, how the
console draws it, its limits, and the mistakes the parser reports.

## How to read a panel header

Open any view, for example:

```text
# Type this on the command line, then press Enter
TRD MX-20000001
```

You should see an interest rate swap. Look at the header of the *Terms* panel. From left to right it shows:

| Part | Example | Meaning |
|---|---|---|
| Title | *Terms* | What the panel is about (the Sutra's `title`). |
| Code | `TRM` | A short tag (the Sutra's `code`), handy when you talk about a panel. |
| Key | `F2` | Press this key to jump to the panel (the Sutra's `key`). |
| **inferred** tag | (only on some panels) | Inference built or completed this panel. Hover over it to read which rule chose it and why. |
| ↓ | | Download the panel as CSV. |
| **?** | | Opens the section of this page for the panel's kind. |

!!! tip "Try every kind in Studio"
    Open **Studio** (`/studio`), type `trade` and `MX-20000001` in the two boxes at the top, and paste any example
    below into the `panels:` list of a Sutra. Press **Ctrl+Enter** to preview. Nothing is saved until you
    press Save, so experiment freely.

Quick chooser:

| Your data looks like | Use |
|---|---|
| one object with named fields | [kv](#kv) |
| a list of similar rows | [table](#table), or [ladder](#ladder) if one row matters most |
| 2–4 similar objects (legs, tranches) | [tabs](#tabs) |
| points along an axis (tenors, dates) | [line](#line), or [area](#area) for several bands against a limit |
| a few labelled amounts | [hbar](#hbar) |
| one number against a maximum | [gauge](#gauge) |
| a grid over two axes | [surface](#surface) |
| a start value, signed contributions and an end value (P&L attribution) | [waterfall](#waterfall) |
| many numbers whose spread matters (scenario P&L) | [histogram](#histogram) |
| rows with two measures to compare (risk against return) | [scatter](#scatter) |
| prices by date with open, high, low and close | [candlestick](#candlestick) |
| entities and how they relate (a group hierarchy) | [graph](#graph) |
| dated events (a trade's lifecycle) | [timeline](#timeline) |
| rows to total by one field across another (MTM by book and currency) | [pivot](#pivot) |
| statuses (confirmed, cleared, settled) | [status](#status) |
| references to other entities | [links](#links) (automatic) |
| a note for the reader | [markdown](#markdown) |
| where the view came from | [provenance](#provenance) |

## kv

**Label/value fields**: the terms of a trade, the margin of a position, an identifier block.

*See it:* `TRD MX-20000001`, panel **Terms** (F2). It shows *Fixed rate 4.0829%*, *Pay frequency Annual*,
*Day count ACT/365F* and so on.

![kv](/static/img/guide/kind-kv.png)

There are two ways to fill it. **Pick and format the fields** with `columns`:

```yaml
# kv with chosen fields
- id: terms
  kind: kv
  title: Terms
  key: F2
  columns:
    - { label: Fixed rate, bind: $.terms.fixedRate, fmt: pct4 }   # 0.040829 shows as 4.0829%
    - { bind: $.terms.payFrequency }                              # no label: shown as "Pay frequency"
    - { label: Effective, bind: $.effectiveDate, fmt: date }
```

Or **show every field of an object** with `rows` and no `columns`:

```yaml
# kv over a whole object
- { id: clearing, kind: kv, title: Clearing, rows: $.clearing }
```

You should see every field of the trade's `clearing` block: its status (*Cleared*), the CCP (*LCH SwapClear*),
the clearing broker, the CCP trade id and so on, each labelled from its field name.

| Option | Meaning |
|---|---|
| `columns` | The fields to show, each `{label?, bind, fmt?, tone?}`. |
| `rows` | An object; with no `columns`, every field in it is shown. |
| `fields` | Same as `columns` (an alternative name). |

In the main column a kv panel shows four pairs across; in the right column (`area: right`), two.

## table

**Rows and columns**: cashflows, the member trades of a netting set, the holdings of a book.

*See it:* `NSET NS-SUMMIT-NY`, panel **Member trades** (F3): 108 trades with product, notional, maturity and MTM,
and a total row under the MTM column.

![table](/static/img/guide/kind-table.png)

```yaml
# table with a total and "N more"
- id: trades
  kind: table
  title: Member trades
  key: F3
  rows: $.trades                     # the array to list
  limit: 12                          # show 12 rows...
  moreLabel: "(size($.trades) - 12) + ' more trades'"   # ...then "96 more trades"
  columns:
    - { label: Trade, bind: "@.tradeId", link: true }   # link: the cell opens that trade
    - { bind: "@.product" }
    - { bind: "@.notional", fmt: compact }              # 227000000 shows as 227.0m
    - { label: MTM, bind: "@.mtm", fmt: signed0, tone: sign, total: true }
```

Inside `columns`, `@` is the current row: `@.mtm` is the row's `mtm`.

| Option | Meaning |
|---|---|
| `rows` (required) | The array to list. Can be anywhere in the document (`$.legs[0].cashflows`). |
| `columns` | `{label?, bind, fmt?, tone?, total?, link?}`. Leave it out and inference picks the columns. |
| `limit`, `moreLabel` | Show only the first N rows, and a line saying how many more there are. |
| `totalLabel` | The text in the total row (default *Total*). Columns with `total: true` are summed. |
| `link` | `link: true` on a column makes each cell open the entity it names. |
| `pivot` | `true`, or `{ fields, rows, columns, values, filters, heat, chart }`: a **Table \| Pivot** switch over every row of the table, where readers drag fields into rows, columns, values and filters ([the Pivot tab](pivot-tab)). Without it, no switch. |

```yaml
# a table with a Pivot tab: notional by product and maturity bucket to start with
  pivot:
    fields: [product, currency, maturityBucket, notional, mtm, tradeId]   # row paths; they need not be columns
    rows: [product]
    columns: [maturityBucket]
    values: [{ field: notional, agg: sum }]
    filters: [currency]
```

## tabs

**One layout per element** of a small array: the two legs of a swap, the near and far legs of an FX swap.

*See it:* `TRD MX-20000001`, panel **Legs**: *Leg 1 · Receive fixed 4.0829%* and *Leg 2 · Pay AONIA compounded*, side
by side.

![tabs](/static/img/guide/kind-tabs.png)

```yaml
# tabs: one kv per leg
- id: legs
  kind: tabs
  title: Legs
  each: $.legs                        # one tab per element
  layout: columns                     # side by side; use "tabs" for clickable tabs
  tabTitle: "'Leg ' + @.leg + ' · ' + @.label"
  body:                               # the panel drawn for each element; @ is that element
    kind: kv
    columns:
      - { label: Pay/receive, bind: "@.payReceive" }
      - { label: Rate, bind: "@.rate", fmt: pct4 }
      - { label: Notional, bind: "@.notional", fmt: amount0 }
```

| Option | Meaning |
|---|---|
| `each` (required) | The array; one body per element. |
| `body` | The panel to repeat (usually `kv`). If it has no columns, inference fills them. |
| `tabTitle` | An expression for each tab's title. |
| `layout` | `tabs` (default) or `columns` (side by side). |

## line

**A curve**: zero rates by tenor, daily P&L by date.

*See it:* `TRD MX-20000001`, right column, **Interest rate curve: Zero rates (%)** (F4). The points do not come from
the trade: they are read from the trade's discount curve, `CRV-AUD-OIS`.

![line](/static/img/guide/kind-line.png)

```yaml
# line from the document itself
- { id: pnl, kind: line, title: Daily P&L, area: right, rows: $.pnlHistory, x: date, y: pnl, fmt: signed0 }
```

```yaml
# line from a linked entity
- id: marketData
  kind: line
  title: "Interest rate curve: Zero rates (%)"
  area: right
  source: "link($.discountCurve, 'ir-curve')"   # open CRV-AUD-OIS and read from it
  rows: points                                   # now this means the curve's own $.points
  x: tenor
  y: zeroRate
  fmt: price2
```

| Option | Meaning |
|---|---|
| `rows` | The points. Each row needs an `x` and a `y` field. |
| `x`, `y` | The field names for the horizontal and vertical axes. |
| `source` | `link(id, kind)`: read the rows from another entity, on the same business date. |
| `mark` | Highlight one x value (a trade's maturity on its curve). |
| `unit`, `fmt`, `footer` | Axis unit, number format and a line of text under the chart. |

## area

**Banded series against a limit**: expected exposure and PFE 95 against a credit limit.

*See it:* `NSET NS-SUMMIT-NY`, panel **Exposure profile** (F2): two bands over tenors from 0 to 10Y and a dashed
line at the 133m limit.

![area](/static/img/guide/kind-area.png)

```yaml
# area with a limit line
- id: exposure
  kind: area
  title: Exposure profile
  key: F2
  rows: $.profile                    # [{tenor: 1M, ee: 19002613, pfe: 50006774}, ...]
  x: tenor
  limit: $.limit
  series:
    - { label: Expected exposure, value: ee, tone: link }
    - { label: PFE 95, value: pfe, tone: accent }
```

| Option | Meaning |
|---|---|
| `rows` (required) | The points. |
| `x` | The field for the horizontal axis. |
| `series` | One `{label, value, tone?}` per band; `value` names a field of each row. |
| `limit`, `limitLabel` | A dashed horizontal line and its label. |
| `unit` | The axis unit. |

## hbar

**Horizontal bars**, one per row, scaled to the largest: DV01 by tenor bucket, MTM by asset class, expression by
tissue.

*See it:* `TRD MX-20000001`, right column, **DV01 by bucket (USD)**. Negative bars use the negative colour.

![hbar](/static/img/guide/kind-hbar.png)

```yaml
# hbar of sensitivities
- id: sensitivities
  kind: hbar
  title: DV01 by bucket (USD)
  area: right
  rows: $.sensitivities               # [{bucket: 3M, dv01: -5544}, ...]
  label: bucket
  value: dv01
  fmt: signed0
  tone: sign
```

| Option | Meaning |
|---|---|
| `rows` (required) | The bars. |
| `label`, `value` | The fields that name and size each bar. |
| `fmt`, `tone` | How to format and colour the values. |

## ladder

**A dated table with one row highlighted**: the cashflow schedule with the next payment marked, daily
settlements with today's row.

*See it:* `TRD MX-20000001`, panel **Cashflows** (F3): 35 payments of both legs, with the next one highlighted.

![ladder](/static/img/guide/kind-ladder.png)

```yaml
# ladder with the next payment highlighted
- id: schedule
  kind: ladder
  title: Cashflows
  key: F3
  rows: $.schedule
  highlight: "#index == $.nextIndex"   # #index is the row number, from 0
  columns:
    - { label: Pay date, bind: "@.payDate", fmt: date }
    - { bind: "@.leg" }
    - { label: Amount, bind: "@.amount", fmt: signed0, tone: sign }
    - { label: PV, bind: "@.pv", fmt: signed0, tone: sign, total: true }
```

`highlight` is checked for every row; rows where it is true are highlighted. Other useful forms:
`"@.status == 'Fixed'"`, or `"#index == size($.lifecycle.events) - 1"` for the last row.

| Option | Meaning |
|---|---|
| `rows` (required) | The rows. |
| `columns` | As for `table`. |
| `highlight` | A condition evaluated per row. |
| `totalLabel` | The total row's text. |
| `pivot` | As for `table`: a [Pivot tab](pivot-tab) over the ladder's rows (the banking cash-flow ladders open on PV by flow type and leg). |

## links

**Linked entities**: every reference in the document becomes a door to another entity, with a short badge read
from that entity.

*See it:* `TRD MX-20000001`, right column, **Linked entities**. You should see *Counterparty CP-MERIDIAN-RE* (badge
`A`, its rating), *Netting set NS-MERIDIAN-RE-NY* (badge `PFE 46.0m`), *Book BOOK-RATES-3*, *Trader TRDR-ASHAH*,
*Desk DESK-RATES*, the curves and the fixing index. Click any of them to open it.

![links](/static/img/guide/kind-links.png)

```yaml
# links: no options needed
- { id: refs, kind: links, title: Linked entities, code: REFS, area: right }
```

The panel needs no options: which fields are references, and what the badge says, come from the domain pack
(`graph.fields` and `graph.badges` in `pack.yaml`). What you may see beside a link:

| You see | Meaning |
|---|---|
| a badge (`PFE 46.0m`, `A`) | read from the target entity just now |
| *pending* | the target did not arrive within the time budget; reload to try again |
| *no access*, link disabled | your role may not open that kind of entity |

## status

**Operational fields coloured by meaning**: confirmation, clearing and reporting status. Good states (Confirmed,
Cleared, Accepted, Settled, Live) are green; pending ones amber; breaks red. The word is always shown, so the
colour is never the only signal.

![status](/static/img/guide/kind-status.png)

```yaml
# status panel over a trade
- id: ops
  kind: status
  title: Confirmation and clearing
  fields:
    - { label: Confirmation, bind: $.confirmation.status, tone: status }   # Confirmed
    - { label: Method, bind: $.confirmation.method }                        # MarkitWire
    - { label: Clearing, bind: $.clearing.status, tone: status }           # Cleared
    - { label: Reporting, bind: $.regulatory.reportingStatus, tone: status } # Accepted
```

Try it on `MX-20000001` in Studio: the four values above appear.

## provenance

**How this view was built.** Every view should end with this panel. For `TRD MX-20000001` it reads, for example:

```text
# What the provenance panel tells you
Layout       Sutra irs-fixfloat v1 + inference
Fingerprint  b7df…1372
Source       murex-rates, gen 1721
```

- **Layout** names the Sutra and its version, `+ inference` when inference filled a gap, or *inference only*.
- **Source** and **generation** say where the document came from and which version of it you see (the
  generation rises each time a live entity changes).
- The footer of the view shows the business date the data is for (`as of 2026-09-25`) when you have picked one.

![provenance](/static/img/guide/kind-provenance.png)

```yaml
# provenance: no options
- { id: built, kind: provenance, title: How this view was built }
```

## markdown

**A static note** for the reader: what the view is for, a caveat about units, a desk instruction. `${…}` inserts
values from the document. The note is shown as plain text, so write sentences rather than Markdown formatting
(asterisks and hashes appear as typed).

![markdown](/static/img/guide/kind-markdown.png)

```yaml
# markdown note with a value inside
- id: notes
  kind: markdown
  title: Reading this view
  text: "MTM is in USD whatever the trade currency. This trade faces ${$.counterparty.name}."
```

On `MX-20000001` the note reads *MTM is in USD whatever the trade currency. This trade faces Meridian Reinsurance Ltd.*

## gauge

**One number against a maximum**: limit utilisation, a budget used.

![gauge](/static/img/guide/kind-gauge.png)

```yaml
# gauge of limit utilisation
- { id: usage, kind: gauge, title: Limit utilisation, value: $.utilisation, max: 1, fmt: pct0 }
```

Try it in Studio on `netting-set` / `NS-CASCADIA-TKY` (utilisation 0.7969): the gauge shows **80%**.

| Option | Meaning |
|---|---|
| `value` (required) | The number. |
| `max` | The full-scale value (1 for a ratio, or a limit such as `$.limit`). |
| `label`, `fmt` | Text under the number, and its format. |

## surface

**A grid of values over two axes**: an FX volatility smile by expiry and delta, swaption vols by expiry and
tenor. It draws as a heatmap; the **3D** button turns it into a surface you can rotate (drag) and zoom
(scroll).

*See it:* `FXV FXV-EURUSD`, panel **Smile surface (vol %)** (F4).

![surface as a heatmap](/static/img/guide/kind-surface.png)

![surface in 3D](/static/img/guide/kind-surface-3d.png)

Each element of `rows` is one point on the vertical axis (named by `y`); each column is one point on the
horizontal axis and holds the value:

```yaml
# surface: expiry down, delta across
- id: smile
  kind: surface
  title: Smile surface (vol %)
  key: F4
  rows: $.grid                    # [{expiry: 1M, p25: 7.05, atm: 6.67, c25: 6.79}, …]
  y: expiry
  columns:
    - { label: 25D P, bind: "@.p25", fmt: price2 }
    - { label: ATM, bind: "@.atm", fmt: price2 }
    - { label: 25D C, bind: "@.c25", fmt: price2 }
```

| Option | Meaning |
|---|---|
| `rows`, `y` (required) | The rows, and the field that labels each row. |
| `columns` | One per x point. |
| `view` | `heatmap` (default) or `3d`. |
| `fmt`, `unit` | Value format and unit. |

## waterfall

**Steps from a start to an end**: a P&L explain from yesterday's MTM to today's, a book's P&L from risk factors to
the actual figure. Each step is a bar that floats from the running total before it to the one after it: rises in
the positive tone, falls in the negative tone, totals as full bars from zero.

*See it:* `TRD MX-20000001`, panel **P&L explain (USD, opening to closing MTM)** (F5). The bars run from *Opening
MTM +1,748,877* through *Carry −11,426*, *Roll-down +11,390*, *Rates delta +132,686* and four more steps to
*Closing MTM +1,875,863*, the trade's MTM. `PNL PNL-COMM-1` (F3) shows a book's attribution ending at *Actual −180,316*.

![waterfall](/static/img/guide/kind-waterfall.png)

```yaml
# waterfall: an opening total, signed steps, and a closing total the server adds up
- id: explain
  kind: waterfall
  title: "P&L explain (USD, opening to closing MTM)"
  key: F5
  rows: $.pnlExplain          # [{step: Opening MTM, pnl: 1748877, total: true}, {step: Carry, pnl: -11426}, …]
  label: step
  value: pnl
  sum: Closing MTM            # appends a total bar at the running sum
  fmt: signed0
```

| Option | Meaning |
|---|---|
| `rows` (required) | The steps, in order. |
| `label`, `value` | The fields with each step's name and amount (default `label`, `value`). |
| `total` | The field that marks a step as a total, drawn from zero; the running sum restarts there (default `total`). |
| `sum` | The label of a closing total bar the server appends at the running sum. |
| `fmt`, `unit` | Format of the amounts, and the axis unit. |

## histogram

**How a list of numbers is spread**: a VaR scenario vector, a P&L history. The server bins the numbers (the
square root of the count, 5 to 40 bins, unless `bins` says) and draws dashed marker lines where you ask.

*See it:* `VAR VAR-RATES`, panel **Scenario P&L distribution, 500 days (USD)** (F3): 500 scenario P&Ls in 22
bins, with *VaR 99% −9.6m*, *ES 97.5% −10.9m* and *Mean −335.2k* marked.

![histogram](/static/img/guide/kind-histogram.png)

```yaml
# histogram with marker lines read from the document
- id: scenarios
  kind: histogram
  title: "Scenario P&L distribution, 500 days (USD)"
  key: F3
  rows: $.scenarioPnl          # [-1234567, 845120, …]: numbers, or rows with `value` naming the field
  fmt: compact
  markers:
    - { label: VaR 99%, value: "-$.var99", tone: neg }
    - { label: ES 97.5%, value: "-$.es975", tone: bad }
    - { label: Mean, value: $.meanPnl, tone: link }
```

| Option | Meaning |
|---|---|
| `rows` (required) | A list of numbers, or of rows. |
| `value` | With rows, the field (or `@` expression) holding the number. |
| `bins` | How many bins, 1 to 200. |
| `markers` | Lines at values: each `{ label, value, tone }`, `value` an expression over the document. |
| `fmt`, `unit` | Format of bin edges and markers, and the axis unit. |

## scatter

**Two measures per row**: books' VaR against their P&L, desks' DV01 against MTM. Points can be sized by a third
field and coloured by a group; a point whose label is an entity id opens it on click.

*See it:* `LE LE-NY`, panel **Risk and return (books: VaR against today's P&L, USD)** (F3): fifteen books, coloured by desk, sized
by their trade count; *BOOK-RATES-1* sits at VaR 6.0m and P&L −2.96m.

![scatter](/static/img/guide/kind-scatter.png)

```yaml
# scatter: x and y per row, sized and grouped
- id: riskReturn
  kind: scatter
  title: "Risk and return (books: VaR against today's P&L, USD)"
  key: F3
  rows: $.books                # [{book: BOOK-RATES-1, desk: Rates · Swaps and options, var: 6016760, pnl: -2955541, trades: 49}, …]
  x: var
  y: pnl
  size: trades
  label: book
  group: desk
  fmt: signed0
  xFmt: compact
  xLabel: VaR 99% 1D (USD)
  yLabel: P&L 1D (USD)
```

| Option | Meaning |
|---|---|
| `rows`, `x`, `y` (required) | The rows, and the fields (or `@` expressions) of the two measures. |
| `size`, `label`, `group` | Point size, point label (an id opens its entity), and the category that colours it. |
| `fmt`, `xFmt` | Formats of y and x (x defaults to `fmt`). |
| `xLabel`, `yLabel` | Axis titles (default: the field names). |

## candlestick

**Daily bars**: open, high, low and close by date, with volume underneath when the rows carry it. Rising bars
use the positive tone, falling bars the negative one. Scroll inside the chart to zoom when there are more than 60 bars.

*See it:* `EQ EQ-CSCA`, panel **Daily bars (last 60 days)** (F3): the last close *21.84*, *+0.81 (+3.85%)* on the
day. `CMD CMD-BRENT` (F3) shows the Brent front month the same way.

![candlestick](/static/img/guide/kind-candlestick.png)

```yaml
# candlestick with volume
- { id: ohlc, kind: candlestick, title: "Daily bars (last 60 days)", key: F3, rows: $.ohlc, x: date, volume: volume, fmt: price2 }
```

| Option | Meaning |
|---|---|
| `rows` (required) | The bars, oldest first. |
| `x` | The date field (default `date`). |
| `open`, `high`, `low`, `close` | The price fields (default those names). |
| `volume` | The volume field; without it there are no volume bars. |
| `fmt`, `unit` | Price format and unit. |

## graph

**Entities and how they relate**: a counterparty's group and its members, the agreement and CSA it signed, the
netting sets they govern. With `layout: tree` (the default) the nodes nothing points to are at the top and each
level below; `force` lets related nodes pull together. A node whose id is an entity opens it on click.

*See it:* `CPTY CP-MERIDIAN`, panel **Group hierarchy (agreements and netting sets)** (F3): *Meridian Financial
Group* over *Meridian Life Assurance* (this view, ringed) and *Meridian Reinsurance Ltd*, then the ISDA, its CSA and
four netting sets. **Entities** under the chart lists every node as a link.

![graph](/static/img/guide/kind-graph.png)

```yaml
# graph: nodes and edges from the document
- id: hierarchy
  kind: graph
  title: Group hierarchy (agreements and netting sets)
  key: F3
  nodes: $.hierarchy.nodes     # [{id: GRP-MERIDIAN, label: Meridian Financial Group, type: Group (ultimate parent)}, …]
  edges: $.hierarchy.edges     # [{from: GRP-MERIDIAN, to: CP-MERIDIAN, label: parent of}, …]
  label: label
  group: type
  layout: tree
```

| Option | Meaning |
|---|---|
| `nodes` (required) | The nodes; each needs an `id`, and may say its `kind` when the id alone does not. |
| `edges` | The relations: `from`, `to` and an optional `label`. |
| `label`, `group` | The node fields shown and coloured by (default `label`, `type`). |
| `layout` | `tree` (default) or `force`. |

## timeline

**Dated events in order**: a trade's lifecycle, a margin call's workflow. Each event has a dot toned by its status
(green for done or settled, amber for pending, red for failed), the date, the event, the status and a short note.

*See it:* `TRD MX-20000001`, panel **Lifecycle** (F6): *Booked*, *Confirmed* (Matched on MarkitWire), *Cleared*
(LCH SwapClear), *Amended* (Notional corrected), *Cash settled*, *Next payment* (Pending) and *Maturity* in 2032.

![timeline](/static/img/guide/kind-timeline.png)

```yaml
# timeline: events sorted by date on the server
- { id: lifecycle, kind: timeline, title: Lifecycle, key: F6, rows: $.lifecycle.timeline, detail: description }
```

| Option | Meaning |
|---|---|
| `rows` (required) | The events, in any order: the server sorts them by date. |
| `date`, `label`, `detail`, `status` | The fields (default `date`, `event`, `description`, `status`). |
| `tone` | The tone of the status (default `status`; `sign` or a fixed tone also work). |

## pivot

**Rows totalled by one field across another**: MTM by book and currency, exposure by rating and tenor bucket.
The server aggregates (`sum`, `count`, `avg`, `min` or `max`), adds row and column totals, and can shade each
cell by its value. The table sorts, filters and pages like any other.

*See it:* `DESK DESK-RATES`, panel **MTM grid (by book and currency, USD)** (F3): three books across seven currencies;
*BOOK-RATES-1* totals *177.4m*, the desk *132.8m*. **Trades by book and product family** counts the same rows.

![pivot](/static/img/guide/kind-pivot.png)

```yaml
# pivot: sum of mtm by book across currency, shaded
- id: mtmGrid
  kind: pivot
  title: MTM grid (by book and currency, USD)
  key: F3
  rows: $.positions            # one row per trade: [{book: BOOK-RATES-1, currency: EUR, family: swap, mtm: 1761589}, …]
  by: book
  across: currency
  value: mtm
  agg: sum
  heat: true
  fmt: compact
  tone: sign
```

| Option | Meaning |
|---|---|
| `rows`, `by`, `across` (required) | The rows, the field down the side, and the field across the top. |
| `value` | The field aggregated; without it the pivot counts rows. |
| `agg` | `sum` (default), `count`, `avg`, `min` or `max`. |
| `heat` | `true` shades each cell by its value. |
| `totals` | `false` hides the row and column totals. |
| `fmt`, `tone` | Format and tone of the cells. |

## Options every panel accepts

| Key | Meaning | Example |
|---|---|---|
| `id` | Unique within the Sutra. | `id: terms` |
| `kind` | One of the twenty above. | `kind: kv` |
| `title` | Header text; may contain `${…}`. | `title: "Cashflows · ${$.legs[0].label}"` |
| `key` | A function key, `F2`–`F12`, unique in the Sutra. | `key: F3` |
| `code` | A short tag at the right of the header. | `code: CRV` |
| `area` | `main` (default) or `right`. | `area: right` |
| `infer` | `true` lets inference add columns the Sutra does not list. | `infer: true` |

The [Sutra guide](sutra-guide) explains paths, formats and matching; the
[Rachana reference](rachana-reference) lists every key and problem code.
[Panels in depth](panels) covers every kind's data, options, server computation, drawing, limits and mistakes.
