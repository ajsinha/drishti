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
# Panels in depth: the twenty panel kinds

Every box on a Drishti view is a panel, and every panel is one of twenty kinds. This document explains each kind
completely: what it is for, when to choose it over another, the data it needs, every option it takes, a real Sutra
from the banking packs and what it draws, what the server computes before the console sees anything, how the
console draws it, how it behaves with empty or broken data, how much it will read, and the mistakes the parser
catches.

Read it when you choose a kind for a new Sutra, when a panel does not show what you expected, or when you change
the code of a kind. For a short tour with screenshots, start with
[The twenty panel kinds](../../console/web/guides/panel-kinds.md); for the exact grammar of every key, the
[Rachana reference](RACHANA_REFERENCE.md#panel-kinds); for a tutorial, [RACHANA_GUIDE.md](RACHANA_GUIDE.md).

## Contents

1. [How a panel is built](#1-how-a-panel-is-built)
2. [Choosing a kind](#2-choosing-a-kind)
3. [Keys every panel takes](#3-keys-every-panel-takes)
4. [Field names and expressions](#4-field-names-and-expressions)
5. [Fields and tables: kv, status, provenance, table, ladder, tabs](#5-fields-and-tables)
6. [Charts over a list: line, area, hbar, gauge, surface](#6-charts-over-a-list)
7. [waterfall](#7-waterfall)
8. [histogram](#8-histogram)
9. [scatter](#9-scatter)
10. [candlestick](#10-candlestick)
11. [graph](#11-graph)
12. [timeline](#12-timeline)
13. [pivot](#13-pivot)
14. [links and markdown](#14-links-and-markdown)
15. [Empty and broken data](#15-empty-and-broken-data)
16. [Accessibility, keyboard and print](#16-accessibility-keyboard-and-print)
17. [Export](#17-export)
18. [Limits](#18-limits)
19. [Common mistakes and their problem codes](#19-common-mistakes-and-their-problem-codes)
20. [Where the code is](#20-where-the-code-is)

## 1. How a panel is built

A panel goes through five steps between the Sutra file and the screen.

| Step | Where | What happens |
|---|---|---|
| Parse | `SutraParser`, `SutraBuilder` | The panel's `kind` must be one of the twenty (`DRS-2021`); every option must be one the kind accepts (`DRS-2023`); every required option must be present (`DRS-2022`); restricted option values are checked (`DRS-2029`). |
| Compile | `SutraExpressions` | The options that hold expressions (`rows`, `nodes`, `edges`, marker values, `value` on a gauge, …) are compiled, so a broken expression is `DRS-2101` when the Sutra loads, not when a user opens a view. |
| Merge | `LayoutMerger` | The Sutra is matched to the document, and inference fills what the Sutra leaves open (`infer: true`, a `kv` with `rows` and no `columns`, the links panel). |
| Bind | `Binder`, `ChartBinder` | Each panel is bound to the document on its own thread of the bind pool (`drishti.engine.bind-parallelism`). The result is a `PanelView`: `id`, `kind`, `title`, `code`, `key`, `area`, `inferred`, `explanation`, `data` (one record per kind), `error`, `empty`, and the Sutra's `span` and `height` when it sets them. An exception in one panel becomes that panel's `error`; the other panels and the view are unaffected. |
| Draw | `_macros/panels.html`, `view.js`, `charts.js`, `tables.js` | The console renders each panel with one Jinja macro per kind (first paint, no JavaScript needed for text, tables and timelines) and then enhances it: tables sort and filter, charts are drawn with the vendored ECharts. |

The view model is what `GET /api/v1/views/{kind}/{id}` returns. On the sample `var` entity `VAR-RATES` the whole view
is 5.7 kB and is built in 0.75 ms on the server (`timings.total`), of which binding the four panels takes 0.38 ms.

Live views send changed panels as patches. A `line` or `area` panel's new data moves the existing chart; every other
kind is re-rendered on the console with the same macro and re-enhanced, so charts, tables and timelines look the
same after a tick as on first paint.

## 2. Choosing a kind

| Your data looks like | Use | Rather than |
|---|---|---|
| one object with named fields | `kv` | `table` with one row |
| operational states (confirmed, cleared, settled) | `status` | `kv`: `status` colours each value by meaning |
| a list of similar rows | `table` | `tabs` when there are more than four |
| a list where one row matters most (the next payment) | `ladder` | `table`: a ladder highlights rows and never hides any |
| two to four similar objects (legs, tranches) | `tabs` | `table`: tabs show each object's own fields |
| points along an axis (tenors, dates) | `line` | `hbar` when the order of the axis matters |
| several bands over one axis against a limit | `area` | several `line` panels |
| a few labelled amounts | `hbar` | `waterfall` when the amounts add up to something |
| one number against a maximum | `gauge` | a strip figure when there is no maximum |
| a grid of numbers over two axes (a vol surface) | `surface` | `pivot` when the grid must be aggregated from rows |
| a start value, signed contributions and an end value | `waterfall` | `hbar`, which does not show the running total |
| many numbers whose spread matters | `histogram` | `line`, which shows order, not distribution |
| rows with two measures to compare | `scatter` | two `hbar` panels |
| prices with open, high, low and close by date | `candlestick` | `line` of the close, which hides the day's range |
| entities and how they relate | `graph` | `links`, which lists references without their structure |
| dated events | `timeline` | `ladder`, which needs columns and does not sort |
| rows to total by one field across another | `pivot` | `table` with totals, which aggregates one dimension only |
| references to other entities | `links` (automatic) | id columns in a table |
| a note for the reader | `markdown` | a title that says too much |
| where the view came from | `provenance` | |

## 3. Keys every panel takes

These keys belong to every panel; they are never kind options, and any other key must be an option of the kind.
`span` and `height` size a top-level panel only: a `tabs` body takes the size of its panel (`DRS-2030`).

| Key | Required | Meaning |
|---|---|---|
| `id` | yes | Unique in the Sutra (`DRS-2024` when not). Used in the URL fragment, the CSV file name and live patches. |
| `kind` | yes | One of the twenty. |
| `title` | | Heading text; may contain `${…}` templates. The function-key bar labels a panel with the title's first two words, cut at ` (` or ` · `: write `MTM grid (by book and currency, USD)`, not `MTM by book and currency (USD)`, which would read *MTM by*. |
| `description` | | What the panel answers, for authors and reviewers. Never shown in the view. |
| `key` | | A function key `F2`–`F12`, once per Sutra (`DRS-2025`). |
| `code` | | A short code at the right of the heading (`PNLX`, `HIER`). |
| `area` | | `main` (default) or `right` (`DRS-2027` otherwise). Charts and tables wider than about six columns belong in `main`. |
| `span` | | Width in columns of a 12-column grid, a whole number from 1 to 12 (default: 12, the whole column). Panels narrower than their column sit side by side in Sutra order: `span: 8` and `span: 4` make one row. On a phone every panel takes the whole width. `DRS-2030` outside 1-12. |
| `height` | | Height in grid rows of 2.5 rem (40 px at the usual text size), 1 to 24 (default: as tall as the content). A panel with a height scrolls inside; printing ignores it. `DRS-2030` outside 1-24. |
| `infer` | | `true` lets inference add columns the Sutra does not list. |
| `columns` | | For `kv`, `table`, `ladder` and `surface` (and a `tabs` body): `{label, bind, fmt, tone, total, link}`. |
| `body` | | For `tabs` only (`DRS-2023` on any other kind). |

## 4. Field names and expressions

Options are of three sorts, and the difference matters because only expressions are compiled at load time.

| Sort | Examples | Read as |
|---|---|---|
| Expression | `rows: $.pnlExplain`, `nodes: $.hierarchy.nodes`, `value: "-$.var99"` in a marker, a gauge's `value` | Rachana-EL over the document (`$`). Compiled when the Sutra loads. |
| Field of each row | `label: step`, `x: var`, `by: book` | A field name of each element of the list. |
| Plain value | `fmt: signed0`, `agg: sum`, `bins: 30`, `heat: true` | Text, a number or a boolean. |

For `line`, `area` and `hbar`, a field of each row is a bare name only: `label: "@.bucket"` looks for a field
literally called `@.bucket` and finds nothing. For the seven kinds from `waterfall` to `pivot`, a field may also be a
dotted path (`counterparty.name`) or an expression over the row when it starts with `@` or `$` or contains a space
or a parenthesis (`y: "@.pnl / 1000"`, `label: "upper(@.book)"`); such an expression is evaluated per row, so a bare
field name is faster on long lists. `surface`'s `y` follows the same rule (an expression when it starts with `$`
or `@`).

## 5. Fields and tables

These kinds are drawn as HTML by the macro alone; JavaScript only adds sorting, filtering, paging and tabs.

### kv

**For** the terms of a trade, an identifier block, any object with named fields. **Data**: `columns` evaluated
against the document, or against the object `rows` names; with `rows` and no `columns`, inference lists up to 16
scalar fields of the object. **Server**: one formatted, toned cell per column (`fmt`, `tone`, and a link when the
value is an id a pack recognises). **Console**: a grid four across in the main column, two on the right.

```yaml
  - id: terms
    kind: kv
    title: Terms
    code: TRM
    key: F2
    columns:
      - { label: Fixed rate, bind: $.terms.fixedRate, fmt: pct4 }
      - { label: Day count, bind: $.terms.dayCount }
      - { label: Counterparty, bind: $.counterparty.name }
```

On `TRD MX-20000001` this shows *Fixed rate 4.0829%*, *Day count ACT/365F*, *Counterparty Meridian Reinsurance Ltd*.
Data: `{"fields": [{"label": "Fixed rate", "text": "4.0829%", "path": "$.terms.fixedRate"}, …]}`. Empty when every
cell is blank or a dash.

### status

**For** operational states. **Option**: `fields`, a list of `{label, bind, fmt, tone}`; each `bind` is an
expression. With `tone: status`, text containing *fail*, *reject*, *dispute* or *breach* is `bad`, *pending*,
*unmatched* or *warn* is `warn`, other text `ok`. Drawn like `kv`; the colour always accompanies the text.

### provenance

**For** *How this view was built*: layout label (`Sutra irs-fixfloat v1 + inference`), the shape fingerprint and the
source with its generation. No options. Never empty.

### table

**For** a list of similar rows. **Options**: `rows` (required), `columns` (inferred when absent: scalar fields present
in at least 60% of rows, up to 9), `limit`, `moreLabel`, `totalLabel`, `search`. **Server**: one row of formatted
cells per element; a total row sums `total: true` columns over **all** rows, including those beyond `limit`; the
"more" line counts the rest. A column bound to a field ending in `Id`, `Ref` or `_id` links automatically.
**Console**: `tables.js` sorts by any heading (numbers, amounts such as `1.5m`, percentages and dates as values),
filters (a box in the heading, per-column filters taking `>1m` or `<=0`), pages (25 rows by default, remembered
per browser) and walks with the keyboard. `search: false` hides the filter.

On netting set `NS-MERIDIAN-RE-NY`, *Member trades* lists the trades with their MTM and a total. Data:
`{"columns": […], "numeric": […], "rows": [{"cells": […], "highlight": false, "path": "$.trades[0]"}], "total": {…}, "more": "…", "search": true}`.

### ladder

**For** a list where some rows matter more: a schedule with the next payment lit. Like `table` without `limit` or
`moreLabel` (`DRS-2023`: a ladder shows every row); `highlight` is an expression per row (`@`, `#index`).
`highlight: "#index == $.nextIndex"` on `MX-20000001` lights the 2026-12-30 cashflow.

### tabs

**For** two to four similar objects. **Options**: `each` (required), `tabTitle` (an expression per element),
`layout` (`tabs` or `columns`), and a `body` of `kind: kv` whose columns are evaluated per element. With
`layout: columns`, *Leg 1 · Receive fixed 4.0829%* and *Leg 2 · Pay AONIA compounded* stand side by side on
`MX-20000001`. Tabs are buttons with `role="tab"`; the selected tab survives a live repaint.

## 6. Charts over a list

`line` and `area` are drawn by `view.js` with ECharts (SVG renderer, colours from the theme's tokens, redrawn on a
theme change); `hbar` and `gauge` are HTML bars whose widths `view.js` sets from `data-w` (the content security
policy forbids inline styles); `surface` is an ECharts heatmap.

### line

**For** points along an axis: a curve by tenor, P&L by date. **Options**: `rows` (default `points`), `x` (default
`tenor`), `y` (default `value`), `source` (a `link(id, kind)`: the linked entity is fetched with the same business
date and `rows` is read from **its** document), `mark` (an x to highlight, written under the chart as
`5Y point 4.03%`), `fmt`, `unit`. Every trade Sutra in the trading pack draws its discount curve this way:
`source: "link($.discountCurve, 'ir-curve')"` with `rows: points`, `x: tenor`, `y: zeroRate`. When the curve has
not arrived within `drishti.graph.link-budget` (40 ms), the panel says *waiting for CRV-AUD-OIS* and fills in on the
next build.

### area

**For** several bands over one axis against a limit: an exposure profile. **Options**: `rows`, `x`, `series` (a
list of `{label, value, tone}`), `limit` (an expression, drawn dashed), `limitLabel`, `unit`. On a netting set,
*Exposure profile* draws EE and PFE 95 against the credit limit.

### hbar

**For** a few labelled amounts: DV01 by bucket. **Options**: `rows`, `label`, `value`, `fmt`, `tone` (without it,
negative bars are `neg`). Bars scale to the largest absolute value; rows without a finite number are skipped. Every
trade's *DV01 by bucket (USD)* is one.

### gauge

**For** one number against a maximum. **Options**: `value` (required expression), `max` (expression, default 1),
`fmt` (applied to value ÷ max, default `pct0`), `label`. Empty when the value is not a number.

### surface

**For** a grid already in the document: a volatility smile by expiry and delta. **Options**: `rows`, `y`
(required), `columns` (one per x point), `fmt`, `unit`, `view` (`heatmap` or `3d`). The server returns `z[row][column]`
with its minimum and maximum; the console labels cells when there are at most 72, and loads ECharts GL (vendored,
patched to need no `eval`) only when **3D** is pressed, falling back to the heatmap without WebGL. `FXV FXV-EURUSD`
(F4) is the reference.

## 7. waterfall

**What it is for.** Steps from a start to an end: yesterday's MTM, today's moves by risk factor, today's MTM; a
book's P&L from risk factors to the actual figure. The reader sees both each contribution and where the running
total stands after it.

**Choose it over** `hbar` when the amounts add up to something meaningful, and over a `table` when the size of each
step relative to the others is the point.

**Data.** A list of rows, in order. Each row has a label, a signed amount and, optionally, a flag that makes it a
total (its amount is a level, not a move).

```json
"pnlExplain": [
  {"step": "Opening MTM", "pnl": 1748877, "total": true},
  {"step": "Carry", "pnl": -11426}, {"step": "Roll-down", "pnl": 11390}, {"step": "Rates delta", "pnl": 132686},
  {"step": "FX delta", "pnl": -2391}, {"step": "Vol (vega)", "pnl": 5004}, {"step": "Theta", "pnl": -8685},
  {"step": "Unexplained", "pnl": 408}
]
```

**Options.**

| Option | Required | Default | Meaning |
|---|---|---|---|
| `rows` | yes | | Expression giving the steps. |
| `label` | | `label` | Field with the step's name. |
| `value` | | `value` | Field with the amount. A row whose amount is not a number is left out. |
| `total` | | `total` | Field that marks a total row. |
| `sum` | | | Label of a closing total bar appended at the running sum. |
| `fmt` | | six significant digits | Format of the amounts. |
| `unit` | | | Axis unit. |
| `colors` | | `gain-loss` | `gain-loss`: rises in the theme's good colour (green), falls in its bad colour (red). `theme`: the theme's positive and negative colours (blue and orange in most themes), which readers with red-green colour blindness tell apart. Totals are neutral (grey) either way. Any other value is `DRS-2029`. |

**Banking example.** Every trade Sutra in the trading pack (`packs/trading/sutras/*/*.v1.sutra.yaml`):

```yaml
  - id: explain
    kind: waterfall
    title: "P&L explain (USD, opening to closing MTM)"
    code: PNLX
    key: F5
    rows: $.pnlExplain
    label: step
    value: pnl
    sum: Closing MTM
    fmt: signed0
```

On `TRD MX-20000001` it draws nine bars: *Opening MTM +1,748,877* as a full bar, seven floating steps, and *Closing
MTM +1,875,863*, which equals the trade's MTM in the strip. The market-risk pack's `pnl-explain` Sutra draws the
same for a book (`PNL PNL-COMM-1`, F3: nine factors ending at *Actual −180,316*).

**What the server computes.** `ChartBinder.waterfall` walks the rows once, keeping a running total. A normal step
becomes `{label, value, from: running, to: running + value, text, tone: ok|bad, total: false}` (`pos|neg` with
`colors: theme`); a total step becomes `{from: 0, to: value, tone: muted, total: true}` and resets the running total
to its value; `sum` appends a total at the final running total. At most `drishti.panels.max-points` rows are read.

```json
{"steps": [{"label": "Opening MTM", "value": 1748877, "from": 0, "to": 1748877, "text": "+1,748,877", "tone": "muted", "total": true},
           {"label": "Carry", "value": -11426, "from": 1748877, "to": 1737451, "text": "−11,426", "tone": "bad", "total": false}, …]}
```

**How the console draws it.** `charts.js` draws each step as a rectangle from `from` to `to` (an ECharts custom
series) filled with the step's tone, so rises are green (`--d-ok`), falls red (`--d-bad`) and totals grey
(`--d-muted`) in every theme, light and dark; with `colors: theme`, rises take `--d-pos` and falls `--d-neg`. Each bar
is labelled with its signed text when there are at most 12 steps, so the colour is never the only cue. When large totals sit beside small moves (as with an MTM), the value axis starts near the moves instead of
at zero and the total bars run off the bottom; their labels carry the full figure. Labels rotate 30° beyond six
steps. The tooltip shows the step's amount and the running total after it.

## 8. histogram

**What it is for.** The spread of many numbers: the 500 scenario P&Ls a historical VaR is read from, a P&L history.
Marker lines put the risk figures on the distribution they come from.

**Choose it over** `line` when order does not matter and shape does (fat tails, skew, where VaR sits).

**Data.** A list of numbers, or of rows with a numeric field.

**Options.**

| Option | Required | Default | Meaning |
|---|---|---|---|
| `rows` | yes | | Expression giving the list. |
| `value` | | | With rows, the field (or `@` expression) holding each number. Without it, each element is the number. |
| `bins` | | √count, between 5 and 40 | Number of equal-width bins, a whole number from 1 to 200 (`DRS-2029` otherwise). |
| `markers` | | | List of `{label, value, tone}`. `value` is an expression over the document (compiled at load); `tone` defaults to `accent`. Any other key is `DRS-2029`. |
| `fmt` | | six significant digits | Format of bin edges and marker values. |
| `unit` | | | Axis unit. |

**Banking example.** The market-risk pack's `var` Sutra:

```yaml
  - id: scenarios
    kind: histogram
    title: "Scenario P&L distribution, 500 days (USD)"
    code: DIST
    key: F3
    rows: $.scenarioPnl
    fmt: compact
    unit: USD
    markers:
      - { label: VaR 99%, value: "-$.var99", tone: neg }
      - { label: ES 97.5%, value: "-$.es975", tone: bad }
      - { label: Mean, value: $.meanPnl, tone: link }
```

On `VAR VAR-RATES` it draws 500 values in 22 bins from −13.5m to +11.65m, with dashed lines at *VaR 99% −9.6m*,
*ES 97.5% −10.9m* and *Mean −335.2k*; the legend under the chart repeats the markers and says *500 values*. The
generator scales each desk's scenario vector so that its fifth worst value (the 1% quantile of 500) is minus the
desk's VaR, so the VaR line falls where the tail begins.

**What the server computes.** `ChartBinder.histogram` reads up to `drishti.panels.max-values` numbers (default
100,000), counts the rest and anything that is not a number as `dropped`, finds the minimum and maximum, and counts
the values into `k` equal-width bins `[from, to)` (the last one closed). All values equal gives one bin. Each marker
expression is evaluated; a marker whose value is missing or not a number is left out (the histogram still draws).

```json
{"bins": [{"from": -13525866, "to": -12415029.5, "count": 2, "label": "−13.5m to −12.4m"}, …],
 "markers": [{"label": "VaR 99%", "value": -9611219, "text": "−9.6m", "tone": "neg"}, …], "count": 500, "dropped": 0, "fmt": "compact", "unit": "USD"}
```

**How the console draws it.** Bins are touching rectangles on a value axis (so uneven ranges are honest), markers
are labelled dashed vertical lines in their tone, labels alternating top and bottom so neighbouring markers do not
collide. The axis extends to include every marker.

## 9. scatter

**What it is for.** Two measures per row: books' VaR against their P&L, desks' DV01 against MTM. A third field can
size the points and a fourth colour them.

**Choose it over** two `hbar` panels when the relation between the measures is the question (which books earn the
most per unit of risk).

**Options.**

| Option | Required | Default | Meaning |
|---|---|---|---|
| `rows` | yes | | Expression giving the rows. |
| `x`, `y` | yes | | Fields (or `@` expressions) of the two measures. Rows without both numbers are left out. |
| `size` | | | Field whose absolute value sizes the point (symbol area proportional, 6 to 28 px). |
| `label` | | | Field labelling the point. A value that is an id a pack recognises, or a `link(…)`, makes the point open that entity. |
| `group` | | | Field whose value colours the point; one series and one legend entry per group, in first-seen order. |
| `fmt` | | six significant digits | Format of y. |
| `xFmt` | | `fmt` | Format of x. |
| `xLabel`, `yLabel` | | the field names | Axis titles. |

**Banking example.** The banking-core pack's `legal-entity` Sutra:

```yaml
  - id: riskReturn
    kind: scatter
    title: "Risk and return (books: VaR against today's P&L, USD)"
    code: RR
    key: F3
    rows: $.books
    x: var
    y: pnl
    label: book
    fmt: signed0
    size: trades
    group: desk
    xFmt: compact
    xLabel: VaR 99% 1D (USD)
    yLabel: P&L 1D (USD)
```

On `LE LE-NY` it draws the fifteen books of the entity's five desks, one colour per desk, sized by trade count:
*BOOK-RATES-1* at VaR 6.0m and P&L −2.96m, the credit books far to the right. Clicking a point opens its book.

**What the server computes.** `ChartBinder.scatter` evaluates x and y per row, keeps rows where both are numbers, up
to `drishti.panels.max-points` points (default 5,000; the rest are counted in `more`), formats both, resolves the
label to a link, and collects the groups.

```json
{"points": [{"x": 6016760, "y": -2955541, "size": 49, "xText": "6.0m", "yText": "−2,955,541", "label": "BOOK-RATES-1",
             "group": "Rates · Swaps and options", "link": {"kind": "book", "id": "BOOK-RATES-1", "mnemonic": "BOOK"}}, …],
 "groups": ["Rates · Swaps and options", …], "xLabel": "VaR 99% 1D (USD)", "yLabel": "P&L 1D (USD)", "sized": true, "more": 0}
```

**How the console draws it.** Both axes scale to the data (not from zero). Point labels are drawn when there are at
most 24 points; the tooltip gives label, group and both values. A click on a linked point follows the link (inside
a workspace pane it becomes a selection, like any link).

## 10. candlestick

**What it is for.** Daily bars of a price: open, high, low and close, with volume.

**Choose it over** `line` when the day's range and direction matter, not only the close.

**Options.**

| Option | Required | Default | Meaning |
|---|---|---|---|
| `rows` | yes | | Expression giving the bars, oldest first. |
| `x` | | `date` | Field with each bar's date. |
| `open`, `high`, `low`, `close` | | those names | Price fields. A bar without all four numbers is left out. |
| `volume` | | | Volume field. Without it there are no volume bars. |
| `fmt` | | six significant digits | Price format (last price and change). |
| `unit` | | | Price axis unit. |

**Banking example.** The market-data pack's `equity` and `commodity` Sutras:

```yaml
  - id: ohlc
    kind: candlestick
    title: Daily bars (last 60 days)
    code: OHLC
    key: F3
    rows: $.ohlc
    x: date
    fmt: price2
    volume: volume
```

On `EQ EQ-CSCA` it draws 60 bars from 2026-07-08 to 2026-09-30 over a volume pane, and *Last 21.84* with
*+0.81 (+3.85%)* under the chart. The last 30 closes are those of the line chart *Price, last 30 days* on the same
view: both are generated from the same walk. `CMD CMD-BRENT` shows the Brent front month.

**What the server computes.** `ChartBinder.candlestick` keeps the latest `drishti.panels.max-points` rows (default
5,000), reads the four prices of each, makes `high` at least the open and close and `low` at most, and computes the
last close and the change from the previous close, toned by sign.

```json
{"candles": [{"x": "2026-09-29", "open": 20.83, "high": 21.14, "low": 20.81, "close": 21.03, "volume": 12170753}, …],
 "volume": true, "last": "21.84", "change": "+0.81 (+3.85%)", "tone": "pos", "fmt": "price2"}
```

**How the console draws it.** Rising bars (close ≥ open) in the `pos` tone, falling ones in `neg`; volume bars in a
second pane share the date axis and the crosshair. Beyond 60 bars the chart opens on the last 60 and scrolls or
zooms with the mouse wheel inside it.

## 11. graph

**What it is for.** Entities and how they relate: a counterparty's legal-entity hierarchy from the group (ultimate
parent) to its members, the master agreement the counterparty signed, the CSA that secures it and the netting sets
it governs. Nodes that are entities open them.

**Choose it over** `links` when the structure matters (who owns whom, what nets under what), not only the fact of a
reference.

**Data.** Two lists: nodes with an `id` (and the fields the options name), and edges with `from`, `to` and an
optional `label`.

**Options.**

| Option | Required | Default | Meaning |
|---|---|---|---|
| `nodes` | yes | | Expression giving the nodes. A node needs an `id`; a repeated id is kept once. A node may say its `kind` when the id alone does not tell. |
| `edges` | | | Expression giving the edges. An edge to a node that is not listed is left out. |
| `label` | | `label` | Node field shown under the node (the id when empty). |
| `group` | | `type` | Node field that colours the node and makes the legend. |
| `layout` | | `tree` | `tree` or `force` (`DRS-2029` otherwise). |

Note that a graph takes `nodes`, not `rows`: `rows` on a graph is `DRS-2023`.

**Banking example.** The banking-core pack's `counterparty` Sutra:

```yaml
  - id: hierarchy
    kind: graph
    title: Group hierarchy (agreements and netting sets)
    code: HIER
    key: F3
    nodes: $.hierarchy.nodes
    edges: $.hierarchy.edges
    label: label
    group: type
    layout: tree
```

On `CPTY CP-MERIDIAN` it draws *Meridian Financial Group* at the top, its two members *Meridian Life Assurance*
(this view, larger and ringed) and *Meridian Reinsurance Ltd* below, then *ISDA 2002 Master* under the counterparty,
and under the agreement its *Credit support annex* and four netting sets (*NS-MERIDIAN-NY · 16 trades*, …). Each
node opens its entity: `GRP-MERIDIAN`, `CP-MERIDIAN-RE`, `AGR-MERIDIAN-ISDA`, `CSA-MERIDIAN`, `NS-MERIDIAN-NY`.

**What the server computes.** `ChartBinder.graph` keeps nodes with an id, once each, up to `drishti.panels.max-nodes`
(default 300; the rest are counted in `more`), resolves each id to a link through the packs' id patterns (or the
node's `kind`), marks the node whose id is the viewed entity as `focus`, and keeps the edges whose two ends are
kept, up to twice the node limit.

```json
{"nodes": [{"id": "GRP-MERIDIAN", "label": "Meridian Financial Group", "group": "Group (ultimate parent)",
            "link": {"kind": "counterparty-group", "id": "GRP-MERIDIAN", "mnemonic": "GRP"}, "focus": false}, …],
 "edges": [{"from": "GRP-MERIDIAN", "to": "CP-MERIDIAN", "label": "parent of"}, …], "layout": "tree", "more": 0}
```

**How the console draws it.** With `tree`, `charts.js` places the nodes nothing points to on the first level and
each other node one level below the first node that points to it (breadth first), spread evenly across the panel's
own width, so circles stay round; with `force`, ECharts' force layout pulls related nodes together and nodes can be
dragged. Arrows show the edges' direction; edge labels are drawn when there are at most 30 edges. Hovering a node
highlights its neighbours. Linked nodes are circles and open their entity on click; other nodes are rounded squares.
**Entities** under the chart lists every node as a link, for the keyboard and screen readers.

## 12. timeline

**What it is for.** Dated events in order: a trade's lifecycle (booked, confirmed, cleared, amended, partially
terminated, cash settled, the next payment, maturity), a margin call's workflow.

**Choose it over** `ladder` when the events are the subject (each with a status and a note), not rows of figures.

**Options.**

| Option | Required | Default | Meaning |
|---|---|---|---|
| `rows` | yes | | Expression giving the events, in any order. |
| `date` | | `date` | Field with the date or ISO timestamp. |
| `label` | | `event` | Field with the event's name. |
| `detail` | | `description` | Field with a short note. |
| `status` | | `status` | Field with the status shown beside the event. |
| `tone` | | `status` | Tone of the status: `status` (by meaning), `sign`, or a fixed tone. |

**Banking example.** Every trade Sutra in the trading pack:

```yaml
  - id: lifecycle
    kind: timeline
    title: Lifecycle
    code: LIFE
    key: F6
    rows: $.lifecycle.timeline
    detail: description
```

On `TRD MX-20000001` it lists seven events: *2025-07-25 Booked* (Done, *Captured in Murex by TRDR-ASHAH: receive
fixed Interest rate swap (fixed/float)*), *Confirmed* (Matched on MarkitWire), *2025-07-28 Cleared* (Novated to LCH
SwapClear), *2025-08-07 Amended* (Notional corrected, version 2), *2026-09-29 Cash settled*, *2026-12-30 Next payment*
(Pending, amber) and *2032-06-25 Maturity*. About one live trade in eight also has a *Partially terminated* event.

**What the server computes.** `ChartBinder.timeline` reads each event's fields, skips rows with neither a date nor a
label, resolves the status's tone, sorts by date as text (ISO dates and timestamps sort correctly as written) and
keeps the latest `drishti.panels.max-events` (default 500), counting the rest in `more`.

```json
{"events": [{"date": "2025-07-25", "label": "Booked", "detail": "Captured in Murex by TRDR-ASHAH: …", "status": "Done", "tone": "ok"}, …], "more": 0}
```

**How the console draws it.** As HTML only: an ordered list down a rule, a dot per event (filled green for `ok`,
an amber ring for `warn`, red for `bad`), the date in monospace, the event in bold, the status as a small outlined
tag in its tone, and the note below. When events were left out, *N earlier events not shown* follows the list.

## 13. pivot

**What it is for.** Rows totalled by one field down the side and another across the top: MTM by book and currency,
exposure by rating and tenor bucket, trade counts by book and product family.

**Choose it over** a `table` with totals when the rows must be aggregated along two dimensions, and over `surface`
when the grid is not already in the document.

**Options.**

| Option | Required | Default | Meaning |
|---|---|---|---|
| `rows` | yes | | Expression giving the rows. |
| `by` | yes | | Field whose values are the row keys, in first-seen order. A missing value is `(none)`. |
| `across` | yes | | Field whose values are the column keys, in first-seen order. |
| `value` | | | Field aggregated. Without it the pivot counts rows (whatever `agg` says). A row whose value is not a number is left out. |
| `agg` | | `sum` | `sum`, `count`, `avg`, `min` or `max` (`DRS-2029` otherwise). |
| `fmt` | | six significant digits | Format of the cells (counts are always whole numbers). |
| `tone` | | | Tone of the cells, for example `sign`. |
| `heat` | | `false` | `true` shades each cell from the smallest to the largest value. Must be a boolean (`DRS-2029`). |
| `totals` | | `true` | `false` hides the total column and row. Must be a boolean. |

**Banking example.** The banking-core pack's `desk` Sutra has two:

```yaml
  - id: mtmGrid
    kind: pivot
    title: MTM grid (by book and currency, USD)
    code: PIV
    key: F3
    rows: $.positions
    fmt: compact
    tone: sign
    by: book
    across: currency
    value: mtm
    agg: sum
    heat: true
  - id: tradeCount
    kind: pivot
    title: Trades by book and product family
    rows: $.positions
    by: book
    across: family
    agg: count
```

`$.positions` holds one row per trade of the desk (`{tradeId, book, currency, family, mtm}`, 156 rows on
`DESK-RATES`). On `DESK DESK-RATES` the first pivot shows three books across EUR, USD, CHF, GBP, JPY, CAD and AUD:
*BOOK-RATES-1* totals *177.4m*, *BOOK-RATES-2* *−66.2m*, the desk *132.8m* (the same as the desk's MTM in the
strip). The second counts 156 trades: 60 swaps, 54 options, 24 exotics, 12 futures and 6 linear products.

**What the server computes.** `ChartBinder.pivot` reads up to `drishti.panels.max-values` rows (default 100,000) and
keeps, for every cell, every row key, every column key and the whole grid, the count, sum, minimum and maximum of the
values that fall there; each shown figure is the aggregate of those underlying rows, so an `avg` total is the average
of all its rows, not the average of the cells. Up to `pivot-columns` column keys (default 40) and `pivot-rows` row
keys (default 200) are shown; values under further column keys still count in the row totals and the grand total,
and further row keys are counted in `more`. The smallest and largest shown cells give the heat scale.

```json
{"by": "book", "columns": ["EUR", "USD", "CHF", "GBP", "JPY", "CAD", "AUD"],
 "rows": [{"label": "BOOK-RATES-1", "cells": [{"text": "4.3m", "tone": "pos"}, …], "values": [4323568, 87191847, …], "total": {"text": "177.4m", "tone": "pos"}}, …],
 "totals": [{"text": "95.6m", "tone": "pos"}, …, {"text": "132.8m", "tone": "pos"}], "agg": "sum", "heat": true, "min": -48311455, "max": 106978259, "more": 0}
```

**How the console draws it.** As an HTML table (no chart): row keys as row headings, the total column and row in
bold. With `heat`, each cell gets one of ten shades of the accent colour (`heat-0` to `heat-9`) by its place between
the smallest and the largest value, behind text that keeps its own tone, so the number is always readable. Being a
table, it sorts, filters and pages like any other (`tables.js`).

## 14. links and markdown

### links

Every entity the document refers to, found from the packs' `graph.fields`, each fetched within the link budget and
shown with a badge (a counterparty's rating, a netting set's PFE). No options. *pending* when the entity has not
arrived in time, *missing* when it does not exist. A Sutra with a links panel always reports `+ inference`.

### markdown

Static text for the reader: `text` is a template (`${…}` evaluated against the document) shown as one plain
paragraph. Markdown syntax is not rendered inside the panel. Notes for authors belong in the Sutra's `notes`.

## 15. Empty and broken data

Real documents are incomplete. A panel whose data is missing or of the wrong shape is never an error page: it is
returned flagged `empty` (and `error` when binding failed), and the console shows *No data available* with, when
there was an error, *The data did not have the shape this panel expects* and the reason in a tooltip.

| Kind | Empty when |
|---|---|
| `kv`, `status`, `provenance` | every cell is blank or a dash |
| `table`, `ladder` | no rows |
| `tabs` | no tabs, or every tab blank |
| `line`, `area` | no x values, or no finite number in any series |
| `hbar` | no bar |
| `gauge` | the value is not a number |
| `surface` | no finite cell |
| `links` | no linked entity (*No linked entities*) |
| `markdown` | blank text |
| `waterfall` | no step with a number |
| `histogram` | no number to bin |
| `scatter` | no row with both measures |
| `candlestick` | no bar with all four prices |
| `graph` | no node with an id |
| `timeline` | no event with a date or a label |
| `pivot` | no row with a number (or no row at all, for a count) |

For the seven kinds from `waterfall` on, `rows` (or `nodes`) that is not a list gives an empty panel, a row without
the fields asked for is left out, and a value that is not a number is skipped: none of these is an error. The
server test `ChartPanelsTest` checks this on a document where every list is the wrong type, and the console test
`test_every_panel_kind_renders_imperfect_data_without_failing` renders all twenty macros with null, empty and
mistyped data. In the browser, a chart whose data cannot be drawn is replaced by *No data available* without
stopping the other charts.

## 16. Accessibility, keyboard and print

- **Keys.** A panel's `key` (F2–F12) scrolls to it and focuses it; the function-key bar lists them.
- **Tables** (`table`, `ladder`, `pivot`) take focus; ↑ ↓ move between rows (turning pages), PgUp and PgDn page,
  Home and End jump, Enter opens the selected row's link. Headings sort with Enter or Space.
- **Charts** have `role="img"` and an `aria-label` that says what they show (*Scenario P&L distribution, 500 days
  (USD): 500 values in 22 bins*). Under each chart of the five ECharts kinds, a collapsed **Data** (or **Entities**)
  section holds the same numbers as a table or a list of links. It is reachable with Tab and read by screen readers.
- **Colour is never alone.** Tones always accompany a sign or text: a negative amount carries its minus sign, a
  timeline status its word, a heat cell its number.
- **Themes.** Charts read their colours from the theme's tokens and are redrawn when the theme changes; the token
  contrast (text at least 4.5:1, accents at least 3:1) is checked for every theme by `test_contrast.py`.
- **Print** uses the light print palette and prints the charts as drawn; open **Data** first to print the numbers too.
  It keeps the order and widths of the view (the user's own layout when they keep one), leaves hidden panels out and
  prints every panel at its full height.
- **Layout mode** (`Alt+L`, [USER_GUIDE.md](USER_GUIDE.md#layout-mode-arrange-a-view-your-way)) lets a user move,
  resize and hide panels for themselves from the keyboard as well as with a pointer; each change is announced.

## 17. Export

The ↓ in a panel's heading downloads it as CSV (`/export/{kind}/{id}/{panel}.csv`), with numbers as numbers rather
than as the view formats them.

| Kind | Columns |
|---|---|
| `table`, `ladder` | the table's columns, then the total row |
| `kv`, `status` | Field, Value |
| `tabs` | Tab, Field, Value |
| `line`, `area` | x, then one column per series |
| `hbar` | Label, Value |
| `gauge` | Label, Value, Limit |
| `surface` | y, then one column per x |
| `links` | Link, Entity, Kind, Summary |
| `waterfall` | Step, Amount, Total |
| `histogram` | From, To, Count |
| `scatter` | Label, Group, x label, y label, Size |
| `candlestick` | Date, Open, High, Low, Close, Volume |
| `graph` | Type (`node` or `edge`), Id or from, To, Label, Group |
| `timeline` | Date, Event, Status, Detail |
| `pivot` | the `by` field, one column per column key, Total; then the Total row |

## 18. Limits

Every kind reads one document that is already in memory, so binding is fast; the limits exist so that one very long
list cannot make a view slow to build or heavy to draw. `drishti.panels` sets them
([CONFIGURATION.md](../admin/CONFIGURATION.md)):

| Key | Default | Applies to | Beyond the limit |
|---|---|---|---|
| `max-values` | 100,000 | numbers a `histogram` bins; rows a `pivot` aggregates | counted in the histogram's `dropped`; not aggregated |
| `max-points` | 5,000 | points a `scatter` draws; steps a `waterfall` reads; bars a `candlestick` keeps (the latest) | counted in the scatter's `more` |
| `max-nodes` | 300 | nodes a `graph` draws; edges at twice that | counted in `more` |
| `max-events` | 500 | events a `timeline` lists (the latest) | *N earlier events not shown* |
| `pivot-rows` | 200 | row keys a `pivot` shows | *N more rows* |
| `pivot-columns` | 40 | column keys a `pivot` shows | still counted in the row and grand totals |

Other limits that shape panels: a `table`'s `limit` (rows shown; totals still cover all rows),
`drishti.graph.link-budget` (how long `line` with `source:` and `links` wait for linked entities, default 40 ms) and
`drishti.engine.bind-parallelism` (threads binding panels, one per core by default).

Measured on the sample data (a laptop, server warm, `timings` from the view model): the `var`, `counterparty`,
`desk`, `legal-entity` and `equity` views build in 0.7–0.75 ms each and the trade view, with ten panels, in 1.65 ms,
including fetching the document; the views are 5–19 kB of JSON. A deliberately large pasted document in Studio
preview (100,000 scenario values, 100,000 pivot rows, 20,000 scatter points, 5,000 events and 1,000 nodes, 7.5 MB)
binds all five panels in 12.8 ms; the response is 560 kB because the scatter, timeline and graph stop at their limits.

## 19. Common mistakes and their problem codes

| Mistake | What you see | Fix |
|---|---|---|
| `kind: chart`, `kind: bar` | `DRS-2021 unknown panel kind 'chart'; expected one of kv, table, …, pivot` | use one of the twenty |
| a scatter without `y` | `DRS-2022 'scatter' panel 'rr' needs option 'y'` | add it (each kind's required options are in its table) |
| `rows:` on a `graph` | `DRS-2023 option 'rows' is not valid for 'graph' panels` | a graph takes `nodes` and `edges` |
| `limit:` on a `ladder`, `search:` on a chart | `DRS-2023 option 'limit' is not valid for 'ladder' panels`, `option 'search' applies only to panels that show a table` | remove it, or use a `table` |
| `agg: median` on a pivot | `DRS-2029 option 'agg' of 'pivot' panels must be one of sum, count, avg, min, max, not 'median'` | one of the five |
| `heat: yes please` | `DRS-2029 option 'heat' of 'pivot' panels must be true or false` | `true` or `false` |
| `bins: 0`, `bins: 1000` | `DRS-2029 option 'bins' of 'histogram' panels must be a whole number from 1 to 200` | a number in range, or leave it out |
| a marker without `value`, or with `color:` | `DRS-2029 each histogram marker must be a mapping with a 'value' expression …`, `unknown key 'color' in a histogram marker` | `{ label, value, tone }` |
| `layout: circle` on a graph | `DRS-2029 option 'layout' of 'graph' panels must be one of tree, force` | `tree` or `force` |
| `rows: "$.legs[0"`, a marker `value: "-$.var99 +"` | `DRS-2101 expected ']' …` at load | fix the expression |
| two panels with one id, a key used twice | `DRS-2024`, `DRS-2025` | rename, or pick another key |
| `span: 0`, `span: 13`, `height: 30`, `span: half` | `DRS-2030 span must be a whole number from 1 to 12 (columns of the 12-column grid), not '13'` | a whole number in range |
| `span:` on a `tabs` body | `DRS-2030 'span' sizes a whole panel: a tabs body takes the size of its panel` | put it on the `tabs` panel |
| `label: "@.bucket"` on an `hbar` | bars without labels (no error) | `label: bucket`: hbar fields are bare names |
| `value: "@.mtm"` on a pivot or `x: "@.var"` on a scatter | works (an expression per row), but slower on long lists | `value: mtm` |
| a waterfall whose total rows have no flag | the opening amount is drawn as a move from zero, and every step floats from the wrong level | mark the level rows `total: true` (or name the flag field with `total:`) |
| a histogram marker `value: $.var99` when VaR is stored positive | the line falls in the right tail | `value: "-$.var99"` |
| a pivot `value` that is text | every row left out: an empty panel | point `value` at a number, or drop it to count |
| graph node ids that are not entity ids | nodes are drawn but do not open anything | use the entity ids, or give each node its `kind` |
| a title such as `MTM by book and currency` with a key | the key bar reads *F3 MTM by* | put the subject first: `MTM grid (by book and currency)` |

## 20. Where the code is

| What | Where |
|---|---|
| The kinds and their options | `drishti-rachana/.../model/PanelKind.java`; restricted values in `PanelOptions.java` |
| Parsing and option checks | `drishti-rachana/.../parse/SutraBuilder.java` (`DRS-2021`, `-2022`, `-2023`, `-2029`) |
| Which options are expressions | `drishti-rachana/.../SutraExpressions.java` (`EL_OPTIONS`) |
| The JSON Schema (Studio and editor completion) | `drishti-rachana/.../RachanaSchema.java`, served at `GET /api/v1/rachana/schema` |
| Binding | `drishti-engine/.../bind/Binder.java` (the first thirteen kinds), `ChartBinder.java` (the seven from `waterfall` on), limits in `PanelLimits.java` |
| The view model records and emptiness | `drishti-engine/.../view/PanelData.java`, `Emptiness.java` |
| Macros | `console/web/templates/_macros/panels.html` |
| Drawing | `console/web/static/js/view.js` (`line`, `area`, `surface`, bars, tabs), `charts.js` (`waterfall`, `histogram`, `scatter`, `candlestick`, `graph`), `tables.js` (every table, the pivot included) |
| Styles | `console/web/static/css/terminal.css` |
| CSV export | `console/core/export.py` |
| Tests | `ChartPanelsTest`, `BankingPacksTest`, `ImperfectDataTest`, `SutraParserTest`, `StudioTest`; `console/tests/test_terminal.py`, `test_export.py` |
| The banking Sutras and data that use the new kinds | `tools/packgen/banking/risk_data.py`, `market_data.py`, `make_sutras.py` (Sutras); `data_trades.py`, `data_results.py`, `data_market.py` (data) |

Adding a kind is a recipe of its own: [DEVELOPER_GUIDE.md, 5.4 Add a panel kind](DEVELOPER_GUIDE.md#54-add-a-panel-kind).
