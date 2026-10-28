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
# History queries: `TRD 1000015 30 <GO>`

Status: **built and released in 2.6.5** (2026-10-20), between 2.6.0 (wave D) and 2.6.6; the *As built* notes say where
the build differs from the design. Written 2026-10-20 from the
design agreed with the product owner, checked against the code on `develop` and against the two connector branches that are not merged
yet (drishti-iceberg and iceberg-external; Cassandra). Related: [ARCHITECTURE.md section 5](ARCHITECTURE.md#5-walk-through-one-request) (a command becomes a screen),
[DRISHTI_NET.md](DRISHTI_NET.md) (forwarding), [SUPER_SCALE.md](SUPER_SCALE.md) section 10 (slices and owners),
[SCALE_STRATEGY.md](SCALE_STRATEGY.md) (the history row of its table), [DRISHTI_ICEBERG.md](DRISHTI_ICEBERG.md),
[CASSANDRA_CONNECTOR.md](../connectors/CASSANDRA_CONNECTOR.md).

## Contents

1. [The ask](#1-the-ask) · 2. [What exists already](#2-what-exists-already) · 3. [Principles](#3-principles) ·
4. [The command](#4-the-command) · 5. [The history view](#5-the-history-view) · 6. [The `history:` block in pack.yaml](#6-the-history-block-in-packyaml) ·
7. [The server endpoint](#7-the-server-endpoint) · 8. [The JSON contract](#8-the-json-contract) · 9. [The connector SPI](#9-the-connector-spi) ·
10. [Fast paths, store by store](#10-fast-paths-store-by-store) · 11. [Limits, cache and settings](#11-limits-cache-and-settings) ·
12. [Drishti\*net and super scale](#12-drishtinet-and-super-scale) · 13. [Security](#13-security) · 14. [Performance targets](#14-performance-targets) ·
15. [Extras: DIFF, export, CLI, BI, plain words](#15-extras-diff-export-cli-bi-plain-words) · 16. [Errors](#16-errors) ·
17. [The data flow](#17-the-data-flow) · 18. [Build steps](#18-build-steps-h1h14) · 19. [Tests](#19-tests) ·
20. [Docs to update](#20-docs-to-update-when-it-is-built) · 21. [Where the code forced a change](#21-where-the-code-forced-a-change-from-the-agreed-design) ·
22. [Open questions](#22-open-questions)

## 1. The ask

The product owner (2026-10-20): *"if someone says `trd dffgg 30` then we should show top level attributes for that trade for last 30
days; if someone says `eq` and `30` then we should show latest 30 days of data for that equity and so on."* It is called a killer
feature. Traders and risk managers compare Drishti with Bloomberg, so the bar is a terminal: typed, keyboard-first, dense, fast and
beautiful in every theme.

One sentence: **add a range after the id, and the screen becomes that entity over time.**

```text
TRD 1000015 30 <GO>          the trade, one row per business day, the last 30 days it has data
EQ EQ-CSCA 3M <GO>           the equity: a price chart, the high, low and change, and the same table
FX FX-AUDUSD YTD <GO>        the FX spot since 1 January
CRV CRV-USD-SOFR FROM 2026-09-01 TO 2026-09-30 <GO>
TRD 1000015 30 DIFF <GO>     only the fields that changed, day by day
```

## 2. What exists already

History queries are built from parts that are in the code now. Nothing below is new.

| Part | Where | What it gives the design |
|---|---|---|
| One field over time | `HistoryController.series`, `GET /api/v1/history/{kind}/{id}/series?path=&days=` (drishti-server `server/api/HistoryController.java`) | One Rachana-EL path over up to `MAX_DAYS` = 260 business days, one `router.fetch(ref, AsOf.of(d))` per day, all at once, redacted per day with `Entitlements.redact`. Used by the field dialog `web/static/js/history.js` (click a number). |
| What changed | `HistoryController.diff`, `GET /api/v1/history/{kind}/{id}/diff?from=&to=&fromKnownAt=&toKnownAt=`; `engine/history/DocumentDiff` (limit 2000 changes) | The diff between two days, or two "known at" instants. Console page `/compare/{kind}/{id}`, export `/export/compare/{kind}/{id}.csv`. |
| Dated reads | `SourcePlugin.fetch(EntityRef, AsOf)`, `AsOf(businessDate, knownAt, live)`, `Provenance(source, generation, fetchedAt, live, businessDate)` (drishti-api) | Every dated connector answers one entity on one date; the provenance says which date the data is for and its generation. |
| Time travel | `SourcePlugin.timeTravel()`; `DRS-1007` `NO_TIME_TRAVEL` | Delta and Iceberg answer `knownAt`; others refuse it rather than show today's data. |
| A range of days as columns | `SourcePlugin.scan(ScanRequest)` with `ScanRequest(kind, paths, filters, from, to, batchRows)` and `ColumnScans.over` (at most `MAX_DAYS` = 800 calendar days) | Promoted columns over a date range with an `EQ` filter, no document parsed. Delta implements it; the others fall back. |
| Business days | `engine/time/BusinessDates` (`calendar().previous(d)`, `earliest()`, `current()`, `parse(date, knownAt)`) | The calendar the series already walks. |
| Routing | `engine/source/SourceRouter` (`fetch`, `fetchAll`, `readOrder`, `Fallback` for Drishti\*net peers), `SourceRegistry` | Several connectors per kind in read order (Redis for recent days, Delta behind it); peers when no local source holds the entity. |
| Forwarding | `server/net/ViewForwardFilter` (already forwards `/api/v1/history/{kind}/{id}...`), `PeerSource`, `NetDataController` (`/api/v1/net/fetch/{kind}/{id}`) | A history request reaches the node that serves the kind with no new routing code. |
| The command line | `engine/command/CommandParser`, `server/api/CommandController` (`POST /api/v1/command`, `/suggest`, `/history`, `/aliases`), `CommandMemory.expand` (aliases), `engine/search/SearchQuery.pick/looksLikePick`, `engine/search/PhraseParser`; console `routes/terminal_routes.py` `/go`, `web/static/js/command.js` | Where the range is parsed and the page chosen (section 4). |
| Pinned links | console `core/asof.py` (`?asOf=&knownAt=`), `share_url()` and `view()` in `routes/terminal_routes.py` (`?gen=`, `pin = asit / changed / retry`) | A history row opens "the normal view as of that day" with the machinery that exists. |
| Export | console `routes/export_routes.py`, `core/export.py` (`to_csv` with BOM and CRLF, `filename()`, `to_xlsx`, the formula guard) | The conventions history export follows. |

SCALE_STRATEGY.md measured the series at **0.02–0.12 s for 30 days** and diffs at 6–14 ms (2026-10-18, Delta). History queries
generalise the series from one field to a whole row and give it its own page, command and API.

## 3. Principles

1. **One command, any kind.** The range follows the id; the kind decides the shape (a document table, or a market chart and table).
2. **Days with data, not calendar days.** `30` is the last 30 business days on which that entity has data. The header names the dates.
3. **Per-kind behaviour is YAML.** A pack's `history:` block picks columns, chart fields and limits. No code per kind.
4. **The SPI stays optional.** A new default method loops over today's dated reads; a connector adds a fast path only when its layout
   allows one. Every connector works on day one.
5. **Same masks, same rights.** Every day's row is redacted exactly as the live view is. Rights are checked per kind.
6. **Bounded.** A maximum range, a row cap, a timeout, bounded parallelism and a cache keyed by the data's version.
7. **Terminal-grade.** Keyboard-first, dense, every theme, every contrast rule, under a second on a laptop.

## 4. The command

### 4.1 Grammar

```text
<MNEMONIC> <ID> <RANGE> [DIFF] <GO>

RANGE  :=  N                     N business days on which the entity has data (1 ≤ N ≤ max-days)
        |  N D | N W | N M | N Y calendar days, weeks, months, years back from the date box (30D, 2W, 3M, 1Y)
        |  YTD | MTD | QTD       since the first day of the year, month or quarter
        |  FROM yyyy-mm-dd [TO yyyy-mm-dd]
```

Case never matters (`30d`, `ytd`, `from`). `<GO>` anywhere is ignored, as today. The end of every range is the date box (the console's
`drishti_asof`, live means the current business date) unless `TO` says otherwise. `N D` and the other calendar forms take every business
day in the span that has data, so `30D` gives about 21 rows and `30` gives 30.

### 4.2 Where it is parsed, and every clash checked

Today `CommandParser.parse` splits the text in two (`split("\\s+", 2)`): the mnemonic and **everything else as the id**. So `TRD 1000015 30`
is read now as the id `1000015 30`; `CommandController.resolve` then finds no such trade and, because `SearchQuery.looksLikePick` is true for
any id containing a space, shows a pick list. The range must therefore be taken off **before** that, in a new class
`engine/command/HistoryRange` (parse and render, about 150 lines), called from `CommandController.resolve` after `CommandMemory.expand`.

The rule: **a range is the last one to four words, never the first word after the mnemonic, and only after an id.** Then:

| Typed | Today | With history | Why it cannot clash |
|---|---|---|---|
| `TRD 1000015` | opens the trade | the same | no range word |
| `TRD 1000015 30` | pick list (id `1000015 30` not found) | history, 30 days | the range is the third word |
| `TRD 30` | opens trade `30` | the same | the range is never the first word after the mnemonic, so a numeric id is always an id |
| `TRD MX-20000001 30` | pick list | history | as above |
| `TRD MX-2000 30` (a prefix) | pick list | the id is resolved as today first: one match opens its history; several give the pick list `/s?q=TRD MX-2000&days=30`, whose rows open history | the range is kept on the pick list |
| `BOND UST 2030` where `UST 2030` **is** an id | opens it | opens it | the whole rest is tried as an id first (the existing `exists()` read); only when it is not an entity is a trailing range taken off |
| `TRD MX-2* 30`, `TRD desk=rates 30` | pick list or search | the same, unchanged | a `*`, an operator (`SearchQuery.OPERATOR`) or `where / order by / limit` (`looksLikeSearch`) means a search; ranges apply to one entity only in 2.6.5 |
| `TRD limit 30` | search (`SUFFIX`) | the same | `looksLikeSearch` is checked first |
| `MYBOOK 30` (alias for `BOOK BOOK-RATES-1`) | `BOOK BOOK-RATES-1 30`, a pick list | history of the book | `CommandMemory.expand` appends trailing words (`MYBOOK F3` becomes `BOOK BOOK-RATES-1 F3`), so the range survives the alias |
| `MYPICK 30` (alias for `TRD desk=rates`) | search | the same | an alias that expands to a search stays a search |
| `MX-20000001 30` (bare id) | not read (two words), plain words | history | allowed only when `ReferenceCatalog.kindOf(id)` names the kind (the pack's `graph.id-patterns`), as for a bare id today |
| `1000015 30` (bare, no id pattern) | plain words | plain words, which now understand it (section 15.5): an id that names one entity of one kind, then a range or a time phrase | no id pattern for `1000015` |
| `MKT`, `RUPAKA …`, `FORM …`, `FUNC …`, `WHAT …`, `DATAMAP …` | pack overview, BI, form, functions, Knowledge, data map | the same | single words and reserved first words are handled before the command in `/go` |
| `TRD 1000015 FROM 2026-09-01` | pick list | history from that date to the date box | `FROM` and `TO` are keywords only in the last four words and only followed by a date |
| `TRD YTD` | opens trade `YTD` | the same | first word after the mnemonic |

`HistoryRange.trailing(String rest)` returns `Optional<Split(String id, HistoryRange range, boolean diff)>`; it never throws. A range that is
read but not allowed (too large, `TO` before `FROM`, a future `FROM`) fails with its own code (section 16), not with a pick list.
`HistoryRange.malformed` names what reads as a range but is none (`0`, `-5`, `99999`, `1.5M`, `3X`, `FROM yesterday`,
`FROM 2026-02-30`, `DIFF DIFF`, a bare `DIFF`, `30 30`, and since QA1-203 `30.`, `1e3`, a dangling `FROM` or `TO`
(`FROM 2026-10-01 TO`: *TO needs a date*), `TO` without `FROM` (*TO follows FROM*), `DIFF 30` (*DIFF follows a range: TRD 1000001 30
DIFF*)); `CommandController` answers it with `DRS-4022` (`DRS-4021` for a number of days
past `max-days`) and what to type when what is before it is one entity the caller may open and the whole text is not itself an entity.
`TO` before `FROM` and a date that does not exist are `DRS-4022` too, as at the endpoint. The console's `/go` shows the message on the
terminal page. Digits of another script are read as digits first (`TRD 1000001 ３0` is `TRD 1000001 30`, `０` is *0 days is empty*), as
the endpoint reads `days=０`. A bare id with such a range (`1000015 -5`, `1000015 since yesterday`) goes to plain words, which answer the
same `DRS-4022` (section 15.5); the console shows it on the terminal page too.

### 4.3 The answer and the redirect

`ApiDtos.CommandResponse` gains one field, `history` (null when there is no range):

```json
{ "ref": {"kind": "trade", "id": "1000015"}, "mnemonic": "TRD", "list": null, "matched": null, "pack": null,
  "history": {"range": "30", "days": 30, "from": null, "to": null, "diff": false} }
```

The console's `/go` (`routes/terminal_routes.py`) redirects to **`/v/{kind}/{id}/history?days=30`** (or `?from=&to=`, plus `&diff=1`).
`/v/{kind}/{id}?days=30` is accepted too and redirects there (303), so the short link the product owner wrote works. The route is
declared before `/v/{kind}/{id_:path}`, as `/about` and `/panels` are. A pick list carries `days` through `/s?q=…&days=30`.

### 4.4 Type-ahead, history and aliases

- `SuggestionService.suggest` (engine `command/SuggestionService`): when the text is `<MNEMONIC> <ID> ` and the id names one entity, the
  first suggestions are ranges: `30 — last 30 days`, `3M — last 3 months`, `YTD — since 1 January`, `30 DIFF — what changed`. `Tab`
  completes, as today. The kind's `history.default-days` is the first one offered. Across Drishti*net the peers' suggestions are
  merged with one row per range (each completes differently) and the ranges first, then the entity whose id was typed, from whichever
  node holds it (on a node that does not hold `1000002`, `TRD 1000002` lists the peer's `1000002` before this node's `10000020`…;
  QA1-204), then the rest; for a caller with field masks the range rows keep
  their labels (only entity rows are described again from the document as the caller sees it). A partial range (`TRD 1000015 3`)
  offers the ranges that start with it to every caller.
- `CommandMemory.remember` keeps the typed text, so `↑` recalls `TRD 1000015 30` as typed.
- Aliases store any command text, so `MYTRD = TRD 1000015 30` works without change.
- Share links carry the range and pin the end: `/v/trade/1000015/history?days=30&to=2026-10-19` (and `&knownAt=` when pinned), so the
  recipient sees the same rows a week later. A link without `to` is live: it moves with the business date.

## 5. The history view

### 5.1 Shapes

| Shape | When | Screen |
|---|---|---|
| `document` | default (trades, counterparties, agreements) | header, the table, the drawer of the day opened |
| `market` | `history.<kind>.chart` is set (equity, curves, FX), or `shape: market` | header, headline numbers, a chart, the same table |
| none | no source of the kind keeps daily history and no embedded series (section 6) | today's view with a banner: *"FX-AUDUSD keeps no daily history here: its connector holds today's data only."* (`DRS-1012`) |

### 5.2 Trade history (document shape)

```text
┌─ TRD 1000015 ▸ HISTORY ─────────────────────────────────────────────────────────────────────── 30 business days ─┐
│ ComSwap ENGY-SWAP · S 72,000 · CP-HARBORPT · BOOK-COMM-2          2026-09-08 → 2026-10-19 · trade-endur (delta)  │
│ 30 rows · 6 days changed · 1 day restated · known: latest                       [C] changes only  [D] diff  [E] ⤓ │
├────────────┬───────────┬─────┬──────────┬────────────┬──────────────┬─────────────┬───────────┬──────┬──────────┤
│ Date       │ Status    │ Ver │ Quantity │ Price      │ Maturity     │ Portfolio   │ Cpty      │ Legs │ Events   │
├────────────┼───────────┼─────┼──────────┼────────────┼──────────────┼─────────────┼───────────┼──────┼──────────┤
│▶2026-10-19 │ VALIDATED │  3  │   72,000 │ 2,341.0500▲│ 2028-07-31   │ BOOK-COMM-2 │ CP-HARBOR…│  2 ▸ │  5 ▸     │
│ 2026-10-16 │ VALIDATED │  3  │   72,000 │ 2,336.1200▼│ 2028-07-31   │ BOOK-COMM-2 │ CP-HARBOR…│  2 ▸ │  5 ▸     │
│ 2026-10-15 │ VALIDATED │ ░3░ │ ░75,000░ │ 2,338.4000 │ 2028-07-31   │ BOOK-COMM-2 │ CP-HARBOR…│  2 ▸ │ ░5░▸ ⟲2  │
│ 2026-10-14 │ VALIDATED │  2  │   72,000 │ 2,332.9131 │ 2028-07-31   │ BOOK-COMM-2 │ CP-HARBOR…│  2 ▸ │  4 ▸     │
│ 2026-10-13 │ ·         │  ·  │        · │          · │ ·            │ ·           │ ·         │  ·   │  ·       │
│ …          │           │     │          │            │              │             │           │      │          │
├────────────┴───────────┴─────┴──────────┴────────────┴──────────────┴─────────────┴───────────┴──────┴──────────┤
│ ░ changed from the day before   ⟲2 two versions that day (the latest shown)   · same as the day before (dimmed) │
│ ↑↓ row  ←→ column  Enter open the day  C changes only  D diff  F fields  E export  R range  ? keys  Esc view   │
└────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

- **One row per business day**, newest first on screen (the JSON is oldest first, section 8). A day with no data for this entity is left
  out; it is listed in the header's tooltip ("no data on 2026-09-14").
- **Columns** are the kind's top-level scalars: the pack's `history.<kind>.columns` when given, otherwise the kind's search key columns
  (`drishti.search.columns`, from `columns:` in pack.yaml) first and then every other top-level scalar, labelled as the view labels them.
  A column empty on every row is hidden (`F` shows it). Values use the pack's formats (`fmt($.PRICE, 'price2')`).
- **Nested parts** (top-level lists and objects: `LEGS`, `EVENTS`) show as a count with `▸`. Clicking, or `Enter` on the cell, opens
  that day's part in the drawer. A count that changed is highlighted like any changed cell.
- **Changed cells** (different from the previous day, the day before the range included, section 7.3) use the `--d-warn` tint and a
  `▲`/`▼` for numbers; unchanged cells after the first change are dimmed with `--d-muted`, so the eye finds the moves.
- **Changes only** (`C`) hides days on which nothing moved; the header says "6 of 30 days".
- **Several versions** in one day (a restatement, a second load) show `⟲2` beside the date; its tooltip names the versions or
  generations. The row is the latest version of that day.
- **A row opens the normal view as of that day**: `Enter` (or a double click) goes to
  `/v/{kind}/{id}?asOf=2026-10-15&gen=<generation>&via=history` (and `&knownAt=` when the history is "known at"), so the banner
  says "as it was, from its history" or "changed since the history read that day" (never "shared"), with *Back to the history*.

### 5.3 Equity history (market shape)

```text
┌─ EQ EQ-CSCA ▸ HISTORY ──────────────────────────────────────────────────────────────────────────── 3 months ──┐
│ Cascadia Airlines Inc. · TSX · CAD · Airlines                    2026-07-20 → 2026-10-19 · 64 days · market    │
│ LAST 21.84   CHG +2.89 +15.25%   HIGH 22.10 (2026-10-08)   LOW 18.71 (2026-08-04)   RANGE 3.39   AVG 20.11   │
├─────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ 22 ┤                                                                        ╭─╮ ╭╮                              │
│ 21 ┤                                                         ╭──╮  ╭───╮ ╭──╯ ╰─╯╰─╮╭─●                         │
│ 20 ┤                                     ╭─╮ ╭──────╮ ╭──────╯  ╰──╯   ╰─╯         ╰╯                           │
│ 19 ┤      ╭──╮      ╭─────╮  ╭────╮ ╭────╯ ╰─╯      ╰─╯                                                         │
│ 18 ┤──────╯  ╰──────╯     ╰──╯    ╰─╯                                                                           │
│    └┬──────────────┬──────────────┬──────────────┬──────────────┬──────────────┬──────────────┬────────────     │
│    07-20          08-04          08-18          09-01          09-15          09-29          10-13              │
│    [1M] [3M] [6M] [YTD] [1Y]   PRICE ▾   ○ line ○ area   ⌖ crosshair: 2026-10-08 22.10                          │
├────────────┬──────────┬──────────┬────────────────┬────────┬─────────────────────────────────────────────────────┤
│ Date       │ Price    │ Chg 1D   │ Market cap     │ Beta   │                                                     │
├────────────┼──────────┼──────────┼────────────────┼────────┤                                                     │
│▶2026-10-19 │  21.84 ▲ │ +3.85%   │ 34,324,492,441 │ 1.46   │                                                     │
│ 2026-10-16 │  21.03 ▼ │ −0.47%   │ 33,051,000,120 │ 1.46   │                                                     │
│ …          │          │          │                │        │                                                     │
└────────────┴──────────┴──────────┴────────────────┴────────┴─────────────────────────────────────────────────────┘
 ↑↓ row (the crosshair follows)  1 3 6 Y T range  P next chart field  Enter open the day  E export  ? keys  Esc view
```

- The **headline numbers** are computed by the server on the headline field (`history.<kind>.headline`, default the first chart field):
  last, change and change % over the range (first to last row), high and low with their dates, range (high − low) and average.
- The **chart** is ECharts, as the field dialog in `history.js` already uses, coloured from the theme's tokens (`--d-ink`, `--d-muted`,
  `--d-link`, `--d-border`) read at draw time, so every theme works. Several chart fields (a curve's `TEN_Y` and `SLOPE_2S_10S`) are one
  axis each, `P` steps between them. The table's selected row and the chart's crosshair move together.
- Range buttons re-ask the server; the page URL follows (`?range=3M`), so `Back` works and a link reproduces it.
- Curves and surfaces: the table shows the scalars (`TEN_Y`, `SLOPE_2S_10S`, `AS_OF`); `POINTS` is a part (`12 ▸`), and the drawer draws
  that day's curve with the previous day's as a faint line.

### 5.4 Keys and accessibility

| Key | Does | Key | Does |
|---|---|---|---|
| `↑` `↓`, `j` `k` | previous, next row | `Home` `End`, `PgUp` `PgDn` | first, last, page |
| `←` `→` | previous, next column (the header cell is announced) | `Enter` | open the selected day's view |
| `Space` | open the selected cell's part in the drawer | `C` | changes only, on and off |
| `D` | diff mode (section 15.1) | `F` | choose fields (a checklist; the choice is kept per kind in the user's settings) |
| `R` | type a new range in place | `1` `3` `6` `Y` `T` | 1M, 3M, 6M, 1Y, YTD (market shape) |
| `P` | next chart field | `E` | export menu (CSV, JSON, Excel) |
| `?` | the keys | `Esc` | close the drawer, else back to today's view |

The table is a real `<table>` (`.tbl`, `data-plain`) with a roving `tabindex` on rows, `aria-selected` on the selected row and
`aria-sort` on the date column. A changed cell carries `data-changed` and visible text in its title ("changed from 75,000") so colour is
never the only signal. The chart has an `aria-label` with the headline numbers and a "table" fallback (the table below it). Keys are
ignored while focus is in an input, as the command line's `/` is. Reduced motion turns off the chart's animation.

**Themes:** all ten (`terminal`, `light`, `wallstreet`, `blue`, `green`, `crimson`, `crimson-dark`, `terminal-green`, `terminal-crimson`,
`terminal-blue`). The new `web/static/css/history.css` uses only tokens: text in `TEXT_TOKENS` (`--d-ink`, `--d-muted`, `--d-link`,
`--d-warn`, `--d-pos`, `--d-neg`…), fills with `--d-on-fill` text, so `drishti-console/tests/test_contrast.py` (every `color: var(--d-X)`
in `web/static/css/*.css` must be a text token or `on-accent` / `on-fill`; 4.5:1 on every ground) covers it with no new rule.

## 6. The `history:` block in pack.yaml

Kinds are declared in `pack.yaml` as a list (`kinds: [trade, …]`), and per-kind settings live in top-level maps keyed by kind:
`ingest: {trade: …}`, `form: {ir-curve: […]}`, `mnemonics:`. `history:` follows the `form:` pattern exactly: read through
`Pack.manifest()` by a new server component `server/design/HistoryDefinitions` (as `FormDefinitions` reads `form:`), parsed by a new
`engine/history/HistorySpec.parse(kind, raw, problems)` (as `engine/search/form/FormSpec.parse`).

```yaml
history:
  trade:
    columns: [TRADE_STATUS, TRADE_VERSION, QUANTITY, PRICE, MATURITY_DATE, PORTFOLIO, COUNTERPARTY]   # default: top-level scalars
    exclude: [SOURCE_UPDATE_TIME, _meta]          # never shown
    ignore-changes: [SOURCE_UPDATE_TIME]          # shown, but a change here is not "a change" (changes only, DIFF)
    parts: [LEGS, EVENTS]                         # default: every top-level list or object
    default-days: 30
    max-days: 250                                  # at most drishti.history.max-days
  equity:
    shape: market                                  # implied by chart:
    chart: [PRICE]
    headline: PRICE                                # high, low, change; default: the first chart field
    columns: [PRICE, CHANGE_1D, MARKET_CAP, BETA]
    series: {path: HISTORY, date: DATE, value: CLOSE}   # used when no source keeps daily documents
  ir-curve:
    chart: [TEN_Y, SLOPE_2S_10S]
    columns: [TEN_Y, SLOPE_2S_10S, AS_OF]
  news-topic:
    enabled: false                                 # no history for this kind (the command says so, DRS-1012)
```

### 6.1 Schema

| Key | Type | Default | Rule |
|---|---|---|---|
| `enabled` | boolean | `true` | `false` turns history off for the kind |
| `shape` | `document` \| `market` | `market` when `chart` is set, else `document` | |
| `columns` | list of a path, or `{path \| expr, label?, format?}` | key columns first, then top-level scalars | a path is a top-level field; `expr` is Rachana-EL (as the series' `path` is today, e.g. `first($.LEGS).NOTIONAL`); at most `drishti.history.max-columns` |
| `exclude` | list of paths | `[]` | |
| `ignore-changes` | list of paths | `[]` | |
| `parts` | list of paths | every top-level list or object | each must be a list or object in the sample documents (a warning otherwise) |
| `chart` | list of paths or `{path \| expr, label?, format?}` | `[]` | numeric; at most 4 |
| `headline` | path | first of `chart` | must be in `chart` |
| `series` | `{path, date, value \| values}` | none | a list inside the document with a date per item (the market samples carry `HISTORY: [{DATE, CLOSE}]`) |
| `default-days` | 1..`max-days` | `drishti.history.default-days` (30) | |
| `max-days` | 1..`drishti.history.max-days` | the server's | |

Unknown keys are warnings, bad values errors, with a new code **`DRS-2061` `HISTORY_INVALID`** (422) beside `DRS-2060` `FORM_INVALID`. The
checks run where pack manifests are checked: the deploy stage (`manifestCheck` in `server/deploy/PackDeployService.java`), `sutra lint <pack-dir>`
(`server/cli/SutraCli`) and pack reload (a warning in the log and on Admin → Packs, as `FormDefinitions` logs a bad form). The Build
workbench gets a History tab later (open question 3). `docs/guides/PACK_DEVELOPER_GUIDE.md` gets a step for it.

### 6.2 Embedded series

The market samples (`config/packs/market-data/samples/equity.jsonl`, `fx-spot.jsonl`) carry their own history inside today's document
(`HISTORY: [{DATE, CLOSE}]`, `[{DATE, MID}]`). When `series:` is declared and the kind's sources hold no daily documents in the range,
the rows come from that list in the newest document (`"basis": "embedded"` in the answer): a chart and a one-column table, no versions.
When daily documents exist they win, because they carry every field. *As built:* when the stored days start inside the window
(a store that keeps a week, asked for 3 months), the days before the oldest stored one are filled from the newest document's series
(`basis: "documents+series"`, those rows `source: "series"`), so the chart and the headline numbers cover the whole range; beside a
series row only the series' own fields count as changes.

### 6.3 Shipped packs

H13 adds `history:` to `trading` (trade), `market-data` (equity, equity-index, fx-spot, ir-curve, credit-curve, commodity, bond,
inflation-index), `counterparty-risk` and `market-risk` (their headline measures), with `enabled: false` on derived kinds whose pages
are rebuilt per day at a cost (`counterparty-trades`, `portfolio-trades`: open question 2).

## 7. The server endpoint

### 7.1 Path

```text
GET /api/v1/history/{kind}/{id}?days=30 | range=3M | from=yyyy-mm-dd&to=yyyy-mm-dd
    [&fields=PRICE,TRADE_STATUS] [&diff=true] [&knownAt=<ISO-8601>] [&order=asc|desc]
GET /api/v1/history/{kind}/~?id=<id>&…          an id a path cannot carry ("/", "\", ".."), as entity_path() does today
```

It sits in the existing `HistoryController` (`@RequestMapping("/api/v1/history")`) beside `/series` and `/diff`, not under
`/api/v1/entities` (section 21). The date box's `AsOf` (resolved by `AsOfResolver` from `X-Drishti-As-Of`) is the default end;
`to` overrides it. The access log gets `"/api/v1/history/{kind}/{id}" → "history"` in `AccessRecorder.ACTIONS`.
A mnemonic or a plural in place of the kind is `DRS-4004` saying which kind to use (*TRD is a mnemonic: use the kind trade*, *unknown
kind trades: did you mean trade?*). Kinds are matched exactly, as everywhere in the API: one in another case is `DRS-4004` too
(*unknown kind Trade: kinds are written in lower case, did you mean trade?*), and one that no pack, source or mnemonic knows names the
kinds there are (*unknown kind nokind: the kinds here are …*), unless a source that serves any kind holds that entity. A field in `fields` that is no column but a top-level field of the documents is shown as a column;
one that no day's document has is `DRS-4004` (*no trade has a field NOPE on these days*), never dropped silently.

### 7.2 What it does

```text
HistoryController.rows(kind, id, params, AsOf, Principal)
  1. entitlements.requireOpen(principal, kind)                       DRS-6xxx as today
  2. spec   = historyDefinitions.of(kind)                             enabled? shape, columns, limits
  3. window = HistoryWindow.resolve(range, AsOf, BusinessDates, spec) dates; DRS-4021 / DRS-4022 on a bad range
  4. key    = (kind, id, window, fields, knownAt, router.dataVersion(kind, LATEST))
     cache hit → step 7
  5. days   = router.history(ref, window + one day before, fields, knownAt, budget)   section 9
  6. rows   = HistoryRows.build(days, spec): latest version per day, baseline compare, changed[], parts counts, summary
  7. redact every row's document for the principal, then project the columns   section 13
  8. answer (section 8); partial when some days failed or timed out (DRS-1014 in problems)
```

The service class is `engine/history/HistoryService` (engine, so the CLI and BI reuse it), with `HistoryWindow`, `HistoryRows` and
`HistoryCache` beside `DocumentDiff`. The controller stays thin. `series` is re-pointed at the same service (one field is a one-column
history), so the field dialog gets the fast paths and the 260-day constant becomes the setting.

### 7.3 Days, versions and "known at"

- **Days on which the entity has data.** For `days=N` the window ends at the date box and walks back over business days
  (`BusinessDates.calendar().previous`) until N days with data are found or `max-days` business days are passed. A connector that can list
  the entity's days says them directly (section 9: `days(…)`), so no empty day is read. A **snapshot** connector that answers a date with
  an earlier date's document (its `lookback-days`) does not count that date: a row is kept only when `provenance.businessDate()` equals
  the date asked, as `ColumnScans` already skips such days.
- **Effective kinds** (a row only when the entity changes, `mode.<kind>: effective`) have data on every day by definition. Each business
  day is a row; days carried forward from an earlier change are marked `carried: true` and dimmed, so `C` (changes only) shows exactly
  the change days.
- **The baseline.** One more day before the window is read, so the first row's changes are real. It is reported as `baseline`, not as a row.
- **Several versions in a day.** A row is the latest version of its day. `versions` is how many versions of that day the store keeps
  (Delta: commits in the retained log that added files for the date; Iceberg: snapshots that added files to the partition; others:
  `null`, unknown) and `restated` is true when there is more than one. Only connectors that can say it cheaply fill it (section 10).
- **Known at.** Without `knownAt` each day is its latest version (what we know now). With `knownAt=K` the whole history is read as the store
  stood at instant K: each day is the version of that day that was known at K, and days loaded after K are absent. It is Delta's and
  Iceberg's time travel (`timeTravel()`); a connector without it that may hold one of the dates refuses with `DRS-1007` as reads do
  today. One that holds none of them (`holdsNone`, or `NOT_HELD` for every date: the shipped `file` connector before any file of the kind)
  is not asked, so it never refuses for the Delta or Iceberg sources behind it. A `knownAt` that is not an ISO-8601 instant fails with
  `DRS-4003` naming it (*knownAt '2026-10-20' is not an instant*). A window with no data as known at K is `DRS-1013` saying so, and the
  days it has now are named as known now, since they may have been loaded after K (*… has no data in the 5 business days to 2026-10-20 as
  known at 2026-10-20T13:00:00Z: none of its data in that window was known then; as known now its days with data run from 2026-07-27 to
  2026-10-20, which may have been loaded after 2026-10-20T13:00:00Z: ask without knownAt, or with a later one*). "Each day as it was known at
  the end of that day" (as first reported) is a different question, kept as open question 1.

## 8. The JSON contract

This is the contract between the two builders (section 18). Builder B builds the console against a fixture of exactly this shape
(`drishti-testkit/src/main/resources/history/trade-1000015-30.json`, added in H1) and the server's tests assert it.

```json
{
  "ref": {"kind": "trade", "id": "1000015"},
  "mnemonic": "TRD",
  "title": "ComSwap ENGY-SWAP · CP-HARBORPT",
  "shape": "document",
  "basis": "documents",
  "window": {
    "asked": "30",
    "from": "2026-09-08",
    "to": "2026-10-19",
    "days": 30,
    "mode": "data-days",
    "baseline": "2026-09-07",
    "missing": ["2026-09-14"],
    "knownAt": null,
    "loadedTo": null,
    "loadedFrom": null
  },
  "columns": [
    {"path": "TRADE_STATUS", "label": "Trade status", "type": "text", "format": null, "masked": false, "key": true},
    {"path": "QUANTITY", "label": "Quantity", "type": "number", "format": "compact", "masked": false, "key": true},
    {"path": "PRICE", "label": "Price", "type": "number", "format": null, "masked": false, "key": false},
    {"path": "TRADER_ID", "label": "Trader", "type": "text", "format": null, "masked": true, "key": false}
  ],
  "parts": [
    {"path": "LEGS", "label": "Legs"},
    {"path": "EVENTS", "label": "Events"}
  ],
  "chart": [],
  "rows": [
    {
      "date": "2026-09-08",
      "dataDate": "2026-09-08",
      "carried": false,
      "values": {"TRADE_STATUS": "VALIDATED", "QUANTITY": 72000.0, "PRICE": 2332.9131, "TRADER_ID": "•••"},
      "display": {"TRADE_STATUS": "VALIDATED", "QUANTITY": "72K", "PRICE": "2,332.9131", "TRADER_ID": "•••"},
      "parts": {"LEGS": 2, "EVENTS": 4},
      "changed": [],
      "changes": null,
      "versions": 1,
      "restated": false,
      "generation": 41,
      "source": "trade-endur",
      "deleted": false
    },
    {
      "date": "2026-10-15",
      "dataDate": "2026-10-15",
      "carried": false,
      "values": {"TRADE_STATUS": "VALIDATED", "QUANTITY": 75000.0, "PRICE": 2338.4, "TRADER_ID": "•••"},
      "display": {"TRADE_STATUS": "VALIDATED", "QUANTITY": "75K", "PRICE": "2,338.4000", "TRADER_ID": "•••"},
      "parts": {"LEGS": 2, "EVENTS": 5},
      "changed": ["QUANTITY", "PRICE", "parts.EVENTS"],
      "changes": null,
      "versions": 2,
      "restated": true,
      "generation": 58,
      "source": "trade-endur",
      "deleted": false
    }
  ],
  "summary": {
    "field": null,
    "last": null, "first": null, "change": null, "changePct": null,
    "high": null, "highDate": null, "low": null, "lowDate": null, "range": null, "average": null
  },
  "changedDays": 6,
  "partial": false,
  "problems": [],
  "truncated": false,
  "dataVersion": "7@1760700000000",
  "cache": "miss",
  "tookMs": 184
}
```

| Field | Meaning |
|---|---|
| `shape` | `document` or `market` (section 5.1) |
| `basis` | `documents` (daily documents), `embedded` (the kind's `series:` list), `documents+series` (daily documents, and the days before the oldest stored one from the newest document's `series:` list, those rows with `source: "series"`), or `none` (never sent: `DRS-1012` instead) |
| `window.mode` | `data-days` (a bare number), `calendar` (`30D`, `3M`, `YTD`…), `between` (`FROM … TO`) |
| `window.to` | the last day shown: for `data-days` the entity's newest day with data (not the date box), else the end of the span |
| `window.missing` | business days in the window with no data for this entity (every mode; capped at 400), up to the newest date the kind's sources hold |
| `window.loadedFrom` | set only when a `calendar` or `between` span starts before the entity's first day with data: that day (the days before it are not missing: not loaded here, or the entity did not exist yet) |
| `window.loadedTo` | set only when a `calendar` or `between` span ends after the newest date the kind's sources hold: that date (the days after it are not loaded yet, not missing) |
| `columns[].masked` | true when the caller's masks hide the field: its values are the mask on every row |
| `columns[].key` | one of the kind's key columns (shown first, frozen when scrolling) |
| `rows` | oldest first; at most `drishti.history.max-rows`; `order=desc` reverses |
| `rows[].values` | raw values after masks (numbers stay numbers); `display` is the same values formatted for the screen |
| `rows[].dataDate` | the date the data is for; equal to `date` except for `carried` rows of effective kinds |
| `rows[].changed` | paths that differ from the previous row (or the baseline); `parts.<path>` for a part whose count or content changed; fields in `ignore-changes` never appear |
| `rows[].changes` | with `diff=true`: the `HistoryController.Change` list (`path`, `label`, `kind`, `before`, `after`, `delta`), as `/diff` returns |
| `rows[].versions`, `restated` | section 7.3; `null` when the connector cannot say |
| `rows[].deleted` | the entity was deleted that day (a tombstone); its values are empty |
| `summary` | market shape: the headline field's numbers; all null for the document shape |
| `partial`, `problems` | some days could not be read: `[{"code": "DRS-1014", "source": "trade-endur", "days": ["2026-09-21"], "message": "…"}]` |
| `truncated` | the row cap cut the answer (the oldest rows are dropped and the header says so) |
| `dataVersion` | the kind's `dataVersion` at the time of the read (the cache key's last part) |

A market answer differs only in `shape`, `chart` (`[{"path": "PRICE", "label": "Price", "format": "price2"}]`) and a filled `summary`.
Errors use the existing problem shape of `ApiExceptionHandler` (`code`, `message`, `fix`).

## 9. The connector SPI

### 9.1 New types in drishti-api

```java
/** One entity over business dates. fields empty means the whole document. */
public record HistoryRequest(EntityRef ref, LocalDate from, LocalDate to, Set<String> fields, Instant knownAt,
        int lastDays, Duration budget) {}

/** One business date of one entity: the document (or only the asked fields as a document) and where it came from. */
public record HistoryDay(LocalDate date, EntityDocument document, Integer versions) {}

/** What a source answered: the days it holds in the request, the dates it is authoritative for, the days it failed. */
public record HistoryRead(List<HistoryDay> days, Set<LocalDate> covered, Map<LocalDate, String> failed) {}
```

`lastDays > 0` means "the last N days with data on or before `to`" (then `from` is the earliest date the walk may reach).

### 9.2 New methods on `SourcePlugin`

```java
/**
 * The entity on each business date of the request that this source holds, newest last. The default reads day by day
 * through fetch(ref, asOf), at most drishti.history.parallel-days at once (HistoryReads.perDay).
 */
default HistoryRead history(HistoryRequest request) throws Exception {
    return HistoryReads.perDay(this, request);
}

/** The business dates on which this source holds the entity, from..to; empty when it cannot list them cheaply. */
default Optional<NavigableSet<LocalDate>> days(EntityRef ref, LocalDate from, LocalDate to) {
    return Optional.empty();
}
```

`HistoryReads` (drishti-api, beside `ColumnScans`) is the default:

1. The dates: `days(ref, from, to)` when present, else the business days of the request (the caller passes them; a plugin has no calendar).
2. Read each date with `fetch(ref, new AsOf(d, knownAt))` on virtual threads under a `Semaphore` of `parallel-days` (8), newest first, so
   `lastDays` stops early once N days with `provenance.businessDate() == d` are found.
3. A date whose read throws goes to `failed`; a date answered by an earlier date (snapshot lookback) is skipped; `covered` is the dates
   for which `coverage(kind, AsOf.of(d))` is `HELD`.
4. `fields` are applied after the read (the default cannot read less than a document).

`ConnectorInstance` (engine, the wrapper of every configured connector) delegates both methods and counts them in `SourceStats`.
`DerivedSourcePlugin` keeps the default. `PeerSource` implements `history` with one call to the peer (section 12).

### 9.3 The router

`SourceRouter.history(EntityRef, HistoryRequest)` merges the kind's connectors in their read order (`readOrder`, as `fetch` uses): the
first connector answers the dates it holds; the dates it does not cover (`covered`) go to the next, so Redis (today and the last days)
and Delta (years) behind it give one history. Each connector gets the remaining budget. Dates no local connector holds go to
`Fallback.peers` (Drishti\*net). The router never asks a connector for a date an earlier authoritative one covered, as `fetch` does not.

## 10. Fast paths, store by store

What each layout allows, from the code (and the two unmerged branches, read with `git show`).

| Connector | Layout (real) | History fast path | Versions |
|---|---|---|---|
| **delta** (`DeltaSourcePlugin`, `DeltaTable`) | one table per kind, partitioned `business_date=`; `(id, doc)` plus promoted columns (`layout.<kind>.columns`); `Layout.files()` is a `NavigableMap<LocalDate, List<Row>>` of the snapshot; a date's **id map** (`MappedIdMap`, memory-mapped) gives the file; one row group read by id statistics; heap and disk document caches | `days()` = `layout.files().subMap(from, true, to, true).keySet()`: the dates from the snapshot already in memory, no read. Per date: `idMap(kind, l, d).file(id)` (absent = no data, nothing read), then the one row group. When every asked field is promoted, read **only those columns** of that row group (a new `DeltaTable.row(l, d, file, id, columns)`; no `doc` decode, no JSON parse); otherwise the document through the existing caches. Reads go under `docReads` (`max-concurrent-reads`, 16). `knownAt` uses `layout(kind, knownAt)` once for the whole range. Effective tables: the entity's change dates are the dates whose id map holds it. | commits that added files for the date in the retained log (read once per table version, cached); `filesStamp(l, d)` already tells a restated date |
| **drishti-iceberg** (branch `worktree-agent-aea970bf1efcd2886`: `IcebergSourcePlugin`, `KindTable`, `NativeKindTable`, `drishti-iceberg` `scan/Planner`, `scan/Pruning`) | one table per kind; `id`, `doc`, `business_date`, promoted columns (`LEGS.CURRENCY` as `LEGS__CURRENCY`); partitioned `identity(business_date)`, **sorted by id**, files of about 250k rows with disjoint id ranges, 1 MB row groups; manifests keep id bounds | **One planned scan**: `id = X AND business_date BETWEEN from AND to`, pruned by partition summaries and the manifests' id bounds, gives about one file per day with no per-day id map (an id map reads the whole day's id column, about 75 MB per 1M ids). Then each file's row group by its id statistics, projecting `(business_date, id, <promoted fields>)` or `doc`. Files read in parallel (8). `days()` from the planned layout's dates. `knownAt` plans the snapshot `snapshotAt(K)`. | snapshots that added files to the partition, from the snapshot log (`meta/Snapshot`) |
| **iceberg-external** (same branch: `IcebergExternalPlugin`, `ExternalTable`, `KindConfig`) | any layout: id from identifier fields or named columns, date from a column, `day(ts)` or the partition spec; a table with no date is current-only | the same planned scan when the date is a partition or has column bounds; otherwise the per-day **id index** it already builds (`id-index-mb`); a table with no date has no history (`DRS-1012`) | as drishti-iceberg |
| **iceberg** (develop: `IcebergTable`, `IcebergLake`; kept as `engine: library` on the branch) | as drishti-iceberg | the default (it re-reads the Parquet footer on every read) | none |
| **cassandra** (branch `worktree-agent-ad01b2d6c9c414a21`: `CassandraSourcePlugin`, `CassandraLayout`, `DayReads`) | data table `PRIMARY KEY ((business_date, generation, bucket), id)`; **`entity_days`** `PRIMARY KEY ((kind, id), business_date)` DESC, written for every row; `kind_days` per date with its `generation` and `buckets` | `days()` = one single-partition range read: `SELECT business_date FROM entity_days WHERE kind=? AND id=? AND business_date >= ? AND business_date <= ?`, filtered by `kind_days` (an `entity_days` row may outlive its data). Then one token-aware point read per date (generation and bucket computed locally), in parallel. The documents cannot come from one query: `business_date` is in the data table's partition key. No `knownAt` (one generation kept, `DRS-1007`). | 1 (one generation kept) |
| **jdbc** table mode (`PostgresLayout`, `TableCatalog`; `(kind, id, business_date, doc jsonb)`, partitioned by month) | one row per entity per date | **one query**: `SELECT business_date, doc FROM <table> WHERE kind = ? AND id = ? AND business_date BETWEEN ? AND ? ORDER BY business_date` (only the asked fields with `doc -> 'F'` when `fields` is set), partition pruning by month; `days()` the same without `doc` | `null` |
| **jdbc** query mode (`query.<kind>` with `:id` and `:asOf`) | a site's own SQL | an optional `history.<kind>` query with `:id`, `:from`, `:to` (documented in `JDBC_QUERIES.md`); without it the default per-day loop of the existing query | from a `generation` column when the query returns one |
| **duckdb** (`DuckDbLayout`, `<domain>.entities`, promoted columns) | a row per entity per date | one query with `BETWEEN` as table-mode JDBC, promoted columns read directly | `null` |
| **mongodb** (`MongoLayout`, a document per entity per date, `day_ids` index) | `_id` per entity and date | one indexed range read over the `_id` range of the entity between the two dates (the `effective` read already finds "the last `_id` of the entity's range"), with a projection when `fields` is set | `null` |
| **aerospike** (`AerospikeLayout`) | a record per entity per date, **an index record per entity listing its dates** | `days()` = the entity's index record (one get), then one batch get of the dates' keys | `null` |
| **redis** (`RedisLayout`) | a compressed document per entity per date, **each entity's days**, a day's current generation | `days()` from the entity's days, then one pipelined `MGET` for the dates; it holds the recent days only, so the router asks Delta for the rest (section 9.3) | 1 |
| **file** (`FileSourcePlugin`, `JsonlDay`: `<root>/<yyyy-MM-dd>/<kind>.jsonl` indexed with byte offsets) | a dated folder per day | `days()` = dated folders in range whose day index holds the id (indexes are cached by `index-cache-mb`); a positioned read per day | `null` (generation = file time) |
| **s3** (`S3SourcePlugin`), **rest**, **feeds**, **news**, **kafka**, **rabbitmq**, **activemq**, **yfinance**, **demo** | current data or a stream | the default; most are undated (`capabilities().dated()` false), so the kind has no daily history unless the pack declares an embedded `series:` (yfinance's documents carry recent prices) | none |

A connector that cannot do better simply does not override `history`. The contract test (section 19.2) holds every one of them to the
same answers.

## 11. Limits, cache and settings

New `@ConfigurationProperties("drishti.history")` class `engine/history/HistoryProperties`, a `LiveSettings` (changes apply without a
restart, as `SearchProperties`), with its defaults in drishti-server's `application.yaml`:

| Setting | Default | Meaning |
|---|---|---|
| `drishti.history.enabled` | `true` | off: the range is not read and the endpoint answers `DRS-1012` |
| `drishti.history.default-days` | `30` | the range when a link or the CLI names none |
| `drishti.history.max-days` | `366` | the most business days one request may span (a pack's `max-days` can only lower it) |
| `drishti.history.max-rows` | `1000` | the most rows returned (embedded series can be long) |
| `drishti.history.max-columns` | `200` | the most columns |
| `drishti.history.timeout` | `10s` | the whole request; days not read by then are `problems` and the answer is `partial` |
| `drishti.history.parallel-days` | `8` | days read at once per request by the default loop |
| `drishti.history.max-concurrent` | `16` | history requests running at once on this server; the next waits, then `DRS-1014` |
| `drishti.history.cache-mb` | `256` | heap for cached answers, one budget for every key and caller signature; the least recently used go first |

**Cache.** `HistoryCache` keeps unmasked, unformatted rows keyed by `(kind, id, from, to, fields, knownAt, dataVersion)`, where
`dataVersion` is `SourceRouter.dataVersion(kind, AsOf.LATEST)` (Delta: the table's version and commit time; a connector that holds
nothing of the kind counts as `none`) followed, in a cluster, by the data versions of the peers the read would ask, as their digests
carry them (`SourceRouter.peerVersion`, `net:<node>{<connector>=<version>,…}`). A new load, here or on a peer, changes the version, so
the cache never serves stale data and needs no expiry. A connector or peer that gives no version (`Optional.empty()`) is not cached, nor
is an answer that failed, is partial or has no day.
Masks and formats are applied on every answer, so an entry of days this node's own sources read serves every user.

**Days a peer answered are the caller's alone** (after the 2.6.6 soak, SOAK2-01). A peer masks the days it sends for the user it acts
for (section 13), so they are not unmasked rows. When a peer answered any day of a read (`SourceHistory.Routed.fromPeer`), the entry is
kept under the key above followed by `|viewer=<signature>`, the caller's security signature (`Principal.securitySignature()`: the
sorted roles and the embedding app, which decide every node's masks and the nodes the caller reaches; the live relay's topic partition
is the same signature). A caller with another signature misses it and reads the peer again for themselves, so an administrator never
gets a viewer's `•••` and a viewer never gets an administrator's raw values. A read whose days were all this node's own is kept under
the shared key, and a lookup tries the shared key first, then the caller's. A caller without a signature (`Viewer.of(redact, masked)`)
is never cached when a peer answered. Before, the first caller's masked days were served to everyone for the life of the entry: in soak 2,
77 administrator histories of peer-held trades showed `PRICE` as `•••` in a column marked `masked: false`, with the price's changes
missing. `purgeCaches` (Admin → Cache) empties it.

**One budget, in real heap** (after the 2.6.6 soak 3, SOAK3-02). The cache is one `HistoryCache` per server: every key, shared or signed,
counts against the same `cache-mb`, and the least recently used entries are dropped to stay under it (`HistoryCacheBoundTest`: forty
signatures of a hundred peer-held trades each stay under the budget). An answer over a quarter of the budget is never kept. The size of an
entry is the heap its days take on a 64-bit JVM (`HistoryCache.estimate`: 56 bytes a field of an object, its value's box and a string's
characters, the day's records; within a few per cent of the measured heap). Before, it counted 48 bytes a node, 2.3 to 3.2 times under
the heap, so `cache-mb: 64` held 150 to 210 MB. What a steady server looks like: the cache grows while new entities and ranges are asked,
then stays at `cache-mb` (Admin → Cache: `bytes` near the budget, `entries` flat); a new load on any node changes the data version and
the old entries leave as new ones come. The other caches a cluster keeps per caller signature are bounded by count: a view's shape
(`drishti.engine.fingerprint-cache-size`, 100,000 entries of about 300 bytes, about 30 MB at most), the effective layouts
(`layout-cache-size`, 10,000, keyed by shape and not by caller), and the map panels' listings and documents (32 and 8 entries, kept
`drishti.panels.map-cache-seconds`, 60 s).

**Docs and test.** Every setting goes into `docs/admin/CONFIGURATION.md`; `drishti-console/tests/test_docs_settings.py` already fails when
a key of `application.yaml` or a `@ConfigurationProperties` binding is not in that document.

## 12. Drishti\*net and super scale

**Today's cluster (2.5.0).** No new routing is needed for the page:

- `ViewForwardFilter.target()` already matches `/api/v1/history/{kind}/{id}` (area `history`, three segments or more) and forwards the
  whole request to a node that renders the kind (`NetRouting.view(kind, principal, dated, entityId)`), retrying the next node once
  (`DRS-5052`, `DRS-5053`).
- When the pack is here but the data is elsewhere, `SourceRouter.history` sends the dates no local connector holds to `Fallback.peers`
  (`PeerSources`, ordered by `NetRouting.dataPeers`, replicas left out). `PeerSource.history` makes **one** call per peer, not one per day:
  a new `GET /api/v1/net/history/{kind}/~?id=&from=&to=&fields=&knownAt=` on `NetDataController`, beside `/net/fetch`. It carries the
  same headers (`X-Drishti-Net-Hop: 1`, `X-Drishti-Net-On-Behalf-Of`, `X-Drishti-As-Of`, `X-Drishti-Known-At`), checks
  `entitlements.requireOpen`, applies the caller's masks at the peer, and asks no further peer (one hop).
- The answers are merged by date; a date two peers both answer (shards that overlap) takes the higher generation.
- A node that holds the kind but is down, suspect or drained cannot be asked. Dates no node that answered holds are then failures of
  that node (`Fallback.downHolders`): with no day read the answer is `502 DRS-5052` naming it, as the view says; with some days read it
  is partial, a `problems` entry with code `DRS-5052` naming it. Never `DRS-1013`, and never cached.
- **A down node counts only for the dates it may hold** (after the 2.6.5 soak, SOAK-01). Each source of a node's digest carries `earliestDate` and
  `latestDate` for the kind (the oldest and newest business dates its table holds), and the last digest heard before the node went down
  is kept. A date outside every one of its sources' ranges cannot be there, so it is not named (`Fallback.downHolders(kind, date)`,
  `NetRouting.downHolders(kind, date)`). A `range=3M` history of a CALYPSO trade asked while the MUREX holder is down, whose lake starts
  on 2026-10-02, is complete: the 54 days before 10-02 are before any lake, as the window's `loadedFrom` says. A source that keeps no
  dates, or a digest without the two dates (a node of 2.6.5 or older), may hold any date, as before.
- **A peer that refuses the connection** (it just died and gossip has not said so yet, or it is restarting and its port is not open:
  `ConnectException`, a reset, no route) is down as far as the read goes (SOAK-02, SOAK-03): the next holder is asked for the same
  dates, and the dates no one answered are `DRS-5052` naming it, `… cannot be reached now: drishti-c (not answering: the connection
  failed)`, for the dates its digest says it may hold. Never `DRS-1003`.
- A peer that is asked but does not answer in time (frozen, overloaded) is a time-out of those days: `504 DRS-1014` *the node qa1-b did
  not answer the history of trade/10000901 within 10 s* when no day was read, else a `problems` entry with `DRS-1014`. Any other failure
  of the peer (it answered with its own error) is about that node too: `DRS-5052` *the node qa1-b that holds trade failed reading the
  history of trade/10000901: …*, as a view says, never `DRS-1003`, which names a source of the server that answered. A date that one
  peer failed and another answered is not failed.

**Super scale (SUPER_SCALE.md section 10, not built).** A slice is `(table, business day, group)`; each slice has owners on the ring
(`hash(table, day, group)`, 64 virtual nodes, 2 owners for the `hot-days`). A history of one entity spans one slice per day, so the days
spread over owners. `HistorySlices` (built with that design, not in 2.6.5) groups the window's days by `owners[0]` of each day's slice
(the group comes from the day's range map, section 9.1 there), sends one `/net/history` per owner with its days, merges, and reads any
owner that does not answer within `forward-timeout` from S3 itself (section 10.3 there: anyone can read anything). 30 days on a ring of
10 servers is at most 10 calls in parallel.

## 13. Security

- **Rights per kind.** `entitlements.requireOpen(principal, kind)` first, as `series` and `diff` do. A part opened in the drawer is the
  same entity; a linked entity opened from it goes through its own view and its own check.
- **Masks on every row.** Each day's document goes through `Entitlements.redact(principal, data)` **before** any column is projected,
  any change is computed or any summary is taken, so a masked field reads as the mask on every row, never shows as "changed", and cannot
  be probed by the headline numbers. `columns[].masked` comes from `Entitlements.masked(principal, field)`. Hidden fields (no role may see
  them) are dropped from the columns entirely.
- **The cache holds unmasked rows** of this node's own days and never leaves the server; masks are applied per answer. Days a peer
  answered arrive masked for the caller and are kept under the caller's security signature only (section 11, SOAK2-01).
- **Peers** apply masks themselves (`NetDataController`) and the asking node applies them again, as type-ahead does ("masks twice").
  A field that reads as the mask on any day, whoever masked it, is `masked: true` in `columns[]` and never in `changed` or the diff's
  changes, even where this node's own rules do not mask it (a peer's `redact` stricter than this node's). A field not masked for the
  caller shows every real change.
- **Export** (section 15.2) goes through the same endpoint, so it is masked the same way; the console never masks.
- **Audit.** Each request is one access-log row (`history`), with the range in the detail, as `series` is today.

## 14. Performance targets

| Case | Target | Reasoning |
|---|---|---|
| 30 days of one trade, Delta, 10K trades a day, laptop, cold (first read since start) | **< 1 s** at the browser; < 400 ms at the server | `days()` reads nothing; 30 id maps of 10K ids are small; 30 row groups at about 20 ms cold, 8 at a time ≈ 80 ms; formatting and masks < 20 ms |
| the same, warm (documents in the doc cache) | < 150 ms at the server | Delta's warm single read is 4.3 ms median (DELTA_CONNECTOR.md); 30 / 8 × 4.3 ≈ 20 ms |
| the same, cached answer | < 30 ms at the server | the cache key needs only `dataVersion`, which is cheap by contract |
| 250 days, Delta, promoted fields only | < 1.5 s cold | column reads of one row group per day, no `doc` |
| 30 days, drishti-iceberg | < 400 ms cold | see below |
| 30 days, Cassandra | < 100 ms (estimate) | one `entity_days` read and 30 point reads of about 5 ms in parallel |

**Iceberg.** The Iceberg builder measured one trade at **54 ms** median (native engine, doc cache emptied; 94 ms for a past date not yet
read) against Delta's **4.3 ms** warm (ICEBERG_CONNECTOR.md section 17 on the branch). Thirty single reads would cost 1.6 s one after
another, or about 300–600 ms eight at a time, and each new day also reads its whole id column for the id map. History must not go that
way on Iceberg: the planned scan (section 10) prunes to one file per day by the manifests' id bounds, reads no id map, and reads only the
needed columns, so the per-day cost is a footer (cached by the native engine) and one row group.

The benchmark (section 19.4) measures these at 10K trades × 30 days and records them in SCALE_STRATEGY.md's history row.

**Measured (2026-10-20, H14).** `HistoryBench` (`drishti-benchmarks`), 10,000 synthetic trades × 30 business days (2026-08-19 ..
2026-09-30, about 6% amended a day) written with Drishti's writer under `target/`, the native Delta engine, 200 random trades, the 30-day
history at the service (`HistoryService.read`, columns, changes and all), laptop, one process, `-Xmx4g`:

| Case | Median | p95 | Target |
|---|---|---|---|
| cold (each trade's documents read for the first time, the table's id maps loaded) | **13.2 ms** | 19.1 ms | < 400 ms |
| warm (documents in the caches) | **0.83 ms** | 1.6 ms | < 150 ms |
| cached answer | **0.29 ms** | 0.49 ms | < 30 ms |
| default per-day loop (the connector's `days()` hidden), cold | 11.1 ms | 18.5 ms | |
| the Delta fast path again, cold, after the default loop | 8.5 ms | | |

The write took 7.6 s. **The fast path is 1.3× the default loop here, not the 3× of section 19.4**: on Delta the default loop already
costs nothing for a date without the entity (its id map, cached, answers "not here" without a read), and in this table every date holds
every trade, so both read the same 30 row groups. The fast path's gain is in the reads it never starts: dates the entity was not loaded,
and stores where a per-date call is a network round trip. The first fast-path run, before the default loop, measured 13.2 ms against the
default's 11.1 ms (an order effect: the file pages were cold for the first run), so the table reports both. Not measured yet: 250 days
of promoted fields only (that read is not built, section 21), and the other stores (H6 and the rest of H5).

## 15. Extras: DIFF, export, CLI, BI, plain words

### 15.1 `TRD X 30 DIFF`

`diff=true` adds `rows[].changes`, each row against the one before, with `DocumentDiff` (the "What changed" engine, nested paths
included, `LIMIT` 2000 per row). The screen (`D`) becomes a change log: one block per changed day, each change on its own line
(`LEGS[FIXED].NOTIONAL 10,000,000 → 12,500,000 +2,500,000`), days with no change left out. A day's block links to
`/compare/{kind}/{id}?from=<previous>&to=<date>` for the full compare page.

### 15.2 Export

| Route (console, `routes/export_routes.py`) | Gives |
|---|---|
| `GET /export/history/{kind}/{id}.csv?days=…&fields=…` | the table as shown: date, then the columns (display values), `changed` as a column; `to_csv` (BOM, CRLF, the formula guard) |
| `GET /export/history/{kind}/{id}.json?…` | the server's answer as it is (section 8) |
| `GET /export/history/{kind}/{id}.xlsx?…` | through `to_xlsx` (one sheet, bold frozen header, numbers stay numbers) |

Names follow `export.filename()`: `1000015-history-2026-09-08-2026-10-19.csv`. The specific routes are declared before
`/export/{kind}/{id}/{panel}.csv`. Row caps are the server's `max-rows`.

### 15.3 CLI

In `tools/drishti.py`, a new group `history` (argparse, `cmd_history_get(a, extra)`, the shared `server_parent()` options):

```text
drishti.py history trade 1000015 --days 30
drishti.py history equity EQ-CSCA --range 3M --fields PRICE,CHANGE_1D
drishti.py history trade 1000015 --from 2026-09-01 --to 2026-09-30 --diff
drishti.py history trade 1000015 --days 30 --changes-only --json
drishti.py history trade 1000015 --days 30 --csv > trade-1000015.csv
```

Text output is a table, newest first, changed cells marked `*`; `--json` prints the answer (`say_json`); `--csv` writes the CSV of
15.2. Exit codes: 0, 1 (problems), 2 (usage), as the other groups. `tools/test_drishti_cli.py` gets its cases. The Python client
(`clients/python/drishti_client.py`) gets `history(kind, id, days=…)`.

### 15.4 A BI data source "history"

A dataset could chart a field over time: `source: {history: {kind: trade, ids: [1000015], fields: [PRICE], range: 3M}}`, giving the
table `(business_date, id, PRICE, …)`. It needs a new `DatasetDef.Source` type `HISTORY`, `DatasetDefParser` (its own `DRS-91xx` code),
`bi/mq/DatasetSources.answer()` and `fingerprint()` (the fingerprint is the history cache key), and `core/model_layer.py` in the console.
The BI time machinery (`bi/dataset/Histories.java`, live-table history with a `ts` column) is the precedent. **It is specified here and
built as step H12**, which was left out of 2.6.5 and is not built yet (release not decided); nothing else depends on it.

### 15.5 Plain words

`PhraseParser` (deterministic, no model) learns one more form before searches: *kind word, id, time phrase*. Time phrases: `last N days`
→ `N`, `last N weeks|months|years` → `NW|NM|NY`, `last month` → `FROM <first of the previous month> TO <its last day>`, `this month` →
`MTD`, `this year`, `ytd` → `YTD`, `since <date>` → `FROM <date>`, `between <date> and <date>`, `from|since <date> to|until <date>`
(two dates are put in order), `past 30 days`.
The id is the word that names one entity of the kind (`Vocabulary.entityId`: its id in any case, or the one id ending `-<word>`, so `audusd`
is `FX-AUDUSD`), even when it is also a field's value (`EQ-CSCA`, the ticker); else a word with a digit or a dash that is no field's value.
Without a kind word, an id that names one entity of one kind (`Vocabulary.kindOfEntity`) followed by a range or a time phrase is that
entity's history (`1000015 30`, `BOOK-RATES-3 last week`); followed by what reads as a range but is none it is `DRS-4022` with what to type
(`1000015 -5`, `1000015 since yesterday`). A kind word and one id with no time phrase open that entity (`fx audusd` is `FX FX-AUDUSD`).
A search reads one business date, so a time phrase in one (`trades in BOOK-RATES-3 last week`) is never dropped silently: it is listed as
not understood, with the step *left out: a search reads one business date (the date box); for one entity over time name it with the time
phrase alone (BOOK-RATES-3 last week)*, and the console does not run the search on its own. `since <date>` in a search stays a condition
on the kind's date (`trades in BOOK-RATES-3 since 2026-01-01`), as before (QA1-207).

```text
trade 1000015 last month                 TRD 1000015 FROM 2026-09-01 TO 2026-09-30
equity EQ-CSCA last 3 months             EQ EQ-CSCA 3M
fx audusd since 2026-10-01               FX FX-AUDUSD FROM 2026-10-01
trade 1000015 since 2026-10-01 until 2026-10-10   TRD 1000015 FROM 2026-10-01 TO 2026-10-10
fx audusd                                FX FX-AUDUSD
1000015 30                               TRD 1000015 30
what changed on trade 1000015 this week  TRD 1000015 FROM 2026-10-19 DIFF
```

The console's `/go` already sends a failed command of two or more words to `/s?words=`; the search page shows *"Read as
`TRD 1000015 FROM 2026-09-01 TO 2026-09-30` — Enter to open"*, with the steps, as phrases are explained today, and opens it directly
when the phrase was read fully (no ignored words).

## 16. Errors

New codes in `drishti-common` `ErrorCode` (none of them used before):

| Code | Name | HTTP | When | Message (example) |
|---|---|---|---|---|
| `DRS-1012` | `HISTORY_NOT_KEPT` | 404 | the kind has no daily history: history off for it (`enabled: false`), or every connector of the kind is undated and no `series:` is declared | "equity keeps no daily history here: market-store holds today's data only" |
| `DRS-1013` | `HISTORY_NO_DATA` | 404 | the entity exists but has no data in the window | "TRD 1000015 has no data from 2026-01-02 to 2026-01-30; its days with data run from 2026-06-20 to 2026-10-19" |
| `DRS-1014` | `HISTORY_TIMEOUT` | 504 (or 200 with `partial`) | a connector, or a Drishti*net peer (an HTTP time-out of its one call), did not answer within the budget: the whole request when no day was read, otherwise a `problems` entry | "trade-endur did not answer 4 of 30 days within 10 s" |
| `DRS-2061` | `HISTORY_INVALID` | 422 | the pack's `history:` block is wrong | "history.equity.headline: PRICE2 is not one of chart" |
| `DRS-4021` | `HISTORY_RANGE_TOO_LARGE` | 400 | more than `max-days` (the kind's or the server's) | "366 business days at most (asked 500): narrow it with FROM … TO" |
| `DRS-4022` | `HISTORY_BAD_RANGE` | 400 | `TO` before `FROM`, a future `FROM`, an unreadable date | "FROM 2026-10-15 is after TO 2026-10-01"; "FROM 2026-10-30 is in the future (the business date is 2026-10-20)" |

A `knownAt` on a store without time travel stays `DRS-1007`; an unknown entity stays `DRS-1001`; a Drishti*net node that holds the kind
and cannot be reached, refuses the connection or fails its read is `DRS-5052` naming it, only for the dates it may hold (section 12); a
peer's time-out is `DRS-1014`. `DRS-1003` names only a source of the server that answered, so the console never sets a healthy server aside
for another node's failure (DRISHTI_NET.md 11.1). The server logs `DRS-5052`, `DRS-5053` and `DRS-1014` as one short WARN line per change
of what they say about a node, with a count once a minute, never with a stack trace (after the 2.6.5 soak, SOAK-06). Each new code goes into API_GUIDE's
"Error code table" and TROUBLESHOOTING's "Error codes" table in the same push; `drishti-console/tests/test_docs_error_codes.py` fails
otherwise (and checks `DRS-2061` in TROUBLESHOOTING). The console shows each on the history page as the view's `missing.html` does,
with the fix: the largest allowed range as a link, the first day with data as a link.

## 17. The data flow

```text
 Browser                 Console (FastAPI)                 Server (Spring)                                    Connectors
 ┌────────────────┐ GET  ┌──────────────────────┐ POST    ┌───────────────────────────────┐
 │ TRD 1000015 30 │────▶ │ /go                  │───────▶ │ CommandController.resolve     │
 │ <GO>           │      │ (terminal_routes.py) │ command │  CommandMemory.expand (alias) │
 └────────────────┘      │                      │◀─────── │  HistoryRange.trailing        │
                         │ 303 → /v/trade/      │ history │  exists() / pick as today     │
                         │  1000015/history     │         └───────────────────────────────┘
 ┌────────────────┐ GET  │  ?days=30            │
 │ history page   │────▶ │ history route        │  GET    ┌───────────────────────────────┐
 │ (history.html, │      │  backend.history()   │───────▶ │ ViewForwardFilter (net: owner)│──▶ peer node
 │  history-page  │      │  asof headers        │ /api/v1 │ HistoryController.rows        │
 │  .js)          │      └──────────────────────┘ /history│  requireOpen · HistorySpec    │
 │                │◀──── HTML + JSON (rows) ──────────────│  HistoryWindow (BusinessDates)│
 └────────────────┘                                       │  HistoryCache (dataVersion)   │
                                                          │        │ miss                 │
                                                          │  SourceRouter.history ────────┼──▶ ConnectorInstance.history
                                                          │   read order, coverage,       │      ├─ delta: days() + row groups
                                                          │   budget, Fallback.peers ─────┼──▶   ├─ cassandra: entity_days + reads
                                                          │        │                      │      ├─ jdbc: one BETWEEN query
                                                          │  HistoryRows (changed,        │      └─ default: HistoryReads.perDay
                                                          │   versions, summary, diff)    │           (fetch(ref, AsOf) × N, 8 at once)
                                                          │  Entitlements.redact per row  │◀── HistoryRead(days, covered, failed)
                                                          └───────────────────────────────┘
```

The same service answers `drishti.py history` (REST), the export routes, the field dialog (`/series`) and, later, the BI source.

## 18. Build steps H1..H14

Two builders in parallel. **Builder A**: server, SPI, connectors (Java). **Builder B**: command, console, CLI (Java in
`engine/command` and `engine/search/PhraseParser` only, Python, JavaScript, CSS). The contract between them is section 8, fixed in H1
as a fixture file; neither touches the other's files. Each step is one push with its tests and docs; the drill runs once, at the end.

| Step | Builder | What | Done when (its own tests) | Docs in the same push |
|---|---|---|---|---|
| **H1** | A | The contract: `HistoryRequest`, `HistoryDay`, `HistoryRead`, `SourcePlugin.history` and `days` defaults, `HistoryReads.perDay`; `ConnectorInstance` delegation; the fixture `drishti-testkit/src/main/resources/history/trade-1000015-30.json` (and an equity one); `DatedSourceContract` history cases | every plugin that extends `DatedSourceContract` passes the new cases with the default | `docs/connectors/CONNECTOR_DEVELOPER_GUIDE.md` (the SPI) |
| **H2** | A | `engine/history`: `HistoryProperties`, `HistoryWindow` (ranges, business days, baseline), `HistoryRows` (latest per day, `changed`, parts, summary, diff), `HistoryCache`, `HistorySpec` (+ `DRS-2061`), `HistoryService`; `SourceRouter.history` (read order, coverage, budget, partial) | unit tests (section 19.1) | CONFIGURATION (settings), API_GUIDE and TROUBLESHOOTING (codes) |
| **H3** | A | `HistoryController.rows` (`/{kind}/{id}` and `/~`), the new `ErrorCode`s, masks, `AccessRecorder.ACTIONS`; `series` re-pointed at the service (260 becomes `max-days`); `HistoryDefinitions` reading `history:`; deploy and `sutra lint` checks | controller tests with a fake router: masks per row, rights, every error, the JSON equals the fixture's shape | API_GUIDE endpoint, PACK_DEVELOPER_GUIDE `history:` |
| **H4** | A | Drishti\*net: `PeerSource.history`, `NetDataController` `/net/history`, merge by date | in-process cluster test (two nodes, a kind's dates split across them) | NET.md routing table |
| **H5** | A | Fast paths: delta (with promoted-column row read and versions), jdbc table and query mode, duckdb, mongodb, aerospike, redis, file | the contract test per plugin with the fast path, plus "reads no document when the fields are promoted" for delta | each connector's document in `docs/connectors/` |
| **H6** | A | Fast paths on the merged branches: drishti-iceberg / iceberg-external (planned scan), cassandra (`entity_days`) | their contract tests; starts only after those branches are on develop | ICEBERG_CONNECTOR, CASSANDRA_CONNECTOR |
| **H7** | B | The command: `HistoryRange`, `CommandController.resolve` (range after alias expansion, before pick), `CommandResponse.history`, `SuggestionService` range suggestions | `HistoryRangeTest` (every row of section 4.2's table), `CommandControllerTest` cases | USER_GUIDE command line |
| **H8** | B | Console: `/v/{kind}/{id}/history` route, `/v/…?days=` redirect, `/go` redirect, `backend.history()`, `terminal/history.html`, `web/static/js/history-page.js`, `web/static/css/history.css`; document shape, keys, changes only, drawer, open the day | `FakeBackend.history` from the fixture; route and template tests; `test_contrast.py` passes | USER_GUIDE history section |
| **H9** | B | Market shape: chart, headline numbers, range buttons, crosshair, embedded series | template tests with the equity fixture | USER_GUIDE |
| **H10** | B | DIFF mode, export routes (csv, json, xlsx), share links and pick lists carrying the range | export tests (names, BOM, formula guard, masked values as served) | USER_GUIDE, API_GUIDE "console's own JSON routes" |
| **H11** | B | `drishti.py history`, the Python client's `history()`, `PhraseParser` time phrases | `test_drishti_cli.py` cases, `PhraseParserTest` cases | CLI_GUIDE, USER_GUIDE plain words |
| **H12** | A | BI source `history` (not built; was left out of 2.6.5, section 15.4) | dataset parser and `DatasetSources` tests | DATASETS.md |
| **H13** | A + B | The shipped packs' `history:` blocks (section 6.3); help card; tutorial 21; CHANGELOG | `sutra lint` of every shipped pack clean | PACKS, tutorial 21, help |
| **H14** | B, then both | Browser test and the benchmark (section 19.3, 19.4); then the release drill, once | both green under the 10K-trade cap | SCALE_STRATEGY history row, RELEASE_NOTES |

Order: H1 first (a day; it fixes the contract). Then A runs H2 → H3 → H4 → H5 (→ H6 when the branches land) and B runs H7 → H8 → H9 →
H10 → H11 at the same time against the fixture. They meet at H13 and H14, when B's console points at A's real endpoint. At most two
builders, one heavy test process each, as the house rule says.

## 19. Tests

### 19.1 Unit

| Test | Checks |
|---|---|
| `HistoryRangeTest` (engine) | every row of section 4.2: bare number, `30D`, `2W`, `3M`, `1Y`, `YTD`, `MTD`, `QTD`, `FROM … [TO …]`, `DIFF`, case, `<GO>`; never the first word; ids with spaces; operators and `*` stay searches |
| `HistoryWindowTest` | data days vs calendar days over the business calendar (weekends, holidays), the baseline day, `max-days` from pack and server, `DRS-4021`, `DRS-4022` |
| `HistoryRowsTest` | latest version per day, snapshot lookback days skipped, effective days carried and marked, `changed` against the baseline, `ignore-changes`, parts counts, summary (high/low with dates, change %), diff rows equal `DocumentDiff`'s |
| `HistorySpecTest` | the schema of section 6.1, defaults, every `DRS-2061` problem, unknown keys as warnings |
| `HistoryCacheTest` | a new `dataVersion` misses; no version, no caching; size bound |
| `SourceRouterHistoryTest` | two connectors in read order split a range by coverage; peers asked only for the rest; a slow connector gives `partial` with `DRS-1014`; the budget is shared |
| `HistoryControllerTest` (server) | masks on every row (a masked field is never `changed` and not in the summary), `requireOpen`, every error code, the answer's shape equals the fixture's (field by field), `~` ids |
| console `test_history_routes.py` | `/go` redirects, `/v/…?days=` redirect, pick list with `days`, the template renders both shapes from the fixtures, missing data and `DRS-1012` pages |
| `test_history_export.py` | names, BOM and CRLF, the formula guard, the JSON as served |
| `tools/test_drishti_cli.py` | `history` text, `--json`, `--csv`, `--changes-only`, exit codes |

### 19.2 Contract across plugins

`drishti-testkit` `DatedSourceContract` (extended today by the delta, jdbc (two), redis, aerospike, file, duckdb, iceberg (two),
mongodb tests and the `DayFolderContractTest` example) gets history cases over its fixture `ROWS` (D1, D2, D3; `T-1` restated on D3):

- `historyOfASnapshotKindHasOneRowPerDateItHolds`: `T-1` from D1 to D3 gives 3 days, mtm 100, 110, 125; `T-3` gives D1 only.
- `historySkipsDatesAnsweredByAnEarlierDate`: a snapshot date with no partition is not a row.
- `historyOfAnEffectiveKindListsTheChangeDates`: `CP-X` gives D1 and D3 (`A`, `A-`).
- `historyAsksOnlyTheFields`: `fields={mtm}` gives documents with `mtm` (and nothing a mask would need is lost: the server masks before projecting).
- `historyDaysAgreeWithHistory`: when `days()` is present it lists exactly the dates `history` returns.
- `historyMatchesFetchDayByDay`: for every date, the day's document equals `fetch(ref, AsOf.of(d))`. This one keeps every fast path honest.
- `historyWithKnownAtFollowsTimeTravel`: when `timeTravel()` is true, a `knownAt` before D3's load gives `T-1` without `restated`.

Cassandra's and the new Iceberg plugins' tests extend the class when their branches land.

### 19.3 Browser

`drishti-console/tests/test_history_browser.py` (Playwright, as the other `*_browser.py`), on the demo stack with the sample packs:
type `TRD 1000015 30` in the command line, Enter; the page shows the dates in the header and 30 rows (or the days the sample holds);
`↓` `↓` moves the selection (`aria-selected`); `C` hides unchanged days and the count in the header changes; `Enter` opens the view of
that day with the pinned banner; `Esc` returns. `EQ EQ-CSCA 3M` draws the chart and the headline numbers; `1` switches to 1M and the URL
follows. Repeated in `light` and `terminal` themes. It waits for the page's own "loaded" marker (`data-history-ready`), never fixed sleeps,
as the 2.5.0 flaky-test work requires.

### 19.4 Performance

`drishti-benchmarks` `HistoryBench`: **10K trades × 30 business days** in a Delta table under the build's temporary folder (never
`data/`, never more than 10K trades locally), cleaned at the end. It measures, for 200 random trades: cold and warm 30-day history at the
service, the cached answer, 250 days with promoted fields only, and the default loop against the fast path (the fast path must be at
least 3× faster cold). It fails when the warm median passes 150 ms or the cold p95 passes 400 ms. One heavy job at a time.

*As built:* a plain `main` (not JMH), run as
`java -Xmx4g -cp <the module's class path> com.ash.drishti.benchmarks.HistoryBench [trades=10000] [days=30] [sample=200]`; it refuses more
than 10,000 trades, deletes its folder at the end and exits 1 on a missed target. The numbers are in section 14.

## 20. Docs to update when it is built

| Document | What |
|---|---|
| `docs/guides/USER_GUIDE.md` › The command line | the range after the id (section 4), the history page, keys, changes only, DIFF, plain words |
| `docs/guides/API_GUIDE.md` | `GET /api/v1/history/{kind}/{id}` with the JSON of section 8; `/net/history`; the codes in "Error code table"; the console's export routes |
| `docs/guides/CLI_GUIDE.md` | `drishti.py history` |
| `docs/guides/PACKS.md`, `docs/guides/PACK_DEVELOPER_GUIDE.md` | the `history:` block (section 6), with the shipped packs' blocks as examples |
| `docs/admin/CONFIGURATION.md` | `drishti.history.*` (test_docs_settings.py) |
| `docs/guides/TROUBLESHOOTING.md` | the six codes, each with its fix (test_docs_error_codes.py) |
| `docs/admin/NET.md` | `/net/history` in the routing section |
| `docs/connectors/CONNECTOR_DEVELOPER_GUIDE.md` and each connector's document | the SPI methods and each connector's fast path |
| help cards | `drishti-console/config/help.yaml` (a "History" guide under the command line category) and the trading and market-data packs' `config/help.yaml` |
| `CHANGELOG.md`, `RELEASE_NOTES.md` | each step's line; the release note for 2.6.5 |
| `docs/architecture/SCALE_STRATEGY.md` | the history row with the new measurements |
| this document | "as built" notes, as WRITE_BACK.md and DRISHTI_NET.md keep them |
| **tutorial 21** `docs/tutorials/21-an-entity-over-time.md` | hands-on and complete: (1) `TRD 1000015 30` in the UI, the same with `drishti.py history trade 1000015 --days 30`, and the `history:` YAML that shapes it; (2) changes only and DIFF (UI, `--changes-only`, `--diff`); (3) open a day (UI; `drishti.py view get trade/1000015 --date …`); (4) `EQ EQ-CSCA 3M`, the chart and the numbers (UI, CLI, YAML `chart:`, `headline:`); (5) add `history:` to your own pack and `sutra lint` it (YAML, CLI, Admin → Packs); (6) export (UI, `--csv`); (7) plain words; (8) limits and errors (`max-days`, `DRS-4021`). Every step shows the UI, the CLI where there is one, and the YAML, with screenshots in `img/` |

## 21. Where the code forced a change from the agreed design

| Agreed | In this design | Why |
|---|---|---|
| `GET /api/v1/entities/{kind}/{id}/history` | `GET /api/v1/history/{kind}/{id}` | `HistoryController` already owns `/api/v1/history/{kind}/{id}/series` and `/diff`; `ViewForwardFilter`, `AccessRecorder` and `NetRouting` already treat the `history` area; `/entities/{kind}/{id}/raw` is the raw document. One area for history keeps forwarding, audit and rights in one place. |
| link `/v/trade/1000015?days=30` | canonical `/v/trade/1000015/history?days=30`; the short form redirects to it | the view route `/v/{kind}/{id_:path}` renders panels; history is its own page (as `/about` and `/panels` are sub-routes); the short link still works |
| the parser takes a third word | the range is taken off **after** the alias is expanded and **after** the whole rest is tried as an id | `CommandParser` reads everything after the mnemonic as the id, and ids may hold spaces; `CommandMemory.expand` appends trailing words |
| `history(kind, id, from, to, fields)` | `history(HistoryRequest)` returning `HistoryRead`, plus `days(ref, from, to)` | the read also needs `knownAt`, "the last N days with data", a budget, and must say which dates it covers so the router can ask the next connector (Redis in front of Delta); `days()` lets Delta, Cassandra, Aerospike, Redis and files list dates without reading |
| a row cap and a timeout | also `parallel-days` and `max-concurrent` | the existing series fires every day's read at once with no bound |
| market kinds from daily documents | also an embedded `series:` | the shipped market samples keep their history inside today's document (`HISTORY: [{DATE, CLOSE}]`) |
| Cassandra: `entity_days` gives the days | it gives the days; the documents still need one point read per day | `business_date` is part of the data table's partition key |
| default columns: top-level scalars | key columns first, empty columns hidden | a trade has about 45 top-level scalars (`config/packs/trading/samples/trade.jsonl`) |
| *As built (H1):* `HistoryRequest(ref, from, to, fields, knownAt, lastDays, budget)` | `HistoryRequest(ref, from, to, dates, fields, knownAt, lastDays, budget, parallelDays)` | a plugin has no calendar, so the business dates travel in the request (`dates`, empty means every calendar day); the default loop's parallelism is the server's `parallel-days`, so it travels too |
| *As built (H5):* Delta: `days()`, a promoted-column row read, and `versions` from the log | `days()` only | the demo came first; with `days()` a history reads only the dates that hold the entity. The column-only read (no `doc` decode) and the per-day version count are still to build, so Delta's `versions` is `null` |
| *As built (H3):* effective kinds: every business day a row, carried days marked | the change days only | a source's history leaves out a date answered by an earlier date's document, so the rows of an effective kind are its change dates; `carried` is always false. Filling the days between is a later step |
| *As built (H14):* the fast path at least 3× the default loop | 1.3× on Delta | the default loop is already cheap on Delta (section 14) |
| *As built (H3):* `series:` only when no source keeps daily documents | also to fill the days before the oldest stored day; `window.loadedFrom` | the demo cluster's market lake keeps 7 days: `EQ EQ-CSCA 3M` drew 7 points and said "no data on 55 days" |
| *As built (H3):* `window.to` is the date box | `data-days` ends at the newest day with data; `loadedTo` added | found in the first integration run: a lake loaded up to Friday, read on Tuesday, said "no data on 2 days" for Monday and Tuesday, which are only not loaded yet |
| *As built (H1):* `window.missing` only for `calendar` and `between` | filled in every mode | the document screen's header lists the days with no data for a bare number too (section 5.2: "no data on 2026-09-14"); the fixture's 30 rows span 31 business days |

## 22. Open questions

All five were decided by the product owner on 2026-10-20, before the build started.

1. **"As first reported."** Should a mode show each day as it was known at the end of that day (bitemporal, for audit), beside "latest"
   and "known at K"? It needs one time-travel layout per day on Delta and Iceberg: affordable for 30 days, not for 366.
   **Decided: later, not in 2.6.5.** 2.6.5 has "latest" and "known at K" only.
2. **Derived kinds** (`counterparty-trades`, `portfolio-trades`): their pages are rebuilt from every member trade per day. History is off
   for them in the shipped packs; turn it on later with a per-day page cache?
   **Decided: off in the shipped packs** (`enabled: false`); a per-day page cache is not planned yet.
3. **The Build workbench:** a History tab to edit `history:` with a preview, now or with 2.7.0's write-back forms?
   **Decided: with write-back's forms (2.7.0 when decided, now 2.8.0).** In 2.6.5 the block is edited as YAML and checked by `sutra lint` and the deploy check.
4. **Ranges over a pick list** (`TRD desk=rates 30`: many trades over time) are BI's job; keep them out of the command for good?
   **Decided: left to BI** (the `history` data source, section 15.4). The command takes a range for one entity only.
5. **Tutorial numbering:** the ask names tutorial 21; 20 is not used on develop today. Keep 21 (20 left for 2.6.0's tutorial)?
   **Decided: 21** (`docs/tutorials/21-an-entity-over-time.md`).

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
