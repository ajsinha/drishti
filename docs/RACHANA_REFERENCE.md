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
# Rachana reference

**Rachana** (रचना, *composition*) is Drishti's declarative screen grammar. Because of it, no product
ever gets its own coded screen. A **Sutra** (सूत्र, *thread*) is one layout written in Rachana: a
versioned file that describes how to lay out one family of entities. The mockups' *How this
view was built* reads `Sutra irs-vanilla v3 + inference`, meaning the Sutra `irs-vanilla`, version
3, completed by inference.

A Sutra holds no code and no pixels. It is loaded by `SutraRegistry` from `drishti.rachana.dirs` and the
enabled packs, and reloads as soon as it is saved. If an edit is invalid, the last good version is kept
and the problems are reported with line and column.

## File format: Markdown Sutras

The standard format is a **Markdown Sutra**, `sutras/<domain>/<name>.v<N>.sutra.md`: an ordinary
Markdown document that people and AI assistants can read, with exactly one fenced `sutra` block that
holds the layout in Rachana (YAML syntax, with Rachana-EL expressions in string values).

````markdown
# Vanilla interest-rate swap (`irs-vanilla` v3)

Fixed-for-floating swaps. The strip leads with MTM and DV01; F2 shows the legs.

```sutra
sutra: irs-vanilla
version: 3
match: { kind: trade, where: "$.product == 'IRS'" }
panels:
  - { id: legs, kind: kv, title: Legs, key: F2, rows: $.legs }
```

## Why these panels
Traders asked for the legs first …
````

Rules:

- The engine reads only the `sutra` block. Everything else is documentation: headings, prose, tables,
  links, images. Write down *why* the layout is what it is; the help centre and Studio render it.
- Exactly one `sutra` block (DRS-2004 otherwise). Both ```` ``` ```` and `~~~` fences work.
- Problem locations use the line numbers of the Markdown file, so an error at line 14 is at line 14 in
  your editor.
- Plain `*.yaml` Sutras still load (the block's content on its own), for tools that emit YAML.
  `tools/sutra_to_md.py` converts them.
- Editors can validate the block against `drishti-rachana/src/main/resources/sutra.schema.json`, but
  the authoritative checks are the parser's.

## A complete example, annotated

The Sutra below lays out a vanilla fixed/float interest rate swap from the trading pack (`TRD T-10001`). It
uses most of the grammar: matching, the title, the strip, a dozen panels of different kinds, expressions,
templates, links and function keys. Every line is commented. The test suite (`RachanaReferenceExampleTest`)
previews this exact block against `T-10001` and fails if it stops parsing or leaves a panel empty, so it
always works as written.

How to read the notation:

- `$` is the document. `$.counterparty.name` walks into objects, and `$.legs[0].rate` into arrays.
- `@` is the current row inside `rows`, `each` or a column: `@.payDate` is the row's `payDate`.
- A value in `"double quotes"` is a Rachana-EL expression (operators, functions). A bare path such as
  `$.tradeId` needs no quotes.
- `${…}` inside text (titles, pills) embeds an expression in a template.
- `#` starts a YAML comment, which the engine ignores.

The fragment of the document that the Sutra reads looks like this (abridged):

```json
{
  "tradeId": "T-10001", "productType": "IRS_FIXFLOAT", "productName": "Interest rate swap (fixed/float)",
  "assetClass": "Rates", "status": "Live", "direction": "Receive fixed", "currency": "AUD",
  "notional": 242000000, "mtm": 1875863, "pnl1d": 126986, "maturityDate": "2032-06-25",
  "counterparty": { "id": "CP-MERIDIAN-RE", "name": "Meridian Reinsurance Ltd" },
  "book": "BOOK-RATES-3", "nettingSet": "NS-MERIDIAN-RE-NY", "discountCurve": "CRV-AUD-OIS",
  "terms": { "fixedRate": 0.040829, "dayCount": "ACT/365F" },
  "risk": { "dv01": -155245 },
  "sensitivities": [ { "bucket": "3M", "dv01": -5544 }, … ],
  "legs": [ { "leg": 1, "label": "Receive fixed 4.0829%", "type": "FIXED", "payer": false, "rate": 0.040829,
              "cashflows": [ { "n": 1, "payDate": "2026-06-29", "amount": 8987301.85, "df": 1.0, "pv": 0.0, "status": "Settled" }, … ] },
            { "leg": 2, "label": "Pay AONIA compounded", "type": "FLOAT", "index": "AONIA", … } ],
  "schedule": [ { "payDate": "2025-09-29", "leg": 2, "amount": -1403091.13, "status": "Settled" }, … ],
  "pnlHistory": [ { "date": "2026-09-03", "pnl": -15005 }, … ],
  "lifecycle": { "events": [ { "version": 1, "event": "New", "at": "2025-07-25T09:45:00Z", "by": "mo.ops" }, … ] },
  "confirmation": { "status": "Confirmed", "method": "MarkitWire", "matched": "2025-07-25 16:51" },
  "regulatory": { "uti": "5493…", "reportingStatus": "Accepted", "clearingObligation": true },
  "clearing": { "status": "Cleared", "ccp": "LCH SwapClear" }
}
```

````markdown
# Vanilla swap, annotated (`swap-annotated` v1)

Prose around the block is documentation: the help centre and Studio render it, and the engine ignores it.

```sutra
# ---- Identity -----------------------------------------------------------------------------------------
sutra: swap-annotated            # the name: lower-case kebab, 2-64 characters
version: 1                       # name@version is unique; old versions stay loadable, so saved views reproduce
description: A vanilla fixed/float swap, every line explained.
domain: rates                    # grouping in Studio and the catalogue (defaults to the folder the file is in)

# ---- Which documents this layout is for -------------------------------------------------------------------
# kind picks the entity family. where is a Rachana-EL predicate over the document; when several Sutras
# match, the one with the highest priority wins. No match at all: the view is built by inference.
match:
  kind: trade
  where: "$.productType == 'IRS_FIXFLOAT' && size($.legs) == 2 && $.status != 'Matured'"
  priority: 50

# ---- The title line:  [pill]  ID  with  <counterparty> -------------------------------------------------------
title:
  pill: "${$.assetClass} · ${$.productName}"      # a template: text with ${expression} parts
  id: $.tradeId                                   # a path: the big identifier
  with: "link($.counterparty.id, 'counterparty', $.counterparty.name)"   # link(id, kind, text) is navigable

# ---- The strip: up to 8 headline figures under the title ----------------------------------------------------
strip:
  - { bind: $.notional, fmt: amount0 }             # no label: taken from the taxonomy, else the field name ("Notional")
  - { label: Currency, bind: $.currency }
  - { label: Direction, bind: $.direction }
  - { label: Fixed rate, bind: $.terms.fixedRate, fmt: pct4 }                 # 0.040829 shows as 4.0829%
  - { label: Maturity, bind: $.maturityDate, fmt: date }
  - { label: MTM (USD), bind: $.mtm, fmt: signed0, tone: sign, emphasis: true }  # signed, coloured by sign, highlighted
  - { label: 1-day P&L, bind: $.pnl1d, fmt: signed0, tone: sign }
  - { label: Book, bind: "link($.book, 'book')" }  # a link in the strip opens the book

# ---- Panels: the body of the view, in order. area: right puts one in the side column ------------------------
panels:

  # kv: a label/value grid. columns name what to show; bind paths start at $ (the whole document).
  - id: terms
    kind: kv
    title: Terms
    key: F2                                      # F2 jumps here (keys are unique across the Sutra)
    code: TRM                                    # a short tag at the right of the panel header
    columns:
      - { label: Trade date, bind: $.tradeDate, fmt: date }
      - { label: Effective, bind: $.effectiveDate, fmt: date }
      - { label: Day count, bind: $.terms.dayCount }
      - { label: Pay frequency, bind: $.terms.payFrequency }
      - { label: Discount curve, bind: "link($.discountCurve, 'curve')" }   # links work in any cell
      - { label: Net PV of the legs, bind: "sum($.legs, 'pv')", fmt: signed0, tone: sign }   # sum(list, 'field') adds a field up
      - { label: Flows left, bind: "size($.schedule[?@.status != 'Settled']) + ' of ' + size($.schedule)" }  # size() counts

  # tabs: one tab per element of each; inside, @ is that element. layout: columns shows them side by side.
  - id: legs
    kind: tabs
    title: Legs
    key: F3
    each: $.legs
    layout: columns
    tabTitle: "'Leg ' + @.leg + ' · ' + @.label"   # + joins text
    body:                                          # the panel drawn for each element
      kind: kv
      columns:
        - { label: Pay or receive, bind: "@.payer ? 'Pay' : 'Receive'" }               # condition ? then : else
        - { label: Rate, bind: "@.type == 'FIXED' ? fmt(@.rate, 'pct4') : @.index + ' + ' + fmt(@.spread, 'pct4')" }
        - { label: Notional, bind: "@.notional", fmt: amount0 }
        - { label: Frequency, bind: "@.frequency" }
        - { label: Calendar, bind: "@.calendar" }
        - { label: PV, bind: "@.pv", fmt: signed0, tone: sign }

  # table: one row per element of rows (here the first leg's cashflows), with a total row.
  - id: cashflows
    kind: table
    title: "Cashflows · ${$.legs[0].label}"      # templates work in titles too
    key: F4
    rows: $.legs[0].cashflows
    limit: 6                                     # show six rows, then "N more"
    moreLabel: "(size($.legs[0].cashflows) - 6) + ' later cashflows'"
    totalLabel: Total
    columns:
      - { label: "#", bind: "@.n" }
      - { label: Pay date, bind: "@.payDate", fmt: date }
      - { label: Rate, bind: "@.rate", fmt: pct4 }
      - { label: Amount, bind: "@.amount", fmt: signed2, tone: sign, total: true }   # total: summed into the total row
      - { label: DF, bind: "@.df", fmt: df4 }
      - { label: PV, bind: "@.pv", fmt: signed2, tone: sign, total: true }
      - { label: Status, bind: "@.status", tone: status }

  # ladder: a dated list with one highlighted row; highlight is evaluated per row.
  - id: schedule
    kind: ladder
    title: Payment schedule (both legs)
    rows: "$.schedule[?@.status != 'Settled']"   # a filter: [?condition] keeps matching elements
    highlight: "#index == 0"                     # #index is the row's position: highlight the next payment
    columns:
      - { label: Pay date, bind: "@.payDate", fmt: date }
      - { label: Leg, bind: "@.leg == 1 ? 'Fixed' : 'Float'" }
      - { label: Amount, bind: "@.amount", fmt: signed2, tone: sign }

  # status: operational fields, coloured by meaning (Confirmed, Cleared, Accepted…).
  - id: operations
    kind: status
    title: Confirmation, clearing and reporting
    code: OPS
    fields:
      - { label: Confirmation, bind: $.confirmation.status, tone: status }
      - { label: Matched on, bind: "$.confirmation.method + ', ' + $.confirmation.matched" }
      - { label: Clearing, bind: "$.clearing.status + ' at ' + $.clearing.ccp", tone: status }
      - { label: Reporting, bind: $.regulatory.reportingStatus, tone: status }
      - { label: UTI, bind: $.regulatory.uti }
      - { label: Clearing obligation, bind: "$.regulatory.clearingObligation ? 'Yes' : 'No'" }

  # ladder over nested data: the lifecycle events, newest last.
  - id: lifecycle
    kind: ladder
    title: "Lifecycle (${size($.lifecycle.events)} events)"
    rows: $.lifecycle.events
    highlight: "#index == size($.lifecycle.events) - 1"   # the latest event
    columns:
      - { label: Version, bind: "@.version" }
      - { label: Event, bind: "@.event" }
      - { label: When, bind: "@.at" }
      - { label: By, bind: "@.by" }

  # markdown: static notes, written for the people who read this view.
  - id: notes
    kind: markdown
    title: Reading this view
    text: |
      **MTM** and **DV01** are in USD whatever the trade currency. The *Payment schedule* lists
      unsettled flows of both legs; the highlighted row is the next payment.

  # provenance: how the view was built (Sutra and version, source, generation, business date).
  - { id: built, kind: provenance, title: How this view was built }

  # ---- The side column -----------------------------------------------------------------------------------

  # line: a curve from this document's own data. x and y name fields of each row.
  - id: pnl
    kind: line
    title: Daily P&L (last 20 business days)
    area: right
    rows: $.pnlHistory
    x: date
    y: pnl
    fmt: signed0

  # line from ANOTHER entity: source reads the discount curve's own document and plots its points.
  - id: curve
    kind: line
    title: Discount curve
    code: CRV
    key: F5
    area: right
    source: "link($.discountCurve, 'ir-curve')"
    rows: $.points                               # with a source, $ is the curve's document, not the trade
    x: tenor
    y: zeroRate
    fmt: price2
    unit: "%"

  # hbar: horizontal bars, one per row: label and value name fields of each row.
  - id: dv01
    kind: hbar
    title: DV01 by bucket (USD)
    code: SENS
    area: right
    rows: $.sensitivities
    label: bucket
    value: dv01
    fmt: signed0
    tone: sign

  # gauge: one value against a maximum (a limit, a budget).
  - id: dv01use
    kind: gauge
    title: DV01 against the 250k desk guideline
    area: right
    value: "abs($.risk.dv01)"
    max: "250000"
    label: DV01 used
    fmt: compact

  # links: every entity this document refers to, resolved from the reference catalogue, with badges.
  - { id: refs, kind: links, title: Linked entities, code: REFS, area: right }

# ---- Function keys beyond the panel keys ------------------------------------------------------------------
keys:
  F7: "link($.nettingSet, 'netting-set')"        # F7 opens the netting set
  F8: impact                                     # F8: what depends on this trade
  F9: raw                                        # F9: the raw JSON (redacted for roles without raw)
```

## Why these panels

The strip leads with what a trader checks first (MTM, P&L); settlement detail sits lower, and the risk
charts go in the side column.
````

What each part does when the view is built:

| Part | What happens |
|---|---|
| `match` | The engine takes the `trade` Sutras, highest `priority` first, and uses the first whose `where` holds for `T-10001`. |
| `title`, `strip` | Evaluated once per document: each expression gives a value, which `fmt` formats and `tone` colours. |
| `rows`, `each` | Evaluated to a list; the panel's columns are evaluated once per element with `@` set to it. |
| `source` | Follows the link and reads the other entity (the curve) through the same sources and business date. |
| `key`, `keys` | Become the function-key bar; a panel key scrolls to the panel, a link key opens the entity. |
| Missing data | A path that is absent evaluates to nothing: the cell is empty, and a panel with no data says so. |

## Labels

Every strip item and column may give a `label`. When it does not, the label is the name of the field it reads
(the last segment of the path): `bind: $.regulatory.uti` reads as **UTI**, `@.payDate` as **Pay date**. Where a
label comes from, first match wins:

1. `label:` in the Sutra;
2. the **pack taxonomy**: `labels:` in the semantics file of an enabled pack (`packs/<pack>/config/semantics.yaml`),
   e.g. `labels: { mtm: MTM (USD), tradeId: Trade }`;
3. the **global taxonomy**: `labels:` in the core semantics (or the site's replacement,
   `drishti.inference.semantics-file`);
4. the field name in words, spelled with the packs' and the core's `acronyms:` (`dv01ByTenor` → *DV01 by tenor*).

Inference labels the fields it lays out the same way.

## Top level

| Key | Required | Meaning |
|---|---|---|
| `sutra` | yes | Name: lower-case kebab, 2–64 characters (`irs-vanilla`). |
| `version` | yes | A positive integer. `name@version` is unique across all directories (`DRS-2028`). Old versions stay loadable, so a saved view reproduces. |
| `description` | | Free text. |
| `domain` | | Grouping; defaults to the parent folder (`rates`). |
| `match` | yes | `{kind, where?, priority?}`. The Sutra applies to entities of `kind` for which the Rachana-EL predicate `where` is true. The highest `priority` wins. |
| `title` | | `{pill, id, with}`: the title line `[pill] ID with <counterparty>`. `id` and `with` are expressions. |
| `strip` | | Up to **8** header figures: `{label?, bind, fmt?, tone?, emphasis?}`. |
| `panels` | | The panels, in order. See below. |
| `keys` | | Function key → action: a panel id, `link(expr, kind)`, `impact` or `raw`. |

## Panels

Common keys: `id` (unique), `kind`, `title` (may embed `${expr}`), `key` (`F1`–`F12`, unique across
panels and `keys`), `code` (short tag at the header's right, e.g. `CRV`), `area` (`main` | `right`),
`infer` (let inference fill what is not stated), `columns` (list of
`{label?, bind, fmt?, tone?, total?, link?}`), and `body` (tabs only).

| Kind | Required | Optional | Renders |
|---|---|---|---|
| `kv` | — | `rows`, `columns`, `fields` | Label/value grid. With `rows: $.obj` and no columns, every field of the object is shown. |
| `table` | `rows` | `totalLabel`, `limit`, `moreLabel`, `link` | A table. Columns with `total: true` are summed into the total row. `limit` with `moreLabel` shows "N more". |
| `tabs` | `each` | `tabTitle`, `layout` (`tabs` \| `columns`) | One `body` panel per element of `each`, as tabs or side by side (FX near/far legs). |
| `line` | — | `rows`, `source`, `x`, `y`, `mark`, `footer`, `unit`, `fmt` | A curve. `source: link(...)` reads the points from a linked entity; `mark` highlights one x. |
| `area` | `rows` | `x`, `series`, `limit`, `limitLabel`, `unit` | Stacked or banded areas (exposure profile) with a dashed limit line. |
| `hbar` | `rows` | `label`, `value`, `fmt`, `tone` | Horizontal bars (DV01 by tenor, risk). |
| `ladder` | `rows` | `totalLabel`, `highlight` | A dated table with a highlighted row (the intraday settlement). |
| `links` | — | — | Linked entities, resolved from the reference catalogue. |
| `status` | — | `fields` | Operational status fields (confirmation, settlement). |
| `provenance` | — | — | *How this view was built*: Sutra and version, fingerprint, source and generation. |
| `markdown` | `text` | — | Static notes. |
| `gauge` | `value` | `max`, `label`, `fmt` | Utilisation against a limit. |
| `surface` | `rows`, `y` | `fmt`, `unit`, `view` (`heatmap` \| `3d`) | A grid over two axes: each row is a `y` point (expiry), each column an `x` point (strike, delta, tenor) holding the value. A heatmap, with a toggle to a rotatable 3D surface (volatility surfaces, swaption cubes). |

## Formats and tones

`fmt` names a format from the bundled `formats.yaml` (in `drishti-rachana`); a site can override or add formats with `drishti.rachana.formats-file`: `amount0`, `amount2`, `signed0`,
`signed2`, `pct0`, `pct4`, `rate5`, `pips1`, `price2`, `df4`, `date`, `compact` (4.1m).

`tone` colours through theme tokens only, and always together with a sign or glyph:
- `sign`: positive values in the link colour, negative in the negative colour;
- `pos` and `neg`: fixed colours;
- `status`, `link`, `accent`: named token colours.

## Expressions (Rachana-EL)

Bindings are Rachana-EL expressions. It is closed and side-effect free:

| Element | Syntax |
|---|---|
| Document and row references | `$` is the document; `@` is the current row or element; `#index` is its position. |
| Paths | `$.legs[0].rate`, `@.amount`, `$.legs[-1]` |
| Filters | `$.legs[?@.payer]` |
| Operators | `+ - * / %`, `== != < <= > >=`, `&& || !`, `a ? b : c`; `+` also concatenates text. |
| Literals | `'text'`, numbers, `true`, `false`, `null` |
| Functions | `link(id, kind?, label?)`, `size(x)`, `sum(rows, 'field')`, `fmt(x, 'format')`, `coalesce(a, b)`, `first(x)`, `last(x)`, `abs`, `min`, `max`, `upper`, `lower`, `contains(text or list, part)`, `startsWith(text, prefix)` (both case-insensitive) |

### Grammar

```ebnf
expr     = or [ "?" expr ":" expr ] ;
or       = and { "||" and } ;
and      = equality { "&&" equality } ;
equality = compare { ("==" | "!=") compare } ;
compare  = sum { ("<" | "<=" | ">" | ">=") sum } ;
sum      = product { ("+" | "-") product } ;
product  = unary { ("*" | "/" | "%") unary } ;
unary    = ("!" | "-") unary | postfix ;
postfix  = primary { "." ident | "[" expr "]" | "[?" expr "]" } ;
primary  = number | string | "true" | "false" | "null" | "$" | "@" | "#index"
         | ident "(" [ expr { "," expr } ] ")" | ident | "(" expr ")" ;
```

### Evaluation rules

- **Missing never throws.** A missing path evaluates to null. Null is falsy and formats as empty text.
- **Bare identifiers.** A bare identifier (`amount`) is a field of the current row when there is one, otherwise of the document.
- **`+` and text.** `+` adds two numbers; if either side is not a number, it concatenates text.
- **Division.** Dividing by zero gives null.
- **Whole numbers.** A whole-number result prints without decimals (`1 + 1` → `2`).
- **Equality.** `==` compares numbers numerically and everything else as text.
- **`link(id, kind?, label?)`** returns a link value, which the engine resolves into a navigable entity.
- **Compiled once.** Expressions are compiled once, cached by source text (`drishti.rachana.expression-cache-size`), and shared across threads.
- **Checked at load.** Every expression in a Sutra is compiled when the file loads, so a typo is reported against the file (`DRS-2101`), not at view time.
- **Dependency paths.** Each compiled expression reports the document paths it reads. It is available for path-targeted re-binding; live updates currently rebuild with the cached layout and diff (see LIVE.md).

### Choosing a Sutra

`SutraMatcher` takes the Sutras for the entity's kind, highest `priority` first, and picks the first
one whose `where` holds for the document. If none matches, the view is built by inference alone.
There is no separate classifier file: `match` is the classifier.

## Problem codes

| Code | Meaning |
|---|---|
| DRS-2001 | YAML syntax error |
| DRS-2004 | a Markdown Sutra has no ```sutra block, more than one, or an unclosed one |
| DRS-2010 | missing required key |
| DRS-2011 | unknown key |
| DRS-2012 | wrong type |
| DRS-2020 | bad name or version |
| DRS-2021 | unknown panel kind |
| DRS-2022 | a required option is missing for the kind |
| DRS-2023 | option not valid for the kind |
| DRS-2024 | duplicate panel id |
| DRS-2025 | duplicate or invalid function key |
| DRS-2026 | strip longer than 8 |
| DRS-2027 | bad area |
| DRS-2028 | `name@version` defined twice |
| DRS-2101 | expression or template does not compile |

## The reference Sutras (finance pack)

| File | Entity | Mockup |
|---|---|---|
| `packs/finance/sutras/rates/irs-vanilla.v3.sutra.md` | `TRD IRS-48213` | drishti-irs.png |
| `packs/finance/sutras/fx/fx-swap.v2.sutra.md` | `TRD FXS-20931` | drishti-fx-swap.png |
| `packs/finance/sutras/commodities/listed-future.v1.sutra.md` | `TRD CFT-77120` | drishti-commodity-future.png |
| `packs/finance/sutras/credit/netting-set.v1.sutra.md` | `NSET NS-NORTH-01` | drishti-netting-set.png |
