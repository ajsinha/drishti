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
# Datasets: the YAML, the expression language and the security rules

Reference for the **dataset** of Drishti Rūpaka (BI), as built in phase 2 (live tables, section 7.1, are phase 5). For the walk-through see [BI_TUTORIAL.md](BI_TUTORIAL.md) (chapters 1 to 9, and 18 for a live table); for the pages that show a dataset see [BI_GUIDE.md](BI_GUIDE.md) sections 13 and 16; for the
design see [RUPAKA.md](../architecture/RUPAKA.md) sections 6 and 7 and "20.2 Phase 2, as built"; for the HTTP API see
[API_GUIDE.md](API_GUIDE.md#datasets-and-the-query-engine-apiv1bi-phase-2); for the settings see
[CONFIGURATION.md](../admin/CONFIGURATION.md#drishtibiquery--the-dataset-query-engine-phase-2).

A dataset is a YAML document: the **model** reports sit on. Tables come from pack kinds through connectors (by their logical names), relationships
join them, measures compute over them, row rules and masks decide who sees what. It is **stored** as a BI item of kind `dataset` (a draft in a
workspace, published with review like any item) or **shipped** as `config/bi/datasets/<id>.dataset.yaml` (read-only, watched live). Nothing in it
is executed: it is parsed, validated, compiled to SQL with **bound values**, and run by the embedded DuckDB.

Contents: [1](#1-the-file) the file · [2](#2-tables-and-columns) tables and columns · [3](#3-relationships-and-how-queries-join) relationships ·
[4](#4-measures-and-the-expression-language) measures and expressions · [5](#5-hierarchies) hierarchies · [6](#6-security-row-rules-and-masks) security ·
[7](#7-modes-layers-and-rollups) modes, layers and rollups · [8](#8-the-query) the query · [9](#9-problems-and-drs-codes) problems and codes ·
[10](#10-what-is-not-built) what is not built (live tables: [7.1](#71-live-tables-phase-5))

## 1. The file

```yaml
rachana: 1                  # language version (1)
dataset: trading-risk       # the model's name: letters, digits, . _ - (up to 64)
version: 1                  # your own version number
title: ...                  # catalogue fields (BI_GUIDE.md): title, description, owner, tags, endorsement, audience
description: ...
sample: true                # sample content: hidden from business users when sample packs are (drishti.packs.samples)
engine: duckdb              # the only engine built
tables: { ... }             # section 2
relationships: [ ... ]      # section 3
measures: { ... }           # section 4
hierarchies: { ... }        # section 5
security: { rows: [...], masked: [...] }   # section 6
rollups: [ ... ]            # section 7
layout: { ... }             # where the Model view draws each table (written by the diagram; the language ignores it)
```

Unknown top-level keys are a warning. Five complete, commented examples ship in `config/bi/datasets/`: `trades-basic` (one table), `trading-risk` (five tables across three packs, a row rule, masks, a rollup) and `trading-cashflows` (nested arrays as tables), which the tutorial reads in chapters 2 to 5, and the live `live-trading` and `live-exposure` (section 7.1, tutorial chapter 18).

## 2. Tables and columns

```yaml
tables:
  trades:                                   # the table's name: letters, digits, underscore (it is written in expressions)
    source:
      connector: trading-store              # a connector by its LOGICAL name (config/connectors/<name>.yaml); empty: whichever serves the kind
      kind: trade                           # the pack kind
      each: legs                            # optional: one row per element of this array in each document (below)
    mode: cached                            # cached (default) | direct | live  (section 7; live: 7.1)
    refresh:
      on-load: true                         # rebuild the date when a data-load signal for the kind arrives (default true)
      schedule: "0 */15 * * *"              # cron, five fields: stored and checked, NOT scheduled yet (section 10)
    columns:
      tradeId:   { type: string, key: true }
      desk:      string                     # shorthand for { type: string } with the path equal to the name
      cpty:      { path: counterparty.id, type: string }    # a path into the document; "$." in front is allowed and dropped
      notional:  { type: decimal, format: amount0 }
      mtmK:      { type: decimal, expr: "mtm / 1000" }       # calculated column (below)
```

* **Types:** `string`, `integer`, `decimal`, `boolean`, `date`, `timestamp`. A value that cannot be cast to its type is null (never an error).
* **Every table also has** `id` (the entity's id) and `businessDate` (the date the row is for); do not declare them. A table with `each` also has
  `index` (the element's position from 0), and its `id` is the **parent** entity's id, which is how it joins to the parent. A **live** table also has `updateSeq` and `updatedAt`
  (section 7.1); the five names are reserved (`DRS-9010`).
* **`each`:** the trade's `legs` or `schedule` array becomes a table with one row per element; column paths are relative to the element. It is always built by
  reading the documents (an array cannot come from promoted columns) once per business date.
* **Calculated column:** a row-level expression over the table's own stored columns (section 4); it may not aggregate, use measures or the user, or use another
  calculated column. It is computed at query time.
* **`format`** is a format name of the pack, used for display only. **`key`** marks a column others join to (informational; relationships name columns explicitly).
* A table may have no columns (`columns: {}`): it then has `id` and `businessDate` only, which is enough for a lookup joined on id.

**Reading the data** goes through the same connectors as the rest of Drishti ([CONNECTOR_DEVELOPER_GUIDE.md](../connectors/CONNECTOR_DEVELOPER_GUIDE.md#bulk-reads-for-bi-scan-and-sqltarget)):
if the connector can **scan** every declared path as a column (the paths it promotes in `layout.<kind>.columns`) the table is built from column batches and no
document is parsed; if it cannot, the engine reads documents one by one (at most `drishti.bi.query.record-read-max`) and the answer says so in its warnings. *Promote
the columns a dataset uses* in the connector's layout: the tables of the samples that read documents are the reference kinds of the demo lake, which promote none.

## 3. Relationships and how queries join

```yaml
relationships:
  - { from: trades.desk, to: desks.id, cardinality: many-to-one }      # from = the MANY side, to = the ONE side
  - { from: trades.nettingSet, to: netting_sets.id, cardinality: many-to-one, direction: single, active: true }
```

`cardinality` is `many-to-one` (default), `one-to-many`, `one-to-one` or `many-to-many`; `direction` `single` or `both`; `active` true or false. Both columns must exist and have
the same type. **Built:** a query joins along **active** relationships, in the many-to-one direction (and either way for one-to-one), `LEFT JOIN`ed from the table the
measures aggregate (the *base*), so a measure is never counted twice. A query with **no measure** (a list of values) may also join one-to-many and answers distinct rows.
**Refused (`DRS-9020`):** measures that aggregate two tables no many-to-one path connects, and a field whose table cannot be reached from the measures' table without repeating rows.
`direction: both` and many-to-many are stored; a query still joins as written (many-to-many warns). Inactive relationships are skipped and cannot yet be
turned on by a measure.

## 4. Measures and the expression language

```yaml
measures:
  Notional:   { expr: "sum(trades.notional)", format: amount0, description: "..." }
  MTM:        "sum(trades.mtm)"                                   # shorthand: just the expression
  MTM per 1m: { expr: "ratio(sum(trades.mtm), sum(trades.notional)) * 1000000" }
  Share:      { expr: "[MTM] / [Notional]" }                      # [Name] uses another measure
  Open MTM:   { expr: "sum(trades.mtm, trades.status == 'Live')" } # a condition inside the aggregate
  Attrib:     { python: risk.measures.pnl_attribution, inputs: [trades.mtm] }   # stored, NOT run (section 10)
```

The expression language is **Rachana-EL's syntax** (operators, literals, `?:`, text in `'...'` or `"..."`) **extended with aggregation**. It is closed and side-effect free;
it compiles to SQL, it is never evaluated as code. Names: `table.column`, `table.column.part` (a date part), `[Measure name]`, `user.<attribute>` (row rules only),
and in a calculated column a bare column of its own table.

| Aggregates | Meaning |
|---|---|
| `sum(x)`, `avg(x)`, `min(x)`, `max(x)` | over the rows of the group; null rows are ignored (SQL) |
| `count(table)` or `count(x)` | rows, or non-null values of `x` |
| `distinct(x)` | number of different values |
| `ratio(a, b)` | `a / b` as a double, null when `b` is 0; `a` and `b` are aggregate expressions |
| `percentile(x, p)` | the `p`-quantile (0 to 1, a literal) with interpolation |
| second argument | `sum(x, condition)`: only rows where the condition is true (`FILTER (WHERE ...)`) |

| Row functions and operators | |
|---|---|
| `+ - * / %`, comparisons `== = != < <= > >=`, `&& \|\| !` and `and or not`, `x in (a, b)`, `x not in (...)`, `c ? a : b` | `/` is always a floating-point division and null on a zero divisor |
| `abs`, `round(x[, n])`, `coalesce(a, b, ...)`, `upper`, `lower`, `length`, `contains(text, part)`, `startsWith`, `if(c, a, b)` | |
| `year(d)`, `quarter`, `month`, `day`, and the field form `table.dateColumn.year` (also `quarter`, `month`, `day`, `week`) | date parts of a `date` or `timestamp` column |

A measure must aggregate (a column outside an aggregate is an error), aggregates do not nest (except `ratio`'s two arguments), and measures may not refer to each other in a circle.
**Reserved, parsed and refused with `DRS-9013`:** `running`, `lag`, `rank`, `ytd`, `mtd`, `qtd`, `same_period_last_year` (window and time-intelligence functions).

Real compiled output (captured from the `trades-basic` sample by a BI author's `?explain=true`; the values are placeholders, never in the text):

```sql
SELECT "trade"."desk" AS "trade.desk", (sum("trade"."notional")) AS "Notional", (sum("trade"."mtm")) AS "MTM", (count(*)) AS "Trades",
       ((CAST((sum("trade"."mtm")) AS DOUBLE) / NULLIF((count(*)), 0))) AS "Average MTM per trade"
FROM (SELECT t.*, (CAST(t."mtm" AS DOUBLE) / NULLIF(?, 0)) AS "mtmInMillions"
      FROM (SELECT * FROM read_parquet(['.../columns/file_dataset_trades-basic/trade/date=2026-09-30/part.parquet'], union_by_name = true)) AS t) AS "trade"
GROUP BY "trade"."desk" ORDER BY "trade.desk" ASC LIMIT 100001
```

## 5. Hierarchies

```yaml
hierarchies:
  Trade date: [trades.tradeDate.year, trades.tradeDate.quarter, trades.tradeDate.month, trades.tradeDate]
  Desk and book: [desks.name, books.name]
```

An ordered list of levels, coarse to fine; each is `table.column` or `table.column.part` (a bare column name is accepted when it is in one table only). They are validated and
returned with the model for the designer and the viewer (phase 3) to drill with: a visual's `hierarchy:` drills down them ([REPORTS.md](REPORTS.md)); a query names the levels as ordinary fields.

## 6. Security: row rules and masks

```yaml
security:
  rows:
    - { role: desk-head, where: "trades.desk in user.desks" }
    - { role: risk,      where: "true" }
  masked: [counterpartyName, counterparties.name]
```

**Row rules** are evaluated **on the server, for each reader**, and added to every query as a predicate the query cannot remove.

* A reader gets the rows allowed by **any** rule whose `role` they hold (rules of one reader are OR-ed). A reader with **no matching rule gets no rows** and the answer
  says why. With server security off (`drishti.security.enabled: false`) rules are not applied.
* `where` is a row condition: no aggregates, no measures. It may use `user.name`, `user.roles`, `user.desk`, `user.desks` (the profile's desk split at `,` and `;`, so a head
  of two desks lists both) and `user.email`; used with `in`, a list attribute binds one parameter per value.
* **Fail closed.** A rule names tables, and the tables of the reader's rules are joined into **every** query they run. If the measures' table cannot reach a rule's
  table many-to-one, the question is refused (`DRS-9020`, the message names the rule's tables) rather than answered with other readers' data. In `trading-risk` a desk-head
  therefore cannot ask for exposure by counterparty: the netting sets do not reach trades.
* A table over a kind the reader may not open (their roles, or a pack not assigned to them) returns **no rows** for them, with a warning; a `direct` table is refused.

**Masked columns.** A column is masked for a reader when it is listed in `masked` (a bare name matches that column in any table, `table.column` names one) and the reader
**lacks the `raw` power**, or when Drishti's own list `drishti.security.redact` names the column's name or the last segment of its path (`counterpartyId`, `trader`, ...). Masks
are applied **before aggregation, column by column**, and the result is built so the mask cannot be probed:

| What | Result for a masked reader |
|---|---|
| a masked field (dimension) | the constant `•••` in every row, and it is **not grouped on**: its values cannot be told apart |
| a measure aggregating a masked column | `null` |
| a filter on a masked column, or a sort on it | refused, `403 DRS-9021` |
| a masked column used in a row rule | fine: rules run on the server before the mask |

Real output (the sample `trading-risk`, the same query, three readers; `Counterparties` is `distinct(trades.counterpartyId)`, a masked column; `rita` has `risk`, `hana` is a
desk-head whose profile desk is `DESK-FX`, `ann` is an `analyst` without `raw`):

```
rita  (risk)       desks.name = 'G10 and EM FX' ... 10 rows   MTM -15,759,419  Trades 84  Counterparties 17
hana  (desk-head)  desks.name = 'G10 and EM FX' ... 1 row    MTM -15,759,419  Trades 84  Counterparties 17
ann   (analyst)    10 rows, every MTM and Trades as rita sees them,  Counterparties = null  (masked: Counterparties)
ann asks for counterparties.name:   one row  '•••'  MTM 138,562,638  Trades 750     (not grouped: the 18 names cannot be told apart)
ann filters counterparties.name:    403 DRS-9021 'counterparties.name' is masked for you: it cannot be filtered on
```

## 7. Modes, layers and rollups

| `mode` | The table is | Built |
|---|---|---|
| `cached` (default) | typed Parquet, one file per **business date** in the BI store (`drishti.bi.query.store-dir`): the **columns layer** | read once per date from the connector, on the first query, and again when a data-load signal for the kind names the date |
| `direct` | the source's own SQL table; the compiled query runs in the source | needs a connector with a SQL target (the DuckDB and PostgreSQL connectors); every table of the query must be direct and in one store; `each` tables cannot be direct |
| `live` | the kind's current state, **kept in memory by the engine and updated by key as the source pushes changes**, with a retention window (section 7.1) | built in phase 5. With `drishti.bi.live.enabled: false` it falls back to a short-lived snapshot of the connector's state, kept for `drishti.bi.query.live-ttl` |

**The layers a query is answered from, smallest first:** a **rollup** that covers it, else the **columns layer**, else (direct tables) the **source**. The answer reports which
(`layer`, and `rollup`). Layout in the BI store: `columns/<dataset>/<table>/date=<yyyy-mm-dd>/part.parquet` with a `meta.json` (the table's signature, rows, how it was read, warnings),
`rollups/<dataset>/<name>/date=.../part.parquet`. A change of a table's connector, kind, array or columns changes its signature, and the layer is read again.

```yaml
rollups:
  - { name: by_desk_currency, table: trades, by: [desk, currency, assetClass], measures: [MTM, Notional, "P&L 1d", Trades, MTM per 1m notional] }
```

A rollup stores, per date and group, the **components** of its measures: sums, counts, minimums, maximums of expressions over stored columns of its table (`avg(x)` is stored as
`sum(x)` and `count(x)`); a measure built from them (a ratio of sums, a measure of measures) is answered by re-aggregating the components. A query is routed to a rollup when it
uses **one table**, only fields (and filters, and row-rule columns) among the rollup's `by` columns (and `businessDate`), and only measures whose components the rollup holds. Not
rollup material: `distinct`, `percentile`, a condition inside an aggregate, another table, a filter on a measure; validation says so (`DRS-9012`) when a rollup lists such a measure.
`"layer": "columns"` in a query forces the columns layer (the check that both give the same numbers is in the tests).

**When a layer is rebuilt:** a data-load signal (`POST /api/v1/packs/{pack}/loads`, [DATA_LOADS.md](DATA_LOADS.md)) with `status: ready` for a kind deletes that date's partitions of
every published dataset table that reads the kind, bumps the dataset's data generation (so cached results can no longer be served), and, with `eager-rebuild` on, reads the date again in the
background; **only that date, only those datasets**. Without a date in the query the **latest** date is used: the current business date when its layer exists, else the newest date the source
holds (the answer's `dates` says which).

### 7.1 Live tables (phase 5)

A `mode: live` table is the **current state of a kind**, not a copy per business date. The engine reads the kind **once** when the table is first needed (the first query, or the first stream of a report over it), subscribes to
each entity through the same live machinery the terminal's live views use, and then **applies every change to its in-memory table by key**: a new id is inserted, a known id replaces its row, a deletion removes it. Reports and
boards over it redraw as it changes ([REPORTS.md](REPORTS.md#13-live-visuals-and-perspective-phase-5), [BOARDS.md](BOARDS.md)). The sample is [config/bi/datasets/live-trading.dataset.yaml](../../config/bi/datasets/live-trading.dataset.yaml):

```yaml
tables:
  trade:
    source:
      kind: trade               # no connector named: whichever running connector serves trades and can push them
    mode: live
    live:                       # retention: the most rows it holds (and, optionally, `age:` how long a row may go without changing, below)
      rows: 5000
    columns:
      tradeId: { type: string, key: true }
      desk:    string
      mtm:     { type: decimal, format: signed0 }
```

**Where the changes come from.** The connector's `subscribe` for each entity: the demo source ticks its live samples; the Kafka and Redis connectors (and any connector that implements `subscribe`) feed the table the way they feed a live view. `connector:` names
a connector by its logical name when a stream that keeps no state ticks what a store serves (a Kafka connector in `ticks` mode: the entities are listed and read from the store, the changes come from Kafka); empty means the router's choice, as for a live view.
The table follows at most `drishti.bi.live.follow-max` (2,000) entities and says so when it stops there. A connector that **cannot push** is read again every `drishti.bi.live.resync` (30 s): the table still follows it, a row whose values did not change is skipped,
and the table's state says `pushes: false`.

**Incremental, and what that means.** The *table* is maintained incrementally: a batch of changes (merged per entity, every `drishti.bi.live.batch`, 100 ms) is written in **one transaction** with the retention, so a query never sees half a batch, and a row whose declared
columns did not change does not move the table's counters (a source that republishes the same values costs nothing downstream). The *measures* are **recomputed** by the engine over the table on each query, as every measure is: with the table in memory that is a few
milliseconds at 10,000 rows. The engine does **not** keep running totals by group that a change adjusts; that is not built (heavy continuous aggregation belongs in a producer, [LIVE_BI.md](../architecture/LIVE_BI.md)).

**Retention.** `live: { rows, age }` (both optional). `rows` bounds the table: past it the rows changed longest ago go first (`drishti.bi.live.max-rows`, 50,000, is the ceiling whatever a dataset asks). `age` drops rows that have not changed for that long (`90s`, `15m`, `2h`,
`1d` or ISO `PT15M`); it is applied with every batch and on every resync, so a quiet table forgets too. Whatever leaves (evicted, or deleted by the source) starts a new **epoch** of the table; a client that holds rows (a Perspective grid) reads them again.

**Two columns every live table has**, queryable like any other and never declared:

| Column | Type | Meaning |
|---|---|---|
| `updateSeq` | integer | a counter that grows with each row that changes: "what changed since *n*" is `updateSeq > n` |
| `updatedAt` | timestamp | when the row last changed (UTC) |

**Row rules and masks apply on every update.** The table holds the source's rows and knows no reader. Every query, whenever it is asked, is compiled with the asker's row rules as predicates it cannot remove and their masks as `•••` (or null for a measure);
the stream that tells a browser the table changed carries **no data**. Two readers of one table, one with a desk rule and one without `raw`, are each answered as themselves after every update (`LiveTablesTest`, `BiLiveApiTest`).

**How a live table is doing.** `GET /api/v1/bi/datasets/{id}/layers` and the dataset page's Layers table show, per live table, `state` (`starting`, `live`, `stale`, `failed`, `closed`), `rows`, entities followed and how many push, and the time of the last change; the engine page
(`GET /api/v1/bi/engine`, bi-admin) counts live tables and rows. A real answer, a minute after the first query, with the demo source (`tick-ms: 200` here; its default is 400) changing 360 of the 750 trades in every batch:

```json
{"table": "trade", "kind": "trade", "state": "live", "rows": 750, "entities": 750, "subscribed": 750, "pushes": true, "seq": 38239, "epoch": 0, "generation": 109, "lastUpdate": "2026-10-11T13:17:39.331718015Z", "lastSeen": "2026-10-11T13:17:39.737547667Z", "startedAt": "2026-10-11T13:16:41.653443716Z", "applied": 38239, "skipped": 8, "evicted": 0, "deleted": 0, "error": null}
```

![The dataset page's Layers table for a live table](img/bi-live/dataset-live-layers.png)

`stale` means the feed pushes but has delivered nothing for `drishti.bi.live.stale-after` (30 s); `failed` means the first read failed and there are no rows (the queries answer a warning with the reason, or `DRS-9026` when no running connector serves the kind).

**A table nobody uses is closed.** A live table that has not been queried or streamed for `drishti.bi.live.idle-close` (10 minutes) is closed and its subscriptions are given back; the next use starts it again. A dataset edited so that a live table's definition changed
gets a new table.

**What cannot be live:** a table over an array (`each`): it follows whole entities (`DRS-9050`). A live table cannot be a `direct` table's neighbour in one query (the same rule as for cached tables).

**Limits and settings:** `drishti.bi.live.*` in [CONFIGURATION.md](../admin/CONFIGURATION.md#drishtibilive--streaming-bi-phase-5).

## 8. The query

`POST /api/v1/bi/datasets/{id}/query` with the body of [API_GUIDE.md](API_GUIDE.md#datasets-and-the-query-engine-apiv1bi-phase-2): `fields`, `measures`, `filters` (`eq ne in not-in lt le gt ge between contains
starts-with is-null not-null`; a filter whose field is a measure name filters the aggregate), `dates` (`from`, `to`; one date or a range, at most 400 days, at most 31 not-yet-built dates read per query),
`orderBy`, `limit`, `layer` (`auto`, `columns`, `rollup`). Every name is checked against the model and quoted; **every value is a bound parameter**; a value of the wrong type for its column is refused
(`DRS-9020`), never cast into SQL. Limits and their codes are in [CONFIGURATION.md](../admin/CONFIGURATION.md#drishtibiquery--the-dataset-query-engine-phase-2).

## 9. Problems and DRS codes

Validation never throws: it returns every problem with its code and the path of the key (`POST /api/v1/bi/datasets/validate`, the dataset page, and the check on publish, which refuses an invalid dataset).

| Code | Meaning |
|---|---|
| `DRS-9010` | the YAML or a structure is not valid (unknown type, mode or cardinality, a bad name, a missing `source.kind`, bad cron) |
| `DRS-9011` | an expression does not parse, names something the model lacks, misuses an aggregate, uses an unknown function, refers to measures in a circle |
| `DRS-9012` | a relationship, hierarchy, masked column, row rule or rollup points at a table or column that does not exist (or a rollup holds a measure it cannot) |
| `DRS-9013` | a reserved feature (time intelligence, window functions, Python measures): parsed, **not built** |
| `DRS-9020` | a query names a field, measure, operator, value or date the dataset cannot take, or cannot be joined |
| `DRS-9021` | a query filters on, or sorts by, a column masked for the caller, or reads a `direct` table of a kind they may not open |
| `DRS-9022` / `9023` | the answer is over the row limit / the byte limit |
| `DRS-9024` | the query ran past its time limit and was cancelled |
| `DRS-9025` | too many queries at once (server or user) |
| `DRS-9026` | a table's connector is not running, or is not a SQL store for a `direct` table |
| `DRS-9027` | the engine failed on the compiled query (the message says how) |
| `DRS-9050` | a live table cannot be kept: it is over an array (`each`) or its `live:` block is wrong (a query over a live table whose kind no running connector serves answers `DRS-9026`) |
| `DRS-9051` | too many live streams are open for the server or the person (`drishti.bi.live.max-streams`, `max-streams-per-user`), HTTP 429 |

## 10. What is not built

Marked so nothing is mistaken for working: **time intelligence** (`ytd`, `mtd`, `same_period_last_year`) and **window functions** (`running`, `lag`, `rank`); **Python measures** (stored, not run, phase 6);
the **cron `refresh.schedule`** (stored and validated; refresh is by data-load signal and on demand, `POST /datasets/{id}/refresh`); **queries whose measures aggregate several unrelated fact tables**;
**filter direction `both`** and **many-to-many** semantics, and **inactive relationships used by a measure**; **running totals by group that a change adjusts** (live tables are maintained incrementally, measures are recomputed over them: 7.1), **history of a live table** (it holds current state with retention, not every update), **live tables over arrays** (`each`), **live tables in `direct` queries**;
the **BI store as a configured connector** (a folder today; S3 by mounting it); **other engines** than DuckDB (the interface exists); **Delta native deletes/updates** are honoured only as far as the Delta connector
honours them (the engine reads through the connector, not the files); **classification tags** and **per-entry export rules**. (Lineage and impact analysis are phase 4: [BI_GOVERNANCE.md](BI_GOVERNANCE.md).) Reports, the designer and the Model view (a dataset's tables and relationships as a diagram, with the `layout:` key) are built in phase 3: [REPORTS.md](REPORTS.md), [BI_GUIDE.md](BI_GUIDE.md) sections 14 to 16.
