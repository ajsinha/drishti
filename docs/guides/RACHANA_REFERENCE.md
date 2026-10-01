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
versioned YAML file (`<name>.v<N>.sutra.yaml`, starting `rachana: 1`) that describes how to lay out one family of entities. The *How this view was built* panel
reads, for example, `Sutra irs-fixfloat v1 + inference`, meaning the Sutra `irs-fixfloat`, version 1,
completed by inference.

A Sutra holds no code and no pixels. It is loaded by `SutraRegistry` from `drishti.rachana.dirs` and the
enabled packs, and reloads as soon as it is saved. If an edit is invalid, the last good version is kept
and the problems are reported with line and column.

This page is the authoritative reference. Everything in it was checked against the code in
`drishti-rachana` (grammar, parser, validator, Rachana-EL, formats), `drishti-engine` (binding) and
`drishti-inference` (gaps and labels), and the examples were run against the packs' sample data.

| If you want to… | Read |
|---|---|
| see a whole Sutra with every line explained | [A complete example, annotated](#a-complete-example-annotated) |
| copy a working pattern | [Recipes](#recipes) |
| look up one panel kind | [Panel kinds](#panel-kinds) |
| get completion and checking in your editor | [Completion and checking in your editor](#completion-and-checking-in-your-editor) |
| look up a function or an operator | [Rachana-EL](#rachana-el-expressions) |
| understand an error such as `DRS-2023` | [Problem codes](#problem-codes) |
| know what happens when you save a file or press Save in Studio | [Hot reload, Studio and governance](#hot-reload-studio-and-governance) |

## How a Sutra becomes a view

Every request for an entity (`TRD MX-20000001 <GO>`, `GET /api/v1/views/trade/MX-20000001`) goes through the same steps:

1. **Fetch** the document (JSON) from the source that holds the kind.
2. **Match**: take the Sutras whose `match.kind` is the entity's kind and pick the first, in priority order,
   whose `where` holds for this document. No match: the whole layout is inferred.
3. **Merge**: fill the gaps the Sutra leaves (columns it does not list, labels it does not give) by inference.
   The result is the *effective layout*, cached per (Sutra, kind, document shape).
4. **Link**: find the entities the document refers to (for the `links` panel) and the entities charts read
   (`source:`), and fetch them in parallel within `drishti.graph.link-budget` (40 ms).
5. **Bind**: evaluate every expression of the title, the strip and each panel against the document, format and
   colour the values. Panels bind in parallel; one panel that fails shows *No data available* and never takes the
   view down.

The result is a ViewModel (JSON) that the console renders. You can see it with:

```bash
curl -s http://localhost:18480/api/v1/views/trade/MX-20000001 | python3 -m json.tool | head -40
```

You should see `ref`, `mnemonic`, `title`, `strip`, `panels`, `keys`, `provenance` and `timings`, with
`"layout": "Sutra irs-fixfloat v1 + inference"` under `provenance`.

## File format: one YAML file

A Sutra is **one YAML file**, `sutras/<domain>/<name>.v<N>.sutra.yaml`, whose first key is
`rachana: 1`, the version of the Rachana language it is written in. Values are plain YAML; strings may hold
Rachana-EL expressions. That is the only format: there is no second dialect to learn, and any YAML tool
(syntax highlighting, linters, JSON Schema completion, `yq`, code review diffs) works on a Sutra as it is.

```yaml
rachana: 1
sutra: irs-vanilla
version: 3
description: Vanilla fixed/float interest rate swaps, with the legs one key away.
match: { kind: trade, where: "$.productType == 'IRS'" }
panels:
  - id: legs
    kind: table
    title: Legs
    key: F2
    rows: $.legs
    description: One row per leg; the rate is the fixed rate or the current fixing.
    columns:
      - { label: Leg, bind: "@.label" }
      - { label: Rate, bind: "@.rate", fmt: pct4 }
notes: |
  Written for the rates desk. The strip is left to inference on purpose:
  the desk wants every headline field, in the document's order.

  Version 3 moved the legs from a kv panel to a table (review 2026-09).
```

The prose has a place of its own, inside the file, as plain text:

| Key | Where | What to write |
|---|---|---|
| `description` | top level | One paragraph: what this layout shows and for which entities. |
| `notes` | top level | Longer notes for authors and reviewers: why the layout is what it is, what changed in each version, who asked for it. Use a YAML block (`notes: \|`) for several lines. |
| `description` | any panel | One or two sentences on what the panel shows. |

They are plain text: nothing in them is evaluated, and the engine does not use them to build the view (they are
not part of the ViewModel). They travel with the file, so they are in Studio, in a code review diff and in
`GET /api/v1/sutras/{name}/{version}/source`, and nothing has to be kept in step with them.
`description` and `notes` must be text (`DRS-2012 'notes' is plain text` otherwise).

What the server does with a file, in order (`SutraRegistry`, `SutraParser`, `SutraBuilder`):

1. Only files named `*.sutra.yaml` are Sutras. Any other `.yaml`, `.yml` or `.sutra.md` file in a Sutra
   directory is reported as `DRS-2004` with the fix (see [Problem codes](#problem-codes)) and is not loaded.
2. The file is read as YAML, keeping the line and column of every node. Bad YAML: `DRS-2001`.
3. `rachana:` must be present and be a language version this server reads (today: `1`). Otherwise `DRS-2009`.
4. The rest is checked against the grammar below. Every problem is collected (not just the first) with its
   location: `DRS-2010` to `DRS-2027`. Parsing is strict: an unknown key is an error (`DRS-2011`), never
   silently ignored, so a typo such as `pannels:` cannot pass unnoticed.
5. Every Rachana-EL expression and template is compiled. A typo is `DRS-2101`, reported against the file at
   load time, not when someone opens a view.
6. The Sutra is registered under `name@version`; a second file defining the same pair is `DRS-2028`.

Rules that follow from this:

- Problem locations are the file's own line and column, so an error at line 14 is at line 14 in your editor.
- The file name is a convention, not a rule: the name and version come from `sutra:` and `version:`, and the
  parser does not compare them with the file name. Keep them in step anyway; Studio saves to
  `<domain>/<name>.v<N>.sutra.yaml`.
- `#` comments are kept in the file (Studio and `GET /api/v1/sutras/{name}/{version}/source` return the text
  exactly as written) but mean nothing to the engine. Use `notes` for anything a reviewer should read.
- Keep other YAML files out of Sutra directories; they are reported as `DRS-2004`.
- A Sutra from before Drishti 1.11 (`*.sutra.md`, a Markdown document with one ```` ```sutra ```` block)
  converts in one step; the block becomes the file, `rachana: 1` is added, and the prose becomes `notes`:

  ```bash
  python3 tools/rachana/md_to_yaml.py packs/my-pack/sutras --delete
  ```

  You should see one line per file, such as
  `packs/my-pack/sutras/rates/irs-x.v1.sutra.md -> packs/my-pack/sutras/rates/irs-x.v1.sutra.yaml`. Without
  `--delete` the `.sutra.md` files stay beside the new ones (and are reported as `DRS-2004` until you remove
  them). The lines that only restated the layout (the "Applies to" line, the "Panels" table) are dropped,
  since tools derive them from the YAML.
- YAML quoting: a value that starts with `$`, `@` or a letter can usually stay bare (`bind: $.tradeId`).
  Quote any value that contains `: `, ` #`, `{`, `}`, `[`, `]`, `,` at the start, or begins with `'`, `*`,
  `&`, `!`, `%`, `@` followed by a space, or `#`: for example `bind: "@.leg == 1 ? 'Fixed' : 'Float'"`
  and `highlight: "#index == 0"` (unquoted, `#index` would be a YAML comment).

### Completion and checking in your editor

`GET /api/v1/rachana/schema` returns the JSON Schema (draft 2020-12) of the language, generated from the
grammar itself: every key, the panel kinds and the options each takes, which values are Rachana-EL, the tones,
and **this server's** entity kinds and format names (core and enabled packs). `x-rachana-functions` lists the
expression functions. Because it is generated from the parser's own tables, it never disagrees with the parser.

```bash
curl -s http://localhost:18480/api/v1/rachana/schema | python3 -c "
import json, sys
s = json.load(sys.stdin)
print(s['title']); print(s['required']); print(sorted(s['properties']))"
```

You should see:

```text
Rachana Sutra, language 1
['rachana', 'sutra', 'version', 'match']
['description', 'domain', 'keys', 'match', 'notes', 'panels', 'rachana', 'strip', 'sutra', 'title', 'version']
```

Sutra Studio uses it for completion and live checking. Any editor that reads JSON Schema can too; for
example, with the YAML language server (VS Code's YAML extension, and others) put this comment on the first
line of a Sutra:

```yaml
# yaml-language-server: $schema=http://localhost:18480/api/v1/rachana/schema
```

The schema catches most mistakes while you type; the parser remains the authority (it also compiles every
expression, which a schema cannot).

## A complete example, annotated

The Sutra below lays out a vanilla fixed/float interest rate swap from the trading pack (`TRD MX-20000001`). It
uses most of the grammar: matching, the title, the strip, a dozen panels of different kinds, expressions,
templates, links and function keys. Every line is commented. The test suite (`RachanaReferenceExampleTest`)
previews this exact block against `MX-20000001` and fails if it stops parsing or leaves a panel empty, so it
always works as written.

How to read the notation:

- `$` is the document. `$.counterparty.name` walks into objects, and `$.legs[0].rate` into arrays.
- `@` is the current row inside `rows`, `each` or a column: `@.payDate` is the row's `payDate`.
- A value in `"double quotes"` is a Rachana-EL expression (operators, functions). A bare path such as
  `$.tradeId` needs no quotes.
- `${…}` inside text (titles, pills) embeds an expression in a template.
- `#` starts a YAML comment, which the engine ignores.

The fragment of the document that the Sutra reads looks like this (abridged; the full document is
`GET /api/v1/entities/trade/MX-20000001/raw`):

```json
{
  "tradeId": "MX-20000001", "productType": "IRS_FIXFLOAT", "productName": "Interest rate swap (fixed/float)",
  "assetClass": "Rates", "status": "Live", "direction": "Receive fixed", "currency": "AUD",
  "notional": 242000000, "mtm": 1875863, "pnl1d": 126986, "maturityDate": "2032-06-25",
  "counterparty": { "id": "CP-MERIDIAN-RE", "name": "Meridian Reinsurance Ltd" },
  "book": "BOOK-RATES-3", "nettingSet": "NS-MERIDIAN-RE-NY", "discountCurve": "CRV-AUD-OIS",
  "terms": { "fixedRate": 0.040829, "payFrequency": "Annual", "dayCount": "ACT/365F", "businessDay": "Following" },
  "risk": { "dv01": -155245 },
  "sensitivities": [ { "bucket": "3M", "dv01": -5544 }, … ],
  "legs": [ { "leg": 1, "label": "Receive fixed 4.0829%", "type": "FIXED", "payer": false, "rate": 0.040829,
              "cashflows": [ { "n": 1, "payDate": "2026-06-29", "amount": 8987301.85, "df": 1.0, "pv": 0.0, "status": "Settled" }, … ] },
            { "leg": 2, "label": "Pay AONIA compounded", "type": "FLOAT", "index": "AONIA", … } ],
  "schedule": [ { "payDate": "2025-09-29", "leg": 2, "amount": -1403091.13, "pv": 0.0, "status": "Settled" }, … ],
  "nextIndex": 6,
  "pnlHistory": [ { "date": "2026-09-03", "pnl": -15005 }, … ],
  "lifecycle": { "events": [ { "version": 1, "event": "New", "at": "2025-07-25T09:45:00Z", "by": "mo.ops" }, … ] },
  "confirmation": { "status": "Confirmed", "method": "MarkitWire", "matched": "2025-07-25 16:51" },
  "regulatory": { "uti": "5493…", "reportingStatus": "Accepted", "clearingObligation": true },
  "clearing": { "status": "Cleared", "ccp": "LCH SwapClear" }
}
```

```yaml
rachana: 1
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
  priority: 50                   # above the trading pack's irs-fixfloat (10), so this one would win

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
    description: The economic terms, as agreed at trade date.   # plain text for authors; not shown in the view
    columns:
      - { label: Trade date, bind: $.tradeDate, fmt: date }
      - { label: Effective, bind: $.effectiveDate, fmt: date }
      - { label: Day count, bind: $.terms.dayCount }
      - { label: Pay frequency, bind: $.terms.payFrequency }
      - { label: Discount curve, bind: "link($.discountCurve, 'ir-curve')" }   # links work in any cell
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

  # markdown: static notes for the people who read this view. The text is shown as written (plain text,
  # one paragraph); ${...} parts are evaluated, Markdown syntax is not rendered inside the panel.
  - id: notes
    kind: markdown
    title: Reading this view
    text: |
      MTM and DV01 are in USD whatever the trade currency (${$.currency} here). The payment schedule lists
      the unsettled flows of both legs; the highlighted row is the next payment.

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

  # gauge: one value against a maximum (a limit, a budget). The text is value / max in fmt (pct0 by default).
  - id: dv01use
    kind: gauge
    title: DV01 against the 250k desk guideline
    area: right
    value: "abs($.risk.dv01)"
    max: "250000"
    label: DV01 used

  # links: every entity this document refers to, resolved from the reference catalogue, with badges.
  - { id: refs, kind: links, title: Linked entities, code: REFS, area: right }

# ---- Function keys beyond the panel keys ------------------------------------------------------------------
keys:
  F7: "link($.nettingSet, 'netting-set')"        # F7 opens the netting set
  F8: impact                                     # F8: what depends on this trade
  F9: raw                                        # F9: the raw JSON (redacted for roles without raw)

# ---- Notes for authors and reviewers (plain text; the engine ignores them) ------------------------------------
notes: |
  Written to show the grammar, not for a desk. The priority is 50 so that a preview
  of MX-20000001 picks this layout over the trading pack's irs-fixfloat.

  description (top) and notes are prose for people; a panel's description says what that panel shows.
```
What each part does when the view is built:

| Part | What happens |
|---|---|
| `match` | The engine takes the `trade` Sutras, highest `priority` first, and uses the first whose `where` holds for `MX-20000001`. |
| `title`, `strip` | Evaluated once per document: each expression gives a value, which `fmt` formats and `tone` colours. |
| `rows`, `each` | Evaluated to a list; the panel's columns are evaluated once per element with `@` set to it. |
| `source` | Follows the link and reads the other entity (the curve) through the same sources and business date. |
| `key`, `keys` | Become the function-key bar; a panel key scrolls to the panel, a link key opens the entity. |
| Missing data | A path that is absent evaluates to nothing: the cell is empty, and a panel with no data says so. |

You should see, previewing this block against `MX-20000001` in Studio:

| Where | Shows |
|---|---|
| Title | `[Rates · Interest rate swap (fixed/float)] MX-20000001 with Meridian Reinsurance Ltd` (the name is a link to `CPTY CP-MERIDIAN-RE`) |
| Strip | `Notional 242,000,000`, `Fixed rate 4.0829%`, `MTM (USD) +1,875,863` (highlighted, positive colour) … `Book BOOK-RATES-3` (a link) |
| Terms | `Net PV of the legs +1,875,863`, `Flows left 29 of 35` |
| Cashflows | six of the seven fixed cashflows, a *Total* row, and `1 later cashflows` under it |
| Payment schedule | 29 unsettled flows, the first (`2026-12-30`, Float, `−2,253,619.99`) highlighted |
| DV01 against the 250k desk guideline | a bar at 62% with the text `62%` (155,245 / 250,000) |
| Function keys | `F2 Terms`, `F3 Legs`, `F4 Cashflows`, `F5 Discount curve`, `F7 Netting set`, `F8 Impact`, `F9 Raw JSON` |

## Top level

| Key | Required | Type | Default | Meaning |
|---|---|---|---|---|
| `rachana` | yes | integer | | The Rachana language version: `1`. Missing or another number is `DRS-2009`. Write it first. |
| `sutra` | yes | text | | Name: lower-case kebab matching `[a-z][a-z0-9-]{1,63}` (2–64 characters, starting with a letter), e.g. `irs-vanilla`. |
| `version` | yes | integer ≥ 1 | | `name@version` is unique across all directories (`DRS-2028`). `version: "3"` (quoted) is text and is refused (`DRS-2020`). |
| `description` | | text | | One paragraph for people: what the layout shows and for which entities. Not used to build the view. |
| `notes` | | text | | Longer plain-text notes for authors and reviewers (`notes: \|` for several lines). Not used to build the view. |
| `domain` | | text | the parent folder's name | Grouping in Studio and the catalogue, and the folder Studio saves into. |
| `match` | yes | mapping | | `{kind, where?, priority?}`; see [Matching](#matching-choosing-a-sutra-for-a-document). |
| `title` | | mapping | `{id: $.id}` | `{pill?, id?, with?}`; see [Title](#title). |
| `strip` | | list | empty | Up to **8** header figures; see [Strip](#strip). |
| `panels` | | list | empty | The panels, in order; see [Panels](#panels). |
| `keys` | | mapping | empty | Function key → action; see [Function keys](#function-keys). |

Any other top-level key is `DRS-2011` (`unknown key 'pannels' in top level`). A Sutra with no panels is valid: the
view then shows the title and strip only.

## Names, versions and domains

- A Sutra is identified by `name@version`, for example `irs-fixfloat@1`. Studio, the API
  (`GET /api/v1/sutras/irs-fixfloat/1`) and the governance log all use this pair.
- **Several versions of one name can be loaded at once** (`irs-vanilla.v2.sutra.yaml` and
  `irs-vanilla.v3.sutra.yaml`). Only the **latest** version of each name takes part in matching; older versions
  stay available by `name@version` (Studio, `GET /api/v1/sutras/{name}/{version}`), so a reference to an
  older layout can still be reproduced. To roll back, delete or rename the newer file, or publish a higher
  version with the old content.
- `GET /api/v1/sutras` lists the latest version of every name with all its versions:

  ```bash
  curl -s http://localhost:18480/api/v1/sutras | python3 -c "
  import json, sys
  for s in json.load(sys.stdin):
      if s['name'].startswith('irs'): print(s)"
  ```

  You should see entries such as
  `{'name': 'irs-fixfloat', 'latest': 1, 'versions': [1], 'domain': 'rates', 'kind': 'trade', 'where': "$.productType == 'IRS_FIXFLOAT'", 'priority': 10}`.
- `GET /api/v1/sutras/{name}/{version}/source` returns the file's YAML (`text/yaml`), comments included, exactly as loaded.
- The **domain** defaults to the name of the folder holding the file (`packs/trading/sutras/rates/…` → `rates`).

### Where Sutras come from, and who wins

| Source | Setting | Order |
|---|---|---|
| Site directories | `drishti.rachana.dirs` (default `./sutras`, env `DRISHTI_SUTRAS`) | scanned first |
| Enabled packs | each pack's `sutras/` folder (or its `sutras:` key), added by the pack loader as `drishti.rachana.pack-dirs` | after the site, most general pack first |

Directories are scanned recursively; files are read in sorted path order. When two files define the same
`name@version`:

| Both in… | Result |
|---|---|
| two packs | the more specific pack (later in the pack order) **overrides** the general one, and the log says `sutra trade-x@1 from … overrides the one in …`. |
| a site directory and a pack | the site file is kept; the pack's file is reported as `DRS-2028 … is already defined in <site file>`. |
| two site files | the first in path order is kept; the other is reported as `DRS-2028`. |

## Matching: choosing a Sutra for a document

```yaml
match:
  kind: trade                                   # required: the entity kind (the mnemonic's kind, e.g. TRD → trade)
  where: "$.productType == 'IRS_FIXFLOAT'"      # optional Rachana-EL predicate over the document
  priority: 10                                  # optional integer, default 0; higher is tried first
```

`SutraMatcher` does exactly this for every view:

1. Take the latest version of every Sutra whose `match.kind` equals the entity's kind.
2. Sort them by `priority`, highest first; Sutras with equal priority are taken in name order (`a` before `b`).
3. Evaluate each `where` against the document, in that order. The first that is truthy wins. A Sutra without
   `where` always matches, so it is the catch-all for its kind; give it a low priority.
4. If none matches, the view is built by inference alone (`Layout: inference only`).

There is no separate classifier file: `match` is the classifier. There is no automatic "specificity": a Sutra
with a longer `where` does not win by itself, only `priority` decides the order.

Recommended priorities, as the shipped packs use them:

| Priority | Use |
|---|---|
| 0–1 | catch-alls (no `where`), and drafts started from inference (Studio writes `priority: 1`) |
| 10 | the normal product Sutras of a pack (`where: "$.productType == 'IRS_FIXFLOAT'"`) |
| 20–50 | special cases that must beat the normal Sutra (a desk's own variant, a matured-trade layout) |

Example: two layouts for swaps, one for matured trades.

```yaml
# irs-matured.v1.sutra.yaml                    # tried first (priority 20)
match: { kind: trade, where: "$.productType == 'IRS_FIXFLOAT' && $.status == 'Matured'", priority: 20 }

# irs-fixfloat.v1.sutra.yaml (trading pack)    # tried second (priority 10)
match: { kind: trade, where: "$.productType == 'IRS_FIXFLOAT'", priority: 10 }
```

`MX-20000001` has `"status": "Live"`, so `irs-matured` does not hold and `irs-fixfloat` is used. To check which
Sutra a document gets:

```bash
curl -s http://localhost:18480/api/v1/views/trade/MX-20000001 | python3 -c "import json,sys; print(json.load(sys.stdin)['provenance']['layout'])"
```

You should see `Sutra irs-fixfloat v1 + inference`.

Things to know:

- `where` is evaluated for every view (and on every live tick), so keep it cheap: compare fields, avoid long filters.
- `where` sees only `$` (there is no row). Comparing a missing field gives false, never an error.
- `priority` must be a YAML integer; any other value (`priority: high`, `priority: "10"`) is read as 0
  without a warning.

## Title

```yaml
title:
  pill: "${$.assetClass} · ${$.productName}"   # template text (optional)
  id: $.tradeId                                # expression (optional, default $.id)
  with: "link($.counterparty.id, 'counterparty', $.counterparty.name)"   # expression (optional)
```

| Key | Kind of value | Default | Shown as |
|---|---|---|---|
| `pill` | template: plain text with `${expression}` parts | none | the small box before the identifier |
| `id` | expression | `$.id` | the large identifier; when it evaluates to empty, the entity's own id is shown |
| `with` | expression | none | `with <value>` after the identifier; a `link(...)` value is clickable |

Every part is best effort: if one fails to evaluate on a document, that part is left out and the view still
opens. Note that `pill` is not compiled at load time (only `id` and `with` are), so a typo in a pill template
shows up as a missing pill rather than a `DRS-2101`. Unknown keys under `title` are `DRS-2011`.

## Strip

The strip is the row of up to eight headline figures under the title.

```yaml
strip:
  - { label: MTM (USD), bind: $.mtm, fmt: signed0, tone: sign, emphasis: true }
```

| Key | Required | Meaning |
|---|---|---|
| `bind` | yes | Expression for the value. |
| `label` | | Plain text (not a template). When absent, see [Labels](#labels). |
| `fmt` | | A [format](#formats) name. Unknown names fall back to plain text. |
| `tone` | | A [tone](#tones). |
| `emphasis` | | `true` highlights the figure (one per strip is the convention). |

More than eight items is `DRS-2026`. An item whose expression fails on a document (a field of the wrong
type) shows empty instead of failing the view. Each strip cell carries the document path it reads
(`"path": "$.mtm"`), which live updates use to flash the cell that changed.

## Labels

Every strip item and column may give a `label`. When it does not, the label is the name of the field it reads
(the last segment of the first path in the expression): `bind: $.regulatory.uti` reads as **UTI**, `@.payDate` as
**Pay date**. Where a label comes from, first match wins:

1. `label:` in the Sutra;
2. the **pack taxonomy**: `labels:` in the semantics file of an enabled pack (`packs/<pack>/config/semantics.yaml`),
   e.g. `labels: { mtm: MTM (USD), tradeId: Trade }`; a more specific pack wins over a general one;
3. the **global taxonomy**: `labels:` in the core semantics (or the site's replacement,
   `drishti.inference.semantics-file`);
4. the field name in words, spelled with the packs' and the core's `acronyms:` (`dv01ByTenor` → *DV01 by tenor*).

An expression that reads no field (`"'Fixed'"`) gets the expression text itself as its label, so give such
columns a label. Inference labels the fields it lays out the same way.

## Panels

A panel is one box of the view. Panels are drawn in the order written, in the main column, or in the side
column with `area: right`.

### Keys every panel takes

| Key | Required | Default | Meaning |
|---|---|---|---|
| `id` | yes (except a `tabs` body) | | Unique within the Sutra (`DRS-2024`). Used by function keys, live patches and CSV export. Any text; keep it short and kebab or camel case. |
| `kind` | yes | | One of the 13 [panel kinds](#panel-kinds) (`DRS-2021` otherwise). |
| `title` | | none | A template: `"Cashflows · ${$.legs[0].label}"`. If it cannot be rendered for a document it is shown as written. |
| `key` | | none | A function key `F1`–`F12`, unique across panels and `keys` (`DRS-2025`). Pressing it scrolls to the panel and flashes it. Avoid `F1`, which the console uses for help. |
| `code` | | none | A short tag shown at the right of the panel header (`CRV`, `SENS`). |
| `area` | | `main` | `main` or `right` (any case; anything else is `DRS-2027`). |
| `infer` | | `false` | Marks the panel as completed by inference (an *inferred* tag in its header). Inference fills a panel's columns whenever it states none, with or without this flag; see [Inference](../architecture/INFERENCE.md#sutra-and-inference-together). |
| `columns` | | empty | The columns or fields; see below. Used by `kv`, `table`, `ladder`, `tabs` (in the body) and `surface`. |
| `body` | `tabs` only | | The panel drawn once per tab. On any other kind, `DRS-2023 only 'tabs' panels take a 'body'`. |
| `description` | | none | Plain text for authors: what the panel shows. Not shown in the view. |

Every other key of a panel is a **kind option**. Each kind accepts a fixed set (tables below); any other key
is `DRS-2023 option 'x' is not valid for 'kv' panels`, and a missing required option is
`DRS-2022 'table' panel 'flows' needs option 'rows'`.

### Columns

```yaml
columns:
  - { label: Amount, bind: "@.amount", fmt: signed2, tone: sign, total: true }
  - { label: Trade, bind: "@.trade", link: true }
```

| Key | Required | Meaning |
|---|---|---|
| `bind` | yes | Expression. Inside `rows`/`each`, `@` is the row and `#index` its position. |
| `label` | | Plain text header or field label. When absent, see [Labels](#labels). |
| `fmt` | | Format name. In tables, a column with a format other than `date` or `text` is right-aligned as numeric. |
| `tone` | | Tone. |
| `total` | | `true` adds the column into the total row (`table`, `ladder`). |
| `link` | | `true` makes the value a link when it is an identifier a pack recognises (see [Links](#links)). |

Any other key in a column is `DRS-2011 unknown key 'x' in column`.

### Panel kinds

There are 13 kinds (`PanelKind`). Required and accepted options, at a glance:

| Kind | Required options | Optional options | Renders | Uses `columns` |
|---|---|---|---|---|
| [`kv`](#kv) | | `rows`, `columns`, `fields` | label/value grid | yes |
| [`table`](#table) | `rows` | `totalLabel`, `limit`, `moreLabel`, `link` | table with optional total row and "N more" | yes |
| [`tabs`](#tabs) | `each` | `tabTitle`, `layout` | one sub-panel per element, as tabs or side by side | in `body` |
| [`line`](#line) | | `rows`, `source`, `x`, `y`, `mark`, `footer`, `unit`, `fmt` | line chart | no |
| [`area`](#area) | `rows` | `x`, `series`, `limit`, `limitLabel`, `unit` | area chart, several series, dashed limit | no |
| [`hbar`](#hbar) | `rows` | `label`, `value`, `fmt`, `tone` | horizontal bars | no |
| [`ladder`](#ladder) | `rows` | `totalLabel`, `highlight` | table with highlighted rows | yes |
| [`links`](#links-panel) | | | linked entities with badges | no |
| [`status`](#status) | | `fields` | label/value grid, coloured by meaning | no (uses `fields`) |
| [`provenance`](#provenance) | | | how the view was built | no |
| [`markdown`](#markdown) | `text` | | static text | no |
| [`gauge`](#gauge) | `value` | `max`, `label`, `fmt` | one value as a bar against a maximum | no |
| [`surface`](#surface) | `rows`, `y` | `fmt`, `unit`, `view` | heatmap with a 3D toggle | yes (one per x point) |

Which option values are expressions, which are field names, and which are plain text matters: only
expressions and templates are compiled at load time.

| Kind | Expressions (Rachana-EL) | Field names (of each row) | Plain values |
|---|---|---|---|
| `kv` | `rows` | | |
| `table` | `rows`, `moreLabel` | | `limit` (integer), `totalLabel`, `search` (`false` hides the filter) |
| `tabs` | `each`, `tabTitle` | | `layout` |
| `line` | `rows`, `source`, `mark` | `x`, `y` | `unit`, `fmt` |
| `area` | `rows`, `limit` | `x`, each series' `value` | `series[].label`, `series[].tone`, `limitLabel`, `unit` |
| `hbar` | `rows` | `label`, `value` | `fmt`, `tone` |
| `ladder` | `rows`, `highlight` | | `totalLabel`, `search` (`false` hides the filter) |
| `status` | each field's `bind` | | each field's `label`, `fmt`, `tone` |
| `gauge` | `value`, `max` | | `label`, `fmt` |
| `surface` | `rows`; `y` when it starts with `$` or `@` | `y` otherwise | `fmt`, `unit`, `view` |
| `markdown` | | | `text` is a template |

Accepted but currently without effect (they parse, and do nothing yet): `fields` on `kv` (use `columns`),
`link` on `table` (use `link: true` on a column), `footer` on `line`, and `label` on `gauge` (carried in the
view data, not drawn by the console).

When a panel's data does not fit (its `rows` is not a list, a chart's source is unavailable), the panel shows
*No data available* with the reason in a tooltip, and the rest of the view is unaffected.

#### kv

A grid of label/value pairs, four across in the main column and two across on the right.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `columns` | | | The fields: each `bind` is evaluated against the document, or against `rows` when given. |
| `rows` | | | Expression for one object; inside the columns `@` is that object. |
| `fields` | | | Accepted, no effect (see above). |

Without `columns` but with `rows`, inference lists every scalar field of the object (up to 16), and a nested
object that has a `name` shows that name. Without either, the panel is empty.

```yaml
  - id: terms
    kind: kv
    title: Terms
    code: TRM
    key: F2
    columns:
      - { label: Fixed rate, bind: $.terms.fixedRate, fmt: pct4 }
      - { label: Day count, bind: $.terms.dayCount }
      - { label: Effective, bind: $.effectiveDate, fmt: date }
      - { label: Counterparty, bind: $.counterparty.name }

  - { id: clearing, kind: kv, title: Clearing, area: right, rows: $.clearing }   # fields inferred from the object
```

You should see, for `MX-20000001`: `Fixed rate 4.0829%`, `Day count ACT/365F`, `Effective 2025-07-28`,
`Counterparty Meridian Reinsurance Ltd`. In the view JSON a kv panel's data is
`{"fields": [{"label": "Fixed rate", "text": "4.0829%", "path": "$.terms.fixedRate"}, …]}`.

#### table

One row per element of `rows`.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `rows` | yes | | Expression giving a list. Anything else shows *No data available*. |
| `columns` | | inferred | One per column. Without columns, inference picks the scalar fields present in at least 60% of rows (up to 9). |
| `limit` | | all rows | Integer: rows shown. The rest are counted in the "more" line. |
| `moreLabel` | | `"<N> more"` | Expression for the text of the "more" line, evaluated against the document (not a row). |
| `totalLabel` | | `Total` | Text of the total row's label cell. |
| `link` | | | Accepted, no effect (see above). |
| `search` | | `true` | `false` hides the filter box (and the per-column filters) in the panel's heading, for a table too small or too fixed to need one. Table and ladder panels take it; on any other kind it is a problem (`DRS-2023`, *option 'search' applies only to panels that show a table*). |

Totals: when at least one column says `total: true`, a total row is added. It sums that column's numbers over
**all** rows, including those hidden by `limit` (values that are not numbers count as 0), formats the sum with the
column's `fmt` and `tone`, and puts `totalLabel` in the column just before the first totalled column (or the
first column when the first totalled column is the first).

```yaml
  - id: trades
    kind: table
    title: Member trades
    code: TRD
    key: F3
    rows: $.trades
    limit: 4
    moreLabel: "(size($.trades) - 4) + ' more trades'"
    totalLabel: Net
    columns:
      - { label: Trade, bind: "@.tradeId" }                 # an id column: linked automatically
      - { label: Product, bind: "@.product" }
      - { label: Notional, bind: "@.notional", fmt: compact }
      - { label: Maturity, bind: "@.maturity", fmt: date }
      - { label: MTM, bind: "@.mtm", fmt: signed0, tone: sign, total: true }
```

This is the counterparty-risk pack's *Member trades* table with a limit and a net row added. You should see, for
netting set `NS-MERIDIAN-RE-NY`, rows such as
`MX-20000007 · Overnight index swap · 199.0m · 2034-06-26 · −68,527,810`, the trade ids as links, a `Net` row, and
the "more trades" line. The data is
`{"columns": [...], "numeric": [false, false, true, false, true], "rows": [{"cells": [...], "highlight": false, "path": "$.trades[0]"}], "total": {...}, "more": "…"}`.

#### tabs

One sub-panel per element of `each`, as tabs or side by side.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `each` | yes | | Expression giving a list. Inside the body, `@` is one element and `#index` its position. |
| `tabTitle` | | `'#' + (#index + 1)` | Expression for each tab's title. |
| `layout` | | `tabs` | `tabs` (one visible at a time) or `columns` (all side by side, for two or three legs). |
| `body` | | | A nested panel: `kind` is required (use `kv`), `id` is optional (`body`); only its `columns` are used. |

The body is validated like any panel, so a body of `kind: table` would need `rows` (`DRS-2022`); write it as
`kind: kv`. A body without columns gets the fields of the **first** element, inferred.

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
        - { label: Pay/receive, bind: "@.payReceive" }
        - { label: Rate, bind: "@.rate", fmt: pct4 }
        - { label: Spread, bind: "@.spread", fmt: bp1 }
        - { label: Notional, bind: "@.notional", fmt: amount0 }
```

You should see two boxes, `Leg 1 · Receive fixed 4.0829%` and `Leg 2 · Pay AONIA compounded`; the floating
leg's `Rate` is empty (it has none) and both show `Spread 0.0 bp`. With `layout: tabs`, the selected tab is kept
when a live update repaints the panel.

#### line

A line chart: one point per row.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `rows` | | `points` | Expression giving the list of points. |
| `x` | | `tenor` | Field of each row for the x axis (shown as text). |
| `y` | | `value` | Field of each row for the y value (a number). |
| `source` | | | Expression giving a `link(id, kind)`. The linked entity is fetched (same sources, same business date) and `rows` is evaluated against **its** document. |
| `mark` | | | Expression (against the main document) giving one x value to highlight; its value is written under the chart: `5Y point 4.03%`. |
| `fmt` | | | Format of the mark's value. |
| `unit` | | | Unit for the axis and the mark text; `%` is appended without a space, anything else after a space. |
| `footer` | | | Accepted, no effect. |

The series is named after the panel title. When `source` names an entity that has not arrived within the link
budget, the panel shows *No data available* (`waiting for CRV-AUD-OIS`) and fills in on the next build; when it
does not exist, `CRV-AUD-OIS unavailable`. The source entity is shown as a link under the chart.

```yaml
  - id: marketData
    kind: line
    title: "Interest rate curve: Zero rates (%)"
    code: CRV
    key: F4
    area: right
    source: "link($.discountCurve, 'ir-curve')"
    rows: points                    # a bare name: a field of the curve document
    x: tenor
    y: zeroRate
    fmt: price2
```

You should see (this is the trading pack's `irs-fixfloat`) twelve points from `1M` (3.5837) to `30Y` (4.2533),
and a `CRV-AUD-OIS` link under the chart. The data is
`{"x": ["1M", …], "series": [{"label": "Interest rate curve: Zero rates (%)", "values": [3.5837, …], "tone": "link"}], "source": {"kind": "ir-curve", "id": "CRV-AUD-OIS", "mnemonic": "CRV"}}`.

#### area

An area chart with one or more series over a common x axis, and an optional dashed limit line (exposure
profiles).

| Option | Required | Default | Meaning |
|---|---|---|---|
| `rows` | yes | | Expression giving the list of points. |
| `x` | | `tenor` | Field of each row for the x axis. |
| `series` | | none | A list of `{label, value, tone}`: `value` names a field of each row; `tone` colours the series (`link`, `accent`, `pos`, `neg`). Without `series` the chart is empty. |
| `limit` | | | Expression for a number drawn as a dashed horizontal line. |
| `limitLabel` | | `Limit` | Legend text for the limit line. |
| `unit` | | | Unit for the axis. |

A legend is shown when there are two or more series or a limit.

```yaml
  - id: exposure
    kind: area
    title: Exposure profile
    code: EXP
    key: F2
    rows: $.profile
    x: tenor
    limit: $.limit
    limitLabel: Credit limit
    series:
      - { label: Expected exposure, value: ee, tone: link }
      - { label: PFE 95, value: pfe, tone: accent }
```

You should see, for netting set `NS-MERIDIAN-RE-NY`, two filled series over `0, 1M, 3M … 10Y` and a dashed line
at 71,000,000. The data is `{"x": [...], "series": [{"label": "Expected exposure", "values": [...], "tone": "link"}, …], "limit": 71000000.0}`.

#### hbar

Horizontal bars, one per row, scaled to the largest absolute value.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `rows` | yes | | Expression giving the list. |
| `label` | | `label` | Field of each row for the bar's label. |
| `value` | | `value` | Field of each row for the bar's number. Rows whose value is not a finite number are skipped. |
| `fmt` | | plain | Format of the number written beside each bar. |
| `tone` | | by sign | Tone of each bar; without it, negative bars are `neg` and the others `pos`. |

```yaml
  - id: sensitivities
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

You should see seven bars, `3M −5,544` to `7Y −38,811`, all in the negative colour.

#### ladder

A table whose rows can be highlighted: settlement ladders, lifecycle events, the next payment.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `rows` | yes | | Expression giving the list. |
| `columns` | | inferred | As for `table`. |
| `highlight` | | none | Expression evaluated per row (`@`, `#index` available); rows where it is truthy are highlighted. |
| `totalLabel` | | `Total` | As for `table`; columns with `total: true` are summed. |

A ladder does not accept `limit` or `moreLabel` (`DRS-2023`): it always shows every row.

```yaml
  - id: schedule
    kind: ladder
    title: Cashflows
    code: CF
    key: F3
    rows: $.schedule
    highlight: "#index == $.nextIndex"          # the document says which flow is next
    columns:
      - { label: Pay date, bind: "@.payDate", fmt: date }
      - { label: Leg, bind: "@.leg" }
      - { label: Amount, bind: "@.amount", fmt: signed0, tone: sign }
      - { label: PV, bind: "@.pv", fmt: signed0, tone: sign, total: true }
```

You should see 35 flows with row 7 (`#index` 6, `2026-12-30`) highlighted, and a total row whose PV is
`+1,875,863`.

#### links panel

Every entity the document refers to, in document order, without duplicates and without the entity itself. No
options. Which fields count as references, and the kind each points to, comes from the packs' `graph.fields`
(for example `counterparty → counterparty`, `book → book`); a field may hold an id, a list of ids, or an object
with `id` (and `name`, which becomes the text). Each item is fetched within the link budget and shows a
**badge** from the pack's `graph.badges` (a counterparty's rating, a netting set's PFE).

```yaml
  - { id: refs, kind: links, title: Linked entities, code: REFS, area: right }
```

You should see, for `MX-20000001`, items such as `Counterparty CP-MERIDIAN-RE A`, `Netting set NS-MERIDIAN-RE-NY PFE 46.0m`,
`Book BOOK-RATES-3`, `Trader TRDR-ASHAH`, `Desk DESK-RATES`. An item that did not arrive in time says
`pending`; one that does not exist says `missing`. A Sutra with a links panel always reports
`+ inference` in its layout label, because the links are discovered rather than declared.

#### status

Operational fields (confirmation, clearing, settlement), drawn like a kv grid.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `fields` | | none | A list of `{label, bind, fmt?, tone?}`. Each `bind` is an expression against the document. |

Always give each field a `label` (a field without one is labelled `null`). `tone: status` colours by meaning:
text containing *fail*, *reject*, *dispute* or *breach* is `bad`; *pending*, *unmatched* or *warn* is `warn`; any
other non-empty text is `ok`.

```yaml
  - id: operations
    kind: status
    title: Confirmation and clearing
    code: OPS
    fields:
      - { label: Confirmation, bind: $.confirmation.status, tone: status }
      - { label: Platform, bind: "$.confirmation.method + ' · ' + $.confirmation.platformId" }
      - { label: Clearing, bind: "$.clearing.status + ' at ' + $.clearing.ccp", tone: status }
```

You should see `Confirmation Confirmed` and `Clearing Cleared at LCH SwapClear` in the `ok` colour, and
`Platform MarkitWire · MA805672813`.

#### provenance

*How this view was built*. No options.

```yaml
  - { id: built, kind: provenance, title: How this view was built }
```

You should see three lines: `Layout Sutra irs-fixfloat v1 + inference`, `Fingerprint b7df…1372` (a short form of
the document's shape fingerprint) and `Source murex-rates, gen 1` (the source and the document's generation,
which goes up on every live tick).

#### markdown

Static text for the reader.

| Option | Required | Meaning |
|---|---|---|
| `text` | yes | A template: `${expression}` parts are evaluated against the document. |

The text is shown as one plain paragraph: Markdown syntax (`**bold**`, lists) is **not** rendered inside the
panel, and line breaks become spaces. The `markdown` panel is for the people who *read* the view; notes for the
people who *maintain* the Sutra belong in its top-level `notes` (or a panel's `description`), which are never
shown in the view.

```yaml
  - id: notes
    kind: markdown
    title: Reading this view
    text: |
      Amounts are in ${$.currency}; MTM and DV01 are in USD. The highlighted cashflow is the next payment.
```

You should see `Amounts are in AUD; MTM and DV01 are in USD. The highlighted cashflow is the next payment.`

#### gauge

One value against a maximum, as a filled bar and a text.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `value` | yes | | Expression for the value. |
| `max` | | `1` | Expression for the maximum. |
| `fmt` | | `pct0` | Format applied to **value ÷ max** (not to the value). |
| `label` | | | Carried in the data; not drawn by the console at present. Put the meaning in the title. |

The bar's width is value ÷ max. When the value is not a number, the text is `—`.

```yaml
  - id: dv01use
    kind: gauge
    title: DV01 used of the 250k guideline
    area: right
    value: "abs($.risk.dv01)"
    max: "250000"
```

You should see a bar 62% full with the text `62%`. With `max` omitted, a ratio already in the document
(`value: $.utilisation`) reads directly: 0.83 shows as `83%`. Do not give a value format such as `compact`
here: it would format the ratio (`0.6`).

#### surface

A grid of values over two axes (volatility surfaces, correlation matrices): each row of `rows` is one y point,
each column one x point. Drawn as a heatmap, with a button that turns it into a rotatable 3D surface.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `rows` | yes | | Expression giving the list of y points. |
| `y` | yes | | The y label of each row: a field name (`expiry`), or an expression when it starts with `@` or `$`. |
| `columns` | | | One per x point: `label` is the x label, `bind` the cell value (non-numbers leave a gap). |
| `fmt` | | | Format of cell values. |
| `unit` | | | Unit shown with values. |
| `view` | | `heatmap` | `heatmap` or `3d` (which view opens first). |

```yaml
  - id: surface
    kind: surface
    title: Implied vol by expiry × moneyness (%)
    code: SURF
    key: F4
    rows: $.grid
    y: expiry
    columns:
      - { label: "80%", bind: "@.m80", fmt: price2 }
      - { label: "100%", bind: "@.m100", fmt: price2 }
      - { label: "120%", bind: "@.m120", fmt: price2 }
```

You should see (market-data pack, `EQV EQV-CSCA`) a heatmap with expiries `1M … 5Y` down the side and moneyness
across. The data is `{"x": ["80%", …], "y": ["1M", …], "z": [[43.17, …], …], "min": 33.42, "max": 43.17, "view": "heatmap"}`.

## Function keys

Function keys come from two places:

1. a panel's `key:` scrolls to that panel and flashes it; its label on the key bar is the first one or two words
   of the panel title, cut at ` · ` or ` (` (`Cashflows · Receive fixed…` → `Cashflows`);
2. the top-level `keys:` mapping, for actions that are not panels.

```yaml
keys:
  F7: "link($.nettingSet, 'netting-set')"   # open another entity
  F8: impact                                # what depends on this entity
  F9: raw                                   # the raw JSON drawer (redacted for roles without raw access)
  F10: schedule                             # any other text: the id of a panel to scroll to
```

| Action | Key bar label | Does |
|---|---|---|
| `raw` | `Raw JSON` | toggles the raw JSON drawer (Esc closes it) |
| `impact` | `Impact` | opens the impact view of this entity |
| `link(id, kind?, label?)` | the kind in words (`Netting set`) | opens the entity; when the id is empty or its kind cannot be told, the key is left out |
| anything else | the text itself | treated as a panel id to scroll to (not checked at load: a wrong id gives a key that does nothing) |

Keys are `F1` to `F12`, unique across panels and `keys` (`DRS-2025`), and shown in F-number order. `F1` opens the
console's help whatever the Sutra says, so do not use it. Inference gives `F2`–`F6` to its first five main
panels and `F9: raw`.

## Links

A Sutra makes something navigable in three ways:

| How | Where | Example |
|---|---|---|
| `link(id, kind?, label?)` | any expression: title `with`, strip, columns, status fields, `keys`, a line's `source` | `bind: "link($.book, 'book')"` |
| `link: true` on a column | a column whose value is an identifier | `{ label: Trade, bind: "@.trade", link: true }` |
| automatic | a column bound to a plain path whose last field ends in `Id`, `Ref` or `_id` | `{ label: Trade, bind: "@.tradeId" }` |

How the target kind is found: `link(id, 'book')` names it. `link(id)` without a kind, `link: true` and the
automatic case ask the reference catalogue, which matches the id against the enabled packs'
`graph.id-patterns` (for example `^CP-` → `counterparty`). When no pattern matches, the value is shown as plain
text.

The automatic rule (`Binder.namesAnId`) applies when the `bind` is a plain path (`@.x.y`, `$.x[0].y`; no
operators, no functions) whose last field name is longer than two characters and ends in `Id`, `Ref` or
`_id`: `@.tradeId`, `$.counterpartyId`, `@.bookRef`, `@.trade_id`. `@.id` does not qualify (too short); write
`link: true` for it, or for any field named otherwise (`@.trade`, `@.from`).

## Formats

`fmt` (and the `fmt(value, 'name')` function) names a format. Formats are loaded once at start-up in this order,
later definitions replacing earlier ones of the same name:

1. the core `formats.yaml` bundled in `drishti-rachana`;
2. each enabled pack's `config/formats.yaml` (or the file named by its `formats:` key), most general pack first;
3. the site file `drishti.rachana.formats-file`, if it exists.

An unknown format name is not an error: the value is shown as plain text.

### Core formats

| Name | Definition | `1234567.891` | `-0.040829` | Other |
|---|---|---|---|---|
| `text` | as text | `1234567.891` | `-0.040829` | |
| `amount0` | 0 decimals, grouped | `1,234,568` | `−0` → `0` (zero after rounding has no sign) | |
| `amount2` | 2 decimals, grouped | `1,234,567.89` | `−0.04` | |
| `signed0` | 0 decimals, `+` on positives | `+1,234,568` | `0` | `0` → `0` |
| `signed2` | 2 decimals, `+` on positives | `+1,234,567.89` | `−0.04` | |
| `pct0` | × 100, 0 decimals, `%` | `123,456,789%` | `−4%` | `0.83` → `83%` |
| `pct2` | × 100, 2 decimals, `%` | | `−4.08%` | |
| `price2` | 2 decimals, grouped | `1,234,567.89` | `−0.04` | |
| `compact` | k / m / bn, 1 decimal | `1.2m` | `−0.0` | `46000000` → `46.0m`, `2.5e9` → `2.5bn`, `999` → `999.0` |
| `date` | `yyyy-MM-dd` | | | text is shown as is, time included (`2025-07-25T09:45:00Z`) |
| `dmy` | `dd MMM yyyy` | | | `2026-09-30` → `30 Sep 2026` (the first ten characters are read as a date) |

Negative numbers use the true minus sign `−` (U+2212). Rounding is half-up. A text value that is a number
(`"12.5"`) is formatted as a number; any other text is shown as is.

### Formats added by the packs

| Name | Definition | Example | Packs |
|---|---|---|---|
| `pct4` | × 100, 4 decimals, `%` | `0.040829` → `4.0829%` | finance, banking-core, trading, market-data, market-risk, counterparty-risk |
| `rate5` | 5 decimals, no grouping | `1.08345` → `1.08345` | same |
| `pips1` | 1 decimal, `+` on positives | `12.34` → `+12.3` | same |
| `df4` | 4 decimals | `0.991063` → `0.9911` | same |
| `bp1` | 1 decimal, `+` on positives, ` bp` | `2.5` → `+2.5 bp`, `0` → `0.0 bp` | same |
| `temp1` | 1 decimal, ` °C` | `4.25` → `4.3 °C` | logistics |
| `hours0` | 0 decimals, `+` on positives, ` h` | `14` → `+14 h` | logistics |
| `knots1` | 1 decimal, ` kn` | `18.4` → `18.4 kn` | logistics |

### Defining formats

A format file has one `formats:` mapping. Keys of a format:

| Key | Default | Meaning |
|---|---|---|
| `type` | `number` | `number`, `compact`, `date` or `text` (anything else is treated as `number`) |
| `decimals` | `0` | fraction digits (`number`, `compact`) |
| `grouping` | `true` | thousands separators |
| `sign` | `auto` | `auto` (minus only) or `always` (also `+` on positives; never on zero) |
| `scale` | `1` | multiplier applied first (`100` for a percentage of a fraction) |
| `suffix` | none | appended text (`"%"`, `" bp"`) |
| `pattern` | none | `date` only: a Java date pattern; `yyyy-MM-dd` or none shows the text unchanged |

A pack adds formats in `packs/<pack>/config/formats.yaml`:

```yaml
formats:
  bp1:   { type: number, decimals: 1, sign: always, suffix: " bp" }
  pct1:  { type: number, decimals: 1, scale: 100, suffix: "%" }
  usdm:  { type: number, decimals: 1, scale: 0.000001, suffix: " m" }   # 242000000 → 242.0 m
  month: { type: date, pattern: "MMM yyyy" }                           # 2026-09-30 → Sep 2026
```

A site does the same in its own file and points `drishti.rachana.formats-file` at it. Format files are read
at start-up only; restart the server after changing one.

## Tones

`tone` colours a value through theme tokens only, and the console always pairs colour with a sign or glyph.

| Tone | Colour class | When |
|---|---|---|
| `sign` | `pos` or `neg` | by the number's sign; zero, empty or non-numbers get no colour |
| `status` | `ok`, `warn` or `bad` | by the text: *fail*, *reject*, *dispute*, *breach* → `bad`; *pending*, *unmatched*, *warn* → `warn`; other non-empty text → `ok` (case-insensitive) |
| `pos`, `neg`, `link`, `accent`, `ok`, `warn`, `bad` | that class | always |

An unknown tone name is not an error; the value is simply not coloured. Totals use their column's tone; `hbar`
without `tone` colours by sign.

## Totals, limits and "more"

| Option | Kinds | Effect |
|---|---|---|
| column `total: true` | `table`, `ladder` | adds a total row summing that column over all rows (hidden ones included) |
| `totalLabel` | `table`, `ladder` | label of the total row (default `Total`), placed before the first totalled column |
| `limit` | `table` | rows shown; must be a YAML integer (`limit: "6"` is ignored) |
| `moreLabel` | `table` | expression for the line under the table when rows are hidden; default `<N> more` |

## Rachana-EL expressions

Bindings, conditions and templates are written in **Rachana-EL**, a small expression language. It is closed
(no loops, no assignment, no access to anything but the document), side-effect free and **total**: evaluating
an expression never throws on odd data. A missing field, a wrong type or a division by zero gives an empty
value, never an error page.

### Where expressions appear

| Place | Kind of value |
|---|---|
| `match.where` | expression (predicate) |
| `title.id`, `title.with` | expression |
| `title.pill`, a panel's `title`, `markdown.text` | template: text with `${expression}` parts |
| strip `bind`, column `bind`, status field `bind` | expression |
| `rows`, `each`, `tabTitle`, `moreLabel`, `highlight`, `source`, `mark`, area `limit`, gauge `value`/`max` | expression |
| `keys` entries starting with `link(` | expression |
| alert rules (`when`, `message`) | expression, template (see [LIVE.md](../architecture/LIVE.md#monitors-and-alerts)) |

### Lexical rules

| Element | Written as | Notes |
|---|---|---|
| Document | `$` | the whole document (inside a chart with `source`, the source's document) |
| Current row | `@` | the element of `rows`/`each`/a filter being evaluated; outside a row it is empty |
| Row position | `#index` | 0-based position of the row in its list; `-1` outside a row |
| Field | `.name` | letters, digits and `_`; a name with other characters needs brackets: `$['day-count']` |
| Element or key | `[expr]` | a number indexes a list (`[0]` first, `[-1]` last); text looks up a field (`$['tradeId']`) |
| Filter | `[?predicate]` | keeps the list elements for which the predicate (with `@` bound to each) is truthy |
| Bare name | `amount` | a field of the current row inside a row, otherwise of the document |
| Text | `'single'` or `"double"` quotes | `\` makes the next character literal (`'it\'s'`); there are no `\n` escapes |
| Number | `42`, `0.5`, `.5`, `1e6`, `2.5E-3` | a whole number is an integer (`1 + 1` prints `2`) |
| Literals | `true`, `false`, `null` | |
| Grouping | `( … )` | |

Anything else (`&`, `|` alone, `;`, `^`, a lone `#`) is a compile error: `unexpected character '&'`.

A field name with a hyphen is the classic trap: `$.day-count` **compiles**, as `$.day` minus a field `count`,
and shows `NaN`. Write `$['day-count']`.

### Operators and precedence

From lowest to highest binding; operators on one line bind equally and group left to right, except the
conditional, which groups right to left (`a ? b : c ? d : e` is `a ? b : (c ? d : e)`).

| Level | Operators | Result |
|---|---|---|
| 1 | `cond ? then : else` | `then` when `cond` is truthy, else `else` |
| 2 | `\|\|` | `true`/`false` (not one of the operands) |
| 3 | `&&` | `true`/`false` |
| 4 | `==`, `!=` | equality (below) |
| 5 | `<`, `<=`, `>`, `>=` | numeric comparison |
| 6 | `+`, `-` | addition, or text joining for `+` |
| 7 | `*`, `/`, `%` | arithmetic |
| 8 | `!x`, `-x` | not, negation |
| 9 | `.name`, `[i]`, `[?p]`, `f(…)` | paths, filters, calls |

So `!$.a == true` means `(!$.a) == true`, and `$.a + $.b * 2` means `$.a + ($.b * 2)`.

### Values and truthiness

A value is one of: empty (missing or `null`; the two behave the same), a boolean, a number, a text, a list, an
object, or a link (from `link(...)`).

| Value | Truthy when |
|---|---|
| empty | never |
| boolean | it is `true` |
| number | it is not 0 (and not NaN) |
| text | it is not empty — `'false'` and `'0'` are truthy |
| list, object | it has at least one element or field |
| link | always |

### Evaluation rules

- **Missing never throws.** `$.nothing.deeper[3].x` is empty. A path through a value that is not an object or
  list is empty too.
- **`+`** adds when **both** sides are numbers; otherwise it joins their text: `'Leg ' + 1` → `Leg 1`,
  `$.missing + 'x'` → `x`. A number inside quotes is text: `'1' + 1` → `11`.
- **`-`, `*`, `/`, `%`, unary `-`** convert both sides to numbers (text that is a number counts; `true` is 1).
  Anything that is not a number makes the result NaN, which shows as `NaN`: wrap possibly missing values,
  `coalesce($.fee, 0) * 2`.
- **Division and remainder by zero** give empty.
- **Comparisons** `<` `<=` `>` `>=` compare numbers numerically (text that reads as a number counts as one). Two
  texts that are not numbers compare as text, character by character, so ISO dates order correctly:
  `'2026-01-31' < '2026-02-01'` is true and `$.maturityDate < '2028-01-01'` works. A number against text that is
  not a number, or anything against empty, is `false`. (Before 1.12 text never ordered.)
- **Equality** `==` compares numerically when either side is a number (`1 == '1.0'` is true), otherwise as text
  (`'Live' == 'live'` is false: use `lower(...)` or `contains(...)` to ignore case). Empty equals only empty:
  `$.missing == null` is true.
- **`&&` and `||`** short-circuit and always give `true` or `false`. For a default value use `coalesce`, not `||`:
  `$.nick || 'n/a'` gives `true`, `coalesce($.nick, 'n/a')` gives the nickname or `n/a`.
- **Whole numbers** print without decimals: `1 + 1` → `2`, `0.5 * 4` → `2`.
- **Lists and objects** print as `[n]` and `{n}` (their size) when shown as text; use `size(...)` or a panel.
- **Compiled once.** Expressions are compiled once, cached by source text (`drishti.rachana.expression-cache-size`,
  10,000), and shared across threads.
- **Checked at load.** Every expression in a Sutra is compiled when the file loads, so a typo is reported against the
  file (`DRS-2101`), not at view time. Field names are not checked: a misspelt field is simply empty.
- **Dependency paths.** Each compiled expression reports the document paths it reads (`$.legs[0].rate`). Cells carry
  that path so live updates can flash exactly what changed.

### Paths and filters, by example

Against `MX-20000001`:

| Expression | Result |
|---|---|
| `$.tradeId` | `MX-20000001` |
| `$.counterparty.name` | `Meridian Reinsurance Ltd` |
| `$.legs[1].index` | `AONIA` |
| `$.legs[-1].label` | `Pay AONIA compounded` (the last leg) |
| `$['terms']['dayCount']` | `ACT/365F` |
| `$.legs[?@.payer].label` | empty: a filter gives a list, and `.label` of a list is empty; use `first($.legs[?@.payer]).label` → `Pay AONIA compounded` |
| `size($.schedule[?@.status != 'Settled'])` | `29` |
| `size($.schedule[?@.status == 'Projected'])` | `28` |
| `$.schedule[?@.leg == 1 && @.amount > 0]` | the fixed leg's positive flows (a list, for `rows:`) |
| `$.schedule[$.nextIndex].payDate` | `2026-12-30` |
| `$.nothing.at.all` | empty |

### Functions

The set is closed (`Functions.java`); a name outside it is a compile error
(`unknown function 'round'; known: [size, upper, sum, …]`), and so is a wrong number of arguments
(`size takes 1 argument(s), got 2`, `link takes 1-3 argument(s), got 4`).

| Function | Arguments | Returns |
|---|---|---|
| `link(id, kind?, label?)` | 1–3 | a link to entity `id` of `kind` (or the kind the reference catalogue recognises), shown as `label` or the id; empty when `id` is empty |
| `size(x)` | 1 | elements of a list, fields of an object, characters of a text; `0` for anything else (numbers, empty) |
| `sum(list, 'field'?)` | 1–2 | the sum of `field` over the list's elements (or of the elements themselves); non-numbers are skipped; `0` when `list` is not a list |
| `fmt(value, 'format')` | 2 | the value formatted as text with a named [format](#formats); an unknown format gives plain text |
| `coalesce(a, b, …)` | 1–8 | the first argument that is not empty (an empty text `''` is not empty); empty if all are |
| `first(list)` | 1 | the first element; empty for a non-list or an empty list |
| `last(list)` | 1 | the last element |
| `abs(x)` | 1 | the absolute value (NaN for a non-number) |
| `min(a, b, …)` / `min(list)` | 1–8 | the smallest number among the arguments, or among a single list's elements; non-numbers are skipped; empty if none |
| `max(a, b, …)` / `max(list)` | 1–8 | the largest, likewise |
| `upper(text)` | 1 | upper case (empty → `''`) |
| `lower(text)` | 1 | lower case |
| `contains(where, part)` | 2 | **case-insensitive**: true when the text contains `part`, or when `where` is a list with an element that does (nested lists are searched) |
| `startsWith(text, prefix)` | 2 | **case-insensitive** prefix test |

Examples against `MX-20000001`:

| Expression | Result | Shown with `fmt: signed0` |
|---|---|---|
| `link($.book, 'book')` | a link to `book BOOK-RATES-3` | `BOOK-RATES-3` (a link) |
| `link($.counterparty.id, 'counterparty', $.counterparty.name)` | a link shown as `Meridian Reinsurance Ltd` | |
| `link($.counterparty.id)` | a link; the kind comes from the id pattern `^CP-` | |
| `size($.legs)` | `2` | |
| `size($.currency)` | `3` | |
| `sum($.sensitivities, 'dv01')` | `-155244` | `−155,244` |
| `sum($.legs, 'pv')` | `1875863.4100000039` (a fraction: always give sums a format) | `+1,875,863` |
| `fmt($.notional, 'amount0')` | `242,000,000` (text) | |
| `$.currency + ' ' + fmt($.notional, 'amount0')` | `AUD 242,000,000` | |
| `coalesce($.terms.spread, 0)` | `0` (the terms have no spread) | |
| `first($.legs).label` | `Receive fixed 4.0829%` | |
| `last($.pnlHistory).pnl` | `43321` | `+43,321` |
| `abs($.risk.dv01)` | `155245` | |
| `min($.legs[0].rate, $.legs[0].cashflows[0].rate, 0.05)` | `0.040829` | |
| `max(3, '7', 'x')` | `7` | |
| `upper($.status)` | `LIVE` | |
| `contains($.productName, 'SWAP')` | `true` | |
| `contains($.valuation.curves, 'aonia')` | `true` (`FIX-AONIA` is in the list) | |
| `startsWith($.book, 'book-rates')` | `true` | |

`min`/`max` over a list look at the elements themselves, so `max($.pnlHistory)` (a list of objects) is empty.
There is no `max(list, 'field')`; when you need a column's extreme, have the source provide it.

### Templates

A template is text with `${expression}` parts: `"Cashflows · ${$.legs[0].label}"`. Each part is evaluated and
shown as text (a link shows its label; a list shows `[n]`). Braces inside a part are matched, so
`"${size($.legs)} legs"` works. An unclosed `${` is `DRS-2101 template '…': DRS-2101 unclosed '${' at 5`. Text without
`${` is used as is.

Templates are used for panel titles, `title.pill`, `markdown.text` and alert messages. Labels (`label:`) are
plain text, not templates.

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

### Expression errors

| Code | When | Example message |
|---|---|---|
| `DRS-2101` | an expression or template does not compile (at load, in Studio preview, or when saving an alert rule) | `expression '$.legs[0': DRS-2101 expected ']' but found '' at 8` |
| `DRS-2102` | evaluation error | reserved: evaluation is total, so a well-formed expression never raises it in practice |

The problem's message is `expression '<source>': DRS-2101 <reason> at <offset>` (or `template '<source>': …`),
where the offset is the 0-based character position inside the expression. Common reasons:

| Message | Cause | Fix |
|---|---|---|
| `unexpected character '&' at 9` | `&` or `\|` alone, `and`/`or` written as symbols of another language | use `&&`, `\|\|` |
| `unterminated string at 14` | a quote not closed | close it; inside YAML double quotes, use single quotes for EL text |
| `expected ')' but found '' at 20` | a bracket or parenthesis not closed | close it |
| `expected a field name but found '' at 7` | a path ending in a dot (`$.legs.`) | finish the path |
| `unknown function 'round'; known: [...]` | a function that does not exist | use `fmt(x, 'amount0')` and the other functions above |
| `fmt takes 2 argument(s), got 1 at 0` | `fmt($.x)` | `fmt($.x, 'amount0')` |
| `unexpected 'b' at 4` | two values with no operator between them (`'a' 'b'`) | join with `+` |
| `unclosed '${' at 4` | a template part not closed | add `}` |

## Problem codes

A Sutra file that fails any check is not loaded (or keeps its last good version, see
[Hot reload](#hot-reload-studio-and-governance)), and each problem is reported as
`<file>:<line>:<column> <code> <message>`. All problems of a file are reported at once.

| Code | Message (exact form) | Cause | Fix |
|---|---|---|---|
| `DRS-2001` | `YAML syntax: <parser message>` | the file is not valid YAML (bad indentation, a `:` or `#` in an unquoted value, a tab) | fix the YAML; quote values with `: `, ` #`, or a leading `#`, `@`, `*`, `&` |
| `DRS-2004` | `Markdown Sutras are no longer read (Sutras are YAML since 1.11): convert it with python3 tools/rachana/md_to_yaml.py <file> --delete`, or `a Sutra file is named <name>.v<N>.sutra.yaml; rename <file>` | a `.sutra.md` file, or a plain `.yaml`/`.yml` file, in a Sutra directory | convert it with `python3 tools/rachana/md_to_yaml.py <file-or-folder> --delete`, or rename it to `<name>.v<N>.sutra.yaml` (or move a YAML file that is not a Sutra out of the directory) |
| `DRS-2009` | `missing 'rachana: 1' (the Rachana language version) at the top`, `'rachana: 2' is not a language version this server reads (it reads 1)` | no `rachana:` key, or a version this server does not know | put `rachana: 1` first in the file |
| `DRS-2010` | `missing 'sutra'`, `missing 'version'`, `missing 'kind'`, `missing 'id'`, `missing 'bind'`, `missing 'match' mapping with at least 'kind'` | a required key is absent | add it |
| `DRS-2011` | `unknown key 'pannels' in top level` (also `in match`, `in title`, `in strip item`, `in column`) | a misspelt or unsupported key | fix the spelling; panel options are reported as `DRS-2023` instead |
| `DRS-2012` | `'panels' must be a list`, `'keys' must be a mapping of F-key to action`, `a column must be a mapping (label, bind, fmt, tone, total, link)`, `'kind' must be text`, `action for F7 must be text`, `'notes' is plain text` | a value of the wrong shape | write the shape shown in the message |
| `DRS-2020` | `name 'IRS_Vanilla' must be lower-case kebab, 2-64 characters`, `version must be a positive integer` | bad name or version | `irs-vanilla`; `version: 3` unquoted |
| `DRS-2021` | `unknown panel kind 'chart'; expected one of kv, table, tabs, line, area, hbar, ladder, links, status, provenance, markdown, gauge, surface` | a kind that does not exist | use one of the 13 kinds |
| `DRS-2022` | `'table' panel 'flows' needs option 'rows'` | a required option is missing | add it (see each kind's table) |
| `DRS-2023` | `option 'limit' is not valid for 'ladder' panels`, `only 'tabs' panels take a 'body'` | an option the kind does not accept | remove it, or change the kind |
| `DRS-2024` | `duplicate panel id 'legs'` | two panels with one id | rename one |
| `DRS-2025` | `'F13' is not a function key (F1-F12)`, `function key F2 is used twice` | a bad or repeated key | use `F1`–`F12` once each, across panels and `keys` |
| `DRS-2026` | `the strip holds at most 8 figures, found 9` | more than 8 strip items | move figures into a `kv` panel |
| `DRS-2027` | `area must be 'main' or 'right'` | `area: left`, `area: side` | `main` or `right` |
| `DRS-2028` | `trade-x@2 is already defined in /…/trade-x.v2.sutra.yaml` | two files define one `name@version` | raise the version, or remove the duplicate |
| `DRS-2101` | `expression '…': DRS-2101 …`, `template '…': DRS-2101 …` | an expression or template does not compile | see [Expression errors](#expression-errors) |

Other codes you may meet around Sutras:

| Code | HTTP | Meaning |
|---|---|---|
| `DRS-2002` | 422 | the envelope of any of the above through the API: `N problem(s): …`, with a `problems` list in the body |
| `DRS-2003` | 404 | no Sutra `name@version` (`GET /api/v1/sutras/{name}/{version}`) |
| `DRS-2005` | 404 | no such governance proposal |
| `DRS-2006` | 409 | a proposal's Sutra changed after it was proposed: reject it and propose again from the live version |
| `DRS-2007` | 403 | four eyes: the author of a proposal cannot approve it |

Not validated by the parser (so check them in a preview; the [schema](#completion-and-checking-in-your-editor)
offers the valid format and tone names as you type): format and tone names, field names used as `x`, `y`, `label`,
`value`, series `value`s, panel ids used as `keys` actions, `layout` and `view` values, the keys inside status
`fields`, and the type of `limit`.

Where to see problems:

```bash
curl -s http://localhost:18480/api/v1/sutras/problems
```

You should see `{}` when every file is valid, otherwise a map from file to its problems:

```json
{"/srv/drishti/sutras/rates/swap-x.v1.sutra.yaml": [
  {"code": "DRS-2023", "message": "option 'limit' is not valid for 'ladder' panels",
   "location": {"file": "swap-x.v1.sutra.yaml", "line": 31, "column": 12}}]}
```

The same list appears in the server log (`sutra problem …`), on the server health page, and in Studio when you
preview or save.

## Recipes

Each recipe is a panel (or two) you can paste into the `panels:` of a Sutra for `trade` and preview against
`MX-20000001`. The figures under each are what that preview shows.

### A table with a filter, totals and "more"

```yaml
  - id: flows
    kind: table
    title: "Unsettled flows (${size($.schedule[?@.status != 'Settled'])})"
    key: F6
    rows: "$.schedule[?@.status != 'Settled']"
    limit: 5
    moreLabel: "(size($.schedule[?@.status != 'Settled']) - 5) + ' more flows'"
    totalLabel: Net
    columns:
      - { label: Pay date, bind: "@.payDate", fmt: date }
      - { label: Leg, bind: "@.leg == 1 ? 'Fixed' : 'Float'" }
      - { label: Amount, bind: "@.amount", fmt: signed0, tone: sign, total: true }
      - { label: PV, bind: "@.pv", fmt: signed0, tone: sign, total: true }
```

You should see the title `Unsettled flows (29)`, five rows starting
`2026-12-30 · Float · −2,253,620 · −2,233,479`, a `Net` row of `+2,403,291` and `+1,875,863` (all 29 flows, not
just the five shown), and `24 more flows`.

### A chart from another entity's curve

```yaml
  - id: curve
    kind: line
    title: Discount curve (zero rates)
    code: CRV
    area: right
    source: "link($.discountCurve, 'ir-curve')"
    rows: $.points
    x: tenor
    y: zeroRate
    mark: "'5Y'"
    fmt: price2
    unit: "%"
```

You should see the AUD OIS curve from `1M` to `30Y`, the `5Y` point marked, `5Y point 4.03%` under the chart and
a `CRV-AUD-OIS` link beside it. The mark can follow the trade: `mark: "$.maturityTenor"` when the document
carries one.

### Tabs per element

```yaml
  - id: legs
    kind: tabs
    title: Legs
    key: F3
    each: $.legs
    tabTitle: "@.payReceive + ' ' + @.rateType"
    body:
      kind: kv
      columns:
        - { label: Index, bind: "coalesce(@.index, 'fixed')" }
        - { label: Rate, bind: "@.rate", fmt: pct4 }
        - { label: Day count, bind: "@.dayCount" }
        - { label: PV, bind: "@.pv", fmt: signed0, tone: sign }
```

You should see two tabs, `Receive Fixed` and `Pay Float`; the first shows `Index fixed`, `Rate 4.0829%`,
`Day count ACT/365F`, `PV +52,387,405`.

### A ladder with the next row lit

```yaml
  - id: ladder
    kind: ladder
    title: All flows
    rows: $.schedule
    highlight: "#index == $.nextIndex || @.status == 'Failed'"
    columns:
      - { label: Pay date, bind: "@.payDate", fmt: date }
      - { label: Type, bind: "@.type" }
      - { label: Rate, bind: "@.rate", fmt: pct4 }
      - { label: Status, bind: "@.status", tone: status }
      - { label: PV, bind: "@.pv", fmt: signed0, tone: sign, total: true }
```

You should see 35 rows with the seventh (`2026-12-30`) highlighted, statuses in the `ok` colour, and a
`Total` PV of `+1,875,863`.

### Links: a panel, a column and a key

```yaml
  - id: execution
    kind: kv
    title: Execution
    columns:
      - { label: Venue, bind: $.execution.venue }
      - { label: Order, bind: $.execution.orderId }          # ends in Id: links if a pack knows the pattern
      - { label: Trader, bind: "link($.trader, 'trader')" }   # always a link to the trader
  - { id: refs, kind: links, title: Linked entities, code: REFS, area: right }
keys: { F7: "link($.nettingSet, 'netting-set')", F8: impact, F9: raw }
```

You should see `Trader TRDR-ASHAH` as a link, the order id `ORD-C00C6EF736` as plain text (no pack has an id pattern for `ORD-`),
the *Linked entities* list on the right, and `F7 Netting set`, `F8 Impact`, `F9 Raw JSON` in the key bar.

### A strip that reads well

```yaml
strip:
  - { label: Notional, bind: "$.currency + ' ' + fmt($.notional, 'amount0')" }
  - { label: MTM (USD), bind: $.mtm, fmt: signed0, tone: sign, emphasis: true }
  - { label: DV01 (USD), bind: $.risk.dv01, fmt: signed0, tone: sign }
  - { label: Status, bind: $.status, tone: status }
  - { label: Next pay, bind: "$.schedule[$.nextIndex].payDate", fmt: date }
```

You should see `Notional AUD 242,000,000`, `MTM (USD) +1,875,863` highlighted, `DV01 (USD) −155,245`,
`Status Live` in the `ok` colour and `Next pay 2026-12-30`.

## Hot reload, Studio and governance

### Editing files on disk

- With `drishti.rachana.hot-reload: true` (the default) a watcher thread follows every Sutra directory (site
  and packs). A burst of file events is debounced (`drishti.rachana.reload-debounce`, 250 ms) into **one**
  reload, which rescans every directory.
- A file that parses and validates replaces its previous version at once. A file that becomes invalid keeps its
  **last good** Sutra live, and its problems are reported (log, `GET /api/v1/sutras/problems`). A file that was
  never valid since start-up is simply not loaded. A deleted file's Sutras disappear.
- After a reload that changed anything, every cached layout is dropped, so the next build of every view (a page
  load, a refresh, or the next live tick) uses the new Sutra. Open live views pick it up on their next tick.
- Directories are registered with the watcher at start-up. Files in a folder created later are loaded at the
  next reload (any change in a watched folder triggers a full rescan) or at restart.
- Formats and semantic hints are read at start-up only; Sutras are the only thing that hot-reloads.

### Studio

Studio (`/studio` in the console) is a YAML editor for Sutras with a live preview. It completes keys, panel
kinds, options, formats, tones and this server's entity kinds from the
[schema](#completion-and-checking-in-your-editor), and checks the text as you type, showing each problem at
its line. Alongside the editor it summarises the Sutra you are writing and previews it against test entities.

1. Pick a Sutra to start from, or start from inference: Studio loads
   `GET /api/v1/studio/inferred/{kind}/{id}?name=…` (or `POST /api/v1/studio/inferred` with pasted sample JSON),
   which returns a new YAML Sutra (`rachana: 1`, a `description`, `priority: 1`) holding what inference made of
   that entity.
2. Choose the entity to preview against (kind and id), or paste sample JSON.
3. Preview. The console sends the text to `POST /api/v1/studio/preview`, which parses and checks it
   (`SutraRegistry.check`, problems located in `studio.sutra.yaml`) and builds the view with the unsaved Sutra,
   bypassing the layout cache and the matcher: the preview always uses your Sutra, whatever its `match` says.
   Nothing is written.
4. Save. This needs `drishti.rachana.studio-save: true` (off by default) and a role that may author Sutras. The
   text is sent as YAML (`POST /api/v1/sutras`, `Content-Type: text/yaml`) and, with governance on, becomes a
   proposal for review.

### Governance

With `drishti.governance.enabled: true` (the default), Save does not publish: it records a **proposal**.

| Step | Who | What happens |
|---|---|---|
| Propose | an author | the Sutra is checked as it stands (it must be publishable) and stored with the live text it is based on (`202 Accepted`, `{"proposal": {"id", "name", "version", "status": "pending"}}`). Proposing exactly the live text is refused. |
| Review | an approver | `/studio/reviews` lists proposals; each shows a diff against the live version |
| Approve | an approver | with `drishti.governance.four-eyes: true` (default) and security on, the author cannot approve their own proposal (`DRS-2007`). If the live text changed since the proposal, approval is refused (`DRS-2006`). Otherwise the file is written and goes live. |
| Reject | an approver | needs a comment saying why |
| Withdraw | the author or an admin | |

Every step is audited (`sutra-proposed`, `sutra-approved`, …). `GET /api/v1/sutras/{name}/history` lists a
Sutra's proposals. With governance off, Save writes at once.

Where a save (or an approval) writes: `<first directory of drishti.rachana.dirs>/<domain>/<name>.v<N>.sutra.yaml`,
exactly the text you wrote, comments included; then it reloads. A `name@version` already defined by another file (for example by a pack) is
refused with `DRS-2028`: to change a pack's Sutra from Studio, save it as a **new version**; the latest version
wins matching.

## The reference Sutras

| File | Entity | Notes |
|---|---|---|
| `packs/trading/sutras/rates/irs-fixfloat.v1.sutra.yaml` | `TRD MX-20000001` | kv, tabs (columns), ladder, line from a curve, hbar, links |
| `packs/counterparty-risk/sutras/exposure-and-capital/netting-set.v1.sutra.yaml` | `NSET NS-MERIDIAN-RE-NY` | area with limit, table with totals and automatic id links |
| `packs/market-data/sutras/market-data/equity-vol-surface.v1.sutra.yaml` | `EQV EQV-CSCA` | surface, table, line |
| `packs/finance/sutras/rates/irs-vanilla.v3.sutra.yaml` | `TRD IRS-48213` | line with `mark` (finance pack; enabled by default with `DRISHTI_PACKS=finance`) |
| `packs/finance/sutras/fx/fx-swap.v2.sutra.yaml` | `TRD FXS-20931` | finance pack |
| `packs/finance/sutras/commodities/listed-future.v1.sutra.yaml` | `TRD CFT-77120` | finance pack |
| `packs/finance/sutras/credit/netting-set.v1.sutra.yaml` | `NSET NS-NORTH-01` | `link: true` columns, kv with inferred fields |

For a guided, step-by-step introduction, see [RACHANA_GUIDE.md](RACHANA_GUIDE.md). For how inference fills
what a Sutra leaves out, see [INFERENCE.md](../architecture/INFERENCE.md).
