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
# Chart any table

Status: **design, not built.** Release **2.6.7** (decided by the product owner, 2026-10-20), beside BI runs
([BI_RUNS.md](BI_RUNS.md)) and the two Drishti\*net transport items ([DRISHTI_NET.md](DRISHTI_NET.md), *Planned, 2.6.7*). Written
2026-10-20 from the agreed feature, checked against the console and server code on `develop`. Builds on the Pivot tab
(`pivot.js`, `pivot-engine.js`, `pivot-grid.js`; [USER_GUIDE › The Pivot tab](../guides/USER_GUIDE.md#the-pivot-tab-slice-a-table-your-way)),
the chart panels (`charts.js`, [PANELS.md](../guides/PANELS.md)), the BI visuals ([REPORTS.md](../guides/REPORTS.md) section 3) and
history queries ([HISTORY_QUERIES.md](HISTORY_QUERIES.md)).

## Contents

1. [The ask](#1-the-ask) · 2. [What exists already](#2-what-exists-already) · 3. [Principles](#3-principles) ·
4. [Where it appears](#4-where-it-appears) · 5. [UI sketches](#5-ui-sketches) · 6. [Chart types and settings](#6-chart-types-and-settings) ·
7. [Smart defaults](#7-smart-defaults) · 8. [The component and its API](#8-the-component-and-its-api) ·
9. [The settings model and saved arrangements](#9-the-settings-model-and-saved-arrangements) · 10. [What it charts: rows, filters, masks](#10-what-it-charts-rows-filters-masks) ·
11. [Big tables: chart all N](#11-big-tables-chart-all-n) · 12. [Actions: export, save, promote, open in BI](#12-actions-export-save-promote-open-in-bi) ·
13. [Keys](#13-keys) · 14. [Themes and accessibility](#14-themes-and-accessibility) · 15. [Edge cases](#15-edge-cases) ·
16. [Endpoints](#16-endpoints) · 17. [Settings](#17-settings) · 18. [Build steps C1..C10](#18-build-steps-c1c10) · 19. [Tests](#19-tests) ·
20. [Docs to update when it is built](#20-docs-to-update-when-it-is-built) · 21. [What the real code changed](#21-what-the-real-code-changed) ·
22. [Open questions](#22-open-questions)

## 1. The ask

The product owner, 2026-10-20: **every table can be charted.**

* **Where:** a **Chart** icon in every table's heading and a key (**K**). It opens a wide popup (full screen on a phone) with the chart on top
  and its settings beside it; closing returns to the table.
* **Types:** line, bar (grouped or stacked), area, pie or donut, scatter, combo (bars and lines).
* **X:** one column; a date column makes a time axis, a text column makes categories.
* **Y:** one or more series, each with its column, an aggregation for a repeated X (sum, average, count, min, max), the left or right axis
  (two axes for different units), and its own type in a combo.
* **Split by:** an optional category column turns a series into one series per value.
* **Sort and Top N:** by X or by value; Top N with *Others*; a pie has about 12 slices at most.
* **Smart defaults:** it opens already drawn (X = the first date column, else the first text column; Y = the first numeric column).
* **What it charts:** what the table holds, with the same filters, business date and masks. A masked column cannot be charted.
* **Big tables:** when the table shows fewer rows than exist, it offers **Chart all N**, aggregated on the server (the pivot's server
  path for searches; BI runs when heavy).
* **Themes and accessibility:** all ten themes (tokens only, `test_contrast`); operable by keyboard; a text summary for screen readers
  and **View as table**.
* **Actions:** export PNG, SVG, and the chart's data as CSV or Excel; **Save** as the user's own arrangement for that table; for authors,
  **Promote to Sutra** (a chart panel, through review) and **Open in BI** (a report visual).
* **Reuse:** the pivot engine's aggregation and ECharts; **one** shared component, `table-chart.js`, used by every kind of table.

## 2. What exists already

| Piece | Where | What it gives this design |
|---|---|---|
| The table enhancer | `web/static/js/tables.js` | Every table that is not `data-plain` (table and ladder panels, search results, pick lists, admin lists) gets sort, column filters (`colfilter.js`), paging and keys. It builds the heading strip, so the Chart icon goes there **once** for all of them. Its keys are bound on the focused table and use only arrows, PgUp/PgDn, Home/End and Enter: **K is free**. |
| Sutra table cells | `web/templates/_macros/panels.html` (`table`, `drill`, `pivot_table`) | Cells are **display text** (`cell_text(c)`, e.g. `1.5m`, `−30,205,543`); a column is only flagged `numeric`. Raw values are not in the page. A table cut at its `limit` says `d.more` in its footer. |
| Panel records | `GET /api/pivot/records/{kind}/{id}/{panel}` → `ViewController.records` → `PivotBinder.records` | Every row of a panel up to `drishti.pivot.max-records` (50,000) as **raw values**, with the view's business date and masks (a masked field reads `•••`), `total` and `truncated`. **Today only for panels whose Sutra says `pivot:`**, and only the pivot's fields. |
| The pivot engine | `web/static/js/pivot-engine.js` (`window.drishtiPivotEngine`, also `module.exports` for node tests) | `cube(records, {rows, columns, values, filters})` with `sum`, `count`, `avg`, `min`, `max`, `distinct`; keys sorted as the server's `PivotCube` sorts them (numbers by value, natural text order, `(blank)` last); limits 2,000 row keys and 200 column keys; `format` by the Sutra's format names. Exactly the "aggregate Y by X, split by S" this feature needs. |
| The pivot grid and chart | `web/static/js/pivot-grid.js` `chart(cube, st, kind, tokens)` | ECharts options for bar, line and heatmap from a cube, coloured from theme tokens, SVG renderer. It has no time axis, no second axis, no combo, no stacking, no pie or scatter, and it cuts at 200 lines and 40 series. |
| The search pivot | `POST /api/pivot/search/{kind}` → `SearchPivotController` (`/api/v1/search/pivot/{kind}`) | The **same cube shape** computed on the server over every entity the search matches on the business date, masked for the caller, for kinds whose pack says `pivot:`; columns only, unless `"documents": true`. |
| Saved pivots | `/api/pivot/saved/panel/{sutra}/{panel}`, `/api/pivot/saved/search/{kind}` → `SavedPivotController` (`/api/v1/me/pivots`) | Per-user documents in the `PreferenceStore` namespace `pivots`, replicated to every node by `StateSync` (namespaces `settings`, `layouts`, `pivots`, `workspaces`), forgotten on every console worker through `core/user_stamps.py`; promotion to the Sutra's next version with a diff (`SutraPivotEditor`, `SutraGovernance`, `core/sutra_diff.py`). |
| Chart panels | `web/static/js/charts.js` (`window.drishtiCharts`) | `tokens(root)` reads `--d-*` from `<html>` or a shadow host, `palette(t)` (8 tokens), `compact()` numbers, `axis()`, `tooltip()`; a "Data" table under every chart for keyboards, screen readers and print; redraws on a theme change. |
| Sutra chart kinds | [PANELS.md](../guides/PANELS.md): `line`, `area`, `hbar` (`shape: donut`), `scatter`, `histogram`, `waterfall` | One series per kind (or `area`'s list of bands); **no grouped or stacked bars, no combo, no second axis, no split, no Top N**. |
| BI visuals | `bi-report.js`, `bi-grids.js`, REPORTS.md section 3 | Tables and matrices with raw answers (`columns` with `type` and `masked`, `rows`; BI_MODEL_SPEC 5.2), *first 1,000 of N* footers (`table-rows`), **Show as a table** / **Show as a chart** (`data-act="as-table"`) for chart visuals, **Show the rows behind this visual**. Visual types `bar` (horizontal), `column` (`stacked`, `percent`), `line`, `area`, `combo` (`values` as columns, `secondary` as lines on a second axis), `scatter`, `pie`, `donut`. |
| BI runs | [BI_RUNS.md](BI_RUNS.md), `POST /api/v1/bi/runs` | Every BI question through one pipeline: inline within about 2 s, otherwise a visible run; `interaction`, `supersedes`; results reused by fingerprint, data version and security signature. |
| History | `history-page.js`, `terminal/history.html`, `/export/history/{kind}/{id}.{csv,xlsx,json}` | One row per business day (at most `max-days`, 366); a `data-plain` table with its own document-level keys: **`k` is "previous day"** (vim style), `j` next, `C D E R P Y T 1 3 6 ?`. The market shape already draws one line (P cycles the field). |
| Exports | `POST /export/grid.csv`, `POST /export/grid.xlsx` (`routes/export_routes.py`) | A grid the page computed (`{name, header, rows}`) as CSV (formula-safe) or Excel (bold frozen header, numbers as numbers). |
| Dialogs | native `<dialog>` with `showModal()` (`history.js`, `share.js`, `colfilter.js`), a phone sheet in `collab.css`, focus trapped and returned (`share.js`), `zoom.js` `OVERLAYS` includes `dialog[open]` | A popup that Escape closes before the zoom, inside a ShadowRoot for an embedded view (the pivot's `layer`). |
| Keys elsewhere | `bi-report-board.js` | **K toggles kiosk** on a report with pages (document level, any focus). `zoom.js`: Z zoom, Shift+Z full screen. `tables.js`: Alt+Shift+X clears filters. `view.js`: F1–F10 and Alt+Shift+digits. |

## 3. Principles

1. **One component, many adapters.** `table-chart.js` draws, configures, saves and exports; each kind of table hands it a small
   *source adapter* (section 8). No table type has its own chart code.
2. **Raw values, never display text,** wherever the server can give them. A table's cell `1.5m` is a rounded display; charting it would
   draw wrong numbers. Display text is a last resort for tables with no raw source (admin lists), and the chart says so.
3. **The same engine on both sides.** Rows in the browser go through `pivot-engine.js`; rows on the server through `PivotCube` (the search
   pivot) or the BI engine (BI runs). The chart consumes the cube; it never aggregates on its own.
4. **What the table shows, unless asked.** The chart opens on the rows the table holds, with its filters; **Chart all N** is an explicit
   choice with its cost said up front.
5. **Masks hold twice.** The server masks before rows leave it; the component refuses a masked column. A chart never makes a hidden value
   readable (a total of masked values included).
6. **No new aggregation endpoint.** Each table type's existing server path is reused (section 11).
7. **Tokens only.** Every colour is a `--d-*` token through `charts.js` `tokens()`; no literal colour, no inline style (CSP).

## 4. Where it appears

| Table | Renderer | Icon placed by | Raw rows from | Chart all N | Save identity | Promote | Open in BI |
|---|---|---|---|---|---|---|---|
| Sutra `table`, `ladder` (and a drill's current level) | `panels.html` + `tables.js` | `tables.js` heading strip | `/api/pivot/records/…?for=chart` (C3) | records beyond `limit`, up to 50,000 | `panel:<sutra>/<panel>` | **yes** (authors) | no (section 12.4) |
| A Sutra `pivot` table (`by: [a, b]`) | `panels.html` `pivot_table`, `tree-rows.js` | `tables.js` | its rows as shown (already aggregated) | no | `panel:<sutra>/<panel>` | yes | no |
| The Pivot tab | `pivot.js` | not offered: it has its own chart menu | | | | | |
| Search results and pick lists | `terminal/search.html` + `tables.js` | `tables.js` | the page's rows (search answer) | `/api/pivot/search/{kind}` when the pack says `pivot:` | `search:<kind>` | no | when a BI dataset serves the kind |
| History | `terminal/history.html` + `history-page.js` (`data-plain`) | `history-page.js` (beside **Export**) | `/export/history/{kind}/{id}.json?{range}` | the whole range (≤ 366 days, in the browser) | `history:<kind>/<shape>` | no | no |
| BI table, matrix, "rows behind", a chart visual shown as a table | `bi-grids.js`, `bi-matrix.js`, `bi-report.js` | the visual's action bar (`mk('bi-chart', 'bar-chart', …)`) | the visual's answer | **BI runs** | `bi:<report>/<page>/<visual>` | no | **yes** (authors) |
| Other tables (admin lists, a chart panel's Data table) | `tables.js` | `tables.js` | the cells' text (`colfilter.js` value parser) | no | none (not saved) | no | no |

A table with `data-no-chart` (a Sutra's `chart: false`, a form laid out as a table, `data-plain` tables other than history) has no icon.
A table with no chartable column (section 15) shows the icon disabled with the reason as its title.

## 5. UI sketches

### 5.1 Desktop (wide popup, about 92 vw × 86 vh, at least 960 × 600)

```
┌─ Chart · Cash flows (TRD 1000015 · 2026-10-20) ─────────────────────────────────────────────────── [?] [✕] ┐
│ ┌─ chart ──────────────────────────────────────────────────────────────┐ ┌─ settings ────────────────────┐ │
│ │  AMOUNT (sum)                                         ■ USD ■ EUR ─ PV│ │ Type  [Line][Bar][Area][Pie]  │ │
│ │  4.0m ┤        ██                                         ─╮          │ │       [Scatter][Combo]        │ │
│ │  2.0m ┤   ██   ██ ▓▓    ██                     ╭─────────╯  ┤ 1.2m    │ │ Bars  (•) grouped ( ) stacked │ │
│ │     0 ┼───██───██─▓▓────██───▓▓──────────────╯─────────────┤ 0.6m    │ │ X     [PAY_DATE ▾]  by [auto▾]│ │
│ │ −1.0m ┤             ▓▓                                      ┤ 0       │ │ Y     1 [AMOUNT ▾][sum ▾][L▾] │ │
│ │       └──2026-11──2027-02──2027-05──2027-08──2027-11────────┘         │ │         [bar ▾]          [✕]  │ │
│ │                                                                       │ │       2 [PV ▾][sum ▾][R▾]     │ │
│ │  Line chart of AMOUNT and PV by PAY_DATE (month), 24 months,          │ │         [line ▾]         [✕]  │ │
│ │  split by CURRENCY (2). Highest AMOUNT 4.1m in 2027-02.               │ │       [+ series]              │ │
│ └───────────────────────────────────────────────────────────────────────┘ │ Split [CURRENCY ▾] (2 values) │ │
│  Showing 25 of 1,340 rows (the table's filters: STATUS = Live)            │ Sort  [X ▾] [asc ▾]           │ │
│  [Chart all 1,340 rows]                                                   │ Top   [ 0 ] ☐ Others          │ │
│                                                                           │ ☐ Labels  ☐ 100%              │ │
│ [View as table]  [PNG] [SVG] [CSV] [Excel]   [Save] [Reset]   [Promote to Sutra…]  [Open in BI…]           │ │
└────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

* The chart takes about 70% of the width, the settings 30% (a 300–360 px column). Every change redraws at once (no Apply).
* The caption under the chart is the text summary (section 14), visible and read by screen readers.
* The line under the chart says what is charted (*Showing 25 of 1,340 rows*, the table's filters in words, the business date, any masked
  column left out). **Chart all N** is there only when more rows exist and a server path exists (section 11).
* Buttons a user may not use are absent, not disabled (Promote for a non-author; Save in an embedded view).

### 5.2 Phone (full screen)

```
┌──────────────────────────────┐
│ ‹ Cash flows chart       [✕] │
├──────────────────────────────┤
│  AMOUNT (sum) ■USD ■EUR      │
│  4m ┤   █                    │
│  2m ┤ █ █ ▓   █              │
│   0 ┼─█─█─▓───█──▓───        │
│     └─11──02──05──08──       │
│  Line of AMOUNT by PAY_DATE, │
│  24 months, 2 currencies.    │
│  25 of 1,340 rows            │
│  [Chart all 1,340]           │
├──────────────────────────────┤
│ ▸ Settings                   │   ← a collapsible sheet; opened, it covers the lower half
│ ▸ Export and save            │
│ [View as table]              │
└──────────────────────────────┘
```

* `@media (max-width: 760px)`, as `pivot.css` and the share sheet in `collab.css`: the dialog is `100vw × 100dvh`, the chart about 55% of
  the height, the settings and actions in two `<details>` sheets. Controls follow `touch.css` (44 px targets).
* Rotating to landscape gives the chart the whole height and moves the sheets behind a ⚙ button.

### 5.3 Moments

| Moment | What the person sees |
|---|---|
| Opening | the chart already drawn with the defaults (section 7), focus on the chart's type group; announced *"Chart of AMOUNT by PAY_DATE. Settings on the right."* |
| Chart all N, records or history | *Reading 1,340 rows…* in the status line, then the chart redrawn; the line says *All 1,340 rows* |
| Chart all N, search | *Aggregating every match on 2026-10-20…*; the server's partial or masked notes shown as the pivot shows them |
| Chart all N, BI, longer than the inline wait | the run card of BI_RUNS 6.2 inside the popup (*Running: about 2 min, we'll notify you*, Cancel); the popup may be closed, and the run opens the chart from the Runs tab |
| Closing (✕, Escape, the browser's Back on a phone) | the dialog closes and the focus returns to the Chart icon; unsaved settings are kept for the tab's life (per identity, in memory) |

## 6. Chart types and settings

| Type | X | Y series | Split | Aggregation | Notes |
|---|---|---|---|---|---|
| **Line** | date (time axis), number or text | 1–6 | yes (one line per value) | sum, avg, count, min, max | `smooth`, `step` off by default |
| **Bar** | date, number or text | 1–6 | yes | as line | `grouped` (default) or `stacked`; `percent` (100%) when stacked; vertical columns; horizontal when X is text with long labels (> 18 characters on average) |
| **Area** | date or number | 1–6 | yes | as line | stacked when split |
| **Pie / donut** | text (the slices) | exactly 1 | no | sum, count | at most `pie-slices` (12): Top 11 and *Others*; refuses negative values (section 15) |
| **Scatter** | number | 1 numeric Y (size optional: a second numeric column) | yes (colour groups) | none (points), or by X with an aggregation | at most `scatter-points` (5,000) points; beyond, a note and the first 5,000 by the sort |
| **Combo** | date or text | 2–6, each with its own type (bar, line, area) | no | per series | each series on the left or right axis |

**Per series:** `field` (a numeric column; `count` needs none: *Rows*), `agg` (`sum` default for amounts, `avg` for a column whose format
is a percentage or a price (`pct*`, `price*`, `rate*`), `count`, `min`, `max`), `axis` (`left` default; `right` makes a second value axis
with its own format, for different units), `type` (combo only), `label` (default *AMOUNT (sum)*).

**X:** `field`, `bucket` for dates (`auto`, `day`, `week`, `month`, `quarter`, `year`; `auto` keeps days up to `max-points` (500) and
otherwise takes the smallest period that fits, as a BI time series does, and says *by month*), and `gaps` (`connect` default, `break`).

**Split by:** a text, boolean or date column; at most `split-max` (12) series: the 11 largest by the first series and *Others*.

**Sort:** `x` (default for a date or number X: ascending) or `value` (default for a text X: descending by the first series); `dir`.

**Top N:** `0` (all) or N; *Others* on or off. Text X with more than `category-max` (30) values starts at Top 20 with *Others* and says so.

## 7. Smart defaults

Computed by the pure function `defaults(columns, rows)` in `table-chart-engine.js` (unit-tested):

1. Columns are classified from their declared type (records `fmt`, BI `type`, history field kinds) and, only when undeclared, from the
   values: a column is a **date** when ≥ 90% of its non-blank values parse as ISO dates; **number** when ≥ 90% are finite numbers;
   **text** otherwise. Masked columns are left out (section 10).
2. **X** = the first date column; else the first text column whose distinct count is between 2 and `category-max`; else the first text
   column; else the first numeric column (scatter becomes possible).
3. **Y** = the first numeric column that is not X and is not an id (records `kind`, a linked column); else `count` (*Rows*).
4. **Type** = line for a date X, bar for a text X, scatter for numeric X and Y.
5. **Aggregation** = sum, or avg for percentage, price and rate formats.
6. **Split** = none. **Sort** and **Top N** as section 6.
7. A **saved arrangement** for the table's identity (section 9) wins over the defaults when every field it names is still offered;
   otherwise it is set aside with a note (*Your saved chart names PV, which this table no longer has; using the defaults*) and kept.

## 8. The component and its API

### 8.1 Files

| File | What | Size goal |
|---|---|---|
| `web/static/js/table-chart-engine.js` | pure, no DOM, node-testable (`module.exports`) like `pivot-engine.js`: classify, defaults, normalise settings, settings → pivot arrangement, cube → series (Top N, Others, buckets, sort, percent), the text summary, the export grid, the ECharts option builder (with tokens passed in) | ≤ 700 lines |
| `web/static/js/table-chart.js` | the dialog, settings pane, ECharts instance, keys, actions, adapters' glue (`window.drishtiTableChart`) | ≤ 900 lines |
| `web/static/js/table-chart-sources.js` | the adapters: `records`, `search`, `history`, `bi`, `dom` | ≤ 500 lines |
| `web/static/css/table-chart.css` | the dialog, the phone sheet, the settings column, tokens only | ≤ 250 lines |

The files load on demand: `tables.js` adds the icon and, at the first click, injects the three scripts (and `echarts.min.js` when the page
does not have it), so a page with tables pays nothing until a chart is opened. BI pages and the history page already load ECharts.

### 8.2 Opening a chart

```js
window.drishtiTableChart.open(source, {
  root: document,                // or the ShadowRoot of a <drishti-view>; the dialog goes there (as pivot.js's `layer`)
  opener: buttonElement,         // focus returns here on close
  save: true,                    // false in an embedded view: no Save, Reset, Promote
  exports: true,                 // false: no PNG, SVG, CSV, Excel
  fetch: (url, init) => fetch(url, init),   // an embed's own fetch, as pivot.js's options
  url: (path) => path
});
```

### 8.3 The source adapter (what a table hands over)

Every adapter implements the same small interface; `table-chart.js` never looks at the table's DOM.

```js
{
  identity: 'panel:trade-overview/cashflows',      // the saved arrangement's key (section 9); null: not saved
  title: 'Cash flows',                             // the table's title
  context: { kind: 'trade', id: '1000015', asOf: '2026-10-20', knownAt: null },
  filters: 'STATUS = Live',                        // the table's own filters in words (colfilter.js, the search's where), or ''
  columns: [                                       // in the table's order
    { name: 'PAY_DATE', label: 'Pay date', type: 'date',   fmt: 'date' },
    { name: 'AMOUNT',   label: 'Amount',   type: 'number', fmt: 'signed0' },
    { name: 'CURRENCY', label: 'Currency', type: 'text' },
    { name: 'CPTY_LIMIT', label: 'Limit',  type: 'number', masked: true },     // offered greyed: hidden for your role
    { name: 'TRADE_ID', label: 'Trade',    type: 'text', id: true, kind: 'trade' }
  ],
  shown: 25,                                       // rows the table shows after its filters
  total: 1340,                                     // rows that exist (null: unknown; equal to shown: nothing more)
  approximate: false,                              // true for the dom adapter: figures as displayed (rounded)
  can: { save: true, promote: true, openInBi: false, all: true },   // computed from the server's answers, never guessed

  rows(): Promise<{ fields, rows }>,               // the rows the table holds, raw, in the pivot engine's {fields, rows} shape
  all(arrangement, signal): Promise<Answer>|null,  // chart all N (section 11); null when there is no server path
  promote(settings): Promise<Proposal>|null,       // section 12.3
  openInBi(settings): Promise<{ href }>|null       // section 12.4
}
```

`Answer` is one of `{ records: {fields, rows, total, truncated} }` (the browser aggregates), `{ cube }` (the server aggregated: the pivot
cube shape of `pivot-engine.js`, as `/api/pivot/search` answers) or `{ run }` (a BI run still running; the component shows the run card
and listens on `/api/v1/bi/runs/events`).

### 8.4 The adapters

| Adapter | `rows()` | `all()` | Built by |
|---|---|---|---|
| `records` (Sutra table, ladder, drill level) | the rows the table holds after its column filters: `GET /api/pivot/records/{kind}/{id}/{panel}?for=chart`, cut to the table's `limit` and filtered by the same `colfilter.js` predicates the table applies (`CF.matches`) | the same records uncut (up to `max-records`), filters applied | `tables.js`, from the panel's `data-panel`, the view's `data-kind`/`data-id` and the table's filter state |
| `search` (search results, pick lists) | the current page's rows from the search answer (`data-search-rows` JSON written by `search.html`, raw values of the kind's search columns) | `POST /api/pivot/search/{kind}` with `{q, rows: [x], columns: [split], values, filters}` | `tables.js`, from `search.html`'s `data-pivot-search` / `data-pivot-q`, or a new `data-chart-search` for kinds without `pivot:` |
| `history` | the days on the page, from the `.json` export | the whole range from the `.json` export (`max-days` ≤ 366) | `history-page.js` |
| `bi` | the visual's last answer (`columns` with `type` and `masked`, `rows`) | a BI run (section 11.3) | `bi-report.js`, in the visual's action bar |
| `dom` | the cells' text through `colfilter.js`'s classifier and `tables.js`'s value parser (`1.5m` → 1,500,000) | none | `tables.js` for any other table |

### 8.5 What the component does with them

```
open(source) ──► rows() ──► classify + defaults (or the saved settings) ──► toArrangement(settings)
                                                                              │
             cube = pivot-engine.cube(records, arrangement)  ◄────────────────┘   (or the server's cube from all())
                                                                              │
             series = engine.series(cube, settings)   Top N, Others, buckets, sort, percent
                                                                              │
             option = engine.option(series, settings, charts.tokens(root))  ──► echarts.setOption(option, true)
             summary = engine.summary(series, settings) ──► caption + aria-label + View as table
```

`toArrangement` maps the settings onto the pivot: **X → `rows: [x]`**, **Split → `columns: [split]`**, **each series → `values: [{field,
agg}]`** (`count` with no field). Date bucketing is done before the cube by deriving a key column (`PAY_DATE·month` = `2027-02`) in the
browser, or by the server pivot's existing date grain when the search pivot has it (else the search path buckets in the browser from the
day cube). Scatter without aggregation skips the cube and reads the points directly.

`pivot-grid.js`'s `chart()` stays the Pivot tab's; the new option builder is separate because it needs time axes, two value axes, combo,
stacking, pie and scatter. Both use `charts.js`'s `tokens`, `palette`, `compact`, `axis` and `tooltip`, which `charts.js` exports on
`window.drishtiCharts` (C1 adds them to its public object).

## 9. The settings model and saved arrangements

### 9.1 The settings document

```json
{
  "v": 1,
  "type": "combo",
  "x":      { "field": "PAY_DATE", "bucket": "month", "gaps": "connect" },
  "series": [ { "field": "AMOUNT", "agg": "sum", "axis": "left",  "type": "bar",  "label": null },
              { "field": "PV",     "agg": "sum", "axis": "right", "type": "line", "label": "PV (USD)" } ],
  "split":  null,
  "sort":   { "by": "x", "dir": "asc", "series": 0 },
  "top":    { "n": 0, "others": true },
  "bars":   "grouped",
  "percent": false,
  "donut":  false,
  "labels": false,
  "scope":  "shown"
}
```

`normalise(settings, columns)` (pure) drops unknown keys, clamps numbers, removes series whose field is no longer offered or is masked,
keeps at most 6 series, and returns `{settings, problems}`; a saved document with problems is not applied (section 7, rule 7). `scope`
(`shown` or `all`) is saved so a chart saved over all rows reopens over all rows (with the cost said again for BI).

### 9.2 Where a saved arrangement is kept

**The pivot's mechanism, a namespace of its own.** A new `SavedChartController` (`/api/v1/me/charts`) keeps one document per user and
table identity in the `PreferenceStore`, namespace **`charts`**, as `SavedPivotController` keeps `pivots`:

| Identity | Key | Checked on read (`current`) |
|---|---|---|
| `panel:<sutra>/<panel>` | `panel/<sutra>/<panel>` | the Sutra's latest version still has the panel and every field; the user may open its kind |
| `search:<kind>` | `search/<kind>` | the user may open the kind; every field is a search column (or a `pivot:` field for `scope: all`) |
| `history:<kind>/<shape>` | `history/<kind>/<shape>` | the user may open the kind; the fields are the history block's |
| `bi:<report>/<page>/<visual>` | `bi/<report>/<page>/<visual>` | the user may read the report; every field is in the visual's answer |

Why not the `pivots` namespace itself: `GET /api/v1/me/pivots` lists every key of the namespace and checks each as a pivot arrangement
(`PivotSpec.arrangement(...).problems()`), and promotion reads the panel's key; chart documents there would be dropped as invalid pivots
or, worse, promoted as one. What is reused is everything around it: `PreferenceStore`, the `current()` pattern, the console's 10-second
per-user cache (`core/pivots.py` → a sibling `core/table_charts.py`), the user stamp on save, reset and promote (`core/user_stamps.py`),
and replication: **`charts` is added to `StateSync.NAMESPACES`**, so a saved chart follows the user to another node after a failover
(Drishti\*net N7).

The console's routes mirror `routes/pivot_routes.py`: `GET/PUT/DELETE /api/chart/saved/{identity}` (identity URL-encoded,
`panel/…`, `search/…`, `history/…`, `bi/…`), only the keys of section 9.1 passed on. An embedded view (`save: false`) neither reads nor
writes them. Tables of the `dom` adapter are not saved (no stable identity); their settings live for the tab only.

## 10. What it charts: rows, filters, masks

* **Rows:** the rows the table holds: its business date (and `knownAt` on a history *as it was* view), its Sutra-computed rows, and its
  **column filters and search box** (`colfilter.js`; the adapter applies the same predicates to the raw rows, so the chart counts what the
  table counts). Paging is ignored: a table on page 2 of 25 rows charts all its filtered rows, not the page. The status line says which.
* **Sorting the table** does not change the chart (the chart has its own sort).
* **Masks:** the server masks first (records `•••`, the search pivot's `masked`, BI `columns[].masked` and `meta.masked`, history's
  masked fields). The component then:
  * offers a **masked column** greyed in every picker, with *hidden for your role*; it is never X, Y, Split, a size or a sort key;
  * a column with **some** masked cells (a row-level rule) is chartable; its masked cells are left out of every aggregate and the status
    line says *12 values hidden for your role were left out*. A count is a count of the visible rows;
  * never shows a total, a percentage of total or *Others* that would include a masked value;
  * exports only what is drawn (section 12.1), so an export cannot carry more than the screen.
* **Masked server side twice across nodes:** a forwarded view or a scattered search is masked on the node that read it and again on the
  showing node (DRISHTI_NET N3); the records endpoint follows the view's path, so it inherits both.

## 11. Big tables: chart all N

**Chart all N** is offered when `total > shown` and the adapter's `all()` is not null. The decision on the server path, per table type:

| Table | Server path | Why this one |
|---|---|---|
| Sutra table, ladder | **`GET /api/pivot/records/…?for=chart`** (the rows; the browser aggregates with `pivot-engine.js`) | A panel's rows are one document's, already computed for the view; the endpoint already returns every row up to `drishti.pivot.max-records` (50,000) with the view's date and masks. Aggregating 50,000 rows in the browser takes tens of milliseconds (the Pivot tab does it today). Neither the search pivot (it aggregates entities of a kind, not a document's rows) nor BI runs (no model) apply. |
| Search results, pick lists | **`POST /api/pivot/search/{kind}`** (the cube) | The search pivot already aggregates every match of the business date on the server, masked, from columns, with its budget (`drishti.pivot.budget`, 20 s) and limits, across Drishti\*net shards through the search's scatter. A search is a terminal question, not a BI question, so it does not belong in BI runs. It answers the pivot's cube, which the component consumes as is. Offered only for kinds whose pack says `pivot:` (the pack owner's consent to server aggregation over the day) and only over its `pivot:` fields; otherwise **Chart all** is absent and the status line says *Charting every match needs the pack's `pivot:`*. |
| BI table, matrix | **BI runs** (`POST /api/v1/bi/runs`, source `mq`) | BI_RUNS decides that every BI query goes through one bounded pipeline; a chart over every row of a BI table can be an ad-hoc heavy question (182 dates of trades). The run gives the estimate, the confirmation above the threshold, the inline wait, fairness, reuse by fingerprint and masks per runner. |
| History | **`GET /export/history/{kind}/{id}.json?{range}`** (the rows; the browser aggregates) | A range is at most `max-days` (366) rows: small, already served whole, masked. |

### 11.1 Records (Sutra tables)

The browser asks once per open chart (cached for the dialog's life), applies the table's column filters, then aggregates. When the
server answers `truncated: true` the line reads *All 50,000 of 61,204 rows (the server's limit)*: never silently cut.

### 11.2 Search

Body: `{ "q": "<the search>", "rows": ["<x>"], "columns": ["<split>"] | [], "values": [{"field": "<y>", "agg": "<agg>"}…],
"filters": [<the table's column filters, as the pivot's filters>] }`. Text X with Top N asks the full cube and cuts in the browser (the
cube is bounded by the pivot's 2,000 row keys and 200 column keys; beyond, the pivot's own *partial* note is shown). A date X with a
bucket other than `day` is bucketed in the browser from the day cube. `avg` with *Others* is computed from the cube's `sum` and `count`
of the same field, which the chart asks for alongside (two values for one series), so *Others* stays exact.

### 11.3 BI runs

The component builds an MQ from the visual's own question (the model, the page's slicers, filters, parameters and as-of from
`stateFor(spec)`, the visual's filters), replacing its `select` by `{ by: [x, split], measures: [...] }`: a Y that is a **measure** is
computed by the engine at the chart's grain (its aggregation menu reads *the measure's own*, since re-aggregating a ratio is wrong); a Y
that is a **field** gets the chosen aggregation as a local measure. It submits `{source: {type: "mq", mq}, options: {interaction: false,
supersedes: "<report>/<page>/<visual>/chart", title: "Chart of … from <visual>"}}`:

* done within the inline wait: drawn at once;
* `409 DRS-9082` (confirm above the threshold): the popup shows the estimate and **Run it** / **Not now**;
* longer: the run card; the run appears in the Runs tab with its title; opening its result opens the report with the chart popup on it
  (`?run=…&chart=1`);
* a time series too long for `chart-rows` (500) is asked by period as BI does (*by week*), and the X bucket shows it.

## 12. Actions: export, save, promote, open in BI

### 12.1 Export

| Export | How | Name |
|---|---|---|
| **PNG** | ECharts `getDataURL({type: 'png', pixelRatio: 2, backgroundColor: --d-surface})` from a short-lived canvas-renderer instance of the same option (the dialog draws with SVG, as the pivot does) | `<table>-chart-<date>.png` |
| **SVG** | `getDataURL({type: 'svg'})` of the SVG instance; the theme's colours are written in, so the file stands alone | `<table>-chart-<date>.svg` |
| **CSV**, **Excel** | `POST /export/grid.csv` / `/export/grid.xlsx` with `{name, header, rows}`: X, then one column per drawn series (split values become columns), *Others* as a row; formula-safe; numbers as numbers | the existing `filename(name, date)` |

Downloads are a `Blob` and a temporary link for PNG and SVG (no server round trip), and a form post for CSV and Excel, as the Pivot tab
does. Exports are of the chart's **aggregated data as drawn** (masked values left out, Top N applied). `exports: false` (an embedded view)
hides them.

### 12.2 Save, Reset

**Save** keeps the settings for the table's identity (section 9); **Reset** deletes them and redraws the defaults. Both write the user
stamp, so every console worker and every node sees them at once. Announced: *Chart saved: it opens like this next time*.

### 12.3 Promote to Sutra (authors, Sutra tables only)

Offered when the console's `promote` power for pivots is true (an author, with Studio saving on: `core/pivots.py` `promote`) and the table
is a Sutra panel. Like pivot promotion it proposes the Sutra's **next version** through review (`SutraGovernance`), with the changes in
words and the diff (`core/sutra_diff.py`), or, with review off, publishes it.

**The mapping: always a new panel kind `chart`.** The existing kinds cannot hold the settings (`line` and `hbar` draw one series, `area`
has no split, none has grouped or stacked bars, a combo, a second axis or Top N). Mapping to them would lose settings silently, so the
promotion writes one new kind that takes the settings model as its options:

```yaml
  - id: cashflows-chart                    # <table id>-chart, -chart-2 … when taken
    kind: chart
    title: "Cash flows by month"
    span: 12                               # the table's span
    rows: $.CASH_FLOWS                     # the table's own rows expression, so the chart reads what the table reads
    filter: "STATUS == 'Live'"             # the column filters at the time of promotion, as an expression (asked in the dialog)
    type: combo
    x: { field: PAY_DATE, bucket: month }
    series:
      - { field: AMOUNT, agg: sum, axis: left,  type: bar }
      - { field: PV,     agg: sum, axis: right, type: line, label: "PV (USD)" }
    sort: { by: x }
```

Fields are the table's column binds (the promotion resolves each chart field to the column's `bind`). The server's `ChartBinder`
aggregates with the engine's `PivotCube` (the search pivot's), so a panel's numbers equal the popup's; `charts.js` draws `kind: chart`
with the engine's option builder (`table-chart-engine.js` loaded by `panels.html` for that kind). The panel kind gets its schema entry,
problem codes, `RACHANA_REFERENCE.md` entry, a **Data** table and CSV like every chart panel. `SutraChartEditor` (beside
`SutraPivotEditor`) inserts the panel after the table and keeps the table.

Endpoints: `GET/POST /api/chart/saved/panel/{sutra}/{panel}/promotion` (console) → `/api/v1/me/charts/panel/{sutra}/{panel}/promotion`
(server), the same shapes as the pivot's (`fromVersion`, `version`, `review`, `changes`, `diff`; the POST answers a proposal with its
`href: /build/reviews/<id>`).

### 12.4 Open in BI

Offered to users with BI author powers when the table maps to a BI model:

| Table | Opens |
|---|---|
| BI table or matrix | a draft of the report (as the designer's *Edit*) with a **new visual** after the table, selected in the designer: `/bi/design/<draft>?visual=<id>` |
| Search results of a kind a BI dataset serves (the pack's dataset whose table carries the kind) | a new draft report on that dataset, one page, the visual, the search's `where` as page filters when each term maps to a field; otherwise not offered |
| Sutra tables, history, `dom` tables | not offered: their rows are a document's nested fields or one entity over time, not a model's table |

The mapping to BI visual types (REPORTS.md section 3):

| Chart | BI visual | Wells and format |
|---|---|---|
| line | `line` | `axis` = X (a date with its bucket: `PAY_DATE.month`), `values` = the series, `legend` = split |
| bar, grouped | `column` | as line |
| bar, stacked / 100% | `column` | `format: { stacked: true }` / `percent: true` |
| bar with long text labels | `bar` | as line |
| area | `area` | as line |
| pie / donut | `pie` / `donut` | `axis` = X, `values` = the one series |
| scatter | `scatter` | `details` = X, `x` and `y` measures, `size` |
| combo | `combo` | `axis` = X; series on the **left** as `values` (columns), on the **right** as `secondary` (lines) |

BI's `combo` draws every `values` measure as columns on the first axis and every `secondary` as lines on the second. A chart whose combo
puts a line on the left or a bar on the right cannot be mapped as is: the dialog says *In BI, bars use the left axis and lines the right:
PV moves to the right axis*, and goes on only when confirmed. Top N becomes the visual's `top` filter; Others has no BI equivalent and is
dropped with a note. A field Y with an aggregation becomes a local measure (`sum(AMOUNT)`); a measure Y stays the measure.

## 13. Keys

**K** opens the chart where it is free; every context was checked against the code:

| Context | Key | Check |
|---|---|---|
| A focused table enhanced by `tables.js` (panels, search results, pick lists, admin lists) | **K** (either case) | the table's keydown handles arrows, PgUp/PgDn, Home/End and Enter only; Alt+Shift+X is the root's; Z is `zoom.js` on the panel |
| The history page | **Shift+K** | `history-page.js` binds lowercase `k` to *previous day* (with `j`); `K` (Shift) is unbound; the key line and the `?` card list it |
| A BI report, focus inside a table or matrix visual | **K** | `bi-report-board.js` binds K (either case) at document level to **kiosk** when the report has pages. The visual's handler runs first (bubbling), opens the chart and calls `preventDefault()`; the kiosk handler gains `if (e.defaultPrevented) return;`. K elsewhere on the report still toggles kiosk |
| The Pivot tab | none | the tab has its own chart menu; R, C, V, F are its zone keys |
| While typing in a field | none | ignored, as every key handler here does |

**Inside the popup** (a native `<dialog>`; the focus is trapped and returns to the opener on close, as `share.js` does):

| Key | Does |
|---|---|
| Esc | closes (a menu or picker inside closes first; `zoom.js` sees `dialog[open]` in `OVERLAYS` and does not unzoom) |
| Tab / Shift+Tab | the type group, X, each series' controls, Split, Sort, Top, the actions, the chart |
| 1–6 (focus not in a field) | type: 1 line, 2 bar, 3 area, 4 pie, 5 scatter, 6 combo |
| ← → on the chart | moves between points (X values); the value of each series is announced in the live region; Home/End first/last |
| ↑ ↓ on the chart | moves between series at the same X |
| T | View as table / back to the chart |
| A | Chart all N (when offered; the cost said first) |
| S | Save (when offered) |
| E | the export menu |
| ? | the popup's key card |

## 14. Themes and accessibility

* **Tokens only.** Colours come from `charts.js` `tokens(root)` (which reads a shadow host's tokens in an embedded view) and its
  `palette(t)`: `link`, `accent`, `ok`, `neg`, `warn`, `bad`, `muted`, `ink`; *Others* is always `faint`; the axes `border` and
  `muted`; negative bars in a single-series bar chart may use `neg` (`tone: sign`, as `hbar`). More than 8 series reuse the palette with a
  dash pattern (lines) or a decal (bars, ECharts `aria.decal`), so colour is never the only difference. A theme change redraws the chart
  (the same listener `charts.js` uses).
* **Contrast (`test_contrast.py`):** a new test asserts, in all ten themes, that every palette token and `faint` reach 3:1 against
  `surface` (non-text graphics), that axis label tokens reach 4.5:1, and that adjacent palette entries differ by at least a minimum
  ΔE so two neighbouring series are told apart.
* **Keyboard:** every setting is a native control; the chart is focusable with point navigation (section 13); nothing needs a pointer.
* **Screen readers:** the chart is `role="img"` with an `aria-label` that is the text summary; the summary is also the visible caption:
  *"Bar chart of AMOUNT (sum) by CURRENCY, 7 categories, highest USD 4.1m, lowest NOK −0.3m; 25 of 1,340 rows."* For a time axis: the
  first and last X, the highest and lowest point, the trend (up, down, flat by the least-squares slope over the range). Changes are
  announced in a polite live region (*Split by DESK: 4 series*).
* **View as table:** the chart's data (the export grid) as a plain `table.tbl` under the chart (with `data-plain`, so it does not get
  its own Chart icon), as every chart panel's **Data** table.
* **Motion:** `animation: false` (as the pivot's charts), so `prefers-reduced-motion` is honoured by default.
* **Print:** `@media print` prints the chart and its caption, not the settings.

## 15. Edge cases

| Case | Behaviour |
|---|---|
| **No numeric column** | Y defaults to `count` (*Rows*): a bar of how many rows per X. Line and scatter still possible over count; scatter needs two numeric columns and is disabled with the reason. |
| **No column at all chartable** (every column masked, or only ids) | the icon is disabled with *Nothing to chart: every column is hidden for your role* (or *only identifiers*). |
| **A single row** | drawn (one bar, one point, a full pie); the caption says *one row*. A line with one point is drawn as a point. |
| **No rows** (the table is empty, or its filters leave none) | the popup opens with *No rows to chart (the table's filters leave none)* and the settings still usable; Chart all N if more exist. |
| **Nulls and blanks** | a blank X is the pivot's `(blank)` key, last in order, labelled *(blank)*; a blank Y is left out of sum, avg, min and max, counted by count only when counting rows (as `pivot-engine.js` `Acc`). A time axis leaves blank-X rows out and says *3 rows without a PAY_DATE left out*. |
| **Mixed types in a column** (numbers and text) | classified by the 90% rule (section 7); values that do not fit the type are left out of that role and counted in the status line (*2 non-numeric AMOUNT values left out*). A column below 90% either way is text. |
| **Very many categories** | text X: more than `category-max` (30) starts at Top 20 with *Others*; the cube itself stops at 2,000 row keys (the pivot's limit), which the line says. Split: at most 12 series (11 and *Others*). Pie: at most 12 slices. |
| **Negative values in a pie** | refused: *A pie shows shares of a whole, and AMOUNT has negative values (3 of 7 slices). Use a bar chart, or chart the absolute values.* with buttons **Bar chart** and **Absolute values** (a series option `abs: true`, saying *absolute* in the label). Zero slices are left out and said. |
| **Dates with gaps** (weekends, holidays, missing days) | a time axis spaces dates by time; lines connect across gaps by default (`gaps: connect`); `break` draws a gap where a business day expected by the calendar (`CATO` calendar when the pack has one, else Monday–Friday) is missing. Bars stand at their dates. Buckets (week, month) sum within the bucket. |
| **Dates as text in different shapes** | ISO dates and date-times only; other text stays text (categories). Time zones: a date-time is shown in the console's zone, bucketed by the date in that zone. |
| **Very large or small numbers** | `compact()` labels (`1.5m`, `3.6tn`), the series' format in tooltips; a right axis gets its own format. |
| **Percent columns** | `avg` by default; a 100% stacked bar over percentages is refused (*already percentages*). |
| **Identifiers** (trade ids, linked columns) | never a default Y; as X they are categories (and a point opens the entity on Enter, as `charts.js` opens a linked point). |
| **A live table that ticks** | the chart is a snapshot when opened; *Updated rows: Refresh* appears when the table changes underneath (a live patch event on the panel); no auto-redraw while a person is reading. |
| **The table changes while the popup is open** (filters changed in another pane) | the popup keeps its rows until **Refresh**. |
| **A saved chart naming a removed or masked column** | not applied; the defaults are used with a note; the saved document is kept until saved again or reset. |
| **A server path that fails** | the pivot's problem text (`{code, detail}`) in the status line, the shown-rows chart kept. |

## 16. Endpoints

All but the last two are reuses or small extensions; nothing new aggregates.

| Method | Path (console → server) | New or changed | Notes |
|---|---|---|---|
| `GET` | `/api/pivot/records/{kind}/{id}/{panel}?for=chart` → `/api/v1/views/{kind}/{id}/panels/{panel}/records?for=chart` | **changed** | `for=chart` gives every **column** of a table, ladder or drill level (not only the `pivot:` fields), for any table panel, with each field's `fmt`, `type`, `masked`, `id`/`kind`. `PivotBinder.records` gets the fields from `p.columns()` (each column's `bind`, label and format) when the panel has no `pivot:` or `for=chart`. Refused with `DRS-1040` (*not a table*) for other kinds; `drishti.pivot.enabled: false` does not switch charts off (`drishti.charts.enabled` does). |
| `POST` | `/api/pivot/search/{kind}` | reused | as is; the chart sends one or two values per series (section 11.2) |
| `GET` | `/export/history/{kind}/{id}.json` | reused | as is |
| `POST` | `/api/v1/bi/runs` | reused | BI_RUNS 14.2, `source.type: mq` |
| `POST` | `/export/grid.csv`, `/export/grid.xlsx` | reused | as is |
| `GET/PUT/DELETE` | `/api/chart/saved/{scope}/{…}` → `/api/v1/me/charts/{scope}/{…}` | **new** | section 9.2; `GET /api/v1/me/charts` lists the user's valid ones (for `drishti.py`) |
| `GET/POST` | `/api/chart/saved/panel/{sutra}/{panel}/promotion` → `/api/v1/me/charts/panel/{sutra}/{panel}/promotion` | **new** | section 12.3 |
| `POST` | `/api/chart/open-in-bi` → `/api/v1/bi/drafts/from-chart` | **new** | `{source: "bi"|"search", report?, page?, visual?, kind?, q?, settings}` → `201 {draft, visual, href, notes}` |

Error codes: `DRS-1040` (not a table), `DRS-1041` (a chart field is masked or unknown), `DRS-1042` (open in BI: no dataset serves the
kind), `DRS-1043` (promotion: the panel is not a table). Free numbers in the `DRS-104x` block, to be confirmed against `ErrorCode` at C2.

## 17. Settings

Server, `application.yaml`:

```yaml
drishti:
  charts:
    enabled: true                 # false hides every Chart icon (the console reads it from /api/v1/settings)
    max-points: 500               # points on a time axis before auto-bucketing
    category-max: 30              # text X values before the default Top 20 + Others
    split-max: 12                 # series a Split may make (11 and Others)
    pie-slices: 12
    scatter-points: 5000
    series-max: 6
```

Rows are bounded by the existing `drishti.pivot.max-records` (50,000), the search pivot's limits and BI runs' admission; no second cap.

## 18. Build steps C1..C10

One console builder and one server builder (two at most, as the release's other items need theirs); targeted tests between steps.

| Step | Lane | What | Done when |
|---|---|---|---|
| **C1** | console | `table-chart-engine.js`: classify, defaults, normalise, `toArrangement`, `series` (Top N, Others, buckets, sort, percent, abs), the summary, the export grid, the option builder; `charts.js` exports `tokens`, `palette`, `compact`, `axis`, `tooltip` | unit tests of section 19.1 pass under node |
| **C2** | server | `for=chart` on the records endpoint (`PivotBinder` fields from columns, `type`/`masked` per field), `drishti.charts.*` properties, error codes | `ViewApiTest`/`PivotApiTest` cases: a non-pivot table's records; masked field flagged; a non-table refused |
| **C3** | console | `table-chart.js` (the dialog, settings, ECharts, keys, live region, View as table, PNG/SVG/CSV/Excel) and `table-chart.css`; the `dom` and `records` adapters; the icon and K in `tables.js` (lazy load) | a Sutra table charts its shown rows and all rows; keys work; phone layout |
| **C4** | server | `SavedChartController` (`/api/v1/me/charts`, namespace `charts`), `charts` in `StateSync.NAMESPACES` | save, read back, invalidated by a Sutra change, replicated to a peer (`NetStateSyncTest`) |
| **C5** | console | `routes/chart_routes.py` (saved, promotion, open in BI), `core/table_charts.py` (cache, user stamps); Save / Reset in the dialog | `test_table_chart.py` route cases |
| **C6** | console | the `search` adapter (`search.html` writes the page's raw rows as `data-search-rows`; chart all via the search pivot) and the `history` adapter (`history-page.js`, Shift+K, the key line and card) | search and history charts in the browser test |
| **C7** | console | the `bi` adapter: the icon in `bi-report.js`'s action bar for table, matrix and rows-behind, K with `preventDefault` and the kiosk guard in `bi-report-board.js`, chart all through BI runs (the run card inside the popup) | BI table charts inline and as a run |
| **C8** | server | the `chart` panel kind: schema, `ChartBinder` over `PivotCube`, problem codes; `SutraChartEditor` and the promotion endpoints | a promoted Sutra draws the same numbers as the popup (`ChartPanelTest`) |
| **C9** | both | Open in BI: `/api/v1/bi/drafts/from-chart` (BI table → draft with a visual; search kind → a new draft on its dataset), the mapping of 12.4 with its notes | the designer opens with the visual selected; the combo axis note shown |
| **C10** | console | the browser test across table types and ten themes, the mask test, `test_contrast` additions, docs (section 20), help card, tutorial section | section 19 green; docs tests green |

## 19. Tests

### 19.1 Unit (node, from `test_table_chart.py`, as `test_pivot.py` runs `pivot-engine.js`)

* **Classify:** ISO dates, date-times, numbers, numbers as text, mixed columns at 89% and 91%, all blank, masked.
* **Defaults:** date + number → line by date; text + number → bar; two numbers → scatter; no number → count; ids never Y; masked never
  chosen; a saved document with a removed field → defaults with the note.
* **Aggregation** (through `pivot-engine.js` `cube`): sum, avg, count, min, max per X with repeated X; blanks; split into series; the
  numbers equal the server's `PivotCube` for the same rows (the existing `test_client_engine_writes_numeric_keys_as_the_server_does`
  pattern, extended to aggregates).
* **Top N and Others:** additive (sum, count) and `avg` from sum and count; Others off; a pie capped at 12; ties broken by X order.
* **Buckets:** day to week, month, quarter, year; auto at 500 points; gaps `connect` and `break` against a Monday–Friday calendar.
* **Pie refusal:** negative values refused with the message; `abs` accepted; zeros left out.
* **Normalise:** unknown keys dropped, 7 series cut to 6, bad enums replaced, `v` checked.
* **Summary text:** a bar, a time line with trend, a single row, an empty chart.
* **Option builder:** two axes when a series is on the right; stacked and 100%; combo types; no literal colour in the option (every
  colour is one of the tokens passed in).

### 19.2 Server

* `PivotApiTest` / `ViewApiTest`: `for=chart` on a table without `pivot:`; columns, formats, `masked`; a drill level; truncation at
  `max-records`.
* `SavedChartApiTest`: per user and identity; `current()` drops a document whose field is gone; another user cannot read it; replicated
  by `StateSync` to a second node.
* `ChartPanelTest`: the `chart` kind's numbers equal `PivotCube` and the popup's for the same rows; masks; problem codes.
* `ChartPromotionTest`: the diff, review on and off, the panel inserted after the table with a unique id.
* `OpenInBiTest`: BI table → draft visual of the right type and wells for every row of the 12.4 table; search kind without a dataset →
  `DRS-1042`.

### 19.3 Browser (`test_table_chart_browser.py`, one test process, 10K trades at most)

For each table type: a Sutra table, a ladder, a drill level, search results, a pick list, the history page (document and market
shapes), a BI table, a BI matrix and a BI chart shown as a table:

1. the icon is in the heading; K (Shift+K on history) opens the popup with the defaults drawn (the SVG has the expected number of
   bars or points);
2. changing type, X, a second series on the right axis, Split and Top N redraws; the summary updates;
3. **Chart all N** where offered: a Sutra table with `limit: 25` over 1,340 rows draws 1,340 rows' totals; a search with `pivot:` sends
   one `/api/pivot/search` request; a BI table submits one run (inline with the small lake; a run card with a forced `inline-wait: 0`);
4. Save, reload, the chart reopens as saved; Reset forgets it; a second console worker shows it at once;
5. PNG, SVG, CSV and Excel download, and the CSV equals the drawn data;
6. Esc closes and the focus is back on the icon; a zoomed panel stays zoomed;
7. **phone** (390 × 844): full screen, the sheets open and close, no horizontal scroll;
8. **ten themes:** the popup in every theme passes the contrast checks and has no literal colour (the computed fill of each series is a
   token value);
9. keyboard only: open, change every setting, navigate points, export, close, without a pointer;
10. on a BI report with pages, K on a table visual opens the chart and K elsewhere still toggles kiosk.

### 19.4 Masks (`test_table_chart_masks_browser.py` and server cases)

A user whose role masks `MTM` and a row-level rule on one desk:

* `MTM` is greyed in every picker and cannot be chosen by key or by a crafted saved document (the server's `current()` and the console's
  `normalise` both refuse it);
* a chart over a partly masked column leaves the masked rows out and says how many; totals, percentages and *Others* exclude them;
* Chart all N through records, the search pivot and BI runs never returns the masked values (asserted on the wire, not only on screen);
* the CSV and Excel exports carry nothing the screen does not;
* a chart saved by a user who may see `MTM` and opened by one who may not: not applied, defaults used.

## 20. Docs to update when it is built

| Doc | What |
|---|---|
| [USER_GUIDE.md](../guides/USER_GUIDE.md) | a new section *Chart any table* (the icon and K, types, X, series, split, sort and Top N, chart all N, masks, save, export, keys, phone), linked from the table and Pivot tab sections and the history section |
| [BI_GUIDE.md](../guides/BI_GUIDE.md) | charting a BI table, chart all N as a run, Open in BI and the combo axis rule, K beside kiosk |
| [PANELS.md](../guides/PANELS.md) | the Chart icon on tables (*How a panel behaves*), `chart: false` on a table, and the new `chart` panel kind with its options, states and CSV |
| [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md), [SUTRA_DEVELOPER_GUIDE.md](../guides/SUTRA_DEVELOPER_GUIDE.md) | the `chart` kind's options; promotion from the popup |
| `config/help.yaml` | the context help card `table-chart: user-guide#chart-any-table`, and the popup's `?` links it |
| [Tutorial 21](../tutorials/21-an-entity-over-time.md) and the tables tutorial | a section *Chart it* with UI, keys and the resulting YAML of a promoted chart panel, with screenshots (desktop and phone) |
| [CONFIGURATION.md](../admin/CONFIGURATION.md) | `drishti.charts.*` |
| [API_GUIDE.md](../guides/API_GUIDE.md) | `for=chart`, `/api/v1/me/charts`, `bi/drafts/from-chart` |
| [DRISHTI_NET.md](DRISHTI_NET.md) N7 | `charts` among the replicated namespaces |
| this document | *As built* notes; status to *built* |
| CHANGELOG, RELEASE_NOTES | the release's entries |

## 21. What the real code changed

Reading the code on `develop` changed the agreed outline in these places:

1. **Tables do not carry raw values.** `panels.html` renders display text (`1.5m`) with only a `numeric` flag per column, so "chart what the
   table holds" cannot read the DOM without drawing rounded numbers. The design reads raw rows from the server (the records endpoint for
   panels, the search answer, the BI answer, the history JSON) and keeps DOM parsing only for tables with no raw source, labelled
   *approximate*.
2. **The records endpoint is pivot-only.** `PivotBinder.records` throws for a panel without `pivot:` and returns only the pivot's fields.
   Charting *any* table needs `for=chart` with the table's columns (C2); this is the one server change on the read path.
3. **The search pivot is opt-in per pack.** `SearchPivotController` serves only kinds whose pack says `pivot:` and only their fields, a
   pack owner's consent to day-wide aggregation. Chart all N on a search follows that consent rather than bypassing it; the shown page is
   always chartable.
4. **K is taken in two places.** `history-page.js` uses lowercase `k` for *previous day* (so history gets **Shift+K**), and
   `bi-report-board.js` uses K for kiosk at document level (so the BI table's handler takes K first with `preventDefault` and the kiosk
   handler learns to respect it).
5. **No Sutra chart kind can hold the settings.** `line`, `area`, `hbar` and `scatter` draw one series or fixed bands; Promote therefore
   adds one new `chart` kind instead of a lossy mapping.
6. **BI's combo is fixed: columns left, lines right.** The per-series axis of the popup cannot always map; Open in BI says what moves.
7. **The pivot's saved-arrangement namespace cannot be shared.** `SavedPivotController` lists and validates every key of `pivots` as a
   pivot arrangement and promotes from it; charts get the same mechanism under `charts`, and `StateSync.NAMESPACES` must list it or a
   saved chart would not follow the user across nodes.
8. **`pivot-grid.js`'s chart is too narrow to extend in place** (no time axis, one value axis, cuts at 200 lines and 40 series); a separate
   option builder keeps the Pivot tab's behaviour unchanged.
9. **A BI measure must not be re-aggregated.** A BI table's measure column (a ratio, an average) summed again by X would be wrong; the BI
   path asks the engine for the measure at the chart's grain, and only fields get an aggregation menu.

**C1 as built (`table-chart-engine.js`, ahead of the wiring).** Coding the engine settled these points:

* **Count of rows needs a column.** `pivot-engine.js` `normalise` drops a value whose field is not a record field, so *Rows* cannot be
  `{field: null, agg: count}` beside other values. `prepare` adds a derived `·rows` column (1 per kept row) and counts it, so a count is
  of the rows that reached the chart (masked X or split cells drop their row first).
* **A masked X or split cell drops its row; a masked Y cell is only left out of that series.** The section 10 rule ("left out of every
  aggregate") needs that split: a row cannot be placed without its X.
* **Top N does not apply to a time axis** (it would cut dates out of a line); the engine notes it and keeps every date.
* **Scatter's aggregation `none`** is a seventh value of the series' `agg`, allowed only for `type: scatter`; `normalise` turns it into
  `sum` for every other type.
* **The summary's numbers use `pivot-engine.js` `format(v, 'compact')`** (`−300k`, `4.1m`), not the section 14 example's `−0.3m`.
* **Bad enums are fixes, not problems.** `normalise` returns `{settings, problems, fixes}`: only a removed or masked field (or another
  `v`) is a problem that sets a saved document aside; a bad `type` or `agg` is replaced and listed in `fixes`.

## 22. Open questions

1. **Chart all N on searches without `pivot:`:** keep the pack owner's opt-in (this design), or let the chart aggregate the kind's
   search columns without it?
2. **Open in BI for Sutra tables:** worth a "dataset from a document's rows" path later, or never (this design: not offered)?
3. **Live tables:** snapshot with *Refresh* (this design), or follow live patches when the person asks?
4. **Saved charts for `dom` tables** (admin lists): keep them per tab only, or key them by screen and table id?
5. **More than one saved chart per table** (named charts, like bookmarks): later, or in 2.6.7?
