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
write a Sutra one step at a time, preview each step, and finish with a complete trade view and a list of the
mistakes everyone makes once. Every example on this page parses and validates as written, and every figure
was read from the running sample server.

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
   none, lays out the whole view by inference (from the shape of the data; see [INFERENCE.md](INFERENCE.md)).

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
before/after pairs against the swap `T-10001` (look at it with
`curl -s http://localhost:18480/api/v1/entities/trade/T-10001/raw | python3 -m json.tool`).

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

| Expression | Result for `T-10001` |
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

Now a bigger layout: the swap `T-10001`. Open Studio on it
(`http://localhost:17480/studio?kind=trade&id=T-10001`) and grow the Sutra one block at a time, previewing each.

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

You should see `[Rates · Interest rate swap (fixed/float)] T-10001 with Meridian Reinsurance Ltd` and the strip
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

Put the blocks of 4.2 to 4.6 together under one `panels:` and you have a complete trade view. The
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

`T-10001` is `Live`, so `irs-matured` does not hold; `swap-desk` does. A trade of another product falls to
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
   version (empty for a new Sutra), and approves or rejects it (a rejection needs a reason).
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
| `kind: chart` | `DRS-2021 unknown panel kind 'chart'; expected one of kv, table, tabs, line, area, hbar, ladder, links, status, provenance, markdown, gauge, surface` | `kind: line` |
| a `table` without `rows` | `DRS-2022 'table' panel 'variants' needs option 'rows'` | add `rows: $.variants` |
| `limit: 5` on a `ladder` | `DRS-2023 option 'limit' is not valid for 'ladder' panels` | use a `table`, or drop `limit` |
| `body:` on a `kv` | `DRS-2023 only 'tabs' panels take a 'body'` | make it `kind: tabs` with `each:` |
| two panels with `id: legs` | `DRS-2024 duplicate panel id 'legs'` | rename one |
| `key: F3` twice, or in `keys:` too | `DRS-2025 function key F3 is used twice` | one key per action |
| `key: F13` | `DRS-2025 'F13' is not a function key (F1-F12)` | `F2`–`F12` |
| nine strip items | `DRS-2026 the strip holds at most 8 figures, found 9` | move some into a `kv` panel |
| `area: left` | `DRS-2027 area must be 'main' or 'right'` | `area: right` |
| saving `gene@1` from Studio | `DRS-2028 gene@1 is already defined in …/packs/genomics/…/gene.v1.sutra.yaml` | new name, or `version: 2` |
| no `rachana:` line | `DRS-2009 missing 'rachana: 1' (the Rachana language version) at the top` | add `rachana: 1` as the first key |
| `rachana: 2` | `DRS-2009 'rachana: 2' is not a language version this server reads (it reads 1)` | `rachana: 1` |
| `notes:` followed by a list | `DRS-2012 'notes' is plain text` | `notes: \|` and indented text |
| `gene-mine.v1.sutra.md` (an old Markdown Sutra) | `DRS-2004 Markdown Sutras are no longer read (Sutras are YAML since 1.11): convert it with python3 tools/rachana/md_to_yaml.py … --delete` | run the converter |
| `gene-mine.yaml` in a Sutra folder | `DRS-2004 a Sutra file is named <name>.v<N>.sutra.yaml; rename gene-mine.yaml` | `gene-mine.v1.sutra.yaml` |
| `bind: "size($.legs, 'pv')"` | `DRS-2101 expression 'size($.legs, 'pv')': DRS-2101 size takes 1 argument(s), got 2 at 0` | `sum($.legs, 'pv')` |
| `bind: "round($.mtm)"` | `DRS-2101 expression 'round($.mtm)': DRS-2101 unknown function 'round'; known: [size, upper, sum, …] at 0` | `fmt: amount0` does the rounding |
| `title: "Legs ${size($.legs)"` | `DRS-2101 template 'Legs ${size($.legs)': DRS-2101 unclosed '${' at 5` | close the brace |
| `bind: $.notionl` | no error; the cell is empty | fix the field name; preview catches these |
| `where: "$.status == 'live'"` | no error; the Sutra never matches (`Live` ≠ `live`) | `lower($.status) == 'live'` |
| `fmt: compact` on a gauge | the gauge reads `0.6` | leave `fmt` out (the gauge shows value ÷ max as `62%`) |
| a status field without `label` | the label reads `null` | give every status field a `label` |

## Where next

- [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md): every key, panel kind, format, function and problem code.
- [INFERENCE.md](INFERENCE.md): what Drishti does without a Sutra, and how to start a Sutra from inference.
- [LIVE.md](LIVE.md): how a view you laid out keeps itself up to date.
- [PACKS.md](PACKS.md): packaging Sutras with mnemonics, links, formats and samples.
