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
# The Sutra guide

A **Sutra** is the layout of one family of screens: every interest rate swap, every netting set, every
volatility surface. It says which figures lead the header, which panels show what, and which keys open what.
It is written in **Rachana**, Drishti's screen grammar, as one ordinary YAML file. It holds no code
and no pixels, so a Sutra is short, reviewable and safe, and one Sutra serves every document that matches it.

This guide goes from a first Sutra to every panel kind, with real examples from the banking packs and the views
they produce. Every example runs against sample data that ships with the packs, so you can paste it into Studio
and see the result. Keep the [Rachana reference](rachana-reference) open for the full list of keys.

!!! tip "Before you start"
    You need the trading pack enabled (it is, if `TRD MX-20000001` opens in the terminal) and access to **Studio**
    (`/studio`). Previewing needs no special role; only saving does.

![A trade view built by a Sutra](/static/img/guide/view-trade.png)

*`TRD MX-20000001`: an interest rate swap laid out by the `irs-fixfloat` Sutra. The header strip, the
Terms and Legs panels, the cashflow ladder, the curve on the right and the function keys at the bottom all come
from about sixty lines of Rachana.*

## 1. A first Sutra in five minutes

1. Open **Studio** (`/studio`) and start a new Sutra.
2. Set the entity to preview against to kind `trade`, id `MX-20000001`.
3. Select all the text in the editor and replace it with the block below. As you type, the editor completes keys,
   panel kinds and formats and marks what the grammar does not allow.
4. Preview it.

```yaml
rachana: 1
sutra: my-first-trade
version: 1
description: A first look at any trade.
match: { kind: trade }
title: { pill: "Trade", id: $.tradeId, with: $.counterparty.name }
strip:
  - { bind: $.productName }
  - { bind: $.notional, fmt: amount0 }
  - { bind: $.maturityDate, fmt: date }
  - { label: MTM (USD), bind: $.mtm, fmt: signed0, tone: sign, emphasis: true }
panels:
  - { id: terms, kind: kv, title: Terms, rows: $.terms }
  - { id: refs, kind: links, title: Linked entities, area: right }
```

That is a complete Sutra. On the right you should see:

- the title line: a *Trade* pill, **MX-20000001** and *Meridian Reinsurance Ltd*;
- four strip figures: *Product name Interest rate swap (fixed/float)*, *Notional 242,000,000*, *Maturity date
  2032-06-25* and **MTM (USD)**, highlighted, with a `+` sign. The first three have no `label`, so they are named
  after their fields;
- a **Terms** panel listing every field of the trade's `terms` object (fixed rate, pay frequency *Annual*, day
  count *ACT/365F*, business day *Following*);
- **Linked entities** on the right: the counterparty, netting set, book, trader, desk, curves and fixing index the
  trade points to.

Now change something and preview again. Some things to try:

| Change | What happens |
|---|---|
| `fmt: amount0` → `fmt: compact` on the notional | *242.0m* |
| add `- { label: DV01, bind: $.risk.dv01, fmt: signed0, tone: sign }` to the strip | a fifth figure, *−155,245*, in the negative colour |
| `kind: kv` → `kind: gauge` on *Terms* | problems `DRS-2022` (a gauge needs `value`) and `DRS-2023` (`rows` is not a gauge option), with their line |
| remove the `refs` panel | *Linked entities* disappears |

## 2. The file: one YAML document

A Sutra lives in `sutras/<domain>/<name>.v<N>.sutra.yaml`, inside a pack or a site directory. The whole file is
the Sutra, and it starts with `rachana: 1`, the version of the Rachana language it is written in. What the layout
is for, and why, goes in the file too, in two plain-text keys:

```yaml
rachana: 1
sutra: irs-fixfloat
version: 1
description: Fixed/float interest rate swaps for the rates desk.
notes: |
  The strip leads with MTM and DV01 because that is what traders check first.
  The cashflow ladder lights the next payment; the curve on the right is the discount curve.
match: { kind: trade, where: "$.productType == 'IRS_FIXFLOAT'", priority: 10 }
panels:
  - id: terms
    kind: kv
    title: Terms
    description: The economic terms as booked.
    rows: $.terms
```

- `rachana: 1` comes first. Without it, or with a version this server does not read, the file is refused with
  `DRS-2009`.
- `description` is one paragraph: what the layout shows and for which entities. `notes` is longer text for the next
  author and for reviewers, written as a YAML block (`notes: |` and indented lines, line breaks kept). A panel can
  have its own `description`. All three are plain text (not Markdown) and never change the view.
- `name@version` is unique. Keep old versions: saved views and history reproduce with the version they used.
- Problems report the file's own line and column.
- Only `*.sutra.yaml` files are read. An old Markdown Sutra (`*.sutra.md`) or any other `.yaml` file in a Sutra
  folder is reported as `DRS-2004`. Convert old files with
  `python3 tools/rachana/md_to_yaml.py <file-or-folder> --delete` (their prose becomes `notes:`), or rename.
- Because a Sutra is plain YAML, any editor that reads JSON Schema can complete and check it: point it at
  `/api/v1/rachana/schema` on the server (for example, with the YAML extension of VS Code, a first line
  `# yaml-language-server: $schema=http://localhost:18480/api/v1/rachana/schema`). The schema lists this server's
  entity kinds and formats, so completion offers exactly what will work here.

## 3. Anatomy

```yaml
rachana: 1                           # the language version, always first
sutra: irs-fixfloat                  # name: lower-case kebab
version: 1
description: Exchanges fixed for floating RFR-compounded payments.
match: { kind: trade, where: "$.productType == 'IRS_FIXFLOAT'", priority: 10 }
title: { pill: "Rates · Interest rate swap (fixed/float)", id: $.tradeId,
         with: "link($.counterparty.id, 'counterparty', $.counterparty.name)" }
strip: [ … up to eight header figures … ]
panels: [ … the panels, in reading order … ]
keys: { F7: "link($.nettingSet, 'netting-set')", F8: impact, F9: raw }
```

| Part | What it does |
|---|---|
| `rachana` | The Rachana language version: `1`. |
| `description`, `notes` | Plain text for people: one paragraph, and longer notes. The view ignores them. |
| `match` | Which documents this Sutra lays out: a `kind`, an optional `where` condition over the document, and a `priority`. The highest-priority Sutra whose `where` holds wins; none means inference alone. |
| `title` | The title line: a `pill`, the identifier (`id`), and `with` (usually the counterparty, as a link). |
| `strip` | The header figures, at most eight. `emphasis: true` highlights the one the desk watches. |
| `panels` | The body. Each has an `id`, a `kind` and options for that kind. `area: right` puts it in the side column. |
| `keys` | Function keys: `F2`–`F12` go to panels (a panel's own `key:`), links (`link(…)`), `impact` (F8) or `raw` (F9). |

![The title line and strip](/static/img/guide/strip.png)

## 4. Labels: name what you must, the rest names itself

Every strip item and column may have a `label`. Leave it out and the label is the name of the field it reads, in
words: `bind: @.payDate` reads as **Pay date**. Where a label comes from, first match wins:

1. `label:` in the Sutra;
2. the **pack taxonomy**: `labels:` in the pack's `config/semantics.yaml` (`mtm: MTM (USD)`, `tradeId: Trade`);
3. the **global taxonomy**: `labels:` in the core semantics, or the site's replacement file;
4. the field name in words, with the packs' `acronyms:` spelled right: `uti` → **UTI**, `dv01ByTenor` → **DV01 by tenor**.

So a pack teaches Drishti its vocabulary once, and every Sutra and every inferred screen in that pack uses it.

## 5. Reading the document: paths and expressions

Every `bind`, `rows`, `where` and title part is a **Rachana-EL** expression: a closed, side-effect-free language
compiled once and cached.

| You want | Write |
|---|---|
| a field | `$.notional` |
| a field of a nested object, any depth | `$.regulatory.uti`, `$.execution.venue` |
| an array element | `$.legs[0].rate`, the last one `$.legs[-1].label` |
| deep inside arrays of objects | `$.legs[0].cashflows[2].amount` |
| elements by content | `$.legs[?@.type == 'FLOAT']` |
| the current row (in `rows`, `each`, `columns`) | `@.payDate`, `@.fixing.source.index`; its position `#index` |
| arithmetic and comparisons | `$.used / $.limit`, `$.mtm < -1000000` |
| a choice | `"$.direction == 'PAY_FIXED' ? 'Pay fixed' : 'Receive fixed'"` |
| text | `"$.currency + ' ' + fmt($.notional, 'amount0')"` |
| a fallback | `coalesce(@.index, 'Fixed')` |
| a count, first or last element, a sum | `size($.legs)`, `first($.fixings)`, `last($.fixings)`, `sum($.trades, 'mtm')` |
| a navigable link | `link($.book, 'book')`, `link($.counterparty.id, 'counterparty', $.counterparty.name)` |

A path into a branch the document lacks is empty: the cell shows a dash, and a panel with nothing to show says
*No data available*. Quote an expression in YAML when it contains `:`, `#`, `'`, `@` at the start, or `,`
inside `{ }`. See [Tutorial 4 · Nested documents](nested-data) for trees several levels deep.

## 6. Formats and tones

`fmt` formats a value; `tone` colours it (always together with a sign or a glyph, never colour alone).

| Format | Shows | Format | Shows |
|---|---|---|---|
| `amount0` | 50,000,000 | `signed0` | +22,310 / −412,580 |
| `amount2` | 1,962,430.56 | `signed2` | +0.53 |
| `pct0` / `pct2` | 65% / 3.85% | `pct4` (banking) | 3.8500% |
| `price2` | 71.15 | `compact` | 4.1m |
| `date` | 2031-10-02 | `dmy` | 02 Oct 2031 |
| `bp1` (banking) | +22.6 bp | `rate5`, `pips1`, `df4` (banking) | 1.17400, +55.8, 0.9632 |

| Tone | Colour |
|---|---|
| `sign` | positive in the positive colour, negative in the negative colour |
| `pos`, `neg` | fixed |
| `status` | green for good states (Live, Confirmed, Settled), amber for pending, red for breaks |
| `link`, `accent` | named theme colours |

Packs add formats in `config/formats.yaml`; a site can override them.

## 7. The twenty panel kinds

### kv: fields as a grid

*See it:* `TRD MX-20000001`, panel **Terms** (F2).

Label/value pairs. List `columns`, or point `rows` at an object and leave `columns` out to show all its fields.

```yaml
- id: terms
  kind: kv
  title: Terms
  key: F2
  columns:
    - { label: Fixed rate, bind: $.terms.fixedRate, fmt: pct4 }
    - { bind: $.terms.payFrequency }
    - { bind: $.terms.dayCount }
    - { label: Effective, bind: $.effectiveDate, fmt: date }
```

![kv](/static/img/guide/kind-kv.png)

### table: rows with columns, totals and "N more"

*See it:* `NSET NS-SUMMIT-NY`, panel **Member trades** (F3).

```yaml
- id: trades
  kind: table
  title: Member trades
  key: F3
  rows: $.trades
  limit: 12
  moreLabel: "(size($.trades) - 12) + ' more trades'"
  columns:
    - { label: Trade, bind: "@.tradeId", link: true }
    - { bind: "@.product" }
    - { bind: "@.notional", fmt: compact }
    - { bind: "@.maturity", fmt: date }
    - { label: MTM, bind: "@.mtm", fmt: signed0, tone: sign, total: true }
```

![table](/static/img/guide/kind-table.png)

### ladder: a dated table with the row that matters highlighted

*See it:* `TRD MX-20000001`, panel **Cashflows** (F3): `$.nextIndex` says which row is the next payment.

```yaml
- id: schedule
  kind: ladder
  title: Cashflows
  key: F3
  rows: $.schedule
  highlight: "#index == $.nextIndex"
  columns:
    - { label: Pay date, bind: "@.payDate", fmt: date }
    - { bind: "@.leg" }
    - { bind: "@.rate", fmt: pct4 }
    - { bind: "@.amount", fmt: signed0, tone: sign }
    - { label: PV, bind: "@.pv", fmt: signed0, tone: sign, total: true }
```

![ladder](/static/img/guide/kind-ladder.png)

### tabs: one layout per element, as tabs or side by side

*See it:* `TRD MX-20000001`, panel **Legs**.

```yaml
- id: legs
  kind: tabs
  title: Legs
  each: $.legs
  layout: columns                    # or tabs
  tabTitle: "'Leg ' + @.leg + ' · ' + @.label"
  body:
    kind: kv
    columns:
      - { label: Pay/receive, bind: "@.payReceive" }
      - { bind: "@.index" }
      - { bind: "@.rate", fmt: pct4 }
      - { bind: "@.frequency" }
      - { bind: "@.notional", fmt: amount0 }
```

![tabs](/static/img/guide/kind-tabs.png)

### line: a curve, from the document or from a linked entity

*See it:* `TRD MX-20000001`, right column, **Interest rate curve: Zero rates (%)** (F4).

`source: link(…)` reads the points from another entity: here the trade's discount curve.

```yaml
- id: marketData
  kind: line
  title: "Interest rate curve: Zero rates (%)"
  key: F4
  area: right
  source: "link($.discountCurve, 'ir-curve')"
  rows: points
  x: tenor
  y: zeroRate
  fmt: price2
```

![line](/static/img/guide/kind-line.png)

### area: exposure bands against a limit

*See it:* `NSET NS-SUMMIT-NY`, panel **Exposure profile** (F2).

```yaml
- id: exposure
  kind: area
  title: Exposure profile
  key: F2
  rows: $.profile
  x: tenor
  series:
    - { label: Expected exposure, value: ee, tone: link }
    - { label: PFE 95, value: pfe, tone: accent }
  limit: $.limit
```

![area](/static/img/guide/kind-area.png)

### hbar: bars scaled to the largest value

*See it:* `TRD MX-20000001`, right column, **DV01 by bucket (USD)**.

```yaml
- id: sensitivities
  kind: hbar
  title: DV01 by bucket (USD)
  area: right
  rows: $.sensitivities
  label: bucket
  value: dv01
  fmt: signed0
  tone: sign
```

![hbar](/static/img/guide/kind-hbar.png)

### surface: a grid over two axes, heatmap or 3D

*See it:* `FXV FXV-EURUSD`, panel **Smile surface (vol %)** (F4); the example below is the equity version (`EQV EQV-CSCA`).

Each row of `rows` is one point on the `y` axis; each column is one point on the x axis and holds the value.
**3D** turns the heatmap into a surface you can rotate and zoom.

```yaml
- id: surface
  kind: surface
  title: Implied vol by expiry × moneyness (%)
  key: F4
  rows: $.grid
  y: expiry
  columns:
    - { label: 80%, bind: "@.m80" }
    - { label: 90%, bind: "@.m90" }
    - { label: 100%, bind: "@.m100" }
    - { label: 110%, bind: "@.m110" }
    - { label: 120%, bind: "@.m120" }
```

![surface as a heatmap](/static/img/guide/kind-surface.png)

![surface in 3D](/static/img/guide/kind-surface-3d.png)

### status: operational states

*Try it:* paste the example into the `panels:` list of a Sutra previewed on `trade` / `MX-20000001`: *Confirmed*, *MarkitWire*, *Cleared*.

```yaml
- id: ops
  kind: status
  title: Confirmation and settlement
  fields:
    - { label: Confirmation, bind: $.confirmation.status, tone: status }
    - { label: Method, bind: $.confirmation.method }
    - { label: Matched, bind: $.confirmation.matched }
    - { label: Clearing, bind: $.clearing.status }
```

![status](/static/img/guide/kind-status.png)

### gauge: one number against a maximum

*Try it:* in Studio on `netting-set` / `NS-CASCADIA-TKY` (utilisation 0.7969) the gauge shows *80%*.

```yaml
- { id: usage, kind: gauge, title: Limit utilisation, value: $.utilisation, max: 1, fmt: pct0 }
```

![gauge](/static/img/guide/kind-gauge.png)

### markdown: a note

The note is shown as plain text (Markdown marks are not rendered); `${…}` inserts values from the document.

```yaml
- { id: notes, kind: markdown, title: Desk note, text: "Hedges the ${$.counterparty.name} liability book." }
```

![markdown](/static/img/guide/kind-markdown.png)

### links: everything the document points to

*See it:* `TRD MX-20000001`, right column, **Linked entities**.

Found from the pack's reference fields (`nettingSet`, `book`, `discountCurve`, …), each with a badge read from
the target (`EE 4.1m`, `81% used`). A link you may not open shows as denied.

```yaml
- { id: refs, kind: links, title: Linked entities, code: REFS, area: right }
```

![links](/static/img/guide/kind-links.png)

### provenance: how this view was built

*See it:* the last panel of any view, e.g. `TRD MX-20000001`: *Sutra irs-fixfloat v1 + inference*.

The Sutra and version (or *inference only*), the data fingerprint, the source and its generation.

```yaml
- { id: built, kind: provenance, title: How this view was built }
```

![provenance](/static/img/guide/kind-provenance.png)

### waterfall: steps from a start to an end

*See it:* `TRD MX-20000001`, panel **P&L explain (USD, opening to closing MTM)** (F5), or `PNL PNL-COMM-1` (F3).

Each step floats from the running total before it to the one after it: rises green, falls red, totals grey.
`sum:` adds a closing total bar; `colors: theme` uses the theme's positive and negative colours instead.

```yaml
- id: explain
  kind: waterfall
  title: "P&L explain (USD, opening to closing MTM)"
  key: F5
  rows: $.pnlExplain
  label: step
  value: pnl
  sum: Closing MTM
  fmt: signed0
```

![waterfall](/static/img/guide/kind-waterfall.png)

### histogram: how a list of numbers is spread

*See it:* `VAR VAR-RATES`, panel **Scenario P&L distribution, 500 days (USD)** (F3).

The server bins the numbers (`bins:` 1 to 200, or the square root of the count) and draws a dashed line per marker.

```yaml
- id: scenarios
  kind: histogram
  title: "Scenario P&L distribution, 500 days (USD)"
  key: F3
  rows: $.scenarioPnl
  fmt: compact
  markers:
    - { label: VaR 99%, value: "-$.var99", tone: neg }
    - { label: ES 97.5%, value: "-$.es975", tone: bad }
```

![histogram](/static/img/guide/kind-histogram.png)

### scatter: two measures per row

*See it:* `LE LE-NY`, panel **Risk and return (books: VaR against today's P&L, USD)** (F3).

`x` and `y` are field names of each row; `size`, `label` and `group` size, name and colour the points, and a label
that is an entity id opens it.

```yaml
- { id: riskReturn, kind: scatter, title: "Books: VaR against P&L", key: F3, rows: $.books, x: var, y: pnl, size: trades, label: book, group: desk, fmt: signed0, xFmt: compact }
```

![scatter](/static/img/guide/kind-scatter.png)

### candlestick: daily bars with volume

*See it:* `EQ EQ-CSCA`, panel **Daily bars (last 60 days)** (F3), or `CMD CMD-BRENT` (F3).

```yaml
- { id: ohlc, kind: candlestick, title: "Daily bars (last 60 days)", key: F3, rows: $.ohlc, x: date, volume: volume, fmt: price2 }
```

![candlestick](/static/img/guide/kind-candlestick.png)

### graph: entities and how they relate

*See it:* `CPTY CP-MERIDIAN`, panel **Group hierarchy (agreements and netting sets)** (F3).

Nodes need an `id`; a node that is an entity opens it, and the one you are viewing is ringed. `layout: tree` (the
default) or `force`.

```yaml
- id: hierarchy
  kind: graph
  title: Group hierarchy (agreements and netting sets)
  key: F3
  nodes: $.hierarchy.nodes
  edges: $.hierarchy.edges
  layout: tree
```

![graph](/static/img/guide/kind-graph.png)

### timeline: dated events in order

*See it:* `TRD MX-20000001`, panel **Lifecycle** (F6).

The server sorts the events by date; each status is toned (green done, amber pending, red failed).

```yaml
- { id: lifecycle, kind: timeline, title: Lifecycle, key: F6, rows: $.lifecycle.timeline, detail: description }
```

![timeline](/static/img/guide/kind-timeline.png)

### pivot: rows totalled by one field across another

*See it:* `DESK DESK-RATES`, panel **MTM grid (by book and currency, USD)** (F3).

The server aggregates (`agg: sum`, `count`, `avg`, `min` or `max`) and adds row and column totals; `heat: true` shades
the cells.

```yaml
- { id: mtmGrid, kind: pivot, title: "MTM grid (by book and currency, USD)", key: F3, rows: $.positions, by: book, across: currency, value: mtm, heat: true, fmt: compact, tone: sign }
```

![pivot](/static/img/guide/kind-pivot.png)

## 8. Every panel kind as a complete Sutra

Section 7 shows each kind as a panel inside a pack's Sutra. Here each kind is a whole Sutra on its own, short enough to
paste into Studio as it stands: open Studio on the entity named, replace the editor's text, and press **Ctrl+Enter**.
Each one was previewed against its sample entity with no problems and no empty panel. The examples match their kind
with no `priority`, so saving one does not displace the pack's own layout.

[The twenty panel kinds](panel-kinds) explains each kind's options with a screenshot;
[Rachana, step by step](rachana-guide#10-every-panel-kind-by-example) goes through the same twenty Sutras option by
option, with the mistake each kind invites and its problem code.

### kv on a bond trade

`TRD BBG-60000001`: *Bond terms* (`Coupon 1.6708%`, `Yield 3.4159%`, …) and, on the right, every field of the
settlement instructions.

```yaml
rachana: 1
sutra: kind-kv
version: 1
match: { kind: trade }
panels:
  - id: terms
    kind: kv
    title: Bond terms
    columns:
      - { label: Coupon, bind: $.terms.coupon, fmt: pct4 }
      - { label: Frequency, bind: $.terms.couponFrequency }
      - { label: Face amount, bind: $.terms.faceAmount, fmt: amount0 }
      - { label: Clean price, bind: $.terms.cleanPrice, fmt: price2 }
      - { label: Yield, bind: $.terms.yield, fmt: pct4 }
  - { id: ssi, kind: kv, title: Settlement instructions, area: right, rows: $.settlementInstructions }
```

### table on a netting set

`NSET NS-NORTHBRIDGE-FRA`: three of five member trades, a `Net +2,816,262` row over all five, and `2 more trades`.

```yaml
rachana: 1
sutra: kind-table
version: 1
match: { kind: netting-set }
panels:
  - id: trades
    kind: table
    title: Member trades
    rows: $.trades
    limit: 3
    moreLabel: "(size($.trades) - 3) + ' more trades'"
    totalLabel: Net
    columns:
      - { label: Trade, bind: "@.tradeId" }
      - { label: Product, bind: "@.product" }
      - { label: Notional, bind: "@.notional", fmt: compact }
      - { label: Maturity, bind: "@.maturity", fmt: date }
      - { label: MTM, bind: "@.mtm", fmt: signed0, tone: sign, total: true }
```

### tabs on a counterparty

`CPTY CP-NORTHBRIDGE`: one tab per netting set, `NS-NORTHBRIDGE-FRA` to `NS-NORTHBRIDGE-TKY`.

```yaml
rachana: 1
sutra: kind-tabs
version: 1
match: { kind: counterparty }
panels:
  - id: sets
    kind: tabs
    title: Netting sets
    each: $.nettingSets
    tabTitle: "@.id"
    body:
      kind: kv
      columns:
        - { label: Agreement, bind: "@.agreement", link: true }
        - { label: Trades, bind: "@.trades" }
        - { label: Net MTM, bind: "@.netMtm", fmt: signed0, tone: sign }
```

### line from the document and from a linked curve

`TRD BBG-60000001`: twenty days of P&L with the last marked, and the CHF government curve read from `CRV-CHF-GOVT`.

```yaml
rachana: 1
sutra: kind-line
version: 1
match: { kind: trade }
panels:
  - id: pnl
    kind: line
    title: Daily P&L (USD)
    rows: $.pnlHistory
    x: date
    y: pnl
    mark: $.valuation.asOf
    fmt: signed0
  - id: curve
    kind: line
    title: Benchmark curve (zero rate, %)
    area: right
    source: "link($.benchmarkCurve, 'ir-curve')"
    rows: $.points
    x: tenor
    y: zeroRate
```

### area against a limit

`NSET NS-NORTHBRIDGE-FRA`: expected exposure and PFE by tenor, peaking at `3M`, under a 5,000,000 credit limit.

```yaml
rachana: 1
sutra: kind-area
version: 1
match: { kind: netting-set }
panels:
  - id: exposure
    kind: area
    title: Exposure profile (USD)
    rows: $.profile
    x: tenor
    series:
      - { label: Expected exposure, value: ee, tone: link }
      - { label: PFE 95, value: pfe, tone: accent }
    limit: $.limit
    limitLabel: Credit limit
```

### hbar of sensitivities

`TRD BBG-60000001`: eight DV01 bars from `3M −5,289` to `10Y −42,311`.

```yaml
rachana: 1
sutra: kind-hbar
version: 1
match: { kind: trade }
panels:
  - id: dv01
    kind: hbar
    title: DV01 by bucket (USD)
    rows: $.sensitivities
    label: bucket
    value: dv01
    fmt: signed0
    tone: sign
```

### ladder with the next coupon lit

`TRD BBG-60000001`: 24 coupons, the next one (`2027-03-02`) highlighted, totalling `95,040,000`.

```yaml
rachana: 1
sutra: kind-ladder
version: 1
match: { kind: trade }
panels:
  - id: coupons
    kind: ladder
    title: Coupon schedule
    rows: $.schedule
    highlight: "#index == $.nextIndex"
    totalLabel: All coupons
    columns:
      - { label: Pay date, bind: "@.date", fmt: date }
      - { label: Rate, bind: "@.rate", fmt: pct4 }
      - { label: Amount, bind: "@.amount", fmt: amount0, total: true }
      - { label: Status, bind: "@.status", tone: status }
```

### links

`TRD BBG-60000001`: ten linked entities with badges, from `CP-SUMMIT` (`A-`) to `BND-CHLUME169114` (`112.96`).

```yaml
rachana: 1
sutra: kind-links
version: 1
match: { kind: trade }
panels:
  - { id: refs, kind: links, title: Linked entities }
```

### status

`TRD BBG-60000001`: `Confirmed · DTCC CTM`, `Cleared at LCH SwapClear`, `Accepted`, `Official EOD`, all green.

```yaml
rachana: 1
sutra: kind-status
version: 1
match: { kind: trade }
panels:
  - id: ops
    kind: status
    title: Operations
    fields:
      - { label: Confirmation, bind: "$.confirmation.status + ' · ' + $.confirmation.method", tone: status }
      - { label: Clearing, bind: "$.clearing.status + ' at ' + $.clearing.ccp", tone: status }
      - { label: Reporting, bind: $.regulatory.reportingStatus, tone: status }
      - { label: Valuation, bind: $.valuation.status, tone: status }
```

### provenance

`TRD BBG-60000001`: `Sutra kind-provenance v1`, the fingerprint, and `summit-fi, gen 1`.

```yaml
rachana: 1
sutra: kind-provenance
version: 1
match: { kind: trade }
panels:
  - { id: built, kind: provenance, title: How this view was built }
```

### markdown

`TRD BBG-60000001`: *A short government bond position in CHF, booked in BOOK-FI-3. MTM and P&L are in USD.*

```yaml
rachana: 1
sutra: kind-markdown
version: 1
match: { kind: trade }
panels:
  - id: note
    kind: markdown
    title: Reading this view
    text: "A ${lower($.direction)} ${lower($.productName)} position in ${$.currency}, booked in ${$.book}. MTM and P&L are in ${$.mtmCurrency}."
```

### gauge of VaR against its limit

`VAR VAR-COMM`: `58%` (VaR 10,959,317 of an 18,940,000 limit).

```yaml
rachana: 1
sutra: kind-gauge
version: 1
match: { kind: var }
panels:
  - { id: usage, kind: gauge, title: VaR 99% used of the desk limit, value: $.var99, max: $.limit }
```

### surface of a volatility smile

`CMDV CMDV-BRENT`: eight contract months by three strikes, from `27.26` to `32.82`.

```yaml
rachana: 1
sutra: kind-surface
version: 1
match: { kind: commodity-vol-surface }
panels:
  - id: smile
    kind: surface
    title: Implied vol by contract month and moneyness (%)
    rows: $.grid
    y: month
    fmt: price2
    columns:
      - { label: 90%, bind: "@.m90" }
      - { label: ATM, bind: "@.m100" }
      - { label: 110%, bind: "@.m110" }
```

### waterfall from risk factors to the actual P&L

`PNL PNL-COMM-1`: nine steps and a closing `Actual −180,316` bar the server adds.

```yaml
rachana: 1
sutra: kind-waterfall
version: 1
match: { kind: pnl-explain }
panels:
  - id: explain
    kind: waterfall
    title: P&L from risk factors to actual (USD)
    rows: $.explainSteps
    label: step
    value: pnl
    sum: Actual
    fmt: signed0
```

### histogram with VaR, ES and mean

`VAR VAR-COMM`: 500 scenario P&Ls in 30 bins, with `VaR 99% −11.0m`, `ES 97.5% −12.4m` and `Mean −49.8k` marked.

```yaml
rachana: 1
sutra: kind-histogram
version: 1
match: { kind: var }
panels:
  - id: scenarios
    kind: histogram
    title: Scenario P&L, 500 days (USD)
    rows: $.scenarioPnl
    bins: 30
    fmt: compact
    markers:
      - { label: VaR 99%, value: "-$.var99", tone: neg }
      - { label: ES 97.5%, value: "-$.es975", tone: bad }
      - { label: Mean, value: $.meanPnl, tone: link }
```

### scatter of books

`LE LE-FRA`: three books, VaR across and P&L up, sized by their trade counts; each point opens its book.

```yaml
rachana: 1
sutra: kind-scatter
version: 1
match: { kind: legal-entity }
panels:
  - id: books
    kind: scatter
    title: Books, VaR against today's P&L (USD)
    rows: $.books
    x: var
    y: pnl
    size: trades
    label: book
    group: desk
    fmt: signed0
    xFmt: compact
    xLabel: VaR 99% 1D
    yLabel: P&L 1D
```

### candlestick with volume

`CMD CMD-BRENT`: 60 daily bars and volume, `Last 74.60`, `+1.12 (+1.52%)`.

```yaml
rachana: 1
sutra: kind-candlestick
version: 1
match: { kind: commodity }
panels:
  - id: bars
    kind: candlestick
    title: Front month, last 60 days
    rows: $.ohlc
    x: date
    volume: volume
    fmt: price2
    unit: USD/bbl
```

### graph of a counterparty's group

`CPTY CP-NORTHBRIDGE`: the group over the counterparty (ringed), its ISDA, CSA and four netting sets.

```yaml
rachana: 1
sutra: kind-graph
version: 1
match: { kind: counterparty }
panels:
  - id: group
    kind: graph
    title: Group, agreements and netting sets
    nodes: $.hierarchy.nodes
    edges: $.hierarchy.edges
    label: label
    group: type
    layout: tree
```

### timeline of a trade's lifecycle

`TRD BBG-60000001`: five events from *Booked* to *Maturity*, *Next payment* in amber.

```yaml
rachana: 1
sutra: kind-timeline
version: 1
match: { kind: trade }
panels:
  - { id: life, kind: timeline, title: Lifecycle, rows: $.lifecycle.timeline, label: event, detail: description }
```

### pivot of a desk's positions

`DESK DESK-COMM`: three books across five product families, with totals; the desk's `13.3m` at the bottom right.

```yaml
rachana: 1
sutra: kind-pivot
version: 1
match: { kind: desk }
panels:
  - id: grid
    kind: pivot
    title: MTM by book and product family (USD)
    rows: $.positions
    by: book
    across: family
    value: mtm
    agg: sum
    heat: true
    fmt: compact
    tone: sign
```

### All together, arranged with `area`, `span` and `height`

`span` (1–12) sets a panel's width on its column's 12-column grid, so `span: 8` and `span: 4` share a row; `height`
(1–24) fixes its height in rows, and the panel scrolls inside; `area: right` moves it to the side column. One bond
trade carries the data for twelve kinds:

```yaml
rachana: 1
sutra: bond-desk
version: 1
description: A government bond position on one screen, using every panel kind the trade's data supports.
match: { kind: trade, where: "$.productType == 'GOVT_BOND'", priority: 20 }
title:
  pill: "${$.assetClass} · ${$.productName}"
  id: $.tradeId
  with: "link($.counterparty.id, 'counterparty', $.counterparty.name)"
strip:
  - { label: Notional, bind: "$.currency + ' ' + fmt($.notional, 'amount0')" }
  - { label: Direction, bind: $.direction }
  - { label: Maturity, bind: $.maturityDate, fmt: date }
  - { label: MTM (USD), bind: $.mtm, fmt: signed0, tone: sign, emphasis: true }
  - { label: 1-day P&L, bind: $.pnl1d, fmt: signed0, tone: sign }
  - { label: DV01 (USD), bind: $.risk.dv01, fmt: signed0, tone: sign }
panels:
  # Main column, row 1: the terms (8 of 12 columns) beside the operational states (4)
  - id: terms
    kind: kv
    title: Bond terms
    key: F2
    span: 8
    columns:
      - { label: Coupon, bind: $.terms.coupon, fmt: pct4 }
      - { label: Frequency, bind: $.terms.couponFrequency }
      - { label: Face amount, bind: $.terms.faceAmount, fmt: amount0 }
      - { label: Clean price, bind: $.terms.cleanPrice, fmt: price2 }
      - { label: Yield, bind: $.terms.yield, fmt: pct4 }
      - { label: Underlying, bind: "link($.underlyingBond, 'bond')" }
  - id: ops
    kind: status
    title: Operations
    span: 4
    fields:
      - { label: Confirmation, bind: $.confirmation.status, tone: status }
      - { label: Clearing, bind: $.clearing.status, tone: status }
      - { label: Reporting, bind: $.regulatory.reportingStatus, tone: status }
  # Row 2: two charts of equal width and a fixed height of 9 rows
  - id: explain
    kind: waterfall
    title: P&L explain (USD)
    key: F3
    span: 6
    height: 9
    rows: $.pnlExplain
    label: step
    value: pnl
    sum: Closing MTM
    fmt: signed0
  - id: pnl
    kind: line
    title: Daily P&L (USD)
    span: 6
    height: 9
    rows: $.pnlHistory
    x: date
    y: pnl
    fmt: signed0
  # Row 3: the coupon ladder, full width, scrolling inside 8 rows
  - id: coupons
    kind: ladder
    title: Coupon schedule
    key: F4
    height: 8
    rows: $.schedule
    highlight: "#index == $.nextIndex"
    columns:
      - { label: Pay date, bind: "@.date", fmt: date }
      - { label: Rate, bind: "@.rate", fmt: pct4 }
      - { label: Amount, bind: "@.amount", fmt: amount0, total: true }
      - { label: Status, bind: "@.status", tone: status }
  # Row 4: what happened to the trade, as a timeline and as the audit table
  - { id: life, kind: timeline, title: Lifecycle, key: F5, span: 7, rows: $.lifecycle.timeline, detail: description }
  - id: versions
    kind: table
    title: Versions
    span: 5
    search: false
    rows: $.lifecycle.events
    columns:
      - { label: "#", bind: "@.version" }
      - { label: Event, bind: "@.event" }
      - { label: Reason, bind: "@.reason" }
      - { label: By, bind: "@.by" }
  - { id: built, kind: provenance, title: How this view was built }
  # Side column: a note, a gauge and the bucketed risk, then every linked entity
  - id: note
    kind: markdown
    title: Reading this view
    area: right
    text: "A ${lower($.direction)} position in ${$.currency}; MTM, P&L and DV01 are in ${$.mtmCurrency}."
  - { id: dv01use, kind: gauge, title: DV01 used of the 250k guideline, area: right, value: "abs($.risk.dv01)", max: "250000" }
  - { id: dv01, kind: hbar, title: DV01 by bucket (USD), area: right, rows: $.sensitivities, label: bucket, value: dv01, fmt: signed0, tone: sign }
  - { id: refs, kind: links, title: Linked entities, code: REFS, area: right, height: 10 }
keys: { F7: "link($.nettingSet, 'netting-set')", F8: impact, F9: raw }
```

![The bond-desk Sutra on TRD BBG-60000001: twelve panel kinds arranged with span and height](/static/img/guide/sutra-bond-desk.png)

*`TRD BBG-60000001` laid out by `bond-desk`: terms beside operations, the P&L waterfall beside the daily P&L, the
coupon ladder at a fixed height, the lifecycle beside the versions table; a note, a gauge, the DV01 bars and the links
on the right.*

The other eight kinds live on other entities. Grouped by entity:

```yaml
rachana: 1
sutra: counterparty-desk
version: 1
description: A counterparty with its netting sets as tabs and its group as a graph.
match: { kind: counterparty, priority: 20 }
title: { pill: "Counterparty · ${$.type}", id: $.counterpartyId, with: $.name }
strip:
  - { label: Rating, bind: $.rating, emphasis: true }
  - { label: Net MTM (USD), bind: $.netMtm, fmt: signed0, tone: sign }
  - { label: PFE peak (USD), bind: $.pfePeak, fmt: compact }
panels:
  - id: sets
    kind: tabs
    title: Netting sets
    key: F2
    span: 7
    each: $.nettingSets
    tabTitle: "@.id"
    body:
      kind: kv
      columns:
        - { label: Agreement, bind: "@.agreement", link: true }
        - { label: Trades, bind: "@.trades" }
        - { label: Net MTM, bind: "@.netMtm", fmt: signed0, tone: sign }
  - id: kyc
    kind: kv
    title: KYC
    span: 5
    columns:
      - { label: Status, bind: $.kyc.status }
      - { label: Risk rating, bind: $.kyc.riskRating }
      - { label: Last review, bind: $.kyc.lastReview, fmt: date }
      - { label: PD 1Y, bind: $.kyc.pd1y, fmt: pct2 }
  - { id: group, kind: graph, title: "Group, agreements and netting sets", key: F3, height: 12, nodes: $.hierarchy.nodes, edges: $.hierarchy.edges }
  - { id: built, kind: provenance, title: How this view was built }
  - { id: refs, kind: links, title: Linked entities, area: right }
```

![The counterparty-desk Sutra on CPTY CP-NORTHBRIDGE: netting sets as tabs and the group as a graph](/static/img/guide/sutra-counterparty-desk.png)

```yaml
rachana: 1
sutra: risk-desk
version: 1
description: A trading desk's MTM by book and family, its books, and its VaR result's P&L read from another entity.
match: { kind: desk, priority: 20 }
title: { pill: Desk, id: $.deskId, with: $.name }
strip:
  - { label: MTM (USD), bind: $.mtm, fmt: signed0, tone: sign, emphasis: true }
  - { label: VaR 99% (USD), bind: $.var99, fmt: compact }
panels:
  - { id: grid, kind: pivot, title: MTM by book and product family (USD), key: F2, rows: $.positions, by: book, across: family, value: mtm, heat: true, fmt: compact, tone: sign }
  - { id: books, kind: scatter, title: "Books, MTM and trade count", span: 6, height: 9, rows: $.books, x: mtm, y: trades, label: id, xFmt: compact, xLabel: MTM (USD), yLabel: Trades }
  - { id: varPnl, kind: line, title: P&L series of the desk's VaR result (USD), span: 6, height: 9, source: "link($.varResult, 'var')", rows: $.pnlSeries, x: date, y: pnl }
  - { id: built, kind: provenance, title: How this view was built }
  - { id: refs, kind: links, title: Linked entities, area: right }
```

![The risk-desk Sutra on DESK DESK-COMM: a pivot, a scatter and a line read from the desk's VaR result](/static/img/guide/sutra-risk-desk.png)

The `area`, `histogram`, `candlestick` and `surface` screens, on a netting set, a VaR result, a commodity and a
volatility surface, are in [Rachana, step by step](rachana-guide#1022-all-twenty-kinds-on-real-screens).

## 9. One product family, many Sutras: `match`, `where` and `priority`

Every trade has `kind: trade`; the document says which product it is. A Sutra per product matches on it:

```yaml
match: { kind: trade, where: "$.productType == 'IRS_FIXFLOAT'", priority: 10 }
```

- `where` can test anything in the document: `"$.productType == 'IRS' && size($.legs) == 2"`.
- The highest `priority` whose `where` holds wins. A general Sutra (`priority: 1`, no `where`) catches what no
  specific one matches; without it, inference lays those documents out.
- A new version (`v2`) replaces the old one for new views; keep `v1` for history and saved views.

The banking packs generate 125 product Sutras this way from one taxonomy (`tools/packgen/banking/`); write them
by hand for a small domain, generate them for a large one.

**See which Sutra won.** Open a few trades and read the last panel, *How this view was built*:

| Command | `productType` in the document | Layout |
|---|---|---|
| `TRD MX-20000001` | `IRS_FIXFLOAT` | `Sutra irs-fixfloat v1 + inference` |
| `TRD WSS-1500025` | `FX_OPTION` | `Sutra fx-option v1 + inference` |
| `TRD CLY-3000001` | `CDS_SINGLE` | `Sutra cds-single v1 + inference` |
| `TRD IMG-400067` | `AUTOCALLABLE` | `Sutra autocallable v1 + inference` |

!!! note "Studio previews ignore `match`"
    A Studio preview always applies the Sutra in the editor to the entity you chose, whatever its `match` says, so
    you can try a layout on any document. `match` and `priority` decide which documents use the Sutra once it is
    published. To check them, publish (or review and approve) and open the entities in the terminal.

## 10. Sutra and inference together

A Sutra says what matters; inference fills the rest.

- A `table` or `kv` panel without `columns` gets its columns from the data (the strongest fields first).
- `infer: true` on a panel lets inference add to it.
- The *How this view was built* panel says `Sutra irs-fixfloat v1 + inference` whenever inference helped, and an
  *inferred* tag on a panel says why.
- A document no Sutra matches is still shown: inference lays it out entirely (*inference only*).

## 11. Dates, live data and imperfect data: nothing to write

- **Business dates.** The top bar's date applies to every read. A Sutra never mentions dates: the same layout
  shows today's live data or any past business day's snapshot, and the footer says which.

  ![The business date in the top bar](/static/img/guide/business-date.png)

- **Live.** When the source streams, strip figures and table cells update in place; nothing to declare.
- **Imperfect data.** Missing fields show a dash; a panel with no data says *No data available*; wrong types
  are shown as text. A Sutra never breaks a screen.

## 12. Writing Sutras in Studio

![Studio: the Sutra editor with a live preview](/static/img/guide/studio.png)

1. Start from a Sutra that works, or **Start from inference** on a real entity.
2. Edit the YAML. The editor completes keys, panel kinds, options, formats and entity kinds from the language's
   schema, and checks the text as you type.
3. Preview against the entity, against one of the test entities, or against JSON you paste in.
4. Problems are listed with their line; click one to go there. The summary shows what the Sutra matches and
   which panels and keys it defines.
5. **Submit for review** (authors, where saving is on): an approver approves it and it goes live; or commit the file
   to the pack's `sutras/` directory through version control.

## 13. Checklist

- `match.where` is specific enough, and `priority` is higher than any general Sutra for the kind.
- The strip leads with what the reader checks first, and has at most eight figures.
- Every panel a user reaches often has a `key` (F2–F6); F7 opens the parent entity, F8 impact, F9 raw JSON.
- Money has `fmt: signed0` or `amount0`, and `tone: sign` where the sign matters.
- Labels are left to the taxonomy unless the Sutra needs different words.
- `description` says what the layout is for; `notes` say why it is what it is.
- Preview against a thin document and a rich one (Sample JSON) before saving.

**Problem codes** you may meet: `DRS-2001` YAML syntax, `DRS-2004` a file in a Sutra folder that is not a
`*.sutra.yaml` (an old `.sutra.md`, or a plain `.yaml`: convert or rename), `DRS-2009` missing or unknown
`rachana:` version, `DRS-2010` missing key, `DRS-2011` unknown key, `DRS-2012` wrong type, `DRS-2020` bad name or
version, `DRS-2021` unknown panel kind, `DRS-2022`/`2023` panel options, `DRS-2024` duplicate panel id, `DRS-2025`
function key clash, `DRS-2026` strip longer than 8, `DRS-2027` bad area, `DRS-2028` `name@version` defined twice,
`DRS-2101` an expression that does not compile. The [Rachana reference](rachana-reference) lists them all.
