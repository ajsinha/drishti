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
# Reports: the YAML, the visuals and how they behave

A **report** is pages of **visuals** on a 12-column grid, bound to the fields and measures of one **dataset** ([DATASETS.md](DATASETS.md)). This is the reference for the file, for the visual
catalogue, for how a visual becomes a query, and for what people can do with a report on the page. To read or build one step by step see [BI_TUTORIAL.md](BI_TUTORIAL.md) (chapters 10 to 15, and 18 for a live report); for the
pages (viewer, designer, Model view) see [BI_GUIDE.md](BI_GUIDE.md) sections 14 to 16 and 18. The design is [RUPAKA.md](../architecture/RUPAKA.md) sections 8 and 9; what is built is its sections 20.3 and 20.5.

Three sample reports ship in `config/bi/reports/`, each marked `sample: true` and commented line by line: **Trades overview** (on `trades-basic`), **Counterparty risk** (cross-pack, on
`trading-risk`) and **Cashflow ladder** (deep trades, on `trading-cashflows`); phase 5 adds **Live trading desk** (on the live dataset `live-trading`: cards, a bar, a rolling line, two tables, a Perspective grid and a pivot) and
**Live exposure** (on `live-exposure`), and the board **Desk wall** ([BOARDS.md](BOARDS.md)) that shows tiles of both.

## Contents

1. [Where a report lives](#1-where-a-report-lives)
2. [The file](#2-the-file)
3. [The visuals and their wells](#3-the-visuals-and-their-wells)
4. [Filters](#4-filters)
5. [Format](#5-format)
6. [Slicers](#6-slicers)
7. [How a visual becomes a query](#7-how-a-visual-becomes-a-query)
8. [What people can do with a report](#8-what-people-can-do-with-a-report)
9. [Checking a report: DRS-9030 to DRS-9035](#9-checking-a-report-drs-9030-to-drs-9035)
10. [Security](#10-security)
11. [Settings](#11-settings)
12. [Built and not built](#12-built-and-not-built)
13. [Live visuals and Perspective (phase 5)](#13-live-visuals-and-perspective-phase-5)

## 1. Where a report lives

* **A file** in the BI folder: `config/bi/reports/<id>.report.yaml` (`drishti.bi.dir`). It is a published, read-only item of the catalogue (workspace *Shipped content*, id `file:report:<id>`), watched
  **live**: add, change or delete the file and the catalogue and the viewer follow, with no restart; a file that does not parse keeps its last good version and is reported ([BI_GUIDE.md](BI_GUIDE.md) section 10).
* **An item** of a workspace: a `report` item whose YAML body is a draft you edit in the designer, then publish like any item (review, versions, endorsement, audience). Publishing checks the structure
  and refuses a report with errors (`DRS-9030`).

The catalogue keys `title`, `description`, `owner`, `tags`, `endorsement`, `audience` and `sample` are read from the same file; the rest is the report.

## 2. The file

```yaml
rachana: 1
report: trades-overview        # the report's name: letters, digits, hyphen, underscore, dot
version: 1
title: Trades overview
description: The day's trades at a glance.
sample: true
dataset: trades-basic          # a catalogue id, a shipped file's name, or the dataset's own name
theme: { palette: default }    # default, colour-blind or mono
dates: { from: 2026-10-01, to: 2026-10-08 }   # optional; without it each query reads the latest business date
filters: []                    # report filters: see section 4
pages:
  - id: overview
    title: Overview
    filters: []                # page filters
    visuals:
      - id: mtm
        type: card
        title: Mark to market
        at: { x: 6, y: 0, w: 3, h: 2 }          # column, row, width in columns, height in rows
        wells: { values: [MTM] }
        format: { conditional: [{ measure: MTM, op: lt, value: 0, colour: "#b3261e" }] }
bookmarks:
  - { name: FX desk only, page: overview, filters: [{ field: trade.desk, op: in, values: [DESK-FX] }] }
```

| Key | Meaning |
|---|---|
| `rachana` | the language version, `1` |
| `report` | the report's name (also its file name) |
| `version`, `title`, `description` | your own version number; the catalogue's title and description |
| `sample` | `true`: sample content, hidden from people who may not see samples (like sample packs) |
| `dataset` | the dataset it reads. Found by catalogue id, by file name (`trades-basic` is `file:dataset:trades-basic`), by the dataset's own name or by its title; a draft dataset of your own works too. Not found: `DRS-9034` |
| `theme` | `palette`: `default` (the console's colours, following light, dark and high contrast), `colour-blind` (Okabe-Ito) or `mono`; optional `background` (a `#rrggbb`, used by the contrast check) |
| `dates` | `from` and `to` (`yyyy-mm-dd`, inclusive) for every query; a person can pick a business date in the viewer's toolbar instead |
| `filters`, `bookmarks`, `pages` | sections 4, 8 and below |

A **page** has `id` (letters, digits, hyphen, underscore, up to 40; made from the title when missing), `title`, `filters`, `visuals`, and optionally `drillthrough: { fields: [table.column] }`, which makes it a
**drill-through target** (section 8): it is left out of the page tabs and opened from a visual. At most 30 pages, 60 visuals a page.

A **visual**:

| Key | Meaning |
|---|---|
| `id` | letters, digits, hyphen, underscore; unique on the page (made up, `v1`, when missing) |
| `type` | one of section 3 |
| `title` | shown over the visual |
| `at` | `{ x, y, w, h }`: column `x` (0 to 11), row `y` (0 or more), `w` columns wide (1 to 12, `x + w` at most 12), `h` rows high (1 to 40); a row is 56 px |
| `wells` | by well name, the fields and measures it shows (section 3) |
| `filters`, `sort`, `limit` | the visual's own filters; `sort: { by, desc }` on a field or measure of the visual; most rows (default: section 11) |
| `hierarchy` | a dataset hierarchy the visual drills down (charts, tables, matrices) |
| `drillthrough` | `{ page, field }`: a click on a mark of `field` may open that page carrying the mark's value; the page must list the field under its own `drillthrough: fields` |
| `format` | display settings, section 5 |
| `text`, `src`, `alt`, `interacts`, `action` | the words of a text visual or button; an image's address (this site's `/static/...` or a `data:image/` URI, never another address) and its alt text (also a text alternative for any visual); `interacts: false` stops other visuals' selections from filtering this one; a button's `action: { navigate: <page> }` or `{ bookmark: <name> }` |

The viewer's keyboard order is the picture's: visuals are read top to bottom, then left to right.

## 3. The visuals and their wells

A **well** holds fields (`table.column`, or `table.column.year|quarter|month|week|day` for a date), measures (names of the dataset) or either, and takes a few items. Charts draw with ECharts
(vendored); everything else is plain elements. Required wells must be filled. The designer's Visualizations pane offers the first 21 types below (`bar` to `button`); `live-chart`, `perspective-grid` and `perspective-pivot` (phase 5) are written in the YAML, and the designer's canvas shows a note for a Perspective visual (its page has no WebAssembly).

| Type | Group | Wells (kind, how many) | What it draws |
|---|---|---|---|
| `bar` | chart | `axis` field (1, required), `legend` field (1), `values` measures (required) | horizontal bars, ranked by the first measure; one series per measure, or per legend value |
| `column` | chart | as `bar` | vertical columns; `format: { stacked: true }` stacks them |
| `line` | chart | as `bar` | lines in axis order (a date axis in date order) |
| `area` | chart | as `bar` | filled lines |
| `combo` | chart | `axis` (1, required), `values` (required), `secondary` measures | columns for `values`, lines for `secondary` on a second axis |
| `scatter` | chart | `details` field (1, required), `x` measure (1, required), `y` measure (1, required), `size` measure (1) | a point per value of `details` |
| `pie`, `donut` | chart | `axis` field (1, required), `values` measure (1, required) | shares of the whole, with the percentage in the tip |
| `waterfall` | chart | `axis` field (1, required), `values` measure (1, required) | each step from the running total, and a total |
| `treemap` | chart | `axis` fields (1 or 2, required), `values` measure (1, required) | nested rectangles |
| `heatmap` | chart | `axis` field (1, required), `legend` field (1, required), `values` measure (1, required) | a grid coloured by the measure |
| `card` | card | `values` measure (1, required) | one number; `format.conditional` colours it |
| `multirow-card` | card | `axis` field (1), `values` measures (required) | a row per value of `axis`, or the measures as rows |
| `kpi` | card | `values` measure (1, required), `target` measure (1), `axis` field (1) | the value, how it stands against the target, a sparkline over `axis` |
| `gauge` | card | `values` measure (1, required), `target` measure (1) | a dial; `format: { min, max }` sets the range |
| `table` | table | `columns` fields and measures (required) | the fields grouped, the measures added up; click a column title to sort; data bars and conditional colours; a key column of a table whose kind is a Drishti kind links to the entity in the terminal |
| `matrix` | table | `rows` fields (required), `columns` field (1), `values` measures (required) | rows by columns; `format: { subtotals: true }` adds a subtotal per group of the first row field (collapsible), a total per row and a grand total |
| `slicer` | slicer | `field` (1, required) | section 6 |
| `text`, `image`, `button` | static | none | `text`; `src` and `alt`; `text` and `action` |
| `live-chart` | live | `values` measures (1 to 4, required) | a **rolling line**: each update of the live table adds a point per measure; `format: { window: 120 }` keeps the latest 5 to 1,000 points (section 13) |
| `perspective-grid` | perspective | `columns` fields of **one table** (required) | the table's rows in a Perspective grid, masked for the reader and updated as the table changes (section 13) |
| `perspective-pivot` | perspective | `rows` fields (required), `columns` fields, `values` fields (required) | the same rows pivoted by Perspective in the browser: `rows` down, `columns` across, `values` aggregated (`format: { aggregates: { trade.mtm: sum } }`) |

Reserved and **refused with `DRS-9033`** (not built): `bubble`, `funnel`, `histogram`, `box`, `map`, `python`, `entity-view`, `shape`.

A measure in a field well, a field in a measure well, an unknown well, a required well left empty or one over its number of items is `DRS-9032`; a name the dataset does not have is `DRS-9031`.
Totals in a matrix are the sum of the shown figures, so they are computed for measures that add up (a `sum` or a `count`) and shown as `–` for the others (an average, a ratio, a distinct count):
asking the engine for a true total of those is not built.

## 4. Filters

A filter is `{ field, op, values }` at **report**, **page** or **visual** level. `field` is a column (or date part) of the dataset or, for a comparison on a total, a **measure**.

| `op` | Values |
|---|---|
| `in`, `not-in` | a list |
| `eq`, `ne`, `lt`, `le`, `gt`, `ge` | one value |
| `between` | two values |
| `contains`, `starts-with` | text |
| `is-null`, `not-null` | none |
| `top` | `n`, `by` (a measure), `dir` (`desc` for the highest, `asc` for the lowest): keep the `n` values of `field` with the highest measure |

```yaml
filters: [{ field: counterparties.id, op: top, n: 10, by: Exposure (EE peak) }]   # the ten largest counterparties
```

A `top` filter is resolved first: the server ranks the values of `field` in the context of the other filters (slicers included) and keeps the first `n`, so the answer follows the person's choices.
Rank by a column that is not masked (`counterparties.id`, not the masked name): a filter on a masked column is `DRS-9021`.

## 5. Format

`format` is a map of plain settings; a key a visual does not use is ignored.

| Key | For | Meaning |
|---|---|---|
| `labels` | charts | data labels |
| `stacked`, `smooth` | bar, column, area; line, area, combo | stack the series; smooth the line |
| `legend` | charts | `bottom`, `top`, `right`, `none` |
| `axisTitles` | bar, column, line, area, combo | `{ x, y }` |
| `colour` | charts with one series | a `#rrggbb`; the designer offers the theme's colours |
| `numberFormat` | charts, cards, tables | `amount0`, `signed0`, `signed2`, `compact`, `pct`; else the measure's own format from the dataset |
| `conditional` | cards, kpis, gauges, tables, matrices, bar, column | `[{ measure, op: lt, le, gt, ge, eq or ne, value, colour }]` |
| `dataBars` | table, matrix | measures to draw as bars in the cell |
| `subtotals` | matrix | subtotals and totals |
| `min`, `max` | gauge | the dial's range |
| `mode`, `scope` | slicer | section 6 |
| `size` | text | `heading` |

## 6. Slicers

A `slicer` visual lists the values of its field (from the dataset, under the reader's rows and masks) and filters the visuals in its **scope**.

* `format.mode: list` (the default: a checkbox list with a search box), `dropdown` (the shared multi-select) or `range` (two inputs, for a number or a date field). `relative-date` is reserved (`DRS-9033`).
* `format.scope: page` (the default) filters this page; `report` filters every page, so a choice made on one page holds on the others.
* A slicer never filters itself, and other slicers filter it. A visual a slicer cannot reach through the dataset's relationships answers `DRS-9020` and says so.

## 7. How a visual becomes a query

For each visual the server builds one dataset query ([DATASETS.md](DATASETS.md) section 8) from the visual and the state of the page, in this order of authority: the report's filters, the page's, the
visual's, the slicers in scope, the marks selected on **other** visuals of the page, the values carried by a drill-through, the drill-down path. Then it runs it through the engine **as the person looking**: their
row rules, their masks, the layer the planner chooses.

| Part | Rule |
|---|---|
| fields | the `axis`, `legend` (and `rows`, `columns`, `details`) wells in order; a table's `columns` that are not measures |
| measures | the `values`, `secondary`, `target`, `x`, `y`, `size` wells |
| order | a date axis in date order; else the first measure, largest first; a table, matrix, heatmap or slicer has no explicit order (the engine's, which never sorts on a masked column); `sort` overrides |
| rows | `limit`, else the chart, table or slicer default of section 11; a chart says when it was cut |
| dates | the person's business date, else the report's `dates`, else the latest business date |
| selections | a selected mark on another visual filters this one (`in` on the mark's field); with highlight on, a bar or column chart asks twice, the whole answer and the highlighted one; other types are filtered. A visual with `interacts: false`, and a slicer, is not touched |
| drill-down | the first axis field moves down the visual's hierarchy one level per value clicked, and the values clicked become `eq` filters (past the last level: `DRS-9035`) |
| show the rows | the visual's own filters and selection, and every column of the table it reads (`id` and `index` too), up to the "show rows" limit |

A visual whose query is refused shows the server's code and words in its frame, with a Retry button, and the others carry on.

## 8. What people can do with a report

**The viewer** (`/bi/r/<id>` and `/bi/r/<id>/<page>`; `BI <report> <GO>` opens it). Pages are tabs; a toolbar holds the business date, bookmarks, *Click a mark to filter the page or highlight* and Reset;
the filters in force are chips under it.

* **Cross-filter and highlight.** Click a mark (or focus a chart, move with the arrows and press Enter) and the other visuals filter to it (or, for bar and column charts, draw the highlighted share over the
  whole). Click it again, press Esc or use the visual's clear button to let go. Ctrl or Shift adds to the selection.
* **Drill down and up** along the visual's `hierarchy`: select a mark, press the drill-down button (or `d`); `u` goes up. The title shows the path.
* **Drill through.** With a mark selected, a visual offers *Drill through to <page>* for each page whose `drillthrough: fields` lists the mark's field. The page opens with the value carried as a chip, in the
  URL (`?carry=...`, so it can be shared), and a Back button.
* **Show the rows** (`r`): the table of the underlying rows of the visual (masked columns read `•••`); **Show as a table** (`t`): the visual's own numbers as a table. Both are also what a screen reader reads.
* **Open in the terminal.** A cell of a key column (or a selected mark on one) of a table whose kind is a Drishti kind opens `/v/<kind>/<id>`.
* **Bookmarks** (`bookmarks: [{ name, page, slicers: { <slicer id>: [values] }, filters }]`): choose one and the page, slicer choices and filters are applied.
* **Keys.** Tab moves through the visuals in reading order; in a chart the arrows move between marks and Enter selects; `Esc` clears; `t`, `r`, `d`, `u` as above; `?` lists them.
* **A phone** (390 px) shows one column in reading order with 44 px targets and no sideways scroll.

**The designer** (`/bi/design/<draft>`) and the **Model view** (`/bi/m/<dataset>`) are described in [BI_GUIDE.md](BI_GUIDE.md) sections 15 and 16.

## 9. Checking a report: DRS-9030 to DRS-9035

The parser never throws for a bad document: it returns every problem with its code and the path of the key, so a person sees all of them at once (`POST /api/v1/bi/reports/validate`, the designer's
YAML tab and Check button, and the viewer when a report cannot be drawn).

| Code | Meaning | Example |
|---|---|---|
| `DRS-9030` | the structure is wrong: not YAML, an unknown visual type, a bad `at`, a duplicate id, an operator that is not one, an image from another address | `pages[0].visuals[1].at: x is 0 to 11 and w is 1 to 12, and x + w is at most 12 ... this is x=8, w=6` |
| `DRS-9031` | a name the dataset or the report lacks: a field, a measure, a hierarchy, a page, a bookmark, a slicer | `pages[0].visuals[0].wells.axis: table 'trade' has no column 'nope' (it has id, tradeId, desk, ...)` |
| `DRS-9032` | a visual built wrongly: a measure in a field well, a field in a measure well, an unknown well, a required well empty, too many items | `...wells.values: 'trade.mtm' is a field; the values well takes measures (Notional, MTM, ...)` |
| `DRS-9033` | reserved, not built: a visual type, the relative-date slicer | `...type: the visual 'map' is reserved and not built yet` |
| `DRS-9034` | the dataset cannot be found or has errors, so the report cannot be drawn | `no dataset 'x' (a catalogue id, a file name such as trades-basic, or the dataset's name)` |
| `DRS-9035` | a visual query names a page, a visual or a slicer the report lacks, or its state is not valid (a drill path past the last level, dates that are not dates) | `the hierarchy 'Desk and book' has 2 levels; there is nothing below trade.book` |

**Check** (the designer's button, `quality: true` on validate) adds warnings that stop nothing: `A11Y` (a chart, table, card or image with neither title nor alt text; an image without alt text; a chosen
colour with a contrast ratio under 3 against the theme's background), `LAYOUT` (two visuals overlap: the picture and the keyboard order disagree) and `PERF` (a visual whose last preview took over a second).

## 10. Security

* A reader needs the **report** (the catalogue's audience and workspace roles decide); they need not be able to open the dataset's catalogue entry: the report reads its dataset with the grants of the
  people who published it (a published or shipped report), and the engine applies **the reader's own row rules and masks**. A reader is not told the dataset's row rules through the report. A **draft** (and the designer) finds only the
  datasets its author may read in the catalogue, so a report cannot be used to reach a dataset the catalogue hides from its author.
* A row rule is a predicate on the dataset's tables, applied to every query. If a visual reads a table the rule's tables cannot reach many-to-one, that visual answers `DRS-9020`, naming the rule: in the
  sample *Counterparty risk* a desk head's exposure visuals say so, and an analyst sees the counterparty name as `•••` (one bar, since every name is the same mask).
* The report's queries go through the same limits as any query (`drishti.bi.query.*`): three at a time per person (the page asks three at a time and retries a refusal for busy), time, rows and bytes.
* Nothing in a report is executed: it is parsed, validated, turned into parameterised dataset queries (every value is bound) and drawn. No script, style or address from a report reaches the page.
* The designer and draft queries need `bi-author`; viewing needs `bi`. A draft in a personal workspace is private to its owner.

## 11. Settings

`drishti.bi.report.*` ([CONFIGURATION.md](../admin/CONFIGURATION.md#drishtibireport--reports-phase-3)): `chart-rows` (500), `table-rows` (1000), `slicer-values` (1000), `show-rows` (1000),
`top-candidates` (10000), `perspective-rows` (20000: the most rows a Perspective visual is sent). The query limits are `drishti.bi.query.*`; the live ones `drishti.bi.live.*`; the console's `bi.live.min_interval_ms` and `bi.live.stale_seconds` (section 13).

## 12. Built and not built

Built: the report model and its parser, validator and writer (DRS-9030 to 9035); the visual catalogue above; filters at three levels with top-N; slicers (list, dropdown, range); cross-filter, highlight (bar and
column), drill-down, drill-through, show the rows, show as a table, open in the terminal; bookmarks; palettes; the viewer, the designer and the Model view; the three stored sample reports; the API.

Built in phase 5 (section 13): visuals over live tables redraw in place with a live indicator, pause and resume, stale and disconnected states; the rolling chart; the Perspective grid and pivot; the sample live reports.

Not built, and not faked: Perspective **charts** (the d3fc plugin) and the Perspective settings panel (the viewer's configuration is the YAML); a Perspective visual over **several tables**; a `perspective-*` visual in the designer's canvas (it shows a note: the designer's page has no WebAssembly); `map`, `bubble`, `funnel`, `histogram`, `box`, `python`, `entity-view` and `shape` visuals; the relative-date slicer; true totals for
measures that do not add up; a time-intelligence or window measure (the dataset refuses them); highlight for charts other than bar and column; capturing a bookmark's slicer choices from the page (they are
written in the YAML); different layouts per device (a phone stacks the grid); a *Check* that runs expected values on sample data; export, subscriptions, alerts and embedding (phase 7); Explore to visual (phase 9);
report-level security beyond the catalogue and the dataset's rules.

## 13. Live visuals and Perspective (phase 5)

### Live visuals

Nothing in a report says "live". A report whose dataset has a **live table** ([DATASETS.md](DATASETS.md#71-live-tables-phase-5)) is live: the viewer opens **one stream** for the report (`GET /api/v1/bi/reports/{id}/live`, over the console's one channel per browser,
[LIVE.md](../architecture/LIVE.md#live-bi-a-reports-live-tables)) and shows a **live indicator** above the pages.

![The Live trading desk: cards, a bar, a rolling line and two tables, redrawn as the trades tick](img/bi-live/live-desk.png)

| Part | What it says |
|---|---|
| the dot and word | **Live** (green), **Paused**, **Stale** (amber: nothing heard from the server for `bi.live.stale_seconds`, 30 s, or the source has pushed nothing for `drishti.bi.live.stale-after`), **Feed failing** (red: the table's first read failed), **Disconnected** (red: the stream dropped; the browser retries by itself), **Paused (tab in background)** (the tab was hidden for ten seconds and gave its stream back) |
| *Last update 07:49:57* | the time of the live table's last change (the browser's clock zone), not of the last redraw |
| **Pause** / **Resume** | stops redrawing (the stream stays open); on resume one redraw catches up. 44 px on a phone |

![The indicator, live](img/bi-live/live-indicator.png) ![Paused](img/bi-live/live-paused.png) ![Disconnected: the console was stopped](img/bi-live/live-disconnected.png)

**How an update reaches a visual.** The stream says only that the tables **changed** (a `tick`, at most one per `drishti.bi.live.frame`, 250 ms, the latest state: a slow browser skips ticks, never queues them) and how they are doing (a `status` every
`drishti.bi.live.status`, 5 s, which is also its heartbeat). It carries **no data**. A tick makes the page ask its visuals again, **as the person looking**, so their row rules and masks apply to every update. The page:

* **coalesces**: a redraw round runs at most every `bi.live.min_interval_ms` (250 ms) and never two at once; a burst of ticks is one round, and a slow server is not queued behind (back-pressure is "the next round starts when the last one is done");
* **asks three queries at a time** (the per-person limit) for the visuals of the current page, and **quietly**: a visual is not greyed, its picture is drawn over in place (a chart keeps its instance and its keyboard cursor; a table keeps its scroll position; a card flashes when its number changes, unless the person asks for reduced motion);
* keeps the old picture when an update fails, with a dashed border and "old: <reason>" in the line under it, and draws it again on the next good answer;
* redraws everything when a stream (re)connects (`hello` carries the generation: a page that has the latest does not ask again).

A real stream, first events (abridged; the table's counters are in `tables`):

```text
event:hello
data:{"generation":95,"lastUpdate":"2026-10-11T11:50:03.684098666Z","state":"live","at":"2026-10-11T11:50:04.732755792Z","tables":[{"table":"trade","kind":"trade","state":"live","rows":750,"entities":750,"subscribed":750,"pushes":true,"seq":33503,"epoch":0,"generation":95,...}]}

event:tick
data:{"generation":96,"lastUpdate":"2026-10-11T11:50:04.545007543Z","state":"live","at":"2026-10-11T11:50:05.484058137Z","tables":[{"table":"trade",...,"seq":33863,"epoch":0,"generation":96,...}]}
```

Every JSON answer of `POST /api/v1/bi/reports/{id}/query` over a live dataset also carries `live`: `{generation, lastUpdate, state, at, tables: [...]}` (the counters are read **before** the query, so a client that asks "changed since `seq`" misses nothing).
A real card answer: `"meta": {"layer": "live", "rows": 1, "cached": false, ...}`, `"live": {"generation": 99, "state": "live", ...}`. The result cache is keyed by the live generation, so many readers of one state share an answer.

**Cards, KPIs, bars, columns, lines, tables, matrices and the rest** update this way; slicers do not (they keep their open state; a chosen value still filters every update). **A rolling line** (`live-chart`) adds one point per update of its measures
and keeps the latest `format.window` points (default 120). The points live **in the browser, from the moment the page opened**: the server holds no history of an aggregate (a live table is current state), and a reload starts the line again.

```yaml
- { id: rolling, type: live-chart, title: Net MTM and losses, rolling, at: { x: 6, y: 2, w: 6, h: 5 }, wells: { values: [MTM, Losses] }, format: { window: 120 } }
```

### Perspective

`perspective-grid` and `perspective-pivot` show the **rows of one table** in [Perspective](https://perspective.finos.org/) (FINOS, Apache-2.0, vendored: `drishti-console/web/static/vendor/perspective/`, no CDN). Perspective runs in the browser as WebAssembly with its engine in a
worker of this origin; it does the sorting, scrolling and **pivoting** itself, on the rows the person may see.

```yaml
- { id: all-trades, type: perspective-grid, title: Trades, at: { x: 0, y: 0, w: 12, h: 12 },
    wells: { columns: [trade.tradeId, trade.desk, trade.book, trade.currency, trade.trader, trade.notional, trade.mtm] } }
- { id: desk-currency, type: perspective-pivot, title: MTM by desk and currency, at: { x: 0, y: 0, w: 12, h: 12 },
    wells: { rows: [trade.desk], columns: [trade.currency], values: [trade.mtm, trade.notional] },
    format: { aggregates: { trade.mtm: sum, trade.notional: sum } } }
```

![A Perspective grid of 750 live trades](img/bi-live/perspective-grid.png)

![A Perspective pivot: desks down, currencies across, MTM and notional summed, re-pivoted as the trades tick](img/bi-live/perspective-pivot.png)

* **The first read is Arrow.** `POST /api/v1/bi/reports/{id}/query?format=arrow` (the console passes the bytes through unchanged) answers the visual's rows as an Apache Arrow IPC stream, **masked for the reader**: a masked column holds `•••`, never the value. The columns are named as the table names them (`trader`, not `trade.trader`), plus the table's `id` (the key Perspective updates rows by) and, for a live table, `updateSeq`. The headers carry `X-Bi-Rows`, `X-Bi-Masked` and `X-Bi-Live` (the table's counters as JSON).
* **Then only what changed.** On a tick the page asks the same visual with `state: { since: <seq> }` (JSON): the rows whose `updateSeq` is above the one the last answer ended at, applied to the Perspective table **by key**. When rows have *left* the table (evicted, deleted: its `epoch` changed) the whole set is read again and replaces the table. The line under the visual says the row count and "live".
* **Masks per reader.** The same visual for a reader without `raw`:

![The grid for a reader without raw: the trader column reads the mask, before and after every update](img/bi-live/perspective-grid-masked.png)

```text
columns: tradeId, desk, book, productType, currency, direction, trader, notional, mtm, pnl1d, id, updateSeq
rows:    ["BBG-60000001","DESK-FI","BOOK-FI-3","GOVT_BOND","CHF","Short","•••",198000000.0,-978378.0,-23374.0,"BBG-60000001",1]      masked: ["trader"]
```

* **Only a page that holds one is given WebAssembly.** The console gives a report or board page that holds a Perspective visual, in its own response, the strict policy plus `script-src 'wasm-unsafe-eval'` and the SHA-256 of the four `<style>` texts Perspective writes
  (`style-src 'self' 'sha256-...'`): never `'unsafe-eval'`, never `'unsafe-inline'`, no blob worker (the engine's worker is a script of this origin, `worker-src 'self'`). Every other BI page, the designer included, keeps the strict policy with no WebAssembly. The list of hashes is `STYLE_HASHES` in
  `drishti-console/core/perspective_csp.py`; `python tools/perspective-style-hashes.py --console ... --check` measures the vendored version's styles on the real pages and a Chromium test fails, naming the hash, when a library update writes another. Details and measurements: [RUPAKA_POC.md](../architecture/RUPAKA_POC.md#12-the-perspective-retest-phase-5).
* **If Perspective cannot start** in a tab (WebAssembly blocked or failing), the visual shows the first 500 rows in Drishti's own table with a line that says so, and keeps following the live table the same way.
* **Checks.** All the fields of a `perspective-*` visual must be columns **of one table** and not date parts; `format.aggregates` may name only columns of `values` and one of `sum avg count "distinct count" min max first last median dominant unique`; a pivot's `values` must hold a column (`DRS-9031`, `DRS-9032`). Without `title` or `alt` Check warns (`A11Y`).
* **Not built:** Perspective charts (d3fc) and the settings panel; the viewer is configured by the YAML only. Rows are limited by `drishti.bi.report.perspective-rows` (20,000).
