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

**Rachana** (रचना, *composition*) is Drishti's declarative screen grammar. Because of it, no product ever gets its own coded screen.
A **Sutra** (सूत्र, *thread*) is one layout written in Rachana: a versioned YAML file (`<name>.v<N>.sutra.yaml`, starting
`rachana: 1`) that describes how to lay out one family of entities. The *How this view was built* panel reads, for example,
`Sutra irs-fixfloat v1 + inference`, meaning the Sutra `irs-fixfloat`, version 1, completed by inference.

A Sutra holds no code and no pixels. It is loaded by `SutraRegistry` from `drishti.rachana.dirs` and the enabled packs, and reloads as
soon as it is saved. If an edit is invalid, the last good version is kept and the problems are reported with line and column.

This page is the authoritative reference. Everything in it was checked against the code in `drishti-rachana` (grammar, parser,
validator, Rachana-EL, formats), `drishti-engine` (binding) and `drishti-inference` (gaps and labels), and every example was
previewed against the demo lakes of the seven packs (`drishti.py lake demo`, sixty business days ending 2026-10-16): the trades of
the `trading` pack in the common schema of their source systems (ENDUR, CALYPSO, MUREX), and every other kind, all in the production
systems' UPPER_SNAKE attribute names. Figures that depend on the business date are marked as such.

The `curl` commands use a personal API token (**My account → API tokens**; the `design:write` scope for the preview and save
calls) and

```bash
export DRISHTI=http://localhost:18480/api/v1
AUTH="Authorization: Bearer $DRISHTI_TOKEN"
```

| If you want to… | Read |
|---|---|
| see a whole Sutra with every line explained | [A complete example, annotated](#a-complete-example-annotated) |
| copy a working pattern | [Recipes](#recipes) |
| look up one panel kind | [Panel kinds](#panel-kinds) |
| share panels between many Sutras | [Shared panel blocks](#shared-panel-blocks-use) |
| get completion and checking in your editor | [Completion and checking in your editor](#completion-and-checking-in-your-editor) |
| look up a function or an operator | [Rachana-EL](#rachana-el-expressions) |
| declare the search form of a pack's kinds (`form:` in `pack.yaml`) | [Pack search form](#pack-search-form-form-in-packyaml) |
| understand an error such as `DRS-2023` | [Problem codes](#problem-codes) |
| know what happens when you save a file or press Save in the workbench | [Hot reload, the workbench and governance](#hot-reload-the-workbench-and-governance) |

## How a Sutra becomes a view

Every request for an entity (`TRD MX-20000001 <GO>`, `GET /api/v1/views/trade/MX-20000001`) goes through the same steps:

1. **Fetch** the document (JSON) from the source that holds the kind (for a trade, the Delta connector of its source system:
   `trade-murex` for `MX-…`).
2. **Match**: take the Sutras whose `match.kind` is the entity's kind and pick the first, in priority order, whose `where` holds for
   this document. No match: the whole layout is inferred.
3. **Merge**: fill the gaps the Sutra leaves (columns it does not list, labels it does not give) by inference. The result is the
   *effective layout*, cached per (Sutra, kind, document shape).
4. **Link**: find the entities the document refers to (for the `links` panel) and the entities panels read (`source:`), and fetch
   them in parallel within `drishti.graph.link-budget` (40 ms).
5. **Bind**: evaluate every expression of the title, the strip and each panel against the document, format and colour the values.
   Panels bind in parallel; one panel that fails shows *No data available* and never takes the view down.

The result is a ViewModel (JSON) that the console renders. You can see it with:

```bash
curl -s -H "$AUTH" $DRISHTI/views/trade/MX-20000001 | python3 -m json.tool | head -40
```

You should see `ref`, `mnemonic`, `title`, `strip`, `controls`, `panels`, `keys`, `provenance` and `timings`, with
`"layout": "Sutra irs-fixfloat v1 + inference"` and `"source": "trade-murex"` under `provenance`.

## File format: one YAML file

A Sutra is **one YAML file**, `<name>.v<N>.sutra.yaml` in a Sutra folder, whose first key is `rachana: 1`, the version of the Rachana
language it is written in. Values are plain YAML; strings may hold Rachana-EL expressions. That is the only format: there is no
second dialect to learn, and any YAML tool (syntax highlighting, linters, JSON Schema completion, `yq`, code review diffs) works on a
Sutra as it is.

```yaml
rachana: 1
sutra: irs-vanilla
version: 3
description: Murex fixed/float interest rate swaps, with the legs one key away.
match: { kind: trade, where: "$.SOURCE_SYSTEM == 'MUREX' && $.PRODUCT_TYPE == 'IRS_FIXFLOAT'", priority: 20 }
panels:
  - id: legs
    kind: table
    title: Legs
    key: F2
    rows: $.LEGS
    description: One row per leg; the rate is the fixed rate or the first period's fixing.
    columns:
      - { label: Leg, bind: "@.LEG_ECONOMIC_TYPE + ' ' + @.LEG_TYPE" }
      - { label: Rate, bind: "@.CASH_FLOWS[0].RATE", fmt: pct4 }
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

They are plain text: nothing in them is evaluated (a `${...}` in a `description` is shown as written; parameterised text belongs in
the pack's `config/about.yaml`, see [About text and glossary](PACK_DEVELOPER_GUIDE.md#about-text-and-glossary)), and the engine does
not use them to build the view (they are not part of the ViewModel). The Sutra's `description` is **shown to users**: it is the
second paragraph of *What you are looking at* in the [About this page](USER_GUIDE.md#about-this-page) drawer, and a panel's
`description` is its note there. They travel with the file, so they are in the workbench, in a code review diff and in
`GET /api/v1/sutras/{name}/{version}/source`, and nothing has to be kept in step with them. `description` and `notes` must be text
(`DRS-2012 'notes' is plain text` otherwise).

What the server does with a file, in order (`SutraRegistry`, `SutraParser`, `SutraBuilder`):

1. Only files named `*.sutra.yaml` are Sutras, and `*.block.yaml` files are [shared panel blocks](#shared-panel-blocks-use). Any other
   `.yaml`, `.yml` or `.sutra.md` file in a Sutra directory is reported as `DRS-2004` with the fix (see [Problem codes](#problem-codes))
   and is not loaded.
2. The file is read as YAML, keeping the line and column of every node. Bad YAML: `DRS-2001`. YAML that would be read in a way you
   may not mean is `DRS-2033`: a key written twice in one mapping (YAML keeps only the last), a second document after `---` (it would
   be ignored), or a tag (`!panel`, `!!binary`; the core tags such as `!!str` are allowed). A leading `---` is fine.
3. `rachana:` must be present and be a language version this server reads (today: `1`). Otherwise `DRS-2009`.
4. Every `{ use: <block>@<version>, with: {…} }` among the panels is replaced by the block's panels (`DRS-2048` when it cannot be).
5. The rest is checked against the grammar below. Every problem is collected (not just the first) with its location: `DRS-2010` to
   `DRS-2039`. Parsing is strict: an unknown key is an error (`DRS-2011`), never silently ignored, so a typo such as `pannels:` cannot
   pass unnoticed.
6. Every Rachana-EL expression and template is compiled. A typo is `DRS-2101`, reported against the file at load time, not when
   someone opens a view. So is an expression beyond the [size limits](#size-limits) (nested deeper than 200 levels, or longer than
   10,000 characters).
7. The Sutra is registered under `name@version`; a second file defining the same pair is `DRS-2028`.

Whatever goes wrong while one file is read, it is that file's problem and never stops the server or hot reload: a failure none of
the checks above foresee is `DRS-2032` (and is logged with its stack trace).

Rules that follow from this:

- Problem locations are the file's own line and column, so an error at line 14 is at line 14 in your editor.
- The file name is a convention, not a rule: the name and version come from `sutra:` and `version:`, and the parser does not compare
  them with the file name. Keep them in step anyway; the workbench saves to `<domain>/<name>.v<N>.sutra.yaml`.
- `#` comments are kept in the file (the workbench and `GET /api/v1/sutras/{name}/{version}/source` return the text exactly as
  written) but mean nothing to the engine. Use `notes` for anything a reviewer should read.
- Keep other YAML files out of Sutra directories; they are reported as `DRS-2004`.
- A Sutra from before Drishti 1.11 (`*.sutra.md`, a Markdown document with one ```` ```sutra ```` block) converts in one step; the
  block becomes the file, `rachana: 1` is added, and the prose becomes `notes`:

  ```bash
  python3 tools/rachana/md_to_yaml.py config/packs/my-pack/sutras --delete
  ```

  You should see one line per file, such as
  `config/packs/my-pack/sutras/rates/irs-x.v1.sutra.md -> config/packs/my-pack/sutras/rates/irs-x.v1.sutra.yaml`. Without `--delete`
  the `.sutra.md` files stay beside the new ones (and are reported as `DRS-2004` until you remove them). The lines that only restated
  the layout (the "Applies to" line, the "Panels" table) are dropped, since tools derive them from the YAML.
- YAML quoting: a value that starts with `$`, `@` or a letter can usually stay bare (`bind: $.TRADE_ID`). Quote any value that
  contains `: `, ` #`, `{`, `}`, `[`, `]`, `,` at the start, or begins with `'`, `*`, `&`, `!`, `%`, `@` followed by a space, or `#`:
  for example `bind: "@.LEG_ID == 1 ? 'Fixed' : 'Float'"` and `highlight: "#index == 0"` (unquoted, `#index` would be a YAML comment).
  Inside a flow mapping (`{ … }`) a comma ends the value, so quote any text with a comma. Inside YAML double quotes a backslash starts
  a YAML escape (`"\d"` is `DRS-2001`).

### Completion and checking in your editor

`GET /api/v1/rachana/schema` returns the JSON Schema (draft 2020-12) of the language, generated from the grammar itself: every key,
the panel kinds and the options each takes, which values are Rachana-EL, the tones, and **this server's** entity kinds and format
names (core and enabled packs). `x-rachana-functions` lists the expression functions with their argument counts. Because it is
generated from the parser's own tables, it never disagrees with the parser.

```bash
curl -s -H "$AUTH" $DRISHTI/rachana/schema | python3 -c "
import json, sys
s = json.load(sys.stdin)
print(s['title']); print(s['required']); print(sorted(s['properties'])); print(sorted(s['x-rachana-functions']))"
```

You should see:

```text
Rachana Sutra, language 1
['rachana', 'sutra', 'version', 'match']
['controls', 'description', 'domain', 'keys', 'match', 'notes', 'panels', 'rachana', 'strip', 'sutra', 'title', 'version']
['abs', 'coalesce', 'contains', 'days', 'first', 'flatten', 'fmt', 'group', 'last', 'link', 'lower', 'matches', 'matchesCase', 'max', 'min', 'nonblank', 'oneOf', 'size', 'startsWith', 'sum', 'today', 'upper', 'within']
```

The workbench's YAML tab uses it for completion and live checking. Any editor that reads JSON Schema can too; for example, with the
YAML language server (VS Code's YAML extension, and others) put this comment on the first line of a Sutra:

```yaml
# yaml-language-server: $schema=http://localhost:18480/api/v1/rachana/schema
```

(With security on, save the schema to a file once with the `curl` above and point the comment at the file.) The schema catches most
mistakes while you type; the parser remains the authority (it also compiles every expression, which a schema cannot), and
`drishti.py sutra lint` runs the parser without a server ([SUTRA_DEVELOPER_GUIDE.md](SUTRA_DEVELOPER_GUIDE.md#15-testing-expectyaml-sutra-linttestpreview-ci)).

## A complete example, annotated

The Sutra below lays out a vanilla fixed/float interest rate swap from the trading pack (`TRD MX-20000001`, booked in Murex). It uses
most of the grammar: matching, the title, the strip, a dozen panels of different kinds, expressions, templates, links and function
keys. Every line is commented. The test suite (`RachanaReferenceExampleTest`) previews this exact block against `MX-20000001` and
fails if it stops parsing or leaves a panel empty, so it always works as written.

How to read the notation:

- `$` is the document. `$.LEGS[0].INDEX.NAME` walks into arrays and objects.
- `@` is the current row inside `rows`, `each` or a column: `@.PAYMENT_DATE` is the row's `PAYMENT_DATE`.
- A value in `"double quotes"` is a Rachana-EL expression (operators, functions). A bare path such as `$.TRADE_ID` needs no quotes.
- `${…}` inside text (titles, pills) embeds an expression in a template.
- `#` starts a YAML comment, which the engine ignores.

The trade is in the common schema every source system writes (47 upper-snake attributes, the same for Endur, Calypso and Murex;
[PACKS.md](PACKS.md#trading)). The fragment of the document that the Sutra reads looks like this (abridged; the full document is
`GET /api/v1/entities/trade/MX-20000001/raw`):

```json
{
  "SOURCE_SYSTEM": "MUREX", "TRADE_ID": "MX-20000001", "TRADE_STATUS": "AMENDED", "TRADE_ACTION": "AMEND", "TRADE_VERSION": "2",
  "QUANTITY": 242000000.0, "PRICE": 0.040829, "BUY_SELL_CODE": "S", "PRODUCT_TYPE": "IRS_FIXFLOAT",
  "PRODUCT_SUB_TYPE": "Interest rate swap (fixed/float)", "PRODUCT_GROUP": "Rates", "PRODUCT_SUB_GROUP": "swap",
  "COUNTERPARTY": "CP-MERIDIAN-RE", "LEGAL_ENTITY": "LE-NY", "PORTFOLIO": "BOOK-RATES-3", "TRADER_ID": "TRDR-ASHAH",
  "TRADE_DATE": "2025-07-25", "START_DATE": "2025-07-28", "MATURITY_DATE": "2032-06-25", "CURRENCY": "AUD",
  "DELIVERY_VERSUS_PAYMENT": "NO", "INTERNAL_EXTERNAL_CODE": "External", "TRANSIT": "00107",
  "LEGS": [ { "LEG_ID": 1, "LEG_TYPE": "FIXED", "LEG_ECONOMIC_TYPE": "Receive", "NOTIONAL": 242000000.0,
              "DISCOUNT_INDEX": "CRV-AUD-OIS", "RESET_CONVENTION": "Annual", "INDEX": { "NAME": "", "GROUP": "", "SUB_GROUP": "" },
              "CASH_FLOWS": [ { "FLOW_ID": 20000001001, "NOTIONAL": 242000000.0, "RATE": 0.040829, "START_DATE": "2025-07-28",
                                "END_DATE": "2026-06-25", "PAYMENT_DATE": "2026-06-29", "RESETS": [] }, … ] },
            { "LEG_ID": 2, "LEG_TYPE": "FLOAT", "LEG_ECONOMIC_TYPE": "Pay", "INDEX": { "NAME": "AONIA", … },
              "CASH_FLOWS": [ { …, "RESETS": [ { "RESET_ID": "R-1", "RESET_DATE": "2025-07-28", "RATE": 0.035376 }, … ] }, … ] } ],
  "EVENTS": [ { "LEG_ID": "", "EVENT_TYPE": "BOOKED", "EVENT_DATE": "2025-07-25", "AMOUNT": "" }, …,
              { "LEG_ID": 1, "EVENT_TYPE": "CASH_SETTLEMENT", "PAYMENT_TYPE": "Interest", "EVENT_DATE": "2026-06-29", "AMOUNT": 8987301.85 }, … ]
}
```

```yaml
rachana: 1
# ---- Identity -----------------------------------------------------------------------------------------
sutra: swap-annotated            # the name: lower-case kebab, 2-64 characters
version: 1                       # name@version is unique; old versions stay loadable, so saved views reproduce
description: A vanilla fixed/float swap, every line explained.
domain: rates                    # grouping in the workbench and the catalogue (defaults to the folder the file is in)

# ---- Which documents this layout is for -------------------------------------------------------------------
# kind picks the entity family. where is a Rachana-EL predicate over the document; when several Sutras
# match, the one with the highest priority wins. No match at all: the view is built by inference.
match:
  kind: trade
  where: "$.PRODUCT_TYPE == 'IRS_FIXFLOAT' && size($.LEGS) == 2 && $.TRADE_STATUS != 'MATURED'"
  priority: 50                   # above the trading pack's irs-fixfloat (10), so this one would win

# ---- The title line:  [pill]  ID  with  <counterparty> -------------------------------------------------------
title:
  pill: "${$.PRODUCT_GROUP} · ${$.PRODUCT_SUB_TYPE}"   # a template: text with ${expression} parts
  id: $.TRADE_ID                                       # a path: the big identifier
  with: "link($.COUNTERPARTY, 'counterparty')"         # link(id, kind, text?) is navigable

# ---- The strip: up to 8 headline figures under the title ----------------------------------------------------
strip:
  - { bind: "$.LEGS[0].NOTIONAL", fmt: amount0 }   # no label: taken from the glossary, else the field name ("Notional")
  - { label: Currency, bind: $.CURRENCY }
  - { label: Side, bind: "$.BUY_SELL_CODE == 'B' ? 'Buy' : 'Sell'" }                # condition ? then : else
  - { label: Fixed rate, bind: $.PRICE, fmt: pct4 }                                  # 0.040829 shows as 4.0829%
  - { label: Maturity, bind: $.MATURITY_DATE, fmt: date }
  - { label: Status, bind: "$.TRADE_STATUS + ' v' + $.TRADE_VERSION", tone: status, emphasis: true }   # coloured by meaning, highlighted
  - { label: Source, bind: $.SOURCE_SYSTEM }
  - { label: Portfolio, bind: "link($.PORTFOLIO, 'book')" }  # a link in the strip opens the book

# ---- Panels: the body of the view, in order. area: right puts one in the side column ------------------------
panels:

  # kv: a label/value grid. columns name what to show; bind paths start at $ (the whole document).
  - id: terms
    kind: kv
    title: Terms
    key: F2                                      # F2 jumps here (keys are unique across the Sutra)
    code: TRM                                    # a short tag at the right of the panel header
    description: The economic terms, as booked.   # plain text, shown in the About this page drawer, never evaluated
    columns:
      - { label: Trade date, bind: $.TRADE_DATE, fmt: date }
      - { label: Start, bind: $.START_DATE, fmt: date }
      - { label: Floating index, bind: "$.LEGS[?@.LEG_TYPE == 'FLOAT'][0].INDEX.NAME" }       # a filter: the floating leg, wherever it sits
      - { label: Discount curve, bind: "link($.LEGS[0].DISCOUNT_INDEX, 'ir-curve')" }          # links work in any cell
      - { label: Legs' notional, bind: "sum($.LEGS, 'NOTIONAL')", fmt: amount0 }              # sum(list, 'field') adds a field up
      - { label: Flows, bind: "size(flatten($.LEGS[*].CASH_FLOWS))" }                         # flatten: every leg's flows as one list
      - { label: Resets of leg 2, bind: "size(flatten($.LEGS[1].CASH_FLOWS[*].RESETS))" }   # the floating leg's fixings, every period's

  # tabs: one tab per element of each; inside, @ is that element. layout: columns shows them side by side.
  - id: legs
    kind: tabs
    title: Legs
    key: F3
    each: $.LEGS
    layout: columns
    tabTitle: "'Leg ' + @.LEG_ID + ' · ' + @.LEG_ECONOMIC_TYPE + ' ' + @.LEG_TYPE"   # + joins text
    body:                                          # the panel drawn for each element
      kind: kv
      columns:
        - { label: Index, bind: "nonblank(@.INDEX.NAME, 'Fixed')" }              # nonblank: the first value that is not empty text
        - { label: Notional, bind: "@.NOTIONAL", fmt: amount0 }
        - { label: Frequency, bind: "@.RESET_CONVENTION" }
        - { label: Calendar, bind: "@.RESET_HOLIDAY_SCHEDULE" }
        - { label: Periods, bind: "size(@.CASH_FLOWS)" }

  # table: one row per element of rows (here the first leg's cash flows), with a total row.
  - id: cashflows
    kind: table
    title: "Cash flows · leg ${$.LEGS[0].LEG_ID} (${$.LEGS[0].LEG_TYPE})"   # templates work in titles too
    key: F4
    rows: $.LEGS[0].CASH_FLOWS
    limit: 6                                     # show six rows, then "N more"
    moreLabel: "(size($.LEGS[0].CASH_FLOWS) - 6) + ' later flows'"
    totalLabel: Total
    columns:
      - { label: Flow, bind: "@.FLOW_ID" }
      - { label: Start, bind: "@.START_DATE", fmt: date }
      - { label: Payment, bind: "@.PAYMENT_DATE", fmt: date }
      - { label: Rate, bind: "@.RATE", fmt: pct4 }
      - { label: Notional, bind: "@.NOTIONAL", fmt: amount0, total: true }   # total: summed into the total row

  # ladder: a dated list with one highlighted row; highlight is evaluated per row.
  - id: schedule
    kind: ladder
    title: Payments to come (both legs)
    rows: "$.EVENTS[?nonblank(@.AMOUNT) && @.EVENT_DATE > '2026-09-30']"   # a filter: [?condition] keeps matching elements
    highlight: "#index == 0"                     # #index is the row's position: highlight the first
    columns:
      - { label: Date, bind: "@.EVENT_DATE", fmt: date }
      - { label: Leg, bind: "@.LEG_ID == 1 ? 'Fixed' : 'Float'" }
      - { label: Amount, bind: "@.AMOUNT", fmt: signed2, tone: sign }

  # status: operational fields, coloured by meaning (AMENDED, VALIDATED, CANCELLED…).
  - id: operations
    kind: status
    title: Booking
    code: OPS
    fields:
      - { label: Status, bind: $.TRADE_STATUS, tone: status }
      - { label: Last action, bind: "$.TRADE_ACTION + ' (version ' + $.TRADE_VERSION + ')'" }
      - { label: Source system, bind: $.SOURCE_SYSTEM }
      - { label: Legal entity, bind: "link($.LEGAL_ENTITY, 'legal-entity')" }
      - { label: Transit, bind: $.TRANSIT }
      - { label: Delivery versus payment, bind: $.DELIVERY_VERSUS_PAYMENT }

  # ladder over nested data: the deal's own events (no leg), the latest last.
  - id: lifecycle
    kind: ladder
    title: "Lifecycle (${size($.EVENTS[?!nonblank(@.LEG_ID)])} events)"
    rows: "$.EVENTS[?!nonblank(@.LEG_ID)]"
    highlight: "#index == size($.EVENTS[?!nonblank(@.LEG_ID)]) - 1"   # the latest event
    columns:
      - { label: Event, bind: "@.EVENT_TYPE" }
      - { label: Date, bind: "@.EVENT_DATE", fmt: date }

  # markdown: static notes for the people who read this view. The text is shown as written (plain text,
  # one paragraph); ${...} parts are evaluated, Markdown syntax is not rendered inside the panel.
  - id: notes
    kind: markdown
    title: Reading this view
    text: |
      Amounts are in ${$.CURRENCY}. A cash event is signed from the bank's side: negative is paid, positive received.
      The trade's value is the risk engine's (market-risk's trade valuation, TVAL ${$.TRADE_ID}).

  # provenance: how the view was built (Sutra and version, source, generation, business date).
  - { id: built, kind: provenance, title: How this view was built }

  # ---- The side column -----------------------------------------------------------------------------------

  # line: a curve from this document's own data. x and y name fields of each row; step holds each value to the next point.
  - id: pnl
    kind: line
    title: Notional profile, leg 1
    area: right
    rows: $.LEGS[0].CASH_FLOWS
    x: START_DATE
    y: NOTIONAL
    step: true
    fmt: amount0

  # line from ANOTHER entity: source reads the discount curve's own document and plots its points.
  - id: curve
    kind: line
    title: Discount curve
    code: CRV
    key: F5
    area: right
    source: "link($.LEGS[0].DISCOUNT_INDEX, 'ir-curve')"
    rows: $.POINTS                               # with a source, $ is the curve's document, not the trade
    x: TENOR
    y: ZERO_RATE
    fmt: price2
    unit: "%"

  # hbar: horizontal bars, one per row: label and value name fields of each row; group() sums a field by another.
  - id: dv01
    kind: hbar
    title: Payments by year
    code: PAY
    area: right
    rows: "group($.EVENTS[?nonblank(@.AMOUNT)], 'EVENT_DATE', 'AMOUNT', 'year')"
    fmt: signed0
    tone: sign

  # gauge: one value against a maximum (a limit, a budget). The text is value / max in fmt (pct0 by default).
  - id: dv01use
    kind: gauge
    title: Notional against a 500m counterparty guideline
    area: right
    value: "$.LEGS[0].NOTIONAL"
    max: "500000000"
    label: Guideline used

  # links: every entity this document refers to, resolved from the reference catalogue, with badges.
  - { id: refs, kind: links, title: Linked entities, code: REFS, area: right }

# ---- Function keys beyond the panel keys ------------------------------------------------------------------
keys:
  F7: "link($.COUNTERPARTY, 'counterparty-trades')"   # F7 opens every trade with the counterparty, from every source system
  F8: impact                                     # F8: what depends on this trade
  F9: raw                                        # F9: the raw JSON (field masks for roles without raw)

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
| `source` | Follows the link and reads the other entity (the curve) through the same sources and business date; any panel kind that reads data takes it. A source the viewer may not open shows "no access". |
| `key`, `keys` | Become the function-key bar; a panel key scrolls to the panel, a link key opens the entity. |
| Missing data | A path that is absent evaluates to nothing, and an extract's empty text is nothing to `nonblank`: the cell shows a dash or nothing, and a panel with no data says so. |

You should see, previewing this block against `MX-20000001` in the workbench (or with `POST /api/v1/studio/preview`, kind `trade`, id
`MX-20000001`):

| Where | Shows |
|---|---|
| Title | `[Rates · Interest rate swap (fixed/float)] MX-20000001 with CP-MERIDIAN-RE` (a link to `CPTY CP-MERIDIAN-RE`) |
| Strip | `Notional 242,000,000` (the label from the field name), `Currency AUD`, `Side Sell`, `Fixed rate 4.0829%`, `Maturity 2032-06-25`, `Status AMENDED v2` (highlighted), `Source MUREX`, `Portfolio BOOK-RATES-3` (a link) |
| Terms | `Trade date 2025-07-25`, `Start 2025-07-28`, `Floating index AONIA`, `Discount curve CRV-AUD-OIS` (a link), `Legs' notional 484,000,000`, `Flows 35`, `Resets of leg 2 90` |
| Legs | two columns: `Leg 1 · Receive FIXED` (Index *Fixed*, Frequency *Annual*, Calendar *GBLO*, Periods *7*) and `Leg 2 · Pay FLOAT` (Index *AONIA*, Frequency *Monthly*, Periods *28*) |
| Cash flows | six of the seven fixed periods (`20000001001 · 2025-07-28 · 2026-06-29 · 4.0829% · 242,000,000` first), a *Total* row of `1,694,000,000`, and `1 later flows` under it |
| Payments to come | the 29 cash events after 2026-09-30, the first (`2027-06-29 · Fixed · +9,880,618.00`) highlighted |
| Booking | `Status AMENDED`, `Last action AMEND (version 2)`, `Source system MUREX`, `Legal entity LE-NY`, `Transit 00107`, `Delivery versus payment NO` |
| Lifecycle | `Lifecycle (7 events)`: `BOOKED`, `CONFIRMED`, `CLEARED`, `AMENDED`, `CASH_SETTLED`, `NEXT_PAYMENT`, `MATURITY`, the last highlighted |
| Reading this view | `Amounts are in AUD. A cash event is signed from the bank's side: …` (the `${$.CURRENCY}` evaluated) |
| Notional profile, leg 1 | a flat step line at 242,000,000 from 2025-07-28 over seven periods |
| Discount curve | twelve points from `1M` (3.5837) to `30Y` (4.2533) and a `CRV-AUD-OIS` link |
| Payments by year | one bar a year, `2025 −3,713,969` to 2032 |
| Notional against a 500m counterparty guideline | a bar at 48% (242,000,000 / 500,000,000) |
| Function keys | `F2 Terms`, `F3 Legs`, `F4 Cash flows`, `F5 Discount curve`, `F7 Counterparty trades`, `F8 Impact`, `F9 Raw JSON` |

## Top level

| Key | Required | Type | Default | Meaning |
|---|---|---|---|---|
| `rachana` | yes | integer | | The Rachana language version: `1`. Missing or another number is `DRS-2009`. Write it first. |
| `sutra` | yes | text | | Name: lower-case kebab matching `[a-z][a-z0-9-]{1,63}` (2–64 characters, starting with a letter), e.g. `irs-fixfloat`. |
| `version` | yes | integer ≥ 1 | | `name@version` is unique across all directories (`DRS-2028`). `version: "3"` (quoted) is text and is refused (`DRS-2020`). |
| `description` | | text | | One paragraph for people: what the layout shows and for which entities. Not used to build the view. |
| `notes` | | text | | Longer plain-text notes for authors and reviewers (`notes: \|` for several lines). Not used to build the view. |
| `domain` | | text | the parent folder's name | Grouping in the workbench and the catalogue, and the folder it saves into. |
| `match` | yes | mapping | | `{kind, where?, priority?}`: `kind` and `where` are text, `priority` a whole number (`DRS-2012` otherwise, as for `kind: 42` or `priority: high`); see [Matching](#matching-choosing-a-sutra-for-a-document). |
| `title` | | mapping | `{id: $.id}` | `{pill?, id, with?}`: a title written out names its `id` (`DRS-2010` otherwise); see [Title](#title). |
| `strip` | | list | empty | Up to **8** header figures; see [Strip](#strip). |
| `controls` | | list | empty | Up to **12** controls the reader sets (dropdowns, date ranges, sliders, search ...), read in expressions as `ctl.<id>`; see [Controls](#controls). |
| `panels` | | list | empty | The panels, in order, and `{ use: <block> }` entries; see [Panels](#panels). |
| `keys` | | mapping | empty | Function key → action; see [Function keys](#function-keys). |

Any other top-level key is `DRS-2011` (`unknown key 'pannels' in top level`). A Sutra with no panels is valid: the view then shows
the title and strip only.

## Names, versions and domains

- A Sutra is identified by `name@version`, for example `irs-fixfloat@1`. The workbench, the API
  (`GET /api/v1/sutras/irs-fixfloat/1`) and the governance log all use this pair.
- **Several versions of one name can be loaded at once** (`irs-vanilla.v2.sutra.yaml` and `irs-vanilla.v3.sutra.yaml`). Only the
  **latest** version of each name takes part in matching; older versions stay available by `name@version` (the workbench,
  `GET /api/v1/sutras/{name}/{version}`), so a reference to an older layout can still be reproduced. To roll back, delete or rename the
  newer file, or publish a higher version with the old content.
- `GET /api/v1/sutras` lists the latest version of every name with all its versions:

  ```bash
  curl -s -H "$AUTH" $DRISHTI/sutras | python3 -c "
  import json, sys
  for s in json.load(sys.stdin):
      if s['name'] in ('irs-fixfloat', 'endur-comswap', 'counterparty'): print(s)"
  ```

  You should see the three entries, for example
  `{'name': 'irs-fixfloat', 'latest': 1, 'versions': [1], 'domain': 'murex', 'kind': 'trade', 'where': "$.SOURCE_SYSTEM == 'MUREX' && $.PRODUCT_TYPE == 'IRS_FIXFLOAT'", 'priority': 10}`
  and `{'name': 'counterparty', … 'domain': 'counterparty-and-legal', 'kind': 'counterparty', 'where': None, 'priority': 10}`.
- `GET /api/v1/sutras/{name}/{version}/source` returns the file's YAML (`text/yaml`), comments included, exactly as loaded (a
  product Sutra's `use:` entries as written; `sutra lint` and a preview see them expanded).
- The **domain** defaults to the name of the folder holding the file (`config/packs/trading/sutras/murex/…` → `murex`). It is a label
  only (the catalog groups by it; the workbench saves back there): it never decides what the Sutra matches
  ([Folders mean nothing](#folders-mean-nothing)).

### Shared panel blocks (`use:`)

Many Sutras of one pack lay out the same parts: every trade has a deal ticket, legs, cash flows and events, whatever its product. Write
those panels once as a **block** and `use:` it from each Sutra. A block is a file `<name>.v<N>.block.yaml` anywhere in a Sutra
directory (the trading pack keeps its six in `sutras/common/`). This is the start of the shipped `legs` block:

```yaml
rachana: 1
block: legs                     # the name, as in the file name
version: 1                      # the version, as in the file name
description: "The legs side by side: each leg's terms, notional profile (a step line), fixings and cash flows opening to their resets."
params: { rate-fmt: price4 }    # every parameter with its default: pct4 for rates (a fraction a year), price4 for prices per unit
panels:
  - id: legs
    kind: tabs
    title: Legs
    code: LEGS
    key: F3
    each: $.LEGS
    layout: columns
    panels:
      - id: leg-terms
        kind: kv
        title: Terms
        columns:
          - { label: Index, bind: "nonblank(@.INDEX.NAME, '–')" }
          - { label: Spread, bind: "@.FLOAT_SPREAD", fmt: "%{rate-fmt}" }   # %{param} is replaced by its value
          …
```

In a Sutra's `panels:` (at the top or inside a tab), `{ use: legs@1, with: { rate-fmt: pct4 } }` is replaced by the block's panels, each
`%{param}` filled from `with:` or the default. `use: legs` (no version) takes the newest version, so a pack that changes a block in a
way its Sutras should not follow writes `legs.v2.block.yaml` and moves Sutras one at a time. The panels expanded are ordinary panels:
the Sutra's own rules (unique ids, keys, options) apply to the result, and `sutra lint` / `sutra test` check every Sutra with its blocks
expanded.

The trading pack's six: `deal-ticket` (the DES ticket; parameters `rate-fmt` and `product`, the product type in words), `legs`,
`flow-schedule` (every flow of every leg in one table; `rate-fmt`), `events` (the events timeline and the events table that the Sutra's
`legs` and `when` controls filter), `payments` (the netted payment ladder and payments by month) and `deal-footer` (provenance and
linked entities). Each of the pack's 130 product Sutras is a title, a strip, the two controls and its `use:` lines, plus a product's own
panels.

- A block is not a Sutra: it matches nothing and is never chosen; only Sutras that `use:` it show its panels.
- A block may not `use:` another block, and a `with:` naming a parameter the block does not declare is an error.
- Blocks load with the Sutras of the same directories (site and packs), before them.
- Mistakes are `DRS-2048`: `no block deal-ticket@3 (blocks: deal-footer, deal-ticket, events, legs, flow-schedule, payments)`,
  `block 'legs' has no parameter 'colour'`, `block 'nested' uses another block`, `name it legs.v1.block.yaml`.

### Folders mean nothing

A Sutra directory is read recursively. Sub-folders (`sutras/common/`, `sutras/endur/`, `sutras/calypso/`, `sutras/murex/`,
`sutras/pages/`) are only for people: Drishti infers **nothing** from a folder's name, not a source system, a kind or a priority. What
a Sutra lays out is decided by its own `match:` alone (`where: "$.SOURCE_SYSTEM == 'ENDUR' && $.PRODUCT_TYPE == 'ComSwap'"`), so moving
a file to another folder changes nothing it shows. The folder name is kept only as the Sutra's *domain*, a label the catalog groups by
and the folder a Sutra saved from the workbench is written back to.

### Where Sutras come from, and who wins

| Source | Setting | Order |
|---|---|---|
| Site directories | `drishti.rachana.dirs` (default `./sutras`, env `DRISHTI_SUTRAS`) | scanned first |
| Enabled packs | each pack's `sutras/` folder (or its `sutras:` key), added by the pack loader as `drishti.rachana.pack-dirs` | after the site, most general pack first |

Directories are scanned recursively; files are read in sorted path order. When two files define the same `name@version`:

| Both in… | Result |
|---|---|
| two packs | the more specific pack (later in the pack order) **overrides** the general one, and the log says `sutra trade-x@1 from … overrides the one in …`. |
| a site directory and a pack | the site file is kept; the pack's file is reported as `DRS-2028 … is already defined in <site file>`. |
| two site files | the first in path order is kept; the other is reported as `DRS-2028`. |

## Matching: choosing a Sutra for a document

```yaml
match:
  kind: trade                                   # required: the entity kind (the mnemonic's kind, e.g. TRD → trade)
  where: "$.SOURCE_SYSTEM == 'MUREX' && $.PRODUCT_TYPE == 'IRS_FIXFLOAT'"   # optional Rachana-EL predicate over the document
  priority: 10                                  # optional integer, default 0; higher is tried first
```

`SutraMatcher` does exactly this for every view:

1. Take the latest version of every Sutra whose `match.kind` equals the entity's kind.
2. Sort them by `priority`, highest first; Sutras with equal priority are taken in name order (`a` before `b`).
3. Evaluate each `where` against the document, in that order. The first that is truthy wins. A Sutra without `where` always matches, so
   it is the catch-all for its kind; give it a low priority.
4. If none matches, the view is built by inference alone (`Layout: inference only`).

There is no separate classifier file: `match` is the classifier. There is no automatic "specificity": a Sutra with a longer `where`
does not win by itself, only `priority` decides the order. Folders and file names play no part.

Recommended priorities, as the shipped packs use them:

| Priority | Use |
|---|---|
| 0–1 | catch-alls (no `where`), and drafts started from inference (the workbench's inference draft writes `priority: 1`; `sutra gen --fallback` writes `<kind>-default` at 1) |
| 10 | the normal Sutras of a pack: one per kind, or for trades one per source system and product type (`where: "$.SOURCE_SYSTEM == 'MUREX' && $.PRODUCT_TYPE == 'IRS_FIXFLOAT'"`) |
| 20–50 | special cases that must beat the normal Sutra (a desk's own variant, a matured-trade layout) |

Example: a layout for matured Murex swaps.

```yaml
# irs-matured.v1.sutra.yaml                    # tried first (priority 20)
match: { kind: trade, where: "$.SOURCE_SYSTEM == 'MUREX' && $.PRODUCT_TYPE == 'IRS_FIXFLOAT' && $.TRADE_STATUS == 'MATURED'", priority: 20 }

# irs-fixfloat.v1.sutra.yaml (trading pack)    # tried second (priority 10)
match: { kind: trade, where: "$.SOURCE_SYSTEM == 'MUREX' && $.PRODUCT_TYPE == 'IRS_FIXFLOAT'", priority: 10 }
```

`MX-20000001` has `"TRADE_STATUS": "AMENDED"`, so `irs-matured` does not hold and `irs-fixfloat` is used. To check which Sutra a
document gets:

```bash
curl -s -H "$AUTH" $DRISHTI/views/trade/MX-20000001 | python3 -c "import json,sys; print(json.load(sys.stdin)['provenance']['layout'])"
```

You should see `Sutra irs-fixfloat v1 + inference`. For a whole pack, **Admin → Packs → <pack> → Sutra coverage**
(`GET /api/v1/packs/{name}/coverage`) lists, for each combination of the fields its Sutras match on (for trades: source system,
product type and sub type), the number of documents, an example, the Sutra(s) that match and the `when` panels shown, and flags
combinations with *none* or *several*: on the demo lakes, `trade 132 combinations, none 0, several 0`.

Things to know:

- `where` is evaluated for every view (and on every live tick), so keep it cheap: compare fields, avoid long filters.
- `where` sees only `$` (there is no row). Comparing a missing field gives false, never an error.
- Name the source system with the product type: two systems may book the same product type.
- `priority` must be a YAML integer; `priority: high` is `DRS-2012`.
- A product's sub types are best served by `when:` panels in the product's one Sutra ([Keys every panel takes](#keys-every-panel-takes)),
  not by Sutras of their own, so every document matches exactly one Sutra.

## Title

```yaml
title:
  pill: "${$.PRODUCT_GROUP} · ${$.PRODUCT_SUB_TYPE}"   # template text (optional)
  id: $.TRADE_ID                                       # expression (required in a written title)
  with: "link($.COUNTERPARTY, 'counterparty')"         # expression (optional)
```

| Key | Kind of value | Default | Shown as |
|---|---|---|---|
| `pill` | template: plain text with `${expression}` parts | none | the small box before the identifier |
| `id` | expression, required in a written `title` (`DRS-2010 missing 'id' in title` otherwise) | `$.id` when there is no `title` at all | the large identifier; when it evaluates to empty, the entity's own id is shown (the packs' documents carry `TRADE_ID`, `COUNTERPARTY_ID`…, not `id`) |
| `with` | expression | none | `with <value>` after the identifier; a `link(...)` value is clickable |

Every part is best effort: if one fails to evaluate on a document, that part is left out and the view still opens. Note that `pill` is
not compiled at load time (only `id` and `with` are), so a typo in a pill template shows up as a missing pill rather than a `DRS-2101`.
Unknown keys under `title` are `DRS-2011`.

## Strip

The strip is the row of up to eight headline figures under the title.

```yaml
strip:
  - { label: Notional, bind: "$.CURRENCY + ' ' + fmt(first($.LEGS).NOTIONAL, 'amount0')", emphasis: true }
  - { label: Status, bind: "$.TRADE_STATUS + ' v' + $.TRADE_VERSION", tone: status }
```

| Key | Required | Meaning |
|---|---|---|
| `bind` | yes | Expression for the value. |
| `label` | | Plain text (not a template). When absent, see [Labels](#labels). |
| `fmt` | | A [format](#formats) name. Unknown names fall back to plain text. |
| `tone` | | A [tone](#tones). |
| `emphasis` | | `true` highlights the figure (one per strip is the convention). |

More than eight items is `DRS-2026`. An item whose expression fails on a document (a field of the wrong type) shows empty instead of
failing the view. Each strip cell carries the document path it reads (`"path": "$.TRADE_STATUS"`), which live updates use to flash
the cell that changed.

## Labels

Every strip item and column may give a `label`. When it does not, the label is the name of the field it reads (the last segment of the
first path in the expression), in words: `bind: $.COUNTERPARTY_COUNTRY_OF_RISK` reads as **Counterparty country of risk**,
`@.PAYMENT_DATE` as **Payment date**, `$.KYC.EMIR_CLASSIFICATION` as **EMIR classification**. Where a label comes from, first match
wins:

1. `label:` in the Sutra;
2. the **pack taxonomy**: `labels:` in the semantics file of an enabled pack (`config/packs/<pack>/config/semantics.yaml`), e.g.
   `labels: { NET_MTM: Net MTM (USD) }`; a more specific pack wins over a general one;
3. the **global taxonomy**: `labels:` in the core semantics (or the site's replacement, `drishti.inference.semantics-file`);
4. the field name in words, spelled with the packs' and the core's `acronyms:` (the `core` pack lists MTM, PV, DV01, PFE, EE, CVA, VaR,
   KYC, EMIR, LEI, UEN, FX, OIS…): `PFE_PEAK` → *PFE peak*, `PD_1Y` → *PD 1y*, a camel-case `dv01ByTenor` → *DV01 by tenor*.

An expression that reads no field (`"'Fixed'"`) gets the expression text itself as its label, so give such columns a label. Inference
labels the fields it lays out the same way.

## Panels

A panel is one box of the view. Panels are drawn in the order written, in the main column, or in the side column with `area: right`.
Each column is a grid of 12 columns: `span` makes a panel narrower (panels narrower than their column sit side by side, in order) and
`height` gives it a fixed height in rows. Users may rearrange a view for themselves in
[layout mode](USER_GUIDE.md#layout-mode-arrange-a-view-your-way); that never changes the Sutra, and an author may promote such a layout to
the Sutra's next version, written with these same keys.

An entry of `panels:` may also be `{ use: <block>[@<version>], with: { <param>: <value> } }`, replaced by the panels of a
[shared block](#shared-panel-blocks-use).

### Keys every panel takes

| Key | Required | Default | Meaning |
|---|---|---|---|
| `id` | yes (except a `tabs` body) | | Unique within the Sutra (`DRS-2024`). Used by function keys, live patches, controls (`panel:`), `on-select` and CSV export. Any text; keep it short and kebab or camel case. |
| `kind` | yes | | One of the 22 [panel kinds](#panel-kinds) (`DRS-2021` otherwise). |
| `title` | | none | A template: `"Cash flows · leg ${$.LEGS[0].LEG_ID}"`. If it cannot be rendered for a document it is shown as written. |
| `key` | | none | A function key `F1`–`F12`, unique across panels and `keys` (`DRS-2025`). Pressing it scrolls to the panel and flashes it. Avoid `F1`, which the console uses for help. |
| `code` | | none | A short tag shown at the right of the panel header (`CRV`, `DES`). |
| `area` | | `main` | `main` or `right` (any case; anything else is `DRS-2027`). |
| `span` | | `12` | Width in columns of the 12-column grid of its column: a whole number from 1 to 12 (`DRS-2030` otherwise). `span: 8` and `span: 4` share a row. On a phone every panel is full width. |
| `height` | | content | Height in grid rows of 2.5 rem: a whole number from 1 to 24 (`DRS-2030` otherwise). The panel scrolls inside; printing ignores it. Not on a `tabs` body or a panel inside a tab, which take the size of their panel (`DRS-2030`). |
| `source` | | | Expression giving a `link(id, kind)`: the panel reads that linked entity's document instead of this one (same sources, business date and field masks), so `$` in its options is the other entity. Every kind but `links` and `provenance` takes it; the title still reads this entity. Example: `{ kind: line, source: "link($.LEGS[0].DISCOUNT_INDEX, 'ir-curve')", rows: $.POINTS, x: TENOR, y: ZERO_RATE }`. A `line` with a source also refreshes live when that entity changes; other kinds refresh with the view. A source of a kind the viewer may not open is never fetched: the panel shows "no access to <kind>" and none of that entity's values. |
| `when` | | always | An expression over the document: the panel is shown only where it is true (left out, with no empty box, elsewhere). Any kind takes it, and so does a panel inside a tab (`@` is then the tab's element). Use it to give one product's sub types their own panels inside the product's one Sutra: `when: "$.PRODUCT_SUB_TYPE == 'ENGY-B-SWAP'"`, rather than a second Sutra for the sub type; the trading pack's `legs` block shows a leg's *Fixings* chart only `when: "size(flatten(@.CASH_FLOWS[*].RESETS)[?nonblank(@.RATE)]) > 0"`. |
| `where` | | all rows | On every kind with `rows` except `kv`: a condition on each row (`@` the row); only rows for which it is true are shown, drawn and totalled. Usually reads a control: see [Reading a control](#reading-a-control). |
| `on-select` | | | On `table`, `ladder` and `hbar`: a click on a row or bar sets a control; see [Reading a control](#reading-a-control). |
| `infer` | | `false` | Marks the panel as completed by inference (an *inferred* tag in its header). Inference fills a panel's columns whenever it states none, with or without this flag; see [Inference](../architecture/INFERENCE.md#sutra-and-inference-together). |
| `columns` | | empty | The columns or fields; see below. Used by `kv`, `table`, `ladder`, `tabs` (in the body) and `surface`. |
| `body` | `tabs` only | | The panel drawn once per tab. On any other kind, `DRS-2023 only 'tabs' panels take a 'body'`. |
| `panels` | `tabs` only | | Whole panels shown inside each tab; see [Panels inside a tab](#panels-inside-a-tab). |
| `description` | | none | Plain text for authors and the *About this page* drawer: what the panel shows. Not evaluated. |

Every other key of a panel is a **kind option**. Each kind accepts a fixed set (tables below); any other key is
`DRS-2023 option 'x' is not valid for 'kv' panels`, and a missing required option is `DRS-2022 'table' panel 'flows' needs option 'rows'`.

### Columns

```yaml
columns:
  - { label: Amount, bind: "@.AMOUNT", fmt: signed2, tone: sign, total: true }
  - { label: Trade, bind: "@.TRADE_ID" }
  - { label: Netting set, bind: "@.ID", link: true }
```

| Key | Required | Meaning |
|---|---|---|
| `bind` | yes | Expression. Inside `rows`/`each`, `@` is the row and `#index` its position. |
| `label` | | Plain text header or field label. When absent, see [Labels](#labels). |
| `fmt` | | Format name. In tables, a column with a format other than `date` or `text` is right-aligned as numeric. |
| `tone` | | Tone. |
| `total` | | `true` adds the column into the total row (`table`, `ladder`). |
| `link` | | `true` makes the value a link when it is an identifier a pack recognises (see [Links](#links)); a column bound to a field ending in `_ID` or `_REF` is linked without it. |

Any other key in a column is `DRS-2011 unknown key 'x' in column`.

### Panel kinds

There are 22 kinds (`PanelKind`). Required and accepted options, at a glance (each kind in depth, with when to use it, what it does
when empty, masked or live, and a screenshot: [PANELS.md](PANELS.md)). Besides these, every kind takes the keys of the table above
(`source` all but `links` and `provenance`; `where` the kinds with `rows` except `kv`; `on-select` `table`, `ladder` and `hbar`).

| Kind | Required options | Optional options | Renders | Uses `columns` |
|---|---|---|---|---|
| [`kv`](#kv) | | `rows`, `columns`, `fields` | label/value grid | yes |
| [`table`](#table) | `rows` | `totalLabel`, `limit`, `moreLabel`, `link`, `search`, [`pivot`](#pivot-a-pivot-tab-on-a-table-or-ladder), `children`, `expand`, [`levels`, `crumb`, `root`](#levels-drill-down-level-by-level), `empty` | table with optional total row and "N more"; a tree with `children`; a drill-down with `levels` | yes |
| [`tabs`](#tabs) | `each` | `tabTitle`, `layout`, `body`, [`panels`](#panels-inside-a-tab) | one sub-panel (or several panels) per element, as tabs or side by side | in `body` |
| [`line`](#line) | | `rows`, `x`, `y`, `mark`, `footer`, `unit`, `fmt`, `step` | line chart | no |
| [`area`](#area) | `rows` | `x`, `series`, `limit`, `limitLabel`, `unit` | area chart, several series, dashed limit | no |
| [`hbar`](#hbar) | `rows` | `label`, `value`, `fmt`, `tone`, `shape` | horizontal bars, or a donut of shares | no |
| [`ladder`](#ladder) | `rows` | `totalLabel`, `highlight`, `search`, [`pivot`](#pivot-a-pivot-tab-on-a-table-or-ladder), `children`, `expand`, [`levels`, `crumb`, `root`](#levels-drill-down-level-by-level), `empty` | table with highlighted rows | yes |
| [`links`](#links-panel) | | | linked entities with badges | no |
| [`status`](#status) | | `fields` | label/value grid, coloured by meaning | no (uses `fields`) |
| [`provenance`](#provenance) | | | how the view was built | no |
| [`markdown`](#markdown) | `text` | | static text | no |
| [`gauge`](#gauge) | `value` | `max`, `label`, `fmt` | one value as a bar against a maximum | no |
| [`surface`](#surface) | `rows`, `y` | `fmt`, `unit`, `view` | heatmap with a 3D toggle | yes (one per x point) |
| [`waterfall`](#waterfall) | `rows` | `label`, `value`, `total`, `sum`, `fmt`, `unit`, `colors` | floating bars from a start to an end | no |
| [`histogram`](#histogram) | `rows` | `value`, `bins`, `markers`, `fmt`, `unit` | binned distribution with marker lines | no |
| [`scatter`](#scatter) | `rows`, `x`, `y` | `size`, `label`, `group`, `fmt`, `xFmt`, `xLabel`, `yLabel` | one point per row | no |
| [`candlestick`](#candlestick) | `rows` | `x`, `open`, `high`, `low`, `close`, `volume`, `fmt`, `unit` | daily bars with volume | no |
| [`graph`](#graph) | `nodes` | `edges`, `label`, `group`, `layout` | nodes and edges, nodes open their entity | no |
| [`timeline`](#timeline) | `rows` | `date`, `label`, `detail`, `status`, `tone` | dated events with toned status | no |
| [`pivot`](#pivot) | `rows`, `by`, `across` | `value`, `agg`, `fmt`, `tone`, `heat`, `totals`, `expand` | aggregate table with totals and heat | no |
| [`metric`](#metric) | `value` | `label`, `fmt`, `tone`, `delta`, `deltaFmt`, `deltaTone`, `unit`, `caption` | a big-number KPI tile with its change | no |
| [`map`](#map) | | `highlight`, `region`, `link`, `shade`, `fmt`, `unit` | the world with a country highlighted, shaded and clickable; or one country's regions | no |

Which option values are expressions, which are field names, and which are plain text matters: only expressions and templates are
compiled at load time.

| Kind | Expressions (Rachana-EL) | Field names (of each row) | Plain values |
|---|---|---|---|
| `kv` | `rows` | | |
| `table` | `rows`, `moreLabel`, `children`, `crumb`, `root`, each `pivot` field's `bind`, each level's `rows`, `crumb` and column `bind`; `limit` when it reads a control | each `pivot` field written as a path | `limit` (integer), `totalLabel`, `search` (`false` hides the filter), `expand`, `empty`, each level's `title`, the rest of `pivot` |
| `tabs` | `each`, `tabTitle` | | `layout` |
| `line` | `rows`, `mark` | `x`, `y` | `unit`, `fmt`, `step` (true or false) |
| `area` | `rows`, `limit` | `x`, each series' `value` | `series[].label`, `series[].tone`, `limitLabel`, `unit` |
| `hbar` | `rows` | `label`, `value` | `fmt`, `tone`, `shape` (`bars` or `donut`) |
| `ladder` | `rows`, `highlight`, `children`, `crumb`, `root`, each `pivot` field's `bind`, each level's `rows`, `crumb` and column `bind` | each `pivot` field written as a path | `totalLabel`, `search` (`false` hides the filter), `expand`, `empty`, each level's `title`, the rest of `pivot` |
| `status` | each field's `bind` | | each field's `label`, `fmt`, `tone` |
| `gauge` | `value`, `max` | | `label`, `fmt` |
| `surface` | `rows`; `y` when it starts with `$` or `@` | `y` otherwise | `fmt`, `unit`, `view` |
| `markdown` | | | `text` is a template |
| `waterfall` | `rows` | `label`, `value`, `total` | `sum`, `fmt`, `unit`, `colors` (`gain-loss` or `theme`) |
| `histogram` | `rows`, each marker's `value` | `value` | `bins` (1–200), `markers[].label`, `markers[].tone`, `fmt`, `unit` |
| `scatter` | `rows` | `x`, `y`, `size`, `label`, `group` | `fmt`, `xFmt`, `xLabel`, `yLabel` |
| `candlestick` | `rows` | `x`, `open`, `high`, `low`, `close`, `volume` | `fmt`, `unit` |
| `graph` | `nodes`, `edges` | `label`, `group` (of each node) | `layout` (`tree` or `force`) |
| `timeline` | `rows` | `date`, `label`, `detail`, `status` | `tone` |
| `pivot` | `rows` | `by` (one field or a list), `across`, `value` | `agg` (`sum`, `count`, `avg`, `min`, `max`), `heat`, `totals` (`true`/`false`), `expand` (whole number or `all`), `fmt`, `tone` |
| `metric` | `value`, `delta`, `caption` (a template) | | `label`, `fmt`, `tone`, `deltaFmt`, `deltaTone`, `unit` |
| `map` | `highlight`, `region`, `shade.field`, `shade.by` | | `link` (`kind`, `id` template), `shade.kind`, `shade.fmt`, `shade.label`, `fmt`, `unit` |

**Field names.** On `line`, `area` and `hbar` a field name is matched exactly as the document writes it: `x: TENOR`, `label: ID`,
`value: NET_MTM`; `id` does not find `ID`, and an `@` expression there finds nothing (no error, no points). The defaults (`tenor`,
`value`, `label`) suit rows that `group()` makes, whose fields are `label`, `value` and `count`. For the seven kinds from `waterfall`
on (and `timeline`), a field name may also be a dotted path (`COUNTERPARTY.NAME`) or an expression over the row when it starts with
`@` or `$` (`y: "@.PNL / 1000"`, `label: "@.EVENT_TYPE + ' · leg ' + @.LEG_ID"`), and a plain name that the row does not have is also
looked up in UPPER_SNAKE: the defaults `date`, `open`, `close`, `label`, `value` read a production document's `DATE`, `OPEN`,
`CLOSE`, `LABEL`, `VALUE`. A graph's nodes and edges read `id`, `kind`, `from`, `to` and `label` in either case (`ID`, `FROM`).

Values outside the allowed set (`agg: median`, `layout: circle`, `colors: rainbow`, `bins: 0`, `heat: "yes"` (an unquoted `yes` is YAML
for `true`), a marker without `value`) are `DRS-2029`. So are values of the wrong type: `rows`, `each`, `nodes`, `edges` and `source`
must be expressions written as text (`rows: 5` is not), `markdown` `text` text, table `limit` a whole number from 1 (or an expression
reading a control), table and ladder `search` true or false, `kv` and `status` `fields` and `area` `series` lists.

Accepted but currently without effect (they parse, and do nothing yet): `fields` on `kv` (use `columns`), `link` on `table` (use
`link: true` on a column), `footer` on `line`, and `label` on `gauge` (carried in the view data, not drawn by the console).

When a panel's data does not fit (its `rows` is not a list, a chart's source is unavailable), the panel shows *No data available* (or
its `empty:` text) with the reason in a tooltip, and the rest of the view is unaffected.

#### kv

A grid of label/value pairs, four across in the main column and two across on the right.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `columns` | | | The fields: each `bind` is evaluated against the document, or against `rows` when given. |
| `rows` | | | Expression for one object; inside the columns `@` is that object. |
| `fields` | | | Accepted, no effect (see above); a list when written (`DRS-2029` otherwise). |

Without `columns` but with `rows`, inference lists every scalar field of the object (up to 16), labelled in words, and a nested object
that has a `name` shows that name. Without either, the panel is empty.

```yaml
  - id: terms
    kind: kv
    title: Deal ticket
    code: DES
    key: F2
    columns:
      - { label: Transaction, bind: $.TRANSACTION_ID }
      - { label: Trade date, bind: $.TRADE_DATE, fmt: date }
      - { label: Legal entity, bind: "link($.LEGAL_ENTITY, 'legal-entity')" }
      - { label: Discount curve, bind: "link($.LEGS[0].DISCOUNT_INDEX, 'ir-curve')" }

  - { id: kyc, kind: kv, title: KYC, area: right, rows: $.KYC }   # on a counterparty: fields inferred from the object
```

You should see, for `MX-20000001`: `Transaction 20000001002`, `Trade date 2025-07-25`, `Legal entity LE-NY` and
`Discount curve CRV-AUD-OIS` (both links); and for `CP-MERIDIAN-RE` the inferred `Status Approved`, `Risk rating Standard`,
`Last review 2026-02-11`, `EMIR classification FC`, `PD 1y 0` (unformatted: list a column with `fmt: pct2` to read `0.06%`). In the view
JSON a kv panel's data is `{"fields": [{"label": "Transaction", "text": "20000001002", "path": "$.TRANSACTION_ID"}, …]}`.

#### table

One row per element of `rows`.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `rows` | yes | | Expression giving a list. Anything else shows *No data available*. |
| `columns` | | inferred | One per column. Without columns, inference picks the scalar fields present in at least 60% of rows (up to 9). |
| `limit` | | all rows | A whole number, 1 or more: rows shown; or an expression reading a control (`limit: ctl.top`). The rest are counted in the "more" line. `limit: many`, `limit: -5` or `limit: "6"` (quoted) is `DRS-2029`. |
| `moreLabel` | | `"<N> more"` | Expression for the text of the "more" line, evaluated against the document (not a row). |
| `totalLabel` | | `Total` | Text of the total row's label cell. |
| `link` | | | Accepted, no effect (see above). |
| `children` | | none | Expression evaluated per row (`@` is the row) giving the rows nested under it, for example `children: "@.RESETS"`. A row that has children shows a ▸/▾ toggle and expands in place, indented, to any depth (up to `drishti.panels.tree-depth`, 12). Columns evaluate against each child with `@`. `total: true` columns sum the rows that have no children only, so nothing counts twice. A number, a list or a boolean is `DRS-2029`. A table with `children` is not sorted, paged or given per-column filters; its filter keeps the ancestors of the rows that match. |
| `expand` | | `1` | Levels shown open at first: `1` shows only the top level, `2` opens those to show their children, `all` opens every level. A whole number from 1 or `all` (`DRS-2029` otherwise). |
| `levels` | | none | A drill-down: the rows each row opens to, level by level, each level with its own `rows` (read with `@` the row above), `title`, `crumb` and `columns`; 1 to 6 levels. See [levels: drill down level by level](#levels-drill-down-level-by-level). Not with `children` or `pivot` (`DRS-2029`). |
| `crumb` | | `'#' + (#index + 1)` | With `levels`: an expression naming a first-level row in the breadcrumb (`"'Cash flow ' + @.FLOW_ID"`). |
| `root` | | the panel title | With `levels`: an expression, read against the document, for the first step of the breadcrumb (`"'Deal ' + $.TRADE_ID"`). |
| `empty` | | `No data available` | The words shown when there are no rows (the list is missing, null or empty): `empty: No events booked`. |
| `search` | | `true` | `false` hides the filter box (and the per-column filters) in the panel's heading, for a table too small or too fixed to need one. Table and ladder panels take it; on any other kind it is a problem (`DRS-2023`, *option 'search' applies only to panels that show a table*). `true` or `false` only (`search: maybe` is `DRS-2029`). |
| `pivot` | | none | Offers a **Pivot** tab beside the table: `true`, or the fields a user may pivot by and the arrangement it opens with ([below](#pivot-a-pivot-tab-on-a-table-or-ladder)). Without it there is no Pivot tab. |

Totals: when at least one column says `total: true`, a total row is added. It sums that column's numbers over **all** rows, including
those hidden by `limit` (values that are not numbers count as 0), formats the sum with the column's `fmt` and `tone`, and puts
`totalLabel` in the column just before the first totalled column (or the first column when the first totalled column is the first).

```yaml
  - id: trades
    kind: table
    title: Member trades
    code: TRD
    key: F3
    rows: $.TRADES
    limit: 4
    moreLabel: "(size($.TRADES) - 4) + ' more trades'"
    totalLabel: Net
    columns:
      - { label: Trade, bind: "@.TRADE_ID" }                # an id column: linked automatically
      - { label: Product, bind: "@.PRODUCT" }
      - { label: Notional, bind: "@.NOTIONAL", fmt: compact }
      - { label: Maturity, bind: "@.MATURITY", fmt: date }
      - { label: MTM, bind: "@.MTM", fmt: signed0, tone: sign, total: true }
```

This is the counterparty-risk pack's *Member trades* table with a limit and a net row added. You should see, for netting set
`NS-MERIDIAN-RE-NY` (343 trades), four rows starting `MX-30003114 · Overnight index swap · 832.8m · 2034-06-26 · −286,788,885`, the
trade ids as links, a `Net` row of `+99,704,064` (all 343, not the four shown), and `339 more trades`. The data is
`{"columns": [...], "numeric": [false, false, true, false, true], "rows": [{"cells": [...], "highlight": false, "path": "$.TRADES[0]"}], "total": {...}, "more": "…"}`.

#### `pivot`: a Pivot tab on a table or ladder

> This is the **`pivot:` option**, which gives a `table` or `ladder` an interactive Pivot tab the user drives. It is not the **`pivot`
> panel kind** (a fixed aggregate grid the Sutra defines: [`kind: pivot`](#pivot)). To choose between them, see the
> [Sutra developer guide](SUTRA_DEVELOPER_GUIDE.md#12-expandable-row-groups-pivot-by-lists-and-tree-tables).

A table or ladder may offer its rows as an interactive, Excel-style pivot: a **Table | Pivot** switch in the panel, where a user drags
fields into rows, columns, values and filters, with subtotals, totals, a chart, a drill-down to the rows under any cell, and export
([USER_GUIDE.md](USER_GUIDE.md#the-pivot-tab-slice-a-table-your-way)). It is **opt-in**: only a panel whose Sutra says `pivot:` has the
tab, and only the fields the Sutra offers can be used. Every other table stays a table.

```yaml
pivot: true                     # the panel's own columns are the fields; the tab opens with nothing arranged
pivot: false                    # the same as leaving it out: no Pivot tab
pivot:                          # the fields on offer, and the arrangement the tab opens with
  fields: [PRODUCT, ASSET_CLASS, CURRENCY, BOOK, MATURITY_BUCKET, { field: MATURITY, label: Maturity date },
           { field: NOTIONAL, fmt: compact }, { field: MTM, label: MTM (USD), fmt: signed0 }, TRADE_ID]
  rows: [PRODUCT]
  columns: [MATURITY_BUCKET]
  values: [{ field: NOTIONAL, agg: sum }]
  filters: [CURRENCY, ASSET_CLASS]
  heat: true
  chart: bar
```

| Key | Default | Meaning |
|---|---|---|
| `fields` | the panel's columns | The fields a user may pivot by, at most 40, in the order the field list shows them. A field is a **path over the row** (`CURRENCY`, `COUNTERPARTY.NAME`; `@.CURRENCY` is the same), or a mapping `{ field, bind, label, fmt }`: `field` is its name (letters, digits, `_`, `.` and `-`), `bind` an expression over the row (`@`) for a field that is not a plain path, `label` and `fmt` how it is shown. A field without a label takes the label of a column bound to the same expression, else its name in words; likewise its `fmt`. A field need not be a column: a table can show five columns and offer ten fields. |
| `rows` | none | Field names down the side, outermost first; at most 4. |
| `columns` | none | Field names across the top, outermost first; at most 4. A field may not be in both. |
| `values` | none | The numbers in the cells, at most 6, each `{ field, agg, show }`. `agg`: `sum` (the default), `count` (rows with a value), `avg`, `min`, `max` or `distinct` (how many different values). `show`: `value` (the default), `pctRow`, `pctColumn` or `pctTotal` (the cell as a share of its row's total, its column's total or the grand total). |
| `filters` | none | Fields offered as filters: a name (every value kept), `{ field, values: [USD, EUR] }` (only those values) or `{ field, min, max }` (an inclusive range of numbers, or of ISO dates written as text). Not both `values` and a range. |
| `heat` | `false` | Open with the cells shaded by their value. |
| `chart` | none | Open with a `bar`, `line` or `heatmap` chart of the result. |
| `totals` | `true` | `false` opens without subtotals and grand totals. |

With `pivot: true`, or a mapping without `fields`, the fields are the panel's columns: a column bound to a row path (`"@.MTM"`) is named
by the path (`MTM`); any other column by its label in kebab case (`Notional in USD` is `notional-in-usd`, `col3` when the label gives
nothing), and a repeated name gets `-2`. Write `rows`, `columns`, `values` and `filters` with those names.

Group keys are values as text (`(blank)` for a missing one) in natural order: numbers by value, and text with numbers in it by those
numbers (`2-5Y` before `10Y+`, `BOOK-9` before `BOOK-10`).

What a user arranges is kept for them (per Sutra and panel) and never changes the Sutra; an author may **promote** it, which writes this
option into the Sutra's next version through review: only that panel's `pivot:` key is rewritten (as a block mapping), its `fields` and
`totals` stay as written, and every other line and comment of the file is kept.

The pivot reads **every row** of the panel (the browser receives them when the tab is first shown, up to `drishti.pivot.max-records`,
50,000), not only the first `limit` the table shows. It sees what the table sees: the same document, business date and Sutra.

The shipped `netting-set` Sutra (counterparty-risk pack) offers this pivot on its *Member trades* (the `pivot:` above without `heat`
and `chart`). You should see, for `NS-MERIDIAN-RE-NY`, a **Table | Pivot** switch above the table; **Pivot** shows notional by product
down the side and the maturity buckets across, with row and column totals, over all 343 trades. In the view JSON the table's data
carries the offer: `"pivot": {"fields": [{"name": "PRODUCT", "label": "Product", "numeric": false, "fmt": null}, …],
"rows": ["PRODUCT"], "columns": ["MATURITY_BUCKET"], "values": [{"field": "NOTIONAL", "agg": "sum"}], "filters": [...], "heat": false,
"chart": null, "totals": true, "maxRows": 50000}`.

Mistakes are `DRS-2031` with the reason (*pivot rows name 'DESK', which is not one of its fields (ID, TRADES)*, *pivot value 'agg' must
be one of sum, count, avg, min, max, distinct, not 'median'*, *a pivot has at most 4 row fields and 4 column fields*); `pivot` on any
kind but `table` and ladder is `DRS-2023`; a field's `bind` that does not compile is `DRS-2101` when the Sutra loads. The JSON Schema
describes the option, so the workbench completes its keys, `agg`, `show` and `chart` and checks them as you type.

A pack opts a kind's **search results and pick lists** into the same Pivot tab with `pivot:` beside `columns:` in `pack.yaml`
([PACK_DEVELOPER_GUIDE.md](PACK_DEVELOPER_GUIDE.md#pivot-a-pivot-tab-on-search-results)).

#### tabs

One sub-panel per element of `each`, as tabs or side by side.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `each` | yes | | Expression giving a list. Inside the body, `@` is one element and `#index` its position. |
| `tabTitle` | | `'#' + (#index + 1)` | Expression for each tab's title. |
| `layout` | | `tabs` | `tabs` (one visible at a time) or `columns` (all side by side, for two or three legs). |
| `body` | | | A nested panel: `kind` is required (use `kv`), `id` is optional (`body`); only its `columns` are used. |
| `panels` | | | A list of panels shown inside **each** tab, under the body's fields (see [Panels inside a tab](#panels-inside-a-tab)). |

The body is validated like any panel, so a body of `kind: table` would need `rows` (`DRS-2022`); write it as `kind: kv`. A body without
columns gets the fields of the **first** element, inferred.

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
        - { label: Index, bind: "nonblank(@.INDEX.NAME, 'Fixed')" }
        - { label: Notional, bind: "@.NOTIONAL", fmt: amount0 }
        - { label: Frequency, bind: "@.RESET_CONVENTION" }
        - { label: Periods, bind: "size(@.CASH_FLOWS)" }
```

You should see, for `MX-20000001`, two boxes, `Leg 1 · Receive FIXED` (`Index Fixed`, `Notional 242,000,000`, `Frequency Annual`,
`Periods 7`) and `Leg 2 · Pay FLOAT` (`Index AONIA`, `Frequency Monthly`, `Periods 28`). With `layout: tabs`, the selected tab is kept
when a live update repaints the panel.

##### Panels inside a tab

A tab can hold whole panels, not only the fields of a `body`. `panels:` is a list; each entry is an ordinary panel with an `id` that is
unique among the tab's panels, and it is read with `@` the tab's element, so `rows: "@.CASH_FLOWS"` is the cash flows of *this* leg.
The tabs come from `each`, one per element, **whatever the count** (a cap has one leg, a commodity swap three or four).

| Rule | Why |
|---|---|
| the kind is `kv`, `status`, `table`, `ladder`, `hbar`, `markdown`, `metric`, `line`, `area` or `timeline` | the other charts, `links`, `pivot` and `provenance` draw things of the whole view (`DRS-2023`); a line, area or timeline of one element (a leg's notional profile, its fixings) is drawn in its tab, and with `layout: columns` the legs stand side by side, each with its own charts |
| no `tabs` inside a tab | one level of tabs; deeper structure is a tree table (below) or a table that drills level by level ([`levels`](#levels-drill-down-level-by-level)) (`DRS-2023`) |
| no `pivot:` on a table inside a tab | the Pivot tab reads the whole panel by its id (`DRS-2023`) |
| no `span` or `height` | the panels take the size of their tabs panel (`DRS-2030`) |
| an `id` on each, unique within the tab (`DRS-2010`, `DRS-2024`) | the live view and the CSV name the panels by it |
| `panels:` on any other kind | `DRS-2023`: only `tabs` take it |

A table inside a tab can be a **tree whose children come from a nested array of the row**: `children: "@.RESETS"` evaluates, for each
cash flow, to that cash flow's own resets, which open under it. The child rows are another kind of row than the cash flows (a reset has
a reset date and a rate, no payment date), so the columns read whichever field is there: `nonblank(@.PAYMENT_DATE, @.RESET_DATE, '–')`.
A cash flow with no resets (a fixed one) is a leaf with no toggle. Do not total a column the resets also carry (the tree adds up only
the rows with no children). This is the trading pack's `legs` block (shortened):

```yaml
  - id: legs
    kind: tabs
    title: Legs
    each: $.LEGS
    layout: columns
    tabTitle: "'Leg ' + @.LEG_ID + ' · ' + nonblank(@.LEG_ECONOMIC_TYPE + ' ' + @.LEG_TYPE, @.LEG_TYPE, '–')"
    panels:
      - id: leg-notional
        kind: line
        title: Notional profile
        rows: "@.CASH_FLOWS"
        x: START_DATE
        y: NOTIONAL
        step: true
        fmt: amount0
      - id: leg-fixings
        kind: line
        title: Fixings
        when: "size(flatten(@.CASH_FLOWS[*].RESETS)[?nonblank(@.RATE)]) > 0"
        rows: "flatten(@.CASH_FLOWS[*].RESETS)[?nonblank(@.RATE)]"
        x: START_DATE
        y: RATE
        fmt: pct4
      - id: leg-flows
        kind: table
        title: Cash flows and resets
        rows: "@.CASH_FLOWS"
        children: "@.RESETS"
        expand: 1
        columns:
          - { label: Flow / reset, bind: "nonblank(@.FLOW_ID, @.RESET_ID, '·')" }
          - { label: Pay / reset date, bind: "nonblank(@.PAYMENT_DATE, @.RESET_DATE, '–')" }
          - { label: Notional, bind: "@.NOTIONAL", fmt: amount0, total: true }
          - { label: Rate / price, bind: "@.RATE", fmt: pct4 }
```

On `MX-20000001` you should see two columns; the fixed leg has its notional profile and seven cash flows and no *Fixings* chart (its
`when` is false: a fixed leg has no resets); the floating leg (titled `Leg 2 · Pay FLOAT · AONIA`: the block adds the index) has its fixings chart and 28 cash flows, each with a ▸ that
opens its resets (`R-1 · 2025-07-28 · 3.5376%`, `R-2 …`). A live update repaints the panel, keeps the selected tab and the rows you
opened, and shows a moved fixing in its nested cell. Without a Sutra, inference lays such a document out the same way: a tab per
element, the element's fields, and a table for each list it holds, a tree when those rows hold lists again (PACK_DEVELOPER_GUIDE.md,
Modelling deep documents). The worked pictures are in the [User guide](USER_GUIDE.md#a-deep-trade-legs-cashflows-and-fixings).

#### levels: drill down level by level

A tree (`children`) opens rows in place with the same columns at every depth. When each level is a different thing (a deal's legs, each
leg's cash flows, each cash flow's resets) give the table **`levels`**: the table shows one level at a time, each with its own columns,
count and total, and a row that has rows under it opens them in place of the level, with the way back on screen:

```yaml
  - id: legs-cash-flows
    kind: table
    title: Cash flows
    rows: "@.CASH_FLOWS"                         # inside a tab of legs: @ is the leg
    crumb: "'Cash flow ' + @.FLOW_ID"            # a row's name in the breadcrumb
    root: "'Deal ' + $.TRADE_ID + ' › Leg ' + @.LEG_ID"
    empty: No cash flows
    columns:
      - { label: Payment date, bind: "@.PAYMENT_DATE", fmt: date }
      - { label: Notional, bind: "@.NOTIONAL", fmt: amount0, total: true }
      - { label: Resets, bind: "size(@.RESETS)", fmt: amount0 }
    levels:
      - rows: "@.RESETS"                         # what a cash flow opens to, read with @ the cash flow
        title: Resets
        crumb: "'Reset ' + (#index + 1)"
        columns:
          - { label: Reset date, bind: "@.RESET_DATE", fmt: date }
          - { label: Rate, bind: "nonblank(@.RATE, '-')" }
```

You should see the cash flows with a count beside the title; the first cell of each row is a link with the number of resets it holds
(on the floating leg of `MX-20000001`, `2025-09-29 … 2 ▸`). Opening it (a click, or ↑ ↓ to the row and Enter or →) shows the breadcrumb
*Deal MX-20000001 › Leg 2 › Cash flow 20000001008 › Resets*, the cash flow's own values as a ticket under it, then its resets with their
count and total. **Esc**, Backspace or ← goes up one level (the row you came from is selected), a breadcrumb step goes straight to that
level, and the browser's Back does too: every level is in the address (`?dr.legs-cash-flows=1`, and `~2` for the second tab of a tabs
panel), so a copied address opens the same level, in the same tab. Every level's table sorts, filters and pages like any table.

| Key of a level | Required | Meaning |
|---|---|---|
| `rows` | yes | Expression giving the rows a row of the level above opens to, read with `@` that row (`"@.RESETS"`); a missing or empty list is a level with no rows (it says *No resets*), never an error. |
| `title` | | What the rows of the level are; the caption above them and the last step of the breadcrumb. |
| `crumb` | | Expression naming one row of this level in the breadcrumb (default its number). |
| `columns` | | As a table's (`label`, `bind`, `fmt`, `tone`, `total`, `link`); a `total: true` column adds up the rows of the level under each row. |

Up to 6 levels; every expression is checked when the Sutra loads (`DRS-2101`), the shape of `levels` too (`DRS-2029`). The rows are bound
on the server once, bounded like a tree by `drishti.panels.tree-rows`; the console only shows and hides them. Build → New pack writes this
for a document whose arrays hold arrays ([Schema to pack, A deep trading JSON](SCHEMA_TO_PACK.md#appendix-a-deep-trading-json)).

#### line

A line chart: one point per row.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `rows` | | `points` | Expression giving the list of points. |
| `x` | | `tenor` | Field of each row for the x axis (shown as text), written exactly as the document writes it. |
| `y` | | `value` | Field of each row for the y value (a number). |
| `mark` | | | Expression (against the main document) giving one x value to highlight; its value is written under the chart: `5Y point 4.03%`. |
| `fmt` | | | Format of the mark's value. |
| `unit` | | | Unit for the axis and the mark text; `%` is appended without a space, anything else after a space. |
| `step` | | `false` | `true` draws the line as steps: a value holds until the next point (a leg's notional schedule, an amortising principal). |
| `footer` | | | Accepted, no effect. |

With a `source`, the linked entity is fetched (same sources, same business date) and `rows` is evaluated against **its** document. The
series is named after the panel title. When `source` names an entity that has not arrived within the link budget, the panel shows *No
data available* (`waiting for CRV-AUD-OIS`) and fills in on the next build; when it does not exist, `CRV-AUD-OIS unavailable`. The
source entity is shown as a link under the chart.

```yaml
  - id: curve
    kind: line
    title: Discount curve
    code: CRV
    key: F5
    area: right
    source: "link($.LEGS[0].DISCOUNT_INDEX, 'ir-curve')"
    rows: $.POINTS                  # a field of the curve's document
    x: TENOR
    y: ZERO_RATE
    mark: "'5Y'"
    fmt: price2
    unit: "%"
```

You should see, for `MX-20000001`, twelve points from `1M` (3.5837) to `30Y` (4.2533), `5Y point 4.03%` under the chart and a
`CRV-AUD-OIS` link. The data is
`{"x": ["1M", …], "series": [{"label": "Discount curve", "values": [3.5837, …], "tone": "link"}], "mark": "5Y", "source": {"kind": "ir-curve", "id": "CRV-AUD-OIS", "mnemonic": "CRV"}}`.

#### area

An area chart with one or more series over a common x axis, and an optional dashed limit line (exposure profiles).

| Option | Required | Default | Meaning |
|---|---|---|---|
| `rows` | yes | | Expression giving the list of points. |
| `x` | | `tenor` | Field of each row for the x axis. |
| `series` | | none | A list of `{label, value, tone}`: `value` names a field of each row; `tone` colours the series (`link`, `accent`, `pos`, `neg`). Without `series` the chart is empty. Anything but a list (`series: 5`) is `DRS-2029`. |
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
    rows: $.PROFILE
    x: TENOR
    limit: $.LIMIT
    series:
      - { label: Expected exposure, value: EE, tone: link }
      - { label: PFE 95, value: PFE, tone: accent }
```

This is the shipped `netting-set` Sutra's. You should see, for `NS-MERIDIAN-RE-NY`, two filled series over ten tenors from `0`, the
expected exposure peaking at 483.3m and the PFE at 1.1bn, and a dashed line at the limit, 1,696,000,000. The data is
`{"x": ["0", "1M", …], "series": [{"label": "Expected exposure", "values": [...], "tone": "link"}, …], "limit": 1696000000.0}`.

#### hbar

Horizontal bars, one per row, scaled to the largest absolute value; or, with `shape: donut`, a ring of shares.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `rows` | yes | | Expression giving the list. `group(list, 'key', 'value', 'by'?)` makes such a list from any rows. |
| `label` | | `label` | Field of each row for the bar's label, written exactly as the document writes it. |
| `value` | | `value` | Field of each row for the bar's number. Rows whose value is not a finite number are skipped. |
| `fmt` | | plain | Format of the number written beside each bar. |
| `shape` | | `bars` | `donut` draws the same rows as a ring of shares of their total (the total, formatted with `fmt`, in the middle; the rows, their amounts and shares listed under it). Use it for a share of a whole: notional by product type. Negative values count by their size. |
| `tone` | | by sign | Tone of each bar; without it, negative bars are `neg` and the others `pos`. |

```yaml
  - id: by-product
    kind: hbar
    title: Notional by product type
    code: PROD
    key: F4
    area: right
    shape: donut
    rows: "group($.TRADES, 'PRODUCT_TYPE', 'NOTIONAL')"
    fmt: compact
```

This is the trading pack's *counterparty trades* page (`/v/counterparty-trades/CP-NORTHPLAINS`). You should see a ring with
`288.4bn` in the middle: the counterparty's 1,352 trades are all Endur `ComSwap`. A page of a counterparty with several product types
shows one slice each. The bars form, `rows: "group($.EVENTS[?nonblank(@.AMOUNT)], 'EVENT_DATE', 'AMOUNT', 'year')"` with
`fmt: signed0` and `tone: sign`, gives the payments of a trade by year (`2025 −3,713,969` first, on `MX-20000001`).

#### ladder

A table whose rows can be highlighted: settlement ladders, lifecycle events, the next payment.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `rows` | yes | | Expression giving the list. |
| `columns` | | inferred | As for `table`. |
| `highlight` | | none | Expression evaluated per row (`@`, `#index` available); rows where it is truthy are highlighted. |
| `totalLabel` | | `Total` | As for `table`; columns with `total: true` are summed. |
| `search` | | `true` | As for `table`. |
| `children` | | none | As for `table`: the rows nested under each row, a tree. |
| `expand` | | `1` | As for `table`. |
| `levels`, `crumb`, `root`, `empty` | | | As for `table`. |
| `pivot` | | none | As for `table`: a [Pivot tab](#pivot-a-pivot-tab-on-a-table-or-ladder) over the ladder's rows. |

A ladder does not accept `limit` or `moreLabel` (`DRS-2023`): it always shows every row.

```yaml
  - id: ladder
    kind: ladder
    title: Fixed leg, the next payment lit
    rows: $.LEGS[0].CASH_FLOWS
    highlight: "@.PAYMENT_DATE == first($.LEGS[0].CASH_FLOWS[?@.PAYMENT_DATE > today()]).PAYMENT_DATE"
    columns:
      - { label: Payment, bind: "@.PAYMENT_DATE", fmt: date }
      - { label: Rate, bind: "@.RATE", fmt: pct4 }
      - { label: Status, bind: "@.PAYMENT_DATE < today() ? 'Paid' : 'Future'", tone: status }
      - { label: Notional, bind: "@.NOTIONAL", fmt: amount0, total: true }
```

You should see, for `MX-20000001` on the business date 2026-10-16, seven flows: `2026-06-29 · 4.0829% · Paid`, then
`2027-06-29 … Future` highlighted (the next payment), and a total row of `1,694,000,000`. Pick an earlier date in the top bar and the
highlight moves: `today()` is the business date of the view.

#### links panel

Every entity the document refers to, in document order, without duplicates and without the entity itself. No options. Which fields count
as references, and the kind each points to, comes from the packs' `graph.fields` (for example `COUNTERPARTY → counterparty`,
`PORTFOLIO → book`); a field may hold an id, a list of ids, or an object with `id` (and `name`, which becomes the text). Each item is
fetched within the link budget and shows a **badge** from the pack's `graph.badges` (a counterparty's rating, an economy's growth).

```yaml
  - { id: refs, kind: links, title: Linked entities, code: REFS, area: right }
```

You should see, for `MX-20000001`, items such as `Counterparty CP-MERIDIAN-RE A`, `Legal entity LE-NY`, `Portfolio BOOK-RATES-3` and
`Trader TRDR-ASHAH`; for `CP-MERIDIAN-RE`, `Economy ECON-BMU +1.89 % growth`, `Group GRP-MERIDIAN` and `Credit limit LIM-MERIDIAN-RE`.
An item that did not arrive in time says `pending`; one that does not exist says `missing`. A Sutra with a links panel always reports
`+ inference` in its layout label, because the links are discovered rather than declared.

#### status

Operational fields (confirmation, clearing, settlement, booking), drawn like a kv grid.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `fields` | | none | A list of `{label, bind, fmt?, tone?}`. Each `bind` is an expression against the document. |

Always give each field a `label` (a field without one is labelled `null`). `tone: status` colours by meaning: text containing *fail*,
*reject*, *dispute* or *breach* is `bad`; *pending*, *unmatched* or *warn* is `warn`; any other non-empty text is `ok`.

```yaml
  - id: operations
    kind: status
    title: Booking
    code: OPS
    fields:
      - { label: Status, bind: $.TRADE_STATUS, tone: status }
      - { label: Last action, bind: "$.TRADE_ACTION + ' (version ' + $.TRADE_VERSION + ')'" }
      - { label: Delivery versus payment, bind: $.DELIVERY_VERSUS_PAYMENT }
      - { label: Transit, bind: $.TRANSIT }
```

You should see, for `MX-20000001`, `Status AMENDED` in the `ok` colour, `Last action AMEND (version 2)`, `Delivery versus payment NO`
and `Transit 00107`.

#### provenance

*How this view was built*. No options.

```yaml
  - { id: built, kind: provenance, title: How this view was built }
```

You should see three lines: `Layout Sutra irs-fixfloat v1 + inference`, `Fingerprint ddd1…092a` (a short form of the document's shape
fingerprint) and `Source trade-murex, gen …` (the connector and the document's generation, which goes up on every load or live tick).

#### markdown

Static text for the reader.

| Option | Required | Meaning |
|---|---|---|
| `text` | yes | A template: `${expression}` parts are evaluated against the document. |

The text is shown as plain text: Markdown syntax (`**bold**`, lists) is **not** rendered inside the panel. The `markdown` panel is for the
people who *read* the view; notes for the people who *maintain* the Sutra belong in its top-level `notes` (or a panel's `description`),
which are never shown in the view.

```yaml
  - id: notes
    kind: markdown
    title: Reading this view
    text: |
      Amounts are in ${$.CURRENCY}. A cash event is signed from the bank's side: negative is paid, positive received.
```

You should see `Amounts are in AUD. A cash event is signed from the bank's side: negative is paid, positive received.` The
news-and-markets pack's `listed-company` Sutra uses one to say where its data comes from and that its connectors are off until a site
enables them.

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
  - id: guideline
    kind: gauge
    title: Notional used of a 500m guideline
    area: right
    value: "first($.LEGS).NOTIONAL"
    max: "500000000"
```

You should see, for `MX-20000001`, a bar 48% full with the text `48%`. With `max` omitted, a ratio already in the document
(`value: $.UTILISATION` on a netting set, `0.647`) reads directly as `65%`. Do not give a value format such as `compact` here: it would
format the ratio (`0.5`).

#### surface

A grid of values over two axes (volatility surfaces, correlation matrices): each row of `rows` is one y point, each column one x point.
Drawn as a heatmap, with a button that turns it into a rotatable 3D surface.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `rows` | yes | | Expression giving the list of y points. |
| `y` | yes | | The y label of each row: a field name (`EXPIRY`), or an expression when it starts with `@` or `$`. |
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
    rows: $.GRID
    y: EXPIRY
    columns:
      - { label: "80%", bind: "@.M_80", fmt: price2 }
      - { label: "90%", bind: "@.M_90", fmt: price2 }
      - { label: "100%", bind: "@.M_100", fmt: price2 }
      - { label: "110%", bind: "@.M_110", fmt: price2 }
      - { label: "120%", bind: "@.M_120", fmt: price2 }
```

You should see (market-data pack, `EQV EQV-CSCA`) a heatmap with six expiries `1M … 5Y` down the side and five moneyness points across.
The data is `{"x": ["80%", …], "y": ["1M", …], "z": [[43.17, 40.35, 37.54, 36.64, 35.74], …], "min": 33.42, "max": 43.17, "view": "heatmap"}`.

#### waterfall

Ordered steps as floating bars: each step runs from the running total before it to the one after it; a total step (and the closing bar
`sum` adds) is drawn from zero. Rises take the `ok` tone (green), falls `bad` (red), totals `muted` (neutral); `colors: theme` uses
`pos` and `neg` instead (blue and orange in most themes, colour-blind friendly).

| Option | Required | Default | Meaning |
|---|---|---|---|
| `rows` | yes | | Expression giving the steps, in order. |
| `label` | | `label` | Field with the step's name. |
| `value` | | `value` | Field with the signed amount (for a total, the level). Rows without a number are left out. |
| `total` | | `total` | Field that marks a total step; the running sum restarts at its value. |
| `sum` | | | Label of a closing total bar at the running sum. |
| `fmt`, `unit` | | | Format of the amounts (default: rounded to six significant digits); unit of the axis. |
| `colors` | | `gain-loss` | `gain-loss` (rises green, falls red) or `theme` (the theme's positive and negative colours); anything else is `DRS-2029`. |

```yaml
  - id: explain
    kind: waterfall
    title: "P&L waterfall (risk factors to actual, USD)"
    code: PNLX
    key: F3
    rows: $.EXPLAIN_STEPS
    label: STEP
    value: PNL
    fmt: signed0
    sum: Actual
    unit: USD
```

You should see (market-risk pack, `PNL PNL-COMM-1`) ten bars from *Carry −621,562* and *Roll-down −266,384* through the risk factors to
the closing *Actual*, `−4,309,239` (the strip's *Actual*). The data is `{"steps": [{"label": "Carry", "value": -621562, "from": 0,
"to": -621562, "text": "−621,562", "tone": "bad", "total": false}, …], "unit": "USD"}`.

#### histogram

The distribution of a list of numbers in equal-width bins from the smallest to the largest, with marker lines.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `rows` | yes | | Expression giving a list of numbers, or of rows. |
| `value` | | | With rows, the field (or `@` expression) holding the number. Non-numbers are counted as dropped. |
| `bins` | | √count, 5 to 40 | Number of bins, 1 to 200. |
| `markers` | | | List of `{ label, value, tone }`; `value` is an expression over the document, `tone` defaults to `accent`. A marker whose value is missing is left out. |
| `fmt`, `unit` | | | Format of bin edges and markers (default: six significant digits); unit of the axis. |

```yaml
  - id: scenarios
    kind: histogram
    title: "Scenario P&L distribution, 500 days (USD)"
    code: DIST
    key: F3
    rows: $.SCENARIO_PNL
    fmt: compact
    unit: USD
    markers:
      - { label: VaR 99%, value: "-$.VAR_99", tone: neg }
      - { label: ES 97.5%, value: "-$.ES_975", tone: bad }
      - { label: Mean, value: $.MEAN_PNL, tone: link }
```

You should see (market-risk pack, `VAR VAR-COMM`) 22 bins over 500 values and dashed lines at *VaR 99% −446.9m* and *ES 97.5% −504.9m*,
and the mean. The data is `{"bins": [{"from": -609701478, "to": -544502487.77, "count": 3, "label": "−609.7m to −544.5m"}, …],
"markers": [{"label": "VaR 99%", "value": -446852430, "text": "−446.9m", "tone": "neg"}, …], "count": 500, "dropped": 0}`.

#### scatter

One point per row with two numbers; rows without both are left out.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `rows` | yes | | Expression giving the rows. |
| `x`, `y` | yes | | Fields (or `@` expressions) of the two measures. |
| `size` | | | Field that sizes the point (by its absolute value, area-proportional). |
| `label` | | | Field that labels the point; an id a pack recognises (or a `link(…)`) makes the point open that entity. |
| `group` | | | Field whose value colours the point; one legend entry per group, in first-seen order. |
| `fmt`, `xFmt` | | `xFmt` = `fmt` | Formats of y and x in tooltips and the data table (default: six significant digits). |
| `xLabel`, `yLabel` | | the field names | Axis titles. |

```yaml
  - { id: riskReturn, kind: scatter, title: "Risk and return (books: VaR against today's P&L, USD)", code: RR, key: F3,
      rows: $.BOOKS, x: VAR, y: PNL, label: BOOK, fmt: signed0, size: TRADES, group: DESK, xFmt: compact,
      xLabel: VaR 99% 1D (USD), yLabel: "P&L 1D (USD)" }
```

You should see (core pack, `LE LE-NY`) fifteen points in five colours (the desks); each opens its book, the first `BOOK-RATES-1` at
VaR `58.2m`, P&L `−88,858,230`.

#### candlestick

Open, high, low and close by date, oldest first, with volume bars under the prices when the rows carry volume.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `rows` | yes | | Expression giving the bars. The latest `drishti.panels.max-points` are kept. |
| `x` | | `date` | Field with the bar's date. |
| `open`, `high`, `low`, `close` | | those names | Price fields; a bar without all four is left out. The defaults also read `OPEN`, `HIGH`, `LOW`, `CLOSE`. |
| `volume` | | | Volume field; no volume bars without it. |
| `fmt`, `unit` | | | Price format (default: six significant digits) and unit. |

```yaml
  - { id: ohlc, kind: candlestick, title: Daily bars (last 60 days), code: OHLC, key: F3, rows: $.OHLC, x: DATE, fmt: price2, volume: VOLUME }
```

You should see (market-data pack, `EQ EQ-CSCA`) 60 bars and *Last 21.84* with *+0.81 (+3.85%)* under the chart.

#### graph

Nodes and the edges between them. Each node needs an `id`; a node whose id a pack recognises (or that names its `kind`) is a link. The
node whose id is the viewed entity is ringed. Nodes and edges read `id`, `kind`, `from`, `to` and `label` in lower case or UPPER_SNAKE.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `nodes` | yes | | Expression giving the nodes (`id`, optional `kind`, and the fields below). |
| `edges` | | | Expression giving the edges: `from`, `to` (node ids), optional `label`. Edges to unknown nodes are left out. |
| `label` | | `label` | Node field shown under the node. |
| `group` | | `type` | Node field that colours it (and the legend). |
| `layout` | | `tree` | `tree` (levels from the nodes nothing points to) or `force`. |

```yaml
  - id: hierarchy
    kind: graph
    title: Group hierarchy (agreements and netting sets)
    code: HIER
    key: F3
    nodes: $.HIERARCHY.NODES
    edges: $.HIERARCHY.EDGES
    label: LABEL
    group: TYPE
    layout: tree
```

You should see (core pack, `CPTY CP-MERIDIAN-RE`) the group `GRP-MERIDIAN` over its two counterparties, then the ISDA, its CSA and four
netting sets, each a link.

#### timeline

Dated events, sorted by date on the server (ISO dates and timestamps sort as written), drawn as a vertical list.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `rows` | yes | | Expression giving the events. |
| `date` | | `date` | Field with the date or timestamp. |
| `label` | | `event` | Field (or `@` expression) with the event's name. |
| `detail` | | `description` | Field (or `@` expression) with a short note. |
| `status` | | `status` | Field with the status shown beside the event. |
| `tone` | | `status` | Tone applied to the status (see [Tones](#tones)). |

```yaml
  - id: events
    kind: timeline
    title: Events by leg
    code: EVT
    key: F5
    rows: "$.EVENTS[?nonblank(@.EVENT_DATE)]"
    date: EVENT_DATE
    label: "@.EVENT_TYPE + (nonblank(@.LEG_ID) ? ' · leg ' + @.LEG_ID : ' · deal')"
    detail: "nonblank(@.PAYMENT_TYPE, '') + (nonblank(@.AMOUNT) ? ' ' + @.CURRENCY + ' ' + fmt(@.AMOUNT, 'signed2') : '')"
```

This is the trading pack's `events` block. You should see, for `MX-20000001`, 42 events from *BOOKED · deal* and *CONFIRMED · deal*
on 2025-07-25 to the last payment of 2032, each payment with its type and signed amount.

#### pivot

> This is the **`pivot` panel kind**: a fixed grid the Sutra defines, written `kind: pivot`. For a table that lets the user pivot its own
> rows, see the [`pivot:` option](#pivot-a-pivot-tab-on-a-table-or-ladder) of `table` and `ladder`.

The rows aggregated by one field (down the side) across another (along the top), with row and column totals. Totals are aggregates of
the underlying rows (an `avg` total is the average of all its rows, not of the cells).

| Option | Required | Default | Meaning |
|---|---|---|---|
| `rows` | yes | | Expression giving the rows. |
| `by` | yes | | Field whose values are the row keys (first-seen order), or a **list of fields** (`by: [BOOK, FAMILY]`, at most 6) for nested groups: each group row shows the subtotal of the rows in it and a ▸/▾ toggle opens it. A string keeps working. A number, an empty list or a list of non-names is `DRS-2029`. |
| `across` | yes | | Field whose values are the column keys (first-seen order). |
| `value` | | | Field aggregated; without it the pivot counts rows. Rows whose value is not a number are left out. |
| `agg` | | `sum` | `sum`, `count`, `avg`, `min` or `max`. |
| `fmt`, `tone` | | | Format (default: six significant digits) and tone of every cell (counts are shown as whole numbers). |
| `heat` | | `false` | Shade each cell by its value, from the smallest to the largest. |
| `totals` | | `true` | `false` hides the total column and row (and the subtotal rows of nested groups). |
| `expand` | | `1` | With a list `by`: levels shown open at first (`1`: only the outermost groups, closed; `2`: those opened one level; `all`: every level). A whole number from 1 or `all`. |

```yaml
  - { id: mtmGrid, kind: pivot, title: "MTM grid (by book and currency, USD)", code: PIV, key: F3, rows: $.POSITIONS,
      by: BOOK, across: CURRENCY, value: MTM, agg: sum, heat: true, fmt: compact, tone: sign }
```

This is the `core` pack's `desk` Sutra. You should see (`DESK DESK-RATES`) three books across seven currencies (`EUR`, `USD`, `CHF`,
`GBP`, `JPY`, `CAD`, `AUD`), *BOOK-RATES-1* totalling *6.0bn*, *BOOK-RATES-2* *−1.5bn*, *BOOK-RATES-3* *−393.7m* and the desk *4.1bn*. A
list `by` nests the rows: `by: [BOOK, FAMILY]` gives a row for each book, with its subtotal, and under it a row for each of its product
families (`swap 6.2bn`, `linear −161.2m`… under *BOOK-RATES-1*). Heat shading is not drawn on nested pivots.

#### metric

A big-number KPI tile: one large, formatted and toned figure, with an optional change beside it, a unit and a caption. Use several in a
row (`span: 3` or `span: 4` each) for headline numbers; for one number against a maximum use [`gauge`](#gauge).

| Option | Required | Default | Meaning |
|---|---|---|---|
| `value` | yes | | Expression for the figure. A missing value shows a dash, never an error; a masked one shows the mask. |
| `label` | | | Plain text shown with the figure (what it is). |
| `fmt`, `tone` | | | Format and tone of the figure (see [Formats](#formats), [Tones](#tones)). |
| `delta` | | | Expression for the change. Without it, or when it is empty, no change is drawn. |
| `deltaFmt` | | the format of `value` | How the change is formatted. |
| `deltaTone` | | `sign` | Tone of the change: `sign` (positive and negative colours), `status`, or a fixed tone such as `neg`, `warn` or `accent`. |
| `unit` | | | Short text shown small beside the figure (`USD`, `bp`). |
| `caption` | | | Small text under the figure; a template, so it may hold `${...}` parts. |

`value`, `delta` and every other option are one value written as text, never a list or a mapping (`DRS-2029`); `source` works as for
any panel.

```yaml
  - id: pay7
    kind: metric
    title: Payments, next 7 days
    value: "sum(flatten($.TRADES[*].EVENTS)[?nonblank(@.AMOUNT) && nonblank(@.EVENT_DATE)][?days(today(), @.EVENT_DATE) >= 0 && days(today(), @.EVENT_DATE) <= 7], 'AMOUNT')"
    fmt: signed0
    tone: sign
    caption: "${size(flatten($.TRADES[*].EVENTS)[?nonblank(@.AMOUNT) && nonblank(@.EVENT_DATE)][?days(today(), @.EVENT_DATE) >= 0 && days(today(), @.EVENT_DATE) <= 7])} events"
    span: 4
```

This is the first of the three tiles of the trading pack's counterparty, portfolio, legal-entity and index pages (7, 30 and 90 days).
You should see, for `CP-NORTHPLAINS` on the business date 2026-10-16, `+2,272,287` in the positive colour with `7 events` under it; the
30-day tile reads `−62,072,389` (449 events). The runnable version with a `delta` is the [metric example](examples/metric.md); its
catalogue entry is [PANELS.md](PANELS.md#metric).

#### map

A map of the world with a country highlighted, shaded by a figure and clickable, or of one country's regions. It reads the document for
the place to show (`highlight`, `region`) and, across a whole kind, for which countries have a page (`link`) and what shades them
(`shade`). The shapes are Natural Earth, shipped with the console (countries at 1:110m, regions below a country at 1:10m, public
domain): nothing is fetched from the internet, there are no map tiles and no map service.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `highlight` | | | Expression for the country to highlight: an ISO 3166-1 alpha-3 or alpha-2 code (`AUS`, `AU`) or the English name. In `region` mode, an ISO 3166-2 region code (`AU-NSW`). A masked value highlights nothing. |
| `region` | | | Expression for a country (same forms as `highlight`). The map then shows that country's regions, with ISO 3166-2 codes, instead of the world. A document with no such country makes the panel empty. |
| `link` | | | `{ kind: economy, id: "ECON-{iso3}" }`: a click on a country opens that entity. The template holds `{iso3}`, `{iso2}` or (regions) `{code}`. The server lists the entities of the kind: a country with no such entity is not clickable and says so on hover; a kind the reader may not open lists nothing and the panel says *no access*. |
| `shade` | | | `{ kind: economy, field: $.GDP_PER_CAPITA, by: $.ISO3, fmt: amount0, label: GDP per capita (USD) }`: colour each country by `field` of the entity of `kind` that `by` names (default `$.iso3`), with a legend. Read as the caller may see it: a masked field shades nothing and the panel says so. |
| `fmt`, `unit` | | | Format of the shade's figures when `shade` has none, and a unit shown in the legend and tooltip. |

`highlight`, `region`, `shade.field` and `shade.by` are expressions compiled when the Sutra loads (`DRS-2101`); `link` and `shade` that
are not mappings of the shape above, a link template with no code, and a `highlight` that is a list are `DRS-2029`. The entities are read
once per kind and kept for `drishti.panels.map-cache-seconds` (60), at most `drishti.panels.map-entities` (1,000), within
`drishti.panels.map-budget-millis` (5,000): a slower read leaves the map unshaded, with the reason under it. Countries too small for the
world shapes (Cayman Islands, Bermuda, ...) and regions that are tiny at their country's scale are drawn as dots, so they can still be
clicked. Under the map, *Open a country* lists the same places as links (the way in from a keyboard, a screen reader or a phone).

```yaml
  - id: geography
    kind: map
    title: Where it is
    highlight: $.ISO3
    link: { kind: economy, id: "ECON-{iso3}" }
    shade: { kind: economy, field: $.GDP_PER_CAPITA, by: $.ISO3, fmt: amount0, label: GDP per capita (USD) }
    height: 11
```

This is the world-economy pack's `economy` Sutra. You should see, for `ECON ECON-AUS`, the world shaded by GDP per capita with
Australia highlighted; every country with an economy page (`ECON-ABW`, `ECON-AFG` …) opens it. The runnable version is the
[map example](examples/map.md); its catalogue entry is [PANELS.md](PANELS.md#map). A BI report's map visual draws with the same shapes
and drills from the world to a country and its regions:
[REPORTS.md](REPORTS.md#31-the-map-funnel-histogram-box-plot-and-bubble).

## Controls

A Sutra may declare **controls**: dropdowns, date ranges, a slider, a search box. The reader sets them on the screen; panels read their
values in expressions as `ctl.<id>`, so the same Sutra shows a swap's events for one leg and one quarter, or a netting set's trades for one
asset class. Controls only choose: they never write. The model is the one of
[RUPAKA.md section 8.1](../architecture/RUPAKA.md#81-interactive-sutras-and-slicers-controls-view-state-and-filters), built once for the
terminal.

Every trade Sutra of the trading pack declares these two, read by the `events` block's events table:

```yaml
controls:
  - { id: legs, kind: multi-select, label: Legs, options: "$.LEGS[*].LEG_ID", default: all, placement: header, panel: event-list, remember: user }
  - { id: when, kind: date-range, label: Between, placement: header, panel: event-list }
panels:
  - id: event-list
    kind: table
    title: "Events${ctl.when ? ' · ' + ctl.when : ''}"
    rows: $.EVENTS
    where: "(!nonblank(@.LEG_ID) || oneOf(@.LEG_ID, ctl.legs)) && within(@.EVENT_DATE, ctl.when)"
    empty: No events booked
    columns: [ ... ]
```

Open `TRD MX-20000001 <GO>`: the *Events* header holds *Legs* and *Between*; the address
`.../v/trade/MX-20000001?ctl.legs=1&ctl.when=2026-10-01..2027-12-31` shows leg 1's events (and the deal's own) in that range. The server
answers (`GET /api/v1/views/trade/MX-20000001?ctl.legs=1&ctl.when=2026-10-01..2027-12-31&panels=event-list`, trimmed; the presets are
those of the business date 2026-10-16):

```json
{"controls": [
  {"id": "legs", "kind": "multi-select", "label": "Legs", "placement": "header", "panel": "event-list",
   "options": [{"value": "1", "label": "1"}, {"value": "2", "label": "2"}], "value": "1", "values": ["1"],
   "required": false, "remember": true, "range": false, "reads": ["event-list"], "truncated": false},
  {"id": "when", "kind": "date-range", "label": "Between", "placement": "header", "panel": "event-list",
   "options": [{"value": "2026-10-16..2026-10-16", "label": "Today"}, {"value": "2026-10-16..2026-10-22", "label": "Next 7 days"}, "..."],
   "value": "2026-10-01..2027-12-31", "required": false, "remember": false, "range": false, "reads": ["event-list"], "truncated": false}],
 "panels": [{"id": "event-list", "title": "Events · 2026-10-01..2027-12-31", "data": {"rows": [
   {"cells": [{"label": "Leg", "text": "deal"}, {"label": "Event", "text": "20000001006"}, {"label": "Type", "text": "NEXT_PAYMENT"}, "..."]},
   {"cells": [{"label": "Leg", "text": "1"}, {"label": "Event", "text": "20000001009"}, {"label": "Type", "text": "CASH_SETTLEMENT"},
              {"label": "Payment type", "text": "Interest"}, {"label": "Date", "text": "2027-06-29"}, {"label": "Amount", "text": "+9,880,618.00"}, "..."]}]}}]}
```

### The keys of a control

| Key | Required | Meaning |
|---|---|---|
| `id` | yes | The name read as `ctl.<id>`: a letter, then letters, digits and underscores, at most 32 (`DRS-2034`); unique (`DRS-2035`). `ctl` itself is reserved |
| `kind` | yes | `dropdown`, `multi-select`, `cascading`, `toggle`, `segmented`, `date`, `date-range`, `number-range`, `slider`, `top-n` or `search` (`DRS-2034` otherwise) |
| `label` | | The text beside the control (the id when absent) |
| `options` | for `dropdown`, `multi-select`, `cascading`, `toggle`, `segmented`, `top-n` | A list of values (`[Pay, Receive]`, or `{ value, label }` items), **or** an expression over the record (`"$.LEGS[*].LEG_ID"`; a list of records offers their `id`), **or** `{ kind: counterparty }`, the ids of that entity kind the sources list (at most 500; only when the reader may open the kind). More than 500 are cut and the control says so (`truncated`). Kinds that take no options refuse them (`DRS-2034`) |
| `default` | | The value to start from: a value, `first`, `all` (a multi-select), or for dates a relative token (below). It must fit the kind (`DRS-2039`). A `top-n`, `toggle` and `segmented` control always has a value: its first option when no default is given |
| `remember` | | `user` keeps the reader's last choice (per browser and sign-in, see below); `none` (the default) does not |
| `required` | | `true`: the reader cannot clear it (a dropdown has no *(all)* entry) |
| `placement` | | `strip` (default: under the title, with the strip), `header` (in a panel's header; needs `panel: <panel id>`, `DRS-2034` otherwise) or `side` (a bar at the side of the page) |
| `parent` | for `cascading` | The control whose value the options depend on; it must be declared **before** this one (`DRS-2037`) |
| `min`, `max` | for `slider` | A `number-range`'s or `slider`'s bounds: a number or an expression over the record (`"max($.TRADES[*].NOTIONAL)"`). A slider without both is `DRS-2034`; bounds that cannot be read (a masked field, a bad expression) leave the slider without a scale |
| `step` | | A `slider`'s step: a number above zero or an expression over the record (default `1`). Values snap to `min + n x step`; the top of the scale is always reachable (`DRS-2034` for a step of zero or less) |
| `range` | | `true`: a `slider` with two handles whose value is `from..to`. Only for sliders (`DRS-2034` otherwise); a `default` must match (one number, or `from..to` for two handles: `DRS-2039`) |

At most 12 controls; an unknown key is `DRS-2011`.

### The kinds, and what `ctl.<id>` holds

| Kind | Drawn as | `ctl.<id>` |
|---|---|---|
| `dropdown` | a select with an *(all)* entry (unless `required`) | the chosen value as text; empty when none |
| `multi-select` | a button over a checklist with *All* and *None* | a **list** of the chosen values; empty when none, which `oneOf` treats as everything |
| `cascading` | a select whose options follow its `parent` | as a dropdown; its `options` read `ctl.<parent>`: `"$.COUNTRIES[?(@.REGION == ctl.region)][*].ID"` |
| `toggle` | a switch for two options, buttons for more | the chosen option |
| `segmented` | joined buttons (arrow keys move and choose) | the chosen option |
| `date` | a date input | an ISO date as text, or empty |
| `date-range` | two date inputs and a presets list | `from..to` as text (either end may be empty), empty when none |
| `number-range` | two number inputs | `min..max` as text |
| `slider` | a track with one handle, or two with `range: true` (below) | one handle: a **number**, so `@.NOTIONAL >= ctl.minNotional` compares as numbers (empty when the scale has no bounds); two handles: `from..to` as text, for `within(@.NOTIONAL, ctl.band)` |
| `top-n` | a select of the options | a whole number; a table's `limit: ctl.top` shows that many rows |
| `search` | a search box (applied when the reader pauses) | the text |

**The slider.** `{ id: minNotional, kind: slider, label: Notional at least, min: 0, max: "max($.TRADES[*].NOTIONAL)", step: 1000000 }`
draws a track with a round handle; `range: true` draws two (the lower cannot pass the upper). The request's value is held to `min`..`max`,
snapped to the step and, for two handles, ordered; a value that is not a number falls back to the `default`, then to `min` (both ends for
two handles), so the view always carries a value the slider can show, and a link reproduces it (`?ctl.minNotional=250000000`). Keys on a
handle: `←` `↓` and `→` `↑` move one step, `PageDown` and `PageUp` ten, `Home` and `End` to the ends (for two handles, to the other
handle); a press or drag on the track or a handle moves the nearest one (a drag follows the pointer and settles on release). Each handle
is `role="slider"` with `aria-valuemin`, `aria-valuemax`, `aria-valuenow`, `aria-valuetext` and a label (*Notional at least*, or *… from*
and *… to*); the handles are 44 px on a touch screen. The shipped `netting-set` Sutra of the counterparty-risk pack uses one as a notional
threshold on its member trades:

```yaml
controls:
  - { id: minNotional, kind: slider, label: Notional at least, min: 0, max: "max($.TRADES[*].NOTIONAL)", step: 1000000, placement: header, panel: trades }
panels:
  - id: trades
    kind: table
    rows: $.TRADES
    where: "(ctl.asset == '' || @.ASSET_CLASS == ctl.asset) && oneOf(@.PRODUCT, ctl.product) && oneOf(@.STATUS, ctl.status) && (ctl.minNotional == '' || @.NOTIONAL >= ctl.minNotional)"
```

Relative dates are read on the **business date of the request** (today, or the date picked in the top bar) and the server's business
calendar (`drishti.business-date.calendar`; not a calendar per pack): `today`, `yesterday`, `previous-business-day`, `this-week`,
`this-month`, `last-month`, `next-month`, `this-quarter`, `this-year`, `ytd`, `mtd`, `next-90d`, `last-30d` (calendar days; the first
day counted is the business date) and `next-5bd`, `last-3bd` (business days on the calendar). A `date-range` offers presets (today, next
7 / 30 / 90 days, last 30 days, this month, last month, this quarter, year to date) resolved the same way, and always **holds the resolved
range** (`next-90d` becomes `2026-10-16..2027-01-13` on 2026-10-16), so a link reproduces what was seen.

### Reading a control

`ctl.<id>` is an expression like any other, in the places that read the record: a panel's `rows`, `where`, `limit`, `title` (as
`${ctl.id}`), a column or strip `bind`, a control's own `options`, `min` and `max`. A reference to a control the Sutra does not declare
is `DRS-2036`. Three panel options are for controls:

| Option | On | Meaning |
|---|---|---|
| `where` | every kind with `rows` except `kv` | A condition on each row, with `@` the row and `#index` its place in the *data*: only rows for which it is true are shown, drawn and added up (`total: true` sums what is shown). A row whose condition fails or is masked is left out. Not text: `DRS-2029` |
| `limit` | `table` | Besides a whole number, an expression: `limit: ctl.top`. Reads as no limit when it is empty |
| `on-select` | `table`, `ladder`, `hbar` | `{ by: <control id>, field: <row field>, filter: [panel ids] }`: a click (or Enter) on a row, or on a bar, sets the control `by` to the row's `field` (default: the control's id) or to the bar's label; clicking the same one again clears it. `by` must be a control and `filter` must list panels of the Sutra (`DRS-2038`) |

Two functions read the values ([Functions](#functions)): `within(@.EVENT_DATE, ctl.when)` and `oneOf(@.LEG_ID, ctl.legs)`. For a
dropdown with an *(all)* entry write `(ctl.asset == '' || @.ASSET_CLASS == ctl.asset)`, as the shipped `netting-set` Sutra does:

```yaml
controls:
  - { id: asset, kind: dropdown, label: Asset class, options: "$.BY_ASSET[*].ASSET", placement: header, panel: trades }
  - { id: product, kind: multi-select, label: Product, options: "$.TRADES[*].PRODUCT", placement: header, panel: trades }
panels:
  - id: trades
    kind: table
    title: "Member trades (${size($.TRADES)} of ${$.TRADE_COUNT})${ctl.asset ? ' · ' + ctl.asset : ''}"
    rows: $.TRADES
    where: "(ctl.asset == '' || @.ASSET_CLASS == ctl.asset) && oneOf(@.PRODUCT, ctl.product)"
  - id: byAsset
    kind: hbar
    rows: $.BY_ASSET
    on-select: { by: asset, field: ASSET, filter: [trades] }
    label: ASSET
    value: MTM
```

On `NS-MERIDIAN-RE-NY`, `?ctl.asset=Rates&ctl.minNotional=250000000` gives the title `Member trades (343 of 343) · Rates` and 55 rows.

### Where the values live

- **The address.** `?ctl.legs=1&ctl.when=2026-10-01..2027-12-31`: the console writes a control's value there once the reader has set it
  (a control left at its default is not written), a link opens the screen with the same values, and the Share button's link carries them.
- **The server.** `GET /api/v1/views/{kind}/{id}?ctl.<id>=...` builds the view with the values; a value that is not among a control's
  options is replaced by its default. `&panels=a,b` builds **only those panels** (`panels=-` none); the view always carries `controls`,
  each with its options, its value now and `reads`: the panels (and `strip`, `title`) whose expressions read it, directly or through a
  cascading child. The panel rows are computed by the same expression the browser's request carries; nothing is filtered in the browser.
- **Live.** A stream opened with the values (`.../stream?ctl.legs=1`) builds every frame with them, so a tick keeps the filter; the
  console moves its subscription when a control changes.
- **Remembering.** `remember: user` stores the choice in the browser (`localStorage`, per signed-in user and Sutra) and applies it when
  the address has no value for the control. The server has no per-user store for view preferences yet, so it is per browser, not per
  account.
- **Masks.** Options are read from the record **as the caller may see it**: a masked value is never an option, so a dropdown cannot show
  a reader without `raw` a value they may not see, and a `where` or a search over a masked field never matches (a search cannot probe it).
  A lookup of a kind the reader may not open lists nothing.

### What is not built yet

Options from a dataset column (a Rūpaka dataset), the Sutra-level view `title:` reading a control (a panel's `title` does; a control that
the view `title` reads reloads the page), a Pivot tab and a CSV export that follow the controls (they read every row), and control values
stored with a *scheduled subscription* (there is no scheduled delivery yet; a **sent** share carries them, see below).

### Column filters (`f.<panel>.<column>`)

Besides controls, the reader can filter any `table`, `ladder` or one-field `pivot` panel by its column headings (the funnel button, as in
Excel). This is not declared in the Sutra: every table has it. The filters live in the address as `f.<panel id>.<column heading>=<filter>`
(`?f.event-list.Type=in:CASH_SETTLEMENT&f.event-list.Amount=gt:1m`), are applied in the browser to the rows the server sent, and a masked
cell never satisfies a condition. A share keeps them with the controls (below). The menu, the filter syntax and the keys are in the
[User guide](USER_GUIDE.md#filter-a-table-like-excel); a filter naming a panel or column the Sutra does not have is ignored.

### Controls with a sent share

A share keeps the **view state** its sender had on screen: the control values (`ctl.*`) and the column filters (`f.<panel>.<column>`), as
the address writes them, in the share's `view` (at most `drishti.collab.share.max-view-state` characters, 4000 by default; nothing else
from the address is kept). The recipient who opens the share lands on the same view with the same values and filters; the **picture** of
the share (when one was asked for) is built with the control values and shows only the rows the column filters leave, after the masks of
its least privileged recipient are applied (a masked cell never matches a condition). A share sent without any view state, and every share
made before this was kept, is unchanged. The values are not copied data: they only select what each recipient's own rights show.

## Pack search form (`form:` in `pack.yaml`)

Not part of a Sutra: the `form:` section of a pack's `pack.yaml` declares the search form of each kind (`FORM <pack> <GO>`, query by
form). The server compiles the inputs to the ordinary search and runs it as any search, so masks, kinds per role and packs per user apply.
A kind with no entry gets a form **inferred** from its search columns and the fields its Sutras read. The walk-through, with an example, is
in [PACK_DEVELOPER_GUIDE.md, form](PACK_DEVELOPER_GUIDE.md#form-the-search-form-of-a-kind). The rules below are the ones the parser
(`FormSpec`, `FormField`, `FieldType` in `drishti-engine`, `tools/packform.py` for `drishti.py pack check`) applies. The trading pack's
form of `trade`, shortened:

```yaml
form:
  trade:                                  # a kind of this pack; the short form: just the list of fields
  - path: TRADE_ID
    label: Trade id
    type: id
    group: Identity
    order: 1
    help: 'One or many: paste a list. MX-2* is any id starting MX-2; Endur ids are numbers.'
  - path: SOURCE_SYSTEM
    label: Source system
    type: enum
    group: Identity
    order: 2
  - { path: PRODUCT_TYPE, label: Product type, type: enum, group: Product, order: 4 }
  - path: QUANTITY
    label: Quantity
    type: amount
    group: Money
    order: 12
    operators: ['>=', '<=', between, '=']
    help: 'Amounts take k, m and bn: 250k, 1.5m, 2bn.'
  - { path: TRADE_DATE, label: Trade date, type: date, group: Dates, order: 13 }
```

| Level | Key | Type | Default | Meaning |
|---|---|---|---|---|
| `form` | `<kind>` | list, or mapping | | One entry per kind; the kind must be one of the pack's `kinds` (`pack check` refuses another) |
| kind | `fields` | list | | Required (and non-empty) in the mapping form (`trade: { calendar: USNY, fields: [...] }`); the whole value in the short form |
| kind | `calendar` | text | server's | A calendar name known to the server (`USNY`, ...); `last 5 business days` runs on it. Another key in the mapping is refused (`use fields and calendar`) |
| field | `path` | text | | **Required.** A document path (`MTM`, `COUNTERPARTY.NAME`, `LEGS[0].NOTIONAL`); a leading `$.` is dropped. Each path once per kind |
| field | `label` | text | the path in words | The heading |
| field | `type` | `text`, `number`, `amount`, `date`, `enum`, `id`, `bool` | `text` | Decides the input and the operators |
| field | `operators` | list | all of the type's | A subset of the type's operators; the first is the default. One the type lacks is refused (`between` on text) |
| field | `default` | text | empty | What the input starts with |
| field | `order` | whole number | 0 | Lower first; fields without one follow in the order written |
| field | `group` | text | none | A heading; fields of a group sit together |
| field | `help` | text | none | One line under the input |
| field | `required` | boolean | false | The form cannot be submitted without it |
| field | `hidden` | boolean | false | Not an input; kept so a saved search can use it. `required` and `hidden` together need a `default` |
| field | `values` | list | the values found in the data | Only for `enum` (on another type it is refused) |

| Type | Operators (first is the default) |
|---|---|
| `text` | `equals`, `contains`, `starts`, `regex` |
| `number`, `amount` | `=`, `between`, `>`, `<`, `>=`, `<=` (an amount takes `250k`, `1.5m`, `2bn`) |
| `date` | `on`, `between`, `relative`, `before`, `after` |
| `enum`, `id` | `in` |
| `bool` | `is` |

A problem is reported against the key, for example
`pack.yaml form.trade.fields[2] (QUANTITY): type 'money' is not one of text, number, amount, date, enum, id, bool`; a bad `form:` fails
`drishti.py pack check`, and a design's Form tab in the Build workbench reports it as `DRS-2060`.

## Function keys

Function keys come from two places:

1. a panel's `key:` scrolls to that panel and flashes it; its label on the key bar is the first one or two words of the panel title, cut
   at ` · ` or ` (` (`Cash flows · leg 1` → `Cash flows`, `Events by leg` → `Events by`);
2. the top-level `keys:` mapping, for actions that are not panels.

```yaml
keys:
  F7: "link($.COUNTERPARTY, 'counterparty-trades')"   # open another entity (here: every trade with the counterparty)
  F8: impact                                         # what depends on this entity
  F9: raw                                            # the raw JSON drawer (field masks for roles without raw access)
  F10: event-list                                    # any other text: the id of a panel to scroll to
```

| Action | Key bar label | Does |
|---|---|---|
| `raw` | `Raw JSON` | toggles the raw JSON drawer (Esc closes it) |
| `impact` | `Impact` | opens the impact view of this entity |
| `link(id, kind?, label?)` | the kind in words (`Counterparty trades`) | opens the entity; when the id is empty or its kind cannot be told, the key is left out |
| anything else | the text itself | treated as a panel id to scroll to (not checked at load: a wrong id gives a key that does nothing) |

Keys are `F1` to `F12`, unique across panels and `keys` (`DRS-2025`), and shown in F-number order. `F1` opens the console's help (and
*About this page*) whatever the Sutra says, so do not use it (`sutra lint` warns, `DRS-2045`). Inference gives `F2`–`F6` to its first five
main panels and `F9: raw`.

## Links

A Sutra makes something navigable in three ways:

| How | Where | Example |
|---|---|---|
| `link(id, kind?, label?)` | any expression: title `with`, strip, columns, status fields, `keys`, a panel's `source` | `bind: "link($.PORTFOLIO, 'book')"` |
| `link: true` on a column | a column whose value is an identifier | `{ label: Netting set, bind: "@.ID", link: true }` |
| automatic | a column bound to a plain path whose last field ends in `_ID` or `_REF` (or, camel case, `Id`, `Ref`, `_id`) | `{ label: Trade, bind: "@.TRADE_ID" }` |

How the target kind is found: `link(id, 'book')` names it. `link(id)` without a kind, `link: true` and the automatic case ask the
reference catalogue, which matches the id against the enabled packs' `graph.id-patterns` (for example `^CP-` → `counterparty`, `^NS-` →
`netting-set`). When no pattern matches, the value is shown as plain text.

The automatic rule (`Binder.namesAnId`) applies when the `bind` is a plain path (`@.x.y`, `$.x[0].y`; no operators, no functions) whose
last field name is longer than three characters and ends in `_ID` or `_REF` (`@.TRADE_ID`, `$.TRADER_ID`, `$.BOOK_REF`), or longer than
two and ends in `Id`, `Ref` or `_id` (`@.tradeId`, `@.bookRef`). `@.ID` does not qualify (too short); write `link: true` for it, or for
any field named otherwise (`@.AGREEMENT`, `@.PORTFOLIO`). An automatic link needs a pattern too: on `MX-20000001`, `$.TRADER_ID` opens
`TRDR-ASHAH` and `$.TRANSACTION_ID` (`20000001002`) stays text.

## Formats

`fmt` (and the `fmt(value, 'name')` function) names a format. Formats are loaded once at start-up in this order, later definitions
replacing earlier ones of the same name:

1. the core `formats.yaml` bundled in `drishti-rachana`;
2. each enabled pack's `config/formats.yaml` (or the file named by its `formats:` key), most general pack first;
3. the site file `drishti.rachana.formats-file`, if it exists.

An unknown format name is not an error: the value is shown as plain text.

### Core formats

| Name | Definition | `1234567.891` | `-0.040829` | Other |
|---|---|---|---|---|
| `text` | as text | `1234567.891` | `-0.040829` | |
| `amount0` | 0 decimals, grouped | `1,234,568` | `0` (zero after rounding has no sign) | |
| `amount2` | 2 decimals, grouped | `1,234,567.89` | `−0.04` | `"12.5"` (text) → `12.50` |
| `signed0` | 0 decimals, `+` on positives | `+1,234,568` | `0` | `0` → `0` |
| `signed2` | 2 decimals, `+` on positives | `+1,234,567.89` | `−0.04` | |
| `pct0` | × 100, 0 decimals, `%` | `123,456,789%` | `−4%` | `0.83` → `83%` |
| `pct2` | × 100, 2 decimals, `%` | | `−4.08%` | |
| `price2` | 2 decimals, grouped | `1,234,567.89` | `−0.04` | |
| `compact` | k / m / bn, 1 decimal | `1.2m` | `−0.0` | `46000000` → `46.0m`, `2500000000` → `2.5bn`, `999` → `999.0` |
| `date` | `yyyy-MM-dd` | | | text is shown as is, time included (`2025-07-25T09:45:00Z`) |
| `dmy` | `dd MMM yyyy` | | | `2026-09-30` → `30 Sep 2026` (the first ten characters are read as a date) |

Negative numbers use the true minus sign `−` (U+2212). Rounding is half-up. A text value that is a number (`"12.5"`) is formatted as a
number; any other text is shown as is (`"abc"` stays `abc`).

### Formats added by the packs

| Name | Definition | Example | Packs |
|---|---|---|---|
| `pct4` | × 100, 4 decimals, `%` | `0.040829` → `4.0829%` | core, market-data, trading, counterparty-risk, market-risk |
| `price4` | 4 decimals | `0.040829` → `0.0408` (a price per unit, not scaled) | same |
| `rate5` | 5 decimals, no grouping | `1.08345` → `1.08345` | same |
| `pips1` | 1 decimal, `+` on positives | `12.34` → `+12.3` | same |
| `df4` | 4 decimals | `0.991063` → `0.9911` | same |
| `bp1` | 1 decimal, `+` on positives, ` bp` | `2.5` → `+2.5 bp`, `0` → `0.0 bp` (no scaling: give it basis points, not a fraction) | same |
| `pc1` | 1 decimal, `%` (the value is already a percentage, no scaling) | `3.84` → `3.8%` | world-economy |
| `pc2` | 2 decimals, `%` | `3.841` → `3.84%` | world-economy; **news-and-markets** defines its own `pc2` with a `+` on positives and a space (`1.2345` → `+1.23 %`), and with the seven packs enabled that one wins |

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

A pack adds formats in `config/packs/<pack>/config/formats.yaml`, as the trading pack does:

```yaml
formats:
  pct4:   { type: number, decimals: 4, scale: 100, suffix: "%" }
  price4: { type: number, decimals: 4 }
  bp1:    { type: number, decimals: 1, sign: always, suffix: " bp" }
  usdm:   { type: number, decimals: 1, scale: 0.000001, suffix: " m" }   # 242000000 → 242.0 m
  month:  { type: date, pattern: "MMM yyyy" }                           # 2026-09-30 → Sep 2026
```

A site does the same in its own file and points `drishti.rachana.formats-file` at it. Formats and semantic hints are re-read when the
packs change (an edit of a pack's `config/formats.yaml` is picked up within the packs poll interval); the site file at start-up.

## Tones

`tone` colours a value through theme tokens only, and the console always pairs colour with a sign or glyph.

| Tone | Colour class | When |
|---|---|---|
| `sign` | `pos` or `neg` | by the number's sign; zero, empty or non-numbers get no colour |
| `status` | `ok`, `warn` or `bad` | by the text: *fail*, *reject*, *dispute*, *breach* → `bad`; *pending*, *unmatched*, *warn* → `warn`; other non-empty text → `ok` (case-insensitive) |
| `pos`, `neg`, `link`, `accent`, `ok`, `warn`, `bad` | that class | always |

An unknown tone name is not an error; the value is simply not coloured. Totals use their column's tone; `hbar` without `tone` colours by
sign.

## Totals, limits and "more"

| Option | Kinds | Effect |
|---|---|---|
| column `total: true` | `table`, `ladder` | adds a total row summing that column over all rows (hidden ones included; with `where`, the rows it keeps; in a tree, the rows without children) |
| `totalLabel` | `table`, `ladder` | label of the total row (default `Total`), placed before the first totalled column |
| `limit` | `table` | rows shown; a whole number from 1 (`limit: "6"`, quoted, is `DRS-2029`) or an expression reading a control |
| `moreLabel` | `table` | expression for the line under the table when rows are hidden; default `<N> more` |

## Rachana-EL expressions

Bindings, conditions and templates are written in **Rachana-EL**, a small expression language. It is closed (no loops, no assignment,
no list literals, no access to anything but the document and the controls), side-effect free and **total**: evaluating an expression
never throws on odd data. A missing field, a wrong type or a division by zero gives an empty value, never an error page. Expressions are
bounded in size ([size limits](#size-limits)), so evaluation cannot run out of stack either; and should one panel's expression still
fail, that panel shows the problem and the rest of the view renders.

### Where expressions appear

| Place | Kind of value |
|---|---|
| `match.where` | expression (predicate) |
| `title.id`, `title.with` | expression |
| `title.pill`, a panel's `title`, `markdown.text`, metric `caption` | template: text with `${expression}` parts |
| strip `bind`, column `bind`, status field `bind` | expression |
| `rows`, `each`, `tabTitle`, `moreLabel`, `highlight`, `source`, `mark`, `when`, `children`, `crumb`, `root`, area `limit`, gauge `value`/`max`, metric `value`/`delta` | expression |
| `where` (a panel's row condition), a table's `limit` that reads a control (`ctl.top`) | expression (see [Controls](#controls)) |
| a control's `options` (when text), `min`, `max`, `step` | expression over the record, which may read `ctl.<parent>` |
| `keys` entries starting with `link(` | expression |
| alert rules (`when`, `message`) | expression, template (see [LIVE.md](../architecture/LIVE.md#monitors-and-alerts)) |

### Lexical rules

| Element | Written as | Notes |
|---|---|---|
| Document | `$` | the whole document (inside a panel with `source`, the source's document) |
| Current row | `@` | the element of `rows`/`each`/a filter being evaluated; outside a row it is empty |
| Row position | `#index` | 0-based position of the row in its list; `-1` outside a row |
| Field | `.name` | letters, digits and `_`, exactly as the document writes it (`.TRADE_ID`, not `.trade_id`); a name with other characters needs brackets: `$['day-count']` |
| Element or key | `[expr]` | a number indexes a list (`[0]` first, `[-1]` last); text looks up a field (`$['TRADE_ID']`) |
| Filter | `[?predicate]` | keeps the list elements for which the predicate (with `@` bound to each) is truthy |
| Projection | `[*]` | `$.LEGS[*].LEG_ID`: the field of every element, as a list |
| Bare name | `AMOUNT` | a field of the current row inside a row, otherwise of the document |
| Control | `ctl.<id>` | the value of a control ([Controls](#controls)); `ctl` is reserved and is never a field of the data |
| Text | `'single'` or `"double"` quotes | `\` makes the next character literal (`'it\'s'`; so `'\d'` is the letter `d`); there are no `\n` escapes |
| Number | `42`, `0.5`, `.5`, `1e6`, `2.5E-3` | a whole number is an integer (`1 + 1` prints `2`) |
| Literals | `true`, `false`, `null` | there are no list or object literals |
| Grouping | `( … )` | |

Anything else (`&`, `|` alone, `;`, `^`, a lone `#`, `[` starting a value) is a compile error: `unexpected character '&'`,
`unexpected '['`.

A field name with a hyphen is the classic trap: `$.day-count` **compiles**, as `$.day` minus a field `count`, and shows `NaN`. Write
`$['day-count']`.

### Operators and precedence

From lowest to highest binding; operators on one line bind equally and group left to right, except the conditional, which groups right to
left (`a ? b : c ? d : e` is `a ? b : (c ? d : e)`).

| Level | Operators | Result |
|---|---|---|
| 1 | `cond ? then : else` | `then` when `cond` is truthy, else `else` |
| 2 | `\|\|` | `true`/`false` (not one of the operands) |
| 3 | `&&` | `true`/`false` |
| 4 | `==`, `!=` | equality (below) |
| 5 | `<`, `<=`, `>`, `>=` | ordering (below) |
| 6 | `+`, `-` | addition, or text joining for `+` |
| 7 | `*`, `/`, `%` | arithmetic |
| 8 | `!x`, `-x` | not, negation |
| 9 | `.name`, `[i]`, `[?p]`, `[*]`, `f(…)` | paths, filters, projections, calls |

So `!$.a == true` means `(!$.a) == true`, and `$.a + $.b * 2` means `$.a + ($.b * 2)`.

### Values and truthiness

A value is one of: empty (missing or `null`; the two behave the same), a boolean, a number, a text, a list, an object, or a link (from
`link(...)`). An extract's empty text `""` is a text, not empty: `nonblank(...)` treats it as missing, nothing else does.

| Value | Truthy when |
|---|---|
| empty | never |
| boolean | it is `true` |
| number | it is not 0 (and not NaN) |
| text | it is not empty — `'false'` and `'0'` are truthy |
| list, object | it has at least one element or field |
| link | always |

### Evaluation rules

- **Missing never throws.** `$.NOTHING.DEEPER[3].X` is empty. A path through a value that is not an object or list is empty too.
- **`+`** adds when **both** sides are numbers; otherwise it joins their text: `'Leg ' + 1` → `Leg 1`, `$.MISSING + 'x'` → `x`. A number
  inside quotes is text: `'1' + 1` → `11`.
- **`-`, `*`, `/`, `%`, unary `-`** convert both sides to numbers (text that is a number counts; `true` is 1). Anything that is not a number
  makes the result NaN, which shows as `NaN` (`$.QUANTTY * 2`): wrap possibly missing values, `nonblank($.QUANTTY, 0) * 2` → `0`.
- **Division and remainder by zero** give empty (`1 / 0`).
- **Comparisons** `<` `<=` `>` `>=` compare numbers numerically (text that reads as a number counts as one). Two texts that are not numbers
  compare as text, character by character, so ISO dates order correctly: `'2026-01-31' < '2026-02-01'` is true, `$.MATURITY_DATE <
  '2028-01-01'` is false for `MX-20000001` (2032-06-25), and `@.PAYMENT_DATE < today()` tells a paid flow from one to come. A number
  against text that is not a number, or anything against empty, is `false`.
- **Equality** `==` compares numerically when either side is a number (`1 == '1.0'` is true, so `@.LEG_ID == 1` holds for a leg id
  written `1` or `"1"`), otherwise as text (`$.TRADE_STATUS == 'amended'` is false for `AMENDED`: use `lower(...)` or `contains(...)` to
  ignore case). Empty equals only empty: `$.MISSING == null` is true; an extract's `""` is not `null`.
- **`&&` and `||`** short-circuit and give `true` or `false` (or `•••` when a masked value decides). For a default value use `nonblank` or
  `coalesce`, not `||`: `$.STRATEGY || 'n/a'` gives `true`, `nonblank($.STRATEGY, 'n/a')` gives the strategy or `n/a`.
- **Masked values.** A field the viewer's role sees masked (`drishti.security.redact`, roles without `raw`) is the value `•••`, and it
  carries through: a path under it is `•••`, and so is any operator, comparison, `?:` or function given it (`fmt($.NET_MTM, 'signed0')`,
  `$.NET_MTM / 1e6`, `link($.COUNTERPARTY, …)`, `sum($.TRADES, 'MTM')` when one element's `MTM` is masked). `•••` is never truthy, so a
  condition on a masked field is never true, `!` included; `coalesce` passes it on only when it is the first non-empty value, and
  `a || b` is still `true` when its other side is. Every format shows it as `•••`.
- **Whole numbers** print without decimals: `1 + 1` → `2`, `0.5 * 4` → `2`.
- **Lists and objects** print as `[n]` and `{n}` (their size) when shown as text (`$.LEGS[*].LEG_ID` shows `[2]`); use `size(...)` or a
  panel.
- **Compiled once.** Expressions are compiled once, cached by source text (`drishti.rachana.expression-cache-size`, 10,000), and shared
  across threads.
- **Checked at load.** Every expression in a Sutra is compiled when the file loads, so a typo is reported against the file (`DRS-2101`),
  not at view time. Field names are not checked: a misspelt field is simply empty.
- **Bounded.** An expression may nest at most 200 levels and be at most 10,000 characters long; see [Size limits](#size-limits).
- **Dependency paths.** Each compiled expression reports the document paths it reads (`$.LEGS[0].CASH_FLOWS[0].RATE`). Cells carry that
  path so live updates can flash exactly what changed.

### Paths and filters, by example

Against `MX-20000001`:

| Expression | Result |
|---|---|
| `$.TRADE_ID` | `MX-20000001` |
| `$.COUNTERPARTY` | `CP-MERIDIAN-RE` |
| `$.LEGS[1].INDEX.NAME` | `AONIA` |
| `$.LEGS[-1].LEG_ECONOMIC_TYPE` | `Pay` (the last leg) |
| `$['LEGS'][0]['RESET_CONVENTION']` | `Annual` |
| `$.LEGS[?@.LEG_TYPE == 'FLOAT'].INDEX.NAME` | empty: a filter gives a list, and `.INDEX` of a list is empty; use `first($.LEGS[?@.LEG_TYPE == 'FLOAT']).INDEX.NAME` → `AONIA` |
| `size($.EVENTS[?@.EVENT_TYPE == 'CASH_SETTLEMENT'])` | `35` |
| `size($.EVENTS[?!nonblank(@.LEG_ID)])` | `7` (the deal's own events) |
| `$.EVENTS[?nonblank(@.AMOUNT) && @.LEG_ID == 1]` | the fixed leg's 7 cash events (a list, for `rows:`); `sum(…, 'AMOUNT')` is `+68,325,150` |
| `$.LEGS[1].CASH_FLOWS[0].RESETS[0].RATE` | `0.035376` |
| `$.NOTHING.AT.ALL` | empty |
| `$.LEGS[*].LEG_ID` | `[1, 2]`: **projection**, the field of every element as a list (what a control's `options` read; `[*]` first, then `.field`) |
| `flatten($.LEGS[*].CASH_FLOWS)` | every cash flow of every leg, 35, as one list |
| `ctl.legs` | the value of the control `legs` (see [Controls](#controls)) |

### Functions

The set is closed (`Functions.java`); a name outside it is a compile error (`unknown function 'round'; known: abs, coalesce, contains, …`,
in alphabetical order), and so is a wrong number of arguments (`size takes 1 argument(s), got 2`, `link takes 1-3 argument(s), got 4`).
The schema lists them with their argument counts (`x-rachana-functions`).

| Function | Arguments | Returns |
|---|---|---|
| `link(id, kind?, label?)` | 1–3 | a link to entity `id` of `kind` (or the kind the reference catalogue recognises), shown as `label` or the id; empty when `id` is empty |
| `size(x)` | 1 | elements of a list, fields of an object, characters of a text; `0` for anything else (numbers, empty) |
| `sum(list, 'field'?)` | 1–2 | the sum of `field` over the list's elements (or of the elements themselves); non-numbers are skipped; `0` when `list` is not a list |
| `fmt(value, 'format')` | 2 | the value formatted as text with a named [format](#formats); an unknown format gives plain text |
| `coalesce(a, b, …)` | 1–8 | the first argument that is not empty (an empty text `''` is not empty); empty if all are |
| `nonblank(a, b, …)` | 1–8 | the first argument that has a value, skipping empty and all-space text as well as null (what most extracts write for a missing value): `nonblank(@.RATE, @.STRIKE_PRICE, '-')`; empty if none has |
| `first(list)` | 1 | the first element; empty for a non-list or an empty list |
| `last(list)` | 1 | the last element |
| `flatten(list)` | 1 | a list of lists made one list, in order: `flatten($.LEGS[*].CASH_FLOWS)` is every cash flow of every leg; elements that are not lists are kept; empty for anything but a list. One level only |
| `abs(x)` | 1 | the absolute value (NaN for a non-number) |
| `min(a, b, …)` / `min(list)` | 1–8 | the smallest number among the arguments, or among a single list's elements; non-numbers are skipped; empty if none |
| `max(a, b, …)` / `max(list)` | 1–8 | the largest, likewise: `max($.TRADES[*].NOTIONAL)` (a projection) is the largest notional |
| `upper(text)` | 1 | upper case (empty → `''`) |
| `lower(text)` | 1 | lower case |
| `contains(where, part)` | 2 | **case-insensitive**: true when the text contains `part`, or when `where` is a list with an element that does (nested lists are searched) |
| `startsWith(text, prefix)` | 2 | **case-insensitive** prefix test |
| `matches(text, pattern)` | 2 | **case-insensitive** regular-expression test, matching anywhere in the text (anchor with `^` and `$`). The pattern is run by a linear-time engine, so no pattern can take exponential time: classes `[a-z]` `\d` `\w` `\s`, groups `( )` and `(?: )`, alternation `\|`, `* + ? {n,m}`, `^`, `$`, a leading `(?i)`. Back-references, look-around, atomic groups, possessive quantifiers and `\b` are refused (`DRS-4010`), as is a pattern over 200 characters; a value is cut to 8,192 characters and a match over 50 ms is `DRS-4011`. In a Sutra a backslash must survive both YAML and Rachana-EL text, so prefer `[0-9]` to `\d`. In a search: `TRD where COUNTERPARTY matches '^CP-MER'` |
| `matchesCase(text, pattern)` | 2 | `matches`, but upper and lower case differ |
| `within(value, range)` | 2 | true when `value` lies in a `from..to` range (what a `date-range` or `number-range` control holds; either end may be empty and an empty range holds everything): numbers compare as numbers, anything else as text, so ISO dates order, and a timestamp is compared by its date. A missing or masked value is never within |
| `oneOf(value, list)` | 2 | true when `value` equals an element of `list` (what a `multi-select` control holds, or a projection); **nothing chosen (an empty list, or an empty text) holds everything**. A masked value is never one of them |
| `group(list, 'key', 'value'?, 'by'?)` | 2–4 | the list's elements grouped by the field `key` (a dotted path), one row `{label, value, count}` per distinct key in key order: `value` the sum of the field `value` over the group (number-like text counts; without a value field, the count), `count` its elements; `by` cuts an ISO date key to its `month` (2026-10) or `year` (2026) first. Elements without a key are left out; at most 500 groups. Feeds a chart: `rows: "group($.EVENTS[?nonblank(@.AMOUNT)], 'EVENT_DATE', 'AMOUNT')"` is a payment ladder |
| `days(from, to)` | 2 | whole calendar days from one ISO date to another (a timestamp counts by its date), negative when `to` is earlier; empty when either is not a date |
| `today()` | 0 | the business date the view is built for (the document's, else the picked or current date), as ISO text; empty outside a view. `$.EVENTS[?days(today(), @.EVENT_DATE) >= 0 && days(today(), @.EVENT_DATE) <= 30]` are the events of the next 30 days |

Examples against `MX-20000001` (the business date 2026-10-16 where it matters):

| Expression | Result | Shown with a format |
|---|---|---|
| `link($.PORTFOLIO, 'book')` | a link to `book BOOK-RATES-3` | `BOOK-RATES-3` (a link) |
| `link($.TRADER_ID, 'trader', $.TRADER_FIRST_NAME + ' ' + $.TRADER_LAST_NAME)` | a link shown as `A. Shah` | |
| `link($.COUNTERPARTY)` | a link; the kind comes from the id pattern `^CP-` | |
| `size($.LEGS)` | `2` | |
| `size($.CURRENCY)` | `3` | |
| `sum($.LEGS, 'NOTIONAL')` | `484000000` | `amount0`: `484,000,000` |
| `sum($.EVENTS[?nonblank(@.AMOUNT)], 'AMOUNT')` | `-1061367.76` (a fraction: always give sums a format) | `signed0`: `−1,061,368` |
| `fmt($.QUANTITY, 'amount0')` | `242,000,000` (text) | |
| `$.CURRENCY + ' ' + fmt(first($.LEGS).NOTIONAL, 'amount0')` | `AUD 242,000,000` | |
| `coalesce($.STRATEGY, 'n/a')` | `n/a` (`STRATEGY` is null) | |
| `coalesce($.PACKAGE_ID, 'n/a')` | empty text (`PACKAGE_ID` is `""`, which `coalesce` keeps) | |
| `nonblank($.PACKAGE_ID, 'n/a')` | `n/a` | |
| `first($.LEGS).LEG_ECONOMIC_TYPE` | `Receive` | |
| `last($.EVENTS).EVENT_TYPE` | `CASH_SETTLEMENT` | |
| `abs(-155245)` | `155245` | |
| `min($.PRICE, 0.05)` | `0.040829` | `pct4`: `4.0829%` |
| `max(3, '7', 'x')` | `7` | |
| `max($.LEGS[*].NOTIONAL)` | `242000000` | |
| `upper($.PRODUCT_SUB_GROUP)` | `SWAP` | |
| `lower($.TRADE_STATUS)` | `amended` | |
| `contains($.PRODUCT_SUB_TYPE, 'SWAP')` | `true` | |
| `contains($.LEGS[*].LEG_TYPE, 'float')` | `true` (a list with an element that contains it) | |
| `startsWith($.PORTFOLIO, 'book-rates')` | `true` | |
| `matches($.TRADE_ID, '^MX-2[0-9]+$')` | `true` | |
| `matchesCase($.TRADE_ID, '^mx-')` | `false` | |
| `within($.MATURITY_DATE, '2030-01-01..2035-12-31')` | `true` | |
| `oneOf($.CURRENCY, $.LEGS[*].CURRENCY)` | `true` | |
| `size(group($.EVENTS[?nonblank(@.AMOUNT)], 'EVENT_DATE', 'AMOUNT', 'year'))` | `8` (2025 to 2032; `'month'` gives 28) | |
| `days($.TRADE_DATE, $.MATURITY_DATE)` | `2527` | |
| `days(today(), $.MATURITY_DATE)` | `2079` | |
| `today()` | `2026-10-16` | `dmy`: `16 Oct 2026` |

`min`/`max` over a list look at the elements themselves, so `max($.LEGS)` (a list of objects) is empty: project the field first,
`max($.LEGS[*].NOTIONAL)`.

### Templates

A template is text with `${expression}` parts: `"Cash flows · leg ${$.LEGS[0].LEG_ID}"`. Each part is evaluated and shown as text (a
link shows its label; a list shows `[n]`). Braces inside a part are matched, so `"${size($.LEGS)} legs"` works. An unclosed `${` is
`DRS-2101 template '…': unclosed '${' at 5`. Text without `${` is used as is.

Templates are used for panel titles, `title.pill`, `markdown.text`, a metric's `caption` and alert messages. Labels (`label:`) are plain
text, not templates.

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
postfix  = primary { "." ident | "[" expr "]" | "[?" expr "]" | "[*]" } ;     (* a "." ident after "[*]" takes that field of each element *)
primary  = number | string | "true" | "false" | "null" | "$" | "@" | "#index"
         | "ctl" "." ident                                                  (* the value of a control *)
         | ident "(" [ expr { "," expr } ] ")" | ident | "(" expr ")" ;
```

### Size limits

Parsing and evaluation are recursive, so an expression's size is bounded; within the bounds both are safe on any thread, and beyond them
the expression does not compile (`DRS-2101`, with the position where the bound was passed). The same bounds hold wherever Rachana-EL is
read: Sutras (at load and in a workbench preview), alert rules, search conditions (`TRD where …`), history paths and derived kinds.

| Bound | Default | Setting | Message |
|---|---|---|---|
| nesting depth | 200 | `drishti.rachana.max-expression-depth` | `expression nested deeper than 200 levels (drishti.rachana.max-expression-depth) at <offset>` |
| length | 10,000 characters | `drishti.rachana.max-expression-length` | `expression is longer than 10000 characters (drishti.rachana.max-expression-length) at 10000` |

Every level of nesting counts: a parenthesis, a `[…]` index or filter, a function call, a unary `!` or `-`, a branch of `?:`, a `.field`
step, and each operand of a chain of binary operators (`$.A + $.B + $.C` is three levels deep, since it is evaluated as
`($.A + $.B) + $.C`). Real Sutras stay far below 200; a chain of hundreds of terms is better written with `sum(list, 'field')`. A
template's `${…}` parts are bounded one by one.

### Expression errors

| Code | When | Example message |
|---|---|---|
| `DRS-2101` | an expression or template does not compile (at load, in a workbench preview, or when saving an alert rule), including one beyond the [size limits](#size-limits) | `expression '$.LEGS[0': expected ']' but the expression ends at 8` |
| `DRS-2102` | evaluation error | evaluation is total, so a well-formed expression within the size limits never raises it; if one does (the limits raised far beyond the default), the panel shows `DRS-2102 an expression of this panel is nested too deeply to evaluate …` and the rest of the view renders |

The problem's message is `expression '<source>': <reason> at <offset>` (or `template '<source>': …`), where the offset is the 0-based
character position inside the expression; `sutra lint` prints it after the file, line and code:
`counterparty-mine.v1.sutra.yaml:6 DRS-2101 expression 'size($.NETTING_SETS, 'TRADES')': size takes 1 argument(s), got 2 at 0`.
Common reasons:

| Message | Cause | Fix |
|---|---|---|
| `unexpected character '&' at 9` | `&` or `\|` alone, `and`/`or` written as symbols of another language | use `&&`, `\|\|` |
| `unexpected '[' at 18` | a list written in the expression (`oneOf($.CURRENCY, ['AUD', 'NZD'])`) | there are no list literals: compare (`$.CURRENCY == 'AUD' \|\| $.CURRENCY == 'NZD'`), or read a list from the document or a control |
| `unterminated string at 14` | a quote not closed | close it; inside YAML double quotes, use single quotes for EL text |
| `expected ')' but the expression ends at 20` | a bracket or parenthesis not closed | close it |
| `expected a field name but the expression ends at 7` | a path ending in a dot (`$.LEGS.`) | finish the path |
| `the expression ends early: a value is missing at 7` | an operator with nothing after it (`$.QUANTITY >`) | finish the expression |
| `unknown function 'round'; known: abs, coalesce, …` | a function that does not exist (the known ones are listed alphabetically) | use `fmt(x, 'amount0')` and the other functions above |
| `fmt takes 2 argument(s), got 1 at 0` | `fmt($.QUANTITY)` | `fmt($.QUANTITY, 'amount0')` |
| `unexpected 'b' at 4` | two values with no operator between them (`'a' 'b'`) | join with `+` |
| `unclosed '${' at 4` | a template part not closed | add `}` |
| `expression nested deeper than 200 levels (drishti.rachana.max-expression-depth) at 1203` | an expression nested past the [size limits](#size-limits), or a very long chain of `+`, `&&`, `\|\|`, … | simplify it: `sum(...)` for long sums, a shorter condition; raise the setting only if you must |
| `expression is longer than 10000 characters (drishti.rachana.max-expression-length) at 10000` | a generated or pasted expression too long | shorten it, or raise the setting |

## Problem codes

A Sutra file that fails any check is not loaded (or keeps its last good version, see
[Hot reload](#hot-reload-the-workbench-and-governance)), and each problem is reported as `<file>:<line>:<column> <code> <message>`
(`sutra lint`: `<file>:<line> <code> <message>`). All problems of a file are reported at once. The grammar's own codes run
`DRS-2010` to `DRS-2039`; around them are YAML and file problems (`DRS-2001`, `DRS-2004`, `DRS-2009`, `DRS-2032`, `DRS-2033`), the pack about-file
family (`DRS-2040` to `DRS-2047`), shared blocks (`DRS-2048`) and expressions (`DRS-2101`).

| Code | Message (exact form) | Cause | Fix |
|---|---|---|---|
| `DRS-2001` | `YAML syntax: <parser message>` | the file is not valid YAML (bad indentation, a `:` or `#` in an unquoted value, a tab) | fix the YAML; quote values with `: `, ` #`, or a leading `#`, `@`, `*`, `&` |
| `DRS-2004` | `Markdown Sutras are no longer read (Sutras are YAML since 1.11): convert it with python3 tools/rachana/md_to_yaml.py <file> --delete`, or `a Sutra file is named <name>.v<N>.sutra.yaml; rename <file>` | a `.sutra.md` file, or a plain `.yaml`/`.yml` file, in a Sutra directory | convert it with `python3 tools/rachana/md_to_yaml.py <file-or-folder> --delete`, or rename it to `<name>.v<N>.sutra.yaml` (or move a YAML file that is not a Sutra out of the directory) |
| `DRS-2009` | `missing 'rachana: 1' (the Rachana language version) at the top`, `'rachana: 2' is not a language version this server reads (it reads 1)` | no `rachana:` key, or a version this server does not know | put `rachana: 1` first in the file |
| `DRS-2010` | `missing 'sutra'`, `missing 'version'`, `missing 'kind'`, `missing 'id'`, `missing 'bind'`, `missing 'match' mapping with at least 'kind'`, `missing 'id' in title: the expression for the large identifier, such as $.TRADE_ID`, `a panel inside a tab needs an 'id'` | a required key is absent | add it |
| `DRS-2011` | `unknown key 'pannels' in top level` (also `in match`, `in title`, `in strip item`, `in column`) | a misspelt or unsupported key | fix the spelling; panel options are reported as `DRS-2023` instead |
| `DRS-2012` | `'panels' must be a list`, `'keys' must be a mapping of F-key to action`, `a column must be a mapping (label, bind, fmt, tone, total, link)`, `'kind' must be text`, `action for F7 must be text`, `'notes' is plain text`, `'kind' in match is an entity kind written as text (such as trade), not '42'`, `'priority' in match must be a whole number (higher is tried first), not 'high'` | a value of the wrong shape | write the shape shown in the message |
| `DRS-2020` | `name 'IRS_Vanilla' must be lower-case kebab, 2-64 characters`, `version must be a positive integer up to 2147483647, not '0'` | bad name or version (also a version too large for any number, which is not reported as YAML syntax) | `irs-vanilla`; `version: 3` unquoted |
| `DRS-2021` | `unknown panel kind 'chart'; expected one of kv, table, tabs, line, area, hbar, ladder, links, status, provenance, markdown, gauge, surface, waterfall, histogram, scatter, candlestick, graph, timeline, pivot, metric, map` | a kind that does not exist | use one of the 22 kinds |
| `DRS-2022` | `'table' panel 'flows' needs option 'rows'` | a required option is missing | add it (see each kind's table) |
| `DRS-2023` | `option 'limit' is not valid for 'ladder' panels`, `only 'tabs' panels take a 'body'`, `only 'tabs' panels take 'panels'`, `a 'surface' panel cannot sit inside a tab; use one of kv, status, table, ladder, hbar, markdown, metric, line, area, timeline`, `a panel inside a tab cannot hold tabs of its own`, `a panel inside a tab cannot have a Pivot tab; put the table in a panel of its own`, `option 'pivot' applies only to panels that show a table (table, ladder), not 'kv'` | an option the kind does not accept | remove it, or change the kind |
| `DRS-2024` | `duplicate panel id 'legs'`, `two panels of this tab are called 'terms'` | two panels with one id | rename one |
| `DRS-2025` | `'F13' is not a function key (F1-F12)`, `function key F2 is used twice` | a bad or repeated key | use `F1`–`F12` once each, across panels and `keys` |
| `DRS-2026` | `the strip holds at most 8 figures, found 9` | more than 8 strip items | move figures into a `kv` panel |
| `DRS-2027` | `area must be 'main' or 'right'` | `area: left`, `area: side` | `main` or `right` |
| `DRS-2028` | `trade-x@2 is already defined in /…/trade-x.v2.sutra.yaml` | two files define one `name@version` | raise the version, or remove the duplicate |
| `DRS-2029` | `option 'agg' of 'pivot' panels must be one of sum, count, avg, min, max, not 'median'`, `option 'heat' of 'pivot' panels must be true or false, not 'yes'`, `option 'bins' of 'histogram' panels must be a whole number from 1 to 200, not '0'`, `each histogram marker must be a mapping with a 'value' expression …`, `option 'layout' of 'graph' panels must be one of tree, force, not 'circle'`, `option 'colors' of 'waterfall' panels must be one of gain-loss, theme, not 'rainbow'`, `option 'rows' of 'table' panels is an expression written as text (such as $.cashflows), not '5'`, `option 'limit' of 'table' panels must be a whole number of rows, 1 or more, not 'many'`, `option 'search' of 'table' panels must be true or false, not 'maybe'`, `option 'fields' of 'status' panels must be a list of { label, bind, fmt, tone }, not '5'`, `option 'series' of 'area' panels must be a list of { label, value, tone }, not '5'`, `option 'text' of 'markdown' panels is text (a template with ${expression} parts), not '[1, 2]'` | an option value the kind does not allow, or of the wrong type | use one of the values listed, or the type named |
| `DRS-2030` | `span must be a whole number from 1 to 12 (columns of the 12-column grid), not '13'`, `height must be a whole number from 1 to 24 (grid rows), not '30'`, `'span' sizes a whole panel: a tabs body takes the size of its panel` | a size outside the grid, not a whole number, or on a `tabs` body or a panel inside a tab | a whole number in range, on the panel itself |
| `DRS-2031` | `'pivot' is true, false, or a mapping of fields, rows, columns, values, filters, heat, chart, totals; not 'yes please'`, `pivot rows name 'desk', which is not one of its fields (currency, mtm, product)`, `pivot value 'agg' must be one of sum, count, avg, min, max, distinct, not 'median'`, `pivot value 'show' must be one of value, pctRow, pctColumn, pctTotal, not 'pctBook'`, `pivot field 'a' is in both rows and columns`, `a pivot has at most 4 row fields and 4 column fields`, `a pivot shows at most 6 values, found 7`, `pivot field '$.book' is not a field path (letters, digits, _ and dots); for an expression write { field: name, bind: <expression> }`, `pivot field 'a' is listed twice`, `unknown key 'colour' in pivot`, `pivot 'chart' must be one of bar, line, heatmap, not 'pie'`, `pivot 'heat' is true or false, not 'maybe'`, `a pivot filter keeps either 'values' or a range ('min', 'max'), not both`, `pivot 'fields' is a non-empty list of field paths (or { field, bind, label, fmt })` | a `pivot:` the Pivot tab cannot use | use the names its fields have, the values listed, or `pivot: true` ([the option](#pivot-a-pivot-tab-on-a-table-or-ladder)) |
| `DRS-2032` | `the file could not be loaded: <reason>` (for example `it is nested too deeply to read (StackOverflowError)`) | a failure while reading the file that none of the other checks foresee; the server log has the stack trace | simplify the file; if it looks valid, report the log entry. The other Sutras load and hot reload carries on |
| `DRS-2033` | `duplicate key 'version' (first written at line 3): YAML would keep only the last, so write each key once`, `a second YAML document (after '---'): a Sutra file holds exactly one, and the rest would be ignored`, `YAML tag '!panel' is not allowed in a Sutra: write the value plainly` | YAML that is valid but would not be read as written: a key twice in one mapping, a second document, a tag other than the core ones (`!!str`, `!!int`, …) | write each key once, one document per file, no tags |
| `DRS-2034` | `unknown control kind 'knob'; expected one of dropdown, multi-select, ...`, `a dropdown control needs 'options': a list, an expression such as $.legs[*].id, or { kind: <entity kind> }`, `control id '9x' must be a letter, then letters, digits and underscores`, `a search control takes no 'options'`, `placement must be strip, header or side`, `a control placed in a panel header names the panel`, `remember is user or none`, `a Sutra has at most 12 controls` | a control that is not well formed | follow the message; the keys are in [Controls](#controls) |
| `DRS-2035` | `duplicate control id 'legs'` | two controls with one id | rename one |
| `DRS-2036` | `'ctl.leg' reads a control the Sutra does not declare (it has legs, when)` | an expression or template reads `ctl.<id>` for an id with no control | declare the control or fix the name |
| `DRS-2037` | `a cascading control names the control it depends on: parent: <control id>`, `control 'country' depends on 'region', which must be a control declared before it` | a cascading control without a parent, or a parent that is missing or later | declare the parent first |
| `DRS-2038` | `on-select of panel 'byAsset': 'by' must name one of the Sutra's controls (asset, product), not 'x'`, `on-select ... filters panel 'ghost', which the Sutra does not have`, `on-select ... has no key 'colour'` | an `on-select` that names nothing real | `{ by: <control id>, filter: [panel ids], field: <row field> }` |
| `DRS-2039` | `default of control 'when': 'soon' is not a range (2026-10-01..2026-12-31) or a relative one (next-90d, last-30d, this-month, ytd)`, `'7' is not one of its options (5)` | a default the control cannot hold | a value among the options, `first`, `all`, a date or a relative token |
| `DRS-2040` | `unknown key 'titel' in kind 'var' (expected title, about, guide, glossary or panels)`, `the file must start with 'about: 1' (the schema version)`, `the file is not valid YAML: …` | a pack's `config/about.yaml` ([About text](../architecture/CONTEXT_HELP.md)) has an unknown key, a wrong or missing version, a wrongly shaped entry, a duplicate key or broken YAML | fix the line named; only that entry is left out, the view is never affected |
| `DRS-2041` | `kind 'stranger' belongs to neither this pack nor a pack it extends` | an about file writes about a kind its pack (or a pack it extends) does not own | name a kind from the pack's `kinds:` or a parent's, or move the entry to the pack that owns the kind |
| `DRS-2042` | `the template does not compile: …` | an `about` template (a kind's or a panel's) has a `${...}` expression that does not compile | fix the expression; the line and column are those of the text in the file |
| `DRS-2043` | `use: nope names no vocabulary entry visible to pack market-risk (its own or an extended pack's)` | a glossary entry's `use` names no `vocabulary` entry in the pack's file or its parents' | add the vocabulary entry or fix the name |
| `DRS-2044` | `text is 700 characters; the limit is 600 (drishti.about.max-text)` | one text of an about entry (term, means, unit, sign, note, formula, a value's meaning, title) is longer than the cap | shorten it |
| `DRS-2045` | `F1 is bound in keys: it opens About this page on a view, and this binding wins on this view (the drawer stays on '?'); bind another key to keep F1 for help` | a lint warning (`sutra lint`), not a load error: a Sutra binds `F1` | bind a key from `F2` to `F12`, or accept that `F1` runs your binding on this view |
| `DRS-2046` | `about text for panel 'ghost' of kind 'widget' matches no panel of any Sutra for the kind` | a lint warning: `panels.<id>` in `about.yaml` names no panel | correct the id or delete the entry |
| `DRS-2048` | `no block legs@3 (blocks: deal-ticket, legs, events)` | a `use:` names no block, a `with:` names no parameter, a block uses another or its file is misnamed | write the block, fix the name or version, or drop the parameter ([Shared panel blocks](#shared-panel-blocks-use)) |
| `DRS-2047` | `field 'colour' is shown but has no glossary entry (kinds.widget.glossary in config/about.yaml, or a vocabulary entry)` | a lint warning: a field a Sutra shows has no glossary entry; fails `sutra lint --strict` | add the entry to the kind's `glossary:` or to `vocabulary:` |
| `DRS-2101` | `expression '…': <reason> at <offset>`, `template '…': <reason> at <offset>` | an expression or template does not compile, or is beyond the [size limits](#size-limits) | see [Expression errors](#expression-errors) |

Other codes you may meet around Sutras:

| Code | HTTP | Meaning |
|---|---|---|
| `DRS-2002` | 422 | the envelope of any of the above through the API: `N problem(s): …`, with a `problems` list in the body (`POST /api/v1/studio/preview`, `POST /api/v1/sutras`) |
| `DRS-2003` | 404 | no Sutra `name@version` (`GET /api/v1/sutras/{name}/{version}`) |
| `DRS-2005` | 404 | no such governance proposal |
| `DRS-2006` | 409 | a proposal's Sutra changed after it was proposed: reject it and propose again from the live version |
| `DRS-2007` | 403 | four eyes: the author of a proposal cannot approve it |
| `DRS-2060` | 422 | a search form (`form:` of a pack, or a design's Form tab) that is not valid; the message names the field and what is allowed ([Pack search form](#pack-search-form-form-in-packyaml)) |

Not validated by the parser (so check them in a preview; the [schema](#completion-and-checking-in-your-editor) offers the valid format and
tone names as you type): format and tone names, field names used as `x`, `y`, `label`, `value`, series `value`s, panel ids used as `keys`
actions, `layout` and `view` values, and the keys inside status `fields`.

Where to see problems:

```bash
curl -s -H "$AUTH" $DRISHTI/sutras/problems
```

You should see `{}` when every file is valid, otherwise a map from file to its problems:

```json
{"/srv/drishti/sutras/rates/swap-x.v1.sutra.yaml": [
  {"code": "DRS-2023", "message": "option 'limit' is not valid for 'ladder' panels",
   "location": {"file": "swap-x.v1.sutra.yaml", "line": 31, "column": 12}}]}
```

The same list appears in the server log (`sutra problem …`), on the server health page, in the workbench when you preview or save, and,
without a server, from `drishti.py sutra lint`.

## Recipes

Each recipe is a panel (or two) you can paste into the `panels:` of a Sutra for `trade` and preview against `MX-20000001`. The figures
under each are what that preview shows on the business date 2026-10-16.

### A table with a filter, totals and "more"

```yaml
  - id: flows
    kind: table
    title: "Cash events to come (${size($.EVENTS[?nonblank(@.AMOUNT) && nonblank(@.LEG_ID) && @.EVENT_DATE > today()])})"
    key: F6
    rows: "$.EVENTS[?nonblank(@.AMOUNT) && nonblank(@.LEG_ID) && @.EVENT_DATE > today()]"
    limit: 5
    moreLabel: "(size($.EVENTS[?nonblank(@.AMOUNT) && nonblank(@.LEG_ID) && @.EVENT_DATE > today()]) - 5) + ' more events'"
    totalLabel: Net
    columns:
      - { label: Date, bind: "@.EVENT_DATE", fmt: date }
      - { label: Leg, bind: "@.LEG_ID == 1 ? 'Fixed' : 'Float'" }
      - { label: Type, bind: "@.PAYMENT_TYPE" }
      - { label: Amount, bind: "@.AMOUNT", fmt: signed0, tone: sign, total: true }
```

You should see the title `Cash events to come (29)`, five rows starting `2027-06-29 · Fixed · Interest · +9,880,618`, a `Net` row of
`+2,403,272` (all 29 events, not just the five shown), `24 more events`, and `F6 Cash events` in the key bar.

### A chart from another entity's curve

```yaml
  - id: curve
    kind: line
    title: Discount curve (zero rates)
    code: CRV
    area: right
    source: "link($.LEGS[0].DISCOUNT_INDEX, 'ir-curve')"
    rows: $.POINTS
    x: TENOR
    y: ZERO_RATE
    mark: "'5Y'"
    fmt: price2
    unit: "%"
```

You should see the AUD OIS curve from `1M` to `30Y`, the `5Y` point marked, `5Y point 4.03%` under the chart and a `CRV-AUD-OIS` link
beside it. The mark can follow the trade: `mark: "$.MATURITY_TENOR"` when the document carries one.

### Tabs per element

```yaml
  - id: legs
    kind: tabs
    title: Legs
    key: F3
    each: $.LEGS
    tabTitle: "@.LEG_ECONOMIC_TYPE + ' ' + @.LEG_TYPE"
    body:
      kind: kv
      columns:
        - { label: Index, bind: "nonblank(@.INDEX.NAME, 'fixed')" }
        - { label: Rate, bind: "@.CASH_FLOWS[0].RATE", fmt: pct4 }
        - { label: Reset, bind: "@.RESET_CONVENTION" }
        - { label: Calendar, bind: "@.RESET_HOLIDAY_SCHEDULE" }
```

You should see two tabs, `Receive FIXED` and `Pay FLOAT`; the first shows `Index fixed`, `Rate 4.0829%`, `Reset Annual`,
`Calendar GBLO`, the second `Index AONIA`, `Rate 3.5377%` (its first period's rate), `Reset Monthly`.

### A ladder with the next row lit

```yaml
  - id: ladder
    kind: ladder
    title: Fixed leg, the next payment lit
    rows: $.LEGS[0].CASH_FLOWS
    highlight: "@.PAYMENT_DATE == first($.LEGS[0].CASH_FLOWS[?@.PAYMENT_DATE > today()]).PAYMENT_DATE"
    columns:
      - { label: Payment, bind: "@.PAYMENT_DATE", fmt: date }
      - { label: Rate, bind: "@.RATE", fmt: pct4 }
      - { label: Status, bind: "@.PAYMENT_DATE < today() ? 'Paid' : 'Future'", tone: status }
      - { label: Notional, bind: "@.NOTIONAL", fmt: amount0, total: true }
```

You should see seven rows, `2026-06-29 … Paid` first and `2027-06-29 … Future` highlighted, statuses in the `ok` colour, and a `Total`
of `1,694,000,000`.

### Links: a panel, a column and a key

```yaml
  - id: execution
    kind: kv
    title: Execution
    columns:
      - { label: Trader, bind: $.TRADER_ID }                   # ends in _ID: links when a pack knows the pattern (TRDR-)
      - { label: Transaction, bind: $.TRANSACTION_ID }         # ends in _ID, but no pack knows the pattern: plain text
      - { label: Book, bind: "link($.PORTFOLIO, 'book')" }     # always a link to the book
  - { id: refs, kind: links, title: Linked entities, code: REFS, area: right }
keys: { F7: "link($.COUNTERPARTY, 'counterparty')", F8: impact, F9: raw }
```

You should see `Trader TRDR-ASHAH` and `Book BOOK-RATES-3` as links, `Transaction 20000001002` as plain text, the *Linked entities* list
on the right, and `F7 Counterparty`, `F8 Impact`, `F9 Raw JSON` in the key bar.

### A strip that reads well

```yaml
strip:
  - { label: Notional, bind: "$.CURRENCY + ' ' + fmt(first($.LEGS).NOTIONAL, 'amount0')", emphasis: true }
  - { label: Side, bind: "$.BUY_SELL_CODE == 'B' ? 'Buy' : 'Sell'" }
  - { label: Status, bind: $.TRADE_STATUS, tone: status }
  - { label: Next payment, bind: "first($.EVENTS[?nonblank(@.AMOUNT) && @.EVENT_DATE > today()]).EVENT_DATE", fmt: date }
  - { label: Days to maturity, bind: "days(today(), $.MATURITY_DATE)", fmt: amount0 }
```

You should see `Notional AUD 242,000,000` highlighted, `Side Sell`, `Status AMENDED` in the `ok` colour, `Next payment 2027-06-29` and
`Days to maturity 2,079`. A booking carries no MTM; put the valuation's figures on the trade page with a panel whose
`source: "link('TVAL-' + $.TRADE_ID, 'trade-valuation')"` reads market-risk's trade valuation.

### A donut of shares

```yaml
  - id: shares
    kind: hbar
    title: Notional by leg type
    area: right
    shape: donut
    rows: "group($.LEGS, 'LEG_TYPE', 'NOTIONAL')"
    fmt: compact
```

You should see a ring with `484.0m` in the middle and two equal slices, `FIXED 242.0m` and `FLOAT 242.0m`.

## Hot reload, the workbench and governance

### Editing files on disk

- With `drishti.rachana.hot-reload: true` (the default) a watcher thread follows every Sutra directory (site and packs). A burst of file
  events is debounced (`drishti.rachana.reload-debounce`, 250 ms) into **one** reload, which rescans every directory.
- A file that parses and validates replaces its previous version at once. A file that becomes invalid keeps its **last good** Sutra
  live, and its problems are reported (log, `GET /api/v1/sutras/problems`). A file that was never valid since start-up is simply not
  loaded. A deleted file's Sutras disappear. A changed block is re-expanded into every Sutra that uses it.
- After a reload that changed anything, every cached layout is dropped, so the next build of every view (a page load, a refresh, or the
  next live tick) uses the new Sutra. Open live views pick it up on their next tick.
- Directories are registered with the watcher at start-up. Files in a folder created later are loaded at the next reload (any change in
  a watched folder triggers a full rescan) or at restart.
- No file can stop the watcher: anything that goes wrong reading one file is that file's problem (`DRS-2032` when no other check names
  it), and the next edit is picked up as usual. `GET /api/v1/admin/health` shows `"sutras": {"hotReload": "WATCHING", "problemFiles": 0}`;
  `OFF` means hot reload is turned off, and `STOPPED: <reason>` (with the overall status `DEGRADED` and an error in the log) means the
  watcher has ended unexpectedly and edits are not picked up until a restart.
- Sutras hot-reload on their own; formats and semantic hints are re-read whenever the packs change (an edit of a pack's
  `config/formats.yaml` or `config/semantics.yaml` is picked up within the packs poll interval, with no restart; a file that is not
  valid YAML refuses the change and the last good one stays).

### The workbench

Sutras are written and previewed in the **Build workbench** (Build → New screen in the console). Its use is the
[Screen designer guide](SCREEN_DESIGNER.md) (the YAML tab is the editor, with completion from the
[schema](#completion-and-checking-in-your-editor) and a check as you type; the Design, Summary, Problems, Tests and Versions tabs read
the same Sutra back; saving and proposing are [section 22](SCREEN_DESIGNER.md#22-saving-and-proposing)). The old `/studio` page no longer
exists; its addresses redirect into the workbench ([where things went](SCREEN_DESIGNER.md#19-studio-users-where-things-went)). The HTTP
facts that remain, for scripts:

- `GET /api/v1/studio/inferred/{kind}/{id}?name=...` (or `POST /api/v1/studio/inferred` with sample JSON) returns a new YAML Sutra
  (`rachana: 1`, a `description`, `priority: 1`) holding what inference made of that entity.
- `POST /api/v1/studio/preview` with `{yaml, kind, id}` (or `document` instead of an id) parses and checks the text
  (`SutraRegistry.check`, problems located in `studio.sutra.yaml`) and builds the view with the unsaved Sutra, bypassing the layout cache
  and the matcher: **a preview always uses your Sutra, whatever its `match` says**. Nothing is written. A personal token needs the
  `design:write` scope.
- Saving needs `drishti.rachana.studio-save: true` (off by default; the quick-start run configurations set `DRISHTI_STUDIO_SAVE=true`) and a
  role that may author Sutras. The text is sent as YAML (`POST /api/v1/sutras`, `Content-Type: text/yaml`) and, with governance on, becomes
  a proposal for review: `202` with `{"proposal": {"name", "version", "status": "pending", "id": "P-000001"}}`.

### Governance

With `drishti.governance.enabled: true` (the default), Save does not publish: it records a **proposal**.

| Step | Who | What happens |
|---|---|---|
| Propose | an author | the Sutra is checked as it stands (it must be publishable) and stored with the live text it is based on (`202 Accepted`, `{"proposal": {"id", "name", "version", "status": "pending"}}`). Proposing exactly the live text is refused. |
| Review | an approver | **Build → Govern → Reviews** (`/build/reviews`) lists proposals; each shows a diff against the live version (or, for a new version, the latest earlier one): panel blocks that moved are said in words (`moved: <title> from position a to b (main → side)`, positions counted within the column), the other edits as a line diff, and the full line diff behind a toggle |
| Approve | an approver | with `drishti.governance.four-eyes: true` (default) and security on, the author cannot approve their own proposal (`DRS-2007`). If the live text changed since the proposal, approval is refused (`DRS-2006`). Otherwise the file is written and goes live. `POST /api/v1/sutras/proposals/{id}/approve` (token scope `design:approve`) |
| Reject | an approver | needs a comment saying why (`…/reject`) |
| Withdraw | the author or an admin | `POST /api/v1/sutras/proposals/{id}/withdraw` (`design:write`) |

Every step is audited (`sutra-proposed`, `sutra-approved`, …). `GET /api/v1/sutras/{name}/history` lists a Sutra's proposals. With
governance off, Save writes at once.

Where a save (or an approval) writes: `<first directory of drishti.rachana.dirs>/<domain>/<name>.v<N>.sutra.yaml`, exactly the text you
wrote, comments included; then it reloads. A `name@version` already defined by another file (for example by a pack) is refused with
`DRS-2028`: to change a pack's Sutra from the workbench, save it as a **new version**; the latest version wins matching.

## The reference Sutras

| File | Open it with | Shows |
|---|---|---|
| `config/packs/trading/sutras/murex/irs-fixfloat.v1.sutra.yaml` | `TRD MX-20000001` | a product Sutra made of shared blocks, two controls on its events table |
| `config/packs/trading/sutras/endur/endur-comswap.v1.sutra.yaml` | `TRD 1000001`, `TRD 1000003` | sub types as `when:` panels (`fixed-float`, `basis`) |
| `config/packs/trading/sutras/common/*.v1.block.yaml` | (used by every trade Sutra) | blocks with parameters; tabs holding panels; a tree of cash flows and resets; timeline; payment ladder |
| `config/packs/trading/sutras/pages/counterparty-trades.v1.sutra.yaml` | `/v/counterparty-trades/CP-NORTHPLAINS` | metric tiles, a donut, ladders over many trades |
| `config/packs/core/sutras/counterparty-and-legal/counterparty.v1.sutra.yaml` | `CPTY CP-MERIDIAN-RE` | table with totals, kv, graph |
| `config/packs/counterparty-risk/sutras/exposure-and-capital/netting-set.v1.sutra.yaml` | `NSET NS-MERIDIAN-RE-NY` | area with a limit, four controls, `on-select`, a Pivot tab, automatic id links |
| `config/packs/market-data/sutras/market-data/equity-vol-surface.v1.sutra.yaml` | `EQV EQV-CSCA` | surface, table, line |
| `config/packs/market-risk/sutras/market-risk/var.v1.sutra.yaml` | `VAR VAR-COMM` | histogram with markers, line, hbar |
| `config/packs/world-economy/sutras/economics/economy.v1.sutra.yaml` | `ECON ECON-AUS` | map with a shade and a link |
| `docs/guides/examples/*.sutra.yaml` | Help → Examples | eleven self-contained examples with their own small documents, every kind |

For a guided, step-by-step introduction, see [SUTRA_DEVELOPER_GUIDE.md](SUTRA_DEVELOPER_GUIDE.md). For how inference fills what a Sutra
leaves out, see [INFERENCE.md](../architecture/INFERENCE.md).
