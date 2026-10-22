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
# The UI Board: screens as circuit boards of certified chips

Status: **release 2.7.0, being built: P1 (the model and the board check) and P2 (the Chip Store and saved boards) are built** ([12.1](#121-as-built-p1), [12.2](#122-as-built-p2)); the rest is design. Decided by the product owner on 2026-10-21 (the north star of
[IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)). Related: [GENUI.md](GENUI.md) (typed slots on blocks, the composer; its
fragment catalogue becomes the Chip Store, its composer becomes the 2.9.0 AI author of board documents),
[CONTENT_STORE.md](CONTENT_STORE.md) (where chips and boards live), [BI_RUNS.md](BI_RUNS.md) section 9 (access profiles,
masks), [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md) and [PANELS.md](../guides/PANELS.md) (the grammar and its panel kinds),
[BOARDS.md](../guides/BOARDS.md) and [REPORTS.md](../guides/REPORTS.md) (today's BI boards and reports).

## Contents

1. [Definitions](#1-definitions)
2. [The chip document](#2-the-chip-document)
3. [The board document](#3-the-board-document)
4. [Pin types and compatibility](#4-pin-types-and-compatibility)
5. [The board check](#5-the-board-check)
6. [The render API](#6-the-render-api)
7. [Security, masks and limits](#7-security-masks-and-limits)
8. [Storage keys](#8-storage-keys)
9. [The Chip Store: lifecycle and versions](#9-the-chip-store-lifecycle-and-versions)
10. [The Board Designer](#10-the-board-designer)
11. [Migration: the converter and its proof](#11-migration-the-converter-and-its-proof)
12. [Build phases for 2.7.0](#12-build-phases-for-270)
13. [Later releases](#13-later-releases)
14. [Open questions for the product owner](#14-open-questions-for-the-product-owner)

## 1. Definitions

The vocabulary is the product owner's; every other Drishti document uses it from 2.7.0 on.

| Term | Meaning |
|---|---|
| **UI Board** (board) | One screen, built like a circuit board: chips placed on it, wired by traces, powered by suppliers. A board is a document of a few kilobytes of metadata. It holds no data and no code. |
| **Chip** | A reusable, consistent, well-defined **Sutra in the Rachana grammar** with typed pins. A chip is a **pure function that paints UI**: the same typed input always gives the same screen and the same behaviour. A chip does not know or care where its input comes from ("a chip doesn't care which battery cell sends current"). |
| **Pin** | A typed connection point of a chip. **data-in** pins take rows or a document (from a supplier or another chip's out pin); **control-in** pins take filters, ids, dates and parameters; **out** pins give what the person did or what the chip holds: the selection, a clicked date, the current filter, the result rows. |
| **Pin type** | A type in Rachana terms: `id`, `date`, `measure`, `dimension`, `rowset` of a shape, and so on (section 4). |
| **Pin mapping** | Per chip on a board: which attribute of the supplier feeds which pin or column of the chip (a connector's `trade_id` onto the chip's `id`). The mapping is what lets one chip serve boards whose suppliers name things differently. |
| **Trace** | A wire from an out pin of one chip to an in pin of another: parent and child, master and detail, follow the selection. |
| **Supplier** (power supply) | Where data current comes from: a **connector** (an entity kind of a pack, a search, a live topic). **BI models and datasets** are **power regulators** between the connectors and the chips: they shape, aggregate, secure and cache the current. |
| **Technical manual** (datasheet) | The `manual:` section **inside every chip's script**: purpose, pins, defaults, limits, examples. It is read by three audiences: the Drishti renderer and the board check, people (the Chip Store, the Board Designer), and AI agents (2.9.0). |
| **Chip Store** (also the Widget Store) | The governed library of chips, in the content store: versioned; draft, review, publish, certify; "where used" for every chip. |
| **Module** (sub-board) | Wired chips grouped and published as a new chip; its unwired pins chosen as the module's pins. |
| **Board check** | The static check of a board document: every required pin connected and type-compatible, mappings valid, no broken trace, chip versions valid (section 5). |
| **Board Designer** | The visual editor of a board. YAML or JSON are the other way to write the same one board document. |
| **Render** | Painting a board with real data, bound as the person asking (masks, row rules, roles): the layout first, then each chip's data, streamed. |
| **Board-local chip** | A chip that lives inside one board document and is not in the Chip Store; what the converter makes of a one-off panel. |

What is new and what is reused:

* **Reused:** the Rachana grammar and its panel kinds, `SutraBlocks` (versioned blocks), the typed slots of
  [GENUI.md](GENUI.md#52-slots-new) section 5.2 (a pin is a slot that is also wired), `ViewPipeline.preview` (rendering
  an unregistered Sutra), `Entitlements`, `QueryService.Caller`, access-profile shared answers, BI runs, the live channel,
  `AuditLog`, the content store with compare-and-set.
* **New:** the chip header and manual, pins and pin types, the board document, the board check, the render API and its
  stream, the Chip Store lifecycle, the Board Designer, the converter.

## 2. The chip document

One YAML document per chip version. Four parts: the **header**, the **manual**, the **pins**, the **body** (Rachana
panels, written exactly as in a Sutra, reading pins by `%{pin}` and `@pin.column`).

| Key | Required | Meaning |
|---|---|---|
| `rachana: 1` | yes | the grammar version |
| `chip` | yes | the name: lower-case letters, digits, hyphen (up to 64); unique in the Chip Store, or in the board for a board-local chip |
| `version` | yes | a positive integer, immutable once published |
| `title`, `category`, `tags` | yes, yes, no | for the Chip Store palette |
| `manual` | yes | the technical manual (below); a chip without one is refused at publish |
| `pins` | yes | `in:` (data and control) and `out:` |
| `body` | yes | `panels:` in the Rachana grammar; may only read pins, never a source by name |
| `limits` | no | the chip's own limits (rows painted, refresh), never above the server's |
| `compatible-with` | no | earlier versions whose boards may move to this one without change (section 9.3) |

The **manual** has fixed keys so all three audiences can read it without guessing:

| Manual key | For |
|---|---|
| `purpose` | one paragraph: what the chip shows and when to use it |
| `pins` | per pin: what it means, what happens when it is empty |
| `behaviour` | what the person can do (sort, filter, select) and which out pin each action drives |
| `defaults` | what the chip does with no control input |
| `limits` | rows, columns, refresh, sizes; what happens past them |
| `examples` | at least one wiring example (a board fragment), checked by the board check at publish |
| `not-for` | what to use instead in common wrong cases (read by people and agents) |
| `accessibility` | keyboard and screen-reader behaviour |

### 2.1 A full example: the filterable table

```yaml
rachana: 1
chip: filterable-table
version: 2
title: Filterable table
category: tables
tags: [table, filter, selection, master]
compatible-with: [1]                 # version 1 boards may move here unchanged (it added the 'date' column pin)

manual:
  purpose: >
    A table of rows of one shape with an id, an optional date, a label and up to six measure columns.
    The person filters it by typing, by a date range or by the control pins; selecting a row sends its id
    out, so a detail chip can follow the selection.
  pins:
    rows:      "The rows to show. Required. Each row must give an id; the other columns are optional."
    filter:    "A filter from another chip (a slicer, a search box). Empty means no filter."
    date-from: "Show rows on or after this date. Empty means no lower bound."
    date-to:   "Show rows on or before this date. Empty means no upper bound."
    page-size: "Rows per page, 10 to 200. Default 50."
    selected:  "Out. The id of the row the person selected; empty until they select one."
    visible:   "Out. The rows after every filter, for a chart or a total chip to follow."
    filter-out: "Out. The filter the person typed, for other chips to share."
  behaviour: >
    Clicking a row selects it (one at a time) and sends 'selected'. Typing in the search box filters on the
    label and id columns and sends 'filter-out'. Column headers sort. Measure columns are totalled.
  defaults: "No filter, no date bounds, 50 rows a page, sorted by the date column descending, else by id."
  limits: >
    Paints at most 10,000 rows (the server pages the rest; the footer says 'first 10,000 of N').
    Measures are summed in the supplier's currency; rows of mixed currencies show no total.
  examples:
    - title: Trades of a book, detail follows the selection
      board: |
        chips:
          trades: { use: filterable-table@2, supply: { connector: trading/trade-search },
                    map: { rows.id: TRADE_ID, rows.date: TRADE_DATE, rows.label: COUNTERPARTY,
                           rows.measures: [NOTIONAL, MTM] } }
          ticket: { use: entity-card@1, supply: { connector: trading/trade } }
        traces:
          - { from: trades.selected, to: ticket.id }
  not-for: "One record (use entity-card); a pivot (use matrix); more than six measures (use grid)."
  accessibility: "Arrow keys move the row cursor, Enter selects; the selection is announced."

pins:
  in:
    rows:
      role: data
      type: rowset
      required: true
      shape:                          # the columns the chip reads, in its own names
        id:       { type: id, required: true }
        date:     { type: date }
        label:    { type: dimension }
        measures: { type: measure, many: true, max: 6 }
    filter:    { role: control, type: filter }
    date-from: { role: control, type: date }
    date-to:   { role: control, type: date }
    page-size: { role: control, type: number, min: 10, max: 200, default: 50 }
  out:
    selected:   { type: id, of: rows.id }        # carries the id's kind, if the supplier gives one
    visible:    { type: rowset, shape-of: rows }
    filter-out: { type: filter }

limits: { rows: 10000 }

body:
  panels:
    - id: table
      kind: table
      title: "%{title|Rows}"
      rows: "@rows"
      where:
        - "@rows.date >= %{date-from|-inf}"
        - "@rows.date <= %{date-to|+inf}"
        - "%{filter|true}"
      page-size: "%{page-size}"
      sort: [ { by: date, desc: true }, { by: id } ]
      columns:
        - { label: Id,   bind: "@.id",    link: "%{rows.id.kind}" }
        - { label: Date, bind: "@.date",  fmt: date, optional: true }
        - { label: Name, bind: "@.label", optional: true }
        - { each: measures, label: "@name", bind: "@.value", fmt: amount0, total: true }
      search: { on: [id, label], emits: filter-out }
      on-select: { emits: selected, value: "@.id" }
      exposes: { visible: "@filtered" }
```

Rules for a chip body (enforced by the check at publish, section 9):

* **Pure.** The body reads only its pins. A `bind:` to a document path, a source, a model or a URL by name is refused;
  the supplier is the board's business.
* **Typed substitution only.** `%{pin}` is replaced by a typed value, as a YAML scalar, as GenUI slots are
  ([GENUI.md](GENUI.md#52-slots-new) 5.2). A pin value is never text spliced into an expression.
* **Every out pin is driven** by an `emits:` or `exposes:` in the body, and every `emits:` names a declared out pin.
* **Deterministic.** No clock, no random, no viewer name in the body; the viewer's identity reaches a chip only through
  masked data and through a control pin the board wires on purpose.

## 3. The board document

One YAML (or the same structure in JSON) document. Its keys:

| Key | Meaning |
|---|---|
| `rachana: 1`, `board`, `version`, `title`, `description` | as a chip; `version` is the board's own revision, set by the store |
| `params` | the board's own control inputs (a date, a book), shown in its header; wired like out pins (`board.params.<name>`) |
| `chips` | the chip instances, by instance name: `use` (`name@version`), `supply`, `map`, `controls`, `title` |
| `local-chips` | board-local chips (section 11), full chip documents |
| `traces` | wires: `from: <instance>.<out pin>` (or `board.params.<p>`), `to: <instance>.<in pin>`; optional `when`, `transform` |
| `layout` | named grid areas per device (`desktop`, `phone`; `tablet` falls back to desktop), and the theme |
| `owner`, `workspace` | set by the store, never trusted from the body |

A **supply** names one supplier: `connector: <pack>/<kind or search>` (an entity or rows from a connector),
`model: <bi model>` with `query:` (measures, dimensions, filters as in a report visual), `dataset: <name>`, or
`live: <pack>/<topic>`. A data-in pin fed by a trace has no `supply`.

A **map** puts the supplier's attribute names onto the chip's pins and shape columns: `rows.id: TRADE_ID`. Unmapped
optional columns stay empty; a missing required one fails the check. `controls` gives constant values to control pins.

### 3.1 A full example: book, trades and the trade ticket

```yaml
rachana: 1
board: book-trades
version: 7
title: Book trades
description: A book's trades over a date range; the selected trade's ticket and its cash flows beside it.

params:
  as-of:  { type: date, default: today }
  book:   { type: id, of: trading/book, default: "FX-LDN-01" }

chips:
  trades:
    use: filterable-table@2
    title: Trades
    supply:
      connector: trading/trade-search          # the pack's search over trades, as the viewer
      query: { book: "%{board.params.book}" }
    map:
      rows.id:       TRADE_ID
      rows.date:     TRADE_DATE
      rows.label:    COUNTERPARTY_NAME
      rows.measures: [NOTIONAL, MTM_USD]
    controls:
      page-size: 25

  ticket:
    use: entity-card@1
    title: Trade ticket
    supply:
      connector: trading/trade                 # the trade entity, opened by id
    map:
      doc.id:      TRADE_ID
      doc.title:   DEAL_NUMBER
      doc.fields:  [INSTRUMENT_TYPE, BUY_SELL, CCY, NOTIONAL, START_DATE, MATURITY_DATE, STATUS]

  flows:
    use: cashflow-ladder@1
    title: Cash flows
    supply:
      model: trading-risk                      # a BI model as the regulator
      query:
        measures: [FLOW_AMOUNT]
        dimensions: [cashflows.PAY_DATE, cashflows.CCY]
    map:
      rows.date:    cashflows.PAY_DATE
      rows.group:   cashflows.CCY
      rows.amount:  FLOW_AMOUNT
      rows.trade:   cashflows.TRADE_ID          # the column the trace filters on

traces:
  - from: trades.selected                      # master to detail: the ticket follows the selection
    to:   ticket.id
  - from: trades.selected                      # and the ladder shows only that trade's flows
    to:   flows.filter
    transform: { filter-on: rows.trade }        # an id becomes the filter rows.trade = <id>
  - from: board.params.as-of
    to:   trades.date-to

layout:
  theme: terminal-dark
  desktop:
    columns: 12
    areas:
      - { chip: trades, at: [1, 1], span: [7, 8] }      # [column, row], [columns wide, rows high]
      - { chip: ticket, at: [8, 1], span: [5, 4] }
      - { chip: flows,  at: [8, 5], span: [5, 4] }
  phone:
    order: [trades, ticket, flows]                      # one column, in this order
    collapsed: [flows]                                  # shown as a heading the person opens
```

Three chips, the pin mappings, two traces between chips (plus one from a board parameter), and the layout for desktop
and phone. Note `trades.selected` feeds two traces: an out pin may drive any number of in pins; an in pin takes one.

## 4. Pin types and compatibility

### 4.1 The types

| Type | Value | Notes |
|---|---|---|
| `id` | a key; optional `of: <pack>/<kind>` | an id of a kind links to that kind's board |
| `text` | plain text | length-limited; never an expression |
| `number` | a number; `min`, `max`, `integer` | |
| `bool` | true or false | |
| `enum` | one of `values:` | |
| `date` | a business date | resolved by `AsOf` rules (`today`, `T-1`, `EOM`) |
| `datetime` | an instant | |
| `period` | a date range (`from`, `to`) | |
| `measure` | a number with meaning: optional `unit`, `ccy`, `additive` | a BI measure or a numeric attribute |
| `dimension` | a value to group or label by | a BI dimension column or any scalar attribute |
| `filter` | a structured filter (`column op value`, combined by `and`/`or`) | never free text; built by the server |
| `selection` | a set of ids of one kind | the many-select form of `id` |
| `document` | one entity document of a kind (`of:`) | for record chips (kv, entity card) |
| `rowset` | rows of a declared `shape` (column name: type, `required`, `many`) | `many: true` takes several columns as a list |

### 4.2 Compatibility: may this source feed this pin?

The check decides from the declared types only, never from data. A source is a supplier attribute (after the map), an
out pin (through a trace) or a constant.

| Source | Pin | Compatible when |
|---|---|---|
| T | T | always, if the refinements agree (below) |
| `id` of kind K | `id` (no `of`) | yes |
| `id` (no kind) | `id` of kind K | warning (BC-W1): the link cannot be checked; allowed |
| `id` of kind K | `id` of kind J | no, unless J is K or the pack declares K a subtype of J |
| `id` | `selection` | yes (a set of one) |
| `selection` | `id` | no (choose one: add a `transform: first`) |
| `date` | `datetime` | yes (start of the day, the board's zone) |
| `datetime` | `date` | no, unless `transform: date` |
| `date` | `period` | yes (one day) |
| `measure` | `number` | yes (unit dropped) |
| `number` | `measure` | yes; unit unknown (warning if the pin wants a `ccy`) |
| `measure` ccy A | `measure` ccy B | no, unless the pin says `ccy: any`; never summed across |
| `dimension` | `text` | yes |
| any scalar | `dimension` | yes |
| `id`, `selection`, `period` | `filter` | only with `transform: { filter-on: <column> }` |
| `rowset` S | `rowset` P | every **required** column of P is mapped from a column of S whose type is compatible; optional columns may be absent; extra columns of S are ignored |
| `document` of K | `rowset` | only through `rows: <path>` in the map (an array of the document) |

Refinements: `of` (kind), `unit`, `ccy`, `min`/`max`, `values`. A constant in `controls` is checked against the pin type
and its refinements. A required pin with a `default` counts as connected.

## 5. The board check

The board check runs on every save (Board Designer, YAML or JSON upload, the API) and before every render of an unsaved
board. It is static: metadata only, no data read, typically under 50 ms. It answers a list of problems, each with a rule,
the place (instance, pin, trace or layout area), a message a person can act on, and a fix where one is obvious. Errors
block save and render; warnings do not.

| Rule | Level | Checks |
|---|---|---|
| BC-01 | error | the document parses and has the required keys; names are well formed |
| BC-02 | error | every `use: name@version` exists in the Chip Store (or `local-chips`) and is not withdrawn; an unversioned `use` is refused |
| BC-03 | error | every required in pin of every chip is connected: by `supply` and `map`, by a trace, by a `controls` value, or by a default |
| BC-04 | error | every connection is type-compatible (section 4.2) |
| BC-05 | error | every `map` key names a pin or shape column of the chip, and every mapped attribute exists in the supplier's fields as the author sees them (the pack's form fields, glossary and shape; a model's columns and measures) |
| BC-06 | error | every trace names an existing instance and an existing out pin (`from`) and in pin (`to`); no in pin has two sources |
| BC-07 | error | no cycle of traces among data pins (a control cycle, A filters B filters A, is allowed only through `selection` pins, which settle in one round) |
| BC-08 | error | every supply names a supplier the author may read (a kind they may open, a model they may query) |
| BC-09 | error | the board's sizes are within limits (section 7.3) |
| BC-10 | error | every layout area names an instance; every instance is placed on desktop; areas do not overlap |
| BC-11 | error | the chip version is published (or certified, where the workspace demands certified chips) for a shared board; drafts only on the author's personal boards |
| BC-W1 | warning | an `id` without a kind feeds an `id` of a kind |
| BC-W2 | warning | a chip version is deprecated, or a newer compatible version exists |
| BC-W3 | warning | an out pin drives nothing and an optional in pin is empty with no default (perhaps unfinished) |
| BC-W4 | warning | the phone layout leaves out an instance (it is appended at the end) |
| BC-W5 | warning | a mapped attribute is masked for the author (the viewer will see `•••`) |

The same check runs at chip publish over the chip's own `examples`, and over every board that uses a chip when a new
version claims `compatible-with` (section 9.3).

## 6. The render API

All under `/api/v1/ui` (new), in `drishti-server`, JSON. The console proxies them as it does every other API.

### 6.1 Endpoints

| Method and path | Does | Power (section 7.1) |
|---|---|---|
| `POST /ui/boards/check` | checks a board document (body); answers the problems | `ui.board.view` |
| `POST /ui/render` | renders an **unsaved** (dynamic) board document; streams | `ui.board.render-dynamic` |
| `GET /ui/boards/{id}/render?params.<p>=…` | renders a **saved** board; streams | `ui.board.view` and read access to the board |
| `POST /ui/renders/{renderId}/pins` | a pin changed in the browser (a selection, a typed filter): the server repaints the chips downstream of it; streams their events | the render's owner only |
| `DELETE /ui/renders/{renderId}` | ends a render session (also ends on idle, 10 min) | owner |
| `GET /ui/boards?workspace=…` | lists the boards the caller may open | `ui.board.view` |
| `GET /ui/boards/{id}` | a board document and its version (`ETag`) | read access |
| `PUT /ui/boards/{id}` | saves (create with `If-None-Match: *`, update with `If-Match`); runs the check first | `ui.board.save` |
| `DELETE /ui/boards/{id}` | deletes (with `If-Match`) | owner or workspace admin |
| `GET /ui/chips?category=&tag=&q=&certified=` | the Chip Store listing | `ui.board.view` |
| `GET /ui/chips/{name}` / `{name}/{version}` | a chip's versions / one chip document | `ui.board.view` |
| `GET /ui/chips/{name}/{version}/manual` | the manual alone (JSON), for people and agents | `ui.board.view` |
| `GET /ui/chips/{name}/where-used` | boards and modules using each version (only those the caller may see are named; the rest counted) | `ui.board.view` |
| `PUT /ui/chips/{name}/draft` | saves a draft | `ui.chip.author` |
| `POST /ui/chips/{name}/{version}/{action}` | `submit`, `approve`, `reject`, `publish`, `certify`, `deprecate`, `withdraw` (section 9) | per action |
| `POST /ui/migrate/dry-run` / `POST /ui/migrate/proof` | the converter (section 11) | `ui.migrate` (admin) |

### 6.2 The render request and the stream

```json
POST /api/v1/ui/render
{ "board": { "rachana": 1, "board": "scratch", "chips": { … }, "traces": [ … ], "layout": { … } },
  "params": { "as-of": "2026-10-20", "book": "FX-LDN-01" },
  "device": "desktop",
  "format": "html" }
```

`format` is `html` (each chip painted by the console's renderer, as panels are today) or `viewmodel` (the JSON ViewModel,
for hosts with their own renderer, as [GENUI.md](GENUI.md#95-a-json-stream-for-hosts-with-their-own-renderer-opt-in)
9.5). The answer is `text/event-stream` (SSE, as `StreamController` uses), events in this order:

| Event | Data | When |
|---|---|---|
| `render` | `{ renderId, board, boardHash, chips: {instance: "name@version"} }` | first; after the check passed |
| `layout` | the resolved grid for the device, the theme, each chip's frame (title, size, a skeleton) | at once, before any data is read: the person sees the screen's shape within one round trip |
| `chip` | `{ instance, state: "data", viewmodel or html, meta: { rows, truncated, runId?, reused? } }` | per chip, as each supplier answers, in any order |
| `chip` | `{ instance, state: "running", runId }` | when a supplier's BI question became a run (BI_RUNS); the run card shows, the chip follows on completion |
| `chip` | `{ instance, state: "error", code, message }` | one chip failed; the others go on |
| `frame` | `{ instance, patch }` | a live supplier ticked (the live channel) |
| `done` | `{ ms, chips: n, errors: n }` | every chip painted once |
| `: hb` | | every 15 s while the session stays open for live frames |

A chip downstream of a trace is painted after its upstream chip's out pin has a value (its default first, if any), so a
master and its detail arrive in order. `POST /ui/renders/{renderId}/pins` with `{ "instance": "trades", "pin": "selected",
"value": "1000015" }` repaints `ticket` and `flows` only, on the same stream.

### 6.3 Errors (in `ErrorCode` since P1)

The range **DRS-2070 to DRS-2079** is free: checked against `ErrorCode.java` (2.6.7: 20xx holds 2001 to 2007, 2060, 2061)
and against every code the architecture documents propose (GenUI reserves 2048 to 2054). The numbers are confirmed again
when built.

| Code | HTTP | Meaning |
|---|---|---|
| `DRS-2070` | 400 | the board or chip document cannot be read (YAML or JSON, or a missing required key) |
| `DRS-2071` | 422 | the board check failed; the body lists the problems (section 5) |
| `DRS-2072` | 404 | no such chip, or no such version of it |
| `DRS-2073` | 404 | no such board, or not one the caller may open |
| `DRS-2074` | 403 | the caller lacks the power for this (render a dynamic board, save, author, certify) |
| `DRS-2075` | 413 | the board is over a size limit (bytes, chips, traces, module depth) |
| `DRS-2076` | 429 | over the render rate limit; `Retry-After` set |
| `DRS-2077` | 504 | a chip's supplier did not answer in the render budget (as a `chip` error event; the board goes on) |
| `DRS-2078` | 409 | a lifecycle step out of order (publish without approval, certify a draft, change a published version) |
| `DRS-2079` | 410 | the render session ended (idle, or the board changed); render again |

Existing codes reused: `DRS-5057` (409, content conflict on a stale `If-Match`), and the supplier's own codes inside a
chip error event (`DRS-1003`, `DRS-1004`, the BI codes), so a person sees the same message as on today's pages.

## 7. Security, masks and limits

### 7.1 Powers

New powers, granted to roles in the usual way (Admin, roles and powers):

| Power | Allows | Default roles |
|---|---|---|
| `ui.board.view` | open boards shared with you; list chips; read manuals | every signed-in role |
| `ui.board.save` | save personal boards, and boards in a workspace you edit | analyst and above |
| `ui.board.render-dynamic` | render an unsaved board document (the Designer's live preview, the API, later AI agents) | analyst and above; **off** for embed tokens unless the scope names it |
| `ui.chip.author` | write chip drafts, publish board-local chips into review | designer |
| `ui.chip.review` | approve or reject a submitted chip version | reviewer (not the same person as the author) |
| `ui.chip.certify` | certify a published version | chip-certifier (admin by default) |
| `ui.migrate` | run the converter and its proof | admin |

### 7.2 Real data as the person asking

Nothing on a board is computed "as the system". Every supplier is read with the asking person's rights, through the
paths that already enforce them:

* **Connectors (entities and searches):** `ViewPipeline` with `Entitlements` (`redactor`, `mayOpen`, `masked`,
  `restrict`). A kind the person may not open makes that chip an error card (`DRS-2074`), never an empty table that looks
  like "no data".
* **BI models and datasets:** `QueryService.Caller` with their roles, attributes, row rules, masks and denied tables. A
  board's model question is answered by access profile ([BI_RUNS.md](BI_RUNS.md#93a-a-report-visuals-answer-is-shared-by-access-profile)
  9.3a): two viewers with the same profile share one computation, a different mask never gets it.
* **Masks keep the shape.** A masked column is painted as `•••`, not dropped, so a board looks the same to everyone and
  only values differ. A column hidden by a rule (not masked) is absent; the chip paints an optional column as empty and
  a required one as a chip error.
* **Out pins carry only what the person sees.** A selection of a masked id carries the id (ids are not masked by rule;
  where a pack masks an id, the out pin carries an opaque token the server resolves for the same person only).
* **Traces never widen.** A detail chip is read as the same person; following a selection cannot open a record the person
  could not open directly.

### 7.3 Limits (in `config/ui/ui.yaml`, new; the defaults)

| Limit | Default | Past it |
|---|---|---|
| board document size | 64 KB | `DRS-2075` |
| chips per board / traces per board | 40 / 120 | `DRS-2075` |
| module nesting depth | 3 | `DRS-2075` |
| rows painted per chip | 10,000 (server pages the rest) | truncated, said in the footer |
| render budget per chip, first paint | 10 s, then a BI run card | `DRS-2077` for non-BI suppliers |
| render budget per board | 30 s to `done` | the remaining chips report `DRS-2077` |
| dynamic renders per person | 30 a minute, 4 at once | `DRS-2076` |
| render sessions per person | 8 open (oldest closed) | `DRS-2079` on the closed one |
| saved boards per person | 500 | `DRS-2075` |

### 7.4 Audit

Every render writes one `AuditLog.record` row: who, `ui.render` (or `ui.render.dynamic`), the board id or the hash of the
dynamic document, the chips and versions, the suppliers read, duration, chips failed. **No values.** Saves, deletes and
every Chip Store lifecycle step are audited too. Dynamic documents are kept (by hash, 7 days) so an auditor can see what
was painted for whom.

## 8. Storage keys

Chips and boards live in the content store ([CONTENT_STORE.md](CONTENT_STORE.md)), so every node of a cluster sees them,
with compare-and-set on every write. Segments are escaped and numbers zero-padded as in CONTENT_STORE.md section 9.

| Key | What |
|---|---|
| `ui/chips/<chip>/meta.json` | catalogue fields: title, category, tags, owner, latest published and certified versions, deprecated |
| `ui/chips/<chip>/draft.yaml` | the author's current draft (one per chip) |
| `ui/chips/<chip>/versions/<n>.yaml` | a version, immutable once published |
| `ui/chips/<chip>/versions/<n>.state.json` | its lifecycle state, reviewer, certifier, dates, signature (sha-256 of the YAML) |
| `ui/chips/<chip>/events/<seq>.json` | a lifecycle event (submit, approve, publish, certify, deprecate, withdraw) |
| `ui/where-used/<chip>/<n>.json` | the boards and modules using that version (an index, rebuilt from the boards on demand) |
| `ui/boards/users/<user>/<board>.yaml` | a personal saved board |
| `ui/boards/workspaces/<ws>/<board>.yaml` | a board shared in a workspace (the BI workspace membership rules) |
| `ui/boards/.../<board>.revisions/<seq>.yaml` | earlier saves (last 50) |
| `ui/dynamic/<yyyy-mm-dd>/<hash>.yaml` | a rendered unsaved document, for audit, kept 7 days |
| `ui/usage/<yyyy-mm-dd>/<node>.json` | renders per board and per chip that day (observability, promotion suggestions) |
| `ui/migration/<pack>/<run>.json` | a converter dry run or proof report |

Seed chips (section 9.4) ship in `config/ui/chips/<chip>.v<n>.chip.yaml`, read-only and certified, and appear in the
listing beside store chips; a store chip of the same name and version is refused.

## 9. The Chip Store: lifecycle and versions

### 9.1 Lifecycle

```
 draft ──submit──▶ in review ──approve──▶ approved ──publish──▶ published ──certify──▶ certified
   ▲                  │ reject                                     │ deprecate          │ deprecate
   └──────────────────┘                                            ▼                    ▼
                                                               deprecated ──withdraw──▶ withdrawn
```

* **draft:** editable by its author; usable only on the author's personal boards.
* **in review / approved:** frozen; a reviewer other than the author reads the manual, the body and the board check over
  the manual's examples. Publish checks again (purity, every out pin driven, the manual complete).
* **published:** immutable, version number fixed; usable on any board.
* **certified:** published and vouched for: tested over the examples with real data, accessible, within performance
  limits. A workspace may require certified chips only (`ui.yaml`: `workspaces.<ws>.certified-only`).
* **deprecated:** still renders; not offered in the palette; boards show warning BC-W2.
* **withdrawn:** an emergency stop (a security or correctness fault). Boards that pin it render a placeholder card that
  names the fault and the replacement; their owners are told.

### 9.2 Versions are pinned

A board names `chip@N` always. A published version never changes, so a board renders the same tomorrow as today. The
Chip Store keeps every version that any saved board uses.

### 9.3 How a chip upgrade reaches boards

1. The author publishes version N+1 and may declare `compatible-with: [N]`.
2. The server **verifies** the claim: every in pin of N exists in N+1 with a type that accepts what N accepted; no
   required pin was added without a default; every out pin of N still exists with the same type. If not, the claim is
   refused (`DRS-2078`).
3. It runs the board check over every board in `where-used` of N with `@N` replaced by `@N+1`.
4. Each board owner sees "upgrade available" (BC-W2) in the Designer, with the result of step 3. Upgrading is one click
   (a new board revision). A board may opt in with `follow: compatible` on the instance: then the store upgrades it
   automatically when step 3 passes, as a new revision, and says so in its history.
5. A version that is not compatible is never applied automatically; the owner rewires in the Designer.

### 9.4 Seeding

2.7.0 ships the Chip Store seeded from what Drishti already paints:

* **from the panel kinds of the grammar** (the twenty-two kinds of `RachanaSchema`: `kv`, `table`, `ladder`, `hbar`,
  `metric`, `markdown`, `status`, `timeline`, `map`, `area`, `provenance`, the charts and the others): one chip each, its
  pins derived from the kind's options (`rows`, `where`, `on-select`), its manual written from PANELS.md;
* **from the BI visual types** of REPORTS.md section 3 (`bar`, `column`, `line`, `area`, `card`, `kpi`, `table`,
  `matrix`, `slicer`, the maps, Perspective and the others): one chip each, its wells as typed pins (an `axis` field well
  is a `dimension` pin, a `values` well a `measure` pin with `many`);
* **from the pack blocks** (`deal-ticket`, `legs`, `flow-schedule`, `events`, `payments`, `deal-footer`): as chips with
  the GenUI slots turned into pins;
* a few composite chips needed by the converter: `entity-card`, `filterable-table`, `cashflow-ladder`, `search-box`,
  `date-range`.

### 9.5 Modules (sub-boards)

In the Designer the person selects wired chips and chooses **Make a module**. The module is a chip whose body is a
board fragment (`body: { board: { chips, traces, layout } }`); the person chooses which unwired pins become the module's
pins (renamed if they wish), and writes its manual (prefilled from the inner chips' manuals). It goes through the same
lifecycle. Its inner chips stay pinned to their versions; a module's `where-used` and its inner chips' `where-used` both
list it. Depth is limited (7.3).

## 10. The Board Designer

As built (12.4): Build's designer in its **Board** mode (beside a **Chip** mode), not a new page; *Boards* is Build's one list of boards and designs. Maya UX tokens, the Terminal themes.

* **Palette (left):** the Chip Store by category, search, a *certified* filter; each chip shows its manual's purpose on
  hover and its full manual in a side sheet. Suppliers in a second tab: the packs' kinds and searches, BI models and
  datasets, live topics, as the person may read them.
* **Canvas (centre):** a grid; drag a chip on, resize, move. Pins are drawn as small labelled ports on the chip's edge,
  coloured by type. **Drawing a trace** is a drag from an out pin to an in pin; only compatible pins light up while
  dragging. Plugging a supplier: drag it onto a chip; the mapping sheet opens with the supplier's attributes on the left,
  the chip's pins and columns on the right, matched by name and type where it can (`TRADE_ID` to `id`), the person
  fixing the rest.
* **Live data, as the person:** every chip paints real data through the render API as the designer works (debounced;
  the dynamic render power), so the designer sees what a viewer with their rights will see.
* **Red for bad wiring:** the board check runs on each change; a problem paints its pin, trace or chip red with the
  message; the *Problems* list at the bottom jumps to each. Save is disabled while errors remain.
* **Phone and desktop:** a toggle shows the phone layout (one column; order and collapsed set by drag); themes switch
  live.
* **YAML / JSON:** a tab shows the same board document; edits in either view are the same document (one model, two
  editors; the text view checks on pause and keeps the canvas in step).
* **Save, share, history:** save to *My boards* or a workspace; revisions listed; restore. *Make a module* (9.5).
* **Chip authoring** (for `ui.chip.author`): a chip editor with the manual form, the pin table and the body in YAML, a
  test bench that wires the chip to a sample supplier, and *Submit for review*.

The command line gains `drishti.py ui check <board.yaml>`, `ui render <board.yaml> --as <user>` (for tests, prints the
event stream), `ui chips`, `ui migrate --dry-run --pack <p>`.

## 11. Migration: the converter and its proof

Goal: every existing screen becomes a board with nothing broken, pack by pack.

### 11.1 What is converted

| Today | Becomes |
|---|---|
| An entity page (the Sutra of a kind) | a board `<pack>-<kind>` with a board param `id` (of the kind); each panel becomes a chip instance of the matching seed chip, its options moved into the map and controls; tabs become a `tabs` chip holding modules |
| A pack block used by Sutras | a seed chip (9.4) |
| A BI report page | a board per page; each visual a chip of its visual type; the report's slicers become control chips; cross-filtering becomes traces (`selected` to `filter`); the model is the supplier |
| A BI board page (tiles) | a board; each tile a chip supplied by its report's model and query |
| A Terminal page (`TRD <id> <GO>` and kin) | a board in the Terminal theme, the command's parameters as board params |
| A one-off panel that matches no seed chip (an unusual expression, a custom kind) | a **board-local chip** inside that board's document: the panel's Sutra wrapped with pins derived from what it reads |

### 11.2 The dry run

`POST /ui/migrate/dry-run { pack }` (and `drishti.py ui convert --pack trading --dry-run`) converts every screen of the
pack in memory and writes a report (`ui/migration/<pack>/<run>.json`, shown in Admin): per screen, the board it would
make, the chips used (store or board-local), the board check result, and anything it could not convert with the reason.
Nothing is saved and nothing changes for anyone.

### 11.3 The proof: same screen, same data

`POST /ui/migrate/proof { pack, users: [...], samples: 20 }` renders, for each screen, **both** the old page and the new
board, for each test user (at least one per access profile in the pack: full, masked, row-restricted) and for a sample
of entities per kind (20 by default, chosen across the data, plus the ones named in the pack's tests):

* **same data:** the ViewModels are normalised (order of keys, layout positions dropped) and compared value by value:
  every label, value, mask, total, link and row; a difference fails the screen with the path of the first difference;
* **same screen:** the panels' kinds, titles and order, and a structural snapshot of the HTML (classes and nesting), so
  a chip that paints differently is caught; an optional pixel diff in the Playwright suite;
* **same behaviour:** the old cross-filters and drill links are replayed as trace events and compared.

A screen that passes is marked proven. When every screen of a pack is proven, the admin switches the pack
(`ui.yaml: packs.<pack>.screens: boards`): the old routes render the converted boards. The old Sutras and reports stay in
place and are not deleted until a later release; switching back is one setting. A pack with an unproven screen keeps
that screen on the old path; the rest of the pack may switch.

## 12. Build phases for 2.7.0

Two builders at most (A on the server and grammar, B on the store and console), one heavy test process each, 10K trades
at most in local tests. Each phase ends with its tests green, its docs updated and a CHANGELOG line.

| Phase | Builder | Deliverables | Tests |
|---|---|---|---|
| **P1. Chips, pins, boards: the model and the check** (built, [12.1](#121-as-built-p1)) | A | `drishti-rachana`: the chip document parser (header, manual, pins, body), pin types and the compatibility table (4.2), the board document parser (YAML and JSON to one model), the board check BC-01 to BC-11 and BC-W1 to BC-W5, chip publish checks (purity, out pins driven, manual complete); `drishti.py ui check` | unit tests per rule, good and bad (each rule at least one failing board); the compatibility table as a parameterised test; YAML and JSON of the same board give the same model; the section 2.1 and 3.1 examples pass |
| **P2. The Chip Store and saved boards** (built, [12.2](#122-as-built-p2)) | B | content-store keys (section 8), the lifecycle (9.1) with powers, versions and `compatible-with` verification (9.3), where-used index, board save, revisions, delete with compare-and-set; the REST API of 6.1 except render; seed chips from panel kinds, BI visual types and pack blocks (9.4) with manuals | store contract tests on the filesystem and SQLite backends; lifecycle order (`DRS-2078`); a reviewer may not approve their own chip; where-used after save and delete; two nodes saving the same board (`DRS-5057`); every seed chip passes its own manual examples |
| **P3. The render API** (built, [12.3](#123-as-built-p3)) | A | `POST /ui/render`, `GET /ui/boards/{id}/render`, pin changes, sessions; layout-first SSE; suppliers through `ViewPipeline.preview` and `QueryService` as the caller; traces at runtime; BI run cards; live frames on the channel; limits and audit (7.3, 7.4) | render as three users (full, masked, row-restricted) and compare masks; trace ordering (detail after master); one failing supplier leaves the others painted; every limit hit gives its code; audit row has no values; access-profile reuse (second viewer `reused`) |
| **P4. The board viewer and the Board Designer** | B | console: the board viewer (any saved board, phone and desktop, themes), the Designer (palette, canvas, pins, traces, mapping sheet, live data, red problems, YAML/JSON tab, save, history, modules), the chip editor and test bench | Playwright: build the 3.1 board by drag and wire, see red on a bad wire, fix, save, reopen, YAML edit reflected on canvas; phone layout; keyboard-only path; console unit tests for the mapping matcher |
| **P5. The converter and its proof** (built, [12.4](#124-as-built-p5)) | A | dry run, board-local chips, the proof (data, screen, behaviour), Admin pages for reports, per-pack switch; trading pack first, then the others pack by pack | trading: every entity page, report page and board page proven for the three users on the 10K-trade sample; a deliberately broken chip makes the proof fail with the right path; switching back restores the old pages |
| **P6. Modules, observability, docs and QA** | A and B | modules (9.5), `ui/usage` counts and the Admin view of chip and board use, the Boards guide, the Board Designer tutorial (UI, CLI and YAML at every step), help pages, the QA pass of the release | module round trip (make, publish, use, where-used); usage counts across two nodes; docs reviewed against the built behaviour; the release QA checklist |

P1 and P2 run in parallel (P2 uses P1's model through an interface agreed on day one); P3 needs P1; P4 needs P2 and P3's
check and render endpoints (it may start on the check alone); P5 needs P3; P6 last. One drill at the end of 2.7.0.

### 12.1 As built: P1

**Where.** Package `com.ash.drishti.rachana.ui` in `drishti-rachana`, no Spring: `UiDocuments` (the reader: YAML or JSON to
one model), `ChipDoc`, `ChipManual`, `PinSpec`, `TypeRef`, `PinType`, `ChipRef`, `BoardDoc` (with its `Instance`, `Supply`,
`Mapping`, `Trace`, `Transform`, `Layout`), `PinCompatibility` (4.2), `BoardCheck` (section 5), `ChipPublishCheck`,
`UiProblem` (rule, level, path, message, fix), `ChipState` (9.1), and the two seams P2 and P3 fill: `ChipCatalog` (find
`name@version` with its state and any newer compatible version) and `SupplierCatalog` (a supplier's fields, types and masks
as the author sees them). The codes of 6.3 are in `ErrorCode` as `UI_DOCUMENT_INVALID` (2070), `UI_CHECK_FAILED`,
`UI_CHIP_NOT_FOUND`, `UI_BOARD_NOT_FOUND`, `UI_FORBIDDEN`, `UI_TOO_LARGE`, `UI_RATE_LIMITED`, `UI_SUPPLIER_TIMEOUT`,
`UI_LIFECYCLE_ORDER`, `UI_RENDER_ENDED` (2079). `drishti.py ui check` runs `UiCli` in the exec jar (`java -jar … ui check`),
without a server ([CLI_GUIDE.md](../guides/CLI_GUIDE.md#ui-check-the-ui-board-check-270)).

**Corrections and precisions the code forced:**

* **Publish rules got names:** `CP-01` manual complete (every key, a line per pin, at least one example), `CP-02` pure (no
  `source`, `connector`, `model`, `dataset`, `live`, `url`, `fetch`, `sql` key; a `bind:` starts with `@` or `%{`; every
  `%{x}` and `@x` names an in pin), `CP-03` every out pin driven and every `emits:` / `exposes:` declared, `CP-04`
  deterministic (no `now(`, `today(`, `random(`, `%{viewer}`, `@user`), `CP-05` every manual example passes the board check.
* **Built-in names in a body:** `%{title}` is the instance's frame title (2.1 uses `%{title|Rows}`, and `title` is no pin);
  `@name`, `@value`, `@filtered`, `@row`, `@index`, `@count`, `@total` and `@.column` (the current row) are not pins.
* **`of:` has two meanings, told apart by a slash:** `of: trading/trade` is a kind; on an out pin `of: rows.id` (no slash)
  names the column whose supplier kind the id carries (resolved through the instance's map and the supplier's field kind).
* **A `document` pin may carry a `shape:`** (3.1 maps `doc.id`, `doc.title`, `doc.fields` onto `entity-card`).
* **What a supply feeds:** every data in pin of the instance that no trace feeds. A map key is `<pin>.<column>`, or `<pin>`
  alone (a scalar pin, or `rows: <path>` reading a document's array). A required shape column left unmapped is `BC-03`.
* **Two sources:** a trace plus a map entry, or a trace plus a `controls` constant, on one pin is `BC-06`, as two traces are.
  A `%{board.params.x}` in a supply query must name a declared parameter (`BC-06`).
* **`BC-01` also refuses unknown keys** at every level (a typo is a problem, not silently ignored).
* **`BC-07`:** a cycle is an error when any of its traces ends at a data pin, or when none of them carries a `selection`.
* **`BC-W3` is per instance:** no out pin of it drives anything *and* an optional in pin is empty with no default.
* **A plain `number` feeding a `measure` with a `ccy`** (4.2: "warning if the pin wants a ccy") is reported as `BC-W1`, the
  warning for a refinement that cannot be checked.
* **Constants:** a `date` constant is `yyyy-mm-dd` or an AsOf word (`today`, `T-1`, `EOM`); a `rowset` or `document` is never a
  constant.
* **Suppliers need a catalogue:** the attribute half of `BC-05`, `BC-08`, `BC-W5` and an out pin's kind via `of:` run only when
  a `SupplierCatalog` is given (P3: the packs and models as the caller; the command line: `--suppliers FILE`). Without one they
  are skipped, never guessed.
* **Manual examples are fragments:** `rachana`, `board` and `layout` may be left out; `BC-11` and `BC-W3` do not apply.
* **The examples:** 2.1 passes unchanged. 3.1 passes unchanged; it needs `entity-card@1` and `cashflow-ladder@1`, written as test
  chips until P2's seed chips replace them. With the test supplier file its one finding is `BC-W5` (`MTM_USD` masked).

### 12.2 As built: P2

**Where.** Package `com.ash.drishti.server.ui` in `drishti-server`: `ChipStore` (drafts, versions, the lifecycle, the seed chips;
it implements P1's `ChipCatalog`), `BoardStore` (saved boards, revisions, sharing, copies, where-used, following compatible
upgrades), `UiController` (`/api/v1/ui`, every route of 6.1 except render), `UiAccess` (the powers), `UiSettings`
(`config/ui/ui.yaml`), `UiDocs` (documents as maps, the `compatible-with` verification), `UiConfiguration`. Seed chips:
`config/ui/chips/*.chip.yaml`, written by `tools/ui_seed_chips.py`. Settings: `drishti.ui.dir` ([CONFIGURATION.md](../admin/CONFIGURATION.md)).
Routes: [API_GUIDE.md](../guides/API_GUIDE.md#ui-boards-and-the-chip-store-apiv1ui-270). No database table: everything is in the
content store.

**Corrections and precisions the code forced:**

* **Keys.** As section 8, with every user and workspace segment escaped as BI's are (`BiDocs.seg`) and version, revision and event
  numbers padded to ten digits: `ui/chips/<chip>/versions/0000000002.yaml` and `.state.json`, `ui/chips/<chip>/events/<n>.json`
  (numbered by `ui/chips/<chip>/events.seq`), `ui/where-used/<chip>/<n>.json` (`{boards: [{scope, space, board}]}`), a board's
  revisions `<board>.revisions/<n>.yaml` keyed by the revision they were, and two new ones for sharing: `<board>.share.json` beside a
  personal board (who it is shared with) and `ui/shared/<person>/<owner>/<board>.json` (the shared-with-me index).
* **Powers on the existing roles (14.1 item 4):** `ui.board.view` is any signed-in caller; `ui.board.save` a role with `layout`;
  `ui.chip.author` a role with `author`; `ui.chip.review` a role with `approve` (the Sutra approver); `ui.chip.certify` the
  `bi-steward` power; an administrator holds all. Workspace boards follow the BI workspace membership: viewers open, contributors
  and above save, the board's owner or a workspace admin deletes.
* **Lifecycle steps.** `submit` takes the chip's current draft (one draft per chip, owned by whoever drafted it first) at its
  `version:`, which must be new; `reject` needs a comment and deletes the submitted version, so the draft can be submitted again
  with the same number; `publish` is the author's or a reviewer's, after approval, and refuses a version whose text changed since
  submission. Nobody approves or rejects their own chip: `403 DRS-2007` (the Sutra four-eyes code), not `DRS-2074`. `withdraw` is
  the emergency stop: allowed from published, certified or deprecated (not only deprecated as drawn in 9.1), by a reviewer or
  certifier, with a comment naming the fault. Every step is an event and an audit row.
* **The publish gate** is P1's `ChipPublishCheck` (CP-01 to CP-05, examples through `BoardCheck` against the store), at submit and
  again at publish; its errors are `422 DRS-2071`. Then `compatible-with` is verified against each named earlier version
  (`DRS-2078` with the reasons when it does not hold).
* **Boards follow (14.1 item 3):** after a verified publish, every board in where-used of an earlier compatible version moves to
  the new one as a new revision, saved as the system with compare-and-set, unless the instance says `follow: pinned` or the board is
  in a certified-only workspace (it waits for certification). `ChipCatalog.Entry.newerCompatible` reports the newest such version for
  BC-W2.
* **Board save** runs P1's `BoardCheck` against the Chip Store plus the caller's own current draft (as state `draft`), as a personal
  or a shared board (certified-only from `ui.yaml`), with the limits of `ui.yaml`; then the store's rule that a version before
  publish is usable only on its author's personal boards (BC-11). The bytes, chips, traces and boards-per-person limits answer
  `413 DRS-2075` before the check. The store sets `rachana`, `board`, `version` (the revision), `owner` and `workspace`, whatever
  the body says.
* **Addressing a board:** `{name}` plus `owner` (a personal board shared with you; your own by default) or `workspace`; the id in
  answers and where-used is `user:<owner>:<board>` or `workspace:<ws>:<board>`. Sharing a personal board (14.1 item 1) is read-only
  for the people named; copying into a workspace makes a new board owned by the copier.
* **Where-used** names only the boards the caller may open and counts the rest, per version.
* **Seed chips:** 68 versions of 67 chips, certified and read-only (a store chip of the same name and version is refused; a lifecycle
  step on one is `DRS-2078`): `panel-<kind>` for the 22 grammar panel kinds, `bi-<type>` for the 34 BI visual types of REPORTS.md
  section 3, the trading pack's six blocks under their own names, and `entity-card`, `filterable-table` (versions 1 and 2, the 2.1
  example, 2 compatible with 1), `cashflow-ladder`, `search-box`, `date-range`. A document pin carries the shape `{id, title,
  fields}`. Each passes `ChipPublishCheck` with its manual's example. A seed body is the chip's painting contract for P3; the panel
  options in it are P3's to honour.
* **Not in P2:** render (P3), `ui/dynamic` and `ui/usage` (P3, P6), modules (P6), restoring a revision from the API (the Designer,
  P4; a revision can be read and saved again).

### 12.3 As built: P3

**Where.** Package `com.ash.drishti.server.ui`: `UiRenderController` (the four render routes), `RenderService` (check, powers, limits,
sessions, audit), `BoardRender` (one session: layout, trace order, pin mapping, pin changes), `BoardSuppliers` and
`ServerBoardSuppliers` (every supplier as the caller), `IdTokens` (masked ids), `RenderSettings` (the `render:` section of
`config/ui/ui.yaml`, beside P2's `limits:`), `UiRenderConfiguration`. Saved boards come from P2's `BoardStore` and chips from its
`ChipStore` (plus the caller's own drafts on personal boards). Routes: [API_GUIDE.md](../guides/API_GUIDE.md#rendering-a-board-270-p3).
Settings: [CONFIGURATION.md](../admin/CONFIGURATION.md).

**Corrections and precisions the code forced:**

* **Routes.** The saved-board render is `GET /ui/boards/{name}/render` with `owner=` or `workspace=` as P2's routes address a board;
  the audit row names it `user:<owner>:<board>` or `workspace:<ws>:<board>`. `POST /ui/boards/check` is P2's. `stream: once` (or
  `?stream=once`) ends the stream at `done`, for hosts without pin changes and the command line; otherwise it stays open (`: hb` every
  15 s) for pin changes. `POST /ui/renders/{id}/pins` answers `{renderId, chips, errors, ms}` once the chips downstream are painted;
  their events go on the render's own stream. A session closed by the limit or by idle gets an `ended` event (`DRS-2079`).
* **Format.** Both formats send the chip's ViewModel: `{chip, title, body, pins, controls, keys}`, the pins holding the supplier's rows
  in the chip's own column names (a `many` column as `[{name, value}]`, a `document` pin as one row). Turning a body into HTML is
  the console's (P4); the server does not paint HTML.
* **Order.** Chips are painted on virtual threads, each after every chip upstream of it by a trace (a selection cycle's back edge is
  dropped). A detail whose required control pin has no value, or an entity supply (`connector: <pack>/<kind>`, not `-search`) with no
  id yet, is sent as `state: waiting` naming the pin: the ticket of 3.1 waits for a selection instead of listing every trade. A pin
  change repaints the transitive dependants of that out pin only, never the master. An out pin with `shape-of` (`visible`) is the
  rows as painted; a selection is whatever the browser sends; out pins reset to their defaults when their chip is painted again.
* **Suppliers.** `connector: <pack>/<kind>` with an id: `SourceRouter.fetch` and the caller's `Entitlements.redact`, a kind they may not
  open being `DRS-2074`; `<pack>/<kind>-search` (or a kind with no id): `StructuredSearch` with the query's keys as `field = 'value'`
  and the trace filters; `model:` a BI run through `RunSources` and `RunService` as the caller, asked as an interaction (an unknown
  size runs interactively, no confirmation), waiting `chip-budget` inline, then the run card; the board query's `dimensions` and
  `measures` (or a `select` as written), `where`, `time`, `sort`, `params`, with `filter-on` traces as `eq` filters; `live:
  <pack>/<kind>` the record by id, then the live hub's versions as `frame`s, redacted per frame. A `dataset:` supply is refused in
  2.7.0 (name its model). Board parameters are substituted into the supply query as text.
* **Masks.** A masked attribute is painted `•••` on the chip; the chip event's `meta.masked` names the chip columns masked. A required
  shape column the caller's rule hides (absent, not masked) is the chip's `DRS-2074` error.
* **Masked ids (14.1 #10).** Where the column an id out pin carries (`of: rows.id`) is masked, the row's `keys` entry is a random
  `tok_…` token held by `IdTokens` for the same person (six idle periods); a pin value or filter that is a token is resolved only for
  that person, anyone else (or an expired token) being `DRS-2074`. A model row has no entity reference, so a masked model id is not
  selectable (its key is null).
* **Access-profile reuse.** A model answer is kept for `render.reuse` (30 s) under the caller's access profile
  (`DatasetService.accessProfile`: roles, row rules and masks; never a personal one) and the model query; a second viewer of the same
  profile gets it with `meta.reused: true`. A `REUSED` BI run is reported the same way.
* **Limits.** Bytes, chips and traces are P2's `limits:` (a BC-09 problem is `413 DRS-2075`, not `422`); `render:` holds rows per chip,
  the chip and board budgets, the per-person dynamic rate (sliding minute) and concurrency, sessions per person, the idle time, the reuse
  time and `keep-dynamic`. A non-BI supplier past the chip budget is that chip's `DRS-2077`; the board budget turns every chip not yet
  painted into `DRS-2077` and sends `done`.
* **Powers.** `ui.board.render-dynamic` is `UiAccess.mayRenderDynamic`: a role that may save boards (`layout`), never an embed token
  (14.1 #8); a saved render needs `ui.board.view` and read access.
* **Audit.** One row per render at its first `done`: action `ui.render` or `ui.render.dynamic`, subject the board id or
  `dynamic:sha256:<hash>`, detail `chips=<instance>=<name@version>,…; suppliers=<kind:name>,…; ms=…; failed=n`. Dynamic documents are
  kept as `<data folder>/ui/dynamic/<hash>.board` for `keep-dynamic` (7 days; this node only, not the content store).
* **Not in P3:** traces' `when:`, the `first`/`date` transforms beyond passing the value, HTML painting (P4), the `ui/usage` counts (P6),
  a server-side supplier catalogue for the check's attribute rules (the check runs without one; a missing attribute is found at read).
* **Tests.** `RenderServiceTest` (seed chips, the 3.1 board, suppliers per person: order, a selection repainting only the details, masks
  and absent rows, the masked-id token, one failing chip, the chip budget, every limit's code, the audit row without values, embed tokens)
  and `UiRenderApiTest` (the whole stack with security on over a file-connector lake: the 3.1 board on a BI model with row rules and a
  mask and the ticket over the lake's trade entity, rendered as rita, ann and hana; a selection on the open stream; access-profile reuse;
  `DRS-2071` before any stream).

### 12.4 As built: P5

**Where.** The converter is in `drishti-rachana`, package `com.ash.drishti.rachana.ui.migrate`, no Spring: `PageConverter` (a Sutra to a
board), `ReportConverter` (a BI report to a board per page), `BoardSutra` (a converted board painted back into the grammar), `PackDryRun`
(a pack's screens and the report), `Conversion`, `ConvertCli` (`ui convert`). On the server, package `com.ash.drishti.server.ui`:
`PackScreens` (the per-pack switch, the converted boards, serving a page from its board), `BoardProof`, `UiMigrationController`
(routes: [API_GUIDE.md](../guides/API_GUIDE.md#migration-to-boards-270-p5)); `ViewController` asks `PackScreens` first.
CLI: [CLI_GUIDE.md](../guides/CLI_GUIDE.md#ui-convert-the-dry-run-of-a-packs-conversion-to-boards-270). The switch:
[OPERATIONS.md](../admin/OPERATIONS.md#switching-a-pack-to-boards-270).

**Corrections and precisions the code forced:**

* **An entity page's board.** `board: <pack>-<sutra>` with the parameter `id: { type: id, of: <pack>/<kind>, required: true }`; every
  chip reads the record through `connector: <pack>/<kind>` with `query: { id: "%{board.params.id}" }` and maps `doc.id: id`. A document
  pin of a converted board carries the **whole record** as the viewer may see it (the panels read it by path), not only the mapped shape.
* **The page header is a board-local chip** (`local-header`): the Sutra's `title`, `strip`, `controls` and `keys` in its body under
  `page:`; its out pins `ctl-<control>` carry the page controls. A panel that reads `ctl.x` gets the in pin `ctl-x` and a trace from the
  header: that is follow-the-selection. The header is not counted when a screen is classed: **clean** means every panel is a Chip Store
  chip. Drill-through links (`link(...)`) stay as navigation to other pages, counted, not drawn as traces (a trace joins chips on one board).
* **Which chip.** A block use (`use: deal-ticket@1, with: {...}`) is the seed chip of that block, its `with` as the instance's controls
  (an unset control stays the block's own default). Any other panel is the seed chip `panel-<kind>` only when that chip carries every option
  of the panel (each a pin or a constant); otherwise it is a board-local chip `local-<panel>` wrapping the panel, its record paths written
  over the document pin (`$.X` as `@doc.X`, so the chip is pure and can be submitted to the Chip Store as it is). A choice is accepted only
  when `BoardSutra` paints it back into exactly the panel it came from. In practice the generic `panel-*` seed chips carry only the
  simplest panels (the provenance panel); pages built from the trading blocks convert clean.
* **A report page's board.** `board: <report>-<page>`; each visual an instance of `bi-<type>@1` supplied by `model: <the report's model>`, its
  wells the query (a field with a dot is a dimension, else a measure) and the pin map (`rows.<well>`), its `at` the desktop area. A visual with
  options the seed chip has no pin for (`format`, `filters`, `hierarchy`, `alt`, ...) is a board-local copy of the seed chip with those
  options in its body; a visual type with no seed chip (`python`, `tile`, `text`, ...) or a board that fails the check fails the page.
  Cross-filtering is **not** drawn: the `bi-*` seed chips have no `filter` in pin (a P6 item). A report belongs to the pack whose kinds its
  model's first dataset reads; a report over no pack's kind is in no pack's dry run.
* **The proof** (`BoardProof`) compares, per page, sample record and person: the old page as `GET /views/{kind}/{id}` builds it, and the
  board painted by `BoardSutra` and bound over the record **as the board's supplier reads it for that person**
  (`ServerBoardSuppliers.document`, the caller's masks), with the same link rule. Both ViewModels are compared as JSON trees after dropping
  what names the builder (`provenance`, `timings`, the data of a `provenance` panel); the first difference is reported by path
  (`panels[1].title`) with both values. Samples: up to `samples` records per page found by the kind's search, plus `ids` per kind (a derived
  kind such as `counterparty-trades` has no search of its own). BI report pages are converted and their structure checked by the board
  check, but their data proof (both BI runs as the person) is not in P5.
* **The switch** is `packs.<pack>: boards | sutras` (or `{screens: ...}`) in `ui.yaml`, read at start, plus `PUT /api/v1/ui/packs/{pack}`
  for an administrator (this node, until the next start: instant). On `boards` the record is matched to its Sutra as before and the
  Sutra's converted board serves the page; a screen whose conversion fails stays on its Sutra. Converted boards are made in memory from
  the pack's files on first use and again after any Sutra changes; they are not saved in the Board store in P5.
* **Not in P5:** the Admin page for the reports (the reports are JSON from the routes; the console page is P6 with the usage view), the
  data proof of BI report pages, BI board (tile) files (none are shipped), Terminal pages, traces for report cross-filters, publishing the
  board-local candidates.

**Proof, trading** (`UiMigrationTest`, the pack's committed sample trades, 765, three people: `full` every kind raw, `masked` every kind
with `NOTIONAL` and `TRADER_ID` masked, `restricted` the trade kinds only so links to counterparties and books are refused): **134 of 134
entity pages proven**, 3 records per page, 1,206 comparisons, no difference. A board-local chip with one title changed fails its page at
`panels[1].title` (`Payments, next 30 days` against `Payments, next 31 days`).

**The dry run of every pack** (`drishti.py ui convert --pack <pack> --dry-run`, 2026-10-21; "chips" counts the board-local chips, the
headers aside):

| Pack | Screens | Entity pages | Report pages | Clean | With board-local chips | Board-local chips | Fail |
|---|---|---|---|---|---|---|---|
| trading | 136 | 134 | 2 | 125 | 11 | 47 | 0 |
| core | 10 | 10 | 0 | 0 | 10 | 22 | 0 |
| counterparty-risk | 10 | 10 | 0 | 0 | 10 | 26 | 0 |
| market-data | 20 | 20 | 0 | 0 | 20 | 58 | 0 |
| market-risk | 14 | 6 | 8 | 0 | 13 | 44 | 1 |
| news-and-markets | 4 | 4 | 0 | 0 | 4 | 22 | 0 |
| world-economy | 15 | 13 | 2 | 0 | 14 | 68 | 1 |

The two failures: `report:python-desk-risk/risk` (BC-03, the `python` visual's required `rows.columns` has no well) and
`report:region-economics/regions` (BC-03, the `text` visual's required `content` pin is not given). Packs other than trading build their
pages from panels, not blocks, so every page needs board-local chips: those 240 chips are the Chip Store's candidate list (14.1 decision 5).

### 12.5 As built: P4

**One designer (the product owner, during P4: "we do not want two versions of the same functionality").** The Board Designer is not a
second tool: it is Build's designer in **Board mode**, under `/build` beside the screen designer, reached from **Build → Designer**
(`/build/new`, which now offers *New board* and *New chip*), and **Build → Boards** (`/build`) is the one list of boards and screen
designs. The designer has two modes in one shell (one palette area, one YAML / JSON tab): **Board** (this phase) and **Chip** (a
placeholder that P4b's chip designer fills). The viewer is `/ui/b/<name>`. The guide is [UI_BOARD_GUIDE.md](../guides/UI_BOARD_GUIDE.md).

**Where (drishti-console).** `routes/ui_routes.py` (the pages; `/ui/api/...` passed on to `/api/v1/ui/...` as the caller with the document as
sent, `If-Match` / `If-None-Match` on and `ETag` back; the two render streams relayed event by event, a refusal before the stream answered
as JSON with its problems; `/ui/api-yaml/dump` and `/load` for the YAML tab, the dump a minimal patch that keeps the person's comments
(`core/yaml_roundtrip.py`); `/ui/api-suppliers` for the mapping sheet), `core/backend.py` (`ui()`, `ui_events()`), `core/sutra_board.py`
(the adapter below), `web/templates/ui/` (`_shell`, `design`, `view`), `web/static/js/ui-board-paint.js` (a ViewModel to HTML),
`ui-board-view.js` (the stream, the layout, chip states, pins), `ui-board-canvas.js` (palette, canvas, pins, traces),
`ui-board-design.js` (the document, the mapping sheet, check, live data, YAML tab, Undo/Redo, save, revisions, share, copy, modes),
`ui-chip-editor.js` (Chip mode's placeholder), `ui-boards.js` (the Boards list, keys), `web/static/css/ui-board.css`.

**Corrections and precisions the code forced:**

* **Painting reuses the renderers.** A `visual` body (the `bi-*` chips) and the chart panel kinds (`line`, `area`, `hbar`, `waterfall`,
  `gauge`, `metric` as a card) go to the BI visual renderers (`drishtiBi.visuals.draw`) with the chip's columns as wells; every table-like
  body is a `table.tbl`, so `tables.js` pages, filters and gives it **Chart** (Chart any table); `kv` is a definition list. The panel
  kinds' own ECharts builders (`charts.js`) need the server's computed panel view models, which a chip's rows are not; they are not used.
* **Selections** send the row's `keys` entry (the id, or the masked-id token) to `POST /ui/renders/{id}/pins`; a chart mark sends its value.
* **Live data in the designer** is a dynamic render (`POST /ui/render`) after each change (debounced), painted into the canvas boxes; it
  waits while the board check has errors.
* **Supplier attributes** for the mapping sheet: a pack's kind from its search form (`/forms/<pack>?kind=`), a BI model's measures and
  columns; offered only where the pin column's type accepts them. No server-side supplier catalogue exists yet (12.3), so when the
  console cannot list them the sheet takes typed names.
* **Compatibility while dragging** is a small client table (4.2 simplified) that only lights the pins; the board check decides. A selection
  traced into a `filter` pin gets `transform: {filter-on: <the target's id column>}` by itself.
* **Problem paths** paint: `traces[i]…` the wire and its target pin, `chips.<instance>…` the box and the named pin.
* **Screen designs as boards: a thin adapter until P5.** `core/sutra_board.py` maps a design's Sutra to a board of `panel-<kind>@1`
  instances (ids, titles, two columns from `area:`); bindings are listed as notes, not converted. *Export Sutra* writes a board's panel
  chips back as a Sutra (until 3.0). P4b replaced it with P5's converter (12.6); the routes (`/build/board-api/from-design/<id>`,
  `/build/board-api/to-sutra`) stayed.
* **Chip mode plugs in** at `ui-chip-editor.js`: the mode (`designer.setMode('chip')`, `/build/chip/<name>`, the `ub:mode` event), the
  shared YAML tab (the chip's document while in Chip mode), the palette (`designer.onChipPick(ref)`) and the test bench (`[data-ce-bench]`,
  each manual example rendered through `POST /ui/render`).
* **Not in P4:** modules (P6); the full chip designer (P4b, 12.6); restoring a revision is "load it in the YAML tab, then save"; the BI report
  designer stays separate (folding it in: a report is a board of `bi-*` chips; it needs the converter for report pages, the BI
  interactions (cross-filter, drill, slicers) as traces and pins, the Format pane as chip controls, and bookmarks as board params).
* **Tests.** `tests/test_ui_board.py` (the routes, against the stand-in `tests/ui_board_backend.py`, whose stream has P3's event shapes)
  and `tests/test_ui_board_browser.py` (Chromium: the 3.1 board built by drag, map and wire; a bad wire in red; save, reopen, a 409; the
  YAML tab and Undo; live data; the viewer, a selection, Chart, a live frame, a failing chip; the phone layout; keyboard only; Chip mode;
  ten themes). Pictures: `tests/ui_board_shots.py` → `docs/guides/img/ui-board/`.

### 12.6 As built: P4b

**The chip designer is Chip mode of the one designer** (the product owner: one designer, not two). A chip is a Rachana-grammar Sutra plus
typed pins plus its own manual, a pure function that paints UI; the guide is [UI_BOARD_GUIDE.md](../guides/UI_BOARD_GUIDE.md#chip-mode).

**Where (drishti-console).** `web/static/js/ui-chip-model.js` (the chip document checked in the browser: the pin table's rules, CP-01 to CP-04,
CP-07, unused and undriven pins, what breaks against an earlier version, a module made from wired chips), `ui-chip-editor.js` (the document,
the toolbar, the step tabs, the YAML tab in Chip mode, the problems, the palette's copy and the Candidates), `ui-chip-pins.js` (the pin table
and the manual form), `ui-chip-body.js` (the body with the screen designer's `build/palette.js` and `build/inspector.js` over a small store
of the body's panels; `build/expr.js` completes `@pin`, `@.column` and `%{pin}`), `ui-chip-bench.js`, `ui-chip-release.js`,
`ui-chip-module.js` (composite chips and Make a chip), `ui-board-palette.js` (the Chip Store palette's search, both modes),
`ui-board-route.js` (the circuit routing). Server (`com.ash.drishti.server.ui.UiChipToolsController`): `POST /ui/chips/check`,
`POST /ui/convert/sutra`, `POST /ui/convert/board`, `GET /ui/candidates[/{pack}/{board}/{chip}]`
([API_GUIDE.md](../guides/API_GUIDE.md#the-chip-designers-calls-270-p4b)); `PackScreens.packs()`, `convert()`, `sutraOf()`; the listing's
`pins` summary (`ChipStore.pinSummary`).

**Corrections and precisions the code forced:**
* **Checked twice.** The browser checks every keystroke (rule `PIN` for the pin table: names, types, roles, a rowset's columns, an enum's
  values, typed defaults; CP-01 to CP-04, CP-07); the server's `ChipPublishCheck` runs on each pause through `POST /ui/chips/check` and has the
  last word (CP-05 needs the store, CP-06 the earlier versions). Submit runs it again (P2).
* **CP-06** (new): a version that claims `compatible-with` an earlier released one while removing or retyping a pin. A breaking change is a
  new major version: it may not claim compatibility, so boards stay on their version until their owner moves them (14.1 decision 3); P2's
  publish refuses the same claim with `DRS-2078`.
* **Composite chips: a board with no connectors** (the product owner, during P4b). A chip's body may be a board of chips:
  `body: {board: {chips, traces, layout}, wires: {<chip in pin>: <instance>.<pin>}, exposes: {<chip out pin>: <instance>.<pin>}}`. Its data
  enters only through its pins. **CP-07** (new, in `ChipPublishCheck`): no supply inside (`body.board.chips.<i>.supply`); every wired chip pin
  names an inner pin of the same type; every required inner in pin is fed (an inner trace, a chip pin, a constant in `controls`, a default);
  at most `MAX_DEPTH` = 3 levels of chips in chips. A supply inside is CP-07, not CP-02. In the designer, **Body: chips** edits the inner
  board on Board mode's own canvas with the supplier pickers disabled, and an exposure table turns inner pins into chip pins. This is the
  minimal form of 9.5's modules; P3's renderer paints a composite's body only on the bench (chip by chip, client side): rendering it on a
  board through the render API is P6.
* **Make a chip** (Board mode) makes such a composite from the chosen instances: their unconnected pins become `<instance>-<pin>` chip pins
  (a pin set by a constant stays inside), the supplies stay on the outer board (one supply moves onto the new instance with its map renamed;
  a second one is dropped with a message), traces to the rest of the board are rewired to the chip's pins, and the draft is saved.
* **The bench paints locally**: the samples are the chip's ViewModel pins, painted by `ui-board-paint.js` as the viewer would; "Use a supplier"
  renders the first example through `POST /ui/render` (`stream: once`) as the author and takes the chip's pins. The examples as tests are the
  server's CP-05 per example plus a real render.
* **Themes on the bench** switch the page's theme while the bench is open (themes are `:root` tokens), and restore it on leaving.
* **Candidates** come from the converted boards P5 keeps in memory per pack (`PackScreens.boards`); one opens as `<pack>-<name>@1`, version 1.
* **P4's thin adapter is gone** (`core/sutra_board.py`): `/build/board-api/from-design/<id>` calls `POST /ui/convert/sutra` and
  `/build/board-api/to-sutra` calls `POST /ui/convert/board`; the console routes are unchanged.
* **Finding chips** (the product owner, during P4b): the palette loads the listing once (and again on a mode switch) and searches it in the
  browser: id, title, purpose, category, tags and pin types (`rowset of trade`), title and id first; category chips (from the category and
  tags, else guessed from the name); "Fits this data" from the selected instance's supplier attributes; favourites and recent chips in
  `localStorage`; `/`, arrows, Enter.
* **A board looks like a circuit** (the product owner, during P4b): Manhattan routing with a clearance round the boxes and separate vertical
  channels per source, hops (SVG arcs) where a later wire's horizontal run crosses an earlier one's vertical, junction dots where a fan-out
  splits, square pads, a dot grid, the live "current" (none under `prefers-reduced-motion`).
* **Not in P4b:** rendering composites through the render API and module where-used (P6); choosing which unwired pins become a module's pins
  by name at Make a chip (they are all exposed, then renamed in the exposure table); the inspector's form for chip-only options (`on-select`,
  `exposes`) is the Body YAML tab.
* **Tests.** `UiChipCheckTest` (the check, CP-06, CP-07), `UiMigrationTest` (convert one Sutra, export, the candidates); console:
  `test_ui_chip.py` (routes against `tests/ui_chip_backend.py`), `test_ui_chip_browser.py` (a chip authored from scratch with a CP-02 in red
  and fixed, the bench in two themes and on a phone, submit, four eyes, publish, used on a board; a composite chip with one exposed in pin and
  one out pin used on a board and a connector inside refused; Make a chip from two wired chips; a candidate; Open as board),
  `test_ui_palette_browser.py`, `test_ui_circuit_browser.py`. Pictures: `tests/ui_board_shots.py`.

## 13. Later releases

* **2.8.0, write-back:** chips gain **act** pins (a button chip's out pin wired to a write-back command), so boards act,
  within the write-back rules of [WRITE_BACK.md](WRITE_BACK.md); the board check adds rules for them.
* **2.9.0, AI:** agents compose boards by reading the chip manuals (`/manual`) and writing board documents, never code;
  a generated board passes the same board check, masks and limits as a hand-built one. GenUI's composer
  ([GENUI.md](GENUI.md)) becomes the board writer; its fragments are chips.
* **Promotion suggestions:** personal boards that many people copy or open are suggested to a workspace admin for
  promotion to a shared, reviewed board.
* **Observability:** chip and board use (renders, errors, timings by chip version) on an Admin dashboard, feeding the
  certification review and deprecation.

## 14. Open questions for the product owner

1. **Where personal boards live:** in the person's own space (`ui/boards/users/<user>/…`), with sharing by copying to a
   BI workspace, or should a personal board be shareable directly by link to named people?
2. **Certified-only:** should shared workspaces accept only certified chips by default, or only published ones (with
   certified-only as a per-workspace option, as proposed)?
3. **Automatic upgrades:** is `follow: compatible` (the store upgrades a board automatically when the check passes)
   acceptable, or must every chip upgrade be a person's click?
4. **Who may review and certify chips:** a new role (chip reviewer, chip certifier), or the existing Sutra governance
   reviewers and BI certifiers?
5. **Board-local chips:** should the converter's one-off chips stay local for ever, or be listed to a designer as
   candidates for the Chip Store after the migration?
6. **The proof bar:** is "same data" (values, masks, totals, links) plus a structural HTML comparison enough to switch a
   pack, or is a pixel comparison required as well?
7. **Old pages after the switch:** how long are the old Sutras and reports kept once a pack runs on boards (one release,
   or until 3.0)?
8. **Dynamic render for embed hosts:** may an embed token be scoped to render unsaved boards (needed for 2.9.0 agents
   in other applications), or only saved ones in 2.7.0?
9. **Default limits:** are 40 chips, 120 traces, 64 KB, 30 dynamic renders a minute per person the right defaults for the
   desks?
10. **Masked ids in out pins:** where a pack masks an id, is the opaque token (resolved only for the same person) the
    right behaviour, or should such a column be refused as a trace source?
11. **Release numbering:** the implementation plan's release table still lists 2.7.0 as write-back in places; confirm the
    order UI Board 2.7.0, write-back 2.8.0, AI 2.9.0, and that GENUI.md is folded into 2.9.0.

### 14.1 Decisions (product owner, 2026-10-21: "Go ahead" on the suggested answers)

1. Personal boards are private by default and shareable by link to named people (and copyable to a workspace).
2. Shared workspaces accept published chips by default; certified-only is a per-workspace option.
3. Boards follow compatible chip upgrades automatically when the board check passes; a breaking version needs a click.
4. The existing reviewer and certifier roles review and certify chips; no new role.
5. The converter's board-local chips are listed to designers as Chip Store candidates.
6. The proof bar to switch a pack is same data plus structure; no pixel comparison.
7. Old pages are kept until 3.0, with a per-pack switch back.
8. Embed tokens render saved boards only in 2.7.0; unsaved boards come with 2.9.0.
9. Default limits: 40 chips, 120 traces, 64 KB, 30 dynamic renders a minute per person; all configurable.
10. A masked id as a trace source passes an opaque token resolved only for the same person.
11. Release order: UI Board 2.7.0, write-back 2.8.0, AI 2.9.0 with GENUI.md folded in.
