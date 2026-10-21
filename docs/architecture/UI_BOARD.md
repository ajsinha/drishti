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

Status: **design, release 2.7.0, not built.** Decided by the product owner on 2026-10-21 (the north star of
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

### 6.3 Errors (proposed codes)

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

A new console page, *Boards*, beside the BI Workspace; maya UX tokens, the Terminal themes.

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

`POST /ui/migrate/dry-run { pack }` (and `drishti.py ui migrate --dry-run --pack trading`) converts every screen of the
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
| **P1. Chips, pins, boards: the model and the check** | A | `drishti-rachana`: the chip document parser (header, manual, pins, body), pin types and the compatibility table (4.2), the board document parser (YAML and JSON to one model), the board check BC-01 to BC-11 and BC-W1 to BC-W5, chip publish checks (purity, out pins driven, manual complete); `drishti.py ui check` | unit tests per rule, good and bad (each rule at least one failing board); the compatibility table as a parameterised test; YAML and JSON of the same board give the same model; the section 2.1 and 3.1 examples pass |
| **P2. The Chip Store and saved boards** | B | content-store keys (section 8), the lifecycle (9.1) with powers, versions and `compatible-with` verification (9.3), where-used index, board save, revisions, delete with compare-and-set; the REST API of 6.1 except render; seed chips from panel kinds, BI visual types and pack blocks (9.4) with manuals | store contract tests on the filesystem and SQLite backends; lifecycle order (`DRS-2078`); a reviewer may not approve their own chip; where-used after save and delete; two nodes saving the same board (`DRS-5057`); every seed chip passes its own manual examples |
| **P3. The render API** | A | `POST /ui/render`, `GET /ui/boards/{id}/render`, pin changes, sessions; layout-first SSE; suppliers through `ViewPipeline.preview` and `QueryService` as the caller; traces at runtime; BI run cards; live frames on the channel; limits and audit (7.3, 7.4) | render as three users (full, masked, row-restricted) and compare masks; trace ordering (detail after master); one failing supplier leaves the others painted; every limit hit gives its code; audit row has no values; access-profile reuse (second viewer `reused`) |
| **P4. The board viewer and the Board Designer** | B | console: the board viewer (any saved board, phone and desktop, themes), the Designer (palette, canvas, pins, traces, mapping sheet, live data, red problems, YAML/JSON tab, save, history, modules), the chip editor and test bench | Playwright: build the 3.1 board by drag and wire, see red on a bad wire, fix, save, reopen, YAML edit reflected on canvas; phone layout; keyboard-only path; console unit tests for the mapping matcher |
| **P5. The converter and its proof** | A | dry run, board-local chips, the proof (data, screen, behaviour), Admin pages for reports, per-pack switch; trading pack first, then the others pack by pack | trading: every entity page, report page and board page proven for the three users on the 10K-trade sample; a deliberately broken chip makes the proof fail with the right path; switching back restores the old pages |
| **P6. Modules, observability, docs and QA** | A and B | modules (9.5), `ui/usage` counts and the Admin view of chip and board use, the Boards guide, the Board Designer tutorial (UI, CLI and YAML at every step), help pages, the QA pass of the release | module round trip (make, publish, use, where-used); usage counts across two nodes; docs reviewed against the built behaviour; the release QA checklist |

P1 and P2 run in parallel (P2 uses P1's model through an interface agreed on day one); P3 needs P1; P4 needs P2 and P3's
check and render endpoints (it may start on the check alone); P5 needs P3; P6 last. One drill at the end of 2.7.0.

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
