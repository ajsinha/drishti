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
# Rachana guide: writing Sutras, from your first panel to a full trade view

This guide teaches Rachana, Drishti's layout grammar, by doing. You start with a gene from the genomics pack,
write a Sutra one step at a time, preview each step, and finish with a complete trade view, a list of the
mistakes everyone makes once, and a complete example of every panel kind. Every example on this page parses and
validates as written, and every figure was read from the running sample server.

The [Rachana reference](RACHANA_REFERENCE.md) is the companion to this guide: when you want every option of a
panel kind, every function or every error code, look there.

**What you need**

- the server on `http://localhost:18480` and the console on `http://localhost:17480` (see
  [GETTING_STARTED.md](GETTING_STARTED.md)), started with the banking packs and the genomics pack, for example
  `DRISHTI_PACKS=market-risk,counterparty-risk,liquidity-risk,climate-risk,operational-risk,retail-banking,genomics,politics-society,economics`
  (a pack's parents load with it, so this brings `banking-core`, `market-data` and `trading` too);
- `curl` and `python3` for the read-only checks;
- a browser for Studio.

The `curl` commands assume security is off. With security on, add `-H "Authorization: Bearer <token>"`.

**Contents**

1. [What a Sutra is, and when one applies](#1-what-a-sutra-is-and-when-one-applies)
2. [Your first Sutra: a gene](#2-your-first-sutra-a-gene)
3. [Rachana-EL step by step](#3-rachana-el-step-by-step)
4. [A trade view, built step by step](#4-a-trade-view-built-step-by-step)
5. [Matching several Sutras](#5-matching-several-sutras)
6. [Saving: Studio and governance](#6-saving-studio-and-governance)
7. [How packs ship Sutras](#7-how-packs-ship-sutras)
8. [Things to know before you write many Sutras](#8-things-to-know-before-you-write-many-sutras)
9. [Common mistakes and their messages](#9-common-mistakes-and-their-messages)
10. [Every panel kind, by example](#10-every-panel-kind-by-example): a complete Sutra for each of the twenty kinds on a
    sample entity, the layout keys, and all twenty together on real screens

## 1. What a Sutra is, and when one applies

A **Sutra** is one YAML file, `<name>.v<N>.sutra.yaml`, that holds one layout: which panels to draw, in which
order, bound to which parts of a JSON document. Its first key is always `rachana: 1`, the version of the Rachana
language it is written in. It says nothing about pixels or code. One Sutra lays out a whole *family* of entities,
for example every gene, or every fixed/float swap.

Because a Sutra is ordinary YAML, ordinary YAML tools work on it: any editor that reads JSON Schema can complete and
check it against the schema the server publishes (`GET /api/v1/rachana/schema`, see
[Editing outside Studio](#editing-outside-studio)), and linters and diff tools need nothing special.

When you open an entity (`GENE GENE-BRCA1 <GO>` in the terminal), Drishti:

1. fetches its document;
2. looks for a Sutra whose `match.kind` is the entity's kind and whose `where` condition holds, highest
   `priority` first;
3. if it finds one, lays out the view by it, and lets **inference** fill only what it leaves out; if it finds
   none, lays out the whole view by inference (from the shape of the data; see [INFERENCE.md](../architecture/INFERENCE.md)).

The bottom panel of every view, *How this view was built*, tells you which case applied. So does the API:

```bash
curl -s http://localhost:18480/api/v1/views/gene/GENE-BRCA1 | python3 -c "import json,sys; print(json.load(sys.stdin)['provenance']['layout'])"
```

You should see `Sutra gene v1 + inference`: the genomics pack's Sutra `gene`, version 1, completed by inference
(its *Identifiers* panel lists no fields, so inference supplies them, and its *Linked entities* are always found
at view time).

You write a Sutra when inference is not good enough: when the identifier, the headline figures, the formats or
the choice of chart should be what a person who knows the domain would choose.

## 2. Your first Sutra: a gene

### Step 1: look at the data

Always start from the document itself:

```bash
curl -s http://localhost:18480/api/v1/entities/gene/GENE-BRCA1/raw | python3 -m json.tool
```

You should see the provenance and, under `data` (expression shortened):

```json
{
  "geneId": "GENE-BRCA1",
  "symbol": "BRCA1",
  "name": "BRCA1 DNA repair associated",
  "location": "chr17:43,044,295-43,125,483",
  "chromosome": "17",
  "start": 43044295,
  "end": 43125483,
  "length": 81189,
  "strand": "-",
  "transcripts": 38,
  "expression": [
    { "tissue": "Brain", "tpm": 14.34 },
    { "tissue": "Lung", "tpm": 2.64 },
    …
    { "tissue": "Pancreas", "tpm": 15.39 }
  ],
  "variants": [
    { "id": "VRNT-BRCA1-185DELAG", "protein": "p.Glu23fs", "significance": "Pathogenic" }
  ],
  "identifiers": { "assembly": "GRCh38.p14", "hgncSymbol": "BRCA1", "uniprot": "P38398", "biotype": "protein coding" },
  "protein": "PROT-P38398",
  "pathway": "PWY-HR"
}
```

Read it the way a Sutra will: `$` is this whole document, `$.symbol` is `BRCA1`, `$.identifiers.uniprot` is
`P38398`, `$.expression` is a list of eight objects, and `$.expression[0].tissue` is `Brain`.

### Step 2: the smallest Sutra that works

A Sutra needs four things: the language version (`rachana: 1`), a name (`sutra:`), a version, and a `match`
with a `kind`. Here is the smallest useful one, with a single panel:

```yaml
rachana: 1
sutra: gene-mine
version: 1
match: { kind: gene, priority: 20 }
panels:
  - id: basics
    kind: kv
    title: Basics
    columns:
      - { label: Symbol, bind: $.symbol }
      - { label: Location, bind: $.location }
```

- `rachana: 1` comes first in every Sutra. It names the version of the language, so that a later version of
  Rachana can change the grammar without misreading today's files. Leave it out and you get
  `DRS-2009 missing 'rachana: 1' (the Rachana language version) at the top`.
- `sutra: gene-mine` is the name: lower-case letters, digits and hyphens, starting with a letter.
- `version: 1` is a plain integer. Later you publish `version: 2` and keep version 1 on disk.
- `match: { kind: gene, priority: 20 }` applies it to every gene, ahead of the pack's `gene` Sutra (priority 10).
- `kind: kv` is a label/value grid; each column's `bind` says where its value comes from.

### Step 3: preview it in Studio

1. Open `http://localhost:17480/studio?kind=gene&id=GENE-BRCA1`. The two boxes in the top bar hold the entity to
   preview against: kind `gene`, id `GENE-BRCA1`.
2. Replace the editor's text with the YAML above.
3. Press **Ctrl+Enter** (or **Preview**).

You should see, in the *Preview* tab, a view titled `GENE-BRCA1` with one panel, *Basics*, showing
`Symbol BRCA1` and `Location chr17:43,044,295-43,125,483`. Nothing has been saved: a preview always uses the text in
the editor, whatever its `match` says, and touches no file.

If the text has a problem, the status line says `DRS-2002: 1 problem(s) below` and the list under it gives each
problem with its code, line and reason, for example `DRS-2010 line 7: missing 'kind'`; click one to jump to the
line. The [mistakes section](#9-common-mistakes-and-their-messages) lists the usual ones.

### Step 4: a title

The title line is `[pill] ID with <something>`. Without a `title:`, the identifier is `$.id` and, when the document
has no `id`, the entity's own id. Make it explicit:

```yaml
title: { pill: "Gene · ${$.symbol}", id: $.geneId }
```

`pill` is a **template**: text in which `${…}` is replaced by the value of an expression. You should see the
title `[Gene · BRCA1] GENE-BRCA1`.

### Step 4b: say what it is for: `description` and `notes`

A Sutra carries its own explanation in two optional plain-text keys, so the file is the whole story and nothing
else needs to be kept in step with it:

```yaml
description: Genes for the research desk.
notes: |
  Where the gene is, where it is expressed, and its known variants.
  Priority 20 puts it ahead of the genomics pack's own gene Sutra (priority 10).
```

- `description` is one paragraph: what the layout shows and for which entities. Studio and the catalogue show it.
- `notes` is longer text for the next author and for reviewers: why the panels are in this order, what was
  tried and dropped. Write it as a YAML block (`notes: |` and the lines indented under it); the line breaks are
  kept. It is plain text, not Markdown.
- A panel can have its own `description` too (`- { id: basics, kind: kv, description: The gene at a glance, … }`)
  to say what that one panel is for.

None of these change the view. They must be text: `notes: 3` is `DRS-2012 'notes' is plain text`.

### Step 5: a strip of headline figures

The strip is the row of up to eight figures under the title:

```yaml
strip:
  - { label: Symbol, bind: $.symbol, emphasis: true }
  - { label: Location, bind: $.location }
  - { label: Length (bp), bind: $.length, fmt: amount0 }
  - { label: Strand, bind: "$.strand == '-' ? 'Reverse' : 'Forward'" }
  - { label: Transcripts, bind: $.transcripts }
```

You should see `Symbol BRCA1` (highlighted), `Location chr17:43,044,295-43,125,483`, `Length (bp) 81,189`,
`Strand Reverse` and `Transcripts 38`.

Three things just happened:

- `fmt: amount0` formatted `81189` with a thousands separator. Formats are named; the core set is `amount0`,
  `amount2`, `signed0`, `signed2`, `pct0`, `pct2`, `price2`, `compact`, `date`, `dmy` and `text`, and the packs add
  more (`pct4`, `bp1`, …).
- `"$.strand == '-' ? 'Reverse' : 'Forward'"` is a Rachana-EL expression: *if the strand is `-`, show `Reverse`,
  otherwise `Forward`*. It is in double quotes for YAML; the text inside the expression uses single quotes.
- `emphasis: true` highlights one figure. Use it once.

### Step 6: key/value panels, written and inferred

Replace *Basics* with two kv panels:

```yaml
  - id: position
    kind: kv
    title: Position on GRCh38
    key: F2
    columns:
      - { label: Chromosome, bind: $.chromosome }
      - { label: Start, bind: $.start, fmt: amount0 }
      - { label: End, bind: $.end, fmt: amount0 }
      - { label: Span, bind: "$.end - $.start + 1", fmt: amount0 }
  - id: identifiers
    kind: kv
    title: Identifiers
    area: right
    rows: $.identifiers
```

You should see *Position on GRCh38* with `Chromosome 17`, `Start 43,044,295`, `End 43,125,483` and
`Span 81,189` (arithmetic works on numbers), and on the right *Identifiers* with `Assembly GRCh38.p14`,
`Hgnc symbol BRCA1`, `Uniprot P38398`, `Biotype protein coding`.

The second panel lists no columns. `rows: $.identifiers` points it at an object, and inference fills in one field
per scalar in that object, labelled from the field names. That is quick, and it picks up new fields when the
source adds them; list columns when you want to choose the labels and the order (*HGNC symbol*, not *Hgnc symbol*).

`key: F2` binds the F2 key to the panel: pressing it scrolls there. `area: right` puts the panel in the side column.
Each column is a grid of 12 columns: `span: 6` would make a panel half as wide, so two such panels share a row, and
`height: 8` would give it a fixed height of 8 rows (it scrolls inside). Both are optional; users may also arrange a view
for themselves in layout mode (`Alt+L`), which never changes the Sutra ([reference](RACHANA_REFERENCE.md#keys-every-panel-takes)).

### Step 7: a table over a nested array, with links

```yaml
  - id: variants
    kind: table
    title: "Known variants (${size($.variants)})"
    key: F3
    rows: $.variants
    columns:
      - { label: Variant, bind: "@.id", link: true }
      - { label: Protein change, bind: "@.protein" }
      - { label: Significance, bind: "@.significance" }
```

Inside a table, `@` is the current row: `@.protein` is the row's `protein`. You should see the title
`Known variants (1)` and one row, `VRNT-BRCA1-185DELAG · p.Glu23fs · Pathogenic`, with the variant id as a link
that opens `VRNT VRNT-BRCA1-185DELAG`.

`link: true` made the id a link: the genomics pack declares that ids starting with `VRNT-` are variants. A column
whose field name ends in `Id`, `Ref` or `_id` (`@.variantId`) would be linked without it; `@.id` is too short a
name for that rule, so it needs `link: true`.

A table can also offer its rows as an interactive pivot. Add one line, `pivot: true`, and the panel gets a
**Table | Pivot** switch: under **Pivot**, a reader drags the table's columns into rows, columns, values and filters,
with totals, a chart and a drill-down to the rows under any cell
([USER_GUIDE.md](USER_GUIDE.md#the-pivot-tab-slice-a-table-your-way)). To choose the fields and how it opens:

```yaml
    pivot:
      fields: [significance, protein, id]      # row paths; they need not be columns
      rows: [significance]
      values: [{ field: id, agg: count }]
```

Without `pivot:` there is no switch: a Sutra decides where a pivot helps
([reference](RACHANA_REFERENCE.md#pivot-a-pivot-tab-on-a-table-or-ladder)).

### Step 8: a chart

Expression by tissue is a label and a number per row, which is what horizontal bars are for:

```yaml
  - id: expression
    kind: hbar
    title: Tissue expression (TPM)
    key: F4
    rows: $.expression
    label: tissue
    value: tpm
    fmt: price2
```

For an `hbar`, `label` and `value` are **field names** of each row (not expressions, so no `@.`). You should see
eight bars, the longest `Colon 45.92`, the shortest `Blood 1.23`.

The key bar shows `F4 Tissue expression`: a panel key is labelled with the first two words of the title, cut at
` (` or ` · `. Write titles whose first words make sense on their own (*Expression by tissue* would read
*Expression by*).

### Step 9: linked entities, provenance and keys

```yaml
  - { id: refs, kind: links, title: Linked entities, code: REFS, area: right }
  - { id: built, kind: provenance, title: How this view was built }
keys: { F7: "link($.protein, 'protein')", F8: impact, F9: raw }
```

- `links` lists every entity the document refers to, found from the packs' reference fields: you should see
  `Protein PROT-P38398` and `Pathway PWY-HR`, both links.
- `provenance` says how the view was built.
- `keys` adds function keys that are not panels: `F7` opens the protein (`link(id, kind)`), `F8` the impact view,
  `F9` the raw JSON. Panel keys and these keys share F1–F12; F1 is the console's help.

### The whole gene Sutra

```yaml
rachana: 1
sutra: gene-mine
version: 1
description: Genes for the research desk.
match: { kind: gene, priority: 20 }
title: { pill: "Gene · ${$.symbol}", id: $.geneId }
strip:
  - { label: Symbol, bind: $.symbol, emphasis: true }
  - { label: Location, bind: $.location }
  - { label: Length (bp), bind: $.length, fmt: amount0 }
  - { label: Strand, bind: "$.strand == '-' ? 'Reverse' : 'Forward'" }
  - { label: Transcripts, bind: $.transcripts }
  - { label: Expressed in, bind: "size($.expression[?@.tpm > 10]) + ' of ' + size($.expression) + ' tissues'" }
panels:
  - id: position
    kind: kv
    title: Position on GRCh38
    key: F2
    columns:
      - { label: Chromosome, bind: $.chromosome }
      - { label: Start, bind: $.start, fmt: amount0 }
      - { label: End, bind: $.end, fmt: amount0 }
      - { label: Span, bind: "$.end - $.start + 1", fmt: amount0 }
  - id: variants
    kind: table
    title: "Known variants (${size($.variants)})"
    key: F3
    rows: $.variants
    columns:
      - { label: Variant, bind: "@.id", link: true }
      - { label: Protein change, bind: "@.protein" }
      - { label: Significance, bind: "@.significance" }
  - { id: built, kind: provenance, title: How this view was built }
  - id: expression
    kind: hbar
    title: Tissue expression (TPM)
    key: F4
    area: right
    rows: $.expression
    label: tissue
    value: tpm
    fmt: price2
  - { id: refs, kind: links, title: Linked entities, code: REFS, area: right }
  - id: identifiers
    kind: kv
    title: Identifiers
    area: right
    rows: $.identifiers
keys: { F7: "link($.protein, 'protein')", F8: impact, F9: raw }
notes: |
  Where the gene is, where it is expressed, and its known variants.
  Priority 20 puts it ahead of the genomics pack's own gene Sutra (priority 10).
```

You should see the strip end with `Expressed in 5 of 8 tissues` (Brain, Liver, Colon, Breast and Pancreas are
above 10 TPM), and the key bar `F2 Position on`, `F3 Known variants`, `F4 Tissue expression`, `F7 Protein`,
`F8 Impact`, `F9 Raw JSON`. Preview it against `GENE-TP53` too (change the id box): the same layout serves every
gene, and you should see `Known variants (1)` with `VRNT-TP53-R175H`.

## 3. Rachana-EL step by step

Every `bind`, `rows`, `where` and `highlight` is a Rachana-EL expression. This section builds them up with
before/after pairs against the swap `MX-20000001` (look at it with
`curl -s http://localhost:18480/api/v1/entities/trade/MX-20000001/raw | python3 -m json.tool`).

### Paths

| You write | You get | Why |
|---|---|---|
| `$.notional` | `242000000` | a field of the document |
| `$.counterparty.name` | `Meridian Reinsurance Ltd` | `.` walks into objects |
| `$.legs[0].label` | `Receive fixed 4.0829%` | `[0]` is the first element |
| `$.legs[-1].label` | `Pay AONIA compounded` | `[-1]` is the last |
| `$.notionl` | *(empty)* | a misspelt field is empty, never an error: check spelling in the preview |
| `$['day-count']` | | a field whose name has a hyphen needs brackets; `$.day-count` would subtract |

### Rows: `@` and `#index`

Inside `rows:`, `each:` or a filter, `@` is the current element and `#index` its position (from 0).

| Before | After |
|---|---|
| `bind: $.schedule[0].amount` — always the first row | `bind: "@.amount"` — each row's own amount |
| `highlight: "#index == 0"` — the first row lit | `highlight: "#index == $.nextIndex"` — the row the document says is next |
| `tabTitle: "'Leg ' + #index"` — `Leg 0`, `Leg 1` | `tabTitle: "'Leg ' + (#index + 1)"` — `Leg 1`, `Leg 2` |

### Filters

`[?condition]` keeps the elements of a list for which the condition holds, with `@` set to each.

| Expression | Result for `MX-20000001` |
|---|---|
| `size($.schedule)` | `35` |
| `size($.schedule[?@.status != 'Settled'])` | `29` |
| `size($.schedule[?@.leg == 1])` | the fixed leg's flows |
| `first($.legs[?@.payer]).label` | `Pay AONIA compounded` |

A filter gives a list, so `$.legs[?@.payer].label` is empty; take an element first (`first(...)`, `[0]`).

### Functions

| Before | After | Shows |
|---|---|---|
| `bind: $.notional, fmt: amount0` | `bind: "$.currency + ' ' + fmt($.notional, 'amount0')"` | `AUD 242,000,000` |
| `bind: $.risk.dv01` | `bind: "abs($.risk.dv01)", fmt: amount0` | `155,245` |
| `bind: "$.terms.spread"` (missing: empty) | `bind: "coalesce($.terms.spread, 0)", fmt: bp1` | `0.0 bp` |
| `bind: "$.legs[0].pv + $.legs[1].pv", fmt: signed0` | `bind: "sum($.legs, 'pv')", fmt: signed0` | `+1,875,863` |
| `where: "$.status == 'live'"` (never true: case matters) | `where: "lower($.status) == 'live'"` | matches `Live` |
| `where: "$.productName == 'swap'"` | `where: "contains($.productName, 'swap')"` | `contains` and `startsWith` ignore case |

The whole set is `link`, `size`, `sum`, `fmt`, `coalesce`, `first`, `last`, `abs`, `min`, `max`, `upper`, `lower`,
`contains` and `startsWith`; there is no other function. See the
[reference](RACHANA_REFERENCE.md#functions) for each.

### Formats and tones

`fmt:` decides how a value reads; `tone:` decides its colour.

| Value | `fmt` | `tone` | Shows |
|---|---|---|---|
| `1875863` | none | none | `1875863` |
| `1875863` | `amount0` | none | `1,875,863` |
| `1875863` | `signed0` | `sign` | `+1,875,863`, positive colour |
| `-155245` | `signed0` | `sign` | `−155,245`, negative colour (a true minus sign) |
| `0.040829` | `pct4` | | `4.0829%` |
| `0.83` | `pct0` | | `83%` |
| `46000000` | `compact` | | `46.0m` |
| `Confirmed` | | `status` | `Confirmed`, `ok` colour |
| `Pending` | | `status` | `Pending`, `warn` colour |

`tone: status` reads the words: *fail*, *reject*, *dispute*, *breach* are bad; *pending*, *unmatched*, *warn* are a
warning; anything else is fine. So it suits confirmation and settlement statuses, but not every status: a variant's
`Pathogenic` would show in the *fine* colour. Leave such fields untoned.

### Conditions

Conditions combine with `&&`, `||` and `!`, and choose with `? :`:

```yaml
where: "$.productType == 'IRS_FIXFLOAT' && $.status != 'Matured'"
bind: "@.payer ? 'Pay' : 'Receive'"
bind: "@.type == 'FIXED' ? fmt(@.rate, 'pct4') : @.index + ' + ' + fmt(@.spread, 'pct4')"
```

Two traps:

- `<`, `>`, `<=`, `>=` compare **numbers** only. `@.payDate > '2026-09-30'` is always false, because text that is not
  a number cannot be ordered. Compare dates with `==`, or use a field the data provides (`$.nextIndex`, a status).
- `||` gives `true` or `false`, not a value: `$.nick || 'n/a'` shows `true`. For a default, use `coalesce($.nick, 'n/a')`.

## 4. A trade view, built step by step

Now a bigger layout: the swap `MX-20000001`. Open Studio on it
(`http://localhost:17480/studio?kind=trade&id=MX-20000001`) and grow the Sutra one block at a time, previewing each.

### 4.1 Identity, title and strip

```yaml
rachana: 1
sutra: swap-desk
version: 1
description: Fixed/float swaps for the rates desk.
match:
  kind: trade
  where: "$.productType == 'IRS_FIXFLOAT'"
  priority: 20
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
  - { label: Status, bind: $.status, tone: status }
  - { label: Book, bind: "link($.book, 'book')" }
panels: []
```

You should see `[Rates · Interest rate swap (fixed/float)] MX-20000001 with Meridian Reinsurance Ltd` and the strip
`Notional AUD 242,000,000 · Direction Receive fixed · Maturity 2032-06-25 · MTM (USD) +1,875,863 · 1-day P&L +126,986 ·
DV01 (USD) −155,245 · Status Live · Book BOOK-RATES-3`. `with` and `Book` are links. `panels: []` is valid: a Sutra
may have no panels.

### 4.2 Terms, and legs as tabs

Replace `panels: []` with:

```yaml
panels:
  - id: terms
    kind: kv
    title: Terms
    code: TRM
    key: F2
    columns:
      - { label: Fixed rate, bind: $.terms.fixedRate, fmt: pct4 }
      - { label: Pay frequency, bind: $.terms.payFrequency }
      - { label: Day count, bind: $.terms.dayCount }
      - { label: Effective, bind: $.effectiveDate, fmt: date }
      - { label: Discount curve, bind: "link($.discountCurve, 'ir-curve')" }
  - id: legs
    kind: tabs
    title: Legs
    key: F3
    each: $.legs
    layout: columns
    tabTitle: "'Leg ' + @.leg + ' · ' + @.label"
    body:
      kind: kv
      columns:
        - { label: Pay or receive, bind: "@.payer ? 'Pay' : 'Receive'" }
        - { label: Rate, bind: "@.type == 'FIXED' ? fmt(@.rate, 'pct4') : @.index + ' + ' + fmt(@.spread, 'pct4')" }
        - { label: Day count, bind: "@.dayCount" }
        - { label: PV, bind: "@.pv", fmt: signed0, tone: sign }
```

You should see *Terms* (`Fixed rate 4.0829%`, `Day count ACT/365F`, the curve as a link) and two boxes side by side,
`Leg 1 · Receive fixed 4.0829%` (`Rate 4.0829%`, `PV +52,387,405`) and `Leg 2 · Pay AONIA compounded`
(`Rate AONIA + 0.0000%`, `PV −50,511,542`). With `layout: tabs` (the default) they would be tabs instead. The
`body` is a panel without an `id`; only its columns are used, and it must be a `kv`.

### 4.3 Cashflows with totals and "more"

```yaml
  - id: cashflows
    kind: table
    title: "Cashflows · ${$.legs[0].label}"
    key: F4
    rows: $.legs[0].cashflows
    limit: 5
    moreLabel: "(size($.legs[0].cashflows) - 5) + ' later cashflows'"
    totalLabel: Total
    columns:
      - { label: "#", bind: "@.n" }
      - { label: Pay date, bind: "@.payDate", fmt: date }
      - { label: Amount, bind: "@.amount", fmt: signed2, tone: sign, total: true }
      - { label: DF, bind: "@.df", fmt: df4 }
      - { label: PV, bind: "@.pv", fmt: signed2, tone: sign, total: true }
      - { label: Status, bind: "@.status", tone: status }
```

You should see five of the seven fixed cashflows, a total row `Total · +68,325,150.22 · … · +52,387,404.92` (the
totals cover all seven rows, not just the five shown; the label sits in the column just before the first total),
and `2 later cashflows` under the table. Numeric columns (those with a format other than `date`) are right-aligned.

### 4.4 A ladder that lights the next payment

```yaml
  - id: schedule
    kind: ladder
    title: Payment schedule
    code: CF
    rows: $.schedule
    highlight: "#index == $.nextIndex"
    columns:
      - { label: Pay date, bind: "@.payDate", fmt: date }
      - { label: Leg, bind: "@.leg == 1 ? 'Fixed' : 'Float'" }
      - { label: Amount, bind: "@.amount", fmt: signed0, tone: sign }
      - { label: PV, bind: "@.pv", fmt: signed0, tone: sign, total: true }
```

You should see all 35 flows with the seventh (`2026-12-30`, Float, `−2,253,620`) highlighted, and a total PV of
`+1,875,863`. A ladder always shows every row (it takes no `limit`); use a table when you need to cut.

### 4.5 Charts: the trade's own data and another entity's

```yaml
  - id: pnl
    kind: line
    title: Daily P&L (USD)
    area: right
    rows: $.pnlHistory
    x: date
    y: pnl
    fmt: signed0
  - id: curve
    kind: line
    title: Discount curve
    code: CRV
    key: F5
    area: right
    source: "link($.discountCurve, 'ir-curve')"
    rows: $.points
    x: tenor
    y: zeroRate
    mark: "'5Y'"
    fmt: price2
    unit: "%"
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
```

You should see on the right: twenty days of P&L ending `2026-09-30`; the AUD OIS curve from `1M` to `30Y` with the
`5Y` point marked and `5Y point 4.03%` under it, beside a `CRV-AUD-OIS` link; and seven DV01 bars from `3M −5,544` to
`7Y −38,811`.

`source:` is what makes the second chart special: Drishti fetches the curve entity (with the same business date),
and inside that panel `$` is the **curve's** document, so `rows: $.points` reads the curve's points. `mark` is
evaluated against the trade.

### 4.6 Operations, a gauge, links and keys

```yaml
  - id: operations
    kind: status
    title: Confirmation and clearing
    code: OPS
    fields:
      - { label: Confirmation, bind: $.confirmation.status, tone: status }
      - { label: Platform, bind: "$.confirmation.method + ' · ' + $.confirmation.platformId" }
      - { label: Clearing, bind: "$.clearing.status + ' at ' + $.clearing.ccp", tone: status }
      - { label: Reporting, bind: $.regulatory.reportingStatus, tone: status }
  - { id: built, kind: provenance, title: How this view was built }
  - id: dv01use
    kind: gauge
    title: DV01 used of the 250k guideline
    area: right
    value: "abs($.risk.dv01)"
    max: "250000"
  - { id: refs, kind: links, title: Linked entities, code: REFS, area: right }
keys:
  F7: "link($.nettingSet, 'netting-set')"
  F8: impact
  F9: raw
```

You should see `Confirmation Confirmed`, `Clearing Cleared at LCH SwapClear` and `Reporting Accepted` in the *fine*
colour; a gauge 62% full reading `62%` (the gauge formats value ÷ max, with `pct0` unless you say otherwise); the
linked counterparty (badge `A`), netting set (badge `PFE 46.0m`), book, trader, desk and more; and the key bar
`F2 Terms`, `F3 Legs`, `F4 Cashflows`, `F5 Discount curve`, `F7 Netting set`, `F8 Impact`, `F9 Raw JSON`.

### 4.7 How the P&L moved, and what happened to the trade

Two more questions a trader asks of a trade: *why did the MTM move today*, and *what has happened to it so far*.
The trade's `pnlExplain` lists yesterday's MTM and today's moves by risk factor; `lifecycle.timeline` lists dated
events. A `waterfall` and a `timeline` answer them:

```yaml
  - id: explain
    kind: waterfall
    title: "P&L explain (USD, opening to closing MTM)"
    code: PNLX
    key: F6
    rows: $.pnlExplain          # [{step: Opening MTM, pnl: 1748877, total: true}, {step: Carry, pnl: -11426}, …]
    label: step
    value: pnl
    sum: Closing MTM
    fmt: signed0
  - { id: lifecycle, kind: timeline, title: Lifecycle, code: LIFE, rows: $.lifecycle.timeline, detail: description }
```

You should see eight bars: *Opening MTM +1,748,877* as a full bar, seven floating steps (*Carry −11,426* down,
*Rates delta +132,686* up, …) and *Closing MTM +1,875,863*, which the server adds because of `sum:` and which equals
the strip's MTM. Under it, seven events from *Booked* (2025-07-25) to *Maturity* (2032-06-25), *Next payment* in
amber because its status is *Pending*.

`label`, `value` and `detail` are field names of each row, as for `hbar`. The server does the arithmetic (the running
total, the closing bar, the sort by date), so the console only draws. Five more kinds work the same way:
`histogram` (a VaR scenario vector with VaR and ES lines), `scatter` (books' VaR against P&L), `candlestick` (daily
bars), `graph` (a counterparty's group, agreement and netting sets) and `pivot` (MTM by book and currency, with
totals). [Chapter 10](#10-every-panel-kind-by-example) has a complete Sutra for each of the twenty kinds on a sample
entity, [The twenty panel kinds](../../console/web/guides/panel-kinds.md) shows each on the packs' own screens, and
[PANELS.md](PANELS.md) explains every option and what the server computes.

Put the blocks of 4.2 to 4.7 together under one `panels:` and you have a complete trade view. The
[reference's annotated example](RACHANA_REFERENCE.md#a-complete-example-annotated) is the same layout with every line
commented, and the trading pack's `packs/trading/sutras/rates/irs-fixfloat.v1.sutra.yaml` is the one the server uses.

## 5. Matching several Sutras

Many Sutras can apply to one kind. The engine takes the latest version of each Sutra of that kind, tries them by
`priority` (highest first, then by name), and uses the first whose `where` holds.

### Specific before general

There is no automatic "most specific wins": a longer `where` does not win by itself. Give the special case a
higher priority:

```yaml
# irs-matured.v1.sutra.yaml: matured swaps get a short layout
match: { kind: trade, where: "$.productType == 'IRS_FIXFLOAT' && $.status == 'Matured'", priority: 30 }

# swap-desk.v1.sutra.yaml: every other fixed/float swap
match: { kind: trade, where: "$.productType == 'IRS_FIXFLOAT'", priority: 20 }

# trade-fallback.v1.sutra.yaml: any trade at all
match: { kind: trade, priority: 0 }
```

`MX-20000001` is `Live`, so `irs-matured` does not hold; `swap-desk` does. A trade of another product falls to
`trade-fallback` (no `where` always holds). Without a fallback, it falls to the product's own pack Sutra if one
matches, and to inference otherwise.

Good conditions are cheap and read stable fields: the product type, an asset class, a flag. They run for every
view and on every live tick.

### Checking which Sutra applies

```bash
curl -s http://localhost:18480/api/v1/sutras | python3 -c "
import json, sys
for s in json.load(sys.stdin):
    if s['kind'] == 'gene': print(s['priority'], s['name'], s['where'])"
```

You should see `10 gene None`: one Sutra for genes, applying to all of them. After you save `gene-mine` with priority
20, it appears above, and every gene's *How this view was built* says `Sutra gene-mine v1`.

### Versions

`name@version` identifies a Sutra. To change a published Sutra, publish the next version
(`swap-desk.v2.sutra.yaml` with `version: 2`); keep version 1 on disk. Matching uses only the **latest** version of
each name; the older one stays readable (`GET /api/v1/sutras/swap-desk/1/source`), so you can compare or roll back
by removing version 2. Two files may not define the same `name@version` (`DRS-2028`).

## 6. Saving: Studio and governance

On this sample server Studio may save (`drishti.rachana.studio-save: true`) and governance is on:

```bash
curl -s http://localhost:18480/api/v1/studio/settings
```

You should see `{"save":true,"review":true,"approve":true}` (`approve` is whether *you* may approve).

1. In Studio, type a note for the reviewer (*What changed?*) and press **Submit for review**. The Sutra is checked as
   it stands; if it has problems they are listed and nothing is submitted. Otherwise the status line says
   `Submitted gene-mine v1 for review as <id>`.
2. An approver opens **Studio → Reviews** (`/studio/reviews`), sees the proposal with a diff against the live
   version (empty for a new Sutra), and approves or rejects it (a rejection needs a reason). Panel blocks that only
   changed place are listed as moves (*moved: Legs from position 1 to 2 (main → side)*), with the remaining edits as
   a line diff; **Full line diff** opens the plain diff.
3. With security on and `drishti.governance.four-eyes: true` (the default), the author cannot approve their own
   proposal (`DRS-2007`). If someone changed the live Sutra after you proposed, approval is refused (`DRS-2006`):
   propose again from the live version.
4. On approval the file is written to `<first site Sutra directory>/<domain>/<name>.v<N>.sutra.yaml` (here
   `./sutras/studio/gene-mine.v1.sutra.yaml`, unless the Sutra says `domain:`) and goes live at once.

Every step is recorded in the audit log. With governance off, the button reads **Save** and publishes directly.

You can also skip Studio: put the file in a Sutra directory (`drishti.rachana.dirs`, `./sutras` by default). The
server notices within a quarter of a second and loads it; an invalid file is reported and, if it had a valid
version before, that version stays live:

```bash
curl -s http://localhost:18480/api/v1/sutras/problems
```

You should see `{}` when everything loaded.

Only files named `*.sutra.yaml` are Sutras. Any other `.yaml` or `.yml` file in a Sutra directory, and any old
Markdown Sutra (`*.sutra.md`, the format before Drishti 1.11), is reported as `DRS-2004` with the fix: rename the
YAML file, or convert the Markdown one, which keeps its comments and turns its prose into `notes:`:

```bash
python3 tools/rachana/md_to_yaml.py sutras/ --delete
```

`--delete` removes each `.sutra.md` once its `.sutra.yaml` is written; leave it out to keep both and compare first
(the `.md` is still reported until you remove it).

### Editing outside Studio

Any editor that reads JSON Schema can complete and check a Sutra as you type. The server publishes the schema of
the language, with this server's entity kinds and format names filled in:

```bash
curl -s http://localhost:18480/api/v1/rachana/schema | python3 -c "
import json, sys
s = json.load(sys.stdin)
print(s['title']); print(s['required']); print(len(s['properties']['match']['properties']['kind']['enum']), 'kinds')"
```

You should see `Rachana Sutra, language 1`, `['rachana', 'sutra', 'version', 'match']` and the number of kinds the
enabled packs serve. With the YAML extension of VS Code, for example, a first line

```text
# yaml-language-server: $schema=http://localhost:18480/api/v1/rachana/schema
```

gives completion of keys, panel kinds, options, formats and kinds, and marks unknown keys before you save. The
schema is generated from the grammar, so it never disagrees with the parser, but the server's checks (expressions,
duplicate ids and keys) remain the final word: `GET /api/v1/sutras/problems` after you save.

## 7. How packs ship Sutras

A pack is a folder under `packs/` with a `pack.yaml`. Its Sutras live in `sutras/` (or the folder its `sutras:` key
names), one sub-folder per domain:

```text
packs/genomics/
  pack.yaml                         # kinds, mnemonics, id patterns, links, badges, roles
  config/semantics.yaml             # optional: inference hints (roles, acronyms, labels)
  config/formats.yaml               # optional: named formats
  sutras/genomics-and-biology/
    gene.v1.sutra.yaml
    variant.v1.sutra.yaml
    …
  samples/gene/GENE-BRCA1.json      # sample documents for the demo source
```

- A pack's Sutras load when the pack is enabled (`drishti.packs.enabled`, env `DRISHTI_PACKS`), alongside the site's.
- A pack that `extends:` another may redefine one of its parent's Sutras with the same `name@version`; the more
  specific pack wins. A site file with the same `name@version` as a pack's keeps the site's and reports the pack's as
  `DRS-2028`; to change a pack's layout for your site, prefer a new name with a higher priority, or a higher version.
- Some packs are generated (`packs/genomics/pack.yaml` begins `Generated by tools/packgen/genomics/make.py`): change
  the generator, not the generated Sutras, or your edit is lost at the next generation.
- Packs can also add formats (`config/formats.yaml`) that their Sutras use, such as `pct4` and `bp1`.

See [PACKS.md](PACKS.md) for writing a pack.

## 8. Things to know before you write many Sutras

- **Labels you leave out are filled in.** `{ bind: $.notional, fmt: amount0 }` reads *Notional*; pack vocabularies
  spell acronyms (`dv01ByTenor` → *DV01 by tenor*).
- **A field missing from one document is not an error.** The cell is empty; a panel whose data is missing says
  *No data available*. One Sutra can serve documents with optional parts.
- **A markdown panel shows plain text.** `**bold**` is not rendered inside a panel. Explanations for the people
  who maintain the layout belong in `description` and `notes`, not in a panel.
- **Sizes are not fixed.** `highlight: "#index == size($.history) - 1"` lights the last row of any length;
  `#index == 19` lights row 20 only.
- **Options that are field names take no `@.`:** `x: tenor`, `label: bucket`, `value: dv01`. Options that are
  expressions take full paths: `rows: $.points`.

## 9. Common mistakes and their messages

All the problems of a file are reported at once. Studio lists them as `<code> line <n>: <message>`; the server log
and `GET /api/v1/sutras/problems` give `<file>:<line>:<column> <code> <message>`.

| You wrote | You see | Fix |
|---|---|---|
| `bind: @.amount` (unquoted) | `DRS-2001 YAML syntax: while scanning for the next token found character '@' that cannot start any token. (Do not use @ for indentation) …` | quote it: `bind: "@.amount"` |
| `highlight: #index == 0` | no error, and no row is highlighted (`#` starts a YAML comment, so the option is empty) | `highlight: "#index == 0"` |
| `version: "2"` | `DRS-2020 version must be a positive integer` | `version: 2` |
| `sutra: Gene_Mine` | `DRS-2020 name 'Gene_Mine' must be lower-case kebab, 2-64 characters` | `sutra: gene-mine` |
| no `match:` | `DRS-2010 missing 'match' mapping with at least 'kind'` | `match: { kind: gene }` |
| `pannels:` | `DRS-2011 unknown key 'pannels' in top level` | `panels:` |
| `kind: chart` | `DRS-2021 unknown panel kind 'chart'; expected one of kv, table, tabs, line, area, hbar, ladder, links, status, provenance, markdown, gauge, surface, waterfall, histogram, scatter, candlestick, graph, timeline, pivot` | `kind: line` |
| a `table` without `rows` | `DRS-2022 'table' panel 'variants' needs option 'rows'` | add `rows: $.variants` |
| `limit: 5` on a `ladder` | `DRS-2023 option 'limit' is not valid for 'ladder' panels` | use a `table`, or drop `limit` |
| `body:` on a `kv` | `DRS-2023 only 'tabs' panels take a 'body'` | make it `kind: tabs` with `each:` |
| two panels with `id: legs` | `DRS-2024 duplicate panel id 'legs'` | rename one |
| `key: F3` twice, or in `keys:` too | `DRS-2025 function key F3 is used twice` | one key per action |
| `key: F13` | `DRS-2025 'F13' is not a function key (F1-F12)` | `F2`–`F12` |
| nine strip items | `DRS-2026 the strip holds at most 8 figures, found 9` | move some into a `kv` panel |
| `area: left` | `DRS-2027 area must be 'main' or 'right'` | `area: right` |
| `span: 13` | `DRS-2030 span must be a whole number from 1 to 12 (columns of the 12-column grid), not '13'` | `span: 12` (or leave it out) |
| `pivot: { rows: [desk] }` with no field `desk` | `DRS-2031 pivot rows name 'desk', which is not one of its fields (…)` | use one of the names the message lists, or add the field to `fields` |
| `{ id: group, kind: graph, title: Group, agreements and netting sets }` | `DRS-2023 option 'agreements and netting sets' is not valid for 'graph' panels` (in `{ }` a comma ends the value) | quote it: `title: "Group, agreements and netting sets"` |
| saving `gene@1` from Studio | `DRS-2028 gene@1 is already defined in …/packs/genomics/…/gene.v1.sutra.yaml` | new name, or `version: 2` |
| no `rachana:` line | `DRS-2009 missing 'rachana: 1' (the Rachana language version) at the top` | add `rachana: 1` as the first key |
| `rachana: 2` | `DRS-2009 'rachana: 2' is not a language version this server reads (it reads 1)` | `rachana: 1` |
| `notes:` followed by a list | `DRS-2012 'notes' is plain text` | `notes: \|` and indented text |
| `gene-mine.v1.sutra.md` (an old Markdown Sutra) | `DRS-2004 Markdown Sutras are no longer read (Sutras are YAML since 1.11): convert it with python3 tools/rachana/md_to_yaml.py … --delete` | run the converter |
| `gene-mine.yaml` in a Sutra folder | `DRS-2004 a Sutra file is named <name>.v<N>.sutra.yaml; rename gene-mine.yaml` | `gene-mine.v1.sutra.yaml` |
| `bind: "size($.legs, 'pv')"` | `DRS-2101 expression 'size($.legs, 'pv')': DRS-2101 size takes 1 argument(s), got 2 at 0` | `sum($.legs, 'pv')` |
| `bind: "round($.mtm)"` | `DRS-2101 expression 'round($.mtm)': DRS-2101 unknown function 'round'; known: abs, coalesce, contains, … at 0` | `fmt: amount0` does the rounding |
| `title: "Legs ${size($.legs)"` | `DRS-2101 template 'Legs ${size($.legs)': DRS-2101 unclosed '${' at 5` | close the brace |
| `bind: $.notionl` | no error; the cell is empty | fix the field name; preview catches these |
| `where: "$.status == 'live'"` | no error; the Sutra never matches (`Live` ≠ `live`) | `lower($.status) == 'live'` |
| `fmt: compact` on a gauge | the gauge reads `0.6` | leave `fmt` out (the gauge shows value ÷ max as `62%`) |
| a status field without `label` | the label reads `null` | give every status field a `label` |

## 10. Every panel kind, by example

This chapter is a catalogue: one complete, minimal Sutra for each of the twenty panel kinds, each previewed against a
sample entity that ships with the banking packs, then the twenty together on real screens. Every Sutra here is whole
(no `…`), so you can paste it into Studio as it stands, and each was previewed against its entity with no problems
and no empty panel.

**How to try one.** Open Studio on the entity the section names, for example
`http://localhost:17480/studio?kind=trade&id=BBG-60000001`, replace the editor's text with the Sutra and press
**Ctrl+Enter**. A preview uses the text in the editor whatever its `match` says. The examples match their kind with
no `priority` (0), so even if you save one, the pack's own Sutra (priority 10) still lays out the entity. To open the
entity itself in the terminal, type the command shown (`TRD BBG-60000001`).

Each section says what the kind is for, the entity it uses, the Sutra, what each option does, what you should see,
and one mistake with the problem it produces. [The twenty panel kinds](../../console/web/guides/panel-kinds.md) shows
each kind on the packs' own screens with a screenshot, and [PANELS.md](PANELS.md) explains every option and what the
server computes.

| Kind | Use it for | Entity | Section |
|---|---|---|---|
| `kv` | one object's named fields | `TRD BBG-60000001` | [10.1](#101-kv-fields-as-a-grid) |
| `table` | a list of similar rows | `NSET NS-NORTHBRIDGE-FRA` | [10.2](#102-table-rows-with-totals-and-n-more) |
| `tabs` | one layout per element of a short list | `CPTY CP-NORTHBRIDGE` | [10.3](#103-tabs-one-layout-per-element) |
| `line` | points along an axis | `TRD BBG-60000001` | [10.4](#104-line-a-curve-from-the-document-or-another-entity) |
| `area` | bands against a limit | `NSET NS-NORTHBRIDGE-FRA` | [10.5](#105-area-exposure-bands-against-a-limit) |
| `hbar` | a few labelled amounts | `TRD BBG-60000001` | [10.6](#106-hbar-bars-scaled-to-the-largest) |
| `ladder` | dated rows, one highlighted | `TRD BBG-60000001` | [10.7](#107-ladder-a-schedule-with-the-next-row-lit) |
| `links` | everything the document refers to | `TRD BBG-60000001` | [10.8](#108-links-the-entities-the-document-points-to) |
| `status` | operational states, coloured by meaning | `TRD BBG-60000001` | [10.9](#109-status-states-coloured-by-meaning) |
| `provenance` | how the view was built | `TRD BBG-60000001` | [10.10](#1010-provenance-how-the-view-was-built) |
| `markdown` | a note for the reader | `TRD BBG-60000001` | [10.11](#1011-markdown-a-note-with-values-in-it) |
| `gauge` | one number against a maximum | `VAR VAR-COMM` | [10.12](#1012-gauge-one-number-against-a-maximum) |
| `surface` | a grid over two axes | `CMDV CMDV-BRENT` | [10.13](#1013-surface-a-grid-over-two-axes) |
| `waterfall` | signed steps from a start to an end | `PNL PNL-COMM-1` | [10.14](#1014-waterfall-steps-from-a-start-to-an-end) |
| `histogram` | how many numbers are spread | `VAR VAR-COMM` | [10.15](#1015-histogram-a-distribution-with-marker-lines) |
| `scatter` | two measures per row | `LE LE-FRA` | [10.16](#1016-scatter-two-measures-per-row) |
| `candlestick` | open, high, low and close by date | `CMD CMD-BRENT` | [10.17](#1017-candlestick-daily-bars-with-volume) |
| `graph` | entities and how they relate | `CPTY CP-NORTHBRIDGE` | [10.18](#1018-graph-entities-and-their-relations) |
| `timeline` | dated events in order | `TRD BBG-60000001` | [10.19](#1019-timeline-dated-events-in-order) |
| `pivot` | rows totalled by one field across another | `DESK DESK-COMM` | [10.20](#1020-pivot-a-total-by-one-field-across-another) |

Then [10.21](#1021-layout-area-span-and-height) shows the layout keys every panel takes, and
[10.22](#1022-all-twenty-kinds-on-real-screens) puts the twenty kinds together.

### 10.1 `kv`: fields as a grid

**For** the named fields of one object: a trade's terms, a client's identifiers. **Entity:** the government bond
trade `TRD BBG-60000001` (Studio: kind `trade`, id `BBG-60000001`).

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

| Option | What it does here |
|---|---|
| `columns` | The fields to show, each `{ label, bind, fmt }`; `bind` is an expression over the document. |
| `rows` | (second panel) One object; with no `columns`, every scalar field in it is shown, labelled from its name. |
| `area: right` | (second panel) Puts the panel in the side column. |

**You should see** *Bond terms* with `Coupon 1.6708%`, `Frequency Quarterly`, `Face amount 17,179,094`,
`Clean price 104.36` and `Yield 3.4159%`, and on the right *Settlement instructions* with `Method CLS`,
`Our agent JPMorgan Chase Bank, N.A.`, `SSI ID SSI-CHF-6A7EC8` and the rest, each labelled for you. The layout line
reads `Sutra kind-kv v1 + inference`: inference listed the second panel's fields.

**Common mistake:** `limit: 5` on a kv (it shows one object, not a list): `DRS-2023 option 'limit' is not valid for
'kv' panels`. Use a `table` for a list.

### 10.2 `table`: rows with totals and "N more"

**For** a list of similar rows: cashflows, member trades, holdings. **Entity:** the netting set
`NSET NS-NORTHBRIDGE-FRA` (kind `netting-set`), whose `trades` lists five securities-financing trades.

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

| Option | What it does here |
|---|---|
| `rows` (required) | The list; inside `columns`, `@` is the current row. |
| `limit` | Shows the first 3 rows. |
| `moreLabel` | An expression for the line under the table; it is evaluated against the document, not a row. |
| `totalLabel` | The text of the total row (default `Total`). |
| `total: true` | (on a column) Sums that column over **all** rows, the hidden ones too. |

**You should see** three rows, the first `BBG-60000107 · Securities lending · 243.0m · 2027-01-15 · +2,398,191`, the
trade ids as links (a field named `…Id` is linked without `link: true`), a `Net +2,816,262` row (the netting set's
net MTM, all five trades) and `2 more trades` under the table.

**Common mistake:** leaving out `rows`: `DRS-2022 'table' panel 'trades' needs option 'rows'`.

### 10.3 `tabs`: one layout per element

**For** a short list of similar objects, one tab (or box) each: the legs of a swap, a client's netting sets.
**Entity:** the counterparty `CPTY CP-NORTHBRIDGE` (kind `counterparty`), which has four netting sets.

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

| Option | What it does here |
|---|---|
| `each` (required) | The list; one tab per element. |
| `tabTitle` | An expression for each tab's title; `@` is the element. |
| `body` | The panel drawn in each tab: a `kv` without an `id`, whose columns read `@`. |
| `layout` | Not used here: `tabs` (the default) or `columns` to show the elements side by side. |

**You should see** four tabs, `NS-NORTHBRIDGE-FRA` to `NS-NORTHBRIDGE-TKY`; the first shows
`Agreement AGR-NORTHBRIDGE-ISDA` (a link), `Trades 5` and `Net MTM +2,816,262`, the second `Net MTM −14,873,783`.

**Common mistake:** sizing the body (`body: { kind: kv, span: 6, … }`): `DRS-2030 'span' sizes a whole panel: a tabs
body takes the size of its panel`. Put `span` on the tabs panel itself.

### 10.4 `line`: a curve, from the document or another entity

**For** points along an axis: P&L by date, rates by tenor. **Entity:** `TRD BBG-60000001`, with twenty days of
`pnlHistory` and a `benchmarkCurve` that names the curve `CRV-CHF-GOVT`.

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

| Option | What it does here |
|---|---|
| `rows` | The points (default `points`). |
| `x`, `y` | **Field names** of each row (no `@.`): the axis text and the number. |
| `mark` | An expression giving one x value to mark; its value is written under the chart. |
| `fmt` | The format of the marked value. |
| `source` | (second panel) `link(id, kind)`: the curve entity is fetched, and `rows` is read from **its** document. |

**You should see** the daily P&L from `2026-09-03` to `2026-09-30` with the last day marked and
`2026-09-30 point −30,997` under it, and on the right the CHF government curve from `1M` (0.1616) to `30Y` (0.7789),
with a `CRV-CHF-GOVT` link under the chart.

**Common mistake:** giving a line several `series` (that is an `area` option): `DRS-2023 option 'series' is not valid
for 'line' panels`.

### 10.5 `area`: exposure bands against a limit

**For** one or more series over a common axis against a dashed limit: exposure profiles. **Entity:** the netting set
`NSET NS-NORTHBRIDGE-FRA`, whose `profile` holds expected exposure and PFE by tenor.

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

| Option | What it does here |
|---|---|
| `rows` (required) | The points. |
| `x` | The field for the horizontal axis (default `tenor`). |
| `series` | One `{ label, value, tone }` per band; `value` is a field name of each row. |
| `limit`, `limitLabel` | An expression for the dashed line, and its legend text. |

**You should see** two filled bands over `0, 1M, 3M … 10Y`, peaking at `3M` (expected exposure 379,996, PFE 878,778),
and *Credit limit* in the legend. The vertical axis always reaches the limit, so the dashed line at 5,000,000 is drawn
(labelled *limit 5.0m*) well above the bands: the netting set uses less than a fifth of its limit.

**Common mistake:** writing `y: ee` as for a line: `DRS-2023 option 'y' is not valid for 'area' panels`. An area's
values are its `series`.

### 10.6 `hbar`: bars scaled to the largest

**For** a handful of labelled amounts: DV01 by bucket, VaR by book. **Entity:** `TRD BBG-60000001` and its eight
`sensitivities`.

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

| Option | What it does here |
|---|---|
| `rows` (required) | The bars. |
| `label`, `value` | Field names of each row: the bar's text and its number. |
| `fmt` | The format of the number beside each bar. |
| `tone: sign` | Colours each bar by its sign (the default does the same). |

**You should see** eight bars from `3M −5,289` to `10Y −42,311`, all in the negative colour (the bond is short).

**Common mistake:** asking for the top five with `limit: 5`: `DRS-2023 option 'limit' is not valid for 'hbar'
panels`. An hbar draws every row; give it a shorter list.

### 10.7 `ladder`: a schedule with the next row lit

**For** a dated list in which one row matters most: the next coupon, today's settlement. **Entity:**
`TRD BBG-60000001`, whose `schedule` holds 24 coupons and whose `nextIndex` says which is next.

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

| Option | What it does here |
|---|---|
| `rows` (required) | The rows; a ladder shows all of them. |
| `highlight` | A condition per row (`@` the row, `#index` its position); rows where it holds are lit. |
| `totalLabel` | The total row's text. |
| `columns` | As for a table; `total: true` sums a column. |

**You should see** 24 rows with the first (`2027-03-02 · 3.5397% · 3,960,000 · Scheduled`) highlighted, because
`nextIndex` is 0, and a total row `All coupons 95,040,000`.

**Common mistake:** `limit: 5` on a ladder: `DRS-2023 option 'limit' is not valid for 'ladder' panels`. A ladder
always shows every row; use a `table` to cut.

### 10.8 `links`: the entities the document points to

**For** every reference in the document, as links with a short badge read from each target. **Entity:**
`TRD BBG-60000001`.

```yaml
rachana: 1
sutra: kind-links
version: 1
match: { kind: trade }
panels:
  - { id: refs, kind: links, title: Linked entities }
```

The panel takes no options: which fields are references, and the badge each target shows, come from the packs.

**You should see** ten links, among them `Counterparty CP-SUMMIT` (badge `A-`), `Netting set NS-SUMMIT-NY`
(`PFE 76.9m`), `Book BOOK-FI-3`, `Clearing account CLR-EUREX-1` (`IM 20.4m`), `Benchmark curve CRV-CHF-GOVT`
(`0.73% 10Y`) and `Underlying bond BND-CHLUME169114` (`112.96`). The layout line says `+ inference`, because the
links are found rather than declared.

**Common mistake:** pointing it at part of the document (`rows: $.counterparty`): `DRS-2023 option 'rows' is not
valid for 'links' panels`. It always reads the whole document.

### 10.9 `status`: states coloured by meaning

**For** operational fields whose words carry a verdict: confirmation, clearing, reporting. **Entity:**
`TRD BBG-60000001`.

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

| Option | What it does here |
|---|---|
| `fields` | The fields, each `{ label, bind, fmt, tone }`; `bind` is an expression over the document. |
| `tone: status` | Reads the words: *fail*, *reject*, *dispute*, *breach* are bad; *pending*, *unmatched*, *warn* a warning; anything else fine. |

**You should see** `Confirmation Confirmed · DTCC CTM`, `Clearing Cleared at LCH SwapClear`, `Reporting Accepted`
and `Valuation Official EOD`, all in the *fine* colour.

**Common mistake:** `rows: $.confirmation`, as for a kv: `DRS-2023 option 'rows' is not valid for 'status' panels`.
Writing `columns:` instead of `fields:` is not reported, but the panel stays empty, and a field without a `label`
is labelled `null`.

### 10.10 `provenance`: how the view was built

**For** the last panel of every view: which Sutra laid it out, and where the data came from. **Entity:**
`TRD BBG-60000001`.

```yaml
rachana: 1
sutra: kind-provenance
version: 1
match: { kind: trade }
panels:
  - { id: built, kind: provenance, title: How this view was built }
```

No options. **You should see** `Layout Sutra kind-provenance v1`, `Fingerprint a581…a3fd` (a short form of the
document's shape) and `Source summit-fi, gen 1`.

**Common mistake:** giving it text (`text: Built by the bond desk`): `DRS-2023 option 'text' is not valid for
'provenance' panels`. A note is a `markdown` panel.

### 10.11 `markdown`: a note with values in it

**For** a sentence to the reader: what the view is for, the units. **Entity:** `TRD BBG-60000001`.

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

| Option | What it does here |
|---|---|
| `text` (required) | A template: each `${…}` is replaced by the value of an expression over the document. |

**You should see** `A short government bond position in CHF, booked in BOOK-FI-3. MTM and P&L are in USD.` The text
is shown as plain text: `**bold**` and lists are not rendered.

**Common mistake:** a markdown panel without `text`: `DRS-2022 'markdown' panel 'note' needs option 'text'`.

### 10.12 `gauge`: one number against a maximum

**For** a utilisation: VaR against its limit, a budget used. **Entity:** the VaR result `VAR VAR-COMM` (kind `var`),
with `var99` 10,959,317 and `limit` 18,940,000.

```yaml
rachana: 1
sutra: kind-gauge
version: 1
match: { kind: var }
panels:
  - { id: usage, kind: gauge, title: VaR 99% used of the desk limit, value: $.var99, max: $.limit }
```

| Option | What it does here |
|---|---|
| `value` (required) | An expression for the number. |
| `max` | An expression for the full scale (default 1, for a ratio already in the document). |

**You should see** a bar 58% full with the text `58%`: the gauge shows value ÷ max, formatted `pct0` unless `fmt`
says otherwise.

**Common mistake:** leaving out `value` (`{ …, kind: gauge, max: $.limit }`): `DRS-2022 'gauge' panel 'usage' needs
option 'value'`. And do not give a value format such as `fmt: compact`: `fmt` formats the ratio, so it would read
`0.6`.

### 10.13 `surface`: a grid over two axes

**For** values over two axes: volatility by expiry and moneyness, a correlation matrix. **Entity:** the Brent
volatility surface `CMDV CMDV-BRENT` (kind `commodity-vol-surface`), whose `grid` holds one row per contract month.

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

| Option | What it does here |
|---|---|
| `rows`, `y` (required) | The rows (one per point down the side), and the field that labels each. |
| `columns` | One per point across the top: `label` names it, `bind` reads the cell from the row. |
| `fmt` | The format of every cell. |
| `view` | Not used here: `heatmap` (the default) or `3d` to open as a rotatable surface. |

**You should see** a heatmap with the months `X6` to `M7` down the side and `90%`, `ATM`, `110%` across, from `27.26`
(`M7`, at the money) to `32.82` (`X6`, 90%): the smile is lowest at the money and falls with time. **3D** turns it into
a surface.

**Common mistake:** leaving out `y`: `DRS-2022 'surface' panel 'smile' needs option 'y'`.

### 10.14 `waterfall`: steps from a start to an end

**For** signed contributions that add up: a P&L explain, a bridge from one figure to another. **Entity:** the P&L
explain `PNL PNL-COMM-1` (kind `pnl-explain`), whose `explainSteps` run from carry to the unexplained remainder.

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

| Option | What it does here |
|---|---|
| `rows` (required) | The steps, in order. |
| `label`, `value` | Field names: the step's name and its signed amount. |
| `sum` | Appends a closing total bar at the running sum, with this label. |
| `fmt` | The format of the amounts. |
| `total`, `colors` | Not used here: the field that marks a step as a total, and `gain-loss` (default) or `theme` colours. |

**You should see** nine floating bars from `Carry −26,009` to `Unexplained +5,460`, rises (`Theta +7,431`) in green and
falls in red, and a grey `Actual −180,316` bar that the server added and that equals the document's `actual`.

**Common mistake:** a colour scheme the kind does not have (`colors: red-green`): `DRS-2029 option 'colors' of
'waterfall' panels must be one of gain-loss, theme, not 'red-green'`.

### 10.15 `histogram`: a distribution with marker lines

**For** how a list of numbers is spread: scenario P&Ls, a P&L history. **Entity:** `VAR VAR-COMM`, whose
`scenarioPnl` holds 500 numbers.

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

| Option | What it does here |
|---|---|
| `rows` (required) | A list of numbers (or of rows, with `value` naming the field). |
| `bins` | How many bins, 1 to 200 (default the square root of the count, 5 to 40). |
| `fmt` | The format of bin edges and markers. |
| `markers` | Dashed lines, each `{ label, value, tone }`; `value` is an expression over the document. |

**You should see** 30 bins over 500 values from `−15.0m` to `20.2m`, with `VaR 99% −11.0m`, `ES 97.5% −12.4m` and
`Mean −49.8k` marked.

**Common mistake:** `bins: 0`: `DRS-2029 option 'bins' of 'histogram' panels must be a whole number from 1 to 200,
not '0'`. A marker without a `value` is `DRS-2029` too.

### 10.16 `scatter`: two measures per row

**For** comparing rows on two measures: risk against return. **Entity:** the legal entity `LE LE-FRA` (kind
`legal-entity`), whose `books` carry VaR, P&L and a trade count.

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

| Option | What it does here |
|---|---|
| `rows`, `x`, `y` (required) | The rows, and the field names of the two measures. |
| `size`, `label`, `group` | Point size, point label (a book id, so the point opens the book), and the field that colours it. |
| `fmt`, `xFmt` | Formats of y and x. |
| `xLabel`, `yLabel` | Axis titles. |

**You should see** three points in one colour (*Repo and securities lending*): `BOOK-SFT-1` at VaR `14.9m` and P&L
`−327,989` (the largest, 17 trades), `BOOK-SFT-2` at `13.3m` and `+54,701`, `BOOK-SFT-3` at `12.9m` and `−136,981`.

**Common mistake:** leaving out `y`: `DRS-2022 'scatter' panel 'books' needs option 'y'`.

### 10.17 `candlestick`: daily bars with volume

**For** prices by date with open, high, low and close. **Entity:** the commodity `CMD CMD-BRENT` (kind `commodity`),
whose `ohlc` holds 60 days.

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

| Option | What it does here |
|---|---|
| `rows` (required) | The bars, oldest first. |
| `x` | The date field (default `date`). `open`, `high`, `low`, `close` default to those names. |
| `volume` | The volume field: bars under the prices. |
| `fmt`, `unit` | The price format and unit. |

**You should see** 60 bars from `2026-07-08` to `2026-09-30`, volume under them, and `Last 74.60`, `+1.12 (+1.52%)`.

**Common mistake:** `limit: 30` to show fewer days: `DRS-2023 option 'limit' is not valid for 'candlestick' panels`.
The chart zooms instead (scroll inside it).

### 10.18 `graph`: entities and their relations

**For** how entities relate: a group hierarchy, the agreements and netting sets under it. **Entity:**
`CPTY CP-NORTHBRIDGE`, whose `hierarchy` holds eight nodes and seven edges.

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

| Option | What it does here |
|---|---|
| `nodes` (required) | The nodes; each needs an `id`. A node whose id is an entity opens it. |
| `edges` | The relations: `from`, `to` and an optional `label`. |
| `label`, `group` | The node fields shown and coloured by (these are the defaults). |
| `layout` | `tree` (levels from the nodes nothing points to) or `force`. |

**You should see** *Northbridge Capital Holdings* at the top, *Northbridge Capital LLP* (ringed: the entity you are
viewing) under it, then *ISDA 2002 Master*, its *Credit support annex* and four netting sets, each a link.

**Common mistake:** a layout the kind does not have (`layout: circle`): `DRS-2029 option 'layout' of 'graph' panels
must be one of tree, force, not 'circle'`.

### 10.19 `timeline`: dated events in order

**For** what happened, and what will: a trade's lifecycle, a workflow. **Entity:** `TRD BBG-60000001`, whose
`lifecycle.timeline` holds five events.

```yaml
rachana: 1
sutra: kind-timeline
version: 1
match: { kind: trade }
panels:
  - { id: life, kind: timeline, title: Lifecycle, rows: $.lifecycle.timeline, label: event, detail: description }
```

| Option | What it does here |
|---|---|
| `rows` (required) | The events, in any order: the server sorts them by date. |
| `label`, `detail` | Field names of the event's name and its note (`date` and `status` keep their defaults). |

**You should see** `Booked` and `Confirmed` on 2026-08-31, `Cleared` on 2026-09-01, `Next payment` on 2027-03-02 in
amber (its status is *Pending*) and `Maturity` on 2038-04-30, each with its note.

**Common mistake:** `highlight:` as on a ladder: `DRS-2023 option 'highlight' is not valid for 'timeline' panels`.
The status tone already marks what is pending.

### 10.20 `pivot`: a total by one field across another

**For** rows aggregated in two directions with totals: MTM by book and family, exposure by rating and tenor.
**Entity:** the desk `DESK DESK-COMM` (kind `desk`), whose `positions` holds one row per trade.

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

| Option | What it does here |
|---|---|
| `rows`, `by`, `across` (required) | The rows, the field down the side and the field along the top. |
| `value`, `agg` | The field aggregated and how: `sum` (default), `count`, `avg`, `min`, `max`. Without `value` it counts rows. |
| `heat` | `true` shades each cell by its value. |
| `fmt`, `tone` | The format and tone of every cell. |

**You should see** three books across `future`, `linear`, `swap`, `option` and `exotic`, with `BOOK-COMM-1 11.8m`,
`BOOK-COMM-2 20.5m`, `BOOK-COMM-3 −19.1m` in the total column and the desk's `13.3m` at the bottom right (the
desk's MTM, 13,315,593).

**Common mistake:** an aggregation the kind does not have (`agg: median`): `DRS-2029 option 'agg' of 'pivot' panels
must be one of sum, count, avg, min, max, not 'median'`. `heat` and `totals` must be `true` or `false`; note that YAML
reads an unquoted `yes` as `true`.

### 10.21 Layout: `area`, `span` and `height`

Three keys place any panel, whatever its kind:

| Key | Values | Effect |
|---|---|---|
| `area` | `main` (default) or `right` | The column: the wide main column or the side column. |
| `span` | 1 to 12 (default 12) | The width in columns of the 12-column grid of its column. Panels narrower than their column sit side by side, in order: `span: 8` then `span: 4` share a row. On a phone every panel is full width. |
| `height` | 1 to 24 | A fixed height in grid rows (2.5 rem each); the panel scrolls inside. Without it, the panel takes the height of its content. |

`area: left` is `DRS-2027 area must be 'main' or 'right'`; `span: 13` and `height: 30` are `DRS-2030`
(`height must be a whole number from 1 to 24 (grid rows), not '30'`). Users may rearrange a view for themselves in
layout mode (`Alt+L`), which never changes the Sutra.

One YAML trap comes with short panels on one line: inside `{ … }` a comma ends a value, so
`{ id: group, kind: graph, title: Group, agreements and netting sets, … }` reads `agreements and netting sets` as a
key, and you get `DRS-2023 option 'agreements and netting sets' is not valid for 'graph' panels`. Quote such a title:
`title: "Group, agreements and netting sets"`.

### 10.22 All twenty kinds on real screens

A real screen mixes kinds. The government bond trade carries the data for twelve of them, so one Sutra lays out the
whole trade with every one, and uses `area`, `span` and `height` to arrange them. Preview it against
`TRD BBG-60000001`:

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

**You should see**, in the main column: *Bond terms* (eight columns wide) beside *Operations* (four); the P&L explain
waterfall from `Opening MTM −955,004` to `Closing MTM −978,378` (the strip's MTM) beside the daily P&L, both nine rows
high; the coupon ladder, eight rows high and scrolling, with the next coupon lit; the lifecycle beside a one-row
*Versions* table (`search: false` drops its filter box); and *How this view was built*. In the side column: the note,
the gauge at `76%`, the DV01 bars and ten linked entities in a box ten rows high. The key bar shows `F2 Bond terms`,
`F3 P&L explain`, `F4 Coupon schedule`, `F5 Lifecycle`, `F7 Netting set`, `F8 Impact` and `F9 Raw JSON`.

The other eight kinds need data that lives on other entities. Grouped by entity, these Sutras cover them, each
previewed against the entity named above it.

`CPTY CP-NORTHBRIDGE`: `tabs` and `graph`, with a kv beside the tabs.

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

`DESK DESK-COMM`: `pivot` and `scatter`, and a `line` whose `source` reads the desk's VaR result (`VAR-COMM`), so one
screen shows data from two entities.

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

`NSET NS-NORTHBRIDGE-FRA`: `area`, with a gauge (`18%` of the limit) and a table whose columns inference picks.

```yaml
rachana: 1
sutra: netting-set-desk
version: 1
match: { kind: netting-set, priority: 20 }
panels:
  - { id: exposure, kind: area, title: Exposure profile (USD), key: F2, span: 8, rows: $.profile, x: tenor, limit: $.limit, series: [ { label: EE, value: ee, tone: link }, { label: PFE 95, value: pfe, tone: accent } ] }
  - { id: usage, kind: gauge, title: Limit utilisation, span: 4, value: $.utilisation }
  - { id: trades, kind: table, title: Member trades, rows: $.trades }
```

`VAR VAR-COMM`: `histogram`, beside VaR by book.

```yaml
rachana: 1
sutra: var-desk
version: 1
match: { kind: var, priority: 20 }
panels:
  - { id: scenarios, kind: histogram, title: Scenario P&L (USD), key: F2, span: 8, rows: $.scenarioPnl, fmt: compact, markers: [ { label: VaR 99%, value: "-$.var99", tone: neg } ] }
  - { id: books, kind: hbar, title: VaR by book (USD), span: 4, rows: $.contributions, label: book, value: var, fmt: compact }
```

`CMD CMD-BRENT`: `candlestick`, beside the forward curve read from `CMDC-BRENT`.

```yaml
rachana: 1
sutra: brent-desk
version: 1
match: { kind: commodity, priority: 20 }
panels:
  - { id: bars, kind: candlestick, title: Front month (USD/bbl), key: F2, span: 8, rows: $.ohlc, volume: volume, fmt: price2 }
  - { id: forward, kind: line, title: Forward curve (USD/bbl), span: 4, source: "link($.commodityCurve, 'commodity-curve')", rows: $.points, x: month, y: price }
```

`CMDV CMDV-BRENT`: `surface`, opening in 3D.

```yaml
rachana: 1
sutra: brent-vol-desk
version: 1
match: { kind: commodity-vol-surface, priority: 20 }
panels:
  - { id: smile, kind: surface, title: Implied vol (%), key: F2, view: 3d, rows: $.grid, y: month, columns: [ { label: 90%, bind: "@.m90" }, { label: ATM, bind: "@.m100" }, { label: 110%, bind: "@.m110" } ] }
```

| Kind | Screen | Kind | Screen |
|---|---|---|---|
| `kv` | bond-desk, counterparty-desk | `gauge` | bond-desk, netting-set-desk |
| `table` | bond-desk, netting-set-desk | `surface` | brent-vol-desk |
| `tabs` | counterparty-desk | `waterfall` | bond-desk |
| `line` | bond-desk, risk-desk, brent-desk | `histogram` | var-desk |
| `area` | netting-set-desk | `scatter` | risk-desk |
| `hbar` | bond-desk, var-desk | `candlestick` | brent-desk |
| `ladder` | bond-desk | `graph` | counterparty-desk |
| `links` | bond-desk, counterparty-desk, risk-desk | `timeline` | bond-desk |
| `status` | bond-desk | `pivot` | risk-desk |
| `provenance` | bond-desk, counterparty-desk, risk-desk | `markdown` | bond-desk |

## Where next

- [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md): every key, panel kind, format, function and problem code.
- [INFERENCE.md](../architecture/INFERENCE.md): what Drishti does without a Sutra, and how to start a Sutra from inference.
- [LIVE.md](../architecture/LIVE.md): how a view you laid out keeps itself up to date.
- [PACKS.md](PACKS.md): packaging Sutras with mnemonics, links, formats and samples.
