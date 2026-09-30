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
It is written in **Rachana**, Drishti's screen grammar, inside an ordinary Markdown document. It holds no code
and no pixels, so a Sutra is short, reviewable and safe, and one Sutra serves every document that matches it.

This guide goes from a first Sutra to every panel kind, with real examples from the banking packs and the views
they produce. Keep the [Rachana reference](rachana-reference) open for the full list of keys.

![A trade view built by a Sutra](/static/img/guide/view-trade.png)

*`TRD T-10001`: an interest rate swap laid out by the `irs-fixfloat` Sutra. The header strip, the
Terms and Legs panels, the cashflow ladder, the curve on the right and the function keys at the bottom all come
from about sixty lines of Rachana.*

## 1. A first Sutra in five minutes

Open **Studio** (`/studio`), choose *New Sutra…*, set the entity to `trade` / `T-10001`, paste this and press
**Ctrl+Enter**:

```sutra
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

That is a complete Sutra. The header shows four figures (three of them named after their fields, because they
have no `label`), a *Terms* panel lists every field of the trade's `terms` object, and *Linked entities* finds
every book, curve and netting set the trade points to. Everything else on the screen is filled by inference.

## 2. The file: Markdown around one `sutra` block

A Sutra lives in `sutras/<domain>/<name>.v<N>.sutra.md`, inside a pack or a site directory. The file is
Markdown: write what the layout is for and why, for the next person and for AI assistants. The engine reads only
the fenced block marked `sutra`:

````markdown
# Interest rate swap (`irs-fixfloat` v1)

Swaps for the rates desk. The strip leads with MTM and DV01 because that is what traders check first.

```sutra
sutra: irs-fixfloat
version: 1
...
```

## Why these panels
F3 is the cashflow ladder with the next payment highlighted, because operations asked for it.
````

- One `sutra` block per file (problem `DRS-2004` otherwise). Problems report the Markdown file's line numbers.
- `name@version` is unique. Keep old versions: saved views and history reproduce with the version they used.
- Studio edits the whole document; its **Document** tab shows the prose as the help centre renders it.

## 3. Anatomy

```yaml
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

## 7. The thirteen panel kinds

### kv: fields as a grid

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

```yaml
- { id: usage, kind: gauge, title: Limit utilisation, value: $.utilisation, max: 1, fmt: pct0 }
```

![gauge](/static/img/guide/kind-gauge.png)

### markdown: a note

```yaml
- { id: notes, kind: markdown, title: Desk note, text: "Hedges the ${$.counterparty.name} liability book." }
```

![markdown](/static/img/guide/kind-markdown.png)

### links: everything the document points to

Found from the pack's reference fields (`nettingSet`, `book`, `discountCurve`, …), each with a badge read from
the target (`EE 4.1m`, `81% used`). A link you may not open shows as denied.

```yaml
- { id: refs, kind: links, title: Linked entities, code: REFS, area: right }
```

![links](/static/img/guide/kind-links.png)

### provenance: how this view was built

The Sutra and version (or *inference only*), the data fingerprint, the source and its generation.

```yaml
- { id: built, kind: provenance, title: How this view was built }
```

![provenance](/static/img/guide/kind-provenance.png)

## 8. One product family, many Sutras: `match`, `where` and `priority`

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

## 9. Sutra and inference together

A Sutra says what matters; inference fills the rest.

- A `table` or `kv` panel without `columns` gets its columns from the data (the strongest fields first).
- `infer: true` on a panel lets inference add to it.
- The *How this view was built* panel says `Sutra irs-fixfloat v1 + inference` whenever inference helped, and an
  *inferred* tag on a panel says why.
- A document no Sutra matches is still shown: inference lays it out entirely (*inference only*).

## 10. Dates, live data and imperfect data: nothing to write

- **Business dates.** The top bar's date applies to every read. A Sutra never mentions dates: the same layout
  shows today's live data or any past business day's snapshot, and the footer says which.

  ![The business date in the top bar](/static/img/guide/business-date.png)

- **Live.** When the source streams, strip figures and table cells update in place; nothing to declare.
- **Imperfect data.** Missing fields show a dash; a panel with no data says *No data available*; wrong types
  are shown as text. A Sutra never breaks a screen.

## 11. Writing Sutras in Studio

![Studio: the Markdown editor with a live preview](/static/img/guide/studio.png)

1. Start from a Sutra that works, or **Start from inference** on a real entity.
2. Edit; **Ctrl+Enter** previews against the entity, or against JSON you paste in *Sample JSON*.
3. **Insert…** adds any panel kind already shaped; **Jump to…** moves around long documents.
4. Problems are listed with their line; click one to go there.
5. **Submit for review** (authors, where saving is on): an approver approves it and it goes live; or commit the file
   to the pack's `sutras/` directory through version control.

## 12. Checklist

- `match.where` is specific enough, and `priority` is higher than any general Sutra for the kind.
- The strip leads with what the reader checks first, and has at most eight figures.
- Every panel a user reaches often has a `key` (F2–F6); F7 opens the parent entity, F8 impact, F9 raw JSON.
- Money has `fmt: signed0` or `amount0`, and `tone: sign` where the sign matters.
- Labels are left to the taxonomy unless the Sutra needs different words.
- The prose says why the layout is what it is.
- Preview against a thin document and a rich one (Sample JSON) before saving.

**Problem codes** you may meet: `DRS-2001` YAML syntax, `DRS-2004` no or several `sutra` blocks, `DRS-2010`
missing key, `DRS-2011` unknown key, `DRS-2021` unknown panel kind, `DRS-2022`/`2023` panel options, `DRS-2024`
duplicate panel id, `DRS-2025` function key clash, `DRS-2026` strip longer than 8, `DRS-2028` `name@version`
defined twice, `DRS-2101` an expression that does not compile. The [Rachana reference](rachana-reference) lists
them all.
